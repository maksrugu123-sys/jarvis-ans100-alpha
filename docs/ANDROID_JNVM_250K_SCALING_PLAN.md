# Android JNVM: 250,000-neuron scaling plan

## Goal and status

The target architecture is a sparse network with up to 250,000 neuron records. The target is a structural capacity, not a promise that 250,000 neurons can all be active in RAM or that the current Android runtime already supports this count.

**Status: separate 250k LIF-DT runtime with pair-based STDP, bounded R-STDP, durable episode attribution and opt-in counterfactual-gated reward recovery is implemented as an isolated prototype; automatic app decision-lifecycle integration and device verification remain incomplete.** The legacy AndroidSparseNeuronNetwork remains capped at 10,000 neurons, 100,000 connections and 100,000 pending events. A new independent AndroidJnvM250kStore initializes a separate 250,000-row SQLite neuron store, sparse edge storage, persisted STDP/R-STDP traces and reward receipts, bounded paged reads and an atomic disk-backed event queue. This does not mean the full ANS cognitive runtime can yet execute across 250,000 neurons. Do not change the global legacy limit: several existing topologies use the current 10,000-neuron range as part of their stable ID layout.

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

Implemented after the storage foundation: `AndroidJnvM250kWorkingSet` adds a separate access-order LRU cache for neuron core state. It reads one neuron on a cache miss, writes state through to SQLite before publishing it in RAM, and delegates timestamp commits to the store before updating cached snapshots. It reuses the existing device-aware 1/2/4/8 MiB preference and rechecks the current device ceiling when its capacity is queried, when a state is accessed, and before writes/commits. Shrinking the budget evicts least-recently-used clean snapshots; SQLite remains authoritative. The wrapper intentionally does not own/close the store. Runtime callers must route state reads and writes through the working set; direct writes to the store while the cache is alive can make resident snapshots stale. The current unit tests cover budget-to-capacity mapping and the shared LRU trimming helper; they do not yet exercise the working set against a real Android SQLite database.\n\nStill not implemented: feature-state persistence, automatic wiring of the episode-attribution bridge into the app's decision/action lifecycle, automatic app decision-lifecycle wiring for durable episode traces, structural edge growth/pruning driven by learning, automatic CPU/GPU/hybrid backend selection, settings UI profile selection and device-level performance measurements. The 2,048-byte-per-resident-neuron value is only a planning allowance inherited from the existing RAM-budget policy, not a measured heap footprint. The 1/2/4/8 MiB values bound this managed core-state cache only, not total app RAM.

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

Important boundary: the isolated 250k runtime now performs pair-based STDP and bounded reward-modulated STDP with persisted eligibility traces and idempotent reward receipts. An opt-in decision-bound attribution bridge is now present, but MainActivity does not instantiate it automatically. Feature-state extensions, the full cognitive hypothesis lifecycle, and evidence of useful task learning remain incomplete. Instrumentation coverage is authored but not run; no Android build or device measurement has been performed.

## Durable reward-episode journal

`AndroidJnvM250kEpisodeJournal` now stores the active episode snapshot in a separate versioned SQLite database. The persisted snapshot contains the action and episode IDs, bounded Space-Saving candidate sketch, ordered signal path, observed/omitted counters and next insertion order. The episode bridge can be constructed with `AndroidJnvM250kRewardAttribution.persistent(context, applyReward)`; it restores the latest recoverable episode when recreated after process death.

Reward completion uses a two-phase recovery protocol across the journal and the JNVM store:

1. The journal writes `REWARD_PENDING` and the exact reward scalar before calling the synapse reward API.
2. The JNVM store applies the reward under its stable `episode-{id}` receipt.
3. The journal marks the episode `FINISHED` only after the reward call returns.

If the process stops between steps 2 and 3, the restored episode retries the same reward ID and exact connection-ID set; the JNVM receipt prevents double application. A pending reward cannot be changed or aborted. Episodes with no recorded transmissions still cannot be rewarded. The journal allows one active episode at a time and prunes old completed/aborted records, retaining a bounded recent history.

