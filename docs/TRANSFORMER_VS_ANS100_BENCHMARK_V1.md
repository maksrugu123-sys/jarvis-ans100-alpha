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

Task families cover direct relation status (YES / NO / UNKNOWN), two-hop reachability (YES / NO), and evidence classification (YES / NO / CONFLICT / UNKNOWN). Each task has a single canonical expected answer. The generator explicitly varies labels within each family; relation-status tasks include all three of YES, NO, and UNKNOWN in both development and pair-disjoint splits.

The pair-disjoint split draws from the same bounded 32-symbol vocabulary as development while using disjoint queried subject/object pairs. The generator records each task's queried pair separately from its supporting fact edges and checks the pair-disjoint and shared-vocabulary invariants for every seed. It accepts 2–16 tasks per split and rejects counts outside that range or custom counts that would violate those guarantees instead of silently producing a weaker split. Supporting graph edges are not required to be globally disjoint: the contract is that the evaluated query pairs are new. The concept-disjoint split uses a separate symbol namespace and is checked for zero symbol overlap with development. The compositional split tests a two-link relation not supplied as a direct fact.

## Input fairness

Both systems receive exactly the same `input_text` for a task, with no expected answer, labels, split metadata, or evaluator internals. No web, external tools, hidden files, or extra examples are allowed in the primary run. Any few-shot examples, retrieval, persistent memory, or other augmentation must be declared and run as a separate condition.

The ANS-100 adapter may translate the common input into its neural input-frame representation, but it must preserve the same facts and query and must not inject gold labels. The Transformer receives the same textual facts and query. The evaluator's structured `facts` and `expected_answer` fields are for test generation/scoring only and must never be sent to either system.

## Resource fairness and reporting

Run both systems on the same target device and runtime conditions. Record the exact model/runtime version, quantization, parameter count where known, device, OS, thread count, context/input limits, cold-start latency, warm latency, per-task latency, peak process memory (prefer PSS/RSS on Android), and energy/battery data only if actually measured.

Use the same task order and repeat protocol. Report quality and resource use separately rather than pretending two different architectures have equal compute. If practical, run both an unrestricted-quality comparison and a fixed-device-resource comparison. Never infer RAM, CPU, or energy from parameter count alone.

Missing predictions count as wrong in overall accuracy. Duplicate or unknown prediction task IDs are rejected. The scorer also rejects duplicate source-task IDs, unknown split/family values, non-canonical gold labels, and invalid seed metadata before calculating any score. Results include per-seed accuracy so a pooled score cannot hide a seed-specific collapse. The scorer reports per-split/per-family accuracy, submitted rows, answered rows, abstentions, missing rows, coverage, latency median/p95, peak-memory max/median, cold-start versus warm steady-state latency/memory summaries, and paired task outcomes (only Transformer correct / only ANS-100 correct / same correctness). Explicit null answers count as abstentions and reduce coverage; they are not confused with missing output files. The `development` split is excluded from every score and paired outcome; predictions carrying development task IDs are rejected.

## Structured input prototype (Stage B1)

A separate `ANS100StructuredBenchmarkInputAdapter` has been added on the experimental branch. It recognizes only the benchmark's explicit fact/query grammar and routes six roles through six disjoint sub-lanes inside the existing 138 concept-input neurons (23 slots per role): fact subject, fact relation, fact object, query subject, query relation, and query object. Supported fact forms are direct positive/negative relations, directed edges, and supporting/refuting evidence. Supported query forms are direct relation status, two-hop directed-path questions, and evidence classification.

The adapter emits clean presence pulses and a trace containing role, normalized value, and bounded fact/query provenance. It rejects unsupported syntax rather than silently treating it as understood language. It reports hash-slot collisions. This is deterministic grammar parsing plus role-aware feature routing; it is not learned semantic parsing, synonym understanding, hypothesis generation, or causal reasoning. The original lexical adapter remains unchanged as the baseline.

The structured adapter is currently an isolated prototype and is NOT yet wired into `ANS100StandaloneBenchmarkRuntime` or the Android benchmark UI. The new JVM unit tests have been authored but have NOT been run. Do not compare structured-adapter performance until it is integrated behind an explicit benchmark mode, its tests pass, and both old/new modes are evaluated on the exact same tasks.

## Current implementation boundary

The repository currently contains a 10k sparse LIF network, concept activation and associative/structural experiments, but the full app still has heuristic Core logic that prepares some evidence and candidate signals. A benchmark adapter must identify those signals and report them. It must not silently label the current integrated pipeline as pure neural reasoning.

The shared Python tool still generates tasks and scores prediction JSONL files; it does not itself invoke Android. The Android app now exposes an isolated Qwen-only inference entry point:
- `JarvisViewModel.runTransformerOnlyBenchmark(inputText, onComplete)` serializes execution on the conversation executor;
- `LiteRtLmResponseEngine.benchmarkTransformerOnly(inputText)` uses the installed local Qwen3-0.6B INT4 model directly;
- the path bypasses ANS-100 handoff, Core understanding, chat history, workspace memory, fallback responses, and tool calling;
- output is parsed strictly as one canonical label; explanations and ambiguous generations are unscorable;
- generation and initialization time are returned; the Android wrapper samples process PSS around this path (see measurement limitations below).

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
- per-task latency, network initialization duration, event counts, source spikes, readout spike counts, and learned-synapse count are returned. The Android wrapper samples process PSS around each task.

The neural adapter is intentionally lexical and lossy: it hashes token features into 138 input slots and reports collisions. Training labels are accepted only when the task ID begins with `development:`. It does not parse relation semantics or prove autonomous reasoning. This makes the current ANS-100 condition an honest test of the existing sparse network plus learned canonical readout, not a claim of full semantic intelligence.

