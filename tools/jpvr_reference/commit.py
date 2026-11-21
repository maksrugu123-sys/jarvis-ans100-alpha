"""Strict JPVR v1 COMMIT descriptor codec and snapshot cross-validator.

This is a reference codec only. It does not write, sync, publish or recover a
container and must never be treated as proof of durable storage.
"""
from __future__ import annotations

from dataclasses import dataclass
import struct
from collections.abc import Mapping

from .codec import (
    DecodedPage,
    JPVRFormatError,
    PageType,
    Superblock,
    _fixed_bytes,
    _require_uint,
    decode_page,
    encode_page,
    encode_superblock,
)
from .manifest import (
    DecodedManifestPage,
    Manifest,
    PageRef,
    decode_manifest_page,
    validate_manifest,
)


COMMIT_MAGIC = b"JCOM"
COMMIT_VERSION = 1
COMMIT_HEADER = struct.Struct("<4sHHQ16s16s16sQQQQQQ")
COMMIT_HEADER_BYTES = COMMIT_HEADER.size  # 112 bytes


@dataclass(frozen=True)
class CommitRecord:
    generation: int
    transaction_id: bytes
    commit_id: bytes
    operation_id: bytes
    manifest_ref: PageRef
    durable_tail_page_no: int
    previous_commit_ref: PageRef | None = None


@dataclass(frozen=True)
class DecodedCommitPage:
    page_no: int
    transaction_id: bytes
    commit_generation: int
    record: CommitRecord


