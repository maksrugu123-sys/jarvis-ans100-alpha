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


class LocalConsoleHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.service = LocalConsoleService(os.path.join(self.temp.name, "http.jpvr"))
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

    def _post(self, path, payload, *, headers=None):
        request_headers = {"Content-Type": "application/json"}
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
