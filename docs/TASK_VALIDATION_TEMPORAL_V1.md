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

## Test suite — first run

The user ran:

```sh
python3 -m unittest discover -s tools -t . -v
```

Observed result at that time: **69 tests passed, 0 failures, 0 errors, 1.833 seconds**.

This included 26 CQE reference tests, 29 Numerical Oracle tests, 9 Calibration Round 2 tests, and 5 task-validation tests. These are reference-tool unit tests, not production runtime or build tests.

No application build, APK/AAB generation, CI, or GitHub Actions workflow was run.

## Follow-up: fixed-baseline boundary characterization

The deterministic tool `tools/task_validation/baseline_boundary.py` evaluates the unchanged fixed-weight neuron across 15 inter-pulse intervals (2–60 ms) and 9 input strengths (0.2–1.0), for 135 cells total. Each cell contains three pulses and records output spike count plus the fixed burst/sparse decision.

This is not a training experiment: there are no STDP/R-STDP updates, no parameter optimization, and no STARTER changes. The input-strength grid is constrained to the Numerical Oracle's validated inclusive range [0, 1].

### Observed boundary-grid results

The user ran the command successfully on Android Termux on 7 November 2026:

```sh
python3 -m tools.task_validation.baseline_boundary > baseline_boundary_report.json
```

The report identified benchmark `JARVIS_FIXED_BASELINE_BOUNDARY_GRID_V1` and contained **135/135 cells**. The observed spike-count matrix is:

| Strength | 2 ms | 3 ms | 4 ms | 5 ms | 6 ms | 7 ms | 8 ms | 10 ms | 12 ms | 15 ms | 20 ms | 30 ms | 40 ms | 50 ms | 60 ms |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.7 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.8 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.9 | 1 | 1 | 1 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 1.0 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

Here each value is the number of output spikes from three input pulses, not a classification accuracy.

### Interpretation and limits

- In this sampled grid, strengths 0.2–0.7 produced no output spikes at any tested interval.
- At strength 0.8, spikes occurred only at 2–3 ms intervals.
- At strength 0.9, spikes occurred at 2–7 ms intervals.
- At strength 1.0, spikes occurred at 2–8 ms intervals.
- No tested cell produced more than one output spike.

This is consistent with temporal summation: closely spaced inputs can accumulate sufficiently to cross threshold, while more widely spaced inputs decay before summating. The sampled grid does not establish an exact continuous threshold or a universal boundary; it only characterizes the listed discrete conditions and the current STARTER profile.

### Full test suite after the boundary-grid fix

The user reran:

```sh
python3 -m unittest discover -s tools -t . -v
```

Observed result: **74 tests passed, 0 failures, 0 errors, 2.325 seconds**.

Breakdown:
- 26 CQE reference tests;
- 29 Numerical Oracle tests;
- 9 Calibration Round 2 tests;
- 5 temporal-pattern task-validation tests;
- 5 baseline-boundary task-validation tests.

The additional strength-range test verifies that every grid value respects the Oracle's [0, 1] input constraint. These are reference-tool unit tests, not production runtime, build, or CI tests.

No application build, APK/AAB generation, CI, or GitHub Actions workflow was run.

## Follow-up protocol: JARVIS_TEMPORAL_ROBUSTNESS_V1

**Status:** protocol, reference script and unit tests are specified; the new benchmark has not yet been run by the user. No measured accuracy, weight change or learning benefit is claimed in this section.

### Objective

Check whether the one-neuron reference model preserves burst/sparse discrimination when the input conditions vary and the two timing classes partially overlap. The task deliberately includes amplitude variation so that input strength is a nuisance factor shared by both classes rather than a clean class cue.

### Pre-registered dataset

- One Adaptive LIF-DT reference neuron and one excitatory synapse; initial weight 0.5.
- Each episode contains exactly three pulses, represented by two inter-pulse gaps and three separate input-strength values.
- Training: 12 episodes, balanced 6 BURST / 6 SPARSE.
- Held-out: 10 different episodes, balanced 5 BURST / 5 SPARSE.
- The observed BURST gap range is 4–8 ms; SPARSE is 7–10 ms. These ranges overlap at 7–8 ms.
- Both classes use the same per-pulse strength set: 0.85, 0.90 and 0.95. Strength values vary between pulses and episodes.
- The exact input signatures (both gaps and all three strengths) are disjoint between training and held-out data.
- All episodes are explicit constants. There is no random sampling or seed-dependent generation.
- The neuron starts from a rested state for each episode; synaptic state and learned weight persist across training episodes. Episodes are separated by 2,000 ms of model time.

The label is assigned by the fixed dataset, not generated from a hidden threshold. Because the timing bands overlap, some examples are intrinsically ambiguous for a classifier that sees only these input events. Results must be interpreted with that limitation in mind.

### Compared conditions

1. **FIXED:** no weight updates.
2. **STDP:** pair-based excitatory STDP after each pulse; the learning rule is not given the class label.
3. **R-STDP:** eligibility updates after each pulse; after the complete episode, an external evaluator sends reward 1.0 for a correct classification or 0.0 for an incorrect classification.

The decision rule is frozen before evaluation: one or more output spikes means BURST; zero means SPARSE. The same training and held-out episodes are used for all three conditions. During held-out evaluation, every condition uses fixed inference: no STDP updates and no reward updates.

### Recorded output

The report records train and held-out accuracy, precision/recall/specificity and balanced accuracy; TP/FP/TN/FN; each episode's gaps, strengths, per-pulse output spikes and prediction; the synaptic weight after every training episode; final weight; and held-out weight-freeze verification. Each learned condition is compared against FIXED on held-out accuracy and number of correctly classified episodes.

### Execution

From the repository root on the user's Python environment:

    python3 -m tools.task_validation.temporal_robustness > temporal_robustness_report.json
    python3 -m unittest discover -s tools -t . -v

The expected test count increases by the new robustness tests once the user runs the full suite. Until that run is reported, the existing 74-test result remains the latest confirmed test result.

### Interpretation limits

- The fixed baseline is explicitly required by a unit test to make at least one held-out error; this checks that the challenge set is not another already-perfect control task.
- A lower STDP/R-STDP score is not automatically proof that the learning rule is intrinsically ineffective; it can indicate a mismatch between the task, the one-synapse capacity, the output rule and the learning signal.
- Since overlapping input conditions can carry conflicting labels, 100% accuracy is not an appropriate assumed target.
- This is a small deterministic reference challenge, not a statistically powered estimate, calibrated profile, production benchmark or biological-equivalence claim.
- Do not change STARTER parameters based on this experiment. If the one-neuron model cannot express the required distinction, document the capacity limit before considering a larger model.
