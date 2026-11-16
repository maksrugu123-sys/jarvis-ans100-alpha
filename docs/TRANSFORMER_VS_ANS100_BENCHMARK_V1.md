# Transformer vs ANS-100 benchmark V1

## Status and purpose

**Shared task generator and offline scorer implemented. Focused tests authored, not run. No model has been executed.**

This stage establishes a common comparison contract; it does not claim ANS-100 beats a Transformer or vice versa. The benchmark is intentionally small and synthetic so the expected answers are known and repeatable. A later stage must connect real model adapters and collect prediction files before any performance conclusion.

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

Missing predictions count as wrong in overall accuracy. Duplicate or unknown task IDs are rejected. The scorer reports per-split/per-family accuracy, latency median/p95, peak-memory max/median, and paired task outcomes (only Transformer correct / only ANS-100 correct / same correctness).

## Current implementation boundary

The repository currently contains a 10k sparse LIF network, concept activation and associative/structural experiments, but the full app still has heuristic Core logic that prepares some evidence and candidate signals. A benchmark adapter must identify those signals and report them. It must not silently label the current integrated pipeline as pure neural reasoning.

This benchmark tool currently generates tasks and scores prediction JSONL files. It does not invoke the Android app, load a Transformer, call Qwen, or export ANS-100 decisions. A future integration stage is required to collect real predictions. Until then there is no empirical winner.

## Files and commands

- Generator/scorer: `tools/task_validation/transformer_vs_ans100_benchmark_v1.py`
- Tests: `tools/task_validation/test_transformer_vs_ans100_benchmark_v1.py`

Generate the shared task set:

```sh
python3 -m tools.task_validation.transformer_vs_ans100_benchmark_v1 generate --output benchmark/transformer_vs_ans100_tasks.jsonl
```

Each system must produce JSONL rows with `task_id`, `answer`, `latency_ms`, and `peak_memory_mb`. Resource fields may be omitted when not measured; do not invent values. The answer must be one of YES, NO, UNKNOWN, CONFLICT (or a documented canonical alias).

Score both prediction files against the exact same task file:

```sh
python3 -m tools.task_validation.transformer_vs_ans100_benchmark_v1 score --tasks benchmark/transformer_vs_ans100_tasks.jsonl --transformer benchmark/transformer_predictions.jsonl --ans100 benchmark/ans100_predictions.jsonl --report benchmark/transformer_vs_ans100_report.json
```

Do not commit private user data or real conversation text into benchmark fixtures. The current fixture contains only synthetic symbols and facts.
