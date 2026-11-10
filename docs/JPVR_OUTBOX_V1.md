# JPVR v1 — OUTBOX immutable event reference codec

**Status:** JOUT v1 payload/page codec, genesis validator, adjacent sequence validator and source-COMMIT binding validator are implemented as Python reference code. The user reports that the then-current 11-module JPVR suite passed after source-COMMIT binding was added (242 test methods at that time; the exact command/count/duration are not present in the chat). Four new genesis-validator tests are authored but not yet run; the OUTBOX module now has 50 methods (46 in the user-reported passing set + 4 new tests). This is not a publisher, acknowledgement store, durable worker, or recovery engine.

## Purpose and atomicity boundary

An OUTBOX page stores one immutable event descriptor created as part of a state-changing JPVR transaction. Its source COMMIT is in the same generation and physically follows the OUTBOX page. This encodes the key visibility rule: an event must not be published until its transaction is confirmed by the active durable COMMIT/superblock path.

Delivery is at-least-once. A consumer must deduplicate using event_id (and the caller's idempotency contract); this codec does not provide exactly-once delivery or perform delivery.

## JOUT v1 payload

The eight-byte little-endian prefix is:

| Offset | Size | Field | Value |
|---:|---:|---|---|
| 0 | 4 | magic | `JOUT` |
| 4 | 2 | version | 1 |
| 6 | 2 | prefix_bytes | 8 |

The prefix is followed by deterministic 8-byte-aligned TLV fields:

| ID | Wire type | Field | Required |
|---:|---|---|---|
| 1 | ID128 | event_id | yes |
| 2 | ID128 | idempotency_key | yes |
| 3 | ID128 | operation_id | yes |
| 4 | UTF8 | event_type | yes |
| 5 | BYTES | event_payload | yes |
| 6 | U64 | sequence | yes |
| 7 | ID128 | commit_id | yes |
| 8 | BYTES[16] | commit_ref (page_no u64 + generation u64) | yes |
| 9 | BYTES[16] | previous_event_ref | no |

All identifiers are nonzero 128-bit values. sequence starts at 1. event_type is non-empty UTF-8 of at most 128 encoded bytes. event_payload is opaque bytes of at most 3,500 bytes. The codec rejects a payload that cannot fit in one 4-KiB page, malformed TLV, unknown mandatory fields, invalid known OPTIONAL flags, nonzero padding, and malformed PageRefs.

## Page ordering and sequence validation

- Every OUTBOX page has a nonzero transaction_id.
- Its commit_ref generation must equal the OUTBOX page's generation.
- The source COMMIT page must physically follow the OUTBOX page.
- previous_event_ref, when present, must point to an earlier page and cannot have a newer generation.
- validate_outbox_sequence(previous, current) checks strictly increasing page numbers, non-regressing generations, exact previous-entry linkage, contiguous sequence numbers, adjacent event_id/idempotency_key uniqueness, same-generation source commit_id/PageRef consistency, and transaction_id behavior, a changed commit_id for each new generation, and prior-COMMIT page ordering across generation boundaries. It also validates the supplied decoded-page structures before comparing them.
- Sequence validation is an adjacent-pair check. A caller must scan/validate the full chain and use the durable manifest high-water mark before treating a sequence as complete.

## Source COMMIT binding

`validate_outbox_commit_reference(outbox_page, commit_page_bytes)` decodes the exact COMMIT page named by the event's commit_ref and cross-checks:
- physical COMMIT page number against commit_ref.page_no;
- COMMIT generation against both the PageRef and OUTBOX page generation;
- transaction_id against the OUTBOX page envelope;
- commit_id and operation_id against the immutable event descriptor.

It returns the decoded COMMIT descriptor. This is a linkage check, **not** proof that the COMMIT is active or durable, that the event is reachable from the MANIFEST's OUTBOX root, or that the event is within its committed high-water mark. Before publication, a future reader must also validate the COMMIT chain, MANIFEST, active Superblock, root reachability and OUTBOX high-water mark.

## API

- encode_outbox_payload / decode_outbox_payload — JOUT v1 deterministic TLV codec.
- encode_outbox_page / decode_outbox_page — PageType.OUTBOX envelope and source-COMMIT ordering.
- validate_outbox_genesis — requires the first event to have sequence 1 and no predecessor reference. The caller must anchor scanning at the committed MANIFEST root/high-water mark; this helper cannot prove that an earlier reachable page was not omitted.
- validate_outbox_sequence — validates one adjacent pair in the append-only event sequence.
- validate_outbox_commit_reference — checks the OUTBOX event against the actual referenced COMMIT descriptor.

## Explicit limitations

- This is an immutable event codec, not delivery state. Subscriber acknowledgements, retry scheduling, retention, compaction, and subscriber-specific cursors are not implemented.
- No global event_id/idempotency-key uniqueness index exists; the adjacent validator only catches immediate duplicates.
- No file/container I/O, page allocator/reservation, locking, flush/sync, superblock publication, transaction assembly, publisher, or crash recovery exists.
- Even a successful source-COMMIT binding check does not prove active durability or manifest reachability. A future reader must validate the COMMIT chain, MANIFEST OUTBOX root, high-water mark and active Superblock before publication.

## Tests

Run:

```sh
python3 -m unittest tools.jpvr_reference.test_outbox -v
```

Then run the complete JPVR test command in README.md. The user subsequently reported that all tests passed after the OUTBOX source-COMMIT change; the available historical baseline is **196/196 PASS** for the earlier ten-module set, and the 11-module JPVR source set at the time contained 242 methods. The current source contains **246 methods across eleven modules**: 46 OUTBOX cases are in the user-reported passing set and the four new genesis-validator tests are **AUTHORED, NOT RUN**. No build, CI, or GitHub Actions workflow was started.
