"""V3 temporal-rate benchmark with event-level plasticity diagnostics.

This is a deterministic float64 reference experiment, not a production runtime.
It preserves JARVIS_SINGLE_NEURON_STARTER_V1 and evaluates decisions at a
fixed observation deadline. Inputs arriving after that deadline are withheld
from the decision and from that episode's learning update.
"""
from __future__ import annotations

import hashlib
import itertools
import json
import math
import statistics
from dataclasses import dataclass
from typing import Iterable

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

NS_PER_MS = 1_000_000
EPISODE_SPACING_MS = 2_000
INITIAL_WEIGHT = 0.5
DECISION_DEADLINE_MS = 7
FAST_GAP_VALUES = (2, 3)
SLOW_GAP_VALUES = (4, 5)
STRENGTH_LEVELS = (0.80, 0.85, 0.90, 0.95, 1.00)
RUN_SEEDS = (104729, 130363, 155921, 181081)
TRAIN_PER_CLASS = 16
HELD_OUT_PER_CLASS = 20
CONDITION_MODES = ("fixed", "stdp", "rstdp", "rstdp_neutral")
DIAGNOSTIC_WEIGHT_GRID = (0.45, 0.475, 0.50, 0.525, 0.55, 0.575, 0.60)


@dataclass(frozen=True)
class Episode:
    episode_id: str
    label: str
    gaps_ms: tuple[int, int]
    strengths: tuple[float, float, float]


def _signature(episode: Episode) -> tuple[tuple[int, int], tuple[float, float, float]]:
    return episode.gaps_ms, episode.strengths


def _candidate_episode(
    seed: int, split: str, label: str, index: int, attempt: int
) -> Episode:
    material = (
        f"JARVIS_TEMPORAL_ROBUSTNESS_V3|{seed}|{split}|{label}|{index}|{attempt}"
    )
    digest = hashlib.sha256(material.encode("utf-8")).digest()
    gap_values = FAST_GAP_VALUES if label == "FAST" else SLOW_GAP_VALUES
    gaps = (gap_values[digest[0] % 2], gap_values[digest[1] % 2])
    strengths = tuple(
        STRENGTH_LEVELS[digest[offset] % len(STRENGTH_LEVELS)]
        for offset in (2, 3, 4)
    )
    return Episode(
        episode_id=f"seed-{seed}-{split}-{label.lower()}-{index:03d}",
        label=label,
        gaps_ms=gaps,
        strengths=strengths,
    )


def _interleave(seed: int, episodes: list[Episode]) -> tuple[Episode, ...]:
    """Deterministically shuffle within classes and alternate class labels."""
    buckets = {
        label: sorted(
            (item for item in episodes if item.label == label),
            key=lambda item: hashlib.sha256(
                f"V3_ORDER|{seed}|{item.episode_id}".encode("utf-8")
            ).digest(),
        )
        for label in ("FAST", "SLOW")
    }
    digest = hashlib.sha256(f"V3_START_CLASS|{seed}".encode("utf-8")).digest()
    first = "FAST" if digest[0] % 2 == 0 else "SLOW"
    second = "SLOW" if first == "FAST" else "FAST"
    ordered: list[Episode] = []
    for index in range(len(buckets["FAST"])):
        ordered.append(buckets[first][index])
        ordered.append(buckets[second][index])
    return tuple(ordered)


def _generate_split(
    seed: int,
    split: str,
    per_class: int,
    seen: set[tuple[tuple[int, int], tuple[float, float, float]]],
) -> tuple[Episode, ...]:
    generated: list[Episode] = []
    for label in ("FAST", "SLOW"):
        for index in range(per_class):
            for attempt in range(10_000):
                episode = _candidate_episode(seed, split, label, index, attempt)
                signature = _signature(episode)
                if signature not in seen:
                    seen.add(signature)
                    generated.append(episode)
                    break
            else:
                raise RuntimeError("could not generate a unique V3 input signature")
    return _interleave(seed, generated)


