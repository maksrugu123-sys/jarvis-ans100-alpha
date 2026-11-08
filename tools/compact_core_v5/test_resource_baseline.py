import json
import sys
import unittest

from tools.compact_core_v5.resource_baseline import (
    PROFILES, build_reference_population, deep_sizeof, run_baseline,
)


class DeepSizeTests(unittest.TestCase):
    def test_shared_objects_are_counted_once(self):
        shared = "a deliberately non-interned shared value " * 3
        one = deep_sizeof([shared])
        two = deep_sizeof([shared, shared])
        self.assertEqual(
            two - one,
            sys.getsizeof([None, None]) - sys.getsizeof([None]),
        )

    def test_object_graph_size_is_positive(self):
        self.assertGreater(deep_sizeof({"nested": [1, 2, 3]}), 0)


class ResourceBaselineTests(unittest.TestCase):
    def test_profiles_are_staged_and_ordered(self):
        self.assertEqual(PROFILES["smoke"], (1, 10))
        self.assertEqual(PROFILES["pilot"], (1, 10, 100, 1000))
        self.assertEqual(PROFILES["full"], (1, 10, 100, 1000, 10000))

    def test_population_is_deterministic_and_has_expected_shapes(self):
        first = build_reference_population(4)
        second = build_reference_population(4)
        self.assertEqual(first, second)
        self.assertEqual(
            {key: len(value) for key, value in first.items()},
            {
                "neurons": 4,
                "synapses_none": 4,
                "synapses_stdp": 4,
                "synapses_rstdp": 4,
                "input_signals": 4,
            },
        )
        self.assertEqual(
            first["synapses_rstdp"][2].trace_last_update_model_time_ns,
            first["synapses_rstdp"][2].eligibility_last_update_model_time_ns,
        )

    def test_smoke_report_is_deterministic_and_json_safe_without_os_metrics(self):
        first = run_baseline("smoke", include_process_metrics=False)
        second = run_baseline("smoke", include_process_metrics=False)
        self.assertEqual(first, second)
        encoded = json.dumps(first, sort_keys=True, allow_nan=False)
        self.assertIn("JARVIS_V5_REFERENCE_RESOURCE_BASELINE_V1", encoded)
        self.assertEqual(len(first["measurements"]), 2)

    def test_invalid_population_and_profile_are_rejected(self):
        for value in (0, -1, True, 1.5):
            with self.assertRaises(ValueError):
                build_reference_population(value)
        with self.assertRaises(ValueError):
            run_baseline("unknown", include_process_metrics=False)


if __name__ == "__main__":
    unittest.main(verbosity=2)
