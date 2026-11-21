"""Optional cognitive state integrated into the bounded ANS network event loop.

Feature state is kept as a sparse sidecar and participates in input gating,
activity prediction, confidence/uncertainty updates, and synaptic memory traces.
The Numerical Oracle neuron equations remain unchanged.
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
    PATTERN = "pattern"
    ATTENTION = "attention"
    CONTRADICTION = "contradiction"
    EXCITATORY = "excitatory"

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
    pattern_signature: int = 0
    pattern_trace: float = 0.0
    pattern_observations: int = 0
    predictor_expected_signature: int = 0
    predictor_confidence: float = 0.0
    predictor_observations: int = 0
    predictor_hits: int = 0

    def validate(self) -> None:
        if (isinstance(self.sensitivity, bool)
                or not isinstance(self.sensitivity, (int, float))
                or not math.isfinite(self.sensitivity)
                or not 0.5 <= self.sensitivity <= 2.0):
            raise NeuronFeatureError("sensitivity must be a finite real number in [0.5, 2]")
        for name in ("memory_trace", "prediction_error", "confidence", "uncertainty",
                     "plasticity", "inhibition", "context_match", "pattern_trace", "predictor_confidence"):
            _unit(name, getattr(self, name))
        for name in ("observations", "pattern_signature", "pattern_observations", "predictor_expected_signature", "predictor_observations", "predictor_hits"):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= (1 << 64) - 1:
                raise NeuronFeatureError(f"{name} exceeds unsigned 64-bit range")

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
    """Update bounded sidecar features; confidence is NOT a calibrated probability.

    Sensitivity is consumed by NetworkRuntime as a bounded input-gain factor.
    It does not rewrite the Numerical Oracle threshold or its LIF-DT equations.
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
    # Negative feedback: low activity raises sensitivity; high activity lowers it.
    sensitivity = min(2.0, max(0.5, state.sensitivity + config.sensitivity_rate
                               * (config.target_activity - observation.activity)))
    plasticity = _clamp(0.5 + config.plasticity_surprise_gain * surprise
                        - config.plasticity_stability_gain * confidence
                        + 0.15 * max(0.0, observation.reward))
    return NeuronFeatureState(
        sensitivity=sensitivity, memory_trace=memory_trace,
        prediction_error=surprise, confidence=confidence,
        uncertainty=uncertainty, plasticity=plasticity,
        inhibition=inhibition, context_match=observation.context_similarity,
        observations=state.observations + 1,
        pattern_signature=state.pattern_signature,
        pattern_trace=state.pattern_trace,
        pattern_observations=state.pattern_observations,
        predictor_expected_signature=state.predictor_expected_signature,
        predictor_confidence=state.predictor_confidence,
        predictor_observations=state.predictor_observations,
        predictor_hits=state.predictor_hits,
    )



def effective_prediction_gain(state: NeuronFeatureState, observed_signature: int) -> float:
    """Bounded confidence-weighted gain for a one-step persistence forecast."""
    state.validate()
    if isinstance(observed_signature, bool) or not isinstance(observed_signature, int) or not 0 <= observed_signature <= (1 << 64) - 1:
        raise NeuronFeatureError("observed signature must be an unsigned 64-bit integer")
    if not state.predictor_expected_signature or not observed_signature:
        return 1.0
    if observed_signature == state.predictor_expected_signature:
        return min(1.15, 1.0 + 0.15 * state.predictor_confidence)
    return max(0.85, 1.0 - 0.10 * state.predictor_confidence)


