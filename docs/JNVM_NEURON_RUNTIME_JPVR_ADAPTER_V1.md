# JNVM ↔ JPVR neuron-runtime checkpoint adapter V1

## Status and scope

Implemented as a Python reference adapter for the existing one-neuron NeuronRuntime. The adapter converts a complete runtime snapshot into one versioned JPVR application record and can reconstruct the runtime from its decoded JREC/BLOB payload. Its focused tests are AUTHORED, NOT RUN.

This is not yet a complete JNVM persistence service. It does not select the latest checkpoint through the neuron INDEX, allocate record versions automatically, call JPVRStorage.commit() itself, restore an entire NetworkRuntime, persist pending network events/connections, or persist an external Reward Baseline Manager. The caller still assembles and commits the typed transaction.

## Record contract

- Record type: 0x0101.
- Schema ID: 0x4A4E564D (ASCII JNVM).
- Schema version: 1.
- Object ID: first 16 bytes of SHA-256 over the domain-separated prefix JARVIS:JNVM:NEURON-RUNTIME:V1 followed by strict UTF-8 neuron_id.
- Record version: caller-supplied, non-zero unsigned 64-bit version.
- Payload: canonical UTF-8 JSON with sorted keys, compact separators, finite numbers only, and an exact versioned key set. The JPVR JREC/BLOB envelope provides the outer integrity checks.

The payload includes neuron identity, clock epoch ID, learning rule, processed-batch counter, all NeuronParams/SynapseParams/RewardParams, the full NeuronState, and every SynapseState sorted by synapse ID. That includes STDP pre/post traces, eligibility value, eligibility context ID, and its start/update times.

The required non-zero 128-bit clock_epoch_id explicitly binds every persisted model timestamp to one logical clock domain. A process restart must resume that same logical epoch and model-time timeline; it must not silently compare old timestamps to a newly reset operating-system monotonic clock. Epoch scheduling/rebasing remains the caller's responsibility.

## API

- encode_neuron_runtime_snapshot(neuron_id, runtime, clock_epoch_id=...) returns the logical payload bytes.
- decode_neuron_runtime_snapshot(payload) validates the full schema and reconstructs a new NeuronRuntime.
- make_neuron_runtime_record_input(...) chooses DataRecordInput (JREC/DATA) when the payload fits the small-record limit, otherwise BlobRecordInput (BLOB).
- append_neuron_runtime_checkpoint(request, ...) returns a new TypedTransactionRequest with the record appended. It does not mutate the caller's request and rejects duplicate neuron checkpoints in the same transaction.
- decode_neuron_runtime_record(decoded_record) validates record type/schema/object identity and reconstructs the checkpoint.

The decoder rejects duplicate JSON keys, missing/unknown fields, invalid UTF-8, non-finite numbers, unsupported versions, invalid neuron/synapse IDs, malformed clock epochs, out-of-range counters and any runtime state rejected by the Numerical Oracle validation rules.

## Persistence and recovery boundaries

The adapter uses the existing typed transaction builder; new checkpoint records remain immutable and are committed with the transaction's COMMIT/MANIFEST. A successful adapter call only creates an in-memory transaction input. Durable persistence occurs only after the caller builds a valid plan and JPVRStorage.commit(plan) returns confirmed success.

The neuron INDEX locator/update contract is not implemented here. A caller must preserve the object ID and monotonically advance record_version, while a later adapter stage will atomically publish and resolve a versioned checkpoint locator through the COW index.

The snapshot stores model timestamps exactly as supplied, plus the required clock epoch. It does not store a wall-clock timestamp or claim that model time equals OS monotonic time. It does not capture external reward baselines, network topology, pending event queues, task sessions, CQE inbox/outbox state, or in-flight side effects.

## Tests

Run the focused suite on Termux:

    python3 -m unittest tools.neuron_runtime.test_jpvr_adapter -v

Seventeen test methods cover exact runtime/configuration round-trip, active R-STDP eligibility recovery, deterministic serialization, DATA-vs-BLOB selection, identifier/epoch/version validation, duplicate JSON keys, schema mismatch, non-finite state rejection, object-ID binding, typed transaction integration for DATA and BLOB, duplicate checkpoint rejection, and processed-batch counter bounds.

All tests are AUTHORED, NOT RUN. No Python tests, imports/syntax checks, runtime benchmarks, builds, CI, GitHub Actions, APK/AAB or Android filesystem measurements were performed.

## Follow-on indexed persistence layer

The originally planned locator and storage facade have since been implemented separately in `tools/neuron_runtime/jpvr_store.py`. See [JNVM_NEURON_CHECKPOINT_STORE_V1.md](JNVM_NEURON_CHECKPOINT_STORE_V1.md) for the fixed-size neuron INDEX locator, atomic checkpoint-plus-locator COMMIT, automatic record-version progression and validated load protocol. This adapter remains the lower-level record codec/typed-input layer; it does not itself perform storage I/O.

The implementation also permits a delayed R-STDP reward to advance a synapse's trace timestamps beyond the neuron's last membrane update. Both remain in the same caller-supplied clock epoch, but they are independent state clocks and are restored exactly.
