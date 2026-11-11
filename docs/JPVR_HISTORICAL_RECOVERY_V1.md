# JPVR Historical Recovery Scanner V1

## Status and purpose

Implemented as a read-only Python reference scanner in tools/jpvr_reference/recovery.py, exposed through JPVRStorage.scan_history(). The 23 historical-scanner tests are AUTHORED, NOT RUN for this implementation turn. No Android device verification or production recovery claim is made.

The existing standalone scan_committed_outbox() walks the COMMIT predecessor chain and OUTBOX event links, but it requires a complete caller-materialized catalog and does not validate every historical MANIFEST's roots as a historical snapshot. This scanner adds that missing full-history audit for the file-backed storage engine.

## API

```python
from tools.jpvr_reference import JPVRStorage

with JPVRStorage.open("jarvis.jpvr") as store:
    report = store.scan_history()
    print(report.is_complete)
    print(report.verified_generation)
    print(report.verified_durable_tail_page_no)
    print(report.issue)
    print(report.orphan_tail_bytes)
```

Pure reference API: scan_jpvr_history(superblock, page_catalog, verify_index_trees=False).

Result types:
- JPVRHistoryScanResult: active Superblock, verified snapshots, OUTBOX events, first issue, commit/OUTBOX completeness flags, last verified durable tail, and physical orphan-tail byte count when invoked through file storage.
- JPVRHistoricalSnapshot: decoded COMMIT and MANIFEST for one generation, a reconstructed historical Superblock, first/last page numbers, page count, and optional INDEX tree statistics.
- JPVRRecoveryIssue: stable issue code, explanatory message, and optional physical page/generation.
- scan_jpvr_history: read-only scanner for any Mapping of page number to bytes; the mapping may be lazy/file-backed.

## Audit algorithm

1. Read pages 2 through the active Superblock's durable tail in physical order. Validate page number, envelope, header CRC, payload CRC, reserved bytes and padding. Retain compact page-header metadata; do not retain all encoded page bytes.
2. Enumerate COMMIT pages in physical order.
3. For each generation, require generation numbers to be contiguous from 1, unique commit and transaction IDs, and an exact predecessor PageRef to the preceding physical COMMIT.
4. Derive that generation's append interval: page 2 through genesis COMMIT, then each previous COMMIT page + 1 through the current COMMIT page. Every page in the interval must be present and share the current COMMIT's generation and transaction ID.
5. Reject an intermediate COMMIT, require exactly one MANIFEST, and require the MANIFEST to be the final staged page immediately before the COMMIT.
6. Decode the generation's MANIFEST and validate the COMMIT/MANIFEST/previous-COMMIT relationship against a reconstructed historical Superblock. A prefix-limited page mapping prevents an old MANIFEST from referencing a page appended by a future generation.
7. Validate all seven INDEX root references and CHECKPOINT/JOURNAL/OUTBOX high-water references for that historical snapshot. If verify_index_trees=True, traverse all seven B+ trees in every generation and record each IndexTreeStats.
8. Verify that the final validated COMMIT exactly matches active Superblock generation, page, commit ID, checkpoint, durable tail and alternating slot.
9. Reuse the OUTBOX predecessor/source-COMMIT/sequence/deduplication validator over the validated history.

## Partial-history reporting and recovery meaning

A corruption/missing page/I/O failure or invalid historical transaction produces one JPVRRecoveryIssue. Fully validated earlier generations remain in snapshots; verified_generation and verified_durable_tail_page_no identify the last fully validated commit boundary. If only OUTBOX validation fails, commit_history_complete remains true, outbox_history_complete is false, and the commit snapshots remain available.

When called through JPVRStorage.scan_history(), file bytes beyond the active durable prefix are reported as orphan_tail_bytes and has_orphan_tail. Such bytes are not committed state. The scan never truncates them.

A verified prefix is a recovery candidate, not an automatic rollback. The scanner validates the OUTBOX chain for the last fully verified snapshot even when a later generation failed. A later explicit copy operation can publish that prefix as a separate file, but must independently re-scan and require the exact expected commit identity. The source remains unchanged.

## Memory and cost

The scanner reads raw page bytes lazily and retains a compact header record per physical page plus one snapshot record per generation: O(page count + generation count) metadata, rather than O(page count × 4096) retained page bytes. Historical MANIFEST/root validation performs additional random reads. verify_index_trees=True can cost O(the sum of reachable INDEX pages across all snapshots) and is opt-in.

## Tests

```sh
python3 -m unittest tools.jpvr_reference.test_recovery -v
```

The 23 test methods cover empty/genesis and one-/three-generation histories; corrupted, missing and unreadable pages; invalid historical roots; generation/transaction identity mismatch; incorrect MANIFEST placement; duplicate IDs; generation gaps; active Superblock/checkpoint mismatch; uncommitted/orphan tail; extra catalog pages; optional full-tree traversal; valid and broken OUTBOX history; and the file-backed lazy scanner.

These tests are AUTHORED, NOT RUN in the implementation turn. Current JPVR source suite has 362 test methods across seventeen modules. No builds, CI, GitHub Actions or device measurements were run.

## Limits

- It is read-only and does not auto-repair, roll back, truncate, compact or reclaim pages.
- It validates historical MANIFEST references and optionally traverses historical indexes; it does not reconstruct the semantic neuron/JNVM state from arbitrary application-level records.
- OUTBOX validation confirms committed event history and identity/sequence consistency; it does not deliver events, acknowledge subscribers or guarantee exactly-once side effects.
- It can identify a verified prefix but does not establish that the device's flash controller or filesystem survived actual power loss. The two fsync barriers remain a software protocol requirement, not hardware-level proof.


## Recovery-copy boundary

When a later page or generation is damaged, the scanner still validates the OUTBOX chain for the final fully verified snapshot. Therefore outbox_history_complete refers to the recoverable prefix in a partial-history report. If that prefix OUTBOX chain is invalid or unreadable, recovery-copy publication is refused. See docs/JPVR_RECOVERY_REPAIR_V1.md for the explicit no-overwrite, source-preserving copy operation.
