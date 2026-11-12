# JARVIS ANS-100 Alpha

**JARVIS ANS-100 Alpha** is a research project for a compact adaptive neural system. Development is staged: begin with one artificial neuron and a small number of synapses, validate behavior and resource cost, and scale only when each previous step is supported by evidence.

The repository has entered the first executable Python reference-orchestration stage. It is still not a production neural runtime, an integrated JPVR-001 prototype, or a finished assistant.

## Current status — 12 November 2026

- **JPVR reference:** seventeen modules currently contain **362 test methods**. The COW-integrated typed builder feeds a file-backed two-Superblock storage engine. JPVRStorage.scan_history() audits the full COMMIT/MANIFEST history and validates the OUTBOX chain even for the last verified prefix when a later generation is damaged. JPVRStorage.recover_copy(destination, expected_commit_id=...) can publish that verified prefix as a separate file: it rechecks history under a shared lock, requires the exact commit identity, copies and reads back only verified pages, reconstructs both alternating Superblock slots, and publishes atomically without overwriting an existing path. The original file is never truncated or rewritten. The 23 historical-scan tests and 12 recovery-copy tests are AUTHORED, NOT RUN. The reference writer/scanner/copy-recovery are not yet Android-device-verified or production-ready. In-place repair, compaction, free-page reuse, durable CQE delivery and hardware-level power-loss guarantees remain out of scope. **Neuron checkpoint persistence:** tools/neuron_runtime/jpvr_adapter.py serializes complete one-neuron state/configuration into versioned JREC/DATA or BLOB records, and tools/neuron_runtime/jpvr_store.py now resolves a fixed 100-byte versioned locator in the neuron COW INDEX. JPVRNeuronCheckpointStore.save() commits the checkpoint record and locator in one transaction; load() validates the COMMIT ancestry, DATA/BLOB metadata, CRCs and snapshot schema before restoring NeuronRuntime. A genesis container receives its seven empty index roots in a separate initialization commit. The 17 adapter tests and 17 new store tests are AUTHORED, NOT RUN. The one-neuron facade defaults to a 4096-page materialized catalog. A separate JPVRNetworkCheckpointStore now persists the complete bounded NetworkRuntime topology, pending events, scheduler limits and model clock in one BLOB+CHECKPOINT commit. A bounded RewardBaselineManager now owns per-task ordered feedback and idempotency; JPVRRewardBaselineStore persists its pending/committed state in a dedicated container. The two checkpoints are not yet one atomic Runtime snapshot.
- **Numerical Oracle:** a deterministic Python/float64 reference implementation exists for Adaptive LIF-DT, STDP and R-STDP. It is a research oracle, not the production runtime.
- **Neuron runtime coordinator:** tools/neuron_runtime contains an in-memory Python reference coordinator for one Adaptive LIF-DT neuron and multiple incoming excitatory synapses. It delegates neuron/STDP/R-STDP equations to the Numerical Oracle. Its 15 focused tests were included in the user's full Termux run on 8 November 2026: 216/216 repository tests passed in 19.280 s. This is Python reference verification, not production NCC/runtime or device performance evidence.
- **Multi-neuron network coordinator:** `tools/neuron_runtime/network.py` adds directed delayed connections, deterministic same-timestamp batching, bounded event/run budgets and in-memory timestamp-level candidate commit. Fifteen scheduler tests are AUTHORED, NOT RUN. `network_adapter.py` and `network_store.py` now persist all nodes, connections, pending events, scheduler limits and model clock through a versioned BLOB+CHECKPOINT commit; 11 codec and 14 store tests are AUTHORED, NOT RUN. This remains a single-threaded Python reference layer, not production NCC or concurrent scheduling.
- **CQE reference:** a partial, single-threaded Python contract reference exists. It now includes age-based queue promotion, a bounded 4,096-entry recent subscriber-event deduplication window, stricter committed-event validation and bounded outbox preflight. Commit store and inbox/outbox remain volatile; durable JPVR integration is not implemented. The latest additions are not yet test-verified.
- **Latest test evidence:** before V4, the user ran 107 tests. Dedicated V4, V4.1 and V4.2 suites passed (12, 15 and 6 tests). On 8 November, V4.3 focused tests passed (7 tests, 1.636 s); smoke and pilot completed. Pilot held-out accuracy: FIXED 81.84%, STDP 100%, frozen R-STDP V4 83.01%, Decision-Latched 82.23%, Neutral 81.84%. Decision-Latched processed all 6,144 inputs and skipped 1,024 post-decision updates, but did not beat V4. Separately, the exact uploaded repository archive passed local container verification on Python 3.13.5/Linux x86_64 after two regression-test corrections: BTCC 17/17, V5.1 tests 6/6, and full repository suite 158/158. These are reference/unit checks, not an Android-device run or task-level BTCC benchmark.
- **Calibration:** Round 1's 99-scenario sweep was generated and reviewed. Round 2's 23-scenario report was generated locally and summarized in docs/CALIBRATION_ROUND2.md. The canonical STARTER parameter profile has not been calibrated or changed.
- **Temporal task V1:** user-run held-out accuracy was FIXED 80%, STDP 90%, R-STDP 80% on one small deterministic challenge. This was preliminary evidence, not proof of generalization.
- **Temporal robustness V2:** user-run held-out accuracy was FIXED 74.375%, STDP 66.25%, rewarded R-STDP 74.375%, neutral R-STDP 74.375%. Rewarded and neutral R-STDP predictions matched on all four seeds; task-level reward benefit was not demonstrated. Details: docs/TASK_VALIDATION_TEMPORAL_V2_RESULTS.md.
- **Temporal robustness V3:** user-run held-out accuracy was FIXED 100%, STDP 98.75%, R-STDP 100%, neutral R-STDP 100%. All 128 training reward modulators were positive, so negative reward correction was not tested. FAST delivered three pulses before the 7 ms deadline while SLOW delivered two; V3 therefore tested deadline-bounded event-density/latency, not same-count interval discrimination. Details: docs/TASK_VALIDATION_TEMPORAL_V3_RESULTS.md.
- **V4 — R-STDP feedback/error recovery:** the exploratory two-seed pilot completed with 12/12 dedicated tests passing. Held-out accuracy: FIXED 81.84%, STDP 100%, R-STDP 83.01%, neutral R-STDP 81.84%. R-STDP used both reward signs, but all 1,355 non-zero reward-driven updates depressed the weight; task-level benefit over FIXED was small (+1.17 percentage points, 6 fewer false positives), while STDP was best. The full pre-registered profile remains pending. Canonical STARTER weight 0.5 is unchanged; 0.70 is only the shared diagnostic initial weight. Details: docs/TASK_VALIDATION_R_STDP_V4_PILOT_RESULTS.md.
- **V4.1 alignment audit:** user-run Android Termux verification: **15/15 dedicated tests passed** and the two-seed pilot completed 8/8 conditions. R-STDP recorded 2,048 feedback events and 0 positive / 1,355 negative / 693 zero weight updates. The cross-tab showed correct FAST outcomes with negative eligibility (therefore depressing the weight), false-positive SLOW→FAST outcomes with positive eligibility (also depressing it), and correct SLOW outcomes with zero eligibility. Baseline/modulator and product/delta sign checks were consistent. Full repository suite after V4.1 remains NOT RUN. Details: docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md.
- **V5 Compact Adaptive Neuron Core:** the architecture proposal is in `docs/JARVIS_COMPACT_NEURON_CORE_V5.md`. The V5.1 reference resource-baseline harness (`tools/compact_core_v5/resource_baseline.py`) passed 6/6 unit tests and its small 1/10-item smoke profile ran in a Linux x86_64 Python 3.13.5 container. This measures only the Python Oracle reference representation; there is no packed candidate, Android-device baseline, or demonstrated memory benefit.
- **SuperNeuron research track:** BTCC (Boundary-Triggered Counterfactual Credit) has an implemented deterministic reference replay oracle in `tools/super_neuron_btcc/credit_oracle.py`; focused tests passed 17/17 in the local Python 3.13.5 container. It estimates leave-one-event-out credit and compares its direction with central finite-difference gradients. A documented redundancy counterexample shows a known blind spot. BTCC is not integrated into the learning rule and has no task-level accuracy or learning improvement established.
- **V4.2 causal-credit audit:** user-run Android Termux verification: **6/6 dedicated tests passed**; smoke (128 episodes) and pilot (2,048 episodes) completed. In pilot, R-STDP produced 1,355 negative, 0 positive and 693 zero updates. Correct FAST episodes had negative eligibility despite positive feedback; false-positive SLOW→FAST episodes had positive eligibility with negative feedback; correct SLOW episodes had zero eligibility. The 1 ns shadow changed aggregate eligibility magnitude/sign balance but did not change any per-episode eligibility or weight-update signs. This diagnoses a temporal-credit alignment limitation, not a proven fix. Details: docs/TASK_VALIDATION_R_STDP_V4_2_CREDIT_AUDIT.md and docs/TASK_VALIDATION_R_STDP_V4_2_RESULTS.md.
- **V4.3 decision-boundary temporal credit:** focused tests passed (7/7, 1.636 s), and smoke/pilot completed on Android Termux. In the two-seed pilot, Decision-Latched reached 82.23% held-out accuracy versus 83.01% for frozen V4 R-STDP and 100% for STDP. It processed all sensory inputs and gated exactly 1,024 post-decision plasticity events, but did not improve held-out quality. It remains experimental, not the default. Details: docs/TASK_VALIDATION_R_STDP_V4_3.md and docs/TASK_VALIDATION_R_STDP_V4_3_RESULTS.md.

