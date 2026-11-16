"""Regression tests for the leakage-safe ANS benchmark adapter."""
import hashlib
import unittest
from tools.task_validation.ans100_benchmark_adapter_v1 import (
    INPUT_SCHEMA, TRACE_SCHEMA, NEURAL_ORIGIN, export_model_inputs,
    predictions_from_traces, validate_trace,
)

class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.task={"task_id":"heldout:001","input_text":"Facts: A -> B. Question: Is A linked to B?",
                   "expected_answer":"YES","split":"concept_disjoint","facts":[{"gold":"secret"}]}
        self.inputs=export_model_inputs([self.task])
        self.digest=hashlib.sha256(self.task["input_text"].encode()).hexdigest()

    def trace(self, **changes):
        row={"schema":TRACE_SCHEMA,"task_id":self.task["task_id"],"input_sha256":self.digest,
             "decision":{"producer":"HYBRID_POPULATION_CONTROLLER_V1","status":"SELECTED"},
             "heuristic_sources":["CORE_INTENT_HINT","CORE_PLAN_GOAL_HINT"],
             "neural_readout":{"canonical_answer":None,"answer_origin":None},"measurements":{}}
        row.update(changes)
        return row

    def test_export_strips_gold_and_split_metadata(self):
        row=self.inputs[0]
        self.assertEqual(row["schema"],INPUT_SCHEMA)
        self.assertEqual(set(row),{"schema","task_id","input_text","input_sha256"})
        self.assertNotIn("expected_answer",row)

    def test_duplicate_or_empty_input_rejected(self):
        with self.assertRaises(ValueError): export_model_inputs([self.task,self.task])
        with self.assertRaises(ValueError): export_model_inputs([{"task_id":"x","input_text":" "}])

    def test_heuristic_selected_status_is_not_scored(self):
        self.assertEqual(predictions_from_traces([self.trace()],self.inputs),[])

    def test_explicit_neural_readout_is_scored(self):
        row=self.trace(decision={"producer":NEURAL_ORIGIN,"status":"SELECTED"},
            neural_readout={"canonical_answer":"YES","answer_origin":NEURAL_ORIGIN},
            measurements={"latency_ms":12.5,"peak_memory_mb":73})
        self.assertEqual(predictions_from_traces([row],self.inputs),
            [{"task_id":"heldout:001","answer":"YES","latency_ms":12.5,"peak_memory_mb":73}])

    def test_controller_cannot_claim_neural_answer(self):
        row=self.trace(neural_readout={"canonical_answer":"YES","answer_origin":NEURAL_ORIGIN})
        with self.assertRaises(ValueError): predictions_from_traces([row],self.inputs)

    def test_invalid_answer_and_provenance_rejected(self):
        with self.assertRaises(ValueError):
            validate_trace(self.trace(neural_readout={"canonical_answer":"MAYBE","answer_origin":NEURAL_ORIGIN}))
        with self.assertRaises(ValueError):
            validate_trace(self.trace(neural_readout={"canonical_answer":"YES","answer_origin":"CORE"}))

    def test_hash_mismatch_rejected(self):
        with self.assertRaises(ValueError): predictions_from_traces([self.trace(input_sha256="0"*64)],self.inputs)

    def test_invalid_measurement_rejected(self):
        with self.assertRaises(ValueError):
            predictions_from_traces([self.trace(measurements={"latency_ms":-1})],self.inputs)

if __name__ == "__main__": unittest.main()
