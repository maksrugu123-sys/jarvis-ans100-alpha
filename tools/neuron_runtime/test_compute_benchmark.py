"""Tests for comparable compute benchmark measurements."""
import unittest
from unittest.mock import patch
from tools.neuron_runtime.compute_benchmark import ComputeBenchmarkError, run_compute_benchmark

class ComputeBenchmarkTests(unittest.TestCase):
    def test_reports_speedup_against_cpu_baseline(self):
        ticks = iter([0, 100, 0, 50, 0, 100, 0, 50, 0, 100, 0, 50])
        with patch("tools.neuron_runtime.compute_benchmark.perf_counter_ns", side_effect=lambda: next(ticks)):
            result = run_compute_benchmark({"cpu": lambda: 7, "gpu": lambda: 7},
                                           operations=100, warmup_runs=0, measured_runs=3)
        self.assertEqual(result.measurements["cpu"].median_ns, 100)
        self.assertEqual(result.measurements["gpu"].median_ns, 50)
        self.assertAlmostEqual(result.faster_than_baseline_percent["gpu"], 100.0)

    def test_rejects_different_backend_results(self):
        with self.assertRaises(ComputeBenchmarkError):
            run_compute_benchmark({"cpu": lambda: 1, "gpu": lambda: 2},
                                  operations=1, warmup_runs=0, measured_runs=3)

    def test_requires_cpu_baseline_by_default(self):
        with self.assertRaises(ComputeBenchmarkError):
            run_compute_benchmark({"gpu": lambda: 1}, operations=1, warmup_runs=0, measured_runs=3)

    def test_rejects_unknown_mode(self):
        with self.assertRaises(ComputeBenchmarkError):
            run_compute_benchmark({"auto": lambda: 1}, operations=1, warmup_runs=0, measured_runs=3)

if __name__ == "__main__":
    unittest.main()