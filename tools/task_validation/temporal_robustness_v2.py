"""Multi-seed robustness benchmark for overlapping temporal patterns.

This reference experiment compares a fixed one-neuron baseline, STDP, rewarded
R-STDP, and a neutral-reward R-STDP control. It does not modify or calibrate
the STARTER profile.
"""
from __future__ import annotations

import hashlib
import json
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
RUN_SEEDS = (104729, 130363, 155921, 181081)
TRAIN_PER_CLASS = 16
HELD_OUT_PER_CLASS = 20
BURST_GAP_RANGE_MS = (4, 8)
SPARSE_GAP_RANGE_MS = (7, 11)
STRENGTH_LEVELS = (0.80, 0.85, 0.90, 0.95, 1.00)
CONDITION_MODES = ("fixed", "stdp", "rstdp", "rstdp_neutral")


@dataclass(frozen=True)
class Episode:
    episode_id: str
    label: str
    gaps_ms: tuple[int, int]
    strengths: tuple[float, float, float]


def _signature(episode: Episode) -> tuple[tuple[int, int], tuple[float, float, float]]:
    return episode.gaps_ms, episode.strengths


def _candidate_episode(
    seed: int,
    split: str,
    label: str,
    index: int,
    attempt: int,
) -> Episode:
    """Create one repeatable sample from a seed/split/class-specific digest."""
    material = (
        f"JARVIS_TEMPORAL_ROBUSTNESS_V2|{seed}|{split}|{label}|{index}|{attempt}"
    )
    digest = hashlib.sha256(material.encode("utf-8")).digest()
    gap_low, gap_high = (
        BURST_GAP_RANGE_MS if label == "BURST" else SPARSE_GAP_RANGE_MS
    )
    gap_span = gap_high - gap_low + 1
    gaps = (
        gap_low + digest[0] % gap_span,
        gap_low + digest[1] % gap_span,
    )
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


def _generate_split(
    seed: int,
    split: str,
    per_class: int,
    seen_signatures: set[tuple[tuple[int, int], tuple[float, float, float]]],
) -> tuple[Episode, ...]:
    episodes: list[Episode] = []
    for label in ("BURST", "SPARSE"):
        for index in range(per_class):
            for attempt in range(10_000):
                episode = _candidate_episode(seed, split, label, index, attempt)
                signature = _signature(episode)
                if signature not in seen_signatures:
                    seen_signatures.add(signature)
                    episodes.append(episode)
                    break
            else:
                raise RuntimeError("could not generate a unique episode signature")
    return tuple(episodes)


def _validate_dataset(
    train_episodes: tuple[Episode, ...],
    held_out_episodes: tuple[Episode, ...],
) -> None:
    all_episodes = train_episodes + held_out_episodes
    ids = [episode.episode_id for episode in all_episodes]
    if len(ids) != len(set(ids)):
        raise ValueError("episode IDs must be unique")

    signatures = [_signature(episode) for episode in all_episodes]
    if len(signatures) != len(set(signatures)):
        raise ValueError("full input signatures must be unique across both splits")

    train_signatures = {_signature(episode) for episode in train_episodes}
    held_signatures = {_signature(episode) for episode in held_out_episodes}
    if not train_signatures.isdisjoint(held_signatures):
        raise ValueError("training and held-out signatures must be disjoint")

    for episodes, expected_per_class, name in (
        (train_episodes, TRAIN_PER_CLASS, "training"),
        (held_out_episodes, HELD_OUT_PER_CLASS, "held-out"),
    ):
        if len(episodes) != expected_per_class * 2:
            raise ValueError(f"{name} split has an unexpected episode count")
        labels = [episode.label for episode in episodes]
        if labels.count("BURST") != expected_per_class:
            raise ValueError(f"{name} BURST count is not balanced")
        if labels.count("SPARSE") != expected_per_class:
            raise ValueError(f"{name} SPARSE count is not balanced")

    for episode in all_episodes:
        if episode.label not in {"BURST", "SPARSE"}:
            raise ValueError(f"unsupported label: {episode.label}")
        if len(episode.gaps_ms) != 2 or any(
            isinstance(gap, bool) or not isinstance(gap, int) or gap <= 0
            for gap in episode.gaps_ms
        ):
            raise ValueError("each episode must contain two positive integer gaps")
        if len(episode.strengths) != 3 or any(
            strength not in STRENGTH_LEVELS for strength in episode.strengths
        ):
            raise ValueError("each pulse strength must use the shared strength grid")
        low, high = (
            BURST_GAP_RANGE_MS
            if episode.label == "BURST"
            else SPARSE_GAP_RANGE_MS
        )
        if any(gap < low or gap > high for gap in episode.gaps_ms):
            raise ValueError("episode timing is outside its class generation range")


