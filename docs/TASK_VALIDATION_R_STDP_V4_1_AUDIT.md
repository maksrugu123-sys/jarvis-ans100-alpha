# V4.1 — Eligibility–Reward Alignment Audit

**Benchmark extension:** `JARVIS_R_STDP_FEEDBACK_ERROR_RECOVERY_V4`  
**Audit version:** V4.1  
**Date:** 7 November 2026  
**Status:** V4.1 code/tests executed by the user on Android Termux; 15 dedicated tests passed and the two-seed pilot completed 8/8 conditions. Full repository suite after V4.1 remains NOT RUN. User-supplied evidence is recorded in section 8.

## 1. Why this audit exists

The user-run V4 pilot produced both positive and negative reward modulators and both signs of eligibility, but all 1,355 non-zero reward-driven weight updates were negative. A task-level cross-tab is needed to determine which episode types produced those signs and whether the reward baseline is behaving as specified.

V4.1 is a report/diagnostic extension. It does not alter the V4 task, examples, ordering, reward policy, initial weights, neuron model, learning rates, STARTER profile, or numerical-oracle equations.

## 2. Per-feedback episode cross-tab

For every training feedback event, the report increments one cell keyed by:

`label | prediction | correctness | eligibility_sign | modulator_sign | weight_delta_sign`

Allowed values:
- label and prediction: `FAST` or `SLOW`;
- correctness: `correct` or `incorrect`;
- each sign: `negative`, `zero` or `positive`.

The per-condition mapping is `training_diagnostics.episode_alignment_cross_tab`. The aggregate mapping is merged across seeds, not averaged. The sum of cell counts must equal `reward_event_count`.

This directly reveals whether negative eligibility is concentrated in correct FAST decisions, positive eligibility in false positives, whether feedback signs follow correctness, and whether any positive weight updates occur for a specific episode class.

## 3. Reward-baseline audit

The report records baseline immediately before every feedback:
- number of feedback events;
- initial baseline and `beta_b`;
- mean, minimum and maximum baseline-before values;
- the expected modulator definition:
  `clip(reward_value - baseline_before, -1, 1)`.

The code also counts any mismatch between the modulator returned by the numerical oracle and that expression. The baseline is observed, not modified.

Because the reward policy is binary (1.0 for correct, 0.0 for incorrect), a baseline strictly between 0 and 1 naturally produces a positive modulator after a correct outcome and a negative modulator after an incorrect one. The changing baseline magnitude can change update magnitude even when its sign does not change.

## 4. Delta/product reconciliation

For every feedback event, V4.1 partitions the actual reward-driven delta into positive, negative or zero. It separately checks the sign of:

`eligibility_at_feedback × modulator`

The report distinguishes:
- matching non-zero product and delta signs;
- zero delta despite a non-zero product (for example, a bound can suppress an update);
- opposite product/delta signs;
- non-zero delta when the product is zero.

The last two categories should remain zero for the current numerical rule. Their appearance is an integrity issue to investigate; they are not to be silently normalized away.

## 5. Regression requirements

The dedicated tests require:
1. every feedback event appears in exactly one cross-tab cell;
2. the six key dimensions contain valid values and correctness matches label/prediction;
3. positive + negative + zero delta counts equal feedback-event count;
4. all non-zero products reconcile into sign-matching, zero-delta or sign-mismatch categories;
5. no non-zero delta occurs without a non-zero eligibility/modulator product;
6. baseline statistics are finite, ordered and within [0,1];
7. the recorded modulator equals reward minus baseline;
8. held-out inference remains frozen and the existing STARTER/diagnostic-weight separation remains intact.

## 6. Run on Android Termux

From the repository root:

```sh
python3 -m tools.task_validation.rstdp_feedback_v4 --profile pilot > rstdp_feedback_v4_pilot_report.json
python3 -m unittest tools.task_validation.test_rstdp_feedback_v4 -v
```

V4.1 must be distinguished from the earlier V4 pilot. The report produced before the V4.1 code change does not contain this cross-tab. The new benchmark and tests must actually be rerun before any V4.1 result is claimed.

## 7. Interpretation boundary and next decision

A cross-tab is descriptive evidence. Eligibility sign co-occurring with a correct or incorrect episode does not prove that the trace causally represents the correct synaptic contribution. If the audit confirms that both correct FAST events and false-positive SLOW events depress the same scalar weight, do not tune the reward rule by guesswork. The next experiment should isolate causal credit assignment (for example, vary presynaptic/post-synaptic event ordering while keeping reward correctness controlled) and compare it against an explicit baseline.

The canonical `JARVIS_SINGLE_NEURON_STARTER_V1` weight remains 0.5. The 0.70 value remains a separately declared diagnostic starting weight, not a calibrated or replacement profile. No build, CI workflow, APK or AAB is part of this audit.

## 8. User-run V4.1 execution record — 7 November 2026

The following execution output was supplied by the user from Android Termux. It is not represented as an execution performed by the author of this V4.2 change.

- Dedicated V4.1 tests: **PASS — 15 tests, 0 failures, 0 errors, 4.061 s**.
- V4.1 pilot: **PASS as an executed benchmark run — 8/8 seed/condition runs completed**, using two seeds and four conditions.
- R-STDP feedback events: 2,048.
- R-STDP weight updates: **0 positive, 1,355 negative, 693 zero**.
- R-STDP baseline-before-reward: initial 0.5, mean 0.8045988381060147, minimum 0.495, maximum 0.891672785088837; beta_b 0.01.
- Aggregate cross-tab:
  - 1,024: FAST predicted FAST, correct, negative eligibility, positive modulator, negative delta.
  - 331: SLOW predicted FAST, incorrect, positive eligibility, negative modulator, negative delta.
  - 693: SLOW predicted SLOW, correct, zero eligibility, positive modulator, zero delta.
- Neutral R-STDP: 2,048 feedback events, 0 positive / 0 negative / 2,048 zero updates; baseline remained 0.5.
- No positive task-level R-STDP update occurred. The baseline/modulator relation and product/delta sign reconciliation were consistent in the supplied report.
- The full repository suite after V4.1 was **NOT RUN** according to the supplied output.

Interpretation: the audit found no baseline-sign accounting discrepancy. It showed that correct FAST and false-positive SLOW episodes both depressed the same scalar weight, while correct SLOW episodes had zero eligibility. The cross-tab did not expose which pulse created those eligibility signs; that is the specific gap addressed by V4.2.

## 9. V4.2 follow-up

V4.2 is a separate pulse-level diagnostic, not a change to the V4 task or the learning rule. It replays the V4 R-STDP training split, records the per-pulse pre/post traces and q contributions, reconstructs eligibility at feedback, and compares the existing simultaneous-batch pathway with an isolated shadow pathway that places the postsynaptic event 1 ns after the presynaptic event. The shadow never updates the real synapse.

Code: tools/task_validation/rstdp_causal_credit_v42.py  
Tests: tools/task_validation/test_rstdp_causal_credit_v42.py  
Protocol: docs/TASK_VALIDATION_R_STDP_V4_2_CREDIT_AUDIT.md

The V4.2 code and tests are authored; they have **NOT RUN** at the time of this documentation update. Run the focused tests and a phone-friendly smoke/pilot on Android Termux before interpreting the pulse-level report.