The journal is intentionally a separate database, so it is not a cross-database atomic transaction. Correct retry behavior depends on the existing durable idempotent reward receipt in the JNVM store. `AndroidJnvM250kEpisodeJournalInstrumentedTest` covers recovery of an open episode and retry of a reward-pending episode after recreating the attribution object. These instrumentation tests are authored but **not run**. No APK build, Android device test, or performance experiment has been run.

## Target-response evidence and graded synapse credit

`AndroidJnvM250kCausalLink` now carries both the source-spike timestamp and the expected target-arrival timestamp (`source timestamp + synaptic delay`). Episode attribution merges arrival observations and emitted target spikes into one timestamp-ordered stream. A transmission is only a candidate; it becomes reward-eligible when its own target neuron emits a later spike within the bounded 100 ms response window. Spikes from unrelated neurons do not confirm it.

When several distinct tracked connections arrive at the same target before one target spike, that response is divided evenly among those connections. Repeated transmissions on one connection are deduplicated while its response window is pending, so one edge cannot claim multiple shares of the same target response. Candidate state and pending response windows are persisted in the existing episode journal. Unanswered windows expire; connections without a matched target response receive no reward.

The resulting per-connection response score is the average share of matched target responses, conservatively multiplied by transmission-observation coverage (`assessed / (observed + runtime-omitted)`), where `assessed` excludes pending transmissions lost when the bounded candidate sketch evicts a connection or when a target spike is confounded by an external input. This matters because the runtime caps returned causal links per run and the candidate sketch is bounded: unobserved or evicted transmissions may include competing inputs. The score is passed to `applyRewardToConnections` as a bounded 0..1 per-edge credit multiplier. SQLite applies `reward * credit * learningRate * eligibility` to that exact edge, and the idempotent receipt includes the canonical per-edge credit values so a retry with different scores is rejected. The legacy unweighted exact-edge API remains compatible.

These are temporal response and competition heuristics, not true counterfactual causality. The separate evaluator now replays a bounded pre-action graph with one candidate connection disabled, but does not reproduce online STDP, environment feedback or the complete target membrane history. The decision-bound cycle is opt-in and is not instantiated by MainActivity. Unit and Android instrumentation tests have been added or updated for silent transmissions, unrelated spikes, competing inputs, weighted reward receipt replay, and persistent recovery. Tests are authored but **not run**; no build or device experiment was run.

Target spikes whose timestamp group also contains an external/non-runtime-generated input are marked ambiguous by the runtime. Such a spike cannot confirm any pending synapse candidate; the affected pending paths are counted as unassessed and reduce coverage. This avoids automatically crediting a synapse when a direct input could have caused the same spike. The event-origin check currently relies on the runtime's generated synaptic event-ID format, so it is a conservative engineering marker rather than a cryptographic provenance guarantee.

Legacy manual integration contract (without counterfactual replay): create one persistent attribution object for an episode, call `recordRun(runtime.run(...))` once for each completed runtime batch, then call `finishEpisode(outcomeReward)` only after the action outcome is known. For a selected ANS decision with a captured pre-action snapshot, use `AndroidJnvM250kDecisionLearningCycle` below instead. Wire both callbacks so normal fallback remains exact-edge unweighted and the weighted callback forwards the score map to the runtime:

```kotlin
val attribution = AndroidJnvM250kRewardAttribution.persistent(
    context = context,
    applyReward = { id, reward, ids ->
        runtime.applyRewardToConnections(id, reward, ids)
    },
    applyWeightedReward = { id, reward, credits ->
        runtime.applyRewardToConnections(id, reward, credits.keys, credits)
    },
)

val runResult = runtime.run()
attribution.recordRun(runResult)
// After the environment reports the action outcome:
val learningResult = attribution.finishEpisode(outcomeReward)
```

The explicit `AndroidJnvM250kDecisionLearningCycle` now accepts an `ANS100Decision`, captures the bounded pre-action JNVM context, schedules inputs, records each completed runtime batch and finishes from external feedback. The app's MainActivity/chat route does not yet instantiate this bridge automatically.

## Bounded counterfactual edge replay

`AndroidJnvM250kCounterfactualEvaluator` adds an isolated sensitivity experiment for up to eight candidate synapses:

