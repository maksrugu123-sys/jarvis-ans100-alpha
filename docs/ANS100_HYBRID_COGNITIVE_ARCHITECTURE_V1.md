# ANS-100 Hybrid Cognitive Architecture V1

**Status:** DECIDED / SPECIFIED. The user selected the hybrid architecture (one 10,000-neuron sparse network with specialized interacting populations and bounded software agents). This document is a design contract, not an implementation or a claim of general intelligence.

**Target branch:** `experiment/jarvis-integrated-prototype-v1`

## 1. Decision

JARVIS will use one logical ANS-100 network with a fixed ceiling of 10,000 logical neurons. The network is divided into typed, interacting populations. A bounded software orchestration layer (the Agent Manager) assigns work to those populations and manages a small number of analysis passes. The agents are controllers over shared evidence and network state; they are not separate language models and are not one-agent-per-neuron.

Qwen3-0.6B INT4 remains the natural-language realization component. It receives the original user message plus a bounded, provenance-preserving ANS-100 decision object. It does not replace ANS-100 analysis, and ANS-100 does not update Qwen weights.

## 2. Existing implementation baseline (source audit)

The current Android implementation already has:
- a SQLite-backed sparse event-driven LIF-DT runtime with a source-level 10,000-neuron ceiling;
- a bounded resident LRU and atomic same-timestamp SQLite publication;
- a separate app-local Core understanding/planning pipeline (currently heuristic and explicitly described as such);
- a separate ten-neuron request-shape signal pass;
- a separate 10,000-slot hashed dialogue feature classifier;
- an Android sparse dialogue graph with an input/hidden/readout structure and explicit-feedback R-STDP;
- a LiteRT-LM response engine for Qwen.

The current Android sparse dialogue topology is not yet the selected hybrid architecture. Its source initializer documents 9,000 hashed text inputs, 996 LIF hidden neurons, and four readout neurons. Inputs fan out to a bounded set of hidden targets; hidden units connect to the four readouts. The four readouts are not a general reasoning system. Existing R-STDP is limited to explicit feedback and does not establish semantic understanding or useful generalization.

The existing heuristic Core output is useful as upstream evidence but must not be relabelled as a learned semantic representation. The ten-neuron signal pass, 10,000-slot hashed classifier, and 10,000-node sparse spiking graph are distinct components and must remain separately identified until a deliberate integration replaces their roles.

## 3. Logical populations within the 10,000-neuron budget

The following is the initial *design allocation*, not a measured optimum. It must be versioned and can be revised only through documented evaluation. IDs are stable within a topology version.

| Population | Initial capacity | Purpose |
|---|---:|---|
| Evidence/input | 1,200 | Typed upstream evidence and feature activation |
| Association | 2,800 | Sparse co-activation and learned relationships between evidence features |
| Working state | 1,800 | Bounded active task/context state across analysis rounds |
| Candidate hypotheses | 1,500 | Competing interpretations or solution candidates |
| Critic/uncertainty | 1,200 | Conflict, missing-evidence and uncertainty signals |
| Goal/planning | 900 | Goal progress, dependency and next-step signals |
| Decision/readout | 600 | Candidate ranking signals and structured decision fields |
| **Total** | **10,000** | |

Population names describe engineering roles, not biological cell types or independent intelligence. Not every neuron is active on every turn. The runtime must activate only a bounded subset based on evidence and the selected compute profile.

The Agent Manager is software orchestration and does not consume additional logical neuron IDs. It may not bypass network budgets or safety gates.

## 4. Bounded software agents

The first orchestration version has six deterministic controller roles:

1. **Router** — classifies task complexity from available Core evidence and chooses a bounded compute profile.
2. **Evidence Analyst** — checks evidence types, provenance, source availability and missing fields before scheduling input activation.
3. **Hypothesis Manager** — creates and tracks a bounded set of candidate interpretations/solution paths; it must not invent evidence.
4. **Critic** — checks candidates against supporting/conflicting evidence and uncertainty signals; it can request one additional bounded analysis pass.
5. **Decision Arbiter** — selects a supported candidate or abstains; raw membrane values or synaptic means are not calibrated probabilities.
6. **Memory/Tool Planner** — selects approved memory references or proposes read-only tool steps. It cannot perform side effects or grant itself permissions.

