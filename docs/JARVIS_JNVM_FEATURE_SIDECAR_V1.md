# JNVM cognitive sidecar codec V1

The optional feature state has a strict, deterministic, versioned UTF-8 codec
in `tools/neuron_runtime/feature_codec.py`.

- The base ALIF-DT hot state remains unchanged (40-byte numeric payload).
- Per-neuron cognitive features and per-synapse memory traces are encoded as a
  separate sparse sidecar; neurons with no sidecar need no feature payload.
- Decoder validates format/version, exact field sets, finite bounded values,
  UTF-8 identity, duplicate JSON keys, canonical synapse ordering, and optional
  expected neuron identity.
- This is a serialization component only. It is **not yet attached** to
  `NeuronRuntime`, `NeuronWorkingSet`, JPVR checkpoint transactions, or the
  Android SQLite timestamp transaction. It therefore does not yet provide
  crash-atomic persistence of feature state with the base neuron state.
- Focused tests are authored in
  `tools/neuron_runtime/test_feature_codec.py`; they have not been run in this
  remote editing session.

Next integration gate: extend the JPVR checkpoint schema with an optional
sidecar reference and commit it in the same checkpoint transaction as the
base state, preserving read compatibility for schema V1. Then wire feature
updates into candidate/commit/publish in the network scheduler and mirror the
same transaction boundary on Android.
