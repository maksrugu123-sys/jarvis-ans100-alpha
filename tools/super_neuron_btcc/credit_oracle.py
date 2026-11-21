"""Reference replay oracle for JARVIS BTCC 0.2.

BTCC is an experimental diagnostic, not a production learning rule.
Counterfactual replays are isolated and never mutate the caller's state.
"""
from __future__ import annotations

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
import math
from typing import Optional

from tools.numerical_oracle.jarvis_oracle import (
    InputSignal,
    NeuronParams,
    NeuronState,
    OracleInputError,
    SynapseParams,
    attention_gain,
    decay_neuron_state,
    effective_threshold,
    process_neuron_batch,
)

U64_MAX = (1 << 64) - 1
DEFAULT_MAX_COUNTERFACTUAL_EVENTS = 8
MAX_BTCC_EVENTS = 64
DEFAULT_LOSS_EPSILON = 1e-12


@dataclass(frozen=True)
class BTCCEvent:
    event_id: str
    synapse_id: str
    time_ns: int
    strength: float
    attention: float = 0.0

    def validate(self) -> None:
        if not isinstance(self.event_id, str) or not self.event_id:
            raise OracleInputError("event_id must be a non-empty string")
        if not isinstance(self.synapse_id, str) or not self.synapse_id:
            raise OracleInputError("synapse_id must be a non-empty string")
        if (
            isinstance(self.time_ns, bool)
            or not isinstance(self.time_ns, int)
            or not 0 <= self.time_ns <= U64_MAX
        ):
            raise OracleInputError("time_ns must be a U64 model timestamp")
        _unit_interval("strength", self.strength)
        _unit_interval("attention", self.attention)


@dataclass(frozen=True)
class ReplayResult:
    predicted_fast: bool
    first_spike_time_ns: Optional[int]
    decision_margin: float
    margin_evaluation_time_ns: Optional[int]
    probability_fast: float
    loss: float
    target_fast: bool
    processed_event_count: int

    @property
    def correct(self) -> bool:
        return self.predicted_fast == self.target_fast


@dataclass(frozen=True)
class EventCredit:
    event_id: str
    synapse_id: str
    omission_loss: float
    delta_loss: float
    effective_event_contribution: float


@dataclass(frozen=True)
class SynapseCreditDirection:
    synapse_id: str
    fitted_negative_loss_slope: float
    decision_sensitivity_per_weight: float
    estimated_descent_direction: float


@dataclass(frozen=True)
class BTCCAudit:
    mode: str
    trigger_reason: str
    baseline: ReplayResult
    event_credits: tuple[EventCredit, ...]
    synapse_directions: tuple[SynapseCreditDirection, ...]

    @property
    def audited(self) -> bool:
        return bool(self.event_credits)


@dataclass(frozen=True)
class GradientComparison:
    synapse_id: str
    central_difference_gradient: float
    negative_gradient: float
    estimated_descent_direction: float
    sign_aligned: bool


