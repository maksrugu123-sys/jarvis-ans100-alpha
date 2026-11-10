# JPVR typed transaction builder V1

## Purpose

`tools/jpvr_reference/typed_transaction.py` turns typed JPVR inputs into one deterministic, validated in-memory append. It is the construction layer above the lower-level DATA/BLOB/INDEX/JOURNAL/OUTBOX codecs and `assemble_transaction`.

The builder returns a `TypedTransactionBuildPlan` containing the final `TransactionAssemblyPlan`, a MANIFEST and COMMIT descriptor, plus exact page-number ranges for each typed page family. It never modifies the caller's page catalog.

## Input model

- `DataRecordInput`: logical JREC fields and payload. Records are encoded and greedily packed into DATA pages; a JREC is never split across pages.
- `BlobRecordInput`: logical large-record fields and payload. Each record is encoded as a contiguous BLOB fragment run.
- `IndexPageInput`: an immutable `IndexNode`. Optional `root_name` publishes that page as one of the seven MANIFEST roots. Nodes must be supplied in child-before-parent physical order; child references must already contain intended page numbers/generations.
- `JournalEntryInput`: one `JournalEntry` plus optional `confirmed_no_commit` evidence. Consecutive revisions for the same operation in one request are linked automatically; supplied external predecessor refs must resolve in the catalog and pass the journal transition validator.
- `OutboxEvent`: event identity and sequence. The builder assigns the exact new COMMIT page reference, requires the new COMMIT identity, attaches the prior OUTBOX high-water event, and validates sequence continuity. Event and idempotency IDs must be unique within the new request.
- `index_roots` may explicitly replace selected MANIFEST roots. Unchanged roots and JOURNAL/OUTBOX high-water marks are inherited from the active snapshot. On genesis, all seven roots must be supplied by tagged INDEX pages or explicit page refs.

## Deterministic page order

1. DATA pages (JREC records greedily packed, without record splitting)
2. BLOB pages (one contiguous fragment run per logical record)
3. INDEX pages (caller-provided immutable nodes)
4. JOURNAL pages
5. OUTBOX pages
6. one MANIFEST page
7. one COMMIT page (always last)

The builder computes the final COMMIT page number before encoding JOURNAL and OUTBOX pages, so same-generation references point to the exact future COMMIT. The MANIFEST high-water values are set to the newest pages emitted by the request, or inherited unchanged when no new entries are emitted.

For a non-genesis append, the current active COMMIT and MANIFEST are loaded from the supplied page catalog and validated against the supplied Superblock before roots or high-water values are inherited. The final ordered bytes are then passed through `assemble_transaction`, which validates the proposed snapshot and inactive-Superblock candidate.

## API

```python
from tools.jpvr_reference import (
    BlobRecordInput,
    DataRecordInput,
    IndexPageInput,
    JournalEntryInput,
    TypedTransactionRequest,
    build_typed_transaction,
    validate_typed_transaction_plan,
)

plan = build_typed_transaction(request)
validate_typed_transaction_plan(plan, request)
```

Focused tests:

```sh
python3 -m unittest tools.jpvr_reference.test_typed_transaction -v
```

## Invariants and limits

The builder validates all generated page families with their existing codecs, checks DATA/BLOB round trips, validates supplied JOURNAL transitions, validates OUTBOX sequence links and source-COMMIT references, and runs the complete transaction assembly/snapshot validator before returning a plan.

It does **not** build or split B+ trees from key/value mutations, invoke the COW upsert planner automatically, prove that every key in an INDEX tree is reachable, or inspect the entire historical OUTBOX sequence for duplicate IDs. For that last check, use the committed-history scanner with a complete page catalog. Callers that provide internal INDEX nodes are responsible for their child PageRefs and node ordering; the MANIFEST validator checks each declared root page but does not traverse every root tree.

This remains a pure in-memory reference builder. It does not reserve or lock physical page numbers, write the container, flush/fsync, publish either Superblock slot, arbitrate torn slots, recover after crashes, or deliver OUTBOX events. A successful plan is not a durable commit and is not evidence of production readiness.
