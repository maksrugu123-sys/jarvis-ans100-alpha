# JPVR v1 — OUTBOX immutable event reference codec

**Status:** JOUT v1 payload/page codec and adjacent sequence validator implemented as a Python reference slice on 9 November 2026. The 36 initial focused tests plus one package-export regression and two cross-generation COMMIT identity tests (39 total) are authored but not run. This is not a publisher, acknowledgement store, durable worker, or recovery engine.

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

## API

- encode_outbox_payload / decode_outbox_payload — JOUT v1 deterministic TLV codec.
- encode_outbox_page / decode_outbox_page — PageType.OUTBOX envelope and source-COMMIT ordering.
- validate_outbox_sequence — validates one adjacent pair in the append-only event sequence.

## Explicit limitations

- This is an immutable event codec, not delivery state. Subscriber acknowledgements, retry scheduling, retention, compaction, and subscriber-specific cursors are not implemented.
- No global event_id/idempotency-key uniqueness index exists; the adjacent validator only catches immediate duplicates.
- No file/container I/O, page allocator/reservation, locking, flush/sync, superblock publication, transaction assembly, publisher, or crash recovery exists.
- A structurally valid OUTBOX page is not evidence that its source COMMIT is active or durable. A future reader must validate the COMMIT chain and active superblock before publication.

## Tests

Run:

```sh
python3 -m unittest tools.jpvr_reference.test_outbox -v
```

Then run the complete JPVR test command in README.md. At authoring time, the user has reported **196/196 PASS** on Android Termux for the previous ten-module JPVR suite. All 39 OUTBOX tests are **AUTHORED, NOT RUN**. No build, CI, or GitHub Actions workflow was started.
