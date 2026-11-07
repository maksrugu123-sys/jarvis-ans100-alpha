"""Deterministic exploratory parameter sweeps for the JARVIS starter profile.

This tool emits JSON to stdout. It does not write result files, choose a
production profile, or claim that any parameter set is calibrated.
Run from the repository root with:
    python3 -m tools.numerical_oracle.calibration_sweeps
"""

from __future__ import annotations

from dataclasses import replace
from itertools import product
import json

from .jarvis_oracle import (
    InputSignal,
    NeuronState,
    SynapseState,
    apply_reward,
    effective_threshold,
    process_neuron_batch,
    starter_neuron_params,
    starter_reward_params,
    starter_synapse_params,
    update_rstdp,
    update_stdp,
)

MS = 1_000_000
PROFILE_ID = "JARVIS_SINGLE_NEURON_STARTER_V1"
SWEEP_SCHEMA = "JARVIS_NUMERICAL_SWEEP_V0_1"

TAU_M_MS = (10, 20, 40)
TAU_A_MS = (50, 100, 200)
THETA_0 = (1.0, 1.1, 1.2)
REFRACTORY_MS = (1, 2, 4)
STDP_GAPS_MS = (1, 5, 10, 20, 40, 80)
REWARD_DELAYS_AFTER_PAIR_MS = (0, 250, 1000, 4000, 4980, 4981)

# A fixed probe trajectory: it deliberately includes short gaps, an input
# during the possible refractory interval, and longer recovery intervals.
LIF_PROBE = (
    (0, 0.62),
    (5, 0.48),
    (10, 0.55),
    (11, 0.80),
    (13, 0.50),
    (25, 0.70),
    (40, 0.62),
    (60, 0.80),
)


def _rounded(value: float) -> float:
    return round(value, 12)


def _lif_sweep() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    base = starter_neuron_params()
    for tau_m, tau_a, theta_0, refractory in product(
        TAU_M_MS, TAU_A_MS, THETA_0, REFRACTORY_MS
    ):
        params = replace(
            base,
            tau_m_ns=tau_m * MS,
            tau_a_ns=tau_a * MS,
            theta_0=theta_0,
            refractory_ns=refractory * MS,
        )
        state = NeuronState()
        spike_times_ms: list[int] = []
        for time_ms, strength in LIF_PROBE:
            step = process_neuron_batch(
                state,
                time_ms * MS,
                [InputSignal("calibration-probe", 1.0, strength, 0.25)],
                params,
            )
            state = step.state
            if step.spike_emitted:
                spike_times_ms.append(time_ms)
        rows.append(
            {
                "tau_m_ms": tau_m,
                "tau_a_ms": tau_a,
                "theta_0": theta_0,
                "refractory_ms": refractory,
                "spike_count": len(spike_times_ms),
                "spike_times_ms": spike_times_ms,
                "final_u": _rounded(state.u),
                "final_a": _rounded(state.a),
                "final_effective_threshold": _rounded(
                    effective_threshold(state, params)
                ),
            }
        )
    return rows


def _stdp_sweep() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    params = starter_synapse_params()
    for gap_ms in STDP_GAPS_MS:
        for order in ("pre_then_post", "post_then_pre"):
            state = SynapseState()
            if order == "pre_then_post":
                first = update_stdp(state, params, 0, n_pre=1, n_post=0)
                second = update_stdp(
                    first.state, params, gap_ms * MS, n_pre=0, n_post=1
                )
            else:
                first = update_stdp(state, params, 0, n_pre=0, n_post=1)
                second = update_stdp(
                    first.state, params, gap_ms * MS, n_pre=1, n_post=0
                )
            rows.append(
                {
                    "pair_order": order,
                    "gap_ms": gap_ms,
                    "initial_weight": 0.5,
                    "final_weight": _rounded(second.state.weight),
                    "weight_delta": _rounded(second.weight_delta),
                    "q_raw": _rounded(second.q_raw),
                }
            )
    return rows


def _rstdp_sweep() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()
    context_id = "calibration-reward-delay"
    pair_time_ns = 20 * MS

    seed = update_rstdp(
        SynapseState(), synapse_params, reward_params,
        0, n_pre=1, n_post=0, learning_context_id=context_id,
    )
    eligible = update_rstdp(
        seed.state, synapse_params, reward_params,
        pair_time_ns, n_pre=0, n_post=1, learning_context_id=context_id,
    )

    for delay_ms in REWARD_DELAYS_AFTER_PAIR_MS:
        reward_time_ns = pair_time_ns + delay_ms * MS
        result = apply_reward(
            eligible.state,
            synapse_params,
            reward_params,
            reward_time_ns,
            reward_value=1.0,
            baseline_before=0.5,
            learning_context_id=context_id,
        )
        rows.append(
            {
                "delay_after_pair_ms": delay_ms,
                "age_from_context_start_ms": reward_time_ns // MS,
                "status": result.status,
                "weight_delta": _rounded(result.weight_delta),
                "baseline_after": _rounded(result.baseline_after),
            }
        )
    return rows


def run_sweeps() -> dict[str, object]:
    """Return a stable, bounded sweep report without touching the filesystem."""
    lif = _lif_sweep()
    stdp = _stdp_sweep()
    rstdp = _rstdp_sweep()
    return {
        "schema": SWEEP_SCHEMA,
        "profile_id": PROFILE_ID,
        "purpose": "exploratory_sensitivity_analysis_not_calibration",
        "counts": {"lif": len(lif), "stdp": len(stdp), "rstdp": len(rstdp)},
        "lif": lif,
        "stdp": stdp,
        "rstdp": rstdp,
    }


def main() -> None:
    print(json.dumps(run_sweeps(), sort_keys=True, indent=2))


if __name__ == "__main__":
    main()