- **Not implemented yet:** production neuron/NCC runtime, scheduler-owned clock-epoch continuity/rebase policy, atomic combined NetworkRuntime+RewardBaseline Runtime checkpoint and cross-domain feedback/weight transaction, CQE inbox/outbox/session persistence and durable event delivery, in-place JPVR repair/rollback, compaction/free-page reuse, full capability scopes/resource budgets, production-grade scheduler guarantees, and Reasoning Engine execution. One-neuron and whole-network checkpoint stores are bounded Python reference APIs, not production JNVM services. Non-destructive recovery-copy publication remains a Python reference API, not automatic restoration or production repair.
- **Not measured or validated yet:** target-device runtime performance, RAM/CPU/energy/I/O, float32 runtime equivalence, and JPVR crash consistency.
- No application builds, APK/AAB generation, CI runs or GitHub Actions workflows were performed for these experiments.

## Repository map
| `tools/neuron_runtime/task_session.py` | Bounded task/session lifecycle registry and canonical snapshot codec (not durable JPVR storage) |
| `tools/neuron_runtime/test_task_session.py` | Task/session lifecycle, CAS, idempotency, capacity and snapshot tests |
| `docs/JARVIS_TASK_SESSION_LIFECYCLE_V1.md` | Task/session lifecycle V1 contract and limitations |

