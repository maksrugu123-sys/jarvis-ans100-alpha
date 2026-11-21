# V4 R-STDP Feedback/Error-Recovery — Pilot Results

**Benchmark:** `JARVIS_R_STDP_FEEDBACK_ERROR_RECOVERY_V4`  
**Run date:** 7 November 2026  
**Execution environment:** Android Termux; exact Python version was not included in the supplied output.  
**Status:** exploratory pilot completed; full pre-registered profile remains pending.  
**Canonical STARTER:** unchanged at 0.5.  
**Diagnostic initial weight:** 0.70 for all four task-level conditions; this is not a calibrated profile.

## 1. Commands and execution

User-run benchmark:

```sh
python3 -m tools.task_validation.rstdp_feedback_v4 --profile pilot > rstdp_feedback_v4_pilot_report.json
```

The CLI reported completion of all 8 seed/condition runs:

- Seeds: 104729 and 130363.
- Per seed: 512 matched training pairs (1,024 episodes) and 128 matched held-out pairs (256 episodes).
- Combined across both seeds: 2,048 training episodes and 512 held-out episodes.
- Every condition used the same task protocol and matched data within each seed.

Dedicated V4 tests:

```sh
python3 -m unittest tools.task_validation.test_rstdp_feedback_v4 -v
```

**PASS:** 12 tests, 0 failures, 0 errors, 2.962 seconds.

The full repository test suite was not run as part of this V4 pilot. The previously reported 107-test result predates V4 and must not be represented as a post-V4 full-suite pass.

## 2. Held-out results

Aggregate held-out set: 512 episodes (256 FAST, 256 SLOW).

| Condition | Correct | Accuracy | TP | FN | TN | FP |
|---|---:|---:|---:|---:|---:|---:|
| FIXED | 419/512 | 81.8359% | 256 | 0 | 163 | 93 |
| STDP | 512/512 | **100.0000%** | 256 | 0 | 256 | 0 |
| R-STDP | 425/512 | 83.0078% | 256 | 0 | 169 | 87 |
| R-STDP neutral | 419/512 | 81.8359% | 256 | 0 | 163 | 93 |

Per-seed held-out accuracy summary from the report:

- FIXED: mean 81.8359%, range 80.8594–82.8125%.
- STDP: mean 100.0000%, both runs 100%.
- R-STDP: mean 83.0078%, range 81.6406–84.3750%.
- R-STDP neutral matched FIXED on the aggregate held-out predictions.

R-STDP exceeded FIXED by **1.171875 percentage points**, corresponding to 6 fewer false positives among 512 held-out episodes. This is a small pilot difference, not sufficient evidence of robust task-level error correction. STDP outperformed all other conditions and made no held-out errors in either pilot seed.

## 3. Training results

| Condition | Correct | Accuracy | False positives |
|---|---:|---:|---:|
| FIXED | 1,701/2,048 | 83.0566% | 347 |
| STDP | 2,031/2,048 | 99.1699% | 17 |
| R-STDP | 1,717/2,048 | 83.8379% | 331 |
| R-STDP neutral | 1,701/2,048 | 83.0566% | 347 |

STDP's per-seed final weights were 0.6055889854 and 0.6090516151 (mean approximately 0.6073203003). R-STDP's were 0.6978193055 and 0.6979755106 (mean approximately 0.6978974079). FIXED and neutral R-STDP remained at 0.70.

## 4. Signed feedback diagnostics

R-STDP received both reward-modulator signs:

| Signal | Count |
|---|---:|
| Positive modulators | 1,717 |
| Negative modulators | 331 |
| Zero modulators | 0 |
| Negative eligibility at feedback | 1,024 |
| Positive eligibility at feedback | 331 |
| Zero eligibility | 693 |
| Non-zero eligibility × modulator sign matches | 1,355 |
| Reward events | 2,048 |
| Incorrect training outcomes | 331 |

All **1,355 non-zero reward-driven weight updates were negative**:

- Positive reward with negative eligibility: 1,024 depressing updates.
- Negative reward with positive eligibility: 331 depressing updates.
- Zero eligibility: 693 no-change outcomes.
- Total reward-driven weight delta across the two runs: **−0.004205183945517832**.
- All 331 false-positive training episodes received a negative weight update.
- No false-negative training episodes occurred, so the task-level false-negative correction counter remained zero.

This explains the behavior more precisely than reward sign alone. The three-factor equation uses the product of reward modulation and eligibility; positive reward does not necessarily mean potentiation. Correct FAST episodes produced negative eligibility, so their positive reward also depressed the weight. Incorrect SLOW false positives produced positive eligibility, so their negative reward depressed the weight too. In this task, both observed non-zero event types therefore pushed the same single synaptic weight downward.

## 5. Interpretation

1. **The signed R-STDP mechanism was exercised.** Both positive and negative reward modulators occurred, and the measured update direction matched the sign of modulator × eligibility for all 1,355 non-zero updates.
2. **Neutral control behaved as intended.** Its modulator was zero for all 2,048 reward events, it made no reward-driven weight changes, and its held-out predictions matched FIXED.
3. **Task-level error correction was weak.** R-STDP reduced held-out false positives from 93 to 87 relative to FIXED, but remained far below STDP's zero errors. With only two seeds and a reduced pilot, the 6-example difference is preliminary.
4. **The central limitation is credit assignment in this task.** Correct FAST episodes and incorrect SLOW false positives both generated weight depression because their eligibility signs opposed their reward-modulator signs. A single scalar excitatory weight cannot selectively reinforce one outcome class under this observed eligibility/outcome mapping.
5. **No implementation sign bug is evident from the inspected formula and counters.** The reference rule computes the reward update from `eta_r × modulator × eligibility × weight_span`, applies its soft bound and cap, and consumes the episode eligibility context. This verifies consistency with the specified mathematical rule; it does not prove the rule is the best learning design for the task.

The result does **not** show that R-STDP is generally ineffective. It shows that this particular one-neuron/one-synapse task and current eligibility mapping produced mostly non-discriminative depression. It also does not establish autonomous self-correction, general intelligence, consciousness, or production-runtime behavior.

## 6. Next validation step

Do not change the canonical STARTER, learning equations, or learning rates based only on this pilot.

Recommended sequence:

1. Inspect representative episode-level traces: correct FAST, correct SLOW, and false-positive SLOW, including pulse times, output spike times, eligibility, reward modulator, and weight delta.
2. Confirm that the eligibility/outcome relationship is reproducible and not an artifact of a diagnostic aggregation.
3. Run the full pre-registered profile (8,000 training pairs, 256 held-out pairs, four seeds) on the same frozen implementation when practical. Compare FIXED, STDP, rewarded R-STDP and neutral R-STDP without changing the task or STARTER.

## 7. Limits and checks not performed

- The pilot is reduced-size and exploratory; it does not replace the full pre-registered benchmark.
- No full post-V4 repository test suite was run in this session.
- No application build, APK/AAB generation, CI/GitHub Actions workflow, production runtime test, or target-device resource measurement was run.
- The supplied output did not include the exact Python version or full per-episode trace excerpts.
- The local JSON report remains the user's local artifact; it was not committed to this repository.
