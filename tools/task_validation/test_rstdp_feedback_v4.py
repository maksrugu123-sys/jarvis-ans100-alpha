"""Contract and protocol tests for V4 reward feedback/error recovery."""
import json
import unittest

from tools.task_validation.rstdp_feedback_v4 import (
    CANONICAL_STARTER_WEIGHT,
    CONDITION_MODES,
    DECISION_DEADLINE_MS,
    DIAGNOSTIC_INITIAL_WEIGHT,
    _build_dataset,
    _pulse_times_ms,
    _signed_eligibility_probe,
    run_benchmark,
)


class RstdpFeedbackV4Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Keep unit tests quick; the CLI uses the full pre-registered benchmark.
        cls.report = run_benchmark(
            train_pairs=128,
            held_out_pairs=64,
            seeds=(104729, 130363),
        )

    def test_signed_eligibility_and_reward_quadrants(self):
        probe = _signed_eligibility_probe()
        rows = {
            (row["eligibility_case"], row["feedback_case"]): row
            for row in probe["rows"]
        }
        expected = {
            ("positive", "positive"): 1,
            ("positive", "negative"): -1,
            ("negative", "positive"): -1,
            ("negative", "negative"): 1,
        }
        for key, direction in expected.items():
            row = rows[key]
            self.assertNotEqual(row["eligibility_before_feedback"], 0.0)
            self.assertEqual((row["weight_delta"] > 0) - (row["weight_delta"] < 0),
                             direction)
        for key in (("positive", "neutral"), ("negative", "neutral")):
            self.assertEqual(rows[key]["modulator"], 0.0)
            self.assertEqual(rows[key]["weight_delta"], 0.0)

    def test_no_eligibility_control_does_not_change_weight(self):
        control = _signed_eligibility_probe()["no_eligibility_control"]
        self.assertEqual(control["status"], "NO_ELIGIBILITY")
        self.assertEqual(control["weight_delta"], 0.0)
        self.assertEqual(control["weight_after"], CANONICAL_STARTER_WEIGHT)

    def test_dataset_is_balanced_matched_and_deadline_bounded(self):
        train, held_out = _build_dataset(104729, train_pairs=64, held_out_pairs=32)
        for episodes, expected_pairs in ((train, 64), (held_out, 32)):
            self.assertEqual(len(episodes), expected_pairs * 2)
            self.assertEqual(sum(row.label == "FAST" for row in episodes), expected_pairs)
            self.assertEqual(sum(row.label == "SLOW" for row in episodes), expected_pairs)
            for episode in episodes:
                pulse_times = _pulse_times_ms(episode)
                self.assertEqual(len(pulse_times), 3)
                self.assertLessEqual(pulse_times[-1], DECISION_DEADLINE_MS)
                self.assertEqual(len(episode.strengths), 3)

        train_strengths = {row.strengths for row in train}
        held_strengths = {row.strengths for row in held_out}
        self.assertTrue(train_strengths.isdisjoint(held_strengths))

        by_pair = {}
        for episode in train:
            by_pair.setdefault(episode.pair_id, []).append(episode)
        for members in by_pair.values():
            self.assertEqual({row.label for row in members}, {"FAST", "SLOW"})
            self.assertEqual(members[0].strengths, members[1].strengths)

    def test_dataset_generation_is_deterministic(self):
        first = _build_dataset(104729, train_pairs=16, held_out_pairs=8)
        second = _build_dataset(104729, train_pairs=16, held_out_pairs=8)
        self.assertEqual(first, second)

    def test_report_keeps_starter_and_diagnostic_weight_separate(self):
        protocol = self.report["protocol"]
        self.assertEqual(protocol["canonical_starter_weight"], CANONICAL_STARTER_WEIGHT)
        self.assertEqual(protocol["diagnostic_initial_weight"], DIAGNOSTIC_INITIAL_WEIGHT)
        self.assertFalse(protocol["starter_profile_modified"])
        self.assertTrue(protocol["all_conditions_share_diagnostic_initial_weight"])
        for run in self.report["runs"]:
            for condition in run["conditions"]:
                self.assertEqual(condition["initial_weight"], DIAGNOSTIC_INITIAL_WEIGHT)

    def test_all_four_conditions_are_compared_on_same_protocol(self):
        for run in self.report["runs"]:
            self.assertEqual(
                [row["condition"] for row in run["conditions"]],
                list(CONDITION_MODES),
            )
            for condition in run["conditions"]:
                self.assertEqual(
                    condition["held_out"]["episodes"],
                    self.report["protocol"]["held_out_pairs_per_seed"] * 2,
                )

    def test_rewarded_rstdp_receives_both_modulator_signs_and_eligibility_signs(self):
        aggregate = {
            row["condition"]: row
            for row in self.report["aggregate_conditions"]
        }
        diagnostics = aggregate["rstdp"]["training_diagnostics"]
        self.assertGreater(diagnostics["positive_modulator_count"], 0)
        self.assertGreater(diagnostics["negative_modulator_count"], 0)
        self.assertGreater(diagnostics["positive_eligibility_count"], 0)
        self.assertGreater(diagnostics["negative_eligibility_count"], 0)
        self.assertGreater(diagnostics["reward_event_count"], 0)

    def test_negative_reward_can_depress_weight_on_false_positive_episodes(self):
        aggregate = {
            row["condition"]: row
            for row in self.report["aggregate_conditions"]
        }
        diagnostics = aggregate["rstdp"]["training_diagnostics"]
        self.assertGreater(
            diagnostics["false_positive_negative_weight_update_count"], 0
        )
        self.assertGreater(diagnostics["negative_reward_weight_delta_count"], 0)

    def test_neutral_control_has_zero_modulator_and_no_weight_change(self):
        aggregate = {
            row["condition"]: row
            for row in self.report["aggregate_conditions"]
        }
        neutral = aggregate["rstdp_neutral"]
        diagnostics = neutral["training_diagnostics"]
        self.assertEqual(diagnostics["positive_modulator_count"], 0)
        self.assertEqual(diagnostics["negative_modulator_count"], 0)
        self.assertEqual(diagnostics["reward_weight_delta_sum"], 0.0)
        for run in self.report["runs"]:
            condition = next(
                row for row in run["conditions"]
                if row["condition"] == "rstdp_neutral"
            )
            self.assertEqual(condition["weight_after_training"], DIAGNOSTIC_INITIAL_WEIGHT)

    def test_fixed_control_keeps_perturbed_weight_unchanged(self):
        for run in self.report["runs"]:
            fixed = next(
                row for row in run["conditions"] if row["condition"] == "fixed"
            )
            self.assertEqual(fixed["weight_after_training"], DIAGNOSTIC_INITIAL_WEIGHT)
            self.assertEqual(fixed["weight_delta"], 0.0)

    def test_held_out_evaluation_is_inference_only(self):
        for run in self.report["runs"]:
            for condition in run["conditions"]:
                self.assertTrue(condition["held_out_weights_frozen"])
                self.assertEqual(
                    condition["weight_after_training"],
                    condition["weight_after_held_out"],
                )

    def test_report_is_json_serializable_without_non_finite_values(self):
        encoded = json.dumps(self.report, sort_keys=True, allow_nan=False)
        self.assertTrue(encoded.startswith("{"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