| Path | Purpose |
|---|---|
| `docs/JARVIS_ARCHITECTURE.md` | Main architecture specification, decisions, open questions and development plan |
| `docs/TESTING_POLICY.md` | Required test practices, verification evidence and result labels |
| `docs/DEVELOPMENT_LOG.md` | Append-only record of changes, tests, experiments and limitations |
| `docs/CALIBRATION_ROUND2.md` | Round 1 observations, Round 2 scenarios and verification record |
| `docs/TASK_VALIDATION_TEMPORAL_V1.md` | Initial task-level and V1 protocols/results |
| `docs/TASK_VALIDATION_TEMPORAL_V2.md` | V2 protocol |
| `docs/TASK_VALIDATION_TEMPORAL_V2_RESULTS.md` | V2 results and limitations |
| `docs/TASK_VALIDATION_TEMPORAL_V3.md` | V3 protocol |
| `docs/TASK_VALIDATION_TEMPORAL_V3_RESULTS.md` | V3 results and task interpretation |
| `docs/TASK_VALIDATION_R_STDP_V4.md` | V4 signed-feedback and error-recovery protocol |
| `docs/TASK_VALIDATION_R_STDP_V4_PILOT_RESULTS.md` | Exploratory V4 pilot metrics, interpretation and limitations |
| `docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md` | V4.1 eligibility/reward alignment audit protocol and user-run results |
| `docs/TASK_VALIDATION_R_STDP_V4_2_CREDIT_AUDIT.md` | V4.2 per-pulse eligibility and shadow event-order diagnostic protocol |
| `docs/TASK_VALIDATION_R_STDP_V4_2_RESULTS.md` | User-run V4.2 results and causal-credit interpretation |
| `docs/TASK_VALIDATION_R_STDP_V4_3.md` | V4.3 decision-latched temporal-credit protocol |
| `docs/TASK_VALIDATION_R_STDP_V4_3_RESULTS.md` | V4.3 execution status and result summary instructions |
| `docs/JARVIS_COMPACT_NEURON_CORE_V5.md` | V5 compact-state architecture proposal and acceptance gates |
| `docs/JARVIS_SUPER_NEURON_BTCC_RESEARCH.md` | SuperNeuron BTCC learning-quality research hypothesis and acceptance plan |
| `tools/super_neuron_btcc/credit_oracle.py` | BTCC isolated event replay, counterfactual scores and gradient comparator |
| `tools/super_neuron_btcc/test_credit_oracle.py` | BTCC mathematical and failure-mode tests |
| `tools/compact_core_v5/resource_baseline.py` | V5.1 reference-object and process-resource baseline harness |
| `tools/compact_core_v5/test_resource_baseline.py` | V5.1 baseline harness unit tests |
| `tools/numerical_oracle/` | Float64 reference equations, sweeps and tests |
| `tools/neuron_runtime/` | One-neuron coordinator, bounded multi-neuron event loop, JPVR checkpoint adapter and tests |
| `docs/JARVIS_NETWORK_RUNTIME_V1.md` | Multi-neuron event-loop reference contract, queue limits and verification scope |
| `docs/JNVM_NEURON_RUNTIME_JPVR_ADAPTER_V1.md` | Versioned one-neuron checkpoint payload/schema and JPVR DATA/BLOB adapter contract |
| `docs/JNVM_NEURON_CHECKPOINT_STORE_V1.md` | Neuron-index locator, atomic JPVR save/load protocol and validation boundary |
| `tools/neuron_runtime/jpvr_store.py` | Bounded JPVRNeuronCheckpointStore facade for indexed one-neuron checkpoint persistence |
| `tools/neuron_runtime/network_adapter.py` | Canonical versioned snapshot codec for all NetworkRuntime state |
| `tools/neuron_runtime/network_store.py` | Bounded JPVR BLOB+CHECKPOINT persistence for one active network per container |
| `docs/JNVM_NETWORK_CHECKPOINT_STORE_V1.md` | Network topology, pending-event queue and scheduler-clock checkpoint contract |
| `tools/neuron_runtime/reward_baseline.py` | Ordered per-task R-STDP reward baseline owner with prepare/commit and bounded deduplication |
| `tools/neuron_runtime/reward_baseline_adapter.py` | Canonical versioned Reward Baseline Manager snapshot codec |
| `tools/neuron_runtime/reward_baseline_store.py` | Dedicated-container JPVR BLOB+CHECKPOINT persistence with ancestry and CAS validation |
| `tools/neuron_runtime/test_reward_baseline.py` | Reward ownership, ordered feedback, idempotency and snapshot codec tests |
| `tools/neuron_runtime/test_reward_baseline_store.py` | JPVR save/load, CAS, reopen and corruption tests |
| `docs/JNVM_REWARD_BASELINE_CHECKPOINT_STORE_V1.md` | Reward feedback sequence, pending processing, snapshot and persistence contract |
| `tools/neuron_runtime/test_jpvr_store.py` | Focused locator, DATA/BLOB, commit-chain and persistence regression tests |
| `tools/task_validation/` | V1–V4.3 deterministic task benchmarks, audits and tests |
| `tools/cqe_reference/` | Partial executable reference for CQE envelopes, authorization, queues, commits and events |
| `tools/jpvr_reference/` | JPVR_FORMAT_V1 codecs/planners, batch COW INDEX mutation, transaction builder, and file-backed two-Superblock storage and historical recovery scanner |
| `docs/JPVR_COW_WRITER_V1.md` | COW upsert planner contract, validation invariants, test command and explicit non-goals |
| `docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md` | Typed transaction builder and integrated COW mutation pipeline contract |
| `docs/JPVR_STORAGE_ENGINE_V1.md` | Physical file layout, ordered page writes, Superblock publication, reopen and orphan-tail recovery |
| `docs/JPVR_HISTORICAL_RECOVERY_V1.md` | Streaming page/transaction audit, historical MANIFEST validation and safe-prefix diagnostics |
| `docs/JPVR_RECOVERY_REPAIR_V1.md` | Explicit, non-destructive recovery-copy publication and failure model |
| `docs/JPVR_COMMIT_V1.md` | JCOM v1 fixed COMMIT payload, snapshot checks and non-goals |
| `docs/JPVR_JOURNAL_V1.md` | JNRL v1 operation-state entries, immutable history links and lifecycle rules |
| `docs/JPVR_OUTBOX_V1.md` | JOUT v1 immutable event/page and sequence contract |
| `docs/JPVR_OUTBOX_SCAN_V1.md` | Read-only validation of active snapshot, COMMIT chain and committed OUTBOX history |
| `AGENTS.md` | Mandatory contributor/agent instructions and project constraints |