def _finite(name: str, value: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise OracleInputError(f"{name} must be a real number")
    result = float(value)
    if not math.isfinite(result):
        raise OracleInputError(f"{name} must be finite")
    return result


def _unit_interval(name: str, value: float) -> float:
    result = _finite(name, value)
    if not 0.0 <= result <= 1.0:
        raise OracleInputError(f"{name} must be in [0, 1]")
    return result


def _target_bool(target_fast: int) -> bool:
    if (
        isinstance(target_fast, bool)
        or not isinstance(target_fast, int)
        or target_fast not in (0, 1)
    ):
        raise OracleInputError("target_fast must be integer 0 (SLOW) or 1 (FAST)")
    return bool(target_fast)


def _validate_inputs(
    events: Sequence[BTCCEvent],
    weights: Mapping[str, float],
    initial_state: NeuronState,
    neuron_params: NeuronParams,
    synapse_params: SynapseParams,
    target_fast: int,
    temperature: float,
    loss_epsilon: float,
    deadline_ns: Optional[int],
    max_events: int,
) -> tuple[tuple[BTCCEvent, ...], dict[str, float], bool, float, float]:
    if not isinstance(events, (list, tuple)):
        raise OracleInputError("events must be a list or tuple")
    if (
        isinstance(max_events, bool)
        or not isinstance(max_events, int)
        or not 1 <= max_events <= MAX_BTCC_EVENTS
    ):
        raise OracleInputError(
            f"max_events must be an integer in [1, {MAX_BTCC_EVENTS}]"
        )
    if len(events) > max_events:
        raise OracleInputError(
            f"episode has {len(events)} events; bounded BTCC limit is {max_events}"
        )
    prepared = tuple(events)
    for event in prepared:
        if not isinstance(event, BTCCEvent):
            raise OracleInputError("every event must be a BTCCEvent")
        event.validate()

    event_ids = [event.event_id for event in prepared]
    if len(event_ids) != len(set(event_ids)):
        raise OracleInputError("event_id values must be unique within an episode")

    if not isinstance(weights, Mapping):
        raise OracleInputError("weights must be a mapping from synapse ID to weight")
    required_synapses = {event.synapse_id for event in prepared}
    if set(weights) != required_synapses:
        raise OracleInputError("weights must contain exactly the synapses used by this episode")

    neuron_params.validate()
    synapse_params.validate()
    if synapse_params.w_min < 0.0 or synapse_params.w_max > 1.0:
        raise OracleInputError("BTCC 0.2 supports excitatory weights in [0, 1] only")
    initial_state.validate(neuron_params)

    weight_map: dict[str, float] = {}
    for synapse_id in sorted(required_synapses):
        weight = _finite(f"weights[{synapse_id!r}]", weights[synapse_id])
        if not synapse_params.w_min <= weight <= synapse_params.w_max:
            raise OracleInputError(f"weight for {synapse_id!r} is outside synapse bounds")
        weight_map[synapse_id] = weight

    target = _target_bool(target_fast)
    temperature_value = _finite("temperature", temperature)
    if temperature_value <= 0.0:
        raise OracleInputError("temperature must be positive")
    epsilon = _finite("loss_epsilon", loss_epsilon)
    if not 0.0 < epsilon < 0.5:
        raise OracleInputError("loss_epsilon must be in (0, 0.5)")

    if deadline_ns is not None:
        if (
            isinstance(deadline_ns, bool)
            or not isinstance(deadline_ns, int)
            or not 0 <= deadline_ns <= U64_MAX
        ):
            raise OracleInputError("deadline_ns must be a U64 model timestamp")
        if deadline_ns < initial_state.last_update_model_time_ns:
            raise OracleInputError("deadline cannot precede the initial neuron state")
        if any(event.time_ns > deadline_ns for event in prepared):
            raise OracleInputError("an input event occurs after the decision deadline")

    ordered = tuple(sorted(prepared, key=lambda event: (event.time_ns, event.event_id)))
    return ordered, weight_map, target, temperature_value, epsilon


def _binary_cross_entropy(
    margin: float, target_fast: bool, temperature: float, epsilon: float
) -> tuple[float, float]:
    scaled = margin / temperature
    if scaled >= 0.0:
        probability = 1.0 / (1.0 + math.exp(-scaled))
    else:
        exp_value = math.exp(scaled)
        probability = exp_value / (1.0 + exp_value)
    probability = min(1.0 - epsilon, max(epsilon, probability))
    loss = (
        -math.log(probability)
        if target_fast
        else -math.log1p(-probability)
    )
    return probability, loss


def replay_episode(
    events: Sequence[BTCCEvent],
    weights: Mapping[str, float],
    initial_state: NeuronState,
    neuron_params: NeuronParams,
    synapse_params: SynapseParams,
    target_fast: int,
    *,
    temperature: float = 0.25,
    loss_epsilon: float = DEFAULT_LOSS_EPSILON,
    deadline_ns: Optional[int] = None,
    max_events: int = DEFAULT_MAX_COUNTERFACTUAL_EVENTS,
) -> ReplayResult:
    """Replay an episode and calculate a smooth loss from its decision margin.

    Decision semantics: FAST iff the Oracle emits at least one spike at an
    input-event timestamp. The smooth margin is the maximum pre-reset,
    non-refractory membrane-minus-threshold margin up to the first spike.
    """
    ordered, weight_map, target, temperature_value, epsilon = _validate_inputs(
        events, weights, initial_state, neuron_params, synapse_params,
        target_fast, temperature, loss_epsilon, deadline_ns, max_events,
    )

    state = initial_state
    first_spike_time: Optional[int] = None
    selected_margin: Optional[float] = None
    selected_time: Optional[int] = None

    groups: dict[int, list[BTCCEvent]] = defaultdict(list)
    for event in ordered:
        groups[event.time_ns].append(event)

    for time_ns in sorted(groups):
        batch_events = groups[time_ns]
        signals = [
            InputSignal(
                source_id=event.event_id,
                signed_weight=weight_map[event.synapse_id],
                strength=event.strength,
                attention=event.attention,
            )
            for event in batch_events
        ]

        decayed = decay_neuron_state(state, time_ns, neuron_params)
        deltas = [
            attention_gain(signal.attention, neuron_params)
            * signal.signed_weight
            * signal.strength
            for signal in signals
        ]
        pre_reset_u = min(
            neuron_params.u_max,
            max(neuron_params.u_min, decayed.u + math.fsum(deltas)),
        )
        threshold = effective_threshold(decayed, neuron_params)
        refractory = time_ns < state.refractory_until_model_time_ns
        margin = pre_reset_u - threshold

        step = process_neuron_batch(
            state, time_ns, signals, neuron_params
        )

        if first_spike_time is None and not refractory:
            if selected_margin is None or margin > selected_margin:
                selected_margin = margin
                selected_time = time_ns
            if step.spike_emitted:
                first_spike_time = time_ns
                selected_margin = margin
                selected_time = time_ns

        state = step.state

    # Match the task's explicit decision deadline. Refractory-period inputs
    # remain in membrane state and may trigger a spike when refractoriness ends.
    # If an event already occurred exactly at the deadline, that timestamp was
    # evaluated in the loop above and must not be processed twice.
    if deadline_ns is not None and (
        not groups or deadline_ns > max(groups)
    ):
        decayed = decay_neuron_state(state, deadline_ns, neuron_params)
        margin = decayed.u - effective_threshold(decayed, neuron_params)
        refractory = deadline_ns < state.refractory_until_model_time_ns
        step = process_neuron_batch(state, deadline_ns, [], neuron_params)
        if first_spike_time is None and not refractory:
            if selected_margin is None or margin > selected_margin:
                selected_margin = margin
                selected_time = deadline_ns
            if step.spike_emitted:
                first_spike_time = deadline_ns
                selected_margin = margin
                selected_time = deadline_ns
        state = step.state

    if selected_margin is None:
        # No non-refractory decision evaluation was available.
        selected_margin = neuron_params.u_rest - neuron_params.theta_0
        selected_time = None

    probability, loss = _binary_cross_entropy(
        selected_margin, target, temperature_value, epsilon
    )
    return ReplayResult(
        predicted_fast=first_spike_time is not None,
        first_spike_time_ns=first_spike_time,
        decision_margin=selected_margin,
        margin_evaluation_time_ns=selected_time,
        probability_fast=probability,
        loss=loss,
        target_fast=target,
        processed_event_count=len(ordered),
    )


def _event_contribution_at_margin(
    event: BTCCEvent,
    weights: Mapping[str, float],
    selected_time_ns: Optional[int],
    neuron_params: NeuronParams,
) -> float:
    if selected_time_ns is None or event.time_ns > selected_time_ns:
        return 0.0
    elapsed = selected_time_ns - event.time_ns
    return (
        weights[event.synapse_id]
        * attention_gain(event.attention, neuron_params)
        * event.strength
        * math.exp(-elapsed / neuron_params.tau_m_ns)
    )


def audit_episode(
    events: Sequence[BTCCEvent],
    weights: Mapping[str, float],
    initial_state: NeuronState,
    neuron_params: NeuronParams,
    synapse_params: SynapseParams,
    target_fast: int,
    *,
    mode: str = "gated",
    uncertainty_band: float = 0.1,
    temperature: float = 0.25,
    loss_epsilon: float = DEFAULT_LOSS_EPSILON,
    credit_ridge: float = 1e-12,
    deadline_ns: Optional[int] = None,
    max_events: int = DEFAULT_MAX_COUNTERFACTUAL_EVENTS,
) -> BTCCAudit:
    """Compute bounded leave-one-event-out credit, optionally behind a gate.

    Modes:
      always: audit every episode;
      gated: audit only an error or an episode within uncertainty_band.
    """
    if mode not in ("always", "gated"):
        raise OracleInputError("mode must be 'always' or 'gated'")
    band = _finite("uncertainty_band", uncertainty_band)
    if band < 0.0:
        raise OracleInputError("uncertainty_band must be non-negative")
    ridge = _finite("credit_ridge", credit_ridge)
    if ridge <= 0.0:
        raise OracleInputError("credit_ridge must be positive")

    ordered, weight_map, target, temperature_value, epsilon = _validate_inputs(
        events, weights, initial_state, neuron_params, synapse_params,
        target_fast, temperature, loss_epsilon, deadline_ns, max_events,
    )
    baseline = replay_episode(
        ordered, weight_map, initial_state, neuron_params, synapse_params,
        int(target), temperature=temperature_value, loss_epsilon=epsilon,
        deadline_ns=deadline_ns, max_events=max_events,
    )

    if mode == "always":
        trigger_reason = "forced_always"
        should_audit = True
    elif not baseline.correct:
        trigger_reason = "prediction_error"
        should_audit = True
    elif abs(baseline.decision_margin) <= band:
        trigger_reason = "near_decision_boundary"
        should_audit = True
    else:
        trigger_reason = "confident_correct"
        should_audit = False

    if not should_audit:
        return BTCCAudit(
            mode=mode,
            trigger_reason=trigger_reason,
            baseline=baseline,
            event_credits=(),
            synapse_directions=(),
        )

    credits: list[EventCredit] = []
    numerator: dict[str, list[float]] = defaultdict(list)
    denominator: dict[str, list[float]] = defaultdict(list)
    sensitivity: dict[str, list[float]] = defaultdict(list)

    for event in ordered:
        omitted = tuple(candidate for candidate in ordered if candidate.event_id != event.event_id)
        omitted_synapses = {candidate.synapse_id for candidate in omitted}
        alternative_weights = {
            synapse_id: weight
            for synapse_id, weight in weight_map.items()
            if synapse_id in omitted_synapses
        }
        alternative = replay_episode(
            omitted, alternative_weights, initial_state, neuron_params, synapse_params,
            int(target), temperature=temperature_value, loss_epsilon=epsilon,
            deadline_ns=deadline_ns, max_events=max_events,
        )
        delta_loss = alternative.loss - baseline.loss
        contribution = _event_contribution_at_margin(
            event, weight_map, baseline.margin_evaluation_time_ns, neuron_params
        )
        credits.append(EventCredit(
            event_id=event.event_id,
            synapse_id=event.synapse_id,
            omission_loss=alternative.loss,
            delta_loss=delta_loss,
            effective_event_contribution=contribution,
        ))
        numerator[event.synapse_id].append(delta_loss * contribution)
        denominator[event.synapse_id].append(contribution * contribution)
        if weight_map[event.synapse_id] > 0.0:
            sensitivity[event.synapse_id].append(
                contribution / weight_map[event.synapse_id]
            )

    directions: list[SynapseCreditDirection] = []
    for synapse_id in sorted(weight_map):
        denom = math.fsum(denominator[synapse_id])
        if denom <= 0.0:
            fitted_slope = 0.0
        else:
            fitted_slope = math.fsum(numerator[synapse_id]) / (denom + ridge)
        dz_dw = math.fsum(sensitivity[synapse_id])
        directions.append(SynapseCreditDirection(
            synapse_id=synapse_id,
            fitted_negative_loss_slope=fitted_slope,
            decision_sensitivity_per_weight=dz_dw,
            estimated_descent_direction=fitted_slope * dz_dw,
        ))

    return BTCCAudit(
        mode=mode,
        trigger_reason=trigger_reason,
        baseline=baseline,
        event_credits=tuple(credits),
        synapse_directions=tuple(directions),
    )


def central_difference_weight_gradient(
    events: Sequence[BTCCEvent],
    weights: Mapping[str, float],
    synapse_id: str,
    initial_state: NeuronState,
    neuron_params: NeuronParams,
    synapse_params: SynapseParams,
    target_fast: int,
    *,
    step_size: float = 1e-5,
    temperature: float = 0.25,
    loss_epsilon: float = DEFAULT_LOSS_EPSILON,
    deadline_ns: Optional[int] = None,
    max_events: int = DEFAULT_MAX_COUNTERFACTUAL_EVENTS,
) -> float:
    """Independent central finite-difference d(loss)/d(weight) reference."""
    prepared = tuple(events)
    if not isinstance(weights, Mapping) or synapse_id not in weights:
        raise OracleInputError("synapse_id must exist in weights")
    synapse_params.validate()
    step = _finite("step_size", step_size)
    if step <= 0.0:
        raise OracleInputError("step_size must be positive")
    current = _finite(f"weights[{synapse_id!r}]", weights[synapse_id])
    if current - step < synapse_params.w_min or current + step > synapse_params.w_max:
        raise OracleInputError("central difference step crosses a synapse weight boundary")

    plus = dict(weights)
    minus = dict(weights)
    plus[synapse_id] = current + step
    minus[synapse_id] = current - step
    loss_plus = replay_episode(
        prepared, plus, initial_state, neuron_params, synapse_params, target_fast,
        temperature=temperature, loss_epsilon=loss_epsilon,
        deadline_ns=deadline_ns, max_events=max_events,
    ).loss
    loss_minus = replay_episode(
        prepared, minus, initial_state, neuron_params, synapse_params, target_fast,
        temperature=temperature, loss_epsilon=loss_epsilon,
        deadline_ns=deadline_ns, max_events=max_events,
    ).loss
    return (loss_plus - loss_minus) / (2.0 * step)


def compare_credit_to_finite_difference(
    audit: BTCCAudit,
    events: Sequence[BTCCEvent],
    weights: Mapping[str, float],
    synapse_id: str,
    initial_state: NeuronState,
    neuron_params: NeuronParams,
    synapse_params: SynapseParams,
    target_fast: int,
    *,
    step_size: float = 1e-5,
    temperature: float = 0.25,
    loss_epsilon: float = DEFAULT_LOSS_EPSILON,
    deadline_ns: Optional[int] = None,
    max_events: int = DEFAULT_MAX_COUNTERFACTUAL_EVENTS,
) -> GradientComparison:
    if not audit.audited:
        raise OracleInputError("cannot compare gradients without an audited episode")
    direction = next(
        (item for item in audit.synapse_directions if item.synapse_id == synapse_id),
        None,
    )
    if direction is None:
        raise OracleInputError("synapse_id has no BTCC direction")
    gradient = central_difference_weight_gradient(
        events, weights, synapse_id, initial_state, neuron_params,
        synapse_params, target_fast, step_size=step_size,
        temperature=temperature, loss_epsilon=loss_epsilon,
        deadline_ns=deadline_ns, max_events=max_events,
    )
    negative_gradient = -gradient
    estimated = direction.estimated_descent_direction
    sign_aligned = (
        (estimated > 0.0 and negative_gradient > 0.0)
        or (estimated < 0.0 and negative_gradient < 0.0)
        or (estimated == 0.0 and negative_gradient == 0.0)
    )
    return GradientComparison(
        synapse_id=synapse_id,
        central_difference_gradient=gradient,
        negative_gradient=negative_gradient,
        estimated_descent_direction=estimated,
        sign_aligned=sign_aligned,
    )
