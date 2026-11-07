# Task-Level Validation V1 — Temporal Burst vs Sparse

**Run date:** 7 November 2026  
**Environment:** Android Termux; Python version not recorded  
**Benchmark:** `JARVIS_TEMPORAL_BURST_VS_SPARSE_V1`  
**Status:** Executed successfully; results are limited to this deterministic reference experiment.

## Protocol

One Adaptive LIF-DT reference neuron and one excitatory synapse were used. Three input pulses formed each episode. BURST used short intervals; SPARSE used long intervals. The output rule was fixed before the experiment: one or more output spikes means BURST; zero means SPARSE.

Three conditions were compared:

- FIXED: no synaptic updates.
- STDP: pair-based excitatory STDP updates after input events.
- R-STDP: eligibility updates plus an evaluator reward of 1.0 for a correct episode and 0.0 for an incorrect episode.

There were 12 training episodes and 6 held-out episodes per condition. Held-out intervals did not overlap training intervals; held-out input strength was 0.95. Plasticity and reward updates were disabled during held-out evaluation. The STARTER profile was not changed.

## Observed results

| Condition | Train accuracy | Held-out accuracy | TP | FP | TN | FN | Final weight |
|---|---:|---:|---:|---:|---:|---:|---:|
| FIXED | 1.000 | 1.000 | 3 | 0 | 3 | 0 | 0.5000000000 |
| STDP | 1.000 | 1.000 | 3 | 0 | 3 | 0 | 0.5204623985 |
| R-STDP | 1.000 | 1.000 | 3 | 0 | 3 | 0 | 0.5000493025 |

Initial weight was 0.5 in all conditions. Held-out output spike counts, ordered as BURST, SPARSE, BURST, SPARSE, BURST, SPARSE:

- FIXED: `[1, 0, 1, 0, 1, 0]`
- STDP: `[1, 0, 1, 0, 1, 0]`
- R-STDP: `[1, 0, 1, 0, 1, 0]`

All three conditions achieved 6/6 held-out classifications. There were no false positives or false negatives.

## Interpretation

The fixed-weight baseline already solved this deliberately separated task perfectly. Therefore, this experiment does **not** demonstrate an accuracy benefit from STDP or R-STDP.

STDP changed the synaptic weight from 0.5 to approximately 0.520462, while R-STDP changed it to approximately 0.500049. These are observed parameter changes, not evidence that either learning rule improved task performance. In particular, the near-zero R-STDP net change does not by itself show that reward modulation is ineffective; opposing updates can cancel, and the chosen task did not require adaptation.

The result establishes only that the current one-neuron reference model distinguishes this specific set of short-gap and long-gap sequences without learning. It does not establish general pattern recognition, robust learning, biological equivalence, or production runtime quality.

## Test suite

The user ran:

```sh
python3 -m unittest discover -s tools -t . -v
```

Observed result: **69 tests passed, 0 failures, 0 errors, 1.833 seconds**.

This includes 26 CQE reference tests, 29 Numerical Oracle tests, 9 Calibration Round 2 tests, and 5 task-validation tests. These are reference-tool unit tests, not production runtime or build tests.

No application build, APK/AAB generation, CI, or GitHub Actions workflow was run.

## Next experiment

Do not change the STARTER profile based on this result. First characterize the fixed neuron's decision boundary across a controlled grid of inter-pulse intervals and input strengths. Use that map to select a harder, pre-registered task with overlapping/noisy conditions where the baseline is not already perfect. Then compare FIXED, STDP, and R-STDP on identical training and held-out sequences, reporting confusion matrices and weight trajectories. Keep all held-out updates disabled.

If the one-synapse model cannot express the required distinction, increase model capacity only as a separately documented experiment; do not attribute a representational limitation to failed learning.


## Follow-up: fixed-baseline boundary characterization

A separate deterministic characterization tool has been added at `tools/task_validation/baseline_boundary.py`. It evaluates the unchanged fixed-weight neuron across 15 inter-pulse intervals (2–60 ms) and 9 input strengths (0.4–1.2), for 135 cells total. Each cell contains three pulses and records output spike count plus the fixed burst/sparse decision.

This is not a training experiment: there are no STDP/R-STDP updates, no parameter optimization, and no STARTER changes. Its purpose is to map where the current baseline changes its decision, so the next task can be chosen from measured boundary behavior rather than guessed.

Run from the repository root:

```sh
python3 -m tools.task_validation.baseline_boundary > baseline_boundary_report.json
```

The grid and its four unit tests have been added but have not yet been run by the user. Do not treat its response map as verified until the command and full test suite have been executed.
