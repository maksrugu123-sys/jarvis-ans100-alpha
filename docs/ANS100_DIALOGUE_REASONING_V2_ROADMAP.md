# ANS-100 Dialogue Reasoning V2

Status: architecture and implementation contract; not a claim that V2 is implemented.

Target branch: `experiment/jarvis-integrated-prototype-v1`

## Goal

Make ANS-100 a measurable analysis and decision-support stage before the language model. Qwen remains responsible for fluent natural-language realization. ANS-100 must contribute structured, inspectable evidence that changes the prompt in a useful way; it must not be represented as independently understanding arbitrary language until controlled evaluations demonstrate that capability.

## Current source-level implementation (not runtime-verified)

The Android conversation path now has the following source-level behavior:
- obtains up to 128 active hashed word/bigram feature IDs from the separate dialogue feature classifier;
- maps them into 9,000 input neurons in a persistent 10,000-node SQLite/JNVM network;
- propagates events through 996 LIF-DT hidden neurons, then into four output/readout neurons (greeting, question, thanks, farewell), using 75,984 sparse directed connections;
- computes mean learned hidden-to-output synaptic weights for active hidden neurons and passes those values, active counts, event metrics, and any confident explicitly trained classifier candidate to LiteRT-LM as advisory context alongside the original request and Core plan;
- records eligibility only for hidden neurons and output neurons that actually spiked; explicit user feedback can apply bounded R-STDP reward to the matching hidden-to-output synapses;
- upgrades the former direct feature-to-output topology in place by projecting old readout weights through the deterministic input-to-hidden mapping, clearing stale events/eligibility and resetting repurposed hidden states.

The four readout scores are mean synaptic weights, not probabilities or semantic decisions. Hashed tokens/bigrams are a structural feature encoding, not a semantic embedding. The separate four-class feature classifier and the spiking network remain distinct components and their outputs must remain separately labelled. The source changes and tests are not runtime-verified yet.

## V2 architecture

### 1. Semantic evidence encoder

Create a deterministic, bounded Android encoder that converts already-available, explicitly named upstream evidence into typed features. Candidate sources:
- the user's raw message (length, punctuation, language-independent structural signals);
- Core understanding output (intent, entities, ambiguity/clarification flags, when present);
- reasoning-plan metadata (goal, ordered step types, dependency/unknown counts, when present);
- conversation context and user-approved memory references.

Do not pretend that hashed token IDs or character counts encode meaning. Every feature must carry a source and feature-family identifier. Missing Core evidence must remain missing, not be fabricated. Bound feature count and payload size per turn.

### 2. Sparse recurrent ANS-100 network

Evolve the current fixed input-to-four-output classifier in small, reviewable stages:
- retain the 10,000-node budget and sparse bounded connectivity;
- introduce typed populations for input/evidence, association, working-state, candidate, critic/uncertainty, and readout roles;
- use recurrent/feedback paths only with explicit fan-in/fan-out and event/time budgets;
- preserve ALIF-DT state, synaptic delay, eligibility traces, and JPVR/JNVM persistence;
- activate a bounded subset per request; never allocate all-to-all connectivity or run an unbounded event queue;
- initialize role-specific topology and weights deterministically with a versioned seed so experiments are reproducible;
- version the network schema and provide a safe migration/reset path before changing persisted topology.

Do not count placeholder nodes as reasoning capacity. A node population counts as participating only when it receives meaningful input, propagates signals through documented connections, and can affect a measured readout.

### 3. Structured decision/readout

Replace the four-label-only handoff gradually with a versioned decision object containing:
- interpreted task/intent evidence and its provenance;
- candidate response goals or solution paths;
- supporting and conflicting evidence references;
- uncertainty and missing-information indicators;
- suggested answer structure and constraints;
- bounded memory references (never raw unapproved private memory);
- network version, active populations, event budget, and confidence calibration metadata.

