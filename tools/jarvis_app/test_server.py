"""Regression tests for the loopback-only JARVIS local console."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from io import BytesIO
import os
import zipfile
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
from tools.jarvis_app.visible_browser import VisibleBrowserUnavailable
from tools.neuron_runtime.task_session import TaskConflict, TaskStatus
from tools.neuron_runtime.workspace_store import WorkspaceSnapshot, encode_workspace


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

    def test_visible_browser_is_disabled_on_android_platform(self):
        service = LocalConsoleService(
            os.path.join(self.temp.name, "android-console.jpvr"),
            platform="android",
        )
        try:
            status = service.browser_status()
            self.assertFalse(status["supported"])
            self.assertFalse(status["browser_open"])
            with self.assertRaises(VisibleBrowserUnavailable):
                service.browser_search("test query")
        finally:
            service.close()

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

    def test_desktop_console_serves_navigation_and_local_preferences(self):
        with urlopen(self.base_url + "/", timeout=3) as response:
            html = response.read().decode("utf-8")
            self.assertEqual(response.status, 200)
            self.assertIn("text/html", response.headers.get("Content-Type", ""))

        for expected in (
            'class="side-nav"',
            'data-nav-target="chat"',
            'data-nav-target="network"',
            'data-nav-target="browser"',
            'data-nav-target="settings"',
            'id="compact-ui-toggle"',
            'id="motion-ui-toggle"',
            'id="settings-platform"',
            'id="ans100-desktop-status"',
            "Создай папку Проекты",
            "Закрой вкладку",
        ):
            self.assertIn(expected, html)
        self.assertEqual(html.count('id="events"'), 1)

        with urlopen(self.base_url + "/app.js", timeout=3) as response:
            app_js = response.read().decode("utf-8")
        self.assertIn('const links = $("[data-nav-target]");', app_js)
        self.assertIn("normalBrowserSearch", app_js)
        self.assertIn("publicBrowserCommandUrl", app_js)
        self.assertIn("Команда браузера", app_js)
        self.assertIn("Команды браузера работают отдельно", app_js)
        self.assertIn('window.open("about:blank"', app_js)
        self.assertIn('browserExtensionCommand("close-last-tab")', app_js)
        self.assertIn('browserExtensionCommand("close-last-window")', app_js)
        self.assertIn('api("/api/folders"', app_js)
        self.assertIn('api("/api/browser/close-tab"', app_js)

        with urlopen(self.base_url + "/styles.css", timeout=3) as response:
            styles = response.read().decode("utf-8")
        self.assertIn(".desktop-workspace", styles)
        self.assertIn("body.reduced-motion", styles)

        with urlopen(self.base_url + "/sw.js", timeout=3) as response:
            worker = response.read().decode("utf-8")
        self.assertIn('jarvis-local-console-v15', worker)

    def test_browser_close_tab_endpoint_dispatches_without_launching_a_browser(self):
        original = self.service.browser_close_tab
        self.service.browser_close_tab = lambda: {
            "supported": True,
            "browser_open": True,
            "closed_tab": True,
            "remaining_tabs": 1,
        }
        try:
            status, payload = self._post("/api/browser/close-tab", {})
        finally:
            self.service.browser_close_tab = original
        self.assertEqual(status, 200)
        self.assertTrue(payload["closed_tab"])
        self.assertEqual(payload["remaining_tabs"], 1)

    def test_folder_creation_endpoint_is_authenticated_and_confined(self):
        with self.assertRaises(HTTPError) as missing_auth:
            self._post("/api/folders", {"name": "Projects"}, authenticated=False)
        self.assertEqual(missing_auth.exception.code, 401)

        status, payload = self._post("/api/folders", {"name": "Projects"})
        self.assertEqual(status, 201)
        self.assertEqual(payload["name"], "Projects")
        self.assertTrue(payload["created"])
        self.assertTrue(os.path.isdir(os.path.join(self.temp.name, "workspace-files", "Projects")))

        with self.assertRaises(HTTPError):
            self._post("/api/folders", {"name": "../outside"})
        self.assertFalse(os.path.exists(os.path.join(self.temp.name, "outside")))

        with self.assertRaises(HTTPError):
            self._post("/api/folders", {"name": "Projects"})

    def test_desktop_ans100_status_is_authenticated_and_reports_live_chat_integration(self):
        with self.assertRaises(HTTPError) as missing_auth:
            urlopen(self.base_url + "/api/ans100", timeout=3)
        self.assertEqual(missing_auth.exception.code, 401)

        request = Request(
            self.base_url + "/api/ans100",
            headers={"Authorization": "Bearer " + self.token},
        )
        with urlopen(request, timeout=3) as response:
            payload = json.loads(response.read().decode("utf-8"))
        topology = payload["desktop_topology"]
        self.assertEqual(topology["neuron_count"], 10_000)
        self.assertEqual(topology["declared_connection_count"], 25_500)
        self.assertEqual(len(topology["populations"]), 7)
        self.assertTrue(payload["active_in_chat"])
        self.assertEqual(payload["runtime_state"], "ten_thousand_neuron_sparse_network_integrated")
        self.assertEqual(payload["runtime"]["runtime_version"], "JARVIS_ANS100_HYBRID_V4")
        self.assertEqual(payload["runtime"]["neuron_count"], 10_000)
        self.assertEqual(payload["runtime"]["connection_count"], 25_500)
        self.assertEqual(payload["runtime"]["input_contract"], "JARVIS_TYPED_EVIDENCE_V1")
        self.assertFalse(payload["runtime"]["learning_enabled"])
        self.assertFalse(payload["runtime"]["persistent_state"])
        self.assertEqual(payload["runtime"]["decision_authority"], "advisory_only")

    def test_visible_browser_status_is_authenticated_and_desktop_only(self):
        with self.assertRaises(HTTPError) as missing_auth:
            urlopen(self.base_url + "/api/browser", timeout=3)
        self.assertEqual(missing_auth.exception.code, 401)
        request = Request(
            self.base_url + "/api/browser",
            headers={"Authorization": "Bearer " + self.token},
        )
        with urlopen(request, timeout=3) as response:
            payload = json.loads(response.read().decode("utf-8"))
        self.assertEqual(response.status, 200)
        self.assertTrue(payload["supported"])
        self.assertFalse(payload["browser_open"])

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

    def test_dialog_rstdp_status_is_authenticated_and_reports_separate_spiking_network(self):
        with self.assertRaises(HTTPError) as missing_auth:
            urlopen(self.base_url + "/api/dialog-rstdp", timeout=3)
        self.assertEqual(missing_auth.exception.code, 401)

        request = Request(
            self.base_url + "/api/dialog-rstdp",
            headers={"Authorization": "Bearer " + self.token},
        )
        with urlopen(request, timeout=3) as response:
            state = json.loads(response.read().decode("utf-8"))
        self.assertEqual(response.status, 200)
        self.assertEqual(state["state"], "experimental_sparse_dialogue_rstdp")
        self.assertEqual(state["input_feature_neurons"], 256)
        self.assertEqual(state["output_spiking_neurons"], 4)
        self.assertEqual(
            state["trained_examples_per_label"],
            {label: 0 for label in ("greeting", "question", "thanks", "farewell")},
        )
        self.assertFalse(state["raw_utterances_persisted"])

    def test_dialog_rstdp_learn_endpoint_requires_auth_and_changes_explicitly_labelled_state(self):
        payload = {
            "text": "hello jarvis this is a dialogue learning sample",
            "expected_label": "greeting",
            "feedback_id": "00000000-0000-4000-8000-000000000001",
        }
        with self.assertRaises(HTTPError) as missing_auth:
            self._post("/api/dialog-rstdp/learn", payload, authenticated=False)
        self.assertEqual(missing_auth.exception.code, 401)

        status, result = self._post("/api/dialog-rstdp/learn", payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["status"], "APPLIED")
        self.assertEqual(result["expected_label"], "greeting")
        self.assertEqual(result["feedback_id"], payload["feedback_id"])
        self.assertGreater(
            result["scores_after"]["greeting"],
            result["scores_before"]["greeting"],
        )
        self.assertFalse(result["raw_utterances_persisted"])

        status, repeated = self._post("/api/dialog-rstdp/learn", payload)
        self.assertEqual(status, 200)
        self.assertEqual(repeated["status"], "DUPLICATE")

    def test_dialog_rstdp_endpoint_rejects_unknown_payload_fields(self):
        status = None
        try:
            self._post(
                "/api/dialog-rstdp/learn",
                {"text": "hello", "expected_label": "greeting", "command": "run shell"},
            )
        except HTTPError as error:
            status = error.code
        self.assertEqual(status, 400)
        state = self.service.dialog_rstdp.status()
        self.assertEqual(
            state["trained_examples_per_label"],
            {label: 0 for label in ("greeting", "question", "thanks", "farewell")},
        )

    def test_ans100_signal_endpoint_requires_auth_and_returns_runtime_summary(self):
        payload = {"prompt": "Как подключить ANS-100 к JARVIS?"}
        with self.assertRaises(HTTPError) as missing_auth:
            self._post("/api/ans100/conversation-signal", payload, authenticated=False)
        self.assertEqual(missing_auth.exception.code, 401)

        status, result = self._post("/api/ans100/conversation-signal", payload)
        self.assertEqual(status, 200)
        self.assertEqual(result["runtime_version"], "JARVIS_ANS100_CONVERSATION_SIGNAL_V2")
        self.assertEqual(result["network_neuron_count"], 10)
        self.assertEqual(result["active_neuron_count"], 10)
        self.assertEqual(result["processed_events"], 15)
        self.assertEqual(result["processed_batches"], 10)
        self.assertTrue(result["attention_gate_active"])
        self.assertFalse(result["learning_enabled"])
        self.assertTrue(result["state_persisted"])
        self.assertIn("integrator_adaptation", result["features"])
        self.assertIn("punctuation_density", result["features"])
        self.assertIn("sentence_density", result["features"])
        self.assertIn("uppercase_ratio", result["features"])
        self.assertIn("effective_threshold", result["features"])
        self.assertEqual(result["scope"], "low_level_signal_preprocessing_only")

    def test_workspace_endpoint_supports_authenticated_cas_round_trip(self):
        with self.assertRaises(HTTPError) as missing_auth:
            urlopen(self.base_url + "/api/workspace", timeout=3)
        self.assertEqual(missing_auth.exception.code, 401)

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

    def test_ans100_signal_service_is_bounded_and_does_not_mutate_workspace(self):
        before = self.service.workspace_state()["revision"]
        result = self.service.analyze_conversation_signal("Как подключить ANS-100?")

        self.assertEqual(
            result["runtime_version"],
            "JARVIS_ANS100_CONVERSATION_SIGNAL_V2",
        )
        self.assertEqual(result["network_neuron_count"], 10)
        self.assertEqual(result["active_neuron_count"], 10)
        self.assertEqual(result["processed_events"], 15)
        self.assertEqual(result["processed_batches"], 10)
        self.assertTrue(result["attention_gate_active"])
        self.assertFalse(result["learning_enabled"])
        self.assertTrue(result["state_persisted"])
        self.assertIn("integrator_adaptation", result["features"])
        self.assertEqual(self.service.workspace_state()["revision"], before)

    def test_health_advertises_versioned_android_bridge_contract(self):
        with urlopen(self.base_url + "/api/health", timeout=3) as response:
            payload = json.loads(response.read().decode("utf-8"))
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["storage"], "jpvr")
        self.assertEqual(payload["api_schema"], "jarvis.local-console")
        self.assertEqual(payload["api_version"], 2)
        self.assertTrue(payload["auth_required"])
        self.assertEqual(payload["auth_scheme"], "Bearer")
        self.assertEqual(payload["workspace_api_version"], 1)
        self.assertEqual(payload["platform"], "desktop")

    def test_workspace_restore_replaces_all_records_with_revision_guard(self):
        initial = self.service.workspace_state()
        now = datetime.now(timezone.utc).isoformat()
        records = initial["workspace"]["records"]
        records["conversations"].append({
            "id": "restore-conversation-001",
            "revision": 0,
            "updated_at": now,
            "title": "Large conversation",
            "messages": [{
                "id": "restore-message-001",
                "role": "assistant",
                "created_at": now,
                "content": "x" * 70_000,
            }],
        })
        records["memories"].append({
            "id": "restore-memory-001",
            "revision": 0,
            "updated_at": now,
            "content": "A backup memory for restore coverage.",
            "category": "test",
            "user_approved": True,
        })
        replacement = json.loads(encode_workspace(WorkspaceSnapshot(
            workspace_id="jarvis-default",
            revision=0,
            exported_at=now,
            source_platform="desktop",
            records=records,
        )))
        status, restored = self._post("/api/workspace/restore", {
            "expected_revision": initial["revision"],
            "workspace": replacement,
        })
        self.assertEqual(status, 200)
        self.assertEqual(
            [item["id"] for item in restored["workspace"]["records"]["memories"]],
            ["restore-memory-001"],
        )
        self.assertEqual(
            len(restored["workspace"]["records"]["conversations"][0]["messages"][0]["content"]),
            70_000,
        )
        current_revision = restored["revision"]

        empty_replacement = initial["workspace"]
        status, replaced = self._post("/api/workspace/restore", {
            "expected_revision": current_revision,
            "workspace": empty_replacement,
        })
        self.assertEqual(status, 200)
        self.assertEqual(replaced["revision"], current_revision + 1)
        self.assertEqual(replaced["workspace"]["records"]["memories"], [])

        with self.assertRaises(HTTPError) as unauthenticated:
            self._post("/api/workspace/restore", {
                "expected_revision": replaced["revision"],
                "workspace": replacement,
            }, authenticated=False)
        self.assertEqual(unauthenticated.exception.code, 401)

        with self.assertRaises(HTTPError) as stale:
            self._post("/api/workspace/restore", {
                "expected_revision": current_revision,
                "workspace": replacement,
            })
        self.assertEqual(stale.exception.code, 409)
        latest = self.service.workspace_state()
        self.assertEqual(latest["workspace"]["records"]["memories"], [])

    def test_workspace_restore_rejects_foreign_identity_and_tampered_digest(self):
        initial = self.service.workspace_state()
        now = datetime.now(timezone.utc).isoformat()
        foreign = json.loads(encode_workspace(WorkspaceSnapshot(
            workspace_id="another-workspace",
            revision=0,
            exported_at=now,
            source_platform="desktop",
            records=initial["workspace"]["records"],
        )))
        with self.assertRaises(HTTPError) as wrong_identity:
            self._post("/api/workspace/restore", {
                "expected_revision": initial["revision"],
                "workspace": foreign,
            })
        self.assertEqual(wrong_identity.exception.code, 409)

        tampered = json.loads(json.dumps(initial["workspace"]))
        tampered["integrity"]["digest"] = "0" * 64
        with self.assertRaises(HTTPError) as invalid_digest:
            self._post("/api/workspace/restore", {
                "expected_revision": initial["revision"],
                "workspace": tampered,
            })
        self.assertEqual(invalid_digest.exception.code, 400)
        unchanged = self.service.workspace_state()
        self.assertEqual(unchanged["revision"], initial["revision"])
        self.assertEqual(unchanged["workspace"]["records"], initial["workspace"]["records"])

    def test_workspace_api_creates_and_deletes_explicit_memories(self):
        initial = self.service.workspace_state()
        records = initial["workspace"]["records"]
        now = datetime.now(timezone.utc).isoformat()
        records["memories"].append({
            "id": "memory-api-test-001",
            "revision": 0,
            "updated_at": now,
            "content": "The user explicitly approved this test memory.",
            "category": "test",
            "user_approved": True,
        })
        created_document = json.loads(encode_workspace(WorkspaceSnapshot(
            workspace_id="jarvis-default",
            revision=0,
            exported_at=now,
            source_platform="desktop",
            records=records,
        )))
        status, created = self._post("/api/workspace", {
            "expected_revision": initial["revision"],
            "workspace": created_document,
        })
        self.assertEqual(status, 200)
        self.assertEqual(len(created["workspace"]["records"]["memories"]), 1)

        delete_records = created["workspace"]["records"]
        delete_records["memories"] = []
        delete_time = datetime.now(timezone.utc).isoformat()
        delete_document = json.loads(encode_workspace(WorkspaceSnapshot(
            workspace_id="jarvis-default",
            revision=0,
            exported_at=delete_time,
            source_platform="desktop",
            records=delete_records,
        )))
        status, deleted = self._post("/api/workspace", {
            "expected_revision": created["revision"],
            "workspace": delete_document,
        })
        self.assertEqual(status, 200)
        self.assertEqual(deleted["workspace"]["records"]["memories"], [])

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
            (
                "/api/reasoning/plan",
                {
                    "reasoning_request": {
                        "request_id": "unauthorized-plan",
                        "idempotency_key": "unauthorized-idem",
                        "objective": "Must not be processed",
                    },
                },
            ),
            (
                "/api/reasoning/verify",
                {
                    "reasoning_request": {
                        "request_id": "unauthorized-verify",
                        "idempotency_key": "unauthorized-verify-idem",
                        "objective": "Must not be processed",
                    },
                    "plan_id": "0" * 64,
                    "completed_step_ids": [],
                    "observations": [],
                },
            ),
            ("/api/browser/search", {"query": "unauthorized browser search"}),
            ("/api/browser/open", {"url": "https://example.com/"}),
            ("/api/browser/read", {}),
            ("/api/browser/control", {"paused": True}),
            ("/api/browser/stop", {}),
            ("/api/browser/ask", {"question": "unauthorized", "expected_revision": 0}),
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


    def test_core_reasoning_plan_and_outcome_review_are_exposed_by_authenticated_api(self):
        request_payload = {
            "request_id": "req-server-1",
            "idempotency_key": "idem-server-1",
            "objective": "Prepare the report",
            "subtasks": [
                {
                    "task_id": "collect",
                    "title": "Collect the source material",
                    "criteria": [
                        {"criterion_id": "source-ready", "description": "The source material is collected"},
                    ],
                },
                {
                    "task_id": "review",
                    "title": "Review the source material",
                    "depends_on": ["collect"],
                    "criteria": [
                        {"criterion_id": "review-ready", "description": "The material is reviewed"},
                    ],
                },
            ],
        }
        status, plan = self._post(
            "/api/reasoning/plan", {"reasoning_request": request_payload},
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(plan["steps"]), 2)
        self.assertFalse(plan["execution_authorized"])

        status, reviewed = self._post(
            "/api/reasoning/verify",
            {
                "reasoning_request": request_payload,
                "plan_id": plan["plan_id"],
                "completed_step_ids": ["collect", "review"],
                "observations": [
                    {"criterion_id": "source-ready", "passed": True, "evidence_refs": ["file:1"]},
                    {"criterion_id": "review-ready", "passed": True, "evidence_refs": ["file:2"]},
                ],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(reviewed["status"], "verified")
        self.assertEqual(reviewed["scope"], "caller_supplied_observation_review")

    def test_reasoning_verification_rejects_a_plan_id_from_another_request(self):
        request_payload = {
            "request_id": "req-server-2",
            "idempotency_key": "idem-server-2",
            "objective": "Prepare another report",
            "subtasks": [],
        }
        status, plan = self._post(
            "/api/reasoning/plan", {"reasoning_request": request_payload},
        )
        self.assertEqual(status, 200)
        request_payload["objective"] = "Changed after planning"
        status, result = self._post(
            "/api/reasoning/verify",
            {
                "reasoning_request": request_payload,
                "plan_id": plan["plan_id"],
                "completed_step_ids": ["task-main"],
                "observations": [],
            },
        )
        self.assertEqual(status, 400)
        self.assertIn("plan_id", result["error"])

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

    def test_browser_extension_archive_is_downloadable_and_has_manifest(self):
        with urlopen(self.base_url + "/downloads/jarvis-browser-extension.zip", timeout=3) as response:
            archive_bytes = response.read()
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers.get_content_type(), "application/zip")
            self.assertIn("attachment", response.headers.get("Content-Disposition", ""))

        with zipfile.ZipFile(BytesIO(archive_bytes)) as archive:
            names = set(archive.namelist())
            self.assertIn("jarvis-browser-extension/manifest.json", names)
            self.assertIn("jarvis-browser-extension/popup.html", names)
            self.assertIn("jarvis-browser-extension/popup.js", names)
            self.assertIn("jarvis-browser-extension/background.js", names)
            self.assertIn("jarvis-browser-extension/bridge.js", names)
            manifest = json.loads(archive.read("jarvis-browser-extension/manifest.json"))
            background = archive.read("jarvis-browser-extension/background.js").decode("utf-8")
            bridge = archive.read("jarvis-browser-extension/bridge.js").decode("utf-8")
        self.assertIn("closeLastOwnedTab", background)
        self.assertIn("closeLastOwnedWindow", background)
        self.assertIn("chrome.tabs.remove(tab.id)", background)
        self.assertIn("chrome.windows.remove(win.id)", background)
        self.assertIn("JARVIS_LOCAL_CONSOLE", bridge)
        self.assertEqual(manifest["manifest_version"], 3)
        self.assertEqual(manifest["version"], "1.1.0")
        self.assertEqual(manifest["background"]["service_worker"], "background.js")
        self.assertEqual(manifest["version"], "1.1.0")
        self.assertEqual(manifest["background"]["service_worker"], "background.js")
        self.assertIn("activeTab", manifest["permissions"])
        self.assertNotIn("tabs", manifest["permissions"])
        self.assertEqual(
            manifest["host_permissions"],
            ["http://127.0.0.1:8765/*"],
        )

    def test_browser_extension_cors_is_narrow_and_authenticated(self):
        extension_origin = "chrome-extension://" + ("a" * 32)
        preflight = Request(
            self.base_url + "/api/extension/state",
            headers={
                "Origin": extension_origin,
                "Access-Control-Request-Method": "GET",
                "Access-Control-Request-Headers": "authorization",
            },
            method="OPTIONS",
        )
        with urlopen(preflight, timeout=3) as response:
            self.assertEqual(response.status, 204)
            self.assertEqual(response.headers.get("Access-Control-Allow-Origin"), extension_origin)
            self.assertIn("GET", response.headers.get("Access-Control-Allow-Methods", ""))

        request = Request(
            self.base_url + "/api/extension/state",
            headers={
                "Origin": extension_origin,
                "Authorization": "Bearer " + self.token,
            },
        )
        with urlopen(request, timeout=3) as response:
            payload = json.loads(response.read().decode("utf-8"))
            self.assertEqual(response.headers.get("Access-Control-Allow-Origin"), extension_origin)
        self.assertTrue(payload["supported"])
        self.assertIn("revision", payload)
        self.assertIn("chat_available", payload)

        foreign = Request(
            self.base_url + "/api/extension/state",
            headers={
                "Origin": "https://untrusted.example",
                "Authorization": "Bearer " + self.token,
            },
        )
        with self.assertRaises(HTTPError) as blocked:
            urlopen(foreign, timeout=3)
        self.assertEqual(blocked.exception.code, 403)

    def test_extension_rejects_local_page_before_reading_or_sending_content(self):
        with self.assertRaisesRegex(LocalConsoleError, "Private, loopback and reserved"):
            self.service.ask_about_extension_page(
                "Summarize this page",
                self.service.workspace_revision,
                "Local router",
                "http://127.0.0.1/",
                "private local page contents",
            )

    def test_extension_ask_endpoint_rejects_missing_token(self):
        request = Request(
            self.base_url + "/api/extension/ask",
            data=json.dumps({
                "question": "Summarize this page",
                "expected_revision": self.service.workspace_revision,
                "title": "Example",
                "url": "https://example.com/",
                "text": "Page text",
            }).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Origin": "chrome-extension://" + ("a" * 32),
            },
            method="POST",
        )
        with self.assertRaises(HTTPError) as blocked:
            urlopen(request, timeout=3)
        self.assertEqual(blocked.exception.code, 401)

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
