import math
import unittest

from .calibration_round2 import run_calibration_round2


class CalibrationRound2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = run_calibration_round2()
        cls.rows = cls.report["scenarios"]

    def _group(self, name):
        return [row for row in self.rows if row["group"] == name]

    def test_round2_report_is_deterministic_and_has_fixed_coverage(self):
        self.assertEqual(run_calibration_round2(), self.report)
        self.assertEqual(self.report["schema"], "JARVIS_CALIBRATION_ROUND2_V0_1")
        self.assertEqual(self.report["profile_id"], "JARVIS_SINGLE_NEURON_STARTER_V1")
        self.assertEqual(
            self.report["counts"],
            {
                "total": 23,
                "input_amplitude": 5,
                "temporal_spacing": 3,
                "temporal_patterns": 3,
                "adaptation_recovery": 4,
                "attention_gain": 3,
                "simultaneous_order": 2,
                "initial_conditions": 3,
            },
        )

    def test_all_scenarios_remain_finite_and_bounded(self):
        for row in self.rows:
            outcome = row["outcome"]
            for field in (
                "final_u", "final_a", "final_effective_threshold",
                "max_adaptation", "max_effective_threshold",
            ):
                self.assertTrue(math.isfinite(outcome[field]), (row["scenario_id"], field))
            self.assertGreaterEqual(outcome["final_u"], -2.0)
            self.assertLessEqual(outcome["final_u"], 2.0)
            self.assertGreaterEqual(outcome["final_a"], 0.0)
            self.assertLessEqual(outcome["final_a"], 1.0)
            self.assertGreaterEqual(outcome["final_effective_threshold"], 1.0)
            self.assertLessEqual(outcome["final_effective_threshold"], 1.5)
            self.assertLessEqual(outcome["spike_count"], outcome["input_event_count"])

    def test_subthreshold_amplitude_response_is_monotonic(self):
        rows = sorted(
            self._group("input_amplitude"),
            key=lambda row: row["parameters"]["strength"],
        )
        potentials = [row["outcome"]["final_u"] for row in rows]
        self.assertEqual([row["outcome"]["spike_count"] for row in rows], [0] * 5)
        self.assertEqual(potentials, sorted(potentials))
        self.assertGreater(potentials[-1], potentials[0])

    def test_shorter_pulse_gap_produces_stronger_temporal_summation(self):
        rows = sorted(
            self._group("temporal_spacing"),
            key=lambda row: row["parameters"]["gap_ms"],
        )
        self.assertGreater(rows[0]["outcome"]["spike_count"], rows[1]["outcome"]["spike_count"])
        self.assertGreaterEqual(rows[1]["outcome"]["spike_count"], rows[2]["outcome"]["spike_count"])

    def test_burst_and_sparse_patterns_are_distinguished(self):
        rows = {row["parameters"]["pattern"]: row for row in self._group("temporal_patterns")}
        self.assertEqual(rows["burst"]["outcome"]["input_event_count"], 8)
        self.assertEqual(rows["sparse"]["outcome"]["input_event_count"], 8)
        self.assertGreater(
            rows["burst"]["outcome"]["spike_count"],
            rows["sparse"]["outcome"]["spike_count"],
        )

    def test_adaptation_and_threshold_recover_monotonically_during_quiet_time(self):
        rows = sorted(
            self._group("adaptation_recovery"),
            key=lambda row: row["parameters"]["quiet_wait_ms"],
        )
        adaptation = [row["outcome"]["final_a"] for row in rows]
        thresholds = [row["outcome"]["final_effective_threshold"] for row in rows]
        self.assertTrue(all(value > 0.0 for value in adaptation))
        self.assertEqual(adaptation, sorted(adaptation, reverse=True))
        self.assertEqual(thresholds, sorted(thresholds, reverse=True))

    def test_attention_gain_is_bounded_and_monotonic(self):
        rows = sorted(
            self._group("attention_gain"),
            key=lambda row: row["parameters"]["attention"],
        )
        potentials = [row["outcome"]["final_u"] for row in rows]
        self.assertEqual([row["outcome"]["spike_count"] for row in rows], [0, 0, 0])
        self.assertEqual(potentials, sorted(potentials))
        self.assertLessEqual(potentials[-1], 1.0)

    def test_simultaneous_input_order_does_not_change_outcome(self):
        forward, reverse = self._group("simultaneous_order")
        self.assertEqual(forward["outcome"], reverse["outcome"])

    def test_initial_conditions_are_explicitly_exercised(self):
        rows = {row["parameters"]["initial_u"]: row for row in self._group("initial_conditions")}
        self.assertEqual(set(rows), {0.0, 0.25})
        adapted = next(
            row for row in self._group("initial_conditions")
            if row["parameters"]["initial_a"] == 0.5
        )
        resting = next(
            row for row in self._group("initial_conditions")
            if row["parameters"]["initial_a"] == 0.0
        )
        self.assertGreater(
            adapted["outcome"]["final_effective_threshold"],
            resting["outcome"]["final_effective_threshold"],
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