- It accepts detached initial neuron states, one bounded edge page, a bounded external-input snapshot and an explicit outcome-neuron set.
- It replays the same inputs from the same initial states once with all supplied edges, then once per candidate with exactly that connection ID suppressed.
- Both branches use the deterministic CPU LIF-DT reference kernel. Each replay has independent copied state and an isolated priority queue; no production SQLite database, working set, connection weight, eligibility trace or reward receipt is opened or mutated.
- The report compares spikes only in the explicitly selected outcome population. A positive delta means fewer outcome spikes after suppressing the edge; a negative delta means more. The normalized sensitivity score is a replay difference, not a causal probability or reward multiplier.
- Hard limits are eight candidate edges, 512 default replay events, 64 default timestamp groups, one 512-edge snapshot page, with configurable ceilings of 2,048 events and 256 timestamp groups. Generated fan-out is included in the per-replay work budget; a replay that hits a bound is marked incomplete.
- Candidate IDs must exist in the supplied edge snapshot, and all edge endpoints, external-input targets and outcome neurons must have supplied initial states. Inputs must be later than each target's snapshot time.

This is a controlled counterfactual *model replay*, not a faithful replay of the complete production runtime. It currently does not reproduce online STDP/eligibility updates, external environment feedback, unprovided hidden state, asynchronous app actions or GPU/hybrid backend behavior. It assumes the supplied snapshot and input stream are complete and deterministic. If either branch is incomplete, its score must not be treated as a validated effect. The evaluator is now invoked by the opt-in `AndroidJnvM250kDecisionLearningCycle` after a selected decision's runtime episode has completed. It still is not automatically wired into MainActivity or the ordinary chat pipeline, and its score must not automatically change R-STDP credit outside the gated journal path.

Unit tests are authored for an edge that changes the selected outcome, an unrelated edge, input snapshot immutability, hard candidate bounds and incomplete replay reporting. Tests are **not run**; no APK build or Android device experiment was run.


## Decision-bound 250k learning cycle

`AndroidJnvM250kDecisionLearningCycle` now provides an explicit bridge from the typed ANS decision contract to the separate 250k runtime:

- `AndroidJnvM250kRuntime.captureDecisionEpisodeContext` accepts only `SELECTED` decisions, requires an empty persisted event queue, and captures a detached pre-action state/edge snapshot by traversing outgoing connections from the external-input targets. The graph traversal is bounded to 16 levels, 512 edges and 512 neuron states; it fails closed if any reachable frontier remains outside those bounds.
- `AndroidJnvM250kDecisionEpisodeContext` binds the snapshot to the exact decision turn and selected candidate. It stores the pre-action neuron states, captured edges, external input batch and selected outcome/readout neuron IDs. The complete context is written to the episode journal before the input batch is scheduled.
- `AndroidJnvM250kDecisionLearningCycle.begin` persists the context and schedules the captured inputs. `run` executes one bounded 250k runtime batch and immediately records its spikes/transmissions into the episode journal. `finish` runs the CPU counterfactual replay and applies only the exact-edge credits accepted by the gate, using an externally supplied reward in [-1, 1].
- Candidate edges are selected from the episode's actual target-response evidence, ranked deterministically and limited to the evaluator's eight-candidate ceiling. The same persisted pre-action snapshot and inputs are used for baseline and one-edge-suppressed replays.
- Episode snapshot schema is version 7. It persists the decision context, exact gate decision and runtime-completion status. Reward is refused if no runtime batch completed, the last batch left pending events, or the runtime budget was exhausted.
- `resumeRecoveredEpisode` reuses the durable episode snapshot. If a reward was already marked pending, the exact persisted accepted-credit map and stable reward ID are replayed instead of recalculating the gate.

Minimal opt-in wiring:

```kotlin
val attribution = AndroidJnvM250kRewardAttribution.persistent(
    context = context,
    applyReward = { id, reward, ids ->
        runtime.applyRewardToConnections(id, reward, ids)
    },
    applyWeightedReward = { id, reward, credits ->
        runtime.applyRewardToConnections(id, reward, credits.keys, credits)
    },
)
val cycle = AndroidJnvM250kDecisionLearningCycle(runtime, attribution)
cycle.begin(
    decision = selectedDecision,
    episodeId = stableEpisodeId,
    externalInputs = decisionInputEvents,
    outcomeNeuronIds = selectedCandidateReadoutNeuronIds,
)
val runResult = cycle.run()
// Only after independent feedback reports the actual outcome:
val learning = cycle.finish(observedReward)
```