def _build_dataset(
    seed: int,
) -> tuple[tuple[Episode, ...], tuple[Episode, ...]]:
    """Build one independent, deterministic train/held-out dataset."""
    seen: set[tuple[tuple[int, int], tuple[float, float, float]]] = set()
    train = _generate_split(seed, "train", TRAIN_PER_CLASS, seen)
    held_out = _generate_split(seed, "held-out", HELD_OUT_PER_CLASS, seen)
    _validate_dataset(train, held_out)

    # Avoid presenting all examples from one class as a contiguous block.
    # The order is deterministic and independent of model outcomes.
    def order_key(episode: Episode) -> bytes:
        material = (
            f"JARVIS_TEMPORAL_ROBUSTNESS_V2_ORDER|{seed}|{episode.split}|"
            f"{episode.episode_id}"
        )
        return hashlib.sha256(material.encode("utf-8")).digest()

    return (
        tuple(sorted(train, key=order_key)),
        tuple(sorted(held_out, key=order_key)),
    )


def _f1(precision: float, recall: float) -> float:
    return 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0


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
    precision_burst = tp / (tp + fp) if tp + fp else 0.0
    recall_burst = tp / (tp + fn) if tp + fn else 0.0
    specificity_sparse = tn / (tn + fp) if tn + fp else 0.0
    precision_sparse = tn / (tn + fn) if tn + fn else 0.0
    f1_burst = _f1(precision_burst, recall_burst)
    f1_sparse = _f1(precision_sparse, specificity_sparse)
    return {
        "episodes": total,
        "correct": tp + tn,
        "accuracy": (tp + tn) / total if total else 0.0,
        "precision_burst": precision_burst,
        "recall_burst": recall_burst,
        "f1_burst": f1_burst,
        "precision_sparse": precision_sparse,
        "recall_sparse": specificity_sparse,
        "f1_sparse": f1_sparse,
        "specificity_sparse": specificity_sparse,
        "balanced_accuracy": (recall_burst + specificity_sparse) / 2.0,
        "macro_f1": (f1_burst + f1_sparse) / 2.0,
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
    if mode not in CONDITION_MODES and mode != "inference":
        raise ValueError(f"unsupported condition: {mode}")

    neuron_params = starter_neuron_params()
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()

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
        elif mode in {"rstdp", "rstdp_neutral"}:
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
    reward_modulation: float | None = None

    if mode in {"rstdp", "rstdp_neutral"}:
        baseline_before_reward = reward_baseline
        if mode == "rstdp":
            reward_value = 1.0 if correct else 0.0
        else:
            # Consume eligibility with zero prediction error. This is a
            # neutral-reward control, not an absence of evaluator activity.
            reward_value = baseline_before_reward
        reward_modulation = reward_value - baseline_before_reward
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
        "reward_modulation": reward_modulation,
    }
    return record, synapse_state, reward_baseline


