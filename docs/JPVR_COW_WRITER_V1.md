# JPVR v1 — Copy-on-Write B+ Tree Upsert Planner

**Status:** implemented as a pure in-memory Python reference planner on 9 November 2026. The 16 focused tests are authored but have not yet been run. This document does not claim a durable storage implementation.

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
- No JOURNAL, COMMIT page, MANIFEST update, inactive-superblock write, crash recovery or rollback protocol is implemented here.
- The page catalog is an in-memory mapping, not a file-backed container. The planner's full-tree validation is O(number of reachable index pages).
- No performance, RAM, energy or Android-device measurements have been made.

These boundaries are intentional. This layer supplies and tests the immutable tree rewrite logic; a future container writer must provide exclusive allocation, ordered page writes, transaction/commit semantics, durability barriers and recovery before claiming persistence.

## Tests

Run the new focused suite:

```sh
python3 -m unittest tools.jpvr_reference.test_cow -v
```

Run all eight JPVR reference test modules:

```sh
python3 -m unittest \
 tools.jpvr_reference.test_codec \
 tools.jpvr_reference.test_records \
 tools.jpvr_reference.test_blob \
 tools.jpvr_reference.test_data \
 tools.jpvr_reference.test_layout \
 tools.jpvr_reference.test_manifest \
 tools.jpvr_reference.test_index \
 tools.jpvr_reference.test_cow -v
```

The user reported that the previous 125 JPVR tests passed in Termux. The 16 tests in `test_cow.py` are new and remain NOT RUN until the user executes them. No build, Gradle task, CI workflow or GitHub Actions run was started.
