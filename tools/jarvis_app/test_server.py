"""Regression tests for the loopback-only JARVIS local console."""
from __future__ import annotations

import json
import os
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from tools.jarvis_app.server import (
    LocalConsoleError,
    LocalConsoleService,
    create_http_server,
)
from tools.neuron_runtime.task_session import TaskConflict, TaskStatus


class LocalConsoleServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.data_path = os.path.join(self.temp.name, "console.jpvr")
        self.service = LocalConsoleService(self.data_path)
        self.addCleanup(self.service.close)
        self.addCleanup(self.temp.cleanup)

    def test_session_can_pause_resume_and_close_with_revision_checks(self):
        session = self.service.create_session("Main session")
        paused = self.service.set_session_status(
            session["session_id"], "paused", session["revision"],
        )
        self.assertEqual(paused["status"], "paused")
        self.assertEqual(paused["revision"], session["revision"] + 1)

        resumed = self.service.set_session_status(
            session["session_id"], "open", paused["revision"],
        )
        self.assertEqual(resumed["status"], "open")
        with self.assertRaises(TaskConflict):
            self.service.set_session_status(
                session["session_id"], "paused", session["revision"],
            )

        closed = self.service.set_session_status(
            session["session_id"], "closed", resumed["revision"],
        )
        self.assertEqual(closed["status"], "closed")

    def test_session_cannot_close_while_task_is_nonterminal(self):
        session = self.service.create_session("Active work")
        task = self.service.create_task(session["session_id"], "Pending task")
        with self.assertRaises(TaskConflict):
            self.service.set_session_status(
                session["session_id"], "closed", session["revision"],
            )

        self.service.cancel_queued_task(task["task_id"], task["revision"])
        closed = self.service.set_session_status(
            session["session_id"], "closed", session["revision"],
        )
        self.assertEqual(closed["status"], "closed")

    def test_repeating_current_status_does_not_create_a_new_checkpoint(self):
        session = self.service.create_session("No-op check")
        version = self.service.checkpoint_version
        unchanged = self.service.set_session_status(
            session["session_id"], "open", session["revision"],
        )
        self.assertEqual(unchanged["revision"], session["revision"])
        self.assertEqual(self.service.checkpoint_version, version)

    def test_access_token_persists_and_rotation_revokes_previous_token(self):
        auth_path = os.path.join(self.temp.name, "console.auth")
        auth_data_path = os.path.join(self.temp.name, "auth-test.jpvr")
        first = LocalConsoleService(auth_data_path, auth_path)
        first_token = first.bootstrap_token
        self.assertIsNotNone(first_token)
        self.assertTrue(first.authenticate(first_token))
        self.assertEqual(os.stat(auth_path).st_mode & 0o777, 0o600)
        first.close()

        second = LocalConsoleService(auth_data_path, auth_path)
        self.assertIsNone(second.bootstrap_token)
        self.assertTrue(second.authenticate(first_token))
        second.close()

        rotated = LocalConsoleService(auth_data_path, auth_path, rotate_token=True)
        self.assertIsNotNone(rotated.bootstrap_token)
        self.assertFalse(rotated.authenticate(first_token))
        self.assertTrue(rotated.authenticate(rotated.bootstrap_token))
        rotated.close()


    def test_auth_path_cannot_alias_jpvr_file_even_during_rotation(self):
        with open(self.data_path, "rb") as stream:
            before = stream.read()

        with self.assertRaisesRegex(LocalConsoleError, "different files"):
            LocalConsoleService(
                self.data_path,
                self.data_path,
                rotate_token=True,
            )

        with open(self.data_path, "rb") as stream:
            self.assertEqual(stream.read(), before)



    def test_hard_link_auth_path_cannot_alias_jpvr_file_during_rotation(self):
        alias_path = os.path.join(self.temp.name, "auth-hard-link")
        try:
            os.link(self.data_path, alias_path)
        except (OSError, NotImplementedError):
            self.skipTest("hard links are unavailable on this filesystem")
        with open(self.data_path, "rb") as stream:
            before = stream.read()

        with self.assertRaisesRegex(LocalConsoleError, "different files"):
            LocalConsoleService(
                self.data_path,
                alias_path,
                rotate_token=True,
            )

        with open(self.data_path, "rb") as stream:
            self.assertEqual(stream.read(), before)


    def test_reasoning_preview_returns_plan_without_changing_jpvr_state(self):
        version_before = self.service.checkpoint_version
        payload = {
            "task_id": "preview-service",
            "goal_description": "Prepare the system",
            "initial_facts": ["battery_ok"],
            "goal_facts": ["ready"],
            "actions": [
                {
                    "action_id": "power_on",
                    "summary": "Power on",
                    "preconditions": ["battery_ok"],
                    "add_facts": ["powered_on"],
                },
                {
                    "action_id": "boot",
                    "summary": "Boot",
                    "preconditions": ["powered_on"],
                    "add_facts": ["ready"],
                },
            ],
        }
        result = self.service.preview_reasoning(payload)

        self.assertEqual(result["status"], "solved")
        self.assertEqual(
            [step["action_id"] for step in result["plan"]],
            ["power_on", "boot"],
        )
        self.assertEqual(
            result["notice"],
            "Это только символьный preview. Действия не исполнялись.",
        )
        self.assertEqual(self.service.checkpoint_version, version_before)
        self.assertEqual(self.service.state()["tasks"], [])

    def test_reasoning_preview_rejects_duplicate_facts(self):
        with self.assertRaisesRegex(ValueError, "duplicates"):
            self.service.preview_reasoning({
                "task_id": "preview-invalid",
                "goal_description": "Prepare the system",
                "initial_facts": ["ready", "ready"],
                "goal_facts": ["ready"],
                "actions": [],
            })


class LocalConsoleHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.service = LocalConsoleService(os.path.join(self.temp.name, "http.jpvr"))
        self.token = self.service.bootstrap_token
        self.server = create_http_server(self.service, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"
        self.addCleanup(self._shutdown)

    def _shutdown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.service.close()
        self.temp.cleanup()

    def _post(self, path, payload, *, headers=None, authenticated=True):
        request_headers = {"Content-Type": "application/json"}
        if authenticated and self.token:
            request_headers["Authorization"] = "Bearer " + self.token
        if headers:
            request_headers.update(headers)
        request = Request(
            self.base_url + path,
            data=json.dumps(payload).encode("utf-8"),
            headers=request_headers,
            method="POST",
        )
        with urlopen(request, timeout=3) as response:
            return response.status, json.loads(response.read().decode("utf-8"))

    def test_session_status_endpoint_persists_and_returns_updated_state(self):
        created = self.service.create_session("HTTP session")
        status, payload = self._post(
            f"/api/sessions/{created['session_id']}/status",
            {"status": "paused", "expected_revision": created["revision"]},
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload["session"]["status"], "paused")
        self.assertEqual(
            payload["state"]["sessions"][0]["revision"],
            created["revision"] + 1,
        )

    def test_workspace_endpoint_supports_authenticated_cas_round_trip(self):
        initial = self.service.workspace_state()
        status, saved = self._post(
            "/api/workspace",
            {
                "expected_revision": initial["revision"],
                "workspace": initial["workspace"],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(saved["revision"], initial["revision"] + 1)
        self.assertEqual(saved["workspace"]["workspace_id"], "jarvis-default")

        request = Request(
            self.base_url + "/api/workspace",
            headers={"Authorization": "Bearer " + self.token},
        )
        with urlopen(request, timeout=3) as response:
            restored = json.loads(response.read().decode("utf-8"))
        self.assertEqual(restored["revision"], saved["revision"])

        stale = Request(
            self.base_url + "/api/workspace",
            data=json.dumps({
                "expected_revision": initial["revision"],
                "workspace": initial["workspace"],
            }).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.token,
            },
            method="POST",
        )
        with self.assertRaises(HTTPError) as conflict:
            urlopen(stale, timeout=3)
        self.assertEqual(conflict.exception.code, 409)

    def test_health_advertises_versioned_android_bridge_contract(self):
        with urlopen(self.base_url + "/api/health", timeout=3) as response:
            payload = json.loads(response.read().decode("utf-8"))
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["storage"], "jpvr")
        self.assertEqual(payload["api_schema"], "jarvis.local-console")
        self.assertEqual(payload["api_version"], 2)
        self.assertTrue(payload["auth_required"])
        self.assertEqual(payload["auth_scheme"], "Bearer")

    def test_http_requires_a_valid_bearer_token_for_state_and_mutations(self):
        with self.assertRaises(HTTPError) as missing:
            urlopen(self.base_url + "/api/state", timeout=3)
        self.assertEqual(missing.exception.code, 401)

        wrong = Request(
            self.base_url + "/api/state",
            headers={"Authorization": "Bearer " + "A" * 43},
        )
        with self.assertRaises(HTTPError) as invalid:
            urlopen(wrong, timeout=3)
        self.assertEqual(invalid.exception.code, 401)

        with urlopen(Request(
            self.base_url + "/api/state",
            headers={"Authorization": "Bearer " + self.token},
        ), timeout=3) as response:
            payload = json.loads(response.read().decode("utf-8"))
        self.assertEqual(response.status, 200)
        self.assertEqual(payload["tasks"], [])

        with self.assertRaises(HTTPError) as blocked_mutation:
            self._post("/api/sessions", {"title": "Must remain blocked"}, authenticated=False)
        self.assertEqual(blocked_mutation.exception.code, 401)
        self.assertEqual(self.service.state()["sessions"], [])

    def test_every_post_route_rejects_missing_token_before_processing(self):
        session = self.service.create_session("Protected session")
        task = self.service.create_task(session["session_id"], "Protected task")
        version_before = self.service.checkpoint_version
        session_id = session["session_id"]
        task_id = task["task_id"]
        routes = [
            ("/api/sessions", {"title": "unauthorized"}),
            ("/api/tasks", {"session_id": session_id, "title": "unauthorized"}),
            (
                f"/api/sessions/{session_id}/status",
                {"status": "paused", "expected_revision": session["revision"]},
            ),
            (
                f"/api/tasks/{task_id}/cancel",
                {"expected_revision": task["revision"]},
            ),
            (
                "/api/reasoning/preview",
                {
                    "task_id": "unauthorized-preview",
                    "goal_description": "Must not be processed",
                    "initial_facts": ["start"],
                    "goal_facts": ["finish"],
                    "actions": [],
                },
            ),
        ]

        for path, payload in routes:
            with self.subTest(path=path):
                with self.assertRaises(HTTPError) as captured:
                    self._post(path, payload, authenticated=False)
                self.assertEqual(captured.exception.code, 401)

        state = self.service.state()
        self.assertEqual(self.service.checkpoint_version, version_before)
        self.assertEqual(len(state["sessions"]), 1)
        self.assertEqual(len(state["tasks"]), 1)
        self.assertEqual(state["sessions"][0]["status"], "open")
        self.assertEqual(state["tasks"][0]["status"], "queued")


    def test_reasoning_preview_http_endpoint_returns_symbolic_plan(self):
        status, payload = self._post(
            "/api/reasoning/preview",
            {
                "task_id": "preview-http",
                "goal_description": "Prepare the system",
                "initial_facts": ["battery_ok"],
                "goal_facts": ["ready"],
                "actions": [
                    {
                        "action_id": "boot",
                        "summary": "Boot",
                        "preconditions": ["battery_ok"],
                        "add_facts": ["ready"],
                    },
                ],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload["status"], "solved")
        self.assertEqual(payload["plan"][0]["action_id"], "boot")
        self.assertIn("не исполнялись", payload["notice"])

    def test_foreign_origin_is_rejected(self):
        created = self.service.create_session("Origin check")
        request = Request(
            self.base_url + f"/api/sessions/{created['session_id']}/status",
            data=json.dumps({
                "status": "paused",
                "expected_revision": created["revision"],
            }).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Origin": "http://example.invalid",
            },
            method="POST",
        )
        with self.assertRaises(HTTPError) as captured:
            urlopen(request, timeout=3)
        self.assertEqual(captured.exception.code, 403)
        self.assertEqual(self.service.manager.get_session(created["session_id"]).status.value, "open")


if __name__ == "__main__":
    unittest.main()
