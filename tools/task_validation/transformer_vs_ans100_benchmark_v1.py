"""Shared deterministic benchmark contract for Transformer vs ANS-100.

Creates identical information packages and scores externally produced predictions.
It does not execute either model or claim measured performance.
"""
from __future__ import annotations
import argparse, hashlib, json, statistics
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

SEEDS = (104729, 130363, 155921, 181081)
SPLITS = ("development", "pair_disjoint", "concept_disjoint", "compositional")
FAMILIES = ("relation_status", "two_hop_reachability", "evidence_conflict")
ANSWERS = {"YES", "NO", "UNKNOWN", "CONFLICT"}

def _digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()

def _symbol(seed: int, index: int, split: str) -> str:
    namespace = "shared" if split in ("development", "pair_disjoint") else split
    # Development and pair-disjoint tasks draw from the same fixed vocabulary.
    # A bounded pool makes the split test genuinely pair-disjoint rather than
    # accidentally concept-disjoint because each split used fresh entity IDs.
    return f"{namespace}_s{seed}_entity_{index % 32:02d}"

def _render_fact(fact: dict[str, Any]) -> str:
    kind = fact["kind"]
    if kind == "relation":
        polarity = "is" if fact["polarity"] > 0 else "is explicitly not"
        return f"- {fact['subject']} {polarity} connected to {fact['object']}."
    if kind == "edge":
        return f"- Directed link: {fact['subject']} -> {fact['object']}."
    if kind == "evidence":
        stance = "supports" if fact["polarity"] > 0 else "refutes"
        return f"- Evidence {stance} the claim that {fact['subject']} connects to {fact['object']}."
    raise ValueError(f"unsupported fact kind: {kind}")

def _task(seed: int, split: str, family: str, index: int,
          facts: list[dict[str, Any]], question: str, expected: str,
          query_pair: tuple[str, str]) -> dict[str, Any]:
    facts_text = "\n".join(_render_fact(fact) for fact in facts) or "- No evidence."
    input_text = ("Use only the supplied facts. Do not assume unstated relations.\n\n"
                  f"Facts:\n{facts_text}\n\nQuestion: {question}")
    return {
        "task_id": f"{split}:{family}:s{seed}:{index:03d}",
        "seed": seed, "split": split, "family": family,
        "facts": facts, "question": question, "query_pair": list(query_pair),
        "input_text": input_text, "expected_answer": expected,
    }

def _make_relation_task(seed: int, index: int, split: str) -> dict[str, Any]:
    base = index * 4
    a, b, c, d = (_symbol(seed, base + n, split) for n in range(4))
    mode = index % 3
    if mode == 0:
        facts, qa, qb, expected = [{"kind":"relation","subject":a,"object":b,"polarity":1}], a, b, "YES"
    elif mode == 1:
        facts, qa, qb, expected = [{"kind":"relation","subject":a,"object":c,"polarity":-1}], a, c, "NO"
    else:
        facts, qa, qb, expected = [{"kind":"relation","subject":a,"object":b,"polarity":1}], a, d, "UNKNOWN"
    question = f"What is the evidence status of 'connected' from {qa} to {qb}? Answer exactly YES, NO, or UNKNOWN."
    return _task(seed, split, "relation_status", index, facts, question, expected, (qa, qb))

def _make_two_hop_task(seed: int, index: int, split: str) -> dict[str, Any]:
    base = index * 5
    a, b, c, d, e = (_symbol(seed, base + n, split) for n in range(5))
    if index % 2 == 0:
        facts = [{"kind":"edge","subject":a,"object":b},{"kind":"edge","subject":b,"object":c},{"kind":"edge","subject":d,"object":e}]
        start, end, expected = a, c, "YES"
    else:
        facts = [{"kind":"edge","subject":a,"object":b},{"kind":"edge","subject":d,"object":e}]
        start, end, expected = a, e, "NO"
    question = f"Is there a directed path of at most two links from {start} to {end}? Treat the listed graph as complete. Answer exactly YES or NO."
    return _task(seed, split, "two_hop_reachability", index, facts, question, expected, (start, end))

