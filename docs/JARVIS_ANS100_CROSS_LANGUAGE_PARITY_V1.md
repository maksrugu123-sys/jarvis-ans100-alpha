# ANS-100 Conversation Signal V2 — Python/Kotlin Shared Vectors

**Status:** common fixture and two test consumers implemented at source level; tests AUTHORED, NOT RUN; numerical parity NOT VERIFIED.

## Fixture

Both implementations consume:

app/src/test/resources/ans100/conversation-v2.tsv

- Python consumer: tools/neuron_runtime/test_conversation_signal.py
- Android consumer: app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationSignalRuntimeTest.kt

Each row contains a prompt, six request-shape feature expectations, processed event/batch/spike counts, output-gate state, request-integrator adaptation/effective threshold and model time.

## Covered inputs

1. A full request that activates all six sensors, both aggregators, adaptive integrator and output gate.
2. A punctuation/question-only input that activates structural features but does not cross the request-integrator threshold.
3. A neutral short input with no supported feature signal.

## What the vectors establish

The test contract compares the same deterministic inputs and bounded output fields across the Python and Kotlin implementations. The ten-node topology remains six sensors, two aggregators, one adaptive integrator and one output gate connected by nine directed routes.

## What they do not establish

A shared expected-value file does not prove parity until both suites actually run. These vectors do not test Android/PC storage binary compatibility, process interruption, model inference, STDP/R-STDP dialogue learning, semantic understanding, full cognition or device performance. The values remain experimental and have not been calibrated as useful language features.
