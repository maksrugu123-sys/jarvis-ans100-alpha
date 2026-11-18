"""Small executable JARVIS coordinator backed by the Numerical Oracle.

This is an in-memory reference orchestration layer, not a production runtime.
It delegates all neuron and plasticity equations to the immutable Oracle.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
import math
from typing import Literal, Mapping, Sequence

from tools.numerical_oracle.jarvis_oracle import (
    InputSignal, NeuronParams, NeuronState, NeuronStep, PlasticityStep,
    RewardParams, RewardStep, SynapseParams, SynapseState, apply_reward,
    process_neuron_batch, starter_neuron_params, starter_reward_params,
    starter_synapse_params, update_rstdp, update_stdp,
)

LearningRule = Literal["none", "stdp", "rstdp"]
_U64_MAX = (1 << 64) - 1

def _plasticity_scale(value: float) -> float:
    if (isinstance(value, bool) or not isinstance(value, (int, float))
            or not math.isfinite(value) or not 0.25 <= value <= 2.0):
        raise NeuronRuntimeError("plasticity_scale must be a finite real number in [0.25, 2]")
    return float(value)


class NeuronRuntimeError(ValueError):
    """Invalid runtime configuration or input."""


@dataclass(frozen=True)
class SynapticInput:
    event_id: str
    synapse_id: str
    strength: float
    attention: float = 0.0

    def validate(self) -> None:
        if not isinstance(self.event_id, str) or not self.event_id.strip():
            raise NeuronRuntimeError("event_id must be a non-empty string")
        if not isinstance(self.synapse_id, str) or not self.synapse_id.strip():
            raise NeuronRuntimeError("synapse_id must be a non-empty string")
        for name, value in (("strength", self.strength), ("attention", self.attention)):
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise NeuronRuntimeError(f"{name} must be a real number")
            if not math.isfinite(float(value)) or not 0.0 <= float(value) <= 1.0:
                raise NeuronRuntimeError(f"{name} must be finite and in [0, 1]")


@dataclass(frozen=True)
class SynapseUpdate:
    synapse_id: str
    n_pre: int
    n_post: int
    plasticity: PlasticityStep


@dataclass(frozen=True)
class RuntimeStep:
    time_ns: int
    neuron: NeuronStep
    synapse_updates: tuple[SynapseUpdate, ...]

    @property
    def spike_emitted(self) -> bool:
        return self.neuron.spike_emitted

    @property
    def spike_sequence(self) -> int:
        return self.neuron.state.spike_sequence


@dataclass(frozen=True)
class RewardUpdate:
    synapse_id: str
    baseline_before: float
    result: RewardStep


class NeuronRuntime:
    """Coordinate one ALIF-DT neuron with incoming excitatory synapses.

    Events with the same model timestamp must be supplied in one batch.
    Each active synapse receives its own presynaptic event count and the
    neuron's shared postsynaptic spike count. R-STDP requires a stable
    learning_context_id for the episode. Reward baseline remains external.
    """

    def __init__(
        self,
        synapses: Mapping[str, float | SynapseState],
        *,
        neuron_state: NeuronState | None = None,
        neuron_params: NeuronParams | None = None,
        synapse_params: SynapseParams | None = None,
        reward_params: RewardParams | None = None,
        learning_rule: LearningRule = "none",
        processed_batches: int = 0,
    ) -> None:
        if (
            isinstance(processed_batches, bool)
            or not isinstance(processed_batches, int)
            or not 0 <= processed_batches <= _U64_MAX
        ):
            raise NeuronRuntimeError("processed_batches must be an unsigned 64-bit integer")
        if learning_rule not in ("none", "stdp", "rstdp"):
            raise NeuronRuntimeError("learning_rule must be none, stdp or rstdp")
        if not isinstance(synapses, Mapping):
            raise NeuronRuntimeError("synapses must map IDs to weights or states")
        self._neuron_params = neuron_params if neuron_params is not None else starter_neuron_params()
        self._synapse_params = synapse_params if synapse_params is not None else starter_synapse_params()
        self._reward_params = reward_params if reward_params is not None else starter_reward_params()
        self._neuron_params.validate()
        self._synapse_params.validate()
        self._reward_params.validate()
        if self._synapse_params.w_min < 0.0 or self._synapse_params.w_max > 1.0:
            raise NeuronRuntimeError("this increment supports excitatory weights in [0, 1] only")

        self._neuron_state = neuron_state if neuron_state is not None else NeuronState()
        self._neuron_state.validate(self._neuron_params)
        self._synapse_states: dict[str, SynapseState] = {}
        for synapse_id, value in synapses.items():
            if not isinstance(synapse_id, str) or not synapse_id.strip():
                raise NeuronRuntimeError("synapse IDs must be non-empty strings")
            state = value if isinstance(value, SynapseState) else SynapseState(weight=value)
            state.validate(self._synapse_params, self._reward_params)
            # Synaptic trace/reward clocks may legitimately advance after the
            # last membrane update. They share a clock epoch, but are distinct
            # state clocks and must be validated independently by the Oracle.
            self._synapse_states[synapse_id] = state

        self._learning_rule: LearningRule = learning_rule
        self._processed_batches = processed_batches

    @property
    def neuron_params(self) -> NeuronParams:
        return self._neuron_params

    @property
    def synapse_params(self) -> SynapseParams:
        return self._synapse_params

    @property
    def reward_params(self) -> RewardParams:
        return self._reward_params

    @property
    def neuron_state(self) -> NeuronState:
        return self._neuron_state

    @property
    def synapses(self) -> Mapping[str, SynapseState]:
        return dict(self._synapse_states)

    @property
    def synapse_count(self) -> int:
        """Return the number of owned synapses without materializing a copy."""
        return len(self._synapse_states)

    @property
    def learning_rule(self) -> LearningRule:
        return self._learning_rule

    @property
    def processed_batches(self) -> int:
        return self._processed_batches

    def process_batch(
        self,
        time_ns: int,
        inputs: Sequence[SynapticInput],
        *,
        learning_context_id: str | None = None,
        plasticity_scale: float = 1.0,
    ) -> RuntimeStep:
        plasticity_scale = _plasticity_scale(plasticity_scale)
        if self._processed_batches >= _U64_MAX:
            raise NeuronRuntimeError("processed_batches exhausted its unsigned 64-bit range")
        if not isinstance(inputs, (list, tuple)):
            raise NeuronRuntimeError("inputs must be a list or tuple")
        prepared = tuple(inputs)
        for event in prepared:
            if not isinstance(event, SynapticInput):
                raise NeuronRuntimeError("every input must be a SynapticInput")
            event.validate()
            if event.synapse_id not in self._synapse_states:
                raise NeuronRuntimeError(f"unknown synapse ID: {event.synapse_id}")

        event_ids = [event.event_id for event in prepared]
        if len(event_ids) != len(set(event_ids)):
            raise NeuronRuntimeError("event IDs must be unique within one timestamp batch")
        if self._learning_rule == "rstdp":
            if not isinstance(learning_context_id, str) or not learning_context_id.strip():
                raise NeuronRuntimeError("R-STDP requires a non-empty learning_context_id")

        pre_counts = {key: 0 for key in self._synapse_states}
        signals: list[InputSignal] = []
        for event in prepared:
            state = self._synapse_states[event.synapse_id]
            pre_counts[event.synapse_id] += 1
            signals.append(InputSignal(
                source_id=event.event_id,
                signed_weight=state.weight,
                strength=float(event.strength),
                attention=float(event.attention),
            ))

        # Calculate all candidate results before mutating this coordinator.
        neuron_candidate = process_neuron_batch(
            self._neuron_state, time_ns, signals, self._neuron_params
        )
        synapse_candidates = dict(self._synapse_states)
        updates: list[SynapseUpdate] = []
        n_post = int(neuron_candidate.spike_emitted)

        if self._learning_rule != "none":
            effective_synapse_params = (
                self._synapse_params if plasticity_scale == 1.0 else replace(
                    self._synapse_params,
                    eta_plus=self._synapse_params.eta_plus * plasticity_scale,
                    eta_minus=self._synapse_params.eta_minus * plasticity_scale,
                )
            )
            effective_synapse_params.validate()
            for synapse_id in sorted(self._synapse_states):
                current = self._synapse_states[synapse_id]
                n_pre = pre_counts[synapse_id]
                if self._learning_rule == "stdp":
                    plasticity = update_stdp(
                        current, effective_synapse_params, time_ns, n_pre, n_post
                    )
                else:
                    assert learning_context_id is not None
                    plasticity = update_rstdp(
                        current, effective_synapse_params, self._reward_params,
                        time_ns, n_pre, n_post, learning_context_id,
                    )
                synapse_candidates[synapse_id] = plasticity.state
                updates.append(SynapseUpdate(synapse_id, n_pre, n_post, plasticity))

        self._neuron_state = neuron_candidate.state
        self._synapse_states = synapse_candidates
        self._processed_batches += 1
        return RuntimeStep(time_ns, neuron_candidate, tuple(updates))

    def apply_reward(
        self,
        synapse_id: str,
        time_ns: int,
        reward_value: float,
        baseline_before: float,
        learning_context_id: str | None,
        *,
        plasticity_scale: float = 1.0,
    ) -> RewardUpdate:
        plasticity_scale = _plasticity_scale(plasticity_scale)
        if self._learning_rule != "rstdp":
            raise NeuronRuntimeError("reward is available only in rstdp mode")
        if synapse_id not in self._synapse_states:
            raise NeuronRuntimeError(f"unknown synapse ID: {synapse_id}")
        effective_reward_params = (
            self._reward_params if plasticity_scale == 1.0 else replace(
                self._reward_params,
                eta_r=self._reward_params.eta_r * plasticity_scale,
            )
        )
        effective_reward_params.validate()
        result = apply_reward(
            self._synapse_states[synapse_id],
            self._synapse_params,
            effective_reward_params,
            time_ns,
            reward_value,
            baseline_before,
            learning_context_id,
        )
        # Baseline ownership stays with the external Reward Baseline Manager.
        self._synapse_states[synapse_id] = result.state
        return RewardUpdate(synapse_id, baseline_before, result)