## Run the comprehensive one-neuron exam

To check the implemented neuron as a whole in one run, use:

    python3 -m tools.neuron_validation.full_exam

This is the main scientific/engineering exam. It runs the complete Python unit-test suite, syntax and discovery checks, a deterministic noisy temporal generalization benchmark (FIXED/STDP/R-STDP/neutral controls with separate held-out data and unseen temporal gap bands), and the V5.1 Python-reference resource baseline. It writes neuron_full_exam_report.json and exits non-zero if an automated stage fails.

The default pilot profile uses two seeds, 128 training pairs and 64 held-out pairs per seed at each of three distractor levels (0/2/4). Use --profile smoke for a quick integration check or --profile full for a larger four-seed experiment (0/2/4/6 distractors, up to 10,000 reference objects):

    python3 -m tools.neuron_validation.full_exam --profile smoke
    python3 -m tools.neuron_validation.full_exam --profile full

The report explicitly distinguishes tested mechanisms from NOT_IMPLEMENTED features. It does not claim to test long-term memory, predictive surprise, fatigue/recovery, structural plasticity, JPVR persistence, a packed V5 candidate, a production runtime or Android performance. No learning rule is promoted automatically by the benchmark.

## Run the first-neuron coordinator tests

```sh
python3 -m unittest tools.neuron_runtime.test_runtime -v
```

The first-neuron focused suite is included in the 216/216 full Termux run recorded above. The separate network coordinator test module is tracked below.

## Run the JPVR neuron-runtime checkpoint adapter tests

```sh
python3 -m unittest tools.neuron_runtime.test_jpvr_adapter -v
```

The adapter round-trips one complete NeuronRuntime checkpoint into a versioned JPVR DATA/JREC or BLOB record and restores it after validation. It preserves the caller's 128-bit model-clock epoch. Its 17 tests are AUTHORED, NOT RUN. The adapter remains the record codec; indexed durable persistence is provided by the separate JPVRNeuronCheckpointStore facade below.

## Run indexed JPVR neuron checkpoint-store tests

```sh
python3 -m unittest tools.neuron_runtime.test_jpvr_store -v
```

The 17 store tests are AUTHORED, NOT RUN. JPVRNeuronCheckpointStore initializes seven empty INDEX roots for a genesis container (after preflighting the nine-page minimum catalog bound), then writes the checkpoint record and its versioned neuron-index locator in one JPVR COMMIT. Load resolves the active neuron key, proves the source COMMIT is an ancestor of the active snapshot, verifies the DATA/BLOB page range and record metadata/CRCs, and restores the runtime. The API is bounded by a materialized page catalog (default 4096 pages); it does not persist NetworkRuntime queues, topology or external reward-baseline state. Contract: docs/JNVM_NEURON_CHECKPOINT_STORE_V1.md.

## Run bounded multi-neuron network tests

