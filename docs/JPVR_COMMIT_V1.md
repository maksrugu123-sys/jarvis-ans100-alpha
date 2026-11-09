# JPVR v1 — COMMIT descriptor reference codec

**Status:** JCOM v1 payload/page codec and in-memory snapshot cross-validator added on 9 November 2026. Its 20 focused tests are authored but have not yet been run. This is not a durable writer or recovery engine.

## Binary payload

A COMMIT page uses page type 7 and contains exactly one 112-byte little-endian JCOM v1 payload:

| Offset | Size | Field | Rule |
|---:|---:|---|---|
| 0 | 4 | magic | `JCOM` |
| 4 | 2 | version | 1 |
| 6 | 2 | header_bytes | 112 |
| 8 | 8 | generation | positive u64 |
| 16 | 16 | transaction_id | nonzero |
| 32 | 16 | commit_id | nonzero |
| 48 | 16 | operation_id | nonzero |
| 64 | 8 | previous_commit_page_no | 0 only for generation 1 |
| 72 | 8 | previous_commit_generation | 0 only for generation 1 |
| 80 | 8 | manifest_page_no | page number of this generation's MANIFEST |
| 88 | 8 | manifest_generation | must equal generation |
| 96 | 8 | durable_tail_page_no | must equal the COMMIT page number |
| 104 | 8 | reserved | zero |

The outer page header repeats transaction_id and generation; both copies must match the payload. COMMIT is the final page in the planned append prefix. The referenced MANIFEST and previous COMMIT must precede it; a previous COMMIT must precede the new MANIFEST.

Generation 1 has no previous COMMIT. Every later generation references exactly generation - 1. This is the initial reference profile's chain rule; it does not by itself prove that every historical generation is readable or that the underlying bytes reached durable storage.

## Validation API

- `encode_commit_payload` / `decode_commit_payload`: strict fixed-size JCOM payload codec.
- `encode_commit_page` / `decode_commit_page`: COMMIT page envelope and local ordering checks.
- `validate_commit_snapshot`: cross-checks COMMIT ↔ MANIFEST, the optional previous COMMIT, and optionally the active Superblock. When both Superblock and page catalog are supplied, it also delegates root/high-water checks to `validate_manifest`.

The caller must supply the actual encoded pages. Validation is limited to the supplied snapshot and does not scan a file or prove flush/sync ordering.

## Explicit non-goals

- No JOURNAL or OUTBOX record/page codec is added here.
- No DATA/BLOB/INDEX/MANIFEST/JOURNAL/OUTBOX page sequence is assembled.
- No page reservation, file I/O, process lock, write ordering, flush/sync, Superblock publication, crash recovery, rollback or orphan-page reclamation is implemented.
- A valid JCOM page is a structurally valid descriptor, not evidence of a durable transaction.

## Tests

Run:

```sh
python3 -m unittest tools.jpvr_reference.test_commit -v
```

Then run all nine JPVR modules as shown in README.md. The preceding 141 JPVR tests were reported by the user as passing; the 20 tests in `test_commit.py` are new and remain **AUTHORED, NOT RUN**. No build or CI task was started.
