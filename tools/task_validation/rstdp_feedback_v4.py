"""V4: signed R-STDP feedback and one-neuron error-recovery benchmark.

This is a deterministic Python reference experiment, not the production JARVIS
runtime. The canonical STARTER weight remains 0.5. The recovery experiment
uses a separately declared diagnostic initial weight of 0.70 for every
learning condition.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import sys
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
DECISION_DEADLINE_MS = 40
CANONICAL_STARTER_WEIGHT = 0.5
DIAGNOSTIC_INITIAL_WEIGHT = 0.70
REWARD_BASELINE_INITIAL = 0.5
TRAIN_PAIRS = 8_000
HELD_OUT_PAIRS = 256
RUN_SEEDS = (104729, 130363, 155921, 181081)

FAST_GAP_PAIRS = ((2, 2), (2, 3), (3, 2), (3, 3))
SLOW_GAP_PAIRS = ((18, 18), (18, 19), (19, 18), (19, 19))
CONDITION_MODES = ("fixed", "stdp", "rstdp", "rstdp_neutral")
R_STDP_MODES = ("rstdp", "rstdp_neutral")
MAX_FIRST_ERRORS = 5
TRAJECTORY_CHECKPOINTS = 8


@dataclass(frozen=True)
class StimulusPair:
    pair_id: str
    strengths: tuple[float, float, float]
    fast_gaps_ms: tuple[int, int]
    slow_gaps_ms: tuple[int, int]


@dataclass(frozen=True)
class Episode:
    episode_id: str
    pair_id: str
    label: str
    gaps_ms: tuple[int, int]
    strengths: tuple[float, float, float]


def _digest(material: str) -> bytes:
    return hashlib.sha256(material.encode("utf-8")).digest()


def _strengths_from_digest(digest: bytes) -> tuple[float, float, float]:
    values = []
    for offset in (0, 4, 8):
        unit = int.from_bytes(digest[offset:offset + 4], "big") / 0xFFFFFFFF
        values.append(0.8 + 0.2 * unit)
    return tuple(values)


def _generate_pairs(
    seed: int,
    split: str,
    count: int,
    seen_strengths: set[tuple[float, float, float]],
) -> tuple[StimulusPair, ...]:
    pairs: list[StimulusPair] = []
    for index in range(count):
        for attempt in range(10_000):
            digest = _digest(
                f"JARVIS_R_STDP_FEEDBACK_V4|{seed}|{split}|{index}|{attempt}"
            )
            strengths = _strengths_from_digest(digest)
            if strengths not in seen_strengths:
                seen_strengths.add(strengths)
                pairs.append(StimulusPair(
                    pair_id=f"seed-{seed}-{split}-pair-{index:05d}",
                    strengths=strengths,
                    fast_gaps_ms=FAST_GAP_PAIRS[digest[12] % len(FAST_GAP_PAIRS)],
                    slow_gaps_ms=SLOW_GAP_PAIRS[digest[13] % len(SLOW_GAP_PAIRS)],
                ))
                break
        else:
            raise RuntimeError("could not generate a unique paired stimulus")

    return tuple(sorted(
        pairs,
        key=lambda pair: _digest(
            f"JARVIS_R_STDP_FEEDBACK_V4_ORDER|{seed}|{split}|{pair.pair_id}"
        ),
    ))


def _expand_pairs(
    pairs: tuple[StimulusPair, ...], seed: int, split: str
) -> tuple[Episode, ...]:
    episodes: list[Episode] = []
    for pair in pairs:
        order = _digest(
            f"JARVIS_R_STDP_FEEDBACK_V4_ORIENTATION|{seed}|{split}|{pair.pair_id}"
        )[0]
        fast = Episode(
            episode_id=f"{pair.pair_id}-fast",
            pair_id=pair.pair_id,
            label="FAST",
            gaps_ms=pair.fast_gaps_ms,
            strengths=pair.strengths,
        )
        slow = Episode(
            episode_id=f"{pair.pair_id}-slow",
            pair_id=pair.pair_id,
            label="SLOW",
            gaps_ms=pair.slow_gaps_ms,
            strengths=pair.strengths,
        )
        episodes.extend((slow, fast) if order & 1 else (fast, slow))
    return tuple(episodes)


def _validate_pairs(
    train_pairs: tuple[StimulusPair, ...],
    held_out_pairs: tuple[StimulusPair, ...],
    train_episodes: tuple[Episode, ...],
    held_out_episodes: tuple[Episode, ...],
    train_pair_count: int,
    held_out_pair_count: int,
) -> None:
    if len(train_pairs) != train_pair_count or len(held_out_pairs) != held_out_pair_count:
        raise ValueError("unexpected train/held-out pair count")
    if len(train_episodes) != train_pair_count * 2:
        raise ValueError("training split must contain two episodes per pair")
    if len(held_out_episodes) != held_out_pair_count * 2:
        raise ValueError("held-out split must contain two episodes per pair")

    train_strengths = {pair.strengths for pair in train_pairs}
    held_strengths = {pair.strengths for pair in held_out_pairs}
    if len(train_strengths) != len(train_pairs):
        raise ValueError("training strength signatures must be unique")
    if len(held_strengths) != len(held_out_pairs):
        raise ValueError("held-out strength signatures must be unique")
    if not train_strengths.isdisjoint(held_strengths):
        raise ValueError("training and held-out strength signatures overlap")

    for split_name, pairs, episodes in (
        ("training", train_pairs, train_episodes),
        ("held-out", held_out_pairs, held_out_episodes),
    ):
        by_pair: dict[str, list[Episode]] = {}
        for episode in episodes:
            by_pair.setdefault(episode.pair_id, []).append(episode)
            if episode.label not in {"FAST", "SLOW"}:
                raise ValueError(f"{split_name}: unsupported class label")
            if len(episode.gaps_ms) != 2 or len(episode.strengths) != 3:
                raise ValueError(f"{split_name}: malformed three-pulse episode")
            if any(not 0.8 <= value <= 1.0 for value in episode.strengths):
                raise ValueError(f"{split_name}: strength outside [0.8, 1.0]")
            if sum(episode.gaps_ms) > DECISION_DEADLINE_MS:
                raise ValueError(f"{split_name}: a pulse occurs after the deadline")
            if episode.label == "FAST" and episode.gaps_ms not in FAST_GAP_PAIRS:
                raise ValueError(f"{split_name}: FAST timing outside frozen protocol")
            if episode.label == "SLOW" and episode.gaps_ms not in SLOW_GAP_PAIRS:
                raise ValueError(f"{split_name}: SLOW timing outside frozen protocol")

        if len(by_pair) != len(pairs):
            raise ValueError(f"{split_name}: pair identifiers are not unique")
        for pair_id, members in by_pair.items():
            if len(members) != 2:
                raise ValueError(f"{split_name}: pair must contain FAST and SLOW")
            if {item.label for item in members} != {"FAST", "SLOW"}:
                raise ValueError(f"{split_name}: pair labels are not balanced")
            if members[0].strengths != members[1].strengths:
                raise ValueError(f"{split_name}: paired strengths differ")
            if members[0].pair_id != pair_id or members[1].pair_id != pair_id:
                raise ValueError(f"{split_name}: pair identifier mismatch")


def _build_dataset(
    seed: int,
    train_pairs: int = TRAIN_PAIRS,
    held_out_pairs: int = HELD_OUT_PAIRS,
) -> tuple[tuple[Episode, ...], tuple[Episode, ...]]:
    if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
        raise ValueError("seed must be a non-negative integer")
    if train_pairs <= 0 or held_out_pairs <= 0:
        raise ValueError("train_pairs and held_out_pairs must be positive")

    seen_strengths: set[tuple[float, float, float]] = set()
    train_pair_rows = _generate_pairs(seed, "train", train_pairs, seen_strengths)
    held_pair_rows = _generate_pairs(seed, "held-out", held_out_pairs, seen_strengths)
    train = _expand_pairs(train_pair_rows, seed, "train")
    held_out = _expand_pairs(held_pair_rows, seed, "held-out")
    _validate_pairs(
        train_pair_rows, held_pair_rows, train, held_out,
        train_pairs, held_out_pairs,
    )
    return train, held_out


def _empty_counts() -> dict[str, int]:
    return {"tp": 0, "fp": 0, "tn": 0, "fn": 0}


def _add_prediction(counts: dict[str, int], label: str, prediction: str) -> None:
    if label == "FAST" and prediction == "FAST":
        counts["tp"] += 1
    elif label == "SLOW" and prediction == "FAST":
        counts["fp"] += 1
    elif label == "SLOW" and prediction == "SLOW":
        counts["tn"] += 1
    else:
        counts["fn"] += 1


def _metrics(counts: dict[str, int]) -> dict[str, float | int]:
    tp, fp, tn, fn = (counts[key] for key in ("tp", "fp", "tn", "fn"))
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


def _pulse_times_ms(episode: Episode) -> tuple[int, int, int]:
    return (0, episode.gaps_ms[0], sum(episode.gaps_ms))


def _run_episode(
    episode: Episode,
    episode_index: int,
    mode: str,
    synapse_state: SynapseState,
    reward_baseline: float,
    learning_context_id: str,
    training: bool,
) -> tuple[dict[str, object], SynapseState, float, dict[str, int | float]]:
    if mode not in CONDITION_MODES:
        raise ValueError(f"unsupported V4 condition: {mode}")

    neuron_params = starter_neuron_params()
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()
    start_ns = episode_index * EPISODE_SPACING_MS * NS_PER_MS
    pulse_times = _pulse_times_ms(episode)
    if pulse_times[-1] > DECISION_DEADLINE_MS:
        raise ValueError("all three V4 input pulses must precede the decision deadline")

    neuron_state = NeuronState(last_update_model_time_ns=start_ns)
    spike_times_ms: list[int] = []
    pulse_stats: dict[str, int | float] = {
        "plasticity_event_count": 0,
        "positive_q_count": 0,
        "negative_q_count": 0,
        "positive_local_weight_delta_count": 0,
        "negative_local_weight_delta_count": 0,
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
                source_id="v4_temporal_input",
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

        if training and mode == "stdp":
            plasticity = update_stdp(
                synapse_state,
                synapse_params,
                time_ns,
                n_pre=1,
                n_post=n_post,
            )
            synapse_state = plasticity.state
            pulse_stats["plasticity_event_count"] += 1
            pulse_stats["positive_q_count"] += int(plasticity.q_bounded > 0.0)
            pulse_stats["negative_q_count"] += int(plasticity.q_bounded < 0.0)
            pulse_stats["positive_local_weight_delta_count"] += int(
                plasticity.weight_delta > 0.0
            )
            pulse_stats["negative_local_weight_delta_count"] += int(
                plasticity.weight_delta < 0.0
            )
            pulse_stats["local_weight_delta_sum"] += plasticity.weight_delta

        elif training and mode in R_STDP_MODES:
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
            pulse_stats["plasticity_event_count"] += 1
            pulse_stats["positive_q_count"] += int(plasticity.q_bounded > 0.0)
            pulse_stats["negative_q_count"] += int(plasticity.q_bounded < 0.0)

    decision_time_ns = start_ns + DECISION_DEADLINE_MS * NS_PER_MS
    decision_step = process_neuron_batch(
        neuron_state, decision_time_ns, [], neuron_params
    )
    neuron_state = decision_step.state
    prediction = "FAST" if spike_times_ms else "SLOW"
    correct = prediction == episode.label

    weight_before_feedback = synapse_state.weight
    feedback_record = None
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
        feedback_record = {
            "reward_value": reward_value,
            "baseline_before": baseline_before,
            "baseline_after": reward_baseline,
            "modulator": reward_step.modulator,
            "eligibility_at_feedback": eligibility_at_feedback,
            "weight_delta": reward_step.weight_delta,
            "weight_after": synapse_state.weight,
            "status": reward_step.status,
        }

    record: dict[str, object] = {
        "episode_id": episode.episode_id,
        "pair_id": episode.pair_id,
        "label": episode.label,
        "prediction": prediction,
        "correct": correct,
        "pulse_times_ms": list(pulse_times),
        "observed_input_pulses": len(pulse_times),
        "spike_times_ms": spike_times_ms,
        "weight_before_episode": weight_before_episode,
        "weight_before_feedback": weight_before_feedback,
        "weight_after_episode": synapse_state.weight,
        "feedback": feedback_record,
    }
    return record, synapse_state, reward_baseline, pulse_stats


def _evaluate(
    episodes: tuple[Episode, ...],
    weight: float,
    start_index: int = 0,
) -> dict[str, float | int]:
    state = SynapseState(weight=weight)
    baseline = REWARD_BASELINE_INITIAL
    counts = _empty_counts()
    for offset, episode in enumerate(episodes):
        record, state, baseline, _ = _run_episode(
            episode,
            start_index + offset,
            "fixed",
            state,
            baseline,
            f"v4-eval-{episode.episode_id}",
            training=False,
        )
        _add_prediction(counts, episode.label, str(record["prediction"]))
    if state.weight != weight:
        raise AssertionError("inference-only evaluation changed the synaptic weight")
    return _metrics(counts)


def _new_training_diagnostics() -> dict[str, int | float]:
    return {
        "plasticity_event_count": 0,
        "positive_q_count": 0,
        "negative_q_count": 0,
        "positive_local_weight_delta_count": 0,
        "negative_local_weight_delta_count": 0,
        "local_weight_delta_sum": 0.0,
        "reward_event_count": 0,
        "correct_outcome_count": 0,
        "incorrect_outcome_count": 0,
        "positive_modulator_count": 0,
        "negative_modulator_count": 0,
        "zero_modulator_count": 0,
        "positive_eligibility_count": 0,
        "negative_eligibility_count": 0,
        "zero_eligibility_count": 0,
        "positive_reward_weight_delta_count": 0,
        "negative_reward_weight_delta_count": 0,
        "zero_reward_weight_delta_count": 0,
        "reward_weight_delta_sum": 0.0,
        "false_positive_negative_weight_update_count": 0,
        "false_negative_positive_weight_update_count": 0,
        "eligibility_modulator_product_sign_match_count": 0,
    }


def _accumulate_diagnostics(
    diagnostics: dict[str, int | float],
    pulse_stats: dict[str, int | float],
    record: dict[str, object],
) -> None:
    for key in (
        "plasticity_event_count",
        "positive_q_count",
        "negative_q_count",
        "positive_local_weight_delta_count",
        "negative_local_weight_delta_count",
    ):
        diagnostics[key] += int(pulse_stats[key])
    diagnostics["local_weight_delta_sum"] += float(pulse_stats["local_weight_delta_sum"])

    feedback = record["feedback"]
    if feedback is None:
        return
    assert isinstance(feedback, dict)
    diagnostics["reward_event_count"] += 1
    diagnostics["correct_outcome_count"] += int(bool(record["correct"]))
    diagnostics["incorrect_outcome_count"] += int(not bool(record["correct"]))
    modulator = float(feedback["modulator"])
    eligibility = float(feedback["eligibility_at_feedback"])
    delta = float(feedback["weight_delta"])
    diagnostics["positive_modulator_count"] += int(modulator > 0.0)
    diagnostics["negative_modulator_count"] += int(modulator < 0.0)
    diagnostics["zero_modulator_count"] += int(modulator == 0.0)
    diagnostics["positive_eligibility_count"] += int(eligibility > 0.0)
    diagnostics["negative_eligibility_count"] += int(eligibility < 0.0)
    diagnostics["zero_eligibility_count"] += int(eligibility == 0.0)
    diagnostics["positive_reward_weight_delta_count"] += int(delta > 0.0)
    diagnostics["negative_reward_weight_delta_count"] += int(delta < 0.0)
    diagnostics["zero_reward_weight_delta_count"] += int(delta == 0.0)
    diagnostics["reward_weight_delta_sum"] += delta

    if record["label"] == "SLOW" and record["prediction"] == "FAST" and delta < 0.0:
        diagnostics["false_positive_negative_weight_update_count"] += 1
    if record["label"] == "FAST" and record["prediction"] == "SLOW" and delta > 0.0:
        diagnostics["false_negative_positive_weight_update_count"] += 1
    product = modulator * eligibility
    if (product > 0.0 and delta > 0.0) or (product < 0.0 and delta < 0.0):
        diagnostics["eligibility_modulator_product_sign_match_count"] += 1


def _run_condition(
    seed: int,
    mode: str,
    train: tuple[Episode, ...],
    held_out: tuple[Episode, ...],
    diagnostic_initial_weight: float = DIAGNOSTIC_INITIAL_WEIGHT,
) -> dict[str, object]:
    if mode not in CONDITION_MODES:
        raise ValueError(f"unsupported V4 condition: {mode}")

    synapse_state = SynapseState(weight=diagnostic_initial_weight)
    reward_baseline = REWARD_BASELINE_INITIAL
    train_counts = _empty_counts()
    held_counts = _empty_counts()
    diagnostics = _new_training_diagnostics()
    first_errors: list[dict[str, object]] = []
    trajectory: list[dict[str, object]] = []
    window_counts = _empty_counts()
    checkpoint_interval = max(2, (len(train) // TRAJECTORY_CHECKPOINTS) // 2 * 2)

    for index, episode in enumerate(train):
        record, synapse_state, reward_baseline, pulse_stats = _run_episode(
            episode,
            index,
            mode,
            synapse_state,
            reward_baseline,
            f"v4-{seed}-{mode}-{episode.episode_id}",
            training=True,
        )
        _add_prediction(train_counts, episode.label, str(record["prediction"]))
        _add_prediction(window_counts, episode.label, str(record["prediction"]))
        _accumulate_diagnostics(diagnostics, pulse_stats, record)

        if not record["correct"] and len(first_errors) < MAX_FIRST_ERRORS:
            feedback = record["feedback"]
            first_errors.append({
                "episode_id": record["episode_id"],
                "label": record["label"],
                "prediction": record["prediction"],
                "pulse_times_ms": record["pulse_times_ms"],
                "spike_times_ms": record["spike_times_ms"],
                "weight_before_episode": record["weight_before_episode"],
                "reward_value": None if feedback is None else feedback["reward_value"],
                "baseline_before": None if feedback is None else feedback["baseline_before"],
                "modulator": None if feedback is None else feedback["modulator"],
                "eligibility_at_feedback": (
                    None if feedback is None else feedback["eligibility_at_feedback"]
                ),
                "weight_delta": None if feedback is None else feedback["weight_delta"],
                "weight_after": record["weight_after_episode"],
            })

        if (index + 1) % checkpoint_interval == 0:
            trajectory.append({
                "training_episodes": index + 1,
                "weight": synapse_state.weight,
                "reward_baseline": reward_baseline,
                "window_metrics": _metrics(window_counts),
            })
            window_counts = _empty_counts()

    weight_after_training = synapse_state.weight
    held_before = synapse_state.weight
    for offset, episode in enumerate(held_out):
        record, synapse_state, reward_baseline, _ = _run_episode(
            episode,
            len(train) + offset,
            mode,
            synapse_state,
            reward_baseline,
            f"v4-heldout-{seed}-{episode.episode_id}",
            training=False,
        )
        _add_prediction(held_counts, episode.label, str(record["prediction"]))

    weight_after_held_out = synapse_state.weight
    return {
        "condition": mode,
        "seed": seed,
        "initial_weight": diagnostic_initial_weight,
        "weight_after_training": weight_after_training,
        "weight_delta": weight_after_training - diagnostic_initial_weight,
        "weight_after_held_out": weight_after_held_out,
        "held_out_weights_frozen": weight_after_held_out == held_before,
        "reward_baseline_after_training": (
            reward_baseline if mode in R_STDP_MODES else None
        ),
        "train": _metrics(train_counts),
        "held_out": _metrics(held_counts),
        "training_diagnostics": diagnostics,
        "first_errors": first_errors,
        "weight_trajectory": trajectory,
    }


def _signed_eligibility_probe() -> dict[str, object]:
    """Exercise naturally generated positive/negative eligibility signs.

    A pre event followed by a post event creates positive eligibility. Reversing
    their order creates negative eligibility. The scalar reward is then tested
    with both signs and with a neutral modulator.
    """
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()
    rows: list[dict[str, object]] = []

    for eligibility_name in ("positive", "negative"):
        for feedback_name, reward_value in (
            ("positive", 1.0),
            ("negative", 0.0),
            ("neutral", 0.5),
        ):
            state = SynapseState(weight=CANONICAL_STARTER_WEIGHT)
            context_id = f"v4-sign-probe-{eligibility_name}-{feedback_name}"
            if eligibility_name == "positive":
                first_counts = (1, 0)
                second_counts = (0, 1)
            else:
                first_counts = (0, 1)
                second_counts = (1, 0)

            first = update_rstdp(
                state, synapse_params, reward_params,
                0, first_counts[0], first_counts[1], context_id,
            )
            second = update_rstdp(
                first.state, synapse_params, reward_params,
                2 * NS_PER_MS, second_counts[0], second_counts[1], context_id,
            )
            eligibility = second.state.eligibility_trace
            feedback = apply_reward(
                second.state,
                synapse_params,
                reward_params,
                3 * NS_PER_MS,
                reward_value,
                REWARD_BASELINE_INITIAL,
                context_id,
            )
            rows.append({
                "eligibility_case": eligibility_name,
                "feedback_case": feedback_name,
                "eligibility_before_feedback": eligibility,
                "reward_value": reward_value,
                "baseline_before": REWARD_BASELINE_INITIAL,
                "modulator": feedback.modulator,
                "weight_before": CANONICAL_STARTER_WEIGHT,
                "weight_delta": feedback.weight_delta,
                "weight_after": feedback.state.weight,
                "status": feedback.status,
            })

    no_trace = apply_reward(
        SynapseState(weight=CANONICAL_STARTER_WEIGHT),
        synapse_params,
        reward_params,
        0,
        0.0,
        REWARD_BASELINE_INITIAL,
        None,
    )
    return {
        "scope": "isolated_mathematical_sign_probe_not_task_level_learning",
        "rows": rows,
        "no_eligibility_control": {
            "status": no_trace.status,
            "weight_delta": no_trace.weight_delta,
            "weight_after": no_trace.state.weight,
            "modulator": no_trace.modulator,
        },
    }


def _summary_stats(values: list[float]) -> dict[str, float | int]:
    return {
        "runs": len(values),
        "mean": statistics.fmean(values) if values else 0.0,
        "sample_stdev": statistics.stdev(values) if len(values) > 1 else 0.0,
        "min": min(values) if values else 0.0,
        "max": max(values) if values else 0.0,
    }


def _aggregate_conditions(runs: list[dict[str, object]]) -> list[dict[str, object]]:
    summaries: list[dict[str, object]] = []
    for mode in CONDITION_MODES:
        per_seed = [
            next(item for item in run["conditions"] if item["condition"] == mode)
            for run in runs
        ]
        summary: dict[str, object] = {
            "condition": mode,
            "train": _metrics({
                key: sum(item["train"][key] for item in per_seed)
                for key in ("tp", "fp", "tn", "fn")
            }),
            "held_out": _metrics({
                key: sum(item["held_out"][key] for item in per_seed)
                for key in ("tp", "fp", "tn", "fn")
            }),
            "held_out_accuracy_across_runs": _summary_stats([
                float(item["held_out"]["accuracy"]) for item in per_seed
            ]),
            "held_out_balanced_accuracy_across_runs": _summary_stats([
                float(item["held_out"]["balanced_accuracy"]) for item in per_seed
            ]),
            "weight_after_training_by_seed": [
                {
                    "seed": item["seed"],
                    "weight": item["weight_after_training"],
                    "delta": item["weight_delta"],
                }
                for item in per_seed
            ],
        }
        diagnostic_keys = tuple(per_seed[0]["training_diagnostics"])
        summary["training_diagnostics"] = {
            key: sum(item["training_diagnostics"][key] for item in per_seed)
            for key in diagnostic_keys
        }
        summaries.append(summary)
    return summaries


def run_benchmark(
    train_pairs: int = TRAIN_PAIRS,
    held_out_pairs: int = HELD_OUT_PAIRS,
    seeds: tuple[int, ...] = RUN_SEEDS,
    progress: bool = False,
) -> dict[str, object]:
    if not seeds or len(set(seeds)) != len(seeds):
        raise ValueError("seeds must be a non-empty sequence of unique integers")
    runs: list[dict[str, object]] = []
    starter_rows: list[dict[str, object]] = []
    diagnostic_initial_rows: list[dict[str, object]] = []

    total_conditions = len(seeds) * len(CONDITION_MODES)
    completed_conditions = 0
    for seed_index, seed in enumerate(seeds, start=1):
        if progress:
            print(f"[V4] seed {seed_index}/{len(seeds)} ({seed}): generating dataset", file=sys.stderr, flush=True)
        train, held_out = _build_dataset(seed, train_pairs, held_out_pairs)
        starter_metrics = _evaluate(
            held_out, CANONICAL_STARTER_WEIGHT, start_index=len(train)
        )
        initial_diagnostic_metrics = _evaluate(
            held_out, DIAGNOSTIC_INITIAL_WEIGHT, start_index=len(train)
        )
        starter_rows.append({"seed": seed, **starter_metrics})
        diagnostic_initial_rows.append({"seed": seed, **initial_diagnostic_metrics})

        conditions = []
        for mode in CONDITION_MODES:
            if progress:
                print(f"[V4] condition {completed_conditions + 1}/{total_conditions}: seed={seed}, mode={mode}, train_episodes={len(train)}, held_out_episodes={len(held_out)}", file=sys.stderr, flush=True)
            conditions.append(_run_condition(seed, mode, train, held_out))
            completed_conditions += 1
            if progress:
                print(f"[V4] completed {completed_conditions}/{total_conditions}: seed={seed}, mode={mode}", file=sys.stderr, flush=True)
        runs.append({
            "seed": seed,
            "dataset": {
                "training_pairs": train_pairs,
                "held_out_pairs": held_out_pairs,
                "training_episodes": len(train),
                "held_out_episodes": len(held_out),
                "training_fast": sum(item.label == "FAST" for item in train),
                "training_slow": sum(item.label == "SLOW" for item in train),
                "held_out_fast": sum(item.label == "FAST" for item in held_out),
                "held_out_slow": sum(item.label == "SLOW" for item in held_out),
                "train_held_out_strength_signatures_disjoint": True,
                "paired_strength_sequences_identical": True,
                "all_three_pulses_before_deadline": True,
            },
            "starter_baseline_0_5": starter_metrics,
            "diagnostic_initial_0_70": initial_diagnostic_metrics,
            "conditions": conditions,
        })

    aggregate = _aggregate_conditions(runs)
    by_seed = {run["seed"]: run for run in runs}
    reward_effect_by_seed = []
    for seed in seeds:
        conditions = by_seed[seed]["conditions"]
        fixed = next(item for item in conditions if item["condition"] == "fixed")
        rewarded = next(item for item in conditions if item["condition"] == "rstdp")
        neutral = next(item for item in conditions if item["condition"] == "rstdp_neutral")
        stdp = next(item for item in conditions if item["condition"] == "stdp")
        reward_effect_by_seed.append({
            "seed": seed,
            "rstdp_minus_fixed_held_out_accuracy": (
                rewarded["held_out"]["accuracy"] - fixed["held_out"]["accuracy"]
            ),
            "rstdp_minus_neutral_held_out_accuracy": (
                rewarded["held_out"]["accuracy"] - neutral["held_out"]["accuracy"]
            ),
            "rstdp_minus_stdp_held_out_accuracy": (
                rewarded["held_out"]["accuracy"] - stdp["held_out"]["accuracy"]
            ),
            "rstdp_weight_delta": rewarded["weight_delta"],
            "neutral_weight_delta": neutral["weight_delta"],
        })

    return {
        "benchmark": "JARVIS_R_STDP_FEEDBACK_ERROR_RECOVERY_V4",
        "status": "REFERENCE_EXPERIMENT_NOT_PRODUCTION_RUNTIME",
        "protocol": {
            "canonical_starter_weight": CANONICAL_STARTER_WEIGHT,
            "starter_profile_modified": False,
            "diagnostic_track": "DIAGNOSTIC_BIASED_INITIAL_WEIGHT_NOT_STARTER",
            "diagnostic_initial_weight": DIAGNOSTIC_INITIAL_WEIGHT,
            "all_conditions_share_diagnostic_initial_weight": True,
            "neuron": "JARVIS_SINGLE_NEURON_STARTER_V1 / Adaptive LIF-DT",
            "synapses": 1,
            "pulses_per_episode": 3,
            "decision_deadline_ms": DECISION_DEADLINE_MS,
            "fast_gap_pairs_ms": [list(item) for item in FAST_GAP_PAIRS],
            "slow_gap_pairs_ms": [list(item) for item in SLOW_GAP_PAIRS],
            "all_three_pulses_observed_for_both_classes": True,
            "decision_rule": "at least one output spike by the deadline => FAST; otherwise SLOW",
            "input_strength_range": [0.8, 1.0],
            "strengths_are_paired_nuisance_variables": True,
            "training_pairs_per_seed": train_pairs,
            "held_out_pairs_per_seed": held_out_pairs,
            "seeds": list(seeds),
            "conditions": list(CONDITION_MODES),
            "training_reward_policy": (
                "reward 1.0 for a correct training decision and 0.0 for an incorrect "
                "decision; reward baseline starts at 0.5 and uses the canonical "
                "STARTER R-STDP beta_b"
            ),
            "neutral_control_policy": (
                "same R-STDP eligibility path; reward equals the current baseline, "
                "so the reward modulator is exactly zero"
            ),
            "held_out_policy": "inference only; no plasticity, eligibility or reward updates",
            "dataset_generation": "SHA-256 deterministic, matched-strength pairs, train/held-out disjoint",
            "diagnostic_weight_rationale": (
                "0.70 is a predeclared high-bias diagnostic condition: FAST sequences "
                "tend to spike on the second input, while some SLOW sequences spike "
                "only on the third. This creates opposite-sign eligibility contexts "
                "and false-positive feedback without changing STARTER."
            ),
            "randomness": "none; deterministic dataset generation and ordering",
        },
        "signed_feedback_probe": _signed_eligibility_probe(),
        "starter_baseline": {
            "weight": CANONICAL_STARTER_WEIGHT,
            "scope": "held-out inference-only probe; no learning updates",
            "by_seed": starter_rows,
            "aggregate": _metrics({
                key: sum(row[key] for row in starter_rows)
                for key in ("tp", "fp", "tn", "fn")
            }),
        },
        "diagnostic_initial_baseline": {
            "weight": DIAGNOSTIC_INITIAL_WEIGHT,
            "scope": "same held-out episodes before any training",
            "by_seed": diagnostic_initial_rows,
            "aggregate": _metrics({
                key: sum(row[key] for row in diagnostic_initial_rows)
                for key in ("tp", "fp", "tn", "fn")
            }),
        },
        "aggregate_conditions": aggregate,
        "reward_effect": {
            "comparisons": [
                "R-STDP minus FIXED",
                "R-STDP minus neutral-reward R-STDP",
                "R-STDP minus STDP",
            ],
            "by_seed": reward_effect_by_seed,
            "rstdp_minus_fixed_accuracy": _summary_stats([
                float(row["rstdp_minus_fixed_held_out_accuracy"])
                for row in reward_effect_by_seed
            ]),
            "rstdp_minus_neutral_accuracy": _summary_stats([
                float(row["rstdp_minus_neutral_held_out_accuracy"])
                for row in reward_effect_by_seed
            ]),
        },
        "runs": runs,
        "interpretation_limits": [
            "The evaluator supplies an external scalar correctness reward; the neuron does not know the class label during inference.",
            "The signed probe verifies the numerical reward/eligibility sign rule, not learning or task-level error correction.",
            "A task-level correction claim requires improved held-out confusion metrics against FIXED and neutral R-STDP across seeds.",
            "STDP is retained as a direct local-learning comparator; it may outperform reward-modulated learning.",
            "One neuron and one excitatory synapse have limited credit-assignment capacity; failure is a valid result.",
            "The diagnostic initial weight 0.70 is not a calibrated parameter and does not replace canonical STARTER 0.5.",
            "This reference benchmark does not establish production runtime readiness, biological equivalence, consciousness or general intelligence.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the V4 R-STDP feedback/error-recovery benchmark.")
    parser.add_argument("--profile", choices=("full", "pilot", "smoke"), default="full")
    parser.add_argument("--train-pairs", type=int, default=None)
    parser.add_argument("--held-out-pairs", type=int, default=None)
    parser.add_argument("--seeds", type=int, nargs="+", default=None)
    args = parser.parse_args()
    profiles = {
        "full": (TRAIN_PAIRS, HELD_OUT_PAIRS, RUN_SEEDS),
        "pilot": (512, 128, RUN_SEEDS[:2]),
        "smoke": (128, 64, RUN_SEEDS[:2]),
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
