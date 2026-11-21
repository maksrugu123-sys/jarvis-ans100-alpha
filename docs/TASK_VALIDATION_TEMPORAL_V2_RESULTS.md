# Task-Level Validation V2 — Results and Interpretation

**Benchmark:** `JARVIS_TEMPORAL_ROBUSTNESS_V2`  
**Execution date:** 7 November 2026  
**Execution environment:** Android Termux, Python reference implementation  
**Protocol:** four deterministic seeds; 128 training and 160 held-out episodes per condition  
**STARTER profile:** unchanged

## Executive result

The V2 benchmark executed and its report was inspected. The full test suite also passed: **92 tests, OK**.

The experiment does **not** show a held-out learning benefit for STDP or R-STDP under this task and frozen decision rule.

| Condition | Held-out accuracy | Balanced accuracy | Macro-F1 | TP | FP | TN | FN |
|---|---:|---:|---:|---:|---:|---:|---:|
| FIXED | 0.74375 | 0.74375 | 0.73834 | 48 | 9 | 71 | 32 |
| STDP | 0.66250 | 0.66250 | 0.62264 | 79 | 53 | 27 | 1 |
| R-STDP | 0.74375 | 0.74375 | 0.73834 | 48 | 9 | 71 | 32 |
| R-STDP neutral | 0.74375 | 0.74375 | 0.73834 | 48 | 9 | 71 | 32 |

All held-out sets are balanced, so pooled accuracy and balanced accuracy coincide here.

## Per-seed held-out accuracy

| Seed | FIXED | STDP | R-STDP | R-STDP neutral |
|---:|---:|---:|---:|---:|
| 104729 | 0.725 | 0.675 | 0.725 | 0.725 |
| 130363 | 0.825 | 0.825 | 0.825 | 0.825 |
| 155921 | 0.650 | 0.575 | 0.650 | 0.650 |
| 181081 | 0.775 | 0.575 | 0.775 | 0.775 |
| **Mean** | **0.74375** | **0.66250** | **0.74375** | **0.74375** |
| Sample SD | 0.07465 | 0.11815 | 0.07465 | 0.07465 |

STDP was 8.125 percentage points below FIXED on the pooled held-out set. Its per-seed sample standard deviation was also higher.

## Error profile

STDP achieved 98.75% BURST recall (79/80) but only 33.75% SPARSE specificity (27/80). It generated **53 false BURST predictions** on SPARSE examples, compared with 9 for FIXED. The weight increase therefore shifted the detector toward firing more often; it did not produce a better class boundary.

This is a useful negative result: a larger synaptic weight and higher sensitivity do not automatically mean better learning or generalization.

## Synaptic weight changes

| Condition | Weight after training | Delta from 0.5 |
|---|---:|---:|
| FIXED | 0.500000 (all seeds) | 0 |
| STDP | 0.542307–0.561087 | +0.042307 to +0.061087 |
| R-STDP | 0.500043–0.500083 | +0.000043 to +0.000083 |
| R-STDP neutral | 0.500000 (all seeds) | 0 |

STDP changed the weight substantially, but the change correlated with overprediction rather than improved held-out accuracy.

R-STDP produced very small positive weight changes. The rewarded and neutral controls nevertheless had exactly the same held-out confusion matrix and accuracy for every seed. The report's mean, minimum and maximum held-out accuracy deltas between rewarded and neutral R-STDP were all zero.

This does **not** prove that reward modulation is mathematically inactive. It shows that any reward-driven weight differences in this experiment did not change the discrete held-out predictions. The current task/decision rule may be insensitive to such small updates.

## Interpretation and limitations

1. **FIXED is the current best reference condition** for this exact benchmark. Do not replace it with STDP or claim STDP improved learning.
2. **STDP is active but harmful to this classifier's operating point.** It strongly increases BURST recall at the cost of many SPARSE false positives.
3. **R-STDP's task-level utility has not yet been demonstrated.** Rewarded and neutral R-STDP are indistinguishable on held-out predictions, despite small weight changes in the rewarded condition.
4. Four seeds are useful repeatability checks but are not a statistically powered population estimate. The benchmark is synthetic and limited to one neuron, one excitatory synapse and a frozen rule: any output spike predicts BURST.
5. The per-gap class ranges overlap at 7–8 ms. Results should not be generalized to broad temporal recognition.
6. The result does not justify changing the STARTER parameters to fit this dataset. Preserve the current STARTER profile until a better-designed, independently evaluated experiment supports a change.

## Recommended next research step

Before tuning learning rates, add a diagnostic benchmark layer that makes the learning signal observable and tests whether it can change a decision for a valid reason:

- record per-episode STDP pair contributions, R-STDP eligibility, reward prediction error/modulator, reward status and actual weight delta;
- report predictions and confusion metrics by timing regime, including the ambiguous 7–8 ms region;
- add a small, predeclared threshold/weight sensitivity sweep as a *diagnostic only*, without modifying STARTER;
- construct a task where timing information is actually discriminative for the neuron, while keeping train/held-out signatures disjoint and comparing FIXED, STDP, rewarded R-STDP and neutral R-STDP on identical data;
- require reward-vs-neutral separation across multiple seeds before claiming reward-modulated learning utility.

Do not silently retune the existing benchmark after seeing its results. Keep V2 as a completed historical result and version any revised protocol separately (for example, V3).

## Reproducibility record

The user executed:

```sh
python3 -m tools.task_validation.temporal_robustness_v2 > temporal_robustness_v2_report.json
python3 -m unittest discover -s tools -t . -v
```

The report identified benchmark `JARVIS_TEMPORAL_ROBUSTNESS_V2`. The complete test suite reported **92 tests passed**. The generated JSON report is a local execution artifact and is not represented as committed to the repository.
