"""Tests for the V4.3 decision-boundary temporal-credit experiment."""
import json
import unittest

from tools.numerical_oracle.jarvis_oracle import SynapseState
from tools.task_validation.rstdp_feedback_v4 import (
    _build_dataset,
    _run_condition as run_v4_condition,
)
from tools.task_validation.rstdp_feedback_v4_3 import (
    CONDITION_MODES,
    DIAGNOSTIC_INITIAL_WEIGHT,
    _run_condition,
    _run_episode as run_v43_episode,
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

    def test_latched_policy_gates_only_learning_after_the_decision(self):
        train, _ = _build_dataset(
            104729, train_pairs=8, held_out_pairs=4
        )
        episode = next(item for item in train if item.label == "FAST")

        control_record, _, _, control_stats = run_v43_episode(
            episode, 0, "rstdp_v4",
            SynapseState(weight=DIAGNOSTIC_INITIAL_WEIGHT),
            0.5, "v43-control-probe", True,
        )
        latched_record, _, _, latched_stats = run_v43_episode(
            episode, 0, "rstdp_decision_latched",
            SynapseState(weight=DIAGNOSTIC_INITIAL_WEIGHT),
            0.5, "v43-latched-probe", True,
        )

        # Both paths must process the same sensory inputs and preserve the
        # same neuron output. Only post-decision plasticity is gated.
        self.assertEqual(control_record["spike_times_ms"], latched_record["spike_times_ms"])
        self.assertEqual(control_record["first_spike_pulse_index"], latched_record["first_spike_pulse_index"])
        self.assertEqual(control_record["prediction"], latched_record["prediction"])
        self.assertEqual(control_stats["sensory_input_event_count"], 3)
        self.assertEqual(latched_stats["sensory_input_event_count"], 3)
        self.assertGreater(latched_stats["skipped_postdecision_plasticity_event_count"], 0)
        self.assertEqual(
            latched_stats["postdecision_sensory_input_event_count"],
            latched_stats["skipped_postdecision_plasticity_event_count"],
        )

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
        expected_sensory_events = (
            self.report["protocol"]["training_pairs_per_seed"]
            * 2
            * len(self.report["protocol"]["seeds"])
            * 3
        )
        skipped = diagnostics["skipped_postdecision_plasticity_event_count"]
        self.assertGreater(skipped, 0)
        self.assertEqual(diagnostics["sensory_input_event_count"], expected_sensory_events)
        self.assertEqual(diagnostics["postdecision_sensory_input_event_count"], skipped)
        self.assertEqual(
            diagnostics["plasticity_event_count"] + skipped,
            expected_sensory_events,
        )

        frozen_v4 = next(
            item for item in self.report["aggregate_conditions"]
            if item["condition"] == "rstdp_v4"
        )["training_diagnostics"]
        self.assertEqual(frozen_v4["sensory_input_event_count"], expected_sensory_events)
        self.assertEqual(frozen_v4["plasticity_event_count"], expected_sensory_events)
        self.assertEqual(frozen_v4["skipped_postdecision_plasticity_event_count"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
