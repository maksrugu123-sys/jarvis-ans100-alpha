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

## Explicit limits

- Normal open validates the active snapshot and its immediate predecessor COMMIT; it does not scan the entire historical COMMIT chain.
- Normal open validates direct MANIFEST references. Full active B+ tree validation is opt-in; trees modified by an attached typed COW plan are validated before commit.
- No free-page reuse, compaction, file-format upgrades, snapshot retention policy, full-page integrity sweep, CQE OUTBOX delivery/ACK, or JNVM adapter exists.
- The software calls `fsync` at the specified barriers, but cannot prove that a particular device/controller truthfully flushes volatile caches or survives sudden power loss.
- The writer is currently a POSIX/Android local-file reference. Other platforms/filesystems need separate locking/durability validation.

A successful commit return means the requested OS calls and readback checks completed. It is not a benchmark, a hardware power-failure proof, or production-readiness claim.
