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