These are bounded state machines operating over typed records. They do not call one another recursively. A single orchestrator controls ordering, budgets and cancellation.

## 5. Request pipeline

1. Preserve the exact original user message.
2. Run JARVIS Core understanding and reasoning-plan extraction. Mark heuristic fields as heuristic and retain their source.
3. Collect only available typed evidence: user message structure, Core intent/entities/ambiguity, plan metadata, relevant conversation references, user-approved memory references, and verified tool results.
4. The Evidence Analyst rejects malformed or unsupported fields. Missing evidence remains missing.
5. The Router selects a profile: FAST, STANDARD, DEEP or RESEARCH. Profiles differ by active population limits, maximum event count, analysis rounds, candidate count and deadline. The initial profile values are configuration proposals, not performance claims.
6. The network processes sparse, deterministic, versioned events. Typed population paths allow evidence to activate associations, working state and candidate groups; critic signals can feed back into candidate/goal state within a fixed round budget.
7. The Critic evaluates the current candidate set. At most one additional pass is permitted in V1; later increases require measured latency and resource evidence.
8. The Decision Arbiter emits a structured decision or an explicit abstention.
9. Qwen receives the original request, Core context, structured decision, approved memory excerpts/references and applicable response constraints. Neural outputs remain advisory and are never treated as verified facts or action authorization.
10. The response is returned. Any tool operation remains a separate permission-gated path; a proposed plan is not an executed action.

## 6. Evidence and decision contracts

Every encoded feature must include:
- schema/version;
- stable feature-family and feature identifier;
- source kind (raw-message structural feature, Core field, plan field, approved memory reference, verified tool result);
- source reference or bounded provenance token;
- evidence status (observed, heuristic, user-provided, verified-tool-result);
- bounded numeric activation and timestamp/turn identity.

Do not use a hash of arbitrary text as proof of semantic meaning. Hashing may provide stable indexing for a declared feature family, but its provenance and interpretation must remain explicit. Character counts, punctuation and lexical-shape signals are structural signals only.

The V1 decision object must include:
- schema and topology versions;
- request/turn identifier;
- inferred task goal and its evidence references;
- bounded candidate list with support/conflict references;
- uncertainty and missing-information fields;
- selected candidate or abstention reason;
- suggested response structure and constraints;
- active population IDs/counts, processed event count and budget-exhaustion status;
- advisory confidence metadata with calibration status explicitly marked uncalibrated until measured;
- approved memory references only.

The decision object must be size-limited before prompt construction. It must not contain unapproved private memory or raw hidden state dumps.

## 7. Network and storage invariants

- Preserve a hard ceiling of 10,000 logical neurons and sparse directed connectivity; no dense N×N matrix.
- Preserve bounded connection, synapse, queue, timestamp-batch and per-run event limits.
- No population may directly inject a final answer or bypass documented propagation/readout paths.
- Use deterministic topology generation with a versioned seed and stable IDs.
- Keep ALIF-DT, delay, eligibility and reward equations versioned; topology changes must not silently reinterpret persisted state.
- V2 uses a new topology/database namespace. Do not destructively rewrite or reinterpret the existing V1 SQLite database.
- Provide explicit initialization, integrity validation, rollback/abstention and migration/reset behavior before routing ordinary chat through V2.
- Preserve atomic timestamp publication: changed neuron state, consumed/generated events and model clock commit together before in-memory cache publication.
- Keep the active resident set bounded. SQLite/flash is persistence, not active RAM.
- Do not enable the new graph by default until correctness tests and named-device profiling pass.

## 8. Learning policy

- Ordinary conversation does not automatically reward or punish synapses.
- Only explicit user feedback, explicit expected labels, or independently verified outcomes may produce reward.
- Every reward must bind to the exact turn, topology version, decision version and eligibility trace IDs.
- Duplicate, stale, cross-turn or already-consumed feedback must be rejected idempotently.
- Keep supervised labels, explicit reward and inferred outcomes separate.
- No automatic modification of Qwen weights is implied.

