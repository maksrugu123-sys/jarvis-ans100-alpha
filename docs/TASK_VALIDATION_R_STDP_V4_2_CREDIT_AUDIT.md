# V4.2 — Pulse-Level R-STDP Causal-Credit Audit

**Benchmark:** JARVIS_R_STDP_CAUSAL_CREDIT_AUDIT_V4_2  
**Status:** protocol, Python reference diagnostic and dedicated tests authored; execution pending.  
**Date:** 7 November 2026  
**Production runtime:** not implemented by this diagnostic.

## 1. Why V4.2 follows V4.1

The user-run V4.1 pilot completed all 8 seed/condition runs and its dedicated tests passed 15/15. For rewarded R-STDP, the aggregate contained 2,048 feedback events: 1,024 correct FAST outcomes with negative eligibility and positive modulator; 331 incorrect SLOW-to-FAST outcomes with positive eligibility and negative modulator; and 693 correct SLOW outcomes with zero eligibility. The corresponding update counts were 0 positive, 1,355 negative and 693 zero.

V4.1 showed that the baseline/modulator arithmetic and weight-delta sign reconciliation were consistent. It did not reveal which of the three input pulses created each eligibility contribution. V4.2 addresses that missing temporal resolution. The V4.1 execution results were supplied by the user from Android Termux.

## 2. Questions

1. At which pulse does positive or negative pair-based q first appear?
2. Which pulse contributes the largest signed amount to eligibility at the 40 ms feedback time?
3. Do correct FAST episodes accumulate LTD mainly because an output spike is followed by a later presynaptic pulse?
4. Does the existing same-timestamp batch convention materially differ from an explicitly ordered pre-then-post diagnostic?
5. Can the audit replay the original V4 training path without changing its decisions, final weight or reward baseline?

## 3. Canonical path: per-pulse trace audit

V4.2 replays the exact V4 training split, same ordering, same three pulses, diagnostic initial weight 0.70, same STARTER neuron/synapse/reward parameters and same reward rule.

For each pulse it records:
- pulse index, model time and strength;
- n_pre, n_post and whether the neuron emitted a spike;
- decayed pre_trace and post_trace values consumed by the Oracle;
- q_raw and q_bounded;
- eligibility before decay, after decay/before q, and after the pulse;
- post-pulse pre/post traces;
- that pulse's linearly decayed q contribution at the 40 ms feedback time.

The audit independently reconstructs q from the documented old-trace equation and asserts that it matches the Oracle return value. It also checks that the resulting eligibility matches decayed prior eligibility plus bounded q. The sum of each pulse's decayed contribution is compared with actual eligibility at feedback; any residual remains visible.

Episode categories are grouped by label, prediction and correctness. Aggregate pulse tables show q sign counts/sums and contribution sign counts/sums for pulse 1, 2 and 3 separately. A small number of complete episode traces per category are retained as examples.

## 4. Separate shadow-order sensitivity probe

The existing Oracle contract is unchanged: events sharing a timestamp are a batch; old traces are read before the batch, and traces are updated after the batch. Consequently, a same-timestamp n_pre=1/n_post=1 pair on an empty trace produces q=0 rather than inventing an order.

To quantify sensitivity without changing that rule, V4.2 creates a separate shadow synapse for every episode:
- the actual path still receives the original simultaneous n_pre=1, n_post=0/1 batch;
- the shadow receives the same presynaptic event at t;
- when the actual neuron spikes at t, the shadow receives a postsynaptic event at t+1 ns;
- both paths use the same initial episode weight, same observed spike sequence, same reward, same baseline-before-reward and same feedback time.

The shadow is open-loop and diagnostic only. It is discarded after the episode and never changes the real synapse, later neuron inputs, decisions, reward baseline or held-out data. The 1 ns offset is a controlled tie-order perturbation, not a claim that a biological postsynaptic spike is delayed by exactly 1 ns.

An isolated probe also compares: simultaneous batch; pre then post at a 1 ns separation; and post then pre at a 1 ns separation. This demonstrates the sign sensitivity of the existing pair rule without editing it.

## 5. Controls and non-goals

