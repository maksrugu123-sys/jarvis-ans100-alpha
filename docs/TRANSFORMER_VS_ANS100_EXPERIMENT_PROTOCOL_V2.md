# Transformer vs ANS-100 experiment protocol V2

## Purpose

This protocol compares the installed Qwen3-0.6B INT4 transformer with the isolated ANS-100 neural readout on exactly the same held-out task inputs. It is a bounded, reproducible experiment, not a claim that either system is generally more intelligent.

## Primary comparison

- Transformer: Qwen3-0.6B INT4 through the dedicated transformer-only benchmark path.
- Neural system: structured-input ANS-100 through `ANS100StandaloneBenchmarkRuntime.runStructuredTask`.
- The structured ANS-100 path is the primary neural candidate because it is the current role-structured encoder + learned neural canonical readout. The lexical-hash ANS-100 path remains a separate baseline/control and must not be silently substituted for the primary result.
- Both systems receive the same `input_text` for each held-out task.
- The transformer is not given conversation history, Core answers, workspace memory, tools, or ANS-100 decisions.
- ANS-100 receives canonical labels only for `development` tasks. Held-out calls always pass `trainingLabel=null`.
- The answer must be emitted by the dedicated neural canonical readout. Core/controller/evidence-audit output is instrumentation and must not be promoted to an ANS-100 prediction.

## Dataset

The default generator creates 192 deterministic tasks from four fixed seeds:
- 48 development tasks (training only);
- 48 pair-disjoint tasks (new queried pairs with a shared vocabulary);
- 48 concept-disjoint tasks (unseen concept namespace);
- 48 compositional tasks (two-hop reachability).

The three held-out splits total 144 tasks. Task families are relation status, two-hop reachability, and evidence conflict. These synthetic tasks measure specific bounded capabilities; they do not establish natural-language competence or broad reasoning.

## Termux preparation

Run from the repository root:

```sh
python3 -m tools.task_validation.transformer_vs_ans100_benchmark_v1 generate \
  --output benchmark/transformer_vs_ans100_tasks.jsonl

python3 -m tools.task_validation.ans100_benchmark_adapter_v1 export-ans-training \
  --tasks benchmark/transformer_vs_ans100_tasks.jsonl \
  --output benchmark/ans100_development_training.jsonl

python3 -m tools.task_validation.ans100_benchmark_adapter_v1 export-evaluation-inputs \
  --tasks benchmark/transformer_vs_ans100_tasks.jsonl \
  --output benchmark/held_out_inputs.jsonl
```

The training export contains labels from development only. The held-out export contains the exact common text and SHA-256 input digest, but no answer, split, or label metadata.

## Android experiment

1. Install/select the Qwen3-0.6B INT4 `.litertlm` model in JARVIS.
2. Open the ANS-100 benchmark panel.
3. Select `ans100_development_training.jsonl` as development input.
4. Select `held_out_inputs.jsonl` as held-out input.
5. Start the primary comparison. The app resets only the isolated structured ANS-100 benchmark database, trains structured ANS-100 on development rows, and runs Qwen plus structured ANS-100 on each identical held-out input. On first use, a structured database that has never been created is treated as already clean; existing SQLite database/sidecar files must be deleted and verified absent before the network is reopened.
6. Save the generated transformer and ANS-100 JSONL files without editing their contents. Each prediction row carries the SHA-256 digest of the exact held-out input supplied to both systems; the scorer checks this digest against the source task before scoring.

The current paired runner deliberately requests a cold start for each model on each task. Initialization time is reported separately from generation/inference time; these measurements are not a warm steady-state throughput benchmark.

The separate ANS-100 encoding experiment compares lexical-hash and role-structured input encoders. It is a diagnostic control, not the primary transformer comparison.

## Scoring

```sh
python3 -m tools.task_validation.transformer_vs_ans100_benchmark_v1 score \
  --tasks benchmark/transformer_vs_ans100_tasks.jsonl \
  --transformer benchmark/transformer_predictions.jsonl \
  --ans100 benchmark/ans100_predictions.jsonl \
  --report benchmark/transformer_vs_ans100_report.json
```

Review overall accuracy, accuracy by split/family/seed, coverage, abstentions, missing rows, paired task outcomes, verified input-hash counts, initialization latency, inference latency, and memory measurements. A supplied input hash that differs from the source task is rejected. Non-null predictions outside the canonical answer set are rejected rather than counted as answered. Missing predictions and explicit abstentions do not receive credit.

## Resource measurement limits

Android currently samples process-wide PSS at 20 ms intervals. The measured value is the entire JARVIS Android process, not an isolated allocation attribution for Qwen or ANS-100. Because both runtimes share one process, the other runtime and Android app baseline can remain resident during a measurement. The report therefore labels this scope as `ANDROID_PROCESS_TOTAL_PSS_NOT_PER_MODEL`.

Do not claim that a reported process PSS number is the model's standalone RAM requirement. A true per-model RAM comparison requires separate cold process launches (one model per run), recording the same baseline/peak/delta protocol after force-stopping the app between runs. Those separate-process measurements are not yet implemented or collected.

## Interpretation and safety

- Report which system wins each task family and held-out split; do not collapse everything into an unsupported universal-intelligence ranking.
- Report coverage beside accuracy so a system cannot appear strong by silently abstaining.
- Treat initialization time separately from per-task inference latency.
- Do not use the test labels as ANS-100 inputs during evaluation.
- Do not train on transformer outputs or treat generated dialogue as ground truth.
- Keep `STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED = false`; this experiment evaluates learned readout behavior and does not authorize automatic topology growth.
- Keep all work on `experiment/jarvis-integrated-prototype-v1`; do not modify `main`.

## Verification state

The source-level Android flow and Python scorer have been updated for the structured ANS-100 primary comparison, input-encoder reporting, initialization latency, and explicit process-wide PSS qualification. Tests were authored/updated but not run. No Gradle build, APK, CI workflow, or on-device model experiment has been run as part of this change. No benchmark winner or measured performance result is claimed.


### Execution pacing update (2026-11-17)

The earlier fixed inter-example waits and periodic cooldown sleeps have been removed from Android development training and paired held-out evaluation. The benchmark still checks device memory and thermal stop conditions before each training example and each paired evaluation row. Removing fixed waits does not guarantee that the device will remain cool or have sufficient memory; the safety stop remains authoritative.