## 9. Evaluation gates

The hybrid network is not accepted merely because all 10,000 IDs are allocated. Each population must demonstrate meaningful participation: it receives correctly typed evidence, propagates through documented edges and changes a measurable readout under controlled perturbation.

Required offline fixtures:
1. Deterministic topology/readout for identical input, seed and version.
2. Missing or malformed evidence leads to abstention or safe fallback, never fabricated fields.
3. Each population has at least one positive participation fixture and one ablation fixture.
4. Removing a relevant population or connection measurably changes the expected decision; irrelevant perturbations do not.
5. Candidate/critic feedback stays within a fixed round/event/time budget.
6. No input can directly stimulate final readout without the declared paths.
7. Reward attribution is limited to the originating turn and trace.
8. Persistence/reopen preserves topology, clock, state, weights and pending events.
9. Decision serialization respects prompt and memory-privacy limits.
10. Held-out comparison: Core+Qwen baseline vs Core+legacy-ANS+Qwen vs Core+hybrid-ANS+Qwen. Report task accuracy, abstention quality, calibration, latency, peak RAM, CPU, battery/thermal observations and model-token overhead.

No claim of better reasoning or generalization is allowed without held-out results. No Android performance claim is allowed without measurement on a named target device.

## 10. Rollout sequence

**Phase 0 — contract and inventory (current):** record the hybrid decision, reconcile current topology documentation with source, map existing Core/ANS/Qwen boundaries.

**Phase 1 — typed evidence contract:** implement Kotlin data classes, deterministic bounded encoder and fixtures. Keep V1 graph and current reply path unchanged.

**Phase 2 — decision object and Qwen handoff:** add bounded serialization and prompt integration behind a disabled-by-default feature flag. Preserve the original request and fallback path.

**Phase 3 — V2 topology builder:** create deterministic population ranges and sparse typed connectivity in a separate database namespace. Add topology inspection and migration/reset handling.

**Phase 4 — bounded agent orchestration:** implement Router, Evidence Analyst, Hypothesis Manager, Critic, Decision Arbiter and Memory/Tool Planner as deterministic bounded controllers. Integrate one population at a time.

**Phase 5 — explicit learning and persistence audit:** bind feedback to exact traces/turns and validate durable atomicity.

**Phase 6 — offline evaluation and device profiling:** compare ablations and measure resource use. Enable the hybrid path only if it improves held-out outcomes within the Android budget.

## 11. Non-goals and safety

- No claim of consciousness, human-like thought, emotions or GPT-6-equivalent ability.
- No claim that 10,000 logical neurons are 10,000 simultaneously active or independently intelligent units.
- No unrestricted autonomous actions, arbitrary shell, external side effects or self-granted permissions.
- No replacement of Qwen as the natural-language generator in this architecture.
- No builds, tests, CI, GitHub Actions, APK/AAB generation or device workflows without separate explicit user authorization.
- All implementation remains on `experiment/jarvis-integrated-prototype-v1`; `main` is untouched.


## 12. Phase 1 implementation status

**Implemented in source (not yet integrated into the live conversation/network path):**
- `app/src/main/java/org/jarvis/ans100/alpha/data/TypedEvidence.kt`
- `app/src/test/java/org/jarvis/ans100/alpha/data/TypedEvidenceTest.kt`

The encoder emits deterministic, bounded `JARVIS_TYPED_EVIDENCE_V1` records from the raw message and existing `LocalUnderstanding`/reasoning-plan fields. Every Core-derived field is marked HEURISTIC. Raw user text, observed message structure, approved-memory references and verified-tool results have distinct source/status labels. Feature identifiers are declared constants, not hashes of arbitrary text. Bundle validation enforces turn isolation, unique record IDs and a 64-record maximum. Raw input is bounded at 2,048 characters.

