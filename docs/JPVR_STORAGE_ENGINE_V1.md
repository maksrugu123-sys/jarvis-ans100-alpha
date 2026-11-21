# JPVR Real Storage Engine V1

## Status

Implemented as a Python file-backed reference layer in `tools/jpvr_reference/storage.py`. Its unit tests are **AUTHORED, NOT RUN** in this implementation turn. It is not yet device-verified or production-ready.

## Physical layout

All sizes and offsets are fixed at 4096 bytes:

| Physical region | Offset | Size |
|---|---:|---:|
| Superblock slot 0 | 0 | 4096 |
| Superblock slot 1 | 4096 | 4096 |
| Page 2 (first data page) | 8192 | 4096 |
| Page N | N × 4096 | 4096 |

A new file is created with `O_EXCL`; an existing path is never overwritten. Both genesis Superblock copies are encoded with their physical `slot_id`, written, and synced. The parent directory is synced after file creation.

## API

```python
from tools.jpvr_reference import JPVRStorage

with JPVRStorage.create("jarvis.jpvr") as store:
    current = store.get_superblock()
    catalog = store.load_page_catalog(max_pages=4096)
    # Build TypedTransactionRequest(current_superblock=current,
    #     page_catalog=catalog, ...) and call build_typed_transaction(request).
    # store.commit(plan)

with JPVRStorage.open("jarvis.jpvr") as store:
    active = store.get_superblock()
    page_bytes = store.read_page(active.active_commit_page_no)
```

- `create(path, container_uuid=None, file_mode=0o600)`: creates a new file and two genesis slots.
- `open(path, recover_tail=True, verify_index_trees=False)`: selects a valid snapshot; optionally truncates unpublished tail bytes. Full current INDEX-tree traversal is optional because it is O(total reachable INDEX pages).
- `get_superblock()`: refreshes and returns the selected active snapshot.
- `read_page(page_no)`: reads one page within the current durable prefix and checks its page envelope/CRCs.
- `load_page_catalog(max_pages=4096)`: materializes a mapping for the typed in-memory builder. The default cap is 4096 pages (16 MiB raw page bytes plus Python mapping/object overhead).
- `scan_history(verify_index_trees=None)`: runs the read-only historical audit over a lazy file catalog; returns verified snapshots and reports orphan tail bytes without truncation.
- `recover_copy(destination, expected_commit_id, verify_index_trees=None)`: re-scans under a shared lock and publishes the last fully verified prefix to a new, non-existing destination. It validates the prefix OUTBOX chain, writes both Superblock slots and never modifies the source.
- `commit(TransactionAssemblyPlan | TypedTransactionBuildPlan)`: revalidates the plan under the exclusive writer lock, appends it, and publishes the inactive slot.
- `close()`: closes the file; the class is also a context manager.

The implementation uses POSIX `fcntl.flock` for cross-process advisory locking and an in-process `threading.RLock` per storage object. It uses positional reads/writes so handles do not share a mutable file offset.

## Commit and recovery protocol

1. Acquire the exclusive lock and select the newest valid Superblock from the two physical slots.
2. Remove bytes beyond the selected committed prefix. Revalidate the transaction assembly against the current base so stale plans cannot be written.
3. For typed COW plans, validate old/new roots, generated INDEX bytes, old-tree statistics and the complete modified tree before any append write.
4. Write all new transaction pages in their exact contiguous physical order; COMMIT is last.
5. Call `fsync` on the file. No new Superblock is published before this barrier succeeds.
6. Write the encoded new Superblock to the inactive slot only; call `fsync` again.
7. Re-read both slots and verify that the new snapshot is the selected active one before returning success.

On open, each slot is decoded with its expected physical slot ID. The candidate's durable prefix, active COMMIT, MANIFEST, previous COMMIT and every direct MANIFEST root/high-water reference are checked. The valid candidate with the greatest generation wins. If valid slots have the same generation but describe different logical states, open fails closed. A damaged newer slot therefore falls back to the previous valid slot. Bytes/pages beyond the selected durable tail are orphan data and are truncated when `recover_tail=True`.

