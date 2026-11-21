"""Cognitive wrapper that updates sparse sidecar features on real neuron batches.

The ALIF-DT equations remain owned by NeuronRuntime/Numerical Oracle. This
wrapper adds post-batch feature adaptation without silently changing the
membrane threshold or canonical synaptic weights.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from .neuron_features import (
    NeuronFeatureConfig, NeuronFeatureInput, NeuronFeatureState,
    SynapticMemoryState, update_neuron_features, update_synaptic_memory,
)
from .runtime import NeuronRuntime, RuntimeStep, SynapticInput


class CognitiveNeuronError(ValueError):
    """Invalid cognitive wrapper operation."""


@dataclass(frozen=True)
class CognitiveNeuronStep:
    runtime_step: RuntimeStep
    feature_state: NeuronFeatureState
    synaptic_memory: tuple[tuple[str, SynapticMemoryState], ...]

    @property
    def spike_emitted(self) -> bool:
        return self.runtime_step.spike_emitted


class CognitiveNeuron:
    """One real ALIF-DT runtime plus optional sparse adaptive feature state.

    Call process_batch once per model timestamp. Feature observations are
    derived from the actual spike result and caller-provided prediction/context
    signals. The wrapper does not claim confidence is calibrated probability.
    """

    def __init__(
        self,
        runtime: NeuronRuntime,
        *,
        neuron_id: str,
        feature_state: NeuronFeatureState | None = None,
        synaptic_memory: dict[str, SynapticMemoryState] | None = None,
        feature_config: NeuronFeatureConfig = NeuronFeatureConfig(),
    ) -> None:
        if not isinstance(runtime, NeuronRuntime):
            raise CognitiveNeuronError("runtime must be a NeuronRuntime")
        if not isinstance(neuron_id, str) or not neuron_id.strip() or "\x00" in neuron_id:
            raise CognitiveNeuronError("neuron_id must be a non-empty string without NUL")
        try:
            if len(neuron_id.encode("utf-8", "strict")) > 512:
                raise CognitiveNeuronError("neuron_id exceeds 512 UTF-8 bytes")
        except UnicodeEncodeError as exc:
            raise CognitiveNeuronError("neuron_id must be valid UTF-8") from exc
        if feature_state is None:
            feature_state = NeuronFeatureState()
        if not isinstance(feature_state, NeuronFeatureState):
            raise CognitiveNeuronError("feature_state must be NeuronFeatureState")
        feature_state.validate()
        if not isinstance(feature_config, NeuronFeatureConfig):
            raise CognitiveNeuronError("feature_config must be NeuronFeatureConfig")
        feature_config.validate()
        memories = {} if synaptic_memory is None else dict(synaptic_memory)
        known = set(runtime.synapses)
        if set(memories) - known:
            raise CognitiveNeuronError("synaptic memory references an unknown synapse")
        for key, value in memories.items():
            if not isinstance(key, str) or not isinstance(value, SynapticMemoryState):
                raise CognitiveNeuronError("invalid synaptic memory entry")
            value.validate()
        self.runtime = runtime
        self.neuron_id = neuron_id
        self.feature_state = feature_state
        self.synaptic_memory = memories
        self.feature_config = feature_config

    def process_batch(
        self,
        time_ns: int,
        inputs: Sequence[SynapticInput],
        *,
        predicted_activity: float,
        context_similarity: float = 0.0,
        inhibitory_drive: float = 0.0,
        reward: float = 0.0,
        learning_context_id: str | None = None,
    ) -> CognitiveNeuronStep:
        # Validate the caller-supplied observation before the base runtime can
        # mutate, so malformed sidecar input cannot leave a half-updated step.
        probe = NeuronFeatureInput(
            activity=0.0, predicted_activity=predicted_activity,
            context_similarity=context_similarity,
            inhibitory_drive=inhibitory_drive, reward=reward,
        )
        probe.validate()
        if not isinstance(inputs, (list, tuple)):
            raise CognitiveNeuronError("inputs must be a list or tuple")
        for event in inputs:
            if not isinstance(event, SynapticInput):
                raise CognitiveNeuronError("inputs must contain SynapticInput values")
            event.validate()
            if event.synapse_id not in self.runtime.synapses:
                raise CognitiveNeuronError(f"unknown synapse ID: {event.synapse_id}")
        if self.feature_state.observations == (1 << 64) - 1:
            raise CognitiveNeuronError("feature observation counter exhausted")

        result = self.runtime.process_batch(
            time_ns, inputs, learning_context_id=learning_context_id,
        )
        activity = 1.0 if result.spike_emitted else 0.0
        observation = NeuronFeatureInput(
            activity=activity, predicted_activity=predicted_activity,
            context_similarity=context_similarity,
            inhibitory_drive=inhibitory_drive, reward=reward,
        )
        next_features = update_neuron_features(
            self.feature_state, observation, self.feature_config,
        )

        # Update only traces for synapses that received input in this batch.
        # The canonical STDP/R-STDP weight remains solely in NeuronRuntime.
        next_memories = dict(self.synaptic_memory)
        active_strength: dict[str, float] = {}
        for event in inputs:
            active_strength[event.synapse_id] = max(
                active_strength.get(event.synapse_id, 0.0), float(event.strength),
            )
        for synapse_id, strength in active_strength.items():
            previous = next_memories.get(synapse_id, SynapticMemoryState())
            next_memories[synapse_id] = update_synaptic_memory(
                previous,
                coactivation=strength,
                prediction_success=1.0 - abs(activity - predicted_activity),
                reward=reward,
            )

        self.feature_state = next_features
        self.synaptic_memory = next_memories
        return CognitiveNeuronStep(
            runtime_step=result,
            feature_state=next_features,
            synaptic_memory=tuple(sorted(next_memories.items())),
        )