This is a data contract and adapter, not evidence that ANS-100 semantically understands language. It is not yet connected to the neural network or Qwen prompt. The current Android conversation path is unchanged. Memory/tool input wrappers are internal adapter contracts, not cryptographic proof of approval or tool verification.

Static readback only. Tests were added but not run; no build, CI, workflow, APK/AAB or device execution was performed.


## 13. Phase 2 implementation status — decision contract and handoff builder

**Implemented as additive source (not yet connected to live inference):**
- `app/src/main/java/org/jarvis/ans100/alpha/data/ANS100Decision.kt`
- `app/src/test/java/org/jarvis/ans100/alpha/data/ANS100DecisionTest.kt`

The versioned `ANS100Decision` contract supports up to eight candidates, same-turn supporting/conflicting evidence references, selected/clarification/abstention outcomes, explicit uncertainty and missing-evidence fields, response goals/constraints, active-population metadata, processed-event counts and compute-budget exhaustion. It rejects invalid status combinations, unknown/cross-turn evidence references and any V1 claim of calibrated confidence.

`ANS100QwenHandoffBuilder` preserves the exact original user message and validates it against the typed evidence bundle. It packages bounded Core heuristic context, decision context, approved-memory references only, and provenance-labeled verified-tool summaries in a 4,096-character maximum context. Qwen is explicitly the natural-language realizer, not an action executor or source of invented evidence.

**Not yet done:** no call site in `LiteRtLmResponseEngine`/conversation coordinator, no neural population producer, no memory/tool adapter, and no rollout flag. Existing runtime behavior is unchanged. The next phase must integrate behind a disabled-by-default flag, preserve fallback, and collect measured evidence before enabling the hybrid path.

Tests were added but not executed. No Gradle build, APK/AAB, CI, GitHub Actions, benchmark or device run was performed.


## 14. Phase 2 integration status — gated legacy bridge

Added `ANS100DecisionPolicy` and wired its output through `LiteRtLmResponseEngine` and `PromptContextBudgeter` behind the persisted `ans100_decision_handoff_enabled` flag, whose default is false. The adapter is explicitly marked `CORE_POLICY_V1` / `LEGACY_DIALOGUE_READOUT_V1`: it uses Core's current clarification heuristic and original user-message evidence. Existing neural readout measurements contribute event telemetry only; neural labels/scores are not promoted to semantic decisions or probabilities.

The handoff is built only for messages within the typed-evidence input limit and is appended only when the current estimated prompt budget allows it. Otherwise the existing Qwen prompt path is preserved. The fallback response path and normal default behavior remain unchanged. The flag currently has no user-facing settings control.

This is an integration bridge, **not** the selected 10,000-neuron hybrid cognitive architecture. No new populations, recurrent semantic association, candidate/critic network, learning update, or hybrid decision controller has been implemented. The hybrid controller must replace this conservative policy only after its population topology, event budgets, evidence attribution and ablation evaluation are implemented.

Tests were added for policy labeling and optional prompt inclusion but were not executed. No build, CI, workflow, APK/AAB, benchmark or device run was performed.

## 15. Phase 3 implementation status — isolated hybrid V2 topology

Added:
- `app/src/main/java/org/jarvis/ans100/alpha/data/AndroidHybridTopologyV2.kt`
- `app/src/androidTest/java/org/jarvis/ans100/alpha/data/AndroidHybridTopologyV2Test.kt`

The builder defines the seven contiguous stable ID ranges from the allocation table and creates 27,000 deterministic sparse directed connections under the 100,000-edge limit. The graph includes evidence→association, association recurrence and association→working, working recurrence and working→candidate/goal, candidate→critic, critic→candidate feedback, and candidate/critic/goal→decision paths. There is no direct evidence→decision shortcut. In the version-3 calibration, feed-forward edges use 1.0 to meet the current LIF threshold; association/working recurrence and critic-to-candidate feedback use 0.18 to remain subthreshold for a single incoming pulse. These values are propagation-safety starting values, not learned or empirically optimized parameters.

