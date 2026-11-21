"""Tests for the deterministic multi-seed temporal robustness benchmark."""
import unittest

from tools.task_validation.temporal_robustness_v2 import (
    BURST_GAP_RANGE_MS,
    CONDITION_MODES,
    HELD_OUT_PER_CLASS,
    RUN_SEEDS,
    SPARSE_GAP_RANGE_MS,
    STRENGTH_LEVELS,
    TRAIN_PER_CLASS,
    _build_dataset,
    run_benchmark,
)


class TemporalRobustnessV2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = run_benchmark()

    def test_protocol_uses_multiple_balanced_independent_runs(self):
        self.assertEqual(len(RUN_SEEDS), 4)
        self.assertEqual(len(self.report["runs"]), len(RUN_SEEDS))
        for run in self.report["runs"]:
            self.assertEqual(run["dataset"]["training_episodes"], TRAIN_PER_CLASS * 2)
            self.assertEqual(
                run["dataset"]["held_out_episodes"], HELD_OUT_PER_CLASS * 2
            )
            self.assertEqual(
                run["dataset"]["training_class_counts"],
                {"BURST": TRAIN_PER_CLASS, "SPARSE": TRAIN_PER_CLASS},
            )
            self.assertEqual(
                run["dataset"]["held_out_class_counts"],
                {"BURST": HELD_OUT_PER_CLASS, "SPARSE": HELD_OUT_PER_CLASS},
            )

    def test_training_and_held_out_signatures_are_disjoint_and_unique(self):
        for seed in RUN_SEEDS:
            train, held_out = _build_dataset(seed)
            train_signatures = {
                (episode.gaps_ms, episode.strengths) for episode in train
            }
            held_signatures = {
                (episode.gaps_ms, episode.strengths) for episode in held_out
            }
            self.assertTrue(train_signatures.isdisjoint(held_signatures))
            all_signatures = [
                (episode.gaps_ms, episode.strengths)
                for episode in train + held_out
            ]
            self.assertEqual(len(all_signatures), len(set(all_signatures)))

    def test_class_timing_ranges_partially_overlap(self):
        self.assertEqual(
            (
                max(BURST_GAP_RANGE_MS[0], SPARSE_GAP_RANGE_MS[0]),
                min(BURST_GAP_RANGE_MS[1], SPARSE_GAP_RANGE_MS[1]),
            ),
            (7, 8),
        )

    def test_all_examples_use_three_pulses_and_shared_strength_grid(self):
        for seed in RUN_SEEDS:
            train, held_out = _build_dataset(seed)
            for episode in train + held_out:
                self.assertEqual(len(episode.gaps_ms), 2)
                self.assertEqual(len(episode.strengths), 3)
                self.assertTrue(all(
                    strength in STRENGTH_LEVELS for strength in episode.strengths
                ))

    def test_seeded_datasets_are_reproducible_and_different_between_seeds(self):
        first_a = _build_dataset(RUN_SEEDS[0])
        first_b = _build_dataset(RUN_SEEDS[0])
        second = _build_dataset(RUN_SEEDS[1])
        self.assertEqual(first_a, first_b)
        first_signatures = {
            (episode.gaps_ms, episode.strengths)
            for episode in first_a[0] + first_a[1]
        }
        second_signatures = {
            (episode.gaps_ms, episode.strengths)
            for episode in second[0] + second[1]
        }
        self.assertNotEqual(first_signatures, second_signatures)

    def test_episode_order_is_deterministic_and_not_class_blocked(self):
        for seed in RUN_SEEDS:
            train_a, held_a = _build_dataset(seed)
            train_b, held_b = _build_dataset(seed)
            self.assertEqual(train_a, train_b)
            self.assertEqual(held_a, held_b)
            for episodes in (train_a, held_a):
                labels = [episode.label for episode in episodes]
                transitions = sum(
                    left != right for left, right in zip(labels, labels[1:])
                )
                self.assertGreater(transitions, 1)

    def test_report_contains_all_conditions_and_aggregate_metrics(self):
        self.assertEqual(
            {item["condition"] for item in self.report["aggregate_conditions"]},
            set(CONDITION_MODES),
        )
        for condition in self.report["aggregate_conditions"]:
            held = condition["held_out"]
            self.assertEqual(held["episodes"], len(RUN_SEEDS) * HELD_OUT_PER_CLASS * 2)
            for key in (
                "accuracy",
                "balanced_accuracy",
                "macro_f1",
                "precision_burst",
                "recall_burst",
                "specificity_sparse",
                "tp",
                "fp",
                "tn",
                "fn",
            ):
                self.assertIn(key, held)
            self.assertEqual(
                condition["held_out_accuracy_across_runs"]["runs"],
                len(RUN_SEEDS),
            )

    def test_rewarded_and_neutral_rstdp_are_separately_observable(self):
        for run in self.report["runs"]:
            rewarded = next(
                item for item in run["conditions"] if item["condition"] == "rstdp"
            )
            neutral = next(
                item for item in run["conditions"]
                if item["condition"] == "rstdp_neutral"
            )
            self.assertTrue(all(
                episode["reward_value"] in (0.0, 1.0)
                for episode in rewarded["training_episode_results"]
            ))
            self.assertTrue(all(
                episode["reward_value"] is not None
                and episode["reward_modulation"] == 0.0
                for episode in neutral["training_episode_results"]
            ))
            self.assertEqual(neutral["weight_after_training"], neutral["initial_weight"])
            self.assertTrue(all(
                episode["reward_value"] is None
                for episode in rewarded["held_out_episode_results"]
            ))
            self.assertTrue(all(
                episode["reward_value"] is None
                for episode in neutral["held_out_episode_results"]
            ))

    def test_weights_are_bounded_and_held_out_is_frozen(self):
        for run in self.report["runs"]:
            for condition in run["conditions"]:
                self.assertGreaterEqual(condition["weight_after_training"], 0.0)
                self.assertLessEqual(condition["weight_after_training"], 1.0)
                self.assertTrue(condition["held_out_weights_frozen"])
                self.assertEqual(
                    condition["weight_after_held_out"],
                    condition["weight_after_training"],
                )
                for point in condition["training_weight_trajectory"]:
                    self.assertGreaterEqual(point["weight_after"], 0.0)
                    self.assertLessEqual(point["weight_after"], 1.0)

    def test_starter_profile_is_not_modified(self):
        self.assertFalse(self.report["protocol"]["starter_profile_modified"])
        for run in self.report["runs"]:
            for condition in run["conditions"]:
                self.assertEqual(condition["initial_weight"], 0.5)


if __name__ == "__main__":
    unittest.main()
