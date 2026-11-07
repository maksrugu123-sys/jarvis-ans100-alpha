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

## 2026-11-07 — V4.1 user-run verification and V4.2 causal-credit audit authored

**Objective:** record the user's V4.1 execution evidence, then add a pulse-level diagnostic to isolate where eligibility signs are produced without changing the V4/R-STDP model.

**V4.1 execution supplied by user (Android Termux):**
- Command: python3 -m unittest tools.task_validation.test_rstdp_feedback_v4 -v
- Result: **PASS — 15 tests, 0 failures, 0 errors, 4.061 s**.
- Command: python3 -m tools.task_validation.rstdp_feedback_v4 --profile pilot
- Result: **PASS as an executed pilot — 8/8 conditions completed**, two seeds × four conditions; 512 training pairs and 128 held-out pairs per seed.
- R-STDP feedback/update counts: 2,048 feedback events; 0 positive, 1,355 negative and 693 zero reward-driven weight updates.
- Cross-tab: 1,024 correct FAST with negative eligibility / positive modulator / negative delta; 331 false-positive SLOW→FAST with positive eligibility / negative modulator / negative delta; 693 correct SLOW with zero eligibility / positive modulator / zero delta.
- Baseline-before-reward: initial 0.5, mean 0.8045988381060147, min 0.495, max 0.891672785088837; beta_b 0.01.
- Neutral R-STDP had 2,048 zero weight updates and retained baseline 0.5.
- The full repository suite after V4.1 was **NOT RUN**. These results were supplied by the user and were not rerun by this author.

**Added for V4.2:**
- tools/task_validation/rstdp_causal_credit_v42.py — replays only the V4 R-STDP training stream, captures trace/q/eligibility after each of three pulses, decomposes pulse contributions at 40 ms feedback, checks the Oracle batch equation, and compares it with an isolated 1 ns pre→post shadow ordering.
- tools/task_validation/test_rstdp_causal_credit_v42.py — tests same-time batch tie behavior, ordered sign sensitivity, pulse aggregation, contribution reconciliation, exact parity with V4 training metrics/final weight/reward baseline, shadow isolation and deterministic JSON output.
- docs/TASK_VALIDATION_R_STDP_V4_2_CREDIT_AUDIT.md — protocol, assumptions, commands, controls and interpretation limits.
- Updated docs/TASK_VALIDATION_R_STDP_V4.md, docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md, docs/JARVIS_ARCHITECTURE.md and README.md to record the V4.1 user-run evidence and V4.2 next step.

**Technical decision:** preserve the Oracle's canonical simultaneous-batch rule. The 1 ns ordered pathway is a separate shadow only; it cannot affect the actual neuron, synapse, reward baseline, decisions or later episodes. The canonical STARTER weight 0.5 and diagnostic V4 starting weight 0.70 remain unchanged.

**Verification:** V4.2 code and dedicated tests are **AUTHORED / NOT RUN** at publication. No V4.2 numerical result is claimed. The code includes internal q/eligibility consistency assertions and the tests include a replay parity check; those assertions are not treated as executed verification until the user runs them.

**Builds / CI / GitHub Actions:** NOT RUN. No application build, APK/AAB, CI job or workflow was triggered.

**Remaining limitations / next step:** run the V4.2 focused test module and smoke profile first, then the phone-friendly pilot. Review pulse contribution residuals and actual/shadow sign changes. This diagnostic is not a held-out learning experiment and cannot justify changing event ordering or learning rules by itself.


## 2026-11-07 — V5 compact adaptive neuron core research specification

**Objective:** begin the MASTER PROMPT V2 direction with a coherent architecture-level improvement focused on maximum useful capability per byte and per operation, without changing the current neuron equations or silently degrading quality.

**Repository audit:**
- Confirmed the latest main head before this change was `f3728ce1a0fa88f79a79d27ffd3ddf79d4acf10a`.
- README, AGENTS.md, TESTING_POLICY.md, the current architecture, Numerical Oracle, V4/V4.1/V4.2 protocols, and development history were inspected.
- V4.1 user-run evidence remains 15/15 dedicated tests and 8/8 pilot conditions.
- V4.2 code/tests remain AUTHORED / NOT RUN; no V4.2 result is claimed.
- Production neuron runtime and target-device memory/latency measurements do not exist.

**Added:**
- `docs/JARVIS_COMPACT_NEURON_CORE_V5.md` — proposal for shared immutable parameter sets, hot/cold state separation, rule-specific synapse payloads, compact indexed events, derived-state checks, optional future shared slow context, measurement protocol and explicit acceptance gates.
- `docs/JARVIS_ARCHITECTURE.md` — section 3.28 links and records the proposed V5 architecture and its limits.
- `README.md` — records V5 as PROPOSED, not implemented or measured.

**Technical decisions:**
- Keep the current F64 Numerical Oracle and Adaptive LIF-DT/STDP/R-STDP behaviour as the control.
- Proposed hot payload estimates only: neuron 40 bytes; synapse NONE 8 bytes; STDP 32 bytes; R-STDP 52 bytes (alignment may produce a 56-byte stride). These exclude durable envelopes, IDs, indexes, integrity metadata and allocator overhead; they are not RAM measurements.
- Derive last-spike time only when the refractory/parameter-version invariant is proven.
- Use rule-specific synapse state and a shared R-STDP timestamp only after explicit invariant tests.
- Do not add quantization, new learning rules, changed event ordering, shared slow memory or production runtime in this specification.
- Preserve STARTER, V4 and V4.2. V4.2 must still be run before changing causal-credit or simultaneous-event semantics.

**Research references reviewed:**
- Sun et al., Nature Machine Intelligence (16 June 2026), dual-memory-pathway SNN; shared low-dimensional slow context and task-specific parameter reductions. This motivates only a future multi-neuron experiment.
- Huang et al., ICLR 2026, checkpointing and lossless spike compression for BPTT memory efficiency. JARVIS currently has no BPTT training tape, so this is explicitly future-only.
- Cheng et al., IEEE Transactions on Circuits and Systems I (2025), structured sparsity/event-driven SNN accelerator; hardware/task-specific evidence only.

**Verification performed:** static repository/document/source inspection and cross-check of field invariants in the current Oracle source. The existing update_rstdp/apply_reward paths assign trace and eligibility timestamps to the same event time, but the current generic state validator does not enforce equality for arbitrary constructed states; V5 must test and enforce this in its own versioned state contract before collapsing the fields.

**Tests / benchmarks / device measurements:** NOT RUN. The new specification is documentation-only. V4.2 remains AUTHORED / NOT RUN. No memory, RSS/PSS, latency or energy measurement is claimed.

**Builds / APK/AAB / CI / GitHub Actions:** NOT RUN.

**Next step:** user-run V4.2 focused tests and smoke/pilot; then add a small F64 packed-state/resource-baseline prototype and compare it differentially with the Numerical Oracle before any precision or learning-rule changes.


## 2026-11-07 — V5 documentation structure verification

A post-publication fetch check found that the new architecture subsection had been appended after the document's status section instead of being placed beside the other neural-core subsections, and the README insertion contained a literal escaped newline. Both documentation-only issues were corrected: section 3.28 now appears immediately before section 4, and the README V4.2 bullet is on its own line. No source code, equations, parameters, benchmark protocols or test claims changed. Static fetch/content checks were performed; executable tests and benchmarks were not applicable or run. Builds, APK/AAB, CI and GitHub Actions were not run.
