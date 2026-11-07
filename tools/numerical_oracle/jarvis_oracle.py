"""JARVIS Numerical Oracle: pure-Python float64 reference equations.

This module is a research/reference implementation, not the JARVIS runtime.
It intentionally uses only the Python standard library and does not perform I/O.
All time values are non-negative integer model_time_ns values.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
import math
import struct
from typing import Optional, Sequence, Tuple


class OracleInputError(ValueError):
    """Raised when an input violates the documented numerical contract."""


def _finite(name: str, value: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise OracleInputError(f"{name} must be a real number")
    value = float(value)
    if not math.isfinite(value):
        raise OracleInputError(f"{name} must be finite")
    return value


def _time_ns(name: str, value: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise OracleInputError(f"{name} must be a non-negative integer")
    if value > (1 << 64) - 1:
        raise OracleInputError(f"{name} exceeds the unsigned 64-bit model-time limit")
    return value


def _count(name: str, value: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise OracleInputError(f"{name} must be a non-negative integer")
    if value > (1 << 32) - 1:
        raise OracleInputError(f"{name} exceeds the unsigned 32-bit event-count limit")
    return value


def _clip(value: float, low: float, high: float) -> float:
    return min(high, max(low, value))


def _decay(value: float, delta_ns: int, tau_ns: int) -> float:
    # Python float is binary64; exp() is used as the reference operation.
    return value * math.exp(-(delta_ns / tau_ns))


def float32_roundtrip(value: float) -> float:
    """Round a finite number to IEEE-754 binary32 and return it as Python float."""
    value = _finite("value", value)
    try:
        return struct.unpack("<f", struct.pack("<f", value))[0]
    except (OverflowError, struct.error) as exc:
        raise OracleInputError("value cannot be represented as finite float32") from exc


@dataclass(frozen=True)
class NumericComparison:
    count: int
    max_absolute_error: float
    max_relative_error: float
    mismatched_indices: Tuple[int, ...]
    within_tolerance: bool


def compare_numeric_vectors(
    reference: Sequence[float], candidate: Sequence[float],
    absolute_tolerance: float = 1e-5, relative_tolerance: float = 1e-5,
) -> NumericComparison:
    """Compare candidate runtime values against float64 oracle values.

    The combined acceptance rule is abs(error) <= abs_tol + rel_tol*abs(reference).
    Spike/no-spike and sequence IDs must be compared separately by the caller.
    """
    if len(reference) != len(candidate):
        raise OracleInputError("reference and candidate vectors must have equal lengths")
    absolute_tolerance = _finite("absolute_tolerance", absolute_tolerance)
    relative_tolerance = _finite("relative_tolerance", relative_tolerance)
    if absolute_tolerance < 0.0 or relative_tolerance < 0.0:
        raise OracleInputError("numeric tolerances must be non-negative")
    max_abs = 0.0
    max_rel = 0.0
    mismatches = []
    for index, (expected, actual) in enumerate(zip(reference, candidate)):
        expected = _finite(f"reference[{index}]", expected)
        actual = _finite(f"candidate[{index}]", actual)
        error = abs(actual - expected)
        relative = error / abs(expected) if expected != 0.0 else (0.0 if error == 0.0 else math.inf)
        max_abs = max(max_abs, error)
        max_rel = max(max_rel, relative)
        if error > absolute_tolerance + relative_tolerance * abs(expected):
            mismatches.append(index)
    return NumericComparison(
        count=len(reference),
        max_absolute_error=max_abs,
        max_relative_error=max_rel,
        mismatched_indices=tuple(mismatches),
        within_tolerance=not mismatches,
    )


@dataclass(frozen=True)
class NeuronParams:
    u_rest: float = 0.0
    u_reset: float = 0.0
    u_min: float = -2.0
    u_max: float = 2.0
    theta_0: float = 1.0
    theta_min: float = 1.0
    theta_max: float = 1.5
    tau_m_ns: int = 20_000_000
    a_max: float = 1.0
    delta_a_spike: float = 0.2
    tau_a_ns: int = 100_000_000
    k_a: float = 0.5
    refractory_ns: int = 2_000_000
    g_0: float = 1.0
    k_g: float = 0.5
    g_min: float = 1.0
    g_max: float = 1.5

    def validate(self) -> None:
        for name in (
            "u_rest", "u_reset", "u_min", "u_max", "theta_0", "theta_min",
            "theta_max", "a_max", "delta_a_spike", "k_a", "g_0", "k_g",
            "g_min", "g_max",
        ):
            _finite(name, getattr(self, name))
        _time_ns("tau_m_ns", self.tau_m_ns)
        _time_ns("tau_a_ns", self.tau_a_ns)
        _time_ns("refractory_ns", self.refractory_ns)
        if self.tau_m_ns <= 0 or self.tau_a_ns <= 0:
            raise OracleInputError("tau_m_ns and tau_a_ns must be positive")
        if not self.u_min <= self.u_rest <= self.u_max:
            raise OracleInputError("u_rest must be inside [u_min, u_max]")
        if not self.u_min <= self.u_reset <= self.u_max:
            raise OracleInputError("u_reset must be inside [u_min, u_max]")
        if self.theta_min <= self.u_rest or self.theta_min > self.theta_max:
            raise OracleInputError("threshold bounds must be ordered and above u_rest")
        if self.theta_0 < self.theta_min or self.theta_0 > self.theta_max:
            raise OracleInputError("theta_0 must be inside threshold bounds")
        if self.a_max < 0 or self.delta_a_spike < 0 or self.k_a < 0:
            raise OracleInputError("adaptation values and k_a must be non-negative")
        if self.g_0 < 0 or self.k_g < 0 or self.g_min < 0 or self.g_min > self.g_max:
            raise OracleInputError("gain parameters must be non-negative and ordered")


@dataclass(frozen=True)
class NeuronState:
    u: float = 0.0
    a: float = 0.0
    last_update_model_time_ns: int = 0
    refractory_until_model_time_ns: int = 0
    spike_sequence: int = 0
    last_spike_model_time_ns: Optional[int] = None

    def validate(self, params: NeuronParams) -> None:
        params.validate()
        _finite("state.u", self.u)
        _finite("state.a", self.a)
        _time_ns("last_update_model_time_ns", self.last_update_model_time_ns)
        _time_ns("refractory_until_model_time_ns", self.refractory_until_model_time_ns)
        _time_ns("spike_sequence", self.spike_sequence)
        if self.last_spike_model_time_ns is not None:
            _time_ns("last_spike_model_time_ns", self.last_spike_model_time_ns)
        if not params.u_min <= self.u <= params.u_max:
            raise OracleInputError("state.u is outside configured bounds")
        if not 0.0 <= self.a <= params.a_max:
            raise OracleInputError("state.a is outside configured bounds")
        if self.last_spike_model_time_ns is not None and self.last_spike_model_time_ns > self.last_update_model_time_ns:
            raise OracleInputError("last spike time cannot be later than the state update time")
        if self.last_spike_model_time_ns is not None and self.refractory_until_model_time_ns < self.last_spike_model_time_ns:
            raise OracleInputError("refractory end cannot precede the last spike time")


@dataclass(frozen=True)
class InputSignal:
    source_id: str
    signed_weight: float
    strength: float
    attention: float = 0.0

    def validate(self) -> None:
        if not isinstance(self.source_id, str) or not self.source_id:
            raise OracleInputError("source_id must be a non-empty string")
        _finite("signed_weight", self.signed_weight)
        _finite("strength", self.strength)
        _finite("attention", self.attention)
        if abs(self.signed_weight) > 1.0:
            raise OracleInputError("starter signed synaptic weight magnitude must be in [0, 1]")
        if not 0.0 <= self.strength <= 1.0:
            raise OracleInputError("input strength must be in [0, 1]")
        if not 0.0 <= self.attention <= 1.0:
            raise OracleInputError("attention must be in [0, 1]")


@dataclass(frozen=True)
class NeuronStep:
    state: NeuronState
    spike_emitted: bool
    effective_threshold: float
    total_input_delta: float
    refractory_active: bool


def decay_neuron_state(
    state: NeuronState, time_ns: int, params: NeuronParams
) -> NeuronState:
    """Apply exact analytical LIF/adaptation decay up to time_ns, without firing."""
    state.validate(params)
    time_ns = _time_ns("time_ns", time_ns)
    if time_ns < state.last_update_model_time_ns:
        raise OracleInputError("late event: time_ns is earlier than last_update_model_time_ns")
    delta_ns = time_ns - state.last_update_model_time_ns
    u = params.u_rest + (state.u - params.u_rest) * math.exp(-(delta_ns / params.tau_m_ns))
    a = state.a * math.exp(-(delta_ns / params.tau_a_ns))
    return replace(
        state,
        u=_clip(u, params.u_min, params.u_max),
        a=_clip(a, 0.0, params.a_max),
        last_update_model_time_ns=time_ns,
    )


def effective_threshold(state: NeuronState, params: NeuronParams) -> float:
    state.validate(params)
    return _clip(params.theta_0 + params.k_a * state.a, params.theta_min, params.theta_max)


def attention_gain(attention: float, params: NeuronParams) -> float:
    attention = _finite("attention", attention)
    if not 0.0 <= attention <= 1.0:
        raise OracleInputError("attention must be in [0, 1]")
    return _clip(params.g_0 * (1.0 + params.k_g * attention), params.g_min, params.g_max)


def process_neuron_batch(
    state: NeuronState,
    time_ns: int,
    signals: Sequence[InputSignal],
    params: NeuronParams,
) -> NeuronStep:
    """Process one already-grouped batch of signals sharing exactly one timestamp.

    Callers must combine all simultaneous inputs before calling this function.
    The batch is summed deterministically by stable source_id and math.fsum.
    """
    if not isinstance(signals, (list, tuple)):
        raise OracleInputError("signals must be a list or tuple")
    prepared = list(signals)
    for signal in prepared:
        if not isinstance(signal, InputSignal):
            raise OracleInputError("every signal must be an InputSignal")
        signal.validate()
    source_ids = [signal.source_id for signal in prepared]
    if len(source_ids) != len(set(source_ids)):
        raise OracleInputError("source_id values must be unique within one timestamp batch")

    decayed = decay_neuron_state(state, time_ns, params)
    ordered = sorted(prepared, key=lambda item: item.source_id)
    deltas = [
        attention_gain(item.attention, params) * item.signed_weight * item.strength
        for item in ordered
    ]
    total_delta = math.fsum(deltas)
    u = _clip(decayed.u + total_delta, params.u_min, params.u_max)
    candidate = replace(decayed, u=u)
    threshold = effective_threshold(candidate, params)
    refractory = time_ns < state.refractory_until_model_time_ns
    spike = (not refractory) and (u >= threshold)

    if spike:
        new_a = _clip(candidate.a + params.delta_a_spike, 0.0, params.a_max)
        refractory_until = _time_ns(
            "refractory_until_model_time_ns", time_ns + params.refractory_ns
        )
        new_state = replace(
            candidate,
            u=params.u_reset,
            a=new_a,
            refractory_until_model_time_ns=refractory_until,
            spike_sequence=state.spike_sequence + 1,
            last_spike_model_time_ns=time_ns,
        )
    else:
        new_state = candidate
    new_state.validate(params)
    return NeuronStep(
        state=new_state,
        spike_emitted=spike,
        effective_threshold=threshold,
        total_input_delta=total_delta,
        refractory_active=refractory,
    )


@dataclass(frozen=True)
class SynapseParams:
    w_min: float = 0.0
    w_max: float = 1.0
    tau_pre_ns: int = 20_000_000
    tau_post_ns: int = 40_000_000
    eta_plus: float = 0.005
    eta_minus: float = 0.005
    x_pre_max: float = 8.0
    x_post_max: float = 8.0
    q_batch_max: float = 0.05
    delta_w_batch_max: float = 0.01

    def validate(self) -> None:
        for name in ("w_min", "w_max", "eta_plus", "eta_minus", "x_pre_max", "x_post_max", "q_batch_max", "delta_w_batch_max"):
            _finite(name, getattr(self, name))
        _time_ns("tau_pre_ns", self.tau_pre_ns)
        _time_ns("tau_post_ns", self.tau_post_ns)
        if self.w_max <= self.w_min:
            raise OracleInputError("w_max must be greater than w_min")
        if self.tau_pre_ns <= 0 or self.tau_post_ns <= 0:
            raise OracleInputError("STDP time constants must be positive")
        if min(self.eta_plus, self.eta_minus, self.x_pre_max, self.x_post_max, self.q_batch_max, self.delta_w_batch_max) < 0:
            raise OracleInputError("STDP rates, trace limits and update limits must be non-negative")


@dataclass(frozen=True)
class SynapseState:
    weight: float = 0.5
    pre_trace: float = 0.0
    post_trace: float = 0.0
    trace_last_update_model_time_ns: int = 0
    eligibility_trace: float = 0.0
    eligibility_context_id: Optional[str] = None
    eligibility_started_model_time_ns: Optional[int] = None
    eligibility_last_update_model_time_ns: int = 0

    def validate(self, params: SynapseParams, reward_params: Optional["RewardParams"] = None) -> None:
        params.validate()
        for name in ("weight", "pre_trace", "post_trace", "eligibility_trace"):
            _finite(f"state.{name}", getattr(self, name))
        _time_ns("trace_last_update_model_time_ns", self.trace_last_update_model_time_ns)
        _time_ns("eligibility_last_update_model_time_ns", self.eligibility_last_update_model_time_ns)
        if self.eligibility_started_model_time_ns is not None:
            _time_ns("eligibility_started_model_time_ns", self.eligibility_started_model_time_ns)
        if not params.w_min <= self.weight <= params.w_max:
            raise OracleInputError("synapse weight is outside configured bounds")
        if not 0.0 <= self.pre_trace <= params.x_pre_max or not 0.0 <= self.post_trace <= params.x_post_max:
            raise OracleInputError("pre/post trace is outside configured bounds")
        if reward_params is not None:
            reward_params.validate()
            if not -reward_params.e_max <= self.eligibility_trace <= reward_params.e_max:
                raise OracleInputError("eligibility trace is outside configured bounds")
        if self.eligibility_context_id is None:
            if self.eligibility_started_model_time_ns is not None:
                raise OracleInputError("eligibility start time requires an active context")
            if self.eligibility_trace != 0.0:
                raise OracleInputError("non-zero eligibility requires an active context")
        else:
            if not isinstance(self.eligibility_context_id, str) or not self.eligibility_context_id:
                raise OracleInputError("eligibility_context_id must be a non-empty string")
            if self.eligibility_started_model_time_ns is None:
                raise OracleInputError("active eligibility context requires its start time")
            if self.eligibility_started_model_time_ns > self.eligibility_last_update_model_time_ns:
                raise OracleInputError("eligibility start time cannot follow its last update time")


@dataclass(frozen=True)
class PlasticityStep:
    state: SynapseState
    q_raw: float
    q_bounded: float
    weight_delta: float


def _decayed_synapse_traces(
    state: SynapseState, time_ns: int, params: SynapseParams
) -> Tuple[float, float]:
    time_ns = _time_ns("time_ns", time_ns)
    if time_ns < state.trace_last_update_model_time_ns:
        raise OracleInputError("late STDP event")
    dt = time_ns - state.trace_last_update_model_time_ns
    return (
        _decay(state.pre_trace, dt, params.tau_pre_ns),
        _decay(state.post_trace, dt, params.tau_post_ns),
    )


def _plasticity_q(
    weight: float, n_pre: int, n_post: int,
    pre_before: float, post_before: float, params: SynapseParams,
) -> Tuple[float, float]:
    span = params.w_max - params.w_min
    z = (weight - params.w_min) / span
    q = (
        n_post * params.eta_plus * pre_before * (1.0 - z)
        - n_pre * params.eta_minus * post_before * z
    )
    return q, _clip(q, -params.q_batch_max, params.q_batch_max)


def _updated_traces(
    pre_before: float, post_before: float, n_pre: int, n_post: int,
    time_ns: int, params: SynapseParams,
) -> Tuple[float, float]:
    return (
        _clip(pre_before + n_pre, 0.0, params.x_pre_max),
        _clip(post_before + n_post, 0.0, params.x_post_max),
    )


def update_stdp(
    state: SynapseState, params: SynapseParams, time_ns: int,
    n_pre: int, n_post: int,
) -> PlasticityStep:
    """Apply EXCITATORY_PAIR_EXP_STDP_V1 to one simultaneous event batch."""
    state.validate(params)
    n_pre = _count("n_pre", n_pre)
    n_post = _count("n_post", n_post)
    pre_before, post_before = _decayed_synapse_traces(state, time_ns, params)
    q_raw, q_bounded = _plasticity_q(state.weight, n_pre, n_post, pre_before, post_before, params)
    span = params.w_max - params.w_min
    proposed_delta = span * q_bounded
    weight_delta = _clip(proposed_delta, -params.delta_w_batch_max, params.delta_w_batch_max)
    weight = _clip(state.weight + weight_delta, params.w_min, params.w_max)
    pre_after, post_after = _updated_traces(pre_before, post_before, n_pre, n_post, time_ns, params)
    new_state = replace(
        state,
        weight=weight,
        pre_trace=pre_after,
        post_trace=post_after,
        trace_last_update_model_time_ns=time_ns,
    )
    new_state.validate(params)
    return PlasticityStep(new_state, q_raw, q_bounded, weight - state.weight)


@dataclass(frozen=True)
class RewardParams:
    tau_e_ns: int = 1_000_000_000
    e_max: float = 1.0
    max_eligibility_age_ns: int = 5_000_000_000
    eta_r: float = 0.01
    delta_w_reward_max: float = 0.01
    beta_b: float = 0.01

    def validate(self) -> None:
        for name in ("e_max", "eta_r", "delta_w_reward_max", "beta_b"):
            _finite(name, getattr(self, name))
        _time_ns("tau_e_ns", self.tau_e_ns)
        _time_ns("max_eligibility_age_ns", self.max_eligibility_age_ns)
        if self.tau_e_ns <= 0:
            raise OracleInputError("tau_e_ns must be positive")
        if min(self.e_max, self.eta_r, self.delta_w_reward_max, self.beta_b) < 0:
            raise OracleInputError("reward parameters must be non-negative")
        if self.beta_b > 1.0:
            raise OracleInputError("beta_b must be in [0, 1]")


def update_rstdp(
    state: SynapseState, params: SynapseParams, reward_params: RewardParams,
    time_ns: int, n_pre: int, n_post: int, learning_context_id: str,
) -> PlasticityStep:
    """Accumulate eligibility using the same bounded local pair signal; do not change weight."""
    state.validate(params, reward_params)
    reward_params.validate()
    time_ns = _time_ns("time_ns", time_ns)
    n_pre = _count("n_pre", n_pre)
    n_post = _count("n_post", n_post)
    if not isinstance(learning_context_id, str) or not learning_context_id:
        raise OracleInputError("learning_context_id must be a non-empty string")
    if time_ns < state.eligibility_last_update_model_time_ns:
        raise OracleInputError("late eligibility event")

    pre_before, post_before = _decayed_synapse_traces(state, time_ns, params)
    eligibility_dt = time_ns - state.eligibility_last_update_model_time_ns
    eligibility = _decay(state.eligibility_trace, eligibility_dt, reward_params.tau_e_ns)
    context_id = state.eligibility_context_id
    context_started = state.eligibility_started_model_time_ns
    expired_context_id = None

    if context_id is not None and context_started is not None:
        if time_ns - context_started > reward_params.max_eligibility_age_ns:
            expired_context_id = context_id
            eligibility = 0.0
            context_id = None
            context_started = None
            # Do not let pre/post traces from an expired episode leak into a new one.
            pre_before = 0.0
            post_before = 0.0
    has_events = (n_pre + n_post) > 0
    if expired_context_id == learning_context_id and has_events:
        raise OracleInputError("an expired learning_context_id cannot be reopened")
    if context_id is not None and context_id != learning_context_id:
        raise OracleInputError("a different learning_context_id cannot replace active eligibility")
    if has_events and context_id is None:
        context_id = learning_context_id
        context_started = time_ns
        # A new episode begins with clean pair traces.
        pre_before = 0.0
        post_before = 0.0
    q_raw, q_bounded = _plasticity_q(state.weight, n_pre, n_post, pre_before, post_before, params)
    if context_id is not None:
        eligibility = _clip(eligibility + q_bounded, -reward_params.e_max, reward_params.e_max)
    else:
        eligibility = 0.0

    pre_after, post_after = _updated_traces(pre_before, post_before, n_pre, n_post, time_ns, params)
    new_state = replace(
        state,
        pre_trace=pre_after,
        post_trace=post_after,
        trace_last_update_model_time_ns=time_ns,
        eligibility_trace=eligibility,
        eligibility_context_id=context_id,
        eligibility_started_model_time_ns=context_started,
        eligibility_last_update_model_time_ns=time_ns,
    )
    new_state.validate(params, reward_params)
    return PlasticityStep(new_state, q_raw, q_bounded, 0.0)


@dataclass(frozen=True)
class RewardStep:
    state: SynapseState
    modulator: float
    weight_delta: float
    baseline_after: float
    status: str


def apply_reward(
    state: SynapseState, params: SynapseParams, reward_params: RewardParams,
    time_ns: int, reward_value: float, baseline_before: float,
    learning_context_id: Optional[str],
) -> RewardStep:
    """Apply one already-validated reward to one matching synapse context.

    Durable feedback IDs, sequence ordering and duplicate prevention belong to
    CQE/Reward Baseline Manager, outside this pure numerical oracle.
    """
    state.validate(params, reward_params)
    reward_params.validate()
    time_ns = _time_ns("time_ns", time_ns)
    reward_value = _finite("reward_value", reward_value)
    baseline_before = _finite("baseline_before", baseline_before)
    if not 0.0 <= reward_value <= 1.0 or not 0.0 <= baseline_before <= 1.0:
        raise OracleInputError("reward and baseline must be in [0, 1]")
    if time_ns < state.trace_last_update_model_time_ns or time_ns < state.eligibility_last_update_model_time_ns:
        raise OracleInputError("late reward event")
    if not 0.0 <= reward_params.beta_b <= 1.0:
        raise OracleInputError("beta_b must be in [0, 1]")

    pre_before, post_before = _decayed_synapse_traces(state, time_ns, params)
    eligibility = _decay(
        state.eligibility_trace,
        time_ns - state.eligibility_last_update_model_time_ns,
        reward_params.tau_e_ns,
    )
    context_id = state.eligibility_context_id
    context_started = state.eligibility_started_model_time_ns
    expired = False
    if context_id is not None and context_started is not None:
        if time_ns - context_started > reward_params.max_eligibility_age_ns:
            if learning_context_id != context_id:
                raise OracleInputError("expired reward context does not match the stored context")
            expired = True
            eligibility = 0.0
            context_id = None
            context_started = None
            pre_before = 0.0
            post_before = 0.0
    if context_id is not None and learning_context_id != context_id:
        raise OracleInputError("reward learning_context_id does not match active eligibility")
    if context_id is None and learning_context_id is not None and not expired:
        raise OracleInputError("reward references a context with no active eligibility")

    modulator = _clip(reward_value - baseline_before, -1.0, 1.0)
    baseline_after = _clip(
        baseline_before + reward_params.beta_b * (reward_value - baseline_before),
        0.0, 1.0,
    )
    weight_delta = 0.0
    status = "EXPIRED" if expired else "NO_ELIGIBILITY"
    if context_id is not None:
        span = params.w_max - params.w_min
        raw_delta = reward_params.eta_r * modulator * eligibility * span
        z = (state.weight - params.w_min) / span
        soft_bound = (1.0 - z) if raw_delta >= 0.0 else z
        bounded_delta = _clip(
            raw_delta * soft_bound,
            -reward_params.delta_w_reward_max,
            reward_params.delta_w_reward_max,
        )
        new_weight = _clip(state.weight + bounded_delta, params.w_min, params.w_max)
        weight_delta = new_weight - state.weight
        status = "APPLIED" if weight_delta != 0.0 else "APPLIED_NO_CHANGE"
    else:
        new_weight = state.weight

    new_state = replace(
        state,
        weight=new_weight,
        pre_trace=(0.0 if context_id is None else pre_before),
        post_trace=(0.0 if context_id is None else post_before),
        trace_last_update_model_time_ns=time_ns,
        eligibility_trace=0.0,
        eligibility_context_id=None,
        eligibility_started_model_time_ns=None,
        eligibility_last_update_model_time_ns=time_ns,
    )
    new_state.validate(params, reward_params)
    return RewardStep(new_state, modulator, weight_delta, baseline_after, status)


def starter_neuron_params() -> NeuronParams:
    """Return a fresh copy of JARVIS_SINGLE_NEURON_STARTER_V1 ALIF-DT values."""
    return NeuronParams()


def starter_synapse_params() -> SynapseParams:
    """Return a fresh copy of JARVIS_SINGLE_NEURON_STARTER_V1 STDP values."""
    return SynapseParams()


def starter_reward_params() -> RewardParams:
    """Return a fresh copy of JARVIS_SINGLE_NEURON_STARTER_V1 R-STDP values."""
    return RewardParams()
