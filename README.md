# JARVIS ANS-100 Alpha

**JARVIS ANS-100 Alpha** is a research project for a compact adaptive neural system. Development is intentionally staged: begin with one artificial neuron and a small number of synapses, validate the behavior and resource cost, and scale only when each previous step is supported by evidence.

The repository is currently at the **architecture and reference-experiment stage**. It is not yet a production neural runtime or a finished assistant.

## Current status — 7 November 2026

- **Architecture:** the first-neuron design, Adaptive LIF-DT model, excitatory STDP/R-STDP rules, CQE v1 contract and JPVR_FORMAT_V1 storage format are specified in the architecture document.
- **Numerical Oracle:** a deterministic Python/float64 reference implementation exists for Adaptive LIF-DT, STDP and R-STDP. It is a research oracle, not the production runtime.
- **CQE reference:** a partial, single-threaded Python contract reference exists. Its commit store and event inbox/outbox are volatile; durable JPVR integration is not implemented.
- **Verification:** the latest user-run full test command on Android Termux completed with **64 passed, 0 failures, 0 errors** (26 CQE reference, 29 Numerical Oracle and 9 Calibration Round 2 tests; 1.649 seconds). The Python version for this run was not recorded.
- **Calibration:** Round 1's 99-scenario sweep was generated and reviewed. Round 2's 23-scenario report was generated locally and all summarized scenario outcomes were reviewed and recorded in docs/CALIBRATION_ROUND2.md. The STARTER parameter profile has not been calibrated or changed.
- **Not implemented yet:** production neuron/NCC runtime, JNVM/JPVR storage engine, durable commit/outbox and crash recovery, full capability scopes/resource budgets, scheduler Aging/starvation protection, and Reasoning Engine execution.
- **Task-level validation:** a deterministic one-neuron burst-vs-sparse benchmark now compares fixed weights, STDP and reward-modulated STDP. It has not yet been run; no task-level results are claimed.
- **Not measured or validated yet:** target-device runtime performance, RAM/CPU/energy/I/O, float32 runtime equivalence, and JPVR crash consistency.
- No application builds, APK/AAB generation, CI runs or GitHub Actions workflows were performed for this verification.

## Repository map

| Path | Purpose |
|---|---|
| `docs/JARVIS_ARCHITECTURE.md` | Main architecture specification, decisions, open questions and development plan |
| `docs/CALIBRATION_ROUND2.md` | Round 1 observations, Round 2 scenario design, verification record and next steps |
| `tools/numerical_oracle/` | Float64 reference equations, deterministic sweeps, Round 2 generator and tests |
| `tools/task_validation/` | Deterministic task-level temporal-pattern benchmark and tests |
| `tools/cqe_reference/` | Partial executable reference for CQE envelopes, authorization, queues, commits and events |
| `AGENTS.md` | Mandatory instructions and constraints for contributors and AI coding agents |

## Run the reference tests

From the repository root, with Python 3 installed:

```sh
python3 -m unittest discover -s tools -t . -v
```

This runs Python unit tests only. It is not a production build, application package, Android build or CI workflow.

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