```sh
python3 -m unittest tools.neuron_runtime.test_network -v
```

The network test module currently has 15 tests, including a regression that prevents events earlier than a restored synapse reward/trace timestamp. They are authored but not yet run against the real Numerical Oracle. The previously reported 216/216 Termux run predates this network module and therefore does not verify the new network layer.

## Run all automated Python checks

From the repository root, run one command:

```sh
python3 -m tools.run_all_checks
```

The master runner performs three checks: (1) compiles every Python source under `tools/` without executing it, (2) checks that every `test*.py` module is discoverable by `unittest`, and (3) discovers and runs the complete Python unit-test suite. New test modules are included automatically when they follow the `test*.py` convention and reside in Python packages.

To save a machine-readable report:

```sh
python3 -m tools.run_all_checks --json-report reports/master_test_report.json
```

A non-zero exit code means a syntax, discovery, or unit-test check failed. This command is the routine all-unit-tests entry point; focused modules may still be run directly while debugging. It does **not** run task-level research benchmarks, parameter sweeps, Android/Termux measurements, application builds, APK/AAB packaging, CI, or GitHub Actions. Those remain separate evidence gates and are never silently treated as passed.

## Run the V4 R-STDP feedback/error-recovery benchmark

```sh
# Exploratory phone-friendly pilot (512/128 pairs, two seeds)
python3 -m tools.task_validation.rstdp_feedback_v4 --profile pilot > rstdp_feedback_v4_pilot_report.json

# Full pre-registered benchmark (8000/256 pairs, four seeds)
python3 -m tools.task_validation.rstdp_feedback_v4 --profile full > rstdp_feedback_v4_report.json
```

The CLI prints progress to stderr while keeping the final JSON on stdout, so redirecting stdout to a report file does not hide progress. The pilot is exploratory and reduced-size; it must not be reported as the full pre-registered result. The JSON now includes the V4.1 per-episode eligibility/reward alignment cross-tab and reward-baseline reconciliation automatically. The full profile is the default. V4 first probes positive and negative eligibility against positive, negative and neutral reward modulation. Its task-level recovery track compares FIXED, STDP, rewarded R-STDP and neutral R-STDP on identical same-count three-pulse data and multiple deterministic seeds. It uses the canonical STARTER weight 0.5 only for a read-only baseline probe. The separately declared diagnostic recovery track starts all four conditions at weight 0.70; that perturbation is not a STARTER change or calibration.

The evaluator supplies reward 1.0 for a correct training decision and 0.0 for an incorrect one. Held-out examples are inference-only. A weight change alone is not success: compare held-out confusion metrics against FIXED and neutral R-STDP across seeds, and also report STDP even if it performs better. The protocol and limitations are in docs/TASK_VALIDATION_R_STDP_V4.md.


## Run the V4.2 pulse-level R-STDP credit audit

~~~sh
# Small smoke profile (32 training pairs, two seeds)
python3 -m tools.task_validation.rstdp_causal_credit_v42 --profile smoke > rstdp_causal_credit_v42_smoke.json

# Phone-friendly pilot (512 training pairs, two seeds)
python3 -m tools.task_validation.rstdp_causal_credit_v42 --profile pilot > rstdp_causal_credit_v42_pilot.json

# Dedicated V4.2 contract tests
python3 -m unittest tools.task_validation.test_rstdp_causal_credit_v42 -v
~~~

V4.2 replays only the V4 R-STDP training split. It records per-pulse pair traces and eligibility contributions, and compares the existing batch update with a separate 1 ns pre→post shadow ordering. The shadow is diagnostic only and cannot change actual training. The full profile is deliberately not the default; start with smoke or pilot on a constrained phone. V4.2 is not a held-out learning benchmark and cannot justify a learning-rule change by itself.

## V5.1 reference resource baseline

```sh
python3 -m unittest tools.compact_core_v5.test_resource_baseline -v
python3 -m tools.compact_core_v5.resource_baseline --profile smoke > v5_reference_baseline_smoke.json
python3 -m tools.compact_core_v5.resource_baseline --profile pilot > v5_reference_baseline_pilot.json
```

The harness measures only the current Numerical Oracle representation. It does not benchmark a compact candidate or claim total runtime savings. Run smoke first; pilot includes up to 1,000 records per class. The 10,000-item full profile is not the first phone run.

## Run the V4.3 decision-boundary temporal-credit experiment

```sh
python3 -m unittest tools.task_validation.test_rstdp_feedback_v4_3 -v
python3 -m tools.task_validation.rstdp_feedback_v4_3 --profile smoke > rstdp_feedback_v4_3_smoke.json
python3 -m tools.task_validation.rstdp_feedback_v4_3 --profile pilot > rstdp_feedback_v4_3_pilot.json
```

The smoke profile is deliberately small. The decision-latched condition continues processing each sensory input; it gates only later R-STDP updates after the first output spike. Review the protocol and result-status document before interpreting the report. Smoke and pilot are exploratory and do not replace the full pre-registered profile.

## Run historical temporal benchmarks

V3:

```sh
python3 -m tools.task_validation.temporal_robustness_v3 > temporal_robustness_v3_report.json
```

