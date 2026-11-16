"""Leakage-safe ANS-100 benchmark input and Android trace adapter."""
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path

INPUT_SCHEMA = "ANS100_BENCHMARK_INPUT_V1"
TRACE_SCHEMA = "ANS100_BENCHMARK_TRACE_V1"
NEURAL_ORIGIN = "NEURAL_CANONICAL_READOUT"
ANSWERS = {"YES", "NO", "UNKNOWN", "CONFLICT"}

def read_jsonl(path):
    rows = []
    with path.open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip(): continue
            try: row = json.loads(line)
            except json.JSONDecodeError as exc: raise ValueError(f"{path}:{number}: invalid JSON") from exc
            if not isinstance(row, dict): raise ValueError(f"{path}:{number}: expected object")
            rows.append(row)
    return rows

def write_jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as stream:
        for row in rows: stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")

def export_model_inputs(tasks):
    result, seen = [], set()
    for task in tasks:
        task_id, text = task.get("task_id"), task.get("input_text")
        if not isinstance(task_id, str) or not task_id or task_id in seen: raise ValueError("task IDs must be unique non-empty strings")
        if not isinstance(text, str) or not text.strip(): raise ValueError(f"{task_id}: empty input_text")
        seen.add(task_id)
        result.append({"schema": INPUT_SCHEMA, "task_id": task_id, "input_text": text,
                       "input_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest()})
    return result

def validate_trace(row):
    if row.get("schema") != TRACE_SCHEMA: raise ValueError("unsupported trace schema")
    task_id, digest, decision = row.get("task_id"), row.get("input_sha256"), row.get("decision")
    if not isinstance(task_id, str) or not task_id: raise ValueError("benchmark trace requires task_id")
    if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest): raise ValueError(f"{task_id}: invalid input hash")
    if not isinstance(decision, dict) or not isinstance(decision.get("producer"), str) or not isinstance(decision.get("status"), str):
        raise ValueError(f"{task_id}: missing decision provenance")
    core = row.get("core_input", {})
    if core and not isinstance(core, dict): raise ValueError(f"{task_id}: invalid core_input provenance")
    answer, origin = row.get("answer"), row.get("answer_emission")
    if answer is not None:
        if answer not in ANSWERS: raise ValueError(f"{task_id}: non-canonical answer")
        if origin != NEURAL_ORIGIN or decision["producer"] != NEURAL_ORIGIN:
            raise ValueError(f"{task_id}: answer is not produced by a dedicated neural readout")
    elif origin not in (None, "NOT_SUPPORTED_BY_CURRENT_ANS100_PIPELINE"):
        raise ValueError(f"{task_id}: unsupported answer emission marker")
    return row

def predictions_from_traces(traces, inputs):
    by_id = {r["task_id"]: r for r in inputs}
    if len(by_id) != len(inputs): raise ValueError("duplicate model input IDs")
    seen, output = set(), []
    for raw in traces:
        row = validate_trace(raw); task_id = row["task_id"]
        if task_id not in by_id: raise ValueError(f"unknown task ID: {task_id}")
        if task_id in seen: raise ValueError(f"duplicate trace: {task_id}")
        seen.add(task_id)
        if row["input_sha256"] != by_id[task_id]["input_sha256"]: raise ValueError(f"{task_id}: input hash mismatch")
        answer = row.get("answer")
        if answer is None: continue
        item = {"task_id": task_id, "answer": answer}
        measured = row.get("measurement", {})
        if not isinstance(measured, dict): raise ValueError(f"{task_id}: invalid measurement")
        duration_ns = measured.get("inference_duration_ns")
        if duration_ns is not None:
            if isinstance(duration_ns, bool) or not isinstance(duration_ns, (int, float)) or duration_ns < 0: raise ValueError(f"{task_id}: invalid inference duration")
            item["latency_ms"] = duration_ns / 1_000_000
        memory = measured.get("peak_memory_mb")
        if memory is not None:
            if isinstance(memory, bool) or not isinstance(memory, (int, float)) or memory < 0: raise ValueError(f"{task_id}: invalid peak memory")
            item["peak_memory_mb"] = memory
        output.append(item)
    return output

def main():
    parser = argparse.ArgumentParser(description=__doc__); commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export-inputs"); export.add_argument("--tasks", type=Path, required=True); export.add_argument("--output", type=Path, required=True)
    adapt = commands.add_parser("adapt-ans-traces"); adapt.add_argument("--inputs", type=Path, required=True); adapt.add_argument("--traces", type=Path, required=True); adapt.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "export-inputs":
        rows = export_model_inputs(read_jsonl(args.tasks)); write_jsonl(args.output, rows)
        print(json.dumps({"status":"INPUTS_EXPORTED","count":len(rows),"gold_fields_removed":True}, indent=2))
    else:
        inputs, traces = read_jsonl(args.inputs), read_jsonl(args.traces); rows = predictions_from_traces(traces, inputs); write_jsonl(args.output, rows)
        print(json.dumps({"status":"TRACES_VALIDATED","trace_count":len(traces),"scorable_neural_predictions":len(rows),"unscored_without_neural_answer":len(traces)-len(rows),"heuristic_statuses_promoted":False}, indent=2))

if __name__ == "__main__": main()
