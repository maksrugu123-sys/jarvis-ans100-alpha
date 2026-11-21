# JPVR v1 — Copy-on-Write B+ Tree Upsert Planner

**Status:** implemented as a pure in-memory Python reference planner on 9 November 2026. The user subsequently reran the corrected ten-module JPVR suite on Android Termux: **196/196 PASS** in 2.204 s, including the internal COW split regression. This is user-reported device verification, not evidence of durable storage.

## Scope

`tools/jpvr_reference/cow.py` provides single-key `cow_upsert_index` and batch `cow_apply_index_mutations` insert/replace planning over immutable JIDX v1 pages. Batch updates copy each affected path once and preserve untouched subtree references. The typed transaction builder invokes the batch API; `JPVRStorage` can validate and publish its generated pages.

Public API:

- `validate_index_tree(root, page_catalog, tree_id=..., durable_tail_page_no=...)`
- `cow_upsert_index(root, page_catalog, key, value, tree_id=..., durable_tail_page_no=..., first_page_no=..., commit_generation=..., transaction_id=...)`
- `cow_apply_index_mutations(root, catalog, mutations, tree_id=..., durable_tail_page_no=..., first_page_no=..., commit_generation=..., transaction_id=...)`\n- `CowIndexPlan`, `NewIndexPage` and `IndexTreeStats`

The input page catalog is treated as read-only. The planner builds a separate staged page list and a temporary merged catalog only for final validation. It does not mutate existing page bytes or the caller's mapping.

## Validation performed before an update

The full reachable tree is traversed before a write plan is created. Validation checks:

1. every referenced page exists, is an INDEX page, is within the supplied durable prefix, and has the exact PageRef generation;
2. a child page is not newer than its parent; child levels decrease by exactly one;
3. tree IDs match and no page is visited twice (cycles and shared child pages are rejected);
4. leaf keys and internal separators satisfy the JIDX codec's strict ordering/length rules;
5. each internal separator equals the minimum key in its right subtree, and adjacent child key ranges do not overlap;
6. an empty leaf is allowed only as the root of an empty tree.

The validator returns counts, height, key bounds and the reachable page-number set. This is a full-tree scan, not a constant-time root check.

## Copy-on-write algorithm

For a single key:

1. descend through the old immutable snapshot using lower-bound separators;
2. insert a new key or replace the existing value in the target leaf;
3. encode a replacement leaf; if it does not fit in one 4-KiB INDEX page, split it into two non-empty leaves;
4. copy each ancestor on the modified path, replacing only the child reference that changed;
5. split an overflowing internal node into two valid nodes with at least two child pointers each, promoting the right subtree's lower-bound key;
6. if the old root splits, create a new root one level higher;
7. allocate new page numbers contiguously, starting at `durable_tail_page_no + 1`, and encode every new page with the requested commit generation and transaction ID;
8. validate the resulting tree again and require every newly planned page to be reachable from its new root.

Unaffected subtrees retain their original PageRefs. The old root remains valid against the original catalog. A replacement of an existing key does not increase the entry count.

The planner chooses a legal split that minimizes the difference in encoded payload sizes. It does not promise minimum fill-factor or a future storage engine's exact split policy.

## Explicit limitations

- Batch insert/replace is supported for unique keys. Delete, merge, redistribution, minimum occupancy policy, bulk build and compaction are not implemented.
- Merge, redistribution, minimum occupancy policy, bulk build and compaction are not implemented.
- Page numbers are planned in memory; there is no file offset allocator, process lock or cross-process reservation.
- Returned page bytes are not written, flushed or synced. A `CowIndexPlan` is not a committed transaction and does not extend a real durable prefix.
- COW itself remains an in-memory page planner. The typed builder integrates it with MANIFEST/COMMIT assembly, and the separate `JPVRStorage` layer writes the validated append plan and publishes the inactive Superblock. Durable rollback after a published commit is not provided.
- The page catalog is an in-memory mapping, not a file-backed container. The planner's full-tree validation is O(number of reachable index pages).
- No performance, RAM, energy or Android-device measurements have been made.

These boundaries are intentional. The tree planner and file writer are separate layers. The file writer provides append ordering, locking, fsync barriers and two-slot recovery selection, but full historical scanning, compaction/free-page reuse and device-level power-loss guarantees remain unimplemented; see `JPVR_STORAGE_ENGINE_V1.md`.

## Tests

Run the focused COW suite:

```sh
python3 -m unittest tools.jpvr_reference.test_cow -v
```

Run the current fifteen-module JPVR reference suite using the command in README.md. The 19 new file-backed storage/recovery tests are **AUTHORED, NOT RUN** in the storage-engine implementation turn. The previous user-confirmed full suite and historical ten-module baseline do not verify the current file-backed layer. No build, Gradle task, CI workflow or GitHub Actions run was started.
