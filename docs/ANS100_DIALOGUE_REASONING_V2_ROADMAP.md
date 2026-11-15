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
5. **Seven-population topology — source implementation through version 4, not runtime-verified.** The stable population ranges remain 1,200 / 2,800 / 1,800 / 1,500 / 1,200 / 900 / 600. Version 3 used 27,000 sparse edges; version 4 uses 25,500 deterministic sparse edges and partitions the association/working/candidate/critic/readout paths into six candidate lanes (answer/support, answer/conflict, clarification/support, clarification/conflict, abstain/support, abstain/conflict). The default database is now jarvis_ans100_hybrid_v4.db; V3 and older namespaces are left untouched. Feed-forward weights remain 1.0 and recurrent/critic-feedback weights 0.18. The initializer remains atomic and refuses to overwrite a different or incomplete topology. Per-population telemetry still accumulates across partial run() calls within one active turn and is reset at the next turn boundary; it is in-memory only, not persisted across process death/reopen. A new instrumentation fixture checks lane isolation and single-candidate routing, but it has NOT been run.
6. **Typed evidence, candidate-slot routing and Qwen handoff — connected to the live Android send path, source-only.** The evidence adapter maps the 22 existing feature families to IDs 0–1,055 and six explicit candidate support/conflict features to reserved input IDs 1,056–1,061. Candidate labels are produced by the heuristic Core and retain HEURISTIC provenance. Version-4 topology sends ordinary evidence through a shared analysis lane and the six candidate signals through isolated sparse routes to separate candidate, critic and readout ranges. JarvisViewModel accumulates emitted spike IDs across bounded passes; the controller compares per-candidate support/conflict readout spikes and abstains if any expected lane is missing, unexpected or incomplete. The resulting evidence-bound decision is handed to Qwen. This is not learned semantic reasoning: the network propagates typed Core labels and counts lane-specific spikes; it does not independently understand the text or learn the labels. Tests are authored but NOT run; no runtime/device behavior is verified.
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

The resulting evidence-bound `ANS100QwenHandoff` is carried in `DialogueNeuralAnalysis` and preferred by `LiteRtLmResponseEngine` even when the older optional Core-policy handoff setting is off. Prompt packing now reserves space for this handoff before adding optional conversation history, Core details or telemetry; it still omits the handoff if the current prompt plus base instructions leave insufficient model context. Qwen is instructed to preserve abstention/clarification and not execute tools or actions.

The legacy classifier and its feedback UI/ViewModel entry points have been removed. The old three-layer SQLite graph implementation remains dormant inside the shared network runtime so its historical database is not destroyed; no startup, normal-chat or settings UI path opens it. A JVM test file now covers complete-population selection, missing-population abstention, pending-queue/budget abstention and clarification preservation; these tests are authored but NOT run. The implementation is source-only: no Gradle build, tests, CI, APK/AAB, benchmark or device run was performed.

## 2026-11-15 bounded candidate competition — source implementation

`ANS100HybridPopulationController` now builds three explicit alternatives for each eligible dialogue turn: answer the original request, ask for clarification, or abstain. Each candidate carries same-turn typed evidence references. The critic rules use the Core clarification flag as conflicting evidence against a direct answer, and the arbiter ranks candidates using small, deterministic integer rule scores. These scores are internal ranking heuristics only: they are not probabilities, learned weights, or neural activations. Missing usable Core intent/task/goal evidence leads to abstention. The seven-population graph remains a required complete-path gate, and incomplete population activity or a non-drained queue still forces abstention.

This is an incremental step from a single Core candidate to an inspectable three-candidate decision contract, but it is **not yet neural hypothesis competition**. The current topology has generic population-to-population projections; candidate identity is not encoded in neuron IDs or dedicated subnetworks, and per-population telemetry is not candidate-specific. Candidate generation and ranking still use the heuristic Core pipeline plus transparent software-controller rules. The network has not learned semantic representations or selected between candidate meanings.