V2:

```sh
python3 -m tools.task_validation.temporal_robustness_v2 > temporal_robustness_v2_report.json
```

These are historical reference experiments. Their recorded results and limitations are in the corresponding V2/V3 documents.

## Other reference experiments

Initial burst-vs-sparse benchmark:

```sh
python3 -m tools.task_validation.temporal_patterns
```

Fixed-baseline boundary characterization:

```sh
python3 -m tools.task_validation.baseline_boundary > baseline_boundary_report.json
```

Round 1 exploratory parameter sweep:

```sh
python3 -m tools.numerical_oracle.calibration_sweeps > sweep.json
```

Round 2 behavioral characterization:

```sh
python3 -m tools.numerical_oracle.calibration_round2 > calibration_round2.json
```

These commands are reference experiments/diagnostics. They do not calibrate or modify the canonical STARTER profile.

## Important engineering boundaries

- JPVR is persistent storage backed by a physical device; it is not additional physical RAM.
- Python packages under `tools/` are reference/validation tools, not the production JARVIS runtime.
- A passing unit-test suite verifies only the assertions covered by those tests. It does not prove task usefulness, biological equivalence, consciousness, general intelligence or production readiness.
- Keep the original `JARVIS_SINGLE_NEURON_STARTER_V1` parameter set immutable. Any future calibrated profile must have its own version and reproducible evidence.
- Do not run application builds or GitHub Actions without explicit user permission. See `AGENTS.md`.

For detailed status, unresolved decisions and the next implementation gates, start with [the architecture document](docs/JARVIS_ARCHITECTURE.md).


## Run JPVR reference tests

Focused COMMIT descriptor tests:

```sh
python3 -m unittest tools.jpvr_reference.test_commit \
 tools.jpvr_reference.test_journal -v
```

Focused JOURNAL tests:

```sh
python3 -m unittest tools.jpvr_reference.test_journal -v
```

Focused OUTBOX codec/genesis tests:

```sh
python3 -m unittest tools.jpvr_reference.test_outbox -v
```

Focused committed-history scanner tests:

```sh
python3 -m unittest tools.jpvr_reference.test_outbox_scan -v
```

Complete JPVR reference suite:

```sh
python3 -m unittest tools.jpvr_reference.test_codec tools.jpvr_reference.test_records tools.jpvr_reference.test_blob tools.jpvr_reference.test_data tools.jpvr_reference.test_layout tools.jpvr_reference.test_manifest tools.jpvr_reference.test_index tools.jpvr_reference.test_cow tools.jpvr_reference.test_commit tools.jpvr_reference.test_journal tools.jpvr_reference.test_outbox tools.jpvr_reference.test_outbox_scan tools.jpvr_reference.test_transaction tools.jpvr_reference.test_typed_transaction tools.jpvr_reference.test_storage tools.jpvr_reference.test_recovery tools.jpvr_reference.test_recovery_copy -v
```

The previously documented baseline contained 262 methods across twelve modules. Since then, 16 transaction-assembly, 19 typed-builder, 11 integrated COW, 19 storage, 23 historical-recovery, and 12 recovery-copy test methods were added, for **362 methods across seventeen modules**. These 100 newer methods are **AUTHORED, NOT RUN** as a combined post-change suite; the complete command above includes all seventeen modules. See the [COW contract](docs/JPVR_COW_WRITER_V1.md), [JCOM contract](docs/JPVR_COMMIT_V1.md), [JOURNAL contract](docs/JPVR_JOURNAL_V1.md), [OUTBOX contract](docs/JPVR_OUTBOX_V1.md), [OUTBOX scanner contract](docs/JPVR_OUTBOX_SCAN_V1.md), [transaction assembly contract](docs/JPVR_TRANSACTION_ASSEMBLY_V1.md) and [typed transaction builder contract](docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md).


## JPVR transaction assembly planner

Focused transaction assembly tests:

```sh
python3 -m unittest tools.jpvr_reference.test_transaction -v
```

The in-memory planner assembles encoded staged pages, a final MANIFEST, and a final COMMIT into one validated append plan. It checks page numbering/generation/transaction identity, COMMIT and previous-COMMIT linkage, all MANIFEST roots/high-water references, and the candidate inactive Superblock. Contract: [JPVR_TRANSACTION_ASSEMBLY_V1.md](docs/JPVR_TRANSACTION_ASSEMBLY_V1.md). This is a reference planner only: no physical writes, flush/sync, Superblock publication, locking, or crash recovery.


The 2026-11-10 CQE priority-aging and JPVR OUTBOX scanner regressions were subsequently corrected; the user has confirmed that no errors remain in their current full-suite run. The 16 transaction-assembly tests, 19 typed-builder tests, 11 integrated COW tests, 19 storage tests and 22 historical-recovery tests remain AUTHORED, NOT RUN; they require focused execution on Termux.


## JPVR typed transaction builder

Focused typed builder tests:

```sh
python3 -m unittest tools.jpvr_reference.test_typed_transaction tools.jpvr_reference.test_cow -v
```

