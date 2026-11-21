# ANS-100 Durable Neural Hypothesis Ledger V1

## Goal

Preserve the network's own bounded hypothesis observations across Android process restarts without turning a repeated activation into a factual answer. The ledger is a continuity mechanism for experiments, not a new reasoning authority.

## Runtime path

1. Android completes the sparse seven-population LIF event pass.
2. `ANS100NeuralAssociationDecoder` emits candidates only when both concept input paths propagated through activated synapses to a shared association neuron that actually spiked.
3. `ANS100NeuralHypothesisEngine` records the completed-turn observation. A later independent completed turn may reproduce it or fail to reproduce it.
4. The ViewModel exports a bounded engine snapshot and saves it to the app-private SQLite ledger.
5. The live benchmark trace records whether that checkpoint save succeeded.
6. Qwen receives the existing bounded handoff for verbalization; it does not write, verify, promote, or select hypotheses.

## Stored data and limits

Database: `ans100_neural_hypotheses.db`.

The database contains one singleton checkpoint row, replaced atomically. The JSON payload stores:

- stable concept IDs and the matched labels already present in the approved workspace;
- hypothesis IDs derived deterministically from the sorted concept pair;
- up to eight recent supporting and eight recent non-reproducing turn IDs per hypothesis;
- the number of shared association targets observed;
- up to 512 recent processed turn IDs for duplicate-turn protection.

Default maximum is 128 hypotheses; hard maximum is 256. Snapshot payloads are limited to 512 KiB. The engine does not retain unbounded per-hypothesis turn histories.

Raw user text, model output, expected benchmark answers, split labels, credentials and external-tool payloads are not stored. The benchmark trace continues to hash the raw input and omit its contents.

## Evidence semantics

- `GENERATED`: one completed turn produced a neural co-activation candidate.
- `REPRODUCED_ON_INDEPENDENT_TURN`: at least two distinct completed turns produced convergence and no complete non-reproduction observation is retained.
- `NOT_REPRODUCED_ON_INDEPENDENT_TURN`: a later complete turn activated both concepts but did not reproduce convergence.
- `MIXED_REPRODUCTION_SIGNAL`: complete turns produced both convergence and non-convergence.

None of these statuses proves that two concepts are semantically related or that a proposition is true. In particular, `MIXED_REPRODUCTION_SIGNAL` is not the same as conflicting factual evidence.

A turn that exhausted its compute budget, left pending events, was duplicated, or had a possibly truncated pair scan cannot provide a negative observation. The current pair-scan gate is intentionally conservative.

## Failure behavior

- An invalid, unknown-schema, or oversized checkpoint is ignored on load.
- A checkpoint save failure does not abort the chat turn.
- The trace field `neural_hypothesis_state_persisted` reports the save result for that turn.
- Local app data can still be lost when app storage is cleared or the app is uninstalled; this is not a backup system.

## Benchmark implications

This stage improves continuity and observability only. It does not create a canonical `YES/NO/UNKNOWN/CONFLICT` neural readout and does not change the benchmark adapter's rule that only a producer explicitly identified as `NEURAL_CANONICAL_READOUT` can supply a scored ANS-100 prediction. No score should be derived from spike counts, repeatability status, Core policy status, or Qwen wording.

## Validation

Tests were added for state round-trip, duplicate-turn protection after restoration, mixed convergence/non-convergence, bounded observation history, invalid checkpoint rejection, and telemetry status. Tests and Android builds were not run for this edit.
