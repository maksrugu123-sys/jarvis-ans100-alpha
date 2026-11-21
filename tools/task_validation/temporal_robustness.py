"""Deterministic robustness benchmark for overlapping temporal patterns.

This is a reference experiment for one Adaptive LIF-DT neuron and one
excitatory synapse. It does not modify or calibrate the STARTER profile.
"""
from __future__ import annotations

import json
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


@dataclass(frozen=True)
class Episode:
    episode_id: str
    label: str
    gaps_ms: tuple[int, int]
    strengths: tuple[float, float, float]


# The two classes deliberately share part of their observed timing range.
# Strength values are nuisance variation, not a class-specific feature.
TRAIN_EPISODES = (
    Episode("train-burst-01", "BURST", (5, 6), (0.90, 0.90, 0.90)),
    Episode("train-sparse-01", "SPARSE", (8, 9), (0.90, 0.90, 0.90)),
    Episode("train-burst-02", "BURST", (6, 5), (0.95, 0.90, 0.90)),
    Episode("train-sparse-02", "SPARSE", (9, 8), (0.95, 0.90, 0.90)),
    Episode("train-burst-03", "BURST", (6, 6), (0.85, 0.90, 0.95)),
    Episode("train-sparse-03", "SPARSE", (8, 10), (0.85, 0.90, 0.95)),
    Episode("train-burst-04", "BURST", (7, 5), (0.90, 0.85, 0.95)),
    Episode("train-sparse-04", "SPARSE", (10, 8), (0.90, 0.85, 0.95)),
    Episode("train-burst-05", "BURST", (5, 7), (0.95, 0.85, 0.90)),
    Episode("train-sparse-05", "SPARSE", (9, 9), (0.95, 0.85, 0.90)),
    Episode("train-burst-06", "BURST", (7, 6), (0.90, 0.95, 0.85)),
    Episode("train-sparse-06", "SPARSE", (7, 10), (0.90, 0.95, 0.85)),
)

HELD_OUT_EPISODES = (
    Episode("held-burst-01", "BURST", (4, 7), (0.90, 0.95, 0.85)),
    Episode("held-sparse-01", "SPARSE", (10, 9), (0.90, 0.95, 0.85)),
    Episode("held-burst-02", "BURST", (7, 4), (0.85, 0.90, 0.95)),
    Episode("held-sparse-02", "SPARSE", (9, 10), (0.85, 0.90, 0.95)),
    Episode("held-burst-03", "BURST", (6, 7), (0.95, 0.85, 0.90)),
    Episode("held-sparse-03", "SPARSE", (8, 8), (0.95, 0.85, 0.90)),
    Episode("held-burst-04", "BURST", (7, 7), (0.90, 0.90, 0.95)),
    Episode("held-sparse-04", "SPARSE", (7, 9), (0.90, 0.90, 0.95)),
    Episode("held-burst-05", "BURST", (5, 8), (0.85, 0.95, 0.90)),
    Episode("held-sparse-05", "SPARSE", (10, 10), (0.85, 0.95, 0.90)),
)

ALL_EPISODES = TRAIN_EPISODES + HELD_OUT_EPISODES


def _validate_episode(episode: Episode) -> None:
    if not episode.episode_id:
        raise ValueError("episode_id must be non-empty")
    if episode.label not in {"BURST", "SPARSE"}:
        raise ValueError(f"unsupported label: {episode.label}")
    if len(episode.gaps_ms) != 2 or any(
        isinstance(gap, bool) or not isinstance(gap, int) or gap <= 0
        for gap in episode.gaps_ms
    ):
        raise ValueError("each episode must contain two positive integer gaps")
    if len(episode.strengths) != 3 or any(
        not 0.0 <= strength <= 1.0 for strength in episode.strengths
    ):
        raise ValueError("each episode must contain three strengths in [0, 1]")


def _validate_protocol() -> None:
    for episode in ALL_EPISODES:
        _validate_episode(episode)

    ids = [episode.episode_id for episode in ALL_EPISODES]
    if len(ids) != len(set(ids)):
        raise ValueError("episode IDs must be unique")

    def signature(episode: Episode) -> tuple[tuple[int, int], tuple[float, float, float]]:
        return episode.gaps_ms, episode.strengths

    train_signatures = {signature(episode) for episode in TRAIN_EPISODES}
    held_out_signatures = {signature(episode) for episode in HELD_OUT_EPISODES}
    if not train_signatures.isdisjoint(held_out_signatures):
        raise ValueError("training and held-out input sequences must be disjoint")

    for episodes, name in (
        (TRAIN_EPISODES, "training"),
        (HELD_OUT_EPISODES, "held-out"),
    ):
        labels = [episode.label for episode in episodes]
        if labels.count("BURST") != labels.count("SPARSE"):
            raise ValueError(f"{name} split must be class-balanced")


