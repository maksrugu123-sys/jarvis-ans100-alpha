# Cognitive neuron runtime integration V1

`tools/neuron_runtime/cognitive_runtime.py` adds a per-neuron orchestration
wrapper around the existing ALIF-DT `NeuronRuntime`.

## What is now connected

- Every successful model-time batch produces an actual spike/no-spike outcome.
- The wrapper converts that outcome plus supplied prediction, context,
  inhibitory-drive and reward signals into a validated feature observation.
- The feature state is updated using the existing bounded deterministic
  feature equations.
- Sparse synaptic coactivation, predictive-success and reward traces are
  updated only for synapses active in the batch.
- Canonical STDP/R-STDP synaptic weights remain owned by the base runtime;
  sidecar traces do not silently overwrite them.
- Invalid caller-provided feature observations are rejected before the base
  runtime is allowed to mutate.

## Explicit limits

- Sensitivity, inhibition, confidence and uncertainty are state signals; this
  wrapper does not yet feed them back into ALIF threshold, event routing,
  plasticity equations or reasoning decisions.
- The wrapper is not yet integrated into `NetworkRuntime`, Android runtime,
  or JPVR/working-set persistence. Use `feature_codec.py` for standalone
  sidecar serialization; atomic joint persistence remains a separate task.
- This does not increase the 10,000-neuron network cap or prove 500,000-neuron
  scalability.
- Focused tests are authored but not executed in this remote edit.

Next: add a versioned cognitive checkpoint envelope and extend the bounded
working-set backend so base runtime and sidecar are saved in one JPVR commit.
