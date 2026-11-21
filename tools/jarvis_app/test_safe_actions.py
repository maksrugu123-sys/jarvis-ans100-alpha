"""Tests for the explicit read-only local action executor and Task Loop bridge."""
from __future__ import annotations

import os
import tempfile
import unittest

from tools.jarvis_app.server import LocalConsoleError, LocalConsoleService
from tools.jarvis_app.safe_actions import SafeActionExecutor


class FakeBrowser:
    def search(self, query):
        return {"query": query, "results": [{"title": "Example", "url": "https://example.com/"}]}

    def open(self, url):
        return {"url": url, "opened": True}

    def read(self):
        return {"url": "https://example.com/", "text": "Visible page excerpt"}


class SafeActionExecutorTests(unittest.TestCase):
    def test_memory_search_includes_only_explicitly_approved_records(self):
        workspace = {
            "records": {
                "memories": [
                    {"id": "approved-1", "user_approved": True, "category": "project", "content": "JARVIS local neural memory"},
                    {"id": "unapproved-1", "user_approved": False, "category": "project", "content": "JARVIS secret text"},
                ],
            },
        }
        executor = SafeActionExecutor(
            platform="desktop",
            browser=FakeBrowser(),
            workspace_provider=lambda: workspace,
        )
        result = executor._search_memory("JARVIS neural")
        self.assertEqual([item["id"] for item in result["matches"]], ["approved-1"])
        self.assertNotIn("secret", result["matches"][0]["excerpt"])

    def test_browser_operations_are_unavailable_on_android_platform(self):
        executor = SafeActionExecutor(
            platform="android",
            browser=FakeBrowser(),
            workspace_provider=lambda: {"records": {"memories": []}},
        )
        from tools.neuron_runtime.reasoning_engine import PlanningAction
        from tools.neuron_runtime.permission_safety_gate import AuthorizationRequest, EffectClass, RequestedBudget
        from tools.neuron_runtime.task_loop import TaskLoopError

        action = PlanningAction("read_page", "Read page", (), ("page_read",), rationale="")
        request = type("Request", (), {
            "effect": EffectClass.READ_ONLY,
            "capability": "browser.read",
            "task_id": "task-test",
        })()
        result = executor.execute(action, request)
        self.assertEqual(result.outcome.value, "failed_no_effect")
        self.assertEqual(result.failure_code, "read_only_action_failed")


class ReadOnlyTaskLoopServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.service = LocalConsoleService(os.path.join(self.temp.name, "console.jpvr"))
        self.addCleanup(self.service.close)
        self.addCleanup(self.temp.cleanup)

    def test_explicit_memory_search_task_completes_via_task_loop(self):
        session = self.service.create_session("Read-only work")
        task = self.service.create_task(session["session_id"], "Search approved memory")
        result = self.service.run_read_only_task(
            task["task_id"],
            task["revision"],
            [{"capability": "memory.search", "input": "JARVIS"}],
        )
        self.assertEqual(result["task"]["status"], "completed")
        self.assertEqual(result["loop"]["status"], "completed")
        self.assertEqual(len(result["outputs"]), 1)
        self.assertEqual(result["outputs"][0]["capability"], "memory.search")


    def test_multiple_read_only_steps_keep_the_complete_fact_chain(self):
        session = self.service.create_session("Multi-step read-only work")
        task = self.service.create_task(session["session_id"], "Search memory twice")
        result = self.service.run_read_only_task(
            task["task_id"],
            task["revision"],
            [
                {"capability": "memory.search", "input": "JARVIS"},
                {"capability": "memory.search", "input": "neuron"},
                {"capability": "memory.search", "input": "memory"},
            ],
        )
        self.assertEqual(result["loop"]["status"], "completed")
        self.assertEqual(len(result["loop"]["executed_step_ids"]), 3)
        self.assertIn("request_received", result["loop"]["observed_facts"])
        self.assertIn("safe_step_1_completed", result["loop"]["observed_facts"])
        self.assertIn("safe_step_2_completed", result["loop"]["observed_facts"])
        self.assertIn("safe_step_3_completed", result["loop"]["observed_facts"])

    def test_shell_or_write_capabilities_are_rejected_before_execution(self):
        session = self.service.create_session("Blocked work")
        task = self.service.create_task(session["session_id"], "Try unsupported action")
        with self.assertRaises(LocalConsoleError):
            self.service.run_read_only_task(
                task["task_id"],
                task["revision"],
                [{"capability": "shell.exec", "input": "echo unsafe"}],
            )
        self.assertEqual(self.service.manager.get_task(task["task_id"]).status.value, "queued")


if __name__ == "__main__":
    unittest.main()
