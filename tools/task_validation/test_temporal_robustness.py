"""Tests for the deterministic temporal robustness benchmark."""
import unittest

from tools.task_validation.temporal_robustness import (
    HELD_OUT_EPISODES,
    TRAIN_EPISODES,
    run_benchmark,
)


class TemporalRobustnessBenchmarkTests(unittest.TestCase):
    def test_training_and_held_out_input_signatures_are_disjoint(self):
        signature = lambda episode: (episode.gaps_ms, episode.strengths)
        train = {signature(episode) for episode in TRAIN_EPISODES}
        held_out = {signature(episode) for episode in HELD_OUT_EPISODES}
        self.assertTrue(train.isdisjoint(held_out))

    def test_timing_ranges_partially_overlap(self):
        episodes = TRAIN_EPISODES + HELD_OUT_EPISODES
        burst = [
            gap for episode in episodes if episode.label == "BURST"
            for gap in episode.gaps_ms
        ]
        sparse = [
            gap for episode in episodes if episode.label == "SPARSE"
            for gap in episode.gaps_ms
        ]
        self.assertGreaterEqual(max(burst), min(sparse))
        self.assertGreaterEqual(max(sparse), min(burst))

    def test_splits_are_balanced_and_use_three_pulses(self):
        for episodes in (TRAIN_EPISODES, HELD_OUT_EPISODES):
            labels = [episode.label for episode in episodes]
            self.assertEqual(labels.count("BURST"), labels.count("SPARSE"))
            self.assertTrue(all(len(episode.strengths) == 3 for episode in episodes))
            self.assertTrue(all(len(episode.gaps_ms) == 2 for episode in episodes))

    def test_fixed_baseline_is_not_perfect_on_held_out_challenge(self):
        report = run_benchmark()
        fixed = next(
            item for item in report["conditions"] if item["condition"] == "fixed"
        )
        self.assertLess(fixed["held_out"]["accuracy"], 1.0)

    def test_results_are_deterministic(self):
        self.assertEqual(run_benchmark(), run_benchmark())

    def test_weights_remain_bounded_and_held_out_is_frozen(self):
        report = run_benchmark()
        for condition in report["conditions"]:
            self.assertGreaterEqual(condition["initial_weight"], 0.0)
            self.assertLessEqual(condition["initial_weight"], 1.0)
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

    def test_held_out_records_do_not_apply_reward(self):
        report = run_benchmark()
        for condition in report["conditions"]:
            for episode in condition["held_out_episode_results"]:
                self.assertIsNone(episode["reward_value"])

    def test_starter_weight_is_not_modified(self):
        report = run_benchmark()
        self.assertTrue(all(
            condition["initial_weight"] == 0.5
            for condition in report["conditions"]
        ))
        self.assertFalse(report["protocol"]["starter_profile_modified"])


if __name__ == "__main__":
    unittest.main()
