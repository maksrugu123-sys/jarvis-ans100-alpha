"""Deterministic temporal evaluation of ANS-100 activity-driven structural growth.

Reference simulation only. It mirrors the bounded proposal-selection rules in
ANS100ActivityDrivenStructuralPlasticity and the fixed v4 concept projection.
It does not simulate Android LIF membrane dynamics, SQLite, or actual spikes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

CONCEPT_START = 1_062
ASSOCIATION_START = 1_200
ASSOCIATION_SIZE = 1_000
PROJECTION_FAN_OUT = 3
MIN_DISTINCT_TURNS = 3
OBSERVATION_WINDOW = 16
MAX_GROWN_PAIRS = 256
MAX_GROWN_EDGES_PER_CONCEPT = 4
MAX_INCOMING_PER_TARGET = 12
SEEDS = (104729, 130363, 155921, 181081)


@dataclass(frozen=True)
class PairLabel:
    pair_id: str
    concept_a: str
    concept_b: str
    related: bool


@dataclass(frozen=True)
class Turn:
    turn_id: str
    round_index: int
    pair_index: int
    active_concepts: tuple[str, str]


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _ordered(indices: Iterable[int], seed: int, round_index: int) -> list[int]:
    return sorted(
        indices,
        key=lambda index: _digest(f"ANS100_STRUCTURAL_EVAL_V1|{seed}|{round_index}|{index}"),
    )


def build_dataset(seed: int) -> tuple[list[PairLabel], list[Turn], list[Turn]]:
    """Two training co-activations per pair; held-out third turn only for 4 positives
    and 2 deliberately difficult negatives. Labels are never fed to the learner.
    """
    pairs = [
        PairLabel(
            pair_id=f"pair-{index:02d}",
            concept_a=f"concept:a{index:02d}",
            concept_b=f"concept:b{index:02d}",
            related=index < 4,
        )
        for index in range(8)
    ]
    training: list[Turn] = []
    for round_index in (0, 1):
        for index in _ordered(range(len(pairs)), seed, round_index):
            pair = pairs[index]
            training.append(Turn(
                turn_id=f"seed-{seed}-train-{round_index}-{index}",
                round_index=round_index,
                pair_index=index,
                active_concepts=(pair.concept_a, pair.concept_b),
            ))

    # Two negative pairs are hard negatives: they recur for a third turn.
    # The remaining two negatives do not recur in held-out time.
    held_out_indices = [0, 1, 2, 3, 4, 5]
    held_out: list[Turn] = []
    for index in _ordered(held_out_indices, seed, 2):
        pair = pairs[index]
        held_out.append(Turn(
            turn_id=f"seed-{seed}-heldout-2-{index}",
            round_index=2,
            pair_index=index,
            active_concepts=(pair.concept_a, pair.concept_b),
        ))
    return pairs, training, held_out


def projection_targets(neuron_id: int) -> tuple[int, ...]:
    """Exact concept projection formula from AndroidHybridTopologyV2."""
    if not CONCEPT_START <= neuron_id < CONCEPT_START + 138:
        raise ValueError("concept input neuron is outside the 138 persistent slots")
    source_offset = neuron_id
    targets = tuple(
        ASSOCIATION_START
        + (source_offset * 37 + slot * 113 + 11) % ASSOCIATION_SIZE
        for slot in range(PROJECTION_FAN_OUT)
    )
    if len(set(targets)) != PROJECTION_FAN_OUT:
        raise AssertionError("fixed projection must have distinct targets")
    return targets


def _pair_key(left: str, right: str) -> tuple[str, str]:
    return tuple(sorted((left, right)))  # type: ignore[return-value]


def _shuffled_turns(turns: list[Turn], seed: int, pair_count: int) -> list[Turn]:
    """Rotate B-side identities per temporal round, preserving event count and degree."""
    result: list[Turn] = []
    for turn in turns:
        shift = (turn.round_index + 1 + (seed % (pair_count - 1))) % pair_count
        partner_index = (turn.pair_index + shift) % pair_count
        result.append(Turn(
            turn_id=f"shuffled-{turn.turn_id}",
            round_index=turn.round_index,
            pair_index=turn.pair_index,
            active_concepts=(turn.active_concepts[0], f"concept:b{partner_index:02d}"),
        ))
    return result


def _metrics(labels: dict[tuple[str, str], bool],
             candidates: set[tuple[str, str]]) -> dict[str, object]:
    tp = fp = tn = fn = 0
    for pair, related in labels.items():
        predicted = pair in candidates
        if related and predicted:
            tp += 1
        elif related:
            fn += 1
        elif predicted:
            fp += 1
        else:
            tn += 1
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    specificity = tn / (tn + fp) if tn + fp else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    total = tp + fp + tn + fn
    return {
        "pairs": total,
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
        "accuracy": (tp + tn) / total if total else 0.0,
        "precision": precision,
        "recall": recall,
        "specificity": specificity,
        "f1": f1,
        "false_association_rate": fp / (fp + tn) if fp + tn else 0.0,
    }


class StructuralGrowthReference:
    """Bounded reference of the Android observer's proposal rules."""

    def __init__(self, concept_ids: Iterable[str]) -> None:
        ordered_ids = sorted(set(concept_ids))
        if not ordered_ids or len(ordered_ids) > 138:
            raise ValueError("dataset must contain 1..138 unique concepts")
        self.neuron_by_concept = {
            concept_id: CONCEPT_START + index
            for index, concept_id in enumerate(ordered_ids)
        }
        self.targets_by_concept = {
            concept_id: set(projection_targets(neuron_id))
            for concept_id, neuron_id in self.neuron_by_concept.items()
        }
        self.target_loads = {
            target: 0 for target in range(
                ASSOCIATION_START, ASSOCIATION_START + ASSOCIATION_SIZE
            )
        }
        for targets in self.targets_by_concept.values():
            for target in targets:
                self.target_loads[target] += 1
        self.traces: dict[tuple[str, str], list[str]] = {}
        self.last_sequence: dict[tuple[str, str], int] = {}
        self.grown_pairs: set[tuple[str, str]] = set()
        self.grown_edges_by_concept: dict[str, int] = {}
        self.proposals: list[dict[str, object]] = []
        self.sequence = 0
        self.operation_count = 0
        self.target_scan_count = 0
        self.observation_rows = 0
        self.observation_payload_bytes = 0
        self.max_pending_pairs = 0

    def observe(self, turn: Turn) -> None:
        self.sequence += 1
        self.observation_rows += 1
        active = sorted(set(turn.active_concepts))
        if len(active) > 64:
            raise ValueError("active concepts exceed Android observer turn cap")
        self.observation_payload_bytes += (
            24 + len(turn.turn_id.encode("utf-8"))
            + sum(8 + len(concept.encode("utf-8")) for concept in active)
        )
        for left_index in range(len(active)):
            for right_index in range(left_index + 1, len(active)):
                self.operation_count += 1
                left, right = active[left_index], active[right_index]
                if left not in self.targets_by_concept or right not in self.targets_by_concept:
                    raise ValueError("turn contains an unknown concept")
                left_targets = self.targets_by_concept[left]
                right_targets = self.targets_by_concept[right]
                if left_targets.intersection(right_targets):
                    continue
                key = _pair_key(left, right)
                if key in self.grown_pairs:
                    continue
                seen = self.traces.setdefault(key, [])
                if seen and self.sequence - self.last_sequence.get(key, self.sequence) > OBSERVATION_WINDOW:
                    seen.clear()
                if turn.turn_id not in seen:
                    seen.append(turn.turn_id)
                    if len(seen) > MIN_DISTINCT_TURNS:
                        del seen[0]
                self.last_sequence[key] = self.sequence
                if len(seen) < MIN_DISTINCT_TURNS:
                    continue
                if len(self.grown_pairs) >= MAX_GROWN_PAIRS:
                    continue
                if (
                    self.grown_edges_by_concept.get(left, 0) + 1 > MAX_GROWN_EDGES_PER_CONCEPT
                    or self.grown_edges_by_concept.get(right, 0) + 1 > MAX_GROWN_EDGES_PER_CONCEPT
                ):
                    continue
                target = self._select_target(key, left_targets | right_targets)
                if target is None:
                    continue
                proposal_id = "ans100-growth:" + _digest(f"{key[0]}|{key[1]}")[:32]
                self.proposals.append({
                    "proposal_id": proposal_id,
                    "concept_a": key[0],
                    "concept_b": key[1],
                    "target_neuron_id": target,
                    "supporting_distinct_turns": len(seen),
                    "connection_count": 2,
                })
                left_targets.add(target)
                right_targets.add(target)
                self.target_loads[target] += 2
                self.grown_pairs.add(key)
                self.grown_edges_by_concept[left] = self.grown_edges_by_concept.get(left, 0) + 1
                self.grown_edges_by_concept[right] = self.grown_edges_by_concept.get(right, 0) + 1
                self.traces.pop(key, None)
                self.last_sequence.pop(key, None)
        self.max_pending_pairs = max(self.max_pending_pairs, len(self.traces))

    def _select_target(self, key: tuple[str, str], excluded: set[int]) -> int | None:
        start = int(_digest(f"{key[0]}|{key[1]}")[:8], 16) % ASSOCIATION_SIZE
        best: int | None = None
        best_load = 2**31 - 1
        for offset in range(ASSOCIATION_SIZE):
            target = ASSOCIATION_START + ((start + offset) % ASSOCIATION_SIZE)
            self.target_scan_count += 1
            load = self.target_loads[target]
            if target in excluded or load >= MAX_INCOMING_PER_TARGET:
                continue
            if load < best_load:
                best, best_load = target, load
                if load == 0:
                    break
        return best

    def report_cost(self) -> dict[str, int]:
        # Logical serialized-payload estimate, not measured RSS or SQLite page size.
        payload_bytes = self.observation_payload_bytes
        for key, turn_ids in self.traces.items():
            payload_bytes += 32 + len(key[0].encode()) + len(key[1].encode())
            payload_bytes += sum(16 + len(turn_id.encode()) for turn_id in turn_ids)
        for proposal in self.proposals:
            payload_bytes += 64 + len(str(proposal["concept_a"]).encode())
            payload_bytes += len(str(proposal["concept_b"]).encode())
        return {
            "observation_rows": self.observation_rows,
            "tracked_pair_rows_peak": self.max_pending_pairs,
            "proposals": len(self.proposals),
            "new_synapses": len(self.proposals) * 2,
            "pair_comparison_operations": self.operation_count,
            "target_scan_steps": self.target_scan_count,
            "total_reference_operations": self.operation_count + self.target_scan_count,
            "estimated_logical_payload_bytes": payload_bytes,
            "max_target_in_degree_after_growth": max(self.target_loads.values(), default=0),
            "max_grown_edges_per_concept": max(self.grown_edges_by_concept.values(), default=0),
        }


