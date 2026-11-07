"""Tests for the V3 temporal-rate benchmark and learning diagnostics."""
import json
import unittest

from tools.task_validation.temporal_robustness_v3 import (
    CONDITION_MODES,
    DECISION_DEADLINE_MS,
    DIAGNOSTIC_WEIGHT_GRID,
    FAST_GAP_VALUES,
    HELD_OUT_PER_CLASS,
    INITIAL_WEIGHT,
    RUN_SEEDS,
    SLOW_GAP_VALUES,
    STRENGTH_LEVELS,
    TRAIN_PER_CLASS,
    _build_dataset,
    run_benchmark,
)


class TemporalRobustnessV3Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = run_benchmark()

    def test_report_has_expected_schema_and_protocol(self):
        report = self.report
        self.assertEqual(report["benchmark"], "JARVIS_TEMPORAL_ROBUSTNESS_V3")
        self.assertEqual(report["status"], "REFERENCE_EXPERIMENT_NOT_PRODUCTION_RUNTIME")
        self.assertEqual(report["protocol"]["decision_deadline_ms"], DECISION_DEADLINE_MS)
        self.assertEqual(report["protocol"]["fast_gap_values_ms"], list(FAST_GAP_VALUES))
        self.assertEqual(report["protocol"]["slow_gap_values_ms"], list(SLOW_GAP_VALUES))
        self.assertEqual(report["protocol"]["shared_strength_grid"], list(STRENGTH_LEVELS))
        self.assertFalse(report["protocol"]["starter_profile_modified"])
        self.assertEqual([item["condition"] for item in report["aggregate_conditions"]],
                         list(CONDITION_MODES))

    def test_dataset_is_balanced_unique_and_split_disjoint_for_each_seed(self):
        for seed in RUN_SEEDS:
            train, held_out = _build_dataset(seed)
            signature = lambda episode: (episode.gaps_ms, episode.strengths)
            train_signatures = {signature(item) for item in train}
            held_signatures = {signature(item) for item in held_out}
            self.assertEqual(len(train), TRAIN_PER_CLASS * 2)
            self.assertEqual(len(held_out), HELD_OUT_PER_CLASS * 2)
            self.assertTrue(train_signatures.isdisjoint(held_signatures))
            self.assertEqual(len(train_signatures), len(train))
            self.assertEqual(len(held_signatures), len(held_out))
            for episodes, count in ((train, TRAIN_PER_CLASS),
                                    (held_out, HELD_OUT_PER_CLASS)):
                labels = [item.label for item in episodes]
                self.assertEqual(labels.count("FAST"), count)
                self.assertEqual(labels.count("SLOW"), count)
                self.assertTrue(all(
                    labels[index] != labels[index + 1]
                    for index in range(len(labels) - 1)
                ))

    def test_fast_slow_pairs_share_identical_pulse_strengths(self):
        for seed in RUN_SEEDS:
            for split, episodes in zip(("train", "held-out"), _build_dataset(seed)):
                by_id = {item.episode_id: item for item in episodes}
                per_class = TRAIN_PER_CLASS if split == "train" else HELD_OUT_PER_CLASS
                for index in range(per_class):
                    suffix = f"{index:03d}"
                    fast = next(
                        item for key, item in by_id.items()
                        if key.endswith(f"-fast-{suffix}")
                    )
                    slow = next(
                        item for key, item in by_id.items()
                        if key.endswith(f"-slow-{suffix}")
                    )
                    self.assertEqual(fast.strengths, slow.strengths)
                    self.assertNotEqual(fast.gaps_ms, slow.gaps_ms)

    def test_seeded_datasets_are_reproducible_and_independent(self):
        first_train, first_held = _build_dataset(RUN_SEEDS[0])
        again_train, again_held = _build_dataset(RUN_SEEDS[0])
        other_train, other_held = _build_dataset(RUN_SEEDS[1])
        self.assertEqual((first_train, first_held), (again_train, again_held))
        first_signatures = {
            (item.gaps_ms, item.strengths) for item in first_train + first_held
        }
        other_signatures = {
            (item.gaps_ms, item.strengths) for item in other_train + other_held
        }
        self.assertNotEqual(first_signatures, other_signatures)

    def test_deadline_makes_temporal_density_observable(self):
        for run in self.report["runs"]:
            condition = run["conditions"][0]
            for row in condition["training_episode_results"] + condition["held_out_episode_results"]:
                if row["label"] == "FAST":
                    self.assertEqual(row["observed_input_pulses"], 3)
                    self.assertEqual(row["withheld_after_deadline_pulses"], 0)
                else:
                    self.assertEqual(row["observed_input_pulses"], 2)
                    self.assertEqual(row["withheld_after_deadline_pulses"], 1)

    def test_exhaustive_boundary_probe_confirms_initial_weight_separability(self):
        probe = self.report["boundary_probe"]
        self.assertEqual(
            [row["weight"] for row in probe["weights"]],
            list(DIAGNOSTIC_WEIGHT_GRID),
        )
        self.assertEqual(probe["initial_weight"], INITIAL_WEIGHT)
        self.assertEqual(probe["initial_weight_accuracy"], 1.0)
        initial = next(
            row for row in probe["weights"] if row["weight"] == INITIAL_WEIGHT
        )
        self.assertEqual(initial["metrics"]["episodes"], 1000)
        self.assertEqual(initial["metrics"]["correct"], 1000)
        self.assertEqual(initial["metrics"]["fp"], 0)
        self.assertEqual(initial["metrics"]["fn"], 0)

    def test_boundary_probe_exposes_both_directions_of_weight_sensitivity(self):
        rows = self.report["boundary_probe"]["weights"]
        low = next(row for row in rows if row["weight"] == 0.45)
        high = next(row for row in rows if row["weight"] == 0.60)
        self.assertGreater(low["metrics"]["fn"], 0)
        self.assertEqual(low["metrics"]["fp"], 0)
        self.assertGreater(high["metrics"]["fp"], 0)
        self.assertEqual(high["metrics"]["fn"], 0)

    def test_all_conditions_use_the_same_seeded_episode_ids(self):
        for run in self.report["runs"]:
            reference = run["conditions"][0]
            reference_train = [
                row["episode_id"] for row in reference["training_episode_results"]
            ]
            reference_held = [
                row["episode_id"] for row in reference["held_out_episode_results"]
            ]
            for condition in run["conditions"][1:]:
                self.assertEqual(
                    [row["episode_id"] for row in condition["training_episode_results"]],
                    reference_train,
                )
                self.assertEqual(
                    [row["episode_id"] for row in condition["held_out_episode_results"]],
                    reference_held,
                )

    def test_stdp_event_diagnostics_capture_pair_updates(self):
        stdp = next(
            item for item in self.report["aggregate_conditions"]
            if item["condition"] == "stdp"
        )
        diag = stdp["training_diagnostics"]
        self.assertGreater(diag["plasticity_event_count"], 0)
        self.assertGreater(diag["positive_q_event_count"], 0)
        self.assertGreater(diag["q_bounded_sum"], 0.0)
        self.assertGreater(diag["event_weight_delta_sum"], 0.0)

    def test_rewarded_and_neutral_rstdp_are_separately_observable(self):
        aggregate = {
            item["condition"]: item for item in self.report["aggregate_conditions"]
        }
        rewarded = aggregate["rstdp"]["training_diagnostics"]
        neutral = aggregate["rstdp_neutral"]["training_diagnostics"]
        self.assertGreater(rewarded["eligibility_abs_at_reward_sum"], 0.0)
        self.assertGreater(rewarded["reward_modulator_abs_sum"], 0.0)
        self.assertEqual(neutral["reward_modulator_abs_sum"], 0.0)
        self.assertEqual(neutral["reward_weight_delta_sum"], 0.0)
        self.assertTrue(neutral["reward_status_counts"])

    def test_neutral_reward_control_keeps_initial_weight(self):
        neutral = next(
            item for item in self.report["aggregate_conditions"]
            if item["condition"] == "rstdp_neutral"
        )
        for row in neutral["weight_after_training_by_seed"]:
            self.assertEqual(row["weight"], INITIAL_WEIGHT)
            self.assertEqual(row["delta"], 0.0)

    def test_held_out_is_inference_only_and_weights_are_frozen(self):
        for run in self.report["runs"]:
            for condition in run["conditions"]:
                self.assertTrue(condition["held_out_weights_frozen"])
                self.assertEqual(
                    condition["weight_after_held_out"],
                    condition["weight_after_training"],
                )
                for row in condition["held_out_episode_results"]:
                    self.assertIsNone(row["reward_diagnostic"])
                    for pulse in row["pulse_diagnostics"]:
                        self.assertIsNone(pulse["learning_update"])

    def test_training_diagnostics_are_recorded_per_pulse_and_per_reward(self):
        run = self.report["runs"][0]
        rstdp = next(item for item in run["conditions"] if item["condition"] == "rstdp")
        rows = rstdp["training_episode_results"]
        self.assertTrue(any(row["reward_diagnostic"] is not None for row in rows))
        self.assertTrue(any(
            pulse["learning_update"] is not None
            for row in rows for pulse in row["pulse_diagnostics"]
        ))
        reward = next(row["reward_diagnostic"] for row in rows
                      if row["reward_diagnostic"] is not None)
        for key in (
            "reward_value", "baseline_before", "baseline_after", "modulator",
            "eligibility_before_decay", "eligibility_at_reward",
            "weight_delta", "weight_after_reward", "status",
        ):
            self.assertIn(key, reward)

    def test_weights_stay_bounded_and_starter_profile_is_unchanged(self):
        for run in self.report["runs"]:
            for condition in run["conditions"]:
                self.assertEqual(condition["initial_weight"], INITIAL_WEIGHT)
                self.assertGreaterEqual(condition["weight_after_training"], 0.0)
                self.assertLessEqual(condition["weight_after_training"], 1.0)
                for point in condition["training_weight_trajectory"]:
                    self.assertGreaterEqual(point["weight_after"], 0.0)
                    self.assertLessEqual(point["weight_after"], 1.0)

    def test_report_is_json_serializable_and_deterministic(self):
        encoded = json.dumps(self.report, sort_keys=True, allow_nan=False)
        self.assertTrue(encoded.startswith("{"))
        self.assertEqual(run_benchmark(), self.report)


if __name__ == "__main__":
    unittest.main(verbosity=2)
