# JARVIS ANS-100 Alpha

**JARVIS ANS-100 Alpha** is a research project for a compact adaptive neural system. Development is staged: begin with one artificial neuron and a small number of synapses, validate behavior and resource cost, and scale only when each previous step is supported by evidence.

The repository is at the **architecture and reference-experiment stage**. It is not yet a production neural runtime or a finished assistant.

## Current status — 7 November 2026

- **Architecture:** the first-neuron design, Adaptive LIF-DT model, excitatory STDP/R-STDP rules, CQE v1 contract and JPVR_FORMAT_V1 storage format are specified in the architecture document.
- **Numerical Oracle:** a deterministic Python/float64 reference implementation exists for Adaptive LIF-DT, STDP and R-STDP. It is a research oracle, not the production runtime.
- **CQE reference:** a partial, single-threaded Python contract reference exists. Its commit store and event inbox/outbox are volatile; durable JPVR integration is not implemented.
- **Latest test evidence:** before V4, the user ran **107 tests, 0 failures, 0 errors in 19.730 seconds** on Android Termux. After V4 was added, the user separately ran its dedicated module: **12 tests passed, 0 failures, 0 errors in 2.962 seconds**. A full post-V4 suite has not yet been run.
- **Calibration:** Round 1's 99-scenario sweep was generated and reviewed. Round 2's 23-scenario report was generated locally and summarized in docs/CALIBRATION_ROUND2.md. The canonical STARTER parameter profile has not been calibrated or changed.
- **Temporal task V1:** user-run held-out accuracy was FIXED 80%, STDP 90%, R-STDP 80% on one small deterministic challenge. This was preliminary evidence, not proof of generalization.
- **Temporal robustness V2:** user-run held-out accuracy was FIXED 74.375%, STDP 66.25%, rewarded R-STDP 74.375%, neutral R-STDP 74.375%. Rewarded and neutral R-STDP predictions matched on all four seeds; task-level reward benefit was not demonstrated. Details: docs/TASK_VALIDATION_TEMPORAL_V2_RESULTS.md.
- **Temporal robustness V3:** user-run held-out accuracy was FIXED 100%, STDP 98.75%, R-STDP 100%, neutral R-STDP 100%. All 128 training reward modulators were positive, so negative reward correction was not tested. FAST delivered three pulses before the 7 ms deadline while SLOW delivered two; V3 therefore tested deadline-bounded event-density/latency, not same-count interval discrimination. Details: docs/TASK_VALIDATION_TEMPORAL_V3_RESULTS.md.
- **V4 — R-STDP feedback/error recovery:** the exploratory two-seed pilot completed with 12/12 dedicated tests passing. Held-out accuracy: FIXED 81.84%, STDP 100%, R-STDP 83.01%, neutral R-STDP 81.84%. R-STDP used both reward signs, but all 1,355 non-zero reward-driven updates depressed the weight; task-level benefit over FIXED was small (+1.17 percentage points, 6 fewer false positives), while STDP was best. The full pre-registered profile remains pending. Canonical STARTER weight 0.5 is unchanged; 0.70 is only the shared diagnostic initial weight. Details: docs/TASK_VALIDATION_R_STDP_V4_PILOT_RESULTS.md.
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
| `tools/numerical_oracle/` | Float64 reference equations, sweeps and tests |
| `tools/task_validation/` | V1–V4 deterministic task benchmarks and tests |
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

The CLI prints progress to stderr while keeping the final JSON on stdout, so redirecting stdout to a report file does not hide progress. The pilot is exploratory and reduced-size; it must not be reported as the full pre-registered result. The full profile is the default. V4 first probes positive and negative eligibility against positive, negative and neutral reward modulation. Its task-level recovery track compares FIXED, STDP, rewarded R-STDP and neutral R-STDP on identical same-count three-pulse data and multiple deterministic seeds. It uses the canonical STARTER weight 0.5 only for a read-only baseline probe. The separately declared diagnostic recovery track starts all four conditions at weight 0.70; that perturbation is not a STARTER change or calibration.

The evaluator supplies reward 1.0 for a correct training decision and 0.0 for an incorrect one. Held-out examples are inference-only. A weight change alone is not success: compare held-out confusion metrics against FIXED and neutral R-STDP across seeds, and also report STDP even if it performs better. The protocol and limitations are in docs/TASK_VALIDATION_R_STDP_V4.md.

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