The version-3 initializer uses the separate `jarvis_ans100_hybrid_v3.db` SQLite database. Neuron rows, edges and topology identity/version metadata are created in one SQLite transaction. Reopening the same initialized topology is idempotent; a different or incomplete pre-existing database is rejected rather than cleared or migrated. The legacy dialogue database and its learned state are not used by this initializer.

**Important boundary:** this phase creates and stores a topology only. It does not yet activate the seven populations from typed dialogue evidence, implement the six controller roles, establish useful learning, or connect V2 readouts to `ANS100Decision`/Qwen. The factory is not called by normal app startup, so the default app path remains unchanged.

**Verification:** instrumentation tests were authored for stable population ranges, deterministic edge generation, sparse bounds, idempotent initialization and legacy-database isolation. Tests have NOT been run. No Gradle build, APK/AAB, CI, workflow, benchmark or device run was performed.


## 16. Phase 4 implementation status — typed evidence input mapping

Added:
- `app/src/main/java/org/jarvis/ans100/alpha/data/AndroidHybridEvidenceInputAdapter.kt`
- `app/src/test/java/org/jarvis/ans100/alpha/data/AndroidHybridEvidenceInputAdapterTest.kt`

The adapter creates a deterministic activation batch from a same-turn `TypedEvidenceBundle`. Its explicit feature-order table assigns the 22 current feature families to 48-slot regions within the evidence/input population. Each repeated feature uses a bounded ordinal; boolean false and true values use separate paired slots. The current 22 feature families occupy IDs 0–1,055, leaving 144 input IDs reserved. Feature order is a persisted mapping contract: existing entries must not be reordered.

Only records with a positive activation hint produce events. Each event is a 1.0 presence pulse because the current LIF threshold is 1.0; the original activation hint is preserved in the trace but is not interpreted as confidence. Source/status combinations are checked before mapping. The trace records turn, record ID, feature ID, source/status, value kind, provenance reference and activation hint. Event IDs include a SHA-256-derived turn token to avoid collisions between different turns scheduled at the same model timestamp. The version-3 feed-forward edges now match the LIF threshold so an individual presence pulse can traverse each forward stage; recurrent and critic feedback edges remain subthreshold to avoid a self-sustaining loop.

This is now callable through `AndroidSparseNeuronNetwork.scheduleHybridEvidence(batch)`. Before enqueueing, the network checks the persisted V2 topology ID/version and exact 10,000-neuron/27,000-edge counts; legacy dialogue databases are rejected. It accepts at most 64 same-timestamp input events and refuses to queue a new turn while events from the prior turn remain pending. SQLite still provides the atomic publication boundary for the queued batch.

**Connected to the live Android send path as an experimental telemetry sidecar, not as a decision-maker:** JarvisViewModel lazily opens the separate hybrid database on the native-network executor, builds typed evidence from the original message and current Core heuristics, invokes beginHybridTurn(batch), and runs a bounded number of passes. The seven-population activity summary is added to DialogueNeuralAnalysis.toModelContext(), which is included in the Qwen response context. Sidecar errors are isolated so the normal conversation continues. ANS100DecisionPolicy remains CORE_POLICY_V1; hybrid population activity does not yet select candidates or decide answers. A new beginHybridTurn(batch) API enforces a drained queue, resets membrane potential/adaptation/refractory state at the current model clock, preserves learned connection weights and monotonic spike sequences, then schedules the typed batch. Hybrid telemetry accumulates across partial run() calls on the same network instance and is reset at the next turn boundary; it is in-memory only and is not recovered after process death/reopen. Per-turn trace persistence/reward attribution remain unimplemented. Non-boolean text/category/numeric contents are not semantically decoded or hashed into neuron IDs; the adapter signals only typed feature presence (and boolean polarity). Therefore this stage does not demonstrate semantic understanding or meaningful population-level reasoning.

JVM tests were authored for feature coverage, stable bounded IDs, boolean polarity, deterministic batches, provenance, zero-activation omission, invalid source/status rejection and record-budget enforcement. Instrumentation tests were authored for versioned hybrid-only scheduling, pending-turn backpressure and legacy-database rejection. Tests were NOT run. No build, CI, APK/AAB, benchmark or device run was performed.


