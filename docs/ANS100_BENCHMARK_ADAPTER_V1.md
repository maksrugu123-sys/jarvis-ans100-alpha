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

The Kotlin adapter currently has no call site in `JarvisViewModel`; this Python stage validates its output contract but does not wire automatic trace capture or file export into the app. Therefore, no current Android trace file or scored ANS prediction exists. This deliberate gap prevents a heuristic controller status from being mislabeled as neural reasoning.

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