def _make_conflict_task(seed: int, index: int, split: str) -> dict[str, Any]:
    base = index * 3
    a, b, c = (_symbol(seed, base + n, split) for n in range(3))
    mode = index % 4
    facts = []
    if mode in (0, 2):
        facts.append({"kind":"evidence","subject":a,"object":b,"polarity":1})
    if mode in (1, 2):
        facts.append({"kind":"evidence","subject":a,"object":b,"polarity":-1})
    if mode == 3:
        facts.append({"kind":"evidence","subject":c,"object":b,"polarity":1})
    expected = ("YES", "NO", "CONFLICT", "UNKNOWN")[mode]
    question = f"Classify the evidence about the claim that {a} connects to {b}. Answer exactly YES, NO, CONFLICT, or UNKNOWN."
    return _task(seed, split, "evidence_conflict", index, facts, question, expected, (a, b))

def build_tasks(seeds: Iterable[int] = SEEDS, tasks_per_split: int = 12) -> list[dict[str, Any]]:
    seed_list = tuple(seeds)
    if not seed_list or len(seed_list) != len(set(seed_list)):
        raise ValueError("at least one unique seed is required")
    if not 1 <= tasks_per_split <= 1000:
        raise ValueError("tasks_per_split must be in 1..1000")
    tasks = []
    for seed in seed_list:
        for split in SPLITS:
            for index in range(tasks_per_split):
                # Keep the vocabulary shared but ensure queried pairs are disjoint
                # between development and pair-disjoint evaluation.
                effective_index = index + (tasks_per_split if split == "pair_disjoint" else 0)
                if split == "compositional":
                    tasks.append(_make_two_hop_task(seed, effective_index, split))
                elif effective_index % 3 == 0:
                    tasks.append(_make_relation_task(seed, effective_index, split))
                elif effective_index % 3 == 1:
                    tasks.append(_make_conflict_task(seed, effective_index, split))
                else:
                    tasks.append(_make_two_hop_task(seed, effective_index, split))
    ids = [task["task_id"] for task in tasks]
    if len(ids) != len(set(ids)):
        raise AssertionError("task IDs are not unique")
    return tasks

def canonical_answer(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    normalized = value.strip().upper().strip(" \t\r\n.,;:!?")
    aliases = {"TRUE":"YES","FALSE":"NO","NOT ENOUGH INFORMATION":"UNKNOWN",
               "INSUFFICIENT INFORMATION":"UNKNOWN","BOTH":"CONFLICT","CONTRADICTORY":"CONFLICT"}
    return aliases.get(normalized, normalized)

def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number}: each JSONL row must be an object")
            rows.append(value)
    return rows

def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * percentile
    lower, upper = int(index), min(int(index) + 1, len(ordered) - 1)
    fraction = index - lower
    return ordered[lower] * (1 - fraction) + ordered[upper] * fraction

