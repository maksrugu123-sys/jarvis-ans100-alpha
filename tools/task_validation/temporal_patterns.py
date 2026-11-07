"""Deterministic burst-vs-sparse task benchmark for the JARVIS numerical oracle.

This is a small task-level experiment, not a production network/runtime.
It compares a fixed-weight baseline with online pair-STDP and reward-modulated
STDP on one ALIF-DT output neuron and one excitatory input synapse.
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass
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


@dataclass(frozen=True)
class Episode:
    label: bool
    interval_ms: int
    strength: float = 1.0


TRAIN_EPISODES = (
    Episode(True, 5), Episode(False, 50),
    Episode(True, 4), Episode(False, 60),
    Episode(True, 6), Episode(False, 40),
    Episode(True, 5), Episode(False, 50),
    Episode(True, 4), Episode(False, 40),
    Episode(True, 6), Episode(False, 60),
)

# Held-out intervals and slightly reduced amplitude are not used for updates.
TEST_EPISODES = (
    Episode(True, 4, 0.95), Episode(False, 45, 0.95),
    Episode(True, 6, 0.95), Episode(False, 55, 0.95),
    Episode(True, 5, 0.95), Episode(False, 65, 0.95),
)


def _metrics(rows: Iterable[tuple[bool, bool]]) -> dict[str, float | int]:
    tp = fp = tn = fn = 0
    for expected, predicted in rows:
        if expected and predicted:
            tp += 1
        elif not expected and predicted:
            fp += 1
        elif not expected and not predicted:
            tn += 1
        else:
            fn += 1
    total = tp + fp + tn + fn
    return {
        "episodes": total,
        "correct": tp + tn,
        "accuracy": (tp + tn) / total if total else 0.0,
        "precision": tp / (tp + fp) if tp + fp else 0.0,
        "recall": tp / (tp + fn) if tp + fn else 0.0,
        "specificity": tn / (tn + fp) if tn + fp else 0.0,
        "tp": tp, "fp": fp, "tn": tn, "fn": fn,
    }


def _run_episode(
    episode: Episode,
    episode_index: int,
    weight: float,
    mode: str,
    synapse_state: SynapseState,
    reward_baseline: float,
    learning_context_id: str,
) -> tuple[bool, int, SynapseState, float]:
    neuron_params = starter_neuron_params()
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()

    # Each episode starts from a rested neuron. Synaptic traces/weights persist.
    start_ns = episode_index * 2_000 * NS_PER_MS
    neuron_state = NeuronState(last_update_model_time_ns=start_ns)
    spike_count = 0

    for pulse_index in range(3):
        time_ns = start_ns + pulse_index * episode.interval_ms * NS_PER_MS
        step = process_neuron_batch(
            neuron_state,
            time_ns,
            [InputSignal(
                source_id="temporal_input",
                signed_weight=weight,
                strength=episode.strength,
                attention=0.0,
            )],
            neuron_params,
        )
        neuron_state = step.state
        n_post = int(step.spike_emitted)
        spike_count += n_post

        if mode == "stdp":
            plasticity = update_stdp(
                synapse_state, synapse_params, time_ns, n_pre=1, n_post=n_post
            )
            synapse_state = plasticity.state
        elif mode == "rstdp":
            plasticity = update_rstdp(
                synapse_state, synapse_params, reward_params, time_ns,
                n_pre=1, n_post=n_post,
                learning_context_id=learning_context_id,
            )
            synapse_state = plasticity.state

    predicted = spike_count > 0

    if mode == "rstdp":
        reward_time_ns = (
            start_ns + 3 * episode.interval_ms * NS_PER_MS
        )
        reward_value = 1.0 if predicted == episode.label else 0.0
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

    return predicted, spike_count, synapse_state, reward_baseline


def _run_condition(mode: str) -> dict[str, object]:
    if mode not in {"fixed", "stdp", "rstdp"}:
        raise ValueError(f"unsupported condition: {mode}")

    synapse_state = SynapseState(weight=0.5)
    reward_baseline = 0.5
    initial_weight = synapse_state.weight
    train_rows: list[tuple[bool, bool]] = []

    for index, episode in enumerate(TRAIN_EPISODES):
        context_id = f"temporal-train-{index:03d}"
        predicted, _, synapse_state, reward_baseline = _run_episode(
            episode, index, synapse_state.weight, mode, synapse_state,
            reward_baseline, context_id,
        )
        train_rows.append((episode.label, predicted))

    weight_after_training = synapse_state.weight
    test_rows: list[tuple[bool, bool]] = []
    test_spike_counts: list[int] = []
    for index, episode in enumerate(TEST_EPISODES):
        # Test episodes are inference-only: no STDP or reward updates.
        predicted, spike_count, synapse_state, reward_baseline = _run_episode(
            episode, len(TRAIN_EPISODES) + index, synapse_state.weight,
            "fixed", synapse_state, reward_baseline,
            f"temporal-test-{index:03d}",
        )
        test_rows.append((episode.label, predicted))
        test_spike_counts.append(spike_count)

    return {
        "condition": mode,
        "initial_weight": initial_weight,
        "weight_after_training": weight_after_training,
        "weight_delta": weight_after_training - initial_weight,
        "reward_baseline_after_training": reward_baseline if mode == "rstdp" else None,
        "train": _metrics(train_rows),
        "held_out": _metrics(test_rows),
        "held_out_spike_counts": test_spike_counts,
    }


def run_benchmark() -> dict[str, object]:
    """Run fixed, STDP and R-STDP conditions and return a JSON-safe report."""
    return {
        "benchmark": "JARVIS_TEMPORAL_BURST_VS_SPARSE_V1",
        "status": "REFERENCE_EXPERIMENT_NOT_PRODUCTION_RUNTIME",
        "task": (
            "Detect three closely spaced input pulses (burst) and reject "
            "three widely spaced pulses (sparse); one or more output spikes "
            "means burst."
        ),
        "protocol": {
            "neuron": "JARVIS_SINGLE_NEURON_STARTER_V1 / Adaptive LIF-DT",
            "synapses": 1,
            "initial_weight": 0.5,
            "pulses_per_episode": 3,
            "training_episodes": len(TRAIN_EPISODES),
            "held_out_episodes": len(TEST_EPISODES),
            "training_burst_intervals_ms": sorted({e.interval_ms for e in TRAIN_EPISODES if e.label}),
            "training_sparse_intervals_ms": sorted({e.interval_ms for e in TRAIN_EPISODES if not e.label}),
            "held_out_burst_intervals_ms": sorted({e.interval_ms for e in TEST_EPISODES if e.label}),
            "held_out_sparse_intervals_ms": sorted({e.interval_ms for e in TEST_EPISODES if not e.label}),
            "held_out_strength": 0.95,
            "decision_rule": "spike_count > 0 => burst; otherwise sparse",
            "learning": {
                "fixed": "no weight updates",
                "stdp": "pair-based excitatory STDP after each input event",
                "rstdp": "eligibility updates per event; reward 1 for correct classification, 0 otherwise",
            },
            "test_policy": "weights frozen; no plasticity or reward updates",
            "randomness": "none; deterministic fixed episode order",
        },
        "conditions": [
            _run_condition("fixed"),
            _run_condition("stdp"),
            _run_condition("rstdp"),
        ],
        "interpretation_limits": [
            "This is a one-neuron, one-synapse reference task, not a general classifier.",
            "A fixed baseline may already solve this deliberately separable task.",
            "STDP is local and is not given class labels; R-STDP receives a scalar correctness reward.",
            "A result on this dataset does not establish general learning ability or production performance.",
            "The STARTER parameter profile is not modified or calibrated by this benchmark.",
        ],
    }


def main() -> None:
    print(json.dumps(run_benchmark(), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
