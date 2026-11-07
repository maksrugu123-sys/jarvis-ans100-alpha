# JARVIS ANS-100 Alpha

**JARVIS ANS-100 Alpha** is a research project for a compact adaptive neural system. Development is intentionally staged: begin with one artificial neuron and a small number of synapses, validate the behavior and resource cost, and scale only when each previous step is supported by evidence.

The repository is currently at the **architecture and reference-experiment stage**. It is not yet a production neural runtime or a finished assistant.

## Current status — 7 November 2026

- **Architecture:** the first-neuron design, Adaptive LIF-DT model, excitatory STDP/R-STDP rules, CQE v1 contract and JPVR_FORMAT_V1 storage format are specified in the architecture document.
- **Numerical Oracle:** a deterministic Python/float64 reference implementation exists for Adaptive LIF-DT, STDP and R-STDP. It is a research oracle, not the production runtime.
- **CQE reference:** a partial, single-threaded Python contract reference exists. Its commit store and event inbox/outbox are volatile; durable JPVR integration is not implemented.
- **Verification:** the latest user-run full test command on Android Termux completed with **69 passed, 0 failures, 0 errors** (26 CQE reference, 29 Numerical Oracle, 9 Calibration Round 2 and 5 task-validation tests; 1.833 seconds). The Python version for this run was not recorded.
- **Calibration:** Round 1's 99-scenario sweep was generated and reviewed. Round 2's 23-scenario report was generated locally and all summarized scenario outcomes were reviewed and recorded in docs/CALIBRATION_ROUND2.md. The STARTER parameter profile has not been calibrated or changed.
- **Not implemented yet:** production neuron/NCC runtime, JNVM/JPVR storage engine, durable commit/outbox and crash recovery, full capability scopes/resource budgets, scheduler Aging/starvation protection, and Reasoning Engine execution.
- **Task-level validation:** the first deterministic one-neuron burst-vs-sparse benchmark was run. FIXED, STDP and R-STDP all achieved 100% train and held-out accuracy (6/6 held-out episodes). The fixed baseline already solved this easy task, so no accuracy benefit from learning was demonstrated. Full observations and limitations are in docs/TASK_VALIDATION_TEMPORAL_V1.md.
- **Not measured or validated yet:** target-device runtime performance, RAM/CPU/energy/I/O, float32 runtime equivalence, and JPVR crash consistency.
- No application builds, APK/AAB generation, CI runs or GitHub Actions workflows were performed for this verification.

## Repository map

| Path | Purpose |
|---|---|
| `docs/JARVIS_ARCHITECTURE.md` | Main architecture specification, decisions, open questions and development plan |
| `docs/CALIBRATION_ROUND2.md` | Round 1 observations, Round 2 scenario design, verification record and next steps |
| `docs/TASK_VALIDATION_TEMPORAL_V1.md` | First task-level benchmark protocol, observed results and limitations |
| `tools/numerical_oracle/` | Float64 reference equations, deterministic sweeps, Round 2 generator and tests |
| `tools/task_validation/` | Temporal-pattern benchmark, fixed-baseline boundary grid and tests |
| `tools/cqe_reference/` | Partial executable reference for CQE envelopes, authorization, queues, commits and events |
| `AGENTS.md` | Mandatory instructions and constraints for contributors and AI coding agents |

## Run the reference tests

From the repository root, with Python 3 installed:

```sh
python3 -m unittest discover -s tools -t . -v
```

This runs Python unit tests only. It is not a production build, application package, Android build or CI workflow.

## Run the first task-level benchmark

From the repository root:

```sh
python3 -m tools.task_validation.temporal_patterns
```

This compares a fixed-weight baseline with STDP and reward-modulated STDP on a deterministic burst-vs-sparse task. The first run is recorded in docs/TASK_VALIDATION_TEMPORAL_V1.md. It prints a JSON report and does not change the STARTER profile.

## Characterize the fixed baseline

After the first task-level run, map the current neuron's response boundary without training:

```sh
python3 -m tools.task_validation.baseline_boundary > baseline_boundary_report.json
```

This evaluates 135 deterministic combinations (15 pulse intervals × 9 input strengths). It does not change the STARTER profile or update synaptic weights. The grid is a characterization step, not a learning benchmark.

## Generate experiment reports

Round 1 exploratory parameter sweep:

```sh
python3 -m tools.numerical_oracle.calibration_sweeps > sweep.json
```

Round 2 behavioral characterization:

```sh
python3 -m tools.numerical_oracle.calibration_round2 > calibration_round2.json
```

Both commands produce deterministic JSON reports and do not optimize parameters or alter the STARTER profile. Review the outputs against explicit task-level criteria before considering any calibration change.

## Important engineering boundaries

- JPVR is persistent storage backed by a physical device; it is not additional physical RAM.
- Python packages under `tools/` are reference/validation tools, not the production JARVIS runtime.
- A passing unit-test suite verifies only the assertions covered by those tests. It does not prove task usefulness, biological equivalence, consciousness, general intelligence or production readiness.
- Keep the original `JARVIS_SINGLE_NEURON_STARTER_V1` parameter set immutable. Any future calibrated profile must have its own version and reproducible evidence.
- Do not run application builds or GitHub Actions without explicit user permission. See `AGENTS.md`.

For the detailed status, unresolved decisions and the next implementation gates, start with [the architecture document](docs/JARVIS_ARCHITECTURE.md).
