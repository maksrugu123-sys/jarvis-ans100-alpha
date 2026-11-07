# JARVIS Calibration Round 2 — behavioral characterization

**Date:** 7 November 2026  
**Branch:** main  
**Status:** Round 2 generator executed; all 64 repository unit tests passed on Android Termux  
**Numerical profile:** JARVIS_SINGLE_NEURON_STARTER_V1 (unchanged)

## 1. Round 1: observed results

The user ran the full existing unit-test command in Android Termux:

~~~sh
python3 -m unittest discover -s tools -t . -v
~~~

Observed result: **55 tests passed, 0 failures, 0 errors** (3.031 seconds). This comprised 26 CQE reference tests and 29 Numerical Oracle tests. The Python version was not recorded for this run.

The user also ran the existing deterministic parameter sweep and inspected its JSON report:

~~~sh
python3 -m tools.numerical_oracle.calibration_sweeps > sweep.json
~~~

The report contains 99 scenarios:

| Group | Scenarios | Observation |
|---|---:|---|
| Adaptive LIF-DT | 81 | 5 cases emitted 1 spike, 34 emitted 2, 33 emitted 3, and 9 emitted 4 |
| Pair STDP | 12 | pre→post produced LTP; post→pre produced LTD; magnitude generally decreased with larger gap |
| Reward-modulated STDP | 6 | eligibility was accepted at age 5,000 ms and expired at 5,001 ms |

Selected STDP observations:
- At a 1 ms gap: LTP delta approximately +0.002378073561; LTD delta approximately −0.00243827478.
- At an 80 ms gap: LTP delta approximately +0.000045789097; LTD delta approximately −0.000338338208.

Selected R-STDP observations:
- Reward age 20 ms: APPLIED, delta approximately 2.299247e-06.
- Age 270 ms: APPLIED, delta approximately 1.790655e-06.
- Age 1,020 ms: APPLIED, delta approximately 8.45846e-07.
- Age 4,020 ms: APPLIED, delta approximately 4.2112e-08.
- Age 5,000 ms: APPLIED, delta approximately 1.5805e-08.
- Age 5,001 ms: EXPIRED, delta 0.

### Round 1 interpretation

The 55 passing tests support the tested reference contracts and the 99 cases support several local mathematical properties. They do not demonstrate task usefulness, network-level learning quality, production readiness, runtime performance, or a calibrated parameter profile.

The LIF results show sensitivity to tau_m within the fixed input probe. The STDP results show the expected timing direction and decay in the tested pairs. The R-STDP results confirm the configured eligibility-age boundary. The very small delayed reward deltas need task-level interpretation before any claim about useful learning.

No parameter was selected or changed from these results. STARTER remains the immutable baseline.

## 2. Round 2 objective

Round 2 broadens the experiment from parameter sensitivity under one fixed probe to controlled behavioral characterization under different input patterns and initial conditions. It deliberately keeps the STARTER parameters fixed.

Run from the repository root:

~~~sh
python3 -m tools.numerical_oracle.calibration_round2 > calibration_round2.json
python3 -m unittest discover -s tools -t . -v
~~~

The generator prints a deterministic JSON report and does not write files itself, change parameters, rank candidates, or perform optimization. Redirecting stdout to calibration_round2.json is optional and is done by the shell command above.

## 3. Scenario matrix

| Group | Cases | Controlled question |
|---|---:|---|
| Input amplitude | 5 | Is subthreshold integration monotonic over the selected safe range? |
| Temporal spacing | 3 | Does the same two-pulse input produce a stronger response at shorter gaps? |
| Temporal patterns | 3 | How do burst, sparse, and sustained inputs differ at the same pulse strength? |
| Adaptation recovery | 4 | Does adaptation/effective threshold decline during 0/50/100/200 ms of quiet time after activity? |
| Attention gain | 3 | Does bounded Attention gain scale the current input monotonically? |
| Simultaneous order | 2 | Does reversing caller list order preserve the same grouped-input result? |
| Initial conditions | 3 | How do residual potential and pre-existing adaptation affect the same input? |
| **Total** | **23** | |

All scenarios use the same STARTER parameter set. Event timestamps are integer model milliseconds converted to model_time_ns. Simultaneous inputs are passed as one batch. The report includes event counts, spike times, state/threshold values, and compact per-event traces.

## 4. Round 2 acceptance checks

The new unit tests check the following invariants:

1. Repeated generation returns an identical report and exact expected scenario coverage.
2. Potentials, adaptation values, and effective thresholds remain within the STARTER bounds and all reported numeric values are finite.
3. The selected subthreshold amplitude series is monotonic and does not emit spikes.
4. The short-gap two-pulse case produces a stronger spike response than longer-gap cases.
5. Burst and sparse patterns are distinguishable under the same pulse strength and event count.
6. Adaptation and effective threshold recover monotonically during the quiet interval.
7. Attention gain is monotonic and remains subthreshold for the selected probe.
8. Reversing simultaneous input order does not change the complete outcome.
9. The initial-state scenarios explicitly distinguish an adapted state from a resting state.

These are behavioral sanity checks for this finite scenario set, not universal model guarantees. A test failure is evidence that either the implementation or an expected invariant needs investigation; it must not be hidden by loosening assertions without explaining the reason.

## 5. What Round 2 does not decide

- It does not optimize or select tau_m, tau_a, theta_0, refractory time, STDP or R-STDP values.
- It does not establish that more spikes are better.
- It does not measure RAM, CPU time, energy, persistent-storage latency or JPVR behavior.
- It does not validate float32 runtime behavior; the oracle remains a float64 reference.
- It does not demonstrate multi-neuron/network behavior, biological fidelity, reasoning ability or general intelligence.
- It does not turn the STARTER profile into CALIBRATED.

## 6. Next decision after the run

The Round 2 generator completed successfully in the user's Android Termux environment. The complete test output reports 64 passed, 0 failures, 0 errors in 1.649 seconds. The JSON report was generated as calibration_round2.json, but its scenario metrics have not yet been inspected in this review. The test pass confirms the implemented assertions, not task-level usefulness or calibration.

Only after this characterization should we define a separate task-level validation suite with explicit desired outputs, false-positive/false-negative costs, and held-out test scenarios. Parameter changes require that separate objective and a versioned candidate profile; the STARTER baseline must remain preserved.

## 7. Verification record

- Round 1 unit tests: 55 passed on the user's Android Termux environment; Python version not captured.
- Round 1 sweep: 99 scenarios generated and inspected by the user.
- Round 2 generator: executed successfully; calibration_round2.json created on the user's device. Detailed scenario metrics are pending inspection.
- Round 2 + full repository unit tests: **64 passed, 0 failures, 0 errors** in 1.649 seconds on Android Termux. Breakdown: 26 CQE reference tests, 29 Numerical Oracle tests, 9 Round 2 tests. Python version not recorded.
- Builds, APK/AAB generation, CI, and GitHub Actions were not run.
- No runtime, benchmark, RAM, or device-performance measurements were performed.
