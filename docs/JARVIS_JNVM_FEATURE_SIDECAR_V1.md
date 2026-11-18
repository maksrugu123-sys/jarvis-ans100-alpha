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

## Network execution integration (Python reference)

`NetworkRuntime` now accepts explicit sparse `feature_states` and
`synaptic_memories` mappings. For neurons with a feature sidecar, the prior
sensitivity and inhibition values gate incoming excitatory signal strength
before the Numerical Oracle-backed neuron update. After the candidate update,
activity, prior memory trace and event attention are used to update the feature
state; touched synapses receive bounded coactivation/prediction traces. Reward
updates add a reward trace without silently changing canonical STDP/R-STDP
weights. Neurons without a sidecar retain the baseline input path.

`NetworkRuntime.export_checkpoint_state()` includes the sidecars, and
`restore_checkpoint_state()` validates and restores them. The single-container
`JPVRNetworkJNVMStore` now embeds each changed neuron's optional sidecar in the
same versioned neuron record and the same group COMMIT as the scheduler state.
Restore reads all neuron sidecars from the same pinned JPVR snapshot. Thus the
Python reference path now carries feature state through candidate / commit /
publish and restart restoration; it does not rely on the separate 128-shard
per-neuron write-back working set.

This does **not** yet integrate the sidecar with Android's SQLite timestamp
transaction or Kotlin `AndroidSparseNeuronNetwork`. It also does not make the
confidence value a calibrated probability, implement a full inhibitory
population, or prove a complete predictive model. The prediction baseline is
the prior bounded memory trace, and context similarity currently uses the
mean event-attention value. The network currently supplies zero new
`inhibitory_drive`, so inhibition is only an input gate for an existing state
value that decays; a real inhibitory population is not connected. The
`plasticity` field is calculated and persisted but does not yet modulate the
Numerical Oracle's STDP/R-STDP learning parameters. Synaptic coactivation,
predictive and reward traces are persisted, but are not yet consumed by later
activation or credit assignment. These are explicit first integration
semantics, not claims of human-like cognition.

See `docs/JARVIS_INTEGRATION_AUDIT_V1.md` for the current component-by-component
connection audit and next integration order.

Focused tests have been authored for real signal modulation, feature updates,
and persistence/restore. They are **NOT RUN**. No benchmark, build, CI or
device measurement has been performed.

Next gate: differential Python/Kotlin behavior and a separately designed
Android transaction integration, followed by measured working-set integration.
The 500,000-neuron capacity path remains a future staged target.
