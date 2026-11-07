"""Tests for the deterministic temporal-pattern task benchmark."""
import unittest

from tools.task_validation.temporal_patterns import (
    TEST_EPISODES,
    TRAIN_EPISODES,
    run_benchmark,
)


class TemporalPatternBenchmarkTests(unittest.TestCase):
    def test_training_and_held_out_intervals_are_disjoint(self):
        train = {(e.label, e.interval_ms) for e in TRAIN_EPISODES}
        held_out = {(e.label, e.interval_ms) for e in TEST_EPISODES}
        self.assertTrue(train.isdisjoint(held_out))

    def test_baseline_solves_the_separated_control_task(self):
        report = run_benchmark()
        fixed = next(x for x in report["conditions"] if x["condition"] == "fixed")
        self.assertEqual(fixed["held_out"]["accuracy"], 1.0)
        self.assertEqual(fixed["held_out"]["fp"], 0)
        self.assertEqual(fixed["held_out"]["fn"], 0)

    def test_results_are_deterministic(self):
        self.assertEqual(run_benchmark(), run_benchmark())

    def test_learning_weights_remain_bounded(self):
        report = run_benchmark()
        for condition in report["conditions"]:
            self.assertGreaterEqual(condition["weight_after_training"], 0.0)
            self.assertLessEqual(condition["weight_after_training"], 1.0)

    def test_starter_weight_is_not_mutated(self):
        report = run_benchmark()
        self.assertTrue(all(x["initial_weight"] == 0.5 for x in report["conditions"]))


if __name__ == "__main__":
    unittest.main()