def score_predictions(tasks: list[dict[str, Any]], predictions: list[dict[str, Any]],
                      system_label: str) -> dict[str, Any]:
    # Validate the evaluation source itself before building an ID map: duplicate
    # IDs would otherwise silently overwrite one another and corrupt the denominator.
    all_ids = [task.get("task_id") for task in tasks]
    if any(not isinstance(task_id, str) or not task_id for task_id in all_ids):
        raise ValueError("benchmark tasks require non-empty string task_id values")
    if len(all_ids) != len(set(all_ids)):
        raise ValueError("benchmark task IDs must be unique")
    for task in tasks:
        if task.get("split") not in SPLITS:
            raise ValueError(f"unknown benchmark split: {task.get('split')!r}")
        if task.get("family") not in FAMILIES:
            raise ValueError(f"unknown benchmark family: {task.get('family')!r}")
        if task.get("expected_answer") not in ANSWERS:
            raise ValueError(f"{task.get('task_id')}: invalid canonical expected answer")

    # Development examples are training-only and must never affect held-out scores.
    evaluation_tasks = [task for task in tasks if task.get("split") != "development"]
    if not evaluation_tasks:
        raise ValueError("benchmark contains no held-out evaluation tasks")
    task_by_id = {task["task_id"]: task for task in evaluation_tasks}
    prediction_by_id = {}
    for row in predictions:
        task_id = row.get("task_id")
        if not isinstance(task_id, str) or task_id not in task_by_id:
            raise ValueError(f"{system_label}: unknown or missing task_id: {task_id!r}")
        if task_id in prediction_by_id:
            raise ValueError(f"{system_label}: duplicate prediction for {task_id}")
        prediction_by_id[task_id] = row
    groups: dict[tuple[str, str], list[bool]] = defaultdict(list)
    latencies, memories, memory_deltas, correct_by_id = [], [], [], {}
    cold_warm = {True: {"latencies": [], "peaks": [], "deltas": []}, False: {"latencies": [], "peaks": [], "deltas": []}}
    for task in evaluation_tasks:
        task_id = task["task_id"]
        row = prediction_by_id.get(task_id)
        correct = canonical_answer(row.get("answer")) == task["expected_answer"] if row else False
        correct_by_id[task_id] = correct
        groups[(task["split"], task["family"])].append(correct)
        if row:
            latency, memory = row.get("latency_ms"), row.get("peak_memory_mb")
            memory_delta = row.get("peak_memory_delta_mb")
            cold_start = row.get("cold_start")
            if isinstance(latency, (int,float)) and not isinstance(latency,bool) and latency >= 0:
                latencies.append(float(latency))
            if isinstance(memory, (int,float)) and not isinstance(memory,bool) and memory >= 0:
                memories.append(float(memory))
            if isinstance(memory_delta, (int,float)) and not isinstance(memory_delta,bool) and memory_delta >= 0:
                memory_deltas.append(float(memory_delta))
            if isinstance(cold_start, bool):
                bucket = cold_warm[cold_start]
                if isinstance(latency, (int,float)) and not isinstance(latency,bool) and latency >= 0:
                    bucket["latencies"].append(float(latency))
                if isinstance(memory, (int,float)) and not isinstance(memory,bool) and memory >= 0:
                    bucket["peaks"].append(float(memory))
                if isinstance(memory_delta, (int,float)) and not isinstance(memory_delta,bool) and memory_delta >= 0:
                    bucket["deltas"].append(float(memory_delta))
    grouped = {}
    for (split, family), outcomes in sorted(groups.items()):
        submitted = sum(1 for task in evaluation_tasks if task["split"] == split and task["family"] == family and task["task_id"] in prediction_by_id)
        answered = sum(1 for task in evaluation_tasks if task["split"] == split and task["family"] == family
                       and task["task_id"] in prediction_by_id and prediction_by_id[task["task_id"]].get("answer") is not None)
        grouped[f"{split}/{family}"] = {"tasks":len(outcomes),"submitted":submitted,"answered":answered,
            "abstentions":submitted-answered,"correct":sum(outcomes),
            "accuracy":sum(outcomes)/len(outcomes) if outcomes else 0.0}
    correct_count = sum(correct_by_id.values())
    submitted_count = len(prediction_by_id)
    answered_count = sum(1 for row in prediction_by_id.values() if row.get("answer") is not None)
    abstention_count = submitted_count - answered_count
    return {
        "system":system_label,"tasks":len(evaluation_tasks),"submitted":submitted_count,
        "answered":answered_count,"abstentions":abstention_count,
        "missing":len(evaluation_tasks)-submitted_count,"correct":correct_count,
        "coverage":answered_count/len(evaluation_tasks),
        "accuracy_over_all_tasks":correct_count/len(evaluation_tasks),
        "accuracy_when_answered":correct_count/answered_count if answered_count else 0.0,
        "groups":grouped,
        "latency_ms":{"count":len(latencies),"median":statistics.median(latencies) if latencies else None,"p95":_percentile(latencies,0.95)},
        "peak_memory_mb":{"count":len(memories),"max":max(memories) if memories else None,"median":statistics.median(memories) if memories else None},
        "peak_memory_delta_mb":{"count":len(memory_deltas),"max":max(memory_deltas) if memory_deltas else None,"median":statistics.median(memory_deltas) if memory_deltas else None},
        "cold_vs_warm": {
            ("cold_start" if cold else "warm_steady_state"): {
                "count": max(len(values["latencies"]), len(values["peaks"]), len(values["deltas"])),
                "latency_ms_median": statistics.median(values["latencies"]) if values["latencies"] else None,
                "peak_memory_mb_max": max(values["peaks"]) if values["peaks"] else None,
                "peak_memory_delta_mb_max": max(values["deltas"]) if values["deltas"] else None,
            } for cold, values in cold_warm.items()
        },
        "correct_by_task":correct_by_id,
    }

