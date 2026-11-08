"""Tests for the V4.3 decision-boundary temporal-credit experiment."""
import json
import unittest

from tools.task_validation.rstdp_feedback_v4 import (
    _build_dataset,
    _run_condition as run_v4_condition,
)
from tools.task_validation.rstdp_feedback_v4_3 import (
    CONDITION_MODES,
    DIAGNOSTIC_INITIAL_WEIGHT,
    _run_condition,
    run_benchmark,
)


class RstdpDecisionBoundaryV43Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = run_benchmark(
            train_pairs=32,
            held_out_pairs=16,
            seeds=(104729,),
        )

    def test_frozen_v4_replay_matches_original_training_path(self):
        train, held_out = _build_dataset(
            104729, train_pairs=16, held_out_pairs=8
        )
        old = run_v4_condition(104729, "rstdp", train, held_out)
        new = _run_condition(104729, "rstdp_v4", train, held_out)
        self.assertEqual(old["train"], new["train"])
        self.assertEqual(old["held_out"], new["held_out"])
        self.assertEqual(old["weight_after_training"], new["weight_after_training"])
        self.assertEqual(old["reward_baseline_after_training"],
                         new["reward_baseline_after_training"])
        self.assertEqual(
            old["training_diagnostics"]["reward_weight_delta_sum"],
            new["training_diagnostics"]["weight_delta_sum"],
        )

    def test_decision_latched_condition_is_present_and_protocol_is_explicit(self):
        self.assertIn("rstdp_decision_latched", CONDITION_MODES)
        protocol = self.report["protocol"]
        self.assertEqual(protocol["diagnostic_initial_weight"],
                         DIAGNOSTIC_INITIAL_WEIGHT)
        self.assertFalse(protocol["starter_profile_modified"])
        self.assertIn("stop adding R-STDP eligibility events",
                      protocol["decision_latched_policy"])

    def test_neutral_control_has_zero_reward_updates(self):
        row = next(
            item for item in self.report["aggregate_conditions"]
            if item["condition"] == "rstdp_neutral"
        )
        diagnostics = row["training_diagnostics"]
        self.assertEqual(diagnostics["positive_weight_delta_count"], 0)
        self.assertEqual(diagnostics["negative_weight_delta_count"], 0)
        self.assertEqual(diagnostics["weight_delta_sum"], 0.0)

    def test_all_conditions_keep_heldout_weights_frozen(self):
        for run in self.report["runs"]:
            for condition in run["conditions"]:
                self.assertTrue(condition["held_out_weights_frozen"])

    def test_report_is_deterministic_and_json_safe(self):
        report = run_benchmark(
            train_pairs=8,
            held_out_pairs=4,
            seeds=(104729,),
        )
        encoded = json.dumps(report, sort_keys=True, allow_nan=False)
        self.assertEqual(
            report,
            run_benchmark(
                train_pairs=8,
                held_out_pairs=4,
                seeds=(104729,),
            ),
        )
        self.assertTrue(encoded)

    def test_latched_policy_counts_postdecision_events(self):
        row = next(
            item for item in self.report["aggregate_conditions"]
            if item["condition"] == "rstdp_decision_latched"
        )
        diagnostics = row["training_diagnostics"]
        self.assertGreaterEqual(
            diagnostics["skipped_postdecision_plasticity_event_count"], 0
        )
        self.assertEqual(
            diagnostics["plasticity_event_count"]
            + diagnostics["skipped_postdecision_plasticity_event_count"],
            self.report["protocol"]["training_pairs_per_seed"] * 2 * len(
                self.report["protocol"]["seeds"]
            ) * 3,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
