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

## 6. Observed Round 2 results — user report, 7 November 2026

The user inspected the generated `calibration_round2.json` on Android Termux and supplied the summarized output for all 23 scenarios.

### 6.1 Input amplitude

| Strength | Spikes | Final potential |
|---:|---:|---:|
| 0.1 | 0 | 0.1 |
| 0.3 | 0 | 0.3 |
| 0.5 | 0 | 0.5 |
| 0.7 | 0 | 0.7 |
| 0.9 | 0 | 0.9 |

The selected single-pulse subthreshold series is monotonic and produced no spikes. It does not test suprathreshold response or repeated-input behavior at different amplitudes.

### 6.2 Temporal spacing

| Gap | Spikes | Spike times | Final potential |
|---:|---:|---|---:|
| 5 ms | 1 | [5] | 0.0 |
| 20 ms | 0 | [] | 0.820727664703 |
| 80 ms | 0 | [] | 0.610989383333 |

The same two 0.6-strength pulses produce a spike only at the 5 ms gap in this probe. This is consistent with temporal summation under the selected starter parameters; it does not establish a universal maximum integration interval.

### 6.3 Temporal patterns

| Pattern | Pulse count | Interval | Spikes | Spike times |
|---|---:|---:|---:|---|
| Burst | 8 | 5 ms | 2 | [10, 30] |
| Sparse | 8 | 50 ms | 0 | [] |
| Sustained | 36 | 10 ms | 5 | [40, 100, 160, 230, 300] |

Burst and sparse have equal pulse count and amplitude (0.45), isolating temporal spacing more cleanly: burst generated two spikes while sparse generated none. Sustained uses 36 pulses, unlike the eight-pulse burst/sparse cases, so its five spikes cannot be attributed to temporal density alone; total input count and duration also differ.

### 6.4 Adaptation recovery

All four cases use the same 20 input events (strength 1.0 at 0, 10, ..., 190 ms), and all produce 10 spikes at [0, 20, 40, 60, 80, 100, 120, 140, 160, 180]. Only the quiet interval after the last event changes.

| Quiet wait | Final adaptation | Final effective threshold | Final potential |
|---:|---:|---:|---:|
| 0 ms | 0.863225288424 | 1.431612644212 | 1.0 |
| 50 ms | 0.523572603668 | 1.261786301834 | 0.082084998624 |
| 100 ms | 0.317562836710 | 1.158781418355 | 0.006737946999 |
| 200 ms | 0.116824838906 | 1.058412419453 | 0.000045399930 |

Adaptation falls monotonically during silence and the effective threshold approaches the baseline 1.0. The values are consistent with exponential recovery using the configured 100 ms adaptation time constant. This validates the tested decay trajectory, not biological recovery or task-level benefit.

### 6.5 Attention gain

| Attention | Final potential | Spikes |
|---:|---:|---:|
| 0.0 | 0.5 | 0 |
| 0.5 | 0.625 | 0 |
| 1.0 | 0.75 | 0 |

The input gain increases monotonically with Attention for this 0.5-strength probe. All outputs remain subthreshold, so this experiment does not test threshold-crossing sensitivity or whether attention improves task performance.

### 6.6 Simultaneous order

Forward and reversed input lists produce exactly the same summarized outcome: zero spikes, final potential 0.66175 and final adaptation 0.0. This supports order invariance for this specific three-input batch, not for every possible batch or floating-point edge case.

### 6.7 Initial conditions

| Initial state | Initial potential | Initial adaptation | Final potential | Final threshold | Spikes |
|---|---:|---:|---:|---:|---:|
| Rest | 0.0 | 0.0 | 0.35 | 1.0 | 0 |
| Residual potential | 0.25 | 0.0 | 0.6 | 1.0 | 0 |
| Adapted + residual | 0.25 | 0.5 | 0.6 | 1.25 | 0 |

The same input adds to residual membrane potential, while pre-existing adaptation raises the effective threshold. The third case therefore distinguishes adaptation from membrane potential even though its final potential matches the residual-only case.

### 6.8 Overall interpretation and limitations

Round 2 supports the following narrow conclusions for the tested scenarios: subthreshold amplitude integration is monotonic; short-gap pulses can temporally sum to a spike; burst and sparse equal-count patterns produce different outcomes; adaptation and threshold recover monotonically during silence; Attention scales input gain monotonically; the tested simultaneous batch is order-invariant; and initial adaptation raises threshold.

The sustained pattern is not a clean density-only comparison because it contains 36 pulses versus 8 in burst and sparse. The amplitude and attention sweeps are entirely subthreshold. No scenario measures whether a spike is useful, whether learning improves a target behavior, or whether a network can classify/predict/act correctly.

**Decision:** keep `JARVIS_SINGLE_NEURON_STARTER_V1` unchanged and do not call it calibrated. Next, define a concrete first task and a task-level test suite with expected outputs, false-positive/false-negative costs, baseline comparisons and held-out sequences. Add further controlled sweeps only where the task criteria reveal a specific uncertainty.

## 6. Next decision after the run

The Round 2 generator completed successfully in the user's Android Termux environment. The complete test output reports 64 passed, 0 failures, 0 errors in 1.649 seconds. The JSON report was generated and all 23 summarized scenario outcomes were inspected and recorded in section 6. The test pass confirms the implemented assertions, not task-level usefulness or calibration.

Only after this characterization should we define a separate task-level validation suite with explicit desired outputs, false-positive/false-negative costs, and held-out test scenarios. Parameter changes require that separate objective and a versioned candidate profile; the STARTER baseline must remain preserved.

## 7. Verification record

- Round 1 unit tests: 55 passed on the user's Android Termux environment; Python version not captured.
- Round 1 sweep: 99 scenarios generated and inspected by the user.
- Round 2 generator: executed successfully; calibration_round2.json created on the user's device. All 23 summarized scenario metrics were subsequently reviewed and recorded in section 6.
- Round 2 + full repository unit tests: **64 passed, 0 failures, 0 errors** in 1.649 seconds on Android Termux. Breakdown: 26 CQE reference tests, 29 Numerical Oracle tests, 9 Round 2 tests. Python version not recorded.
- Builds, APK/AAB generation, CI, and GitHub Actions were not run.
- No runtime, benchmark, RAM, or device-performance measurements were performed.
