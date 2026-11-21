"""Unit tests for the JARVIS master test runner's guardrails."""
import tempfile
import unittest
from pathlib import Path

from tools.run_all_checks import (
    check_python_syntax,
    count_test_cases,
    find_non_discoverable_test_files,
    iter_python_sources,
)


class MasterTestRunnerTests(unittest.TestCase):
    def test_source_scan_skips_generated_and_hidden_directories(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "ok.py").write_text("value = 1\n", encoding="utf-8")
            (root / "__pycache__").mkdir()
            (root / "__pycache__" / "cached.py").write_text(
                "value = 2\n", encoding="utf-8"
            )
            (root / ".generated").mkdir()
            (root / ".generated" / "hidden.py").write_text(
                "value = 3\n", encoding="utf-8"
            )
            self.assertEqual([path.name for path in iter_python_sources(root)], ["ok.py"])

    def test_syntax_scan_reports_invalid_file_and_continues(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "good.py").write_text("value = 42\n", encoding="utf-8")
            (root / "bad.py").write_text("def broken(:\n", encoding="utf-8")
            report = check_python_syntax(root)
            self.assertEqual(report["checked_files"], 2)
            self.assertEqual(len(report["errors"]), 1)
            self.assertEqual(report["errors"][0]["path"], "bad.py")
            self.assertIn("SyntaxError", report["errors"][0]["error"])

    def test_discovery_guard_detects_missing_package_marker(self):
        with tempfile.TemporaryDirectory() as temp:
            tools_root = Path(temp) / "tools"
            nested = tools_root / "feature"
            nested.mkdir(parents=True)
            (tools_root / "__init__.py").write_text("", encoding="utf-8")
            (nested / "test_feature.py").write_text("", encoding="utf-8")
            self.assertEqual(
                find_non_discoverable_test_files(tools_root),
                ["feature/test_feature.py"],
            )
            (nested / "__init__.py").write_text("", encoding="utf-8")
            self.assertEqual(find_non_discoverable_test_files(tools_root), [])

    def test_nested_test_suites_are_counted(self):
        suite = unittest.TestSuite([
            unittest.FunctionTestCase(lambda: None),
            unittest.TestSuite([unittest.FunctionTestCase(lambda: None)]),
        ])
        self.assertEqual(count_test_cases(suite), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
