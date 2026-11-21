# JPVR v1 Transaction Assembly Planner

## Status and scope

This document describes a pure in-memory reference planner. It assembles already encoded JPVR pages into one ordered append plan and validates the proposed COMMIT/MANIFEST snapshot against a caller-supplied current Superblock and page catalog.

It does **not** itself open/write a container or publish a Superblock. The separate file-backed writer in [JPVR_STORAGE_ENGINE_V1.md](JPVR_STORAGE_ENGINE_V1.md) now consumes its plan, provides file locking and fsync ordering, and selects the newest valid Superblock on reopen. A successful in-memory plan alone is not a durable transaction.

## API

`assemble_transaction(current_superblock, staged_page_bytes, commit_page_bytes, page_catalog, previous_commit_page_bytes=None)`

- `current_superblock`: validated current durable root.
- `staged_page_bytes`: non-empty ordered sequence of encoded pages before COMMIT. It must contain exactly one MANIFEST as its final staged page.
- `commit_page_bytes`: encoded COMMIT page, physically last.
- `page_catalog`: read-only mapping of already committed page numbers to encoded bytes. Entries must lie inside the current durable prefix and may not have a generation newer than the current snapshot.
- `previous_commit_page_bytes`: required for non-genesis commits and forbidden for genesis.

The planner checks page CRCs and physical page numbers, contiguous append numbering, next-generation metadata, common nonzero transaction ID, exactly one final MANIFEST, COMMIT as final page, COMMIT↔MANIFEST identity, previous-COMMIT linkage, manifest roots and high-water references, and the proposed inactive Superblock state.

The returned immutable `TransactionAssemblyPlan` carries the append plan, ordered page numbers and exact page bytes, transaction/commit identities, and the candidate next Superblock. `validate_transaction_assembly_plan` rebuilds the plan from its immutable page bytes and rejects modified metadata or stale base state.

## Page ordering contract

The caller prepares pages in physical append order. This planner deliberately does not reorder or generate DATA, BLOB, INDEX, CHECKPOINT, JOURNAL, or OUTBOX pages. The MANIFEST must be the final staged page and COMMIT must immediately follow it as the final durable page. The manifest validator resolves all seven required INDEX roots and any nonzero checkpoint/JOURNAL/OUTBOX high-water page references through the combined catalog.

## Failure behavior

All validation occurs against local copies. The planner never mutates the caller's page catalog or Superblock. Invalid, missing, duplicated, out-of-order, stale, or inconsistent input raises `JPVRFormatError`; no partial plan is returned.

## Verification

Run:

```sh
python3 -m unittest tools.jpvr_reference.test_transaction -v
python3 -m unittest tools.jpvr_reference.test_layout tools.jpvr_reference.test_manifest tools.jpvr_reference.test_commit tools.jpvr_reference.test_journal tools.jpvr_reference.test_outbox tools.jpvr_reference.test_outbox_scan tools.jpvr_reference.test_transaction -v
```

Tests in `test_transaction.py` cover genesis and second-generation assembly, final-page ordering, duplicate/missing MANIFEST, physical gaps, generation and transaction identity mismatches, missing previous COMMIT bytes, invalid catalog references, plan revalidation/tampering, and failure immutability.

Until the user runs these tests, the new tests are **AUTHORED, NOT RUN**. No build, CI, GitHub Actions, physical file I/O, or durability test is part of this stage.

## Remaining gates

1. Execute the COW-integrated builder and file-backed storage tests on Android Termux.
2. Add full historical COMMIT-chain scanning, richer crash/fault-injection coverage and recovery diagnostics.
3. Design compaction/free-page reuse and durable CQE OUTBOX delivery only after the append/recovery layer is verified.