- Canonical JARVIS_SINGLE_NEURON_STARTER_V1 weight remains 0.5.
- The task replay uses diagnostic weight 0.70, as in V4; it is not a calibrated profile.
- V4.2 does not change jarvis_oracle.py, the STDP/R-STDP formula, thresholds, rates, baseline policy, task labels or input dataset.
- Held-out examples are generated only to preserve the V4 train/held-out split validation; V4.2 does not replay or score held-out data.
- The diagnostic does not select a new event-order policy and cannot claim improved learning.
- A change to the real event-order rule would require a separately pre-registered task-level experiment with fixed controls and held-out evaluation.

## 6. Android Termux commands

From the repository root:

~~~sh
# Small smoke diagnostic
python3 -m tools.task_validation.rstdp_causal_credit_v42 --profile smoke > rstdp_causal_credit_v42_smoke.json

# Phone-friendly pilot matching V4.1: 512 training pairs, two seeds
python3 -m tools.task_validation.rstdp_causal_credit_v42 --profile pilot > rstdp_causal_credit_v42_pilot.json

# Dedicated unit tests
python3 -m unittest tools.task_validation.test_rstdp_causal_credit_v42 -v
~~~

The optional full profile uses the V4 full sizes (8,000 training pairs, four seeds) and should not be the first run on a constrained phone. JSON is written to stdout; progress is written to stderr.

## 7. Regression requirements

The dedicated tests verify:
1. simultaneous-batch q is zero for an empty-trace pre/post pair, while 1 ns pre→post and post→pre produce opposite signs;
2. every training episode appears in exactly one cross-tab cell and exactly one event exists for each of its three pulses;
3. stored pulse q/eligibility values are finite and the decayed q contributions reconcile with eligibility at feedback;
4. the canonical audit replay matches the original V4 implementation's training metrics, final weight and reward baseline for a reduced deterministic dataset;
5. the shadow path cannot change the real training result;
6. the report is deterministic and JSON serializable without non-finite values.

## 8. Evidence status and interpretation

V4.1 execution was supplied by the user on Android Termux:
- dedicated tests: 15 passed, 0 failures, 0 errors, 4.061 seconds;
- pilot: 8/8 seed/condition runs completed (2 seeds × 4 conditions);
- R-STDP baseline-before mean/min/max: 0.8045988381060147 / 0.495 / 0.891672785088837;
- R-STDP feedback/update counts: 2,048 feedback events; 0 positive, 1,355 negative and 693 zero updates;
- neutral R-STDP: 2,048 zero updates and baseline unchanged at 0.5.

V4.2 code and tests are authored but NOT RUN at publication time. No V4.2 benchmark result is claimed. The full repository unit-test suite has not been rerun after V4.1. No builds, APK/AAB, CI or GitHub Actions were run.

A per-pulse correlation can locate where the sign is generated. It still does not prove that a different ordering is biologically correct or improves task learning. A new rule must be judged only by a later, controlled, held-out experiment.


## 9. User-run evidence update (2026-11-07)

The user subsequently ran the dedicated V4.2 module on Android Termux after the unittest runner-name fix: **6 tests passed, 0 failures, 0 errors, 0.288 seconds**. The smoke profile completed 128 episodes and the pilot completed 2,048 episodes (two seeds, 1,024 episodes per seed).

The pilot cross-tab recorded 1,024 correct FAST episodes with negative eligibility and positive modulator (negative weight update), 331 false-positive SLOW→FAST episodes with positive eligibility and negative modulator (negative weight update), and 693 correct SLOW episodes with zero eligibility (zero update). Thus there were 1,355 negative, zero positive and 693 zero updates. The actual eligibility sum was -1.6586585879267512; the actual reward-driven weight-delta sum was -0.004205183945517832.

The 1 ns ordered shadow changed the aggregate eligibility sum to +0.32333505612981234, while the reported per-episode eligibility sign counts and weight-update sign counts remained unchanged. This indicates magnitude/order sensitivity, not a demonstrated improvement or a reason to change canonical event ordering.

Interpretation and exact shared metrics: `docs/TASK_VALIDATION_R_STDP_V4_2_RESULTS.md`. Full repository tests remain unrun. Builds, APK/AAB, CI and GitHub Actions were not run.
