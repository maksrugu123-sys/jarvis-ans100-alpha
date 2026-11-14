# JARVIS JNVM Working Set V1

**Status:** compact-state codec and bounded Python LRU working-set are IMPLEMENTED at source level. Focused codec/working-set tests are AUTHORED, NOT RUN. No resource benefit, large-network behavior, or target-device suitability has been measured.

## Scope

This is a first executable reference layer for the JNVM idea. It connects the existing per-neuron JPVR COW-index checkpoint API to a bounded active working set. It is not yet a replacement for NetworkRuntime and it does not make the 10,000-neuron network scheduler lazy.

## Compact resident state

- tools/neuron_runtime/compact_state.py encodes each resident neuron as versioned JPCS V1 binary.
- All mutable numerical values are preserved as IEEE-754 binary64. The codec retains the complete membrane state, optional last-spike timestamp, all synapse weights/traces, both independent synapse clocks, R-STDP eligibility/context and processed-batch counter.
- Immutable parameter bundles are interned in a shared in-memory bank, identified by a 128-bit truncated SHA-256 content key. This is content deduplication, not a semantic parameter-version registry.
- IDs and R-STDP context strings remain in the resident codec. Rule-specific minimal synapse layouts and numeric handles are not introduced yet.
- The packed representation is an in-memory cache representation only. Durable JPVR records still use the existing canonical JSON JNVM V1 record codec. No disk-size reduction is claimed.

## JPVR backing and lookup

- ShardedJPVRNeuronStore maps stable neuron identities to 128 deterministic JPVR shard files.
- Each shard uses the existing JPVRNeuronCheckpointStore, neuron COW INDEX, immutable checkpoint records and record-version CAS.
- Each operation opens the required shard and closes it after load/save. The working-set manager does not retain 128 file descriptors.
- max_pages defaults to 4096 for the JPVR materialized page catalog. Shards are append-only under the current JPVR reference implementation. Page-catalog exhaustion, history growth and absent compaction/free-page reuse remain real limits; the manager surfaces persistence errors and does not silently discard dirty state.

## Active working set

- NeuronWorkingSet is single-writer and uses an LRU cache of packed neuron states. The default capacity is 64; the configurable hard ceiling is 10,000 resident neurons.
- get() loads a cold neuron from its deterministic JPVR shard. Successful activity moves the entry to the most-recently-used end.
- process_batch() and apply_reward() calculate on an isolated candidate, then replace the packed resident value and mark it dirty only after the candidate succeeds.
- flush_neuron() and flush_all() persist dirty states with JPVR record-version CAS.
- LRU eviction first commits dirty state. If storage fails, the old entry stays resident and admission is blocked by backpressure. The system never claims an uncommitted dirty state is safely evicted.
- An uncertain commit pins the entry. The caller must invoke reconcile_neuron() to compare the persisted snapshot and resident candidate before any retry.
- close() flushes all dirty entries before releasing the working set.

## Important transaction boundary

This V1 manager commits one neuron at a time. It does not make a batch across several neurons atomic, and it is not yet wired into NetworkRuntime event scheduling. A future network controller must coordinate candidate states for all neurons touched by one timestamp, persist the required group state through a multi-record transaction, and publish the timestamp only after commit. Do not route production network execution through these separate per-neuron commits yet.

## Platform boundary

- Python reference: available to desktop/Termux-side code, but not integrated into the current desktop Core/NetworkRuntime conversation pass.
- Android without Termux: unchanged. The native Kotlin conversation signal remains its current two-neuron in-process implementation with its app-private checkpoint.
- Termux remains an optional extension; the Android app must not require it to chat or run its local signal pass.
- Native Kotlin packed-state arena, native Android JPVR/JNVM backend, Android/PC numerical parity and device measurement are not implemented.

## Tests and validation

Authored:
- tools/neuron_runtime/test_compact_state.py: exact state round-trip for NONE/STDP/R-STDP; deterministic encoding; parameter deduplication; optional-field round-trip; truncation/trailing data; unknown version/rule; missing/mismatched parameter set.
- tools/neuron_runtime/test_working_set.py: LRU reload; dirty write-before-evict; storage backpressure; candidate isolation; uncertain-commit reconciliation; clock-epoch mismatch; real sharded JPVR round-trip in a temporary directory.

**AUTHORED, NOT RUN.** No tests, Python syntax/import checks, scaling benchmark, Android or PC runtime, Gradle build, APK/AAB, CI, or RAM/latency/battery/thermal measurement was executed for this increment.
