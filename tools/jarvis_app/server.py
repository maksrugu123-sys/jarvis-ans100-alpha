"""Loopback-only local JARVIS task console.

This is a thin UI for bounded Python reference APIs. It has bearer-token authentication
but no tool executor, external credentials, shell access, or side-effecting capabilities.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import threading
import uuid
from urllib.parse import urlsplit

from .auth import LocalConsoleAuth, LocalConsoleAuthError
from .local_inference import LiteRtLmLocalEngine, LocalInferenceError
from .jarvis_core import JarvisCore

from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.reasoning_engine import (
    PlanningAction,
    ReasoningEngine,
    ReasoningError,
    ReasoningProblem,
)
from tools.neuron_runtime.task_session import (
    SessionStatus,
    TaskConflict,
    TaskNotFound,
    TaskSessionError,
    TaskSessionManager,
    TaskStatus,
)
from tools.neuron_runtime.workspace_store import (
    JPVRWorkspaceStore, WorkspaceConflict, WorkspaceError, WorkspaceNotFound,
    WorkspaceSnapshot, decode_workspace, encode_workspace,
)
from tools.neuron_runtime.task_session_store import (
    JPVRTaskSessionCheckpointError,
    JPVRTaskSessionCheckpointNotFound,
    JPVRTaskSessionCheckpointStore,
)


MANAGER_ID = "android-local-console"
MAX_REQUEST_BYTES = 64 * 1024
MAX_WORKSPACE_REQUEST_BYTES = 16 * 1024 * 1024
MAX_PREVIEW_ACTIONS = 64
_STATE_EVENT_LIMIT = 100
_STATIC_DIR = Path(__file__).with_name("static")
_STATIC_FILES = {
    "/": "index.html",
    "/index.html": "index.html",
    "/app.js": "app.js",
    "/styles.css": "styles.css",
    "/manifest.webmanifest": "manifest.webmanifest",
    "/sw.js": "sw.js",
    "/icon.svg": "icon.svg",
}


class LocalConsoleError(ValueError):
    """Invalid local-console request or unavailable persisted state."""


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise LocalConsoleError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _required_text(value: object, name: str, *, maximum: int = 128) -> str:
    if not isinstance(value, str):
        raise LocalConsoleError(f"{name} must be text")
    clean = value.strip()
    if not clean or len(clean) > maximum or "\x00" in clean:
        raise LocalConsoleError(f"{name} must contain 1..{maximum} characters")
    return clean


def _facts(value: object, name: str) -> tuple[str, ...]:
    if not isinstance(value, list) or len(value) > 256:
        raise LocalConsoleError(f"{name} must be a list with at most 256 items")
    facts = tuple(_required_text(item, name, maximum=128) for item in value)
    if len(set(facts)) != len(facts):
        raise LocalConsoleError(f"{name} must not contain duplicates")
    return facts


def _session_view(record) -> dict[str, object]:
    return {
        "session_id": record.session_id,
        "status": record.status.value,
        "revision": record.revision,
        "created_order": record.created_order,
        "title": record.metadata.get("title", record.session_id),
    }


def _task_view(record) -> dict[str, object]:
    return {
        "task_id": record.task_id,
        "session_id": record.session_id,
        "title": record.title,
        "status": record.status.value,
        "revision": record.revision,
        "created_order": record.created_order,
        "updated_order": record.updated_order,
        "result": record.result,
        "failure_code": record.failure_code,
    }


class LocalConsoleService:
    """Single-writer state facade backed by a dedicated task/session JPVR file."""

    def __init__(
        self,
        data_path: str | os.PathLike[str] | None = None,
        auth_path: str | os.PathLike[str] | None = None,
        *,
        rotate_token: bool = False,
        workspace_path: str | os.PathLike[str] | None = None,
        platform: str = "desktop",
        model_path: str | os.PathLike[str] | None = None,
    ) -> None:
        default_path = Path.home() / ".jarvis-ans100-alpha" / "local-console.jpvr"
        self.data_path = (
            Path(data_path).expanduser() if data_path is not None else default_path
        )
        self.auth_path = (
            Path(auth_path).expanduser() if auth_path is not None
            else self.data_path.with_name(self.data_path.name + ".access-token")
        )
        self.workspace_path = (
            Path(workspace_path).expanduser() if workspace_path is not None
            else self.data_path.with_name("workspace.jpvr")
        )
        if platform not in ("desktop", "android"):
            raise LocalConsoleError("platform must be desktop or android")
        self.platform = platform
        self.local_inference = LiteRtLmLocalEngine(model_path) if model_path is not None else None
        self.jarvis_core = JarvisCore(self.local_inference)
        self._conversation_lock = threading.Lock()
        paths = (self.data_path, self.auth_path, self.workspace_path)
        try:
            resolved = [path.resolve(strict=False) for path in paths]
            for index, path in enumerate(paths):
                for other_index in range(index):
                    same = resolved[index] == resolved[other_index]
                    if not same and path.exists() and paths[other_index].exists():
                        same = os.path.samefile(path, paths[other_index])
                    if same:
                        raise LocalConsoleError("JPVR data, workspace, and authentication paths must be separate files")
        except (OSError, RuntimeError) as exc:
            raise LocalConsoleError("cannot validate separate JARVIS storage paths") from exc
        self._lock = threading.RLock()
        self.data_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.workspace_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)

        try:
            self.storage = JPVRStorage.open(self.data_path, recover_tail=True)
        except FileNotFoundError:
            try:
                self.storage = JPVRStorage.create(self.data_path)
            except FileExistsError:
                self.storage = JPVRStorage.open(self.data_path, recover_tail=True)

        self.store = JPVRTaskSessionCheckpointStore(self.storage)
        try:
            loaded = self.store.load(MANAGER_ID)
        except JPVRTaskSessionCheckpointNotFound:
            self.manager = TaskSessionManager(manager_id=MANAGER_ID)
            try:
                saved = self.store.save(self.manager, expected_record_version=0)
            except (JPVRTaskSessionCheckpointError, OSError, ValueError) as exc:
                self.storage.close()
                raise LocalConsoleError("cannot initialize the local task/session checkpoint") from exc
            self.checkpoint_version = saved.record_version
        except (JPVRTaskSessionCheckpointError, OSError, ValueError) as exc:
            self.storage.close()
            raise LocalConsoleError("cannot restore the local task/session checkpoint") from exc
        else:
            self.manager = loaded.manager
            self.checkpoint_version = loaded.descriptor.record_version

        try:
            self.workspace_storage = JPVRStorage.open(self.workspace_path, recover_tail=True)
        except FileNotFoundError:
            try:
                self.workspace_storage = JPVRStorage.create(self.workspace_path)
            except FileExistsError:
                self.workspace_storage = JPVRStorage.open(self.workspace_path, recover_tail=True)
        self.workspace_store = JPVRWorkspaceStore(self.workspace_storage)
        try:
            self.workspace_snapshot, self.workspace_revision, _ = self.workspace_store.load()
        except WorkspaceNotFound:
            initial_workspace = WorkspaceSnapshot(
                workspace_id="jarvis-default",
                revision=0,
                exported_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
                source_platform=self.platform,
                records={"conversations": [], "memories": [], "tasks": [], "settings": []},
            )
            try:
                self.workspace_store.save(initial_workspace, expected_revision=0)
                self.workspace_snapshot, self.workspace_revision, _ = self.workspace_store.load()
            except (WorkspaceError, OSError, ValueError) as exc:
                self.workspace_storage.close()
                self.storage.close()
                raise LocalConsoleError("cannot initialize the shared JARVIS workspace") from exc
        except (WorkspaceError, OSError, ValueError) as exc:
            self.workspace_storage.close()
            self.storage.close()
            raise LocalConsoleError("cannot restore the shared JARVIS workspace") from exc

        try:
            self.auth = LocalConsoleAuth(self.auth_path, rotate=rotate_token)
        except LocalConsoleAuthError as exc:
            self.workspace_storage.close()
            self.storage.close()
            raise LocalConsoleError("cannot initialize local-console authentication") from exc
        self.bootstrap_token = self.auth.bootstrap_token

    def authenticate(self, token: str | None) -> bool:
        return self.auth.verify(token)

    def close(self) -> None:
        if self.local_inference is not None:
            self.local_inference.close()
        with self._lock:
            self.workspace_storage.close()
            self.storage.close()

    def workspace_state(self) -> dict[str, object]:
        with self._lock:
            payload = encode_workspace(self.workspace_snapshot)
            return {
                "revision": self.workspace_revision,
                "workspace": json.loads(payload.decode("utf-8")),
            }

    def restore_workspace(self, document: object, expected_revision: object) -> dict[str, object]:
        """Replace the complete portable workspace after validation and a CAS check.

        The caller must first create and retain an independent encrypted backup.
        This endpoint does not merge records and never changes workspace identity.
        """
        return self.save_workspace(document, expected_revision)

    def save_workspace(self, document: object, expected_revision: object) -> dict[str, object]:
        if isinstance(expected_revision, bool) or not isinstance(expected_revision, int):
            raise WorkspaceError("expected_revision must be an integer")
        if not isinstance(document, dict):
            raise WorkspaceError("workspace must be a JSON object")
        try:
            canonical = json.dumps(
                document, ensure_ascii=False, sort_keys=True,
                separators=(",", ":"), allow_nan=False,
            ).encode("utf-8")
            decoded = decode_workspace(canonical)
        except (TypeError, ValueError, UnicodeEncodeError) as exc:
            raise WorkspaceError("workspace document is invalid") from exc
        with self._lock:
            if decoded.workspace_id != self.workspace_snapshot.workspace_id:
                raise WorkspaceConflict("workspace_id cannot be changed through this endpoint")
            candidate = WorkspaceSnapshot(
                workspace_id=decoded.workspace_id,
                revision=0,
                exported_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
                source_platform=self.platform,
                records=decoded.records,
            )
            self.workspace_store.save(candidate, expected_revision=expected_revision)
            self.workspace_snapshot, self.workspace_revision, _ = self.workspace_store.load()
            return self.workspace_state()

    def conversation_state(self) -> dict[str, object]:
        with self._lock:
            payload = json.loads(encode_workspace(self.workspace_snapshot).decode("utf-8"))
            record = next((item for item in payload["records"]["conversations"] if item["id"] == "jarvis-local-chat"), None)
            memories = payload["records"].get("memories", [])
            core_status = self.jarvis_core.status(memories)
            configured = self.local_inference is not None
            runtime_ready = configured and self.local_inference.runtime_available
            if not configured:
                label = "Модель не настроена · локальный чат отключён"
            elif not runtime_ready:
                label = "Файл модели указан, но пакет litert-lm-api==0.17.1 не установлен"
            else:
                label = self.local_inference.backend_label
            return {
                "revision": self.workspace_revision,
                "conversation": record,
                "model_configured": configured,
                "runtime_available": runtime_ready,
                "backend_label": label,
                "core_status": core_status,
            }

    def send_conversation_message(self, text: object, expected_revision: object) -> dict[str, object]:
        if self.local_inference is None:
            raise LocalInferenceError("Укажите --model и установите litert-lm-api==0.17.1.")
        if not self.local_inference.runtime_available:
            raise LocalInferenceError("Установите пакет: pip install litert-lm-api==0.17.1")
        if not isinstance(text, str):
            raise LocalConsoleError("text должен быть строкой")
        clean = text.strip()
        if not clean or len(clean) > 900 or "\x00" in clean:
            raise LocalConsoleError("Сообщение должно содержать от 1 до 900 символов")
        if isinstance(expected_revision, bool) or not isinstance(expected_revision, int):
            raise LocalConsoleError("expected_revision должен быть целым числом")
        with self._conversation_lock:
            current = self.workspace_state()
            if current["revision"] != expected_revision:
                raise WorkspaceConflict("workspace revision changed; refresh and retry")
            document = current["workspace"]
            records = document["records"]
            conversations = list(records["conversations"])
            existing = next((item for item in conversations if item["id"] == "jarvis-local-chat"), None)
            history = []
            if existing is not None:
                history = [
                    {"role": item["role"], "content": item["content"]}
                    for item in existing["messages"][-4:]
                    if item["role"] in {"user", "assistant"}
                ]
            memories = records.get("memories", [])
            answer, turn_trace = self.jarvis_core.respond(clean, history, memories)
            now = datetime.now(timezone.utc).isoformat(timespec="seconds")
            messages = list(existing["messages"] if existing is not None else [])
            if len(messages) > 49_998:
                messages = messages[-49_998:]
            messages.extend([
                {"id": "message-" + uuid.uuid4().hex, "role": "user", "created_at": now, "content": clean},
                {"id": "message-" + uuid.uuid4().hex, "role": "assistant", "created_at": now, "content": answer},
            ])
            old_revision = existing["revision"] if existing is not None else 0
            if old_revision >= 2_147_483_647:
                raise WorkspaceError("conversation revision limit reached")
            updated = {
                "id": "jarvis-local-chat",
                "revision": old_revision + 1,
                "updated_at": now,
                "title": existing["title"] if existing is not None else clean[:160],
                "messages": messages,
            }
            conversations = [item for item in conversations if item["id"] != "jarvis-local-chat"]
            conversations.append(updated)
            records["conversations"] = conversations
            candidate = WorkspaceSnapshot(
                workspace_id=document["workspace_id"],
                revision=0,
                exported_at=now,
                source_platform=self.platform,
                records=records,
            )
            canonical = json.loads(encode_workspace(candidate).decode("utf-8"))
            saved = self.save_workspace(canonical, expected_revision)
            result_record = next(item for item in saved["workspace"]["records"]["conversations"] if item["id"] == "jarvis-local-chat")
            return {
                "revision": saved["revision"],
                "conversation": result_record,
                "model_configured": True,
                "runtime_available": True,
                "backend_label": self.local_inference.backend_label,
                "core_status": self.jarvis_core.status(memories),
                "turn_trace": turn_trace,
            }

    def _candidate(self) -> TaskSessionManager:
        try:
            return TaskSessionManager.restore_snapshot(self.manager.encode_snapshot())
        except TaskSessionError as exc:
            raise LocalConsoleError("the in-memory task/session state is invalid") from exc

    def _publish(self, candidate: TaskSessionManager) -> None:
        try:
            saved = self.store.save(
                candidate, expected_record_version=self.checkpoint_version
            )
        except (JPVRTaskSessionCheckpointError, OSError, ValueError) as exc:
            raise LocalConsoleError(
                "the change was not published; reload the local console state"
            ) from exc
        self.manager = candidate
        self.checkpoint_version = saved.record_version

    def state(self) -> dict[str, object]:
        with self._lock:
            return {
                "manager_id": self.manager.manager_id,
                "checkpoint_version": self.checkpoint_version,
                "sessions": [_session_view(item) for item in self.manager.sessions],
                "tasks": [_task_view(item) for item in self.manager.tasks],
                "events": [
                    {
                        "sequence": item.sequence,
                        "entity_type": item.entity_type,
                        "entity_id": item.entity_id,
                        "from_status": item.from_status,
                        "to_status": item.to_status,
                        "revision": item.revision,
                    }
                    for item in self.manager.events[-_STATE_EVENT_LIMIT:]
                ],
                "capabilities": {
                    "task_lifecycle": True,
                    "reasoning_preview": True,
                    "external_actions": False,
                    "tool_execution": False,
                    "remote_access": False,
                },
            }

    def create_session(self, title: object) -> dict[str, object]:
        clean_title = _required_text(title, "title", maximum=80)
        with self._lock:
            candidate = self._candidate()
            session_id = "ses-" + uuid.uuid4().hex[:24]
            record = candidate.create_session(
                session_id, metadata={"title": clean_title, "source": "local-console"}
            )
            self._publish(candidate)
            return _session_view(record)

    def create_task(self, session_id: object, title: object) -> dict[str, object]:
        clean_session = _required_text(session_id, "session_id", maximum=128)
        clean_title = _required_text(title, "title", maximum=512)
        with self._lock:
            candidate = self._candidate()
            task_id = "task-" + uuid.uuid4().hex[:24]
            idempotency_key = "local-" + uuid.uuid4().hex
            record = candidate.create_task(
                task_id, clean_session, idempotency_key, clean_title
            )
            self._publish(candidate)
            return _task_view(record)

    def set_session_status(
        self, session_id: object, status: object, expected_revision: object,
    ) -> dict[str, object]:
        clean_session_id = _required_text(session_id, "session_id", maximum=128)
        if isinstance(expected_revision, bool) or not isinstance(expected_revision, int):
            raise LocalConsoleError("expected_revision must be an integer")
        try:
            target = SessionStatus(status)
        except (TypeError, ValueError) as exc:
            raise LocalConsoleError("unsupported session status") from exc
        with self._lock:
            candidate = self._candidate()
            current = candidate.get_session(clean_session_id)
            updated = candidate.set_session_status(
                clean_session_id, target, expected_revision=expected_revision,
            )
            if updated.revision == current.revision:
                return _session_view(updated)
            self._publish(candidate)
            return _session_view(updated)

    def cancel_queued_task(self, task_id: object, expected_revision: object) -> dict[str, object]:
        clean_task_id = _required_text(task_id, "task_id", maximum=128)
        if isinstance(expected_revision, bool) or not isinstance(expected_revision, int):
            raise LocalConsoleError("expected_revision must be an integer")
        with self._lock:
            candidate = self._candidate()
            current = candidate.get_task(clean_task_id)
            if current.status is not TaskStatus.QUEUED:
                raise LocalConsoleError("only queued tasks can be cancelled from this console")
            updated = candidate.transition_task(
                clean_task_id, TaskStatus.CANCELLED,
                expected_revision=expected_revision,
            )
            self._publish(candidate)
            return _task_view(updated)

    def preview_reasoning(self, payload: object) -> dict[str, object]:
        if not isinstance(payload, dict):
            raise LocalConsoleError("reasoning preview must be a JSON object")
        expected = {
            "task_id", "goal_description", "initial_facts", "goal_facts", "actions"
        }
        if set(payload) != expected:
            raise LocalConsoleError("reasoning preview fields do not match the V0.1 contract")
        task_id = _required_text(payload["task_id"], "task_id", maximum=128)
        goal = _required_text(payload["goal_description"], "goal_description", maximum=1024)
        initial = _facts(payload["initial_facts"], "initial_facts")
        goals = _facts(payload["goal_facts"], "goal_facts")
        raw_actions = payload["actions"]
        if not isinstance(raw_actions, list) or len(raw_actions) > MAX_PREVIEW_ACTIONS:
            raise LocalConsoleError(
                f"actions must be a list with at most {MAX_PREVIEW_ACTIONS} items"
            )

        actions = []
        allowed_action_fields = {
            "action_id", "summary", "preconditions", "add_facts",
            "delete_facts", "rationale",
        }
        for index, raw in enumerate(raw_actions):
            if not isinstance(raw, dict) or not {
                "action_id", "summary", "preconditions", "add_facts"
            }.issubset(raw) or set(raw) - allowed_action_fields:
                raise LocalConsoleError(f"action {index + 1} fields are invalid")
            try:
                actions.append(PlanningAction(
                    action_id=raw["action_id"],
                    summary=raw["summary"],
                    preconditions=_facts(raw["preconditions"], "preconditions"),
                    add_facts=_facts(raw["add_facts"], "add_facts"),
                    delete_facts=_facts(raw.get("delete_facts", []), "delete_facts"),
                    rationale=raw.get("rationale", ""),
                ))
            except ReasoningError as exc:
                raise LocalConsoleError(str(exc)) from exc

        try:
            result = ReasoningEngine().reason(ReasoningProblem(
                task_id=task_id,
                goal_description=goal,
                initial_facts=initial,
                goal_facts=goals,
                actions=tuple(actions),
            ))
        except ReasoningError as exc:
            raise LocalConsoleError(str(exc)) from exc

        return {
            "task_id": result.task_id,
            "status": result.status.value,
            "expanded_states": result.expanded_states,
            "goal_facts": list(result.goal_facts),
            "predicted_final_facts": list(result.predicted_final_facts),
            "plan": [
                {
                    "index": step.index,
                    "action_id": step.action_id,
                    "summary": step.summary,
                    "rationale": step.rationale,
                    "required_facts": list(step.required_facts),
                    "added_facts": list(step.added_facts),
                    "deleted_facts": list(step.deleted_facts),
                    "facts_after_step": list(step.facts_after_step),
                }
                for step in result.plan
            ],
            "notice": "Это только символьный preview. Действия не исполнялись.",
        }


def create_http_server(
    service: LocalConsoleService,
    *,
    port: int = 8765,
) -> ThreadingHTTPServer:
    """Create a loopback-only HTTP server; tests may request port=0."""
    if not isinstance(service, LocalConsoleService):
        raise LocalConsoleError("service must be a LocalConsoleService")
    if isinstance(port, bool) or not isinstance(port, int) or not 0 <= port <= 65535:
        raise LocalConsoleError("port must be an integer in 0..65535")

    class Handler(BaseHTTPRequestHandler):
        server_version = "JARVISLocal/0.1"
        sys_version = ""

        def log_message(self, format: str, *args: object) -> None:
            # Local task titles and identifiers are deliberately not written to logs.
            return

        def _send(self, status: int, payload: object, content_type: str = "application/json; charset=utf-8") -> None:
            if content_type.startswith("application/json"):
                body = json.dumps(
                    payload, ensure_ascii=False, allow_nan=False,
                    separators=(",", ":"),
                ).encode("utf-8")
            else:
                body = payload if isinstance(payload, bytes) else str(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(body)

        def _error(self, status: int, message: str) -> None:
            self._send(status, {"error": message})

        def _check_local_origin(self) -> bool:
            expected_host = f"127.0.0.1:{self.server.server_port}"
            if self.headers.get("Host") != expected_host:
                self._error(403, "Доступ разрешён только через 127.0.0.1")
                return False
            origin = self.headers.get("Origin")
            if origin is not None and origin != f"http://{expected_host}":
                self._error(403, "Запросы с другого источника запрещены")
                return False
            return True

        def _require_auth(self) -> bool:
            values = self.headers.get_all("Authorization", [])
            if len(values) != 1 or not values[0].startswith("Bearer "):
                self._error(401, "Требуется токен доступа JARVIS")
                return False
            token = values[0][7:]
            if not service.authenticate(token):
                self._error(401, "Токен доступа недействителен")
                return False
            return True

        def _read_json(self, *, max_bytes: int = MAX_REQUEST_BYTES) -> dict[str, object] | None:
            if self.headers.get_content_type() != "application/json":
                self._error(415, "Ожидался Content-Type: application/json")
                return None
            raw_length = self.headers.get("Content-Length")
            try:
                length = int(raw_length) if raw_length is not None else -1
            except ValueError:
                length = -1
            if not 0 <= length <= max_bytes:
                self._error(413 if length > max_bytes else 400, "Размер запроса недопустим")
                return None
            try:
                value = json.loads(
                    self.rfile.read(length).decode("utf-8", errors="strict"),
                    object_pairs_hook=_unique_object,
                    parse_constant=lambda item: (_ for _ in ()).throw(
                        LocalConsoleError(f"Недопустимое JSON-значение: {item}")
                    ),
                )
            except (UnicodeDecodeError, json.JSONDecodeError, LocalConsoleError) as exc:
                self._error(400, str(exc) or "Некорректный JSON")
                return None
            if not isinstance(value, dict):
                self._error(400, "Корень JSON должен быть объектом")
                return None
            return value

        def do_GET(self) -> None:
            if not self._check_local_origin():
                return
            path = urlsplit(self.path).path
            if path == "/api/health":
                self._send(200, {"status": "ok", "storage": "jpvr", "api_schema": "jarvis.local-console", "api_version": 2, "workspace_api_version": 1, "platform": service.platform, "auth_required": True, "auth_scheme": "Bearer"})
                return
            if path == "/api/state":
                if not self._require_auth():
                    return
                self._send(200, service.state())
                return
            if path == "/api/workspace":
                if not self._require_auth():
                    return
                self._send(200, service.workspace_state())
                return
            if path == "/api/conversation":
                if not self._require_auth():
                    return
                self._send(200, service.conversation_state())
                return
            filename = _STATIC_FILES.get(path)
            if filename is None:
                self._error(404, "Страница не найдена")
                return
            target = _STATIC_DIR / filename
            try:
                content = target.read_bytes()
            except OSError:
                self._error(404, "Файл интерфейса не найден")
                return
            content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
            if filename == "manifest.webmanifest":
                content_type = "application/manifest+json"
            if filename.endswith(".svg"):
                content_type = "image/svg+xml"
            self._send(200, content, f"{content_type}; charset=utf-8")

        def do_POST(self) -> None:
            if not self._check_local_origin():
                return
            path = urlsplit(self.path).path
            if not self._require_auth():
                return
            payload = self._read_json(
                max_bytes=MAX_WORKSPACE_REQUEST_BYTES
                if path in {"/api/workspace", "/api/workspace/restore"}
                else MAX_REQUEST_BYTES
            )
            if payload is None:
                return
            try:
                if path == "/api/workspace/restore":
                    if set(payload) != {"expected_revision", "workspace"}:
                        raise WorkspaceError("ожидались поля expected_revision и workspace")
                    self._send(200, service.restore_workspace(
                        payload["workspace"], payload["expected_revision"]
                    ))
                    return
                if path == "/api/conversation":
                    if set(payload) != {"text", "expected_revision"}:
                        raise LocalConsoleError("ожидались поля text и expected_revision")
                    self._send(200, service.send_conversation_message(payload["text"], payload["expected_revision"]))
                    return
                if path == "/api/workspace":
                    if set(payload) != {"expected_revision", "workspace"}:
                        raise WorkspaceError("ожидались поля expected_revision и workspace")
                    self._send(200, service.save_workspace(
                        payload["workspace"], payload["expected_revision"]
                    ))
                    return
                if path == "/api/sessions":
                    if set(payload) != {"title"}:
                        raise LocalConsoleError("ожидалось поле title")
                    result = service.create_session(payload["title"])
                    self._send(201, {"session": result, "state": service.state()})
                    return
                if path == "/api/tasks":
                    if set(payload) != {"session_id", "title"}:
                        raise LocalConsoleError("ожидались поля session_id и title")
                    result = service.create_task(payload["session_id"], payload["title"])
                    self._send(201, {"task": result, "state": service.state()})
                    return
                if path.startswith("/api/sessions/") and path.endswith("/status"):
                    session_id = path[len("/api/sessions/"):-len("/status")].strip("/")
                    if not session_id or "/" in session_id or set(payload) != {"status", "expected_revision"}:
                        raise LocalConsoleError("неверный путь или поля status/expected_revision")
                    result = service.set_session_status(
                        session_id, payload["status"], payload["expected_revision"]
                    )
                    self._send(200, {"session": result, "state": service.state()})
                    return
                if path.startswith("/api/tasks/") and path.endswith("/cancel"):
                    task_id = path[len("/api/tasks/"):-len("/cancel")].strip("/")
                    if not task_id or "/" in task_id or set(payload) != {"expected_revision"}:
                        raise LocalConsoleError("неверный путь или expected_revision")
                    result = service.cancel_queued_task(task_id, payload["expected_revision"])
                    self._send(200, {"task": result, "state": service.state()})
                    return
                if path == "/api/reasoning/preview":
                    self._send(200, service.preview_reasoning(payload))
                    return
                self._error(404, "Маршрут API не найден")
            except WorkspaceConflict as exc:
                self._error(409, str(exc))
            except LocalInferenceError as exc:
                self._error(503, str(exc))
            except WorkspaceError as exc:
                self._error(400, str(exc))
            except (LocalConsoleError, TaskSessionError, TaskNotFound, TaskConflict) as exc:
                self._error(400, str(exc))
            except Exception:
                # Never return internal paths, checkpoint bytes, or tracebacks to the UI.
                self._error(503, "Локальная операция не выполнена; проверьте состояние хранилища")

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    server.allow_reuse_address = False
    return server


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="JARVIS local task console")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--model", type=Path, default=None, help="path to the optional local .litertlm model; no download is performed")
    parser.add_argument(
        "--data",
        type=Path,
        default=Path.home() / ".jarvis-ans100-alpha" / "local-console.jpvr",
    )
    parser.add_argument(
        "--workspace-file",
        type=Path,
        default=None,
        help="path for the shared desktop/Android workspace JPVR file",
    )
    parser.add_argument(
        "--platform",
        choices=("desktop", "android"),
        default="desktop",
        help="platform label written to the shared workspace snapshot",
    )
    parser.add_argument(
        "--auth-file",
        type=Path,
        default=None,
        help="path for the private SHA-256 access-token digest",
    )
    parser.add_argument(
        "--rotate-token",
        action="store_true",
        help="revoke the previous token and print a new one-time token",
    )
    args = parser.parse_args(argv)
    service = LocalConsoleService(
        args.data, args.auth_file, rotate_token=args.rotate_token,
        workspace_path=args.workspace_file, platform=args.platform,
        model_path=args.model,
    )
    server = create_http_server(service, port=args.port)
    print(f"JARVIS Local Console: http://127.0.0.1:{server.server_port}/")
    print(f"JPVR task/session data: {service.data_path}")
    print(f"JPVR shared workspace: {service.workspace_path}")
    print(f"Access-token digest: {service.auth_path}")
    if service.bootstrap_token:
        print("ANDROID / PWA ACCESS TOKEN (shown only once; save it in the client):")
        print(service.bootstrap_token)
    else:
        print("Access token already provisioned. Use --rotate-token to issue a replacement if it was lost.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        service.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