## 17. Signal propagation calibration — hybrid topology version 3

The first static review found that version-2 feed-forward weights (0.35) were below the shared LIF base threshold (1.0). A single upstream spike therefore could not reliably activate the next population. The calibration revision:
- moves the default hybrid database to jarvis_ans100_hybrid_v3.db and increments the persisted topology version to 3, leaving the previous V2 database untouched;
- sets forward projection weights to 1.0 so a single verified spike can activate its next-stage target;
- keeps association recurrence, working-state recurrence and critic-to-candidate feedback at 0.18, below the single-input threshold, so feedback does not independently sustain a spike loop;
- adds an instrumentation fixture that sends one typed input and checks that all seven populations emit at least one spike, the event queue drains, and the event budget is not exhausted.

This calibration was initially authored with source-level instrumentation fixtures only. The hybrid scheduler is now connected to the live conversation route as described in section 18. The topology propagation, turn isolation and cumulative-telemetry instrumentation fixtures have NOT been run. No build, test, CI, APK/AAB, benchmark or device execution was performed.


## 18. 2026-11-15 hybrid dialogue decision handoff

`JarvisViewModel.sendConversationMessage()` excludes the legacy three-layer dialogue graph/classifier from ordinary message processing and runs the seven-population hybrid graph. Legacy code and its separate SQLite data are retained, not deleted or migrated.

`ANS100HybridPopulationController` consumes the current topology's cumulative per-turn telemetry. It requires at least one spiking neuron in every declared population and a drained event queue before forwarding the Core-proposed candidate as selected. If the queue is still pending, the event budget is exhausted, or any population did not spike, the controller returns an explicit abstention. Core still proposes the only candidate and determines whether clarification is needed. The network does not yet decode text meaning, rank learned semantic hypotheses, or provide calibrated confidence; this controller is a bounded participation gate, not independent semantic reasoning.

The resulting turn-bound `ANS100QwenHandoff` is attached to `DialogueNeuralAnalysis`. `LiteRtLmResponseEngine` prefers this prebuilt hybrid handoff over the older opt-in Core-policy fallback, and `PromptContextBudgeter` reserves space for the handoff before adding optional history, Core details or telemetry; it can still omit the handoff if the base prompt and current user message exhaust the model context budget. Qwen is instructed to preserve abstention and clarification rather than bypass them.

The legacy classifier and its feedback controls/ViewModel methods have been removed. The old three-layer graph's storage/runtime code remains dormant in the shared sparse-network implementation to preserve historical SQLite state; it is no longer opened by startup, ordinary chat or settings UI. A JVM test file covers the population gate's selected, abstained, budget-exhausted and clarification paths. These tests are authored but NOT run. All changes are source-level only; no build, test, CI, APK/AAB, benchmark or device validation was performed.

## 19. 2026-11-15 bounded candidate competition

The live controller now exposes three bounded alternatives — direct response, clarification, and abstention — with same-turn support/conflict evidence references. A deterministic critic/arbiter applies transparent integer ranking rules over typed Core evidence. The rules are explicitly software-controller heuristics, not learned neural scores or probabilities. Missing usable Core intent/task/goal evidence causes abstention; a positive clarification flag conflicts with the direct-answer candidate and supports clarification. The existing seven-population completion gate remains mandatory.

**Architecture boundary:** the current graph's candidate, critic and decision populations are still connected by generic deterministic projections. They do not yet contain candidate-specific slots or semantic candidate identity, and telemetry is not attributable to a particular candidate. Thus this phase makes the decision layer compare multiple inspectable candidates, but does not yet prove that neurons themselves compare semantic hypotheses. Candidate generation/ranking still depends on heuristic Core evidence and explicit controller rules.

The next architecture step is a versioned candidate-slot/readout mapping with dedicated bounded sparse routes, followed by tests that remove or perturb one candidate's evidence and demonstrate the expected candidate-specific neural/readout change. The current changes have source-level tests authored but NOT run; no build, CI, APK/AAB, benchmark or device validation was performed.
