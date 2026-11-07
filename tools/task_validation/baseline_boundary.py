"""Deterministic fixed-baseline decision-boundary characterization grid.

This maps a single ALIF-DT neuron's response over input interval and strength.
It does not train, optimize, or modify the STARTER profile.
"""
from __future__ import annotations

import json

from tools.numerical_oracle.jarvis_oracle import (
    InputSignal,
    NeuronState,
    process_neuron_batch,
    starter_neuron_params,
)

NS_PER_MS = 1_000_000
INTERVALS_MS = (2, 3, 4, 5, 6, 7, 8, 10, 12, 15, 20, 30, 40, 50, 60)
STRENGTHS = (0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2)
FIXED_WEIGHT = 0.5
PULSES = 3


def _evaluate(interval_ms: int, strength: float) -> dict[str, int | float | bool]:
    params = starter_neuron_params()
    state = NeuronState(last_update_model_time_ns=0)
    spike_count = 0

    for pulse_index in range(PULSES):
        timestamp = pulse_index * interval_ms * NS_PER_MS
        step = process_neuron_batch(
            state,
            timestamp,
            [InputSignal(
                source_id="boundary_grid_input",
                signed_weight=FIXED_WEIGHT,
                strength=strength,
                attention=0.0,
            )],
            params,
        )
        state = step.state
        spike_count += int(step.spike_emitted)

    return {
        "interval_ms": interval_ms,
        "strength": strength,
        "spike_count": spike_count,
        "predicts_burst": spike_count > 0,
    }


def run_grid() -> dict[str, object]:
    """Return every deterministic interval/strength response cell."""
    cells = [
        _evaluate(interval_ms, strength)
        for strength in STRENGTHS
        for interval_ms in INTERVALS_MS
    ]
    return {
        "benchmark": "JARVIS_FIXED_BASELINE_BOUNDARY_GRID_V1",
        "status": "REFERENCE_CHARACTERIZATION_NOT_TRAINING",
        "protocol": {
            "neuron": "JARVIS_SINGLE_NEURON_STARTER_V1 / Adaptive LIF-DT",
            "synapses": 1,
            "fixed_weight": FIXED_WEIGHT,
            "pulses_per_cell": PULSES,
            "intervals_ms": list(INTERVALS_MS),
            "strengths": list(STRENGTHS),
            "decision_rule": "spike_count > 0 => burst; otherwise sparse",
            "training": "none",
            "randomness": "none",
            "starter_profile_modified": False,
        },
        "cell_count": len(cells),
        "cells": cells,
    }


def main() -> None:
    print(json.dumps(run_grid(), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
