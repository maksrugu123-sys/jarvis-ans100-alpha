# V4.3 — Decision-Boundary Temporal-Credit Experiment

**Benchmark:** `JARVIS_R_STDP_DECISION_BOUNDARY_CREDIT_V4_3`  
**Status:** protocol and Python reference implementation authored; pre-run regression checks strengthened to prove sensory inputs remain processed while post-decision plasticity is skipped. Tests and benchmark have NOT RUN.  
**Date:** 8 November 2026  
**Production runtime:** not implemented by this experiment.

## 1. Research question

V4.2 showed that correct FAST episodes often accumulate negative eligibility. Pulse-level traces suggest a plausible mechanism: the neuron can emit its first output spike on pulse 2, which already determines the task decision as FAST, but pulse 3 is still processed by the R-STDP learner. A later presynaptic pulse after the postsynaptic spike contributes LTD and may overwhelm the earlier LTP.

V4.3 tests whether this is **post-decision credit contamination**.

The hypothesis is narrow: once the first output spike has latched the classification as FAST, later sensory events cannot change that episode's classification. They should still be processed by the neuron, but should not add eligibility to the already-decided classification outcome.

## 2. Frozen variables

V4.3 preserves:
- canonical STARTER profile and weight 0.5;
- diagnostic training initial weight 0.70, explicitly separate from STARTER;
- neuron, synapse and reward parameter values;
- all three input pulses and their timestamps/strengths;
- the 40 ms deadline and classification rule;
- V4 SHA-256 dataset generation, matched train/held-out pairs and ordering;
- simultaneous-event batch semantics;
- existing R-STDP eligibility, reward baseline and weight-update equations.

No Oracle code or learning equation is modified.

## 3. Compared conditions

1. `fixed`: no learning.
2. `stdp`: existing pair-based STDP.
3. `rstdp_v4`: frozen V4 behavior; all three pulses update eligibility.
4. `rstdp_decision_latched`: experimental policy; the pulse that emits the first output spike is included, but subsequent pulses do not update R-STDP traces/eligibility. Those sensory pulses are still delivered to the neuron.
5. `rstdp_neutral`: full V4 eligibility path, but zero reward modulator.

Every condition starts at the same diagnostic weight and sees the same episodes. V4 remains an exact replay control.

## 4. Why the policy is causal to this task

The classifier's output is a one-way decision: any output spike by 40 ms means FAST. Once a spike occurs, later inputs cannot turn that episode into SLOW under the frozen decision rule. The experimental gate therefore excludes only plasticity events that occur after the decision has become irreversible. It does not remove sensory input or change inference during that episode.

For SLOW episodes with no spike, all three pulses remain eligible through the deadline. A SLOW false positive whose first spike occurs on pulse 3 includes that spike-producing pulse; there are no later pulses to exclude.

This is a task-specific credit assignment hypothesis. It is not a claim that biological STDP should stop after a spike.

## 5. Profiles and commands

From the repository root:

```sh
python3 -m unittest tools.task_validation.test_rstdp_feedback_v4_3 -v
python3 -m tools.task_validation.rstdp_feedback_v4_3 --profile smoke > rstdp_feedback_v4_3_smoke.json
python3 -m tools.task_validation.rstdp_feedback_v4_3 --profile pilot > rstdp_feedback_v4_3_pilot.json
```

- smoke: 64 matched training pairs, 32 held-out pairs, one seed;
- pilot: 512 training pairs, 128 held-out pairs, two seeds;
- full: V4 full profile, 8,000 training pairs, 256 held-out pairs, four seeds.

The full profile is not recommended as the first phone run.

## 6. Required evidence

- exact replay parity between V4.3's frozen `rstdp_v4` condition and the existing V4 `_run_condition`;
- no held-out weight changes;
- exact number of sensory input events processed, counted after each successful return from process_neuron_batch;
- positive, non-zero count of post-decision sensory inputs processed by the neuron;
- equality between post-decision sensory-input count and skipped post-decision plasticity-event count for the latched R-STDP condition;
- identical spike times and prediction for a matched FAST episode under frozen V4 and decision-latched policies;
- positive/negative/zero eligibility, modulator and weight-update counts;
- held-out accuracy, balanced accuracy, macro-F1 and confusion counts for every control;
- per-seed comparisons against V4, FIXED, neutral R-STDP and STDP;
- deterministic JSON-safe report.

The focused regression tests must fail if the latched path skips zero plasticity events, if any sensory pulse is omitted, or if the neuron produces a different spike sequence on the same FAST episode. The frozen V4 baseline must still process and learn from all three pulses.

A positive weight-update count alone is not success. The decision-latched policy must improve held-out error metrics over frozen V4 and controls consistently across seeds, without trading away correct FAST recognition.

## 7. Interpretation and stop conditions

If the decision-latched path changes eligibility signs as predicted but does not improve held-out results, the hypothesis is not sufficient and must not be promoted. If it increases FAST false negatives, it is not an acceptable correction. If it fails to produce positive and negative task-level updates, the temporal-credit problem remains.

Do not change STARTER, reward baseline parameters, STDP/R-STDP rates or event ordering to rescue a weak result. Preserve the frozen V4 condition as the baseline. The experiment is a Python reference benchmark, not a production runtime, and does not establish consciousness, general intelligence, biological equivalence or target-device performance.
