# Transformer vs ANS-100 benchmark V1

## Status and purpose

**Shared task generator/scorer and isolated Android inference entry points implemented. Tests authored, not run. No on-device inference or benchmark measurements have been executed.**

This stage establishes a common comparison contract; it does not claim ANS-100 beats a Transformer or vice versa. The benchmark is intentionally small and synthetic so the expected answers are known and repeatable. The Qwen-only inference adapter is now present, but both model prediction files must be collected before any performance conclusion.

## Systems to compare

1. **Transformer-only:** the local Transformer receives the benchmark input and produces the canonical answer. It must not call ANS-100.
2. **ANS-100-only:** the neural system receives the same information and produces the answer without a language model generating or deciding it. The adapter must disclose any heuristic Core signals still involved; those signals must not be described as autonomous neural reasoning.
3. **Hybrid:** ANS-100 owns memory, analysis, hypothesis/evidence decision and the canonical answer. The Transformer may only verbalize the already-decided result. It must not add facts, change the answer, or make the decision.

Only the first two are used for the direct brain-vs-Transformer accuracy comparison. Hybrid is a separate product-architecture condition and must be scored on whether verbalization preserves the ANS-100 decision.

## Dataset

The deterministic generator creates 192 tasks by default (4 fixed seeds × 4 splits × 12 tasks per split):

- **development:** adapter debugging only; do not report as held-out performance;
- **pair_disjoint:** new queried pairs while reusing the development symbol vocabulary;
- **concept_disjoint:** symbols absent from development;
- **compositional:** new symbol namespace and two-hop directed-path queries.

Task families cover direct relation status (YES / NO / UNKNOWN), two-hop reachability (YES / NO), and evidence classification (YES / NO / CONFLICT / UNKNOWN). Each task has a single canonical expected answer.

The pair-disjoint split uses a disjoint range of pairs from development. The concept-disjoint split uses a separate symbol namespace. The compositional split tests a two-link relation not supplied as a direct fact.

## Input fairness

Both systems receive exactly the same `input_text` for a task, with no expected answer, labels, split metadata, or evaluator internals. No web, external tools, hidden files, or extra examples are allowed in the primary run. Any few-shot examples, retrieval, persistent memory, or other augmentation must be declared and run as a separate condition.

The ANS-100 adapter may translate the common input into its neural input-frame representation, but it must preserve the same facts and query and must not inject gold labels. The Transformer receives the same textual facts and query. The evaluator's structured `facts` and `expected_answer` fields are for test generation/scoring only and must never be sent to either system.

## Resource fairness and reporting

Run both systems on the same target device and runtime conditions. Record the exact model/runtime version, quantization, parameter count where known, device, OS, thread count, context/input limits, cold-start latency, warm latency, per-task latency, peak process memory (prefer PSS/RSS on Android), and energy/battery data only if actually measured.

Use the same task order and repeat protocol. Report quality and resource use separately rather than pretending two different architectures have equal compute. If practical, run both an unrestricted-quality comparison and a fixed-device-resource comparison. Never infer RAM, CPU, or energy from parameter count alone.

Missing predictions count as wrong in overall accuracy. Duplicate or unknown task IDs are rejected. The scorer reports per-split/per-family accuracy, latency median/p95, peak-memory max/median, and paired task outcomes (only Transformer correct / only ANS-100 correct / same correctness). The `development` split is excluded from every score and paired outcome; predictions carrying development task IDs are rejected.

## Current implementation boundary

The repository currently contains a 10k sparse LIF network, concept activation and associative/structural experiments, but the full app still has heuristic Core logic that prepares some evidence and candidate signals. A benchmark adapter must identify those signals and report them. It must not silently label the current integrated pipeline as pure neural reasoning.

The shared Python tool still generates tasks and scores prediction JSONL files; it does not itself invoke Android. The Android app now exposes an isolated Qwen-only inference entry point:
- `JarvisViewModel.runTransformerOnlyBenchmark(inputText, onComplete)` serializes execution on the conversation executor;
- `LiteRtLmResponseEngine.benchmarkTransformerOnly(inputText)` uses the installed local Qwen3-0.6B INT4 model directly;
- the path bypasses ANS-100 handoff, Core understanding, chat history, workspace memory, fallback responses, and tool calling;
- output is parsed strictly as one canonical label; explanations and ambiguous generations are unscorable;
- generation and initialization time are returned; peak memory is not measured by this path.

This is a real Transformer-only inference entry point, but it is not yet a batch runner and has not been executed on-device.

