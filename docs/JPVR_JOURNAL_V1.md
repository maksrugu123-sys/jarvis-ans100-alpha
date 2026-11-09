# JPVR v1 — JOURNAL operation-state reference codec

**Status:** JNRL v1 payload/page codec and append-only transition validator implemented as a Python reference layer on 9 November 2026. The 34 focused tests are authored but have not been run. This is not a durable journal writer or recovery engine.

## Scope and page model

One `PageType.JOURNAL` page contains exactly one immutable operation-state entry. A later state revision is appended on a new page and points to the previous entry for the same logical operation. The physical page header supplies `page_no`, `commit_generation` and `transaction_id`, and its CRC32C protects the entire page. The payload does not duplicate those outer-header fields.

The eight-byte payload prefix is little-endian:

| Offset | Size | Field | Rule |
|---:|---:|---|---|
| 0 | 4 | magic | `JNRL` |
| 4 | 2 | version | 1 |
| 6 | 2 | prefix_bytes | 8 |

The prefix is followed by deterministic, 8-byte-aligned TLV fields:

| Field ID | Wire type | Field | Required |
|---:|---|---|---|
| 1 | ID128 | operation_id | yes |
| 2 | ID128 | idempotency_key | yes |
| 3 | ID128 | owner_module_id | yes |
| 4 | U8 | status | yes |
| 5 | U64 | expected_state_version | no |
| 6 | U64 | new_state_version | no |
| 7 | ID128 | commit_id | no |
| 8 | BYTES[16] | commit_ref (page_no u64 + generation u64) | paired with 7 |
| 9 | BYTES[16] | previous_entry_ref (page_no u64 + generation u64) | no |
| 10 | ID128 | result_object_id | no |

All IDs must be nonzero 128-bit values. The owner is the registered module identity, not an arbitrary caller-supplied domain string. Unknown optional TLV fields are skipped; unknown mandatory fields, duplicate/out-of-order IDs, invalid wire types, noncanonical padding and invalid known OPTIONAL flags are rejected.

## Operation status values

| Value | Status |
|---:|---|
| 1 | QUEUED |
| 2 | RUNNING |
| 3 | PREPARED |
| 4 | COMMITTED |
| 5 | COMPLETED |
| 6 | DEFERRED |
| 7 | REJECTED |
| 8 | FAILED |
| 9 | CANCELLED |
| 10 | COMMIT_UNKNOWN |

`ACCEPTED` is a queue acknowledgement, not an operation lifecycle state. `DEFERRED` means NCC did not accept the operation for execution; a later retry retains the same operation/idempotency identity.

`new_state_version`, when present, requires `expected_state_version` and must be strictly greater. COMMITTED and COMPLETED require both `commit_id` and `commit_ref`. COMMIT_UNKNOWN may have both or neither while the outcome is being resolved. Other statuses cannot carry a commit reference. `result_object_id` is limited to COMPLETED, REJECTED or FAILED entries.

A `previous_entry_ref` must point to an earlier page and cannot reference a later generation. A same-generation `commit_ref` points forward to a COMMIT page later than the JOURNAL page (COMMIT terminates that append prefix); a prior-generation COMMIT must be earlier than the current JOURNAL page.

## Transition validator

`validate_journal_transition(previous, current)` checks an adjacent revision pair:

- page numbers strictly increase and commit generations do not regress;
- the current entry references the exact previous JOURNAL page;
- operation_id, idempotency_key and owner_module_id remain unchanged;
- expected_state_version stays fixed and an assigned new_state_version cannot change;
- commit identity and result_object_id cannot be rewritten after assignment;
- pages in one generation share transaction_id; a later generation uses a new transaction_id;
- only the specified lifecycle transitions are accepted.

Allowed transitions:

- DEFERRED → QUEUED or REJECTED
- QUEUED → RUNNING, REJECTED, FAILED or CANCELLED
- RUNNING → PREPARED, REJECTED, FAILED or CANCELLED
- PREPARED → COMMITTED, FAILED, CANCELLED or COMMIT_UNKNOWN
- COMMIT_UNKNOWN → COMMITTED or FAILED; resolving to FAILED requires the caller to pass `confirmed_no_commit=True` after an authoritative no-commit check
- COMMITTED → COMPLETED

COMPLETED, REJECTED, FAILED and CANCELLED are terminal in this initial profile. COMMIT_UNKNOWN can only be resolved after checking the durable commit state; it is not permission to re-execute the operation.

## API

- `encode_journal_payload` / `decode_journal_payload` — JNRL v1 TLV payload codec.
- `encode_journal_page` / `decode_journal_page` — PageType.JOURNAL envelope and physical PageRef ordering checks.
- `validate_journal_transition(previous, current, confirmed_no_commit=False)` — checks a state transition between two decoded JOURNAL pages.

## Explicit limitations

- The codec does not maintain a global operation_id/idempotency_key uniqueness index or guarantee deduplication by itself.
- It does not scan the full journal chain, validate every referenced COMMIT page against a container, or implement journal retention/checkpoint policy.
- It does not encode durable result payloads; result_object_id is only an object identity that a future index/RecordLocation layer must resolve.
- No OUTBOX codec, complete transaction page-sequence planner, page allocator/lock, file I/O, flush/sync, superblock publication, crash recovery, rollback or orphan-page handling is implemented.
- A valid JOURNAL page is not evidence that its transaction was published or durably committed.

## Tests

Focused suite:

```sh
python3 -m unittest tools.jpvr_reference.test_journal -v
```

Run the complete JPVR reference set using the ten-module command in README.md. The preceding 141 tests (through COW) are user-reported as passing in Termux. The 20 COMMIT tests and 34 JOURNAL tests are **AUTHORED, NOT RUN**. No build, Gradle task, CI workflow or GitHub Actions run was started.
