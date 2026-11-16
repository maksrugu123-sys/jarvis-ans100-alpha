import json
import unittest

from tools.task_validation.neural_association_eval_v1 import (
    DatasetError,
    PairExample,
    _concept_slots,
    _initial_weights,
    _pair_shared_edges,
    _score_pair,
    _train_weights,
    concept_projection,
    evaluate,
    parse_dataset_lines,
    split_concept_disjoint,
    split_pair_disjoint,
)


def sample_dataset():
    # Small deterministic fixture only for testing protocol behavior; these are
    # synthetic labels and must never be reported as model-performance evidence.
    rows = []
    concepts = [f"c{i}" for i in range(12)]
    for i in range(len(concepts)):
        for j in range(i + 1, len(concepts)):
            rows.append(PairExample(
                concepts[i], concepts[j], (i + j) % 2 == 0,
                "explicit_user", f"fixture:{i}:{j}",
            ))
    return tuple(rows)


class NeuralAssociationEvalV1Tests(unittest.TestCase):
    def test_projection_matches_android_v4_formula_and_is_bounded(self):
        projection = concept_projection(1062)
        self.assertEqual(3, len(projection))
        expected = {
            1200 + ((1062 * 37 + slot * 113 + 11) % 1000)
            for slot in range(3)
        }
        self.assertEqual(expected, set(projection))
        self.assertTrue(all(value.startswith("ans100-v4-evidence-general-association-") for value in projection.values()))

    def test_pair_split_has_no_duplicate_pairs_and_both_labels(self):
        examples = sample_dataset()
        split = split_pair_disjoint(examples, 104729)
        train_keys = {item.pair_key for item in split.train}
        held_keys = {item.pair_key for item in split.held_out}
        self.assertFalse(train_keys & held_keys)
        self.assertEqual({False, True}, {item.related for item in split.train})
        self.assertEqual({False, True}, {item.related for item in split.held_out})

    def test_concept_split_has_no_shared_concepts(self):
        examples = sample_dataset()
        split = split_concept_disjoint(examples, 155921)
        train_concepts = {x.concept_a for x in split.train} | {x.concept_b for x in split.train}
        held_concepts = {x.concept_a for x in split.held_out} | {x.concept_b for x in split.held_out}
        self.assertFalse(train_concepts & held_concepts)
        self.assertGreater(split.excluded_cross_partition, 0)

    def test_untrusted_labels_and_duplicate_unordered_pairs_are_rejected(self):
        bad_source = json.dumps({
            "concept_a": "a", "concept_b": "b", "related": True,
            "source": "model_generated", "evidence_ref": "test:1",
        })
        with self.assertRaises(DatasetError):
            parse_dataset_lines([bad_source])
        row = {
            "concept_a": "a", "concept_b": "b", "related": True,
            "source": "explicit_user", "evidence_ref": "test:1",
        }
        with self.assertRaises(DatasetError):
            parse_dataset_lines([json.dumps(row), json.dumps({**row, "concept_a": "b", "concept_b": "a"})])

    def test_missing_class_or_too_few_rows_is_rejected(self):
        rows = [
            json.dumps({
                "concept_a": f"a{i}", "concept_b": f"b{i}", "related": True,
                "source": "explicit_user", "evidence_ref": f"test:{i}",
            })
            for i in range(8)
        ]
        with self.assertRaises(DatasetError):
            parse_dataset_lines(rows)

    def test_feedback_updates_only_edges_of_shared_pair_and_is_bounded(self):
        examples = sample_dataset()
        slots = _concept_slots(examples)
        weights = _initial_weights(examples)
        positive = next(x for x in examples if x.related and _pair_shared_edges(x.concept_a, x.concept_b, slots))
        negative = next(x for x in examples if not x.related and _pair_shared_edges(x.concept_a, x.concept_b, slots))
        before = dict(weights)
        after_positive = _train_weights([positive], slots, weights)
        touched = {edge for _, left, right in _pair_shared_edges(positive.concept_a, positive.concept_b, slots) for edge in (left, right)}
        self.assertTrue(all(after_positive[edge] >= before[edge] for edge in touched))
        self.assertTrue(all(after_positive[edge] == before[edge] for edge in set(before) - touched))
        after_negative = _train_weights([negative], slots, weights)
        neg_touched = {edge for _, left, right in _pair_shared_edges(negative.concept_a, negative.concept_b, slots) for edge in (left, right)}
        self.assertTrue(all(after_negative[edge] <= before[edge] for edge in neg_touched))
        self.assertTrue(all(0.05 <= value <= 1.0 for value in after_positive.values()))

    def test_pair_score_is_zero_without_shared_targets(self):
        examples = sample_dataset()
        slots = _concept_slots(examples)
        weights = _initial_weights(examples)
        pair = next(x for x in examples if not _pair_shared_edges(x.concept_a, x.concept_b, slots))
        self.assertEqual(0.0, _score_pair(pair, slots, weights))

    def test_evaluation_is_deterministic_and_keeps_promotion_gate_closed(self):
        examples = sample_dataset()
        first = evaluate(examples, seeds=(11, 29), split_mode="pair_disjoint")
        second = evaluate(examples, seeds=(11, 29), split_mode="pair_disjoint")
        self.assertEqual(first, second)
        self.assertEqual("CLOSED", first["promotion_gate"])
        self.assertEqual(
            "offline_topology_weight_proxy_not_android_runtime",
            first["evaluation_kind"],
        )
        self.assertEqual(
            {"learned", "fixed", "shuffled_labels", "random_weights"},
            set(first["models"]),
        )

    def test_concept_disjoint_evaluation_reports_unseen_concept_track(self):
        report = evaluate(sample_dataset(), seeds=(7,), split_mode="concept_disjoint")
        split = report["splits"][0]
        self.assertGreater(split["excluded_cross_partition_pairs"], 0)
        self.assertGreater(split["train_pairs"], 0)
        self.assertGreater(split["held_out_pairs"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
