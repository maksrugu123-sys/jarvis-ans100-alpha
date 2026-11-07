"""V4.2 pulse-level R-STDP credit-assignment diagnostic.

The canonical replay records per-pulse traces. A separate shadow synapse
tests one diagnostic pre-at-t/post-at-t-plus-1ns ordering. The shadow never
affects the actual neuron, learning state, decisions, weights or reward baseline.
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
)
from tools.task_validation.rstdp_feedback_v4 import (
    DECISION_DEADLINE_MS,
    DIAGNOSTIC_INITIAL_WEIGHT,
    EPISODE_SPACING_MS,
    HELD_OUT_PAIRS,
    NS_PER_MS,
    RUN_SEEDS,
    TRAIN_PAIRS,
    _build_dataset,
    _pulse_times_ms,
)

AUDIT_SCHEMA = "JARVIS_R_STDP_CAUSAL_CREDIT_V4_2"
SHADOW_POST_OFFSET_NS = 1


def _sign(value: float) -> str:
    if value > 0.0:
        return "positive"
    if value < 0.0:
        return "negative"
    return "zero"


def _clip(value: float, low: float, high: float) -> float:
    return min(high, max(low, value))


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


def _add_prediction(counts: dict[str, int], label: str, prediction: str) -> None:
    if label == "FAST" and prediction == "FAST":
        counts["tp"] += 1
    elif label == "SLOW" and prediction == "FAST":
        counts["fp"] += 1
    elif label == "SLOW" and prediction == "SLOW":
        counts["tn"] += 1
    else:
        counts["fn"] += 1


def _trace_snapshot(
    state: SynapseState,
    time_ns: int,
    context_id: str,
    n_pre: int,
    n_post: int,
    synapse_params: Any,
    reward_params: Any,
) -> dict[str, float]:
    """Reconstruct the exact old-trace values consumed by update_rstdp."""
    if time_ns < state.trace_last_update_model_time_ns:
        raise ValueError("trace audit received a non-monotonic timestamp")
    if time_ns < state.eligibility_last_update_model_time_ns:
        raise ValueError("eligibility audit received a non-monotonic timestamp")

    active = state.eligibility_context_id is not None
    if active and state.eligibility_context_id != context_id:
        raise ValueError("active eligibility context does not match the V4 episode")

    if active:
        started = state.eligibility_started_model_time_ns
        if started is None:
            raise ValueError("active context has no start timestamp")
        if time_ns - started > reward_params.max_eligibility_age_ns:
            raise ValueError("V4 episode exceeded the configured eligibility age")
        trace_dt = time_ns - state.trace_last_update_model_time_ns
        eligibility_dt = time_ns - state.eligibility_last_update_model_time_ns
        pre_before = state.pre_trace * math.exp(
            -trace_dt / synapse_params.tau_pre_ns
        )
        post_before = state.post_trace * math.exp(
            -trace_dt / synapse_params.tau_post_ns
        )
        eligibility_before_decay = state.eligibility_trace
        eligibility_decayed = eligibility_before_decay * math.exp(
            -eligibility_dt / reward_params.tau_e_ns
        )
    else:
        # update_rstdp starts a new context with clean traces and zero eligibility.
        pre_before = 0.0
        post_before = 0.0
        eligibility_before_decay = 0.0
        eligibility_decayed = 0.0

    span = synapse_params.w_max - synapse_params.w_min
    z = (state.weight - synapse_params.w_min) / span
    q_raw = (
        n_post * synapse_params.eta_plus * pre_before * (1.0 - z)
        - n_pre * synapse_params.eta_minus * post_before * z
    )
    q_bounded = _clip(
        q_raw, -synapse_params.q_batch_max, synapse_params.q_batch_max
    )
    return {
        "pre_trace_before": pre_before,
        "post_trace_before": post_before,
        "eligibility_before_decay": eligibility_before_decay,
        "eligibility_decayed_before_q": eligibility_decayed,
        "q_raw_expected": q_raw,
        "q_bounded_expected": q_bounded,
    }


def _same_timestamp_order_probe() -> dict[str, object]:
    """Compare batch tie semantics with two explicit 1 ns diagnostic orders."""
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()
    initial_weight = DIAGNOSTIC_INITIAL_WEIGHT

    simultaneous = update_rstdp(
        SynapseState(weight=initial_weight),
        synapse_params,
        reward_params,
        0,
        n_pre=1,
        n_post=1,
        learning_context_id="v42-same-time",
    )

    pre_first = update_rstdp(
        SynapseState(weight=initial_weight),
        synapse_params,
        reward_params,
        0,
        n_pre=1,
        n_post=0,
        learning_context_id="v42-pre-post",
    )
    pre_then_post = update_rstdp(
        pre_first.state,
        synapse_params,
        reward_params,
        SHADOW_POST_OFFSET_NS,
        n_pre=0,
        n_post=1,
        learning_context_id="v42-pre-post",
    )

    post_first = update_rstdp(
        SynapseState(weight=initial_weight),
        synapse_params,
        reward_params,
        0,
        n_pre=0,
        n_post=1,
        learning_context_id="v42-post-pre",
    )
    post_then_pre = update_rstdp(
        post_first.state,
        synapse_params,
        reward_params,
        SHADOW_POST_OFFSET_NS,
        n_pre=1,
        n_post=0,
        learning_context_id="v42-post-pre",
    )

    return {
        "scope": "isolated_numerical_order_probe_not_a_model_change",
        "initial_weight": initial_weight,
        "simultaneous_batch": {
            "n_pre": 1,
            "n_post": 1,
            "q_raw": simultaneous.q_raw,
            "q_bounded": simultaneous.q_bounded,
            "eligibility_after": simultaneous.state.eligibility_trace,
        },
        "pre_then_post": {
            "post_offset_ns": SHADOW_POST_OFFSET_NS,
            "first_event_q": pre_first.q_raw,
            "second_event_q": pre_then_post.q_raw,
            "eligibility_after": pre_then_post.state.eligibility_trace,
        },
        "post_then_pre": {
            "pre_offset_ns": SHADOW_POST_OFFSET_NS,
            "first_event_q": post_first.q_raw,
            "second_event_q": post_then_pre.q_raw,
            "eligibility_after": post_then_pre.state.eligibility_trace,
        },
    }


def _new_pulse_aggregate(index: int) -> dict[str, int | float]:
    return {
        "pulse_index": index,
        "event_count": 0,
        "q_positive_count": 0,
        "q_negative_count": 0,
        "q_zero_count": 0,
        "q_raw_sum": 0.0,
        "q_bounded_sum": 0.0,
        "eligibility_after_sum": 0.0,
        "feedback_contribution_positive_count": 0,
        "feedback_contribution_negative_count": 0,
        "feedback_contribution_zero_count": 0,
        "feedback_contribution_sum": 0.0,
    }


def _new_group_aggregate() -> dict[str, Any]:
    return {
        "episode_count": 0,
        "pulses": [_new_pulse_aggregate(index) for index in range(3)],
        "feedback": {
            "actual_eligibility_positive_count": 0,
            "actual_eligibility_negative_count": 0,
            "actual_eligibility_zero_count": 0,
            "ordered_shadow_eligibility_positive_count": 0,
            "ordered_shadow_eligibility_negative_count": 0,
            "ordered_shadow_eligibility_zero_count": 0,
            "actual_delta_positive_count": 0,
            "actual_delta_negative_count": 0,
            "actual_delta_zero_count": 0,
            "ordered_shadow_delta_positive_count": 0,
            "ordered_shadow_delta_negative_count": 0,
            "ordered_shadow_delta_zero_count": 0,
            "eligibility_sign_changed_count": 0,
            "weight_delta_sign_changed_count": 0,
            "actual_eligibility_sum": 0.0,
            "ordered_shadow_eligibility_sum": 0.0,
            "actual_delta_sum": 0.0,
            "ordered_shadow_delta_sum": 0.0,
        },
    }


def _accumulate_group(aggregate: dict[str, Any], record: dict[str, Any]) -> None:
    aggregate["episode_count"] += 1
    for pulse in record["pulse_diagnostics"]:
        row = aggregate["pulses"][pulse["pulse_index"]]
        q = float(pulse["q_bounded"])
        contribution = float(pulse["eligibility_contribution_at_feedback"])
        row["event_count"] += 1
        row["q_positive_count"] += int(q > 0.0)
        row["q_negative_count"] += int(q < 0.0)
        row["q_zero_count"] += int(q == 0.0)
        row["q_raw_sum"] += float(pulse["q_raw"])
        row["q_bounded_sum"] += q
        row["eligibility_after_sum"] += float(pulse["eligibility_after_pulse"])
        row["feedback_contribution_positive_count"] += int(contribution > 0.0)
        row["feedback_contribution_negative_count"] += int(contribution < 0.0)
        row["feedback_contribution_zero_count"] += int(contribution == 0.0)
        row["feedback_contribution_sum"] += contribution

    feedback = record["feedback"]
    stats = aggregate["feedback"]
    actual_e = float(feedback["actual_eligibility_at_feedback"])
    shadow_e = float(feedback["ordered_shadow_eligibility_at_feedback"])
    actual_delta = float(feedback["actual_weight_delta"])
    shadow_delta = float(feedback["ordered_shadow_weight_delta"])
    stats["actual_eligibility_" + _sign(actual_e) + "_count"] += 1
    stats["ordered_shadow_eligibility_" + _sign(shadow_e) + "_count"] += 1
    stats["actual_delta_" + _sign(actual_delta) + "_count"] += 1
    stats["ordered_shadow_delta_" + _sign(shadow_delta) + "_count"] += 1
    stats["eligibility_sign_changed_count"] += int(
        _sign(actual_e) != _sign(shadow_e)
    )
    stats["weight_delta_sign_changed_count"] += int(
        _sign(actual_delta) != _sign(shadow_delta)
    )
    stats["actual_eligibility_sum"] += actual_e
    stats["ordered_shadow_eligibility_sum"] += shadow_e
    stats["actual_delta_sum"] += actual_delta
    stats["ordered_shadow_delta_sum"] += shadow_delta


def _merge_pulse_alignments(runs: list[dict[str, Any]]) -> dict[str, Any]:
    merged: dict[str, Any] = {}
    for run in runs:
        for category, source in run["pulse_alignment"].items():
            target = merged.setdefault(category, _new_group_aggregate())
            target["episode_count"] += source["episode_count"]
            for index in range(3):
                for key, value in source["pulses"][index].items():
                    if key != "pulse_index":
                        target["pulses"][index][key] += value
            for key, value in source["feedback"].items():
                target["feedback"][key] += value
    return merged


def _audit_episode(
    episode: Any,
    episode_index: int,
    synapse_state: SynapseState,
    reward_baseline: float,
    learning_context_id: str,
) -> tuple[dict[str, Any], SynapseState, float]:
    neuron_params = starter_neuron_params()
    synapse_params = starter_synapse_params()
    reward_params = starter_reward_params()
    start_ns = episode_index * EPISODE_SPACING_MS * NS_PER_MS
    pulse_times = _pulse_times_ms(episode)
    if pulse_times[-1] > DECISION_DEADLINE_MS:
        raise ValueError("all V4 input pulses must precede the decision deadline")

    neuron_state = NeuronState(last_update_model_time_ns=start_ns)
    spike_times_ms: list[int] = []
    pulse_rows: list[dict[str, Any]] = []
    weight_before_episode = synapse_state.weight
    shadow_state = SynapseState(weight=weight_before_episode)

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

        snapshot = _trace_snapshot(
            synapse_state,
            time_ns,
            learning_context_id,
            1,
            n_post,
            synapse_params,
            reward_params,
        )
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

        if not math.isclose(
            plasticity.q_raw, snapshot["q_raw_expected"],
            rel_tol=0.0, abs_tol=1e-14,
        ):
            raise AssertionError("pulse q_raw does not reconcile with Oracle traces")
        if not math.isclose(
            plasticity.q_bounded, snapshot["q_bounded_expected"],
            rel_tol=0.0, abs_tol=1e-14,
        ):
            raise AssertionError("pulse q_bounded does not reconcile with Oracle traces")
        expected_eligibility = _clip(
            snapshot["eligibility_decayed_before_q"] + plasticity.q_bounded,
            -reward_params.e_max,
            reward_params.e_max,
        )
        if not math.isclose(
            synapse_state.eligibility_trace, expected_eligibility,
            rel_tol=0.0, abs_tol=1e-14,
        ):
            raise AssertionError("per-pulse eligibility does not reconcile with q")

        # Shadow only: pre is at t, and a real neuron spike is replayed at t+1ns.
        shadow_pre = update_rstdp(
            shadow_state,
            synapse_params,
            reward_params,
            time_ns,
            n_pre=1,
            n_post=0,
            learning_context_id=learning_context_id,
        )
        shadow_state = shadow_pre.state
        shadow_post_q = 0.0
        if n_post:
            shadow_post = update_rstdp(
                shadow_state,
                synapse_params,
                reward_params,
                time_ns + SHADOW_POST_OFFSET_NS,
                n_pre=0,
                n_post=1,
                learning_context_id=learning_context_id,
            )
            shadow_state = shadow_post.state
            shadow_post_q = shadow_post.q_bounded

        contribution = plasticity.q_bounded * math.exp(
            -(DECISION_DEADLINE_MS - relative_ms) * NS_PER_MS
            / reward_params.tau_e_ns
        )
        pulse_rows.append({
            "pulse_index": pulse_index,
            "time_ms": relative_ms,
            "strength": strength,
            "n_pre": 1,
            "n_post": n_post,
            "output_spike": step.spike_emitted,
            "pulse_weight": weight_before_episode,
            "pre_trace_before": snapshot["pre_trace_before"],
            "post_trace_before": snapshot["post_trace_before"],
            "q_raw": plasticity.q_raw,
            "q_bounded": plasticity.q_bounded,
            "eligibility_before_decay": snapshot["eligibility_before_decay"],
            "eligibility_decayed_before_q": snapshot["eligibility_decayed_before_q"],
            "eligibility_after_pulse": synapse_state.eligibility_trace,
            "pre_trace_after": synapse_state.pre_trace,
            "post_trace_after": synapse_state.post_trace,
            "eligibility_contribution_at_feedback": contribution,
            "ordered_shadow_post_q": shadow_post_q,
        })

    decision_time_ns = start_ns + DECISION_DEADLINE_MS * NS_PER_MS
    # Match V4: execute the empty deadline step, but classify from pulse spikes.
    process_neuron_batch(neuron_state, decision_time_ns, [], neuron_params)
    prediction = "FAST" if spike_times_ms else "SLOW"
    correct = prediction == episode.label
    reward_value = 1.0 if correct else 0.0
    baseline_before = reward_baseline

    eligibility_at_feedback = synapse_state.eligibility_trace * math.exp(
        -(decision_time_ns - synapse_state.eligibility_last_update_model_time_ns)
        / reward_params.tau_e_ns
    )
    shadow_eligibility_at_feedback = shadow_state.eligibility_trace * math.exp(
        -(decision_time_ns - shadow_state.eligibility_last_update_model_time_ns)
        / reward_params.tau_e_ns
    )
    contribution_sum = math.fsum(
        float(row["eligibility_contribution_at_feedback"]) for row in pulse_rows
    )
    contribution_residual = eligibility_at_feedback - contribution_sum

    actual_reward = apply_reward(
        synapse_state,
        synapse_params,
        reward_params,
        decision_time_ns,
        reward_value,
        baseline_before,
        learning_context_id,
    )
    synapse_state = actual_reward.state
    shadow_reward = apply_reward(
        shadow_state,
        synapse_params,
        reward_params,
        decision_time_ns,
        reward_value,
        baseline_before,
        learning_context_id,
    )

    record = {
        "episode_id": episode.episode_id,
        "pair_id": episode.pair_id,
        "label": episode.label,
        "prediction": prediction,
        "correct": correct,
        "pulse_times_ms": list(pulse_times),
        "spike_times_ms": spike_times_ms,
        "weight_before_episode": weight_before_episode,
        "pulse_diagnostics": pulse_rows,
        "feedback": {
            "reward_value": reward_value,
            "baseline_before": baseline_before,
            "baseline_after": actual_reward.baseline_after,
            "modulator": actual_reward.modulator,
            "actual_eligibility_at_feedback": eligibility_at_feedback,
            "actual_weight_delta": actual_reward.weight_delta,
            "weight_after_feedback": synapse_state.weight,
            "actual_reward_status": actual_reward.status,
            "ordered_shadow_eligibility_at_feedback": shadow_eligibility_at_feedback,
            "ordered_shadow_weight_delta": shadow_reward.weight_delta,
            "ordered_shadow_status": shadow_reward.status,
            "eligibility_linear_contribution_sum": contribution_sum,
            "eligibility_contribution_residual": contribution_residual,
        },
    }
    return record, synapse_state, actual_reward.baseline_after


def _run_seed(
    seed: int,
    train_pairs: int,
    held_out_pairs: int,
    max_examples_per_category: int,
) -> dict[str, Any]:
    train, held_out = _build_dataset(seed, train_pairs, held_out_pairs)
    synapse_state = SynapseState(weight=DIAGNOSTIC_INITIAL_WEIGHT)
    reward_baseline = 0.5
    counts = {"tp": 0, "fp": 0, "tn": 0, "fn": 0}
    cross_tab: dict[str, int] = {}
    pulse_alignment: dict[str, Any] = {}
    representatives: dict[str, list[dict[str, Any]]] = {}
    ordering: dict[str, int | float] = {
        "episodes": 0,
        "eligibility_sign_changed_count": 0,
        "weight_delta_sign_changed_count": 0,
        "actual_eligibility_positive_count": 0,
        "actual_eligibility_negative_count": 0,
        "actual_eligibility_zero_count": 0,
        "ordered_shadow_eligibility_positive_count": 0,
        "ordered_shadow_eligibility_negative_count": 0,
        "ordered_shadow_eligibility_zero_count": 0,
        "actual_delta_positive_count": 0,
        "actual_delta_negative_count": 0,
        "actual_delta_zero_count": 0,
        "ordered_shadow_delta_positive_count": 0,
        "ordered_shadow_delta_negative_count": 0,
        "ordered_shadow_delta_zero_count": 0,
        "actual_eligibility_sum": 0.0,
        "ordered_shadow_eligibility_sum": 0.0,
        "actual_delta_sum": 0.0,
        "ordered_shadow_delta_sum": 0.0,
    }

    for index, episode in enumerate(train):
        context_id = f"v42-{seed}-rstdp-{episode.episode_id}"
        record, synapse_state, reward_baseline = _audit_episode(
            episode, index, synapse_state, reward_baseline, context_id
        )
        label = str(record["label"])
        prediction = str(record["prediction"])
        correctness = "correct" if record["correct"] else "incorrect"
        _add_prediction(counts, label, prediction)
        feedback = record["feedback"]
        key = "|".join((
            label,
            prediction,
            correctness,
            _sign(float(feedback["actual_eligibility_at_feedback"])),
            _sign(float(feedback["modulator"])),
            _sign(float(feedback["actual_weight_delta"])),
        ))
        cross_tab[key] = cross_tab.get(key, 0) + 1

        category = f"{label}|{prediction}|{correctness}"
        group = pulse_alignment.setdefault(category, _new_group_aggregate())
        _accumulate_group(group, record)
        if len(representatives.get(category, [])) < max_examples_per_category:
            representatives.setdefault(category, []).append(record)

        actual_e = float(feedback["actual_eligibility_at_feedback"])
        shadow_e = float(feedback["ordered_shadow_eligibility_at_feedback"])
        actual_delta = float(feedback["actual_weight_delta"])
        shadow_delta = float(feedback["ordered_shadow_weight_delta"])
        ordering["episodes"] += 1
        ordering["eligibility_sign_changed_count"] += int(
            _sign(actual_e) != _sign(shadow_e)
        )
        ordering["weight_delta_sign_changed_count"] += int(
            _sign(actual_delta) != _sign(shadow_delta)
        )
        for prefix, value in (
            ("actual_eligibility", actual_e),
            ("ordered_shadow_eligibility", shadow_e),
            ("actual_delta", actual_delta),
            ("ordered_shadow_delta", shadow_delta),
        ):
            ordering[prefix + "_" + _sign(value) + "_count"] += 1
        ordering["actual_eligibility_sum"] += actual_e
        ordering["ordered_shadow_eligibility_sum"] += shadow_e
        ordering["actual_delta_sum"] += actual_delta
        ordering["ordered_shadow_delta_sum"] += shadow_delta

    return {
        "seed": seed,
        "dataset": {
            "training_pairs": train_pairs,
            "held_out_pairs_generated_for_split_validation": held_out_pairs,
            "training_episodes": len(train),
            "held_out_episodes_not_replayed": len(held_out),
            "all_three_pulses_before_deadline": True,
            "train_held_out_strength_signatures_disjoint": True,
        },
        "training_metrics": _metrics(counts),
        "weight_after_training": synapse_state.weight,
        "reward_baseline_after_training": reward_baseline,
        "cross_tab": cross_tab,
        "pulse_alignment": pulse_alignment,
        "representative_traces": representatives,
        "ordering_sensitivity": ordering,
    }


def run_audit(
    train_pairs: int = 512,
    held_out_pairs: int = 128,
    seeds: tuple[int, ...] = RUN_SEEDS[:2],
    max_examples_per_category: int = 2,
    progress: bool = False,
) -> dict[str, Any]:
    """Replay V4 R-STDP training and return pulse-level plus shadow diagnostics."""
    for name, value in (
        ("train_pairs", train_pairs),
        ("held_out_pairs", held_out_pairs),
        ("max_examples_per_category", max_examples_per_category),
    ):
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"{name} must be an integer")
        if value <= 0:
            raise ValueError(f"{name} must be positive")
    if not seeds or len(set(seeds)) != len(seeds):
        raise ValueError("seeds must be a non-empty sequence of unique integers")
    if any(isinstance(seed, bool) or not isinstance(seed, int) or seed < 0 for seed in seeds):
        raise ValueError("seeds must be non-negative integers")

    runs = []
    for index, seed in enumerate(seeds, start=1):
        if progress:
            print(
                f"[V4.2] seed {index}/{len(seeds)} ({seed}): "
                f"replaying {train_pairs * 2} R-STDP training episodes",
                file=sys.stderr,
                flush=True,
            )
        runs.append(_run_seed(
            seed, train_pairs, held_out_pairs, max_examples_per_category
        ))

    aggregate_cross_tab: dict[str, int] = {}
    for run in runs:
        for key, count in run["cross_tab"].items():
            aggregate_cross_tab[key] = aggregate_cross_tab.get(key, 0) + count

    aggregate_counts = {
        key: sum(int(run["training_metrics"][key]) for run in runs)
        for key in ("tp", "fp", "tn", "fn")
    }
    ordering_keys = tuple(runs[0]["ordering_sensitivity"])
    aggregate_ordering: dict[str, int | float] = {}
    for key in ordering_keys:
        aggregate_ordering[key] = sum(
            run["ordering_sensitivity"][key] for run in runs
        )

    return {
        "schema": AUDIT_SCHEMA,
        "benchmark": "JARVIS_R_STDP_CAUSAL_CREDIT_AUDIT_V4_2",
        "status": "REFERENCE_DIAGNOSTIC_NOT_A_LEARNING_RULE_CHANGE",
        "protocol": {
            "replays": "V4 R-STDP training split only; deterministic SHA-256 dataset",
            "canonical_starter_weight": 0.5,
            "diagnostic_initial_weight": DIAGNOSTIC_INITIAL_WEIGHT,
            "starter_profile_modified": False,
            "synapse_rule": "EXCITATORY_PAIR_EXP_R_STDP_V1 unchanged",
            "batch_semantics": (
                "old pre/post traces are read before a same-timestamp batch; "
                "traces are updated after the batch"
            ),
            "shadow_semantics": (
                "same observed neuron spikes, but a separate shadow synapse "
                "receives pre at t and post at t+1 ns; it never feeds back"
            ),
            "shadow_post_offset_ns": SHADOW_POST_OFFSET_NS,
            "reward_policy": "1.0 for a correct training decision; 0.0 for an incorrect decision",
            "decision_deadline_ms": DECISION_DEADLINE_MS,
            "pulses_per_episode": 3,
            "train_pairs_per_seed": train_pairs,
            "held_out_pairs_generated_per_seed": held_out_pairs,
            "held_out_policy": "generated to validate train/held-out separation; not replayed or scored",
            "seeds": list(seeds),
            "max_representative_examples_per_outcome_category": max_examples_per_category,
        },
        "same_timestamp_order_probe": _same_timestamp_order_probe(),
        "aggregate_training_metrics": _metrics(aggregate_counts),
        "aggregate_cross_tab": aggregate_cross_tab,
        "aggregate_pulse_alignment": _merge_pulse_alignments(runs),
        "aggregate_ordering_sensitivity": aggregate_ordering,
        "runs": runs,
        "interpretation_limits": [
            "Only the V4 R-STDP training split is replayed; this is not a new held-out learning benchmark.",
            "Per-pulse traces describe the existing Oracle batch equation and do not change it.",
            "The 1 ns pre->post shadow is an artificial tie-order sensitivity probe, not a biological timing claim.",
            "Shadow eligibility and deltas are counterfactual diagnostics and never update the real synapse.",
            "A shadow-order difference is not proof of improved task learning; a separate pre-registered held-out experiment is required before changing event order.",
            "The diagnostic initial weight 0.70 is not calibrated and does not replace STARTER 0.5.",
            "This reference experiment does not establish production readiness, biological equivalence, consciousness or general intelligence.",
            "No target-device RAM, CPU, energy or I/O measurements are collected.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run the V4.2 pulse-level R-STDP credit-assignment audit."
    )
    parser.add_argument("--profile", choices=("smoke", "pilot", "full"), default="pilot")
    parser.add_argument("--train-pairs", type=int, default=None)
    parser.add_argument("--held-out-pairs", type=int, default=None)
    parser.add_argument("--seeds", type=int, nargs="+", default=None)
    parser.add_argument("--examples-per-category", type=int, default=2)
    args = parser.parse_args()

    profiles = {
        "smoke": (32, 16, RUN_SEEDS[:2]),
        "pilot": (512, 128, RUN_SEEDS[:2]),
        "full": (TRAIN_PAIRS, HELD_OUT_PAIRS, RUN_SEEDS),
    }
    default_train, default_held, default_seeds = profiles[args.profile]
    train_pairs = args.train_pairs if args.train_pairs is not None else default_train
    held_out_pairs = (
        args.held_out_pairs if args.held_out_pairs is not None else default_held
    )
    seeds = tuple(args.seeds) if args.seeds is not None else tuple(default_seeds)
    if train_pairs < 1 or held_out_pairs < 1 or args.examples_per_category < 1:
        parser.error("pair counts and examples-per-category must be positive")

    report = run_audit(
        train_pairs,
        held_out_pairs,
        seeds,
        args.examples_per_category,
        progress=True,
    )
    print(json.dumps(report, indent=2, sort_keys=True, allow_nan=False))


if __name__ == "__main__":
    main()
