"""Regression tests for the shared Transformer vs ANS-100 benchmark."""
import json
import hashlib
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

    def test_pair_disjoint_split_uses_new_query_pairs_with_shared_vocabulary(self):
        def vocabulary(split):
            return {
                symbol
                for task in self.tasks if task["split"] == split
                for fact in task["facts"]
                for symbol in (fact.get("subject"), fact.get("object"))
            }
        def pairs(split):
            return {
                tuple(task["query_pair"])
                for task in self.tasks if task["split"] == split
            }
        dev_vocab, held_vocab = vocabulary("development"), vocabulary("pair_disjoint")
        self.assertTrue(dev_vocab)
        self.assertTrue(held_vocab)
        self.assertTrue(dev_vocab.intersection(held_vocab))
        self.assertTrue(pairs("development"))
        self.assertTrue(pairs("pair_disjoint"))
        self.assertTrue(pairs("development").isdisjoint(pairs("pair_disjoint")))

    def test_generator_rejects_custom_count_that_breaks_pair_disjoint_contract(self):
        with self.assertRaises(ValueError):
            build_tasks(seeds=SEEDS[:1], tasks_per_split=17)

    def test_query_pairs_are_unique_within_each_evaluation_split(self):
        for split in ("development", "pair_disjoint"):
            rows = [task for task in self.tasks if task["split"] == split]
            pairs = [tuple(task["query_pair"]) for task in rows]
            self.assertEqual(len(pairs), len(set(pairs)))

    def test_scorer_rejects_duplicate_or_invalid_source_tasks(self):
        duplicate = self.tasks + [dict(self.tasks[0])]
        with self.assertRaises(ValueError):
            score_predictions(duplicate, [], "invalid-source")
        invalid = [dict(task) for task in self.tasks]
        invalid[0]["expected_answer"] = "MAYBE"
        with self.assertRaises(ValueError):
            score_predictions(invalid, [], "invalid-gold")

    def test_relation_status_family_contains_yes_no_and_unknown(self):
        for split in ("development", "pair_disjoint"):
            labels = {
                task["expected_answer"] for task in self.tasks
                if task["split"] == split and task["family"] == "relation_status"
            }
            self.assertEqual(labels, {"YES", "NO", "UNKNOWN"})

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

    def test_invalid_non_null_predictions_are_rejected(self):
        held_out = next(t for t in self.tasks if t["split"] != "development")
        for invalid in ("MAYBE", "", 7, []):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    score_predictions(
                        self.tasks,
                        [{"task_id": held_out["task_id"], "answer": invalid}],
                        "invalid-answer",
                    )

    def test_prediction_input_hash_must_match_source_task(self):
        held_out = next(t for t in self.tasks if t["split"] != "development")
        with self.assertRaises(ValueError):
            score_predictions(
                self.tasks,
                [{"task_id": held_out["task_id"], "answer": "YES", "input_sha256": "0" * 64}],
                "tampered-input",
            )

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

    def test_report_exposes_per_seed_results(self):
        report = score_predictions(self.tasks, [], "empty")
        self.assertEqual(set(report["per_seed"]), {str(seed) for seed in SEEDS[:2]})
        for result in report["per_seed"].values():
            self.assertEqual(result["tasks"], 24)
            self.assertEqual(result["correct"], 0)
            self.assertEqual(result["accuracy"], 0.0)

    def test_paired_comparison_requires_input_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            task_path, transformer_path, ans_path = (
                root / "tasks.jsonl", root / "transformer.jsonl", root / "ans.jsonl"
            )
            held_out = next(t for t in self.tasks if t["split"] != "development")
            def dump(path, rows):
                path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            dump(task_path, self.tasks)
            prediction = {"task_id": held_out["task_id"], "answer": held_out["expected_answer"]}
            dump(transformer_path, [prediction])
            dump(ans_path, [prediction])
            with self.assertRaises(ValueError):
                compare_prediction_files(task_path, transformer_path, ans_path)

    def test_paired_comparison_uses_same_tasks_and_reports_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            task_path,transformer_path,ans_path=(root/"tasks.jsonl",root/"transformer.jsonl",root/"ans.jsonl")
            def dump(path,rows):
                path.write_text("".join(json.dumps(row)+"\n" for row in rows),encoding="utf-8")
            dump(task_path,self.tasks)
            held_out=[t for t in self.tasks if t["split"] != "development"]
            dump(transformer_path,[{"task_id":t["task_id"],"answer":t["expected_answer"],"input_sha256":hashlib.sha256(t["input_text"].encode("utf-8")).hexdigest(),"latency_ms":100,"initialization_ms":450,"peak_memory_mb":512,"peak_memory_delta_mb":80,"memory_measurement_scope":"ANDROID_PROCESS_TOTAL_PSS_NOT_PER_MODEL","cold_start":i == 0} for i,t in enumerate(held_out)])
            dump(ans_path,[{"task_id":t["task_id"],"answer":"WRONG","input_sha256":hashlib.sha256(t["input_text"].encode("utf-8")).hexdigest(),"latency_ms":20,"network_initialization_ms":35,"peak_memory_mb":64,"peak_memory_delta_mb":12,"memory_measurement_scope":"ANDROID_PROCESS_TOTAL_PSS_NOT_PER_MODEL","input_encoding":"role_structured_v1","cold_start":True} for t in held_out])
            report=compare_prediction_files(task_path,transformer_path,ans_path)
            self.assertEqual(report["transformer"]["accuracy_over_all_tasks"],1.0)
            self.assertEqual(report["ans100"]["accuracy_over_all_tasks"],0.0)
            self.assertEqual(report["paired_task_outcomes"]["transformer_only_correct"],len(held_out))
            self.assertEqual(report["task_count"],len(held_out))
            self.assertEqual(report["transformer"]["input_integrity"]["verified_hashes"],len(held_out))
            self.assertEqual(report["ans100"]["input_integrity"]["verified_hashes"],len(held_out))
            self.assertEqual(report["excluded_training_splits"],["development"])
            self.assertEqual(report["ans100"]["peak_memory_mb"]["max"],64)
            self.assertEqual(report["transformer"]["peak_memory_delta_mb"]["max"],80)
            self.assertEqual(report["ans100"]["peak_memory_delta_mb"]["max"],12)
            self.assertEqual(report["transformer"]["initialization_ms"]["median"],450)
            self.assertEqual(report["ans100"]["initialization_ms"]["median"],35)
            self.assertEqual(report["ans100"]["input_encodings"],["role_structured_v1"])
            self.assertEqual(report["ans100"]["memory_measurement_scope"],["ANDROID_PROCESS_TOTAL_PSS_NOT_PER_MODEL"])
            self.assertEqual(report["transformer"]["cold_vs_warm"]["cold_start"]["count"],1)
            self.assertEqual(report["transformer"]["cold_vs_warm"]["warm_steady_state"]["count"],len(held_out)-1)

if __name__ == "__main__":
    unittest.main()
