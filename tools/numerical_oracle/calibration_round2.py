"""Deterministic behavioral characterization scenarios for the JARVIS starter neuron.

This is a reference experiment, not a parameter optimizer or runtime benchmark.
Run from the repository root:
    python3 -m tools.numerical_oracle.calibration_round2
"""

from __future__ import annotations

from dataclasses import replace
import json

from .jarvis_oracle import (
    InputSignal,
    NeuronState,
    decay_neuron_state,
    effective_threshold,
    process_neuron_batch,
    starter_neuron_params,
)

MS = 1_000_000
PROFILE_ID = "JARVIS_SINGLE_NEURON_STARTER_V1"
ROUND2_SCHEMA = "JARVIS_CALIBRATION_ROUND2_V0_1"


def _rounded(value: float) -> float:
    return round(float(value), 12)


def _simulate(
    events: dict[int, list[InputSignal]],
    *,
    initial_state: NeuronState | None = None,
    final_time_ms: int | None = None,
) -> dict[str, object]:
    """Run grouped events in model time and return a compact trajectory."""
    params = starter_neuron_params()
    state = initial_state if initial_state is not None else NeuronState()
    trace: list[dict[str, object]] = []
    spike_times_ms: list[int] = []

    for time_ms in sorted(events):
        step = process_neuron_batch(
            state,
            time_ms * MS,
            events[time_ms],
            params,
        )
        state = step.state
        if step.spike_emitted:
            spike_times_ms.append(time_ms)
        trace.append(
            {
                "time_ms": time_ms,
                "u": _rounded(state.u),
                "a": _rounded(state.a),
                "input_delta": _rounded(step.total_input_delta),
                "threshold_at_decision": _rounded(step.effective_threshold),
                "threshold_after_step": _rounded(effective_threshold(state, params)),
                "spike": step.spike_emitted,
                "refractory_active": step.refractory_active,
            }
        )

    last_event_ms = max(events) if events else 0
    if final_time_ms is None:
        final_time_ms = last_event_ms
    if final_time_ms < last_event_ms:
        raise ValueError("final_time_ms cannot precede the final input event")
    if final_time_ms * MS > state.last_update_model_time_ns:
        state = decay_neuron_state(state, final_time_ms * MS, params)

    max_adaptation = max([0.0] + [float(item["a"]) for item in trace])
    max_threshold = max(
        [params.theta_0, params.theta_0 + params.k_a * max_adaptation]
    )
    return {
        "input_event_count": sum(len(batch) for batch in events.values()),
        "spike_count": len(spike_times_ms),
        "spike_times_ms": spike_times_ms,
        "final_time_ms": final_time_ms,
        "final_u": _rounded(state.u),
        "final_a": _rounded(state.a),
        "final_effective_threshold": _rounded(effective_threshold(state, params)),
        "max_adaptation": _rounded(max_adaptation),
        "max_effective_threshold": _rounded(max_threshold),
        "trace": trace,
    }


def _row(
    scenario_id: str,
    group: str,
    events: dict[int, list[InputSignal]],
    *,
    parameters: dict[str, object],
    initial_state: NeuronState | None = None,
    final_time_ms: int | None = None,
) -> dict[str, object]:
    return {
        "scenario_id": scenario_id,
        "group": group,
        "parameters": parameters,
        "outcome": _simulate(
            events,
            initial_state=initial_state,
            final_time_ms=final_time_ms,
        ),
    }


def _single_input(strength: float, attention: float = 0.0) -> InputSignal:
    return InputSignal("calibration-input", 1.0, strength, attention)


