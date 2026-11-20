# Android JNVM: 250,000-neuron scaling plan

## Goal and status

The target architecture is a sparse network with up to 250,000 neuron records. The target is a structural capacity, not a promise that 250,000 neurons can all be active in RAM or that the current Android runtime already supports this count.

**Status: separate 250k LIF-DT runtime with pair-based STDP, bounded R-STDP and an in-memory episode-attribution bridge is implemented as an isolated prototype; app decision-lifecycle integration and device verification remain incomplete.** The legacy AndroidSparseNeuronNetwork remains capped at 10,000 neurons, 100,000 connections and 100,000 pending events. A new independent AndroidJnvM250kStore initializes a separate 250,000-row SQLite neuron store, sparse edge storage, persisted STDP/R-STDP traces and reward receipts, bounded paged reads and an atomic disk-backed event queue. This does not mean the full ANS cognitive runtime can yet execute across 250,000 neurons. Do not change the global legacy limit: several existing topologies use the current 10,000-neuron range as part of their stable ID layout.

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
- Delayed events are stored in SQLite with a time index. Pending queue capacity is 100,000; one enqueue call is limited to 512 events; one timestamp group is limited to 512 events. Generated fan-out is bounded at 8,192 events per timestamp (512 processed events × 16 outgoing edges per neuron).
- Timestamp commit validates that the complete persisted group is consumed, then atomically deletes consumed events, writes changed neuron states, inserts generated events and advances the model clock. Generated events must be later than the committed timestamp; a commit cannot recycle consumed event IDs.

Implemented after the storage foundation: `AndroidJnvM250kWorkingSet` adds a separate access-order LRU cache for neuron core state. It reads one neuron on a cache miss, writes state through to SQLite before publishing it in RAM, and delegates timestamp commits to the store before updating cached snapshots. It reuses the existing device-aware 1/2/4/8 MiB preference and rechecks the current device ceiling when its capacity is queried, when a state is accessed, and before writes/commits. Shrinking the budget evicts least-recently-used clean snapshots; SQLite remains authoritative. The wrapper intentionally does not own/close the store. Runtime callers must route state reads and writes through the working set; direct writes to the store while the cache is alive can make resident snapshots stale. The current unit tests cover budget-to-capacity mapping and the shared LRU trimming helper; they do not yet exercise the working set against a real Android SQLite database.\n\nStill not implemented: feature-state persistence, automatic wiring of the episode-attribution bridge into the app's decision/action lifecycle, persistent episode traces across process death, structural edge growth/pruning driven by learning, automatic CPU/GPU/hybrid backend selection, settings UI profile selection and device-level performance measurements. The 2,048-byte-per-resident-neuron value is only a planning allowance inherited from the existing RAM-budget policy, not a measured heap footprint. The 1/2/4/8 MiB values bound this managed core-state cache only, not total app RAM.

## Implemented 250k population descriptor

`AndroidJnvM250kTopology` now defines ten stable, mutually exclusive ID ranges covering the full 250,000-neuron profile:

| Population | ID range | Count |
|---|---:|---:|
| Perception | 0–19,999 | 20,000 |
| Memory | 20,000–69,999 | 50,000 |
| Pattern detection | 70,000–114,999 | 45,000 |
| Prediction | 115,000–154,999 | 40,000 |
| Attention | 155,000–174,999 | 20,000 |
| Contradiction | 175,000–189,999 | 15,000 |
| Context | 190,000–214,999 | 25,000 |
| Confidence | 215,000–229,999 | 15,000 |
| Coordination | 230,000–239,999 | 10,000 |
| Action selection | 240,000–249,999 | 10,000 |

Excitatory/inhibitory polarity is an orthogonal deterministic property, not an eleventh population: one in every five IDs within each population is provisionally inhibitory (20%), with the rest excitatory. These population sizes and the 80/20 polarity split are initial engineering allocations, not biologically validated or experimentally optimized values. The descriptor validates that ranges are contiguous, non-overlapping and cover exactly 250,000 IDs. It does not yet instantiate neurons, create inter-population connections, or integrate specialization with learning and runtime scheduling.

## Implemented sparse inter-population route policy

`AndroidJnvM250kTopology` now defines an explicit directed route matrix between populations. The initial graph supports the following information flow:

- Perception → memory, pattern detection, attention and context.
- Memory → memory, pattern detection, prediction and context.
- Pattern detection → pattern detection, prediction, memory and confidence.
- Prediction → contradiction, confidence, attention and memory.
- Attention → perception, memory, prediction and action selection.
- Contradiction → prediction, memory and confidence.
- Context → memory, pattern detection, prediction and attention.
- Confidence → action selection and coordination.
- Coordination → action selection and memory.
- Action selection → coordination and perception (feedback path).

The matrix is directional: a route A → B does not automatically permit B → A. Self-connections are rejected. `AndroidJnvM250kStore.registerConnection` now checks the route policy before persisting an edge, while the existing per-neuron limits still cap outgoing degree at 16 and incoming degree at 32. This prevents arbitrary cross-population wiring but does not yet generate a useful graph automatically, assign learned weights, or execute signals. Route coverage and boundary cases have unit tests authored but not run.

## Implemented deterministic sparse connection initializer

`AndroidJnvM250kSparseConnectionInitializer` now emits a reproducible initial graph lazily in bounded batches. It uses a stable route ordering, a fixed SplitMix64 seed mixer (not Kotlin's version-dependent `Random`), stable connection IDs, deterministic target assignment, configurable batch size, per-route fan-out, delay and initial weight. It does not materialize a full edge list, adjacency map or 250k-sized degree array.

The default emits one edge per source neuron for each allowed outgoing population route. The initializer checks the theoretical per-neuron outgoing and incoming degree bounds before returning a sequence, and each generated edge is checked against the route policy. A caller can persist each batch through `AndroidJnvM250kStore.registerConnections`, which validates batch-wide degree totals and commits the whole bounded batch in one SQLite transaction. The lazy sequence API remains available for simple consumers; the resumable coordinator uses the explicit next-edge cursor and atomically persists each batch with its checkpoint.

The default graph is a deterministic structural scaffold with neutral weight 0.5 and 1 ms delay. It is not learned connectivity and does not prove useful cognitive behavior. SQLite persistence and process-restart behavior have not been exercised on Android; unit tests are authored but not run.

## Resumable sparse graph initialization

The sparse initializer now exposes a durable next-edge cursor: route index, source neuron ID and fan-out slot. `AndroidJnvM250kSparseInitializationCoordinator.runNextBatch` executes one bounded step per worker invocation and checks that the requested seed and graph parameters match the persisted run.

`AndroidJnvM250kStore.persistConnectionBatch` writes the batch and all checkpoint fields to the existing metadata table in the same SQLite transaction. It compares the expected previous checkpoint before writing, so a stale worker cannot silently advance the run. On process death, either both the batch and cursor commit, or neither does. A subsequent worker loads the saved cursor and regenerates only the next uncommitted batch. Partial/corrupt checkpoint metadata fails closed.

The coordinator intentionally performs one batch per call; the application should schedule another bounded worker step while `complete == false`, rather than running the entire graph generation in one long task. The generator remains lazy and does not hold the full graph in RAM. This is resumable storage logic, not proof of successful Android background scheduling or SQLite behavior on the target phone. Unit tests are authored but not run.

## 250k runtime integration and app-enablement gaps

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

The legacy runtime limit of 100,000 connections is too low to describe a 250,000-neuron network with useful sparse connectivity. The new profile therefore has a separate per-neuron degree contract and disk-backed edge table. Do not increase the legacy global limit: its outgoing() method materializes all outgoing connections for one neuron, and each spike can generate events. The new store caps one source at 16 outgoing edges and one target at 32 incoming edges; these first-pass limits still need device measurements.

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

Completed in the current branch:
1. Add the target-side SQLite index to legacy schema V13 without changing legacy topology limits.
2. Add an independent 250k profile descriptor and database identity.
3. Add bounded-batch neuron initialization, paged neuron/edge reads and SQLite-backed sparse edges.
4. Enforce per-neuron incoming/outgoing degree limits.
5. Add a bounded disk-backed event queue and atomic timestamp commit contract.

Remaining before enabling the new profile in the app:
6. Partially covered by `AndroidJnvM250kStoreInstrumentedTest`: full-profile initialization/idempotency, batched graph persistence, close/reopen checkpoint recovery, stale-checkpoint rejection, duplicate-ID transaction rollback, timestamp atomicity and partial-checkpoint corruption. Still add focused coverage for degree rejection, event backpressure, working-set cache/database coherence, route enforcement and legacy database isolation.
7. Initial LIF-DT event execution and CPU-backend integration are now implemented in `AndroidJnvM250kRuntime`: complete timestamp groups are aggregated by target, computed through the existing validated kernel, propagated over persisted outgoing edges, and atomically committed through the bounded working set. Signed event strengths now represent inhibitory propagation from the 20% inhibitory population allocation. Still integrate and verify GPU/hybrid backend selection, feature-state persistence, automatic reward-source/credit assignment from the cognitive decision lifecycle, and structural plasticity for the 250k profile.
8. Measure database growth, startup time, query latency, event throughput and actual heap use on the target Android device before enabling the profile in the app settings.

## Verification rules

Until the above sequence is complete:
- report the existing app-integrated legacy runtime as **10,000-neuron capacity** until an explicit app-level profile switch is added;
- distinguish the isolated 250k LIF-DT runtime prototype from a fully integrated cognitive network; do not claim full 250k ANS cognition is running;
- do not extrapolate full-network speed from the isolated LIF-DT kernel benchmark;
- keep Android builds, device benchmarks and heavy tests opt-in;
- distinguish authored tests from tests actually executed.

## Pair-based STDP in the 250k runtime

`AndroidJnvM250kRuntime` now applies pair-based spike-timing-dependent plasticity to sparse edges touched by a timestamp's presynaptic or postsynaptic spikes:

- A persisted `synapse_learning` row stores each edge's pre-trace, post-trace and last trace-update timestamp. The 250k database schema advances additively to version 2; an existing version-1 graph receives zero-initialized trace rows during upgrade.
- Traces decay lazily with an exponential 20 ms time constant. Initial research defaults are `A+ = 0.01` and `A- = 0.012`; weights remain clamped to [0, 1], and traces are bounded.
- A postsynaptic spike potentiates from the previously accumulated presynaptic trace. A presynaptic spike depresses from the previously accumulated postsynaptic trace.
- The algorithm reads traces before adding the current timestamp's spikes. Simultaneous pre/post spikes therefore do not receive an arbitrary ordering advantage.
- Only edges touching the current spike set are queried. The query is bounded by the 512-neuron timestamp limit and the 16-outgoing/32-incoming degree contract; no dense matrix or full graph is loaded.
- Weight changes, updated traces, neuron states, consumed/generated events and model clock are committed in the same SQLite transaction. Propagation for that timestamp uses the newly learned weight.

These are initial engineering parameters, not scientifically tuned values. This section describes the local pair-based STDP update before the separately documented R-STDP reward stage; by itself, the local update does not assign external reward. It also does not create or delete graph edges. The implementation and instrumentation coverage are authored but not executed; no Android build or device experiment has been run.
## Reward-modulated STDP (R-STDP) in the 250k runtime

`AndroidJnvM250kRuntime` now exposes `applyReward(rewardId, reward, rewardedNeuronIds)` on top of the persisted pair-based STDP traces:

- The `synapse_learning` table now also persists a signed eligibility trace. Each STDP timestamp decays the prior eligibility and adds the same signed timing-correlation delta used by local STDP.
- SQLite schema v3 adds the eligibility column and a `reward_receipts` table. Version-2 databases migrate by adding zero-initialized eligibility and the receipt table.
- A reward is a finite scalar in [-1, 1]. Positive reward reinforces positive eligibility and weakens negative eligibility; negative reward reverses that direction. Weights remain clamped to [0, 1]. The initial reward gain is 0.1; it is an engineering default, not experimentally tuned.
- Credit is deliberately local and explicit: callers identify 1–32 rewarded neuron IDs. The store updates only synapses touching those neurons, capped at 1,536 edges from the 16-outgoing/32-incoming degree contract. No full graph scan or dense reward matrix is used.
- Reward is accepted only at the current committed model clock, so it cannot skip pending events. The eligibility trace decays using the same 20 ms time constant. A nonzero reward consumes the selected eligibility traces; a zero reward preserves their decayed values.
- A durable reward receipt makes retries idempotent. Replaying the same reward ID with the same scalar and credited neuron set cannot apply learning twice, even after the model clock advances. Reusing the ID with different feedback is rejected. Receipt history is disk-backed and capped at 100,000 records; when full, new rewards are backpressured rather than silently dropping replay protection.
- Weight updates, trace decay/consumption and the reward receipt are committed in one SQLite transaction.

This implements the R-STDP mechanism and a caller-facing feedback hook, but not automatic credit assignment: the higher-level ANS decision/reasoning lifecycle does not yet determine which neurons deserve reward or punishment. A caller must supply the credited neuron IDs. This is not yet evidence that the network learns a useful task. Positive/negative reward and replay tests are authored but not executed; no Android build or device experiment has been run.
## Exact synapse-level episode reward attribution

The episode bridge now credits exact connection_id values observed in runtime transmissions rather than converting transmissions into endpoint neuron IDs:

- Each recorded link contributes its exact connection ID to a bounded Space-Saving frequency sketch. The episode also retains an ordered connection-ID path up to the configured bound.
- The default credited/tracked connection bound is 1,536, matching the store's maximum rewarded-synapse batch. IDs are validated and admitted only from emitted causal-link records.
- AndroidJnvM250kRuntime.applyRewardToConnections and AndroidJnvM250kStore.applyRewardToConnections apply R-STDP only to those exact IDs. SQL selects connection_id IN (...); adjacent but unobserved connections are not selected.
- Reward receipts encode the selection namespace as connections: plus sorted IDs. Exact-connection receipts remain distinct from legacy neuron-set receipts and preserve idempotent replay validation.
- The legacy applyReward(..., rewardedNeuronIds) API remains available for compatibility; it still rewards eligible synapses adjacent to supplied neurons. The episode bridge no longer calls that API.
- The ordered path records emitted transmissions, not proof that each edge caused the final outcome. Target spikes, competing paths, and counterfactual contribution are not inferred. Episodes remain in-memory and are not wired to the higher-level app decision lifecycle.

This is exact selection of observed synapses, not full causal inference. Unit tests cover exact-ID selection, ordered path retention, bounded tracking, no-transmission rejection, retry behavior and identifier validation. Tests are authored but not executed; no Android build or device experiment has been run.

## Android SQLite integration test authored

`AndroidJnvM250kStoreInstrumentedTest` adds opt-in Android instrumentation coverage against the real SQLite implementation. It initializes the complete 250,000-row profile once, checks initialization idempotency, commits a bounded sparse-edge batch with its checkpoint, closes and reopens the database, and verifies the saved cursor and edges. It also verifies that a stale worker cannot advance the cursor, that a duplicate-ID failure rolls back an earlier insert in the same batch, that timestamp processing commits consumed/generated events, neuron state and model time together, that an invalid consumed-event set rolls back, and that partially deleted checkpoint metadata fails closed.

This is intentionally an instrumentation test because the production store depends on Android SQLite. It performs full 250k initialization and should not be added to lightweight JVM smoke-test runs. The test is authored but **not executed**; database restart, rollback and corruption handling therefore remain unverified on a real Android runtime until the instrumentation test is deliberately run.

## First executable 250k LIF-DT runtime

`AndroidJnvM250kRuntime` is the first event-driven execution layer over the independent SQLite profile. It accepts bounded input batches, processes one complete timestamp group at a time, aggregates signed input per target neuron, uses the existing validated `AndroidNeuronKernel` and injectable compute backend, then propagates spikes over persisted outgoing edges. Neuron state is read and published through `AndroidJnvM250kWorkingSet`; the store remains authoritative and each timestamp is committed atomically before the runtime advances its clock.

The topology's provisional inhibitory polarity now affects signal transmission: excitatory source neurons emit positive event strengths, inhibitory source neurons emit negative strengths. `AndroidNeuronEvent.strength` therefore accepts the signed interval [-1, 1], while the LIF-DT kernel already supports signed finite input. The instrumentation test exercises both signs, checks that an over-budget timestamp group is left untouched, and verifies that an inhibitory signal lowers its target's membrane potential.

Per invocation the runtime caps processed input events at 8,192 and timestamp groups at 1,024; each timestamp remains capped at 512 inputs and generated propagation remains capped at 8,192 events. The working set is rechecked against the device RAM policy and only event-target states are loaded. The compute backend is injectable, with CPU reference as the default. GPU/hybrid selection is not automatically enabled by this runtime yet.

Important boundary: the isolated 250k runtime now performs pair-based STDP and bounded reward-modulated STDP with persisted eligibility traces and idempotent reward receipts. It still lacks automatic reward attribution from the cognitive decision lifecycle, feature-state extensions, the full cognitive hypothesis lifecycle, and evidence of useful task learning. Instrumentation coverage is authored but not run; no Android build or device measurement has been performed.