**Integration boundary:** this is a source-level orchestration API, not an automatic MainActivity/chat integration. The ordinary Android dialogue route currently uses its separate 10k hybrid topology; the 250k JNVM runtime remains an independent profile. A caller must explicitly pass the selected decision, the correct 250k input projection and outcome-readout neuron IDs, and a reward from real external/user feedback. An ANS-generated confidence or its own prediction must not be substituted for the observed reward.

**Recovery limitation:** runtime timestamp commits and episode-journal telemetry writes are separate SQLite transactions. `run()` immediately calls `recordRun()`, but a process death in the small interval after the runtime commit and before the journal write can lose that batch's attribution telemetry. This bridge does not claim cross-database atomicity. The CPU replay also omits online STDP, environment feedback and unprovided state; it is sensitivity evidence inside the captured model, not proof of real-world causality.

The decision-context invariant tests, Android journal-recovery tests and full-250k SQLite snapshot-capture instrumentation test are AUTHORED / NOT RUN. No Gradle build, APK, CI, Android instrumentation or device run was performed.

## Durable counterfactual decision in the episode journal

`AndroidJnvM250kRewardAttribution.finishEpisodeWithCounterfactual(reward, policy)` automatically replays the persisted pre-action snapshot for a decision-bound episode. The lower-level `finishEpisodeWithCounterfactual(reward, report, policy)` remains available for manually supplied reports on legacy episodes:

- It derives response scores from the episode's own target-response evidence, ranks at most eight candidates for replay, and evaluates the counterfactual gate once.
- Before calling weighted R-STDP, the episode snapshot stores the stable reward ID, scalar reward, exact accepted connection IDs and multipliers, rejected IDs/reasons, policy thresholds, and a bounded summary of the counterfactual report.
- If the process stops after the journal write but before reward completion, recovery reuses the stored accepted-credit map. A newly supplied report or policy cannot change that retry decision; the JNVM reward receipt protects against duplicate application.
- A rejected decision is persisted as terminal journal state `REJECTED`. Neither weighted nor unweighted reward callbacks run.
- The journal schema migration preserves existing OPEN, REWARD_PENDING, FINISHED and ABORTED records.
- The gated method requires the weighted callback and refuses the legacy unweighted fallback. Ordinary `finishEpisode()` refuses to consume an episode with a persisted counterfactual decision, preventing accidental double learning through both paths.

The episode journal and JNVM reward database remain separate databases, not one cross-database transaction. Retry safety therefore still depends on the JNVM store's durable idempotent reward receipt. The new method is opt-in and is exposed through `AndroidJnvM250kDecisionLearningCycle`; it is not automatically connected to MainActivity or the ordinary chat pipeline. Instrumentation tests for exact-decision recovery and terminal rejection are authored but **not run**.

## Counterfactual-gated reward policy

`AndroidJnvM250kCounterfactualRewardGate` adds a fail-closed policy for using replay sensitivity as a bounded per-edge reward multiplier:

- The baseline replay must be complete. Each selected candidate's counterfactual replay must also be complete.
- Positive reward is accepted only when suppressing the edge reduces the selected outcome-spike count (`outcomeSpikeDelta > 0`). Negative reward requires the opposite direction (`outcomeSpikeDelta < 0`). Zero reward never produces credits.
- By default, an edge must change the outcome by at least one spike, have sensitivity >= 0.25, and have response-score × sensitivity >= 0.10.
- The final per-edge multiplier is `responseScore * sensitivityScore`, clamped to [0, 1]. Missing candidates, incomplete replays, no outcome change, weak sensitivity, weak combined confidence and direction mismatch are explicitly rejected.
- `applyIfTrusted` invokes the supplied exact-edge reward callback only when at least one edge passes every check. The callback receives only accepted connection IDs and their filtered multipliers; otherwise it is not called. The caller must supply a stable reward ID so the JNVM store's durable receipt can make retries idempotent.

Example for an explicitly managed, separate counterfactual-gated learning operation:

