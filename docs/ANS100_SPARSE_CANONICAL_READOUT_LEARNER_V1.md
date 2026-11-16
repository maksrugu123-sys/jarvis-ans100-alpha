# ANS-100 sparse canonical readout learner V1 (reference experiment)

## What this stage adds

A small sparse supervised readout prototype for four mutually exclusive targets:

- `YES`
- `NO`
- `UNKNOWN`
- `CONFLICT`

It is a reward-modulated, one-vs-rest Hebbian reference learner. A training example contains the actual active neuron IDs, a target supplied through a separate feedback event, a provenance class, a unique event ID, and a hash of the original input. At inference, the prediction API accepts only active neuron IDs; it has no target-label parameter.

The learner is deliberately implemented first as a Python reference experiment. It is not wired into Android, does not alter the existing 10,000-neuron topology, and does not yet produce a physical LIF output spike. The four channels are sparse learned readout units, not calibrated probabilities.

## Leakage and safety boundaries

- Only `EXPLICIT_USER_FEEDBACK` and `VERIFIED_TOOL_OUTCOME` may update weights.
- Model-generated, inferred, implicit-behavior, or arbitrary string provenance is rejected.
- The six Core-labelled candidate input neurons 1056–1061 are forbidden as readout features.
- The current decision-output population (9400–9999) is forbidden as an input feature.
- Inference receives only the active neuron IDs; labels are accepted only by the separate learning API.
- Feedback event IDs are idempotent while retained (last 4096).
- At most 512 active neurons per example and 100,000 sparse synapses.
- Learning rate is capped at 0.1; individual weights are clipped to [-1, 1].
- Scores are raw accumulated synaptic weights, never probabilities.
- No learned signal or a tied winner produces abstention.
- Diagnostics always report `NOT_CALIBRATED` and `production_integrated=false`.

The input hash is an integrity reference, not proof that the input was generated without leakage. A future Android adapter must preserve per-neuron provenance and must prove that Core candidate labels cannot reach this readout through indirect paths.

## Algorithm

For each active source neuron and each of four output channels, the learner stores one sparse weight. On an authorized target event, the target channel receives +learning_rate and the other three receive -learning_rate/3. This is a simple reward-modulated association rule, not backpropagation and not a claim of biological equivalence. Repeated feedback is bounded by weight clipping and event-ID deduplication.

At inference, each channel sums its weights for the active source neurons. The unique highest channel may be returned only if both the minimum score and minimum margin thresholds are met. Otherwise the learner abstains. Thresholds are engineering safeguards, not statistical calibration.

## Files

- `tools/neuron_runtime/canonical_readout.py`
- `tools/neuron_runtime/test_canonical_readout.py`

Suggested verification when test execution is authorized:

```sh
python3 -m unittest tools.neuron_runtime.test_canonical_readout -v
```

Tests were authored but not run in this source-only stage.

## What is still required before Android integration

1. A provenance-preserving export of source-neuron spikes from the Android runtime, with Core-labelled and downstream answer-lane activity excluded.
2. A versioned four-channel physical output population and SQLite persistence/migration plan that does not reinterpret existing V4 databases.
3. A real feedback collection path that distinguishes explicit user judgments from verified external outcomes and prevents duplicate or poisoned updates.
4. A balanced training set with pair-disjoint, concept-disjoint, and compositional held-out splits.
5. Baselines: shuffled labels, random/frozen weights, and a non-neural feature baseline.
6. Calibration, confusion matrix, per-class precision/recall, abstention coverage, and resource measurements on the target Android device.
7. Only after those gates pass may a separate decision producer be introduced. Qwen remains downstream verbalization only.

This prototype advances the learning mechanism, but ANS-100 still does not independently understand arbitrary language or make production canonical decisions.