The Python adapter now separates development-only training labels from held-out evaluation inputs. Development labels may be passed to ANS-100 training only; held-out task inputs contain no labels or split metadata. Both Qwen and ANS-100 must receive the same held-out `input_text`. The training exporter requires `development:` task IDs, while the held-out exporter accepts only the three declared evaluation splits and verifies that each task ID matches its split. The trace adapter preserves cold-start flags, peak-memory deltas, and memory-sampling intervals so the scorer can report those measurements rather than silently dropping them.

No on-device inference has been run yet. The Android ANS-100 path is now callable, but a paired Transformer-vs-ANS-100 result is still unavailable and no empirical winner can be declared.

## Files and commands

- Generator/scorer: `tools/task_validation/transformer_vs_ans100_benchmark_v1.py`
- Tests: `tools/task_validation/test_transformer_vs_ans100_benchmark_v1.py`

Generate the shared task set:

```sh
python3 -m tools.task_validation.transformer_vs_ans100_benchmark_v1 generate --output benchmark/transformer_vs_ans100_tasks.jsonl
```

Each system must produce JSONL rows with `task_id`, `answer`, `latency_ms`, and `peak_memory_mb`. Resource fields may be omitted when not measured; do not invent values. The first held-out pair in each experiment forces a cold engine/network initialization: Qwen's loaded LiteRT engine is closed before its PSS baseline, and the isolated ANS-100 SQLite network is closed and reopened inside the sampling window. Later rows are warm steady-state runs. Each output records `cold_start` so cold and warm rows can be separated. This is an engine/network cold start within the existing Android app process, not a force-stop/relaunch of Android itself. Every held-out task is run as a cold-start measurement: Qwen's native engine is closed before its baseline is sampled, and the ANS-100 SQLite network is closed and reopened (without deleting its learned benchmark database) before its baseline is sampled. This includes model/network initialization in the memory window; initialization time is also reported separately from generation/inference latency. The Android runner samples process-wide PSS every 20 ms during each inference and records `memory_baseline_pss_mb`, `peak_memory_mb`, `peak_memory_delta_mb`, and `memory_sample_interval_ms`. PSS includes the app, runtime, model/network and shared process allocations; it is not per-model heap attribution. The other benchmark subject may still be resident in the same process, so this is a cold subject initialization, not a fully isolated process cold boot. Because both subjects run in one app process and the other model may remain resident, absolute peak PSS is a whole-process figure, not an isolated model footprint. The peak-minus-baseline delta is the better indicator of additional memory during that inference, but it still excludes allocations already resident at baseline. Compare both fields and treat them as sampled peaks (brief spikes between samples may be missed). For a definitive model-footprint comparison, run each subject in a fresh process with the other subject unloaded. The answer must be one of YES, NO, UNKNOWN, CONFLICT (or a documented canonical alias).

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

For every held-out row, the app now also exposes a batch entry point, `runPairedBenchmarkBatch(inputs, onProgress, onComplete)`. Supply only `taskId` and `inputText` from the held-out export. It accepts up to 1,000 unique rows, runs both models sequentially on the shared conversation executor, alternates model order, and reports each task's independent success/failure result through progress callbacks. A failed or unparseable answer must be written as an explicit null answer, not omitted.

For development training, call `resetAns100OnlyBenchmark` first, then `trainAns100DevelopmentBatch(examples, onProgress, onComplete)`. This accepts only unique `development:` IDs, bounded inputs, and canonical labels. It passes the label separately from the text to the isolated ANS-100 runtime. Never include a training label in the input text.

The ANS-100 settings panel now includes a benchmark workflow: choose the development-training JSONL and held-out-input JSONL using Android's document picker, then start the experiment. The app resets the isolated ANS-100 database, trains only on development rows, runs both systems on all held-out rows, samples process PSS during each inference, and asks where to save `transformer_predictions.jsonl` and `ans100_predictions.jsonl`. The JSONL reader checks schema, unique IDs, split prefixes, input length, SHA-256, and rejects held-out files containing gold labels or split metadata. Output rows preserve explicit null answers and inference errors. Pause/resume and durable in-app experiment history are not implemented.

The benchmark database is deliberately separate from the production ANS-100 network. Do not change this boundary or pass benchmark labels through the ordinary production feedback API.

## Android Transformer-only entry point

The paired batch API cold-starts each subject on the first held-out row (unloads/reinitializes Qwen and closes/reopens the isolated ANS-100 network), then measures subsequent rows in warm steady state. The output marks each row's `cold_start` flag. It invokes the Qwen-only engine once per exported held-out row and returns its parsed answer (including null when parsing fails), initialization time, and generation time alongside ANS-100 measurements. The Android JSONL exporter writes both prediction files without expected answers. Do not pass the expected answer or any ANS-100 output to the Transformer method. Durable experiment history remains future work. Peak process-PSS sampling and first-row cold engine/network initialization are implemented, with the co-resident-model and in-process limitations described above.

A null parsed answer is an abstention/unscorable output, not a guessed label. The Android exporter writes an explicit null answer row for abstentions and a separate `inference_error` field when an engine throws. The Python trace adapter also preserves null answers rather than dropping them. Do not treat a missing local model or inference exception as a model answer. Keep the same task set for both systems.

The app currently requires the Qwen model to be installed before enabling the paired experiment button. The benchmark runs on the device and can take substantial time; keep JARVIS open and avoid starting another conversation during the run. No automatic build, CI, or model inference has been run as part of repository changes.
