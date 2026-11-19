# Android JNVM: 250,000-neuron scaling plan

## Goal and status

The target architecture is a sparse network with up to 250,000 neuron records. The target is a structural capacity, not a promise that 250,000 neurons can all be active in RAM or that the current Android runtime already supports this count.

**Status: separate storage foundation implemented; full ANS runtime integration not yet implemented or verified.** The legacy AndroidSparseNeuronNetwork remains capped at 10,000 neurons, 100,000 connections and 100,000 pending events. A new independent AndroidJnvM250kStore now defines and initializes a separate 250,000-row SQLite neuron store, sparse edge storage, bounded paged reads and an atomic disk-backed event queue. This does not mean the full ANS cognitive runtime can yet execute across 250,000 neurons. Do not change the global legacy limit: several existing topologies use the current 10,000-neuron range as part of their stable ID layout.

## Existing architecture findings

- Neuron state is stored in SQLite. Only a bounded access-order LRU working set is held in memory.
- Connections are stored as sparse rows, with a source-neuron index. Outgoing connections are loaded for one source at a time; they are not represented by a dense N×N matrix.
- Events are stored in SQLite and fetched in bounded same-timestamp groups. The current timestamp group limit is 10,000 events and the pending queue limit is 100,000.
- The current MAX_NEURONS = 10,000 is also used by the legacy dialogue topology. That topology places 9,000 input neurons, 996 hidden neurons and four readout neurons at fixed ID ranges. Changing the global value would silently move the output range and invalidate existing ID assumptions.
- The hybrid V2 topology is also deliberately built around a fixed 10,000-neuron population and fixed population ranges. It must retain its own topology-specific size.
- The connection table originally had an index on source_id, but target-side incoming-edge counts and grouping queries had no matching index. Schema V13 adds connections_target_idx while preserving existing rows.

## Implemented 250k storage foundation

The branch now contains:
- `AndroidJnvM250kProfile`: independent profile ID/version, 250,000 neuron capacity, bounded page sizes, and explicit graph/event limits.
- `AndroidJnvM250kStore`: a separate SQLite database (`jarvis_ans100_jnvm_sparse_250k_v1.db`) with no reuse of the legacy dialogue database.
- Initialization inserts neuron rows in batches of 1,000 and can resume after interruption. Repeated initialization returns early when the full initialized range is present.
- Neuron state is updated directly in SQLite. Neuron pages are read with bounded keyset pagination.
- Sparse connections are stored as rows, indexed by source and target, and read in bounded pages.
- Per-neuron degree limits are 16 outgoing and 32 incoming connections. These are conservative engineering bounds for the first storage profile, not measured optimal values. They cap theoretical outgoing edges at 4,000,000 across 250,000 neurons.
- Delayed events are stored in SQLite with a time index. Pending queue capacity is 100,000; one enqueue call is limited to 512 events; one timestamp group is limited to 512 events.
- Timestamp commit validates that the complete persisted group is consumed, then atomically deletes consumed events, writes changed neuron states, inserts generated events and advances the model clock.

Not yet implemented: the 250k scheduler/compute loop, hot-set LRU integration for this new database, feature-state persistence, population/specialization layout, learning/plasticity integration, CPU/GPU backend routing, settings UI selection and device-level performance measurements. The existing 1/2/4/8 MiB RAM preference currently governs the legacy network cache only; it must not be described as controlling the new 250k profile until the runtime integration is complete.

## Required design before enabling 250,000

### 1. Separate structural capacity from topology identity

Introduce an explicit network descriptor containing:
- stable network/topology ID and version;
- structural neuron capacity;
- connection capacity and sparse-degree policy;
- reserved ID ranges for each population;
- storage format version.

Legacy dialogue and hybrid V2 must continue using their existing 10,000-neuron ID layouts. A new 250,000-neuron network must use a separate descriptor and a separate database identity. Never infer a topology's size from a global constant.

### 2. Keep all-neuron state disk-backed

- SQLite remains authoritative for inactive neuron state, feature state, connections and delayed events.
- RAM holds only the configured JNVM hot set and bounded per-run working buffers.
- Do not allocate a 250,000-element Kotlin object array, map, or adjacency list at startup.
- Do not load all connections or all pending events into memory.
- Use bounded keyset/paged reads for diagnostics, maintenance and graph inspection.
- Keep timestamp commits atomic: consumed events, changed states, generated events and model clock commit together.

### 3. Bound sparse connectivity explicitly

The current global limit of 100,000 connections is too low to describe a 250,000-neuron network with useful sparse connectivity. However, increasing it to an arbitrary multi-million number is unsafe: the current outgoing() method materializes all outgoing connections for one neuron, and each spike can generate events.

Before choosing a global edge limit:
- define and enforce a maximum outgoing degree per neuron;
- define a maximum incoming degree or population-specific convergence rule;
- size the delayed-event queue from measured fan-out and run budgets;
- prevent a single high-degree neuron from generating an unbounded in-memory event list;
- keep all edge mutations transactional and indexed by both source and target;
- benchmark SQLite storage size, insertion time, lookup latency and event throughput on the target Android device.

Do not choose a multi-million edge cap until these limits and measurements exist.

### 4. Preserve bounded event processing

Keep same-timestamp groups atomic and bounded. If a timestamp group exceeds the supported atomic limit, reject/backpressure it rather than splitting it into partial commits. For larger workloads, use smaller scheduling batches and bounded run budgets while preserving timestamp atomicity.

The event queue must apply backpressure before writes exceed its configured cap. A future disk-backed queue may be larger than RAM, but queue size must not imply loading the queue into RAM.

### 5. Migrate without damaging existing data

- Do not reinterpret the current dialogue or hybrid databases as the new 250k network.
- Create a new versioned database for the new topology.
- Validate neuron ID ranges, connection endpoints, topology metadata and sparse-degree constraints before accepting a migrated/imported graph.
- Keep schema upgrades additive and transactional.
- Provide a read-only compatibility path for legacy topologies until a deliberate migration is implemented.

## Safe implementation sequence

1. Add storage indexes and migration coverage needed by sparse lookups. (V13 currently adds the target index.)
2. Introduce topology-specific capacity descriptors without changing existing 10k behavior.
3. Add a separate 250k database initializer that writes neuron rows in bounded transactions/batches and never creates dense adjacency structures.
4. Add explicit per-neuron degree limits and connection admission/backpressure.
5. Refactor outgoing-edge retrieval and event generation to enforce the per-neuron and per-run memory bounds.
6. Add tests for 250,000 sparse neuron IDs, maximum-degree rejection, restart persistence, bounded queue behavior, and legacy topology compatibility.
7. Measure on the target device before enabling 250k by default or raising the user-selectable RAM budget.

## Verification rules

Until the above sequence is complete:
- report the runtime as **10,000-neuron capacity**, not 250,000;
- do not claim that the 250k topology is running;
- do not extrapolate full-network speed from the isolated LIF-DT kernel benchmark;
- keep Android builds, device benchmarks and heavy tests opt-in;
- distinguish authored tests from tests actually executed.