def run_seed(seed: int) -> dict[str, object]:
    pairs, training, held_out = build_dataset(seed)
    concept_ids = [concept for pair in pairs for concept in (pair.concept_a, pair.concept_b)]
    labels = {_pair_key(pair.concept_a, pair.concept_b): pair.related for pair in pairs}
    all_turns = training + held_out

    fixed_targets = {
        concept_id: set(projection_targets(CONCEPT_START + index))
        for index, concept_id in enumerate(sorted(concept_ids))
    }
    fixed_candidates = {
        pair for pair in labels
        if fixed_targets[pair[0]].intersection(fixed_targets[pair[1]])
    }

    grown = StructuralGrowthReference(concept_ids)
    for turn in all_turns:
        grown.observe(turn)
    grown_candidates = fixed_candidates | grown.grown_pairs

    shuffled = StructuralGrowthReference(concept_ids)
    for turn in _shuffled_turns(all_turns, seed, len(pairs)):
        shuffled.observe(turn)
    shuffled_candidates = fixed_candidates | shuffled.grown_pairs

    return {
        "seed": seed,
        "protocol": {
            "training_turns": len(training),
            "held_out_turns": len(held_out),
            "training_observations_per_pair": 2,
            "held_out_third_observation_pairs": 6,
            "positive_pairs": sum(pair.related for pair in pairs),
            "negative_pairs": sum(not pair.related for pair in pairs),
            "hard_negative_pairs_with_third_observation": 2,
            "held_out_labels_used_by_learner": False,
            "threshold_selected_on_held_out": False,
            "concept_count": len(concept_ids),
            "fixed_projection_targets_per_concept": PROJECTION_FAN_OUT,
            "max_concept_input_slots": 138,
            "max_grown_pairs": MAX_GROWN_PAIRS,
            "max_grown_edges_per_concept": MAX_GROWN_EDGES_PER_CONCEPT,
            "max_incoming_edges_per_target": MAX_INCOMING_PER_TARGET,
            "observation_window_turns": OBSERVATION_WINDOW,
        },
        "conditions": {
            "fixed_topology": {
                "metrics": _metrics(labels, fixed_candidates),
                "candidate_pairs": len(fixed_candidates),
                "growth": {
                    "new_pairs": 0,
                    "new_synapses": 0,
                    "reference_operations": 0,
                    "estimated_logical_payload_bytes": 0,
                },
                "interpretation": "Necessary shared-target coverage only; no LIF spikes simulated.",
            },
            "grown_topology": {
                "metrics": _metrics(labels, grown_candidates),
                "candidate_pairs": len(grown_candidates),
                "growth": grown.report_cost(),
                "interpretation": "Fixed projection plus pairs receiving a new shared target after 3 distinct co-activation turns.",
            },
            "shuffled_activity_control": {
                "metrics": _metrics(labels, shuffled_candidates),
                "candidate_pairs": len(shuffled_candidates),
                "growth": shuffled.report_cost(),
                "interpretation": "Same event count with B-side concept identities rotated between rounds; evaluates sensitivity to broken temporal pair identity.",
            },
        },
        "grown_proposals": grown.proposals,
        "shuffled_proposals": shuffled.proposals,
    }


