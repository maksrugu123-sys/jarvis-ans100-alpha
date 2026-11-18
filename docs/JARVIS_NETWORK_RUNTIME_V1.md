# JARVIS Network Runtime V1 — bounded multi-neuron event loop

**Status:** the Python reference event loop, sparse cognitive sidecars, and single-container JPVR group checkpoint/coordinator path are implemented at source level. The network test module contains 22 tests, AUTHORED / NOT RUN. No claim of production readiness, Android parity, device verification, or measured scaling is made.

## Runtime execution

`NetworkRuntime` composes existing `NeuronRuntime` instances and delegates the canonical ALIF-DT/STDP/R-STDP equations to the Numerical Oracle-backed runtime.

- A `NetworkConnection` routes one source spike to one target synapse with a positive model-time delay.
- All queued events at one timestamp are processed as a complete deterministic batch.
- Candidate neuron states and outgoing events are prepared before in-memory publication.
- Queue capacity and per-run event budgets are bounded; a same-timestamp batch is never split.
- Fan-out capacity and timestamp overflow are checked before candidate state is published.
- Failed candidate processing restores the timestamp's inputs and does not publish neuron, sidecar, queue, or clock changes.

## Cognitive sidecar integration

`NetworkRuntime` optionally accepts sparse `feature_states` and `synaptic_memories` mappings.

For a neuron with feature state:
- Sensitivity and inhibition gate each incoming excitatory signal through `effective_excitatory_gain`; output is clamped to [0, 1].
- Sensitivity adaptation uses negative feedback: activity below the configured target raises sensitivity, while activity above target lowers it.
- The sidecar's meta-plasticity value scales STDP/R-STDP local learning rates through a bounded runtime parameter adapter. Oracle safety caps remain unchanged; without a sidecar the scale is exactly 1.0.
- After a successful candidate neuron update, activity, the prior memory trace, and event attention update bounded confidence, uncertainty, prediction-error, memory, inhibition, and context fields.
- Touched synapses receive bounded coactivation and predictive traces.
- Reward operations update the optional reward trace without silently changing canonical STDP/R-STDP weights.

Neurons without a feature sidecar retain the baseline signal path. A sidecar is optional and sparse; it does not enlarge the 40-byte base numerical payload.

Important limitations:
- Confidence is an internal ranking signal, not a calibrated probability.
- The current prediction baseline is the prior bounded memory trace, not a learned sequence model.
- Context similarity currently uses mean event-attention.
- Inhibition is currently only a gate for existing state; the runtime supplies no new inhibitory drive or inhibitory population.
- Synaptic memory traces are stored but are not yet consumed by later activation or credit assignment.
- The sidecar does not yet implement full predictive coding, hypothesis competition, or causal reasoning.
- Android Kotlin feature parity and UI controls are not integrated.
- Feature activation is currently selected by supplying sidecar state at runtime; there is no complete user-facing per-mechanism configuration system.

## Checkpoint and persistence path

`NetworkRuntime.export_checkpoint_state()` includes neuron state, sidecars, pending events, topology, queue/run limits, and model clock. `restore_checkpoint_state()` validates and reconstructs these values.

`JPVRNetworkJNVMStore.save_timestamp()` persists changed neuron records, their optional sidecars, the scheduler snapshot, and all corresponding COW index mutations in one JPVR typed transaction / COMMIT. The scheduler contains a complete neuron-record-version manifest. Restore reads the scheduler and every neuron from one pinned JPVR snapshot and rejects version drift.

`NetworkJNVMCoordinator`:
- restores one coherent network snapshot;
- stages inputs and rewards on a detached runtime copy;
- processes at most one complete timestamp in `run_next_timestamp()`;
- commits changed neuron records and scheduler state together;
- publishes the candidate in memory only after a confirmed durable commit;
- freezes on uncertain publication until a reopened container proves either the exact new commit or exact prior snapshot;
- requires explicit reload after a definite stale-version conflict.

This is a single-writer Python reference path. The coordinator still holds the complete `NetworkRuntime` and neuron objects in process RAM. It is not connected to the 128-shard per-neuron write-back working set, and it is not a storage-paged 500,000-neuron runtime.

## Verification state

`tools/neuron_runtime/test_network.py` contains 22 authored tests, including:
- topology and endpoint validation;
- deterministic same-timestamp batching;
- delayed multi-neuron propagation;
- queue/run budget and fan-out backpressure;
- failed-timestamp rollback;
- sidecar input modulation and memory update;
- feature-counter rollback;
- inhibition and sensitivity behavior;
- negative-feedback sensitivity adaptation;
- meta-plasticity propagation into STDP learning parameters;
- rejection of boolean and non-finite sensitivity;
- caller-state isolation and deterministic input ordering.

The focused network tests, JPVR persistence tests, coordinator tests, and full repository suite have **NOT RUN** in this increment. No build, CI, held-out experiment, benchmark, or Android device measurement was run.

Suggested focused verification when explicitly approved:

```bash
python3 -m unittest tools.neuron_runtime.test_network -v
python3 -m unittest tools.neuron_runtime.test_network_jnvm_store -v
python3 -m unittest tools.neuron_runtime.test_network_jnvm_coordinator -v
```

Next engineering gates:
1. Run focused tests and repair any real regressions.
2. Add differential Python/Kotlin golden vectors for feature updates and input gating.
3. Integrate sidecar state into Android's SQLite timestamp transaction without weakening atomicity.
4. Connect bounded working-set paging to network execution only after the group-commit boundary is preserved.
5. Develop prediction and uncertainty into actual hypothesis generation/competition, with independent evidence.
6. Increase logical capacity only through staged indexed storage and measured resource gates.
