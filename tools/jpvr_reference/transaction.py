"""Pure in-memory JPVR v1 transaction assembly and snapshot validation.

This module assembles already encoded append pages into one deterministic
transaction plan. It performs no file I/O, allocation locking, flush, publish,
or crash recovery and is not a durable storage writer.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from .codec import (
    JPVRFormatError,
    PageType,
    Superblock,
    decode_page,
    encode_superblock,
)
from .commit import DecodedCommitPage, decode_commit_page, validate_commit_snapshot
from .layout import AppendPlan, plan_append, validate_append_plan
from .manifest import DecodedManifestPage, decode_manifest_page


@dataclass(frozen=True)
class TransactionAssemblyPlan:
    """Immutable ordered page bytes and the inactive-superblock candidate."""

    append_plan: AppendPlan
    transaction_id: bytes
    commit_id: bytes
    manifest_page_no: int
    ordered_page_numbers: tuple[int, ...]
    ordered_page_bytes: tuple[bytes, ...]

    @property
    def new_superblock(self) -> Superblock:
        return self.append_plan.new_superblock

    @property
    def page_count(self) -> int:
        return len(self.ordered_page_bytes)


def assemble_transaction(
    current_superblock: Superblock,
    staged_page_bytes: Sequence[bytes],
    commit_page_bytes: bytes,
    page_catalog: Mapping[int, bytes],
    *,
    previous_commit_page_bytes: bytes | None = None,
) -> TransactionAssemblyPlan:
    """Validate and assemble one complete append, with COMMIT as its final page.

    staged_page_bytes must contain every new page before COMMIT, in physical
    append order, with MANIFEST as its final entry. page_catalog contains
    already committed pages needed to resolve old roots and the previous
    COMMIT. Inputs are copied/read only; no caller-owned mapping is mutated.
    """
    encode_superblock(current_superblock)
    if not isinstance(staged_page_bytes, (tuple, list)) or not staged_page_bytes:
        raise JPVRFormatError("staged_page_bytes must be a non-empty tuple or list")
    if not isinstance(page_catalog, Mapping):
        raise JPVRFormatError("page_catalog must be a mapping of page_no to bytes")

    # Validate the existing catalog before using any old reference.
    old_catalog: dict[int, bytes] = {}
    for page_no, encoded in page_catalog.items():
        if isinstance(page_no, bool) or not isinstance(page_no, int):
            raise JPVRFormatError("page_catalog keys must be integer page numbers")
        if page_no < 2 or page_no > current_superblock.durable_tail_page_no:
            raise JPVRFormatError("page_catalog contains a page outside the current durable prefix")
        decoded = decode_page(encoded, expected_page_no=page_no)
        old_catalog[page_no] = bytes(encoded)
        if decoded.commit_generation > current_superblock.generation:
            raise JPVRFormatError("page_catalog contains a page newer than the current snapshot")

    staged = tuple(bytes(page) if isinstance(page, (bytes, bytearray, memoryview)) else page
                   for page in staged_page_bytes)
    if any(not isinstance(page, bytes) for page in staged):
        raise JPVRFormatError("staged pages must be bytes-like")

    next_generation = current_superblock.generation + 1
    if next_generation > 0xFFFFFFFFFFFFFFFF:
        raise JPVRFormatError("commit generation overflows u64")

    first_page_no = current_superblock.durable_tail_page_no + 1
    decoded_staged = []
    for offset, encoded in enumerate(staged):
        expected_page_no = first_page_no + offset
        if expected_page_no > 0xFFFFFFFFFFFFFFFF:
            raise JPVRFormatError("staged page range overflows u64")
        decoded = decode_page(encoded, expected_page_no=expected_page_no)
        if decoded.commit_generation != next_generation:
            raise JPVRFormatError("staged page generation does not match next commit generation")
        if decoded.page_type is PageType.COMMIT:
            raise JPVRFormatError("staged pages must not contain a COMMIT page")
        if decoded.page_no in old_catalog:
            raise JPVRFormatError("staged page number already exists in page_catalog")
        decoded_staged.append(decoded)

    transaction_id = decoded_staged[0].transaction_id
    if transaction_id == bytes(16):
        raise JPVRFormatError("transaction_id must be nonzero")
    if any(page.transaction_id != transaction_id for page in decoded_staged):
        raise JPVRFormatError("all staged pages must share one transaction_id")

    manifest_positions = [
        index for index, page in enumerate(decoded_staged)
        if page.page_type is PageType.MANIFEST
    ]
    if manifest_positions != [len(decoded_staged) - 1]:
        raise JPVRFormatError("exactly one MANIFEST must be the final staged page")

    manifest_page_bytes = staged[-1]
    manifest: DecodedManifestPage = decode_manifest_page(
        manifest_page_bytes, expected_page_no=decoded_staged[-1].page_no
    )
    if manifest.transaction_id != transaction_id:
        raise JPVRFormatError("MANIFEST transaction_id does not match staged pages")
    if manifest.commit_generation != next_generation:
        raise JPVRFormatError("MANIFEST generation does not match next commit generation")

    commit_page_no = first_page_no + len(staged)
    if commit_page_no > 0xFFFFFFFFFFFFFFFF:
        raise JPVRFormatError("COMMIT page number overflows u64")
    decoded_commit: DecodedCommitPage = decode_commit_page(
        commit_page_bytes, expected_page_no=commit_page_no
    )
    if decoded_commit.commit_generation != next_generation:
        raise JPVRFormatError("COMMIT generation does not match next commit generation")
    if decoded_commit.transaction_id != transaction_id:
        raise JPVRFormatError("COMMIT transaction_id does not match staged pages")
    if decoded_commit.record.manifest_ref.page_no != manifest.page_no:
        raise JPVRFormatError("COMMIT manifest reference does not match staged MANIFEST")
    if decoded_commit.record.manifest_ref.expected_commit_generation != next_generation:
        raise JPVRFormatError("COMMIT manifest reference generation is invalid")

    commit_id = decoded_commit.record.commit_id
    append = plan_append(
        current_superblock,
        len(staged) + 1,
        commit_id,
        checkpoint_page_no=manifest.manifest.checkpoint_page_no,
    )
    validate_append_plan(append, current_superblock)
    if append.commit_page_no != commit_page_no:
        raise JPVRFormatError("COMMIT page is not the final page of the append plan")

    new_catalog = dict(old_catalog)
    for decoded, encoded in zip(decoded_staged, staged):
        if decoded.page_no in new_catalog:
            raise JPVRFormatError("duplicate page number in assembled transaction")
        new_catalog[decoded.page_no] = encoded
    new_catalog[commit_page_no] = bytes(commit_page_bytes)

    # The full snapshot validator checks MANIFEST roots/high-water references,
    # previous-COMMIT continuity, COMMIT identity, durable tail and superblock.
    validate_commit_snapshot(
        commit_page_bytes,
        manifest_page_bytes,
        previous_commit_page_bytes=previous_commit_page_bytes,
        expected_commit_page_no=commit_page_no,
        superblock=append.new_superblock,
        page_catalog=new_catalog,
    )

    # For a generation transition, the caller must supply the exact predecessor
    # bytes; validate_commit_snapshot enforces presence/absence and identity.
    expected_previous = decoded_commit.record.previous_commit_ref
    if expected_previous is None and previous_commit_page_bytes is not None:
        raise JPVRFormatError("genesis transaction must not receive a previous COMMIT page")
    if expected_previous is not None and previous_commit_page_bytes is None:
        raise JPVRFormatError("previous COMMIT page bytes are required")

    ordered_bytes = staged + (bytes(commit_page_bytes),)
    ordered_numbers = tuple(range(first_page_no, commit_page_no + 1))
    return TransactionAssemblyPlan(
        append_plan=append,
        transaction_id=transaction_id,
        commit_id=commit_id,
        manifest_page_no=manifest.page_no,
        ordered_page_numbers=ordered_numbers,
        ordered_page_bytes=ordered_bytes,
    )


def validate_transaction_assembly_plan(
    plan: TransactionAssemblyPlan,
    current_superblock: Superblock,
    page_catalog: Mapping[int, bytes],
    *,
    previous_commit_page_bytes: bytes | None = None,
) -> None:
    """Rebuild a plan from its immutable page bytes and reject any tampering."""
    if not isinstance(plan, TransactionAssemblyPlan):
        raise JPVRFormatError("plan must be a TransactionAssemblyPlan")
    if not plan.ordered_page_bytes:
        raise JPVRFormatError("transaction plan must contain pages")
    rebuilt = assemble_transaction(
        current_superblock,
        plan.ordered_page_bytes[:-1],
        plan.ordered_page_bytes[-1],
        page_catalog,
        previous_commit_page_bytes=previous_commit_page_bytes,
    )
    if rebuilt != plan:
        raise JPVRFormatError("transaction assembly plan fields are inconsistent")
    validate_append_plan(plan.append_plan, current_superblock)
