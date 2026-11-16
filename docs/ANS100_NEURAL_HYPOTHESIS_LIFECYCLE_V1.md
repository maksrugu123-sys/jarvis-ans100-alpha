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