def _metrics(rows: Iterable[dict[str, object]]) -> dict[str, float | int]:
    tp = fp = tn = fn = 0
    for row in rows:
        expected_burst = row["label"] == "BURST"
        predicted_burst = row["prediction"] == "BURST"
        if expected_burst and predicted_burst:
            tp += 1
        elif not expected_burst and predicted_burst:
            fp += 1
        elif not expected_burst and not predicted_burst:
            tn += 1
        else:
            fn += 1

    total = tp + fp + tn + fn
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    specificity = tn / (tn + fp) if tn + fp else 0.0
    return {
        "episodes": total,
        "correct": tp + tn,
        "accuracy": (tp + tn) / total if total else 0.0,
        "precision_burst": precision,
        "recall_burst": recall,
        "specificity_sparse": specificity,
        "balanced_accuracy": (recall + specificity) / 2.0,
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
) -> tuple[dict[str, object], SynapseState, float]:
    if mode not in {"fixed", "stdp", "rstdp"}:
        raise ValueError(f"unsupported condition: {mode}")

    neuron_params = starter_neuron_params()
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()

    # Each episode starts with a rested neuron. Synaptic state persists.
    start_ns = episode_index * EPISODE_SPACING_MS * NS_PER_MS
    pulse_times_ns = (
        start_ns,
        start_ns + episode.gaps_ms[0] * NS_PER_MS,
        start_ns + sum(episode.gaps_ms) * NS_PER_MS,
    )
    neuron_state = NeuronState(last_update_model_time_ns=start_ns)
    pulse_spikes: list[bool] = []
    weight_before_episode = synapse_state.weight

    for time_ns, strength in zip(pulse_times_ns, episode.strengths):
        step = process_neuron_batch(
            neuron_state,
            time_ns,
            [InputSignal(
                source_id="robustness_input",
                signed_weight=synapse_state.weight,
                strength=strength,
                attention=0.0,
            )],
            neuron_params,
        )
        neuron_state = step.state
        n_post = int(step.spike_emitted)
        pulse_spikes.append(step.spike_emitted)

        if mode == "stdp":
            synapse_state = update_stdp(
                synapse_state,
                synapse_params,
                time_ns,
                n_pre=1,
                n_post=n_post,
            ).state
        elif mode == "rstdp":
            synapse_state = update_rstdp(
                synapse_state,
                synapse_params,
                reward_params,
                time_ns,
                n_pre=1,
                n_post=n_post,
                learning_context_id=learning_context_id,
            ).state

    spike_count = sum(int(spike) for spike in pulse_spikes)
    predicted_burst = spike_count > 0
    correct = predicted_burst == (episode.label == "BURST")
    reward_value: float | None = None

    if mode == "rstdp":
        # The evaluator sends reward only after observing the complete episode.
        reward_value = 1.0 if correct else 0.0
        reward_time_ns = pulse_times_ns[-1] + NS_PER_MS
        reward_step = apply_reward(
            synapse_state,
            synapse_params,
            reward_params,
            reward_time_ns,
            reward_value,
            reward_baseline,
            learning_context_id,
        )
        synapse_state = reward_step.state
        reward_baseline = reward_step.baseline_after

    record = {
        "episode_id": episode.episode_id,
        "label": episode.label,
        "gaps_ms": list(episode.gaps_ms),
        "strengths": list(episode.strengths),
        "spike_emitted_per_pulse": pulse_spikes,
        "output_spike_count": spike_count,
        "prediction": "BURST" if predicted_burst else "SPARSE",
        "correct": correct,
        "weight_before_episode": weight_before_episode,
        "weight_after_episode": synapse_state.weight,
        "reward_value": reward_value,
    }
    return record, synapse_state, reward_baseline


def _run_condition(mode: str) -> dict[str, object]:
    synapse_state = SynapseState(weight=INITIAL_WEIGHT)
    reward_baseline = 0.5
    initial_weight = synapse_state.weight
    train_records: list[dict[str, object]] = []
    weight_trajectory: list[dict[str, float | str]] = []

    for index, episode in enumerate(TRAIN_EPISODES):
        weight_before = synapse_state.weight
        record, synapse_state, reward_baseline = _run_episode(
            episode,
            index,
            mode,
            synapse_state,
            reward_baseline,
            f"robustness-train-{index:03d}",
        )
        train_records.append(record)
        weight_trajectory.append({
            "episode_id": episode.episode_id,
            "weight_before": weight_before,
            "weight_after": synapse_state.weight,
            "weight_delta": synapse_state.weight - weight_before,
        })

    weight_after_training = synapse_state.weight
    held_out_records: list[dict[str, object]] = []

    for index, episode in enumerate(HELD_OUT_EPISODES):
        # Held-out episodes are inference-only for every condition.
        record, synapse_state, reward_baseline = _run_episode(
            episode,
            len(TRAIN_EPISODES) + index,
            "fixed",
            synapse_state,
            reward_baseline,
            f"robustness-held-{index:03d}",
        )
        held_out_records.append(record)

    weight_after_held_out = synapse_state.weight
    return {
        "condition": mode,
        "initial_weight": initial_weight,
        "weight_after_training": weight_after_training,
        "weight_delta": weight_after_training - initial_weight,
        "weight_after_held_out": weight_after_held_out,
        "held_out_weights_frozen": weight_after_held_out == weight_after_training,
        "reward_baseline_after_training": (
            reward_baseline if mode == "rstdp" else None
        ),
        "train": _metrics(train_records),
        "held_out": _metrics(held_out_records),
        "training_weight_trajectory": weight_trajectory,
        "training_episode_results": train_records,
        "held_out_episode_results": held_out_records,
    }


