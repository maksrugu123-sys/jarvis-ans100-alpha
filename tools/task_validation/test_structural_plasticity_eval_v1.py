"""Tests for the deterministic structural-plasticity reference evaluator."""
import unittest

from tools.task_validation.structural_plasticity_eval_v1 import (
    CONCEPT_START,
    SEEDS,
    StructuralGrowthReference,
    _metrics,
    _pair_key,
    build_dataset,
    projection_targets,
    run_benchmark,
    run_seed,
)


class StructuralPlasticityEvalV1Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = run_benchmark()

    def test_temporal_split_is_disjoint_and_keeps_two_training_observations(self):
        for seed in SEEDS:
            pairs, training, held_out = build_dataset(seed)
            self.assertEqual(len(pairs), 8)
            self.assertEqual(len(training), 16)
            self.assertEqual(len(held_out), 6)
            self.assertTrue({turn.turn_id for turn in training}.isdisjoint(
                {turn.turn_id for turn in held_out}
            ))
            self.assertEqual({turn.pair_index for turn in training}, set(range(8)))
            self.assertEqual({turn.pair_index for turn in held_out}, {0, 1, 2, 3, 4, 5})

    def test_projection_matches_three_distinct_bounded_targets(self):
        for neuron_id in range(CONCEPT_START, CONCEPT_START + 138):
            targets = projection_targets(neuron_id)
            self.assertEqual(len(targets), 3)
            self.assertEqual(len(set(targets)), 3)
            self.assertTrue(all(1200 <= target < 2200 for target in targets))

    def test_no_pair_grows_before_third_distinct_observation(self):
        pairs, training, _ = build_dataset(SEEDS[0])
        concept_ids = [value for pair in pairs for value in (pair.concept_a, pair.concept_b)]
        learner = StructuralGrowthReference(concept_ids)
        for turn in training:
            learner.observe(turn)
        self.assertEqual(learner.grown_pairs, set())
        self.assertEqual(learner.proposals, [])

    def test_true_stream_grows_four_positive_and_two_hard_negative_pairs(self):
        result = run_seed(SEEDS[0])
        proposals = result["grown_proposals"]
        grown = {_pair_key(item["concept_a"], item["concept_b"]) for item in proposals}
        self.assertEqual(len(grown), 6)
        self.assertEqual(len(proposals) * 2, 12)
        metrics = result["conditions"]["grown_topology"]["metrics"]
        self.assertEqual(metrics["tp"], 4)
        self.assertEqual(metrics["fp"], 2)
        self.assertEqual(metrics["fn"], 0)
        self.assertEqual(metrics["tn"], 2)

    def test_shuffled_temporal_identity_does_not_create_three_turn_pairs(self):
        result = run_seed(SEEDS[0])
        self.assertEqual(result["shuffled_proposals"], [])
        self.assertEqual(
            result["conditions"]["shuffled_activity_control"]["growth"]["new_synapses"],
            0,
        )

    def test_hard_negatives_expose_coactivation_false_associations(self):
        result = run_seed(SEEDS[0])
        metrics = result["conditions"]["grown_topology"]["metrics"]
        self.assertGreater(metrics["false_association_rate"], 0.0)
        self.assertLess(metrics["precision"], 1.0)

    def test_target_load_and_concept_degree_respect_runtime_caps(self):
        for run in self.report["runs"]:
            growth = run["conditions"]["grown_topology"]["growth"]
            self.assertLessEqual(growth["max_target_in_degree_after_growth"], 12)
            self.assertLessEqual(growth["max_grown_edges_per_concept"], 4)
            self.assertEqual(growth["new_synapses"], growth["proposals"] * 2)

    def test_reports_are_deterministic_and_explicitly_synthetic(self):
        self.assertEqual(self.report, run_benchmark())
        self.assertEqual(self.report["status"], "SYNTHETIC_REFERENCE_EXPERIMENT_NOT_ANDROID_RUNTIME")
        self.assertEqual(self.report["promotion_gate"], "CLOSED")
        self.assertTrue(any("not semantic truth" in item for item in self.report["limits"]))

    def test_metrics_handle_empty_prediction_without_division_error(self):
        metrics = _metrics({_pair_key("a", "b"): True}, set())
        self.assertEqual(metrics["precision"], 0.0)
        self.assertEqual(metrics["recall"], 0.0)
        self.assertEqual(metrics["fn"], 1)


if __name__ == "__main__":
    unittest.main()