def update_sequence_predictor(
    state: NeuronFeatureState, observed_signature: int, *,
    confidence_decay: float = 0.80, confidence_rate: float = 0.20,
) -> NeuronFeatureState:
    """Update a bounded next-observation persistence predictor.

    This deliberately predicts recurrence of the latest active-synapse set;
    it is not a general n-gram or semantic sequence model.
    """
    state.validate()
    if isinstance(observed_signature, bool) or not isinstance(observed_signature, int) or not 0 <= observed_signature <= (1 << 64) - 1:
        raise NeuronFeatureError("observed signature must be an unsigned 64-bit integer")
    confidence_decay = _unit("confidence_decay", confidence_decay)
    confidence_rate = _unit("confidence_rate", confidence_rate)
    if observed_signature == 0:
        return state
    if state.predictor_expected_signature == 0:
        return NeuronFeatureState(**{
            **{name: getattr(state, name) for name in NeuronFeatureState.__dataclass_fields__},
            "predictor_expected_signature": observed_signature,
        })
    if state.predictor_observations == (1 << 64) - 1:
        raise NeuronFeatureError("predictor observation counter exhausted")
    correct = observed_signature == state.predictor_expected_signature
    hits = state.predictor_hits + int(correct)
    if hits > (1 << 64) - 1:
        raise NeuronFeatureError("predictor hit counter exhausted")
    confidence = _clamp(confidence_decay * state.predictor_confidence
                        + confidence_rate * (1.0 if correct else 0.0))
    return NeuronFeatureState(**{
        **{name: getattr(state, name) for name in NeuronFeatureState.__dataclass_fields__},
        "predictor_expected_signature": observed_signature,
        "predictor_confidence": confidence,
        "predictor_observations": state.predictor_observations + 1,
        "predictor_hits": hits,
    })


def update_pattern_memory(
    state: NeuronFeatureState, signature: int, *, decay: float = 0.90,
    increment: float = 0.10,
) -> NeuronFeatureState:
    """Track recurrence of the latest exact coactivation signature in O(1) state.

    A changed signature starts a new trace; a repeated signature reinforces it.
    Empty (zero) signatures only decay the trace and do not erase the last key.
    """
    if not isinstance(state, NeuronFeatureState):
        raise NeuronFeatureError("state must be NeuronFeatureState")
    state.validate()
    if isinstance(signature, bool) or not isinstance(signature, int) or not 0 <= signature <= (1 << 64) - 1:
        raise NeuronFeatureError("pattern signature must be an unsigned 64-bit integer")
    decay = _unit("pattern decay", decay)
    increment = _unit("pattern increment", increment)
    if signature == 0:
        return NeuronFeatureState(
            **{
                **{name: getattr(state, name) for name in NeuronFeatureState.__dataclass_fields__},
                "pattern_trace": _clamp(state.pattern_trace * decay),
            }
        )
    if state.pattern_observations == (1 << 64) - 1:
        raise NeuronFeatureError("pattern observation counter exhausted")
    repeated = signature == state.pattern_signature
    trace = _clamp(state.pattern_trace * decay + increment) if repeated else increment
    return NeuronFeatureState(
        **{
            **{name: getattr(state, name) for name in NeuronFeatureState.__dataclass_fields__},
            "pattern_signature": signature,
            "pattern_trace": trace,
            "pattern_observations": state.pattern_observations + 1,
        }
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

def effective_synaptic_memory_gain(state: SynapticMemoryState) -> float:
    """Return a bounded gain from prior synaptic experience.

    Coactivation, successful prediction and positive reward provide small,
    independent facilitation signals. The gain is deliberately capped at 1.45;
    canonical synapse weights remain authoritative and are never rewritten.
    """
    if not isinstance(state, SynapticMemoryState):
        raise NeuronFeatureError("state must be SynapticMemoryState")
    state.validate()
    gain = (
        1.0
        + 0.20 * state.coactivation_trace
        + 0.15 * state.predictive_trace
        + 0.10 * state.reward_trace
    )
    return min(1.45, max(1.0, gain))


def effective_excitatory_gain(
    activity: float, inhibition: float, sensitivity: float = 1.0,
) -> float:
    """Apply bounded sensitivity and inhibition to one excitatory input.

    Sensitivity may amplify an input, but the resulting signal remains in [0, 1].
    Inhibition gates activation without deleting stored memory or alternatives.
    """
    activity = _unit("activity", activity)
    inhibition = _unit("inhibition", inhibition)
    if (isinstance(sensitivity, bool) or not isinstance(sensitivity, (int, float))
            or not math.isfinite(sensitivity) or not 0.5 <= sensitivity <= 2.0):
        raise NeuronFeatureError("sensitivity must be a finite real number in [0.5, 2]")
    return _clamp(activity * float(sensitivity) * (1.0 - inhibition))
