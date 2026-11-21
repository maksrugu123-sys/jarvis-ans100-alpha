# ANS-100 benchmark adapter V1

## What this stage adds

- The repository already contains `ANS100BenchmarkTraceAdapter.kt`, which emits a privacy-bounded JSON trace from actual Android evidence, controller decision, LIF spikes, activated synapses, association candidates, population activity and optional measured inference duration.
- This stage adds `tools/task_validation/ans100_benchmark_adapter_v1.py`, which exports model-only inputs and validates the existing Kotlin trace schema.
- Model-only rows contain exactly schema, task ID, exact input text and SHA-256 of that text. Evaluator labels, facts metadata and split labels are stripped.
- The adapter checks that each trace hash matches the exact task input. It converts measured nanoseconds to milliseconds and copies peak memory only if actually supplied.
- A scorer-compatible answer is emitted only if both `answer_emission` and `decision.producer` explicitly equal `NEURAL_CANONICAL_READOUT`, and the answer is YES / NO / UNKNOWN / CONFLICT.
- Regression tests cover the current Kotlin trace shape, leakage stripping, provenance enforcement, hash matching and measured resource validation. Tests are authored but not run.

## Current production limitation

The Kotlin trace adapter is telemetry-only: it sets `answer=null` and `answer_emission=NOT_SUPPORTED_BY_CURRENT_ANS100_PIPELINE`. It explicitly records that Core supplies heuristic understanding and candidate support/conflict signals. The controller's SELECTED, NEEDS_CLARIFICATION and ABSTAINED statuses are policy-level decisions, not semantic benchmark answers.

The adapter is now called from the real `JarvisViewModel` hybrid dialogue pass. It writes app-private JSONL traces to `files/ans100_benchmark/android_traces_v1.jsonl`, with a 256-record cap and no raw user message. Trace capture is best-effort and does not interrupt the conversation. The trace still has a null canonical answer, so these records are telemetry and not scored ANS predictions.

## Commands

Generate the shared task set using the benchmark generator, then export gold-free inputs:

```sh
python3 -m tools.task_validation.ans100_benchmark_adapter_v1 export-inputs --tasks benchmark/transformer_vs_ans100_tasks.jsonl --output benchmark/model_inputs.jsonl
```

Validate JSONL rows produced by the Android Kotlin adapter:

```sh
python3 -m tools.task_validation.ans100_benchmark_adapter_v1 adapt-ans-traces --inputs benchmark/model_inputs.jsonl --traces benchmark/ans100_traces.jsonl --output benchmark/ans100_predictions.jsonl
```

## Trace fields consumed

The validator consumes the actual Kotlin schema: `schema`, `task_id`, `input_sha256`, `answer`, `answer_emission`, `decision.producer`, `decision.status`, and optional `measurement.inference_duration_ns` / `measurement.peak_memory_mb`. It accepts additional telemetry fields without using them as semantic answers.

The present Kotlin adapter's null answer is retained as an unscored trace. The validator never infers YES/NO from controller status, spike counts, activation hints or association candidates.

## What this does not claim

No Android inference change, automatic trace capture, Transformer inference, ANS accuracy score, model winner, APK or resource measurement was produced. This is a leakage guard and trace validation boundary, not proof of autonomous reasoning. No tests, builds, CI or GitHub Actions were run.


## Android runtime capture added

The ViewModel now calls the trace adapter after the actual sparse LIF pass and decision-provenance construction. It records observed spikes, activated synapses, population telemetry, Core heuristic provenance and measured ANS-pass elapsed time. The private JSONL store retains at most 256 rows, rejects raw-message fields, and performs no network upload. Peak memory remains unmeasured/null.

## Neural hypothesis lifecycle

A new bounded engine forms a hypothesis only from a shared association target that spiked and was reached by both concept paths. On a later independent completed turn, it records whether the same pair's shared-spike signal was reproduced. A non-reproduction is not treated as factual contradiction; incomplete turns and potentially truncated candidate scans cannot create negative verification. State is in-memory and bounded, not durable.

The new hypothesis status is included in benchmark telemetry. It is not promoted to a canonical answer and is not supplied to Qwen as a decision. Core still prepares upstream lexical concept activations and typed evidence; this is a first network-driven hypothesis lifecycle, not end-to-end autonomous semantic reasoning.

**Tests authored, NOT RUN:** neural hypothesis generation, independent-turn reproduction, non-reproduction semantics, duplicate/incomplete turn handling, and bounded privacy-safe trace storage. No Gradle tests, Android build, APK, CI, GitHub Actions or device inference were run.
