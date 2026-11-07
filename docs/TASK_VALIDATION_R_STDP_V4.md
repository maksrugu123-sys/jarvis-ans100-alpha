# Task-Level Validation V4 — R-STDP Feedback and Error Recovery

**Benchmark:** `JARVIS_R_STDP_FEEDBACK_ERROR_RECOVERY_V4`  
**Status:** V4 reference experiment implemented; exploratory two-seed pilot completed. V4.1 user-run follow-up: 15 dedicated tests passed and pilot 8/8 conditions completed. Full pre-registered V4 profile remains pending. V4.2 pulse-level diagnostic authored; execution pending. Pilot results: docs/TASK_VALIDATION_R_STDP_V4_PILOT_RESULTS.md.  
**Date:** 7 November 2026  
**Production runtime:** not implemented by this benchmark.

## 1. Research question

V3 demonstrated bounded temporal processing in one Adaptive LIF-DT neuron, but it did not produce negative reward signals: every training decision was correct. Consequently it did not test whether R-STDP can use negative feedback to reduce an incorrect response.

V4 asks two separate questions:

1. Does the existing numerical R-STDP rule generate and use both signs of eligibility and both signs of reward modulation, with the weight update following the sign of their product?
2. In a held-out task, does reward-modulated learning reduce errors caused by a deliberately high initial synaptic weight, compared with FIXED, ordinary STDP and a neutral-reward R-STDP control?

The evaluator supplies an external scalar reward after each training decision. The neuron is not given the class label during inference. This is a controlled feedback experiment, not autonomous self-awareness.

## 2. Preserve the canonical STARTER

The canonical `JARVIS_SINGLE_NEURON_STARTER_V1` synaptic weight remains **0.5**. No STARTER parameter is calibrated or changed.

Two tracks are kept distinct:

- **Canonical baseline probe:** evaluate the held-out task at weight 0.5 without learning. This is a read-only reference.
- **Diagnostic recovery track:** all four learning conditions begin at the explicitly declared diagnostic initial weight **0.70**. This is an experimental perturbation, not a replacement STARTER profile.

The same diagnostic initial weight, data, ordering, neuron parameters, synapse parameters and reward parameters are used by FIXED, STDP, R-STDP and neutral R-STDP.

## 3. Signed eligibility/reward probe

The isolated mathematical probe uses the existing `update_rstdp` and `apply_reward` functions; it does not modify their equations.

- Positive eligibility: a presynaptic event at 0 ms, followed by a postsynaptic event at 2 ms.
- Negative eligibility: a postsynaptic event at 0 ms, followed by a presynaptic event at 2 ms.
- Positive reward: reward value 1.0 with baseline 0.5, giving a positive modulator.
- Negative reward: reward value 0.0 with baseline 0.5, giving a negative modulator.
- Neutral reward: reward value equals baseline, giving a zero modulator.

Expected mathematical sign contract:

| Eligibility | Modulator | Expected weight direction |
|---|---|---|
| Positive | Positive | Increase |
| Positive | Negative | Decrease |
| Negative | Positive | Decrease |
| Negative | Negative | Increase |
| Either non-zero sign | Zero | No change |
| No eligibility | Either reward sign | No weight change |

This probe verifies the four sign combinations in the existing formula. It does not by itself establish useful task-level learning.

## 4. Same-count temporal task

Each episode contains exactly three input pulses. Both classes deliver all three pulses before the same 40 ms decision deadline.

| Class | Allowed inter-pulse gaps | Latest third pulse |
|---|---|---:|
| FAST | (2,2), (2,3), (3,2), (3,3) ms | 6 ms |
| SLOW | (18,18), (18,19), (19,18), (19,19) ms | 38 ms |

The decision rule is frozen: at least one output spike by 40 ms predicts FAST; no output spike predicts SLOW.

Each FAST/SLOW pair receives the exact same three pulse strengths. Strengths are deterministic SHA-256-derived nuisance variables in [0.8, 1.0], not class labels. Training and held-out strength triples are unique and disjoint. Each split is class-balanced and deterministically interleaved by matched pair.

The diagnostic weight 0.70 is selected before the run to create a high-excitability condition: FAST examples tend to spike on their second input, while some strong SLOW examples spike only on their third. These different post/pre timing relationships are intended to produce negative eligibility for many correct FAST episodes and positive eligibility for false-positive SLOW episodes. The task then checks whether reward modulation drives the weight down and improves held-out classification. The actual observed event traces and outcomes, not this design rationale, determine the result.

## 5. Dataset and controls

Default pre-registered run:

- Four deterministic seeds: 104729, 130363, 155921 and 181081.
- 8,000 matched training pairs per seed: 8,000 FAST + 8,000 SLOW episodes.
- 256 matched held-out pairs per seed: 256 FAST + 256 SLOW episodes.
- The same episode IDs and presentation order are used by all four conditions.
- Each episode starts with a rested neuron; synaptic state persists between training episodes.
- Episodes are separated by 2,000 ms of model time so short-lived pair traces decay between episodes.
- The held-out data is inference-only. No weight, eligibility or reward-baseline updates are allowed.

Compared conditions:

1. **FIXED:** weight remains at 0.70.
2. **STDP:** existing pair-based excitatory STDP; no label or reward is supplied to the local update.
3. **R-STDP:** existing eligibility updates; after each training decision the evaluator supplies reward 1.0 if correct and 0.0 if incorrect.
4. **R-STDP neutral:** same eligibility path, but reward equals the current baseline, so the reward modulator is zero. Eligibility is consumed at feedback, isolating reward modulation from eligibility processing alone.

