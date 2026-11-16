"""Regression tests for the shared Transformer vs ANS-100 benchmark."""
import json
import tempfile
import unittest
from pathlib import Path

from tools.task_validation.transformer_vs_ans100_benchmark_v1 import (
    SEEDS, build_tasks, canonical_answer, compare_prediction_files, score_predictions,
)

class TransformerVsANS100BenchmarkV1Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tasks = build_tasks(seeds=SEEDS[:2], tasks_per_split=8)

    def test_task_set_is_deterministic_and_has_all_splits(self):
        self.assertEqual(self.tasks,build_tasks(seeds=SEEDS[:2],tasks_per_split=8))
        self.assertEqual({t["split"] for t in self.tasks},
            {"development","pair_disjoint","concept_disjoint","compositional"})

    def test_task_ids_are_unique_and_answers_are_canonical(self):
        ids=[t["task_id"] for t in self.tasks]
        self.assertEqual(len(ids),len(set(ids)))
        self.assertTrue(all(t["expected_answer"] in {"YES","NO","UNKNOWN","CONFLICT"} for t in self.tasks))

    def test_concept_disjoint_split_uses_unseen_symbol_namespace(self):
        dev={s for t in self.tasks if t["split"]=="development" for f in t["facts"] for s in (f.get("subject"),f.get("object"))}
        held={s for t in self.tasks if t["split"]=="concept_disjoint" for f in t["facts"] for s in (f.get("subject"),f.get("object"))}
        self.assertTrue(dev.isdisjoint(held))

    def test_pair_disjoint_split_uses_new_pairs_with_shared_vocabulary(self):
        def pairs(split):
            return {
                (fact["subject"], fact["object"])
                for task in self.tasks if task["split"] == split
                for fact in task["facts"]
            }
        self.assertTrue(pairs("development"))
        self.assertTrue(pairs("pair_disjoint"))
        self.assertTrue(pairs("development").isdisjoint(pairs("pair_disjoint")))

    def test_compositional_split_contains_two_hop_queries(self):
        rows=[t for t in self.tasks if t["split"]=="compositional"]
        self.assertTrue(rows)
        self.assertTrue(all(t["family"]=="two_hop_reachability" for t in rows))

    def test_canonical_answer_maps_common_equivalents(self):
        self.assertEqual(canonical_answer(" true. "),"YES")
        self.assertEqual(canonical_answer("not enough information"),"UNKNOWN")
        self.assertEqual(canonical_answer("both"),"CONFLICT")

    def test_missing_predictions_count_as_wrong(self):
        report=score_predictions(self.tasks,[],"empty")
        self.assertEqual(report["answered"],0)
        held_out_count=sum(t["split"] != "development" for t in self.tasks)
        self.assertEqual(report["missing"],held_out_count)
        self.assertEqual(report["accuracy_over_all_tasks"],0.0)

    def test_explicit_null_answer_is_counted_as_an_abstention_not_a_missing_row(self):
        held_out=next(t for t in self.tasks if t["split"] != "development")
        report=score_predictions(self.tasks,[{"task_id":held_out["task_id"],"answer":None}],"abstaining")
        self.assertEqual(report["submitted"],1)
        self.assertEqual(report["answered"],0)
        self.assertEqual(report["abstentions"],1)
        self.assertEqual(report["missing"],sum(t["split"] != "development" for t in self.tasks)-1)
        self.assertEqual(report["correct"],0)
        self.assertEqual(report["coverage"],0.0)

    def test_development_predictions_are_rejected_from_held_out_scoring(self):
        development=next(t for t in self.tasks if t["split"]=="development")
        with self.assertRaises(ValueError):
            score_predictions(self.tasks,[{"task_id":development["task_id"],"answer":development["expected_answer"]}],"leak")

    def test_duplicate_or_unknown_prediction_ids_are_rejected(self):
        first_task=next(t for t in self.tasks if t["split"] != "development")
        first={"task_id":first_task["task_id"],"answer":first_task["expected_answer"]}
        with self.assertRaises(ValueError):
            score_predictions(self.tasks,[first,first],"duplicate")
        with self.assertRaises(ValueError):
            score_predictions(self.tasks,[{"task_id":"not-a-task","answer":"YES"}],"unknown")

    def test_paired_comparison_uses_same_tasks_and_reports_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            task_path,transformer_path,ans_path=(root/"tasks.jsonl",root/"transformer.jsonl",root/"ans.jsonl")
            def dump(path,rows):
                path.write_text("".join(json.dumps(row)+"\n" for row in rows),encoding="utf-8")
            dump(task_path,self.tasks)
            held_out=[t for t in self.tasks if t["split"] != "development"]
            dump(transformer_path,[{"task_id":t["task_id"],"answer":t["expected_answer"],"latency_ms":100,"peak_memory_mb":512} for t in held_out])
            dump(ans_path,[{"task_id":t["task_id"],"answer":"WRONG","latency_ms":20,"peak_memory_mb":64} for t in held_out])
            report=compare_prediction_files(task_path,transformer_path,ans_path)
            self.assertEqual(report["transformer"]["accuracy_over_all_tasks"],1.0)
            self.assertEqual(report["ans100"]["accuracy_over_all_tasks"],0.0)
            self.assertEqual(report["paired_task_outcomes"]["transformer_only_correct"],len(held_out))
            self.assertEqual(report["task_count"],len(held_out))
            self.assertEqual(report["excluded_training_splits"],["development"])
            self.assertEqual(report["ans100"]["peak_memory_mb"]["max"],64)

if __name__ == "__main__":
    unittest.main()
