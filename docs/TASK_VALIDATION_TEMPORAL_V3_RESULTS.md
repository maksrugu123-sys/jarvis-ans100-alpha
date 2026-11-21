# Task-Level Validation V3 — Results and Interpretation

**Benchmark:** \`JARVIS_TEMPORAL_ROBUSTNESS_V3\`  
**Execution date:** 7 November 2026  
**Environment:** user-run Python reference experiment in Android Termux  
**STARTER profile:** unchanged  
**Overall unit tests:** **107 passed, 0 failures, 0 errors, 19.730 seconds**

## Executive result

V3 completed successfully. The fixed one-neuron/one-synapse baseline classified every held-out example correctly. STDP changed the weight substantially and introduced two false positives. Rewarded R-STDP changed the weight slightly but had exactly the same held-out predictions as the neutral-reward control and FIXED.

| Condition | Held-out accuracy | Balanced accuracy | Macro-F1 | TP | FP | TN | FN |
|---|---:|---:|---:|---:|---:|---:|---:|
| FIXED | 1.0000 | 1.0000 | 1.0000 | 80 | 0 | 80 | 0 |
| STDP | 0.9875 | 0.9875 | 0.98750 | 80 | 2 | 78 | 0 |
| R-STDP | 1.0000 | 1.0000 | 1.0000 | 80 | 0 | 80 | 0 |
| R-STDP neutral | 1.0000 | 1.0000 | 1.0000 | 80 | 0 | 80 | 0 |

Each condition was evaluated on 160 held-out episodes, 80 per class, across four deterministic seeds.

## Per-seed held-out accuracy

| Seed | FIXED | STDP | R-STDP | R-STDP neutral |
|---:|---:|---:|---:|---:|
| 104729 | 1.000 | 0.975 | 1.000 | 1.000 |
| 130363 | 1.000 | 1.000 | 1.000 | 1.000 |
| 155921 | 1.000 | 1.000 | 1.000 | 1.000 |
| 181081 | 1.000 | 0.975 | 1.000 | 1.000 |
| **Mean** | **1.000** | **0.9875** | **1.000** | **1.000** |
| Sample SD | 0.0000 | 0.01443 | 0.0000 | 0.0000 |

The two STDP errors were false positives on SLOW held-out episodes (FP=2, FN=0). The result is consistent with a weight increase moving the detector toward more frequent output spikes.

## Diagnostic weight-boundary probe

The probe evaluated 1,000 valid combinations at each diagnostic weight (500 FAST and 500 SLOW), without learning or modifying STARTER.

| Weight | Accuracy | FP | FN |
|---:|---:|---:|---:|
| 0.450 | 96.6% | 0 | 34 |
| 0.475 | 99.9% | 0 | 1 |
| 0.500 | 100.0% | 0 | 0 |
| 0.525 | 100.0% | 0 | 0 |
| 0.550 | 99.0% | 10 | 0 |
| 0.575 | 95.0% | 50 | 0 |
| 0.600 | 84.0% | 160 | 0 |

Within this predeclared grid, the initial weight 0.50 and 0.525 both separated all 1,000 patterns. Lower weights caused FAST misses; higher weights caused SLOW false positives. This demonstrates meaningful sensitivity to synaptic weight and shows why a weight increase must not be treated as an automatic learning improvement.

## Plasticity diagnostics

### Ordinary STDP

Across four runs:

- 320 observed plasticity events;
- 65 positive and 245 negative bounded pair contributions;
- pooled event weight-delta sum: approximately +0.23143249;
- mean final weight: approximately 0.55785812;
- per-seed final weights ranged from 0.55433778 to 0.56285254.

The held-out classifier incurred two false positives after this weight increase. V3 therefore reproduces the V2 warning: the plasticity rule can be active while moving the model away from a better decision boundary.

### Rewarded R-STDP

Across four runs:

- 320 eligibility updates and 128 reward events;
- pooled eligibility absolute magnitude at reward: approximately 0.26654040;
- pooled reward-modulator sum and absolute sum: approximately +55.00393281;
- all 128 reward modulators were positive; there were **zero negative reward modulators**;
- 64 reward applications changed the weight and 64 reported \`APPLIED_NO_CHANGE\`;
- pooled reward-driven weight delta: approximately +0.00057413;
- mean final weight: approximately 0.50014353.

The reward path is demonstrably active: eligibility was present, reward modulation was non-zero, and 64 reward applications changed the weight. However, this run does **not** test error correction. All training examples were classified correctly, so every evaluator reward was 1.0 and every reward modulation was positive relative to the evolving baseline. No negative reward signal occurred.

The 64 no-change applications are consistent with episodes where eligibility was zero; the presence of a reward event alone does not guarantee a weight update.

### Neutral-reward R-STDP

Across four runs:

- 320 eligibility updates and 128 reward events;
- eligibility was present before reward;
- every reward modulation was exactly zero;
- all 128 reward calls returned \`APPLIED_NO_CHANGE\`;
- final weight remained exactly 0.5.

### Direct reward-vs-neutral result

Rewarded R-STDP minus neutral-reward R-STDP:

- held-out accuracy delta: 0 for every seed and overall;
- held-out balanced-accuracy delta: 0 for every seed and overall;
- weight delta difference: approximately +0.000142 per seed.

Thus reward modulation changed the internal weight state but did not change held-out classifications. Because FIXED already achieved 100%, there was no accuracy headroom. The result neither proves nor disproves the ability of R-STDP to recover from errors.

## Critical task interpretation

The matched FAST/SLOW pairs have identical three pulse strengths; only their gaps differ. However, the 7 ms deadline means FAST presents all three pulses before the decision, while SLOW presents only two and its third pulse is withheld.

Therefore V3 validates **deadline-bounded temporal event-density/latency discrimination**. It does not isolate sensitivity to precise inter-pulse spacing when both classes have the same number of events inside the observation window. The model may use the number of observed events as a sufficient cue.

This distinction is important. V3 is a successful controlled timing/deadline task, but it is not evidence that the single neuron can distinguish arbitrary temporal patterns or same-count temporal sequences.

## Conclusions

1. **FIXED baseline:** perfect on this predeclared task across all four seeds; the unmodified STARTER weight 0.5 is sufficient.
2. **STDP:** active, but it raised the weight toward a region with more false-positive SLOW decisions and lowered held-out accuracy to 98.75%.
3. **R-STDP:** eligibility and reward modulation operated; a small positive weight change occurred, but there was no held-out accuracy gain over FIXED or neutral R-STDP.
4. **Reward correction remains untested:** the training set produced only correct decisions and therefore no negative reward modulation.
5. **Task capacity remains limited:** same-count temporal interval discrimination is not established by V3.

Do not change STARTER based on this benchmark. Preserve V2 and V3 as completed historical experiments.

## Next research step — V4 design criteria

V4 should specifically test whether feedback can correct an initially biased decision boundary, without modifying the canonical STARTER profile:

- define an equal-event-count temporal-summation task: both classes deliver all three pulses before the observation deadline; class identity is carried by inter-pulse spacing, not by the number of visible events;
- use matched FAST/SLOW stimulus pairs with identical strengths;
- pre-register a diagnostic initial-weight perturbation as an explicit benchmark condition separate from STARTER, alongside the canonical 0.5 baseline;
- choose training conditions that generate both correct and incorrect decisions, so reward modulation has both positive and negative examples;
- compare FIXED, STDP, rewarded R-STDP and neutral R-STDP on identical datasets and multiple seeds;
- keep held-out weights, eligibility and reward state frozen;
- record the first error, reward sign, eligibility at feedback, weight update direction and whether the held-out confusion profile improves;
- report whether correction is repeatable across seeds and whether a benefit remains when compared with a fixed-weight control at the same initial weight.

If the one-neuron/one-synapse system cannot correct a demonstrated error under this protocol, treat that as a capacity or credit-assignment limitation rather than silently changing the algorithm or STARTER.

## Reproducibility

The user executed:

~~~sh
python3 -m tools.task_validation.temporal_robustness_v3 > temporal_robustness_v3_report.json
python3 -m unittest discover -s tools -t . -v
~~~

Observed test result: **Ran 107 tests in 19.730s — OK**.

The JSON report was produced locally in Termux. It is not claimed to be committed to the repository. No application build, CI/GitHub Actions, APK/AAB or resource benchmark was run.
