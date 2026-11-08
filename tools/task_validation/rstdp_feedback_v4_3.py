"""V4.3 decision-boundary temporal-credit experiment.

The frozen V4 path updates eligibility for all three input pulses. The
experimental policy latches the classification decision at the first output
spike and stops adding plasticity events after that point, while continuing to
process all sensory inputs through the neuron. This isolates post-decision
credit contamination without changing the neuron, event ordering, reward
equation, dataset, STARTER parameters or diagnostic initial weight.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from typing import Any

from tools.numerical_oracle.jarvis_oracle import (
    InputSignal,
    NeuronState,
    SynapseState,
    apply_reward,
    process_neuron_batch,
    starter_neuron_params,
    starter_reward_params,
    starter_synapse_params,
    update_rstdp,
    update_stdp,
)
from tools.task_validation.rstdp_feedback_v4 import (
    CANONICAL_STARTER_WEIGHT,
    DECISION_DEADLINE_MS,
    DIAGNOSTIC_INITIAL_WEIGHT,
    EPISODE_SPACING_MS,
    HELD_OUT_PAIRS,
    NS_PER_MS,
    RUN_SEEDS,
    TRAIN_PAIRS,
    _add_prediction,
    _build_dataset,
    _empty_counts,
    _metrics,
    _pulse_times_ms,
)

CONDITION_MODES = (
    "fixed",
    "stdp",
    "rstdp_v4",
    "rstdp_decision_latched",
    "rstdp_neutral",
)
R_STDP_MODES = {"rstdp_v4", "rstdp_decision_latched", "rstdp_neutral"}
BENCHMARK = "JARVIS_R_STDP_DECISION_BOUNDARY_CREDIT_V4_3"


def _sign(value: float) -> str:
    if value > 0.0:
        return "positive"
    if value < 0.0:
        return "negative"
    return "zero"


def _run_episode(
    episode: Any,
    episode_index: int,
    mode: str,
    synapse_state: SynapseState,
    reward_baseline: float,
    learning_context_id: str,
    training: bool,
) -> tuple[dict[str, Any], SynapseState, float, dict[str, int | float]]:
    if mode not in CONDITION_MODES:
        raise ValueError(f"unsupported V4.3 condition: {mode}")

    neuron_params = starter_neuron_params()
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()
    start_ns = episode_index * EPISODE_SPACING_MS * NS_PER_MS
    pulse_times = _pulse_times_ms(episode)
    if pulse_times[-1] > DECISION_DEADLINE_MS:
        raise ValueError("all three pulses must precede the frozen deadline")

    neuron_state = NeuronState(last_update_model_time_ns=start_ns)
    spike_times_ms: list[int] = []
    first_spike_pulse_index: int | None = None
    decision_latched = False
    stats: dict[str, int | float] = {
        "plasticity_event_count": 0,
        "skipped_postdecision_plasticity_event_count": 0,
        "positive_q_count": 0,
        "negative_q_count": 0,
        "zero_q_count": 0,
        "local_weight_delta_sum": 0.0,
    }
    weight_before_episode = synapse_state.weight

    for pulse_index, (relative_ms, strength) in enumerate(
        zip(pulse_times, episode.strengths)
    ):
        time_ns = start_ns + relative_ms * NS_PER_MS
        step = process_neuron_batch(
            neuron_state,
            time_ns,
            [InputSignal(
                source_id="v43_temporal_input",
                signed_weight=synapse_state.weight,
                strength=strength,
                attention=0.0,
            )],
            neuron_params,
        )
        neuron_state = step.state
        n_post = int(step.spike_emitted)
        if step.spike_emitted:
            spike_times_ms.append(relative_ms)
            if first_spike_pulse_index is None:
                first_spike_pulse_index = pulse_index

        if training and mode == "stdp":
            plasticity = update_stdp(
                synapse_state, synapse_params, time_ns,
                n_pre=1, n_post=n_post,
            )
            synapse_state = plasticity.state
            stats["plasticity_event_count"] += 1
            stats["positive_q_count"] += int(plasticity.q_bounded > 0.0)
            stats["negative_q_count"] += int(plasticity.q_bounded < 0.0)
            stats["zero_q_count"] += int(plasticity.q_bounded == 0.0)
            stats["local_weight_delta_sum"] += plasticity.weight_delta

        elif training and mode in R_STDP_MODES:
            if mode == "rstdp_decision_latched" and decision_latched:
                # The neuron still receives the input above. Only learning is
                # frozen after the first output spike has latched FAST.
                stats["skipped_postdecision_plasticity_event_count"] += 1
            else:
                plasticity = update_rstdp(
                    synapse_state,
                    synapse_params,
                    reward_params,
                    time_ns,
                    n_pre=1,
                    n_post=n_post,
                    learning_context_id=learning_context_id,
                )
                synapse_state = plasticity.state
                stats["plasticity_event_count"] += 1
                stats["positive_q_count"] += int(plasticity.q_bounded > 0.0)
                stats["negative_q_count"] += int(plasticity.q_bounded < 0.0)
                stats["zero_q_count"] += int(plasticity.q_bounded == 0.0)

        if step.spike_emitted:
            decision_latched = True

    decision_time_ns = start_ns + DECISION_DEADLINE_MS * NS_PER_MS
    process_neuron_batch(neuron_state, decision_time_ns, [], neuron_params)
    prediction = "FAST" if spike_times_ms else "SLOW"
    correct = prediction == episode.label

    feedback = None
    if training and mode in R_STDP_MODES:
        reward_value = (
            reward_baseline if mode == "rstdp_neutral"
            else (1.0 if correct else 0.0)
        )
        eligibility_age_ns = (
            decision_time_ns - synapse_state.eligibility_last_update_model_time_ns
        )
        eligibility_at_feedback = (
            synapse_state.eligibility_trace
            * math.exp(-eligibility_age_ns / reward_params.tau_e_ns)
        )
        baseline_before = reward_baseline
        reward_step = apply_reward(
            synapse_state,
            synapse_params,
            reward_params,
            decision_time_ns,
            reward_value,
            baseline_before,
            learning_context_id,
        )
        synapse_state = reward_step.state
        reward_baseline = reward_step.baseline_after
        feedback = {
            "reward_value": reward_value,
            "baseline_before": baseline_before,
            "baseline_after": reward_baseline,
            "modulator": reward_step.modulator,
            "eligibility_at_feedback": eligibility_at_feedback,
            "weight_delta": reward_step.weight_delta,
            "weight_after": synapse_state.weight,
            "status": reward_step.status,
        }

    record = {
        "episode_id": episode.episode_id,
        "pair_id": episode.pair_id,
        "label": episode.label,
        "prediction": prediction,
        "correct": correct,
        "pulse_times_ms": list(pulse_times),
        "spike_times_ms": spike_times_ms,
        "first_spike_pulse_index": first_spike_pulse_index,
        "postdecision_pulses": (
            0 if first_spike_pulse_index is None
            else max(0, len(pulse_times) - first_spike_pulse_index - 1)
        ),
        "weight_before_episode": weight_before_episode,
        "weight_after_episode": synapse_state.weight,
        "feedback": feedback,
    }
    return record, synapse_state, reward_baseline, stats


def _new_diagnostics() -> dict[str, Any]:
    return {
        "plasticity_event_count": 0,
        "skipped_postdecision_plasticity_event_count": 0,
        "positive_q_count": 0,
        "negative_q_count": 0,
        "zero_q_count": 0,
        "positive_modulator_count": 0,
        "negative_modulator_count": 0,
        "zero_modulator_count": 0,
        "positive_eligibility_count": 0,
        "negative_eligibility_count": 0,
        "zero_eligibility_count": 0,
        "positive_weight_delta_count": 0,
        "negative_weight_delta_count": 0,
        "zero_weight_delta_count": 0,
        "weight_delta_sum": 0.0,
        "correct_fast_positive_eligibility_count": 0,
        "correct_fast_negative_eligibility_count": 0,
        "false_positive_positive_eligibility_count": 0,
        "false_positive_negative_eligibility_count": 0,
        "episode_cross_tab": {},
    }


def _record_diagnostics(
    diagnostics: dict[str, Any],
    record: dict[str, Any],
    stats: dict[str, int | float],
) -> None:
    for key in (
        "plasticity_event_count",
        "skipped_postdecision_plasticity_event_count",
        "positive_q_count",
        "negative_q_count",
        "zero_q_count",
    ):
        diagnostics[key] += int(stats[key])
    feedback = record["feedback"]
    if feedback is None:
        return
    eligibility = float(feedback["eligibility_at_feedback"])
    modulator = float(feedback["modulator"])
    delta = float(feedback["weight_delta"])
    diagnostics["positive_modulator_count"] += int(modulator > 0.0)
    diagnostics["negative_modulator_count"] += int(modulator < 0.0)
    diagnostics["zero_modulator_count"] += int(modulator == 0.0)
    diagnostics["positive_eligibility_count"] += int(eligibility > 0.0)
    diagnostics["negative_eligibility_count"] += int(eligibility < 0.0)
    diagnostics["zero_eligibility_count"] += int(eligibility == 0.0)
    diagnostics["positive_weight_delta_count"] += int(delta > 0.0)
    diagnostics["negative_weight_delta_count"] += int(delta < 0.0)
    diagnostics["zero_weight_delta_count"] += int(delta == 0.0)
    diagnostics["weight_delta_sum"] += delta

    if record["label"] == "FAST" and record["prediction"] == "FAST":
        diagnostics["correct_fast_positive_eligibility_count"] += int(eligibility > 0)
        diagnostics["correct_fast_negative_eligibility_count"] += int(eligibility < 0)
    if record["label"] == "SLOW" and record["prediction"] == "FAST":
        diagnostics["false_positive_positive_eligibility_count"] += int(eligibility > 0)
        diagnostics["false_positive_negative_eligibility_count"] += int(eligibility < 0)

    key = "|".join((
        str(record["label"]),
        str(record["prediction"]),
        "correct" if record["correct"] else "incorrect",
        _sign(eligibility),
        _sign(modulator),
        _sign(delta),
    ))
    cross_tab = diagnostics["episode_cross_tab"]
    cross_tab[key] = cross_tab.get(key, 0) + 1


def _run_condition(
    seed: int,
    mode: str,
    train: tuple[Any, ...],
    held_out: tuple[Any, ...],
) -> dict[str, Any]:
    if mode not in CONDITION_MODES:
        raise ValueError(f"unsupported V4.3 condition: {mode}")
    synapse_state = SynapseState(weight=DIAGNOSTIC_INITIAL_WEIGHT)
    reward_baseline = 0.5
    train_counts = _empty_counts()
    held_counts = _empty_counts()
    diagnostics = _new_diagnostics()

    for index, episode in enumerate(train):
        record, synapse_state, reward_baseline, stats = _run_episode(
            episode, index, mode, synapse_state, reward_baseline,
            f"v43-{seed}-{mode}-{episode.episode_id}", True,
        )
        _add_prediction(train_counts, episode.label, record["prediction"])
        _record_diagnostics(diagnostics, record, stats)

    weight_after_training = synapse_state.weight
    for offset, episode in enumerate(held_out):
        record, synapse_state, reward_baseline, _ = _run_episode(
            episode, len(train) + offset, mode, synapse_state, reward_baseline,
            f"v43-heldout-{seed}-{episode.episode_id}", False,
        )
        _add_prediction(held_counts, episode.label, record["prediction"])

    return {
        "condition": mode,
        "seed": seed,
        "initial_weight": DIAGNOSTIC_INITIAL_WEIGHT,
        "weight_after_training": weight_after_training,
        "weight_delta": weight_after_training - DIAGNOSTIC_INITIAL_WEIGHT,
        "weight_after_held_out": synapse_state.weight,
        "held_out_weights_frozen": synapse_state.weight == weight_after_training,
        "reward_baseline_after_training": (
            reward_baseline if mode in R_STDP_MODES else None
        ),
        "train": _metrics(train_counts),
        "held_out": _metrics(held_counts),
        "training_diagnostics": diagnostics,
    }


def run_benchmark(
    train_pairs: int = 512,
    held_out_pairs: int = 128,
    seeds: tuple[int, ...] = RUN_SEEDS[:2],
    progress: bool = False,
) -> dict[str, Any]:
    if train_pairs < 1 or held_out_pairs < 1:
        raise ValueError("pair counts must be positive")
    if not seeds or len(set(seeds)) != len(seeds):
        raise ValueError("seeds must be a non-empty sequence of unique integers")

    runs: list[dict[str, Any]] = []
    for seed_index, seed in enumerate(seeds, start=1):
        if progress:
            print(
                f"[V4.3] seed {seed_index}/{len(seeds)} ({seed}): dataset",
                file=sys.stderr, flush=True,
            )
        train, held_out = _build_dataset(seed, train_pairs, held_out_pairs)
        conditions = []
        for mode_index, mode in enumerate(CONDITION_MODES, start=1):
            if progress:
                print(
                    f"[V4.3] seed={seed} condition {mode_index}/{len(CONDITION_MODES)} "
                    f"{mode}: {len(train)} training episodes",
                    file=sys.stderr, flush=True,
                )
            conditions.append(_run_condition(seed, mode, train, held_out))
        runs.append({"seed": seed, "conditions": conditions})

    aggregate: list[dict[str, Any]] = []
    for mode in CONDITION_MODES:
        rows = [
            next(item for item in run["conditions"] if item["condition"] == mode)
            for run in runs
        ]
        combined_counts = {
            key: sum(row["held_out"][key] for row in rows)
            for key in ("tp", "fp", "tn", "fn")
        }
        aggregate.append({
            "condition": mode,
            "held_out": _metrics(combined_counts),
            "held_out_accuracy_by_seed": [
                {"seed": row["seed"], "accuracy": row["held_out"]["accuracy"]}
                for row in rows
            ],
            "weight_after_training_by_seed": [
                {"seed": row["seed"], "weight": row["weight_after_training"],
                 "delta": row["weight_delta"]}
                for row in rows
            ],
            "training_diagnostics": {
                key: (
                    {
                        cell: sum(
                            int(row["training_diagnostics"]["episode_cross_tab"].get(cell, 0))
                            for row in rows
                        )
                        for cell in sorted({
                            cell
                            for row in rows
                            for cell in row["training_diagnostics"]["episode_cross_tab"]
                        })
                    }
                    if key == "episode_cross_tab"
                    else sum(row["training_diagnostics"][key] for row in rows)
                )
                for key in rows[0]["training_diagnostics"]
            },
        })

    by_seed = {run["seed"]: run for run in runs}
    comparisons = []
    for seed in seeds:
        rows = {item["condition"]: item for item in by_seed[seed]["conditions"]}
        comparisons.append({
            "seed": seed,
            "decision_latched_minus_v4_accuracy": (
                rows["rstdp_decision_latched"]["held_out"]["accuracy"]
                - rows["rstdp_v4"]["held_out"]["accuracy"]
            ),
            "decision_latched_minus_fixed_accuracy": (
                rows["rstdp_decision_latched"]["held_out"]["accuracy"]
                - rows["fixed"]["held_out"]["accuracy"]
            ),
            "decision_latched_minus_neutral_accuracy": (
                rows["rstdp_decision_latched"]["held_out"]["accuracy"]
                - rows["rstdp_neutral"]["held_out"]["accuracy"]
            ),
            "decision_latched_minus_stdp_accuracy": (
                rows["rstdp_decision_latched"]["held_out"]["accuracy"]
                - rows["stdp"]["held_out"]["accuracy"]
            ),
        })

    return {
        "benchmark": BENCHMARK,
        "status": "REFERENCE_EXPERIMENT_NOT_PRODUCTION_RUNTIME",
        "protocol": {
            "canonical_starter_weight": CANONICAL_STARTER_WEIGHT,
            "starter_profile_modified": False,
            "diagnostic_initial_weight": DIAGNOSTIC_INITIAL_WEIGHT,
            "neuron_and_learning_parameters": "canonical STARTER V1, unchanged",
            "dataset": "exact V4 SHA-256 matched train/held-out generator",
            "seeds": list(seeds),
            "training_pairs_per_seed": train_pairs,
            "held_out_pairs_per_seed": held_out_pairs,
            "conditions": list(CONDITION_MODES),
            "decision_rule": "first output spike latches FAST; no spike by 40ms => SLOW",
            "decision_latched_policy": (
                "continue all three sensory inputs through the neuron, but stop "
                "adding R-STDP eligibility events after the first output spike; "
                "the spike-producing pulse itself remains eligible"
            ),
            "frozen_control": "rstdp_v4 replays all three plasticity events exactly as V4",
            "reward": "1.0 for correct, 0.0 for incorrect; neutral control uses current baseline",
            "held_out": "inference only; no plasticity or reward updates",
        },
        "aggregate_conditions": aggregate,
        "per_seed_comparisons": comparisons,
        "runs": runs,
        "interpretation_limits": [
            "Decision-boundary eligibility gating is a task-specific hypothesis, not a biological claim.",
            "The neuron still processes post-decision sensory inputs; only their plasticity events are gated.",
            "A positive eligibility sign is not sufficient evidence of better learning; held-out metrics across seeds decide.",
            "The 0.70 initial weight is diagnostic only; canonical STARTER 0.5 is unchanged.",
            "This is a Python reference experiment, not production runtime or device resource measurement.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="V4.3 decision-boundary temporal-credit experiment."
    )
    parser.add_argument("--profile", choices=("full", "pilot", "smoke"), default="pilot")
    parser.add_argument("--train-pairs", type=int, default=None)
    parser.add_argument("--held-out-pairs", type=int, default=None)
    parser.add_argument("--seeds", type=int, nargs="+", default=None)
    args = parser.parse_args()
    profiles = {
        "full": (TRAIN_PAIRS, HELD_OUT_PAIRS, RUN_SEEDS),
        "pilot": (512, 128, RUN_SEEDS[:2]),
        "smoke": (64, 32, RUN_SEEDS[:1]),
    }
    default_train, default_held, default_seeds = profiles[args.profile]
    train_pairs = args.train_pairs if args.train_pairs is not None else default_train
    held_out_pairs = args.held_out_pairs if args.held_out_pairs is not None else default_held
    seeds = tuple(args.seeds) if args.seeds is not None else tuple(default_seeds)
    if train_pairs < 1 or held_out_pairs < 1:
        parser.error("pair counts must be positive")
    report = run_benchmark(train_pairs, held_out_pairs, seeds, progress=True)
    print(json.dumps(report, indent=2, sort_keys=True, allow_nan=False))


if __name__ == "__main__":
    main()
