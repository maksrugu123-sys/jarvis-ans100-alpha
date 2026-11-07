"""Contract tests for V4.2 pulse-level R-STDP credit diagnostics."""
import json
import math
import unittest

from tools.task_validation.rstdp_causal_credit_v42 import (
    _same_timestamp_order_probe,
    run_audit,
)
from tools.task_validation.rstdp_feedback_v4 import (
    DIAGNOSTIC_INITIAL_WEIGHT,
    _build_dataset,
    _run_condition,
)


class RstdpCausalCreditV42Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.train, cls.held_out = _build_dataset(
            104729, train_pairs=16, held_out_pairs=8
        )
        cls.report = run_audit(
            train_pairs=16,
            held_out_pairs=8,
            seeds=(104729,),
            max_examples_per_category=2,
        )
        cls.run = cls.report["runs"][0]

    def test_same_timestamp_batch_and_ordered_probe_have_expected_signs(self):
        probe = _same_timestamp_order_probe()
        self.assertEqual(probe["simultaneous_batch"]["q_raw"], 0.0)
        self.assertEqual(probe["simultaneous_batch"]["eligibility_after"], 0.0)
        self.assertGreater(probe["pre_then_post"]["second_event_q"], 0.0)
        self.assertLess(probe["post_then_pre"]["second_event_q"], 0.0)
        self.assertEqual(probe["post_then_pre"]["pre_offset_ns"], 1)

    def test_cross_tab_and_pulse_aggregates_cover_training_episodes(self):
        expected_episodes = len(self.train)
        self.assertEqual(sum(self.run["cross_tab"].values()), expected_episodes)
        self.assertEqual(
            sum(row["episode_count"] for row in self.run["pulse_alignment"].values()),
            expected_episodes,
        )
        for group in self.run["pulse_alignment"].values():
            self.assertEqual(
                [row["event_count"] for row in group["pulses"]],
                [group["episode_count"]] * 3,
            )

    def test_saved_episode_traces_reconcile_pulse_contributions(self):
        saved = [
            row
            for rows in self.run["representative_traces"].values()
            for row in rows
        ]
        self.assertTrue(saved)
        for episode in saved:
            residual = episode["feedback"]["eligibility_contribution_residual"]
            self.assertLess(abs(residual), 1e-12)
            for pulse in episode["pulse_diagnostics"]:
                self.assertEqual(pulse["n_pre"], 1)
                self.assertIn(pulse["n_post"], (0, 1))
                self.assertTrue(math.isfinite(pulse["q_raw"]))
                self.assertTrue(math.isfinite(pulse["eligibility_after_pulse"]))

    def test_audit_replay_matches_existing_v4_training_path(self):
        reference = _run_condition(
            104729, "rstdp", self.train, self.held_out,
            DIAGNOSTIC_INITIAL_WEIGHT,
        )
        self.assertEqual(self.run["training_metrics"], reference["train"])
        self.assertEqual(
            self.run["weight_after_training"], reference["weight_after_training"]
        )
        self.assertEqual(
            self.run["reward_baseline_after_training"],
            reference["reward_baseline_after_training"],
        )

    def test_shadow_order_does_not_change_the_real_training_weight(self):
        reference = _run_condition(
            104729, "rstdp", self.train, self.held_out,
            DIAGNOSTIC_INITIAL_WEIGHT,
        )
        self.assertEqual(
            self.run["weight_after_training"], reference["weight_after_training"]
        )
        self.assertEqual(
            self.report["protocol"]["diagnostic_initial_weight"],
            DIAGNOSTIC_INITIAL_WEIGHT,
        )
        self.assertEqual(
            self.report["protocol"]["canonical_starter_weight"], 0.5
        )
        self.assertFalse(self.report["protocol"]["starter_profile_modified"])

    def test_report_is_deterministic_and_json_safe(self):
        encoded = json.dumps(self.report, sort_keys=True, allow_nan=False)
        self.assertTrue(encoded.startswith("{"))
        self.assertEqual(
            self.report,
            run_audit(
                train_pairs=16,
                held_out_pairs=8,
                seeds=(104729,),
                max_examples_per_category=2,
            ),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
