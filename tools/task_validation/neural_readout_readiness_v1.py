"""Fail-closed audit for a future ANS-100 neural canonical readout.

This tool does not create predictions. It checks that a claimed neural answer
is traceable to dedicated output neurons and reports calibration separately.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

SCHEMA = "ANS100_NEURAL_CANONICAL_READOUT_V1"
ANSWERS = ("YES", "NO", "UNKNOWN", "CONFLICT")
PRODUCER = "NEURAL_CANONICAL_READOUT"
INPUT_ORIGIN = "RAW_NEURAL_INPUT_FRAME"
ALLOWED_DECISION_INPUTS = {
    "spiking_neuron_ids",
    "activated_synapse_ids",
    "working_state_neuron_ids",
    "candidate_state_neuron_ids",
    "critic_state_neuron_ids",
}


def _sha256(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(char in "0123456789abcdef" for char in value)
    )


def _unique_nonnegative_ints(value: object) -> bool:
    return (
        isinstance(value, list)
        and all(type(item) is int and item >= 0 for item in value)
        and len(value) == len(set(value))
    )


def audit_readout(row: object) -> dict:
    """Return structural validity and independent-calibration readiness."""
    errors: list[str] = []
    if not isinstance(row, dict):
        return {"trace_valid": False, "benchmark_scorable": False,
                "errors": ["row must be a JSON object"]}

    readout = row.get("neural_readout")
    if not isinstance(readout, dict):
        return {"trace_valid": False, "benchmark_scorable": False,
                "errors": ["missing neural_readout object"]}

    if readout.get("schema") != SCHEMA:
        errors.append("unsupported neural readout schema")
    if readout.get("producer") != PRODUCER:
        errors.append("producer is not NEURAL_CANONICAL_READOUT")
    answer = readout.get("answer")
    if answer not in ANSWERS:
        errors.append("answer is not one of YES/NO/UNKNOWN/CONFLICT")
    if readout.get("input_origin") != INPUT_ORIGIN:
        errors.append("input did not originate from the raw neural input frame")
    if readout.get("core_dependency") is not False:
        errors.append("Core heuristic dependency is not explicitly false")

    inputs = readout.get("decision_inputs")
    if (not isinstance(inputs, list) or not inputs
            or any(not isinstance(item, str) or item not in ALLOWED_DECISION_INPUTS for item in inputs)
            or len(inputs) != len(set(inputs))):
        errors.append("decision_inputs are missing, duplicated, or include non-neural inputs")

    spikes = readout.get("spiking_neuron_ids")
    if not _unique_nonnegative_ints(spikes):
        errors.append("spiking_neuron_ids must be unique non-negative integers")
        spikes = []

    populations = readout.get("class_neuron_ids")
    if not isinstance(populations, dict) or set(populations) != set(ANSWERS):
        errors.append("class_neuron_ids must define all four canonical output classes")
        populations = {}

    normalized: dict[str, list[int]] = {}
    for label in ANSWERS:
        ids = populations.get(label)
        if not _unique_nonnegative_ints(ids):
            errors.append(f"{label}: output neuron IDs must be unique non-negative integers")
            normalized[label] = []
        else:
            normalized[label] = ids

    all_output_ids = [neuron_id for ids in normalized.values() for neuron_id in ids]
    if len(all_output_ids) != len(set(all_output_ids)):
        errors.append("canonical output classes overlap")
    if any(neuron_id not in spikes for neuron_id in all_output_ids):
        errors.append("a claimed output neuron did not spike in this trace")

    counts = readout.get("class_activity")
    if not isinstance(counts, dict) or set(counts) != set(ANSWERS):
        errors.append("class_activity must define all four canonical classes")
        counts = {}
    else:
        for label in ANSWERS:
            count = counts.get(label)
            actual = sum(neuron_id in set(spikes) for neuron_id in normalized[label])
            if type(count) is not int or count < 0 or count != actual:
                errors.append(f"{label}: class_activity does not match observed output spikes")

    if not errors and isinstance(counts, dict):
        highest = max(counts.values())
        winners = [label for label in ANSWERS if counts[label] == highest]
        if highest == 0:
            errors.append("no canonical output neuron spiked")
        elif len(winners) != 1:
            errors.append("canonical output is tied; abstention policy is required")
        elif winners[0] != answer:
            errors.append("declared answer does not match the unique neural readout winner")

    trace_valid = not errors
    calibration = readout.get("calibration")
    calibration_valid = (
        isinstance(calibration, dict)
        and calibration.get("status") == "INDEPENDENT_HELD_OUT_PASS"
        and _sha256(calibration.get("report_sha256"))
        and type(calibration.get("held_out_examples")) is int
        and calibration["held_out_examples"] > 0
    )
    return {
        "trace_valid": trace_valid,
        "benchmark_scorable": trace_valid and calibration_valid,
        "answer": answer if trace_valid else None,
        "calibration_valid": bool(calibration_valid),
        "errors": errors,
        "calibration_error": None if calibration_valid else
            "independent held-out calibration certificate is absent or invalid",
    }


def read_jsonl(path: Path) -> list:
    rows = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON") from exc
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--traces", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    results = [audit_readout(row) for row in read_jsonl(args.traces)]
    report = {
        "schema": "ANS100_NEURAL_READOUT_AUDIT_REPORT_V1",
        "trace_count": len(results),
        "structurally_valid": sum(item["trace_valid"] for item in results),
        "benchmark_scorable": sum(item["benchmark_scorable"] for item in results),
        "blocked": sum(not item["trace_valid"] for item in results),
        "results": results,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "trace_count", "structurally_valid", "benchmark_scorable", "blocked"
    )}, indent=2))


if __name__ == "__main__":
    main()
