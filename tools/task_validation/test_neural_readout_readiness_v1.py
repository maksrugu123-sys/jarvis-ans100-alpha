"""Regression tests for the fail-closed neural readout audit."""
import unittest

from tools.task_validation.neural_readout_readiness_v1 import (
    ANSWERS, INPUT_ORIGIN, PRODUCER, SCHEMA, audit_readout,
)


def make_row():
    class_ids = {
        "YES": [9400, 9401],
        "NO": [9500, 9501],
        "UNKNOWN": [9600, 9601],
        "CONFLICT": [9700, 9701],
    }
    spikes = [9400, 9401, 9500, 9600]
    return {
        "neural_readout": {
            "schema": SCHEMA,
            "producer": PRODUCER,
            "answer": "YES",
            "input_origin": INPUT_ORIGIN,
            "core_dependency": False,
            "decision_inputs": ["spiking_neuron_ids", "activated_synapse_ids"],
            "spiking_neuron_ids": spikes,
            "class_neuron_ids": class_ids,
            "class_activity": {"YES": 2, "NO": 1, "UNKNOWN": 1, "CONFLICT": 0},
            "calibration": {
                "status": "INDEPENDENT_HELD_OUT_PASS",
                "report_sha256": "a" * 64,
                "held_out_examples": 128,
            },
        }
    }


class NeuralReadoutReadinessTests(unittest.TestCase):
    def test_valid_trace_is_scorable_only_with_calibration_certificate(self):
        row = make_row()
        result = audit_readout(row)
        self.assertTrue(result["trace_valid"])
        self.assertTrue(result["benchmark_scorable"])

    def test_missing_readout_is_blocked(self):
        result = audit_readout({"answer": "YES"})
        self.assertFalse(result["trace_valid"])
        self.assertIsNone(result["answer"])

    def test_core_heuristic_dependency_is_rejected(self):
        row = make_row()
        row["neural_readout"]["core_dependency"] = True
        self.assertFalse(audit_readout(row)["trace_valid"])

    def test_core_feature_cannot_be_smuggled_into_decision_inputs(self):
        row = make_row()
        row["neural_readout"]["decision_inputs"].append("core_candidate_label")
        self.assertFalse(audit_readout(row)["trace_valid"])

    def test_output_neuron_must_have_spiked(self):
        row = make_row()
        row["neural_readout"]["class_neuron_ids"]["YES"] = [9499]
        row["neural_readout"]["class_activity"]["YES"] = 1
        self.assertFalse(audit_readout(row)["trace_valid"])

    def test_class_counts_must_match_observed_spikes(self):
        row = make_row()
        row["neural_readout"]["class_activity"]["YES"] = 1
        self.assertFalse(audit_readout(row)["trace_valid"])

    def test_tied_readout_is_not_silently_resolved(self):
        row = make_row()
        row["neural_readout"]["class_activity"] = {
            "YES": 1, "NO": 1, "UNKNOWN": 1, "CONFLICT": 0
        }
        row["neural_readout"]["spiking_neuron_ids"] = [9400, 9500, 9600]
        self.assertFalse(audit_readout(row)["trace_valid"])

    def test_uncalibrated_neural_trace_is_not_benchmark_scorable(self):
        row = make_row()
        row["neural_readout"]["calibration"] = {"status": "NOT_CALIBRATED"}
        result = audit_readout(row)
        self.assertTrue(result["trace_valid"])
        self.assertFalse(result["benchmark_scorable"])

    def test_declared_answer_must_match_unique_winner(self):
        row = make_row()
        row["neural_readout"]["answer"] = "NO"
        self.assertFalse(audit_readout(row)["trace_valid"])

    def test_wrong_producer_is_rejected(self):
        row = make_row()
        row["neural_readout"]["producer"] = "HYBRID_POPULATION_CONTROLLER_V1"
        self.assertFalse(audit_readout(row)["trace_valid"])


if __name__ == "__main__":
    unittest.main()
