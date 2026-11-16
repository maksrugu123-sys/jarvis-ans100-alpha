"""Regression tests for the leakage-safe ANS benchmark adapter."""
import hashlib
import unittest
from tools.task_validation.ans100_benchmark_adapter_v1 import (
    INPUT_SCHEMA, TRACE_SCHEMA, NEURAL_ORIGIN, export_model_inputs,
    export_ans100_training_rows, export_evaluation_inputs,
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
             "answer":None,"answer_emission":"NOT_SUPPORTED_BY_CURRENT_ANS100_PIPELINE",
             "decision":{"producer":"HYBRID_POPULATION_CONTROLLER_V1","status":"SELECTED"},
             "core_input":{"heuristic_record_count":5,"heuristic_feature_ids":["CORE_INTENT_HINT"]},
             "neural_outputs":{"unique_spiking_neurons":4},
             "measurement":{"inference_duration_ns":None,"peak_memory_mb":None}}
        row.update(changes)
        return row

    def test_export_strips_gold_and_split_metadata(self):
        row=self.inputs[0]
        self.assertEqual(row["schema"],INPUT_SCHEMA)
        self.assertEqual(set(row),{"schema","task_id","input_text","input_sha256"})
        self.assertNotIn("expected_answer",row)

    def test_ans_training_export_contains_labels_only_from_development(self):
        development = {
            "task_id": "dev:001", "input_text": "A is B", "expected_answer": "YES",
            "split": "development",
        }
        held_out = dict(self.task, task_id="held:002")
        rows = export_ans100_training_rows([development, held_out])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["task_id"], "dev:001")
        self.assertEqual(rows[0]["training_label"], "YES")
        self.assertNotIn("expected_answer", rows[0])
        self.assertNotIn("split", rows[0])

    def test_held_out_export_excludes_development_and_all_gold_fields(self):
        development = {
            "task_id": "dev:001", "input_text": "A is B", "expected_answer": "YES",
            "split": "development",
        }
        rows = export_evaluation_inputs([development, self.task])
        self.assertEqual([row["task_id"] for row in rows], ["heldout:001"])
        self.assertNotIn("expected_answer", rows[0])
        self.assertNotIn("split", rows[0])

    def test_invalid_development_training_label_rejected(self):
        development = {
            "task_id": "dev:bad", "input_text": "A is B", "expected_answer": "LEAK",
            "split": "development",
        }
        with self.assertRaises(ValueError):
            export_ans100_training_rows([development])

    def test_duplicate_or_empty_input_rejected(self):
        with self.assertRaises(ValueError): export_model_inputs([self.task,self.task])
        with self.assertRaises(ValueError): export_model_inputs([{"task_id":"x","input_text":" "}])

    def test_real_current_android_trace_is_valid_but_unscored(self):
        self.assertEqual(predictions_from_traces([self.trace()],self.inputs),[])

    def test_dedicated_neural_readout_is_scorable(self):
        row=self.trace(answer="YES",answer_emission=NEURAL_ORIGIN,
            decision={"producer":NEURAL_ORIGIN,"status":"SELECTED"},
            measurement={"inference_duration_ns":12_500_000,"peak_memory_mb":73})
        self.assertEqual(predictions_from_traces([row],self.inputs),
            [{"task_id":"heldout:001","answer":"YES","latency_ms":12.5,"peak_memory_mb":73}])

    def test_controller_cannot_claim_neural_answer(self):
        row=self.trace(answer="YES",answer_emission=NEURAL_ORIGIN)
        with self.assertRaises(ValueError): predictions_from_traces([row],self.inputs)

    def test_invalid_answer_and_provenance_rejected(self):
        with self.assertRaises(ValueError):
            validate_trace(self.trace(answer="MAYBE",answer_emission=NEURAL_ORIGIN,
                decision={"producer":NEURAL_ORIGIN,"status":"SELECTED"}))
        with self.assertRaises(ValueError):
            validate_trace(self.trace(answer="YES",answer_emission="CORE",
                decision={"producer":"CORE","status":"SELECTED"}))

    def test_hash_mismatch_rejected(self):
        with self.assertRaises(ValueError): predictions_from_traces([self.trace(input_sha256="0"*64)],self.inputs)

    def test_invalid_measurement_rejected(self):
        with self.assertRaises(ValueError):
            predictions_from_traces([self.trace(measurement={"inference_duration_ns":-1})],self.inputs)

if __name__ == "__main__": unittest.main()
