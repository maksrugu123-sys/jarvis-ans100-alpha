# JNVM cognitive feature sidecar V1

The optional feature state has a strict, deterministic, versioned UTF-8 codec
in `tools/neuron_runtime/feature_codec.py`.

- The base ALIF-DT hot-state numeric payload remains unchanged (40 bytes).
- Sparse per-neuron cognitive features and per-synapse memory traces are
  encoded by the sidecar codec. Neurons without feature state need no sidecar.
- JPVR one-neuron checkpoints now accept optional `feature_state` and
  `synaptic_memory` arguments through `JPVRNeuronCheckpointStore.save()`.
- When present, the sidecar is embedded in the V2 runtime snapshot envelope.
  The base runtime and sidecar are therefore one JPVR record and are published
  with the neuron INDEX locator in the same JPVR COMMIT.
- Legacy V1 runtime snapshots remain readable. Saves without feature state
  continue to use the V1 snapshot representation.
- Decoder validation covers format/version, exact field sets, finite bounded
  values, UTF-8 identity, duplicate JSON keys, canonical synapse ordering, and
  matching neuron identity.
- Focused persistence regressions are authored in
  `tools/neuron_runtime/test_jpvr_store.py`, alongside codec tests in
  `tools/neuron_runtime/test_feature_codec.py`. They have **not been run**.

This integration is deliberately limited to one-neuron checkpoint save/load.
It does not yet connect feature updates to `NetworkRuntime`, the bounded
`NeuronWorkingSet`, the Android SQLite timestamp transaction, or the Android
neuron runtime. It also does not prove network-wide atomicity across multiple
neurons or event queues.

Next integration gate: carry feature state through the network's candidate /
commit / publish cycle and persist all changed neuron records plus event-queue
changes under one network-level transaction boundary. Only after that should
the 500,000-neuron capacity path be raised in controlled stages.
