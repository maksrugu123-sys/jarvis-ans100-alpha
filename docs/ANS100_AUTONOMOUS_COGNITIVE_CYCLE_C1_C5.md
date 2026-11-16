# ANS-100 autonomous cognitive cycle — C1–C5

**Status:** C3 causal-candidate engine and integration authored on the experimental branch. Existing C1, C2, C4 and C5 components were audited and mapped into this cycle. New tests are authored, not run. No build, CI, APK or device benchmark was run.

This document distinguishes implemented mechanisms from capabilities that are still experimental. It does not claim general language understanding, causal discovery, or autonomous intelligence.

## C1 — activity-driven structural plasticity

Existing components:
- `ANS100ActivityDrivenStructuralPlasticity` observes concepts that actually fired and propagated through an association path.
- Repeated co-activation across distinct completed turns can propose a bounded shared association target.
- `AndroidSparseNeuronNetwork` persists the observation window and accepted grown synapses in SQLite.
- Growth limits constrain tracked pairs, total grown pairs, edges per concept and target fan-in.

**Current production setting:** `STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED = false` in `JarvisViewModel`. Do not enable globally until focused tests and an on-device growth/rollback experiment have been run. The code path is present but not active by default.

## C2 — persistent concept and association memory

Existing components:
- `ANS100WorkspaceConceptInputBridge` loads only user-approved concept, relation and relation-evidence records.
- Ambiguous relation endpoints and unsupported records are rejected.
- Concept IDs are assigned persistent neural input slots by the SQLite network resolver.
- Grown association synapses and their provenance are stored in the network database.
- Neural hypothesis checkpoints are stored in a separate SQLite ledger.

The workspace bridge rebuilds a bounded in-memory typed graph from approved records per turn. This is durable user-approved memory, but it is not yet a self-organizing, open-ended semantic memory learner. Model-generated and inferred content are not accepted as facts.

## C3 — causal-chain hypothesis candidates

New component: `ANS100CausalHypothesisEngine`.

- Starts only from concepts activated in the current turn.
- Produces bounded candidates from two explicitly approved `CAUSES` or `ENABLES` relations.
- Does not compose `PREVENTS`, whose direction/composition semantics are not monotonic.
- The premise edges are not counted as confirmation.
- A candidate remains `UNRESOLVED` until separate direct evidence for the proposed A→C relation exists.
- Direct support, refutation and conflict are preserved separately.
- Candidates are never written back into concept memory as facts.

The workspace bridge includes these reports in the existing hypothesis comparison and Qwen handoff. Telemetry reports candidate counts by verification status. This is auditable graph-based hypothesis generation, not neural causal discovery.

## C4 — hypothesis generation and independent checking

Existing components:
- `ANS100NeuralAssociationDecoder` creates candidates only from actual shared association-target spikes and activated synapses.
- `ANS100NeuralHypothesisEngine` tracks reproduction/non-reproduction on later completed turns.
- `ANS100HypothesisCompetitionEngine` compares neural candidates with independently prepared memory hypotheses and preserves direction.
- The causal engine adds direct-evidence checks for composed causal candidates.

A reproduced co-activation is only a repeatable neural signal. It is not proof of semantic truth or causality.

**Evidence provenance is preserved end-to-end:** each support/refutation report now carries the originating `LearningSource`. Competition distinguishes `EXPLICIT_USER` assertions from `VERIFIED_TOOL` evidence; mixed-source evidence and unclassified evidence are not silently relabelled as user claims or verified facts. The workspace bridge currently imports its approved relation records as `EXPLICIT_USER`, so its support/refutation remains user assertion evidence unless a future trusted-tool ingestion path is explicitly added. `VERIFIED_TOOL` is an origin category, not a cryptographic attestation or proof of source independence; a real tool-ingestion path must bind it to trusted execution and auditable provenance. The trace exports source categories, never raw evidence references.

## C5 — integrated turn cycle

The existing `JarvisViewModel.runHybridDialoguePass` coordinates:
1. typed evidence encoding and local understanding;
2. user-approved concept activation and persistent slot resolution;
3. sparse neural execution and population telemetry;
4. neural association decoding and hypothesis lifecycle;
5. typed memory/causal candidate comparison;
6. bounded decision control and Qwen verbalization handoff;
7. trace persistence for later audits.

The current decision controller still receives typed Core-produced intent/task hints and candidate evidence. ANS-100 can constrain or force abstention in the current architecture, but the project has not yet demonstrated that the neural network independently understands arbitrary user language or owns every decision. Qwen remains a verbalization/model interface, but Core still supplies part of the structured input.

## Newly authored tests

`ANS100CausalHypothesisEngineTest` covers:
- unresolved causal chain candidates do not become stored facts;
- independent direct support can support a candidate;
- direct refutation and conflicting evidence remain distinct;
- `PREVENTS` chains are not composed;
- active-start filtering and output bounds.

Additional provenance tests cover user assertions, verified-tool support/refutation, mixed-source evidence and trace export.

**Test state:** AUTHORED, NOT RUN.

## Required validation before activation

1. Run the new causal engine tests and existing workspace-bridge tests.
2. Run the focused ANS-100 JVM test set.
3. Run a debug compile.
4. Only after explicit approval, enable structural auto-growth in an isolated experiment and measure growth, duplicate suppression, capacity saturation, reset/restart persistence and memory use.
5. Compare held-out accuracy/coverage with and without structural growth; do not infer improvement from synapse count.

No Gradle task, test suite, CI workflow, APK/AAB build or Android device run was executed for this change package. The production structural-growth flag remains disabled.
