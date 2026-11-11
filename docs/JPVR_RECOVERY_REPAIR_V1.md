# JPVR Recovery Copy V1

## Status

Implemented as an explicit non-destructive Python reference operation in JPVRStorage.recover_copy(). Twelve focused tests plus one historical-scanner regression are **AUTHORED, NOT RUN** in this implementation turn. No Android filesystem/device verification or production recovery claim is made.

This is a recovery **copy**, not in-place repair. The damaged source file is never truncated, rewritten, or replaced.

## API

```python
from tools.jpvr_reference import JPVRStorage

with JPVRStorage.open("jarvis.jpvr", recover_tail=False) as store:
    report = store.scan_history()
    if report.last_verified_snapshot is not None:
        expected_id = report.last_verified_snapshot.commit.record.commit_id
    else:
        expected_id = bytes(16)  # only for a verified empty genesis container
    result = store.recover_copy(
        "jarvis-recovered.jpvr",
        expected_commit_id=expected_id,
    )
    print(result.recovered_superblock.generation)
    print(result.source_issue)
    print(result.discarded_orphan_tail_bytes)
```

The method performs a fresh internal scan under its own shared lock. The earlier report is only a way for the caller to discover the candidate commit ID; the method does not accept a caller-supplied scan result as trusted authority. expected_commit_id is mandatory and must be exactly 16 bytes.

## Eligibility rules

1. The last fully verified COMMIT snapshot is the only recovery target; callers cannot pass an arbitrary generation.
2. Every physical page through that COMMIT must pass page-envelope checks, and every generation through the target must pass transaction/MANIFEST/COMMIT validation.
3. The scanner must validate the OUTBOX chain for the same verified prefix. A later damaged generation may be excluded, but an invalid or unreadable OUTBOX chain in the candidate prefix blocks publication.
4. The expected commit ID must exactly match the last verified snapshot. An empty genesis copy is allowed only when the source selection is a complete generation-zero snapshot and the expected ID is all zeroes.
5. The destination must not exist and must differ from the source. The source is held under a shared file lock for the full scan and copy operation.

## Copy and publication protocol

1. Capture the selected source Superblock and physical file size while holding a shared lock.
2. Re-scan the historical prefix lazily and validate its OUTBOX chain.
3. Reconstruct the destination Superblock pair:
   - slot G % 2: the last verified snapshot, generation G;
   - the other slot: its immediate predecessor, generation G-1;
   - for G=1, the predecessor is genesis; for G=0, both slots are genesis copies.
4. Create a temporary file in the destination directory. The file is mode 0600 and is not visible at the destination path.
5. Copy only physical pages 2 through the last verified COMMIT page. Bytes after the verified boundary, including orphan tail bytes and damaged later-generation pages, are excluded.
6. Sync the immutable page bytes and read every copied page back, comparing it with the source and validating its page envelope.
7. Write both Superblock slots with correct physical slot IDs, sync them, read them back, and decode/compare both.
8. Close the temporary file and publish it using a same-directory hard link. The link operation is atomic and fails if the destination path already exists; it does not have os.replace-style overwrite behavior.
9. Remove the temporary name and fsync the parent directory.

## Failure semantics

- Before publication: the source is unchanged; the temporary file is removed on a best-effort basis; the destination path is not intentionally created.
- An existing destination is never overwritten. A race that creates the path before publication is rejected by the atomic no-replace hard link.
- If publication succeeded but unlink/final directory fsync fails, JPVRRecoveryCopyUncertainError reports the path, generation and commit ID. The destination may already exist and must be inspected before a retry. Do not delete it blindly.
- Filesystems that do not support hard links cannot use this publication path; the implementation fails rather than falling back to an overwrite-capable rename.
- The operation does not mutate source Superblocks, source pages, or source orphan-tail bytes.

## Result

JPVRRecoveryCopyResult reports destination path, recovered Superblock, source active generation, source verified generation, first source issue and number of physical bytes beyond the selected source durable tail. rolled_back indicates that the recovered copy stops before the selected source generation. This status does not imply that the source was rolled back.

The method returns only after destination file readback and parent-directory fsync complete. A raised JPVRRecoveryCopyUncertainError means publication may already have occurred.

## Tests

```sh
python3 -m unittest tools.jpvr_reference.test_recovery tools.jpvr_reference.test_recovery_copy -v
```

The recovery-copy tests cover: copy of a previous verified prefix after later-page corruption; unchanged source bytes; correct active/previous Superblock slots; full latest-generation copy; orphan-tail exclusion; existing destination refusal; exact and malformed commit IDs; OUTBOX failure refusal; source/destination identity refusal; cleanup when no-replace publication fails; refusal when neither source Superblock slot can be selected; and uncertain publication after directory-fsync failure. One additional scanner test confirms OUTBOX validation for a prefix preceding later corruption.

All thirteen newly added tests are **AUTHORED, NOT RUN**. No Python tests, syntax/import checks, filesystem fault injection, hard-link behavior, Android/Termux measurement, builds, CI or GitHub Actions were run.

## Explicit non-goals

- In-place Superblock rollback or rewrite.
- Truncating, repairing, or reclaiming pages in the source.
- Automatic recovery without an explicit destination and expected commit ID.
- Reconstructing semantic JNVM/neuron state from application-level records.
- Compaction, free-page reuse, CQE OUTBOX delivery/ACK, or exactly-once external side effects.
- A hardware-level power-loss guarantee or proof that a target Android filesystem supports the required semantics.