The builder accepts typed JREC, BLOB, INDEX, JOURNAL and OUTBOX inputs; packs DATA records without splitting them, fragments large BLOB records, resolves JOURNAL/OUTBOX links to the final COMMIT page, and emits MANIFEST plus final COMMIT. `IndexMutationInput` invokes one batch COW rewrite per affected tree, reuses unchanged subtrees, creates new INDEX pages, and updates the affected MANIFEST roots. It revalidates generated page families and delegates final snapshot validation to the transaction assembly planner. Contract: [JPVR_TYPED_TRANSACTION_BUILDER_V1.md](docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md). This remains in-memory only; it does not reserve physical pages, write durable storage, flush/sync, publish Superblock slots or recover after crashes.


## JPVR file-backed storage engine V1

Focused storage and recovery tests:

```sh
python3 -m unittest tools.jpvr_reference.test_storage -v
```

Create or reopen a container with `JPVRStorage.create(path)` / `JPVRStorage.open(path)`. Use `load_snapshot(max_pages=4096)` to obtain one active Superblock and its materialized bounded catalog under a single shared lock, or `load_page_catalog(max_pages=4096)` when only the catalog is needed. Submit a `TypedTransactionBuildPlan` to `store.commit(plan)`. The default catalog cap is 4096 pages (16 MiB encoded bytes plus Python mapping overhead); increase it only after considering available RAM.

Storage commits append every immutable transaction page and call `fsync` before writing the inactive Superblock slot. The new slot is synced and re-read before success is returned. A `JPVRCommitUncertainError` means publication began but its final outcome could not be confirmed: reopen the file and inspect `active_commit_id` before retrying. Contract and failure model: [JPVR_STORAGE_ENGINE_V1.md](docs/JPVR_STORAGE_ENGINE_V1.md). This is a POSIX/Android reference implementation; it does not provide automatic prefix rollback, compaction, CQE event delivery, or a guarantee against hardware that falsely reports completed flushes.


## JPVR historical recovery scanner

Focused tests:

```sh
python3 -m unittest tools.jpvr_reference.test_recovery -v
```

JPVRStorage.scan_history() uses a lazy file-backed page catalog rather than load_page_catalog(). It reads the durable prefix in physical order, verifies every page envelope, each generation's contiguous append range and transaction identity, final MANIFEST/COMMIT ordering, every historical MANIFEST's roots and high-water references, and the committed OUTBOX chain. It returns one JPVRHistoricalSnapshot per verified generation and verified_durable_tail_page_no as the last safe commit boundary.

A damaged later generation does not erase earlier verified snapshots from the report. issue describes the first detected fault. Bytes beyond the active durable tail are reported as orphan_tail_bytes; scanning is read-only and does not truncate, rewrite a Superblock, or claim to have restored that prefix. Set verify_index_trees=True to traverse all seven B+ trees in every historical snapshot; this may be expensive. Contract: docs/JPVR_HISTORICAL_RECOVERY_V1.md.

## JPVR non-destructive recovery copy V1

Focused tests:

```sh
python3 -m unittest tools.jpvr_reference.test_recovery_copy -v
```

After scan_history(), call store.recover_copy("recovered.jpvr", expected_commit_id=report.last_verified_snapshot.commit.record.commit_id). The operation performs its own locked rescan; the supplied identity must match the last verified snapshot. It refuses an invalid or unverifiable OUTBOX prefix and any existing destination, copies only through the verified COMMIT page, reconstructs the active and immediately previous Superblock slots, fsyncs and reads back the temporary copy, then publishes it without replacing an existing path. The original file is not modified. A JPVRRecoveryCopyUncertainError means the destination may already be published; inspect it before retrying. The operation requires same-directory hard-link support. Twelve focused tests are AUTHORED, NOT RUN.


## Save and restore the complete multi-neuron network

    python3 -m unittest tools.neuron_runtime.test_network_adapter -v
    python3 -m unittest tools.neuron_runtime.test_network_store -v

JPVRNetworkCheckpointStore(storage).save(network_id, network, clock_epoch_id=...) writes one canonical JNNW BLOB plus one JNCP CHECKPOINT page in the same JPVR COMMIT. The active MANIFEST points to that page. load(network_id) checks source COMMIT ancestry, BLOB/page transaction metadata, schema, identity and CRC before restoring the entire NetworkRuntime, including delayed pending events and scheduler clock. There is one active network checkpoint pointer per JPVR container. Eleven codec tests and fourteen store tests are AUTHORED, NOT RUN. Contract: docs/JNVM_NETWORK_CHECKPOINT_STORE_V1.md.


## Persist R-STDP reward baselines

Focused reference tests (authored, not run):

```sh
python3 -m unittest tools.neuron_runtime.test_reward_baseline -v
python3 -m unittest tools.neuron_runtime.test_reward_baseline_store -v
```

`RewardBaselineManager` owns a bounded baseline per `reward_task_id`. A proposal is bound to its manager ID and clock epoch; a feedback ID cannot be reused at a different sequence inside retained task history. A pending proposal is bound to its manager ID and clock epoch; a feedback ID cannot be reused at a different sequence inside retained task history. `prepare_feedback()` validates clock epoch, monotonic model time, reward range and the exact next feedback sequence, then reserves an immutable proposal using the previously committed baseline. The baseline changes only when `commit_feedback()` receives a terminal synapse-processing status (`APPLIED`, `APPLIED_NO_CHANGE`, `NO_ELIGIBILITY`, `EXPIRED` or `DISABLED_NO_OP`). Exact retries are idempotent within the retained receipt window; gaps are deferred and conflicting replays are rejected.

