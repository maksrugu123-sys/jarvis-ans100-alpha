"""Run JARVIS's complete lightweight Python verification suite.

From the repository root:
    python3 -m tools.run_all_checks

This scans Python syntax and discovers/runs every unittest module named
test*.py below tools/. Research benchmarks, device measurements, builds and
CI are deliberately excluded.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import sys
import tempfile
import time
import tokenize
import unittest
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
TOOLS_ROOT = ROOT / "tools"
SKIP_DIRS = {
    ".git", ".venv", "venv", "__pycache__", ".pytest_cache",
    ".mypy_cache", ".ruff_cache", "build", "dist", "node_modules",
    "site-packages",
}


def iter_python_sources(source_root: Path) -> list[Path]:
    """Return Python files while skipping generated and vendor directories."""
    found: list[Path] = []
    if not source_root.is_dir():
        return found
    for current, dirnames, filenames in os.walk(source_root):
        dirnames[:] = sorted(
            name for name in dirnames
            if name not in SKIP_DIRS and not name.startswith(".")
        )
        for filename in filenames:
            if filename.endswith(".py"):
                found.append(Path(current) / filename)
    return sorted(found)


def check_python_syntax(source_root: Path) -> dict[str, object]:
    """Compile every Python source without executing or importing it."""
    files = iter_python_sources(source_root)
    errors: list[dict[str, str]] = []
    for path in files:
        try:
            with tokenize.open(path) as stream:
                source = stream.read()
            compile(source, str(path), "exec")
        except (OSError, SyntaxError, UnicodeError, ValueError) as exc:
            errors.append({
                "path": str(path.relative_to(source_root)),
                "error": f"{type(exc).__name__}: {exc}",
            })
    return {"checked_files": len(files), "errors": errors}


def discover_test_files(tools_root: Path) -> list[Path]:
    """List tests using the repository's test*.py naming convention."""
    return [
        path for path in iter_python_sources(tools_root)
        if path.name.startswith("test")
    ]


def find_non_discoverable_test_files(tools_root: Path) -> list[str]:
    """Flag nested test modules that unittest discovery cannot recurse into."""
    missing: list[str] = []
    for path in discover_test_files(tools_root):
        parent = path.parent
        while parent != tools_root:
            if not (parent / "__init__.py").is_file():
                missing.append(str(path.relative_to(tools_root)))
                break
            parent = parent.parent
    return sorted(set(missing))


def count_test_cases(suite: unittest.TestSuite | unittest.TestCase) -> int:
    """Count leaf test cases inside a possibly nested test suite."""
    if isinstance(suite, unittest.TestSuite):
        return sum(count_test_cases(test) for test in suite)
    return 1


def collect_suite(root: Path) -> tuple[unittest.TestSuite, unittest.TestLoader]:
    loader = unittest.TestLoader()
    suite = loader.discover(
        start_dir=str(root / "tools"),
        pattern="test*.py",
        top_level_dir=str(root),
    )
    return suite, loader


def _failure_rows(items: Iterable[tuple[object, str]]) -> list[dict[str, str]]:
    rows = []
    for test, detail in items:
        test_id = getattr(test, "id", None)
        rows.append({
            "test": test_id() if callable(test_id) else str(test),
            "detail": detail,
        })
    return rows


