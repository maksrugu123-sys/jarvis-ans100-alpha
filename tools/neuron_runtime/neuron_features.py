"""Optional cognitive state beside the compact ANS-100 neuron core.

Deterministic bounded feature updates. This module does not alter Numerical
Oracle equations and is not yet integrated with NetworkRuntime.
"""
from __future__ import annotations
from dataclasses import dataclass
from enum import Enum
import math

class NeuronRole(str, Enum):
    DETECTOR = "detector"
    MEMORY = "memory"
    PREDICTOR = "predictor"
    CONTEXT = "context"
    CONFIDENCE = "confidence"
    INHIBITORY = "inhibitory"
    COORDINATOR = "coordinator"

class KnowledgeStatus(str, Enum):
    SUPPORTED = "supported"
    REFUTED = "refuted"
    CONFLICTING = "conflicting"
    UNRESOLVED = "unresolved"

class NeuronFeatureError(ValueError):
    """Invalid cognitive sidecar state or observation."""

def _unit(name: str, value: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise NeuronFeatureError(f"{name} must be a real number")
    value = float(value)
    if not math.isfinite(value) or not 0.0 <= value <= 1.0:
        raise NeuronFeatureError(f"{name} must be finite and in [0, 1]")
    return value

def _signed(name: str, value: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise NeuronFeatureError(f"{name} must be a real number")
    value = float(value)
    if not math.isfinite(value) or not -1.0 <= value <= 1.0:
        raise NeuronFeatureError(f"{name} must be finite and in [-1, 1]")
    return value

def _clamp(value: float) -> float:
    return min(1.0, max(0.0, value))

@dataclass(frozen=True, slots=True)
class NeuronFeatureState:
    """Optional sparse state; these fields do not enlarge the 40-byte core."""
    sensitivity: float = 1.0
    memory_trace: float = 0.0
    prediction_error: float = 0.0
    confidence: float = 0.0
    uncertainty: float = 1.0
    plasticity: float = 0.5
    inhibition: float = 0.0
    context_match: float = 0.0
    observations: int = 0

    def validate(self) -> None:
        if not math.isfinite(self.sensitivity) or not 0.5 <= self.sensitivity <= 2.0:
            raise NeuronFeatureError("sensitivity must be finite and in [0.5, 2]")
        for name in ("memory_trace", "prediction_error", "confidence", "uncertainty",
                     "plasticity", "inhibition", "context_match"):
            _unit(name, getattr(self, name))
        if isinstance(self.observations, bool) or not isinstance(self.observations, int):
            raise NeuronFeatureError("observations must be a non-negative integer")
        if not 0 <= self.observations <= (1 << 64) - 1:
            raise NeuronFeatureError("observations exceeds unsigned 64-bit range")

@dataclass(frozen=True, slots=True)
class NeuronFeatureInput:
    activity: float
    predicted_activity: float
    context_similarity: float = 0.0
    inhibitory_drive: float = 0.0
    reward: float = 0.0

    def validate(self) -> None:
        _unit("activity", self.activity)
        _unit("predicted_activity", self.predicted_activity)
        _unit("context_similarity", self.context_similarity)
        _unit("inhibitory_drive", self.inhibitory_drive)
        _signed("reward", self.reward)

@dataclass(frozen=True, slots=True)
class NeuronFeatureConfig:
    memory_decay: float = 0.96
    confidence_rate: float = 0.12
    uncertainty_rate: float = 0.20
    inhibition_decay: float = 0.75
    sensitivity_rate: float = 0.04
    plasticity_surprise_gain: float = 0.55
    plasticity_stability_gain: float = 0.25
    target_activity: float = 0.20

    def validate(self) -> None:
        for name in ("memory_decay", "confidence_rate", "uncertainty_rate",
                     "inhibition_decay", "sensitivity_rate",
                     "plasticity_surprise_gain", "plasticity_stability_gain",
                     "target_activity"):
            _unit(name, getattr(self, name))

def update_neuron_features(
    state: NeuronFeatureState,
    observation: NeuronFeatureInput,
    config: NeuronFeatureConfig = NeuronFeatureConfig(),
) -> NeuronFeatureState:
    """Update sidecar features; confidence is NOT a calibrated probability.

    Sensitivity is a threshold multiplier proposal. The existing ALIF-DT
    threshold is unchanged until an explicit adapter applies this state.
    """
    if not isinstance(state, NeuronFeatureState):
        raise NeuronFeatureError("state must be NeuronFeatureState")
    if not isinstance(observation, NeuronFeatureInput):
        raise NeuronFeatureError("observation must be NeuronFeatureInput")
    if not isinstance(config, NeuronFeatureConfig):
        raise NeuronFeatureError("config must be NeuronFeatureConfig")
    state.validate()
    observation.validate()
    config.validate()
    if state.observations == (1 << 64) - 1:
        raise NeuronFeatureError("observation counter exhausted")

    surprise = abs(observation.activity - observation.predicted_activity)
    context_gap = 1.0 - observation.context_similarity
    uncertainty_target = _clamp(0.75 * surprise + 0.25 * context_gap)
    uncertainty = _clamp((1.0 - config.uncertainty_rate) * state.uncertainty
                         + config.uncertainty_rate * uncertainty_target)
    confidence = _clamp((1.0 - config.confidence_rate) * state.confidence
                        + config.confidence_rate * (1.0 - uncertainty))
    rewarded_activity = _clamp(observation.activity + max(0.0, observation.reward) * 0.25)
    memory_trace = _clamp(config.memory_decay * state.memory_trace
                           + (1.0 - config.memory_decay) * rewarded_activity)
    inhibition = _clamp(config.inhibition_decay * state.inhibition
                        + (1.0 - config.inhibition_decay) * observation.inhibitory_drive)
    sensitivity = min(2.0, max(0.5, state.sensitivity + config.sensitivity_rate
                               * (observation.activity - config.target_activity)))
    plasticity = _clamp(0.5 + config.plasticity_surprise_gain * surprise
                        - config.plasticity_stability_gain * confidence
                        + 0.15 * max(0.0, observation.reward))
    return NeuronFeatureState(
        sensitivity=sensitivity, memory_trace=memory_trace,
        prediction_error=surprise, confidence=confidence,
        uncertainty=uncertainty, plasticity=plasticity,
        inhibition=inhibition, context_match=observation.context_similarity,
        observations=state.observations + 1,
    )

@dataclass(frozen=True, slots=True)
class SynapticMemoryState:
    coactivation_trace: float = 0.0
    predictive_trace: float = 0.0
    reward_trace: float = 0.0

    def validate(self) -> None:
        for name in ("coactivation_trace", "predictive_trace", "reward_trace"):
            _unit(name, getattr(self, name))

def update_synaptic_memory(
    state: SynapticMemoryState, *, coactivation: float,
    prediction_success: float, reward: float,
    decay: float = 0.98, learning_rate: float = 0.05,
) -> SynapticMemoryState:
    """Update a sparse synapse sidecar without changing its canonical weight."""
    if not isinstance(state, SynapticMemoryState):
        raise NeuronFeatureError("state must be SynapticMemoryState")
    state.validate()
    coactivation = _unit("coactivation", coactivation)
    prediction_success = _unit("prediction_success", prediction_success)
    reward = _signed("reward", reward)
    decay = _unit("decay", decay)
    learning_rate = _unit("learning_rate", learning_rate)
    return SynapticMemoryState(
        coactivation_trace=_clamp(decay * state.coactivation_trace + learning_rate * coactivation),
        predictive_trace=_clamp(decay * state.predictive_trace + learning_rate * prediction_success),
        reward_trace=_clamp(decay * state.reward_trace + learning_rate * max(0.0, reward)),
    )

def resolve_evidence(
    support: float, refute: float, uncertainty: float, *,
    evidence_threshold: float = 0.60, conflict_threshold: float = 0.45,
) -> KnowledgeStatus:
    """Four-valued evidence logic; missing evidence never becomes NO."""
    support = _unit("support", support)
    refute = _unit("refute", refute)
    uncertainty = _unit("uncertainty", uncertainty)
    evidence_threshold = _unit("evidence_threshold", evidence_threshold)
    conflict_threshold = _unit("conflict_threshold", conflict_threshold)
    if support >= evidence_threshold and refute >= conflict_threshold:
        return KnowledgeStatus.CONFLICTING
    if support >= evidence_threshold and uncertainty < 0.80:
        return KnowledgeStatus.SUPPORTED
    if refute >= evidence_threshold and uncertainty < 0.80:
        return KnowledgeStatus.REFUTED
    return KnowledgeStatus.UNRESOLVED

def effective_excitatory_gain(activity: float, inhibition: float) -> float:
    """Bounded inhibitory gate; suppression never deletes memory or alternatives."""
    return _unit("activity", activity) * (1.0 - _unit("inhibition", inhibition))
