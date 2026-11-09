# JPVR v1 — Copy-on-Write B+ Tree Upsert Planner

**Status:** implemented as a pure in-memory Python reference planner on 9 November 2026. The 16 focused tests were included in the 141-test JPVR set that the user reported passing in Android Termux. This is user-reported verification, not an independent rerun here, and it does not claim durable storage.

## Scope

`tools/jpvr_reference/cow.py` adds one-key insert/replace planning over the immutable JIDX v1 page format. It creates a new root and new INDEX pages while preserving the previous snapshot.

Public API:

- `validate_index_tree(root, page_catalog, tree_id=..., durable_tail_page_no=...)`
- `cow_upsert_index(root, page_catalog, key, value, tree_id=..., durable_tail_page_no=..., first_page_no=..., commit_generation=..., transaction_id=...)`
- `CowIndexPlan`, `NewIndexPage` and `IndexTreeStats`

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

- One key is inserted/replaced per call; batch updates and delete are not implemented.
- Merge, redistribution, minimum occupancy policy, bulk build and compaction are not implemented.
- Page numbers are planned in memory; there is no file offset allocator, process lock or cross-process reservation.
- Returned page bytes are not written, flushed or synced. A `CowIndexPlan` is not a committed transaction and does not extend a real durable prefix.
- The COW planner is not integrated with the separate JCOM/JOURNAL reference codecs, MANIFEST candidate construction, inactive-superblock publication, crash recovery or rollback.
- The page catalog is an in-memory mapping, not a file-backed container. The planner's full-tree validation is O(number of reachable index pages).
- No performance, RAM, energy or Android-device measurements have been made.

These boundaries are intentional. This layer supplies and tests the immutable tree rewrite logic; a future container writer must provide exclusive allocation, ordered page writes, transaction/commit semantics, durability barriers and recovery before claiming persistence.

## Tests

Run the new focused suite:

```sh
python3 -m unittest tools.jpvr_reference.test_cow -v
```

Run the complete ten-module JPVR reference suite using the command in README.md. The earlier 141-test aggregate was user-reported PASS. The later full 195-test Termux run produced 192 PASS, 2 FAIL and 1 ERROR, including the internal COW split case; the COW correction is published but not yet rerun. The current source set contains 196 test methods after adding an INDEX encoder-depth regression test, which is AUTHORED, NOT RUN. No build, Gradle task, CI workflow or GitHub Actions run was started.