```kotlin
val gated = AndroidJnvM250kCounterfactualRewardGate.applyIfTrusted(
    rewardId = "counterfactual-episode-42",
    reward = outcomeReward,
    responseScores = observedResponseScores,
    report = counterfactualReport,
) { id, reward, credits ->
    runtime.applyRewardToConnections(id, reward, credits.keys, credits)
}
if (!gated.decision.mayApplyReward) {
    // Keep the episode out of R-STDP; inspect gated.decision.rejected.
}
```

The low-level `applyIfTrusted` helper remains available for separately managed experiments. For episode-based learning, use `finishEpisodeWithCounterfactual`; do not call both the ordinary `finishEpisode` path and the gated path for the same episode. The durable path stores the canonical decision before applying the reward and replays it after process death.

Six JVM unit tests cover positive/negative reward direction, incomplete baseline and candidate rejection, missing/weak evidence, zero reward, and callback suppression/filtering. Tests are authored but **not run**; no APK build, Android instrumentation run or device experiment was performed.

## Decision-bound cognitive controller (V1)

`AndroidJnvM250kCognitiveController` now coordinates the existing bounded runtime and durable attribution path:

1. Accepts only an `ANS100Decision` with `SELECTED` status and caller-supplied external events/outcome readout neurons.
2. Captures the pre-action graph and neuron states before scheduling inputs, then opens a journaled decision episode.
3. Schedules the captured inputs once and processes bounded runtime batches; each actual run result is recorded by `AndroidJnvM250kRewardAttribution`.
4. Allows outcome assessment only after the runtime reports a drained event queue and at least one event was processed.
5. Calls the attribution layer's replay-from-persisted-snapshot path. The evaluator runs baseline and one-edge-suppressed replays; the reward gate rejects incomplete, weak, unchanged, or directionally inconsistent candidates.
6. The attribution layer persists the exact accepted edge IDs/credits and reward identity before applying the weighted reward in SQLite.

The controller is an orchestration boundary, not a complete autonomous agent. It does not yet generate perception events from raw user/environment input, independently choose actions, execute external tools/actions, or verify real-world outcomes. The caller must provide an independently observed outcome reward; neural outcome spikes alone are only the replay readout. The pre-action graph is a bounded reachable subgraph, not a full network/world snapshot. Process-death recovery remains owned by the attribution journal and must be explicitly reattached by the application layer; this V1 controller does not reconstruct its in-memory phase automatically.

No APK build, instrumentation test, device run, or benchmark was performed for this addition.

## Typed perception and hybrid decision bridge (V1)

`AndroidJnvM250kEvidenceEncoder` projects each typed evidence record into a deterministic neuron address in the 250k topology: user/message structure to PERCEPTION, Core understanding hints to PATTERN_DETECTION, Core reasoning-plan hints to PREDICTION, approved memory to MEMORY, and context/tool evidence to CONTEXT. It preserves `HEURISTIC` provenance through `coreTainted`, maps boolean false to negative input polarity, and resolves target collisions deterministically. The projection is an address/activation encoding only; it does not understand raw language or convert hints into verified facts.

`AndroidJnvM250kCognitiveController.decideAndBeginEpisode` now connects the existing `ANS100HybridPopulationController` to the 250k runtime. The caller supplies the completed 10k hybrid pass's actual telemetry and emitted spike IDs, along with the typed evidence and Core understanding. The existing hybrid controller validates the candidate lanes and emits SELECTED, NEEDS_CLARIFICATION, or ABSTAINED. Only SELECTED decisions are encoded and opened as reward episodes; clarification and abstention are returned without starting learning.

After the decision bridge, the 250k runtime executes the encoded evidence events and the existing attribution/replay/gate/SQLite chain remains responsible for exact-edge learning. The 10k hybrid candidate competition is still the current action-selection authority; the 250k populations do not yet independently select external tools/actions. The native 250k readout now generates repeatable lexical-feature associations and conservative feature predictions from actual spikes and sparse transmissions, but it is not a semantic content decoder. Memory retrieval, content-level reasoning and app-level integration remain incomplete. This is not yet a fully autonomous end-to-end agent.

Unit tests were added for deterministic mapping, population routing, heuristic taint and boolean polarity. They were authored but NOT RUN. No APK build, instrumentation test, device run or benchmark was performed.