The Android app also now exposes an isolated ANS-100-only runtime:
- `JarvisViewModel.runAns100OnlyBenchmark(taskId, inputText, trainingLabel, onComplete)` runs the sparse network on the serialized conversation executor;
- `ANS100StandaloneBenchmarkRuntime` owns `jarvis_ans100_benchmark_v1.db`, separate from the production hybrid network;
- `ANS100BenchmarkNeuralInputAdapter` maps lexical token features into clean concept-input pulses (1062..1199), without Core hints, candidate labels, expected answers, or Qwen;
- the existing four-class canonical readout is decoded directly from network spikes; ties, insufficient activity, and no signal remain abstentions;
- a non-null `trainingLabel` is accepted only by the dedicated benchmark database, and the generic production feedback API still rejects this label source;
- `resetAns100OnlyBenchmark` clears the isolated database before a fresh experiment;
- `JarvisViewModel.runPairedBenchmarkTask(taskId, inputText, onComplete)` runs Qwen and ANS-100 sequentially on exactly the same held-out input and alternates which model runs first across tasks to reduce fixed order bias;
- paired evaluation accepts only `pair_disjoint`, `concept_disjoint`, and `compositional` task IDs;
- per-task latency, network initialization duration, event counts, source spikes, readout spike counts, and learned-synapse count are returned. Peak memory is not measured.

The neural adapter is intentionally lexical and lossy: it hashes token features into 138 input slots and reports collisions. Training labels are accepted only when the task ID begins with `development:`. It does not parse relation semantics or prove autonomous reasoning. This makes the current ANS-100 condition an honest test of the existing sparse network plus learned canonical readout, not a claim of full semantic intelligence.

The Python adapter now separates development-only training labels from held-out evaluation inputs. Development labels may be passed to ANS-100 training only; held-out task inputs contain no labels or split metadata. Both Qwen and ANS-100 must receive the same held-out `input_text`.

No on-device inference has been run yet. The Android ANS-100 path is now callable, but a paired Transformer-vs-ANS-100 result is still unavailable and no empirical winner can be declared.

## Files and commands

- Generator/scorer: `tools/task_validation/transformer_vs_ans100_benchmark_v1.py`
- Tests: `tools/task_validation/test_transformer_vs_ans100_benchmark_v1.py`

Generate the shared task set:

```sh
python3 -m tools.task_validation.transformer_vs_ans100_benchmark_v1 generate --output benchmark/transformer_vs_ans100_tasks.jsonl
```

Each system must produce JSONL rows with `task_id`, `answer`, `latency_ms`, and `peak_memory_mb`. Resource fields may be omitted when not measured; do not invent values. The answer must be one of YES, NO, UNKNOWN, CONFLICT (or a documented canonical alias).

Score both prediction files against the exact same task file. Prediction files must contain one row for every held-out task (including explicit null answers for abstentions); development rows are training-only and must not appear:

```sh
python3 -m tools.task_validation.transformer_vs_ans100_benchmark_v1 score --tasks benchmark/transformer_vs_ans100_tasks.jsonl --transformer benchmark/transformer_predictions.jsonl --ans100 benchmark/ans100_predictions.jsonl --report benchmark/transformer_vs_ans100_report.json
```

Do not commit private user data or real conversation text into benchmark fixtures. The current fixture contains only synthetic symbols and facts.


## Leakage-safe ANS-100 training/evaluation export

Export labels only for the development split:

```sh
python3 -m tools.task_validation.ans100_benchmark_adapter_v1 export-ans-training --tasks benchmark/transformer_vs_ans100_tasks.jsonl --output benchmark/ans100_development_training.jsonl
```

Pass each row's `input_text` and `training_label` separately to `runAns100OnlyBenchmark`. The label must never be included in the input text. Do not train on pair-disjoint, concept-disjoint, or compositional rows.

Export held-out inputs without gold labels or split metadata:

```sh
python3 -m tools.task_validation.ans100_benchmark_adapter_v1 export-evaluation-inputs --tasks benchmark/transformer_vs_ans100_tasks.jsonl --output benchmark/held_out_inputs.jsonl
```

For every held-out row, call `runPairedBenchmarkTask(task_id, input_text)`. It runs both systems sequentially and returns separate success/failure results, preserving abstentions as null answers. Persist the canonical answer (or null), task ID, and measured latency. Score against the original task file only after both prediction files are complete. Reset the ANS-100 benchmark database before each independent run. The Qwen-only path has no training-label parameter.

The benchmark database is deliberately separate from the production ANS-100 network. Do not change this boundary or pass benchmark labels through the ordinary production feedback API.

## Android Transformer-only entry point

The app-side API is intended for a future benchmark screen or instrumentation harness. Call `runTransformerOnlyBenchmark` once per exported input row, persist the returned `answer` (including null when parsing fails), `generationMs`, and task ID in a prediction JSONL file. Do not pass the expected answer or any ANS-100 output to this method. The current API handles one input per call; batch orchestration and app-side JSONL import/export remain future work.

A null parsed answer is an abstention/unscorable output, not a guessed label. Do not treat a missing local model or inference exception as a model answer. Record failures separately and keep the same task set for both systems.
