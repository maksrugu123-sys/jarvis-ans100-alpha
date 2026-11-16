"""Held-out evaluation for the ANS-100 concept-association feedback rule.

This is an offline topology/weight proxy, NOT an Android LIF network replay.
It mirrors the v4 concept projection and bounded per-edge reward update so
that data leakage, label controls, and the proposed learning signal can be
examined before learned weights are allowed to influence live decisions.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

TOPOLOGY_INPUT_START = 1_062
TOPOLOGY_INPUT_CAPACITY = 138
ASSOCIATION_START = 1_200
ASSOCIATION_SIZE = 1_000
PROJECTION_FAN_OUT = 3
PROJECTION_MULTIPLIER = 37
PROJECTION_SLOT_STEP = 113
PROJECTION_SALT = 11
INITIAL_WEIGHT = 0.75
LEARNING_RATE = 0.02
MAX_DELTA = 0.01
MIN_WEIGHT = 0.05
MAX_WEIGHT = 1.0
MAX_DATASET_ROWS = 20_000
TRUSTED_SOURCES = {"explicit_user", "verified_external_outcome"}


class DatasetError(ValueError):
    """Raised when a feedback dataset is malformed or unsafe to evaluate."""


@dataclass(frozen=True)
class PairExample:
    concept_a: str
    concept_b: str
    related: bool
    source: str
    evidence_ref: str

    @property
    def pair_key(self) -> tuple[str, str]:
        return tuple(sorted((self.concept_a, self.concept_b)))


@dataclass(frozen=True)
class DatasetSplit:
    train: tuple[PairExample, ...]
    held_out: tuple[PairExample, ...]
    excluded_cross_partition: int = 0


def _canonical_pair(a: str, b: str) -> tuple[str, str]:
    return tuple(sorted((a, b)))


def parse_dataset_lines(lines: Iterable[str]) -> tuple[PairExample, ...]:
    examples: list[PairExample] = []
    seen: set[tuple[str, str]] = set()
    for line_number, raw in enumerate(lines, start=1):
        if not raw.strip():
            continue
        try:
            row = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise DatasetError("line {} is not valid JSON".format(line_number)) from exc
        if not isinstance(row, dict):
            raise DatasetError("line {} must contain a JSON object".format(line_number))
        a, b = row.get("concept_a"), row.get("concept_b")
        related, source, evidence_ref = (
            row.get("related"), row.get("source"), row.get("evidence_ref")
        )
        if not isinstance(a, str) or not isinstance(b, str):
            raise DatasetError("line {} concept IDs must be strings".format(line_number))
        a, b = a.strip(), b.strip()
        if not a or not b or a == b or len(a) > 96 or len(b) > 96:
            raise DatasetError("line {} has invalid concept IDs".format(line_number))
        if type(related) is not bool:
            raise DatasetError("line {} related must be a JSON boolean".format(line_number))
        if not isinstance(source, str) or source not in TRUSTED_SOURCES:
            raise DatasetError("line {} has an untrusted feedback source".format(line_number))
        if not isinstance(evidence_ref, str) or not evidence_ref.strip() or len(evidence_ref) > 256:
            raise DatasetError("line {} needs a bounded evidence_ref".format(line_number))
        key = _canonical_pair(a, b)
        if key in seen:
            raise DatasetError("duplicate unordered concept pair at line {}".format(line_number))
        seen.add(key)
        examples.append(PairExample(a, b, related, source, evidence_ref.strip()))
        if len(examples) > MAX_DATASET_ROWS:
            raise DatasetError("dataset exceeds the {}-row limit".format(MAX_DATASET_ROWS))
    if len(examples) < 8:
        raise DatasetError("at least 8 unique labeled pairs are required")
    concepts = {item.concept_a for item in examples} | {item.concept_b for item in examples}
    if len(concepts) > TOPOLOGY_INPUT_CAPACITY:
        raise DatasetError("dataset has more concepts than the 138 reserved Android input slots")
    labels = Counter(item.related for item in examples)
    if labels[True] < 2 or labels[False] < 2:
        raise DatasetError("at least two positive and two negative pairs are required for a held-out split")
    return tuple(examples)


def load_dataset(path: str | Path) -> tuple[PairExample, ...]:
    try:
        with Path(path).open("r", encoding="utf-8") as stream:
            return parse_dataset_lines(stream)
    except OSError as exc:
        raise DatasetError("cannot read dataset: {}".format(exc)) from exc


def _stable_digest(value: str) -> bytes:
    return hashlib.sha256(value.encode("utf-8")).digest()


def split_pair_disjoint(
    examples: Sequence[PairExample], seed: int, held_out_fraction: float = 0.25
) -> DatasetSplit:
    if not 0.1 <= held_out_fraction <= 0.5:
        raise ValueError("held_out_fraction must be between 0.1 and 0.5")
    train: list[PairExample] = []
    held_out: list[PairExample] = []
    for label in (False, True):
        bucket = [item for item in examples if item.related is label]
        bucket.sort(key=lambda item: _stable_digest(
            "{}|pair|{}|{}".format(seed, item.pair_key[0], item.pair_key[1])
        ))
        count = max(1, min(len(bucket) - 1, round(len(bucket) * held_out_fraction)))
        held_out.extend(bucket[:count])
        train.extend(bucket[count:])
    train.sort(key=lambda item: item.pair_key)
    held_out.sort(key=lambda item: item.pair_key)
    train_keys = {item.pair_key for item in train}
    test_keys = {item.pair_key for item in held_out}
    if train_keys & test_keys:
        raise AssertionError("pair leakage detected")
    return DatasetSplit(tuple(train), tuple(held_out))


def split_concept_disjoint(
    examples: Sequence[PairExample], seed: int
) -> DatasetSplit:
    concepts = sorted(
        {item.concept_a for item in examples} | {item.concept_b for item in examples},
        key=lambda value: _stable_digest("{}|concept|{}".format(seed, value)),
    )
    boundary = max(2, min(len(concepts) - 2, round(len(concepts) * 0.6)))
    train_concepts = set(concepts[:boundary])
    held_concepts = set(concepts[boundary:])
    train: list[PairExample] = []
    held_out: list[PairExample] = []
    excluded = 0
    for item in examples:
        endpoints = {item.concept_a, item.concept_b}
        if endpoints <= train_concepts:
            train.append(item)
        elif endpoints <= held_concepts:
            held_out.append(item)
        else:
            excluded += 1
    if not train or not held_out:
        raise DatasetError("concept-disjoint split produced an empty partition")
    train_concepts = {item.concept_a for item in train} | {item.concept_b for item in train}
    held_concepts = {item.concept_a for item in held_out} | {item.concept_b for item in held_out}
    if train_concepts & held_concepts:
        raise AssertionError("concept leakage detected")
    for name, partition in (("training", train), ("held-out", held_out)):
        labels = {item.related for item in partition}
        if labels != {False, True}:
            raise DatasetError("{} concept partition must contain both labels".format(name))
    train.sort(key=lambda item: item.pair_key)
    held_out.sort(key=lambda item: item.pair_key)
    return DatasetSplit(tuple(train), tuple(held_out), excluded)


def _concept_slots(examples: Sequence[PairExample]) -> dict[str, int]:
    concepts = sorted(
        {item.concept_a for item in examples} | {item.concept_b for item in examples}
    )
    if len(concepts) > TOPOLOGY_INPUT_CAPACITY:
        raise DatasetError("concept count exceeds reserved neural input slots")
    return {concept: TOPOLOGY_INPUT_START + index for index, concept in enumerate(concepts)}


def concept_projection(neuron_id: int) -> dict[int, str]:
    if not TOPOLOGY_INPUT_START <= neuron_id < TOPOLOGY_INPUT_START + TOPOLOGY_INPUT_CAPACITY:
        raise ValueError("neuron ID is outside the reserved concept-input range")
    result: dict[int, str] = {}
    source_offset = neuron_id
    for slot in range(PROJECTION_FAN_OUT):
        target_offset = (
            source_offset * PROJECTION_MULTIPLIER
            + slot * PROJECTION_SLOT_STEP
            + PROJECTION_SALT
        ) % ASSOCIATION_SIZE
        target_id = ASSOCIATION_START + target_offset
        result[target_id] = "ans100-v4-evidence-general-association-{}-{}".format(
            neuron_id, target_id
        )
    return result


def _pair_shared_edges(
    a: str, b: str, slots: dict[str, int]
) -> list[tuple[int, str, str]]:
    left = concept_projection(slots[a])
    right = concept_projection(slots[b])
    return [
        (target, left[target], right[target])
        for target in sorted(set(left) & set(right))
    ]


def _initial_weights(examples: Sequence[PairExample]) -> dict[str, float]:
    slots = _concept_slots(examples)
    weights: dict[str, float] = {}
    for concept, neuron_id in slots.items():
        for connection_id in concept_projection(neuron_id).values():
            weights[connection_id] = INITIAL_WEIGHT
    return weights


def _train_weights(
    examples: Sequence[PairExample],
    slots: dict[str, int],
    initial: dict[str, float] | None = None,
) -> dict[str, float]:
    weights = dict(initial) if initial is not None else _initial_weights(examples)
    for item in examples:
        shared = _pair_shared_edges(item.concept_a, item.concept_b, slots)
        if not shared:
            continue
        eligibility = min(1.0, len(shared) / PROJECTION_FAN_OUT)
        reward = 1.0 if item.related else -1.0
        requested = max(-MAX_DELTA, min(MAX_DELTA, LEARNING_RATE * reward * eligibility))
        for _, left_edge, right_edge in shared:
            for edge in (left_edge, right_edge):
                weights[edge] = max(MIN_WEIGHT, min(MAX_WEIGHT, weights[edge] + requested))
    return weights


def _score_pair(
    item: PairExample, slots: dict[str, int], weights: dict[str, float]
) -> float:
    shared = _pair_shared_edges(item.concept_a, item.concept_b, slots)
    # Offline proxy: average convergent input strength normalized by three
    # possible targets. This is not the Android LIF membrane/spike calculation.
    return sum(weights[left] + weights[right] for _, left, right in shared) / (
        2.0 * PROJECTION_FAN_OUT
    )


def _projection_coverage(
    examples: Sequence[PairExample], slots: dict[str, int]
) -> dict[str, object]:
    """Report the topology-only upper bound on pairs the decoder can compare.

    A shared projection target is necessary for a candidate, but an actual
    Android candidate additionally requires both synapses to activate and the
    common association neuron to spike during the same turn.
    """
    histogram = Counter(
        len(_pair_shared_edges(item.concept_a, item.concept_b, slots))
        for item in examples
    )
    total = len(examples)
    eligible = total - histogram.get(0, 0)
    return {
        "pairs": total,
        "projection_eligible_pairs": eligible,
        "no_shared_projection_pairs": histogram.get(0, 0),
        "projection_coverage_upper_bound": round(eligible / total, 8) if total else 0.0,
        "shared_target_count_histogram": {
            str(count): histogram.get(count, 0) for count in range(PROJECTION_FAN_OUT + 1)
        },
    }


def _balanced_accuracy(rows: Sequence[tuple[bool, bool]]) -> float:
    positives = [row for row in rows if row[0]]
    negatives = [row for row in rows if not row[0]]
    if not positives or not negatives:
        return 0.0
    return 0.5 * (
        sum(actual == predicted for actual, predicted in positives) / len(positives)
        + sum(actual == predicted for actual, predicted in negatives) / len(negatives)
    )


def _choose_threshold(
    examples: Sequence[PairExample], scores: Sequence[float]
) -> float:
    unique = sorted(set(scores))
    candidates = [0.0, 1.0]
    candidates.extend((left + right) / 2.0 for left, right in zip(unique, unique[1:]))
    best = (-1.0, 2.0)
    for threshold in candidates:
        predictions = [
            (item.related, score >= threshold)
            for item, score in zip(examples, scores)
        ]
        metric = _balanced_accuracy(predictions)
        # Deterministic tie-break: prefer the higher threshold (more conservative).
        if (metric, threshold) > best:
            best = (metric, threshold)
    return best[1]


def _metrics(
    examples: Sequence[PairExample], scores: Sequence[float], threshold: float
) -> dict[str, object]:
    tp = fp = tn = fn = 0
    for item, score in zip(examples, scores):
        predicted = score >= threshold
        if item.related and predicted:
            tp += 1
        elif item.related and not predicted:
            fn += 1
        elif not item.related and predicted:
            fp += 1
        else:
            tn += 1
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    specificity = tn / (tn + fp) if tn + fp else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    total = tp + fp + tn + fn
    return {
        "n": total,
        "threshold_selected_on_training_only": round(threshold, 8),
        "accuracy": round((tp + tn) / total, 8) if total else 0.0,
        "balanced_accuracy": round((recall + specificity) / 2.0, 8),
        "precision": round(precision, 8),
        "recall": round(recall, 8),
        "specificity": round(specificity, 8),
        "f1": round(f1, 8),
        "confusion_matrix": {"tp": tp, "fp": fp, "tn": tn, "fn": fn},
    }


def _dataset_digest(examples: Sequence[PairExample]) -> str:
    payload = [
        {
            "a": item.pair_key[0],
            "b": item.pair_key[1],
            "related": item.related,
            "source": item.source,
            "evidence_ref": item.evidence_ref,
        }
        for item in sorted(examples, key=lambda row: row.pair_key)
    ]
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def evaluate(
    examples: Sequence[PairExample],
    seeds: Sequence[int] = (104729, 130363, 155921, 181081),
    split_mode: str = "pair_disjoint",
) -> dict[str, object]:
    if split_mode not in {"pair_disjoint", "concept_disjoint"}:
        raise ValueError("unsupported split mode")
    # Re-validate even when called as a library API, not only through the CLI.
    examples = parse_dataset_lines(
        json.dumps({
            "concept_a": item.concept_a,
            "concept_b": item.concept_b,
            "related": item.related,
            "source": item.source,
            "evidence_ref": item.evidence_ref,
        }, ensure_ascii=False)
        for item in examples
    )
    if not seeds or len(set(seeds)) != len(seeds):
        raise ValueError("seeds must be a non-empty unique sequence")
    result_rows: dict[str, list[dict[str, object]]] = {
        name: [] for name in ("learned", "fixed", "shuffled_labels", "random_weights")
    }
    split_rows: list[dict[str, int]] = []
    for seed in seeds:
        split = (
            split_pair_disjoint(examples, seed)
            if split_mode == "pair_disjoint"
            else split_concept_disjoint(examples, seed)
        )
        all_rows = split.train + split.held_out
        # Slot assignment is deterministic over the full declared dataset, not
        # separately rebuilt from the training subset.
        slots = _concept_slots(examples)
        initial = _initial_weights(examples)
        learned = _train_weights(split.train, slots, initial)
        fixed = dict(initial)

        shuffled_labels = list(split.train)
        labels = [item.related for item in shuffled_labels]
        random.Random(seed ^ 0x5A17).shuffle(labels)
        shuffled_train = [
            PairExample(item.concept_a, item.concept_b, label, item.source, item.evidence_ref)
            for item, label in zip(shuffled_labels, labels)
        ]
        shuffled = _train_weights(shuffled_train, slots, initial)

        rng = random.Random(seed ^ 0xA51C)
        random_weights = {
            edge: rng.uniform(MIN_WEIGHT, MAX_WEIGHT) for edge in initial
        }
        models = {
            "learned": learned,
            "fixed": fixed,
            "shuffled_labels": shuffled,
            "random_weights": random_weights,
        }
        train_scores = {
            name: [_score_pair(item, slots, weights) for item in split.train]
            for name, weights in models.items()
        }
        test_scores = {
            name: [_score_pair(item, slots, weights) for item in split.held_out]
            for name, weights in models.items()
        }
        for name in models:
            threshold = _choose_threshold(split.train, train_scores[name])
            result_rows[name].append(_metrics(split.held_out, test_scores[name], threshold))
        split_rows.append({
            "seed": seed,
            "train_pairs": len(split.train),
            "train_projection_coverage": _projection_coverage(split.train, slots),
            "held_out_projection_coverage": _projection_coverage(split.held_out, slots),
            "held_out_pairs": len(split.held_out),
            "excluded_cross_partition_pairs": split.excluded_cross_partition,
            "train_positive": sum(item.related for item in split.train),
            "train_negative": sum(not item.related for item in split.train),
            "held_out_positive": sum(item.related for item in split.held_out),
            "held_out_negative": sum(not item.related for item in split.held_out),
        })

    summaries: dict[str, object] = {}
    metric_names = ("accuracy", "balanced_accuracy", "precision", "recall", "specificity", "f1")
    for name, rows in result_rows.items():
        means = {
            metric: round(sum(float(row[metric]) for row in rows) / len(rows), 8)
            for metric in metric_names
        }
        summaries[name] = {"macro_mean": means, "per_seed": rows}
    return {
        "schema": "ANS100_NEURAL_ASSOCIATION_EVAL_V1",
        "evaluation_kind": "offline_topology_weight_proxy_not_android_runtime",
        "split_mode": split_mode,
        "dataset_sha256": _dataset_digest(examples),
        "dataset_pairs": len(examples),
        "dataset_concepts": len({x.concept_a for x in examples} | {x.concept_b for x in examples}),
        "projection_coverage": _projection_coverage(examples, _concept_slots(examples)),
        "trusted_label_sources": sorted({x.source for x in examples}),
        "seeds": list(seeds),
        "splits": split_rows,
        "models": summaries,
        "promotion_gate": "CLOSED",
        "limitations": [
            "No real user-labeled dataset is bundled; all labels must come from explicit user feedback or verified external outcomes.",
            "The projection mapping and reward delta mirror source constants, but this evaluator does not execute Android LIF dynamics, SQLite, or device inference.",
            "The score is a topology/weight proxy, not a calibrated probability or a semantic understanding metric.",
            "Learned weights must not affect live answer/decision competition based on this evaluation alone.",
        ],
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, help="UTF-8 JSONL of explicitly labeled concept pairs")
    parser.add_argument("--split", choices=("pair_disjoint", "concept_disjoint"), default="pair_disjoint")
    parser.add_argument("--seeds", default="104729,130363,155921,181081")
    parser.add_argument("--report", help="Optional JSON report path; stdout when omitted")
    args = parser.parse_args(argv)
    try:
        examples = load_dataset(args.dataset)
        seeds = tuple(int(value.strip()) for value in args.seeds.split(",") if value.strip())
        report = evaluate(examples, seeds=seeds, split_mode=args.split)
    except (DatasetError, ValueError) as exc:
        print("evaluation blocked: {}".format(exc), file=sys.stderr)
        return 2
    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.report:
        Path(args.report).write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