def _validate_dataset(
    train: tuple[Episode, ...], held_out: tuple[Episode, ...]
) -> None:
    all_episodes = train + held_out
    ids = [episode.episode_id for episode in all_episodes]
    signatures = [_signature(episode) for episode in all_episodes]
    if len(ids) != len(set(ids)):
        raise ValueError("episode IDs must be unique")
    if len(signatures) != len(set(signatures)):
        raise ValueError("full input signatures must be unique across both splits")
    if not {_signature(item) for item in train}.isdisjoint(
        {_signature(item) for item in held_out}
    ):
        raise ValueError("training and held-out signatures must be disjoint")

    for episodes, expected_per_class, split_name in (
        (train, TRAIN_PER_CLASS, "training"),
        (held_out, HELD_OUT_PER_CLASS, "held-out"),
    ):
        labels = [item.label for item in episodes]
        if len(episodes) != expected_per_class * 2:
            raise ValueError(f"{split_name} episode count is invalid")
        if labels.count("FAST") != expected_per_class:
            raise ValueError(f"{split_name} FAST class is not balanced")
        if labels.count("SLOW") != expected_per_class:
            raise ValueError(f"{split_name} SLOW class is not balanced")
        if any(labels[index] == labels[index + 1] for index in range(len(labels) - 1)):
            raise ValueError(f"{split_name} order must be class-interleaved")

    for episode in all_episodes:
        if episode.label not in {"FAST", "SLOW"}:
            raise ValueError("unsupported V3 class label")
        allowed_gaps = FAST_GAP_VALUES if episode.label == "FAST" else SLOW_GAP_VALUES
        if len(episode.gaps_ms) != 2 or any(gap not in allowed_gaps for gap in episode.gaps_ms):
            raise ValueError("episode gaps do not match the predeclared class protocol")
        if len(episode.strengths) != 3 or any(
            strength not in STRENGTH_LEVELS for strength in episode.strengths
        ):
            raise ValueError("episode strengths must use the shared strength grid")


def _build_dataset(seed: int) -> tuple[tuple[Episode, ...], tuple[Episode, ...]]:
    seen: set[tuple[tuple[int, int], tuple[float, float, float]]] = set()
    train = _generate_split(seed, "train", TRAIN_PER_CLASS, seen)
    held_out = _generate_split(seed, "held-out", HELD_OUT_PER_CLASS, seen)
    _validate_dataset(train, held_out)
    return train, held_out


def _pulse_times_ms(episode: Episode) -> tuple[int, int, int]:
    return (0, episode.gaps_ms[0], sum(episode.gaps_ms))


def _metrics(rows: Iterable[dict[str, object]]) -> dict[str, float | int]:
    tp = fp = tn = fn = 0
    for row in rows:
        expected_fast = row["label"] == "FAST"
        predicted_fast = row["prediction"] == "FAST"
        if expected_fast and predicted_fast:
            tp += 1
        elif not expected_fast and predicted_fast:
            fp += 1
        elif not expected_fast and not predicted_fast:
            tn += 1
        else:
            fn += 1

    total = tp + fp + tn + fn
    precision_fast = tp / (tp + fp) if tp + fp else 0.0
    recall_fast = tp / (tp + fn) if tp + fn else 0.0
    specificity_slow = tn / (tn + fp) if tn + fp else 0.0
    precision_slow = tn / (tn + fn) if tn + fn else 0.0
    f1_fast = (
        2.0 * precision_fast * recall_fast / (precision_fast + recall_fast)
        if precision_fast + recall_fast else 0.0
    )
    f1_slow = (
        2.0 * precision_slow * specificity_slow / (precision_slow + specificity_slow)
        if precision_slow + specificity_slow else 0.0
    )
    return {
        "episodes": total,
        "correct": tp + tn,
        "accuracy": (tp + tn) / total if total else 0.0,
        "precision_fast": precision_fast,
        "recall_fast": recall_fast,
        "f1_fast": f1_fast,
        "precision_slow": precision_slow,
        "recall_slow": specificity_slow,
        "f1_slow": f1_slow,
        "specificity_slow": specificity_slow,
        "balanced_accuracy": (recall_fast + specificity_slow) / 2.0,
        "macro_f1": (f1_fast + f1_slow) / 2.0,
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
    }


