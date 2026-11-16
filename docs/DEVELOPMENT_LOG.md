# Development update — Chat polish and conversation recall

Date: 2026-11-14
Branch: `experiment/jarvis-integrated-prototype-v1`
Base: `d9d09d2978dccc8fa8b17febf6201385f95e8678`

## Changes
- Kept the current dedicated chat layout and applied the animated gradient border to the newest JARVIS response bubble; older messages keep a low-cost static border.
- Separated response timing from model installation controls into its own Settings → Speed category.
- Added deterministic recall for a user's name when asked “Как меня зовут?”. It only answers from an explicit name in recent saved chat history and has unit coverage for both known and unknown names.
- Existing settings continue to persist accent color and flowing glow. The model's measured initialization/generation/turn metrics and ANS-100 counters remain visible in settings.

## Verification
- JVM tests and debug APK build are triggered by this commit; outcomes will be added after the Actions run completes.
- No claims are made about faster generation or autonomous semantic reasoning without measurements and tests.

---

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


## 2026-11-07 — README newline literal corrected

A second byte-level check showed the README V5/V4.2 separator still contained the two literal characters backslash+n after the prior correction attempt. The exact marker was replaced by a real newline. This is a documentation-only correction; no code, equations, protocols or test status changed. The file is fetched again after publication for verification. No executable tests/benchmarks, builds, APK/AAB, CI or GitHub Actions were run.


## 2026-11-07 — V4.2 unittest runner name collision fix

The user's Android Termux run exposed a test-harness failure: setUpClass assigned the report dictionary to cls.run, shadowing unittest.TestCase.run(). The unittest suite then attempted to call the dictionary and raised TypeError: 'dict' object is not callable before test methods could execute.

Renamed the stored report alias to audit_run and updated its test references. This is a test-only fix; it does not modify the V4.2 audit implementation, the Numerical Oracle, neuron equations, STARTER, V4 datasets, or benchmark protocols.

**Verification:** static inspection confirms the cls.run/self.run shadowing references are absent. The dedicated tests have not yet been rerun; no passing result is claimed. The user's smoke and pilot commands did execute according to their progress output, but their JSON reports have not yet been inspected.

**Builds / CI / GitHub Actions:** NOT RUN.


## 2026-11-07 — V4.2 user-run evidence

The user reran the dedicated V4.2 tests after the unittest runner-name collision fix: 6/6 passed in 0.288 seconds. Smoke (128 episodes) and pilot (2,048 episodes, two seeds) completed on Android Termux.

The pilot produced 1,355 negative, zero positive and 693 zero weight updates. Correct FAST episodes had negative eligibility with positive feedback; false-positive SLOW→FAST episodes had positive eligibility with negative feedback; correct SLOW episodes had zero eligibility. The 1 ns shadow changed aggregate eligibility sum from -1.6586585879267512 to +0.32333505612981234, but no per-episode eligibility or weight-update sign changed. This supports a task-specific temporal-credit alignment diagnosis; it does not establish a fix or general learning capability.

Detailed evidence: docs/TASK_VALIDATION_R_STDP_V4_2_RESULTS.md. Full repository tests after V4.2 remain unrun. No builds, APK/AAB, CI or GitHub Actions were run.


## 2026-11-08 — V4.3 decision-boundary temporal-credit experiment authored

V4.2 pulse-level evidence motivated a narrow hypothesis: in FAST episodes, the first output spike can latch the classification on pulse 2, while pulse 3 still contributes an eligibility update and may add LTD after the task decision is already irreversible.

Added a separate V4.3 reference benchmark comparing FIXED, STDP, frozen V4 R-STDP, decision-latched R-STDP and neutral R-STDP. The decision-latched path continues processing all sensory inputs but skips plasticity events after the first output spike; the spike-producing pulse itself remains eligible. Canonical STARTER 0.5, diagnostic initial weight 0.70, all learning parameters, event ordering, reward equation and V4 dataset generator are preserved. Added a frozen-V4 replay parity test and dedicated protocol/results documents.

Status at publication: authored, NOT RUN. No tests or benchmark have been run by the assistant. No builds, APK/AAB, CI or GitHub Actions were run.


## 2026-11-08 — V4.3 pre-run audit: prove sensory processing and decision-latched plasticity

**Objective:** strengthen V4.3 regression evidence before the user runs it. Static review found that the previous test only asserted a skipped-event count was non-negative; zero skipped events would have passed.

**Changed:**
- `tools/task_validation/rstdp_feedback_v4_3.py` — added total sensory-input and post-decision sensory-input counters. They increment only after `process_neuron_batch` returns. The neuron, learning equations, event ordering and STARTER are unchanged.
- `tools/task_validation/test_rstdp_feedback_v4_3.py` — added a matched FAST episode comparison between frozen V4 and decision-latched policies; asserts identical spike times/prediction, all three sensory inputs processed, and post-decision plasticity skipped. Aggregate assertions now require a positive skip count, complete sensory-input accounting and equality between post-decision sensory inputs and skipped plasticity updates.
- `docs/TASK_VALIDATION_R_STDP_V4_3.md` and `docs/TASK_VALIDATION_R_STDP_V4_3_RESULTS.md` — documented the stronger acceptance gate and kept execution status pending.
- `docs/JARVIS_ARCHITECTURE.md`, `README.md`, `docs/TASK_VALIDATION_R_STDP_V4.md` and `docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md` — synchronized current V4.2/V4.3 status and documented the evidence-gated V5 transition; historical result records were not rewritten.

**Repository review:** recursive Git tree contained 53 entries / 48 files, `truncated=false`. Reviewed contributor/test policy, project map/status, architecture, V5 proposal, development log, Numerical Oracle and tests, CQE reference and tests, calibration tools, V1–V4.3 implementations/tests/protocols/results. The earlier chat count of 42 tree entries was inaccurate and is superseded by this verified inventory.

**Tests authored/updated:** `tools/task_validation/test_rstdp_feedback_v4_3.py` — non-zero decision-latched skipping, complete sensory-input processing, skipped-update reconciliation and unchanged output trajectory for a matched FAST episode. Status: **AUTHORED / NOT RUN**.

**Verification performed:** latest `main` and file blob SHAs fetched before preparing the change; static source/protocol review only. No runtime test is claimed.

**Not run:** V4.3 focused tests, smoke/pilot, full repository unit-test suite, builds, APK/AAB, CI, GitHub Actions and device RAM/CPU/latency measurements.

**Limitations:** V4.3 remains untested. Decision-latched credit is task-specific and must not be generalized to biological STDP. The user must run the strengthened tests and benchmark on Android Termux before treating V4.3 as evidence.

**Next step:** pull `main`, run focused V4.3 tests, then smoke and pilot; compare held-out metrics with frozen V4, FIXED, neutral R-STDP and STDP before beginning the V5 F64 packed-state prototype.


## 2026-11-08 — Record V4.3 user results and author V5.1 resource baseline

**V4.3 user evidence (Android Termux):** focused tests PASS (7 tests, 1.636 s); smoke and pilot completed. Pilot: two seeds, 512 training pairs and 128 held-out pairs per seed. Held-out accuracy: FIXED 81.8359%, STDP 100%, frozen V4 R-STDP 83.0078%, Decision-Latched 82.2266%, Neutral 81.8359%. Decision-Latched processed all 6,144 sensory inputs, including 1,024 post-decision inputs, and skipped 1,024 post-decision plasticity updates; it did not beat V4. Retain it as experimental, not as default. Details in `docs/TASK_VALIDATION_R_STDP_V4_3_RESULTS.md`.

**Added V5.1 baseline harness:** `tools/compact_core_v5/resource_baseline.py`, package initializer and `test_resource_baseline.py`. Measures Python object graph size, pickle protocol 5 reference serialization size, tracemalloc allocations and `/proc/self/status` RSS/HWM where available. Population profiles: smoke (1,10), pilot (1,10,100,1,000), full (1,10,100,1,000,10,000). A shared-vs-replicated parameter scenario is labeled illustrative, not the current Oracle layout. Pickle is diagnostic only, not a persistent-storage format.

**Tests authored:** `tools/compact_core_v5/test_resource_baseline.py` covers shared-object accounting, deterministic population construction, profile shapes, JSON safety and invalid inputs. Status: **AUTHORED / NOT RUN**.

**Not run:** resource baseline smoke/pilot, full repository tests, CPU/latency/energy benchmark, packed-state candidate, builds, APK/AAB, CI or GitHub Actions. No memory reduction is claimed.

**Next step:** user runs focused baseline tests, then smoke and phone-friendly pilot. Use those measurements to choose the packed-state layout; do not skip the baseline gate.


## 2026-11-08 — SuperNeuron BTCC research specification

**Objective:** define a research candidate aimed at improving causal credit assignment while keeping extra computation conditional and bounded.

**Added:** `docs/JARVIS_SUPER_NEURON_BTCC_RESEARCH.md` — proposed Boundary-Triggered Counterfactual Credit (BTCC), including decision-margin scoring, leave-one-event-out loss differences, bounded shadow replays, a compute gate, a proposed normalized event-credit update, mathematical/finite-difference acceptance checks, controls, and a harder temporal/noise benchmark plan.

**Research audit:** reviewed e-prop, Expected Eligibility Traces, EventProp-based event learning with delays, 2026 diffusive neuromodulatory credit, and predictive early stopping in SNN accelerators. These establish related prior art. BTCC is described only as a JARVIS-specific engineering composition; no claim that nobody has proposed a similar method or that it is scientifically novel is made.

**Changed:** README and `docs/JARVIS_ARCHITECTURE.md` now link the proposal. README V4.3 status was corrected to reflect the user's completed 7/7 tests and smoke/pilot result; the full repository suite remains NOT RUN.

**Tests:** none; documentation/research specification only. No implementation or benchmark is claimed.

**Not run:** BTCC replay oracle, finite-difference checks, unit tests, task experiments, resource profiling, builds, APK/AAB, CI and GitHub Actions.

**Next step:** complete V5.1 reference-resource baseline separately, then implement a deterministic BTCC replay oracle and compare its local event-credit direction with central finite-difference loss gradients before running any task-level benchmark.


## 2026-11-08 — BTCC 0.2 replay oracle and finite-difference audit authored

**Added:** `tools/super_neuron_btcc/credit_oracle.py` — bounded deterministic replay using Numerical Oracle event dynamics; pre-reset non-refractory margin capture; smooth binary loss; leave-one-event-out omissions; ALWAYS/GATED audit modes; approximate per-synapse descent direction; central finite-difference gradient comparator. Added `tools/super_neuron_btcc/test_credit_oracle.py` with 14 focused tests covering signs, gradients, gating, deterministic batches, isolation, invalid input and a redundancy counterexample. Added `docs/TASK_VALIDATION_SUPER_NEURON_BTCC_0_2.md`.

**Mathematical audit:** BTCC leave-one-out contribution is a marginal loss difference, not a guaranteed causal attribution or Shapley value. The approximation for event contribution and gradient direction is tested against finite differences; it is not accepted as an update rule yet. The test suite explicitly records the redundancy blind spot: separate events can each have zero omission contribution while shared synaptic-weight gradient remains nonzero.

**Updated:** README, `docs/JARVIS_ARCHITECTURE.md`; latest README V4.3 evidence text corrected to reflect user-supplied PASS and benchmark results.

**Tests:** AUTHORED / NOT RUN. The assistant environment has no local private-repository checkout/Python runner; no test result is claimed. User command: `python3 -m unittest tools.super_neuron_btcc.test_credit_oracle -v`.

**Not run:** BTCC unit tests, full repository suite, task-level benchmark, finite-difference agreement measurement, resource profiling, builds, APK/AAB, CI and GitHub Actions. No quality or performance improvement is claimed.

**Next step:** inspect user-run focused results. If the mathematical tests pass, add sampled coalition/redundancy handling as a separately measured candidate and then test BTCC on a harder temporal/distractor benchmark before considering integration with the learning rule.


### 2026-11-08 — BTCC 0.2 post-publication static audit correction

A static review after the single-synapse omission regression was added confirmed that the focused BTCC test module contains **15 tests**, not 14 as stated in the initial entry. The extra test covers removing the only event belonging to a synapse; the counterfactual replay now filters the shadow weight map to only synapses remaining in that episode. Target labels now require integer 0/1, max-event bounds are validated before comparison, and finite-difference setup validates synapse parameters. These are static-reviewed changes only. **All 15 tests remain AUTHORED / NOT RUN.** The README V4.3 status bullet was also corrected to match the user-run 7/7 tests and smoke/pilot evidence. No build or CI was run.


### 2026-11-08 — BTCC deadline replay audit

A static code audit found that the first BTCC replay version only evaluated decision margins at sensory-event timestamps, even when the protocol supplied a later decision deadline. This could miss a spike caused by an input retained during refractoriness and could omit the deadline from the margin trajectory. Added an explicit empty-batch deadline evaluation when the deadline is later than the final sensory event; a deadline coincident with an input timestamp is not processed twice. Added a hard maximum of 64 candidate events per episode (default remains 8), plus tests for a refractory-retained input that spikes exactly at the deadline and an empty episode whose margin is evaluated at the deadline. Focused suite now contains 17 tests. **AUTHORED / NOT RUN**; no task benchmark or learning-rule integration. This is a static-review correction, not an experimentally verified fix.

### 2026-11-08 — Clean uploaded-archive regression repairs and complete verification

**Objective:** use a fresh extraction of the exact uploaded `jarvis-ans100-alpha-main.zip` as the only source of truth, reproduce its failing checks, repair the regressions without changing the neural equations, and run the full available unit suite.

**Environment:** exact uploaded ZIP freshly extracted; Python 3.13.5, Linux x86_64 (`Linux-6.18.57-x86_64-with-glibc2.41`). No `.git` metadata is present in the uploaded archive.

**Failures reproduced before the fix:**
- `python3 -m unittest discover -s tools -t . -v` — **FAIL, 156/158 tests passed; 2 failures**.
- BTCC failure: `test_decision_deadline_processes_retained_refractory_input` supplied an event at time 0 that emitted the first spike, then asserted that the *first* spike occurred at 2 ms. The assertion contradicted the fixture's own event sequence. Corrected the fixture to begin with an explicit `NeuronState` already inside a refractory interval that expires at 2 ms; a single event at 1 ms is retained and produces the first episode spike exactly at the decision deadline. The production replay implementation was not changed.
- V5.1 failure: the smoke test compared two complete resource reports for equality. The report includes `sys.getsizeof` object-graph estimates and tracemalloc observations, which varied with CPython instance-dictionary/key-sharing and allocator state even for equivalent logical populations. The fixture shape and pickle diagnostic size were stable.

**Changed implementation/tests:**
- `tools/super_neuron_btcc/test_credit_oracle.py` — corrected the refractory regression fixture to model a pre-existing refractory interval. It now tests the intended behavior (input retained during refractoriness, evaluated at deadline) without first creating another spike inside the same episode.
- `tools/compact_core_v5/test_resource_baseline.py` — replaced whole-report equality with checks of deterministic population shape, object counts, pickle-size diagnostics and fixed report fields; measured deep-size/tracemalloc values are checked for valid non-negative integers; `/proc` fields are checked as absent when disabled.
- `tools/compact_core_v5/resource_baseline.py` — the report limitations now explicitly state that deep object-graph and tracemalloc readings are runtime observations, not deterministic fixture values.

**Documentation/status updates:**
- `README.md` — records exact-archive verification and clarifies BTCC/V5.1 state.
- `docs/JARVIS_ARCHITECTURE.md` — records the 17/17 BTCC and 6/6 V5.1 unit results, environment and unverified boundaries; removes the stale V4.3 next-step wording.
- `docs/JARVIS_SUPER_NEURON_BTCC_RESEARCH.md` — marks 0.2/0.3 oracle/unit stages implemented, while keeping task-level validation pending.
- `docs/TASK_VALIDATION_SUPER_NEURON_BTCC_0_2.md` — corrects test count to 17 and records the exact verification command and limitations.
- `docs/DEVELOPMENT_LOG.md` — this append-only result record.

**Final verification:**
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools.super_neuron_btcc.test_credit_oracle -v` — **PASS, 17/17**, 0 failures.
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools.compact_core_v5.test_resource_baseline -v` — **PASS, 6/6**, 0 failures.
- `PYTHONDONTWRITEBYTECODE=1 python3 -m tools.compact_core_v5.resource_baseline --profile smoke` — **PASS**, profile smoke (population sizes 1 and 10). One container run reported deep-object-graph sizes 4,006 / 16,710 bytes; pickle protocol 5 sizes 829 / 3,759 bytes; tracemalloc current deltas 9,495 / 37,715 bytes; tracemalloc peak deltas 16,428 / 52,328 bytes; `/proc` VmRSS/VmHWM deltas 28 / 0 KiB. These are container-only Python-reference measurements, not device or packed-candidate results.
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -t . -v` — **PASS, 158/158**, 0 failures (final verification rerun 4.102 s).

**Scientific interpretation:** BTCC now has a correct, passing deadline/refractory unit regression, but that does not demonstrate a task-level advantage, a representative gradient-direction agreement rate, or an accepted learning rule. Its leave-one-out redundancy limitation remains. V5.1 smoke measures only the existing Python Oracle representation; no compact packed-state candidate or memory reduction has been demonstrated.

**Not run:** BTCC task-level smoke/pilot/harder temporal-noise benchmark; V5.1 pilot/full; Android Termux resource measurement; packed-state implementation or F64 differential experiment; builds, APK/AAB, CI or GitHub Actions.

**Next meaningful step:** pre-register a harder BTCC temporal/noise task with independent held-out inputs, frozen baseline controls and an explicit compute budget before testing any training integration. Keep the canonical STARTER and production learning equations unchanged until that evidence exists.



### 2026-11-08 — Unified Python master test runner authored

**Goal:** provide one repeatable command for routine Python verification so contributors do not have to remember and launch every focused unit-test module separately.

**Added:**
- `tools/run_all_checks.py` — a standard-library-only master entry point. It syntax-compiles Python files below `tools/` without executing them, checks test-module discoverability, runs the full `unittest` discovery suite, prints one overall PASS/FAIL result, and can optionally save a JSON report with `--json-report`.
- `tools/test_run_all_checks.py` — four tests for generated-directory exclusion, syntax-error reporting, test-package discoverability, and nested-suite counting.

**Updated:** `README.md` and `docs/TESTING_POLICY.md` now document `python3 -m tools.run_all_checks` as the routine full-unit-suite command.

**Verification:** before publication, an isolated temporary copy of the runner and its four guardrail tests was executed with Python in the assistant's Linux container: **PASS, 4/4, 0 failures**. This validates those runner helper behaviors only; it is not a run of the repository's existing tests.

**Repository test status:** the committed `tools/test_run_all_checks.py` is **AUTHORED, NOT RUN** against the full repository. The master runner has **NOT RUN** against the full GitHub checkout in this session, so no current full-suite count or PASS is claimed. Its discovery is based on the existing `unittest discover -s tools -t .` policy and automatically includes future `test*.py` modules inside packages.

**Not run:** existing full repository unit suite through the new entry point; task-level BTCC/noise benchmark; V5.1 pilot/full resource profiles; Android/Termux measurements; builds, APK/AAB, CI and GitHub Actions. These remain intentionally outside the master runner.

**Limitations:** a passing master test checks Python syntax and discovered unit-test assertions only. It does not prove task-level learning benefit, complete behavioral correctness, runtime resource use, Android compatibility, production readiness, or any excluded benchmark/build/CI result.

**Next step:** run `python3 -m tools.run_all_checks` from the project root in Termux and share the complete summary if any module fails or is not discovered.



### 2026-11-08 — Comprehensive one-neuron exam authored

**Goal:** replace fragmented checking with one repeatable research/engineering exam that covers all implemented reference-neuron mechanisms and clearly identifies architecture that cannot yet be tested because it is not implemented.

**Added:**
- `tools/neuron_validation/test_neuron_full_exam.py` — broad deterministic contract tests for analytical membrane/adaptation decay, threshold and attention bounds, simultaneous input determinism, excitatory/inhibitory saturation, refractory boundary behavior, spike/reset/adaptation state, timestamp overflow, invalid states/parameters, STDP timing signs and traces, long bounded STDP updates, R-STDP eligibility/reward direction/context/expiry, float32 helper comparison, and multi-seed randomized event/reward stress.
- `tools/neuron_validation/noise_benchmark.py` — a separate same-count FAST/SLOW temporal task with weak excitatory distractors; matched distractors for each pair; split-specific deterministic signatures; FIXED, STDP, R-STDP and neutral R-STDP controls; inference-only held-out evaluation; accuracy, balanced accuracy, macro-F1, confusion counts and resource counters. The diagnostic starting weight is 0.70; canonical STARTER 0.50 remains unchanged.
- `tools/neuron_validation/full_exam.py` — one entry point running syntax/discovery checks, all repository unit tests, the noisy temporal benchmark and the V5.1 reference-object resource baseline. It saves a machine-readable JSON report and explicitly labels unimplemented capabilities.

**Updated:** `README.md` and `docs/TESTING_POLICY.md` now identify the comprehensive exam as the broad one-command validation path. Added `docs/JARVIS_NEURON_FULL_EXAM.md` with profiles, protocol, coverage matrix and interpretation rules.

**Important implementation detail:** each event in a noisy episode is constructed with the synapse's current weight, so within-episode STDP changes affect later input pulses. Decision-deadline spikes are counted and, when present, included as a post event before R-STDP reward application. Training/held-out signatures use 96-bit signal-strength material plus separate split namespaces.

**Verification:** GitHub fetches confirmed the new files and documentation were published on `main`. Code was reviewed statically, but no Python interpreter was run against the current GitHub checkout during this task. The new comprehensive test module, noise benchmark and full-exam command are **AUTHORED, NOT RUN**. No full-suite PASS, noisy-task metric, resource result or runtime correctness claim is made.

**Not run:** complete repository unit tests through the comprehensive runner; noise benchmark smoke/pilot/full; V5.1 resource measurement through the exam; Android/Termux measurements; BTCC task-level learning comparison; float32 runtime equivalence; builds, APK/AAB, CI and GitHub Actions.

**Limitations:** the current reference code does not yet implement predictive/surprise/familiarity learning, fatigue/recovery, long-term associative memory and forgetting, structural synapse creation/deletion, a dedicated homeostatic controller, JNVM/JPVR persistence/recovery, a production neuron runtime, or network-level behavior. The exam reports these as NOT_IMPLEMENTED. The noisy task uses weak excitatory distractors only and has no preselected promotion threshold; its results must be interpreted as exploratory evidence, not proof that a learning rule is superior.

**Next step:** run the default pilot command from Termux, preserve the complete JSON report, fix any failures, then review per-seed held-out results before designing a larger pre-registered acceptance experiment. Keep STARTER and production learning rules unchanged until evidence justifies a change.


### 2026-11-08 — Noisy temporal exam: stronger held-out interval separation

A post-review strengthened the noisy task's generalization test. Previously, train and held-out examples had disjoint signal-strength/noise signatures but drew timing gaps from the same small set. The generator now uses separate, non-overlapping timing-gap pairs for the two splits: training FAST 2–3 ms and SLOW 18–19 ms; held-out FAST 4–5 ms and SLOW 15–16 ms. Both labels retain three task pulses and the same 40 ms decision deadline. Signal-strength material uses three 32-bit digest components; temporal labels now use separate digest bytes so strength values do not directly choose the timing category. Tests now assert gap-signature disjointness both with and without distractors.

Files changed: tools/neuron_validation/noise_benchmark.py, tools/neuron_validation/test_neuron_full_exam.py, docs/JARVIS_NEURON_FULL_EXAM.md, and README.md.

Verification: GitHub fetch/static inspection only. New and modified executable tests remain AUTHORED, NOT RUN. No benchmark metrics or full-suite status are claimed.

Interpretation: this creates a stricter synthetic temporal distribution-shift test, but does not establish real-world generalization. The held-out timing bands still preserve the intended FAST/SLOW ordering and must be reviewed after the first run; no accuracy threshold is imposed before observing the results.


### 2026-11-08 — Comprehensive exam reporting gates tightened

A final static review tightened the exam's evidence reporting. `tools/neuron_validation/full_exam.py` now resolves capability statuses from the actual outcomes of the unit suite, noisy benchmark and resource stage; mechanisms are no longer pre-labelled as tested before execution. The unit-suite PASS gate also requires that every discovered case ran, with no loader errors, skips or unexpected successes.

`tools/neuron_validation/test_neuron_full_exam.py` gained two tests for the coverage-report rules: a NOT RUN stage must never be labelled PASS, and completed stages must retain their scope qualifiers (for example BTCC unit-only and Python-reference resource-only).

Verification: latest files were fetched from GitHub and reviewed statically. These two new coverage tests and the updated runner remain AUTHORED, NOT RUN. No Python tests, noise benchmark, resource benchmark, Android measurement, build or CI job was executed in this task.


### 2026-11-08 — First-neuron Python reference coordinator V1

**Goal:** start converting the documented first-neuron model into an executable, bounded orchestration layer without duplicating or changing the Numerical Oracle equations or canonical STARTER V1 profile.

**Added:**
- tools/neuron_runtime/__init__.py — package exports for the reference coordinator API.
- tools/neuron_runtime/runtime.py — in-memory coordinator for one Adaptive LIF-DT output neuron and multiple incoming excitatory synapses. It supports none, STDP and R-STDP modes; groups all same-timestamp events into one Oracle call; routes each synapse's pre-count and the neuron's post-spike count to plasticity; and calculates candidate immutable states before committing an in-memory batch.
- tools/neuron_runtime/test_runtime.py — 15 deterministic contract tests covering configuration bounds, simultaneous ordering, Oracle delegation, spike/sequence reporting, multiple pre-events on one synapse, post-event routing to all synapses, STDP, delayed R-STDP reward, external baseline ownership, context/time validation and no partial in-memory commit on a failed batch.
- docs/JARVIS_RUNTIME_V1.md — scope, API, verification status and implementation limits.

**Updated:** docs/JARVIS_ARCHITECTURE.md adds section 3.30 and updates current project/JPVR-001 status; README.md documents the reference coordinator and focused test command.

**Implementation boundaries:** this is a Python reference orchestration layer around the existing Numerical Oracle, not an independent numerical engine or production NCC/runtime. It supports only one output neuron and excitatory weights in [0, 1]. baseline_before is provided by the external Reward Baseline Manager; the runtime returns baseline_after and does not own task baseline. There is no CQE routing, durable state commit, multi-synapse feedback transaction, idempotent feedback sequence, serialization, JPVR/JNVM, crash recovery, inhibitory plasticity, Android integration or device resource evidence. Canonical STARTER V1 and Oracle equations were not changed.

**Verification actually executed in the authoring environment (Python 3.13.5):**
- python -m py_compile /mnt/data/jarvis-runtime-v1/tools/neuron_runtime/*.py — **PASS**, syntax compilation only.
- PYTHONPATH=/mnt/data/jarvis-runtime-harness python -m unittest tools.neuron_runtime.test_runtime -v — **PASS, 15/15** in an isolated harness. Important limitation: this harness injected an API-compatible test stand-in for Numerical Oracle. It validates the coordinator orchestration shape against that stand-in; it is not a test against the repository real tools.numerical_oracle.jarvis_oracle.

**Repository verification:** the 15 focused tests are **AUTHORED, NOT RUN** against the real Numerical Oracle. The full repository unit suite is **NOT RUN** in this task. No Android/Termux test, JPVR serialization/recovery test, device RAM/CPU/latency/energy/I/O measurement, build, APK/AAB, CI or GitHub Actions workflow was run.

**Next step:** run python3 -m unittest tools.neuron_runtime.test_runtime -v against the real Oracle, then python3 -m unittest discover -s tools -t . -v. Fix any real-Oracle integration failures before starting JPVR-001 serialization/recovery work.


### 2026-11-08 — Bounded multi-neuron event loop V1

**Цель:** следующий исполняемый слой после первого NeuronRuntime — маршрутизация output spikes между несколькими нейронами с ограниченной очередью и детерминированной обработкой времени.

**Добавлено:**
- `tools/neuron_runtime/network.py` — NetworkConnection, NetworkRuntime, bounded pending queue/run budget, положительные задержки, группировка одного timestamp в batch, резервирование fan-out и timestamp-level in-memory candidate commit.
- `tools/neuron_runtime/test_network.py` — 14 детерминированных contract tests.
- `docs/JARVIS_NETWORK_RUNTIME_V1.md` — API, владение синапсом, семантика времени, ограничения и команды проверки.

**Обновлено:**
- `tools/neuron_runtime/__init__.py` — публичные network exports.
- `README.md` — статус, карта файлов и focused test command.
- `docs/JARVIS_ARCHITECTURE.md` — раздел 3.31, результаты предыдущего NeuronRuntime test run и текущий следующий gate.

**Архитектурные решения:** вес связи остаётся авторитетным в target synapse; NetworkConnection содержит только routing metadata/delay/strength/attention. Zero-delay links запрещены. Все события одного timestamp к одному нейрону обрабатываются единым batch. Весь затронутый timestamp рассчитывается на копиях Runtime и фиксируется в памяти только после успешных вычислений и проверки очереди. Рекуррентная топология допускается только с положительной задержкой и ограничена queue/run budgets. Reward baseline по-прежнему внешний. Oracle equations и canonical STARTER не менялись.

**Тесты:** добавлены 14 tests в `tools/neuron_runtime/test_network.py`, включая propagation через три нейрона, same-time batching, topology validation, queue/run limits, fan-out backpressure, no-partial-commit, timestamp overflow, recurrent budget и deterministic order. Тесты нового модуля **AUTHORED, NOT RUN** против настоящего Numerical Oracle.

**Фактические проверки:**
- `python3 -m py_compile tools/neuron_runtime/network.py tools/neuron_runtime/test_network.py` — **PASS**, syntax only.
- Isolated authoring harness with API-compatible Runtime/Oracle stand-in — **PASS, 14/14, 0.003 s**. Это проверка сетевой логики на stand-in, не интеграционная проверка с репозиторным Oracle.
- Пользовательский `python3 -m unittest discover -s tools -t . -v` на Android Termux до добавления NetworkRuntime — **PASS, 216/216, 0 failures, 0 errors, 19.280 s**. Включает предыдущий NeuronRuntime и Oracle; не включает новый test_network.py. Python version не указана в переданном выводе.

**Не запускалось:** focused network tests against real Oracle, post-change full repository suite, benchmark полезности/обучения сети, Android RAM/CPU/latency/energy/I/O, builds, APK/AAB, CI, GitHub Actions.

**Ограничения:** одно-поточная in-memory reference модель; нет CQE authorization/idempotency, durable queue, production NCC, concurrency, JPVR/JNVM persistence, crash recovery, inhibitory connections/iSTDP, topology learning или device performance evidence. Backpressure использует консервативное резервирование worst-case fan-out.

**Следующий шаг:** пользователь запускает `python3 -m unittest tools.neuron_runtime.test_network -v`, затем `python3 -m unittest discover -s tools -t . -v`. После проверки исправить реальные интеграционные ошибки; только затем решать следующий gate по persistent state/JPVR-001.

### 2026-11-08 — CQE reference lifecycle and event-boundary hardening

**Цель:** устранить постоянное накопление subscriber dedup keys, формализовать безопасное поведение bounded idempotency/result ledger и закрыть некорректные/чрезмерные committed-event descriptors до commit.

**Изменено:**
- `tools/cqe_reference/cqe.py`:
  - subscriber inbox теперь хранит только последние 4 096 `(module_id, event_id)` ключей через set + FIFO history; при вытеснении старый event может быть обработан повторно. Это явно bounded dedup window, а не durable exactly-once;
  - idempotency/result ledger остаётся ограниченным 4 096 принятыми командами; записи намеренно не вытесняются без JPVR, потому что забытый ключ может разрешить повторное выполнение уже применённой команды. После заполнения возвращается `DEFERRED/RESOURCE_EXHAUSTED`;
  - committed events проверяются до commit: tuple и максимум 256 событий, EventSpec, UUID, уникальность event_id внутри proposal, непустой schema_id, положительная int schema_version (bool запрещён), Mapping payload, JSON/inline-size/descriptor limits и совместимость схем всех подписчиков;
  - outbox count/byte preflight теперь считает объём инкрементально и прекращает проверку при превышении лимита, не создавая промежуточный список delivery-size на каждую доставку.
- `tools/cqe_reference/test_cqe.py`:
  - добавлены четыре теста: bounded inbox history, bounded idempotency/result backpressure, duplicate IDs/boolean schema versions и максимальное число committed events.
- `docs/JARVIS_ARCHITECTURE.md`:
  - уточнены bounded inbox semantics, 4 096-key retention window, fail-closed idempotency behavior, возрастное повышение очереди и текущий статус тестов;
  - исторические PASS-результаты не переписаны как новые.
- `README.md`:
  - исправлен устаревший статус scheduler Aging и уточнено разделение reference/production.

**Проверки и доказательства:**
- GitHub source/test/diff fetch и статическая проверка изменённых фрагментов — выполнены; это не syntax check и не runtime verification.
- В текущем `tools/cqe_reference/test_cqe.py` обнаружен 31 test method. Историческая запись о 26 PASS относится к более раннему запуску; пять проверок появились после него (age-based priority test и четыре новых теста этого изменения).
- Четыре новых теста — **AUTHORED, NOT RUN**. Тест age-based priority — присутствует в текущем файле, но после его добавления в исторический набор не подтверждён новым запуском.
- Попытка получить checkout через `git clone --depth 1 https://github.com/maksrugu123-sys/jarvis-ans100-alpha.git` в контейнере — **BLOCKED**: DNS/network environment returned `Could not resolve host: github.com`; поэтому `python3 -m unittest tools.cqe_reference.test_cqe -v` не запускалась.
- GitHub combined status для последнего CQE test commit не вернул status checks. CI/GitHub Actions не запускались.
- Сборки, APK/AAB, Gradle и device measurements — **NOT RUN** по ограничению пользователя.

**Ограничения:** inbox/idempotency/outbox всё ещё volatile и теряются при restart; bounded inbox допускает повторную обработку события после вытеснения; idempotency ledger после 4 096 команд требует backpressure до durable JPVR ledger. Это reference implementation, не production NCC. Синтаксис и поведение новых тестов пока не подтверждены интерпретатором.

**GitHub:** все изменения опубликованы в `main`. Ключевые commits: `f7dc307`, `feddcdf`, `f709828`, `46e343a`, `019a16c`, `7915fbd`, `f1ecfae`, `241cb50`, `bd0c90a`, `cfa79ed`.

**Следующий шаг:** начать JPVR_FORMAT_V1 с независимого, тестируемого binary codec для CRC32C, superblock и 4-KiB page header; затем добавить строгие negative/corruption tests. Не подключать durable commit к CQE до появления и проверки этих нижних уровней.

### 2026-11-08 — JPVR_FORMAT_V1 CRC32C, Superblock and page codec slice

**Цель:** начать реализацию JPVR снизу вверх: создать независимый бинарный codec для двух 4-KiB Superblock и обычных 4-KiB страниц, строго соответствующий уже зафиксированным offset/size/CRC правилам раздела 3.21.

**Добавлено:**
- `tools/jpvr_reference/__init__.py` — публичные exports reference codec.
- `tools/jpvr_reference/codec.py`:
  - CRC32C Castagnoli с reflected polynomial `0x82F63B78`;
  - `Superblock` и encode/decode для 4 096 байт: magic/version/header size/flags/endian marker/page size, container UUID, generation, active commit/checkpoint/durable tail, commit ID, slot ID, CRC32C и zero-reserved region;
  - `PageType`, `DecodedPage` и encode/decode обычной страницы: 64-byte header, 4 032-byte payload area, page/generation/transaction identifiers, payload/header CRC32C, strict length, zero padding and expected physical page number;
  - fail-closed validation для неизвестных flags/types/versions, invalid bounds, inconsistent genesis/commit pointers, wrong slot, bad CRC и nonzero reserved/padding.
- `tools/jpvr_reference/test_codec.py` — 23 детерминированных тестовых метода: CRC check vector, bytes-like inputs, genesis/committed superblock round-trip, A/B slots, corruption/slot/reserved/flag checks, page round-trip with empty/small/full payload, CRC corruption, padding, physical page number, type/bounds and boolean integer rejection.
- `docs/JARVIS_ARCHITECTURE.md` — раздел 3.21.15 фиксирует scope и статус codec.
- `README.md` — отражён начальный codec slice и добавлен путь пакета в repository map.

**Статус реализации:** codec source **IMPLEMENTED**; тесты **AUTHORED, NOT RUN**. Полный JPVR Storage Engine не реализован: отсутствуют file/container I/O, lock, JREC/TLV, BLOB fragmentation, indexes, manifest, journal, COMMIT, OUTBOX, CHECKPOINT, flush/sync, reopen/recovery, compaction и fault injection.

**Проверки и доказательства:**
- GitHub fetch/readback новых файлов, подсчёт 23 тестовых методов и статическая сверка форматов struct/offset с разделом 3.21 — выполнены. Это source review, не Python syntax/runtime test.
- `python3 -m unittest tools.jpvr_reference.test_codec -v` — **NOT RUN**: в текущем контейнере нет checkout; предыдущая попытка git clone была **BLOCKED** из-за DNS `Could not resolve host: github.com`. Никакой PASS для 23 тестов не заявляется.
- Полная Python suite, JPVR file I/O, fault injection, Android/Termux/device measurements — **NOT RUN**.
- Сборки, APK/AAB, Gradle, CI и GitHub Actions — **NOT RUN** согласно ограничению пользователя.

**Ограничения:** код проверяет только отдельные бинарные блоки в памяти. Он не открывает файл-контейнер, не подтверждает durability и не обеспечивает transaction/recovery semantics. CRC32C обнаруживает случайную порчу, но не является криптографической аутентификацией.

**GitHub:** опубликовано в `main`. Commits: `1d4d4e1`, `69ccfcb`, `807edd9`, `a5ea9f5`, `ddd1ecb`, `cc6c695`.

**Следующий шаг:** запустить 23 focused codec tests в Termux, исправить реальные ошибки, затем реализовать JREC 56-byte header + deterministic TLV codec с отдельными bounds/CRC tests. После этого перейти к BLOB fragments. Не подключать codec к CQE commit path до появления transaction journal, durable commit и recovery tests.

### 2026-11-08 — JPVR JREC and deterministic TLV reference codecs

**Цель:** продолжить JPVR_FORMAT_V1 после Superblock/page envelope и реализовать ограниченное framing небольших DATA-записей и каноническое кодирование их TLV payload.

**Добавлено:**
- `tools/jpvr_reference/records.py`:
  - JREC 56-byte header encode/decode по зафиксированным offset: record_type, flags, schema_id/version, object_id, record_version, payload length, payload/header CRC32C и reserved;
  - small record payload ограничен 3 976 bytes, запись выравнивается до 8 bytes и не пересекает DATA page;
  - TLV encode/decode с сортировкой field_id, запретом дубликатов/нулевого ID, строгими wire-type lengths, optional bit, проверкой schema map, zero padding и пределом логического payload 16 MiB;
  - wire_type codes выбраны как U8=1, U16=2, U32=3, U64=4, I64=5, BOOL=6, F32=7, F64=8, ID128=9, BYTES=10, UTF8=11; OPTIONAL flag = 0x01, остальные bits запрещены;
  - конечность float, канонизация −0.0 при encode и отказ при чтении ненормализованного −0.0.
- `tools/jpvr_reference/test_records.py` — 23 тестовых метода для всех wire types, optional/mandatory fields, ordering, duplicates, type mismatch, invalid values, UTF-8, alignment, record round-trip, multi-record packing, CRC, reserved/flags и bounds.
- `tools/jpvr_reference/__init__.py` — exports JREC/TLV codec API.
- `docs/JARVIS_ARCHITECTURE.md` — раздел 3.21.16 фиксирует заголовок, wire type registry, OPTIONAL flag и границы reference slice.
- `README.md` — обновлён статус JPVR codec.

**Проверки и доказательства:**
- GitHub fetch/readback и статическая проверка форматов, offset, размеров struct и test assertions — выполнены; это source review, не Python runtime verification.
- `tools/jpvr_reference/test_records.py`: 23 test methods **AUTHORED, NOT RUN**.
- В паре с `test_codec.py` пакет содержит 46 test methods (23 page/superblock + 23 JREC/TLV), все **AUTHORED, NOT RUN**.
- Python syntax checks и focused unit tests — **NOT RUN**. Текущий контейнер не имеет checkout; git clone ранее блокировался DNS/network failure. Runtime PASS не заявляется.
- BLOB, container I/O, full unit suite, fault injection, Android/Termux/device measurements — **NOT RUN**.
- Builds, Gradle, APK/AAB, CI и GitHub Actions — **NOT RUN** по ограничению пользователя.

**Ограничения и решения:** числовые коды wire_type и OPTIONAL=0x01 были не заданы численно в общей схеме; этот reference slice фиксирует их как начальный выбор. Если до появления persisted files потребуется изменение, оно должно пройти явную compatibility/version policy. Codec не реализует schema registry/migrations, BLOB, page packing/indexing, journal, commit, outbox, durability или recovery.

**GitHub:** опубликовано в `main`. Commits: `2ae7021`, `4dd93b5`, `437c371`, `e07fe5c`, `4d8f2ad`, `48441f2`, `d602c14`, `68fdbc6`.

**Следующий шаг:** реализовать BLOB fragment codec по разделу 3.21.5 с проверкой fragment order/count, total length, page CRC и full-record CRC; затем объединить DATA/BLOB records с page envelope только после прохождения всех focused tests.

### 2026-11-08 — JPVR BLOB fragment codec reference slice

**Цель:** реализовать и проверить на уровне исходного кода фрагментацию большой логической записи в BLOB-страницы JPVR_FORMAT_V1, не создавая преждевременно файловый Storage Engine или ложную durability-гарантию.

**Добавлено:**
- `tools/jpvr_reference/blob.py`:
  - encode/decode JBLB 64-byte fragment header внутри 4 032-byte page payload;
  - до 3 968 bytes данных на фрагмент, последовательные PageType.BLOB page_no;
  - проверка общего record identity/schema/version, fragment_index/count, total length, transaction_id, commit_generation и zero padding;
  - внешняя page CRC32C и итоговая full_payload_crc32c после сборки;
  - ограничения 16 MiB на logical record и 8 192 pages на BLOB.
- `tools/jpvr_reference/test_blob.py` — 15 тестов: multi-fragment round-trip, boundary lengths, small/oversized payload, page order/index, expected page number, page type, outer CRC, full-record CRC after outer checksum recomputation, padding, transaction/generation/metadata consistency.
- `tools/jpvr_reference/__init__.py` — публичные exports для BLOB API, imports упорядочены по зависимостям.
- `docs/JARVIS_ARCHITECTURE.md` — раздел 3.21.17 описывает JBLB header и validation; project status и test count согласованы.
- `README.md` — JPVR codec status/map обновлены.

**Проверки и доказательства:**
- GitHub readback/static inspection BLOB offsets, struct layout, fragment lengths, page envelope coupling and tests — выполнены; это не runtime test.
- Один test assertion для reverse-order scenario скорректирован по фактическому порядку validation: decoder сначала обнаруживает mismatch fragment_index.
- Текущие тесты JPVR: 23 `test_codec.py` + 23 `test_records.py` + 15 `test_blob.py` = **61 test method, all AUTHORED, NOT RUN**.
- Python syntax checks, focused tests и full suite — **NOT RUN** из-за отсутствия checkout и DNS/network blocker для git clone. Ни один runtime PASS не заявляется.
- File I/O, container open/close, fault injection, durability, recovery и Android/Termux/device measurements — **NOT RUN**.
- Builds, APK/AAB, Gradle, CI и GitHub Actions — **NOT RUN** по ограничению пользователя.

**Ограничения:** BLOB codec работает только с in-memory полными страницами; он не резервирует page numbers/offsets в файле, не реализует DATA page packing, indexes, manifest, journal, commit/outbox, flush/sync, reopen/recovery или compaction. 16 MiB/8 192 являются форматными limits, а не измеренными device limits.

**GitHub:** опубликовано в `main`. Commits: `cd1b00b`, `94e4736`, `d297bde`, `df5616a`, `a126912`, `6df352e`, `851faf4`, `14f05ac`.

**Следующий шаг:** собрать JREC и BLOB записи в DATA/BLOB page packing layer, сохраняя строгие границы страницы и проверку всех CRC. Перед дальнейшим расширением выполнить 61 focused JPVR tests в Termux, если доступ к локальному checkout восстановлен.

### 2026-11-08 — JPVR DATA page packing reference slice

**Цель:** добавить безопасную упаковку нескольких небольших JREC записей в одну 4-KiB DATA page и обратное декодирование без выхода за границы страницы.

**Добавлено:**
- `tools/jpvr_reference/data.py`:
  - `encode_data_page` принимает canonical padded JREC records, проверяет каждую запись через существующий decoder, размещает записи последовательно в 4 032-byte DATA payload и отказывает до page encoding при переполнении;
  - `decode_data_page` проверяет outer PageType.DATA/page envelope и читает JREC последовательно до полностью нулевого остатка; ненулевой мусор не игнорируется;
  - пустая DATA page поддерживается; порядок записей сохраняется.
- `tools/jpvr_reference/test_data.py` — 14 тестов для empty/single/multiple records, ordering, capacity, malformed/noncanonical records, wrong page type/size/physical position, outer page CRC, inner JREC CRC и nonzero trailing bytes.
- `tools/jpvr_reference/__init__.py` — экспорт DATA page API.
- `docs/JARVIS_ARCHITECTURE.md` — раздел 3.21.18 описывает packer/parser и ограничения.
- `README.md` — обновлены карта и статус JPVR.

**Проверки и доказательства:**
- GitHub readback/static inspection source and test assertions — выполнены; это не Python runtime test.
- Новые DATA tests: 14 **AUTHORED, NOT RUN**.
- Текущий JPVR набор: 23 page/superblock + 23 JREC/TLV + 15 BLOB + 14 DATA = **75 test methods, all AUTHORED, NOT RUN**.
- Python syntax checks, focused tests и full suite — **NOT RUN**: локальный checkout отсутствует, clone blocked DNS/network.
- Container file I/O, append allocator, indexing, transaction/recovery, fault injection, Android/Termux/device measurements — **NOT RUN**.
- Builds, APK/AAB, Gradle, CI и GitHub Actions — **NOT RUN** по ограничению пользователя.

**Ограничения:** это in-memory one-page packing/parser, не container writer. Нет file offsets/page allocator, multi-page DATA allocation, object indexes, manifest, JOURNAL, COMMIT, OUTBOX, flush/sync, durable reopen/recovery или compaction.

**GitHub:** опубликовано в `main`. Commits: `0d3a985`, `53d0dda`, `a98ceb2`, `cbb3b03`, `6210c2a`.

**Следующий шаг:** реализовать ограниченный in-memory container layout/append planner с выделением последовательных page_no и проверкой durable_tail boundaries; не выполнять реальный file I/O или commit, пока allocator и layout invariants не пройдут focused tests.

### 2026-11-08 — JPVR in-memory append planner reference slice

**Цель:** описать безопасное выделение последовательного диапазона page_no и подготовку следующей inactive Superblock copy без реальной записи на диск.

**Добавлено:**
- `tools/jpvr_reference/layout.py`:
  - immutable `AppendPlan` с ожидаемым base generation/tail/slot, first/last page, commit page, target slot и новой Superblock;
  - `plan_append` начинает диапазон с `durable_tail_page_no + 1`, требует положительный page_count, проверяет u64 overflow, делает последнюю страницу COMMIT page, увеличивает generation и чередует Superblock A/B slot;
  - checkpoint сохраняется по умолчанию или проверяется в пределах нового durable prefix;
  - `validate_append_plan` отклоняет stale base и tampered plan, отдельно проверяя bool/int distinction (в Python bool является подклассом int).
- `tools/jpvr_reference/test_layout.py` — 14 тестов для genesis/next generation, alternating slots, checkpoint, overflow, invalid IDs, stale/tampered plans и строгих numeric types.
- `tools/jpvr_reference/__init__.py` — exports append planner.
- `docs/JARVIS_ARCHITECTURE.md` — раздел 3.21.19 и границы pure planning.
- `README.md` — обновлён текущий JPVR scope и число тестов.

**Проверки и доказательства:**
- GitHub readback/static source review — выполнены; это не runtime verification.
- Один важный defensive check добавлен после review: плановые поля валидируются до equality comparison, чтобы bool не проходил как int.
- Новые tests: 14 **AUTHORED, NOT RUN**.
- Общий JPVR набор: 23 page/superblock + 23 JREC/TLV + 15 BLOB + 14 DATA + 14 append planner = **89 test methods, all AUTHORED, NOT RUN**.
- Python syntax/focused tests/full suite — **NOT RUN**: assistant container has no checkout; git clone blocked by DNS/network.
- Реальный file writer, process lock, append offsets, free-page allocator, transaction journal, commit, superblock disk update, flush/sync, recovery, crash tests — **NOT RUN / NOT IMPLEMENTED**.
- Builds, Gradle, APK/AAB, CI и GitHub Actions — **NOT RUN** по ограничению пользователя.

**Ограничения:** AppendPlan — вычисленный in-memory план, а не reservation. Без file/process lock несколько writers могут получить одинаковый диапазон; он не гарантирует write ordering, durability или recovery. Последняя страница считается COMMIT page по принятому layout contract, но её содержимое этим модулем не создаётся и не проверяется.

**GitHub:** опубликовано в `main`. Commits: `5113c03`, `b6bbad5`, `3a62504`, `8618ccc`, `9ed8990`, `b50ffad`, `03a7653`.

**Следующий шаг:** перейти к container-level in-memory manifest/index planning, а затем к journal/commit model; реальный file writer не добавлять до полного тестирования всех 89 текущих focused checks.

### 2026-11-08 — JPVR test-count correction

Correction to the immediately preceding append-planner entry: a fresh count of `^s+def test_` in `tools/jpvr_reference/test_layout.py` returns **15**, not 14. The five module counts are 23 (`test_codec.py`) + 23 (`test_records.py`) + 15 (`test_blob.py`) + 14 (`test_data.py`) + 15 (`test_layout.py`) = **90 test methods**. README and architecture status were corrected to 90. All 90 remain **AUTHORED, NOT RUN**; this is a source-count correction, not a test result.

### 2026-11-08 — JPVR MANIFEST payload/page codec and root validation

**Цель:** реализовать минимальную структуру согласованного snapshot manifest и проверить его обязательные корни по физическим page headers/durable prefix в памяти.

**Добавлено:**
- `tools/jpvr_reference/manifest.py`:
  - Manifest TLV schema: field 1 container_uuid, 2 generation, 3–9 seven required index roots, 10 checkpoint_page_no, 11 journal_high_water_page_no, 12 outbox_high_water_page_no;
  - PageRef serialized as 16 bytes: little-endian page_no u64 + expected_commit_generation u64;
  - обязательные поля не могут быть OPTIONAL; неизвестные optional пропускаются, неизвестные mandatory/wire_type mismatch отклоняются;
  - encode/decode PageType.MANIFEST page envelope с совпадением payload generation и outer commit_generation;
  - validate_manifest сверяет container UUID/generation с Superblock, root PageRefs с INDEX page headers, checkpoint/JOURNAL/OUTBOX page types, generation bounds и durable_tail.
  - разрешены неизменяемые root pages предыдущих поколений при точном совпадении PageRef и generation не новее manifest.
- `tools/jpvr_reference/test_manifest.py` — 19 тестов для TLV round-trip, optional/mandatory, required fields, manifest page envelope, root types/generation/bounds, container identity и missing references.
- `tools/jpvr_reference/__init__.py` — exports manifest API.
- `docs/JARVIS_ARCHITECTURE.md` — раздел 3.21.20 фиксирует field IDs, PageRef layout и validation semantics.
- `README.md` — обновлён JPVR scope и test count.

**Проверки и доказательства:**
- GitHub readback/static inspection — выполнены; это не Python runtime verification.
- Новые manifest tests: 19 **AUTHORED, NOT RUN**.
- Текущий JPVR пакет содержит 6 test modules: 23 page/superblock + 23 JREC/TLV + 15 BLOB + 14 DATA + 15 append planner + 19 manifest = **109 test methods, all AUTHORED, NOT RUN**.
- Python syntax/focused tests/full suite — **NOT RUN**: assistant container has no checkout; git clone blocked by DNS/network.
- B+ tree operations, index rebuild/fullness verification, file/container I/O, journal/commit, durability/recovery, fault injection, Android/Termux/device measurements — **NOT RUN / NOT IMPLEMENTED**.
- Builds, Gradle, APK/AAB, CI и GitHub Actions — **NOT RUN** по ограничению пользователя.

**Ограничения и решения:** journal/outbox high-water marks в этом initial reference profile трактуются как page numbers. Manifest validator проверяет только перечисленные корни/служебные страницы; он не разбирает B+ tree nodes и не доказывает полноту индекса. Это не production manifest, не transaction commit и не recovery engine.

**GitHub:** опубликовано в `main`. Commits: `9faa0e3`, `9f6e9c2`, `b742ba7`, `af6f497`, `1ae8db3`, `46fb891`, `76d5ccb`, `60e3071`.

**Следующий шаг:** реализовать минимальный immutable COW B+ tree node codec/validator и поиск по RecordLocation; не добавлять mutating tree writer до прохождения 109 focused tests.

### 2026-11-08 — JPVR binary-layout sanity check (not a module test run)

A local isolated Python sanity snippet checked `struct.calcsize` for the intended Superblock prefix (24 bytes), page header (64), JREC header (56), JBLB header (64) and PageRef (16); all matched the documented sizes. The same snippet checked the CRC32C Castagnoli vector `123456789 → E3069283`. This did **not** import or execute repository modules and does not change the status of the 109 JPVR unit tests: all remain **AUTHORED, NOT RUN**.


### 2026-11-09 — JPVR TLV ordering regression-test correction

**Наблюдение из запуска в Termux:** пользователь запустил шесть JPVR test modules: 109 тестов, 108 прошли, один завершился FAIL — `test_decoder_rejects_non_increasing_field_ids`.

**Причина:** тест изменял 16-битное значение по смещению 8. Для однобайтного TLV это байты значения первого поля, а не заголовок второго поля. Поэтому нарушенный порядок field IDs фактически не создавался. Реализация `decode_tlv` уже сравнивает каждый ID с предыдущим и отклоняет `field_id == 0` или `field_id <= previous_field_id`; исправление production decoder не требовалось.

**Изменение:** тест теперь вычисляет смещение заголовка второго поля по размеру первого закодированного TLV и проверяет оба случая: дублирующийся ID и убывающий ID.

**Проверки и ограничения:**
- Исходный пользовательский запуск: 109 tests, 108 PASS, 1 FAIL из-за ошибки тестового смещения.
- После исправления: новый тест опубликован, но повторный запуск пока **NOT RUN**; нельзя считать все 109 тестов прошедшими.
- Сборки, CI и GitHub Actions — **NOT RUN** по ограничению пользователя.

**GitHub:** изменение теста опубликовано в `main`, commit `22123075b81343d4d1e6c1777bd73c00bde05a61`.

**Следующий шаг:** продолжить read-only аудит COW B+ Tree codec/validator и индексации RecordLocation, не начиная mutating tree writer до повторного прохождения focused JPVR suite.


### 2026-11-09 — JPVR immutable INDEX node codec and snapshot lookup

**Изменения:** добавлен `tools/jpvr_reference/index.py` с бинарным payload формата JIDX v1: 44-byte header, LEAF/INTERNAL entries, 8-byte entry alignment, tree_id, PageRef и strict validation. Добавлены INDEX page encode/decode и read-only B+ tree lookup с проверкой durable prefix, page generation, tree identity, циклов и child-level relation. Добавлен `test_index.py` с round-trip, malformed payload, ordering, page envelope, leaf lookup, internal descent и stale/wrong refs cases.

**Граница реализации:** это только immutable node codec и read-only lookup по in-memory page catalog. COW writer, split/merge, page allocator, file I/O, transaction journal, commit, recovery и authoritative DATA/BLOB consistency scan отсутствуют.

**Проверки:** тесты нового модуля написаны, но **NOT RUN**. Полный JPVR набор 109 тестов пользователь ранее сообщил как PASS после исправления TLV test offset; это пользовательский результат, не повторённый мной. Сборки, CI и GitHub Actions **NOT RUN**.

**Коммиты:** `b97e4060` (index codec), `41688d84` (initial tests), `e8f9763e` (child-level validation), `382eeeaa` (child-level regression test).


### 2026-11-09 — JPVR INDEX decoder hardening

После первичной реализации добавлены дополнительные строгие проверки: уровень узла ограничен 0–63, INTERNAL node не может иметь ноль separator entries, шесть reserved bytes каждого internal entry обязаны быть нулевыми. Добавлены два регрессионных теста. Итоговый `test_index.py` содержит 16 тестовых методов.

**Текущая проверка:** все 16 новых тестов **NOT RUN**. Предыдущие 109 JPVR тестов пользователь сообщил как PASS; новый INDEX модуль в тот запуск не входил. Следующий обязательный шаг — запустить новый модуль и весь набор на Termux до разработки writer.

**Дополнительные коммиты:** `45e7be9e` (decoder hardening), `4633ec1b` (regression tests).


### 2026-11-09 — JPVR full-tree validation and copy-on-write upsert planner

**Цель:** перейти от immutable JIDX node codec/read-only lookup к первой проверяемой операции записи нового snapshot без создания ложной гарантии физической durability.

**Добавлено:**
- `tools/jpvr_reference/cow.py`:
  - `validate_index_tree` обходит все reachable INDEX nodes и проверяет PageRef/page generation, durable prefix, tree_id, child level, child generation, циклы/повторное использование страниц, точное соответствие separator минимальному ключу правого subtree и непересечение key ranges;
  - `cow_upsert_index` выполняет одну insert/replace операцию через immutable path-copy;
  - переполняющиеся LEAF nodes делятся на два непустых узла; переполняющиеся INTERNAL nodes делятся с минимум двумя child pointers в каждой половине; при split root создаётся новый уровень;
  - новые page numbers выдаются последовательно после переданного durable_tail, все новые страницы кодируются с заданными commit_generation и transaction_id;
  - результат повторно валидируется, а все созданные страницы обязаны быть reachable из нового root;
  - `CowIndexPlan` возвращает новый root, новые encoded pages, признаки replace и статистику старого/нового дерева.
- `tools/jpvr_reference/test_cow.py` — 16 тестов на full-tree invariants, пустой root, insert/replace, сохранение старого snapshot, leaf split, path-copy с повторным использованием неизменённой ветки, internal split/root growth, поколения и последовательность allocation.
- `tools/jpvr_reference/__init__.py` — exports COW API.
- `docs/JPVR_COW_WRITER_V1.md`, README и этот log — обновлены.

**Проверки и доказательства:**
- Предыдущие 125 тестов JPVR (109 ранних модулей + 16 INDEX tests) пользователь сообщил как PASS в Termux.
- Новые COW tests: 16 **AUTHORED, NOT RUN**. Runtime PASS не заявляется.
- GitHub source readback/static review выполнены; это не замена Python unit-test execution.
- Builds, Gradle, APK/AAB, CI и GitHub Actions **NOT RUN** по ограничению пользователя.

**Границы:** это in-memory upsert planner, а не file writer. Нет delete/merge, bulk update, physical page reservation/lock, file I/O, flush/sync, JOURNAL/COMMIT/MANIFEST integration, superblock update, crash recovery, rollback или compaction. Полный validator обходит весь reachable tree и имеет O(number of INDEX pages) стоимость. `CowIndexPlan` сам по себе не является commit или подтверждением durability.

**GitHub:** изменения опубликованы в `main`; см. commits этого этапа в истории репозитория.

**Следующий шаг:** выполнить 16 focused COW tests и весь 141-test JPVR set в Termux. Только после подтверждения перейти к формированию transaction page sequence (DATA/BLOB/INDEX/MANIFEST/JOURNAL/COMMIT), по-прежнему без file I/O и superblock mutation до отдельной проверки ordering/recovery invariants.


### 2026-11-09 — JPVR user verification update and JCOM v1 descriptor codec

**Verification correction:** пользователь сообщил, что весь предыдущий JPVR набор прошёл. Для данного этапа это **141 тест**: 109 codec/record/blob/data/layout/manifest + 16 immutable INDEX + 16 COW. Это пользовательский запуск на Termux; я не повторял его в этой среде.

**Добавлено:**
- `tools/jpvr_reference/commit.py` — фиксированный JCOM v1 COMMIT payload (112 bytes), page envelope, строгие generation/ID/PageRef rules и проверка, что COMMIT является последней страницей durable prefix.
- `validate_commit_snapshot` сверяет payload с outer page header, MANIFEST identity/generation/transaction_id, предыдущим COMMIT (generation chain) и, при наличии page catalog + Superblock, проверяет семь manifest roots и high-water references.
- `tools/jpvr_reference/test_commit.py` — 20 тестов на layout, reserved bytes, идентификаторы, поколения, порядок ссылок, outer envelope, предыдущий commit и superblock cross-check.
- `docs/JPVR_COMMIT_V1.md`, раздел 3.21.21 архитектуры, README и package exports обновлены.

**Проверки и ограничения:**
- Предыдущие 141 JPVR tests: **USER-REPORTED PASS**.
- Новые JCOM tests: 20 **AUTHORED, NOT RUN**. Static source review не считается runtime test.
- Сборки, Gradle, APK/AAB, CI и GitHub Actions — **NOT RUN**.
- JOURNAL/OUTBOX codecs, transaction sequence planner, page reservation, file I/O, flush/sync, superblock publication и recovery пока отсутствуют.

**GitHub:** `9060c6d7` (JCOM codec), `6e966462` (JCOM tests), далее commits документации/exports в истории `main`.

**Следующий шаг:** после прохождения 20 focused JCOM tests и полного 161-test JPVR набора добавить строгий JOURNAL record/page codec; затем OUTBOX codec и только после них transaction-sequence planner. Не начинать файловую запись до отдельной проверки recovery/order invariants.


### 2026-11-09 — JPVR JNRL v1 append-only operation-state codec

**Цель:** добавить проверяемое представление истории CQE operation status в JPVR и связать повторные записи одной операции с предыдущими JOURNAL pages, не заявляя durable storage.

**Добавлено:**
- `tools/jpvr_reference/journal.py`:
  - JNRL v1 payload: 8-byte prefix и детерминированные TLV fields для operation_id, idempotency_key, owner_module_id, status, expected/new state versions, commit_id/PageRef, previous-entry PageRef и result_object_id;
  - PageType.JOURNAL encode/decode с проверкой transaction_id, commit generation и физического порядка ссылок;
  - `validate_journal_transition` проверяет неизменность identity/version/commit fields, ссылку на предыдущую запись, смену transaction_id между поколениями и разрешённую машину состояний;
  - COMMIT_UNKNOWN может разрешиться в COMMITTED либо в FAILED только после подтверждения, что commit не состоялся; такой переход не означает автоматический retry side effect.
- `tools/jpvr_reference/test_journal.py` — 32 теста для TLV schema, unknown optional/mandatory fields, malformed IDs/status/PageRefs, page envelope/order, state-version invariants, valid lifecycle, illegal jumps и resolution COMMIT_UNKNOWN.
- `tools/jpvr_reference/__init__.py` — экспорт JournalEntry, OperationStatus, page/payload codecs и transition validator.
- `docs/JPVR_JOURNAL_V1.md`, разделы 3.21.22–3.21.23 архитектуры, README и COW/JCOM reference docs обновлены.

**Проверки и доказательства:**
- Пользователь ранее сообщил PASS для 141 JPVR tests, включая COW (109 ранних codecs/manifest + 16 INDEX + 16 COW); это user-reported Termux verification.
- Добавлены 20 JCOM tests и 32 JOURNAL tests. Они **AUTHORED, NOT RUN**; новые 52 теста не объявляются пройденными.
- Статическая GitHub readback проверка подтвердила наличие исходников/документов и посчитала test methods; это не запуск Python tests.
- В ходе review найден некорректно записанный import block в `__init__.py` (буквальные `\\n` внутри исходника); package file переписан нормальным Python multiline syntax и повторно прочитан. Отдельного Python import/syntax run не было.
- Текущий JPVR source set: 10 test modules, 193 test methods (141 user-reported PASS + 20 JCOM + 32 JOURNAL AUTHORED, NOT RUN).
- Builds, Gradle, APK/AAB, CI и GitHub Actions **NOT RUN**.

**Ограничения:** JOURNAL codec не обеспечивает глобальную уникальность operation_id/idempotency_key, не создаёт idempotency index, не сканирует полную историю и не разрешает result_object_id в RecordLocation. OUTBOX codec, complete transaction page-sequence planner, exclusive page reservation, file I/O, flush/sync, superblock publication, crash recovery, rollback и orphan-page handling отсутствуют. Валидная JOURNAL page не подтверждает commit и не доказывает durability.

**GitHub:** опубликовано в `main`. Основные commits: `bf355f4` (JOURNAL codec), `fa2db24` (32 initial tests), `b2bb808` / `9873169` / `1d02d4b` (validation hardening), `4b7acf4` (COMMIT_UNKNOWN resolution regressions), `8608881` (package exports), `da9ed18` (JOURNAL contract), `2ddfea4` (architecture update), `daaa64c` and `71c75e9` (README/architecture totals).

**Следующий шаг:** пользовательский запуск 20 JCOM tests, 32 JOURNAL tests и полного 193-test JPVR набора. После подтверждения перейти к OUTBOX codec; transaction sequence planner делать только после согласования JOURNAL/OUTBOX/COMMIT reference semantics. Файловую запись и durability barriers не начинать без отдельного тестирования order/recovery invariants.


### 2026-11-09 — JPVR JOURNAL lifecycle hardening and status correction

**Дополнение к записи JNRL выше:** после дополнительного статического review state machine была ужесточена. Принятая операция не может перейти из QUEUED обратно в DEFERRED. Переход COMMIT_UNKNOWN → FAILED теперь требует явного аргумента `confirmed_no_commit=True`; вызывающая сторона обязана предварительно подтвердить отсутствие опубликованного commit по авторитетному storage state. Ненулевые transaction_id проверяются и в transition validator. Добавлены два regression test methods: запрет QUEUED → DEFERRED и обязательное подтверждение no-commit. Итоговый `test_journal.py` содержит **34**, а не 32 метода.

**Уточнение тестового статуса:** весь текущий JPVR source set содержит **195 test methods** в десяти модулях: 141 тест пользователь сообщил как PASS в Termux; дополнительные 20 JCOM и 34 JNRL теста — **AUTHORED, NOT RUN**. Новые 54 теста не запускались. Static GitHub readback подтвердил полный список модулей и количество методов; Python import/syntax/unit tests здесь не выполнялись.

**Документы/команды:** README full-suite command теперь включает все десять модулей, в том числе `test_journal`. README, COW/JCOM/JOURNAL contracts и architecture status синхронизированы с числом 195 и финальной state machine.

**Ограничения без изменений:** JOURNAL пока не обеспечивает глобальную idempotency uniqueness, не сканирует/восстанавливает полный journal, не реализует OUTBOX или полный transaction planner. Нет file I/O, page reservation lock, flush/sync, superblock publication или crash recovery. Сборки, Gradle, CI и GitHub Actions не запускались.

**Следующий шаг:** пользователь запускает 20 JCOM tests, 34 JOURNAL tests и все 195 JPVR tests на Termux; только после фактического PASS продолжать с OUTBOX codec и transaction-sequence planning.


### 2026-11-09 — JPVR Termux result: COW split and COMMIT validation fixes

**User-reported run:** the complete 195-test JPVR command executed in Termux. Result: **192 passed, 2 failed, 1 error**. The original 141-test baseline passed again. The three failures were isolated to the newer COW and COMMIT slices.

**Root causes and fixes:**
1. `cow.py`: when splitting an INTERNAL node, the promoted separator was incorrectly retained as the first separator of the right node. In the B+ tree representation, the promoted separator belongs to the new parent; the right node must start with the corresponding child PageRef as its separator-less first child. The split builder now constructs that right-child list correctly. The existing deep internal-root split test covers the regression.
2. `test_commit.py`: the negative fixture `PageRef(9, 1)` for generation 2 was actually valid: it correctly references generation 1, and page 9 precedes manifest page 10. The invalid page-number fixture is now `PageRef(1, 1)`; the wrong-generation fixture remains `PageRef(9, 3)`. This corrects the test rather than weakening valid COMMIT semantics.
3. `commit.py`: clarified the manifest-generation validation error text to explicitly say "current commit generation", matching the contract/test wording.

**Commits:** COW fix `55a89d0`; COMMIT validation wording `81fc33e`; COMMIT negative-fixture correction `8189cf0`.

**Verification status:** fixes are pushed to `main`; they have **not yet been rerun** in Termux. Do not report the 195-test suite as passing until the user reruns it. No builds, CI or GitHub Actions were started.

**Next action:** run focused COW and COMMIT modules, then the complete 195-test JPVR suite. If green, proceed to OUTBOX codec and transaction-sequence planning.

### 2026-11-09 — JPVR INDEX encoder/decoder depth parity hardening

**Цель:** устранить возможность кодировать INTERNAL INDEX node, который собственный decoder отвергает из-за максимальной глубины.

**Статический аудит:** `_validate_node` ранее проверял только, что `node.level` помещается в uint8 (0–255), тогда как `decode_index_node` уже отклонял уровни `>= MAX_INDEX_DEPTH` (64). Поэтому `encode_index_node` мог выдавать заведомо не round-trip совместный payload для уровней 64–255.

**Изменения:**
- `tools/jpvr_reference/index.py` — encoder validation теперь требует `node.level < MAX_INDEX_DEPTH`, сохраняя uint8/type validation.
- `tools/jpvr_reference/test_index.py` — добавлен regression test: максимальный допустимый уровень 63 проходит encode/decode round-trip, уровни 64 и 255 отклоняются encoder-ом.
- `README.md`, `docs/JARVIS_ARCHITECTURE.md`, JPVR COW/JCOM/JOURNAL contracts — синхронизирован текущий источник тестов и история последнего полного запуска.

**Количество тестов:** статический подсчёт десяти JPVR модулей: 23 + 23 + 15 + 14 + 15 + 19 + 17 + 16 + 20 + 34 = **196 test methods**. Новый INDEX regression test — **AUTHORED, NOT RUN**.

**Уточнение предыдущей записи:** фраза «original 141-test baseline passed again» была слишком широкой. Последний полный запуск 195 методов содержал COW test, который завершился ERROR; следовательно этот запуск не подтверждает PASS всего 141-test агрегата. Ранее сообщённый 141 PASS сохраняется только как исторический пользовательский результат. Самый свежий фактический результат остаётся **192 PASS, 2 FAIL, 1 ERROR** до повторного запуска после уже опубликованных COW/COMMIT fixes.

**Проверки:** выполнен только static GitHub source inspection и подсчёт test methods; Python tests и syntax/import checks **NOT RUN**. Новая regression test и текущие 196 методов не объявляются прошедшими. Builds, Gradle, APK/AAB, CI и GitHub Actions — **NOT RUN**.

**Следующий шаг:** после `git pull --ff-only origin main` запустить focused INDEX/COW/COMMIT tests, затем полный 196-test JPVR command. Только после подтверждённого PASS переходить к OUTBOX codec. Файловую запись, durability barriers и recovery по-прежнему не начинать.

### 2026-11-09 — JPVR OUTBOX immutable event reference slice

**Цель:** реализовать следующий безопасный слой JPVR — сериализацию immutable Event, который должен входить в ту же логическую транзакцию, что и изменение состояния, и не публиковаться до подтверждения source COMMIT.

**Добавлено:**
- `tools/jpvr_reference/outbox.py` — JOUT v1 deterministic TLV payload codec, OUTBOX page envelope, source-COMMIT generation/order checks и adjacent global-sequence validator.
- `tools/jpvr_reference/test_outbox.py` — 36 deterministic tests для round-trip, unknown optional/mandatory TLV, prefix/length/identity/type/payload limits, PageRef, page type/CRC, transaction identity, sequence continuity и same-generation COMMIT consistency.
- `docs/JPVR_OUTBOX_V1.md` — бинарный контракт, API и ограничения.

**Архитектурные решения:** одна OUTBOX page хранит одно неизменяемое событие; обязательные идентификаторы — event_id, idempotency_key, operation_id и commit_id; event_type ограничен 128 UTF-8 bytes; opaque event payload — 1,800 bytes; sequence начинается с 1. Event ссылается на COMMIT того же поколения, который физически следует за OUTBOX page. Соседние события одного поколения должны принадлежать одному transaction_id и одному source COMMIT. Доставка остаётся at-least-once.

**Изменено:** `docs/JARVIS_ARCHITECTURE.md` (раздел 3.21.24 и границы реализации), `docs/JPVR_JOURNAL_V1.md` (актуализированы ограничения), `README.md` (11 modules / 232 methods и evidence).

**Проверки:** пользователь выполнил после исправлений COW/COMMIT/INDEX:
- `python3 -m unittest tools.jpvr_reference.test_index tools.jpvr_reference.test_cow tools.jpvr_reference.test_commit -v` — **PASS, 53/53**.
- полный десятимодульный JPVR набор — **PASS, 196/196**, Android Termux, 2.204 s.
Эти результаты относятся к исходному набору до добавления OUTBOX. Статический подсчёт после добавления: 23 + 23 + 15 + 14 + 15 + 19 + 17 + 16 + 20 + 34 + 36 = **232 test methods / 11 modules**. OUTBOX 36 тестов — **AUTHORED, NOT RUN**.

**Не запускалось:** Python tests для нового OUTBOX кода, общий 232-test suite, syntax/import checks, сборки, Gradle, APK/AAB, CI и GitHub Actions. Не утверждается, что новый модуль уже прошёл runtime verification.

**Ограничения:** нет global event/idempotency index, publisher worker, subscriber ACK/cursor pages, retry/retention, transaction page-sequence planner, page allocator/lock, container I/O, flush/sync, superblock publication или crash recovery. Adjacent validator не заменяет полное сканирование chain. Valid OUTBOX page не доказывает active/durable COMMIT.

**Следующий шаг:** пользователь запускает 36 focused OUTBOX tests, затем полный 232-test JPVR set. После PASS можно проектировать subscriber acknowledgement/dispatch cursor или integrated transaction planner; не начинать file I/O/durability до полного transaction/recovery design.

### 2026-11-10 — Export OUTBOX public API and add import regression

**Цель:** сделать новый JOUT codec доступным через общий публичный пакет `tools.jpvr_reference`, сохранив ту же схему экспорта, что у INDEX/COW/COMMIT/JOURNAL.

**Изменения:**
- `tools/jpvr_reference/__init__.py` — экспортированы `OutboxEvent`, `DecodedOutboxPage`, encode/decode payload/page functions и `validate_outbox_sequence`.
- `tools/jpvr_reference/test_outbox.py` — добавлен regression test, проверяющий идентичность package-level exports с исходными символами модуля.
- `README.md`, `docs/JARVIS_ARCHITECTURE.md`, JPVR OUTBOX/JOURNAL/COMMIT/COW contracts — обновлён общий счётчик до 233 test methods / 11 modules и уточнён verification status.

**Проверки:** выполнен static GitHub fetch/readback исходников и документации. Python import/unit tests **NOT RUN**. Десятимодульный baseline 196/196 PASS — пользовательский результат, сообщённый до добавления OUTBOX. OUTBOX module (теперь 37 методов) и общий 233-test suite остаются **AUTHORED, NOT RUN**.

**Ограничения:** exports не означают, что OUTBOX работает как publisher или durable queue. Global event/idempotency uniqueness, subscriber ACK/cursors, retries, retention, transaction assembly, page reservation, file I/O, flush/sync, superblock publication и crash recovery не реализованы.

**Следующий шаг:** пользователь запускает `python3 -m unittest tools.jpvr_reference.test_outbox -v`, затем полный одиннадцатимодульный JPVR набор. Только после фактического PASS выбирать следующий слой; file I/O/durability не начинать до transaction/recovery design.

### 2026-11-10 — OUTBOX cross-generation COMMIT identity validation

**Аудит:** `validate_outbox_sequence` уже требовал новый `transaction_id` на границе поколений и проверял физический порядок предыдущего COMMIT, но не запрещал повторное использование `commit_id` в следующем поколении. Это расходилось с JCOM `validate_commit_snapshot`, который требует менять commit_id между поколениями.

**Изменения:**
- `tools/jpvr_reference/outbox.py` — при переходе между поколениями соседние OUTBOX события теперь обязаны иметь разные `commit_id`.
- `tools/jpvr_reference/test_outbox.py` — добавлены positive regression для корректного перехода gen1→gen2 и negative regression для повторного commit_id.
- `docs/JPVR_OUTBOX_V1.md`, architecture, README и JPVR status docs — обновлены инвариант и счётчик.

**Тестовый статус:** OUTBOX теперь содержит 39 test methods; весь одиннадцатимодульный source set содержит 235 методов. Все OUTBOX tests остаются **AUTHORED, NOT RUN**. Ранее пользователь сообщил PASS 196/196 для базового десятимодульного набора до OUTBOX.

**Проверки:** static source review и GitHub readback; Python tests **NOT RUN**. Builds, Gradle, APK/AAB, CI и GitHub Actions **NOT RUN**.

**Следующий шаг:** выполнить 39 focused OUTBOX tests, затем полный 235-test JPVR suite. Не переходить к transaction assembly или durable file I/O до подтверждения codec tests и отдельной спецификации recovery/order.

### 2026-11-10 — JPVR OUTBOX source-COMMIT binding validator

**Предшествующая пользовательская проверка:** после `git pull --ff-only origin main` пользователь выполнил:
- focused INDEX/COW/COMMIT command — **PASS, 53/53**, 1.352 s;
- полный десятимодульный JPVR command — **PASS, 196/196**, 2.204 s;
- Android environment: Termux on TECNO device, according to the user-provided terminal output.
Эти результаты подтверждают именно десятимодульный baseline до добавления OUTBOX.

**Аудит:** JOUT event хранил `commit_id` и `commit_ref`, однако существующий adjacent-sequence validator не сверял их с actual encoded source COMMIT page. Это позволяло структурно корректной OUTBOX page ссылаться на страницу с другим commit descriptor.

**Изменения:**
- `tools/jpvr_reference/outbox.py` — добавлен `validate_outbox_commit_reference(outbox_page, commit_page_bytes)`. Валидатор декодирует точный физический COMMIT page из PageRef и сверяет physical page number, generation, transaction_id, commit_id и operation_id. Возвращает проверенный `DecodedCommitPage`.
- `tools/jpvr_reference/test_outbox.py` — добавлены семь regression cases: корректная ссылка, несовпадение commit_id, operation_id, transaction_id, generation, физического номера страницы и forged OUTBOX page с нулевым transaction_id.
- `tools/jpvr_reference/__init__.py` — экспорт нового API.
- `docs/JPVR_OUTBOX_V1.md`, `docs/JARVIS_ARCHITECTURE.md` и `README.md` — описаны новый contract, ограничения и обновлён test count.

**Границы:** source-COMMIT binding подтверждает только согласованность двух page descriptors. Он не доказывает, что COMMIT активен/durable, что OUTBOX page достижима из MANIFEST OUTBOX root или находится ниже committed high-water mark. До публикации будущий reader должен валидировать COMMIT chain, MANIFEST, active Superblock, root reachability и high-water. Publisher, subscriber ACK/cursor, retry/retention, file I/O и crash recovery всё ещё отсутствуют. Delivery semantics остаётся at-least-once.

**Количество:** OUTBOX suite вырос с 39 до **46** методов. Одиннадцать JPVR modules содержат **242 test methods** (196 ранее проверенных baseline + 46 OUTBOX). Все 46 OUTBOX tests и новый source-binding API — **AUTHORED, NOT RUN**. Полный 242-test suite **NOT RUN**.

**Проверки, выполненные в этой задаче:** только GitHub source readback и статическая проверка расположения API/tests/docs. Новые Python tests, import checks и весь 242-test suite **NOT RUN**. Builds, Gradle, APK/AAB, CI и GitHub Actions — **NOT RUN**.

**Следующий шаг:** пользователь запускает 46 focused OUTBOX tests, затем полный одиннадцатимодульный 242-test suite. Не начинать publisher, acknowledgement cursor или file I/O до подтверждения codec и отдельной спецификации committed-root lookup/recovery.

### 2026-11-10 — OUTBOX genesis sequence anchor validation

**Цель:** закрыть непроверенный край цепочки OUTBOX: adjacent-pair validation гарантировала последовательность между двумя событиями, но не проверяла корректность первой записи в цепочке.

**Аудит и исправление:**
- tools/jpvr_reference/outbox.py — добавлен validate_outbox_genesis(page). Проверяет decoded OUTBOX envelope, ненулевой transaction_id, sequence ровно 1 и отсутствие previous_event_ref. Docstring явно указывает: вызывающий reader должен закрепить scan за committed MANIFEST root/high-water mark; локальный genesis validator не доказывает отсутствие пропущенных ранее reachable pages.
- tools/jpvr_reference/test_outbox.py — добавлены четыре теста: корректная genesis запись; sequence gap; запрещённая predecessor reference; нулевой transaction_id.
- tools/jpvr_reference/__init__.py — новый API экспортирован из публичного пакета.
- Обновлены docs/JPVR_OUTBOX_V1.md, docs/JARVIS_ARCHITECTURE.md, docs/JPVR_COMMIT_V1.md, docs/JPVR_COW_WRITER_V1.md и README.md: описаны новый инвариант, правильный scan anchor и актуальные test counts.

**Проверки:** пользователь сообщил «Все тесты прошли» после предыдущего batch. Сообщение не включало точную команду, count, duration или environment, поэтому результат записан как user-reported PASS без реконструкции отсутствующих деталей. Предыдущий одиннадцатимодульный JPVR source set содержал 242 test methods; пользовательское сообщение трактуется как подтверждение того набора. Текущая версия содержит 246 методов / 11 JPVR modules; четыре только что добавленных genesis tests — **AUTHORED, NOT RUN**. В этой задаче Python tests, syntax checks и imports не запускались; сделаны GitHub source fetch/review и документальные обновления. Builds, Gradle, APK/AAB, CI и GitHub Actions — **NOT RUN**.

**Ограничения:** валидатор проверяет только genesis descriptor, но не подтверждает, что reader не пропустил более раннюю страницу. Для безопасного чтения всё ещё нужны committed MANIFEST root/high-water anchoring, полный COMMIT chain validation, активный Superblock, reachability, global event/idempotency uniqueness и fail-closed recovery. JPVR остаётся reference codecs/planners, а не durable storage engine.

**Следующий шаг:** выполнить четыре новых focused genesis tests вместе с OUTBOX module, затем полный 246-method JPVR suite. При PASS проектировать read-side OUTBOX chain scanner прежде transaction assembly; не вводить file I/O/durability до утверждения и проверки полного transaction/recovery protocol.


### 2026-11-10 — JPVR committed OUTBOX read-side scanner (one major stage)

**Цель:** перейти от отдельных OUTBOX event/sequence validators к проверяемому read-side проходу по активному JPVR snapshot и всей истории committed OUTBOX.

**Реализация:**
- Добавлен `tools/jpvr_reference/outbox_scan.py` с immutable `OutboxScanResult` и `scan_committed_outbox(superblock, page_catalog)`.
- Scanner требует полный непрерывный in-memory page catalog от page 2 до активного durable tail; проверяет page numbering, CRC/структуру всех страниц и отсутствие дыр.
- Проверяет active COMMIT/MANIFEST snapshot через существующий `validate_commit_snapshot`, включая Superblock identity, durable tail, manifest roots/high-water references и предыдущий COMMIT descriptor.
- Проходит весь COMMIT chain до generation 1; сверяет исторические COMMIT/MANIFEST references, transaction IDs, поколения и append order; требует, чтобы все COMMIT pages в durable catalog принадлежали активной цепочке.
- Начинает OUTBOX traversal с MANIFEST `outbox_high_water_page_no`, идёт по predecessor references к genesis, проверяет source COMMIT каждого event, membership source COMMIT в активной chain, sequence continuity, genesis и глобальные event_id/idempotency_key duplicates.
- Сверяет, что все OUTBOX pages в durable catalog покрыты цепочкой, а при high-water=0 OUTBOX pages отсутствуют.
- Экспортированы `OutboxScanResult` и `scan_committed_outbox` из `tools.jpvr_reference`.
- Добавлен `tools/jpvr_reference/test_outbox_scan.py`: 16 focused test methods, включая single/multi-generation scan, empty outbox, package exports, incomplete/corrupt catalog, Superblock mismatch, missing COMMIT, sequence gap, duplicate IDs, skipped high-water history, missing predecessor coverage и invalid source reference.
- Добавлен [docs/JPVR_OUTBOX_SCAN_V1.md](JPVR_OUTBOX_SCAN_V1.md); обновлены README, OUTBOX contract и architecture section 3.21.25.

**Проверки:** только GitHub source fetch/readback и ручная статическая проверка в этом ходе. Python tests/import/syntax checks **NOT RUN**. Предыдущий 11-module JPVR batch (242 methods на момент его формирования) пользователь сообщил как PASS; после него добавлены 4 genesis tests и 16 scanner tests. Текущий JPVR source count — **262 methods across 12 modules**, эти 20 новых методов — **AUTHORED, NOT RUN**. Ни build, Gradle, CI, GitHub Actions, APK/AAB, ни file I/O не запускались.

**Ограничения:** scanner принимает уже выбранный/декодированный Superblock; он не читает dual physical slots и не выбирает active slot. Полный contiguous catalog нужен в RAM, поэтому это не streaming reader. Active MANIFEST OUTBOX root проверяется как INDEX page, но B+ tree membership каждого event пока не traversed. Исторические MANIFEST descriptors сверяются, но их index roots не replay-ятся как historical snapshots. Логическая целостность не доказывает fsync/flush durability. Это read-side reference scanner, не storage engine, crash recovery или event publisher.

**Следующий шаг:** пользователь запускает 16 scanner tests и 4 genesis tests, затем полный 262-method JPVR suite. При PASS следующий крупный этап — design/implementation transaction assembly planner, который связывает append layout, DATA/BLOB/INDEX/MANIFEST/JOURNAL/OUTBOX/COMMIT в одну атомарную страницу-цепочку; file I/O и durability barriers остаются за пределами до проверки этого planner.


### 2026-11-10 — JPVR scanner and CQE priority-aging regression repair

**Пользовательский сигнал:** полный запуск python3 -m unittest discover -s tools -t . -v завершился Ran 497 tests / FAILED (failures=3, errors=7).

**Причины и исправления:**
- tools/jpvr_reference/outbox_scan.py: DecodedOutboxPage содержит поля page_no, commit_generation, transaction_id и вложенный event: OutboxEvent. Scanner ошибочно читал event_id и idempotency_key на внешнем уровне. Исправлено на page.event.event_id / page.event.idempotency_key.
- Тот же scanner теперь явно проверяет, что существующая страница MANIFEST outbox_high_water_page_no имеет тип OUTBOX, и выдаёт диагностическую ошибку до попытки декодировать её как OUTBOX event.
- test_outbox_scan.py: fixture оборванной predecessor-связи ранее одновременно нарушал operation_id source COMMIT, поэтому падал на более ранней проверке. Fixture теперь сохраняет корректную привязку к generation-2 COMMIT и изолирует отсутствие predecessor link.
- test_cqe.py: priority-aging test ожидал исполнение после 5 секунд, но default command deadline также равен 5 секундам. Для изоляции aging test теперь использует command deadline cap 10 секунд; production aging interval и deadline semantics не ослаблялись.

**Коммиты:** scanner 1474fa54a90524ac50a19e2e3aeda569918c9e38; scanner regression fixture 17844da50253d1d0aa4544eb439a3afdca697052; CQE test c2f98928c9853ce1df42f2584dc4576da471eeb3; docs обновлены отдельными последовательными commits после этих исправлений.

**Проверки:** исходный результат — пользовательский, 497 tests / 7 errors / 3 failures. После исправлений Python tests NOT RUN; требуется focused JPVR scanner + CQE tests и затем полный suite. Builds, Gradle, CI, GitHub Actions, APK/AAB NOT RUN.


### 2026-11-10 — Final two reported regressions: CQE aging deadline and OUTBOX diagnostic ordering

The user's second full run reported 497 tests with only two failures remaining.

- CQE: the previous deadline-cap edit had landed in the neighboring priority-drain test, not in `test_priority_aging_prevents_starvation`. The starvation test's P3 command therefore retained the default 5-second execution cap and expired at exactly the same instant it reached one aging interval. Applied the 10-second command cap to the correct per-test ModuleSpec and added an explicit COMPLETED assertion. The scheduler's aging algorithm was not weakened or changed.
- JPVR: `validate_commit_snapshot` calls `validate_manifest` before the scanner's OUTBOX-chain routine, so the generic MANIFEST type validator raised first. The scanner now decodes the active MANIFEST and checks the referenced high-water page type before invoking the generic snapshot validator, producing the scanner-specific `not OUTBOX` diagnostic. The later defensive check remains in the chain scanner.

Commits: CQE regression test `4671670f15cb067ddb6312e2355fcbff9996db14`; JPVR scanner validation order `d84a033e278c7d515874102f6e82cd3d6bb6d0b1`.

Tests after these edits: NOT RUN. User must pull and rerun focused tests, then full suite. No builds, Gradle, CI or GitHub Actions were run.


### 2026-11-10 — JPVR transaction assembly planner (one major stage)

**Objective:** integrate the existing JPVR append-layout, page codecs, MANIFEST, COMMIT and snapshot validators into one deterministic in-memory transaction assembly plan, without beginning physical storage I/O or durability work.

**Implemented:**
- Added `tools/jpvr_reference/transaction.py` with `TransactionAssemblyPlan`, `assemble_transaction` and `validate_transaction_assembly_plan`.
- Requires an ordered non-empty staged page sequence, exactly one final staged MANIFEST, and a separately supplied COMMIT as the final append page.
- Validates page CRC/physical page numbers, contiguous append range, next generation, common nonzero transaction ID, MANIFEST identity, COMMIT identity, durable-tail/commit placement, previous-COMMIT bytes for non-genesis commits, and all MANIFEST roots/high-water references using the existing snapshot validator.
- Creates the inactive-Superblock candidate through `plan_append` and validates it against the current base. It returns exact ordered page bytes and does not mutate caller inputs.
- Exports the new public API from `tools/jpvr_reference/__init__.py`.
- Added `tools/jpvr_reference/test_transaction.py` with 16 focused tests for genesis and second-generation assembly, ordering, missing/duplicate MANIFEST, gaps, generation/transaction mismatches, missing predecessor bytes, invalid catalog/root references, tampered plans, and failure immutability.
- Added `docs/JPVR_TRANSACTION_ASSEMBLY_V1.md`; updated README and architecture section 3.21.26.

**Verification:** source and test files were reviewed statically after publication. Python tests **NOT RUN** in this environment; the 16 new methods are **AUTHORED, NOT RUN** pending the user's Termux execution. No claim of PASS is made. Builds, Gradle, CI, GitHub Actions, APK/AAB, physical file I/O, fsync/flush, Superblock publication and crash recovery **NOT RUN / OUT OF SCOPE**.

**Limitations:** planner consumes pre-encoded pages and does not itself create DATA/BLOB/INDEX/JOURNAL/OUTBOX content or verify every typed payload's internal semantics beyond the existing MANIFEST/COMMIT snapshot validators. Page allocation is not locked or reserved. A successful plan is not a durable commit and does not establish production readiness.

**Next stage:** after focused and full JPVR tests pass, build a typed transaction builder that consumes DATA/BLOB/COW/JOURNAL/OUTBOX plans and emits the staged page sequence. Physical writer, flush barriers, dual-slot publication and crash recovery remain a separate later design.


### 2026-11-10 — JPVR typed transaction builder V1 (one major stage)

**Цель:** перейти от сборки уже закодированных страниц к typed API, который сам формирует страницы основных JPVR семейств и собирает единый проверяемый append plan. Physical writer и durability остаются вне этого этапа.

**Добавлено:**
- `tools/jpvr_reference/typed_transaction.py`: `DataRecordInput`, `BlobRecordInput`, `IndexPageInput`, `JournalEntryInput`, `TypedTransactionRequest`, `TypedTransactionBuildPlan`, `build_typed_transaction`, `validate_typed_transaction_plan`.
- Канонический порядок страниц DATA → BLOB → INDEX → JOURNAL → OUTBOX → MANIFEST → COMMIT.
- DATA JREC records автоматически кодируются и greedy-pack без разрыва записи между страницами.
- Каждый BLOB record разбивается на непрерывный fragment run и проверяется round-trip decoder.
- INDEX nodes кодируются в порядке caller; можно пометить страницу как один из семи MANIFEST roots. Неизменённые roots наследуются из активного MANIFEST.
- Для non-genesis активные COMMIT/MANIFEST валидируются до наследования roots/high-water. Новый COMMIT physical page number вычисляется до кодирования JOURNAL и OUTBOX.
- JOURNAL последовательности для одной operation автоматически связываются; supplied external predecessor refs должны разрешаться и проходить transition validator.
- OUTBOX events получают ссылку на точный COMMIT новой транзакции, продолжают committed sequence и проходят source-COMMIT validation. Дубли внутри текущей транзакции отклоняются.
- Request transaction/commit/operation IDs, а также JOURNAL/OUTBOX identity fields проверяются как строго bytes-like значения длиной 16 байт до любого преобразования; integer-like значения не могут случайно превратиться в байтовые буферы.
- Export API в `tools/jpvr_reference/__init__.py`; добавлен контракт `docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md`; обновлены README и архитектура 3.21.27.
- Добавлено 19 focused tests в `tools/jpvr_reference/test_typed_transaction.py`.

**Тесты:** 18 новых методов — **AUTHORED, NOT RUN** по прямому запросу продолжить без проверки. Пользователь сообщил, что ошибки старого полного набора устранены; новые тесты в тот запуск не входили. В этом этапе Python tests, builds, Gradle, CI, GitHub Actions, APK/AAB не запускались.

**Ограничения:** builder не строит B+ tree из key/value mutations и не вызывает COW автоматически: caller передаёт immutable INDEX nodes с готовыми child PageRefs и порядком размещения. Глобальную уникальность всей исторической OUTBOX цепочки проверяет scanner на полном catalog. Реального file I/O, page locking/reservation, flush/fsync, Superblock publication/arbitration и crash recovery по-прежнему нет. Успешный in-memory plan не является durable commit.

**Фокусная команда для будущей проверки:** `python3 -m unittest tools.jpvr_reference.test_typed_transaction -v`.


### 2026-11-10 — JPVR COW-integrated index mutation pipeline V1

**Цель:** связать существующий immutable B+ tree COW planner с typed transaction builder, чтобы прикладной код отправлял key/value mutations вместо ручной сборки всех INDEX nodes.

**Реализация:**
- `tools/jpvr_reference/cow.py`: добавлен `cow_apply_index_mutations`. Он проверяет batch unique keys/value bounds/tree invariants, маршрутизирует изменения по исходным child ranges, переписывает затронутые leaf/internal paths один раз за batch, повторно использует незатронутые subtree PageRefs, делит переполненные узлы и проверяет итоговую reachability/statistics. Все новые pages принадлежат одной commit generation и contiguous allocation.
- `tools/jpvr_reference/typed_transaction.py`: добавлены `IndexMutationInput`, `IndexMutationBuildPlan` и `TypedTransactionRequest.index_mutations`. Builder группирует mutations по MANIFEST root, требует единый tree_id на root, обрабатывает roots в каноническом порядке, автоматически запускает batch COW, помещает generated INDEX pages в общий append plan и заменяет только соответствующие MANIFEST roots.
- `tools/jpvr_reference/__init__.py`: новые API экспортированы.
- `tools/jpvr_reference/test_cow.py`: добавлено пять batch-COW regression tests: multi-key path rewrite, replace+insert statistics, leaf split with old-snapshot preservation, duplicate-key rejection и invalid/empty batch immutability.
- `tools/jpvr_reference/test_typed_transaction.py`: добавлено шесть builder integration tests: multi-key mutation, multiple roots/canonical order, replacement preserving old snapshot, duplicate keys, invalid tree/root and plan revalidation.
- Обновлены `docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md`, `docs/JARVIS_ARCHITECTURE.md` (3.21.28) и README.

**Проверки:** source readback и API cross-reference review выполнялись до публикации; Python tests, import/syntax checks **NOT RUN** в этом ходе. Все 11 новых методов — **AUTHORED, NOT RUN**. Нельзя считать их PASS до focused и полного JPVR запуска в Termux. Android/Gradle builds, CI, GitHub Actions, APK/AAB, физический file I/O, flush/fsync и crash recovery — **NOT RUN**.

**Ограничения:** batch planner допускает несколько ключей одного дерева и несколько корней за транзакцию, но не удаление ключей/ребалансировку после удаления. Root tree_id предоставляется caller, поскольку MANIFEST хранит PageRef, а не tree_id. Новый generation root нельзя мутацировать повторно в той же транзакции. Все операции in-memory; durable writer, locking/page reservation, dual-Superblock publication/arbitration и crash recovery остаются отдельным следующим этапом.


### 2026-11-11 — JPVR Real Storage Engine V1 (file-backed reference)

**Цель:** реализовать физическое открытие/создание JPVR контейнера, ordered append, двухслотовую публикацию Superblock и безопасный выбор durable snapshot после сбоя.

**Добавлено / изменено:**
- `tools/jpvr_reference/storage.py`: `JPVRStorage.create/open/get_superblock/read_page/load_page_catalog/commit/close`; physical offsets Superblock slot 0/1 = 0/4096, pages N = N*4096. Создание использует O_EXCL, пишет обе genesis copies, fsync файла и родительского каталога.
- Opening проверяет обе копии Superblock с expected slot id, COMMIT, MANIFEST, previous COMMIT и MANIFEST direct references; выбирает newest valid generation, rejects conflicting equal-generation roots, truncates bytes beyond selected durable tail. Full active B+ tree traversal включается через `verify_index_trees=True`.
- Commit takes cross-process `flock` plus in-process `RLock`, revalidates assembly/stale base and typed COW mutation plan, appends all pages, fsyncs page bytes, writes inactive Superblock and fsyncs it, then re-reads selected snapshot. Failures after publication begins are surfaced as `JPVRCommitUncertainError(commit_id, generation)`.
- `load_page_catalog(max_pages=4096)` exposes bounded materialization for the existing typed transaction builder; the default maximum is 4096 pages (16 MiB raw page bytes plus Python object overhead).
- `tools/jpvr_reference/test_storage.py`: 19 focused tests authored for both genesis slots, no-overwrite creation, truncated containers, genesis/second-generation commits, alternating slots, stale plans, orphan/partial tail cleanup, damaged Superblock/COMMIT/root fallback, both-slot corruption, equal-generation conflict, page bounds, bounded catalog reads, typed COW publication, metadata mismatch and injected partial Superblock publication.
- `tools/jpvr_reference/__init__.py`: exported storage API.
- Added `docs/JPVR_STORAGE_ENGINE_V1.md`; updated `docs/JARVIS_ARCHITECTURE.md`, README, COW and transaction-planner contracts.

**Проверки:** GitHub source readback and static source/API review only. Python tests, import/syntax checks and actual fault-injection execution **NOT RUN**. The 19 new storage tests are **AUTHORED, NOT RUN**. Current JPVR source count is 327 test methods across fifteen modules (308 prior + 19 new). No Android/Gradle build, CI, GitHub Actions, APK/AAB or device I/O/resource measurement was run.

**Ограничения:** normal open checks the active snapshot, direct MANIFEST references and immediate previous COMMIT, not the entire historical COMMIT chain or every unreferenced page. Full B+ tree traversal is optional at open; modified typed-COW trees are validated before commit. No compaction, free-page reuse, full recovery history, CQE delivery/ACK, JNVM adapter, or hardware-level flush guarantee is implemented. `fsync` calls are present but need device testing and cannot prove the specific flash controller's power-loss behavior.

**Focused Termux command:** `python3 -m unittest tools.jpvr_reference.test_storage -v`. The combined 15-module JPVR suite command is in README. All new tests remain **AUTHORED, NOT RUN** until the user executes them.


### 2026-11-11 — JPVR Historical Recovery Scanner V1

**Цель:** проверять всю физическую историю JPVR от genesis до активного Superblock, проверять каждый исторический MANIFEST отдельно и сообщать последнюю полностью подтверждённую transaction boundary без автоматического изменения файла.

**Добавлено / изменено:**
- tools/jpvr_reference/recovery.py: публичный scan_jpvr_history(superblock, page_catalog, verify_index_trees=False); модели JPVRRecoveryIssue, JPVRHistoricalSnapshot, JPVRHistoryScanResult. Сканер последовательно читает страницу за страницей, сохраняет compact page headers, проверяет generation-specific contiguous append ranges, единый transaction_id/generation, финальное положение MANIFEST/COMMIT, уникальность commit/transaction IDs, exact predecessor PageRef, и каждую historical COMMIT↔MANIFEST snapshot ссылку.
- Каждая историческая MANIFEST проверяется через validate_commit_snapshot с synthetic historical Superblock и prefix-limited page catalog, чтобы future pages нельзя было использовать в старом snapshot. Проверяются все семь INDEX roots и CHECKPOINT/JOURNAL/OUTBOX high-water references.
- Существующий OUTBOX linked-history helper повторно используется для event predecessor, source COMMIT chain membership, sequence и identity validation.
- При первом повреждении отчёт сохраняет предыдущие полностью verified generations, issue code/message/page/generation и verified_durable_tail_page_no. Сканирование не выполняет автоматический rollback или truncate.
- tools/jpvr_reference/storage.py: добавлен JPVRStorage.scan_history(), работающий под shared lock с lazy _DiskPageCatalog, без load_page_catalog(). Он сообщает orphan_tail_bytes, но не удаляет orphan tail.
- tools/jpvr_reference/test_recovery.py: 22 теста на genesis/1/3 поколений, повреждённую или отсутствующую страницу, I/O failure, неправильный historical root, page generation/transaction identity, неверный порядок MANIFEST, дубли commit/transaction IDs, разрыв поколений, несовпадение active Superblock/checkpoint, uncommitted/orphan tail, optional B+ tree verification, OUTBOX chain и lazy file-backed scan.
- Экспортированы JPVRHistoryScanResult, JPVRHistoricalSnapshot, JPVRRecoveryIssue, scan_jpvr_history. Добавлена docs/JPVR_HISTORICAL_RECOVERY_V1.md; обновлены README, architecture (3.33), JPVR_STORAGE_ENGINE_V1 и JPVR_OUTBOX_SCAN_V1.

**Проверки:** GitHub source readback/static API and documentation consistency review only. Python tests, import/syntax checks, injected filesystem failure execution and Android Termux I/O measurement NOT RUN. Все 22 новых test methods AUTHORED, NOT RUN. JPVR suite source total = 349 methods across sixteen modules. No Android/Gradle build, CI, GitHub Actions, APK/AAB or device I/O/resource measurement was run.

**Ограничения:** scanner сохраняет O(page count) компактных headers, raw page bytes читает лениво; optional full-tree audit может быть затратным. Scanner только формирует safe-prefix report; автоматическое восстановление старого Superblock/состояния, truncate/rollback, compaction и free-page reuse не реализованы. Orphan-tail bytes only reported. Hardware-level power-loss guarantees and actual Android flash behavior remain unverified.

**Focused Termux command:** python3 -m unittest tools.jpvr_reference.test_recovery -v. Full JPVR suite command is in README; the historical scanner module is included there.

### 2026-11-11 — JPVR Recovery Copy V1 (non-destructive verified-prefix publication)

**Цель:** добавить явную безопасную операцию восстановления, которая превращает исторически проверенный JPVR prefix в отдельный валидный файл, не изменяя повреждённый источник.

**Добавлено / изменено:**
- tools/jpvr_reference/recovery.py: JPVRRecoveryCopyResult с destination path, восстановленным Superblock, source/recovered generations, исходной recovery issue, orphan-tail count и признаком rollback.
- tools/jpvr_reference/recovery.py: historical scanner теперь проверяет OUTBOX цепочку последнего полностью verified snapshot даже если более поздняя страница/транзакция повреждена. Ранее такая цепочка не проверялась, что блокировало безопасное решение о копировании prefix. Добавлен один тест на сохранённый verified OUTBOX prefix.
- tools/jpvr_reference/storage.py: JPVRStorage.recover_copy(destination, expected_commit_id=..., verify_index_trees=None); операция под shared flock пересканирует источник, требует exact 16-byte commit identity и valid OUTBOX chain, пишет only verified physical page prefix во временный файл, восстанавливает active + immediate previous Superblock slots, fsync/readback-checks страницы и обе копии Superblock, затем публикует новым same-directory hard link без overwrite. Исходный файл и его Superblock не меняются.
- Добавлен JPVRRecoveryCopyUncertainError: если destination уже опубликован, но финальный directory fsync/cleanup не подтверждён, caller обязан проверить destination перед retry.
- tools/jpvr_reference/test_recovery_copy.py: 12 тестов на recover-to-copy без мутации source, active/previous slots, сохранение текущего generation, orphan tail trim в копии, existing destination refusal, exact/malformed commit IDs, OUTBOX refusal, source=destination, no-replace publication failure и отсутствие валидных Superblock slots.
- Экспортированы JPVRRecoveryCopyResult и JPVRRecoveryCopyUncertainError; добавлен docs/JPVR_RECOVERY_REPAIR_V1.md; обновлены README, architecture (3.34), historical recovery и storage contracts.

**Проверки:** GitHub readback/static source and documentation consistency review only. Python tests, import/syntax checks, hard-link/fsync failure injection, Android Termux filesystem behavior and device I/O NOT RUN. Все 13 новых test methods AUTHORED, NOT RUN. JPVR reference set = 362 methods across seventeen modules. No Gradle/Android build, CI, GitHub Actions, APK/AAB.

**Ограничения:** это не in-place repair; source Superblock, pages и orphan tail остаются нетронутыми. Copy доступна только если OUTBOX цепочка последнего verified prefix подтверждена; неизвестное/повреждённое состояние OUTBOX блокирует публикацию. Destination publication requires same-directory hard-link support. После JPVRRecoveryCopyUncertainError output path должен быть inspected до retry. Нет semantic JNVM/neuron-state reconstruction, автоматического rollback источника, compaction/free-page reuse, Android device verification или hardware-level power-loss guarantee.

**Focused Termux commands:** python3 -m unittest tools.jpvr_reference.test_recovery tools.jpvr_reference.test_recovery_copy -v. Complete seventeen-module JPVR suite command is in README. New tests are AUTHORED, NOT RUN.


### 2026-11-11 — JNVM↔JPVR One-Neuron Checkpoint Adapter V1

**Цель:** впервые связать фактическое in-memory состояние NeuronRuntime с typed DATA/BLOB input JPVR, сохраняя параметры, память и traces без потери временного clock-domain контекста.

**Добавлено / изменено:**
- tools/neuron_runtime/runtime.py: read-only accessors для NeuronParams, SynapseParams, RewardParams; constructor принимает валидируемый processed_batches для точного восстановления; добавлен u64 overflow guard перед следующим batch.
- tools/neuron_runtime/jpvr_adapter.py: versioned JNVM runtime record (record type 0x0101, schema ID 0x4A4E564D, schema version 1); canonical UTF-8 JSON payload с exact schema, duplicate-key rejection, finite-number validation, 16 MiB limit и stable neuron object ID с SHA-256 domain separation. Snapshot содержит все параметры, NeuronState, все SynapseState/STDP traces/R-STDP eligibility context, learning rule, processed-batch counter и обязательный 128-bit clock_epoch_id.
- Adapter выбирает DataRecordInput/JREC-DATA для payload до small-record limit и BlobRecordInput/BLOB для большего; append_neuron_runtime_checkpoint() возвращает новый immutable transaction request; decoder проверяет record metadata/object identity и восстанавливает новый валидированный NeuronRuntime.
- tools/neuron_runtime/test_jpvr_adapter.py: 16 test methods для полной конфигурации/state round-trip, R-STDP context, deterministic encoding, DATA/BLOB selection, invalid ID/epoch/version/schema/JSON/numeric paths, object-ID mismatch, transaction builder round-trip and duplicate checkpoint guard.
- tools/neuron_runtime/__init__.py: экспортирован adapter API.
- Добавлен docs/JNVM_NEURON_RUNTIME_JPVR_ADAPTER_V1.md; обновлены docs/JARVIS_ARCHITECTURE.md (section 3.35) и README/test command/status.

**Проверки:** GitHub source readback and static structural/document cross-reference only. All 16 tests are **AUTHORED, NOT RUN**. Python tests, import/syntax checks, numerical runtime replay, physical JPVRStorage.commit()/reopen, full snapshot restore through an INDEX locator, Android/Termux I/O and resource measurement **NOT RUN**. No builds, CI, GitHub Actions, APK/AAB.

**Ограничения:** это one-neuron record codec/typed-input adapter, не полноценный JNVM service. Не реализованы atomic neuron COW INDEX locator, automatic record-version allocator, storage-level load/save orchestration, multi-neuron event queue/topology persistence, external reward baseline persistence, CQE session persistence, or scheduler-owned clock-epoch continuity. A caller must retain and resume the same model clock epoch; a new OS monotonic-clock origin cannot be substituted silently. JPVR durable behavior and Android filesystem semantics remain unverified.

**Следующий этап:** add versioned locator updates to the neuron COW INDEX, atomically connecting logical neuron ID/object ID to checkpoint DATA/BLOB page range and record version. Then implement a bounded storage façade that appends checkpoint plus locator in one JPVR transaction and loads by validating the active commit, index tree, record version and record CRC before restoring NeuronRuntime.


### 2026-11-11 — JNVM Indexed One-Neuron Checkpoint Store V1

**Цель:** завершить первый полный путь сохранения/загрузки одного NeuronRuntime через JPVR: стабильный neuron INDEX locator, автоматическое record_version, atomic checkpoint+locator commit и восстановление через активный индекс и историю COMMIT.

**Добавлено / изменено:**
- `tools/jpvr_reference/storage.py`: `JPVRStorage.load_snapshot(max_pages=4096)` возвращает active Superblock и его bounded materialized page catalog под одним shared lock. `load_page_catalog()` теперь делегирует этому согласованному snapshot API.
- `tools/neuron_runtime/jpvr_store.py`: `JPVRNeuronCheckpointStore.save()/load()`; fixed 100-byte JNLR V1 locator; stable object_id neuron index; DATA/JREC и BLOB range resolution; initial seven empty INDEX roots for genesis; COW index update; record-version allocation/expected-version conflict; two-pass planning resolves final COMMIT page while checkpoint and locator share one final transaction.
- Load walks active COMMIT predecessor links to verify that locator source COMMIT is an ancestor of the active snapshot, then checks page range, transaction/generation metadata, JREC/BLOB schema/object/version, page/full-record CRCs and decoded logical neuron identity before returning NeuronRuntime.
- `tools/neuron_runtime/runtime.py` and `jpvr_adapter.py`: removed invalid assumption that all per-synapse timestamps must be <= last membrane update. A delayed R-STDP reward can validly advance its trace/eligibility clock independently within the same explicit clock_epoch_id.
- `tools/neuron_runtime/test_jpvr_store.py`: 15 methods for locator codec, first genesis save, reopen/load, record version update, expected version conflict, BLOB round-trip, neuron-key isolation, not-found, delayed reward restore, corrupted page, bounded page catalog and preflight validation. Adapter/runtime regression coverage updated for delayed feedback timestamps.
- Exports and contracts updated; added `docs/JNVM_NEURON_CHECKPOINT_STORE_V1.md`, architecture section 3.36, README commands/status and JPVR storage snapshot-read documentation.

**Проверки:** GitHub source readback/static inspection and documentation cross-reference only. All 15 new store tests plus the new delayed-reward adapter regression are **AUTHORED, NOT RUN**. No Python test execution, import/syntax execution, filesystem fault injection, Android/Termux I/O, memory/CPU/latency benchmark, build, CI, GitHub Actions, APK or AAB.

**Ограничения:** default store facade materializes a bounded 4096-page catalog; it is not a streaming large-container service. It persists one neuron only; NetworkRuntime queue/topology/network clock, external reward baseline, CQE state and sessions remain separate. Genesis initialization of seven empty index roots is a separate COMMIT; checkpoint DATA/BLOB plus its neuron locator are atomic within the following COMMIT. Commit-uncertain outcomes still require active commit inspection before retry. Android filesystem durability and hardware power-loss semantics remain unverified.

**Следующий этап:** introduce a versioned, checkpointed network/runtime clock and persist the bounded NetworkRuntime topology + pending events together with a set of neuron locators, or first implement the external R-STDP reward-baseline state store. Keep side effects and CQE delivery out of the checkpoint until durable transaction semantics are explicitly specified.


**Дополнение к этапу — scheduler clock consistency:** `tools/neuron_runtime/network.py` теперь инициализирует network clock по максимуму membrane-update и всех per-synapse STDP/R-STDP trace timestamps. Это не позволяет многонейронному scheduler после restore поставить сигнал раньше уже обработанной синаптической награды. `tools/neuron_runtime/test_network.py` содержит новый регрессионный сценарий (15 тестов всего), AUTHORED, NOT RUN. Locator load path дополнительно валидирует полное neuron B+ tree, exact COMMIT durable-tail/page identity и отказывает от нулевого object_id. First save заранее проверяет, что max_pages >= 9, иначе не публикует частичную genesis INDEX initialization; два новых store-теста увеличили suite с 15 до 17. README, architecture, network/store contracts согласованы с этими ограничениями.


### 2026-11-11 — JNVM↔JPVR Multi-Neuron Network Checkpoint V1

**Цель:** закрыть следующий разрыв после one-neuron indexed store: сохранить и восстановить весь bounded NetworkRuntime (all neurons, topology, pending event queue, scheduler limits and model clock) одной JPVR checkpoint-публикацией.

**Изменения:**
- tools/neuron_runtime/network.py: добавлены isolated export и validated restore API для checkpoint state. Restore проверяет clock bounds, pending queue size, event IDs, target neurons/synapses и соответствие internal routed events connection-owned synapses.
- Новый tools/neuron_runtime/network_adapter.py: canonical versioned JNNW V1 snapshot; вложенные complete neuron snapshots, clock epoch, connections, pending events, scheduler limits and stable network object ID.
- Новый tools/neuron_runtime/network_store.py: JPVRNetworkCheckpointStore; BLOB payload + JNCP CHECKPOINT descriptor; source COMMIT ancestry check; payload CRC; generation/transaction/schema/object/version checks; expected_record_version CAS; one active network checkpoint pointer per container.
- tools/jpvr_reference/typed_transaction.py: добавлен CheckpointPageInput; builder stages CHECKPOINT after OUTBOX and before MANIFEST; a new CHECKPOINT pointer is published with the same COMMIT, otherwise existing pointer is inherited.
- tools/neuron_runtime/test_network_adapter.py (11 methods) and test_network_store.py (14 methods) authored for codec/store contracts and failure paths; public exports updated.
- Documentation/status: README, architecture §3.37, network runtime contract, typed transaction builder contract, and this log.

**Файлы:** modified tools/neuron_runtime/network.py, tools/jpvr_reference/typed_transaction.py, tools/jpvr_reference/__init__.py, tools/neuron_runtime/__init__.py, docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md, docs/JARVIS_NETWORK_RUNTIME_V1.md, docs/JARVIS_ARCHITECTURE.md, README.md; added tools/neuron_runtime/network_adapter.py, network_store.py, test_network_adapter.py, test_network_store.py, docs/JNVM_NETWORK_CHECKPOINT_STORE_V1.md.

**Проверки:** GitHub source readback and static cross-reference review only. 11 adapter tests and 14 storage tests are **AUTHORED, NOT RUN**. Existing 15 scheduler tests also remain AUTHORED, NOT RUN. No Python tests, imports/syntax checks, benchmark, Android/Termux device I/O, builds, CI, GitHub Actions, APK or AAB were run, per current no-check/no-build constraint.

**Ограничения:** one active network checkpoint per container; bounded materialized catalog (default 4096 pages); clock epoch continuity remains caller-owned; external reward-baseline, CQE/session/side-effect persistence and event deduplication beyond pending queue remain unimplemented. This is not production JNVM/NCC, and crash consistency/device performance are not measured.

**Следующий этап:** persist the external R-STDP Reward Baseline Manager with explicit clock epoch and compare-and-swap/versioning, then join network and baseline checkpoints into one higher-level runtime snapshot before CQE session/event persistence.


### 2026-11-11 — R-STDP Reward Baseline Manager and JPVR Checkpoint V1

**Цель:** перейти от Oracle-функции `apply_reward()`, принимающей baseline извне, к самостоятельному владельцу baseline для задач: безопасный порядок feedback, replay/idempotency, сохранение pending обработки и восстановление.

**Изменения:**
- `tools/neuron_runtime/reward_baseline.py`: bounded RewardBaselineManager; отдельный baseline на reward_task_id; immutable prepare/commit flow; sequence-gap DEFERRED; conflicting replay/version detection; bounded receipt history; explicit clock epoch and monotonic model time; persisted pending proposal; strict state restore.
- `tools/neuron_runtime/reward_baseline_adapter.py`: canonical JRBM V1 snapshot and stable domain-separated manager object ID; strict JSON duplicate-key, non-finite, schema and identity checks; BLOB record input.
- `tools/neuron_runtime/reward_baseline_store.py`: JRCP V1 descriptor and JPVRRewardBaselineStore; BLOB+CHECKPOINT+MANIFEST+COMMIT save; source COMMIT ancestry, transaction/generation/container checks, BLOB metadata/CRC, page catalog bound and expected_record_version CAS.
- `tools/neuron_runtime/test_reward_baseline.py` (26 methods) and `tools/neuron_runtime/test_reward_baseline_store.py` (15 methods): manager/codec/order/replay/epoch/restart/descriptor/storage/CAS/corruption/ancestry tests.
- `tools/neuron_runtime/__init__.py`: public exports. README, architecture §3.38 and dedicated contract updated.

**Файлы:** added `tools/neuron_runtime/reward_baseline.py`, `reward_baseline_adapter.py`, `reward_baseline_store.py`, `test_reward_baseline.py`, `test_reward_baseline_store.py`, `docs/JNVM_REWARD_BASELINE_CHECKPOINT_STORE_V1.md`; modified `tools/neuron_runtime/__init__.py`, `README.md`, `docs/JARVIS_ARCHITECTURE.md`, this log and `docs/JARVIS_RUNTIME_V1.md`.

**Проверки:** GitHub source readback and static contract review only. 26 manager/codec tests and 15 JPVR store tests are **AUTHORED, NOT RUN**. No Python tests, import/syntax command, fault-injection run, Android/Termux storage I/O, resource benchmark, Gradle/Android build, CI, GitHub Actions, APK or AAB.

**Ограничения:** manager and store are Python reference code, not production feedback evaluator or NCC. One writer and one pending feedback per task; task catalog and dedup history are bounded. Clock epoch remains caller-owned. V1 uses a dedicated JPVR container because one MANIFEST checkpoint pointer cannot simultaneously publish the network and reward-baseline snapshots. The baseline checkpoint is not crash-atomic with NetworkRuntime neuron weights/eligibility, and no durable RewardProcessingRecord/CQE transaction exists. Caller must not commit the baseline until all related synapse operations are terminal success or approved no-op.

**Следующий этап:** implement a versioned composite Runtime checkpoint and feedback transaction for NetworkRuntime + RewardBaselineManager, then integrate CQE durable inbox/outbox/session persistence without losing replay or idempotency guarantees.


### 2026-11-11 — Composite Runtime Checkpoint V1 and DATA/BLOB locator support

**Цель:** объединить NetworkRuntime и RewardBaselineManager snapshot одной JPVR-транзакцией и исправить несоответствие DATA/BLOB sizing контракту JPVR.

**Добавлено / изменено:**
- `tools/neuron_runtime/runtime_store.py`: JRCM V1 composite descriptor; `JPVRRuntimeCheckpointStore.save()/load()` publishes both logical records and one composite CHECKPOINT in one COMMIT; runtime CAS; source COMMIT ancestry, page/transaction/generation/schema/object/version/CRC/epoch checks.
- Both adapters select DATA up to 3976 bytes, BLOB above; standalone JNCP/JRCP stores decode both using descriptor flag 0=BLOB, 1=DATA.
- Added `test_runtime_store.py` (12 tests including mixed DATA+BLOB), one small-network DATA test and reward-baseline DATA descriptor/large-BLOB tests; exports and contracts updated.

**Файлы:** added `tools/neuron_runtime/runtime_store.py`, `tools/neuron_runtime/test_runtime_store.py`, `docs/JNVM_COMPOSITE_RUNTIME_CHECKPOINT_V1.md`; modified network/reward adapters, stores and tests, package exports, README, architecture §3.39, runtime/network/reward contracts and this log.

**Проверки:** GitHub source readback/static review only. Composite tests (12) and new DATA/BLOB regressions are **AUTHORED, NOT RUN**. No Python tests, import/syntax checks, storage fault injection, Android/Termux I/O, benchmarks, builds, CI, GitHub Actions, APK or AAB.

**Ограничения:** one atomic checkpoint publication, not an atomic feedback transaction spanning synapse weights/eligibility and baseline advancement. JRCM requires a dedicated runtime JPVR container because MANIFEST exposes one active checkpoint pointer. Clock epoch continuity remains caller-owned. No concurrent-writer protocol, device durability measurements or production NCC guarantees.

**Следующий этап:** prepare a RewardBaseline proposal and candidate synapse updates together, then publish the runtime state only after all synapse operations reach terminal success/no-op; connect feedback to CQE idempotency next.


### 2026-11-11 — Atomic R-STDP Reward Feedback Transaction V1

**Цель:** связать reward baseline с фактическими R-STDP изменениями одного или нескольких синапсов так, чтобы ошибка одного target не оставляла частично обновлённую сеть, а baseline и synaptic state публиковались одним JPVR COMMIT.

**Добавлено / изменено:**
- `tools/neuron_runtime/feedback_transaction.py`: candidate-state coordinator; strict unique target validation; R-STDP-only targets; exact `beta_b` agreement; cloned network/manager; all-target Oracle reward application; baseline-after/modulator cross-check; terminal-status aggregation; composite JRCM save under required runtime-version CAS; durable duplicate receipt reconciliation without reapplying.
- `tools/neuron_runtime/test_feedback_transaction.py`: 10 focused tests for one-commit success, multi-target rollback, duplicate replay, terminal no-eligibility, beta mismatch, stale CAS and duplicate-target rejection.
- Public package exports, README, architecture §3.40, Runtime V1 contract and new atomic feedback contract updated.

**Файлы:** added `tools/neuron_runtime/feedback_transaction.py`, `tools/neuron_runtime/test_feedback_transaction.py`, `docs/JNVM_ATOMIC_REWARD_FEEDBACK_TRANSACTION_V1.md`; modified `tools/neuron_runtime/__init__.py`, `README.md`, `docs/JARVIS_ARCHITECTURE.md`, `docs/JARVIS_RUNTIME_V1.md` and this log.

**Проверки:** GitHub source readback and static cross-reference review only. Ten tests are **AUTHORED, NOT RUN**. No Python test execution, import/syntax checks, storage fault injection, concurrent-writer tests, Android/Termux I/O, benchmarks, Gradle/Android builds, CI or GitHub Actions.

**Ограничения:** only the bounded local R-STDP calculation plus composite state publication is coordinated. The manager replay identity does not persist a digest of target IDs/learning context; callers must keep the complete target set immutable for each feedback ID. The coordinator is single-writer reference code; commit-uncertain outcomes require reopen/load and retry; no CQE authorization/inbox/outbox/session state, external side effects, exactly-once delivery, NCC or Android durability guarantees.

**Следующий этап:** add a durable CQE inbox/feedback envelope and processing receipt so feedback identity, source/policy provenance, target set and committed runtime version can be recovered and deduplicated as one protocol; then integrate bounded CQE task/session orchestration.


**Дополнительное ужесточение readback review:** duplicate replay now reconstructs and compares the full RewardBaselineReceipt against the active durable checkpoint, not only feedback ID/time/status. It also requires the caller's expected runtime version to equal the active version. Added regression tests for stale duplicate replay and pending events at the reward timestamp; total focused tests: 10, **AUTHORED, NOT RUN**.


### 2026-11-11 — Durable CQE R-STDP Reward Feedback Inbox V1

**Цель:** сохранять входной CQE reward-feedback до применения synapse reward и восстанавливать точно тот же target/context/envelope после прерывания, используя существующий JRCM composite checkpoint.

**Добавлено / изменено:**
- tools/neuron_runtime/cqe_feedback.py: специализированный CQE EVENT adapter для jarvis.reward.feedback v1; canonical envelope; UUID/message/event identity; bounded target set; policy-version и authorization-receipt provenance; target/context SHA-256; detached preflight targets before acceptance; durable pending save before authoritative reward; recovery/resume pending; exact completed duplicate без повторного применения.
- tools/neuron_runtime/reward_baseline.py: optional cqe_envelope_json и feedback_context_digest у pending proposal и terminal receipt; strict metadata validation; snapshot decoder принимает старый JRBM V1 field set и расширенный field set; binding envelope к owner task/feedback/sequence/reward/time/clock epoch.
- tools/neuron_runtime/feedback_transaction.py: metadata передаётся от CQE adapter в baseline proposal и terminal receipt; сохраняется в том же JRCM COMMIT с сетевым состоянием.
- tools/neuron_runtime/test_cqe_feedback.py: 12 тестов для inbox acceptance, pending recovery, exact duplicate/no-write, target/context conflict, message/event ID reuse, provenance, invalid schema/type, duplicate targets и tampered digest and legacy JRBM pending/receipt field-set compatibility and preflight rejection before inbox acceptance.
- tools/neuron_runtime/__init__.py: public CQE feedback adapter exports.
- docs/JNVM_CQE_REWARD_FEEDBACK_INBOX_V1.md, architecture §3.41, README, CQE/atomic-feedback cross references and this log updated.

**Проверки:** GitHub source readback and static cross-reference review only. Twelve tests are **AUTHORED, NOT RUN**. No Python tests, imports/syntax execution, fault injection, Android/Termux I/O, benchmarks, builds, CI or GitHub Actions were run.

**Trust boundary:** the adapter stores upstream policy version and an authorization-receipt UUID but does not authenticate sources, issue/verify capabilities, or execute authorization. The caller must authorize the source in its upstream security/policy layer first; the adapter is not wired to a policy gate and does not register the schema with the general CQE router. The receipt UUID is provenance, not cryptographic proof.

**Ограничения:** this is a reward-feedback-only durable inbox, not a general CQE inbox/outbox or event broker. One pending proposal per reward task, bounded receipt retention, JRCM single-writer CAS, and caller-owned clock epoch remain. No sessions, plans, external side effects, concurrent multi-process writer, exactly-once delivery, Android crash guarantee, production NCC or resource measurements.

**Следующий этап:** durable CQE task/session lifecycle with bounded task registry, authorization snapshot/version checks and explicit retry/terminal states, followed by a user-facing JARVIS task loop. General event outbox/inbox should remain a separate contract rather than being implied by this reward-only path.


### 2026-11-12 — Task & Session Lifecycle V1 reference

**Цель:** создать первый исполняемый ограниченный реестр задач и сессий для будущего JARVIS task loop, не смешивая lifecycle state с исполнением Reasoning Engine или разрешениями.

**Добавлено / изменено:**
- tools/neuron_runtime/task_session.py: SessionStatus OPEN/PAUSED/CLOSED; TaskStatus QUEUED/RUNNING/WAITING/COMPLETED/FAILED/CANCELLED; явные разрешённые переходы; expected_revision CAS; стабильные task/session/idempotency IDs; exact idempotent create retry; bounded task/session/event catalogs; logical order; ordered lifecycle event journal; canonical JSON snapshot encode/strict restore.
- tools/neuron_runtime/test_task_session.py: 15 тестов для создания, idempotency/conflict, state machine, terminal immutability, stale revision, paused/closed session policy, capacity atomicity, snapshot round-trip, duplicate JSON keys, schema/size and invalid metadata.
- docs/JARVIS_TASK_SESSION_LIFECYCLE_V1.md: контракт, ограничения и следующий JPVR этап.
- tools/neuron_runtime/__init__.py: public lifecycle exports.
- docs/JARVIS_ARCHITECTURE.md §3.42 and README repository map/status/usage updated.

**Проверки:** GitHub source readback and static cross-reference review only. Fifteen tests are **AUTHORED, NOT RUN**. No Python tests, import/syntax execution, JPVR integration/fault injection, Android/Termux I/O, benchmarks, builds, CI or GitHub Actions were run.

**Ограничения:** lifecycle snapshot is canonical serialization only, not durable storage. There is no JPVR task/session checkpoint, process-restart recovery, authorization snapshot/version binding, scheduler, general CQE inbox/outbox, concurrent writer, external action execution or production NCC. Logical counters are not wall-clock timestamps. Terminal task/session records are not evicted; capacity exhaustion fails closed.

**Следующий этап:** implement a versioned JPVR Task/Session checkpoint adapter/store with CAS, source COMMIT ancestry validation, bounded journal restore and interrupted-write recovery. Then define authorization snapshot/version binding and integrate a restricted task loop.


## 2026-11-12 — Task & Session JPVR Checkpoint V1

- Added versioned JTSL V1 DATA/BLOB record adapter and JTSC V1 CHECKPOINT descriptor.
- Added JPVRTaskSessionCheckpointStore with one-commit record+descriptor publication, manager identity binding, source COMMIT ancestry verification, CRC32C, and expected-record-version CAS.
- Added reopen/restore, identity isolation, stale CAS, descriptor, corruption, and DATA/BLOB tests (9 test methods).
- Exported the new adapter/store API and documented the dedicated-container rule: one active MANIFEST CHECKPOINT pointer means this store must not share a container with standalone/composite runtime checkpoint stores.
- Tests are authored, not run. No Python execution, syntax/import check, fault injection, Android/Termux I/O, benchmark, build, CI, or GitHub Actions run.


## 2026-11-12 — Task & Session JPVR checkpoint hardening review

**Цель:** провести source-level review уже добавленного Task/Session JPVR persistence перед переходом к Reasoning Engine.

**Исправления:**
- `task_session_adapter.py`: manager ID теперь проверяется тем же ASCII identifier pattern, что и TaskSessionManager; encoder выполняет restore-round-trip и отвергает snapshot, который нельзя восстановить в идентичные canonical bytes; decoder требует canonical bytes.
- `task_session_store.py`: descriptor теперь проверяет JPVR logical-record limit, согласованность DATA/BLOB с длиной, точное количество BLOB страниц, переполнение page range и нулевые reserved bytes. Перед любыми JPVR изменениями save предварительно валидирует полный snapshot; BLOB plan additionally verifies schema, generation, transaction and page range metadata.
- `test_task_session_store.py`: добавлены regression tests для reserved-byte rejection, неправильного BLOB page count, canonical adapter round-trip, нестабильного manager ID, non-canonical bytes и отказа до изменения genesis container при некорректном внутреннем состоянии.
- README, architecture §3.42–3.43 и JPVR store contract согласованы с текущим durable статусом и числом тестов.

**Тесты:** 14 методов в `tools.neuron_runtime.test_task_session_store` — **AUTHORED, NOT RUN**. Пятнадцать lifecycle tests также не запускались в этом этапе.

**Проверка:** только чтение исходников из GitHub, проверка связанных контрактов и readback опубликованных изменений. Python execution, import/syntax checks, unit tests, fault injection, Android/Termux I/O, benchmarks, builds, CI and GitHub Actions — **NOT RUN**.

**Ограничения:** store остаётся single-writer Python reference; container должен быть выделенным из-за единственного активного MANIFEST CHECKPOINT pointer. Он восстанавливает только последний committed registry snapshot и не доказывает exactly-once execution внешних действий, не сохраняет scheduler leases/authorization/CQE side effects и не подтверждён тестами питания/сбоев на Android.

**Следующий этап:** определить versioned Authorization Snapshot + Permission & Safety Gate контракт; только после проверки разрешений и актуальной task revision разрешать side effect. Затем объединить reasoning plan, approval and durable intent/result в ограниченный JARVIS Task Loop.


## 2026-11-12 — Reasoning Engine V0.1

**Цель:** добавить первый исполняемый механизм структурированного планирования, оценки предоставленных evidence-записей и проверки результата для следующего этапа JARVIS.

**Добавлено:**
- `tools/neuron_runtime/reasoning_engine.py` — bounded deterministic breadth-first planner over caller-supplied facts/actions; support/refute evidence grouping; hypothesis coverage states; explicit depth/state/fact-limit outcomes; comparison of observed facts with plan goals.
- `tools/neuron_runtime/test_reasoning_engine.py` — 26 focused tests for shortest deterministic plans, already-satisfied/unreachable goals, preconditions/add/delete effects, bounds/cycle behavior, evidence status/identity checks and result verification.
- `docs/JARVIS_REASONING_ENGINE_V0_1.md` — V0.1 API contract, boundedness, evidence interpretation and non-goals.
- `tools/neuron_runtime/__init__.py` — public Reasoning Engine exports.
- `docs/JARVIS_ARCHITECTURE.md` §3.44 and `README.md` — status, constraints, usage and roadmap updated.

**Технические решения:**
- Search is breadth-first with unit-cost actions and stable action-ID ordering. It finds a shortest action-count plan when one is found within configured bounds; deterministic tie-breaking is explicit.
- Actions are data-only precondition/add/delete records. No Python callback, tool invocation or external side effect can be embedded in a plan.
- Evidence statuses describe only supplied support/refute records. Source authenticity, independence and truth are not inferred; quality labels are informational and do not act as probabilities.
- Result verification trusts caller-supplied observed facts and reports goal completion plus predicted/observed differences. It does not authenticate external outcomes.
- Engine is not yet wired to TaskSessionManager or CQE. Permission & Safety Gate remains the next milestone before any task-loop side effect.

**Проверки:** GitHub source readback and static review only. Twenty-six tests are **AUTHORED, NOT RUN**. No Python execution, import/syntax checks, Android/Termux measurements, benchmark, build, CI or GitHub Actions were run.

**Ограничения:** V0.1 does not parse natural language, generate actions from text, retrieve/authenticate external evidence, execute tools, enforce permissions, persist plans or coordinate concurrent work. It is a structured symbolic reference component, not a general intelligence or autonomous assistant.

**Следующий этап:** Permission & Safety Gate with deny-by-default capabilities, approval and policy-version binding, task/revision binding, and explicit resource/time budgets. Then connect lifecycle + reasoner + gate through a bounded JARVIS Task Loop.


## 2026-11-12 — Reasoning Engine V0.1 evidence and resource-bound hardening

**Цель:** закрыть два важных trust/resource-boundary gap в уже существующем Reasoning Engine, синхронизировать публичный API и обновить контракт.

**Изменено:**
- `tools/neuron_runtime/reasoning_engine.py`:
  - добавлен `EvidenceVerificationStatus` (`PENDING`, `VERIFIED`, `REJECTED`) и `verification_ref` к EvidenceRecord;
  - по умолчанию evidence имеет статус PENDING; в hypothesis assessment попадают только caller-marked VERIFIED записи с quality, отличным от UNVERIFIED;
  - verification_ref проверяется как текст и stable ID для terminal review states; malformed enum types приводят к ReasoningError;
  - action rationale теперь явно проверяется как текст;
  - verify_result потребляет не больше max_facts + 1 значений из observed_facts iterator и отклоняет превышение до неограниченного materialization.
- `tools/neuron_runtime/test_reasoning_engine.py`: добавлены/изменены проверки pending evidence, verified support/refute, unverified quality filtering и bounded iterator; модуль теперь содержит 29 test methods.
- `tools/neuron_runtime/__init__.py`: EvidenceVerificationStatus добавлен в public exports.
- `docs/JARVIS_REASONING_ENGINE_V0_1.md`, `docs/JARVIS_ARCHITECTURE.md` §3.23/§3.44 и `README.md`: описаны review-status semantics, limits и актуальное число тестов.

**Архитектурное ограничение:** verification_status и verification_ref поступают от вызывающей стороны. Сам Reasoning Engine не аутентифицирует reviewer или источник и не проверяет криптографическую подлинность ссылки. Это fail-closed фильтр по статусу, предоставленному caller, а не полноценный независимый trust service; такой механизм должен предоставлять будущий Permission & Safety Gate / evidence-verification boundary.

**Тесты:** 29 методов в `tools.neuron_runtime.test_reasoning_engine` — **AUTHORED, NOT RUN**. Исполняемые тесты не запускались по согласованному ограничению.

**Фактическая проверка:** GitHub fetch/readback и статическая сверка обновлённых источников, exports, README и архитектурного контракта. Python execution, import/syntax checks, Android/Termux execution, benchmark, JPVR fault injection, builds, CI и GitHub Actions — **NOT RUN**.

**Ограничения:** V0.1 по-прежнему принимает только структурированные факты и готовые actions от caller; не декомпозирует свободный естественный язык, не извлекает внешние источники, не выполняет actions, не запрашивает одобрение и не вызывает инструменты. План не связан с TaskSessionManager durable revision и не сохраняется в JPVR.

**Следующий этап:** Permission & Safety Gate V1: deny-by-default capability scopes, явные approvals для значимых side effects, policy-version и task/revision binding, ограниченные resource/time/retry budgets. После этого — bounded JARVIS Task Loop.


## 2026-11-12 — Reasoning Engine V0.1 verification-boundary follow-up

**Сверка текущего состояния main:** после предыдущей записи hardening в ветке присутствуют два дополнительных regression tests, поэтому актуальное количество focused tests — **31**, а не 29. Историческую запись выше не переписываем.

**Дополнительные проверки в текущем source/test set:**
- `EvidenceRecord` с terminal review status требует непустой stable `verification_ref`.
- `verify_result()` отклоняет caller-constructed `ReasoningResult(status=SOLVED)`, если его `predicted_final_facts` не содержит все обязательные goal facts; это не позволяет принять внутренне противоречивый plan result за успешно решённый.
- Полный текущий набор из 31 метода также включает bounded consumption observed-facts generator и ранее добавленные pending/verified/rejected evidence checks.

**Актуальные связанные файлы:** `tools/neuron_runtime/reasoning_engine.py`, `tools/neuron_runtime/test_reasoning_engine.py`, `docs/JARVIS_REASONING_ENGINE_V0_1.md`, `docs/JARVIS_ARCHITECTURE.md` §3.44, `README.md` и public exports в `tools/neuron_runtime/__init__.py`. Контракт и README отражают 31 тест и caller-provided trust boundary.

**Проверки:** current-main GitHub readback/source cross-reference only. 31 tests — **AUTHORED, NOT RUN**. Python execution, imports/syntax, Android/Termux, benchmark, build, CI and GitHub Actions — **NOT RUN**.

**Следующий этап без изменений:** Permission & Safety Gate V1 с deny-by-default capabilities, approvals, policy-version/task-revision binding и resource/time/retry budgets. Не подключать реальное исполнение действий до появления этого gate.

## 2026-11-12 — Permission & Safety Gate V1 and bounded JARVIS Task Loop V0.1

**Цель:** соединить уже существующие Task/Session Lifecycle, JPVR persistence и structured Reasoning Engine в последовательный путь выполнения, при котором каждый отдельный шаг проверяется Permission & Safety Gate до передачи внешнему исполнителю.

**Добавлено / изменено:**
- `tools/neuron_runtime/task_loop.py` — `JARVISTaskLoop`, точные `ActionBinding`, `ActionExecutionResult`, injected `ActionExecutor`, детерминированные plan/step IDs и idempotency keys, gate re-check перед каждым action, postcondition/final-goal verification, terminal lifecycle transition и JPVR CAS snapshot preflight/save.
- `tools/neuron_runtime/test_task_loop.py` — 13 тестовых методов: multi-step plan, no-plan rejection, deny-by-default, uncertain executor result with stable key, explicit no-effect failure, postcondition mismatch, mandatory JPVR for side effects, per-step approvals, stale checkpoint CAS и reopen.
- `tools/neuron_runtime/permission_safety_gate.py` — authorization intent digest теперь включает `retry_index`, чтобы approval нельзя было повторно использовать для другого retry attempt.
- `tools/neuron_runtime/test_permission_safety_gate.py` — regression test связывает approval с retry index; текущий набор содержит 26 методов.
- `tools/neuron_runtime/__init__.py` — публичные exports Task Loop.
- `docs/JARVIS_PERMISSION_SAFETY_GATE_V1.md`, `docs/JARVIS_TASK_LOOP_V0_1.md` — отдельные contracts, trust boundaries, retry/approval rules and recovery limitations.
- `docs/JARVIS_ARCHITECTURE.md` — §§3.45–3.46 и актуальная дорожная карта; `README.md` — status, repository map and focused test commands.

**Архитектурные решения:**
- TaskLoopStatus.WAITING является orchestration yield, а не TaskSessionManager TaskStatus.WAITING: реестр сохраняет RUNNING и неизменную revision, пока ожидается approval или reconciliation. Точный pending AuthorizationRequest возвращается caller, чтобы grant оставался привязанным к той же revision.
- Любой effect кроме READ_ONLY требует dedicated TaskSession JPVR store и актуальный CAS token. Перед resume RUNNING task сравниваются persisted record version и полный canonical manager snapshot; несовпадение блокирует executor.
- ActionExecutor обязан обеспечить durable idempotency по стабильному request key. Неопределённый outcome никогда не классифицируется как гарантированное отсутствие side effect.

**Тесты:** 13 Task Loop tests и 26 Permission Gate tests — **AUTHORED, NOT RUN**. Никакой тестовый модуль не запускался.

**Проверки:** GitHub fetch/readback и статическая проверка связей между исходниками, exports, docs и README. Python execution, import/syntax checks, Android/Termux I/O, benchmarks, builds, CI and GitHub Actions — **NOT RUN**.

**Ограничения:** approval grant и approval_ref поступают от caller и не аутентифицируются/не подписываются; policy не имеет durable revocation registry; executor injected, не встроен. Task Loop не сохраняет durable per-step intent/result ledger, approval records или scheduler lease, поэтому JPVR lifecycle checkpoint и executor idempotency contract не доказывают exactly-once side effects. Natural-language decomposition, automatic action generation, built-in tools, runtime resource enforcement, production NCC and Android app are not implemented.

**Следующий шаг:** спроектировать и реализовать durable per-step intent/result ledger с безопасной reconciliation после restart и согласованной CQE inbox/outbox publication. Только после этого подключать ограниченные встроенные capabilities и тонкую UI оболочку.

## 2026-11-12 — Task Loop invalid-input regression follow-up

При последующей статической сверке уточнён тип TaskLoopResult.reasoning: он допускает None, когда ReasoningEngine отклоняет malformed structured input до построения плана. Добавлен 14-й тест `test_invalid_reasoning_problem_fails_before_executor`, проверяющий переход в FAILED без вызова executor. Историческая запись реализации выше не переписывается; актуальное количество — **14 Task Loop tests**, AUTHORED, NOT RUN. Обновлены `docs/JARVIS_TASK_LOOP_V0_1.md`, `docs/JARVIS_ARCHITECTURE.md` и `README.md`. Выполнен только GitHub source readback; Python/tests/builds/CI не запускались.


## 2026-11-12 — Durable per-step intent/result ledger for JARVIS Task Loop

**Цель:** закрыть известный recovery gap между записью parent task как RUNNING и внешним вызовом ActionExecutor, а также сохранить контрольный след подтверждённых шагов в том же task/session JPVR checkpoint.

**Добавлено / изменено:**
- `tools/neuron_runtime/task_loop.py` — каждый плановый шаг получает детерминированную дочернюю TaskRecord, identity которой связана с manager/task/plan/step. После ALLOWED child переводится в RUNNING и публикуется отдельным JPVR checkpoint до executor call. Известный SUCCEEDED фиксируется child COMPLETED с digest полного executor outcome/observed-facts ответа; FAILED_NO_EFFECT — child FAILED; UNKNOWN сохраняет RUNNING. При повторе completed step executor должен вернуть ранее сохранённый результат под тем же action idempotency key; loop сравнивает его digest и закрывается с ошибкой при несовпадении.
- `tools/neuron_runtime/test_task_loop.py` — добавлены пять сценариев: child-step ledger появляется в RUNNING до входа в executor; неизвестный outcome восстанавливается после закрытия/повторного открытия JPVR файла; ранее завершённый шаг повторно читается по стабильному idempotency key и сверяется; изменённый replay result блокируется fail-closed; исчерпание task capacity блокирует executor до side effect. Модуль содержит **20 test methods**.
- `docs/JARVIS_TASK_LOOP_V0_1.md` — описаны step-ledger lifecycle, порядок gate -> durable intent -> executor, восстановление и ограничения.
- `docs/JARVIS_ARCHITECTURE.md` §3.46 и `README.md` — синхронизированы со статусом и следующим этапом.

**Архитектурное решение:** не создавать второй JPVR checkpoint format для шага. Child TaskRecord хранится в том же TaskSessionManager snapshot, поэтому intent/result и parent lifecycle используют существующий CAS-protected TaskSession JPVR store и его один MANIFEST checkpoint pointer. Каждая запись intent/result публикуется отдельным атомарным JPVR commit; это не атомарная транзакция с внешним сервисом.

**Recovery contract:** child RUNNING означает, что side effect мог произойти; повтор uses the same action idempotency key. Child COMPLETED содержит digest, но не полный observed-facts payload; executor обязан хранить свой durable idempotency ledger и возвращать прежний полный ответ. При несовпадении digest parent task завершается fail-closed. Между внешним effect и сохранением child COMPLETED остаётся crash window; exactly-once external execution не заявляется.

**Тесты:** 20 методов в `tools.neuron_runtime.test_task_loop` — **AUTHORED, NOT RUN**. Остальные существующие Reasoning Engine / Permission Gate / Task Session suites также не запускались в этом изменении.

**Проверки:** GitHub fetch/readback и статическая сверка реализации, тестовых сценариев, JPVR store API, README, архитектуры и task-loop contract. Python execution, import/syntax checks, Android/Termux I/O, crash/fault injection, benchmarks, builds, CI and GitHub Actions — **NOT RUN**. По прежнему ограничению не выполнялись сборки и тесты.

**Ограничения:** исполнителю необходим durable idempotency ledger, который переживает его собственный restart. JPVR step digest не является внешним receipt, не аутентифицирует исполнителя и не координирует внешнюю транзакцию. Scheduler leases, durable approval registry/provider, concurrent workers, Android sandbox and production NCC остаются за пределами V0.1.

**Следующий этап:** тонкая локальная Android app shell поверх Task Loop: интерфейс задач/сессий, статусов, плана и pending approval. Не подключать произвольные системные действия до trusted approval provider и отдельной проверки Android capabilities.


## 2026-11-12 — Initial JARVIS Android UI shell

**Objective:** begin the fifth roadmap stage with a minimal Android application entry point while keeping the Python reference runtime isolated and avoiding any false claim of task execution.

**Added:**
- `settings.gradle.kts`, root `build.gradle.kts`, and `app/build.gradle.kts` — standalone Android application configuration using AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, minSdk 29 and compile/target SDK 35.
- `app/src/main/AndroidManifest.xml` — launcher activity declaration.
- `app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt` — Russian Compose screen with a bounded local draft list, input length limit and explicit disconnected-runtime status.
- `docs/JARVIS_ANDROID_APP_V0_1.md` — UI contract, trust boundary, limitations and next step.

**Changed:**
- `docs/JARVIS_ARCHITECTURE.md` — added §3.47 and documented the Android/runtime boundary.
- `README.md` — added app status and repository map entries; corrected stale Task Loop test count and removed the false claim that its child-step intent/result ledger is not implemented.

**Behavior:** users can add up to 100 task drafts in the current UI process. Drafts are not durable, do not survive process death, and are not executed or sent to the Python Reasoning Engine/Task Loop. No tools, network, shell or device actions are exposed.

**Tests / verification:**
- GitHub file readback: to be completed after publication.
- Kotlin/Gradle compilation, Gradle sync, APK/AAB, emulator/device install and UI tests: **NOT RUN** by project restriction. No build or workflow was triggered.
- The Python test suites were not run; this batch changes only the Android UI/configuration and documentation.

**Limitations / next step:** create versioned Android-facing task/session DTOs and a repository boundary, then design a safe local runtime bridge. Persistent task drafts, process-death recovery, UI-bound approval grants and actual engine execution are not implemented. This UI shell is not a working AI assistant.


## 2026-11-12 — Android local app V0.2 and loopback PWA console improvements

**Цель:** развить первый Android Compose UI shell до устойчивой локальной модели сессий/черновиков; отдельно улучшить существующую Python PWA-консоль, не смешивая их authoritative storage и trust boundaries.

**Android app — добавлено / изменено:**
- `app/src/main/java/org/jarvis/ans100/alpha/data/LocalDraftRepository.kt` — новый bounded versioned `JARVIS_ANDROID_DRAFTS V1` codec/repository; лимиты 32 сессии, 500 черновиков, 240 символов на название, 512 KiB snapshot; strict Base64/UTF-8 decode, duplicate/dangling ID rejection, schema/version checks, session revision CAS, OPEN/PAUSED/CLOSED transitions, task creation only in OPEN, terminal CLOSED, commit failure handling.
- `app/src/main/java/org/jarvis/ans100/alpha/data/SharedPreferencesDraftStorage.kt` — app-private storage adapter с synchronous commit result.
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` — StateFlow UI state and Android lifecycle owner.
- `app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt` — Compose dashboard with session picker and controls, draft creation, draft search, detail dialog and delete. All task cards are explicitly labelled as unsent local drafts; no execution status is inferred.
- `app/src/test/java/org/jarvis/ans100/alpha/data/LocalDraftRepositoryTest.kt` — nine unit-test methods for persistence round-trip/repository recreation, session transitions, stale revision, paused/closed task creation rejection, no-op write avoidance, failed commit, unknown schema, session limit and invalid title.
- `docs/JARVIS_ANDROID_APP_V0_1.md` — current V0.2 app and Android repository contract/limitations updated.

**PWA console — добавлено / изменено:**
- `tools/jarvis_app/server.py` — revision-checked `POST /api/sessions/{id}/status` for pause/resume/close; same-status calls avoid duplicate JPVR commits.
- `tools/jarvis_app/test_server.py` — five test methods covering session lifecycle/revision, close blocked by active task, no-op status, HTTP endpoint and foreign-origin rejection.
- `tools/jarvis_app/static/index.html`, `app.js`, `styles.css` — session controls, task search/status filters, active task counters, translated statuses and expandable result fields.
- `tools/jarvis_app/static/sw.js` — cache version increment to v2, so clients replace the prior static shell.
- `docs/JARVIS_LOCAL_CONSOLE_V1.md` — loopback startup/API/safety contract.

**Architecture / contributor documentation:**
- `docs/JARVIS_ARCHITECTURE.md` §3.47 — added native Android V0.2 status and explicit bridge boundary; clarified Task Loop §3.46 limitations.
- `README.md` — Android/PWA file map and usage/limitations updated.
- `AGENTS.md` — corrected stale claim that Reasoning Engine exists only conceptually; records current bounded Python reference modules and the disconnected Android draft shell.
- `docs/DEVELOPMENT_LOG.md` — this entry.

**Архитектурное решение:** Android SharedPreferences state and Python TaskSessionManager/JPVR remain separate stores. Android drafts are not QUEUED JARVIS tasks and must not be shown as executing/completed. The Python PWA is a separate loopback-only console backed by JPVR. A future bridge must use versioned DTOs, explicit ownership/revision/CAS and a trusted approval boundary; do not synchronize by copying display status strings.

**Тесты:** nine Android repository tests and five PWA console tests are **AUTHORED, NOT RUN**. No Python unit test, Kotlin unit test, import/syntax check, Gradle sync/build, APK/AAB, emulator/device install, Compose UI/instrumentation test, browser/device PWA check or Android performance measurement was executed.

**Проверки:** GitHub file readback and static cross-reference inspection of UI, repositories, routes, docs, README, architecture and contributor instructions only. No builds, tests, CI or GitHub Actions were run.

**Ограничения:** Android repository is app-private but not encrypted against a compromised device, is not JPVR and has no multi-process transaction protocol. Synchronous SharedPreferences commit latency is unmeasured. Android does not yet invoke ReasoningEngine, PermissionSafetyGate, Task Loop or Python JPVR. No trusted UI approval provider, credentials, shell/filesystem/network capabilities or native runtime bridge exist. PWA remains loopback-only and does not execute tools.

**Следующий этап:** design and implement a narrow local Android-to-Python runtime bridge with versioned DTOs, durable TaskSession/JPVR ownership, restart/reconciliation semantics and a trusted approval UX bound to the exact AuthorizationRequest. Until this bridge is implemented and verified, retain draft-only semantics.


## 2026-11-12 — Android ↔ Python JPVR runtime bridge V1

**Цель:** подключить нативное Android-приложение к уже существующей локальной Python-консоли, не смешивая Android-local drafts с authoritative TaskSessionManager/JPVR state и не открывая внешнее исполнение.

**Добавлено / изменено:**
- `app/src/main/java/org/jarvis/ans100/alpha/data/LocalRuntimeBridgeClient.kt` — фиксированный HTTP client для `127.0.0.1:8765`; health/API schema validation; GET state; create session/task; pause/resume/close session с expected revision; cancel QUEUED task с expected revision; strict bounded JSON parsing, request/response limits, timeouts, redirect disabled.
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` — StateFlow runtime connection state and one background executor for HTTP work; results return to main thread.
- `app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt` — V0.3 panel shows JPVR-backed backend sessions/tasks and supports the bounded lifecycle operations. Android-local drafts remain in their own section/store.
- `app/src/main/AndroidManifest.xml` and `app/src/main/res/xml/network_security_config.xml` — INTERNET permission; cleartext is denied by default and allowed only to loopback 127.0.0.1/localhost.
- `tools/jarvis_app/server.py` — `GET /api/health` now publishes `api_schema="jarvis.local-console"` and `api_version=1`.
- `tools/jarvis_app/test_server.py` — added versioned-health regression test; six test methods authored.
- `docs/JARVIS_ANDROID_RUNTIME_BRIDGE_V1.md` — transport, API, ownership, bounds, reconciliation, security boundary and non-goals.
- `docs/JARVIS_ANDROID_APP_V0_1.md`, `docs/JARVIS_LOCAL_CONSOLE_V1.md`, `docs/JARVIS_ARCHITECTURE.md` §3.47–3.48, `README.md`, and `AGENTS.md` — updated for Android V0.3 bridge status and constraints.

**Архитектурные решения:**
- Python TaskSessionManager + dedicated JPVR checkpoint remain authoritative for backend tasks/sessions. Android SharedPreferences remain authoritative only for local drafts; no cross-store copying or status mirroring.
- The bridge exposes only session/task registry operations. It does not call Reasoning Engine, PermissionSafetyGate, Task Loop or ActionExecutor. Created backend tasks remain QUEUED; no execution is inferred.
- API schema/version is checked before initial connection. The bridge is fixed to loopback; no configurable remote host or LAN binding is exposed.
- Loopback/Host/Origin checks are not identity authentication. This API is restricted to non-secret local registry administration.

**Тесты / сборки:** six Python local-console tests and nine Android local-repository tests are AUTHORED, NOT RUN. No Python tests, Kotlin tests, import/syntax checks, Gradle sync/build, APK/AAB, installation, Android↔Termux end-to-end I/O, CI or GitHub Actions were run.

**Статус проверки:** GitHub source readback and static cross-reference review only. No runtime behavior, device networking, Android cleartext policy behavior, or performance has been verified on the target phone. Gradle Wrapper remains absent.

**Ограничения:** native UI does not yet call the structured reasoning-preview endpoint; no trusted approval provider, authentication, ActionExecutor, neural Runtime/NetworkRuntime integration or end-to-end Task Loop is connected. Other local applications may be able to reach the loopback console; do not expose it to LAN/Internet.

**Следующий этап:** add a bounded structured Reasoning Engine preview to Compose without executing actions. Then design a trusted approval boundary and executor-owned durable idempotency/reconciliation before any side effects.


## 2026-11-12 — Android V0.4 symbolic Reasoning Engine preview

**Цель:** завершить следующий безопасный шаг после Android↔Python JPVR registry bridge: дать нативному Compose UI возможность запросить и показать bounded Reasoning Engine plan, не исполняя действий и не меняя durable task/session state.

**Добавлено / изменено:**
- `app/src/main/java/org/jarvis/ans100/alpha/data/LocalRuntimeBridgeClient.kt` — DTOs для preview request/step/result; вызов `POST /api/reasoning/preview`; validation идентификаторов, текстовых полей, уникальных фактов/actions, лимитов 16 actions / 32 input facts per list / 64 возможных итоговых фактов; strict bounded response decode для статуса, шагов и facts.
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` — отдельная асинхронная preview-операция и StateFlow результата; сетевой запрос выполняется на существующем единственном background executor.
- `app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt` — Android V0.4 UI для task label, цели, начальных/целевых фактов, чистых planning transitions и отображения SOLVED / NO_PLAN / DEPTH_LIMIT / SEARCH_LIMIT с шагами. Добавлены локальная проверка строки действий и входных списков.
- `tools/jarvis_app/test_server.py` — три теста: service preview не меняет JPVR checkpoint/task registry; duplicate facts отклоняются; HTTP preview возвращает только символьный план. Всего девять console test methods.
- `docs/JARVIS_ANDROID_APP_V0_1.md`, `docs/JARVIS_ANDROID_RUNTIME_BRIDGE_V1.md`, `docs/JARVIS_LOCAL_CONSOLE_V1.md`, `docs/JARVIS_ARCHITECTURE.md`, `README.md`, `AGENTS.md` — синхронизированы с Android V0.4 scope и trust boundary.

**Архитектурные решения:**
- Reasoning preview принимает caller-authored symbolic facts and pure transitions; это не natural-language parsing и не генерация executable shell/tool commands.
- Preview ID — метка отдельного анализа, не TaskSessionManager task ID; preview не обновляет очередь и JPVR checkpoint.
- Предлагаемые шаги не являются approval и не направляются в PermissionSafetyGate, Task Loop или ActionExecutor.
- Внешние действия, tool execution и remote access остаются disabled. Loopback Host/Origin проверки не являются authentication.
- Для следующего этапа требуется спроектировать authenticated local pairing/identity, durable policy/revocation, trusted approval provider и executor-owned idempotency/reconciliation до включения любых side effects.

**Тесты / сборки:** девять Python local-console tests и девять Android local-draft repository tests — AUTHORED, NOT RUN. Kotlin compilation, Gradle sync/build, APK/AAB, installation, device UI, Android↔Termux end-to-end networking, Python test execution, performance, CI и GitHub Actions — NOT RUN. Не запускались тесты и сборки.

**Статус проверки:** GitHub source readback/static cross-file inspection only. Изменения закоммичены в `main`; проверка runtime behavior не выполнена.

**Ограничения:** Reasoning Engine всё ещё работает только с caller-supplied facts/actions. Нет natural-language understanding, automatic safe-action generation, authenticated evidence, approvals, TaskLoop invocation из Android, ActionExecutor, neural NetworkRuntime/CQE integration или APK. Отображение preview не означает, что цель выполнена в реальном мире.

**Следующий этап:** создать отдельный защищённый local pairing/authentication flow между Android и Termux без LAN binding; затем durable policy/revocation и exact-request approval. До их реализации оставить executor недоступным.


## 2026-11-12 — Android V0.5 authenticated local-console bridge

**Цель:** закрыть предыдущую критическую границу локальной консоли: task/session state и все мутации ранее были доступны любому локальному процессу, способному обратиться к loopback-порту.

**Добавлено / изменено:**
- `tools/jarvis_app/auth.py` — 256-bit random Base64URL bearer token, SHA-256 digest-only persistence, owner-only file permissions (0600) and private parent directory (0700), constant-time digest comparison, explicit token rotation.
- `tools/jarvis_app/server.py` — API v2; public health advertises required Bearer auth, while `GET /api/state` and every POST route reject missing/invalid credentials with HTTP 401. The process still binds only to 127.0.0.1.
- `tools/jarvis_app/test_server.py` — token persistence/restart/rotation and HTTP deny/allow regressions; 11 console test methods total, all AUTHORED / NOT RUN.
- `app/.../data/RuntimeAccessTokenStore.kt` — AES-GCM encrypted token envelope using Android Keystore; plaintext is not persisted in SharedPreferences.
- `LocalRuntimeBridgeClient.kt` and `JarvisViewModel.kt` — API v2 handshake, Bearer header, token load/save/forget and fail-closed UI state.
- `MainActivity.kt` — V0.5 token entry and masked input.
- `tools/jarvis_app/static/app.js`, `index.html`, `styles.css`, `sw.js` — PWA sign-in panel, bearer request headers, current-tab sessionStorage and refreshed static cache version.
- Android bridge, app, local-console and architecture documents plus `AGENTS.md` synchronized to the v2 auth contract.

**Архитектурные решения:**
- The API health endpoint remains unauthenticated but returns no task/session details.
- Token possession is local transport access control; it is not trusted human identity, exact-request approval, signed grants, durable policy or authorization for side effects.
- External actions, tool execution and remote access remain disabled. No Task Loop or ActionExecutor route was added.
- Old V0.4 clients expect API v1 and must be updated/rebuilt before they can use the API v2 console.

**Verification:** latest source files and documentation were fetched back from GitHub and inspected statically. No runtime auth/HTTP/crypto test or Android-to-Termux test was run.

**Tests / builds:** 11 Python console tests are AUTHORED / NOT RUN; 9 Android local draft tests remain AUTHORED / NOT RUN. No Kotlin test, Gradle sync/build, APK/AAB, install, CI or GitHub Actions were run. The repository has no Gradle wrapper.

**Limitations / next stage:** Android token storage and UI still have no dedicated tests. The local token does not establish a human identity or exact action approval. Next implement and independently test a trusted approval provider, persistent policy/revocation, and executor-owned durable idempotency/reconciliation before any side effects.


## 2026-11-12 — V0.5 pairing polish

- Android application metadata advanced to `versionCode = 2`, `versionName = "0.5.0"`.
- PWA now provides an explicit sign-out control; logout removes the bearer token from tab sessionStorage and stops its periodic state refresh. Its service-worker shell cache was advanced to v4.
- Static source readback confirmed the new version metadata, Android Keystore token store, API v2 bearer header, PWA login/logout controls and cache version. This is not a runtime or device test; all build/test prohibitions remain in force.


## 2026-11-12 — Persistent Safety Policy Registry V1 reference

**Цель:** устранить отсутствие постоянного снимка SafetyPolicy и записи отозванных approval IDs, не выдавая caller-supplied approvals за доверенную identity.

**Добавлено:**
- tools/neuron_runtime/policy_registry.py — canonical JSON policy snapshot, schema validation, policy/state digests, private-directory/file requirements, POSIX process lock, temp-write + fsync + atomic replace, registry record CAS, monotonic policy versioning and bounded approval-ID revocation list.
- PersistentPolicySafetyGate — subclass PermissionSafetyGate; требует exact match переданной policy с durable snapshot, fail-closed при missing/corrupt/mismatch и блокирует revoked approval IDs. Compatible with the current JARVISTaskLoop constructor's PermissionSafetyGate type check.
- tools/neuron_runtime/test_policy_registry.py — 17 focused tests for persistence, permissions, CAS, policy version monotonicity, revocation, integrity/schema/path rejection, fail-closed decisions and Task Loop injection. AUTHORED / NOT RUN.
- docs/JARVIS_PERSISTENT_POLICY_REGISTRY_V1.md, Gate/Task Loop contracts, architecture §3.50 and README repository map.

**Граница доверия:** это local POSIX file reference store, не JPVR service. SHA-256 state digest защищает от случайного повреждения, но не от владельца файла, способного переписать JSON и пересчитать digest. ApprovalGrant остаётся caller-supplied; подписи grant и identity approver не проверяются. Не подключать к внешнему executor как production authorization.

**Проверки:** GitHub readback/static review only. 17 test methods AUTHORED, NOT RUN. No Python imports/tests, filesystem fault injection, multi-process race testing, Termux runtime check, Android build, APK/AAB, CI or GitHub Actions were run.


## 2026-11-12 — Durable Executor Idempotency Ledger V1 reference

**Цель:** дать Task Loop устойчивый локальный invocation/result journal для одного ActionExecutor key и закрыть автоматический повтор delegate после неоднозначного результата.

**Добавлено:**
- tools/neuron_runtime/executor_idempotency.py — SQLite-backed DurableIdempotencyExecutor; canonical digest полного authorization intent + PlanningAction; commit IN_PROGRESS claim до delegate call; сохранение SUCCEEDED/FAILED_NO_EFFECT payload; replay final result; fail-closed UNKNOWN для unresolved key; ручная запись authoritative reconciliation result.
- SQLite controls: BEGIN IMMEDIATE, busy timeout, synchronous=FULL, owner-only directory/database, symlink refusal, 256 records maximum V1, 128 KiB result bound, 16,384-page DB cap.
- tools/neuron_runtime/test_executor_idempotency.py — 18 focused tests, AUTHORED / NOT RUN.
- Public exports through tools.neuron_runtime.__init__.
- docs/JARVIS_EXECUTOR_IDEMPOTENCY_LEDGER_V1.md, architecture §3.51, Task Loop contract, README map and AGENTS safety gate synchronized.

**Гарантия и границы:** wrapper returns cached known results and never redispatches an existing IN_PROGRESS/UNKNOWN key. It does not guarantee exactly-once behavior at a downstream service. A crash can leave an unresolved claim before the side effect; an independent trusted reconciliation provider is needed. A caller-supplied reconciliation_ref is an audit string, not proof. No ActionExecutor was connected to an external system and no Android execution route was added.

**Проверки:** GitHub source readback/static review only. 18 tests AUTHORED, NOT RUN. No Python import/test run, multiprocess/fault-injection test, downstream integration, Termux runtime, Android build, APK/AAB, CI or GitHub Actions.


### Durable executor integration follow-up

- Added an integration scenario to `tools/neuron_runtime/test_task_loop.py`: delegate applies the first step but loses its reply; SQLite wrapper returns UNKNOWN without a second delegate call; after recording an independently verified result, Task Loop resumes and executes the next step.
- Task Loop focused test count is now 21 (AUTHORED / NOT RUN). This is an authored integration regression, not evidence of a passing runtime test.


### Unresolved executor key restart regression

- Added `test_unresolved_key_survives_reopen_without_redispatch`: a fresh DurableIdempotencyExecutor sees the persisted UNKNOWN key and returns UNKNOWN without invoking its new delegate.
- Executor ledger suite: 19 methods; AUTHORED, NOT RUN.


### Executor ledger bounded diagnostics

- Added `DurableIdempotencyExecutor.list_unresolved(limit=50)` for ordered bounded metadata inspection of IN_PROGRESS/UNKNOWN records, and `state_counts()` for state totals/capacity.
- Added two focused tests for state counts, unresolved ordering, invalid limits and non-disclosure of action payloads.
- Executor ledger test inventory: 21 methods, AUTHORED / NOT RUN.


### Combined persistent Task Loop reference composition

- Added a Task Loop integration test that injects `PersistentPolicySafetyGate` and `DurableIdempotencyExecutor` together. It runs a bounded read-only two-step plan, then checks both executor results are durably recorded and the policy snapshot stays at the expected record version.
- Task Loop focused inventory is now 22 methods, AUTHORED / NOT RUN. The new integration test has not been executed.

## 2026-11-13 — Local Console Authentication Regression Coverage

**Цель:** добавить отдельные регрессионные проверки к bearer-token механизму, который защищает Android/PWA bridge API v2.

**Добавлено:**
- `tools/jarvis_app/test_auth.py` — 7 focused tests: одноразовая выдача токена, хранение только SHA-256 digest, права 0700/0600, повторное открытие без повторной выдачи, ротация и отзыв старого токена, отказ для malformed token/digest, запрет symlink auth-файла и точная проверка токена.
- Тесты проверяют только локальный механизм хранения/проверки токена; они не утверждают, что bearer token является подтверждением личности человека или одобрением конкретного действия.

**Проверки:** source readback/static inspection only. Новые 7 тестов AUTHORED / NOT RUN. Не запускались Python tests, Android build, APK/AAB, CI или GitHub Actions.


## 2026-11-13 — Consolidated JARVIS project handoff

**Цель:** сохранить сквозной контекст проекта и актуальные границы реализации в репозитории, чтобы дальнейшая работа могла продолжаться без потери архитектурных решений и без повторного объявления уже реализованных компонентов.

**Добавлено:** docs/JARVIS_PROJECT_HANDOFF_2026-11-13.md — единая сводка направления проекта, neural/JPVR/task-session/reasoning/safety/task-loop слоёв, Android V0.5 и Python local console, trust boundaries, verification status, последовательности следующих этапов и рабочего протокола.

**Зафиксировано:** Android V0.5 source включает API v2 Bearer auth, Android Keystore token envelope, PWA sign-in/out, JPVR task/session administration и bounded symbolic reasoning preview. Это source-level implementation; runtime auth, Android↔Termux I/O, Kotlin/Gradle, APK и device verification не подтверждены. Task Loop, trusted human approval, signed exact-action grants, trusted policy authority, downstream idempotency/reconciliation и neural runtime integration в Android остаются незавершёнными. Внешние действия должны оставаться выключенными.

**Ограничение проверки:** документационная синхронизация и GitHub readback only. Тесты, сборки, APK/AAB, CI и GitHub Actions не запускались. Никаких новых runtime возможностей эта запись не создаёт.

**Следующий этап:** следовать порядку в handoff: сначала тестируемость и закрытие auth/client gaps, затем trusted identity + signed exact-request approval, trusted policy/revocation и downstream reconciliation; только потом read-only Task Loop preview и строго ограниченный executor.

## 2026-11-13 — Local console authentication path hardening

**Цель:** закрыть ошибки работы с файловой системой вокруг bearer-token digest и гарантировать, что ротация токена не может перезаписать JPVR-файл при совпадении путей.

**Изменено:**
- `tools/jarvis_app/auth.py` — проверка owner/type родительского каталога; отказ от symlink/dangling-symlink для каталога и digest-файла; чтение через file descriptor с `O_NOFOLLOW` где поддерживается и проверками `fstat`; исправление слишком широких прав; fsync digest и best-effort fsync каталога после atomic replace.
- `tools/jarvis_app/server.py` — preflight сравнение resolved paths и existing file identity для JPVR data/auth paths; одинаковый файл и hard-link alias отклоняются до открытия JPVR storage, включая `--rotate-token`.
- `tools/jarvis_app/test_auth.py` — добавлены 2 регрессионных теста для symlink-parent и dangling-symlink rejection; всего 9 тестов.
- `tools/jarvis_app/test_server.py` — добавлены 2 регрессионных теста: data/auth path collision cannot overwrite JPVR; every POST endpoint rejects a missing token before processing; всего 13 тестов.
- Обновлены `README.md`, `docs/JARVIS_ANDROID_RUNTIME_BRIDGE_V1.md`, `docs/JARVIS_LOCAL_CONSOLE_V1.md` и архитектура §3.49.

**Проверки:** GitHub source readback/static cross-file review only. 22 Python auth/API tests are **AUTHORED, NOT RUN**. Не запускались Python tests/import/syntax execution, filesystem fault injection, Android Keystore/device tests, Android↔Termux end-to-end networking, Gradle/build/APK/AAB, CI или GitHub Actions.

**Ограничения:** локальный bearer token остаётся только transport access control, не удостоверяет человека и не является approval. Проверки владельца/прав/симлинков не защищают файлы от злонамеренного владельца того же Android UID; directory fsync является best-effort для совместимости файловых систем. Android Keystore и токенизация не проверялись runtime. Внешние действия, Task Loop и ActionExecutor остаются выключенными.

**Следующий шаг:** добавить тесты Android token store в подходящем instrumentation harness и проверить полный authenticated bridge на Termux/Android без включения side effects.

## 2026-11-13 — Android Keystore token-store instrumentation tests authored

**Цель:** добавить device-level tests для реального Android Keystore, поскольку JVM unit tests не могут подтвердить поведение AndroidKeyStore/AES-GCM.

**Изменено:**
- `app/build.gradle.kts` — добавлен AndroidJUnitRunner и androidTest dependencies для AndroidX Test core/runner/JUnit.
- `app/src/androidTest/java/org/jarvis/ans100/alpha/data/RuntimeAccessTokenStoreTest.kt` — 7 instrumentation tests: round-trip после recreation и отсутствие plaintext token в preferences; clear; randomized GCM IV on each save; malformed-envelope fail-closed + cleanup; invalidation after Keystore-key deletion; invalid token rejection before persistence; non-exportability of Android Keystore key.
- Обновлены README, Android App V0.5, Runtime Bridge и architecture §3.49.

**Проверки:** source readback/static dependency and test-inventory review only. Семь Android Keystore instrumentation tests **AUTHORED, NOT RUN**. Gradle sync, Kotlin compile, connectedAndroidTest, emulator/device execution, APK/AAB, Android↔Termux end-to-end test, Python tests, CI и GitHub Actions не запускались.

**Ограничения:** реальные AES-GCM/Android Keystore assertions остаются непроверенными до запуска instrumentation suite на Android API 29+ устройстве/эмуляторе. UI flows и полное соединение с Termux также не проверены. Внешние действия остаются выключенными.

**Следующий шаг:** пользователь может отдельно разрешить запуск instrumentation tests/build; до этого оставлять результат как AUTHORED, NOT RUN.

### Local-console authentication regression inventory follow-up

Added one further test to each focused module after reviewing the new path protections:
- `test_auth.py` now has 10 methods; the new case verifies that an existing owner-controlled digest and parent directory with broad permissions are repaired to 0600/0700 before use.
- `test_server.py` now has 14 methods; the new case verifies that a hard link to the JPVR data file is rejected as an auth path even during token rotation, without changing file bytes.

The combined Python authentication/API inventory is now **24 methods AUTHORED, NOT RUN**. README, Android/bridge/local-console contracts and architecture §3.49 were updated to match. GitHub source readback only; no tests, imports/syntax execution, builds, APK/AAB, CI or device checks were run.

## 2026-11-13 — Encrypted offline workspace transfer V1

**Цель:** дать Android и Windows/desktop один переносимый формат памяти без облака, без открытия локального API в LAN и без автоматической перезаписи существующих данных.

**Изменено:**
- `schemas/workspace/v1/encrypted-bundle.schema.json` — зафиксирован envelope V1: PBKDF2-HMAC-SHA256, 310 000 итераций, случайная соль 16 байт, AES-256-GCM, nonce 12 байт, tag 128 бит и Base64-кодирование.
- `tools/jarvis_app/static/app.js` и `static/index.html` — desktop PWA экспортирует `.jws` через WebCrypto и импортирует его после проверки GCM-тега, канонического workspace JSON, SHA-256, лимитов и схемы. Перед записью отображаются счётчики; пользователь может подтвердить или отменить импорт.
- Android `WorkspaceTransferCrypto.kt` — добавлен соответствующий PBKDF2/AES-GCM codec с UTF-8 passphrase bytes, фиксированным AAD, ограничениями размера и очисткой временных byte/char buffers best-effort.
- `LocalRuntimeBridgeClient.kt` — добавлены decrypt/validate, merge planning и CAS commit. Записи объединяются по стабильному ID, настройки по key; одинаковые записи пропускаются, конфликт оставляет локальную версию.
- `JarvisViewModel.kt` и `MainActivity.kt` — подключён Android Storage Access Framework для `.jws` CreateDocument/OpenDocument; импорт разбит на preview и отдельное подтверждение. Изменение локальной ревизии между preview и commit приводит к отказу CAS, а не к перезаписи.
- `workspace_store.py` и `workspace.schema.json` — числовые значения настроек ограничены JSON safe-integer диапазоном ±9,007,199,254,740,991 для одинакового представления в Python, JavaScript и Kotlin.
- `test_workspace_store.py` — добавлен тест отклонения integer setting вне safe-integer диапазона; workspace-store inventory теперь 11 методов.
- `app/src/androidTest/java/org/jarvis/ans100/alpha/data/WorkspaceTransferCryptoTest.kt` — 4 instrumentation test cases: UTF-8 round-trip, фиксированный PBKDF2/AES-GCM fixture с полным каноническим workspace V1 и проверяемым SHA-256, неверная парольная фраза и изменение ciphertext.
- `tools/jarvis_app/static/sw.js` — cache version повышен до v6, чтобы уже установленная PWA получила новый transfer UI вместо старого app.js из cache.
- Синхронизированы README, Android Runtime Bridge, Shared Workspace Memory и Cross-Platform Runtime docs.

**Правило импорта:** workspace_id должен совпасть. Новые записи добавляются; одинаковые записи не дублируются; конфликты не перезаписывают локальную версию. Пароль не передаётся local API и не сохраняется. Файл и пароль нужно переносить разными каналами. Автоматической синхронизации, pairings и cloud transport нет.

**Проверки:** GitHub source readback и статическая межфайловая проверка. Python workspace-store tests (11) и Android instrumentation crypto tests (4) — **AUTHORED, NOT RUN**. Не запускались Python tests, Kotlin/Gradle compile, Android build/APK/AAB, browser/device E2E, Windows 10/11 runtime verification, CI или GitHub Actions. Межплатформенный fixture ещё не подтверждён выполнением на обоих runtime.

**Ограничения:** реализация существует на уровне исходного кода. Android SAF, криптография на реальном Android, desktop WebCrypto, round-trip между Android и desktop, обработка крупных файлов и реальный CAS-конфликт требуют runtime-проверки. Передача ручная; файлы на телефоне и PC остаются раздельными до импорта.

**Следующий этап:** после разрешения пользователя выполнить отдельную проверку на Android и desktop без включения внешних действий; прежде этого — не заявлять runtime-совместимость как доказанную.


## 2026-11-13 — Encrypted workspace backup and guarded restore V1

**Цель:** добавить отдельный зашифрованный point-in-time backup общей памяти для Android и PC, не смешивая его с merge-переносом JWS и не представляя его как восстановление сырого JPVR-файла.

**Изменено / добавлено:**
- Добавлены `schemas/workspace/v1/workspace-backup.schema.json` и `schemas/workspace/v1/encrypted-backup.schema.json`. JWB payload и envelope имеют отдельные versioned markers; backup encryption domain отделён от JWS purpose-specific AAD.
- `tools/jarvis_app/server.py` — добавлен authenticated `POST /api/workspace/restore`; маршрут требует ровно `expected_revision` и полный `workspace`, использует существующую schema/integrity validation, сохраняет workspace identity и публикует полную замену через JPVR CAS. Лимит 16 MiB применяется и к save, и к restore routes.
- `tools/jarvis_app/test_server.py` — добавлены два regression tests: большой restore payload (70,000-byte message) с полной заменой и stale CAS/auth checks; отказ для foreign workspace ID и tampered SHA-256. Инвентарь теперь 18 методов, AUTHORED / NOT RUN.
- `tools/jarvis_app/static/index.html`, `static/app.js`, `static/sw.js` — desktop PWA получил JWB export, local decrypt/validation preview, отдельное подтверждение backup текущей ревизии, destructive restore confirmation, CAS commit и fail-closed invalidation после stale/ambiguous response. В WebCrypto best-effort обнуляются временные password/plaintext/salt/nonce/ciphertext byte arrays. Service-worker cache увеличен до v8 после исправления PWA download lifecycle.
- `app/src/main/java/org/jarvis/ans100/alpha/data/WorkspaceTransferCrypto.kt` — добавлены encrypt/decrypt APIs для JWB с отдельным marker/AAD, сохраняя прежний JWS contract.
- `app/src/main/java/org/jarvis/ans100/alpha/data/LocalRuntimeBridgeClient.kt` — добавлены JWB payload creation/validation, nested + wrapper digest checks, same-workspace validation, collection-count preview и CAS replacement call.
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` и `MainActivity.kt` — добавлены backup revision evidence, Android SAF create/open, local preview, required acknowledgement, second confirmation, restore state invalidation and error path. Memory mutations are disabled while destructive restore preview is active.
- `app/src/androidTest/java/org/jarvis/ans100/alpha/data/WorkspaceTransferCryptoTest.kt` — добавлены проверки JWB/JWS cryptographic domain separation, wrong passphrase, tampered ciphertext и passphrase cleanup. Теперь 9 instrumentation methods, AUTHORED / NOT RUN.
- Добавлен `docs/JARVIS_WORKSPACE_BACKUP_RESTORE_V1.md` и обновлены схемы, README, architecture §3.52, Android App/Runtime Bridge, Shared Workspace Memory, Cross-Platform Runtime, PC Quick Start, Local Console и project handoff.
- `AGENTS.md` и project handoff теперь указывают `mobile-first-offline-v1` как текущую авторизованную ветку и запрещают изменение `main` без отдельного явного разрешения.

**Формат и гарантии:**
- JWB uses PBKDF2-HMAC-SHA256 (310,000 rounds), random 16-byte salt, AES-256-GCM, 12-byte nonce, 128-bit tag and backup-only AAD `JARVIS-WORKSPACE-BACKUP-ENCRYPTED-V1`.
- Restore is full replacement, not merge. It validates encrypted envelope, canonical JSON, backup wrapper SHA-256, nested workspace SHA-256, schema/limits and same workspace ID before write.
- Both interfaces require the user to save a separate current-revision backup, acknowledge it and confirm replacement. Server independently validates and uses expected revision CAS.
- JWB contains only portable workspace collections. It excludes task/session JPVR, Android drafts, tokens, Android Keystore keys, binaries and neuron/network checkpoints. It cannot repair a corrupt workspace JPVR file that cannot be opened; preserve the original and use the separate JPVR recovery-copy reference flow.

**Проверки:** GitHub source readback and static cross-file review only. Python `test_server.py` (18) and Android `WorkspaceTransferCryptoTest.kt` (9) are **AUTHORED, NOT RUN**. Existing Python auth (11), workspace-store (11), Android Keystore (7) and Android local repository (9) suites remain as recorded in their contracts. No Python tests, Kotlin compilation, Gradle sync/build, Android APK/AAB, SAF/device run, desktop browser flow, Windows 10/11 execution, cross-platform JWB interoperability, CI or GitHub Actions were run.

**Ограничения:** implementation is source-level only. Browser download retention cannot be proven by the page; desktop requires explicit user acknowledgement after download initiation. Android marks a backup written only after its SAF output stream closes, then still requires checkbox acknowledgement. The backup is not a raw JPVR file image. A stale or ambiguous restore response clears its preview and requires refresh/reconciliation before another attempt. No live pairing or sync exists.

**Следующий этап:** получить отдельное разрешение на focused runtime/device/browser verification of JWB/JWS and bridge behavior. After evidence, continue with trusted approver identity, signed exact-request grants, trusted policy/revocation source and downstream idempotency/reconciliation. Keep all external actions disabled.

## 2026-11-13 — Deterministic desktop-to-Android JWB interoperability fixture

**Цель:** дополнить JWB crypto coverage проверкой фиксированного envelope, созданного по desktop WebCrypto contract, а не только Android self-round-trip и cross-format rejection.

**Изменено:**
- `app/src/androidTest/java/org/jarvis/ans100/alpha/data/WorkspaceTransferCryptoTest.kt` — существующий `encryptedBackupUsesSeparateEnvelopeAndReadsPortableWebCryptoFixture` теперь дополнительно расшифровывает детерминированный desktop-compatible JWB fixture с UTF-8 русским содержимым, PBKDF2-HMAC-SHA256/310,000, фиксированными salt/nonce и backup AAD `JARVIS-WORKSPACE-BACKUP-ENCRYPTED-V1`; проверяется точное каноническое plaintext содержимое и оба SHA-256 digest.
- Число Android transfer/backup instrumentation methods не изменилось: **9**. Добавлен ещё один вектор в уже существующий метод.
- README, Android App, Android Runtime Bridge, Cross-Platform Runtime и Workspace Backup & Restore docs указывают на JWS/JWB fixture coverage.

**Проверки:** GitHub readback/static review only. Девять Android instrumentation methods остаются **AUTHORED, NOT RUN**. Сам fixture vector был подготовлен локальным коротким криптографическим генератором для фиксированных входов; это не запуск Android test и не browser runtime verification. Kotlin/Gradle, Android device, desktop browser, Python tests, builds, CI и GitHub Actions не запускались.

**Ограничения:** cross-platform behavior остаётся source-level до фактического запуска этого fixture в Android instrumentation и до независимого browser WebCrypto round-trip на desktop. Внешние действия JARVIS остаются выключенными.


### Backup/restore hardening follow-up — 13 November 2026

- Android `WorkspaceTransferCrypto.kt` now clears decoded salt, nonce and ciphertext arrays even when malformed metadata fails before AES-GCM starts; invalid Base64 decode buffers are cleared before rejection. The JWS/JWB formats and KDF/cipher parameters are unchanged.
- Android `MainActivity.kt` resets the destructive confirmation-dialog state when the restore preview changes, disables memory creation during that preview, and clears pending backup-revision state when the Storage Access Framework save dialog is cancelled.
- Desktop `app.js` delays object-URL revocation after initiating JWS/JWB download and clears the prior-backup acknowledgement after reconciling an ambiguous restore as successfully applied.
- PWA service-worker shell cache is now `jarvis-local-console-v8`.
- No test inventory changed: Python service/API 18, Python auth 11, Android transfer/backup crypto 9, Android Keystore 7, Android local draft 9 are AUTHORED / NOT RUN as applicable. Static GitHub source readback only; no tests, builds, browser execution, device runs, CI or Actions were performed.


## 2026-11-13 — Signed exact-request approval verifier V1

**Цель:** устранить ключевой пробел legacy Permission & Safety Gate: объект ApprovalGrant был привязан к запросу и сроку, но не содержал проверяемого доказательства того, какой доверенный approver его подписал. Добавить отдельный fail-closed Ed25519 режим без включения внешнего исполнения.

**Добавлено / изменено:**
- `tools/neuron_runtime/signed_approval.py` — добавлены immutable `ApprovalTrustKey` / `ApprovalTrustStore`, `SignedApprovalEnvelope`, domain-separated canonical signing bytes, strict UTF-8 JSON encode/decode и Ed25519 signature verification. Подпись покрывает schema/version/algorithm, key/approver IDs, UTC issue/expiry и полный ApprovalGrant. Проверяются approver-to-key binding, key status/validity, signature, expiry, не более 30 секунд допустимого future skew и lifetime не более 5 минут. Envelope ограничен 32 KiB, повторяющиеся и неизвестные JSON поля отклоняются. Если optional `cryptography` package отсутствует, проверка завершается fail-closed; криптографического fallback нет.
- `TrustedApprovalSafetyGate` — добавлен отдельный gate, принимающий только подписанный envelope; unsigned ApprovalGrant отклоняется. Существующий `PermissionSafetyGate` оставлен как legacy reference API, чтобы не менять смысл старых unit/reference сценариев.
- `tools/neuron_runtime/task_loop.py` — approvals mapping теперь принимает SignedApprovalEnvelope наряду с legacy ApprovalGrant; фактический режим enforcement определяется внедрённым gate. В trusted режиме `now_ns` должен быть UTC Unix nanoseconds.
- `tools/neuron_runtime/__init__.py` — экспортированы новые типы, decoder/encoder и verifier.
- `tools/neuron_runtime/test_signed_approval.py` — добавлено 16 методов: valid Ed25519 signature, signature tamper, изменение любого grant-поля, unknown/revoked/mismatched key, expiry, future clock skew, key validity, запрет unsigned grant, отсутствие approval, JSON round-trip, duplicate/unknown JSON fields, неверная длина signature и duplicate key IDs. **AUTHORED, NOT RUN.**
- Добавлены `schemas/approval/v1/signed-approval.schema.json` и optional dependency manifest `requirements-trusted-approval.txt`.
- Обновлены README, Permission & Safety Gate V1, Task Loop V0.1, architecture §3.53 и project handoff.

**Точные границы контракта:**
- Signature algorithm: Ed25519; raw public key 32 bytes; signature 64 bytes; JSON signature — unpadded Base64URL, 86 characters.
- Canonical payload is sorted compact ASCII JSON with domain separator `JARVIS:SIGNED-APPROVAL:V1\\0`; signature covers all envelope fields except the signature itself.
- Intent/policy digests and full ApprovalGrant bind the grant to task/session revisions, plan/step/action, capability, scope, idempotency key, policy identity/digest, resource budgets, effect, retry index and expiry. Task Loop plan ID commits to the canonical structured plan/action bindings.
- Legacy unsigned gate remains available for reference tests only. The Android/PWA UI and local-console API do not yet create or consume signed approval envelopes.

**Проверки:** GitHub branch readback and static cross-file/source review only. 16 tests in `test_signed_approval.py` are **AUTHORED, NOT RUN**. No Python imports/tests, cryptographic runtime checks, Termux execution, Android Keystore, desktop signer, Gradle/build/APK/AAB, browser/device run, CI or GitHub Actions were run.

**Ограничения / security gaps still open:**
- The verifier trusts only keys in the supplied in-memory trust snapshot; there is no authenticated enrollment/root, signed trust bundle, persistent key rotation/revocation, monotonic anti-rollback counter or secure recovery.
- `trust_epoch` is metadata only; it does not prevent a caller from rolling back a trust snapshot.
- No platform private-key signer exists. Android needs non-exportable Android Keystore signing with explicit user-authenticated approval; desktop needs a protected signer and enrollment path.
- The verifier does not itself prove a human saw the approval UI, consume approval IDs across processes, reconcile external outcomes or enable an executor. External actions, tools and remote access remain disabled.
- Cross-platform signature interoperability and Python cryptography availability on the target Termux/Python environment have not been measured.

**Следующий этап:** design and implement secure approver enrollment/key provisioning and signed trust-store updates with rotation, revocation and anti-rollback; only after that integrate the Android user-confirmation signer and test signed envelope interoperability on Android and PC.


## 2026-11-13 — Android in-process local conversation foundation V0.1

**Objective:** begin delivering a usable Android-local JARVIS interaction that does not depend on Termux, a loopback Python server or internet; keep the response backend replaceable and disclose that a real offline LLM is not yet connected.

**Added:**
- app/src/main/java/org/jarvis/ans100/alpha/data/LocalConversationRuntime.kt — USER/ASSISTANT message domain, strict bounded V1 history codec, repository, LocalResponseEngine interface, deterministic LocalFallbackResponseEngine and LocalConversationCoordinator.
- app/src/main/java/org/jarvis/ans100/alpha/data/SharedPreferencesConversationStorage.kt — app-private durable history adapter.
- app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationRuntimeTest.kt — eight focused tests (AUTHORED, NOT RUN).
- docs/JARVIS_ANDROID_IN_PROCESS_CONVERSATION_V0_1.md — implementation contract and explicit limitations.

**Changed:**
- JarvisViewModel.kt — load/send/clear conversation operations, StateFlow state and background-executor dispatch.
- MainActivity.kt — local conversation panel, explicit no-LLM backend label and confirmed local-history deletion.
- docs/JARVIS_ARCHITECTURE.md — new §3.54.
- docs/JARVIS_PROJECT_HANDOFF_2026-11-13.md — next-stage priority changed from signer infrastructure to local model + cross-platform conversation continuity.
- README.md and AGENTS.md — status, file map and current Android boundary corrected.

**Behavior and limits:**
- Android local chat path is independent of the Termux bridge and network. It supports deterministic greetings, /help and /status; unsupported prompts explicitly disclose that an LLM is not installed.
- History is bounded to 120 messages, 4,000 characters per message and 1 MiB encoded state. Exchanges are persisted as complete user/assistant pairs; invalid persisted data is rejected.
- Android-local chat is separate from the shared Python/JPVR workspace and is not yet included in .jws/.jwb, the PC PWA or cross-device sync. It is app-private but not separately encrypted by this feature.
- No neural-runtime integration, model download, voice, natural-language task parsing or action execution was added. External actions remain disabled.

**Verification:** current branch/file readback, static source inspection, and final GitHub readback only. The eight Kotlin tests are **AUTHORED, NOT RUN**. Gradle sync/build, APK/AAB, Android compilation/install, Termux-to-app or airplane-mode runtime checks, performance/RAM/battery measurements, Python tests, CI and GitHub Actions are **NOT RUN**. No build or test command was issued.

**Next meaningful step:** evaluate a real compact offline model on the target phone and implement one backend behind LocalResponseEngine; then unify portable conversation history with the PC workspace contract.


## 2026-11-14 — Repository-wide source audit and offline LLM status alignment

**Цель:** сверить всю структуру текущей рабочей ветки после добавления LiteRT-LM, выявить расхождение между фактическими исходниками и статусом в архитектуре/README/handoff, а также зафиксировать реальные границы Android/PC conversation paths.

**Объём аудита:** получено полное Git tree рабочей ветки без truncation (222 файла: app, tools, docs, schemas и root configuration); сверены branch head, сравнение с main, последние commit summaries, Android model store/response engine/ViewModel/Compose UI, desktop inference adapter/server/PWA endpoints, portable workspace path, тестовые inventories, handoff и основные platform contracts. История и исходники просмотрены статически; это не построчная проверка каждого из 222 файлов и не runtime audit.

**Изменено в этом documentation-only batch:**
- AGENTS.md — current snapshot обновлён: optional Qwen3/LiteRT-LM adapter существует на уровне исходника; fallback остаётся при отсутствии модели; отдельно указаны ограничения хранения и отличие LLM от ANS-100.
- README.md — current status и platform summary отражают Android/desktop adapters, ручную установку модели, раздельные хранилища истории и отсутствие runtime verification.
- docs/JARVIS_ARCHITECTURE.md — уточнён §3.54 и добавлен §3.55 с контрактом LiteRT-LM, лимитами, storage boundaries, проверками и следующими задачами.
- docs/JARVIS_MOBILE_FIRST_OFFLINE_RUNTIME_V1.md — Phase C и ближайшие приоритеты переведены в фактическое состояние: adapter source implemented, acceptance not yet met.
- docs/JARVIS_CROSS_PLATFORM_RUNTIME_V1.md — Batch 5 и критерий доступности backend приведены к текущему source-level состоянию.
- Никакой source code, runtime dependency, model artifact или test code не менялись в этом batch.

**Подтверждённая реализация на исходном уровне:** Android использует LiteRT-LM 0.17.1, вручную импортируемый Qwen3-0.6B INT4 no-think model file, CPU backend, context ceiling 1,280 tokens, output ceiling 96 tokens и доступную RAM guard 1,280 MiB. Desktop/PWA использует pinned litert-lm-api 0.17.1, CLI --model, bounded prompt/history/output и authenticated conversation endpoints с workspace revision CAS. Модель не bundled и не auto-downloaded. Вызовы не включают automatic tool calling.

**Существенные найденные ограничения:**
- Android conversation остаётся в app-private SharedPreferences; desktop conversation сохраняется в portable JPVR workspace. Android chat пока не попадает в JWS/JWB, поэтому общая история между телефоном и PC отсутствует; live sync/pairing также нет.
- Импорт Android проверяет имя и размер файла, но не publisher digest / содержимое модели.
- В текущем adapter stage нет streaming/cancellation. No-think Qwen adapter не заменяет Reasoning Engine, Memory Retrieval или ANS-100 NetworkRuntime; offline voice не реализован.
- Восемь Android tests относятся к общему repository/coordinator, а не к LiteRT model store/engine. Четыре desktop adapter tests используют fake runtime. Оба набора **AUTHORED, NOT RUN**.
- Нет измерений Android/PC RAM, скорости, first-token latency, батареи/температуры или русского качества; наличие исходников не доказывает runtime compatibility.

**Проверки:** GitHub branch/tree/file readback, commit metadata, compare with main и статический cross-file review. Build/test commands не запускались. Python/Kotlin tests, Gradle sync/build, APK/AAB, actual model load/inference, Android device, desktop browser/PWA, airplane mode, CI и GitHub Actions — **NOT RUN**. Executable tests were not applicable to this documentation-only alignment batch.

**Ветки:** изменения планируются только в mobile-first-offline-v1. На момент аудита branch head был 2b2adf83a5d3817fbdc0c46b86c3345ec7cb7c99; main был f8d21607af468e92abfcf1902a915511b2c940c6. Main не изменялся.

**Следующий meaningful step:** интегрировать Android conversation records с portable shared workspace/JWS/JWB contract, сохранив локальный путь; затем добавить model-integrity/cancellation/test coverage и запросить отдельное разрешение на измерение фактической работы модели на телефоне и PC.

## 2026-11-14 — JARVIS Core orchestration V1 (source implementation)

- Added an explicit conversation orchestrator on Android and desktop/PWA. The core owns the turn boundary, validates user input and model output, routes deterministic `/help` and `/status` commands, and persists complete exchanges; the language model remains a text-generation component.
- Android now labels the backend as `JARVIS Core → …` and reports the actual integration boundaries. Recent local conversation history is used as context. Android JPVR long-term memory, automatic Reasoning Engine invocation, and ANS-100 network invocation are still not integrated into the chat loop.
- Desktop/PWA now retrieves up to three relevant saved workspace memories using bounded lexical overlap and supplies them to the language model as explicitly untrusted reference data. This is keyword retrieval, not semantic/vector search.
- Desktop capability status exposes model/runtime availability without claiming inference verification. Reasoning remains a separate symbolic preview; ANS-100 NetworkRuntime remains separate; external actions stay disabled.
- Updated model instructions to describe the LLM as JARVIS's language component, not the entire system.
- Added Android and Python regression tests. Tests/builds/device inference were **not run**; changes are source-level only and require verification on Android and desktop.

## 2026-11-14 — JPVR Android recall and ANS-100 signal pre-pass V1 (source implementation)

- Android JARVIS Core now retrieves up to three approved JPVR workspace memories using bounded lexical overlap (2,000-record in-memory cache, 700 characters per cached memory, 1,800 total supplied context characters). The cache is populated only after an authenticated workspace read, confirmed import, or confirmed restore; explicitly removing the bridge token clears it. Without a loaded workspace snapshot, chat continues with local conversation history only.
- LiteRT-LM Android receives selected memories as explicitly untrusted reference notes. The original response-engine interface remains backward compatible; the deterministic fallback does not pretend to reason over notes when the language model is unavailable.
- Added `tools/neuron_runtime/conversation_signal.py`: a real, bounded two-neuron ALIF-DT pass built on the existing `NeuronRuntime`, `NetworkRuntime`, and Numerical Oracle. It processes normalized token volume, lexical diversity, and question punctuation, then routes a source spike through an attention-gate neuron.
- Desktop/PWA JARVIS Core invokes this pass during each language turn and provides only its numeric summary to the language engine. Android requests the same pass through a new authenticated loopback endpoint in the Termux service with a short timeout; an unavailable service is fail-open and does not block local chat.
- This is signal preprocessing only: no semantic understanding, no dialogue-driven learning, no persistent neural checkpoint, no automatic Reasoning Engine call, and no external actions. ANS-100's full cognitive integration remains incomplete.
- Added Python and Android regression tests, including an authenticated endpoint contract and tests for bounded recall and fail-open behavior. Tests/builds/device inference were **not run**; source-level implementation only.

## 2026-11-14 — Persistent ALIF-DT conversation checkpoint V1 (source implementation)

- Converted the ANS-100 conversation signal pass into a stateful two-neuron ALIF-DT network. Adaptive threshold state is retained across user turns; model time advances by a bounded 10 ms step per processed turn.
- Added a separate `ans100-conversation.jpvr` container owned by the Termux/desktop local-console service. The full NetworkRuntime checkpoint is loaded on service start and committed after processed turns with JNNW record-version compare-and-swap. The neural checkpoint is deliberately separate from the task/session JPVR and portable workspace JPVR.
- The first real input event creates checkpoint version 1; a blank-feature short input does not create a false "persisted" claim. Failed save leaves the candidate network unpublished and the optional neural path fails open.
- The checkpoint stores model parameters, adaptive neuron state, topology and clock, but no raw prompt. Synaptic STDP/R-STDP learning remains disabled.
- Android now validates and receives adaptive-state/threshold fields and reports whether the last signal result was durably checkpointed. Desktop capability status reports whether durable state is configured.
- Added persistence/restart tests and updated bridge/Kotlin regression tests. Tests/builds/device inference were **not run**; source-level implementation only.


## 2026-11-14 — Make Android ANS-100 in-process with optional Termux extension

**Objective:** remove Termux as a prerequisite for Android conversation processing while retaining the authenticated Termux runtime as an optional capability extension.

**Changed:**
- Added `app/src/main/java/org/jarvis/ans100/alpha/data/LocalConversationSignalRuntime.kt`: bounded Kotlin signal pass with app-private checkpoint persistence.
- Updated `JarvisViewModel.kt`: local signal processing is the default; an already-connected Termux signal endpoint is preferred when available, and errors fall back to the in-process runtime.
- Updated `JarvisCore.kt`: signal context now carries a validated checkpoint version and status text no longer implies the Termux bridge is required.
- Added `app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationSignalRuntimeTest.kt`.
- Updated `docs/JARVIS_MOBILE_FIRST_OFFLINE_RUNTIME_V1.md` with the hybrid boundary and remaining native-memory gap.

**Tests:** six focused Kotlin tests authored; **NOT RUN**. They cover feature extraction, event budget, persistence/reopen, failed checkpoint writes, malformed checkpoints, and punctuation-only input.

**Builds / device / CI:** Gradle sync/build, APK/AAB, emulator/device, airplane-mode, CI and GitHub Actions **NOT RUN**. This is source-level implementation only; no compile or runtime success is claimed.

**Remaining:** native Android durable user-memory CRUD and retrieval; explicit Termux package/service discovery and capability UI; a permission-scoped extension API for additional tools; full source-level review of the existing workspace and transfer flows.


## 2026-11-14 — Correct signal-context time metadata

**Correction:** the first in-process ANS-100 commit added tests that asserted model-time metadata, but the shared context type did not yet declare that field. Before any test/build was run, source review caught the mismatch.

**Changed:** added a non-negative `modelTimeNs` field to `ConversationSignalContext`; the in-process runtime now supplies its current model clock, and the optional Termux bridge parses both `model_time_ns` and `checkpoint_version` from the Python response using strict non-negative integer validation.

**Verification:** source-level cross-file correction only. Tests/builds remain **NOT RUN**.


## 2026-11-14 — Make Termux an optional Android capability extension

**Objective:** preserve a standalone Android JARVIS while allowing Termux, when installed and explicitly paired, to add its existing local-console capabilities.

**Changed:**
- `app/src/main/AndroidManifest.xml`: added package visibility for only `com.termux`; retained INTERNET permission and the loopback-only network security configuration.
- `JarvisViewModel.kt`: detect Termux installation, avoid startup bridge attempts when absent, make bridge connection conditional, and keep core chat independent of extension failures. Added a package re-check and a safe action to open Termux. Shared JPVR operations now require a live authenticated extension connection.
- `MainActivity.kt`: relabelled Termux as optional, added extension status, package re-check and open-Termux controls; clarified which capabilities need the companion.
- `data/OptionalTermuxExtension.kt`: added a pure extension-state resolver and routing predicate for the optional signal endpoint.
- `OptionalTermuxExtensionStatusTest.kt`: authored state-transition and fallback-routing tests.
- `docs/JARVIS_OPTIONAL_TERMUX_EXTENSION_V1.md`: documented the standalone contract, pairing, capability boundary, failure behavior and security model.
- Updated `docs/JARVIS_MOBILE_FIRST_OFFLINE_RUNTIME_V1.md`, `docs/JARVIS_ARCHITECTURE.md`, `docs/JARVIS_CROSS_PLATFORM_RUNTIME_V1.md`, `docs/JARVIS_ANDROID_APP_V0_1.md`, `docs/JARVIS_ANDROID_RUNTIME_BRIDGE_V1.md`, `AGENTS.md` and `README.md` to record the optional-extension decision and keep the launch instructions accurate.

**Tests:** eight focused Kotlin test methods are authored; **NOT RUN**. No Gradle/Kotlin compilation, APK/AAB, Android device, Termux pairing, loopback request, airplane-mode, Python test or performance check was run.

**Known limitations:** Termux still requires the user to start the Python local-console service manually and pair its token. Native Android durable user-memory CRUD and a full in-app natural-language Reasoning Engine are not implemented by this change. The extension remains limited to the documented local-console APIs; arbitrary tool execution and external actions remain disabled.

**Next step:** perform a full source-level integration review of Android memory/reasoning boundaries and the optional extension lifecycle, then obtain separate permission before any build or device/test execution.


## 2026-11-14 — Record proposed visible PC browser research mode

**Objective:** capture the user's idea for JARVIS PC to open a real browser window, visibly search and read pages, and let the user inspect or take over.

**Changed:**
- Added `docs/JARVIS_VISIBLE_BROWSER_RESEARCH_V1.md`: proposed visible foreground browser contract, read-only scope, user pause/takeover/stop controls, source provenance, page-content trust boundary, resource bounds and staged acceptance checks.
- Added architecture section 3.59 in `docs/JARVIS_ARCHITECTURE.md`. This feature is explicitly **PROPOSED**, not implemented.
- Updated `README.md` and `AGENTS.md` to record the proposal and prevent future contributors from describing it as an existing feature.

**Tests / implementation:** no browser controller, package dependency, desktop code or browser profile was added. No tests or desktop runtime checks were applicable to this documentation-only proposal.

**Next step:** implement a visible desktop browser adapter and bounded read-only research service in a later batch, then verify visible-window behavior, manual takeover, cancellation, source provenance and rejection of page-originated instructions.


## 2026-11-14 — Add bounded 10,000-neuron Python network capacity

**Objective:** begin the user's requested increase in ANS-100 network scale while preserving staged validation and the Android standalone constraint.

**Changed:**
- `tools/neuron_runtime/network.py`: added hard topology ceilings of 10,000 neurons, 100,000 directed connections and 100,000 total synapses. Oversized topology is rejected before network-state deep copies.
- `tools/neuron_runtime/runtime.py`: added an O(1) `synapse_count` property used by topology validation.
- Added `tools/neuron_runtime/test_network_limits.py`: authored tests for the exact neuron ceiling and over-limit rejection for neurons, connections and total synapses.
- Added `tools/neuron_runtime/network_scaling_benchmark.py`: deterministic sparse-chain benchmark profiles for 1, 10, 100, 1,000 and 10,000 nodes, including event propagation, elapsed time and Python allocation observations.
- Added `docs/JARVIS_10K_NEURON_SCALING_V1.md` and architecture section 3.60. Updated `README.md` to distinguish Python network capacity from Android's existing two-neuron Kotlin signal pass.

**Behavior / limits:** Python `NetworkRuntime` now has an explicit 10,000-neuron source-level capacity ceiling. This is not a measured performance claim. The full topology remains resident in Python memory. JPVR network checkpoints remain complete snapshots rather than lazy JNVM paging. Android standalone 10,000-neuron execution is not implemented; Termux remains optional.

**Tests:** new topology-limit tests are AUTHORED, NOT RUN. The sparse-chain benchmark is AUTHORED, NOT RUN. Existing network scheduler, codec and checkpoint-store suites were not run.

**Verification:** static source edits and GitHub readback only. No Python tests, benchmark, import/syntax checks, Gradle build, APK/AAB, CI, Android/PC runtime, or device RAM/CPU/thermal measurement was run. No test or performance result is claimed.

**Next:** execute the 1 → 10 → 100 → 1,000 → 10,000 network scaling protocol on PC, record memory/latency/stability, then design packed-state/JNVM paging and a native Kotlin runtime with numerical parity before claiming 10,000-neuron Android support.


## 2026-11-14 — Add compact F64 resident state and bounded JNVM working set V1

**Objective:** establish a first source-level paging layer for individual ANS-100 neuron states, reusing the existing JPVR neuron COW index without changing the Numerical Oracle or requiring Termux for Android conversation.

**Added:**
- tools/neuron_runtime/compact_state.py: versioned JPCS V1 little-endian packed resident-state codec. It preserves all current neuron/synapse state using exact F64 numerical fields, including independent synapse clocks and R-STDP context metadata; shared immutable parameter bundles are interned by content digest.
- tools/neuron_runtime/working_set.py: bounded single-writer LRU resident cache, deterministic 128-way JPVR sharding, per-neuron record-version CAS, cold-load, explicit flush/evict, dirty-state write-before-evict, backpressure on persistence failure and uncertain-commit reconciliation.
- tools/neuron_runtime/test_compact_state.py: codec round-trip, determinism, rule/optional-state and malformed-payload tests.
- tools/neuron_runtime/test_working_set.py: LRU, dirty flush, backpressure, candidate isolation, uncertain-commit, clock-epoch and real JPVR temporary-directory round-trip tests.
- docs/JARVIS_JNVM_WORKING_SET_V1.md: contract and limitations.

**Changed:** README.md, docs/JARVIS_ARCHITECTURE.md section 3.61, docs/JARVIS_COMPACT_NEURON_CORE_V5.md and docs/JARVIS_10K_NEURON_SCALING_V1.md now distinguish the compact resident-state prototype from the existing JSON durable record format and whole-network checkpoint.

**Behavior / boundaries:** the Python manager operates on one neuron at a time and is not integrated into NetworkRuntime; it does not provide atomic multi-neuron timestamp commits. JPVR shards use the existing 4,096-page default materialized catalog and append-only store; compaction is absent. Android's in-process Kotlin runtime remains two neurons and remains independent of the optional Termux extension.

**Tests:** the two new focused suites are AUTHORED, NOT RUN. Existing network scheduler/codec/store suites were not run.

**Verification:** GitHub source readback and static cross-file editing only. No Python syntax/import checks, unit tests, benchmark, PC/Android runtime, RAM/latency/thermal measurements, Gradle/APK/AAB build, CI or GitHub Actions were run. No performance saving is claimed.

**Next:** run the staged packed-vs-reference measurement on PC; design network-level timestamp transaction coordination; implement a native Kotlin equivalent with Python/Kotlin golden-vector parity before claiming 10,000-neuron Android support.


## 2026-11-14 — Make JPVR neuron checkpoint loads page-lazy

**Objective:** avoid materializing every DATA/BLOB page in a shard when faulting in one cold neuron, while preserving the existing append-only snapshot and integrity checks.

**Changed:**
- tools/neuron_runtime/jpvr_store.py: added a pinned read-only Mapping over one selected Superblock's durable prefix, with a 64-page read-through LRU cache. The load path now resolves COMMIT, MANIFEST, neuron INDEX, locator, and targeted DATA/BLOB pages on demand. Save/planning remains on its existing bounded materialized catalog.
- tools/neuron_runtime/test_jpvr_store.py: authored tests that fail if load_snapshot() is invoked during a read, and that preserve the page-count read bound.
- docs/JARVIS_ARCHITECTURE.md, docs/JARVIS_JNVM_WORKING_SET_V1.md and README.md: documented the partial read-lazy behavior and retained history/write-path limitations.

**Safety invariant:** the lazy view pins the selected Superblock and relies on the current JPVR append-only rule: already committed page bytes are immutable. This assumption must be revisited before page reuse or compaction is introduced. COMMIT ancestry validation still traverses historical COMMIT pages; only unrelated DATA/BLOB checkpoint pages are skipped.

**Tests:** two focused tests are AUTHORED, NOT RUN. No Python test, syntax/import check, benchmark, build, CI, or device measurement was run.

**Limitations:** save/planning continues to materialize at most the configured 4,096-page catalog. The format/writer has no compaction or free-page reuse. This change does not integrate the per-neuron working set with NetworkRuntime or provide atomic multi-neuron timestamp commits.

**Next:** design and implement a network-level candidate/commit coordinator so all neurons touched by one timestamp can be published atomically before the scheduler exposes routed events.


## 2026-11-14 — Fence neuron mutation while JPVR commit outcome is uncertain

**Finding:** static review found that the working set blocked repeated flush/eviction for an uncertain commit, but still allowed process_batch() and apply_reward() to mutate that same resident candidate before reconciliation.

**Fix:** both mutation entry points now call a shared reconciliation fence and raise NeuronWorkingSetCommitUncertain before candidate calculation. The regression test now attempts a second batch during the uncertain window and asserts no state change before reconciliation.

**Docs:** corrected the 10k scaling note: compact F64 state exists as a separate partial working-set prototype, while NetworkRuntime still retains its full object graph and is not integrated with the manager. Added architecture section 3.62.

**Verification:** source-level edits and static string/readback review only. Tests and benchmarks remain NOT RUN; no builds or device measurements.


## 2026-11-14 — Preserve JPVR lazy-load argument validation

**Objective:** retain the original materialized loader's strict max_pages contract after switching checkpoint reads to a lazy page catalog.

**Changed:** tools/neuron_runtime/jpvr_store.py now rejects booleans, non-integers and negative values before selecting a Superblock. tools/neuron_runtime/test_jpvr_store.py adds a regression contract for invalid bound values.

**Tests:** one test method is AUTHORED, NOT RUN. No tests or builds were run.

**Reason:** without explicit validation, values such as None could leak a Python TypeError from the page-count comparison instead of producing the repository's JPVRFormatError.


## 2026-11-14 — Specify atomic multi-neuron JNVM transaction boundary

**Finding:** the compact LRU prototype uses 128 separate JPVR shard containers. Since JPVR commits are container-local, this layout cannot atomically publish all neurons affected by one NetworkRuntime timestamp.

**Decision:** do not connect the per-neuron working set directly to NetworkRuntime. Specify a dedicated single-container-per-network store with neuron and scheduler COW indexes, all changed neuron records plus scheduler queue/clock state in one typed transaction, and one COMMIT/Superblock publication boundary. In-memory state and outgoing events publish only after durable commit; uncertain outcomes freeze the writer until recovery reconciliation.

**Added:** docs/JARVIS_NETWORK_JNVM_TRANSACTION_V1.md, architecture section 3.63 and a corresponding gate in the 10k scaling roadmap.

**Verification:** architecture and existing JPVR API review only. This is a design specification, not an implementation. No tests, benchmarks, builds or device measurements.


## 2026-11-14 — Expose logical DATA/BLOB placement in JPVR build plans

**Objective:** provide stable physical placement information for every logical record in a multi-record transaction, without reimplementing JPVR page packing in application code.

**Changed:**
- tools/jpvr_reference/typed_transaction.py: TypedTransactionBuildPlan now returns data_record_page_numbers in DataRecordInput order and blob_record_page_ranges in BlobRecordInput order. DATA mappings may repeat a page number when multiple JRECs share one DATA page; BLOB mappings provide each logical record's contiguous first-page/count pair.
- tools/jpvr_reference/test_typed_transaction.py: authored tests cover DATA records split across pages, DATA records sharing a page, and multiple BLOB logical records with distinct contiguous ranges.
- docs/JARVIS_ARCHITECTURE.md section 3.64 and docs/JARVIS_NETWORK_JNVM_TRANSACTION_V1.md now record the placement-map contract and its role in a future atomic multi-neuron transaction.

**Tests:** the two builder tests are AUTHORED, NOT RUN. Existing JPVR typed-transaction and neuron/network checkpoint tests were not run.

**Verification:** GitHub source readback and static layout review only. No tests, benchmarks, builds, CI or device measurements.

**Limitations:** this does not yet build a group checkpoint facade or integrate NetworkRuntime. Locator COMMIT page resolution still requires placeholder and final builder passes. The JPVR page-catalog and append-only growth bounds remain unchanged.

**Next:** implement a single-container multi-neuron + scheduler group checkpoint store using these maps and one neuron COW INDEX plus the primary_object INDEX; add all-or-none, stale-version and uncertain-commit recovery tests.


## 2026-11-14 — Add single-container atomic network JNVM group checkpoints

**Objective:** implement the first durable group-storage primitive required by the network timestamp transaction specification, without routing the live in-memory scheduler through partially durable per-neuron writes.

**Added:**
- tools/neuron_runtime/network_jnvm_store.py: one JPVR container per logical network; changed neuron records use the existing JNVM schema and neuron COW INDEX; the scheduler topology/queue/clock record uses a separate JNSL locator under primary_object.
- save_timestamp() stages all changed neuron records plus one scheduler record, uses canonical typed-builder placement maps, resolves locator COMMIT references and publishes all neuron/scheduler index mutations in one JPVR COMMIT.
- restore_network() pins one active Superblock/catalog and restores scheduler metadata plus all neuron records from that same snapshot, verifying the common clock epoch.
- tools/neuron_runtime/test_network_jnvm_store.py: authored tests for initial group restore, shared COMMIT identity, partial neuron updates, stale scheduler/neuron CAS, topology immutability and uncertain-publication recovery.

**Changed:** docs/JARVIS_ARCHITECTURE.md section 3.66, docs/JARVIS_NETWORK_JNVM_TRANSACTION_V1.md and README.md now distinguish the implemented group storage facade from the not-yet-integrated event loop.

**Atomicity boundary:** first-use empty INDEX initialization is a separate schema commit. The initial scheduler and neuron set are committed together afterward. A crash between those commits leaves an initialized empty container, not a partial network. Later timestamp saves use one group COMMIT. Uncertain Superblock publication propagates; the caller must reopen and reconcile before retrying.

**Tests:** AUTHORED, NOT RUN. No Python syntax/import check, unit test, benchmark, build, CI or device measurement was run.

**Limitations:** NetworkRuntime.run() remains in-memory and is not integrated. The caller must prepare candidates and persist them before publishing corresponding in-memory state. Topology and clock epoch are immutable in V1. Save planning still materializes the bounded JPVR page catalog; compaction and page reclamation are absent. Pending IDs are checked for uniqueness, but no historical processed-event deduplication ledger exists.

**Next:** add a fail-closed timestamp coordinator around NetworkRuntime.run() that stages candidate neurons, generated events and clock state; invoke the group store before publishing in-memory state, and freeze/reconcile on uncertain commits. Keep Android's native two-neuron path unchanged until Python/Kotlin parity is designed.


## 2026-11-14 — Add complete neuron-version manifest to network scheduler V2

**Objective:** prevent a standalone per-neuron checkpoint update from being silently combined with older scheduler state and the other neuron records.

**Changed:**
- tools/neuron_runtime/network_jnvm_store.py: scheduler payload/record schema and locator wire version advanced to V2. Every scheduler snapshot stores a complete neuron_id -> record_version map.
- save_timestamp() reads every neuron locator from the same active JPVR snapshot, requires the map to match the previous scheduler record, checks expected versions for changed neurons, increments only those entries, and stores the full next-version map with the scheduler.
- restore_network() compares each neuron locator version against the scheduler map before decoding/rebuilding the network.
- tools/neuron_runtime/test_network_jnvm_store.py: added regression tests for correct map persistence, restore rejection after an out-of-band single-neuron save, and group-save rejection of version drift.
- Updated docs/JARVIS_NETWORK_JNVM_TRANSACTION_V1.md, docs/JARVIS_ARCHITECTURE.md and README.md.

**Tests:** AUTHORED, NOT RUN. No syntax/import check, unit test, benchmark, build, CI or device measurement was executed.

**Compatibility:** V1 experimental scheduler records are not automatically migrated. Use a fresh V2 container or implement a separately reviewed migration.

**Next:** implement a fail-closed in-memory timestamp coordinator around NetworkRuntime.run(), persisting the complete candidate via the group store before publishing state. Keep Android's native two-neuron path unchanged until Python/Kotlin parity exists.


## 2026-11-14 — Android conversation resilience and 10-neuron signal V2

**Objective:** expand the user-dialogue signal path from two to ten neurons on Android and desktop/Termux, preserve older adaptive checkpoints, and ensure an optional signal failure does not block the conversation.

**Changed source and tests:**
- tools/neuron_runtime/conversation_signal.py — six request-shape sensors, two aggregators, adaptive integrator and output gate; V2 contract; bounded event budget; V1 Python JPVR migration when the old queue is empty.
- tools/neuron_runtime/test_conversation_signal.py — tests for ten-neuron propagation, adaptive state, JPVR reopen, legacy migration, no-op requests and input bounds.
- app/src/main/java/org/jarvis/ans100/alpha/data/LocalConversationSignalRuntime.kt — matching ten-neuron Kotlin pass, V2 SharedPreferences codec and V1 state migration.
- app/src/main/java/org/jarvis/ans100/alpha/data/JarvisCore.kt — validates V1 compatibility and V2 ten-neuron signal summaries.
- app/src/main/java/org/jarvis/ans100/alpha/data/LocalRuntimeBridgeClient.kt — accepts validated V2 Termux results and a bounded V1 compatibility response.
- app/src/main/java/org/jarvis/ans100/alpha/data/OptionalTermuxExtension.kt — removes duplicate shouldUseTermuxSignal declaration and adds an exception-contained optional signal helper.
- app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt — Termux failure falls back to Kotlin; if both signal paths fail, the conversation coordinator still receives the user's message with a null signal context.
- app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationSignalRuntimeTest.kt — ten-neuron, event-budget, checkpoint migration, persistence and error tests.
- app/src/test/java/org/jarvis/ans100/alpha/data/OptionalTermuxExtensionStatusTest.kt — signal failure helper tests.
- tools/jarvis_app/jarvis_core.py, tools/jarvis_app/test_jarvis_core.py — capability status exposes the ten-neuron runtime and integration test expects V2.
- README.md, AGENTS.md, docs/JARVIS_ARCHITECTURE.md, docs/JARVIS_CROSS_PLATFORM_RUNTIME_V1.md, docs/JARVIS_ANDROID_IN_PROCESS_CONVERSATION_V0_1.md, docs/JARVIS_PC_QUICKSTART_V1.md — updated architecture and implementation status.

**Verification:** GitHub source/blob review only. Static readback of the changed source and documentation is being performed before publication. No executable tests were run; new/updated tests are AUTHORED, NOT RUN.

**Builds / APK/AAB / CI / GitHub Actions:** NOT RUN. No workflow was dispatched. The user's GitHub Actions storage warning (90% usage) was noted; this task created no Actions artifacts or caches.

**Limitations:** the V2 implementation has not been compiled or executed. The signal features are request-shape measurements only; dialogue-driven learning remains disabled. Android/Python differential parity, actual resource use, and 10-neuron runtime latency on a phone/PC are not measured. The 10,000-neuron general-network ceiling remains a separate unmeasured capacity limit, not a claim that the phone runs 10,000 neurons during every dialog turn.

**Next step:** once explicitly authorized, run the focused Python and Kotlin tests and then device/resource validation; separately implement the proposed visible PC browser and atomic JNVM network integration.


## 2026-11-14 — Complete V2 conversation contract regression coverage and Android status display

**Objective:** complete the ten-neuron V2 migration batch by updating remaining server/API assertions and making the native Android status command report the active signal topology.

**Changed:**
- app/src/main/java/org/jarvis/ans100/alpha/data/JarvisCore.kt — Android /status now reports configured signal network size and number of active neurons in the most recent pass; default topology is ten when no signal result exists, while a validated V1 compatibility result reports two.
- app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationRuntimeTest.kt — verifies that /status reports the ten-neuron native topology.
- app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationSignalRuntimeTest.kt — replaces obsolete V1/two-neuron behavior expectations with V2 ten-neuron propagation, legacy checkpoint migration and bounded signal-context validation.
- tools/jarvis_app/test_server.py — both authenticated endpoint and service-level tests assert V2, ten neurons, 15 processed events, 10 scheduler batches and the extended feature fields.

**Verification:** GitHub source readback and static consistency review only. Tests are AUTHORED, NOT RUN. No Python test process or Android test/build process was started.

**Builds / CI / GitHub Actions:** NOT RUN; no workflow was dispatched and no Actions artifacts or caches were created.

**Limitations:** API and Android code remain uncompiled/unexecuted. The existing V1 ConversationSignalContext fixture in the older conversation orchestration test is intentionally retained to exercise legacy bridge compatibility. Continue with device validation only after separate authorization.


## 2026-11-14 — Integrate fail-closed atomic timestamp coordinator with network JNVM

**Objective:** connect the existing one-container JPVR group store to the in-memory NetworkRuntime without exposing partial timestamp batches or publishing candidate state before durable commit.

**Changed / added:**
- `tools/neuron_runtime/network.py` — adds `max_timestamps` to `NetworkRuntime.run()`; it stops only after a complete timestamp batch, leaving later events queued.
- `tools/neuron_runtime/network_jnvm_coordinator.py` — new single-writer coordinator. Durable scheduler-only input admission, atomic reward/state update, one-timestamp execution, changed-neuron detection, post-COMMIT in-memory publication, uncertainty freeze, exact commit/prior-state reconciliation after reopen, and stale-version reload from a new handle.
- `tools/neuron_runtime/test_network_jnvm_coordinator.py` — new tests for atomic two-neuron fan-in at one timestamp, a later target timestamp, scheduler-only input persistence, pre-publication failure isolation, uncertain commit/reopen outcomes, stale writer reload, durable R-STDP reward update and empty-queue no-op.
- `docs/JARVIS_NETWORK_JNVM_TRANSACTION_V1.md` — documents source-level coordinator integration and remaining JNVM limits.
- `docs/JARVIS_ARCHITECTURE.md` — appends §3.68 with the timestamp coordinator API, commit boundary, recovery states and limitations.
- `README.md` — updates atomic network JNVM and scheduler integration status.

**Verification:** GitHub source/blob/static review only. Tests are **AUTHORED, NOT RUN**. No test command, failure-injection process, benchmark, build, CI or device measurement was executed.

**Builds / APK/AAB / CI / GitHub Actions:** **NOT RUN**. No workflow was dispatched; no Actions artifacts or caches were created.

**Limitations:** the coordinator still holds a complete in-memory NetworkRuntime; it is not production NCC or multi-process writer leadership. The per-neuron 128-shard working set is not integrated. JPVR page-catalog bounds, append-only growth, absent compaction/reclamation and missing historical processed-event deduplication remain. Correctness and resource use are not runtime-verified or measured.

**Next step:** after separate authorization, run the focused Python store/coordinator/network tests and deterministic failpoint recovery checks; then compare coordinator outcomes against the in-memory NetworkRuntime and measure profiles at 1/10/100/1,000/10,000 neurons on a named PC.


## 2026-11-14 — Add app-private approved memories and shared ANS-100 parity vectors

**Objective:** give Android durable device-local approved memories independent of Termux, provide local UI management, and make the ten-neuron Kotlin/Python conversation-signal contract compare against one shared fixture.

**Changed / added:**
- app/src/main/java/org/jarvis/ans100/alpha/data/LocalWorkspaceMemoryStorage.kt — versioned Base64URL memory codec and SharedPreferences adapter with bounded records and strict decoding.
- app/src/main/java/org/jarvis/ans100/alpha/data/JarvisCore.kt — persistent approved-memory cache, merge of approved workspace records, add/delete APIs, write-before-publish and fail-closed corrupt/commit handling.
- app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt and app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt — local-memory state, add/delete flow and device-local Compose panel; removing the optional Termux token no longer deletes local notes.
- app/src/test/java/org/jarvis/ans100/alpha/data/LocalWorkspaceMemoryStoreTest.kt — memory reopen, approval, merge, failed-write, corrupt-snapshot and bounds tests.
- app/src/test/resources/ans100/conversation-v2.tsv — common feature/event/adaptation/time golden vectors.
- app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationSignalRuntimeTest.kt and tools/neuron_runtime/test_conversation_signal.py — both runtimes consume the same parity fixture.
- Documentation: README, AGENTS, architecture, mobile-first, cross-platform, Core orchestration, Android conversation guide and 10k scaling guide; added Android local-memory and ANS-100 parity contracts.

**Behavior / boundaries:** new Android device-local notes persist app-privately without Termux and are available to Core through bounded lexical retrieval. The store is not native JPVR, not application-level encrypted at rest, and device-local records are not automatically included in JWS/JWB or synchronized with PC. The common signal fixture is authored contract coverage, not proof of parity before execution.

**Tests:** new memory tests and shared-vector consumer tests are AUTHORED, NOT RUN. No test suite was executed.

**Verification:** GitHub source fetch, static patch/readback review and commit object inspection only. No Kotlin/Python test, syntax/import check, Gradle build, APK/AAB, CI, device runtime, model inference or resource measurement was performed.

**Next:** implement the visible headed PC research browser with authenticated bounded read-only controls; then plan a portable and conflict-safe path for transferring Android-created local memories into the PC workspace. Preserve the existing user build/test restrictions.


## 2026-11-14 — Implement visible PC browser research V0.1 and harden offline memory boundaries

**Objective:** provide the first user-visible PC browser workflow for search/open/read/ask while preserving the Android-first local path and keeping external actions disabled.

**Added / changed:**
- `tools/jarvis_app/visible_browser.py` — optional Playwright >=1.48 headed Chromium controller with a dedicated persistent profile, one worker thread, bounded search results/page text, GET/HEAD-only HTTP request guard, a separate WebSocket-blocking route, disabled downloads/service workers, URL validation, public-address DNS checks, explicit pause/manual takeover, resume and stop.
- `requirements-browser.txt` — separate optional Playwright dependency; normal app startup does not require or auto-install it.
- `tools/jarvis_app/server.py` — authenticated loopback-only browser status/search/open/read/ask/control/stop routes. The browser is explicitly desktop-only; Android/Termux reports it unsupported. Asking about the current page re-reads the visible page, passes a maximum 1,800-character excerpt to Core and persists the answer with the source title/URL.
- `tools/jarvis_app/jarvis_core.py` and `tools/jarvis_app/local_inference.py` — bounded external-page context contract; page text is explicitly untrusted, prompt-injection warnings are added to the system instruction and automatic tool calling remains disabled.
- `tools/jarvis_app/static/index.html`, `app.js`, `styles.css`, `sw.js` — visible-browser PWA panel, source selection, read display, question/answer flow, manual takeover, stop/status controls and service-worker cache bump to v10.
- `tools/jarvis_app/test_visible_browser.py` — URL/search validation coverage.
- `tools/jarvis_app/test_jarvis_core.py` and `test_local_inference.py` — external-page context bounds, source provenance and untrusted-data instruction coverage.
- `tools/jarvis_app/test_server.py` — browser status auth, PC-only capability boundary and missing-token rejection for every browser POST route.
- Documentation: `docs/JARVIS_VISIBLE_BROWSER_RESEARCH_V1.md`, `docs/JARVIS_PC_QUICKSTART_V1.md`, `docs/JARVIS_ARCHITECTURE.md` §3.59, `docs/JARVIS_CORE_ORCHESTRATION_V1.md`, `docs/JARVIS_CROSS_PLATFORM_RUNTIME_V1.md`, README and AGENTS.
- Android memory follow-up: `LocalWorkspaceMemoryCache` no longer marks an in-memory-only default cache as durable memory loaded; app-local codec preserves case-sensitive imported IDs and rejects untrimmed stored fields; memory test coverage includes unloaded cache state and imported ID preservation; the Compose description now states device-local notes are not yet transferred to PC.

**Behavior / safety boundaries:** the browser opens visibly and only after explicit search/open requests. JARVIS can read one current page and answer a user question using a bounded untrusted excerpt. The separate browser profile is not the user's everyday profile. The HTTP request guard permits only GET/HEAD; a separate Playwright WebSocket route blocks WebSocket connections; downloads/service workers are disabled; literal URLs and DNS-resolved IPs are checked; this is a best-effort filter, **not a full network sandbox**, and DNS rebinding/TOCTOU and browser/page-script risks remain. Do not use it for sensitive authenticated browsing. The browser does not submit forms, send messages, buy items, upload files or execute external actions. Manual takeover is explicit, not automatic gesture detection. Search extraction depends on Bing page markup. Android/Termux remains independent and does not expose this PC-only feature.

**Tests:** new/updated browser validation, HTTP, Core and local-inference tests are AUTHORED, NOT RUN. The ANS-100 shared-vector and Android approved-memory tests from the preceding entry also remain AUTHORED, NOT RUN.

**Verification:** GitHub source/blob review and static content/readback checks only. No Python test, Kotlin test, Playwright browser launch, PWA interaction, local-model inference, Python import/syntax command, Gradle build, APK/AAB, desktop/phone runtime, CI, GitHub Actions, screenshot, RAM/latency/thermal measurement or Windows/macOS/Linux headed-browser check was executed.

**Limitations / next:** validate the headed browser on a real PC only after separately authorized runtime checks; improve browser network isolation before sensitive use; build a portable conflict-safe export/import path for Android-created local memories; then verify the Python/Kotlin shared ANS-100 vectors with explicit permission. Do not call the browser or local memory production-ready.


## 2026-11-14 — Keep Android local-memory I/O off the UI thread

**Objective:** avoid blocking the Compose main thread during synchronous SharedPreferences commit and ensure the local memory panel reflects imported approved workspace records.

**Changed:** `JarvisViewModel.kt` now serializes add/delete operations on the existing conversation background executor. The panel exposes a busy state, disables conflicting mutations, and clears the input only after the commit is acknowledged. Approved memory refresh/import/restore results are copied into the panel state after the persistent cache commit.

**Tests:** existing `LocalWorkspaceMemoryStoreTest.kt` remains AUTHORED, NOT RUN. No dedicated ViewModel/UI test was added in this source-only change.

**Verification:** GitHub source readback and static inspection only. No Kotlin tests, Gradle build, APK/AAB, device interaction, CI or performance measurement was run.


## 2026-11-14 — Integrate native Android sparse dialogue classifier and harden offline model lifecycle

**Objective:** make the native Compose chat expose the requested 10,000-slot dialogue-feature experiment without conflating it with the ten-neuron ANS-100 signal runtime or Qwen, while tightening the separately installed offline model path.

**Changed / added:**
- `app/src/main/java/org/jarvis/ans100/alpha/data/LocalDialogFeatureClassifier.kt` — new bounded sparse classifier with 10,000 addressable feature slots, Unicode unigram/bigram hashing, four intent labels, abstention until explicit training, bounded numeric weights, lazy checkpoint loading, write-before-publish and fail-closed corrupt-state handling. It stores no source utterances.
- `app/src/test/java/org/jarvis/ans100/alpha/data/LocalDialogFeatureClassifierTest.kt` — seven focused tests for untrained abstention, explicit learning, checkpoint/reopen, failed commit, corrupt checkpoint, invalid input/labels and bounded deterministic feature extraction.
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` — integrates classifier analysis into each native conversation turn as a non-blocking optional side-channel; adds explicit feedback training for the last user message on the background executor; cancels active model download when the ViewModel is cleared.
- `app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt` — displays active-feature count, training count and heuristic label; adds four explicit feedback buttons and explains the difference between sparse text features, ANS-100 and the language model.
- `app/src/main/java/org/jarvis/ans100/alpha/data/LiteRtLmModelStore.kt` — checks cancellation before connecting and after receiving headers, validates Content-Range start/end/total, rejects non-identity response encoding, and exposes small pure validators for regression tests.
- `app/src/test/java/org/jarvis/ans100/alpha/data/LiteRtLmModelStoreValidationTest.kt` — four tests for valid and malformed/mismatched Content-Range and allowed/rejected Content-Encoding values.
- `app/src/main/java/org/jarvis/ans100/alpha/data/LiteRtLmResponseEngine.kt` — reduces prompt/context to source-level limits suitable for the compact 1,280-token model: 750 input characters, two prior messages of 160 characters, two approved memories of 120 content characters and a compact numeric ANS-100 summary; max output remains 96 tokens.
- Documentation updated: `README.md`, `AGENTS.md`, `docs/JARVIS_ARCHITECTURE.md` (§§3.71–3.72), `docs/JARVIS_EXPERIMENTAL_PROTOTYPE_V1.md`, `docs/JARVIS_10K_NEURON_SCALING_V1.md`, `docs/JARVIS_ANDROID_IN_PROCESS_CONVERSATION_V0_1.md`, and `docs/JARVIS_DIALOG_NEURON_EXPERIMENT_V1.md`.

**Behavior / boundaries:** the Android classifier uses SHA-256 hashing while the Python reference uses BLAKE2b; their feature indices and checkpoints are not compatible. The classifier reports only heuristic intent labels, does not generate text, does not enter Qwen as a reasoning result, and does not authorize or trigger actions. The native ten-neuron ANS-100 pass is unchanged. Model weights remain outside the APK and are installed separately. Range/cancellation and prompt-budget rules are source-level only; the character limits are not an exact tokenizer budget.

**Tests:** seven Android classifier tests and four pure model-download validation tests were added. Both groups are **AUTHORED, NOT RUN**. Existing Android conversation, signal, memory, transfer and Python regression tests were not run and remain in their prior documented status.

**Verification performed:** fetched the changed source and documentation from the target GitHub branch after updates; manually inspected the classifier bounds, checkpoint validation/write order, ViewModel/UI wiring, HTTP range guards, model hash/size pins and platform-boundary documentation. This was a source/readback review only; no executable check command was run.

**Not run:** all Kotlin/JUnit tests, Python tests, Gradle sync/build, APK/AAB packaging, CI/GitHub Actions, Playwright/browser runtime, model download/resume, LiteRT-LM initialization/inference, TECNO device interaction, and RAM/CPU/latency/battery/thermal measurements. The build/test restriction remains in force.

**Limitations / next:** verify compilation and the focused Kotlin test modules only after separate user authorization. Then, with the user's device, test the first model download, interruption/resume, SHA check, model initialization, memory pressure and Russian answer quality. Collect a disjoint labelled dialogue set and evaluate the Android and Python classifiers separately; do not compare their checkpoints as if they shared feature IDs.


## 2026-11-14 — Add bounded native Android symbolic reasoning V1

**Objective:** give native Android JARVIS Core a real dependency-checked symbolic planning pass before language generation, rather than passing only a fixed list of template steps.

**Changed / added:**
- `app/src/main/java/org/jarvis/ans100/alpha/data/LocalReasoningEngine.kt` — new bounded breadth-first planner over validated fact/action graphs; checks prerequisites, applies add/delete effects, deduplicates states and reports SOLVED, NO_PLAN, DEPTH_LIMIT or SEARCH_LIMIT.
- `LocalUnderstandingPipeline.kt` — attaches an optional structured reasoning result to the Core analysis context.
- `JarvisCore.kt` — invokes the symbolic planner for ordinary chat turns and search-result turns before response-engine handoff; /status now reports reasoning integration.
- `app/src/test/java/org/jarvis/ans100/alpha/data/LocalReasoningEngineTest.kt` — five tests for shortest plan, missing preconditions, bounded search, question dependency planning and clarification path.
- `docs/JARVIS_ARCHITECTURE.md` §3.73 and `README.md` — record behavior and limitations.

**Behavior / boundaries:** the planner is a deterministic finite-state symbolic search. It validates only caller-supplied candidate facts/actions. It does not retrieve evidence, prove factual truth, execute actions, or grant permissions. `executionAuthorized=false` is fixed in the result. The language model remains the response realizer, not the planner.

**Verification:** fetched and reviewed the current branch files and blob SHAs, assembled one atomic Git tree/commit, then fetched the updated source back from GitHub. No executable Kotlin/Python tests, Gradle sync/build, APK/AAB, CI, device inference or performance measurements were run. Five new JVM tests are **AUTHORED, NOT RUN**.

**Limitations / next:** extend the same fact/action contract with independently sourced evidence and contradiction handling; do not equate a solved symbolic graph with general reasoning. Next engineering batches cover event/reward learning, the native sparse neuron network and durable working set, portable cross-device synchronization, and one-shot local action approvals.


## 2026-11-14 — Add native Android sparse LIF-DT network and SQLite JNVM working set

**Objective:** provide a native Android 10,000-node-capable sparse event runtime and persist inactive neuron state without requiring Termux.

**Added:**
- `app/src/main/java/org/jarvis/ans100/alpha/data/AndroidSparseNeuronNetwork.kt` — sparse directed LIF-DT network with up to 10,000 neuron records, 100,000 connections and 100,000 pending events; per-timestamp detached candidates; whole-timestamp event/state/clock publication in one SQLite transaction; deterministic sparse-chain initialization; 128-entry default LRU.
- `app/src/androidTest/java/org/jarvis/ans100/alpha/data/AndroidSparseNeuronNetworkTest.kt` — three instrumentation tests for 10,000-node initialization, bounded LRU, propagation and durable reopen.
- `docs/JARVIS_ANDROID_SPARSE_NETWORK_JNVM_V1.md` and architecture §3.74 — record storage, scheduler and safety boundaries.

**Behavior / boundaries:** this is a native Android runtime and persistence implementation at source level, independent of Termux. It is not yet the ordinary chat signal path; the ten-neuron diagnostic remains the default for conversation. The runtime uses deterministic starter LIF-DT parameters and a sparse chain initializer; task-specific topology and calibrated learning are separate work.

**Verification:** source written through a single Git tree/commit and read back from GitHub. No Kotlin compilation, instrumentation tests, Gradle build, APK, device runtime or resource benchmark was run. Three Android instrumentation tests are **AUTHORED, NOT RUN**.

**Limitations / next:** test SQLite transaction rollback and uncertain process death on a real device; measure 1/10/100/1,000/10,000-node profiles, RAM, I/O, CPU and thermal behavior; integrate a bounded learning path and explicit feedback before routing chat through this network.


## 2026-11-14 — Connect native 10k JNVM runtime to Android UI and add explicit reward-modulated dialogue learning

**Objective:** expose the native sparse network on the phone without running it on every chat turn, and let users explicitly reward or correct a confident dialogue-classifier output.

**Changed / added:**
- `AndroidSparseNeuronNetwork.kt` — make the network exception base open so the dedicated backpressure exception can inherit from it.
- `LocalDialogFeatureClassifier.kt` — add bounded pair-eligibility trace plus explicit +1/-1 reward modulation; add reward counter and V1-to-V2 numeric checkpoint migration.
- `LocalDialogFeatureClassifierTest.kt` — add positive-reward, negative-reward and V1 migration cases.
- `JarvisViewModel.kt` — add a separate background executor and lifecycle for the native SQLite network; add explicit 10,000-node initialization, bounded impulse probe and one-feedback-per-latest-answer flow.
- `MainActivity.kt` — add native network controls/metrics and explicit Useful/Not useful feedback buttons for the latest answer.
- Documentation: README, architecture §3.75 and the dialogue-neuron contract.

**Behavior / boundaries:** network initialization is user-triggered; it does not run at app startup or replace the existing 10-neuron conversation signal pass. The impulse probe processes at most 4,096 events and 32 timestamps per tap. Dialogue reward learning requires an already confident four-label prediction and an explicit user tap; no automatic reward is inferred.

**Tests:** three reward/migration JVM tests are added; the three Android sparse-network instrumentation tests remain from the preceding batch. All are AUTHORED, NOT RUN.

**Verification:** source changes are committed to the experimental branch and read-back verification is pending in this batch. No Kotlin compile, unit/instrumentation run, Gradle build, APK, device interaction or performance measurement is claimed.

**Limitations / next:** this reward mechanism is a small pair-eligibility classifier, not yet STDP across the full 10k spiking network. The next learning stage must record explicit sequence timing/eligibility and evaluate on disjoint conversations with neutral/rewarded controls. PC/phone memory sync and exact-action one-shot approval/execution remain open.


## 2026-11-14 — Connect a bounded read-only action set to the Python Task Loop

**Objective:** move the existing Task Loop beyond planning-only status without enabling arbitrary or irreversible actions.

**Added / changed:**
- `tools/jarvis_app/safe_actions.py` — explicit executor allow-list for approved-memory search and visible desktop browser search/open/read; bounded output; no shell or write operations.
- `tools/jarvis_app/server.py` — authenticated `POST /api/tasks/{task_id}/run` endpoint; validates exact task revision and at most four allowed steps, maps them to dependency-linked PlanningActions and read-only ActionBindings, applies a deny-by-default SafetyPolicy, runs JARVIS Task Loop and persists lifecycle/step checkpoints in JPVR.
- `tools/jarvis_app/test_safe_actions.py` — four tests for approval-filtered memory search, platform boundary, Task Loop memory search and shell-capability rejection.
- `docs/JARVIS_SAFE_LOCAL_ACTIONS_V1.md`, architecture §3.76 and README — document capabilities and non-goals.

**Security boundary:** only an explicit authenticated user request starts a queued task. V1 actions are READ_ONLY: approved-memory search, visible-browser search, public-page open and current-page read. All are bounded and browser navigation remains subject to the existing URL/DNS/GET/HEAD filter. Shell, file writes, downloads, messaging, purchases, uploads and external side effects are rejected before planning. No claim of a trusted human identity or unrestricted autonomy is made.

**Verification:** GitHub source readback and static code review only. Four new Python tests are AUTHORED, NOT RUN. No Python process, Playwright browser, model inference, Gradle build, APK, CI or device run was performed.

**Next:** connect this explicit run operation to the desktop PWA and Android task UI; then design separately reviewed one-shot signed approvals for narrowly scoped reversible actions. Keep external/irreversible actions disabled.


## 2026-11-14 — Add private-LAN TLS peer-memory sync transport

**Objective:** establish a safe PC-side transport for later Android/PC synchronization of explicitly approved memories.

**Added / changed:**
- `tools/jarvis_app/peer_memory_sync.py` — bounded additive memory overlay with strict record validation, revision CAS, newer-timestamp update preference, conflict reporting, fsync and atomic replacement.
- `tools/jarvis_app/server.py` — authenticated GET/POST peer-memory endpoints; optional second TLS listener bound only to one explicit private IPv4 address; reject non-loopback cleartext, wildcard and public binds; check Host/Origin against the configured endpoint.
- `tools/jarvis_app/test_peer_memory_sync.py` — four tests for add/reopen, newer update, stale CAS and invalid/unapproved/duplicate inputs.
- Documentation: `docs/JARVIS_PEER_MEMORY_SYNC_V1.md`, architecture §3.77, README.

**Security/data boundary:** only explicitly approved memory records are accepted. Tokens are carried over the TLS peer listener, not cleartext LAN HTTP. The certificate must contain the private LAN IP in its SAN; the planned Android client must verify a pinned SHA-256 certificate fingerprint. The PC's normal local HTTP console remains bound to loopback. No wildcard/public binding is enabled.

**Limitations:** this commit creates only the PC transport/store. Android credential storage, pinned TLS client, UI pairing, automatic sync trigger and end-to-end sync are not implemented yet. V1 is additive and timestamp-based; deletions/chat history are not synchronized. Four Python tests are AUTHORED, NOT RUN. TLS browser/Android connection and firewall behavior are NOT VERIFIED.


## 2026-11-14 — Add deletion tombstones to peer-memory sync V1

**Objective:** ensure a user-confirmed local memory deletion can propagate through the revisioned peer sync protocol without treating absence from a partial upload as deletion.

**Changed:** `PeerMemorySyncStore.merge()` now accepts an explicit bounded `deleted_ids` list, rejects duplicate IDs and overlap with incoming records, removes only exact matching IDs, and increments the CAS revision when additions, newer updates or deletions occur. The authenticated API accepts the tombstone list as an optional third field. Added two Python tests and updated the sync contract.

**Verification:** source/readback only. New tombstone tests are AUTHORED, NOT RUN. No Android client or end-to-end LAN sync has been executed.


## 2026-11-14 — Autonomous Core Milestone V1 audit and implementation sequence

**Added:** `docs/JARVIS_AUTONOMOUS_CORE_MILESTONE_V1.md`, a source-grounded implementation and release-gate plan covering independent reasoning, explicit dialogue learning, native Android sparse 10k network integration, Python JNVM working-set integration, Android/PC approved-memory sync, bounded action execution, and live PC browser/model verification.

**Audit corrections:** the current experimental branch already contains an Android SQLite-backed sparse LIF-DT network with a 10,000-neuron ceiling and bounded LRU hot set, plus Python `NeuronWorkingSet`, PC-side TLS approved-memory sync transport, and a read-only Task Loop executor. These components are source-level implementations, not verified runtime milestones; the Android sparse network is still a manual probe rather than the ordinary chat-turn reasoning path, the Android peer-sync client is absent, and dialogue learning is not full-network STDP/R-STDP.

**Constraints:** `main` remains untouched. No Python tests, Android tests, PC browser/model runtime, Gradle build, APK, CI or device benchmark was run. Existing unrun suites remain AUTHORED / NOT RUN. The Gradle Wrapper is still absent.

**Next implementation priority:** integrate explicit, auditable conversation-event learning into a bounded neural subnetwork and connect the existing Android 10k sparse runtime to ordinary chat as an optional side channel without blocking the language/chat path. In parallel, implement the Android TLS certificate-pinned peer-memory client and task-run UI. Keep external side effects disabled.

## 2026-11-14 — Connect Android sparse LIF-DT network to ordinary chat

**Objective:** connect the existing SQLite-backed sparse Android network to the native conversation path without blocking chat, and make the event handoff bounded and atomic.

**Changed:**
- `LocalDialogFeatureClassifier.kt` now exposes up to 128 stable hashed feature-slot IDs in its prediction record; the checkpoint continues to store numeric weights only.
- `AndroidSparseNeuronNetwork.kt` adds `scheduleInputs()`, which validates one same-timestamp feature batch and inserts all events in one SQLite transaction. Duplicate event IDs, mixed timestamps, unknown neurons and queue overflow fail the whole batch.
- `JarvisViewModel.kt` now queues a best-effort feature-to-neuron pass for normal chat turns on the dedicated native-network executor. It is optional, does not block the conversation executor, and does not feed unverified neural output into Qwen.
- `MainActivity.kt` and the ViewModel's manual probe now use real Kotlin string interpolation; the previous escaped templates displayed literal placeholders and reused a literal probe event ID.
- Added instrumentation coverage for atomic multi-feature scheduling and duplicate-ID rejection; added unit assertions for stable bounded feature IDs.
- Updated the autonomous milestone plan and README to record the integration boundary.

**Learning boundary:** the 10k sparse LIF-DT runtime currently emits an observable signal only. The existing explicit 👍/👎 reward still updates the separate sparse feature classifier; LIF synaptic weights are not yet reward-trained. No semantic reasoning or autonomous decision capability is claimed.

**Verification:** GitHub source/readback and static replacement checks only. New and existing Android tests remain **AUTHORED, NOT RUN**. No Gradle build, APK, device runtime, PC browser/model run, resource measurement, CI or GitHub Actions workflow was executed.

## 2026-11-14 — Expose confirmed read-only Task Loop execution in Android

**Objective:** connect the existing Python Task Loop to the Android Compose task registry without widening the action surface.

**Changed:**
- `LocalRuntimeBridgeClient.kt` adds a strict request builder for exactly one `memory.search` step and a bounded result decoder. It validates task identity, returned state, allowed capability, query provenance and at most five excerpts.
- `JarvisViewModel.kt` adds a dedicated execution operation that requires a connected authenticated Termux bridge and a still-queued task with the exact current revision.
- `MainActivity.kt` adds a per-task confirmation dialog explaining that the operation searches only explicitly approved memories. The UI requires an open owning session and limits the query to 240 characters.
- Added `LocalRuntimeBridgeClientTest.kt` for exact capability payload and rejection of blank/oversized/NUL queries and negative revisions.
- Updated the safe-actions contract, autonomous milestone plan and README.

**Safety boundary:** Android currently exposes only `memory.search`. The existing server-side allow-list remains authoritative. Browser, shell, arbitrary Python, file writes, uploads, messaging and purchases remain unavailable. A successful source implementation is not a runtime verification.

**Verification:** GitHub source readback and static review only. New Android unit tests and existing Python Task Loop tests are **AUTHORED, NOT RUN**. No Termux end-to-end request, Gradle build, APK, device UI, Python test, browser or local-model run was performed.

## 2026-11-14 — Add pinned TLS Android peer memory sync

**Objective:** connect app-private approved Android memories to the PC peer overlay without sending raw chats, model files or credentials.

**Changed:**
- Added `PeerMemorySyncClient.kt`: HTTPS-only endpoint on port 8766; RFC1918 IPv4 literal validation; SHA-256 leaf-certificate pinning; system hostname verification remains enabled for the IP SAN; bounded JSON and response parsing; revision compare-and-swap.
- The client reuses the existing bearer token loaded from the Android Keystore-backed `RuntimeAccessTokenStore`. It does not persist the token in peer-sync settings.
- The Compose local-memory panel now allows manual PC IP/fingerprint pairing and a user-triggered sync. The UI explains that only approved memories are sent.
- The ViewModel sync worker merges the server's authoritative approved-record snapshot into the app-private cache and displays added/updated/conflict counts.
- Added unit tests for private IPv4 restrictions, fingerprint normalization, approved-only record payload and revision validation.
- Updated the PC/Android peer-sync contract and autonomous milestone plan.

**Boundary:** Android V1 is additive/upsert-only. It does not send deletion tombstones, so deleting a note on either device does not delete it on the other. Sync is manual; no background schedule is enabled. TLS pinning, IP-SAN verification, authentication, PC firewall and device pairing still require end-to-end runtime validation.

**Verification:** GitHub source/readback and static checks only. New Android tests are **AUTHORED, NOT RUN**. No live TLS exchange, Python peer test, Gradle build, APK or device check was executed.

## 2026-11-14 — Add persistent dialogue ALIF-DT/R-STDP experiment

**Objective:** move dialogue learning beyond a hashed classifier-only reward update by creating a small real spiking readout with delayed eligibility and explicit user labels.

**Changed:**
- Added `tools/neuron_runtime/dialog_rstdp.py`: four ALIF-DT output neurons, 256 bounded feature synapses per output, hashed unigram/bigram encoding, a feature-before-decision-pulse sequence, R-STDP eligibility and explicit label reward.
- The network is persisted through `NetworkJNVMCoordinator` and `JPVRNetworkJNVMStore`; per-label reward baselines and pending feedback proposals are stored through `JPVRRewardBaselineStore`.
- The event journal stores feature/synapse IDs, context IDs, label and reward metadata, not raw utterances.
- Pending reward proposals are persisted before the network COMMIT. On recovery, active eligibility means apply the reward; cleared eligibility at/after the recorded model time means the network COMMIT already happened and only the reward-baseline ledger is completed.
- Duplicate feedback IDs are checked across all four label ledgers.
- Added `tools/neuron_runtime/test_dialog_rstdp.py` for real synaptic weight change, restart, duplicate feedback, interrupted neural reward and interrupted baseline publication.
- The experiment is intentionally separate from the normal PC/Android conversation path until its tests and held-out evaluation pass.

**Boundaries:** this is a four-output-neuron research readout with 256 feature synapses per output, not a 10,000-neuron runtime and not a general reasoning system. It trains only on explicit expected labels. No silent learning, text logging, or evaluation-set training is enabled.

**Verification:** source/readback only. The new Python tests are **AUTHORED, NOT RUN**. No Python test, syntax/import execution, Gradle build, APK, PC browser/model runtime or device measurement was run.

## 2026-11-14 — Replace Android chat chain with bounded feed-forward 10k topology

**Objective:** prevent ordinary chat feature events from cascading through a long sparse chain and accumulating pending work across turns.

**Changed:**
- `app/src/main/java/org/jarvis/ans100/alpha/data/AndroidSparseNeuronNetwork.kt` — added `initializeSparseDialogueNetwork()`, a task-specific topology with 9,996 feature neurons and four terminal output neurons (39,984 directed connections). The generic chain initializer remains available for scheduler tests. The default SQLite filename now uses V2 so existing V1 chain data is preserved.
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` — initializes the feed-forward topology for the native chat side-channel and maps bounded classifier feature slots into the 9,996 input-neuron range.
- `app/src/androidTest/java/org/jarvis/ans100/alpha/data/AndroidSparseNeuronNetworkTest.kt` — added two instrumentation cases: exact 10,000-neuron/39,984-edge topology counts and a feature-spike fan-out to all four terminal outputs with no pending chain backlog.
- Updated `README.md`, `docs/JARVIS_ARCHITECTURE.md` §3.74, `docs/JARVIS_ANDROID_SPARSE_NETWORK_JNVM_V1.md` and `docs/JARVIS_AUTONOMOUS_CORE_MILESTONE_V1.md`.

**Behavior:** a feature spike now fans out to four terminal readout neurons and stops. It cannot propagate down a 10,000-node chain. The graph remains a numeric diagnostic side-channel; it does not train LIF synapses or control Qwen. Existing V1 database files are not deleted or overwritten.

**Tests:** two new Android instrumentation cases added; the complete network test class now contains seven cases. They are **AUTHORED, NOT RUN**. No Kotlin compile, Gradle build, APK, Android instrumentation, device measurement, Python test, PC browser or local-model runtime check was performed.

**Limitations / next:** implement explicit, bounded plasticity on the 10k graph with durable synapse updates and per-feedback idempotency before claiming that the phone's spiking network learns from dialogue. Then test on the target Android device for queue stability, SQLite write cost, resident-set behavior, RAM, latency, battery and thermal load.


## 2026-11-14 — Harden native Android dialogue reward handoff

**Objective:** prevent delayed reward callbacks from corrupting the feedback state of a newer chat turn and make missing eligibility traces visible rather than reporting a false successful update.

**Changed:**
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` — delayed native reward completion and error callbacks now compare the originating context ID with the currently displayed feedback context. They clear or update context-specific UI state only when they still own that context. A reward that updates zero synapses is reported as a missing/expired eligibility trace rather than a successful learning update.
- `docs/JARVIS_ARCHITECTURE.md` — added §3.79 describing the Android 10k dialogue topology's explicit eligibility/reward path, context ownership, bounded behavior and unverified status.
- `README.md` — corrected the Android status so it distinguishes the hashed four-label classifier from the native spiking graph and records that explicit feedback may update both separate learners.

**Tests:** the Android instrumentation cases added for positive/negative synapse changes, untouched-synapse isolation, duplicate reward consumption, SQLite persistence and invalid reward rejection remain **AUTHORED, NOT RUN**. No additional test was added in this callback-only correction; a deterministic stale-callback regression test is still desirable.

**Verification:** fetched current branch files, applied exact-content replacements, and verified GitHub update responses. No executable test, Kotlin compile, Gradle build, APK, CI, device runtime or performance measurement was run, in accordance with repository instructions.

**Limitations / next:** the Android dialogue graph is a bounded numeric feature-to-four-output learner, not a semantic reasoner. The next high-value engineering task is a static review of the existing Python JNVM working-set integration and its recovery/dirty-eviction invariants, followed by tests only when separately authorized. Automatic sync, PC browser/model runtime and Android build remain unverified.


## 2026-11-14 — Reconcile Android dialogue R-STDP documentation

**Objective:** remove contradictory status statements after the native Android 10k dialogue reward path and its instrumentation coverage were added.

**Changed:**
- `docs/JARVIS_ANDROID_SPARSE_NETWORK_JNVM_V1.md` now documents the bounded SQLite eligibility/reward path and distinguishes it from the separate hashed classifier and Qwen language model.
- `docs/JARVIS_ARCHITECTURE.md` §3.74 now points to §3.79 as the superseding explicit R-STDP implementation instead of claiming that the 10k graph has no reward learning.
- `README.md` removes the stale claim that Android feedback cannot update native LIF synapses and records the current nine-case instrumentation suite.

**Tests:** no new tests in this documentation-only correction. The nine Android instrumentation cases in `app/src/androidTest/java/org/jarvis/ans100/alpha/data/AndroidSparseNeuronNetworkTest.kt` remain **AUTHORED, NOT RUN**.

**Verification:** fetched the current experiment-branch source and compared the status text against the Kotlin implementation and test file. No executable test, Kotlin compile, Gradle build, APK, CI, device runtime or resource measurement was run.

**Limitations / next:** the Android R-STDP rule remains an experimental numeric four-output readout. Eligibility attribution, update quality, held-out generalization and device costs are not verified. The Python per-neuron LRU uses 128 independent JPVR shard containers, whereas NetworkJNVMCoordinator commits a whole multi-neuron timestamp in one container; direct wiring would break atomic timestamp publication. Next, design a single-container lazy neuron-record/scheduler transaction before attempting to integrate that working set.


## 2026-11-14 — Refresh peer-sync and autonomous milestone status

**Objective:** align project handoff documents with the already-implemented Android peer-sync client/UI and the ordinary-chat 10k dialogue reward path.

**Changed:**
- `docs/JARVIS_PEER_MEMORY_SYNC_V1.md` now describes the source-level Android HTTPS client, manual pairing, the optional server tombstone field, and the still-missing Android tombstone/automatic-sync path.
- `docs/JARVIS_AUTONOMOUS_CORE_MILESTONE_V1.md` now records that Android chat routes bounded features through the 10k sparse LIF-DT graph and can apply explicit rewards; it also corrects the peer-sync status.
- `README.md` removes the obsolete claim that the Android pinned client/UI do not exist.

**Tests:** documentation-only status reconciliation; no new tests. Existing Android peer-sync tests and Android sparse-network tests remain **AUTHORED, NOT RUN**. No TLS or device verification was performed.

**Verification:** source/readback comparison only. No Python/Kotlin tests, syntax execution, Gradle build, APK, CI or device runtime was run.

**Limitations / next:** peer sync is manual, additive/upsert-only and not end-to-end verified. Automatic sync must be opt-in and should not begin until pairing, failure handling and deletion semantics are defined. Python per-neuron LRU remains separate from atomic NetworkJNVMCoordinator timestamp commits.


## 2026-11-14 — Audit Android R-STDP readout selectivity

**Objective:** document a limitation found during static review before describing the native Android reward path as a dialogue classifier.

**Changed:**
- `docs/JARVIS_ARCHITECTURE.md` §3.79 records that equal output drive and equal initial weights can cause all four output neurons to spike for one active feature. The current graph therefore has no exclusive class prediction; its reward label comes from the separate classifier or an explicit user label.
- `README.md` and `docs/JARVIS_AUTONOMOUS_CORE_MILESTONE_V1.md` now state the same readout limitation.

**Tests:** no new tests were added. Existing nine Android instrumentation cases remain **AUTHORED, NOT RUN**; the current topology test deliberately asserts four output spikes from one input feature.

**Verification:** static source arithmetic review: with 0.90 output drive, 20 ms membrane time constant, 1 ms connection delay and 0.15 initial synapse weight, the decayed drive plus one incoming feature weight exceeds the 1.0 threshold for each of the four outputs. No executable test, Kotlin compile, Gradle build, APK, CI or device run was performed.

**Limitations / next:** the current native graph performs explicit reward-modulated association but does not independently select a dialogue class. Next implement a calibrated competitive readout (or clearly keep the graph as an association experiment), with deterministic tests for neutral inputs, single/multiple feature patterns, winner selection, ties, reward reversal, and held-out label discrimination. Do not feed its output into replies until those checks and a separate user-authorized runtime validation pass.


## 2026-11-14 — Specify network-level lazy JNVM/LRU integration

**Objective:** define a safe way to fault neuron state into a bounded Python LRU without violating the existing single-container atomic timestamp transaction.

**Changed:**
- Added `docs/superpowers/specs/2026-11-14-network-jnvm-lru-integration-design.md` with current-state audit, rejected alternatives, chosen architecture, atomicity/memory bounds and acceptance gates.
- Added `docs/superpowers/plans/2026-11-14-network-jnvm-lru-integration.md` with file-level tasks, interfaces, tests-first steps and the execution gate.
- Added architecture §3.80 to mark the design PROPOSED and distinguish it from existing source implementations.

**Tests:** documentation/design-only change; no executable test applies. No test code or production code was changed.

**Verification:** read-only GitHub source inspection of `NetworkRuntime`, `NetworkJNVMCoordinator`, `JPVRNetworkJNVMStore`, `NeuronWorkingSet`, Android SQLite LRU, architecture and test policy; then GitHub read-back of this commit. No tests, Python execution, benchmark, Gradle build, APK/AAB, CI or device measurement was run.

**Limitations / next:** implementation is not started. The project handoff requires explicit permission before running tests; TDD requires focused tests to be observed failing before production code. Obtain that permission, then implement the pinned read-view, bounded working set, lazy runtime adapter and atomic coordinator candidate publication in the order specified by the plan. Keep `main` untouched.


## 2026-11-14 — Prepare first installable Android prototype build

**Objective:** shorten the path to a first installable Android prototype without waiting for the unfinished Python JNVM/LRU, automatic cross-device sync, full autonomous reasoning or PC browser milestones.

**Changed:**
- Added `.github/workflows/android-prototype.yml`: GitHub Actions builds `:app:assembleDebug` using JDK 17, Gradle 8.9 and Android SDK 35, then uploads the debug APK for seven days.
- Added `docs/FIRST_ANDROID_PROTOTYPE.md`: scope, workflow, artifact download, model-import boundary, and honest verification criteria.
- Updated README with the first APK build/install guide.

**Compatibility basis:** root build files specify AGP 8.7.3, Kotlin/Compose plugin 2.3.10, compileSdk 35 and Java 17. AGP 8.7 requires Gradle 8.9; Kotlin Gradle Plugin 2.3.10 supports Gradle 7.6.3–9.0 and AGP 8.2.2–9.0. The repository has no Gradle Wrapper, so the workflow installs Gradle directly on the runner.

**Verification:** source and build configuration were read from the experiment branch; workflow YAML and documentation were authored. No build, test, CI run or APK generation has yet been executed by this commit. The push is expected to trigger the workflow if GitHub Actions is enabled and runner quota is available.

**Limitations:** successful assembly will not prove device installation, UI stability, model inference, Russian quality, memory behavior, JNVM/LRU integration, PC parity or automatic sync. The LiteRT-LM model weights are not bundled in the APK and must be imported separately.

**Next:** inspect the workflow run and fix compile/build blockers on this experiment branch only; after a successful artifact, install on Android and perform first-launch checks. Do not touch `main`.


## 2026-11-14 — Fix Android SDK bootstrap in first prototype workflow

**Trigger:** the automatic push workflow ran on commit `9017e17e16bf50deb813c4b0634355669f23c036` but failed before Gradle assembly. Job `132507477499` failed in `android-actions/setup-android@v3`: the action attempted to install obsolete SDK package `tools`, which sdkmanager no longer provides. The Gradle assembly and artifact upload steps were skipped; no APK was produced.

**Changed:** removed the failing setup-android action; use the runner's preinstalled SDK at `/usr/local/lib/android/sdk`, add cmdline-tools/platform-tools to PATH, accept licenses and install only `platform-tools`, `platforms;android-35` and `build-tools;35.0.0`. Updated checkout/setup-java to v5 and documented the correction.

**Verification:** based on the actual failed run and decoded job log. Workflow source updated; the next push should trigger a fresh run. No successful build or APK is claimed yet.


## 2026-11-14 — Fix Android SDK manager path discovery

**Trigger:** second automatic build run `43615178611` reached SDK configuration but failed with `sdkmanager: command not found`. JDK 17 and Gradle 8.9 setup both passed; assembly was skipped. The runner image has command-line tools under a versioned directory (for example `cmdline-tools/16.0/bin`), not necessarily `cmdline-tools/latest/bin`.

**Changed:** discover the actual `sdkmanager` executable under `$ANDROID_HOME/cmdline-tools`, fail with diagnostic path listing if absent, and invoke the discovered absolute path for license acceptance and SDK package installation. Add its directory to PATH for later Gradle tasks.

**Verification:** based on run `43615178611` job log. Workflow source changed; no build has passed yet. Next push should trigger another run.


## 2026-11-14 — Migrate Android Kotlin JVM target to compilerOptions

**Trigger:** Android prototype workflow run 3 (43615308594) passed JDK, Gradle and Android SDK setup, then failed while compiling app/build.gradle.kts: Kotlin Gradle Plugin 2.3.10 treats kotlinOptions.jvmTarget as an error and requires the compilerOptions DSL.

**Changed:** removed the deprecated android.kotlinOptions JVM target assignment and configured the Kotlin JVM target through kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }. Java source/target compatibility remains 17.

**Verification:** the change is authored from the exact compiler error in run 43615308594; no Gradle build or Kotlin compilation has yet verified the correction. The next GitHub Actions run must confirm whether project configuration proceeds to source compilation.

**Build / APK:** previous run failed; no APK artifact exists. The next build is expected to be triggered by the commit.


## 2026-11-14 — Enable AndroidX for Android application dependencies

**Trigger:** prototype build run 43615561735 passed Android SDK setup and Gradle project configuration, then failed at :app:checkDebugAarMetadata because AndroidX dependencies are present while android.useAndroidX is not enabled.

**Changed:** added root gradle.properties with android.useAndroidX=true. This enables the AndroidX dependency mode required by Compose and the app's AndroidX libraries; no dependencies or application behavior were changed.

**Verification:** based on the exact Gradle diagnostic from run 43615561735. No new build has yet verified the property. The next push-triggered workflow is the verification gate.

**Build / APK:** run 43615561735 failed before compilation; no APK artifact was produced.


## 2026-11-14 — Fix missing APK workflow trigger path

**Finding:** commit `6bc661f` added the root `gradle.properties`, but the workflow `push.paths` filter did not include that file. GitHub therefore did not start a new run; this was a trigger-filter omission, not another Gradle build failure.

**Changed:** added `gradle.properties` to `.github/workflows/android-prototype.yml` push path filters so Gradle configuration changes trigger the Android prototype workflow.

**Verification:** source correction committed on the experiment branch. A new Actions run must still complete and upload `jarvis-ans100-debug-apk` before an APK can be claimed. No build result is claimed in this entry.


## 2026-11-14 — Fix first Android Kotlin compilation blockers and reconcile integration status

**Trigger:** authorized Android prototype workflow run `43616668545` (commit `37cf0578e8d58b3e86e22b96a1cdd980042cc312`) completed runner, JDK 17, Gradle 8.9 and Android SDK setup, then failed in `:app:compileDebugKotlin`. The actual compiler log reported unresolved `runOptionalSignalPass`, a Compose `weight` import error, missing `RuntimeTaskSnapshot`, missing dialogue bounds, a stale `AndroidNeuronNetwork` type reference, invalid Kotlin tab/newline escapes, reasoner rules hidden in a class companion while used by top-level data classes, a `SortedSet` inference mismatch, and a malformed decryption `try` expression. APK upload was skipped; no APK was produced.

**Changed:**
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt`: made the optional in-process signal pass explicitly non-blocking with `runCatching(...).getOrNull()`, so a signal failure does not fail the chat turn.
- `app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt`: removed the invalid top-level Compose `weight` import and imported the missing `RuntimeTaskSnapshot` type.
- `app/src/main/java/org/jarvis/ans100/alpha/data/AndroidSparseNeuronNetwork.kt`: added explicit per-turn feature/pair-delay bounds (128 features; 10 ms pair window) and corrected the sparse-network class reference.
- `app/src/main/java/org/jarvis/ans100/alpha/data/AndroidWebSearch.kt`: corrected Kotlin control-character literals.
- `app/src/main/java/org/jarvis/ans100/alpha/data/LocalReasoningEngine.kt`: moved reusable validation/bound rules to a file-level object accessible to the top-level problem/action data classes and explicitly typed the best-state set.
- `app/src/main/java/org/jarvis/ans100/alpha/data/WorkspaceTransferCrypto.kt`: changed the malformed decryption expression to a scoped `run` block while preserving key/plaintext/passphrase zeroization.
- Updated README, architecture, 10k scaling, Android milestone and project handoff text to reconcile the native Android 10k SQLite graph and explicit reward path with older statements that predated those additions.

**Tests:** no tests were added or executed in this correction batch. Existing Python and Android suites remain **AUTHORED, NOT RUN**. No model inference, device runtime, installation, memory, latency, battery or thermal check was performed.

**Verification:** read and filtered the actual GitHub Actions log for run `43616668545`; statically compared each named source location and the related architecture/status documents. The next push-triggered `:app:assembleDebug` run is the compile verification gate. No successful build or APK is claimed by this entry.

**Limitations / next:** further Kotlin/compiler errors may become visible after this batch. Preserve the optional Termux boundary; keep the Android LIF-DT graph a numeric side channel, not semantic reasoning. Do not change `main`.


## 2026-11-14 — Reconcile integrated prototype handoff with Android runtime and CI state

**Changed:** updated `docs/JARVIS_EXPERIMENTAL_PROTOTYPE_V1.md` to document the native Android SQLite/JNVM sparse LIF-DT dialogue side channel separately from the ten-neuron ANS-100 pass and the 10,000-slot hashed intent classifier. Recorded the real Actions run 5 Kotlin compile failure and run 6 in-progress status; no APK is claimed.

**Verification:** compared the handoff against `AndroidSparseNeuronNetwork.kt`, `JarvisViewModel.kt`, architecture §§3.74/3.79, and the actual Actions run metadata at 2026-11-14 16:46 UTC. Documentation-only change; no executable tests were applicable and no tests were run.

**Limitations:** run 6 may still fail or succeed after this snapshot. The APK, model inference, Android install and device behavior remain unverified.


## 2026-11-14 — Raise Gradle heap after DEX merge exhaustion

**Trigger:** Android Actions run `43617428362` (#6), commit `d14c58f45093e19cb7386cfda3dceaed9d99b59e`. JDK, Gradle, SDK setup and Kotlin compilation completed. The build failed at `:app:mergeExtDexDebug` with `java.lang.OutOfMemoryError: Java heap space` in R8/D8 while merging external DEX archives. APK upload was skipped; no APK exists.

**Change:** set Gradle JVM heap to 4 GiB, cap metaspace at 1 GiB, and limit Gradle workers to 2 in root `gradle.properties`. This targets the confirmed D8 heap exhaustion and reduces concurrent build pressure without removing dependencies or application features.

**Verification:** read the completed run's job log and confirmed the failing task and root exception. No local build or tests were run. The next push-triggered Actions run is the verification gate; 4 GiB is a resource setting, not a guarantee that D8 will succeed.

**Boundary:** only `experiment/jarvis-integrated-prototype-v1` is modified. Do not change `main`.


## Android prototype UX + dialogue milestone — 2026-11-14

- Reworked the Android chat surface into a dedicated conversation view with a full-height message list, separated user/assistant bubbles, a pinned composer, and a compact model/status header.
- Moved model import/download/removal, neural diagnostics, dialogue-classifier training, Termux/runtime controls, and storage status into a separate Settings destination with Appearance, Language Model, ANS-100, System, and About categories.
- Added persistent appearance preferences for blue/violet/mint accent colors and an animated flowing-gradient outline toggle.
- Improved the Qwen instruction to answer rather than echo the user's text and expanded bounded recent history from 2 to 6 prior messages (220 characters each).
- Added a deterministic Core response for the assistant-identity exchange, including a polite greeting when the user provides a name. This is a narrow deterministic rule, not general semantic understanding.
- Added runtime measurements for first model initialization, last generation, whole-turn time, a rough output word-count estimate, and visible Core/10-neuron ANS-100 indicators.
- The 10,000-slot sparse classifier and SQLite/JNVM graph remain experimental and are described accordingly.
- No build or tests have been run for this change batch yet; Android Actions will perform the compile/package check after commit.


## Follow-up validation refinement — 2026-11-14

- Extended the assistant-identity fast path to recognize a user's name from up to 12 recent conversation messages, so a separate introduction followed by “А тебя?” can be answered naturally.
- Added a unit test for the multi-message name recall case.
- Android prototype workflow now runs `:app:testDebugUnitTest` before assembling the APK; the build gate therefore checks unit tests and APK packaging in the same run.
- The prior run #8 was still executing the APK assembly task when this refinement was pushed; the new push supersedes it through the workflow's cancel-in-progress policy. Run #9 is the validation for this final commit.


## Android unit-test baseline repair — 2026-11-14

- Run #9 confirmed application Kotlin compilation succeeds, then exposed 9 existing unit-test failures (100 tests total).
- Removed a JVM-test-incompatible dependency on Android `Html.fromHtml` from the search HTML parser; added bounded named/numeric entity decoding using standard Java/Kotlin APIs.
- Corrected the search test to expect the original inflected Russian query ("погоду") and updated two stale Core-runtime assertions to match actual command/last-call semantics.
- Adjusted the feature-classifier token-cap test to keep its 300 distinct short tokens within the classifier's explicit 900-character input limit.
- Added a pure-JVM `org.json` test dependency so JSON payload tests exercise the real JSON implementation rather than Android's unmocked framework stubs.
- Re-run the full Android unit suite before APK packaging; no tests are disabled.


## 2026-11-15 — Extend local dialogue recall and clarify inference timing

- Expanded deterministic local recall from only the assistant/user name exchange to explicitly stated user name and project facts in the saved conversation. Queries such as “Как меня зовут?”, “Над чем я работаю?” and “Что ты помнишь обо мне?” search the complete bounded local conversation history (up to the repository's 120-message retention limit).
- The fact extractor reports only explicit phrases from user messages, never infers personal traits, and writes no separate profile database. If no matching fact is present, it says so instead of guessing.
- Kept the language model as the open-ended answer generator. The model receives up to eight prior messages (256 characters each) rather than six; sampling temperature was reduced from 0.60 to 0.45 for more consistent answers.
- Reset generation timing/token estimates for each ordinary chat turn, so fast Core replies do not display stale LLM-generation measurements. The Speed settings page shows an approximate words-per-second estimate only when an LLM generation measurement exists.
- Added JVM tests for alternate same-message identity wording, long-history name recall, explicit project recall (including dotted names), safe fact summaries, and the no-invention path.
- Verification is delegated to Android Actions after this commit. No local Android build or test run is claimed.
- Branch boundary: experiment/jarvis-integrated-prototype-v1; main remains untouched.


## 2026-11-15 — Fix project-name sentence boundary exposed by Android tests

- Android Actions run #12 compiled the Kotlin sources and executed 107 unit tests; two new fact-recall tests failed because the project-name regex treated the dot in RAZLOM.NET as sentence punctuation.
- Changed the sentence boundary to stop only on sentence punctuation followed by whitespace or on the final punctuation at end-of-input. Dots inside domain-style project names remain part of the extracted name.
- Re-run the complete Android unit-test suite and APK packaging on the next Actions run. The run #12 APK step was skipped after the two test failures.


## 2026-11-15 — Move settings to the top-left and add experimental LiteRT-LM GPU selection

**Changed:**
- Removed Settings from the bottom navigation. A single top-left gear control now opens the unified settings destination from chat and the other main sections; the same control becomes a back-to-chat arrow while Settings is open.
- Added a persisted GPU preference for the Qwen3 LiteRT-LM engine. GPU is opt-in; CPU remains the default. Engine reconfiguration is dispatched through the existing single-thread conversation executor so engine creation/use/close retain one owner thread.
- Added LiteRT-LM's optional Android native-library declarations for `libvndksupport.so` and `libOpenCL.so`.
- Added initialization-time GPU-to-CPU fallback and one same-turn CPU retry if GPU inference fails after initialization. A failed GPU attempt disables the preference to avoid repeating a known-bad backend on every message.
- Exposed the GPU preference in model settings and refresh the active backend label after generation.

**Compatibility basis:** Google LiteRT-LM Android documentation describes `Backend.GPU()` and the two optional native-library declarations. Actual compatibility with this device and the installed Qwen3 model remains unverified.

**Verification:** source edits only. No Gradle build, unit tests, APK, GPU inference, device profiling, latency comparison, thermal test or battery test was run. Automatic Actions triggers remain disabled; the Android workflow is manual-only.

**Limitations / next:** verify OpenCL availability and real Qwen3 GPU inference on the phone. If GPU initialization or inference fails, the same chat turn should retry on CPU. CPU remains the default until a successful device test demonstrates a benefit.


## 2026-11-15 — Stream local Qwen responses and remove the 750-character rejection

- Replaced the blocking LiteRT-LM `sendMessage` call with the 0.17.1 `sendMessageAsync` Flow, collected on the existing serialized conversation worker. Engine/Conversation creation, use and close remain on that worker.
- The response engine publishes cumulative visible text chunks; the ViewModel forwards them to the main thread and Compose displays a live assistant bubble while generation continues. The completed assistant response is still committed through the existing conversation coordinator and local history repository.
- Raised the engine-side prompt character guard from 750 to the same 4,000-character limit already enforced by the chat repository/UI. This removes the immediate mismatch; it does not guarantee every 4,000-character request fits the 1,280-token model context because the system instruction and history also consume context.
- No cancellation control is included in this change; cancellation needs a separately verified lifecycle-safe path.
- **Verification:** source edits only. No Gradle build, unit tests, APK, LiteRT-LM inference or phone test was run. Automatic Actions triggers remain disabled and no workflow was dispatched.
- Branch boundary: `experiment/jarvis-integrated-prototype-v1`; `main` remains untouched.


## 2026-11-15 — Add native LiteRT-LM response measurements

**Changed:**
- Added time-to-first-visible-text measurement for the first streamed text chunk.
- Added the LiteRT-LM conversation KV-cache token count after generation, when the runtime can provide it. The UI labels it as the KV-cache count, not as a count of output tokens.
- Exposed both values in the response-speed panel. The existing output word count remains explicitly approximate; it is not presented as a tokenizer result or true tokens/second metric.
- Kept streaming and all Engine/Conversation lifecycle operations on the serialized conversation worker.

**Cancellation decision:** an interactive Stop button was prototyped, then removed before delivery. The current coordinator and response-engine contract are synchronous and block the serialized worker while collecting the Flow. Calling `cancelProcess()` directly from the UI thread would violate the single-owner threading rule in the Android LiteRT-LM guidance. A correct Stop feature requires a coroutine/suspending refactor through the coordinator so both collection and cancellation can run on the same single-thread dispatcher. Do not reintroduce cross-thread cancellation as a shortcut.

**Verification:** static source read-back and API documentation review only. No Gradle build, unit tests, APK, model inference or device test was run. TTFT is a UI-visible first-text-chunk proxy, not a hardware-level first-token timestamp. KV-cache count is post-generation and is not yet used as a pre-generation context-budget gate. Automatic Actions remain manual-only; no workflow was dispatched.

**Branch:** `experiment/jarvis-integrated-prototype-v1`; `main` untouched.

## 2026-11-15 — Verify pre-generation token-budget API before implementation

**Investigation:**
- Inspected the exact LiteRT-LM Android Kotlin source at tag `v0.17.1`, matching the app dependency.
- The pinned Kotlin `Conversation` API exposes `getTokenCount()` for the current KV cache and experimental prompt-rendering helpers, but it does **not** expose the C++ `CountTokens(messages)` preflight API.
- The C++ runtime documents `CountTokens` as including prompt-template tokens and media soft tokens, but that method is not bridged by the pinned Android Kotlin wrapper. The similarly named current-runtime C++ API must not be mistaken for an available Kotlin call.
- Therefore, the existing post-generation KV-cache count cannot safely be repurposed as a pre-send count. A character-to-token ratio would only be a heuristic and must not be labelled as an exact token count.

**Decision:**
- Do not add a fictitious `countTokens()` call or claim exact preflight budgeting with LiteRT-LM Android 0.17.1.
- Keep the current 4,000-character input contract unchanged until a reliable budget path is selected.
- A proper exact implementation requires either a supported tokenizer/preflight API exposed to Android (for example, a verified JNI bridge around the runtime count operation) or a supported upstream Kotlin API. A render-length heuristic can be added separately, clearly labelled approximate and tested against real model measurements.
- Before choosing a JNI bridge, inspect the packaged 0.17.1 AAR/native ABI and licensing/build implications; do not introduce native code or upgrade the dependency speculatively.

**Verification:** source/API inspection only (project branch and upstream LiteRT-LM tag `v0.17.1`). No code/runtime change, Gradle build, tests, APK, model inference, CI or device test was performed.

**Branch:** `experiment/jarvis-integrated-prototype-v1`; `main` untouched.

## 2026-11-15 — Inspect LiteRT-LM native/JNI route for exact token preflight

**Exact-version findings:**
- Re-read the pinned upstream v0.17.1 Kotlin wrapper, JNI declarations, JNI C++ implementation and C++ Conversation header.
- Conversation.getTokenCount() maps to nativeConversationGetTokenCount(), which calls C++ Conversation::GetTokenCount() and reports the current conversation KV-cache count.
- The v0.17.1 Kotlin wrapper has no countTokens() method; its LiteRtLmJni.kt has no native count-messages declaration; and its JNI C++ file has no nativeConversationCountTokens bridge. The v0.17.1 C++ header also has no Conversation::CountTokens method. Thus a bridge cannot be called from the existing AAR: the required operation is not present in that pinned release.
- The experimental renderMessageIntoString() / renderPrefaceIntoString() methods only render prompt text. They do not return token counts and must not be treated as a tokenizer.
- Checked the newer v0.18.1 tag: it still does not expose CountTokens in the C++ Conversation header or the Android Kotlin/JNI API.
- The current upstream main C++ header has since added Conversation::CountTokens(messages, optional_args), documented to include prompt-template tokens and media soft tokens without mutating the KV cache. However, the current main Kotlin wrapper and JNI declarations/implementation still do not bridge that operation. Therefore neither upgrading to 0.18.1 nor merely upgrading to current Kotlin sources supplies an Android-callable preflight API.
- The app currently uses Kotlin Android plugin 2.3.10, so the Kotlin metadata compatibility note for newer LiteRT-LM releases is not, by itself, the main blocker. The missing Android Kotlin/JNI bridge remains the blocker.

**Decision:**
- Do not add speculative JNI calls, reflection against private native pointers, or a hand-written tokenizer. The existing prebuilt AAR does not expose a supported exact preflight entry point, and a safe custom bridge would require a deliberately rebuilt/maintained LiteRT-LM native artifact plus ABI, lifecycle, packaging and licensing verification.
- Do not upgrade LiteRT-LM solely for token budgeting: the inspected 0.18.1 and current Kotlin/JNI layers still lack the bridge.
- Keep the 4,000-character guard unchanged. Keep post-generation KV-cache count labelled as such. If a future approximate warning is desired, implement and label it separately as an estimate, never as exact token count.
- Revisit exact budgeting only after upstream ships an Android Kotlin/JNI API or the project explicitly approves maintaining a custom native LiteRT-LM build.

**Verification:** read-only source inspection of the project dependency/build configuration and upstream LiteRT-LM tags v0.17.1, v0.18.1 and main. No source/runtime changes outside this documentation entry; no Gradle build, unit tests, APK, AAR download/inspection, native build, model inference, CI or device test was run.

**Branch:** experiment/jarvis-integrated-prototype-v1; main untouched.

## 2026-11-15 — Add conservative context packing for Android LiteRT-LM

**Changed:**
- Added `PromptContextBudgeter` for the Android Qwen/LiteRT-LM path. It estimates input size using a conservative character heuristic (2 characters per estimated token), with a 96-token output reserve and a further 128-token template/safety reserve against the configured 1,280-token limit. This is explicitly an estimate, not tokenizer output or a guarantee.
- The base system instruction and current user request are mandatory. The current request is never silently truncated; if the mandatory estimate is over budget, JARVIS returns a clear message asking the user to shorten or split the request.
- The packer keeps only the newest complete history exchanges that fit (up to 8 messages, each bounded to 256 characters). Older pairs are omitted first. It then adds optional Core understanding, ANS-100 signal context and up to two approved JPVR notes only when they fit. Unapproved memory is never included.
- The planner runs before loading the model engine, so an obviously oversized request is rejected without first initializing the model.
- Added speed-panel diagnostics for estimated input context size, omitted history messages and included/omitted approved memory notes.
- Added focused unit tests for newest-pair preservation, oversized-request rejection, approved-memory filtering and estimate labelling.

**Limitations:** LiteRT-LM Android 0.17.1 still has no preflight tokenizer API. The character heuristic can under- or over-estimate Qwen tokenization and chat-template overhead; it reduces risk but cannot prove that every accepted prompt fits the native context. The post-generation KV-cache count remains a separate actual runtime measurement. The configured limits and heuristic should be validated against real prompts after a permitted build/device test.

**Verification:** GitHub source read-back only. Unit tests were authored but not executed. No Gradle build, APK, inference, CI/workflow dispatch or device test was run. Automatic Actions triggers remain disabled.

**Branch:** experiment/jarvis-integrated-prototype-v1; main untouched.

## Android conversation pipeline audit — 2026-11-15

- Traced the Android send path through `JarvisViewModel`, `LocalConversationCoordinator` / `JarvisCore`, `PromptContextBudgeter`, and `LiteRtLmResponseEngine`.
- Confirmed statically that prompt-budget rejection occurs before engine creation; conversation persistence occurs only after a nonblank, bounded response; failures clear the transient streaming text and surface an error instead of committing a fabricated assistant message.
- Confirmed the GPU runtime-failure path retries generation on CPU and replaces the streaming preview with the CPU attempt's accumulated output.
- Fixed a resource-lifecycle defect in `initializeEngine`: if native engine initialization throws, the partially initialized candidate is now closed best-effort before the exception is propagated and any CPU fallback begins.
- Static inspection only. No Gradle build, unit tests, APK, inference, CI, or GitHub Actions were run. Device behavior remains unverified.

## Conversation recovery and model lifecycle audit — 2026-11-15

- Rechecked the LiteRT-LM engine close/reset paths, serialized conversation executor, model download cancellation, and Compose/ViewModel state handling.
- Confirmed model downloads are cancellable through the active HTTP connection and retain partial data for a later range-resume; model import/delete and inference share the single conversation executor, preventing concurrent engine/file operations from those UI paths.
- Found that the chat composer cleared a submitted prompt immediately, while the repository commits a user/assistant pair only after a complete response. On generation/search failure the prompt was therefore absent from history and had already been erased from the composer.
- Updated the chat UI to retain the submitted text until a new matching user/assistant pair is observed in persisted conversation state. On failure, the draft remains available for correction or retry; a later successful response clears it only if the composer still contains that same text.
- Static inspection only. No Gradle build, unit tests, APK, inference, CI, GitHub Actions, or device lifecycle tests were run. Process-kill and real-device recovery behavior remain unverified.

- Added conservative native-runtime recovery in `LiteRtLmResponseEngine`: a CPU generation failure now retires the cached engine; if the CPU retry after a GPU failure also fails, that fallback engine is retired too. Closing is best-effort so cleanup errors do not mask the generation error.

## Android generation cancellation audit — 2026-11-15

- Verified against the exact LiteRT-LM `v0.17.1` upstream Kotlin source that `Conversation.cancelProcess()` exists and is a no-op when no inference is active.
- The current Android engine uses `runBlocking { sendMessageAsync(...).collect { ... } }` inside the single-thread `conversationExecutor` task. This blocks that executor for the whole stream, so queueing cancellation or engine close on the same executor cannot interrupt the active turn.
- The LiteRT-LM `v0.17.1` Flow adapter's `awaitClose { }` is empty. Cancelling the collector alone therefore does not issue native cancellation; explicit `cancelProcess()` is required.
- A cancelled Conversation must be retired and closed rather than reused. The native API documentation also warns that conversation state after cancellation is not suitable for reuse.
- `onCleared()` currently queues engine close behind the active conversation task and then shuts down the executor. This is serialized and avoids closing the engine underneath active inference, but it is graceful cleanup, not immediate cancellation. Android process termination is ultimately handled by the OS.
- No unsafe cross-thread call to `cancelProcess()`, `close()`, or `shutdownNow()` was introduced. Correct Stop/close behavior requires a suspend/callback-based generation refactor that lets the serialized executor process cancellation while the stream is suspended, followed by closing the cancelled Conversation before its Engine.
- Static source audit only. No Gradle build, unit tests, APK, inference, CI, GitHub Actions, or device lifecycle tests were run.


## Android dialogue neural-context integration — 2026-11-15

**Changed:**
- Traced the Android dialogue path and distinguished the 10-neuron request-shape signal runtime from the separate SQLite-backed 10,000-node sparse LIF dialogue network.
- Confirmed the existing 10,000-node topology contains 9,996 hashed text-feature input nodes and four legacy output/feedback nodes. The four outputs are currently driven as feedback targets and are not four meaningful semantic decisions; the network must not be described as a complete reasoning system.
- Added a bounded `DialogueNeuralAnalysis` value containing measured network size, active feature count, processed event/batch counts, legacy output spikes, pending events, persisted-state status, and an optional explicitly trained four-class feature-classifier candidate.
- Changed ordinary Android chat turns to lazily initialize the 10,000-node sparse network on first use, process the bounded active feature set on its dedicated single-thread executor, and wait for that pass before invoking JARVIS Core. The conversation thread waits on a Future; SQLite/JNVM work remains on the native-network executor and never runs on the UI thread.
- Routed the measured analysis through `LocalConversationCoordinator` / `JarvisCore` and the response-engine interface into the LiteRT-LM prompt packer. Qwen receives the analysis as bounded evidence alongside Core's existing understanding/plan; it is explicitly instructed not to treat legacy output spikes as semantic decisions.
- Preserved the existing explicit user-feedback eligibility/reward path. The feature classifier's label is included only when it has a confident prediction backed by explicit training examples; otherwise no label is claimed.
- Updated the built-in help text to distinguish the 10-neuron shape-signal pass from the experimental 10,000-node Android network. Added a prompt-packing unit test for the new neural evidence section.

**Important limitations / next work:**
- This is the first integration step, not completion of 10,000-neuron reasoning. The present native topology is still a sparse feature-to-four-feedback-output structure; it does not yet perform multi-stage semantic reasoning, recurrent planning, evidence comparison, or independent answer selection.
- The four legacy output units are not used as semantic labels. The optional four-class classifier is only a small explicit-feedback classifier and is not a general-purpose language understanding model.
- The network pass is now on the critical path for ordinary non-command chat after lazy initialization. First-use initialization and real-device latency, memory use, heat, and battery impact are unmeasured.
- The new prompt section is bounded and may be omitted when the conservative estimated context budget is full. The context-size estimator remains heuristic.

**Verification:** GitHub source read-back only. The new prompt-packing test was authored but not run. No Gradle build, unit tests, APK, model inference, CI/GitHub Actions, or device test was run. Automatic Actions remain manual-only; no workflow was dispatched.

**Branch:** experiment/jarvis-integrated-prototype-v1; main untouched.


## Learned Android ANS-100 readout follow-up — 2026-11-15

- Follow-up work removed the artificial direct-drive events previously sent to all four output neurons. Only hashed text-feature nodes now receive external input; output activation must result from propagation through the learned feature-to-output synapses.
- Added a bounded four-way readout of mean learned synaptic weights for greeting, question, thanks and farewell. These values are explicitly labelled as weights, not calibrated probabilities, and are included in the `DialogueNeuralAnalysis` prompt context passed to LiteRT-LM.
- The existing explicit-feedback R-STDP path remains the only way ordinary chat-associated synaptic weights are changed; the language model's weights are not trained by this mechanism.
- Updated the neural prompt-packing test to assert that the learned scores are present and not presented as probabilities.
- Scope remains limited to a learned four-way dialogue readout. It is not yet broad semantic reasoning, multi-step neural planning, or independent answer generation.
- Verification remains source read-back only. No tests, Gradle build, APK, inference, CI or device measurement was run.
- Branch: `experiment/jarvis-integrated-prototype-v1`; `main` untouched.


## Android ANS-100 three-stage dialogue network — 2026-11-15

**Architecture update:**
- Reworked the Android dialogue topology from the former 9,996-input-to-four-output graph into three explicit populations within the same 10,000-node JNVM:
  - 9,000 hashed text-feature input neurons;
  - 996 LIF-DT hidden neurons;
  - 4 feedback-trained readout neurons for greeting, question, thanks, and farewell.
- Each input neuron has eight deterministic sparse connections to the hidden population. Every hidden neuron connects to the four readout neurons. The topology contains 72,000 input-to-hidden plus 3,984 hidden-to-output connections: 75,984 total, below the 100,000 connection cap.
- Input-to-hidden weights start at 0.75 and hidden-to-output weights at 0.15. The input and hidden populations integrate actual event batches through the existing LIF-DT runtime; output units receive only propagated hidden spikes. Removed the former direct output-drive events, which had artificially activated the four outputs regardless of input.
- The four output scores passed to the language model are now mean hidden-to-output synaptic weights for the hidden neurons active in this turn. They are explicitly labelled as weak routing hints, not probabilities, facts, or semantic decisions. The separate explicitly trained four-class feature classifier is included only when it has a confident candidate supported by training examples.
- Explicit user feedback records eligibility for hidden neurons that actually spiked and the readout neurons that actually spiked. R-STDP reward therefore updates only the active hidden-to-output synapses for that turn. Ordinary chat does not update weights.
- Added topology version metadata and an in-place upgrade path from the earlier direct feature-to-output topology. The migration retains existing weights on the 996 nodes reassigned as hidden-to-output sources, creates the new input-to-hidden projection, clears stale queued events and eligibility traces, and resets the repurposed hidden-neuron membrane/adaptation/refractory state. It does not delete the 10,000 neuron records or reset the model clock.
- Bounded per-turn processing at 4,096 events, four timestamps, at most 128 text-feature inputs, and at most 256 hidden neurons retained for readout/reward. SQLite/JNVM remains authoritative; the LRU resident-state cache remains bounded.
- Updated Android instrumentation tests to cover the 75,984-edge topology, actual input → hidden → output propagation, feedback changes to only recorded hidden-to-output synapses, persistence, invalid reward rejection, and idempotent topology initialization. Updated prompt-packing coverage for active hidden counts and learned output preferences.

**Limitations / interpretation:**
- The input representation is still hashed words and adjacent word pairs, not a semantic embedding. The 996 hidden neurons provide real temporal/spiking integration, but the network is not yet a general-purpose reasoning engine.
- The four outputs represent only basic dialogue routing classes. They cannot independently plan explanations, verify facts, compare evidence, or understand arbitrary language.
- Qwen still receives the original user message and JARVIS Core's heuristic understanding/plan. ANS-100 contributes bounded numerical and explicitly trained routing evidence; it does not yet decide the full answer content.
- First-time topology creation and in-place migration write up to 75,984 synapses in one SQLite transaction. Startup latency, memory, battery, thermal impact, and real-device behavior are unmeasured. The first chat after an upgrade may take longer.
- Migration preserves old hidden-to-output weights where they map to the new hidden population; old direct input-to-output weights are not retained because those edges no longer exist in the new topology.

**Verification:** GitHub source read-back only. Android instrumentation tests and prompt-packing unit tests were authored/updated but NOT RUN. No Gradle build, APK, model inference, CI/GitHub Actions, or device test was run. Automatic Actions remain manual-only; no workflow was dispatched.

**Branch:** experiment/jarvis-integrated-prototype-v1; main untouched.


## Correction — Android dialogue topology migration details — 2026-11-15

- Clarification to the preceding three-stage topology entry: the in-place migration does not simply preserve weights from the 996 old feature IDs that become hidden IDs. It reads old direct feature-to-output weights and projects each old feature's four readout weights across the eight deterministic hidden targets assigned to that feature. Each new hidden-to-output weight is initialized from the mean of the old weights projected onto that hidden/output pair; pairs with no old evidence use the configured initial weight.
- This mapping preserves an approximate learned readout tendency while changing the graph structure. It is not an exact function-preserving conversion, and the projected weights require evaluation before any accuracy claim.
- Added an Android instrumentation test that synthesizes a v1 direct readout graph, runs the migration, and checks that a known old feature weight is projected onto its expected hidden target.
- Updated JARVIS_10K_NEURON_SCALING_V1.md, JARVIS_ANDROID_SPARSE_NETWORK_JNVM_V1.md, and ANS100_DIALOGUE_REASONING_V2_ROADMAP.md so the architecture documentation reflects the 9,000-input / 996-hidden / 4-output source topology and distinguishes it from the still-unimplemented broader semantic reasoning design.
- Verification remains GitHub source read-back only. The migration instrumentation test and all other tests remain NOT RUN; no build, APK, inference, CI, workflow, or device test was performed.
- Branch: experiment/jarvis-integrated-prototype-v1; main untouched.


## Windows 10 Chrome / Edge Browser Bridge — 2026-11-15

**Implemented on `experiment/jarvis-integrated-prototype-v1`:**
- Added a Manifest V3 companion extension under `tools/jarvis_app/browser_extension/` with a dark JARVIS popup for opening HTTP(S) URLs, searching in a new Bing tab, and asking JARVIS about the active page.
- Uses only `activeTab`, `scripting`, and `storage` permissions plus the single local host `http://127.0.0.1:8765/*`. The active tab is read only after the user opens the extension and presses its explicit send button; page text is capped at 12,000 characters.
- Added an authenticated desktop-only extension API. The local server accepts the extension origin only on `/api/extension/state` and `/api/extension/ask`, permits only matching CORS preflights, and still requires the existing 43-character Bearer token.
- Page handoff validates HTTP(S) URL syntax, rejects localhost/private/reserved literal IPs, resolves the hostname and rejects any private/reserved DNS result, bounds title/question/text lengths, and passes page text to JARVIS Core as untrusted external context. It does not authorize external actions.
- Added a runtime-generated ZIP download at `/downloads/jarvis-browser-extension.zip`, a download link in the PWA browser panel, unpacked developer-mode installation instructions for Chrome/Edge, popup privacy messaging, and a PWA service-worker cache bump to v13.
- Windows 10 startup remains through the existing `start_jarvis_windows.bat` launcher; the extension is a companion to the local PC console, not a native Windows executable or signed CRX.

**Tests authored, not run:**
- ZIP download content-type/disposition and manifest permission assertions.
- Extension CORS preflight, allowed origin response, rejected foreign origin, authenticated state endpoint, unauthorized ask endpoint, and rejection of a loopback source page.
- No browser extension was installed; no Windows 10, Chrome, Edge, Python runtime, local Qwen inference, build, test suite, CI or GitHub Actions workflow was executed.

**Known limitations:**
- The extension opens/searches tabs and sends the current tab to JARVIS; it does not yet let the JARVIS chat issue commands directly into an already-installed browser extension.
- The current local PC model runtime must be installed and available for page Q&A. The no-model fallback is not a general-purpose page reader.
- Installation is manual via browser Developer mode. No signed store package or native desktop installer exists yet.

**Branch:** `experiment/jarvis-integrated-prototype-v1`; `main` untouched.


## Windows desktop workspace UI — 2026-11-15

**Implemented on `experiment/jarvis-integrated-prototype-v1`:**
- Added a persistent desktop left navigation rail with anchors for Overview, Chat, ANS-100, Browser, Tasks, Memory, Data Transfer, Planning, Settings and the event log. The active item follows visible sections; on narrow layouts it becomes a horizontally scrollable navigation strip.
- Reorganized the desktop shell into a wider two-column workspace while retaining a single-column responsive layout for small screens.
- Added local-only interface settings for compact spacing and reduced motion. Preferences are stored in the browser profile under `jarvis.ui.preferences.v1`; no tokens, model state, network weights or workspace records are stored by these controls.
- Added live navigation status labels sourced from the existing authenticated health, conversation and dialogue R-STDP status APIs. The labels explicitly describe the existing small dialogue prototype and do not present it as the complete 10,000-neuron reasoning system.
- Improved the Windows launcher to set UTF-8/unbuffered Python output and check common per-user/system Edge and Chrome installation locations before falling back to the default browser.
- Bumped the PWA service-worker cache from v13 to v14 and added an HTTP regression test for navigation anchors, settings controls and unique event-list IDs.

**Tests authored, not run:**
- `LocalConsoleHttpTests.test_desktop_console_serves_navigation_and_local_preferences` verifies that the server returns the new navigation/settings controls and a unique event-list ID.
- No browser rendering, responsive layout, Windows batch-file execution, browser launch, Playwright, Qwen inference, Python test suite, build, CI or GitHub Actions run was performed.

**Known limitations:**
- The new layout is shared by the local PWA and is responsive, but no real Windows 10 display scale, DPI, keyboard navigation or screen-reader run has been verified.
- The settings are local presentation preferences only; they do not configure the model, GPU, browser extension, or neural network.
- Chat browser commands continue to control the dedicated visible Playwright/Chromium research browser. The Chrome/Edge extension remains manually operated from its toolbar popup; it does not yet accept live commands from the chat window.

**Branch:** `experiment/jarvis-integrated-prototype-v1`; `main` untouched.


**Follow-up — chat commands for the active desktop browser:**
- Added two explicit commands: `Открой в обычном браузере <домен>` opens a new tab/window in the same browser hosting the JARVIS PWA; `Найди в браузере <запрос>` opens a Bing search in that browser.
- The command handler calls `window.open` synchronously from the user-submitted chat action and does not call the extension or remote browser-control APIs. It accepts HTTP/HTTPS domain URLs and rejects credentials, literal IPs and common local hostnames.
- The command does not switch between Chrome and Edge. The browser hosting JARVIS is used. Existing `Открой сайт` / `Найди в интернете` commands continue to use the separate visible Playwright/Chromium research window.
- The Chrome/Edge extension still works independently from its toolbar popup. Chat-to-extension background command relay is not implemented; this limitation is documented in the PC quickstart.
- Static asset regression coverage includes the normal-browser command route and service-worker v14. Tests remain NOT RUN.


## Desktop ANS-100 V4 status visibility — 2026-11-15

- Added an authenticated `GET /api/ans100` metadata endpoint and a separate ANS-100 status panel in the PC UI. It reports the source-defined seven populations, 10,000 neuron IDs and 25,500 *declared/expected* connections without constructing the graph or allocating neuron state.
- The UI explicitly reports `active_in_chat: false` / source-topology-only. It does not claim that the new 10k topology is running. The existing desktop conversation path's 10-neuron structural signal preprocessor and separate four-output dialogue R-STDP experiment remain distinct.
- Added an authenticated endpoint regression test for 10,000 declared neurons, 25,500 expected edges, seven populations and inactive chat status. The test is authored and NOT RUN.
- This UI endpoint depends on the source module `tools/neuron_runtime/hybrid_ans100.py`; the topology builder's own tests remain NOT RUN. No runtime graph was constructed, no memory/performance measurement was made, and no Android/Python parity claim is made.


**Follow-up — browser commands without Qwen runtime:**
- The chat send control now remains enabled when the local inference runtime is unavailable, but changes its label to **Команда браузера**.
- Recognized local browser commands are routed before any model call. A non-browser chat message is rejected with a clear message explaining that LiteRT-LM is required.
- Added static-asset regression assertions for this behavior. Tests remain NOT RUN.


## Android ANS-100 neural co-activation candidates — 2026-11-16

**Implemented on `experiment/jarvis-integrated-prototype-v1`:**
- Added a topology-owned projection decoder for the three deterministic evidence-to-general-association synapses of each reserved concept input neuron. The decoder mapping uses the exact source-offset, fan-out, salt and target slice of the persisted v4 topology.
- Added `ANS100NeuralAssociationDecoder`. It consumes the concept trace, actual emitted spike neuron IDs and the network's activated connection IDs. A pair is emitted only when both concept inputs have activated their corresponding synapses into at least one same association neuron, and that target neuron actually spiked during the turn.
- Candidate output is bounded to three; at most 64 concept inputs and 2,016 unordered pairs are examined. Candidate identity is deterministic and retains the two concept provenance references, activated synapse IDs and convergent neuron IDs.
- Wired candidates into the Android dialogue handoff. Qwen receives them as untrusted, unverified co-activation candidates and is explicitly prohibited from treating co-activation as semantic similarity, a typed relation, causality or truth. Qwen remains a language realizer; it does not generate the candidates or decide the response.
- Added tests for exact mapping to persisted synapses, shared-spike convergence, missing spike, one-sided synapse activation, deterministic IDs, bounds, duplicate inputs and safe handoff behavior.
- Made hypothesis handoff context allocation dynamic so long original user messages retain priority and optional hypothesis detail is clipped to the remaining context budget.

**Verification and limitations:**
- Tests were authored and source files were read back from GitHub; tests were NOT RUN. No Gradle build, APK, device inference, CI or GitHub Actions workflow was run.
- Automatic Android and Windows builds remain disabled: both workflows contain only `workflow_dispatch`.
- The association projection is still deterministic and unlearned; concept activation remains lexical. Same-turn co-activation is not evidence of meaning. The existing six-lane Core candidate competition remains unchanged, so neural association candidates are handed to the realizer as context but do not yet choose the final decision.
- Next research stage: introduce a bounded, explicit-feedback learning rule for concept assemblies and test it against random/collision baselines and held-out concept pairs before connecting it to decision competition.

**Branch:** `experiment/jarvis-integrated-prototype-v1`; `main` untouched.


## Android ANS-100 explicit-feedback neural association learning kernel — 2026-11-16

**Implemented on `experiment/jarvis-integrated-prototype-v1`:**
- Added `ANS100NeuralAssociationFeedbackLearner`, a bounded adapter over the existing reward-learning rule. It accepts only a decoded neural co-activation candidate, exact current weights for all participating synapses, and feedback tied to the same candidate and turn.
- Validates every candidate edge against the real topology projection and verifies exactly two distinct concept-input synapses per convergent association target. It cannot train unrelated topology edges.
- Allows only explicit user feedback or a verified external outcome. Model-generated and implicit-behavior feedback return a rejection without updates.
- Reuses the existing bounded update rule: positive reward strengthens, negative reward weakens, per-synapse delta is capped at 0.01, and weights remain in 0.05..1.0.
- Added tests for positive and negative updates, untrusted sources, candidate mismatch, missing weights and boundary clamping.
- Added `docs/ANS100_NEURAL_ASSOCIATION_FEEDBACK_LEARNING_V1.md` with the algorithm, trust boundary and required integration work.

**Verification and limitations:**
- Tests were authored but NOT RUN. No Gradle build, APK, CI, or device inference was run.
- This is a pure learning kernel. It returns proposed weight updates but does not yet persist them to SQLite, expose a user feedback control, or participate in live chat. Duplicate-feedback idempotency and restart/restore behavior remain unimplemented.
- No semantic understanding claim: the update reinforces or weakens the exact edges involved in an observed co-activation candidate; it does not prove a relation between concepts.
- Automatic Android and Windows builds remain disabled. `main` untouched.

**Next stage:** connect explicit feedback to an idempotent SQLite transaction, persist learned weights across turns/restarts, and evaluate against shuffled-pair and random-weight baselines on held-out concept pairs.


## Android ANS-100 SQLite-backed explicit concept-association learning — 2026-11-16

**Implemented on `experiment/jarvis-integrated-prototype-v1`:**
- Extended the Android sparse network store with schema v4 tables for bounded neural-association eligibility traces and feedback-event idempotency.
- Added a bounded public network API to save eligibility for exact concept-input → general-association synapses, read their current weights, and apply explicit feedback.
- Feedback event insertion and all synapse weight updates share one SQLite transaction. A repeated `feedbackId` is ignored and cannot apply the same rating twice. Updated weights remain in the existing `connections.weight` table and survive process/network recreation.
- Added a v3 → v4 database migration. It creates the new tables without rebuilding the 10,000-neuron topology and moves only reserved concept-input projection edges from 1.0 to 0.75 to create positive-learning headroom. Other input and topology weights are untouched.
- Split topology construction for the reserved concept input range while preserving the same deterministic edge IDs, target mapping and overall connection count. Concept association edges start at 0.75; reward updates remain bounded to 0.05..1.0 and at most 0.01 per event.
- Connected the Android UI to a separate concept-pair feedback action: it asks whether the displayed pair is related. This is deliberately not the generic answer-quality rating, preventing unrelated answer satisfaction from becoming a false concept label.
- Added instrumentation coverage for persistence across reopen, duplicate-event idempotency, untrusted model-generated feedback rejection, migration, and topology weight expectations.

**Verification and limitations:**
- Source files and workflow definitions were read back from GitHub. Tests were authored but NOT RUN. No Gradle build, APK, CI, or device inference was run.
- The UI offers only the top neural co-activation candidate for explicit labeling. This learns the observed concept-to-association synapses; it does not prove general semantic understanding.
- Learned association strength is not yet part of final answer/decision competition. Held-out evaluation against shuffled-pair and random-weight baselines remains mandatory before using these weights to steer decisions.
- Automatic Android and Windows builds remain disabled (`workflow_dispatch` only). `main` untouched.

**Branch:** `experiment/jarvis-integrated-prototype-v1`.


**Follow-up correctness review — 2026-11-16:**
- Separated the concept-pair label from generic answer-quality feedback in the Android UI. The user now explicitly confirms or rejects the displayed concept pair; a "helpful answer" vote cannot train the concept association.
- Preserved the original absolute evidence-input offset when splitting concept input edges to use the 0.75 starting weight. Connection IDs and target mapping remain identical to the pre-learning topology.
- Tightened SQLite eligibility validation: each convergent association target must have exactly two distinct concept-input source synapses, and non-concept evidence edges are rejected.
- Added regression coverage for initial concept weights and v3 migration headroom. Tests remain NOT RUN.


## Android ANS-100 held-out concept-association evaluation protocol — 2026-11-16

**Objective:** establish a leakage-resistant research gate before learned concept-association weights can influence live decision competition.

**Added on `experiment/jarvis-integrated-prototype-v1`:**
- `tools/task_validation/neural_association_eval_v1.py` — bounded JSONL dataset validation, trusted-label enforcement, deterministic pair-disjoint and concept-disjoint splits, exact source-constant projection mapping, bounded reward updates, training-only threshold selection, and four matched evaluation conditions.
- `tools/task_validation/test_neural_association_eval_v1.py` — synthetic protocol fixtures for topology mapping, split leakage, label-source rejection, duplicate-pair rejection, update bounds, deterministic evaluation, and the closed promotion gate.
- `docs/ANS100_NEURAL_ASSOCIATION_EVAL_V1.md` — dataset contract, split protocol, baselines, metrics, commands and interpretation limits.
- `docs/JARVIS_ARCHITECTURE.md` — Phase 6 status and explicit offline-proxy fidelity boundary.

**Experimental design:**
- Pair-disjoint stratified split prevents the same unordered pair appearing in both training and held-out sets.
- Concept-disjoint split evaluates pairs made only from concepts absent from training; cross-partition pairs are excluded and counted.
- Training-only threshold selection prevents held-out tuning.
- Conditions: learned feedback, fixed weights, shuffled training labels and deterministic random weights.
- Metrics: accuracy, balanced accuracy, precision, recall, specificity, F1 and confusion matrix, with per-seed results.

**Verification:** source and documentation are AUTHORED / NOT RUN. No actual user-verified dataset is present, so no task-performance result is claimed. Synthetic labels exist only inside unit tests to validate protocol mechanics. Python tests and benchmarks were NOT RUN. No Gradle build, APK, CI, GitHub Actions workflow, or device inference was run.

**Interpretation / gate:** the score is an offline topology/weight proxy, not an Android LIF replay or calibrated probability. The promotion gate remains CLOSED; learned weights remain excluded from live answer/decision competition. A real dataset and a separate Android runtime replay/ablation are still required.

**Branch:** `experiment/jarvis-integrated-prototype-v1`; `main` untouched. Automatic workflows remain `workflow_dispatch` only.


## Persistent concept-to-neuron identity — 2026-11-16

**Objective:** eliminate cross-turn concept/synapse reassignment before collecting more explicit association feedback.

**Implementation on `experiment/jarvis-integrated-prototype-v1`:**
- Android sparse-network schema advances from V4 to V5 with `neural_concept_input_slots`, a SQLite registry with a unique concept ID and unique neuron ID.
- `resolveConceptInputSlots` reserves the complete approved concept inventory in one transaction. Existing mappings never move; new concepts receive the lowest unused reserved input neuron; removed concepts do not release/reassign their slot.
- The workspace bridge passes the persistent mapping into `ANS100ConceptNeuralInputAdapter`. Concepts without a reserved slot are skipped and counted; they do not abort the dialogue pass.
- The ViewModel now uses the SQLite-backed resolver in the production hybrid path.
- V4-to-V5 migration intentionally resets only concept-to-association projection weights to the 0.75 initial value and clears their eligibility/feedback records. Previous V4 weights cannot be safely migrated because their source concept-to-slot assignments were ephemeral and could shift between turns. Other evidence weights, topology, and unrelated state are preserved.
- Added Android instrumented tests for persistent mappings across reopen, append-only allocation and V4 migration reset/cleanup, plus unit tests for adapter mapping and bridge wiring.

**Research status:** the held-out evaluator remains an offline topology/weight proxy; it does not replay historical SQLite allocation. Its promotion gate remains CLOSED. No real human-verified dataset is present.

**Verification:** tests authored but NOT RUN. No Gradle build, APK, CI, GitHub Actions workflow, or device inference was run.


### Neural association evaluation: projection-coverage diagnostics

The held-out evaluator now reports deterministic topology projection coverage for the full dataset and for each training/held-out split. It counts pairs with at least one shared association target versus pairs that cannot share any target under the current three-edge concept projection. The report labels this as a necessary-condition upper bound only: an Android decoder candidate still requires both input synapses to activate and the common association neuron to spike in the same turn. Pairs with zero shared targets cannot become candidates through feedback or weight updates alone.

Added a focused regression test for coverage totals, histogram consistency, split accounting, and the still-closed promotion gate. Updated the evaluation protocol documentation. **Tests NOT RUN; no Gradle build, APK, CI, or device inference.** This is a diagnostic improvement to the offline topology/weight proxy, not evidence of semantic understanding and not a promotion of learned weights into live decisions.


## Activity-driven concept-association structural plasticity V1 — 2026-11-16

**Objective:** remove the hard limit where concept pairs with no shared fixed projection target can never form a neural association candidate.

**Implemented on `experiment/jarvis-integrated-prototype-v1`:**
- Added `ANS100ActivityDrivenStructuralPlasticity`, a bounded experimental growth kernel that has no predefined concept-pair catalogue.
- A pair becomes eligible only after co-activation in three distinct completed turn IDs inside a rolling 16-observation window. Replayed turn IDs are ignored.
- The allocator excludes all targets already used by either source concept, then chooses a least-loaded target in the general association population. A stable pair hash only chooses the scan's starting offset; target selection remains based on current graph load, not a fixed pair-to-target table.
- Emits exactly two proposed synapses from the two concept input neurons to the newly selected common target, with conservative initial weight 0.25 and auditable concept/provenance metadata.
- Added bounds for active concepts, pending pair traces, remembered turn IDs, total grown pairs, per-concept growth degree, and target incoming degree.
- Added focused unit tests for distinct-turn threshold, duplicate-turn replay, existing shared-target suppression, target exclusion and load competition, saturation, eligibility expiry, per-concept degree, deterministic proposals, and duplicate concept IDs.
- Added `docs/ANS100_ACTIVITY_DRIVEN_STRUCTURAL_PLASTICITY_V1.md` with the algorithm, limits, trust boundaries and integration gates.

**Critical limitations / integration boundary:**
- This is a prototype kernel, not yet a live topology mutation. It returns proposals; it does not insert them into SQLite or alter the production network.
- Eligibility and grown-pair state are currently in memory and do not survive process restart.
- The existing fixed three-edge projection remains the only live decoder path. New proposals do not affect chat answers or decision competition.
- Repeated co-activation can be incidental and is not proof of semantic relation. Human-verified held-out evaluation and false-association controls remain required.
- Qwen remains a language realizer and is not used to generate associations.

**Verification:** tests are AUTHORED / NOT RUN. No Gradle build, APK, CI, GitHub Actions workflow, or device inference was run. Automatic workflows remain manual-only (`workflow_dispatch`). `main` was not modified.


## 2026-11-16 — Activity-driven structural plasticity prototype

**Objective:** remove the fixed-three-edge coverage ceiling as an experimental capability, without hard-coded concept pairs or language-model-generated associations.

**Added:**
- `app/src/main/java/org/jarvis/ans100/alpha/data/ANS100ActivityDrivenStructuralPlasticity.kt` — bounded activity-driven proposal engine. It observes active concept assemblies, skips pairs already sharing an association target, accumulates distinct turn IDs, and after three observations proposes two directed synapses to a competitively selected shared target. The target is selected by a deterministic pair-hash scan with least-incoming-load competition, excluding both sources' existing targets.
- `app/src/test/java/org/jarvis/ans100/alpha/data/ANS100ActivityDrivenStructuralPlasticityTest.kt` — regression coverage for thresholding, replay idempotency, existing shared targets, target exclusion/load competition, capacity, expiry, degree bounds, deterministic proposals and duplicate inputs.
- `docs/ANS100_ACTIVITY_DRIVEN_STRUCTURAL_PLASTICITY_V1.md` — mechanism, limits, epistemic boundaries and integration/evaluation gates.

**Changed:**
- Corrected the least-loaded-target test fixture so every target except the deliberately empty target has non-zero incoming load; this makes the expected winner independent of the pair-hash starting offset.

**Architecture / safety decisions:**
- No predefined concept-pair list or semantic relation catalogue is used.
- Repeated co-activation is only a structural-growth signal, not proof of semantic relation, causality or truth.
- The learner returns auditable proposals; it does not write SQLite, mutate the live network, alter the decoder, enter answer selection, or change Qwen's role.
- In-memory eligibility is not durable across process restart yet.
- Conservative initial weight is 0.25; growth, per-concept degree, target in-degree, tracked-pair count and observation window are bounded.

**Tests:** AUTHORED / NOT RUN. No test command was executed in this environment.

**Builds / APK / CI / GitHub Actions / device inference:** NOT RUN. Automatic workflows remain manual-only; no workflow was dispatched.

**Limitations:** persistence and atomic synapse insertion, runtime integration, decoder support, restart/replay migration tests, and held-out evaluation against fixed-topology, shuffled-activity and random-growth controls remain outstanding. No claim of autonomous semantic understanding is supported.

**Next stage:** implement durable SQLite eligibility and atomic validation/insertion of proposed synapse pairs, then exercise those synapses in the actual Android LIF runtime behind a disabled-by-default experimental gate.


## 2026-11-16 — Persistent dynamic structural synapse lifecycle (V1.1)

**Added to Android JNVM SQLite schema V7:**
- `structural_synapse_registry` records grown connection ownership, proposal ID, concept IDs and timestamps.
- `structural_synapse_feedback_events` records unique feedback ID, proposal ID, delta, source and timestamp.
- Atomic `addStructuralGrowthProposal` persists both concept-to-association synapses in one transaction, rejects out-of-range endpoints/IDs/weights and enforces total connection, target in-degree and per-concept grown-edge limits. Replaying a proposal is idempotent.
- Pairwise weight mutation clamps weights to [0.05, 1.0], limits delta to ±0.10, requires both edges of each proposal, and accepts only explicit-user or verified-external-outcome feedback. A unique feedback ID prevents duplicate reward application.
- Weak-link removal deletes both edges only when both are included and both weights are below the caller threshold (public API caps threshold at 0.20).
- Read API exposes weights only for registered grown synapses.

**Important:** the underlying sparse runtime reads the same SQLite connections table, so persisted grown edges can propagate signals once the proposal API is explicitly invoked. The Android turn pipeline now fetches registered grown edges for the active concept neurons and supplies them to the association decoder. The existing explicit association rating path accepts registered grown edges and updates complete proposal pairs transactionally; partial grown pairs are deliberately not trained. Automatic proposal generation/insertion on completed turns is still disabled because eligibility traces are not yet durable and rollout needs held-out evaluation.

**Tests:** instrumented tests AUTHORED for proposal insertion/idempotency, feedback persistence across reopen, duplicate feedback replay, rejection of model-generated feedback, whole-pair mutation/removal, preservation of base topology count, and explicit rating of a grown association. Unit test AUTHORED for decoder support of a new target absent from fixed projections; instrumented migration test covers schema V6→V7 feedback-ledger creation without rebuilding topology. NOT RUN. Build, APK, CI, GitHub Actions and device inference: NOT RUN.

**Next:** persist the activity learner's distinct-turn eligibility and bounded growth counters in SQLite, then connect a disabled-by-default proposal observer to completed turns. Evaluate fixed topology vs grown topology vs shuffled co-activation on held-out concepts before enabling automatic insertion.


## 2026-11-16 — Durable structural-plasticity observations (V1.3)

**Android JNVM schema V8:** added bounded turn and concept-observation tables. Each completed turn is recorded transactionally with up to 64 concepts; 64 recent turn IDs are retained for idempotency and the latest 16 are replayed for eligibility reconstruction. The store verifies persistent concept ID-to-neuron-slot identity before accepting observations.

**Graph snapshot:** reads current concept outgoing targets, association-target in-degree, registered grown concept pairs and per-concept grown-edge counts. The activity learner can restore durable growth limits before replay. SQLite independently caps grown pairs at 512, grown edges per concept at 8 and target in-degree at 16.

**ViewModel integration:** added a disabled-by-default observer path. It only records concept inputs that actually spiked and propagated a fixed or grown association synapse; it ignores unfinished/budget-exhausted turns. It replays the last 16 durable observations and sends newly generated proposals through atomic SQLite insertion. Grown links are available to the sparse runtime and decoder on later turns.

**Safety:** STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED remains false. Ordinary app behavior does not automatically mutate topology. Enablement is deferred until a held-out evaluation compares fixed topology, grown topology and shuffled co-activation controls. Co-activation does not establish semantic truth.

**Tests authored, NOT RUN:** persistence and replay after database reopen, duplicate turn replay after reopen, reconstruction of a proposal from three persisted turns, growth-state restoration, bounded 64-turn retention/16-turn replay, per-turn target de-duplication, and V7 to V8 migration without base-topology rebuild. No Gradle tests, Android build, APK, CI, GitHub Actions or device inference were run.

**Next:** create a deterministic held-out evaluation harness with positive/negative concept-pair labels, temporal split, shuffled-activity and fixed-topology baselines; measure candidate precision/recall, false associations, connection growth, storage and runtime cost before considering enabling automatic growth.

## 2026-11-16 — Structural-plasticity temporal evaluation V1

Added tools/task_validation/structural_plasticity_eval_v1.py and focused tests. The deterministic synthetic fixture compares fixed projection, activity-grown topology and shuffled temporal identity. Each labelled pair receives two training-time observations; four positives and two hard negatives receive a third held-out observation. Labels are never supplied to the learner. The report covers classification metrics, false-association rate, growth count, target loads, bounded operation counts and estimated logical payload.

**Interpretation:** the hard negatives intentionally test whether repeated co-activation can create false links. This experiment evaluates the temporal growth heuristic only; it does not establish semantic understanding, Android runtime behavior or real-world accuracy. The payload estimate is not measured RAM/SQLite size, and operation counts are not wall-clock CPU benchmarks.

**Status:** implementation committed; tests AUTHORED / NOT RUN. STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED remains false. No Android/Windows build, APK/AAB, Gradle, CI, GitHub Actions or device inference was run.

## 2026-11-16 — Transformer vs ANS-100 benchmark V1

Added a deterministic shared task generator and prediction scorer for direct Transformer-only vs ANS-100-only comparison, plus a separately scored ANS-100-led / Transformer-verbalizer hybrid condition. The default corpus contains 192 synthetic tasks across development, pair-disjoint, concept-disjoint and compositional splits. Task families cover relation status, two-hop reachability and evidence conflict. The scorer rejects duplicate/unknown task IDs, counts missing outputs as wrong, reports per-split/family accuracy, paired outcomes, latency and peak-memory fields.

**Fairness contract:** both systems receive the same task input; evaluator labels/expected answers are never model inputs. No tools or extra examples in the primary run. Model/runtime/quantization/device/resource metadata must be disclosed. ANS-100 adapters must disclose heuristic Core signals and cannot call the current integrated pipeline pure autonomous neural reasoning.

**Status:** generator/scorer and focused tests committed; tests NOT RUN. No Transformer or ANS-100 model inference has been run, no predictions or measured resource report exist, and no winner is declared. The benchmark is synthetic and is only a first controlled task suite.


## 2026-11-16 — Leakage-safe ANS-100 benchmark adapter V1

Inspected the existing Android `ANS100BenchmarkTraceAdapter.kt`: it already exports actual neural telemetry and heuristic provenance, but deliberately sets answer=null because the current pipeline has no semantic canonical readout. Added a Python validator for this exact Kotlin JSON schema and a gold-free model-input exporter. The validator checks exact-input SHA-256 and accepts scorer predictions only from an explicit `NEURAL_CANONICAL_READOUT`; current controller decisions remain unscored. The Kotlin adapter currently has no call site in JarvisViewModel, so automatic app trace capture/export is still missing. Added regression tests and documentation.

**Status:** implementation and tests committed; tests NOT RUN. No production inference changes, app trace capture, Transformer/ANS inference, predictions or resource measurements. No build, CI or Actions run.

Added `ans100_benchmark_adapter_v1.py` to export exact model inputs without evaluator labels/split metadata and validate Android trace exports. The adapter checks the SHA-256 of each exact input, records heuristic provenance, and emits scorer predictions only when a dedicated `NEURAL_CANONICAL_READOUT` explicitly produces YES/NO/UNKNOWN/CONFLICT. Current `HYBRID_POPULATION_CONTROLLER_V1` SELECTED/clarification/abstention statuses are deliberately not converted into semantic answers. Added focused regression tests and adapter documentation.

**Status:** implementation and tests committed; tests NOT RUN. No production inference path changed, no Android trace exporter or actual predictions exist yet, no Transformer/ANS inference or resource measurements were run. No build, CI or Actions run.


## 2026-11-16 — Android benchmark telemetry and neural hypothesis lifecycle V1

Connected ANS100BenchmarkTraceAdapter to the real JarvisViewModel hybrid dialogue pass. Each pass attempts a best-effort app-private JSONL write with bounded 256-record retention, 64 KiB row cap, input SHA-256 and character count only (no raw message, gold labels, expected answers, or assistant response). Captured values include actual emitted spikes, activated connection IDs, population activity, Core heuristic provenance, association candidates and measured ANS-pass elapsed time. Peak process memory remains null because it is not measured.

Added ANS100NeuralHypothesisEngine. It forms hypotheses from actual shared association-target spikes and checks reproduction on later distinct completed turns. It distinguishes generated, reproduced, and not-reproduced signals; non-reproduction is not factual refutation. Incomplete turns, duplicate turns and potentially truncated candidate scans cannot count as negative verification. State is bounded but currently in-memory only.

**Important limitation:** upstream concept activation and typed evidence still come from lexical/Core preparation. The new engine is a first neural-output-driven association hypothesis lifecycle, not a fully autonomous semantic reasoner. It does not emit benchmark canonical answers, does not give Qwen decision authority, and does not enable automatic structural growth.

**Tests authored, NOT RUN:** hypothesis lifecycle and trace-store regression tests. No Gradle tests, Android build, APK, CI, GitHub Actions, Transformer inference, ANS scoring or device inference were run.


## 2026-11-16 — Typed hypothesis evidence and competing memory explanations V1

Extended the workspace concept bridge with an explicit relation-evidence category. Approved records use the exact syntax `source | RELATION_TYPE | target | SUPPORTS|REFUTES`. Endpoints must resolve uniquely to approved concepts; malformed, ambiguous, unapproved, multiline and overlong records are rejected. Per-turn evidence is capped at the concept-memory verification limit (128).

The existing typed memory verifier now receives these observations and can distinguish SUPPORTED, REFUTED, CONFLICTING and UNRESOLVED. A support and counter-evidence for the same typed relation yields CONFLICTING. The Android diagnostic summary exposes counts for each state.

Benchmark telemetry now carries memory-derived typed explanations separately from neural co-activation hypotheses. It hashes concept labels and hypothesis identifiers and exports only relation type, status and evidence counts. This creates two auditable evidence streams for subsequent comparison; it does not collapse them into a neural canonical answer.

**Provenance boundary:** workspace evidence is EXPLICIT_USER only. It is user-entered assertion, not independently verified external evidence. Text cannot self-assign VERIFIED_TOOL provenance. Authenticated tool ingestion remains future work.

**Tests authored, NOT RUN:** support/refute/conflict parsing, malformed/unapproved/ambiguous evidence rejection. No Gradle tests, Android build, APK, CI, GitHub Actions, or device inference were run.


The same stage adds `ANS100HypothesisCompetitionEngine`, which cross-references actual neural association candidates with typed two-hop memory explanations for the same concept labels. It preserves relation type and direction and classifies the comparison as neural-only, unresolved memory path, user-assertion support, user counter-evidence, or mixed user assertions. It does not choose a winner or claim truth. Per-candidate alternatives are capped at 12.

Benchmark traces include hashed cross-source comparison summaries. The ViewModel diagnostics now expose the number and status of neural-memory comparisons. Tests authored for directionality, unresolved paths, user support/counter-evidence, mixed assertions and privacy-safe trace export. Tests NOT RUN.