The readout must be evidence-bearing and bounded. Do not describe raw synaptic means as probabilities. If the network has no reliable candidate, it must explicitly abstain and let Core/Qwen use the ordinary safe path.

### 4. Qwen handoff

The response pipeline should pass the structured ANS-100 decision alongside Core understanding, reasoning-plan evidence, approved memory, and the original user request. Qwen should:
- answer the user's actual request;
- follow the selected response goal/structure when supported;
- preserve uncertainty and ask a clarification when required;
- never treat an ANS-100 suggestion as verified fact or an authorization to perform an action.

Keep the original user message available. Neural context is advisory, not a replacement for the message or safety checks.

### 5. Learning

Keep supervised labels and explicit user feedback distinct from inferred reward:
- record the exact decision version and trace IDs used for each turn;
- reward only the relevant trace and only from explicit feedback or verified outcomes;
- prevent duplicate, stale, or cross-turn rewards;
- use bounded updates and persist learning metadata;
- do not claim Qwen weights are trained by ANS-100.

## Evaluation gates

Before enabling a new reasoning population in the default Android chat path, add deterministic unit tests and offline evaluation fixtures for:
1. identical input + identical network version/seed => identical initial topology and readout;
2. missing or malformed Core evidence => safe abstention, no fabricated feature;
3. input cannot directly stimulate output/critic populations;
4. propagation respects event, timestamp, fan-in, fan-out, and memory budgets;
5. output changes when learned weights/evidence change, and remains unchanged for irrelevant perturbations;
6. reward affects only the matching turn's eligibility trace;
7. persistence/reload preserves topology version, state, and weights;
8. decision serialization stays within prompt budget and does not expose unapproved memory;
9. ablation: compare Core+Qwen against Core+ANS-100+Qwen on held-out tasks, with accuracy, calibration, latency, memory, and energy metrics.

No claim of improved reasoning is accepted without the ablation results. Android device performance must be measured on real target hardware before increasing defaults.

## Implementation status and next sequence

