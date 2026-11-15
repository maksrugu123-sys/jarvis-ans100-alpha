# Android Sparse Neuron Network & JNVM V1

**Status:** native Android SQLite-backed sparse LIF-DT runtime and bounded LRU working set implemented at source level. Runtime performance and crash/reopen guarantees remain unverified.

## Runtime

`AndroidSparseNeuronNetwork` supports a sparse directed graph with ceilings of 10,000 neurons, 100,000 connections and 100,000 pending events. The default hot working set is 128 neuron states. Inactive states remain in the app-private SQLite database; only requested states enter the LRU cache.

A timestamp is one atomic publication unit. The runtime:
1. reads every pending event at the next timestamp;
2. calculates detached candidate neuron states;
3. derives delayed outgoing events from candidate spikes;
4. commits consumed events, changed states, generated events and the model clock in one SQLite transaction;
5. publishes candidates into the LRU only after SQLite confirms the transaction.

Events at a timestamp are never split. Per-run and per-timestamp limits bound work. A failed transaction leaves the prior durable state authoritative and the pending input queue unchanged. A queue or fan-out limit produces backpressure.

## Limits and non-goals

- This is a sparse event-driven reference runtime, not a dense 10,000 × 10,000 matrix.
- Flash-backed SQLite is persistence, not physical RAM. Active state still enters the LRU/heap for computation.
- `initializeSparseChain()` remains a generic sparse-chain fixture for scheduler and propagation tests.
- The chat-specific `initializeSparseDialogueNetwork()` topology creates 9,996 feature neurons and four terminal output neurons, with 39,984 directed feature-to-output connections. A feature spike reaches the four readouts and stops; it cannot cascade down a 10,000-node chain.
- The 10,000 classifier hash slots map modulo the 9,996 feature-neuron slots. The chat side-channel sends only bounded feature IDs, not raw utterance text, and runs on a dedicated executor.
- The dialogue topology uses a separate V2 SQLite database filename. Existing V1 sparse-chain data is preserved; it is not destructively migrated or overwritten.
- The ten-neuron ANS-100 signal pass remains the request-shape diagnostic. The 10k graph is lazily initialized on the first ordinary non-command chat turn. Only hashed feature neurons receive external inputs; output neurons activate through learned synaptic propagation, with no artificial direct output drive. The graph computes mean learned synaptic weights for four readout categories (greeting, question, thanks, farewell) and passes those uncalibrated scores plus bounded event/feature measurements to LiteRT-LM as advisory prompt context alongside JARVIS Core's analysis. Explicit user feedback can update these weights through the existing bounded R-STDP path. This is a learned four-way readout, not calibrated probabilities, general semantic reasoning, or independent answer generation.
- Explicit Android R-STDP V1 is now implemented at source level for this 10k graph: each ordinary non-command turn can create bounded eligibility rows for active feature/output pairs whose output neurons spiked; an explicit 👍/👎 or expected-label action then applies a signed, bounded reward to the corresponding SQLite synapse weights. Ordinary chat does not change weights, and the language model is not trained. The separate four-label sparse dialogue-feature classifier remains a different learner. See architecture §3.79.
- SQLite atomicity is not a measured hardware-level power-loss guarantee. File-system/SQLite behavior, latency, RAM, flash writes and thermal cost must be measured on a named device.

## Verification

`AndroidSparseNeuronNetworkTest.kt` contains nine instrumentation cases covering generic 10,000-node chain initialization, 10,000-node dialogue topology and 39,984 edges, bounded feature-to-output propagation, positive/negative R-STDP weight changes, untouched-synapse isolation, duplicate reward consumption, SQLite persistence, invalid reward rejection, LRU residency, atomic timestamp batches, duplicate rejection, durable queued events and reopen. Tests are **AUTHORED, NOT RUN**. No APK, Kotlin compile, instrumentation run, benchmark or device measurement is claimed.