A JVM test suite now covers three-candidate output, clarification evidence used as a critic conflict, missing Core evidence, missing population activity, pending events and explicit budget exhaustion. Tests are authored but NOT run. No build, CI, APK/AAB, benchmark or device validation was performed.

**Next required stage:** introduce a versioned candidate-slot contract and candidate-specific sparse routes/readout, feed candidate/evidence feature activations through the declared input path, and prove with controlled perturbation/ablation that changing candidate evidence changes the corresponding candidate/readout activity. Do not call this learned reasoning until those tests and held-out evaluations pass.


## 2026-11-15 candidate-specific neural routing — topology version 4

Added `ANS100CandidateSlotContract.kt` and six typed candidate support/conflict features. The six candidate inputs occupy reserved evidence-input IDs 1,056–1,061. Ordinary evidence (IDs 0–1,055) is routed into a shared association/working lane. Each candidate polarity receives its own association, working, candidate and critic lane; the final decision population is divided into three candidate slots, each with a support and conflict readout half. No candidate lane has a cross-lane edge. The graph has 25,500 deterministic edges, remains below the 100,000-edge cap, and keeps 1.0 feed-forward / 0.18 recurrent weights.

The database was bumped to `jarvis_ans100_hybrid_v4.db` and topology version 4. V3, V2 and legacy databases are not reused, cleared or migrated. The topology ID remains `ans100-hybrid-v2` for compatibility with the existing initializer API; the persisted topology version and database namespace identify this v4 revision.

The encoder derives exactly one support-or-conflict signal per candidate from the existing Core heuristic fields. The six signals are explicitly HEURISTIC; they are not learned by the network. The graph propagates them through isolated sparse routes. `JarvisViewModel` collects emitted neuron IDs across all bounded passes. `ANS100HybridPopulationController` compares actual spiking neurons in each candidate's separate support/conflict readout halves, validates that the observed output matches the expected lane, and abstains on missing/mismatched lanes, incomplete populations, ties, or exhausted budgets. The readout counts are discrete activity measurements, not confidence or probabilities.

New JVM tests cover candidate readout arbitration, clarification/conflict routing, ties, missing lanes, missing populations and event-budget abstention. New Android instrumentation tests cover six-lane isolation, candidate-specific readout and all-population propagation. Tests are authored but NOT run. No Gradle build, CI, APK/AAB, benchmark or device validation was performed. The next scientific step is to replace Core-authored support/conflict labels with independently derived evidence features and train/evaluate the candidate/critic pathways on held-out data, using ablations to prove causal contribution.

## 2026-11-15 candidate-specific sparse routes — source implementation

Hybrid topology storage is now version 4 in a separate database, with six typed candidate input IDs (support/conflict for answer, clarification and abstention). Each candidate/polarity has isolated association, working-state, candidate, critic and readout ranges. Candidate lanes have bounded projections through their own path; the readout population is partitioned into six disjoint 100-neuron ranges. The current deterministic topology contains 25,500 edges, below the 100,000-edge cap. The legacy graph database is not migrated or overwritten.

TypedEvidenceEncoder emits six Boolean candidate signals from the existing Core heuristics. AndroidHybridEvidenceInputAdapter maps those signals to reserved input IDs 1056–1061. The decision controller counts actual emitted spikes in the six readout ranges and compares support-minus-conflict spike counts. The ViewModel accumulates emitted spike IDs across bounded processing passes before making the decision.

This is a real candidate-specific signal-routing/readout path, but it is NOT learned semantic reasoning: Core creates the support/conflict labels using heuristics, the topology is deterministic and untrained, and readout spike counts are not calibrated confidence. The controller abstains if any candidate readout is missing, any required population is inactive, or the queue/budget is incomplete.

Source-level tests were added for isolated candidate routes, readout-range separation, one-candidate propagation and symmetry across all six lanes. Tests are authored but NOT run. No build, CI, APK/AAB, benchmark or Android-device validation was performed.
