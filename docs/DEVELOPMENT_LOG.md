# JARVIS Development Log

This is the append-only record of meaningful implementation, test, experiment, architecture, and documentation work.

**Mandatory for all contributors and agents:** follow `AGENTS.md` and `docs/TESTING_POLICY.md`. Chat history and commit messages are not substitutes for this log.

## Entry format

Each entry should include:

- Date and objective.
- Added, changed, fixed, or removed files/behavior.
- Architectural or technical decisions and their rationale.
- Tests added/updated, with paths and the behavior they cover.
- Exact verification commands, actual status, environment, counts, and duration when available.
- Checks not run and why.
- Benchmark profiles, seeds, train/held-out sizes, result metrics, and report paths where applicable.
- Remaining limitations and the next meaningful step.

Use the status vocabulary from `AGENTS.md`: PASS, FAIL, INTERRUPTED, BLOCKED, NOT RUN, AUTHORED, NOT RUN.

Do not edit old entries to make a result look better. Add a correction entry if an earlier record needs correction. Local JSON reports should be named and described, but should not be committed unless they are intentionally selected as durable project artifacts.

---

## 2026-11-07 — Require durable task records and test evidence

**Objective:** make it mandatory for future agents and contributors to record additions, modifications, tests, results, and limitations in the repository itself.

**Added:**
- `docs/TESTING_POLICY.md` — project-wide test requirements, test quality expectations, verification commands, evidence labels, and interpretation limits.
- `docs/DEVELOPMENT_LOG.md` — this append-only task and verification history.

**Changed:**
- `AGENTS.md` — mandatory development-log entries, test requirements, verification status vocabulary, and completion-report requirements added to the agent instructions.
- `README.md` — contributor-policy and development-log documents added to the repository map.

**Tests added or changed:** none. This is a documentation/process-only change; no source behavior or test code was changed.

**Verification:**
- Repository files were fetched from GitHub `main` before editing.
- Post-commit fetch verification is required and will be recorded after all writes complete.
- Executable tests: **NOT RUN — not applicable to this documentation-only policy change**.

**Builds / CI / GitHub Actions:** NOT RUN. No build or workflow was triggered.

**Limitations:** this policy is enforced through repository instructions and contributor workflow; no automated GitHub branch protection or CI enforcement was configured.

**Next step:** all future meaningful tasks must append their own evidence-backed entry here.

## 2026-11-07 — Verify contributor policy publication

**Verification:** fetched the final files again from GitHub `main` after the documentation commits. Confirmed that:
- `AGENTS.md` contains the mandatory change-log, testing, status-vocabulary, and completion-report rules.
- `docs/TESTING_POLICY.md` contains the required test matrix, execution guidance, and evidence-recording rules.
- `docs/DEVELOPMENT_LOG.md` contains the append-only entry format and the initial policy-change record.
- `README.md` links to the agent instructions, test policy, and development log without a duplicate agent-policy row.

**Git commits created for this policy change:**
- `991e6ab0aa92c34c1caa6d8665035fffe2e8e6f0` — agent requirements.
- `af8a3e8e2b228d51c4ee7220277113d25a16deea` — testing policy.
- `bad5ccd73b6d9184d51e37f9ba82170ca640a8a7` — development log.
- `568bcc40b2df1f3f7fb0b171ea47c8241993eeb8` — initial README links.
- `ccd73c43bc2aae03c9013494a8ff90512fd19346` — remove duplicate README map entry.

**Executable tests:** NOT RUN — documentation-only contributor-policy change.  
**Builds / CI / GitHub Actions:** NOT RUN.

## 2026-11-07 — V4 R-STDP feedback/error-recovery pilot

**Objective:** execute the phone-friendly V4 pilot and assess signed reward/eligibility behavior and held-out error correction.

**Execution supplied by user (Android Termux):**
```sh
python3 -m tools.task_validation.rstdp_feedback_v4 --profile pilot > rstdp_feedback_v4_pilot_report.json
python3 -m unittest tools.task_validation.test_rstdp_feedback_v4 -v
```

**Verification:**
- Benchmark: **PASS** — all 8 seed/condition runs completed (2 seeds × FIXED/STDP/R-STDP/R-STDP neutral).
- Pilot data: per seed 512 matched training pairs (1,024 episodes) and 128 held-out pairs (256 episodes); aggregate 2,048 training episodes and 512 held-out episodes.
- Dedicated V4 tests: **PASS — 12 tests, 0 failures, 0 errors, 2.962 s**.
- Full repository suite after V4: **NOT RUN**. The earlier 107-test pass predates V4.
- Builds, APK/AAB, CI and GitHub Actions: **NOT RUN**.

