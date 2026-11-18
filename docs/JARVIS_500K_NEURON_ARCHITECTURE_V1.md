# ANS-100: 500,000-neuron architecture and cognitive extensions

**Target:** 500,000 addressable logical neurons.  
**Status:** target and migration architecture specified; sparse cognitive sidecars are now connected to the Python in-memory network reference and single-container JPVR group checkpoint path. Current Python and Android network runtimes remain capped at 10,000. A 500,000-node runtime is NOT implemented or verified.

## 1. Non-negotiable architecture

- Keep the existing 40-byte F64 neuron hot-state payload and Numerical Oracle equations unchanged.
- Store optional cognitive state in versioned sparse sidecars; neurons that do not use a capability pay no per-neuron cost for it.
- Use integer neuron IDs (500,000 fits in signed 32-bit IDs) and sparse directed edges. Never allocate an N×N matrix.
- Keep topology, edge metadata, histories and cold neuron state outside the resident working set.
- Use JNVM demand paging, bounded LRU residency, event-driven scheduling and group commits.
- A timestamp's neuron changes, generated events, queue and network clock must become durable atomically.
- Do not infer that a 40-byte payload equals 40 bytes of actual RAM or full persisted cost.

## 2. 500,000 logical neurons is not 500,000 resident neurons

The 40-byte numerical payload alone totals 20,000,000 bytes (about 19.1 MiB) for 500,000 neurons. A 76-byte packed record estimate totals 38,000,000 bytes (about 36.2 MiB), before synapses, indexes, event queues, parameter banks, SQLite/JPVR pages, allocator overhead and application runtime objects. These are serialization arithmetic only, not measured RAM or disk usage.

The phone must keep only a bounded active subset resident. Start with the existing 128-state Android hot set and measure before increasing it. The 500,000 target means addressable/persistable capacity, not a requirement to load all states at once.

## 3. Capability modules

The first source-level sidecars are tools/neuron_runtime/neuron_features.py and app/src/main/java/org/jarvis/ans100/alpha/data/ANS100NeuronFeatures.kt. Both define matching bounded updates for the cognitive feature state. It defines deterministic bounded updates for adaptive sensitivity, compact memory trace, prediction error/predictive surprise, context similarity, confidence and uncertainty, plasticity modulation, inhibitory modulation, synaptic co-activation/predictive/reward traces, and four-valued evidence status: SUPPORTED / REFUTED / CONFLICTING / UNRESOLVED.

Boundaries:
- Confidence is an internal ranking signal, not a calibrated probability.
- The Python network adapter applies sensitivity as a bounded multiplier on incoming excitatory strength; it does not alter the canonical ALIF-DT threshold equation. Android parity and differential verification remain open.
- Sidecar traces do not silently modify canonical STDP/R-STDP weights.
- Inhibition suppresses a signal but does not delete competing hypotheses or evidence.
- UNRESOLVED is not the same as REFUTED.
- Python `NetworkRuntime` now optionally accepts per-neuron sidecars, applies sensitivity/inhibition to incoming excitatory strength, updates bounded feature/synaptic traces, and exports/restores the state. `JPVRNetworkJNVMStore` persists changed-neuron sidecars in the same group COMMIT as scheduler state. Android Kotlin runtime and dialog path remain unintegrated.

Functional roles should be assigned by population/role tables, not heavyweight Python object subclasses per neuron. Proposed roles: detector, memory, predictor, context, confidence, inhibitory and coordinator.

## 4. Migration stages

| Stage | Addressable neurons | Gate |
|---|---:|---|
| A | 10,000 | preserve current topology and data |
| B | 25,000 | indexed paging and sparse topology |
| C | 50,000 | bounded event fan-out and group-commit recovery |
| D | 100,000 | long-run storage compaction and resource profile |
| E | 250,000 | cold-state paging and restart recovery |
| F | 500,000 | full consistency, recovery and resource acceptance |

No stage is promoted because a constant was raised. Every stage needs deterministic initialization without allocating every runtime object; bounded resident cache and pending queue; sparse adjacency index and per-population degree limits; atomic timestamp commit/recovery; restart-safe topology and parameter versions; memory, storage, latency, CPU, thermal and flash-write measurements on the target device.

## 5. Required implementation work

1. Replace Python NetworkRuntime's dict ownership with a storage-backed indexed neuron provider while preserving the in-memory implementation as correctness reference.
2. Complete NetworkCheckpointStore on one JPVR container. Do not connect the existing 128-shard per-neuron write-back prototype directly to the scheduler.
3. Add population/role registries, compact numeric edge IDs, sparse adjacency paging and bounded fan-out.
4. Port the same versioned state and scheduling contract to Android SQLite. Keep its transaction as timestamp publication boundary.
5. [Python reference implemented; tests authored, NOT RUN] Add a versioned feature-state codec and integrate sidecar updates into candidate/commit/publish. Android parity remains open.
6. [Python reference implemented for sidecar-enabled neurons] Add an adapter that applies sensitivity and inhibitory modulation to incoming excitatory signal strength; baseline neurons without sidecars retain the original path. Android parity and an explicit feature-disable configuration remain open.
7. Integrate prediction and uncertainty with hypothesis generation/competition, not with a language model's answer text.
8. Add migration/recovery paths before changing existing database limits or schema.

## 6. Verification policy

Tests and resource experiments are specified but have not been run for this increment. Do not claim that 500,000 neurons are currently instantiated, that the Android app can process 500,000 neurons, or that any RAM saving/performance improvement has been measured. Held-out benchmark work remains paused unless requested separately.
