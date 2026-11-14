# JARVIS Dialog Neuron Experiment V1

Status: **experimental Python and native Android source implementations; regression tests AUTHORED, NOT RUN**.

## Goal

Create the first small, measurable learning experiment for JARVIS dialogue.
The prototype uses a fixed bank of 10,000 addressable feature neurons and a
small set of output intent units. It runs with the Python standard library
only and does not require Termux-specific APIs, a model download, network
access, or an APK build.

## What it actually does

1. Tokenizes a short Russian or English utterance.
2. Creates deterministic unigram and adjacent-token-pair features.
3. Hashes those features into a bank of 10,000 feature-neuron IDs.
4. Scores four output units: greeting, question, thanks, and farewell.
5. Returns no intent before learning or when the top-two score margin is too
   small.
6. Learns only from an explicit caller-provided expected label and reward.
   The expected output's active connections are strengthened; competing
   outputs are weakened.
7. Saves a versioned JSON checkpoint and restores it with validation.

No raw utterances are retained by the model. Only bounded numeric feature
weights and an example counter are stored.

## Important scientific boundary

This is a sparse reward-labelled linear classifier with neuron-like feature
units. It is **not** a transformer, generative language model, full semantic
reasoner, or evidence that 10,000 biologically realistic neurons are running.
It cannot generate natural-language replies. Its first purpose is to measure
whether explicit feedback can improve a tiny intent-routing task without
external dependencies.

## Run the tests

From the repository root:

```sh
python3 -m unittest tools.neuron_runtime.test_dialog_neuron_prototype -v
```

No build is required. Do not describe the tests as passing until they have
actually been run.

## First experiment protocol

Use a fixed, labelled dataset that is split by utterance template, not random
rows, to reduce near-duplicate leakage. Compare:
- untrained baseline (must abstain);
- trained prototype;
- majority-class baseline;
- deterministic keyword baseline.

Report accuracy, macro-F1, abstention rate, and checkpoint size. Keep a held-out
set untouched until the model and threshold are frozen. Do not tune on the
held-out set. Include adversarial ambiguous examples and Russian spelling
variants. A positive result is only a small intent-classification result, not
proof of general dialogue intelligence.

## Next integration gate

Only after tests and held-out evaluation pass should this be connected to the
Android conversation pipeline. It must initially run in shadow mode: record
predictions and confidence locally, do not control user-visible replies, and
do not silently train from private conversation text. User feedback must be
explicit and revocable.