def _run_episode(
    episode: Episode,
    episode_index: int,
    mode: str,
    synapse_state: SynapseState,
    reward_baseline: float,
    learning_context_id: str,
    training: bool,
) -> tuple[dict[str, object], SynapseState, float]:
    if mode not in CONDITION_MODES:
        raise ValueError(f"unsupported V3 condition: {mode}")

    neuron_params = starter_neuron_params()
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()
    start_ns = episode_index * EPISODE_SPACING_MS * NS_PER_MS
    pulse_times = _pulse_times_ms(episode)
    neuron_state = NeuronState(last_update_model_time_ns=start_ns)
    spike_times_ms: list[int] = []
    pulse_records: list[dict[str, object]] = []
    weight_before_episode = synapse_state.weight

    observed_count = 0
    for pulse_index, (relative_ms, strength) in enumerate(
        zip(pulse_times, episode.strengths)
    ):
        if relative_ms > DECISION_DEADLINE_MS:
            continue
        observed_count += 1
        time_ns = start_ns + relative_ms * NS_PER_MS
        weight_before_event = synapse_state.weight
        step = process_neuron_batch(
            neuron_state,
            time_ns,
            [InputSignal(
                source_id="temporal_stream",
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

        update_record: dict[str, object] | None = None
        if training and mode == "stdp":
            plasticity = update_stdp(
                synapse_state, synapse_params, time_ns, n_pre=1, n_post=n_post
            )
            synapse_state = plasticity.state
            update_record = {
                "rule": "STDP",
                "q_raw": plasticity.q_raw,
                "q_bounded": plasticity.q_bounded,
                "eligibility_before": None,
                "eligibility_after": None,
                "event_weight_delta": plasticity.weight_delta,
                "weight_after_event": synapse_state.weight,
            }
        elif training and mode in {"rstdp", "rstdp_neutral"}:
            eligibility_before = synapse_state.eligibility_trace
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
            update_record = {
                "rule": "R-STDP",
                "q_raw": plasticity.q_raw,
                "q_bounded": plasticity.q_bounded,
                "eligibility_before": eligibility_before,
                "eligibility_after": synapse_state.eligibility_trace,
                "eligibility_context_id": synapse_state.eligibility_context_id,
                "event_weight_delta": plasticity.weight_delta,
                "weight_after_event": synapse_state.weight,
            }

        pulse_records.append({
            "pulse_index": pulse_index,
            "time_ms": relative_ms,
            "strength": strength,
            "output_spike": step.spike_emitted,
            "effective_threshold": step.effective_threshold,
            "potential_input_delta": step.total_input_delta,
            "pulse_weight": weight_before_event,
            "learning_update": update_record,
        })

    weight_after_pulses = synapse_state.weight
    decision_step = process_neuron_batch(
        neuron_state,
        start_ns + DECISION_DEADLINE_MS * NS_PER_MS,
        [],
        neuron_params,
    )
    neuron_state = decision_step.state
    if decision_step.spike_emitted:
        spike_times_ms.append(DECISION_DEADLINE_MS)
    predicted_fast = bool(spike_times_ms)
    prediction = "FAST" if predicted_fast else "SLOW"
    correct = prediction == episode.label
    reward_record: dict[str, object] | None = None

    if training and mode in {"rstdp", "rstdp_neutral"}:
        baseline_before = reward_baseline
        reward_value = (
            (1.0 if correct else 0.0)
            if mode == "rstdp"
            else baseline_before
        )
        reward_time_ns = start_ns + DECISION_DEADLINE_MS * NS_PER_MS
        eligibility_before_reward = synapse_state.eligibility_trace
        eligibility_age_ns = max(
            0, reward_time_ns - synapse_state.eligibility_last_update_model_time_ns
        )
        eligibility_at_reward = eligibility_before_reward * math.exp(
            -eligibility_age_ns / reward_params.tau_e_ns
        )
        reward_step = apply_reward(
            synapse_state,
            synapse_params,
            reward_params,
            reward_time_ns,
            reward_value,
            baseline_before,
            learning_context_id,
        )
        synapse_state = reward_step.state
        reward_baseline = reward_step.baseline_after
        reward_record = {
            "reward_value": reward_value,
            "baseline_before": baseline_before,
            "baseline_after": reward_step.baseline_after,
            "modulator": reward_step.modulator,
            "eligibility_before_decay": eligibility_before_reward,
            "eligibility_at_reward": eligibility_at_reward,
            "weight_delta": reward_step.weight_delta,
            "weight_after_reward": synapse_state.weight,
            "status": reward_step.status,
        }

    observed_times = [pulse["time_ms"] for pulse in pulse_records]
    withheld_count = len(episode.strengths) - observed_count
    return {
        "episode_id": episode.episode_id,
        "label": episode.label,
        "gaps_ms": list(episode.gaps_ms),
        "strengths": list(episode.strengths),
        "decision_deadline_ms": DECISION_DEADLINE_MS,
        "observed_input_pulses": observed_count,
        "withheld_after_deadline_pulses": withheld_count,
        "observed_pulse_times_ms": observed_times,
        "decision_check": {
            "time_ms": DECISION_DEADLINE_MS,
            "output_spike": decision_step.spike_emitted,
            "effective_threshold": decision_step.effective_threshold,
            "potential_input_delta": decision_step.total_input_delta,
        },
        "spike_times_ms": spike_times_ms,
        "first_spike_latency_ms": spike_times_ms[0] if spike_times_ms else None,
        "prediction": prediction,
        "correct": correct,
        "weight_before_episode": weight_before_episode,
        "weight_after_pulses": weight_after_pulses,
        "weight_after_episode": synapse_state.weight,
        "pulse_diagnostics": pulse_records,
        "reward_diagnostic": reward_record,
    }, synapse_state, reward_baseline


def _summarize_diagnostics(records: list[dict[str, object]]) -> dict[str, object]:
    q_raw_values: list[float] = []
    q_bounded_values: list[float] = []
    event_weight_deltas: list[float] = []
    eligibility_before_reward: list[float] = []
    eligibility_at_reward: list[float] = []
    modulators: list[float] = []
    reward_weight_deltas: list[float] = []
    reward_statuses: dict[str, int] = {}

    for episode in records:
        for pulse in episode["pulse_diagnostics"]:
            update = pulse["learning_update"]
            if update is not None:
                q_raw_values.append(float(update["q_raw"]))
                q_bounded_values.append(float(update["q_bounded"]))
                event_weight_deltas.append(float(update["event_weight_delta"]))
        reward = episode["reward_diagnostic"]
        if reward is not None:
            eligibility_before_reward.append(float(reward["eligibility_before_decay"]))
            eligibility_at_reward.append(float(reward["eligibility_at_reward"]))
            modulators.append(float(reward["modulator"]))
            reward_weight_deltas.append(float(reward["weight_delta"]))
            status = str(reward["status"])
            reward_statuses[status] = reward_statuses.get(status, 0) + 1

    return {
        "plasticity_event_count": len(q_raw_values),
        "q_raw_sum": math.fsum(q_raw_values),
        "q_bounded_sum": math.fsum(q_bounded_values),
        "positive_q_event_count": sum(value > 0.0 for value in q_bounded_values),
        "negative_q_event_count": sum(value < 0.0 for value in q_bounded_values),
        "event_weight_delta_sum": math.fsum(event_weight_deltas),
        "eligibility_abs_before_reward_sum": math.fsum(abs(x) for x in eligibility_before_reward),
        "eligibility_abs_at_reward_sum": math.fsum(abs(x) for x in eligibility_at_reward),
        "reward_event_count": len(modulators),
        "reward_modulator_sum": math.fsum(modulators),
        "reward_modulator_abs_sum": math.fsum(abs(x) for x in modulators),
        "positive_reward_modulator_count": sum(value > 0.0 for value in modulators),
        "negative_reward_modulator_count": sum(value < 0.0 for value in modulators),
        "reward_weight_delta_sum": math.fsum(reward_weight_deltas),
        "reward_status_counts": reward_statuses,
    }


def _run_condition(
    seed: int,
    mode: str,
    train: tuple[Episode, ...],
    held_out: tuple[Episode, ...],
) -> dict[str, object]:
    synapse_state = SynapseState(weight=INITIAL_WEIGHT)
    reward_baseline = 0.5
    train_records: list[dict[str, object]] = []
    weight_trajectory: list[dict[str, object]] = []

    for index, episode in enumerate(train):
        before = synapse_state.weight
        record, synapse_state, reward_baseline = _run_episode(
            episode,
            index,
            mode,
            synapse_state,
            reward_baseline,
            f"v3-{seed}-{mode}-{episode.episode_id}",
            training=True,
        )
        train_records.append(record)
        weight_trajectory.append({
            "episode_id": episode.episode_id,
            "weight_before": before,
            "weight_after": synapse_state.weight,
            "weight_delta": synapse_state.weight - before,
            "prediction": record["prediction"],
            "label": record["label"],
            "correct": record["correct"],
        })

    weight_after_training = synapse_state.weight
    held_records: list[dict[str, object]] = []
    for index, episode in enumerate(held_out):
        record, synapse_state, reward_baseline = _run_episode(
            episode,
            len(train) + index,
            mode,
            synapse_state,
            reward_baseline,
            f"v3-heldout-{seed}-{episode.episode_id}",
            training=False,
        )
        held_records.append(record)

    weight_after_held_out = synapse_state.weight
    return {
        "condition": mode,
        "seed": seed,
        "initial_weight": INITIAL_WEIGHT,
        "weight_after_training": weight_after_training,
        "weight_delta": weight_after_training - INITIAL_WEIGHT,
        "weight_after_held_out": weight_after_held_out,
        "held_out_weights_frozen": weight_after_held_out == weight_after_training,
        "reward_baseline_after_training": (
            reward_baseline if mode in {"rstdp", "rstdp_neutral"} else None
        ),
        "train": _metrics(train_records),
        "held_out": _metrics(held_records),
        "training_diagnostics": _summarize_diagnostics(train_records),
        "held_out_diagnostics": _summarize_diagnostics(held_records),
        "training_weight_trajectory": weight_trajectory,
        "training_episode_results": train_records,
        "held_out_episode_results": held_records,
    }


def _summary_stats(values: list[float]) -> dict[str, float | int]:
    return {
        "runs": len(values),
        "mean": statistics.fmean(values) if values else 0.0,
        "sample_stdev": statistics.stdev(values) if len(values) > 1 else 0.0,
        "min": min(values) if values else 0.0,
        "max": max(values) if values else 0.0,
    }


def _aggregate_diagnostics(
    run_conditions: list[dict[str, object]], split: str
) -> dict[str, object]:
    keys = (
        "plasticity_event_count",
        "q_raw_sum",
        "q_bounded_sum",
        "positive_q_event_count",
        "negative_q_event_count",
        "event_weight_delta_sum",
        "eligibility_abs_before_reward_sum",
        "eligibility_abs_at_reward_sum",
        "reward_event_count",
        "reward_modulator_sum",
        "reward_modulator_abs_sum",
        "positive_reward_modulator_count",
        "negative_reward_modulator_count",
        "reward_weight_delta_sum",
    )
    result: dict[str, object] = {}
    for key in keys:
        values = [condition[f"{split}_diagnostics"][key] for condition in run_conditions]
        result[key] = sum(values)
    statuses: dict[str, int] = {}
    for condition in run_conditions:
        for status, count in condition[f"{split}_diagnostics"]["reward_status_counts"].items():
            statuses[status] = statuses.get(status, 0) + count
    result["reward_status_counts"] = statuses
    return result


def _aggregate_conditions(runs: list[dict[str, object]]) -> list[dict[str, object]]:
    summaries: list[dict[str, object]] = []
    for mode in CONDITION_MODES:
        run_conditions = [
            next(item for item in run["conditions"] if item["condition"] == mode)
            for run in runs
        ]
        train_records = [
            row for condition in run_conditions
            for row in condition["training_episode_results"]
        ]
        held_records = [
            row for condition in run_conditions
            for row in condition["held_out_episode_results"]
        ]
        accuracy_by_seed = [condition["held_out"]["accuracy"] for condition in run_conditions]
        balanced_by_seed = [
            condition["held_out"]["balanced_accuracy"] for condition in run_conditions
        ]
        macro_f1_by_seed = [condition["held_out"]["macro_f1"] for condition in run_conditions]
        summaries.append({
            "condition": mode,
            "train": _metrics(train_records),
            "held_out": _metrics(held_records),
            "held_out_accuracy_across_runs": _summary_stats(accuracy_by_seed),
            "held_out_balanced_accuracy_across_runs": _summary_stats(balanced_by_seed),
            "held_out_macro_f1_across_runs": _summary_stats(macro_f1_by_seed),
            "held_out_accuracy_by_seed": [
                {"seed": condition["seed"], "accuracy": condition["held_out"]["accuracy"]}
                for condition in run_conditions
            ],
            "weight_after_training_by_seed": [
                {
                    "seed": condition["seed"],
                    "weight": condition["weight_after_training"],
                    "delta": condition["weight_delta"],
                }
                for condition in run_conditions
            ],
            "training_diagnostics": _aggregate_diagnostics(run_conditions, "training"),
        })
    return summaries


def _predict_at_weight(episode: Episode, weight: float) -> str:
    params = starter_neuron_params()
    start_ns = 0
    state = NeuronState(last_update_model_time_ns=start_ns)
    for relative_ms, strength in zip(_pulse_times_ms(episode), episode.strengths):
        if relative_ms > DECISION_DEADLINE_MS:
            continue
        step = process_neuron_batch(
            state,
            start_ns + relative_ms * NS_PER_MS,
            [InputSignal("boundary_probe", weight, strength, 0.0)],
            params,
        )
        state = step.state
        if step.spike_emitted:
            return "FAST"
    decision_step = process_neuron_batch(
        state, start_ns + DECISION_DEADLINE_MS * NS_PER_MS, [], params
    )
    return "FAST" if decision_step.spike_emitted else "SLOW"


def _boundary_probe() -> dict[str, object]:
    """Exhaust all allowed gap and strength combinations at diagnostic weights."""
    rows_by_weight: list[dict[str, object]] = []
    for weight in DIAGNOSTIC_WEIGHT_GRID:
        records: list[dict[str, object]] = []
        for label, gap_values in (
            ("FAST", FAST_GAP_VALUES),
            ("SLOW", SLOW_GAP_VALUES),
        ):
            for gaps in itertools.product(gap_values, repeat=2):
                for strengths in itertools.product(STRENGTH_LEVELS, repeat=3):
                    episode = Episode(
                        episode_id=f"probe-{label}-{gaps}-{strengths}",
                        label=label,
                        gaps_ms=(gaps[0], gaps[1]),
                        strengths=(strengths[0], strengths[1], strengths[2]),
                    )
                    records.append({
                        "label": label,
                        "prediction": _predict_at_weight(episode, weight),
                    })
        metrics = _metrics(records)
        rows_by_weight.append({
            "weight": weight,
            "metrics": metrics,
        })
    return {
        "scope": "diagnostic_only_no_parameter_updates",
        "weights": rows_by_weight,
        "initial_weight": INITIAL_WEIGHT,
        "initial_weight_accuracy": next(
            row["metrics"]["accuracy"]
            for row in rows_by_weight
            if row["weight"] == INITIAL_WEIGHT
        ),
    }


def run_benchmark() -> dict[str, object]:
    runs: list[dict[str, object]] = []
    for seed in RUN_SEEDS:
        train, held_out = _build_dataset(seed)
        conditions = [
            _run_condition(seed, mode, train, held_out)
            for mode in CONDITION_MODES
        ]
        runs.append({
            "seed": seed,
            "dataset": {
                "training_episodes": len(train),
                "held_out_episodes": len(held_out),
                "training_class_counts": {
                    label: sum(item.label == label for item in train)
                    for label in ("FAST", "SLOW")
                },
                "held_out_class_counts": {
                    label: sum(item.label == label for item in held_out)
                    for label in ("FAST", "SLOW")
                },
                "train_held_out_signatures_disjoint": True,
                "input_signatures_unique": True,
                "training_order_class_interleaved": True,
                "held_out_order_class_interleaved": True,
            },
            "conditions": conditions,
        })

    aggregate = _aggregate_conditions(runs)
    by_seed = {run["seed"]: run for run in runs}
    reward_effect_by_seed: list[dict[str, object]] = []
    for seed in RUN_SEEDS:
        conditions = by_seed[seed]["conditions"]
        rewarded = next(item for item in conditions if item["condition"] == "rstdp")
        neutral = next(item for item in conditions if item["condition"] == "rstdp_neutral")
        reward_effect_by_seed.append({
            "seed": seed,
            "held_out_accuracy_delta": (
                rewarded["held_out"]["accuracy"] - neutral["held_out"]["accuracy"]
            ),
            "held_out_balanced_accuracy_delta": (
                rewarded["held_out"]["balanced_accuracy"]
                - neutral["held_out"]["balanced_accuracy"]
            ),
            "weight_delta_difference": (
                rewarded["weight_delta"] - neutral["weight_delta"]
            ),
            "reward_modulator_abs_sum": (
                rewarded["training_diagnostics"]["reward_modulator_abs_sum"]
            ),
        })

    accuracies = [item["held_out_accuracy_delta"] for item in reward_effect_by_seed]
    balanced = [
        item["held_out_balanced_accuracy_delta"] for item in reward_effect_by_seed
    ]
    return {
        "benchmark": "JARVIS_TEMPORAL_ROBUSTNESS_V3",
        "status": "REFERENCE_EXPERIMENT_NOT_PRODUCTION_RUNTIME",
        "task": (
            "Classify a three-pulse stream as FAST when the neuron emits at least "
            "one spike by 7 ms after the first pulse; otherwise classify it as SLOW. "
            "FAST gaps are 2-3 ms; SLOW gaps are 4-5 ms. All classes share the same "
            "per-pulse strength grid. Inputs after the decision deadline are withheld."
        ),
        "protocol": {
            "neuron": "JARVIS_SINGLE_NEURON_STARTER_V1 / Adaptive LIF-DT",
            "synapses": 1,
            "initial_weight": INITIAL_WEIGHT,
            "pulses_per_episode_total": 3,
            "decision_deadline_ms": DECISION_DEADLINE_MS,
            "fast_gap_values_ms": list(FAST_GAP_VALUES),
            "slow_gap_values_ms": list(SLOW_GAP_VALUES),
            "shared_strength_grid": list(STRENGTH_LEVELS),
            "training_episodes_per_seed": TRAIN_PER_CLASS * 2,
            "held_out_episodes_per_seed": HELD_OUT_PER_CLASS * 2,
            "seeds": list(RUN_SEEDS),
            "condition_modes": list(CONDITION_MODES),
            "decision_rule": "any output spike at or before 7 ms => FAST; otherwise SLOW",
            "observation_policy": (
                "Only input pulses with time_ms <= decision_deadline_ms are presented "
                "to the neuron for this decision. Later pulses are not used for "
                "classification or that episode's plasticity/reward update."
            ),
            "training_policy": {
                "fixed": "no synaptic updates",
                "stdp": "pair-based excitatory STDP after each observed input pulse",
                "rstdp": "eligibility after each observed pulse; reward 1 for correct and 0 for incorrect",
                "rstdp_neutral": "same eligibility process, reward equals current baseline",
            },
            "held_out_policy": "inference only; no STDP, eligibility or reward updates",
            "data_generation": (
                "SHA-256 deterministic generation; identical protocol and strength "
                "grid for both classes; train/held-out full signatures disjoint"
            ),
            "starter_profile_modified": False,
            "randomness": "none; deterministic generation and deterministic order",
        },
        "boundary_probe": _boundary_probe(),
        "aggregate_conditions": aggregate,
        "reward_effect": {
            "comparison": "rewarded R-STDP minus neutral-reward R-STDP",
            "held_out_accuracy_delta_across_runs": _summary_stats(accuracies),
            "held_out_balanced_accuracy_delta_across_runs": _summary_stats(balanced),
            "by_seed": reward_effect_by_seed,
        },
        "runs": runs,
        "interpretation_limits": [
            "This is a controlled temporal-rate/latency task for one neuron and one excitatory synapse.",
            "FAST and SLOW gap ranges are intentionally separable; V2 remains the overlapping-range challenge.",
            "The 7 ms deadline is part of the task definition, not a post-hoc threshold selected from benchmark outcomes.",
            "A fixed baseline may solve the task; if so, learning cannot claim a classification-accuracy gain without a different predeclared task or criterion.",
            "STDP receives no class label; R-STDP receives a scalar correctness reward only after the decision.",
            "A reward-driven weight change is not itself evidence of useful learning; held-out behavior and reward-vs-neutral controls are required.",
            "The boundary probe is descriptive only and does not change STARTER or select new parameters.",
            "Four seeds are repeatability checks, not a statistically powered population estimate.",
            "This is not production-runtime validation, biological equivalence, consciousness or general intelligence.",
        ],
    }


def main() -> None:
    report = run_benchmark()
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