All conditions use the canonical STARTER STDP/R-STDP equations and rates. The only altered value is the explicitly named diagnostic initial weight.

## 6. Required diagnostics and interpretation

The report includes:

- the four signed eligibility/reward probe combinations and the no-eligibility control;
- canonical STARTER 0.5 baseline metrics;
- the common pre-training diagnostic-weight metrics;
- train and held-out confusion counts, accuracy, balanced accuracy and macro-F1;
- final weights and held-out weight-freeze checks;
- positive, negative and zero reward-modulator counts;
- positive, negative and zero eligibility-at-feedback counts;
- positive, negative and zero reward-driven weight-update counts;
- false-positive episodes with a negative weight update;
- the first five training errors, including label, prediction, pulse/spike times, baseline, modulator, eligibility, weight delta and resulting weight;
- weight/baseline checkpoints during training;
- per-seed comparisons of R-STDP against FIXED, neutral R-STDP and STDP.

A weight change alone is not a learning success. Task-level error correction requires repeatable held-out improvement over FIXED and neutral R-STDP, with a corresponding reduction in false positives/false negatives. STDP is a direct comparator and may outperform R-STDP; that outcome must be reported rather than hidden.

A single-neuron/one-synapse system may lack sufficient credit-assignment capacity. Failure to recover is a valid experimental result and must not trigger silent changes to STARTER or the learning equations.

## 7. V4.1 eligibility–reward alignment audit

V4.1 extends the V4 report without changing the benchmark dataset, reward policy,
STARTER parameters, STDP/R-STDP equations or learning rates.

For every training feedback event, the report records a six-dimensional
cross-tab cell:

`label | prediction | correctness | eligibility_sign | modulator_sign | weight_delta_sign`

Each sign is one of `negative`, `zero` or `positive`. The cells are stored in
`training_diagnostics.episode_alignment_cross_tab` for each seed/condition and
reconciled across seeds in `aggregate_conditions`. The sum of all cells must
equal the number of feedback events.

The audit also records the reward baseline immediately before each feedback,
its mean/minimum/maximum, and checks the existing oracle contract:

`modulator = clip(reward_value - baseline_before, -1, 1)`

It reconciles positive/negative/zero weight deltas against feedback-event totals
and checks whether non-zero `eligibility × modulator` products agree with the
actual delta sign. A zero delta with a non-zero product is reported separately
because soft bounds or clipping can suppress an update; a sign mismatch is a
distinct integrity failure. The diagnostic counters do not modify the oracle.

Interpretation must remain at the right level:
- The cross-tab reveals which task outcomes co-occur with each eligibility sign.
- It does not prove that an eligibility trace carries useful causal credit.
- A one-synapse scalar weight can move in only one direction at a time; both
  reward signs do not guarantee useful bidirectional task correction.
- The canonical STARTER weight remains 0.5. The 0.70 recovery condition is
  still explicitly diagnostic and is not calibration.

The dedicated tests cover cross-tab coverage, sign-count reconciliation,
product/delta consistency, baseline/modulator consistency and frozen
held-out inference. Their post-V4.1 execution result was supplied by the user
and is recorded in docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md and
docs/DEVELOPMENT_LOG.md.

## 8. Run on Android Termux

From the repository root:

```sh
# Телефонный пилот: 512 train pairs, 128 held-out pairs, два seed
python3 -m tools.task_validation.rstdp_feedback_v4 --profile pilot > rstdp_feedback_v4_pilot_report.json

# Полный pre-registered benchmark: 8000/256 пар, четыре seed
python3 -m tools.task_validation.rstdp_feedback_v4 --profile full > rstdp_feedback_v4_report.json

# Полный набор unit tests
python3 -m unittest discover -s tools -t . -v
```

The pilot profile is an explicitly exploratory, reduced-size run for constrained devices; it does not replace the pre-registered full benchmark. The full profile remains the default if no profile is supplied. Progress is printed to stderr while the final JSON is written to stdout, so shell redirection preserves a clean JSON file. The dedicated V4 unit tests use a smaller deterministic smoke dataset to keep the full test suite practical. The JSON report is a local execution artifact unless separately committed by the user. V4.1 cross-tab and baseline diagnostics are included automatically in each run.

## 9. Scope limits

This is a Python/float64 reference experiment using the Numerical Oracle, not the production JARVIS runtime. It does not measure target-device RAM, CPU, energy or I/O; it does not test JPVR, NCC production integration, scaling, biological equivalence, consciousness or general intelligence. No application build or GitHub Actions workflow is part of V4.

## 10. V4.2 causal-credit diagnostic

V4.2 is a separate read-only diagnostic replay. It records the existing batch pathway's per-pulse decayed pre/post traces, q_raw, q_bounded, eligibility before/after each pulse and each pulse's contribution at the 40 ms feedback time. It reconstructs the Oracle equations independently and checks the replay against the existing V4 R-STDP training path.

A separate shadow synapse receives the same observed output spikes but uses a diagnostic pre-at-t / post-at-t-plus-1-ns ordering. It never feeds into the neuron, real synapse, subsequent episodes or reward baseline. This is an ordering-sensitivity probe, not a model change.

Protocol and interpretation: docs/TASK_VALIDATION_R_STDP_V4_2_CREDIT_AUDIT.md.  
Code: tools/task_validation/rstdp_causal_credit_v42.py.  
Tests: tools/task_validation/test_rstdp_causal_credit_v42.py.

V4.2 code and tests are authored but NOT RUN. The dedicated tests and phone-friendly smoke/pilot must be run before treating its output as evidence. STARTER 0.5, diagnostic initial weight 0.70, Oracle equations, reward policy and V4 data remain unchanged.