def compare_prediction_files(task_path: Path, transformer_path: Path, ans100_path: Path) -> dict[str, Any]:
    tasks = _load_jsonl(task_path)
    transformer = score_predictions(tasks,_load_jsonl(transformer_path),"transformer")
    ans100 = score_predictions(tasks,_load_jsonl(ans100_path),"ANS-100")
    evaluation_tasks = [task for task in tasks if task.get("split") != "development"]
    paired = [(transformer["correct_by_task"][t["task_id"]],ans100["correct_by_task"][t["task_id"]]) for t in evaluation_tasks]
    transformer_wins = sum(t and not a for t,a in paired)
    ans100_wins = sum(a and not t for t,a in paired)
    ties = len(paired)-transformer_wins-ans100_wins
    transformer.pop("correct_by_task")
    ans100.pop("correct_by_task")
    return {
        "benchmark":"JARVIS_TRANSFORMER_VS_ANS100_COMPARISON_V1",
        "status":"SCORED_FROM_SUBMITTED_PREDICTIONS","task_count":len(evaluation_tasks),
        "excluded_training_splits":["development"],
        "transformer":transformer,"ans100":ans100,
        "paired_task_outcomes":{"transformer_only_correct":transformer_wins,
            "ans100_only_correct":ans100_wins,"both_same_correctness":ties},
        "interpretation":"Compare accuracy by split and family, then latency and peak memory. Do not declare a universal winner from this synthetic bounded benchmark."
    }

def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open("w",encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row,ensure_ascii=False,sort_keys=True)+"\n")

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command",required=True)
    generate = sub.add_parser("generate",help="create the shared benchmark task set")
    generate.add_argument("--output",type=Path,default=Path("benchmark/transformer_vs_ans100_tasks.jsonl"))
    generate.add_argument("--tasks-per-split",type=int,default=12)
    score = sub.add_parser("score",help="score predictions from both systems")
    score.add_argument("--tasks",type=Path,required=True)
    score.add_argument("--transformer",type=Path,required=True)
    score.add_argument("--ans100",type=Path,required=True)
    score.add_argument("--report",type=Path,default=Path("benchmark/transformer_vs_ans100_report.json"))
    args = parser.parse_args()
    if args.command == "generate":
        tasks=build_tasks(tasks_per_split=args.tasks_per_split)
        write_jsonl(args.output,tasks)
        print(json.dumps({"status":"TASKS_GENERATED","path":str(args.output),"task_count":len(tasks),
            "splits":list(SPLITS),"families":list(FAMILIES),
            "note":"No model was executed; expected answers are retained in this local evaluation dataset."},ensure_ascii=False,indent=2))
    else:
        report=compare_prediction_files(args.tasks,args.transformer,args.ans100)
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__ == "__main__":
    main()
