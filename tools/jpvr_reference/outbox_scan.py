"""Read-only JPVR snapshot and committed OUTBOX history scanner.

The caller supplies a complete page catalog for the active durable prefix.
This module performs no file I/O and never claims that caller-provided bytes
were actually flushed to stable storage.
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from .codec import (
    JPVRFormatError,
    PageType,
    Superblock,
    decode_page,
    encode_superblock,
)
from .commit import (
    DecodedCommitPage,
    decode_commit_page,
    validate_commit_snapshot,
)
from .manifest import DecodedManifestPage, decode_manifest_page
from .outbox import (
    DecodedOutboxPage,
    decode_outbox_page,
    validate_outbox_commit_reference,
    validate_outbox_genesis,
    validate_outbox_sequence,
)


@dataclass(frozen=True)
class OutboxScanResult:
    """Validated active snapshot plus committed history in ascending order."""

    active_commit: DecodedCommitPage
    active_manifest: DecodedManifestPage
    commits: tuple[DecodedCommitPage, ...]
    events: tuple[DecodedOutboxPage, ...]


def _page_bytes(page_catalog: Mapping[int, bytes], page_no: int, label: str) -> bytes:
    raw = page_catalog.get(page_no)
    if raw is None:
        raise JPVRFormatError(f"{label} references a missing page")
    if not isinstance(raw, (bytes, bytearray, memoryview)):
        raise JPVRFormatError(f"{label} page bytes must be bytes-like")
    return bytes(raw)


def _decode_complete_catalog(
    page_catalog: Mapping[int, bytes],
    durable_tail_page_no: int,
) -> dict[int, object]:
    if not isinstance(page_catalog, Mapping):
        raise JPVRFormatError("page_catalog must be a complete page_no-to-bytes mapping")
    decoded = {}
    for page_no, raw in page_catalog.items():
        if isinstance(page_no, bool) or not isinstance(page_no, int) or page_no < 2:
            raise JPVRFormatError("page_catalog keys must be page numbers >= 2")
        if page_no > durable_tail_page_no:
            raise JPVRFormatError("page_catalog contains a page beyond the active durable tail")
        if not isinstance(raw, (bytes, bytearray, memoryview)):
            raise JPVRFormatError("page_catalog values must be bytes-like")
        decoded[page_no] = decode_page(raw, expected_page_no=page_no)
    return decoded


def _scan_commit_chain(
    active_commit: DecodedCommitPage,
    page_catalog: Mapping[int, bytes],
    decoded_catalog: Mapping[int, object],
) -> tuple[DecodedCommitPage, ...]:
    newest_to_oldest = []
    seen_pages = set()
    seen_commit_ids = set()
    seen_transaction_ids = set()
    current = active_commit

    while True:
        if current.page_no in seen_pages:
            raise JPVRFormatError("COMMIT chain contains a page cycle")
        if len(newest_to_oldest) >= len(page_catalog):
            raise JPVRFormatError("COMMIT chain exceeds the supplied page catalog")
        seen_pages.add(current.page_no)
        if current.record.commit_id in seen_commit_ids:
            raise JPVRFormatError("COMMIT chain contains a duplicate commit_id")
        if current.transaction_id in seen_transaction_ids:
            raise JPVRFormatError("COMMIT chain contains a duplicate transaction_id")
        seen_commit_ids.add(current.record.commit_id)
        seen_transaction_ids.add(current.transaction_id)

        manifest_raw = _page_bytes(
            page_catalog, current.record.manifest_ref.page_no, "COMMIT manifest"
        )
        manifest = decode_manifest_page(
            manifest_raw, expected_page_no=current.record.manifest_ref.page_no
        )
        if manifest.commit_generation != current.commit_generation:
            raise JPVRFormatError("COMMIT manifest generation does not match")
        if manifest.transaction_id != current.transaction_id:
            raise JPVRFormatError("COMMIT manifest transaction_id does not match")
        if manifest.manifest.generation != current.record.manifest_ref.expected_commit_generation:
            raise JPVRFormatError("COMMIT manifest PageRef generation does not match")

        newest_to_oldest.append(current)
        previous_ref = current.record.previous_commit_ref
        if previous_ref is None:
            if current.record.generation != 1:
                raise JPVRFormatError("COMMIT chain ended before generation 1")
            break
        if current.record.generation <= 1:
            raise JPVRFormatError("generation 1 COMMIT cannot have a predecessor")
        if previous_ref.page_no >= current.record.manifest_ref.page_no:
            raise JPVRFormatError("previous COMMIT must precede the current MANIFEST")
        previous_raw = _page_bytes(page_catalog, previous_ref.page_no, "previous COMMIT")
        previous = decode_commit_page(previous_raw, expected_page_no=previous_ref.page_no)
        if previous.commit_generation != previous_ref.expected_commit_generation:
            raise JPVRFormatError("previous COMMIT generation does not match its PageRef")
        if previous.record.generation + 1 != current.record.generation:
            raise JPVRFormatError("COMMIT chain generations are not contiguous")
        if previous.page_no >= current.page_no:
            raise JPVRFormatError("COMMIT pages must be append ordered")
        current = previous

    return tuple(reversed(newest_to_oldest))


def _scan_outbox_chain(
    active_manifest: DecodedManifestPage,
    active_commit: DecodedCommitPage,
    commits: tuple[DecodedCommitPage, ...],
    page_catalog: Mapping[int, bytes],
    decoded_catalog: Mapping[int, object],
) -> tuple[DecodedOutboxPage, ...]:
    high_water = active_manifest.manifest.outbox_high_water_page_no
    commit_by_page = {item.page_no: item for item in commits}
    if high_water == 0:
        outbox_pages = [
            page_no for page_no, decoded in decoded_catalog.items()
            if decoded.page_type is PageType.OUTBOX
        ]
        if outbox_pages:
            raise JPVRFormatError("OUTBOX pages exist but MANIFEST high-water mark is zero")
        return ()

    newest_to_oldest = []
    seen_pages = set()
    page_no = high_water
    expected_generation = None
    while True:
        if page_no in seen_pages:
            raise JPVRFormatError("OUTBOX chain contains a page cycle")
        if len(newest_to_oldest) >= len(page_catalog):
            raise JPVRFormatError("OUTBOX chain exceeds the supplied page catalog")
        seen_pages.add(page_no)
        raw = _page_bytes(page_catalog, page_no, "OUTBOX chain")
        page = decode_outbox_page(raw, expected_page_no=page_no)
        if page.page_no > active_commit.record.durable_tail_page_no:
            raise JPVRFormatError("OUTBOX event is beyond the active durable tail")
        if page.commit_generation > active_commit.commit_generation:
            raise JPVRFormatError("OUTBOX event is newer than the active COMMIT")
        source_raw = _page_bytes(
            page_catalog, page.event.commit_ref.page_no, "OUTBOX source COMMIT"
        )
        source = validate_outbox_commit_reference(page, source_raw)
        committed_source = commit_by_page.get(source.page_no)
        if committed_source is None or committed_source != source:
            raise JPVRFormatError("OUTBOX source COMMIT is not in the active COMMIT chain")
        if source.record.durable_tail_page_no < page.page_no:
            raise JPVRFormatError("source COMMIT durable tail does not include its OUTBOX event")
        newest_to_oldest.append(page)

        previous_ref = page.event.previous_event_ref
        if previous_ref is None:
            break
        if previous_ref.page_no >= page.page_no:
            raise JPVRFormatError("OUTBOX predecessor must precede its event")
        previous_raw = _page_bytes(
            page_catalog, previous_ref.page_no, "OUTBOX predecessor"
        )
        previous = decode_outbox_page(
            previous_raw, expected_page_no=previous_ref.page_no
        )
        if previous.commit_generation != previous_ref.expected_commit_generation:
            raise JPVRFormatError("OUTBOX predecessor generation does not match PageRef")
        page_no = previous_ref.page_no

    events = tuple(reversed(newest_to_oldest))
    validate_outbox_genesis(events[0])
    event_ids = set()
    idempotency_keys = set()
    for index, event in enumerate(events):
        if event.event_id in event_ids:
            raise JPVRFormatError("OUTBOX chain contains a duplicate event_id")
        if event.idempotency_key in idempotency_keys:
            raise JPVRFormatError("OUTBOX chain contains a duplicate idempotency_key")
        event_ids.add(event.event_id)
        idempotency_keys.add(event.idempotency_key)
        if index:
            validate_outbox_sequence(events[index - 1], event)

    reachable = {event.page_no for event in events}
    catalog_outbox_pages = {
        page_no for page_no, decoded in decoded_catalog.items()
        if decoded.page_type is PageType.OUTBOX
    }
    if reachable != catalog_outbox_pages:
        raise JPVRFormatError("MANIFEST OUTBOX high-water chain does not cover every durable OUTBOX page")
    return events


def scan_committed_outbox(
    superblock: Superblock,
    page_catalog: Mapping[int, bytes],
) -> OutboxScanResult:
    """Validate the active snapshot, full COMMIT chain and complete OUTBOX chain.

    page_catalog must contain every data page in the active durable prefix.
    The supplied Superblock must already have been decoded from the intended
    physical slot (use decode_superblock(..., expected_slot_id=...)).
    This validates logical consistency/reachability, not actual disk flushes.
    """
    encode_superblock(superblock)
    decoded_catalog = _decode_complete_catalog(
        page_catalog, superblock.durable_tail_page_no
    )
    active_commit_raw = _page_bytes(
        page_catalog, superblock.active_commit_page_no, "active Superblock COMMIT"
    )
    active_commit = decode_commit_page(
        active_commit_raw, expected_page_no=superblock.active_commit_page_no
    )
    active_manifest_raw = _page_bytes(
        page_catalog, active_commit.record.manifest_ref.page_no, "active COMMIT manifest"
    )
    previous_raw = None
    previous_ref = active_commit.record.previous_commit_ref
    if previous_ref is not None:
        previous_raw = _page_bytes(
            page_catalog, previous_ref.page_no, "active COMMIT predecessor"
        )
    validate_commit_snapshot(
        active_commit_raw,
        active_manifest_raw,
        previous_commit_page_bytes=previous_raw,
        expected_commit_page_no=superblock.active_commit_page_no,
        superblock=superblock,
        page_catalog=page_catalog,
    )
    active_manifest = decode_manifest_page(
        active_manifest_raw, expected_page_no=active_commit.record.manifest_ref.page_no
    )
    commits = _scan_commit_chain(active_commit, page_catalog, decoded_catalog)
    events = _scan_outbox_chain(
        active_manifest, active_commit, commits, page_catalog, decoded_catalog
    )
    return OutboxScanResult(active_commit, active_manifest, commits, events)
