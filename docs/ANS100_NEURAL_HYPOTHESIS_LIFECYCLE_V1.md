# ANS-100 Neural Hypothesis Lifecycle V1

## Purpose

This stage adds a small, bounded hypothesis lifecycle driven by the actual Android sparse-network readout. It does not turn the current system into a general autonomous reasoner.

## Generation

A hypothesis is created only from an ANS100NeuralAssociationDecoder.Candidate. That candidate requires both concept-input paths to activate connections into at least one shared association neuron that actually emitted a spike in the completed network run. Core does not supply the hypothesis pair or its verification status.

The network input is still limited by the existing upstream pipeline: concept IDs are activated through exact/lexical workspace matching, and typed evidence features are prepared by the current Core/understanding pipeline. Therefore this is network-driven association generation over supplied concept activations, not end-to-end semantic perception from raw text.

## Verification

The engine tracks a bounded set of concept-pair hypotheses across distinct completed Android turns:

- GENERATED: one completed turn produced convergent neural activation.
- REPRODUCED_ON_INDEPENDENT_TURN: a later distinct completed turn produced convergent activation for the same pair.
- NOT_REPRODUCED_ON_INDEPENDENT_TURN: both concepts were active on a later complete turn, but no shared-spike candidate was observed.

Non-reproduction is not treated as factual refutation. The engine does not mark negative evidence when the event budget is exhausted, when a turn is incomplete, or when the decoder's pair scan may have been truncated by its candidate cap. Duplicate turn IDs cannot count as independent confirmation.

The state is currently bounded and in-memory (128 hypotheses by default, 256 hard maximum; 512 remembered turn IDs). It is lost when the ViewModel/process is recreated. Durable hypothesis state and stronger evidence-checking mechanisms remain future work.

## Android telemetry capture

Every successfully completed hybrid dialogue pass now attempts to write one adapter-produced JSONL trace to app-private storage:

files/ans100_benchmark/android_traces_v1.jsonl

The trace includes actual emitted spike IDs (bounded sample plus digest), activated connection IDs (bounded sample plus digest), population activity, heuristic-input provenance, association candidates, hypothesis lifecycle counts/statuses, and measured elapsed time for the ANS-100 pass.

Privacy and bounds:

- The raw user message is used only to compute SHA-256 and character count; it is not serialized.
- No expected answer, evaluator label, split metadata, or generated assistant response is stored.
- No network upload is performed.
- Retention is capped at 256 JSONL records and 64 KiB per record.
- File-write failure is non-fatal to the conversation and is reflected as benchmark_trace_saved=false in the diagnostic summary.
- Peak memory is left null because this adapter does not measure Android process PSS/RSS.

## What this stage does not claim

- It does not produce YES/NO/UNKNOWN/CONFLICT benchmark answers.
- It does not prove that a repeated association is semantically true, causal, or useful.
- It does not remove Core from upstream text understanding/evidence preparation.
- It does not give Qwen decision authority; Qwen remains outside this hypothesis lifecycle.
- It does not enable automatic structural-plasticity growth.
- It does not persist hypothesis state across process restarts.
- No tests, Gradle build, APK, CI, GitHub Actions, or on-device inference were run as part of this repository edit.


## Typed support and counter-evidence V1

The workspace bridge now accepts explicitly approved entries in category `relation_evidence` (or `свидетельство_связи`) with this exact four-field format:

`source concept | RELATION_TYPE | target concept | SUPPORTS`

or

`source concept | RELATION_TYPE | target concept | REFUTES`

Both concept labels must resolve to exactly one approved concept. Ambiguous, missing, malformed, unapproved, multiline, and overlong entries are ignored. At most 128 evidence observations are admitted per turn. Supported relation types are the existing exact enum values (IS_A, PART_OF, CAUSES, ENABLES, PREVENTS, HAS_PROPERTY, USED_FOR).

These observations are passed to the existing typed concept-memory verifier. It can return SUPPORTED, REFUTED, CONFLICTING, or UNRESOLVED for a typed memory-graph hypothesis. A support and a counter-evidence record for the same typed relation yield CONFLICTING; a missing observation remains UNRESOLVED.

**Provenance limitation:** these workspace entries are user-entered assertions, classified as EXPLICIT_USER. They are not independent external verification and cannot be promoted to VERIFIED_TOOL merely by writing that label in the text. A future connector must supply authenticated tool provenance before an observation can be treated as externally verified.

The live Android trace now exports a privacy-bounded summary of typed memory explanations beside neural co-activation hypotheses. Labels and memory IDs are hashed; only relation type, verification status and evidence counts are exported. The two streams remain separate and neither is converted into a canonical benchmark answer. This enables later analysis of where neural associations and typed memory explanations converge or disagree without pretending that either one is already a correct answer.


## Cross-source hypothesis competition

`ANS100HypothesisCompetitionEngine` compares each neural co-activation candidate with typed memory-graph explanations whose endpoint labels match the same concept pair. It preserves direction and relation type. The result is one of:

- NEURAL_ONLY
- MEMORY_PATH_UNRESOLVED
- USER_ASSERTIONS_SUPPORT
- USER_COUNTER_EVIDENCE
- MIXED_USER_ASSERTIONS

The engine does not select a winner or convert these states into truth. "Support" and "counter-evidence" refer only to explicit user-entered workspace assertions. A reversed typed path remains a separate directional explanation. Up to 12 explanations are retained per neural candidate.

Android telemetry records the comparison status and hashed explanation IDs, relation types, direction, verification status and evidence counts. This allows benchmark analysis to ask whether a neural association is accompanied by a typed memory path, while keeping the two mechanisms separate.


## Durable neural hypothesis lifecycle V1.1

The Android ViewModel now restores and checkpoints the bounded neural hypothesis engine through a separate app-private SQLite database:

`ans100_neural_hypotheses.db`

The checkpoint contains concept IDs and matched labels, the last bounded supporting/non-reproducing turn IDs for each hypothesis, shared-spiking-target counts, and the recent set of processed turn IDs. It does **not** contain raw user messages, Qwen responses, benchmark gold labels, or tool credentials.

Persistence rules:

- One singleton checkpoint row is replaced atomically in a SQLite transaction.
- The engine retains at most 256 hypotheses (128 by default), 512 recent turn IDs, and eight supporting plus eight non-reproducing observations per hypothesis.
- Duplicate turn IDs cannot count twice after process restart because the recent turn-ID set is restored.
- A malformed, incompatible, or oversized checkpoint is ignored; the conversation continues without persistent hypothesis history.
- A failed save is non-fatal and is surfaced as `neural_hypothesis_state_persisted=false` in the live trace and diagnostics.
- The checkpoint is local application state, not a backup or a guarantee against uninstall/data clearing.

### Mixed reproduction signal

A hypothesis can now report `MIXED_REPRODUCTION_SIGNAL` when some complete independent turns reproduce shared neural convergence and other complete turns fail to reproduce it. This preserves disagreement in the network's observed behavior instead of collapsing it into either confirmation or refutation.

This status is strictly about repeatability of the neural activation path. It is not a semantic contradiction, factual counter-evidence, confidence score, or truth judgement. Incomplete turns and turns whose pair scan may have been truncated still cannot create a non-reproduction observation.

### Validation status

Regression tests were added for checkpoint round-trip, mixed-signal retention, bounded per-hypothesis history, duplicate turn rejection after restore, and trace export. They have **not been executed**. No Gradle build, APK, CI, GitHub Actions run, or on-device inference was performed for this stage.