1. **Architecture contract and baseline audit — specified.** The selected design is one sparse 10,000-neuron network split into seven typed populations, with six bounded software-controller roles. The allocation is a starting design, not a measured optimum.
2. **Typed evidence contract — implemented in source.** `TypedEvidence.kt` and its tests exist. Core-derived fields remain explicitly heuristic; raw user text and structural message features keep separate provenance. Tests have not been run.
3. **Decision object and Qwen handoff — implemented as a gated legacy bridge.** `ANS100Decision.kt`, `ANS100DecisionPolicy.kt`, and the prompt-budget integration exist. The current producer is `CORE_POLICY_V1`, not the hybrid neural network. The persisted decision-contract flag defaults to disabled and has no user-facing settings control. The bridge preserves the existing fallback path; runtime behavior has not been verified.
4. **Legacy Android network — implemented in source, not the selected hybrid topology.** It currently uses 9,000 hashed text-feature inputs, 996 LIF-DT hidden neurons and four readout neurons. Hashed features are not semantic embeddings; the four outputs are not general reasoning.
5. **Seven-population topology and signal calibration — implemented in source, not runtime-integrated or verified.** The selected graph retains deterministic ranges (1,200 / 2,800 / 1,800 / 1,500 / 1,200 / 900 / 600) and 27,000 sparse typed edges. A version-3 calibration revision uses 1.0 feed-forward pulses to cross the LIF threshold of 1.0; association/working recurrence and critic-to-candidate feedback remain subthreshold at 0.18 to avoid a single-spike self-sustaining loop. The new database namespace is jarvis_ans100_hybrid_v3.db; the previous V2 namespace is left untouched. The initializer remains atomic and refuses to overwrite a different or incomplete topology. A seven-population propagation fixture is authored but not run. The run result now has optional hybrid-only telemetry accumulated across partial run() calls on the same network instance and active turn: per population it reports consumed event count, unique neurons processed, unique neurons that spiked, total spike count and outgoing event count. Starting a new turn resets the in-memory counters. These counters are not persisted across process death/reopen. Legacy databases return no hybrid telemetry; counters are descriptive activity metrics, not confidence or semantic quality scores. The fixture checks partial-run accumulation and that all seven populations report activity and event totals reconcile, but it has not been run.
6. **Typed evidence input and hybrid dialogue sidecar — connected to the live Android send path, not yet a hybrid decision-maker.** Added AndroidHybridEvidenceInputAdapter.kt plus AndroidSparseNeuronNetwork.beginHybridTurn(). It maps 22 declared feature families into stable slots inside neuron IDs 0–1,055, uses paired slots for boolean false/true, validates source/status labels, caps a turn at 64 records, and produces same-timestamp 1.0 presence pulses plus a provenance trace. JarvisViewModel now lazily opens the dedicated jarvis_ans100_hybrid_v3.db namespace on the native-network executor, builds bounded typed evidence from the original message plus current Core heuristics, starts an isolated turn, and runs at most four bounded passes (4,096 events / 128 timestamps per pass). The resulting seven-population activity summary is passed into DialogueNeuralAnalysis.toModelContext(), which the response engine includes in the Qwen prompt context. Failures in this experimental sidecar are swallowed and the ordinary chat path continues. This is telemetry-only: ANS100DecisionPolicy still uses CORE_POLICY_V1; the hybrid graph does not select hypotheses or authorize actions. Non-boolean values are not semantically decoded; activation hints remain trace metadata, not calibrated confidence. Trace/reward persistence and a learned decision controller remain pending. Integration fixtures are authored but not run; no runtime or device behavior has been verified.
7. **Bounded agent orchestration — pending.** Implement Router, Evidence Analyst, Hypothesis Manager, Critic, Decision Arbiter and Memory/Tool Planner as bounded controllers; integrate one population and one controller path at a time.
8. **Learning, persistence audit and evaluation — pending.** Prove turn/trace-specific reward attribution, persistence/reopen correctness, per-population participation and ablations against Core+Qwen and the legacy ANS path. Measure latency, peak RAM, CPU and energy on the target Android device before enabling the hybrid path.

All source-level implementation statements above are based on static inspection. No Gradle build, unit test, CI workflow, APK/AAB, benchmark or device run was performed.

## Non-goals and safeguards

- This work does not create consciousness or human-like thought.
- 10,000 nodes do not imply 10,000 simultaneously active neurons.
- Qwen remains the language generator; ANS-100 does not modify Qwen's weights.
- Do not run builds, tests, CI, or Android workflows unless explicitly approved by the project owner.
- Do not modify `main`; all work stays on the target experiment branch until explicitly approved.


## 2026-11-15 hybrid dialogue decision handoff

The ordinary Android chat route invokes the seven-population hybrid graph and no longer runs the legacy three-layer dialogue graph/classifier during normal message processing. Legacy source and SQLite state remain preserved; they have not been deleted or migrated.

A new `ANS100HybridPopulationController` now checks whether all seven populations produced at least one spiking neuron and whether the bounded event queue drained. It may forward the Core-proposed response candidate only when that complete path is observed; otherwise it emits an explicit abstention. Core still supplies the candidate and clarification hint. The controller does not decode semantic content from neuron activity, compare multiple learned hypotheses, or claim calibrated confidence. This is a conservative network-participation gate, not yet independent neural reasoning.

The resulting evidence-bound `ANS100QwenHandoff` is carried in `DialogueNeuralAnalysis` and preferred by `LiteRtLmResponseEngine` even when the older optional Core-policy handoff setting is off. Prompt packing now prioritizes this handoff over optional telemetry. Qwen is instructed to preserve abstention/clarification and not execute tools or actions.

Legacy manual diagnostic/reward APIs remain for cleanup. The implementation is source-only: no Gradle build, tests, CI, APK/AAB, benchmark or device run was performed.