## Neural hypothesis lifecycle handoff

`AndroidJnvM250kCognitiveController.observeNeuralHypotheses` now accepts bounded candidates from the existing `ANS100NeuralAssociationDecoder` after the JNVM decision queue is drained. It records the completed turn through `ANS100NeuralHypothesisEngine`, which tracks support and non-reproduction across independent turns, and can save/restore the bounded checkpoint through `ANS100NeuralHypothesisLedger`. The method reports whether persistence succeeded; without a supplied ledger, hypotheses remain in controller memory only.

Important boundary: this handoff does not itself decode semantic concept pairs from the 250k runtime's spike list. The caller must provide candidates produced by the existing neural association decoder and a complete pair-scan flag. Repeated co-activation is repeatability evidence only, never proof of a true semantic relation or causal fact. A dedicated 250k concept readout/decoder is still a future integration task.
The controller also validates that encoded inputs enter only PERCEPTION/MEMORY/PATTERN_DETECTION/PREDICTION/CONTEXT populations and that counterfactual outcome readouts are selected from ACTION_SELECTION. This prevents accidentally treating arbitrary network neurons as action outcomes.

## Native 250k evidence-slot hypothesis readout (V1)

`AndroidJnvM250kHypothesisReadout` now derives candidate associations directly from the independent 250k runtime rather than requiring the legacy 10k `ANS100NeuralAssociationDecoder`:

- Evidence-slot neuron addresses are stable across turns: the encoder hashes the stable feature/record slot, not the turn ID. Event IDs remain turn-specific to preserve queue idempotency. This allows repeated evidence slots to reuse the same JNVM neuron and its persisted sparse connections.
- The encoding retains only bounded feature labels and provenance references alongside the record-to-neuron map. It does not retain or hash raw text as a semantic concept.
- The runtime carries `coreTainted` onto actual spike telemetry. A candidate is marked `heuristicEvidence` if either input slot is heuristic or inherited taint is present on either source spike or the convergent target spike; this flag is persisted in the hypothesis checkpoint. Older checkpoints without the flag load as `false`.
- A readout candidate requires two distinct evidence-slot source neurons to have actually spiked, both source neurons to have transmitted through recorded sparse edges, and both transmissions to reach the same target neuron at the same arrival timestamp where that target actually spiked.
- Target spikes marked ambiguous because they also received external/non-runtime input are excluded. If any ambiguous target spike exists, if runtime causal links or source spikes were truncated, or if the candidate limit was reached, the pair scan is marked incomplete. Incomplete scans cannot create negative/non-reproduction observations.
- `ANS100NeuralHypothesisEngine.observe250kTurn` reuses the bounded cross-turn lifecycle and SQLite checkpoint format. It records repeated support and complete later non-reproduction as a mixed/repeatability signal, never as proof of semantic truth or causality.
- `AndroidJnvM250kCognitiveController.observe250kNeuralHypotheses` accumulates actual spikes and causal links across bounded runtime batches and runs this native readout only after the queue is drained. It can persist the updated checkpoint through the supplied `ANS100NeuralHypothesisLedger`.

**Interpretation limit:** typed evidence slots remain structural inputs, while lexical concepts below are exact normalized tokens extracted from user text. Neither layer performs synonym resolution, morphology, translation or semantic understanding. A matching target spike is evidence of temporal co-activation, not proof that either edge alone caused the target spike.

Unit tests were added for stable addresses across turns, actual two-edge convergence, target-spike timestamp matching, ambiguous-target rejection, truncated telemetry, and cross-turn reproduction/non-reproduction. Tests are authored but **NOT RUN**. No Gradle build, APK, CI, Android instrumentation or device benchmark was run.

## Lexical concept slots and association-based prediction (V1)

`AndroidJnvM250kEvidenceEncoder` now adds a separate bounded lexical layer from the actual `USER_MESSAGE_TEXT` value:

