# JPVR v1 — committed OUTBOX history scanner

## Purpose

`scan_committed_outbox(superblock, page_catalog)` is a read-only reference scanner for one active JPVR snapshot. It does not write pages, perform file I/O, flush storage, publish events, or provide subscriber acknowledgement state.

The caller must first obtain the intended valid physical Superblock slot, for example with `decode_superblock(raw_slot, expected_slot_id=...)`. The scanner receives that already-decoded Superblock and a complete mapping of every data page from page 2 through the active `durable_tail_page_no`.

## Validation sequence

1. Validate the Superblock structure and decode every page in the supplied durable page catalog, checking page number, page CRCs and a gap-free page range.
2. Resolve the active COMMIT selected by the Superblock.
3. Run `validate_commit_snapshot` for the active COMMIT/MANIFEST pair, previous COMMIT descriptor, Superblock identity, durable tail and all current MANIFEST roots/high-water pages.
4. Walk the entire previous-COMMIT chain back to generation 1. Validate each COMMIT page, its MANIFEST page reference/generation/transaction identity, contiguous generations, append order and unique commit/transaction IDs. Require every durable COMMIT page in the catalog to be covered by the active chain.
5. Start the OUTBOX scan at the active MANIFEST's `outbox_high_water_page_no`. Follow immutable `previous_event_ref` links backwards to genesis.
6. For every OUTBOX event, resolve its exact source COMMIT page and verify generation, transaction ID, commit ID and operation ID. Require that source COMMIT to belong to the active COMMIT chain and that its durable tail includes the event.
7. Reverse the collected events into ascending order; validate genesis and every adjacent sequence pair; reject global duplicate event IDs and idempotency keys.
8. Require the chain to cover every OUTBOX page in the supplied durable catalog. If high-water is zero, there must be no durable OUTBOX pages.

## API

- `OutboxScanResult` — immutable result containing the active COMMIT, active MANIFEST, complete COMMIT chain and complete OUTBOX event chain in ascending generation/sequence order.
- `scan_committed_outbox(superblock, page_catalog)` — performs the checks above and returns `OutboxScanResult`, or raises `JPVRFormatError` on any inconsistency.

The public API is exported from `tools.jpvr_reference`.

## Important boundaries

- The scanner trusts the caller to supply the actual active Superblock selected from the intended physical slot. It does not read or arbitrate the two on-disk Superblock slots.
- The page catalog is an in-memory input and must be complete and contiguous. This implementation is a reference algorithm, not a scalable streaming reader.
- The active MANIFEST's OUTBOX index root is validated as an INDEX page by `validate_manifest`, but this scanner does not traverse the B+ tree to prove each event's membership in that index. Completeness is instead checked against all OUTBOX pages in the supplied durable prefix and the MANIFEST high-water chain.
- Historical COMMIT/MANIFEST descriptor links are validated, but historical MANIFEST roots are not independently replayed as historical snapshots.
- CRC and logical consistency checks do not prove that bytes were actually flushed to stable storage. No crash recovery, file locking, write protocol, fsync, superblock publication or fault injection is implemented.
- This does not implement an event publisher, subscriber cursors/ACKs, retry/retention, external side effects, or exactly-once delivery. It only validates the committed event history presented by the caller.

## Tests and verification

`tools/jpvr_reference/test_outbox_scan.py` contains 16 focused test methods. They cover one- and two-generation scans, empty OUTBOX, package exports, incomplete/corrupt page catalogs, Superblock mismatch, missing COMMIT pages, sequence gaps, duplicate IDs/idempotency keys, high-water omissions, broken predecessor coverage and invalid source references.

The previous 11-module JPVR set was reported by the user as passing at 242 methods. Since that run, four genesis-validator tests and these 16 scanner tests have been added. Current JPVR source count: **262 methods across 12 modules**; the 20 newer tests are **AUTHORED, NOT RUN**. No build, CI or GitHub Actions workflow is part of this verification.
