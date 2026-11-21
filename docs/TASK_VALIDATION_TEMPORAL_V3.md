# Task-Level Validation V3 — Deadline-Bounded Temporal Rate Recognition

**Benchmark:** \`JARVIS_TEMPORAL_ROBUSTNESS_V3\`  
**Status:** completed by the user on Android Termux on 7 November 2026. Results: [TASK_VALIDATION_TEMPORAL_V3_RESULTS.md](TASK_VALIDATION_TEMPORAL_V3_RESULTS.md). All 107 unit tests passed. The task reached 100% fixed-baseline held-out accuracy; no learning accuracy improvement is claimed.  
**Date prepared:** 7 November 2026  
**STARTER profile:** unchanged

## 1. Purpose

V2 showed that ordinary STDP could substantially increase the synaptic weight while reducing held-out accuracy, and that rewarded and neutral R-STDP produced identical discrete predictions in that task. V3 is a new, deliberately separable temporal-rate control task. It is designed to answer two distinct questions:

1. Can the unmodified one-neuron/one-synapse reference model distinguish the declared temporal patterns at a fixed decision deadline?
2. Do STDP or reward-modulated STDP improve held-out behavior, and do their local learning signals explain any change?

V3 does not replace V2. V2 remains the overlapping temporal-pattern challenge; V3 tests whether temporal evidence can be observed cleanly before interpreting learning behavior.

## 2. Frozen task definition

Every episode contains exactly three input pulses and two positive inter-pulse gaps. The class is defined by the generated timing pattern, not by the neuron's output.

| Class | Each inter-pulse gap | Pulse times relative to episode start |
|---|---:|---|
| FAST | 2 or 3 ms | 0 ms, 2–3 ms, 4–6 ms |
| SLOW | 4 or 5 ms | 0 ms, 4–5 ms, 8–10 ms |

The decision deadline is fixed at **7 ms** after the first pulse.

- FAST patterns place all three pulses at or before the deadline.
- SLOW patterns place only the first two pulses at or before the deadline; the third pulse is later than the deadline and is withheld from the decision and from that episode's plasticity/reward update.
- The frozen output rule is: one or more output spikes at or before 7 ms means FAST; otherwise SLOW.
- The deadline and gap ranges are predeclared protocol constants. They are not selected from the observed benchmark results.
- Every pulse strength is drawn from the same shared grid: 0.80, 0.85, 0.90, 0.95 and 1.00. Strength is a nuisance variable, not a class-specific feature.
- Each FAST/SLOW pair shares the exact same three pulse-strength values; only the two gap values differ. This matched-pair construction prevents pulse amplitude from acting as a class cue. Both labels use exactly three pulses in their full episode definition.
- The model is advanced to the exact 7 ms decision time with an empty input batch before the decision is recorded. This makes the deadline semantics explicit rather than evaluating only at the last input event.

The delayed third SLOW pulse is not silently discarded from the dataset: the report records both the total episode definition and the number of pulses observed/withheld at the decision deadline.

## 3. Dataset and repeatability

- Four deterministic seeds: 104729, 130363, 155921 and 181081.
- Each seed: 16 FAST + 16 SLOW training episodes, and 20 FAST + 20 SLOW held-out episodes.
- SHA-256-derived episode generation; no runtime randomness.
- Each sample includes two gaps and three per-pulse strengths.
- Full input signatures must be unique within a seed and disjoint between train and held-out.
- Both splits are class-balanced and deterministically class-interleaved to avoid presenting long class blocks to online learning.
- All four conditions use the exact same episode IDs and ordering within each seed.
- The neuron starts each episode at rest. Synaptic state is preserved throughout training for the same run/condition.
- Each episode has a 2,000 ms start-to-start separation to allow the model's short-lived neuron and synapse traces to decay according to their specified dynamics.

The generated held-out split is not used to update weights, eligibility traces or reward baselines.

## 4. Compared conditions

1. **FIXED:** no synaptic learning updates.
2. **STDP:** pair-based excitatory STDP after each input pulse actually observed by the decision deadline. The local rule receives no class label or reward.
3. **R-STDP:** eligibility is updated after each observed input pulse; after the decision, the evaluator supplies reward 1.0 for a correct class decision and 0.0 for an incorrect decision.
4. **R-STDP neutral:** the same eligibility path is exercised, but reward equals the current reward baseline. This consumes eligibility with zero reward prediction error and isolates the contribution of reward modulation. It is not an absence-of-evaluator condition.

All methods use the same STARTER parameters and the same initial synaptic weight 0.5. Held-out evaluation is inference-only and must leave weights unchanged.

## 5. Event-level diagnostics

The JSON report records, per observed pulse:

- timestamp and input strength;
- effective threshold and input potential delta;
- whether an output spike occurred;
- synaptic weight used for the pulse;
- STDP/R-STDP raw and bounded pair contribution;
- eligibility trace before/after each R-STDP event;
- event-level weight delta for ordinary STDP.

For each R-STDP episode it also records:

- reward value and baseline before/after;
- reward modulator (reward minus baseline);
- eligibility before decay and after decay to reward time;
- reward-step weight delta and apply status.

Pooled diagnostics include positive/negative pair contributions, eligibility magnitudes, positive/negative reward modulators, reward status counts and total weight changes. These fields make it possible to tell apart: no eligibility, eligibility that decays before reward, zero reward modulation, a non-zero reward signal with a negligible weight change, and a weight change that actually changes held-out decisions.

## 6. Exhaustive diagnostic boundary probe

The report performs a descriptive-only exhaustive probe at weights:

\`0.45, 0.475, 0.50, 0.525, 0.55, 0.575, 0.60\`

For each weight it evaluates:

- both classes;
- all four ordered two-gap combinations in that class's allowed gap set;
- all \(5^3 = 125\) per-pulse strength combinations.

That is 1,000 distinct patterns per weight. The probe reports full confusion metrics. It does not update a weight, select a new parameter or modify STARTER. Its purpose is to show how sensitive the frozen decision rule is to a weight shift in either direction. The unit tests require the initial weight 0.50 to separate the predeclared patterns and require the diagnostic sweep to expose both low-weight misses and high-weight false positives; these assertions still need to be confirmed by the user's execution.

## 7. Metrics and interpretation

The report includes pooled and per-seed train/held-out confusion metrics, accuracy, balanced accuracy, class F1, macro-F1, sample standard deviation across seeds, final training weights, held-out weight-freeze checks and event-level learning diagnostics.

Rewarded R-STDP is compared directly against neutral R-STDP. A weight change alone is not a learning success. Any claimed benefit must be visible in held-out decisions, be consistent across multiple seeds, and be interpreted alongside class-specific false positives and false negatives.

The task is intentionally separable and deadline-bounded. A perfect FIXED baseline would demonstrate that the temporal code is visible to this one-neuron model, but it would leave no accuracy headroom for learning to improve. In that case, learning can only be judged for stability/robustness or in a separately predeclared harder task; do not retune the decision deadline or STARTER after looking at results.

V3's FAST/SLOW timing ranges do not overlap. This is intentional and complementary to V2, whose ranges overlap. V3 does not establish broad temporal recognition, general intelligence, biological equivalence or production-runtime performance.

## 8. Run on Android Termux

From the repository root:

~~~sh
python3 -m tools.task_validation.temporal_robustness_v3 > temporal_robustness_v3_report.json
python3 -m unittest discover -s tools -t . -v
~~~

To print a compact summary:

~~~sh
python3 - <<'PY'
import json

with open("temporal_robustness_v3_report.json") as f:
    report = json.load(f)

print("BENCHMARK:", report["benchmark"])
print("BOUNDARY PROBE:")
for item in report["boundary_probe"]["weights"]:
    metrics = item["metrics"]
    print(item["weight"], metrics["accuracy"], "FP:", metrics["fp"], "FN:", metrics["fn"])

print("CONDITIONS:")
for item in report["aggregate_conditions"]:
    held = item["held_out"]
    diag = item["training_diagnostics"]
    print(
        item["condition"],
        "held-out accuracy:", held["accuracy"],
        "balanced accuracy:", held["balanced_accuracy"],
        "macro-F1:", held["macro_f1"],
        "TP/FP/TN/FN:", held["tp"], held["fp"], held["tn"], held["fn"],
        "weight diagnostics:", diag,
    )

print("REWARD EFFECT:", report["reward_effect"])
PY
~~~

## 9. Verification status and restrictions

- The V3 Python reference module and its unit tests have been committed.
- No V3 benchmark execution or V3 unit-test result is claimed yet.
- The user should run the two commands above in Termux and share the output summary.
- This is a pure Python reference experiment. It does not build the Android application.
- No Gradle/Maven/CMake/Make/Cargo/npm build, APK/AAB, CI or GitHub Actions workflow is part of this task.
- STARTER parameters and Numerical Oracle equations are not modified.