**Held-out aggregate results:**
- FIXED: 419/512 = 81.8359%; TP=256, FN=0, TN=163, FP=93.
- STDP: 512/512 = 100%; TP=256, FN=0, TN=256, FP=0.
- R-STDP: 425/512 = 83.0078%; TP=256, FN=0, TN=169, FP=87.
- R-STDP neutral: 419/512 = 81.8359%; same aggregate predictions as FIXED.
- R-STDP improvement over FIXED: +1.171875 percentage points (six fewer false positives among 512 held-out episodes). This is exploratory evidence from two seeds, not a robust general claim.

**Signed R-STDP diagnostics:**
- Reward modulators: +1,717 / −331 / 0.
- Eligibility at feedback: negative 1,024 / positive 331 / zero 693.
- Non-zero modulator × eligibility sign matches: 1,355.
- All 1,355 non-zero reward-driven weight updates were negative; total reward delta sum across both runs = −0.004205183945517832.
- Every one of the 331 false-positive training episodes received a negative weight update. Correct FAST episodes also produced negative updates because positive reward was paired with negative eligibility. Correct SLOW episodes had zero eligibility.
- R-STDP final weights: 0.6978193055 and 0.6979755106. STDP final weights: 0.6055889854 and 0.6090516151.
- The neutral condition had 2,048 zero modulators and no weight change.

**Interpretation:** the signed three-factor numerical rule and diagnostics behaved consistently with the specified product-sign equation. However, in this task the observed eligibility signs made both correct FAST outcomes and false-positive SLOW outcomes depress the same scalar synaptic weight. R-STDP only modestly improved on FIXED and remained well below STDP; the pilot does not establish robust task-level error correction. No equation, STARTER parameter, or learning rate was changed.

**Code inspection:** reviewed `update_rstdp`, `apply_reward`, V4 episode feedback handling and diagnostic aggregation. No obvious sign-accounting discrepancy was found in the inspected paths. This was source inspection, not an additional execution/test.

**Artifacts:** `docs/TASK_VALIDATION_R_STDP_V4_PILOT_RESULTS.md` records the metrics and interpretation. Local JSON report was not committed.

**Next step:** inspect representative episode-level traces, then run the full pre-registered V4 profile when practical without changing the protocol, equations, or canonical STARTER. Preserve the distinction between pilot and full results.


## 2026-11-07 — V4.1 eligibility–reward alignment audit authored

**Objective:** expose which task-level episode types produce each eligibility sign, verify the reward-baseline contribution to the modulator, and reconcile every reward-driven weight delta without changing the V4 experiment or learning rule.

**Changed:**
- `tools/task_validation/rstdp_feedback_v4.py` — added a six-dimensional training-feedback cross-tab (label, prediction, correctness, eligibility sign, modulator sign, weight-delta sign); reward-baseline before-event statistics; a direct check of `modulator = reward_value - baseline_before`; and counters for product/delta sign agreement, zero updates despite a non-zero product, and non-zero deltas without a non-zero product. Per-seed and aggregate reports preserve the table.
- `tools/task_validation/test_rstdp_feedback_v4.py` — added regression tests for cross-tab coverage, sign-count reconciliation, product/delta consistency and reward-baseline consistency.
- `docs/TASK_VALIDATION_R_STDP_V4.md` — documented the V4.1 report extension and interpretation boundaries.
- `docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md` — added the standalone V4.1 audit protocol.
- `docs/JARVIS_ARCHITECTURE.md` and `README.md` — updated the next research gate and repository status.

**Tests:** V4.1 tests are **AUTHORED / NOT RUN**. No fresh numerical result is claimed. The previously user-run V4 pilot evidence remains unchanged.

**Checks:** source-level replacement/preflight checks completed before publication. Python unit tests and the V4.1 pilot are **NOT RUN** in this change.

**STARTER / learning rule:** canonical STARTER weight 0.5, diagnostic initial weight 0.70, reward baseline profile, STDP/R-STDP equations and learning rates are unchanged.

**Builds / CI / GitHub Actions:** NOT RUN. No application build, APK/AAB, or workflow was triggered.

**Limitations / next step:** the cross-tab is descriptive, not causal proof of useful credit assignment. Run the V4.1 pilot and the dedicated test module on Android Termux, then inspect the six-dimensional cells before deciding whether to run the full V4 profile or design a separate credit-assignment test.
