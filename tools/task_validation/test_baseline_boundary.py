"""Tests for fixed-baseline boundary characterization."""
import unittest

from tools.task_validation.baseline_boundary import (
    INTERVALS_MS,
    STRENGTHS,
    run_grid,
)


class BaselineBoundaryTests(unittest.TestCase):
    def test_grid_has_expected_complete_coverage(self):
        report = run_grid()
        self.assertEqual(report["cell_count"], len(INTERVALS_MS) * len(STRENGTHS))
        self.assertEqual(len(report["cells"]), report["cell_count"])

    def test_strength_grid_respects_oracle_input_bounds(self):
        self.assertEqual(len(STRENGTHS), 9)
        self.assertTrue(all(0.0 <= strength <= 1.0 for strength in STRENGTHS))
        self.assertEqual(STRENGTHS[0], 0.2)
        self.assertEqual(STRENGTHS[-1], 1.0)

    def test_grid_is_deterministic(self):
        self.assertEqual(run_grid(), run_grid())

    def test_each_cell_has_valid_response(self):
        for cell in run_grid()["cells"]:
            self.assertIn(cell["interval_ms"], INTERVALS_MS)
            self.assertIn(cell["strength"], STRENGTHS)
            self.assertGreaterEqual(cell["spike_count"], 0)
            self.assertLessEqual(cell["spike_count"], 3)
            self.assertEqual(cell["predicts_burst"], cell["spike_count"] > 0)

    def test_characterization_does_not_claim_training(self):
        report = run_grid()
        self.assertEqual(report["status"], "REFERENCE_CHARACTERIZATION_NOT_TRAINING")
        self.assertFalse(report["protocol"]["starter_profile_modified"])
        self.assertEqual(report["protocol"]["training"], "none")


if __name__ == "__main__":
    unittest.main()
