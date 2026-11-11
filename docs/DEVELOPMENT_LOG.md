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

**Архитектурные решения:** одна OUTBOX page хранит одно неизменяемое событие; обязательные идентификаторы — event_id, idempotency_key, operation_id и commit_id; event_type ограничен 128 UTF-8 bytes; opaque event payload — 3,500 bytes; sequence начинается с 1. Event ссылается на COMMIT того же поколения, который физически следует за OUTBOX page. Соседние события одного поколения должны принадлежать одному transaction_id и одному source COMMIT. Доставка остаётся at-least-once.

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
