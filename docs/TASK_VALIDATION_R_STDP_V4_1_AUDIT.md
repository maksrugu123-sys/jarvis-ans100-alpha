# V4.1 — Eligibility–Reward Alignment Audit

**Benchmark extension:** `JARVIS_R_STDP_FEEDBACK_ERROR_RECOVERY_V4`  
**Audit version:** V4.1  
**Date:** 7 November 2026  
**Status:** code and tests authored; execution not yet performed.

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
