# ANS-100 benchmark adapter V1

## Purpose

This stage establishes a leakage-safe boundary between the shared synthetic benchmark and the actual Android ANS-100 runtime. It does not invent a neural answer where the current app only exposes a policy decision.

## Added

- `tools/task_validation/ans100_benchmark_adapter_v1.py` exports model-only input rows with exactly four fields: schema, task ID, exact input text, and SHA-256 of that text.
- The adapter validates raw ANS trace rows and emits scorer-compatible predictions only when a dedicated neural canonical readout explicitly supplies YES, NO, UNKNOWN, or CONFLICT.
- The exact input hash is checked, so traces from a different prompt cannot be scored.
- Measurements are copied only when present and valid; the adapter does not estimate latency or memory.
- `tools/task_validation/test_ans100_benchmark_adapter_v1.py` adds regression tests. Tests were authored but not run.

## Current production limitation

The Android path currently prepares typed evidence and candidate support/conflict signals using Core/LocalUnderstanding heuristics. `ANS100HybridPopulationController` compares actual LIF readout spikes, but its selected candidate means “prepare an answer”, “ask for clarification”, or “abstain”. It does not emit the benchmark's semantic canonical labels YES/NO/UNKNOWN/CONFLICT. Its decision producer is `HYBRID_POPULATION_CONTROLLER_V1`, not an autonomous neural canonical readout.

Therefore, current production traces may be inspected for neural activity and heuristic provenance, but cannot honestly become accuracy predictions. A SELECTED status is not mapped to YES. Qwen is not used to reason or fill the missing result.

## Commands

Export gold-free inputs after generating the shared task set:

```sh
python3 -m tools.task_validation.ans100_benchmark_adapter_v1 export-inputs --tasks benchmark/transformer_vs_ans100_tasks.jsonl --output benchmark/model_inputs.jsonl
```

Validate a future Android trace export:

```sh
python3 -m tools.task_validation.ans100_benchmark_adapter_v1 adapt-ans-traces --inputs benchmark/model_inputs.jsonl --traces benchmark/ans100_traces.jsonl --output benchmark/ans100_predictions.jsonl
```

## Trace contract

Each trace row contains `schema=ANS100_BENCHMARK_TRACE_V1`, `task_id`, exact `input_sha256`, `decision` with producer/status, `heuristic_sources`, `neural_readout`, and optional actually measured `measurements`.

A prediction is accepted only if both the readout answer origin and decision producer equal `NEURAL_CANONICAL_READOUT`, and the answer is one of the four canonical labels. For the present app, that condition is intentionally unmet.

## What this does not claim

No Android inference adapter, Transformer prediction run, ANS accuracy score, model winner, APK, or resource measurement was produced. This is an evaluation contract and leakage guard, not proof of autonomous reasoning. No tests, builds, CI, or GitHub Actions were run.