`JPVRRewardBaselineStore(storage).save(manager)` writes a canonical JRBM BLOB plus a JRCP CHECKPOINT descriptor and publishes both with one MANIFEST/COMMIT. It verifies source COMMIT ancestry, object/schema/version identity, page bounds and CRC, and supports `expected_record_version` compare-and-swap. Use a **dedicated JPVR container** for this V1 store: the MANIFEST has one active checkpoint pointer, so saving the reward manager into the network container would replace its network checkpoint. The standalone manager store does not coordinate NetworkRuntime. The later JRCM composite store and `process_reward_feedback()` workflow provide a combined path; see the two sections below. Contract: `docs/JNVM_REWARD_BASELINE_CHECKPOINT_STORE_V1.md`.

The 26 manager/codec tests and 15 JPVR store tests are **AUTHORED, NOT RUN**. This standalone manager store is not itself a reward-to-synapse transaction or combined Runtime checkpoint; those are separate later layers described below.


## Atomic composite JARVIS Runtime checkpoint

```sh
python3 -m unittest tools.neuron_runtime.test_runtime_store -v
```

`JPVRRuntimeCheckpointStore(storage).save(network_id, network, reward_baseline_manager)` stores complete NetworkRuntime and RewardBaselineManager snapshots under one JRCM V1 CHECKPOINT page and one JPVR COMMIT. Both component records share the same commit generation, transaction ID and caller-owned model clock epoch. `load(network_id, manager_id)` validates exact source COMMIT ancestry, both component locators, DATA/BLOB schemas and CRCs, and matching clock epochs before restoring either object. `expected_runtime_version` protects the pair with one compare-and-swap version.

Component adapters choose DATA for payloads up to 3976 bytes and BLOB for larger payloads. Standalone network and reward-baseline stores accept both forms too. Use a dedicated runtime JPVR container for JRCM; do not share it with standalone JNCP/JRCP pointers. This layer provides atomic checkpoint publication only. The higher-level `process_reward_feedback()` coordinator now prepares candidate synapse/manager state and publishes both through this composite store. Twelve composite-store tests are AUTHORED, NOT RUN. Contract: `docs/JNVM_COMPOSITE_RUNTIME_CHECKPOINT_V1.md`.


## Run the atomic R-STDP feedback transaction tests

```sh
python3 -m unittest tools.neuron_runtime.test_feedback_transaction -v
```

`process_reward_feedback()` clones the network and baseline manager, applies all selected synapse rewards to candidate state, and commits both snapshots through one composite JRCM JPVR transaction. The function leaves caller objects untouched; replace them with the returned network and manager after success. The runtime version is mandatory for compare-and-swap. See `docs/JNVM_ATOMIC_REWARD_FEEDBACK_TRANSACTION_V1.md`.


## Durable CQE reward-feedback inbox

process_cqe_reward_feedback() preflights all targets on a detached network copy, then persists the normalized reward EVENT and its target/context digest as a pending JRCM checkpoint before applying synapse rewards to authoritative state. The completed receipt, baseline advance and network updates are then published together. After interruption, reload the active JRCM checkpoint and retry the exact envelope with its latest runtime version. This adapter handles reward-feedback only; upstream source authorization and external schema registration are required; general CQE events remain volatile. Contract: docs/JNVM_CQE_REWARD_FEEDBACK_INBOX_V1.md. Twelve focused tests are **AUTHORED, NOT RUN**.


## Task & Session Lifecycle V1

Run focused reference tests (authored, not run):

\`\`\`sh
python3 -m unittest tools.neuron_runtime.test_task_session -v
\`\`\`

The bounded TaskSessionManager provides explicit session/task states, revision checks, idempotent task creation, ordered lifecycle events and a canonical snapshot codec. It is a single-writer Python reference registry. Snapshot encoding is not durable persistence: no JPVR Task/Session checkpoint store or restart recovery is implemented yet. Contract: docs/JARVIS_TASK_SESSION_LIFECYCLE_V1.md. Fifteen tests are **AUTHORED, NOT RUN**.


### Task & Session JPVR Persistence V1 (12 Nov 2026)

TaskSessionManager now has a versioned JPVR DATA/BLOB adapter and a CAS-protected checkpoint store. It commits the snapshot record and CHECKPOINT descriptor together, validates source COMMIT ancestry and payload CRC on load, and restores the last committed task/session registry after reopening the JPVR file. Use a **dedicated JPVR container**; the format currently has one active CHECKPOINT pointer. This does not provide exactly-once external tool execution, scheduler leases, authorization persistence, or multi-process locking. Contract: [JARVIS_TASK_SESSION_JPVR_STORE_V1](docs/JARVIS_TASK_SESSION_JPVR_STORE_V1.md). Nine focused tests are authored, not run; no builds or CI were run.
