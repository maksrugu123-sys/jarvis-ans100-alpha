# JARVIS ANS-100 Alpha

**JARVIS ANS-100 Alpha** is a research project for a compact adaptive neural system. Development is staged: begin with one artificial neuron and a small number of synapses, validate behavior and resource cost, and scale only when each previous step is supported by evidence.

The repository is at the **architecture and reference-experiment stage**. It is not yet a production neural runtime or a finished assistant.

## Current status — 8 November 2026

- **Architecture:** the first-neuron design, Adaptive LIF-DT model, excitatory STDP/R-STDP rules, CQE v1 contract and JPVR_FORMAT_V1 storage format are specified in the architecture document.
- **Numerical Oracle:** a deterministic Python/float64 reference implementation exists for Adaptive LIF-DT, STDP and R-STDP. It is a research oracle, not the production runtime.
- **CQE reference:** a partial, single-threaded Python contract reference exists. Its commit store and event inbox/outbox are volatile; durable JPVR integration is not implemented.
- **Latest test evidence:** before V4, the user ran 107 tests. Dedicated V4, V4.1 and V4.2 suites passed (12, 15 and 6 tests). On 8 November, V4.3 focused tests passed (7 tests, 1.636 s); smoke and pilot completed. Pilot held-out accuracy: FIXED 81.84%, STDP 100%, frozen R-STDP V4 83.01%, Decision-Latched 82.23%, Neutral 81.84%. Decision-Latched processed all 6,144 inputs and skipped 1,024 post-decision updates, but did not beat V4. The full repository suite remains NOT RUN after V4/V4.1/V4.2/V4.3.
- **Calibration:** Round 1's 99-scenario sweep was generated and reviewed. Round 2's 23-scenario report was generated locally and summarized in docs/CALIBRATION_ROUND2.md. The canonical STARTER parameter profile has not been calibrated or changed.
- **Temporal task V1:** user-run held-out accuracy was FIXED 80%, STDP 90%, R-STDP 80% on one small deterministic challenge. This was preliminary evidence, not proof of generalization.
- **Temporal robustness V2:** user-run held-out accuracy was FIXED 74.375%, STDP 66.25%, rewarded R-STDP 74.375%, neutral R-STDP 74.375%. Rewarded and neutral R-STDP predictions matched on all four seeds; task-level reward benefit was not demonstrated. Details: docs/TASK_VALIDATION_TEMPORAL_V2_RESULTS.md.
- **Temporal robustness V3:** user-run held-out accuracy was FIXED 100%, STDP 98.75%, R-STDP 100%, neutral R-STDP 100%. All 128 training reward modulators were positive, so negative reward correction was not tested. FAST delivered three pulses before the 7 ms deadline while SLOW delivered two; V3 therefore tested deadline-bounded event-density/latency, not same-count interval discrimination. Details: docs/TASK_VALIDATION_TEMPORAL_V3_RESULTS.md.
- **V4 — R-STDP feedback/error recovery:** the exploratory two-seed pilot completed with 12/12 dedicated tests passing. Held-out accuracy: FIXED 81.84%, STDP 100%, R-STDP 83.01%, neutral R-STDP 81.84%. R-STDP used both reward signs, but all 1,355 non-zero reward-driven updates depressed the weight; task-level benefit over FIXED was small (+1.17 percentage points, 6 fewer false positives), while STDP was best. The full pre-registered profile remains pending. Canonical STARTER weight 0.5 is unchanged; 0.70 is only the shared diagnostic initial weight. Details: docs/TASK_VALIDATION_R_STDP_V4_PILOT_RESULTS.md.
- **V4.1 alignment audit:** user-run Android Termux verification: **15/15 dedicated tests passed** and the two-seed pilot completed 8/8 conditions. R-STDP recorded 2,048 feedback events and 0 positive / 1,355 negative / 693 zero weight updates. The cross-tab showed correct FAST outcomes with negative eligibility (therefore depressing the weight), false-positive SLOW→FAST outcomes with positive eligibility (also depressing it), and correct SLOW outcomes with zero eligibility. Baseline/modulator and product/delta sign checks were consistent. Full repository suite after V4.1 remains NOT RUN. Details: docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md.
- **V5 Compact Adaptive Neuron Core:** the architecture proposal is in `docs/JARVIS_COMPACT_NEURON_CORE_V5.md`. V5.1 begins with a reference resource-baseline harness (`tools/compact_core_v5/resource_baseline.py`) to measure Oracle object graphs, pickle diagnostic size, tracemalloc and Android/Linux RSS. It is authored but NOT RUN; no memory benefit or compact candidate is claimed.
- **SuperNeuron research track:** BTCC (Boundary-Triggered Counterfactual Credit) has a deterministic replay oracle in `tools/super_neuron_btcc/credit_oracle.py`. Its focused tests are AUTHORED / NOT RUN. The first version estimates leave-one-event-out credit and compares its direction with central finite-difference gradients. A documented redundancy counterexample shows a known blind spot; no learning improvement is claimed.
- **V4.2 causal-credit audit:** user-run Android Termux verification: **6/6 dedicated tests passed**; smoke (128 episodes) and pilot (2,048 episodes) completed. In pilot, R-STDP produced 1,355 negative, 0 positive and 693 zero updates. Correct FAST episodes had negative eligibility despite positive feedback; false-positive SLOW→FAST episodes had positive eligibility with negative feedback; correct SLOW episodes had zero eligibility. The 1 ns shadow changed aggregate eligibility magnitude/sign balance but did not change any per-episode eligibility or weight-update signs. This diagnoses a temporal-credit alignment limitation, not a proven fix. Details: docs/TASK_VALIDATION_R_STDP_V4_2_CREDIT_AUDIT.md and docs/TASK_VALIDATION_R_STDP_V4_2_RESULTS.md.
- **V4.3 decision-boundary temporal credit:** focused tests passed (7/7, 1.636 s), and smoke/pilot completed on Android Termux. In the two-seed pilot, Decision-Latched reached 82.23% held-out accuracy versus 83.01% for frozen V4 R-STDP and 100% for STDP. It processed all sensory inputs and gated exactly 1,024 post-decision plasticity events, but did not improve held-out quality. It remains experimental, not the default. Details: docs/TASK_VALIDATION_R_STDP_V4_3.md and docs/TASK_VALIDATION_R_STDP_V4_3_RESULTS.md.

- **Not implemented yet:** production neuron/NCC runtime, JNVM/JPVR storage engine, durable commit/outbox and crash recovery, full capability scopes/resource budgets, scheduler Aging/starvation protection, and Reasoning Engine execution.
- **Not measured or validated yet:** target-device runtime performance, RAM/CPU/energy/I/O, float32 runtime equivalence, and JPVR crash consistency.
- No application builds, APK/AAB generation, CI runs or GitHub Actions workflows were performed for these experiments.

## Repository map

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
| `tools/task_validation/` | V1–V4.3 deterministic task benchmarks, audits and tests |
| `tools/cqe_reference/` | Partial executable reference for CQE envelopes, authorization, queues, commits and events |
| `AGENTS.md` | Mandatory contributor/agent instructions and project constraints |

## Run reference unit tests

From the repository root, with Python 3 installed:

```sh
python3 -m unittest discover -s tools -t . -v
```

This runs Python unit tests only. It is not a production build, application package, Android build or CI workflow. The V4-specific unit tests use a smaller deterministic smoke dataset; they do not substitute for the full V4 benchmark.

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
