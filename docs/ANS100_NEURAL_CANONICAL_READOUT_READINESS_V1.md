# ANS-100 neural canonical readout readiness V1

## Purpose

The app does not yet have a neural-native canonical answer readout. The existing six candidate lanes are populated from Core-generated labels (answer / clarification / abstain × support / conflict). The sparse network propagates those labels; it does not independently infer them. This stage deliberately does not rename that controller as a neural decision.

The readiness auditor defines a fail-closed evidence contract for a future readout. It does not generate answers, train weights, alter Android runtime behavior, or claim that the current network understands natural language.

## Required neural readout contract

A future implementation may emit one of YES, NO, UNKNOWN, CONFLICT only when it supplies all of the following:

- Dedicated canonical output neuron populations for all four classes.
- Actual spiking neuron IDs from the completed ANS-100 run.
- An exact, non-overlapping mapping from each class to its output neurons.
- Per-class activity counts recomputed from those observed spikes.
- A unique winning class; ties and zero-activity outputs are blocked until a separately specified abstention policy exists.
- Explicit input provenance: RAW_NEURAL_INPUT_FRAME.
- Explicit decision provenance: only neural runtime state fields; Core labels and heuristic candidate decisions are forbidden.
- Producer exactly NEURAL_CANONICAL_READOUT.
- A separate independent held-out calibration certificate before benchmark scoring.

A spike count is not a probability. A unique winner is not evidence of correctness. The readout must be trained or adapted using disclosed supervision, evaluated on pair-disjoint, concept-disjoint and compositional held-out data, and compared with shuffled-label and fixed/random-weight controls. The calibration report must disclose class imbalance, abstention, confusion matrix, per-split results, and uncertainty.

## Auditor

File: `tools/task_validation/neural_readout_readiness_v1.py`

The auditor distinguishes:

1. `trace_valid`: the claimed answer is structurally supported by the actual dedicated output spikes and has no declared Core dependency.
2. `benchmark_scorable`: the trace is structurally valid and carries an independent held-out calibration certificate.

A missing or malformed readout is blocked. An uncalibrated trace may be structurally valid, but is not benchmark-scorable. The audit report never manufactures an answer from controller status, activation hints, memory assertions, or spike totals from unrelated populations.

Run locally when authorized:

```sh
python3 -m unittest tools.task_validation.test_neural_readout_readiness_v1 -v
python3 -m tools.task_validation.neural_readout_readiness_v1 --traces benchmark/neural_readout_traces.jsonl --report benchmark/neural_readout_audit.json
```

These commands were not run as part of this source-only stage.

## Current blocker and next engineering work

The existing output population (9,400–9,999) is partitioned into six heuristic candidate lanes, not four canonical answer classes. The current Core path also creates the support/conflict labels before the network runs. Therefore a genuine neural readout requires a topology/runtime revision and a disclosed training/evidence path; merely counting current readout-lane spikes cannot satisfy this contract.

Next implementation work must:
1. Allocate a versioned four-class output contract without reinterpreting existing persisted topology.
2. Define how raw neural input and recurrent working state can drive those classes without importing Core's answer labels.
3. Specify supervised/verified feedback, negative examples, class balance, and update bounds.
4. Evaluate against held-out and shuffled/random controls before enabling production routing.
5. Keep Qwen strictly downstream as verbalization of the already-decided canonical answer.

No current benchmark result, autonomous-reasoning claim, or calibrated confidence is implied by this readiness contract.