- Unicode NFKC normalization and locale-independent lowercase are applied before tokenization. Tokens are Unicode letters/numbers, 2–32 characters; a small Russian/English stop-word list is removed.
- Up to 24 distinct tokens are retained per turn. Each token gets a stable digest-based concept-slot ID and a stable PERCEPTION neuron address across turns. Typed evidence and lexical tokens use disjoint halves of PERCEPTION; their address allocation is deterministic. Event IDs remain turn-specific.
- Lexical and forecast events use a 1.0 presence pulse to meet the current LIF-DT base threshold. Prediction confidence remains separate metadata on the prediction result; it is not misrepresented as calibrated spike amplitude.
- The readout now considers only these lexical concept slots when forming pair hypotheses, and requires each source spike to occur at the exact current-turn input timestamp. Later recurrent spikes cannot masquerade as direct word observations. Structural and heuristic typed-evidence records are not mixed into lexical pair candidates.
- Labels are the normalized token itself; provenance stores a bounded turn reference and token digest. Raw full message text is not copied into the hypothesis ledger.
- This is exact lexical matching only: no stemming, lemmatization, synonym mapping, cross-language equivalence, phrase meaning or entity resolution. For example, inflected forms are separate feature slots.

`ANS100NeuralHypothesisEngine.predict250k` adds a conservative co-occurrence predictor:

- It emits a prediction only when one lexical feature is active, its partner is absent, the pair has at least two independent supporting turns, and the support ratio is at least 0.75.
- The reported confidence is the bounded ratio `supporting turns / (supporting + non-reproducing turns)`. It is a simple empirical score, not a calibrated probability or quality guarantee.
- Mixed/non-reproduced associations are naturally down-weighted by the ratio and normally fail the threshold.
- The predicted feature is encoded as a bounded pulse into a reserved subrange of the PREDICTION population, separate from typed reasoning-plan input slots. It is not added to the active lexical concept set, so a prediction cannot be mistaken for an observed token.
- Every prediction pulse is marked heuristic-tainted because it is model-generated, regardless of whether its supporting lexical observations were user-provided. Forecasts are returned in `DecisionStart.predictions` for inspection.

These are predictions of likely co-occurring lexical features, not next-word generation, factual claims, or independent language reasoning. They should be treated as model expectations and compared with subsequent observed input. Unit tests were added for stable lexical addresses, lexical-only readout, conservative prediction thresholds, and PREDICTION-population routing. Tests remain **NOT RUN**; no Gradle build, APK, CI or device benchmark was performed.

## Repeated co-occurrence prototypes and prediction outcome audit (V1)

The 250k hypothesis engine now has a bounded concept-prototype stage. It is deliberately named a *co-occurrence prototype*, not a semantic concept:

- A prototype is formed from 2–4 lexical token slots only when every pair in the group has at least two independent supporting turns and pair confidence >= 0.75. This is complete-link grouping: an A–B and B–C chain cannot merge A, B and C unless A–C also passes.
- Prototype IDs are deterministic digests of the sorted member token IDs. The prototype is reconstructed from the persisted pair-hypothesis checkpoint; no raw message or new unbounded dictionary is stored.
- When one member is present in a later turn, the controller may reactivate the prototype into the second half of the MEMORY population. User-approved memory evidence is reserved to the first half. Prototype pulses are marked `coreTainted`, are reported separately in `DecisionStart.conceptPrototypes`, and never enter the set of observed lexical tokens.
- This is still statistical clustering of exact normalized word forms. It does not establish a semantic category, synonym, causal relation, or generalization beyond the observed tokens.

Prediction feedback is now separated from neural co-activation:
- `DecisionStart.predictions` are forecasts generated before the new input is observed. At the end of the turn, the controller compares each forecast with the current encoding's actual lexical token set, not with the neurons that happened to spike.
- The encoder reports when the 24-token lexical cap truncated the message. Truncated turns are not used to score prediction hits/misses, because an omitted token must not be counted as absent.
- Prediction hits and misses are persisted per independent turn in the existing hypothesis checkpoint. After at least two forecast evaluations, `predict250k` uses the lower of the association support ratio and observed prediction accuracy. Repeated misses therefore suppress a previously strong forecast.
- These prediction misses are not factual contradictions and do not change the underlying association's independent co-activation support history.

Tests were added for complete-link grouping, rejection of transitive-only clusters, MEMORY prototype routing/taint, prediction-miss suppression after checkpoint restore, and lexical truncation metadata. Tests are **AUTHORED / NOT RUN**. No Gradle build, APK, CI, instrumentation, or device benchmark was run.
