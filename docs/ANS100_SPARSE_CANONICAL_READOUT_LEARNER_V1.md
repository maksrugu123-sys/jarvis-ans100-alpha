# ANS-100 sparse canonical readout V1

## Current implementation

The four-class canonical readout now has an experimental Android implementation in addition to the Python reference learner.

- Classes: `YES`, `NO`, `UNKNOWN`, `CONFLICT`.
- Dedicated output groups: neurons `8800..9399`, 150 neurons per class.
- Legacy six Core-labelled candidate lanes remain separate at `9400..9999`.
- Android topology version: V5.
- SQLite schema: V9, with bounded eligibility and feedback tables.
- Learned synapses use the `ans100-v9-canonical-readout-` namespace and are persisted in the existing `connections` table.
- The decoder reads only actual spikes inside the four dedicated canonical groups.

This is an experimental readout path. It is not the production answer producer and is not used to let Qwen decide what to answer. Qwen remains downstream verbalization only.

## Input isolation

Eligible readout source neurons are restricted to:

- `1062..1199`: persistent concept input neurons.
- `1200..2199`: general association neurons.
- `4000..4299`: general working-state neurons.

The six Core-labelled candidate input neurons `1056..1061` are rejected by the contract. A graph-reachability regression test traverses the complete static topology from those six Core inputs and verifies that none can reach an eligible readout source or any canonical output neuron.

The static topology also has no incoming edge to `8800..9399`. Those output synapses are created only after an explicit feedback event. The existing heuristic candidate/critic/readout lanes remain at `5800..9999` outside the canonical range; their labels are not converted into canonical classes.

The isolation proof covers the versioned static topology and the approved structural-growth path (concept input to general association). It does not prove safety against arbitrary future code that calls the generic low-level connection API to add new edges. Any such API use must preserve this boundary and add a regression test.

## Learning and storage

- Eligibility is recorded only for actual spiking source neurons from the allowlisted ranges.
- Maximum active source neurons per turn: 512.
- Maximum persisted source neurons: 1,024; maximum readout synapses: 4,096.
- One source neuron has one synapse to each of the four class groups.
- Initial weight: 0.25. On explicit target feedback, the target class receives +0.01 and each other class receives -0.01/3, clamped to `0.05..1.0`.
- The 150 output buckets use a deterministic source-to-target projection. For the dense contiguous 512-source regression fixture, each class bucket receives exactly 3 or 4 source edges; this keeps the target class just above the LIF threshold while the three negative classes remain just below it after one labelled example.
- Feedback accepts only `EXPLICIT_USER` or `VERIFIED_EXTERNAL_OUTCOME`; model-generated and inferred feedback are rejected.
- A turn's eligibility is consumed once. A second feedback event for the same turn cannot train it again, even with a new feedback ID.
- Feedback IDs are idempotent; eligibility and feedback history are age/row bounded.
- SQLite writes, synapse creation and weight updates happen in one transaction.

The dense fixture is a controlled engineering test, not proof that arbitrary sparse or adversarial activation patterns will classify correctly. Hash collisions and source-set composition can still change output activity.

## Decoder and interpretation

The decoder returns a class only when one canonical group has a unique winner, at least two output spikes and a nonzero margin. Otherwise it abstains. Even a unique winner is marked `UNCALIBRATED_WINNER`; the score is not a probability and is not a claim of truth.

The current Android Core still prepares typed evidence and heuristic candidate signals. The canonical readout receives only allowlisted neural spikes, but it does not yet independently understand arbitrary natural language. It is not connected as the final production decision producer.

## Regression coverage

Authored tests cover:

- four disjoint output groups;
- rejection of Core candidate inputs and legacy output neurons;
- static graph reachability isolation from Core candidate signals;
- bounded fan-in for a dense 512-source fixture;
- SQLite persistence across database reopen;
- one-shot feedback consumption and duplicate protection;
- real LIF output spikes and propagation telemetry for the trained class only;
- uncalibrated status and abstention behavior.

Test files:

- `app/src/test/java/org/jarvis/ans100/alpha/data/ANS100CanonicalReadoutDecoderTest.kt`
- `app/src/androidTest/java/org/jarvis/ans100/alpha/data/AndroidSparseNeuronNetworkTest.kt`
- `tools/neuron_runtime/canonical_readout.py`
- `tools/neuron_runtime/test_canonical_readout.py`

Tests were authored/updated in this source-only stage but were **NOT RUN**. Android builds, Python tests, CI and on-device measurements were also **NOT RUN**.

## Required before production use

1. Run the Kotlin JVM and Android instrumentation regression suites.
2. Evaluate pair-disjoint, concept-disjoint and compositional held-out sets with class balance, confusion matrix, per-class precision/recall and abstention coverage.
3. Compare with shuffled-label, frozen/random-weight and non-neural baselines.
4. Measure memory, CPU, latency and battery use on the target Android device.
5. Audit dynamic graph mutations and preserve the Core-isolation boundary.
6. Calibrate thresholds independently before benchmark scoring.
7. Only after all gates pass may a separately reviewed decision producer consume this readout.

No autonomous-reasoning, general-understanding, accuracy or calibration claim is made by this prototype.