def _validate_commit_record(record: CommitRecord) -> tuple[bytes, bytes, bytes]:
    if not isinstance(record, CommitRecord):
        raise JPVRFormatError("record must be a CommitRecord")
    _require_uint("commit generation", record.generation, 0xFFFFFFFFFFFFFFFF)
    if record.generation < 1:
        raise JPVRFormatError("commit generation must be positive")

    transaction_id = _fixed_bytes("transaction_id", record.transaction_id, 16)
    commit_id = _fixed_bytes("commit_id", record.commit_id, 16)
    operation_id = _fixed_bytes("operation_id", record.operation_id, 16)
    if transaction_id == bytes(16) or commit_id == bytes(16) or operation_id == bytes(16):
        raise JPVRFormatError("transaction_id, commit_id and operation_id must be nonzero")

    if not isinstance(record.manifest_ref, PageRef):
        raise JPVRFormatError("manifest_ref must be a PageRef")
    _require_uint("manifest_ref.page_no", record.manifest_ref.page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint(
        "manifest_ref.expected_commit_generation",
        record.manifest_ref.expected_commit_generation,
        0xFFFFFFFFFFFFFFFF,
    )
    if (record.manifest_ref.page_no < 2
            or record.manifest_ref.expected_commit_generation != record.generation):
        raise JPVRFormatError("manifest PageRef must point to current commit generation")

    _require_uint("durable_tail_page_no", record.durable_tail_page_no, 0xFFFFFFFFFFFFFFFF)
    if record.durable_tail_page_no < 2:
        raise JPVRFormatError("durable tail must include at least one data page")

    previous = record.previous_commit_ref
    if record.generation == 1:
        if previous is not None:
            raise JPVRFormatError("generation 1 commit cannot reference a previous commit")
    else:
        if not isinstance(previous, PageRef):
            raise JPVRFormatError("non-genesis commit must reference the previous commit")
        _require_uint("previous_commit_ref.page_no", previous.page_no, 0xFFFFFFFFFFFFFFFF)
        _require_uint(
            "previous_commit_ref.expected_commit_generation",
            previous.expected_commit_generation,
            0xFFFFFFFFFFFFFFFF,
        )
        if previous.page_no < 2 or previous.expected_commit_generation != record.generation - 1:
            raise JPVRFormatError("previous commit must reference the immediately preceding generation")
    return transaction_id, commit_id, operation_id


def encode_commit_payload(record: CommitRecord) -> bytes:
    """Encode the fixed 112-byte JCOM v1 descriptor payload."""
    transaction_id, commit_id, operation_id = _validate_commit_record(record)
    previous = record.previous_commit_ref
    previous_page = 0 if previous is None else previous.page_no
    previous_generation = 0 if previous is None else previous.expected_commit_generation
    return COMMIT_HEADER.pack(
        COMMIT_MAGIC,
        COMMIT_VERSION,
        COMMIT_HEADER_BYTES,
        record.generation,
        transaction_id,
        commit_id,
        operation_id,
        previous_page,
        previous_generation,
        record.manifest_ref.page_no,
        record.manifest_ref.expected_commit_generation,
        record.durable_tail_page_no,
        0,
    )


def decode_commit_payload(payload: bytes | bytearray | memoryview) -> CommitRecord:
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("COMMIT payload must be bytes-like")
    raw = bytes(payload)
    if len(raw) != COMMIT_HEADER_BYTES:
        raise JPVRFormatError("COMMIT payload must be exactly 112 bytes")
    (
        magic, version, header_bytes, generation,
        transaction_id, commit_id, operation_id,
        previous_page, previous_generation,
        manifest_page, manifest_generation, durable_tail, reserved,
    ) = COMMIT_HEADER.unpack(raw)
    if magic != COMMIT_MAGIC or version != COMMIT_VERSION:
        raise JPVRFormatError("invalid COMMIT magic or version")
    if header_bytes != COMMIT_HEADER_BYTES or reserved != 0:
        raise JPVRFormatError("invalid COMMIT header size or reserved field")

    if (previous_page == 0) != (previous_generation == 0):
        raise JPVRFormatError("previous commit PageRef must be fully zero or fully populated")
    previous = None if previous_page == 0 else PageRef(previous_page, previous_generation)
    record = CommitRecord(
        generation=generation,
        transaction_id=transaction_id,
        commit_id=commit_id,
        operation_id=operation_id,
        manifest_ref=PageRef(manifest_page, manifest_generation),
        durable_tail_page_no=durable_tail,
        previous_commit_ref=previous,
    )
    _validate_commit_record(record)
    return record


def encode_commit_page(record: CommitRecord, page_no: int) -> bytes:
    """Encode a COMMIT page; it must be the final page of its planned prefix."""
    _require_uint("page_no", page_no, 0xFFFFFFFFFFFFFFFF)
    _validate_commit_record(record)
    if record.durable_tail_page_no != page_no:
        raise JPVRFormatError("COMMIT page number must equal durable_tail_page_no")
    if record.manifest_ref.page_no >= page_no:
        raise JPVRFormatError("manifest page must precede COMMIT page")
    if (record.previous_commit_ref is not None
            and record.previous_commit_ref.page_no >= record.manifest_ref.page_no):
        raise JPVRFormatError("previous COMMIT page must precede the new manifest")
    return encode_page(
        PageType.COMMIT,
        page_no,
        record.generation,
        record.transaction_id,
        encode_commit_payload(record),
    )


def decode_commit_page(
    page_bytes: bytes | bytearray | memoryview,
    *,
    expected_page_no: int | None = None,
) -> DecodedCommitPage:
    page: DecodedPage = decode_page(page_bytes, expected_page_no=expected_page_no)
    if page.page_type is not PageType.COMMIT:
        raise JPVRFormatError("page type is not COMMIT")
    record = decode_commit_payload(page.payload)
    if record.generation != page.commit_generation:
        raise JPVRFormatError("COMMIT payload generation does not match page header")
    if record.transaction_id != page.transaction_id:
        raise JPVRFormatError("COMMIT payload transaction_id does not match page header")
    if record.durable_tail_page_no != page.page_no:
        raise JPVRFormatError("COMMIT page must equal durable_tail_page_no")
    if record.manifest_ref.page_no >= page.page_no:
        raise JPVRFormatError("manifest page must precede COMMIT page")
    if (record.previous_commit_ref is not None
            and record.previous_commit_ref.page_no >= record.manifest_ref.page_no):
        raise JPVRFormatError("previous COMMIT page must precede the new manifest")
    return DecodedCommitPage(
        page_no=page.page_no,
        transaction_id=page.transaction_id,
        commit_generation=page.commit_generation,
        record=record,
    )


def validate_commit_snapshot(
    commit_page_bytes: bytes | bytearray | memoryview,
    manifest_page_bytes: bytes | bytearray | memoryview,
    *,
    previous_commit_page_bytes: bytes | bytearray | memoryview | None = None,
    expected_commit_page_no: int | None = None,
    superblock: Superblock | None = None,
    page_catalog: Mapping[int, bytes] | None = None,
) -> DecodedCommitPage:
    """Cross-check COMMIT, MANIFEST, optional previous COMMIT and superblock.

    Supplying page_catalog together with superblock additionally validates all
    manifest roots/high-water references. Without file I/O this remains a
    snapshot-consistency check over caller-supplied page bytes only.
    """
    current = decode_commit_page(
        commit_page_bytes, expected_page_no=expected_commit_page_no
    )
    manifest: DecodedManifestPage = decode_manifest_page(
        manifest_page_bytes, expected_page_no=current.record.manifest_ref.page_no
    )
    if manifest.commit_generation != current.record.generation:
        raise JPVRFormatError("manifest generation does not match COMMIT generation")
    if manifest.transaction_id != current.transaction_id:
        raise JPVRFormatError("manifest transaction_id does not match COMMIT")
    if manifest.manifest.generation != current.record.manifest_ref.expected_commit_generation:
        raise JPVRFormatError("manifest PageRef generation does not match MANIFEST page")
    if current.record.manifest_ref.expected_commit_generation != current.commit_generation:
        raise JPVRFormatError("COMMIT manifest reference is not from the current generation")

    previous_ref = current.record.previous_commit_ref
    if previous_ref is None:
        if previous_commit_page_bytes is not None:
            raise JPVRFormatError("genesis COMMIT must not receive a previous COMMIT page")
    else:
        if previous_commit_page_bytes is None:
            raise JPVRFormatError("previous COMMIT page bytes are required")
        previous = decode_commit_page(
            previous_commit_page_bytes,
            expected_page_no=previous_ref.page_no,
        )
        if previous.commit_generation != previous_ref.expected_commit_generation:
            raise JPVRFormatError("previous COMMIT generation does not match PageRef")
        if previous.record.generation + 1 != current.record.generation:
            raise JPVRFormatError("COMMIT generation chain is not contiguous")
        if previous.record.commit_id == current.record.commit_id:
            raise JPVRFormatError("commit_id must change between generations")
        if previous.record.transaction_id == current.transaction_id:
            raise JPVRFormatError("transaction_id must change between generations")

    if (superblock is None) != (page_catalog is None):
        raise JPVRFormatError("superblock and page_catalog must be supplied together")
    if superblock is not None:
        encode_superblock(superblock)
        if superblock.generation != current.commit_generation:
            raise JPVRFormatError("superblock generation does not match COMMIT")
        if superblock.active_commit_page_no != current.page_no:
            raise JPVRFormatError("superblock active COMMIT page does not match")
        if superblock.active_commit_id != current.record.commit_id:
            raise JPVRFormatError("superblock active_commit_id does not match")
        if superblock.durable_tail_page_no != current.record.durable_tail_page_no:
            raise JPVRFormatError("superblock durable tail does not match COMMIT")
        if superblock.container_uuid != manifest.manifest.container_uuid:
            raise JPVRFormatError("superblock container UUID does not match MANIFEST")
        if superblock.checkpoint_page_no != manifest.manifest.checkpoint_page_no:
            raise JPVRFormatError("superblock checkpoint does not match MANIFEST")
        validate_manifest(manifest.manifest, superblock, page_catalog)

    return current