def run_calibration_round2() -> dict[str, object]:
    """Return the fixed Round 2 scenario set without writing files."""
    rows: list[dict[str, object]] = []

    # A. Amplitude response: stay below threshold to observe monotonic integration.
    for strength in (0.1, 0.3, 0.5, 0.7, 0.9):
        rows.append(
            _row(
                f"amplitude_{str(strength).replace('.', '_')}",
                "input_amplitude",
                {0: [_single_input(strength)]},
                parameters={"strength": strength, "attention": 0.0},
            )
        )

    # B. Temporal summation: the same two pulses, with only their separation changed.
    for gap_ms in (5, 20, 80):
        rows.append(
            _row(
                f"spacing_{gap_ms}ms",
                "temporal_spacing",
                {
                    0: [_single_input(0.6)],
                    gap_ms: [_single_input(0.6)],
                },
                parameters={"pulse_strength": 0.6, "gap_ms": gap_ms},
            )
        )

    # C. Same pulse amplitude, different temporal density.
    patterns = (
        ("burst", tuple(range(0, 40, 5))),
        ("sparse", tuple(range(0, 400, 50))),
        ("sustained", tuple(range(0, 360, 10))),
    )
    for name, times in patterns:
        rows.append(
            _row(
                f"pattern_{name}",
                "temporal_patterns",
                {time_ms: [_single_input(0.45)] for time_ms in times},
                parameters={
                    "pattern": name,
                    "pulse_strength": 0.45,
                    "pulse_count": len(times),
                    "interval_ms": 5 if name == "burst" else (50 if name == "sparse" else 10),
                },
            )
        )

    # D. Recovery after repeated high activity; only the quiet interval changes.
    activity_times = tuple(range(0, 200, 10))
    activity_events = {
        time_ms: [_single_input(1.0)] for time_ms in activity_times
    }
    for wait_ms in (0, 50, 100, 200):
        rows.append(
            _row(
                f"recovery_wait_{wait_ms}ms",
                "adaptation_recovery",
                activity_events,
                parameters={"activity_pulse_strength": 1.0, "quiet_wait_ms": wait_ms},
                final_time_ms=activity_times[-1] + wait_ms,
            )
        )

    # E. Attention changes the bounded gain for the current input only.
    for attention in (0.0, 0.5, 1.0):
        rows.append(
            _row(
                f"attention_{str(attention).replace('.', '_')}",
                "attention_gain",
                {0: [_single_input(0.5, attention)]},
                parameters={"strength": 0.5, "attention": attention},
            )
        )

    # F. Simultaneous mixed inputs must be invariant to caller list order.
    simultaneous = [
        InputSignal("z-excitatory", 1.0, 0.35, 0.25),
        InputSignal("a-excitatory", 0.8, 0.4, 0.5),
        InputSignal("m-inhibitory", -0.4, 0.3, 0.2),
    ]
    rows.append(
        _row(
            "simultaneous_forward",
            "simultaneous_order",
            {0: simultaneous},
            parameters={"source_order": "forward", "input_count": len(simultaneous)},
        )
    )
    rows.append(
        _row(
            "simultaneous_reverse",
            "simultaneous_order",
            {0: list(reversed(simultaneous))},
            parameters={"source_order": "reverse", "input_count": len(simultaneous)},
        )
    )

    # G. Identical input under different valid initial states.
    initial_conditions = (
        ("rest", NeuronState()),
        ("residual_potential", NeuronState(u=0.25)),
        ("adapted_and_residual", NeuronState(u=0.25, a=0.5)),
    )
    for name, initial in initial_conditions:
        rows.append(
            _row(
                f"initial_{name}",
                "initial_conditions",
                {0: [_single_input(0.35)]},
                parameters={"initial_u": initial.u, "initial_a": initial.a},
                initial_state=initial,
            )
        )

    groups = (
        "input_amplitude",
        "temporal_spacing",
        "temporal_patterns",
        "adaptation_recovery",
        "attention_gain",
        "simultaneous_order",
        "initial_conditions",
    )
    counts = {"total": len(rows)}
    for group in groups:
        counts[group] = sum(row["group"] == group for row in rows)

    return {
        "schema": ROUND2_SCHEMA,
        "profile_id": PROFILE_ID,
        "purpose": "controlled_behavioral_characterization_not_parameter_optimization",
        "parameter_policy": "STARTER is fixed; no candidate is ranked or selected",
        "counts": counts,
        "scenarios": rows,
    }


def main() -> None:
    print(json.dumps(run_calibration_round2(), sort_keys=True, indent=2))


if __name__ == "__main__":
    main()