def run_benchmark(seeds: Iterable[int] = SEEDS) -> dict[str, object]:
    seed_list = tuple(seeds)
    if not seed_list or len(seed_list) != len(set(seed_list)):
        raise ValueError("at least one unique seed is required")
    runs = [run_seed(seed) for seed in seed_list]
    aggregate: dict[str, dict[str, float]] = {}
    for condition in ("fixed_topology", "grown_topology", "shuffled_activity_control"):
        keys = ("accuracy", "precision", "recall", "specificity", "f1", "false_association_rate")
        aggregate[condition] = {
            key: sum(float(run["conditions"][condition]["metrics"][key]) for run in runs) / len(runs)
            for key in keys
        }
    return {
        "benchmark": "ANS100_ACTIVITY_DRIVEN_STRUCTURAL_PLASTICITY_EVAL_V1",
        "status": "SYNTHETIC_REFERENCE_EXPERIMENT_NOT_ANDROID_RUNTIME",
        "promotion_gate": "CLOSED",
        "seed_count": len(runs),
        "seeds": list(seed_list),
        "aggregate_mean_metrics": aggregate,
        "runs": runs,
        "limits": [
            "Synthetic labels and synthetic event streams only; no real user data.",
            "Temporal co-activation is not semantic truth, similarity, causality, or evidence of understanding.",
            "No Android LIF membrane/spike dynamics, SQLite transactions, decoder execution, or device CPU/RAM measurements are simulated.",
            "Estimated logical payload bytes are a deterministic accounting proxy, not measured memory or database size.",
            "Fixed shared-target coverage is only a necessary-condition upper bound for decoder candidates.",
            "The experiment does not justify enabling STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seeds", default=",".join(map(str, SEEDS)))
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    seeds = tuple(int(item.strip()) for item in args.seeds.split(",") if item.strip())
    report = run_benchmark(seeds)
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.report:
        args.report.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