If an error occurs after writing the new Superblock has begun, `commit` raises `JPVRCommitUncertainError` carrying `commit_id` and `generation`. The caller must reopen and inspect `active_commit_id` before retrying. Do not treat an uncertain result as permission to blindly repeat an external side effect.

## Verification

```sh
python3 -m unittest tools.jpvr_reference.test_storage -v
```

The 19 authored tests cover dual genesis slots, create-no-overwrite, truncated containers, genesis/second-generation commits, alternating slots, stale-plan rejection, orphan/partial tail trimming, damaged Superblock/COMMIT/root fallback, both-slot corruption, equal-generation conflict, page bounds, bounded catalog reads, typed COW page validation and injected partial Superblock publication.

These tests are **AUTHORED, NOT RUN** in the implementation turn. No Python tests, syntax/import checks, Android/Termux measurements, Gradle/Android builds, CI or GitHub Actions have been run for this stage.

## Historical scan

JPVRStorage.open() still validates the selected active snapshot and immediate previous COMMIT for fast open. It does not automatically walk every earlier generation. Call store.scan_history() when a full audit is required. The scanner checks all historical transaction page ranges and each historical MANIFEST separately, and returns the last verified commit boundary in JPVRHistoryScanResult. It reuses the committed OUTBOX chain validator. Set verify_index_trees=True for a full B+ tree traversal for each historical generation.

The scan is read-only. Bytes after the active durable tail are reported in orphan_tail_bytes; they are not included in committed history and are not deleted by scan_history(). If a later generation is corrupt, earlier fully validated snapshots remain in the result as recovery candidates. The scanner validates the OUTBOX chain for that verified prefix even when a later generation failed.

An explicit recover_copy(destination, expected_commit_id=...) operation can publish the verified prefix as a separate file. It re-scans under a shared lock, requires the exact 16-byte COMMIT identity and a valid OUTBOX chain, copies only pages through the verified COMMIT boundary, reconstructs both alternating Superblock slots, fsyncs and reads back the new file, and publishes it through a same-directory hard link that refuses an existing path. The source is never changed. A JPVRRecoveryCopyUncertainError requires inspection of the destination before retrying. This is not in-place rollback or repair. See docs/JPVR_RECOVERY_REPAIR_V1.md.

Focused tests:

```sh
python3 -m unittest tools.jpvr_reference.test_recovery -v
```

The 23 historical scanner tests and 12 recovery-copy tests are AUTHORED, NOT RUN in this implementation turn. Current JPVR source count is 362 methods across seventeen modules; previous transaction/COW/storage tests have not all been rerun as one combined post-change suite.

## Explicit limits

- Normal open validates the active snapshot and its immediate predecessor COMMIT. Full historical scanning is an explicit scan_history() operation, not an automatic open-time step.
- Normal open validates direct MANIFEST references. Full active B+ tree validation is opt-in; trees modified by an attached typed COW plan are validated before commit.
- No automatic repair/rollback to an older historical snapshot, free-page reuse, compaction, file-format upgrades, snapshot retention policy, full-page integrity sweep, CQE OUTBOX delivery/ACK, or JNVM adapter exists.
- The software calls `fsync` at the specified barriers, but cannot prove that a particular device/controller truthfully flushes volatile caches or survives sudden power loss.
- The writer is currently a POSIX/Android local-file reference. Other platforms/filesystems need separate locking/durability validation.

A successful commit return means the requested OS calls and readback checks completed. It is not a benchmark, a hardware power-failure proof, or production-readiness claim.


## Consistent bounded snapshot read

`JPVRStorage.load_snapshot(max_pages=4096)` returns the selected active Superblock and its page catalog while holding one shared file lock. This avoids a race between separately calling `get_superblock()` and `load_page_catalog()`. The catalog is still materialized and bounded; the default limit is 4096 physical pages (16 MiB encoded bytes plus Python mapping overhead). The method reads and validates each page envelope in the selected durable prefix but does not truncate orphan tail bytes. The single-neuron checkpoint-store facade uses this API for a consistent read base; a concurrent writer after the snapshot is read is rejected by the ordinary stale-base validation in `commit()`.