def _run_condition(
    seed: int,
    mode: str,
    train_episodes: tuple[Episode, ...],
    held_out_episodes: tuple[Episode, ...],
) -> dict[str, object]:
    synapse_state = SynapseState(weight=INITIAL_WEIGHT)
    reward_baseline = 0.5
    initial_weight = synapse_state.weight
    train_records: list[dict[str, object]] = []
    weight_trajectory: list[dict[str, float | str]] = []

    for index, episode in enumerate(train_episodes):
        weight_before = synapse_state.weight
        record, synapse_state, reward_baseline = _run_episode(
            episode,
            index,
            mode,
            synapse_state,
            reward_baseline,
            f"temporal-v2-{seed}-{mode}-{episode.episode_id}",
        )
        train_records.append(record)
        weight_trajectory.append({
            "episode_id": episode.episode_id,
            "weight_before": weight_before,
            "weight_after": synapse_state.weight,
            "weight_delta": synapse_state.weight - weight_before,
        })

    weight_after_training = synapse_state.weight
    reward_baseline_after_training = reward_baseline
    held_out_records: list[dict[str, object]] = []

    for index, episode in enumerate(held_out_episodes):
        record, synapse_state, reward_baseline = _run_episode(
            episode,
            len(train_episodes) + index,
            "inference",
            synapse_state,
            reward_baseline,
            f"temporal-v2-held-out-{seed}-{episode.episode_id}",
        )
        held_out_records.append(record)

    weight_after_held_out = synapse_state.weight
    return {
        "condition": mode,
        "seed": seed,
        "initial_weight": initial_weight,
        "weight_after_training": weight_after_training,
        "weight_delta": weight_after_training - initial_weight,
        "weight_after_held_out": weight_after_held_out,
        "held_out_weights_frozen": weight_after_held_out == weight_after_training,
        "reward_baseline_after_training": (
            reward_baseline_after_training
            if mode in {"rstdp", "rstdp_neutral"}
            else None
        ),
        "train": _metrics(train_records),
        "held_out": _metrics(held_out_records),
        "training_weight_trajectory": weight_trajectory,
        "training_episode_results": train_records,
        "held_out_episode_results": held_out_records,
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
        run_conditions = [
            next(
                condition for condition in run["conditions"]
                if condition["condition"] == mode
            )
            for run in runs
        ]
        train_rows = [
            episode
            for condition in run_conditions
            for episode in condition["training_episode_results"]
        ]
        held_rows = [
            episode
            for condition in run_conditions
            for episode in condition["held_out_episode_results"]
        ]
        accuracy_by_seed = [condition["held_out"]["accuracy"] for condition in run_conditions]
        balanced_by_seed = [
            condition["held_out"]["balanced_accuracy"] for condition in run_conditions
        ]
        macro_f1_by_seed = [condition["held_out"]["macro_f1"] for condition in run_conditions]
        summaries.append({
            "condition": mode,
            "train": _metrics(train_rows),
            "held_out": _metrics(held_rows),
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
        })
    return summaries


def run_benchmark() -> dict[str, object]:
    """Run the four conditions on four deterministic, independent datasets."""
    runs: list[dict[str, object]] = []

    for seed in RUN_SEEDS:
        train_episodes, held_out_episodes = _build_dataset(seed)
        conditions = [
            _run_condition(seed, mode, train_episodes, held_out_episodes)
            for mode in CONDITION_MODES
        ]
        runs.append({
            "seed": seed,
            "dataset": {
                "training_episodes": len(train_episodes),
                "held_out_episodes": len(held_out_episodes),
                "training_class_counts": {
                    label: sum(episode.label == label for episode in train_episodes)
                    for label in ("BURST", "SPARSE")
                },
                "held_out_class_counts": {
                    label: sum(episode.label == label for episode in held_out_episodes)
                    for label in ("BURST", "SPARSE")
                },
                "train_held_out_signatures_disjoint": True,
                "input_signatures_unique": True,
            },
            "conditions": conditions,
        })

    aggregate_conditions = _aggregate_conditions(runs)
    by_seed = {run["seed"]: run for run in runs}
    reward_effect_by_seed: list[dict[str, object]] = []
    for seed in RUN_SEEDS:
        conditions = by_seed[seed]["conditions"]
        rewarded = next(item for item in conditions if item["condition"] == "rstdp")
        neutral = next(
            item for item in conditions if item["condition"] == "rstdp_neutral"
        )
        reward_effect_by_seed.append({
            "seed": seed,
            "held_out_accuracy_delta": (
                rewarded["held_out"]["accuracy"] - neutral["held_out"]["accuracy"]
            ),
            "held_out_balanced_accuracy_delta": (
                rewarded["held_out"]["balanced_accuracy"]
                - neutral["held_out"]["balanced_accuracy"]
            ),
            "held_out_correct_episode_delta": (
                rewarded["held_out"]["correct"] - neutral["held_out"]["correct"]
            ),
        })

    return {
        "benchmark": "JARVIS_TEMPORAL_ROBUSTNESS_V2",
        "status": "REFERENCE_EXPERIMENT_NOT_PRODUCTION_RUNTIME",
        "task": (
            "Classify three-pulse episodes as BURST or SPARSE using the frozen "
            "decision rule: one or more output spikes means BURST; no output "
            "spike means SPARSE. The class timing bands overlap, and pulse "
            "strength is a shared nuisance variable."
        ),
        "protocol": {
            "neuron": "JARVIS_SINGLE_NEURON_STARTER_V1 / Adaptive LIF-DT",
            "synapses": 1,
            "initial_weight": INITIAL_WEIGHT,
            "pulses_per_episode": 3,
            "independent_deterministic_runs": len(RUN_SEEDS),
            "run_seeds": list(RUN_SEEDS),
            "training_episodes_per_run": TRAIN_PER_CLASS * 2,
            "training_episodes_per_class_per_run": TRAIN_PER_CLASS,
            "held_out_episodes_per_run": HELD_OUT_PER_CLASS * 2,
            "held_out_episodes_per_class_per_run": HELD_OUT_PER_CLASS,
            "total_held_out_episodes_per_condition": (
                len(RUN_SEEDS) * HELD_OUT_PER_CLASS * 2
            ),
            "burst_gap_range_ms": list(BURST_GAP_RANGE_MS),
            "sparse_gap_range_ms": list(SPARSE_GAP_RANGE_MS),
            "overlap_ms": [
                max(BURST_GAP_RANGE_MS[0], SPARSE_GAP_RANGE_MS[0]),
                min(BURST_GAP_RANGE_MS[1], SPARSE_GAP_RANGE_MS[1]),
            ],
            "shared_pulse_strength_levels": list(STRENGTH_LEVELS),
            "dataset_generation": (
                "SHA-256-derived deterministic candidates keyed by seed, split, "
                "label and sample index; no runtime randomness"
            ),
            "episode_spacing_ms": EPISODE_SPACING_MS,
            "neuron_state": (
                "reset to rested state for each episode; synaptic state persists "
                "within each run and condition"
            ),
            "decision_rule": "output_spike_count > 0 => BURST; otherwise SPARSE",
            "conditions": {
                "fixed": "no synaptic updates",
                "stdp": (
                    "pair-based excitatory STDP after each pulse; no class label "
                    "is supplied to the local learning rule"
                ),
                "rstdp": (
                    "eligibility updates per pulse; evaluator reward is 1.0 for "
                    "correct classification and 0.0 for incorrect classification"
                ),
                "rstdp_neutral": (
                    "same eligibility updates, then reward equal to the current "
                    "reward baseline; this consumes eligibility with zero "
                    "prediction error and controls for reward modulation"
                ),
            },
            "held_out_policy": (
                "all conditions use fixed inference; no STDP, eligibility or "
                "reward updates are applied"
            ),
            "starter_profile_modified": False,
        },
        "aggregate_conditions": aggregate_conditions,
        "reward_effect": {
            "comparison": "rstdp versus rstdp_neutral",
            "per_seed": reward_effect_by_seed,
            "held_out_accuracy_delta_across_runs": _summary_stats([
                item["held_out_accuracy_delta"] for item in reward_effect_by_seed
            ]),
            "held_out_balanced_accuracy_delta_across_runs": _summary_stats([
                item["held_out_balanced_accuracy_delta"] for item in reward_effect_by_seed
            ]),
            "held_out_correct_episode_delta_across_runs": _summary_stats([
                float(item["held_out_correct_episode_delta"])
                for item in reward_effect_by_seed
            ]),
        },
        "runs": runs,
        "interpretation_limits": [
            "This is a deterministic reference experiment with one neuron and one excitatory synapse, not a general classifier.",
            "The class timing ranges overlap at 7–8 ms, so some individual gaps are ambiguous by construction.",
            "Pulse strengths are nuisance variation shared by both classes; they are not intended to encode the class label.",
            "Four deterministic seeds provide repeated challenge sets, not a statistically powered population estimate.",
            "The neutral-reward condition supplies reward equal to the current baseline; it is a zero-modulation control, not a no-evaluator condition.",
            "A rewarded R-STDP advantage must be judged against both FIXED and the neutral-reward control, not from weight change alone.",
            "A change in accuracy can trade BURST recall against SPARSE specificity; inspect confusion counts and macro-F1.",
            "The fixed output rule and one-synapse capacity may limit what any learning rule can express.",
            "The STARTER profile is not optimized, calibrated or modified by this benchmark.",
            "This Python Oracle experiment is not production runtime validation or a biological-equivalence claim.",
        ],
    }


def main() -> None:
    print(json.dumps(run_benchmark(), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
