"""Run the comprehensive JARVIS one-neuron validation exam.

From the repository root:
    python3 -m tools.neuron_validation.full_exam
    python3 -m tools.neuron_validation.full_exam --profile full

This combines the repository unit suite, syntax/discovery checks, the new
neuron dynamics/learning stress tests, a held-out noisy temporal task, and the
V5.1 Python-reference resource baseline. It does not run builds or CI.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import sys
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

from tools.run_all_checks import (
    check_python_syntax,
    discover_test_files,
    find_non_discoverable_test_files,
)
from tools.neuron_validation.noise_benchmark import run_benchmark
from tools.compact_core_v5.resource_baseline import run_baseline

ROOT = Path(__file__).resolve().parents[2]
TOOLS_ROOT = ROOT / "tools"

CAPABILITY_MATRIX = {
    "Adaptive LIF-DT membrane integration and analytical decay": "TESTED",
    "Dynamic threshold and spike adaptation": "TESTED",
    "Attention-to-input gain bounds": "TESTED_AS_A_SCALAR_FUNCTION_ONLY",
    "Spike emission, reset, refractory period and timestamp ordering": "TESTED",
    "Simultaneous input batches and deterministic summation": "TESTED",
    "Excitatory pair-based STDP": "TESTED",
    "Reward-modulated STDP, eligibility, context and expiry": "TESTED",
    "Numerical input/state/parameter bounds": "TESTED",
    "Long deterministic randomized event sequences": "TESTED",
    "Float32 conversion helper": "HELPER_ONLY_NO_FLOAT32_RUNTIME_CANDIDATE",
    "BTCC counterfactual replay": "UNIT_TESTED_ONLY_NOT_EVALUATED_AS_A_LEARNING_RULE",
    "Noisy temporal train/held-out task": "EXPLORATORY_BENCHMARK_NO_ACCEPTANCE_THRESHOLD",
    "V5.1 resource measurements": "PYTHON_REFERENCE_OBJECTS_ONLY",
    "Adaptive predictive model and prediction-error learning": "NOT_IMPLEMENTED",
    "Surprise, familiarity and habituation state": "NOT_IMPLEMENTED",
    "Fatigue and recovery controller": "NOT_IMPLEMENTED",
    "Long-term associative memory and adaptive forgetting": "NOT_IMPLEMENTED",
    "Structural synapse creation/deletion and topology learning": "NOT_IMPLEMENTED",
    "Dedicated homeostatic controller": "NOT_IMPLEMENTED",
    "JNVM/JPVR durable persistence and crash recovery": "NOT_IMPLEMENTED",
    "Production neuron runtime and network-level behavior": "NOT_IMPLEMENTED",
    "Reasoning Engine and task planning": "NOT_IMPLEMENTED",
}

NOT_COVERED = [
    "Android-device equivalence, RAM/CPU/energy and thermal behavior",
    "A packed V5 neuron candidate or measured memory reduction",
    "A float32 runtime candidate compared trajectory-by-trajectory with F64",
    "Inhibitory synaptic plasticity and correlated/adversarial noise",
    "Long-term memory, forgetting, fatigue, recovery and predictive-state mechanisms",
    "Persistent storage, crash consistency, concurrency and network scaling",
    "BTCC task-level learning advantage or production integration",
    "Builds, APK/AAB packaging, CI and GitHub Actions",
]


def _count_cases(suite: unittest.TestSuite | unittest.TestCase) -> int:
    if isinstance(suite, unittest.TestSuite):
        return sum(_count_cases(item) for item in suite)
    return 1


def _failure_rows(items: Iterable[tuple[object, str]]) -> list[dict[str, str]]:
    rows = []
    for test, detail in items:
        test_id = getattr(test, "id", None)
        rows.append({
            "test": test_id() if callable(test_id) else str(test),
            "detail": detail,
        })
    return rows


def _run_unit_suite(quiet: bool) -> dict[str, object]:
    loader = unittest.TestLoader()
    suite = loader.discover(
        start_dir=str(TOOLS_ROOT),
        pattern="test*.py",
        top_level_dir=str(ROOT),
    )
    discovered = _count_cases(suite)
    result = unittest.TextTestRunner(
        stream=sys.stdout, verbosity=1 if quiet else 2
    ).run(suite)
    return {
        "discovered_cases": discovered,
        "executed": result.testsRun,
        "failures": _failure_rows(result.failures),
        "errors": _failure_rows(result.errors),
        "skipped": _failure_rows(result.skipped),
        "expected_failures": _failure_rows(result.expectedFailures),
        "unexpected_successes": [str(test) for test in result.unexpectedSuccesses],
        "loader_errors": list(loader.errors),
        "status": (
            "PASS" if result.wasSuccessful() and discovered > 0 and not loader.errors
            else "FAIL"
        ),
    }


def _validate_noise_report(report: dict[str, object]) -> None:
    runs = report.get("runs")
    protocol = report.get("protocol")
    if not isinstance(runs, list) or not isinstance(protocol, dict) or not runs:
        raise ValueError("noise benchmark produced an incomplete report")
    expected_modes = set(protocol["modes"])
    for run in runs:
        if run.get("train_held_out_signature_overlap") is not False:
            raise ValueError("train/held-out split isolation check failed")
        conditions = run.get("conditions")
        if not isinstance(conditions, list) or {row["condition"] for row in conditions} != expected_modes:
            raise ValueError("noise benchmark did not return every declared control")
        for row in conditions:
            if row.get("held_out_weights_frozen") is not True:
                raise ValueError("held-out weights were not declared frozen")
            for split_name in ("train", "held_out"):
                metrics = row.get(split_name)
                if not isinstance(metrics, dict):
                    raise ValueError(f"missing {split_name} metrics")
                for key in ("accuracy", "balanced_accuracy", "macro_f1"):
                    value = metrics.get(key)
                    if not isinstance(value, (int, float)) or not 0.0 <= value <= 1.0:
                        raise ValueError(f"invalid metric {split_name}.{key}")


def _write_json(path: Path, report: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=path.parent,
        prefix=f".{path.name}.", suffix=".tmp", delete=False,
    ) as stream:
        temp_path = Path(stream.name)
        json.dump(report, stream, indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False)
        stream.write("\n")
    temp_path.replace(path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--profile", choices=("smoke", "pilot", "full"), default="pilot",
        help="smoke=quick, pilot=multi-seed exploratory, full=large multi-seed sweep",
    )
    parser.add_argument(
        "--json-report", default="neuron_full_exam_report.json",
        help="report path relative to repository root (default: %(default)s)",
    )
    parser.add_argument(
        "-q", "--quiet", action="store_true",
        help="compact unit-test output",
    )
    args = parser.parse_args(argv)

    started = time.perf_counter()
    sys.path.insert(0, str(ROOT)) if str(ROOT) not in sys.path else None
    print("JARVIS ONE-NEURON COMPREHENSIVE EXAM")
    print(f"Profile: {args.profile}")
    print(f"Root: {ROOT}")
    print(f"Python: {sys.version.split()[0]} | {platform.platform()}")

    print("\n[1/5] Python syntax and test discovery")
    syntax = check_python_syntax(TOOLS_ROOT)
    discoverable_files = discover_test_files(TOOLS_ROOT)
    hidden_tests = find_non_discoverable_test_files(TOOLS_ROOT)
    package_init = (TOOLS_ROOT / "__init__.py").is_file()
    syntax_errors = syntax["errors"]
    discovery_ok = package_init and not hidden_tests and bool(discoverable_files)
    static_status = "PASS" if not syntax_errors and discovery_ok else "FAIL"
    print(
        f"  {static_status}: {syntax['checked_files']} Python files, "
        f"{len(syntax_errors)} syntax errors, {len(discoverable_files)} test modules"
    )
    for error in syntax_errors:
        print(f"  SYNTAX: {error['path']}: {error['error']}")
    for path in hidden_tests:
        print(f"  DISCOVERY: not importable by unittest discovery: {path}")

    print("\n[2/5] Complete repository unit-test suite")
    unit_started = time.perf_counter()
    try:
        unit_report = _run_unit_suite(args.quiet)
    except Exception as exc:
        unit_report = {
            "status": "FAIL",
            "discovered_cases": 0,
            "executed": 0,
            "failures": [],
            "errors": [],
            "loader_errors": [f"{type(exc).__name__}: {exc}"],
        }
    unit_report["duration_seconds"] = round(time.perf_counter() - unit_started, 4)
    print(
        f"  {unit_report['status']}: "
        f"{unit_report['executed']}/{unit_report['discovered_cases']} tests; "
        f"{len(unit_report.get('failures', []))} failures, "
        f"{len(unit_report.get('errors', []))} errors"
    )

    print("\n[3/5] Noisy temporal generalization benchmark")
    noise_started = time.perf_counter()
    try:
        noise_report = run_benchmark(args.profile)
        _validate_noise_report(noise_report)
        noise_status = "COMPLETED_OBSERVATIONAL"
        noise_error = None
    except Exception as exc:
        noise_report = {}
        noise_status = "FAIL"
        noise_error = f"{type(exc).__name__}: {exc}"
    print(
        f"  {noise_status}: "
        f"{len(noise_report.get('runs', []))} seed/noise runs"
        if noise_report else f"  FAIL: {noise_error}"
    )
    noise_duration = round(time.perf_counter() - noise_started, 4)

    print("\n[4/5] V5.1 Python-reference resource baseline")
    resource_profile = {"smoke": "smoke", "pilot": "pilot", "full": "full"}[args.profile]
    resource_started = time.perf_counter()
    try:
        resource_report = run_baseline(resource_profile)
        resource_status = "MEASURED_REFERENCE_ONLY"
        resource_error = None
    except Exception as exc:
        resource_report = {}
        resource_status = "FAIL"
        resource_error = f"{type(exc).__name__}: {exc}"
    print(
        f"  {resource_status}: populations "
        f"{resource_report.get('population_sizes', [])}"
        if resource_report else f"  FAIL: {resource_error}"
    )
    resource_duration = round(time.perf_counter() - resource_started, 4)

    print("\n[5/5] Coverage and report")
    automated_ok = (
        static_status == "PASS"
        and unit_report.get("status") == "PASS"
        and noise_status == "COMPLETED_OBSERVATIONAL"
        and resource_status == "MEASURED_REFERENCE_ONLY"
    )
    report: dict[str, object] = {
        "exam": "JARVIS_ONE_NEURON_COMPREHENSIVE_EXAM_V1",
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "profile": args.profile,
        "status": "PASS_WITH_DECLARED_LIMITATIONS" if automated_ok else "FAIL",
        "scientific_conclusion": (
            "The exam completed; benchmark metrics are observational. "
            "No learning rule is promoted by this run."
            if automated_ok else
            "One or more automated checks failed or could not complete."
        ),
        "environment": {
            "python": sys.version,
            "platform": platform.platform(),
            "implementation": platform.python_implementation(),
            "root": str(ROOT),
        },
        "static": {
            "status": static_status,
            "python_files_checked": syntax["checked_files"],
            "syntax_errors": syntax_errors,
            "test_modules": [
                str(path.relative_to(ROOT)) for path in discoverable_files
            ],
            "non_discoverable_test_modules": hidden_tests,
        },
        "unit_tests": unit_report,
        "noisy_temporal_benchmark": {
            "status": noise_status,
            "duration_seconds": noise_duration,
            "report": noise_report,
            "error": noise_error,
        },
        "resource_baseline": {
            "status": resource_status,
            "duration_seconds": resource_duration,
            "report": resource_report,
            "error": resource_error,
        },
        "capability_matrix": CAPABILITY_MATRIX,
        "not_covered_or_not_implemented": NOT_COVERED,
        "critical_interpretation": [
            "A test PASS only covers the assertions and implemented mechanisms listed above.",
            "Noise-benchmark accuracy is descriptive; there is no pre-registered promotion threshold.",
            "Held-out data is inference-only and disjoint by generated signal/noise signature.",
            "BTCC is tested as an isolated reference oracle only; it is not integrated or compared as a trainer.",
            "V5.1 resource values describe Python reference objects, not a packed neuron or production runtime.",
            "Unimplemented architecture proposals are reported as NOT_IMPLEMENTED, never counted as passing tests.",
        ],
        "durations_seconds": {
            "total": round(time.perf_counter() - started, 4),
            "unit_tests": unit_report["duration_seconds"],
            "noise_benchmark": noise_duration,
            "resource_baseline": resource_duration,
        },
    }

    report_path = Path(args.json_report)
    if not report_path.is_absolute():
        report_path = ROOT / report_path
    try:
        _write_json(report_path, report)
        write_status = "SAVED"
    except (OSError, TypeError, ValueError) as exc:
        write_status = f"FAILED: {type(exc).__name__}: {exc}"

    total_duration = time.perf_counter() - started
    print(f"\nOVERALL AUTOMATED STATUS: {report['status']}")
    print(f"Capability completeness: PARTIAL — {sum(v == 'NOT_IMPLEMENTED' for v in CAPABILITY_MATRIX.values())} mechanisms not implemented")
    print("Research metrics are observations, not proof that a learning method is better.")
    print(f"JSON report: {write_status} — {report_path}")
    print(f"Total time: {total_duration:.2f}s")
    if write_status != "SAVED":
        return 1
    return 0 if automated_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
