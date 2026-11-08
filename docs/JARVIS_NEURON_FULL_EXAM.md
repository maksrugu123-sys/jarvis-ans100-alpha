# JARVIS One-Neuron Comprehensive Exam V1

**Status:** protocol and executable harness authored; full exam not yet run from the current GitHub main checkout.  
**Scope:** existing Python/float64 reference neuron, STDP/R-STDP, isolated BTCC oracle, synthetic noisy temporal task and V5.1 reference-object measurements.  
**Non-goal:** this is not a production runtime certification, intelligence test, or proof of consciousness.

## 1. One command

From the repository root, run:

    python3 -m tools.neuron_validation.full_exam

This uses the pilot profile by default and runs the entire discovered Python unit-test suite plus additional experiments and resource measurements. It writes neuron_full_exam_report.json.

Profiles:

| Profile | Noise task | Resource population sizes | Intended use |
|---|---|---|---|
| smoke | 1 seed, 24 train pairs, 12 held-out pairs, 0/2 distractors | 1, 10 | Quick integration check |
| pilot (default) | 2 seeds, 128 train pairs, 64 held-out pairs, 0/2/4 distractors | 1, 10, 100, 1,000 | Regular comprehensive phone-friendly research exam |
| full | 4 seeds, 512 train pairs, 256 held-out pairs, 0/2/4/6 distractors | 1, 10, 100, 1,000, 10,000 | Large extended run; may take substantially longer |

Use --json-report PATH to choose another report path and -q for compact unit-test output.

## 2. What the exam checks

### 2.1 Numerical and state contracts

The comprehensive unit module tests:
- analytical membrane and adaptation decay over a grid of initial states and elapsed times;
- rest convergence after long inactivity;
- dynamic threshold monotonicity and clipping;
- attention gain endpoints and range;
- simultaneous batch ordering, duplicate source rejection and deterministic summation;
- excitatory and inhibitory input bounds, membrane saturation and reset;
- refractory behavior immediately before and at the exact refractory boundary;
- spike count, spike timestamp and adaptation invariants;
- monotonic model time, U64 overflow and invalid state/parameter rejection;
- deterministic randomized sequences across four fixed seeds, with state invariants checked after every event batch.

### 2.2 Synaptic learning

The exam tests:
- STDP potentiation for pre-before-post and depression for post-before-pre;
- simultaneous-event behavior and exact trace decay;
- long update sequences with bounded weight and traces;
- R-STDP eligibility accumulation without immediate weight mutation;
- positive, negative and neutral reward direction;
- reward-baseline update;
- eligibility context isolation, expiry and no-eligibility behavior;
- long randomized reward sequences with bounded weights, traces and baseline.

These are checks of the current reference equations. They do not establish that STDP or R-STDP is the best learning algorithm.

### 2.3 Noisy temporal generalization task

The new task is a separate diagnostic, not a replacement for earlier V4/V4.3 results.

Each example has three task-relevant pulses and a fixed 40 ms decision deadline. FAST and SLOW examples have the same pulse count but different inter-pulse timing. Each matched FAST/SLOW pair receives identical signal strengths and identical weak excitatory distractors. The distractors are deterministically generated from seed, split and pair ID. Training and held-out signal/noise signatures are generated from separate split namespaces and checked for overlap.

The benchmark compares four conditions on the same train/held-out protocol:
- FIXED diagnostic weight;
- STDP;
- reward-modulated STDP;
- neutral R-STDP control.

The diagnostic starting weight is 0.70 for every condition. The canonical STARTER weight remains 0.50 and is not changed. Held-out examples are inference-only; their weights are checked as frozen.

Report metrics include training and held-out accuracy, balanced accuracy, macro-F1, confusion counts, final weight, reward baseline and input/plasticity counters. The benchmark has no preselected pass threshold. Its job is to reveal performance, noise sensitivity and failure modes, not to manufacture a winner. A future learning-rule change requires a separately pre-registered acceptance rule and repeated held-out evidence.

The current noise is weak and excitatory. Inhibitory noise, correlated adversarial distractors, nonstationary streams and multi-neuron interactions remain outside this first task.

### 2.4 BTCC

The full existing unittest suite includes the isolated BTCC replay/finite-difference contract tests. BTCC is not used as a trainer in the noisy benchmark. This exam does not establish aggregate gradient-sign agreement or task-level BTCC learning benefit.

### 2.5 Resource observations

The exam invokes the existing V5.1 resource baseline:
- reference object-graph size;
- pickle diagnostic size;
- tracemalloc current/peak deltas;
- process RSS/HWM when procfs exposes them;
- illustrative parameter-sharing scenario.

These are measurements of the current Python Numerical Oracle representation. They are not the size of a packed candidate, not JPVR storage measurements, and not proof of memory savings or production speed.

## 3. Capability coverage and known gaps

The report contains a machine-readable capability matrix. A missing feature is labelled NOT_IMPLEMENTED, never treated as a passing check.

Currently testable or observable:
- Adaptive LIF-DT dynamics, analytical decay, threshold, attention scalar gain, spikes and refractory state;
- current excitatory STDP and reward-modulated STDP reference rules;
- numerical bounds, invalid inputs, deterministic event stress and timing boundaries;
- isolated BTCC replay mechanics;
- synthetic noisy temporal task behavior for FIXED/STDP/R-STDP/neutral controls;
- Python reference-object resource measurements.

Not implemented or not established by this exam:
- production neuron runtime or network-level behavior;
- adaptive prediction model, prediction error learning, surprise/familiarity/habituation state;
- fatigue/recovery controller;
- long-term associative memory, adaptive forgetting and memory protection;
- structural synapse creation/deletion and a dedicated homeostatic controller;
- JNVM/JPVR persistence, crash consistency and recovery;
- a packed V5 neuron, measured compactness benefit or float32 runtime candidate;
- BTCC integration or task-level training benefit;
- Android equivalence, device CPU/energy/thermal performance, APK/AAB, CI and GitHub Actions.

This means the exam is broad across the mechanisms that exist in the repository, but it cannot verify features that have not been implemented.

## 4. Result interpretation

The top-level status PASS_WITH_DECLARED_LIMITATIONS means:
1. all discovered Python unit tests passed;
2. Python syntax and test discovery checks passed;
3. the noisy temporal benchmark executed and produced valid metrics;
4. V5.1 reference-resource measurements completed.

It does not mean the neuron passed every scientific acceptance test. The report separately lists incomplete architecture areas and benchmark metrics without automatically promoting a learning rule.

If any automated stage fails, the command exits non-zero and records the available failure details in the JSON report. If the process is interrupted by Android/Termux resource limits, record it as interrupted and do not interpret partial output as PASS.

## 5. Safety and reproducibility boundaries

- Fixed seeds and profile sizes are recorded.
- The task generator has disjoint train/held-out namespaces and matched distractors per pair.
- No held-out feedback updates the trained weight.
- The STARTER profile is not changed; 0.70 is diagnostic only.
- BTCC remains isolated from the learning rules.
- The exam performs Python tests/experiments and resource observations only. It does not run Gradle/Maven/CMake/Make/Cargo/npm builds, APK/AAB packaging, CI or GitHub Actions.
- A test pass does not establish general intelligence, consciousness, biological equivalence or real-world robustness.

