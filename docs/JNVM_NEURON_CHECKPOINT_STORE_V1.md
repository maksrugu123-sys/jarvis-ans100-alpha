# JNVM one-neuron JPVR checkpoint store V1

## Status

Implemented as a bounded Python reference persistence facade over JPVRStorage, the existing JNVM runtime record codec and the neuron copy-on-write INDEX. The focused store suite contains 15 test methods; those tests plus the new delayed-R-STDP timestamp regression are **AUTHORED, NOT RUN**. This is not production JNVM and has not been verified on Android storage.

## On-disk locator contract

The neuron INDEX is keyed by the stable 16-byte object ID returned by neuron_runtime_object_id(neuron_id). Its value is a fixed 100-byte little-endian locator:

| Field | Width | Meaning |
|---|---:|---|
| magic | 4 | `JNLR` |
| locator_version | 2 | 1 |
| storage_kind | 1 | 1 = DATA/JREC, 2 = BLOB |
| reserved | 1 | Must be zero |
| record_type | 2 | 0x0101 (JNVM runtime checkpoint) |
| schema_version | 2 | 1 |
| schema_id | 4 | 0x4A4E564D |
| object_id | 16 | Stable neuron object identity |
| record_version | 8 | Monotonically increasing non-zero u64 |
| first_page_no | 8 | First immutable record page |
| page_count | 4 | DATA = 1; BLOB = 1..8192 |
| record_generation | 8 | COMMIT generation that wrote the record |
| commit_page_no | 8 | COMMIT page that owns the record |
| commit_id | 16 | Exact source COMMIT identity |
| transaction_id | 16 | Exact source transaction identity |

The decoder rejects unknown format versions, non-zero reserved flags, unsupported JNVM schema metadata, zero identities, invalid page ranges, unsupported DATA/BLOB lengths and malformed values. Load validates the complete neuron B+ tree before resolving a locator and confirms the source COMMIT's durable tail equals its physical COMMIT page. The fixed-size value stays below the JPVR INDEX value limit.

## Save protocol

Use `JPVRNeuronCheckpointStore(storage)` with an open JPVRStorage.

- On a genesis container, the first save creates seven deterministic empty INDEX roots in a separate initialization COMMIT. This is schema initialization only; it does not contain a neuron checkpoint.
- The store reads one bounded, lock-consistent `JPVRStorage.load_snapshot(max_pages=...)` result. The default limit is 4096 pages (16 MiB encoded page bytes plus Python object/mapping overhead).
- It looks up the current neuron locator and validates the referenced prior record before incrementing record_version. An optional `expected_record_version` provides compare-and-swap behavior; use 0 when the caller expects no prior checkpoint.
- It builds the versioned JREC/DATA or BLOB record, plans its page range, then performs a fixed-size locator planning pass so the locator includes the final COMMIT page number.
- It invokes one JPVRStorage.commit() for the new checkpoint record and the neuron COW INDEX upsert. They are visible together or neither is published.
- JPVRStorage.commit rejects a stale base snapshot. Concurrent writers must reload and prepare a new save rather than reusing the old plan.

The return value includes the record version, page range, COMMIT identity/generation and whether this call initialized the index roots. A `JPVRCommitUncertainError` still means the caller must inspect the active Superblock/commit identity before deciding what happened; do not blindly repeat an uncertain operation.

## Load protocol

`load(neuron_id)` reads the active Superblock and its bounded page catalog under one shared lock, resolves the stable object ID through the active neuron INDEX root, decodes the locator and walks the active COMMIT predecessor chain to prove the locator's source COMMIT is an ancestor of the active snapshot.

It then verifies:
- exact source COMMIT page, generation, commit_id and transaction_id;
- the checkpoint page range is within the durable prefix and precedes its owning COMMIT;
- DATA page or complete contiguous BLOB run has matching generation/transaction identity;
- JREC/BLOB type, schema, object ID and record version match the locator;
- page and full-record CRCs and the inner neuron snapshot schema;
- the decoded logical neuron ID matches the requested INDEX key.

Only after these checks does it reconstruct a new NeuronRuntime. A missing key is reported as `JPVRNeuronCheckpointNotFound`; malformed locator/identity metadata is rejected rather than guessed or repaired.

## Model-clock correction

Neuron membrane time and each synapse's STDP/R-STDP trace timestamps are state-specific clocks in the same supplied clock_epoch_id, not a rule that every trace timestamp must be less than the last membrane update. A delayed R-STDP reward can legitimately advance synapse trace timestamps while the membrane remains at its last signal-processing time. Runtime construction and serialization now preserve that state; the next learning update is still required to obey the Oracle's per-synapse monotonic-time checks. This change does not rebase time or choose a new clock epoch.

## API

- `JPVRNeuronCheckpointStore(storage).save(neuron_id, runtime, clock_epoch_id=..., expected_record_version=None, max_pages=4096)`
- `JPVRNeuronCheckpointStore(storage).load(neuron_id, max_pages=4096)`
- `encode_neuron_checkpoint_locator(locator)` and `decode_neuron_checkpoint_locator(value)`
- Result models: `SavedNeuronCheckpoint`, `LoadedNeuronCheckpoint`, `NeuronCheckpointLocator`

## Boundaries and non-goals

This version persists one NeuronRuntime at a time. It does not persist NetworkRuntime topology, pending events, network clock/queue, an external reward-baseline manager, CQE state, task sessions or in-flight side effects. Clock epoch continuity/rebasing remains the caller/scheduler's responsibility. The implementation materializes a bounded page catalog; it is not a streaming large-container checkpoint service. It does not add compaction, page reclamation or in-place repair. POSIX locking/fsync and Android flash/FUSE behavior have not been measured or certified.

## Tests and verification

Run on Termux:

    python3 -m unittest tools.neuron_runtime.test_jpvr_store -v

Seventeen focused tests cover fixed locator encoding/rejection, genesis index initialization, DATA save/load, reopen, version update, stale expected-version refusal, BLOB round-trip, neuron-key isolation, missing checkpoint, future R-STDP reward timestamps, corrupted page rejection, bounded catalog refusal and preflight validation.

All 17 store tests and the additional adapter regression are **AUTHORED, NOT RUN**. GitHub readback/static review only for this stage. No Python tests, import/syntax checks, Android I/O, benchmarks, builds, CI, GitHub Actions, APK or AAB were run.
