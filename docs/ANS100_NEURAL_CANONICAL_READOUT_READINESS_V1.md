# ANS-100 neural canonical readout readiness V1

## Purpose

This document began as a fail-closed readiness contract for a future neural-native readout. The active experiment now implements a first Android version of that contract, but it is not production-ready and does not establish autonomous semantic reasoning.

The six legacy candidate lanes are still labelled by heuristic Core (answer / clarification / abstain × support / conflict). They remain separate from the four experimental canonical answer groups.

## Implemented experimental path

- Four dedicated canonical output groups: `YES`, `NO`, `UNKNOWN`, `CONFLICT`.
- Output neuron range: `8800..9399`, 150 neurons per class.
- Legacy Core-labelled output lanes remain at `9400..9999`.
- Android hybrid topology V5 and SQLite schema V9.
- Actual LIF synapses are created from allowlisted neural source spikes after a separate feedback event.
- Synapses and feedback events persist in SQLite; feedback is transactional, bounded and idempotent.
- The decoder uses actual spikes from the four canonical groups and abstains on ties or insufficient activity.
- Canonical output synapse propagation IDs are included in run telemetry.
- The six Core candidate input neurons `1056..1061` are rejected as sources. A static graph-reachability regression test checks that those inputs cannot reach the allowed source populations or canonical output range.

Allowed source populations:

- `1062..1199`: persistent concept inputs.
- `1200..2199`: general association.
- `4000..4299`: general working state.

The static topology routes working state only to planning neurons `8500..8799`, not into the canonical output groups. Canonical output synapses are created only by the SQLite feedback learner.

## Feedback and output safeguards

Only `EXPLICIT_USER` and `VERIFIED_EXTERNAL_OUTCOME` feedback sources are accepted. Model-generated, inferred and implicit behavior are not training labels. Each turn can be consumed once; duplicate feedback IDs and repeated attempts to rate a consumed turn do not update weights again.

The current LIF readout uses bounded source sets (512 active source neurons per turn), 150 output buckets per class, initial synapse weight 0.25 and bounded reward updates. A dense 512-source fixture is designed so the target class crosses the LIF threshold while the three negative classes remain below it after one labelled example. This is a controlled regression fixture only: arbitrary source-set composition, collisions and sparse inputs may still produce abstention or errors.

## What the readout does not prove

- A unique output-group winner is not proof of a correct answer.
- Spike counts are not probabilities.
- The readout has no independent held-out calibration certificate.
- The current Core still prepares typed evidence and heuristic candidate signals for the wider dialogue pipeline.
- The canonical readout is not the production decision producer and is not allowed to replace Core routing yet.
- The static topology audit does not prevent a future developer from introducing an unsafe dynamic edge through the generic low-level connection API; such changes must be audited and tested.
- No claim of natural-language understanding, general intelligence or autonomous reasoning is made.

## Remaining gates

1. Run all new Kotlin JVM and Android instrumentation tests.
2. Verify the actual SQLite V8→V9 migration and V4→V5 topology migration on representative persisted databases.
3. Evaluate pair-disjoint, concept-disjoint and compositional held-out tasks.
4. Compare against shuffled-label, frozen/random-weight and non-neural baselines.
5. Report confusion matrix, per-class precision/recall, abstention coverage and uncertainty.
6. Independently calibrate before any benchmark scoring.
7. Measure RAM, CPU, latency, battery and database growth on the target Android device.
8. Review all dynamic connection creation paths for Core-signal leakage.

Tests, builds, CI and on-device measurements were **NOT RUN** in the latest source-only implementation stage.
