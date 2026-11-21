"""Deterministic noisy temporal-generalization benchmark for one JARVIS neuron.

This is an exploratory research benchmark, not a production trainer and not a
claim that any learning rule is superior. It compares fixed-weight, STDP,
rewarded R-STDP and neutral R-STDP under matched train/held-out conditions.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
import hashlib
import json
import math
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
EPISODE_SPACING_NS = 2_000 * NS_PER_MS
DEADLINE_MS = 40
DIAGNOSTIC_WEIGHT = 0.70
INITIAL_REWARD_BASELINE = 0.5
MODES = ("fixed", "stdp", "rstdp", "rstdp_neutral")
TRAIN_FAST_GAPS = ((2, 2), (2, 3), (3, 2), (3, 3))
TRAIN_SLOW_GAPS = ((18, 18), (18, 19), (19, 18), (19, 19))
HELDOUT_FAST_GAPS = ((4, 4), (4, 5), (5, 4), (5, 5))
HELDOUT_SLOW_GAPS = ((15, 15), (15, 16), (16, 15), (16, 16))


@dataclass(frozen=True)
class NoisePulse:
    time_ms: int
    strength: float


@dataclass(frozen=True)
class NoiseEpisode:
    episode_id: str
    pair_id: str
    label: str
    gaps_ms: tuple[int, int]
    strengths: tuple[float, float, float]
    distractors: tuple[NoisePulse, ...]


def _digest(*parts: object) -> bytes:
    material = "|".join(str(part) for part in parts)
    return hashlib.sha256(material.encode("utf-8")).digest()


def _unit(byte_value: int) -> float:
    return byte_value / 255.0


def _make_distractors(
    seed: int, split: str, pair_index: int, count: int, max_strength: float,
) -> tuple[NoisePulse, ...]:
    if count == 0:
        return ()
    digest = _digest("JARVIS_NOISY_TEMPORAL_V1", seed, split, pair_index, "noise")
    used_times: set[int] = set()
    rows: list[NoisePulse] = []
    for index in range(count):
        first = digest[(index * 3) % len(digest)]
        second = digest[(index * 3 + 1) % len(digest)]
        third = digest[(index * 3 + 2) % len(digest)]
        time_ms = 8 + first % 27
        while time_ms in used_times:
            time_ms = 8 + ((time_ms - 7) % 27)
        used_times.add(time_ms)
        strength = 0.05 + _unit(second) * (max_strength - 0.05)
        # The third byte is deliberately consumed in the signature, but not
        # converted into an unmodelled property. It helps decorrelate the row.
        _ = third
        rows.append(NoisePulse(time_ms, strength))
    return tuple(sorted(rows, key=lambda item: (item.time_ms, item.strength)))


def _build_split(
    seed: int, split: str, pair_count: int, noise_count: int,
    max_noise_strength: float,
) -> tuple[NoiseEpisode, ...]:
    episodes: list[NoiseEpisode] = []
    for index in range(pair_count):
        digest = _digest("JARVIS_NOISY_TEMPORAL_V1", seed, split, index, "signal")
        strengths = tuple(
            0.80 + 0.20 * (
                int.from_bytes(digest[offset:offset + 4], "big") / 0xFFFFFFFF
            )
            for offset in (0, 4, 8)
        )
        if split == "train":
            fast_pool, slow_pool = TRAIN_FAST_GAPS, TRAIN_SLOW_GAPS
        elif split == "heldout":
            fast_pool, slow_pool = HELDOUT_FAST_GAPS, HELDOUT_SLOW_GAPS
        else:
            raise ValueError("split must be 'train' or 'heldout'")
        fast_gaps = fast_pool[digest[12] % len(fast_pool)]
        slow_gaps = slow_pool[digest[13] % len(slow_pool)]
        distractors = _make_distractors(
            seed, split, index, noise_count, max_noise_strength
        )
        pair_id = f"{split}-{seed}-{index:05d}"
        fast = NoiseEpisode(
            f"{pair_id}-fast", pair_id, "FAST", fast_gaps,
            strengths, distractors,
        )
        slow = NoiseEpisode(
            f"{pair_id}-slow", pair_id, "SLOW", slow_gaps,
            strengths, distractors,
        )
        orientation = _digest("JARVIS_NOISY_TEMPORAL_ORDER", seed, split, index)[0]
        episodes.extend((slow, fast) if orientation & 1 else (fast, slow))
    return tuple(episodes)


def build_dataset(
    seed: int,
    train_pairs: int,
    held_out_pairs: int,
    noise_count: int,
    max_noise_strength: float = 0.30,
) -> tuple[tuple[NoiseEpisode, ...], tuple[NoiseEpisode, ...]]:
    """Create paired balanced splits with disjoint signal/noise signatures."""
    if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
        raise ValueError("seed must be a non-negative integer")
    for name, value in (
        ("train_pairs", train_pairs), ("held_out_pairs", held_out_pairs),
    ):
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            raise ValueError(f"{name} must be a positive integer")
    if isinstance(noise_count, bool) or not isinstance(noise_count, int) or not 0 <= noise_count <= 8:
        raise ValueError("noise_count must be an integer in [0, 8]")
    if (
        isinstance(max_noise_strength, bool)
        or not isinstance(max_noise_strength, (int, float))
        or not math.isfinite(float(max_noise_strength))
        or not 0.05 <= float(max_noise_strength) <= 0.60
    ):
        raise ValueError("max_noise_strength must be finite and in [0.05, 0.60]")

    train = _build_split(seed, "train", train_pairs, noise_count, float(max_noise_strength))
    held = _build_split(seed, "heldout", held_out_pairs, noise_count, float(max_noise_strength))
    train_signatures = {
        (row.strengths, tuple((p.time_ms, p.strength) for p in row.distractors))
        for row in train
    }
    held_signatures = {
        (row.strengths, tuple((p.time_ms, p.strength) for p in row.distractors))
        for row in held
    }
    if not train_signatures.isdisjoint(held_signatures):
        raise AssertionError("train and held-out signal/noise signatures overlap")
    train_timing = {row.gaps_ms for row in train}
    held_timing = {row.gaps_ms for row in held}
    if not train_timing.isdisjoint(held_timing):
        raise AssertionError("train and held-out temporal gap signatures overlap")
    _validate_split(train, train_pairs, noise_count, "train")
    _validate_split(held, held_out_pairs, noise_count, "heldout")
    return train, held


def _validate_split(
    episodes: tuple[NoiseEpisode, ...], expected_pairs: int,
    noise_count: int, split: str,
) -> None:
    if len(episodes) != expected_pairs * 2:
        raise AssertionError("each pair must contain exactly two episodes")
    by_pair: dict[str, list[NoiseEpisode]] = defaultdict(list)
    for episode in episodes:
        by_pair[episode.pair_id].append(episode)
        if episode.label not in ("FAST", "SLOW"):
            raise AssertionError("unsupported label")
        if len(episode.strengths) != 3 or any(not 0.8 <= value <= 1.0 for value in episode.strengths):
            raise AssertionError("malformed signal strength vector")
        if len(episode.distractors) != noise_count:
            raise AssertionError("unexpected distractor count")
        if any(not 8 <= p.time_ms <= 34 or not 0.05 <= p.strength <= 0.60 for p in episode.distractors):
            raise AssertionError("distractor outside frozen bounds")
        if sum(episode.gaps_ms) > DEADLINE_MS:
            raise AssertionError("signal occurs after decision deadline")
        fast_pool = TRAIN_FAST_GAPS if split == "train" else HELDOUT_FAST_GAPS
        slow_pool = TRAIN_SLOW_GAPS if split == "train" else HELDOUT_SLOW_GAPS
        if episode.label == "FAST" and episode.gaps_ms not in fast_pool:
            raise AssertionError("FAST timing outside the split's frozen timing set")
        if episode.label == "SLOW" and episode.gaps_ms not in slow_pool:
            raise AssertionError("SLOW timing outside the split's frozen timing set")
    for members in by_pair.values():
        if len(members) != 2 or {row.label for row in members} != {"FAST", "SLOW"}:
            raise AssertionError("pair must contain exactly one FAST and one SLOW episode")
        if members[0].strengths != members[1].strengths:
            raise AssertionError("matched pair signal strengths differ")
        if members[0].distractors != members[1].distractors:
            raise AssertionError("matched pair distractors differ")


def _metrics(counts: dict[str, int]) -> dict[str, float | int]:
    tp, fp, tn, fn = (counts[key] for key in ("tp", "fp", "tn", "fn"))
    total = tp + fp + tn + fn
    precision_fast = tp / (tp + fp) if tp + fp else 0.0
    recall_fast = tp / (tp + fn) if tp + fn else 0.0
    specificity_slow = tn / (tn + fp) if tn + fp else 0.0
    precision_slow = tn / (tn + fn) if tn + fn else 0.0
    f1_fast = (
        2 * precision_fast * recall_fast / (precision_fast + recall_fast)
        if precision_fast + recall_fast else 0.0
    )
    f1_slow = (
        2 * precision_slow * specificity_slow / (precision_slow + specificity_slow)
        if precision_slow + specificity_slow else 0.0
    )
    return {
        "episodes": total,
        "correct": tp + tn,
        "accuracy": (tp + tn) / total if total else 0.0,
        "balanced_accuracy": (recall_fast + specificity_slow) / 2.0,
        "macro_f1": (f1_fast + f1_slow) / 2.0,
        "tp": tp, "fp": fp, "tn": tn, "fn": fn,
    }


def _record_prediction(counts: dict[str, int], label: str, predicted_fast: bool) -> None:
    if label == "FAST" and predicted_fast:
        counts["tp"] += 1
    elif label == "SLOW" and predicted_fast:
        counts["fp"] += 1
    elif label == "SLOW":
        counts["tn"] += 1
    else:
        counts["fn"] += 1


def _run_episode(
    episode: NoiseEpisode,
    episode_index: int,
    mode: str,
    synapse_state: SynapseState,
    reward_baseline: float,
    training: bool,
) -> tuple[bool, SynapseState, float, dict[str, int | float]]:
    neuron_params = starter_neuron_params()
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()
    start_ns = episode_index * EPISODE_SPACING_NS

    batches: dict[int, list[tuple[str, float]]] = defaultdict(list)
    signal_times = (0, episode.gaps_ms[0], sum(episode.gaps_ms))
    for index, (relative_ms, strength) in enumerate(zip(signal_times, episode.strengths)):
        batches[relative_ms * NS_PER_MS].append((f"signal-{index}", strength))
    for index, pulse in enumerate(episode.distractors):
        batches[pulse.time_ms * NS_PER_MS].append((f"distractor-{index}", pulse.strength))

    neuron_state = NeuronState(last_update_model_time_ns=start_ns)
    spike_seen = False
    stats: dict[str, int | float] = {
        "input_events": 0, "spikes": 0, "plasticity_events": 0,
        "positive_local_updates": 0, "negative_local_updates": 0,
    }
    context_id = f"noise-benchmark-{episode.episode_id}"

    for relative_ns in sorted(batches):
        time_ns = start_ns + relative_ns
        # Construct each impulse with the current weight: within-episode STDP
        # updates must affect later signal events, not a stale episode snapshot.
        batch = [
            InputSignal(source_id, synapse_state.weight, strength, 0.0)
            for source_id, strength in batches[relative_ns]
        ]
        step = process_neuron_batch(
            neuron_state, time_ns, batch, neuron_params,
        )
        neuron_state = step.state
        n_post = int(step.spike_emitted)
        spike_seen = spike_seen or step.spike_emitted
        stats["input_events"] += len(batch)
        stats["spikes"] += n_post

        if training and mode == "stdp":
            plasticity = update_stdp(
                synapse_state, synapse_params, time_ns,
                n_pre=len(batch), n_post=n_post,
            )
            synapse_state = plasticity.state
            stats["plasticity_events"] += 1
            stats["positive_local_updates"] += int(plasticity.weight_delta > 0)
            stats["negative_local_updates"] += int(plasticity.weight_delta < 0)
        elif training and mode in ("rstdp", "rstdp_neutral"):
            plasticity = update_rstdp(
                synapse_state, synapse_params, reward_params, time_ns,
                n_pre=len(batch), n_post=n_post,
                learning_context_id=context_id,
            )
            synapse_state = plasticity.state
            stats["plasticity_events"] += 1

    deadline_ns = start_ns + DEADLINE_MS * NS_PER_MS
    deadline_step = process_neuron_batch(
        neuron_state, deadline_ns, [], neuron_params
    )
    neuron_state = deadline_step.state
    spike_seen = spike_seen or deadline_step.spike_emitted
    stats["spikes"] += int(deadline_step.spike_emitted)

    # The deadline is a genuine decision-evaluation time. If a retained input
    # spikes exactly there, include that post event in the training trace.
    if training and deadline_step.spike_emitted and mode == "stdp":
        plasticity = update_stdp(
            synapse_state, synapse_params, deadline_ns,
            n_pre=0, n_post=1,
        )
        synapse_state = plasticity.state
        stats["plasticity_events"] += 1
        stats["positive_local_updates"] += int(plasticity.weight_delta > 0)
        stats["negative_local_updates"] += int(plasticity.weight_delta < 0)
    elif training and deadline_step.spike_emitted and mode in ("rstdp", "rstdp_neutral"):
        plasticity = update_rstdp(
            synapse_state, synapse_params, reward_params, deadline_ns,
            n_pre=0, n_post=1, learning_context_id=context_id,
        )
        synapse_state = plasticity.state
        stats["plasticity_events"] += 1

    if training and mode in ("rstdp", "rstdp_neutral"):
        reward = (
            reward_baseline if mode == "rstdp_neutral"
            else (1.0 if spike_seen == (episode.label == "FAST") else 0.0)
        )
        reward_step = apply_reward(
            synapse_state, synapse_params, reward_params, deadline_ns,
            reward, reward_baseline, context_id,
        )
        synapse_state = reward_step.state
        reward_baseline = reward_step.baseline_after
    return spike_seen, synapse_state, reward_baseline, stats


def _evaluate(
    episodes: tuple[NoiseEpisode, ...], weight: float, start_index: int,
) -> dict[str, float | int]:
    state = SynapseState(weight=weight)
    baseline = INITIAL_REWARD_BASELINE
    counts = {"tp": 0, "fp": 0, "tn": 0, "fn": 0}
    for offset, episode in enumerate(episodes):
        predicted, state, baseline, _ = _run_episode(
            episode, start_index + offset, "fixed", state, baseline, False
        )
        _record_prediction(counts, episode.label, predicted)
    if state.weight != weight:
        raise AssertionError("held-out inference changed weight")
    return _metrics(counts)


def run_seed(
    seed: int, train_pairs: int, held_out_pairs: int, noise_count: int,
    max_noise_strength: float = 0.30,
) -> dict[str, object]:
    train, held_out = build_dataset(
        seed, train_pairs, held_out_pairs, noise_count, max_noise_strength
    )
    conditions = []
    for mode in MODES:
        state = SynapseState(weight=DIAGNOSTIC_WEIGHT)
        baseline = INITIAL_REWARD_BASELINE
        train_counts = {"tp": 0, "fp": 0, "tn": 0, "fn": 0}
        train_stats: dict[str, int | float] = {
            "input_events": 0, "spikes": 0, "plasticity_events": 0,
            "positive_local_updates": 0, "negative_local_updates": 0,
        }
        for index, episode in enumerate(train):
            predicted, state, baseline, stats = _run_episode(
                episode, index, mode, state, baseline, True
            )
            _record_prediction(train_counts, episode.label, predicted)
            for key, value in stats.items():
                train_stats[key] += value

        trained_weight = state.weight
        held_metrics = _evaluate(held_out, trained_weight, len(train) + 10_000)
        conditions.append({
            "condition": mode,
            "initial_weight": DIAGNOSTIC_WEIGHT,
            "weight_after_training": trained_weight,
            "held_out_weights_frozen": True,
            "train": _metrics(train_counts),
            "held_out": held_metrics,
            "training_resource_counters": train_stats,
            "reward_baseline_after_training": baseline,
        })

    return {
        "seed": seed,
        "train_pairs": train_pairs,
        "held_out_pairs": held_out_pairs,
        "noise_count_per_episode": noise_count,
        "max_noise_strength": max_noise_strength,
        "train_episode_count": len(train),
        "held_out_episode_count": len(held_out),
        "train_held_out_signature_overlap": False,
        "conditions": conditions,
    }


def run_benchmark(profile: str = "smoke") -> dict[str, object]:
    profiles = {
        "smoke": {
            "train_pairs": 24, "held_out_pairs": 12,
            "seeds": (104729,), "noise_counts": (0, 2),
        },
        "pilot": {
            "train_pairs": 128, "held_out_pairs": 64,
            "seeds": (104729, 130363), "noise_counts": (0, 2, 4),
        },
        "full": {
            "train_pairs": 512, "held_out_pairs": 256,
            "seeds": (104729, 130363, 155921, 181081),
            "noise_counts": (0, 2, 4, 6),
        },
    }
    if profile not in profiles:
        raise ValueError(f"unknown profile {profile!r}")
    config = profiles[profile]
    runs = []
    for noise_count in config["noise_counts"]:
        for seed in config["seeds"]:
            runs.append(run_seed(
                seed, config["train_pairs"], config["held_out_pairs"], noise_count
            ))
    return {
        "benchmark": "JARVIS_ONE_NEURON_NOISY_TEMPORAL_GENERALIZATION_V1",
        "status": "EXPLORATORY_NOISE_BENCHMARK",
        "profile": profile,
        "protocol": {
            **config,
            "modes": MODES,
            "diagnostic_initial_weight": DIAGNOSTIC_WEIGHT,
            "canonical_starter_weight": 0.5,
            "starter_modified": False,
            "input_deadline_ms": DEADLINE_MS,
            "matched_noise_per_pair": True,
            "held_out_is_inference_only": True,
            "held_out_signatures_are_disjoint": True,
            "held_out_temporal_gaps_are_disjoint": True,
            "performance_threshold": None,
        },
        "runs": runs,
        "interpretation_limits": [
            "This is a synthetic one-neuron temporal task, not general intelligence or real-world recognition.",
            "Metrics are observational; no condition is declared successful by an arbitrary threshold.",
            "A pilot is not sufficient evidence for changing a learning rule.",
            "BTCC is not integrated into any condition and is not evaluated as a trainer.",
            "The diagnostic weight 0.70 is not the canonical STARTER weight 0.5.",
            "Noise pulses are weak excitatory distractors; inhibitory and adversarial correlated noise are not covered.",
        ],
    }


if __name__ == "__main__":
    report = run_benchmark("smoke")
    print(json.dumps(report, indent=2, sort_keys=True, allow_nan=False))
