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
- The default graph builder creates a sparse chain for deterministic capacity probing; task-specific topology and trained synapses require separate configuration.
- The runtime is not yet connected to the ordinary chat turn. The existing ten-neuron ANS-100 signal pre-pass remains the default conversation diagnostic.
- Android STDP/R-STDP dialogue learning is a separate milestone; this network does not silently train itself from unlabelled conversations.
- SQLite atomicity is not a measured hardware-level power-loss guarantee. File-system/SQLite behavior, latency, RAM, flash writes and thermal cost must be measured on a named device.

## Verification

`AndroidSparseNeuronNetworkTest.kt` contains instrumentation tests for 10,000-node sparse initialization, bounded LRU residency, atomic timestamp propagation, durable queued events and reopen. Tests are **AUTHORED, NOT RUN**. No APK, Kotlin compile, instrumentation run, benchmark or device measurement is claimed.