def write_json_report(path: Path, report: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=path.parent,
        prefix=f".{path.name}.", suffix=".tmp", delete=False,
    ) as stream:
        temporary = Path(stream.name)
        json.dump(report, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
    temporary.replace(path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--json-report", metavar="PATH",
        help="also save a machine-readable report (relative to repository root)",
    )
    parser.add_argument(
        "-q", "--quiet", action="store_true",
        help="use compact unittest output",
    )
    args = parser.parse_args(argv)

    if not TOOLS_ROOT.is_dir():
        print(f"ERROR: tools directory not found: {TOOLS_ROOT}", file=sys.stderr)
        return 2
    if str(ROOT) not in sys.path:
        sys.path.insert(0, str(ROOT))

    started = time.perf_counter()
    print("JARVIS MASTER TEST — Python reference checks")
    print(f"Root: {ROOT}")
    print(f"Python: {sys.version.split()[0]} | {platform.platform()}")

    print("\n[1/3] Python syntax scan")
    syntax = check_python_syntax(TOOLS_ROOT)
    syntax_errors = syntax["errors"]
    assert isinstance(syntax_errors, list)
    print(
        f"  {'PASS' if not syntax_errors else 'FAIL'} — "
        f"{syntax['checked_files']} files scanned, {len(syntax_errors)} syntax errors"
    )
    for row in syntax_errors:
        print(f"  - {row['path']}: {row['error']}")

    print("\n[2/3] Test discovery integrity")
    test_files = discover_test_files(TOOLS_ROOT)
    hidden_tests = find_non_discoverable_test_files(TOOLS_ROOT)
    package_ok = (TOOLS_ROOT / "__init__.py").is_file()
    discovery_ok = package_ok and not hidden_tests and bool(test_files)
    print(
        f"  {'PASS' if discovery_ok else 'FAIL'} — "
        f"{len(test_files)} test*.py files found"
    )
    if not package_ok:
        print("  - tools/__init__.py is missing; package discovery may fail")
    for path in hidden_tests:
        print(f"  - not discoverable by unittest: {path} (missing __init__.py)")
    if not test_files:
        print("  - no test*.py modules found")

    print("\n[3/3] Complete unittest suite")
    test_started = time.perf_counter()
    try:
        suite, loader = collect_suite(ROOT)
        discovered_cases = count_test_cases(suite)
        verbosity = 1 if args.quiet else 2
        result = unittest.TextTestRunner(
            stream=sys.stdout, verbosity=verbosity
        ).run(suite)
        loader_errors = list(loader.errors)
    except Exception as exc:
        discovered_cases = 0
        loader_errors = [f"Discovery failed: {type(exc).__name__}: {exc}"]
        result = unittest.TestResult()
    test_duration = time.perf_counter() - test_started

    tests_ok = result.wasSuccessful() and discovered_cases > 0 and not loader_errors
    print(
        f"  {'PASS' if tests_ok else 'FAIL'} — "
        f"{result.testsRun}/{discovered_cases} tests executed; "
        f"{len(result.failures)} failures, {len(result.errors)} errors, "
        f"{len(result.skipped)} skipped; {test_duration:.2f}s"
    )
    for error in loader_errors:
        print(f"  - discovery: {error}")

    overall_ok = not syntax_errors and discovery_ok and tests_ok
    duration = time.perf_counter() - started
    report: dict[str, object] = {
        "suite": "JARVIS MASTER TEST",
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "status": "PASS" if overall_ok else "FAIL",
        "environment": {
            "python": sys.version,
            "platform": platform.platform(),
            "root": str(ROOT),
        },
        "syntax": syntax,
        "discovery": {
            "test_files": [str(path.relative_to(ROOT)) for path in test_files],
            "non_discoverable": hidden_tests,
            "tools_package_init_present": package_ok,
        },
        "unittest": {
            "discovered_cases": discovered_cases,
            "executed": result.testsRun,
            "failures": _failure_rows(result.failures),
            "errors": _failure_rows(result.errors),
            "skipped": _failure_rows(result.skipped),
            "expected_failures": _failure_rows(result.expectedFailures),
            "unexpected_successes": [str(test) for test in result.unexpectedSuccesses],
            "loader_errors": loader_errors,
            "duration_seconds": round(test_duration, 4),
        },
        "duration_seconds": round(duration, 4),
        "not_run_by_design": [
            "task-level research benchmarks and parameter sweeps",
            "Android/Termux device measurements",
            "Gradle/Maven/CMake/Make/Cargo/npm builds and APK/AAB packaging",
            "CI and GitHub Actions",
        ],
    }

    print("\n" + ("=" * 60))
    print(f"OVERALL: {'PASS' if overall_ok else 'FAIL'} | {duration:.2f}s")
    print("Benchmarks, device measurements, builds and CI are not covered by this runner.")
    if args.json_report:
        report_path = Path(args.json_report)
        if not report_path.is_absolute():
            report_path = ROOT / report_path
        write_json_report(report_path, report)
        print(f"JSON report: {report_path}")
    return 0 if overall_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