def run_benchmark() -> dict[str, object]:
    """Run FIXED, STDP and R-STDP on one deterministic overlapping task."""
    _validate_protocol()
    conditions = [
        _run_condition("fixed"),
        _run_condition("stdp"),
        _run_condition("rstdp"),
    ]
    fixed = conditions[0]
    fixed_accuracy = fixed["held_out"]["accuracy"]
    fixed_correct = fixed["held_out"]["correct"]
    for condition in conditions:
        condition["held_out_comparison_vs_fixed"] = {
            "accuracy_delta": condition["held_out"]["accuracy"] - fixed_accuracy,
            "correct_episode_delta": condition["held_out"]["correct"] - fixed_correct,
        }

    burst_gaps = [
        gap
        for episode in ALL_EPISODES if episode.label == "BURST"
        for gap in episode.gaps_ms
    ]
    sparse_gaps = [
        gap
        for episode in ALL_EPISODES if episode.label == "SPARSE"
        for gap in episode.gaps_ms
    ]

    return {
        "benchmark": "JARVIS_TEMPORAL_ROBUSTNESS_V1",
        "status": "REFERENCE_EXPERIMENT_NOT_PRODUCTION_RUNTIME",
        "task": (
            "Classify three-pulse episodes as BURST or SPARSE using the fixed "
            "rule that one or more output spikes means BURST. Timing bands "
            "partially overlap and pulse strengths vary within the same range."
        ),
        "protocol": {
            "neuron": "JARVIS_SINGLE_NEURON_STARTER_V1 / Adaptive LIF-DT",
            "synapses": 1,
            "initial_weight": INITIAL_WEIGHT,
            "pulses_per_episode": 3,
            "training_episodes": len(TRAIN_EPISODES),
            "held_out_episodes": len(HELD_OUT_EPISODES),
            "training_class_counts": {
                label: sum(episode.label == label for episode in TRAIN_EPISODES)
                for label in ("BURST", "SPARSE")
            },
            "held_out_class_counts": {
                label: sum(episode.label == label for episode in HELD_OUT_EPISODES)
                for label in ("BURST", "SPARSE")
            },
            "observed_gap_ranges_ms": {
                "BURST": [min(burst_gaps), max(burst_gaps)],
                "SPARSE": [min(sparse_gaps), max(sparse_gaps)],
                "overlap": [
                    max(min(burst_gaps), min(sparse_gaps)),
                    min(max(burst_gaps), max(sparse_gaps)),
                ],
            },
            "pulse_strength_values": sorted({
                strength
                for episode in ALL_EPISODES
                for strength in episode.strengths
            }),
            "timing_and_strength_variation": (
                "Explicit deterministic per-episode gap pairs and per-pulse "
                "strengths; no random sampling."
            ),
            "episode_spacing_ms": EPISODE_SPACING_MS,
            "neuron_state": "reset to rested state at each episode; synaptic state persists",
            "decision_rule": "output_spike_count > 0 => BURST; otherwise SPARSE",
            "learning": {
                "fixed": "no synaptic updates",
                "stdp": "pair-based excitatory STDP after each pulse; no class label supplied",
                "rstdp": (
                    "eligibility updates per pulse; external evaluator sends "
                    "reward 1.0 for correct classification, 0.0 otherwise"
                ),
            },
            "test_policy": "all held-out episodes use FIXED inference; no STDP or reward updates",
            "train_held_out_input_signatures_disjoint": True,
            "same_episodes_for_all_conditions": True,
            "randomness": "none; all episodes are explicit constants",
            "starter_profile_modified": False,
        },
        "conditions": conditions,
        "interpretation_limits": [
            "This is one reference neuron and one excitatory synapse, not a general classifier.",
            "Timing ranges overlap, so some inputs are intrinsically ambiguous under the episode labels.",
            "Per-pulse strength variation is a nuisance factor shared by both classes, not the intended class feature.",
            "The held-out split is a fixed deterministic challenge set, not a statistical estimate with confidence intervals.",
            "STDP does not receive class labels; R-STDP receives an evaluator reward derived from classification correctness.",
            "A poor result can reflect model capacity, a decision-rule limitation, or learning behavior; these must be analyzed separately.",
            "The STARTER profile is not optimized, calibrated, or modified by this benchmark.",
            "This Python oracle experiment is not production runtime validation or a biological-equivalence claim.",
        ],
    }


def main() -> None:
    print(json.dumps(run_benchmark(), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
