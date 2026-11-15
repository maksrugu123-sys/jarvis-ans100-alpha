"""Loopback-only local JARVIS task console.

This is a thin UI for bounded Python reference APIs. It has bearer-token authentication
but no tool executor, external credentials, shell access, or side-effecting capabilities.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from io import BytesIO
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import ipaddress
import ssl
import socket
import mimetypes
import os
import re
from pathlib import Path
import threading
import zipfile
import time
import uuid
from urllib.parse import urlsplit

from .auth import LocalConsoleAuth, LocalConsoleAuthError
from .local_inference import LiteRtLmLocalEngine, LocalInferenceError
from .local_fallback import LocalFallbackResponseEngine
from .dialog_neurons import DialogNeuronService
from tools.neuron_runtime.dialog_neuron_prototype import DialogNeuronError
from tools.neuron_runtime.dialog_rstdp import DialogRstdpError, DialogRstdpLearningService
from .jarvis_core import JarvisCore
from .visible_browser import (
    VisibleBrowserResearchError,
    VisibleBrowserUnavailable,
    VisibleBrowserResearchService,
    validate_external_url,
)
from .workspace_files import WorkspaceFolderError, create_workspace_folder
from tools.neuron_runtime.conversation_signal import ConversationSignalRuntime
from tools.neuron_runtime.hybrid_ans100 import (
    EXPECTED_CONNECTION_COUNT as DESKTOP_ANS100_CONNECTION_COUNT,
    NEURON_COUNT as DESKTOP_ANS100_NEURON_COUNT,
    POPULATIONS as DESKTOP_ANS100_POPULATIONS,
    TOPOLOGY_ID as DESKTOP_ANS100_TOPOLOGY_ID,
    TOPOLOGY_VERSION as DESKTOP_ANS100_TOPOLOGY_VERSION,
)

from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.reasoning_engine import (
    PlanningAction,
    ReasoningEngine,
    ReasoningError,
    ReasoningProblem,
)
from tools.reasoning_engine.engine import ReasoningEngineError as StructuredReasoningError
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
from tools.neuron_runtime.task_loop import (
    ActionBinding,
    ActionExecutionResult,
    ExecutionOutcome,
    JARVISTaskLoop,
    TaskLoopError,
)
from tools.neuron_runtime.permission_safety_gate import (
    CapabilityRule,
    EffectClass,
    RequestedBudget,
    SafetyPolicy,
)
from tools.jarvis_app.safe_actions import SafeActionExecutor
from tools.jarvis_app.peer_memory_sync import PeerMemorySyncConflict, PeerMemorySyncError, PeerMemorySyncStore
from tools.neuron_runtime.task_session_store import (
    JPVRTaskSessionCheckpointError,
    JPVRTaskSessionCheckpointNotFound,
    JPVRTaskSessionCheckpointStore,
)


MANAGER_ID = "android-local-console"
MAX_REQUEST_BYTES = 64 * 1024
MAX_WORKSPACE_REQUEST_BYTES = 16 * 1024 * 1024
MAX_PEER_SYNC_BYTES = 4 * 1024 * 1024
MAX_PREVIEW_ACTIONS = 64
_STATE_EVENT_LIMIT = 100
_STATIC_DIR = Path(__file__).with_name("static")
_BROWSER_EXTENSION_DIR = Path(__file__).with_name("browser_extension")
_BROWSER_EXTENSION_API_PATHS = frozenset({"/api/extension/state", "/api/extension/ask"})
_STATIC_FILES = {
    "/": "index.html",
    "/index.html": "index.html",
    "/app.js": "app.js",
    "/styles.css": "styles.css",
    "/manifest.webmanifest": "manifest.webmanifest",
    "/sw.js": "sw.js",
    "/icon.svg": "icon.svg",
}


def _is_browser_extension_origin(origin: str | None) -> bool:
    if not isinstance(origin, str):
        return False
    try:
        parsed = urlsplit(origin)
    except ValueError:
        return False
    return (
        parsed.scheme == "chrome-extension"
        and re.fullmatch(r"[a-p]{32}", parsed.netloc) is not None
        and not parsed.path
        and not parsed.query
        and not parsed.fragment
    )


def _build_browser_extension_archive() -> bytes:
    if not _BROWSER_EXTENSION_DIR.is_dir():
        raise FileNotFoundError("browser extension source directory is missing")
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        files = sorted(
            path for path in _BROWSER_EXTENSION_DIR.iterdir()
            if path.is_file() and not path.is_symlink()
            and path.suffix.lower() in {".json", ".html", ".css", ".js", ".md"}
        )
        if not files or not any(path.name == "manifest.json" for path in files):
            raise FileNotFoundError("browser extension manifest is missing")
        for path in files:
            archive.write(path, f"jarvis-browser-extension/{path.name}")
    return buffer.getvalue()


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
        ans100_state_path: str | os.PathLike[str] | None = None,
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
        self.workspace_files_root = self.data_path.parent / "workspace-files"
        self.ans100_state_path = (
            Path(ans100_state_path).expanduser() if ans100_state_path is not None
            else self.data_path.with_name("ans100-conversation.jpvr")
        )
        if platform not in ("desktop", "android"):
            raise LocalConsoleError("platform must be desktop or android")
        self.platform = platform
        peer_base = Path(data_path) if data_path is not None else (Path.home() / ".jarvis-ans100-alpha" / "local-console.jpvr")
        self.peer_memory_store = PeerMemorySyncStore(peer_base.with_name("peer-memory-sync.json"))
        self.local_inference = (
            LiteRtLmLocalEngine(model_path)
            if model_path is not None else LocalFallbackResponseEngine()
        )
        self.dialog_neurons = DialogNeuronService(
            self.data_path.parent / "dialog-neurons-v1.json"
        )
        self._conversation_lock = threading.Lock()
        paths = (
            self.data_path, self.auth_path, self.workspace_path, self.ans100_state_path,
        )
        try:
            resolved = [path.resolve(strict=False) for path in paths]
            for index, path in enumerate(paths):
                for other_index in range(index):
                    same = resolved[index] == resolved[other_index]
                    if not same and path.exists() and paths[other_index].exists():
                        same = os.path.samefile(path, paths[other_index])
                    if same:
                        raise LocalConsoleError("JPVR data, workspace, authentication, and ANS-100 state paths must be separate files")
        except (OSError, RuntimeError) as exc:
            raise LocalConsoleError("cannot validate separate JARVIS storage paths") from exc
        self._lock = threading.RLock()
        self.data_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.workspace_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.ans100_state_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.jarvis_core = JarvisCore(
            self.local_inference,
            ConversationSignalRuntime(self.ans100_state_path),
            dialog_neuron_service=self.dialog_neurons,
        )
        self.visible_browser = VisibleBrowserResearchService(
            self.data_path.parent / "research-browser-profile"
        )

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
        self.dialog_rstdp = DialogRstdpLearningService(
            self.data_path.parent / "dialog-rstdp-v1"
        )

    def authenticate(self, token: str | None) -> bool:
        return self.auth.verify(token)

    def close(self) -> None:
        self.visible_browser.close()
        self.jarvis_core.close()
        if self.local_inference is not None:
            self.local_inference.close()
        self.dialog_rstdp.close()
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

    def create_workspace_folder(self, name: object) -> dict[str, object]:
        """Create one new folder inside JARVIS's dedicated workspace-files root."""
        if self.platform != "desktop":
            raise LocalConsoleError("Создание папок доступно только в Windows/desktop версии.")
        try:
            return create_workspace_folder(self.workspace_files_root, name)
        except WorkspaceFolderError as exc:
            raise LocalConsoleError(str(exc)) from exc

    def conversation_state(self) -> dict[str, object]:
        with self._lock:
            payload = json.loads(encode_workspace(self.workspace_snapshot).decode("utf-8"))
            record = next((item for item in payload["records"]["conversations"] if item["id"] == "jarvis-local-chat"), None)
            memories = payload["records"].get("memories", [])
            core_status = self.jarvis_core.status(memories)
            model_configured = bool(getattr(self.local_inference, "language_model_configured", True))
            runtime_ready = bool(self.local_inference.runtime_available)
            if model_configured and not runtime_ready:
                label = "Модель указана, но пакет litert-lm-api==0.17.1 не установлен"
                chat_mode = "unavailable"
            elif model_configured:
                label = self.local_inference.backend_label
                chat_mode = "local_model"
            else:
                label = self.local_inference.backend_label
                chat_mode = "fallback"
            return {
                "revision": self.workspace_revision,
                "conversation": record,
                "model_configured": model_configured,
                "runtime_available": runtime_ready,
                "chat_available": runtime_ready,
                "chat_mode": chat_mode,
                "backend_label": label,
                "dialogue_neurons": self.dialog_neurons.status(),
                "core_status": core_status,
            }

    def send_conversation_message(
        self,
        text: object,
        expected_revision: object,
        reasoning_request: object | None = None,
        external_context: object | None = None,
    ) -> dict[str, object]:
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
            try:
                answer, turn_trace = self.jarvis_core.respond(
                    clean, history, memories,
                    reasoning_request=reasoning_request,
                    external_context=external_context,
                )
            except StructuredReasoningError as exc:
                raise LocalConsoleError(str(exc)) from exc
            source_record = None
            stored_answer = answer
            if external_context is not None:
                source_record = {
                    "title": str(external_context.get("title", ""))[:300],
                    "url": str(external_context.get("url", ""))[:2_048],
                    "retrieved_at": str(external_context.get("retrieved_at", ""))[:80],
                }
                citation = (
                    "\n\nИсточник страницы: " + source_record["title"]
                    + "\n" + source_record["url"]
                )
                stored_answer = answer[:max(0, 4_000 - len(citation))] + citation
            now = datetime.now(timezone.utc).isoformat(timespec="seconds")
            messages = list(existing["messages"] if existing is not None else [])
            if len(messages) > 49_998:
                messages = messages[-49_998:]
            messages.extend([
                {"id": "message-" + uuid.uuid4().hex, "role": "user", "created_at": now, "content": clean},
                {"id": "message-" + uuid.uuid4().hex, "role": "assistant", "created_at": now, "content": stored_answer},
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
            model_configured = bool(getattr(self.local_inference, "language_model_configured", True))
            runtime_ready = bool(self.local_inference.runtime_available)
            return {
                "revision": saved["revision"],
                "conversation": result_record,
                "model_configured": model_configured,
                "runtime_available": runtime_ready,
                "chat_available": runtime_ready,
                "chat_mode": "local_model" if model_configured else "fallback",
                "backend_label": self.local_inference.backend_label,
                "dialogue_neurons": self.dialog_neurons.status(),
                "core_status": self.jarvis_core.status(memories),
                "turn_trace": turn_trace,
                "browser_source": source_record,
            }


    def plan_reasoning_task(self, payload: object) -> dict[str, object]:
        try:
            return self.jarvis_core.plan_structured_task(payload)
        except StructuredReasoningError as exc:
            raise LocalConsoleError(str(exc)) from exc

    def verify_reasoning_task(
        self, reasoning_request: object, plan_id: object,
        completed_step_ids: object, observations: object,
    ) -> dict[str, object]:
        try:
            return self.jarvis_core.verify_structured_task(
                reasoning_request, plan_id, completed_step_ids, observations,
            )
        except StructuredReasoningError as exc:
            raise LocalConsoleError(str(exc)) from exc

    def analyze_conversation_signal(self, prompt: object) -> dict[str, object]:
        clean = _required_text(prompt, "prompt", maximum=4_000)
        try:
            return self.jarvis_core.analyze_signal(clean)
        except (TypeError, ValueError, RuntimeError) as exc:
            raise LocalConsoleError("ANS-100 signal preprocessing is unavailable") from exc

    def _require_desktop_browser(self) -> None:
        if self.platform != "desktop":
            raise VisibleBrowserUnavailable("Видимый браузер доступен только в режиме desktop на ПК.")

    def browser_status(self) -> dict[str, object]:
        if self.platform != "desktop":
            return {
                "supported": False,
                "browser_open": False,
                "paused": False,
                "page": None,
                "scope": "desktop_only_visible_browser",
            }
        return {"supported": True, **self.visible_browser.status()}

    def ans100_status(self) -> dict[str, object]:
        return {
            "platform": self.platform,
            "desktop_topology": {
                "id": DESKTOP_ANS100_TOPOLOGY_ID,
                "version": DESKTOP_ANS100_TOPOLOGY_VERSION,
                "neuron_count": DESKTOP_ANS100_NEURON_COUNT,
                "declared_connection_count": DESKTOP_ANS100_CONNECTION_COUNT,
                "populations": [
                    {
                        "id": population.id,
                        "start": population.start,
                        "end": population.end,
                        "size": population.size,
                    }
                    for population in DESKTOP_ANS100_POPULATIONS
                ],
            },
            "active_in_chat": False,
            "runtime_state": "source_topology_only",
            "notice": (
                "The 10,000-neuron desktop topology is defined in source but is not "
                "instantiated or connected to the live conversation path. The current "
                "conversation signal preprocessor remains separate."
            ),
        }

    def browser_extension_state(self) -> dict[str, object]:
        if self.platform != "desktop":
            return {
                "supported": False,
                "chat_available": False,
                "revision": self.workspace_revision,
                "chat_mode": "unavailable",
            }
        state = self.conversation_state()
        return {
            "supported": True,
            "revision": state["revision"],
            "chat_available": state["chat_available"],
            "chat_mode": state["chat_mode"],
            "backend_label": state["backend_label"],
        }

    def ask_about_extension_page(
        self,
        question: object,
        expected_revision: object,
        title: object,
        url: object,
        page_text: object,
    ) -> dict[str, object]:
        self._require_desktop_browser()
        clean_question = _required_text(question, "question", maximum=900)
        clean_title = _required_text(title, "title", maximum=300)
        if not isinstance(page_text, str):
            raise LocalConsoleError("page text must be text")
        clean_text = page_text.strip()
        if not clean_text or len(clean_text) > 12_000 or "\x00" in clean_text:
            raise LocalConsoleError("page text must contain 1..12000 characters")
        try:
            clean_url = validate_external_url(url)
        except VisibleBrowserResearchError as exc:
            raise LocalConsoleError(str(exc)) from exc
        parsed = urlsplit(clean_url)
        host = parsed.hostname
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        try:
            addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except OSError as exc:
            raise LocalConsoleError("cannot verify the page host; no page text was accepted") from exc
        if not addresses or any(
            not ipaddress.ip_address(item[4][0].split("%", 1)[0]).is_global
            for item in addresses
        ):
            raise LocalConsoleError("local, private or reserved network pages cannot be sent to JARVIS")
        retrieved_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
        external_context = {
            "title": clean_title,
            "url": clean_url,
            "retrieved_at": retrieved_at,
            "text": clean_text,
        }
        return self.send_conversation_message(
            clean_question,
            expected_revision,
            external_context=external_context,
        )

    def browser_search(self, query: object) -> dict[str, object]:
        self._require_desktop_browser()
        return {"supported": True, **self.visible_browser.search(query)}

    def browser_open(self, url: object) -> dict[str, object]:
        self._require_desktop_browser()
        return {"supported": True, **self.visible_browser.open(url)}

    def browser_read(self) -> dict[str, object]:
        self._require_desktop_browser()
        return {"supported": True, **self.visible_browser.read()}

    def browser_close_tab(self) -> dict[str, object]:
        self._require_desktop_browser()
        return {"supported": True, **self.visible_browser.close_tab()}

    def browser_control(self, paused: object) -> dict[str, object]:
        self._require_desktop_browser()
        return {"supported": True, **self.visible_browser.set_paused(paused)}

    def browser_stop(self) -> dict[str, object]:
        self._require_desktop_browser()
        return {"supported": True, **self.visible_browser.stop()}

    def ask_about_browser_page(
        self,
        question: object,
        expected_revision: object,
    ) -> dict[str, object]:
        self._require_desktop_browser()
        page = self.visible_browser.read()
        external_context = {
            "title": page["title"],
            "url": page["url"],
            "retrieved_at": page["retrieved_at"],
            "text": page["text"],
        }
        return self.send_conversation_message(
            question,
            expected_revision,
            external_context=external_context,
        )

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
                    "structured_reasoning": True,
                    "reasoning_outcome_review": True,
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



    def peer_memory_snapshot(self) -> dict[str, object]:
        return self.peer_memory_store.snapshot()

    def merge_peer_memories(
        self,
        records: object,
        expected_revision: object,
        deleted_ids: object = (),
    ) -> dict[str, object]:
        return self.peer_memory_store.merge(records, expected_revision, deleted_ids)

    def read_only_memory_workspace(self) -> dict[str, object]:
        workspace = self.workspace_state()["workspace"]
        records = workspace.get("records", {}) if isinstance(workspace, dict) else {}
        base = list(records.get("memories", [])) if isinstance(records, dict) and isinstance(records.get("memories", []), list) else []
        peer = self.peer_memory_store.snapshot()["memories"]
        known = {
            item.get("id", item.get("memory_id"))
            for item in base
            if isinstance(item, dict)
        }
        base.extend(item for item in peer if item["id"] not in known)
        return {"records": {"memories": base}}

    def run_read_only_task(
        self,
        task_id: object,
        expected_revision: object,
        steps: object,
    ) -> dict[str, object]:
        clean_task_id = _required_text(task_id, "task_id", maximum=128)
        if isinstance(expected_revision, bool) or not isinstance(expected_revision, int):
            raise LocalConsoleError("expected_revision must be an integer")
        if not isinstance(steps, list) or not 1 <= len(steps) <= 4:
            raise LocalConsoleError("steps must contain between one and four read-only actions")

        allowed = {"memory.search", "browser.search", "browser.open", "browser.read"}
        normalized = []
        for index, step in enumerate(steps):
            if not isinstance(step, dict) or set(step) != {"capability", "input"}:
                raise LocalConsoleError(f"step {index + 1} must contain exactly capability and input")
            capability = _required_text(step["capability"], "capability", maximum=128)
            value = step["input"]
            if capability not in allowed:
                raise LocalConsoleError("only memory.search, browser.search, browser.open and browser.read are available")
            if not isinstance(value, str) or len(value) > 2048 or "\x00" in value:
                raise LocalConsoleError("step input must be bounded text")
            if capability == "browser.read":
                if value.strip():
                    raise LocalConsoleError("browser.read input must be empty")
            elif not value.strip():
                raise LocalConsoleError(f"{capability} requires non-empty input")
            elif capability in {"memory.search", "browser.search"} and len(value) > 240:
                raise LocalConsoleError("search query must not exceed 240 characters")
            elif capability == "browser.open" and len(value) > 2048:
                raise LocalConsoleError("browser URL exceeds its limit")
            if capability.startswith("browser.") and self.platform != "desktop":
                raise LocalConsoleError("visible browser actions are available only in the desktop runtime")
            normalized.append((capability, value.strip()))

        with self._lock:
            task = self.manager.get_task(clean_task_id)
            if task.revision != expected_revision:
                raise LocalConsoleError("task revision conflict; refresh state before running")
            if task.status not in (TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING):
                raise LocalConsoleError("only queued/running/waiting tasks can enter the read-only task loop")

            actions = []
            bindings = {}
            rules_by_capability = {}
            available_facts = ["request_received"]
            for index, (capability, value) in enumerate(normalized, start=1):
                action_id = f"safe_step_{index}"
                next_fact = f"safe_step_{index}_completed"
                summary = {
                    "memory.search": "Search explicitly approved workspace memories",
                    "browser.search": "Search the web in the visible desktop browser",
                    "browser.open": "Open a selected public page in the visible desktop browser",
                    "browser.read": "Read the current page in the visible desktop browser",
                }[capability]
                actions.append(PlanningAction(
                    action_id=action_id,
                    summary=summary,
                    rationale=value,
                    preconditions=tuple(available_facts),
                    add_facts=(next_fact,),
                    delete_facts=(),
                ))
                bindings[action_id] = ActionBinding(
                    capability=capability,
                    scope=capability,
                    effect=EffectClass.READ_ONLY,
                    budget=RequestedBudget(
                        compute_units=2,
                        memory_bytes=16 * 1024 * 1024,
                        timeout_ms=20_000,
                        output_bytes=12_000,
                        retry_index=0,
                    ),
                )
                rules_by_capability[capability] = CapabilityRule(
                    capability=capability,
                    allowed_scopes=(capability,),
                    allowed_effects=(EffectClass.READ_ONLY,),
                    requires_approval=False,
                    max_compute_units=4,
                    max_memory_bytes=32 * 1024 * 1024,
                    max_timeout_ms=30_000,
                    max_output_bytes=16_384,
                    max_retries=0,
                )
                available_facts.append(next_fact)

            problem = ReasoningProblem(
                task_id=clean_task_id,
                goal_description=f"User-requested bounded read-only task: {task.title}"[:1024],
                initial_facts=("request_received",),
                goal_facts=(available_facts[-1],),
                actions=tuple(actions),
            )
            executor = SafeActionExecutor(
                platform=self.platform,
                browser=self.visible_browser,
                workspace_provider=self.read_only_memory_workspace,
            )
            executor.clear_task(clean_task_id)
            loop = JARVISTaskLoop()
            try:
                result = loop.run(
                    self.manager,
                    clean_task_id,
                    problem,
                    bindings,
                    SafetyPolicy(
                        policy_id="local_read_only_v1",
                        version=1,
                        rules=tuple(rules_by_capability.values()),
                        enabled=True,
                    ),
                    executor,
                    now_ns=time.time_ns,
                    checkpoint_store=self.store,
                    expected_checkpoint_version=self.checkpoint_version,
                )
                loaded = self.store.load(self.manager.manager_id)
                self.checkpoint_version = loaded.descriptor.record_version
            except (TaskLoopError, JPVRTaskSessionCheckpointError, OSError, ValueError) as exc:
                try:
                    loaded = self.store.load(self.manager.manager_id)
                    self.manager = loaded.manager
                    self.checkpoint_version = loaded.descriptor.record_version
                except Exception:
                    pass
                raise LocalConsoleError(f"read-only task loop did not complete: {exc}") from exc

            return {
                "task": _task_view(self.manager.get_task(clean_task_id)),
                "loop": {
                    "status": result.status.value,
                    "plan_id": result.plan_id,
                    "reason_code": result.reason_code,
                    "executed_step_ids": list(result.executed_step_ids),
                    "pending_step_id": result.pending_step_id,
                    "observed_facts": list(result.observed_facts),
                },
                "outputs": executor.outputs_for(clean_task_id),
                "state": self.state(),
                "notice": "Executed only allow-listed read-only actions. No shell or external write actions are available.",
            }

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

        def _send(
            self,
            status: int,
            payload: object,
            content_type: str = "application/json; charset=utf-8",
            *,
            extra_headers: dict[str, str] | None = None,
        ) -> None:
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
            request_path = urlsplit(self.path).path
            origin = self.headers.get("Origin")
            if request_path in _BROWSER_EXTENSION_API_PATHS and _is_browser_extension_origin(origin):
                self.send_header("Access-Control-Allow-Origin", origin)
                self.send_header("Vary", "Origin")
            for header_name, header_value in (extra_headers or {}).items():
                self.send_header(header_name, header_value)
            self.end_headers()
            self.wfile.write(body)

        def _error(self, status: int, message: str) -> None:
            self._send(status, {"error": message})

        def _check_local_origin(self) -> bool:
            expected_host = f"{self.server.bound_host}:{self.server.server_port}"
            if self.headers.get("Host") != expected_host:
                self._error(403, "Неверный Host для локального JARVIS endpoint")
                return False
            origin = self.headers.get("Origin")
            expected_origin = ("https" if self.server.is_tls else "http") + "://" + expected_host
            path = urlsplit(self.path).path
            extension_request = (
                path in _BROWSER_EXTENSION_API_PATHS
                and _is_browser_extension_origin(origin)
            )
            if origin is not None and origin != expected_origin and not extension_request:
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

        def do_OPTIONS(self) -> None:
            path = urlsplit(self.path).path
            expected_host = f"{self.server.bound_host}:{self.server.server_port}"
            origin = self.headers.get("Origin")
            request_method = self.headers.get("Access-Control-Request-Method", "").upper()
            requested_headers = {
                item.strip().lower()
                for item in self.headers.get("Access-Control-Request-Headers", "").split(",")
                if item.strip()
            }
            if (
                self.headers.get("Host") != expected_host
                or path not in _BROWSER_EXTENSION_API_PATHS
                or not _is_browser_extension_origin(origin)
                or request_method not in ({"GET"} if path.endswith("/state") else {"POST"})
                or not requested_headers.issubset({"authorization", "content-type"})
            ):
                self._error(403, "Предварительный запрос расширения отклонён")
                return
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Max-Age", "300")
            self.send_header("Content-Length", "0")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()

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
            if path == "/api/peer-sync/memories":
                if not self._require_auth():
                    return
                self._send(200, service.peer_memory_snapshot())
                return
            if path == "/api/conversation":
                if not self._require_auth():
                    return
                self._send(200, service.conversation_state())
                return
            if path == "/api/dialog-neurons":
                if not self._require_auth():
                    return
                self._send(200, service.dialog_neurons.status())
                return
            if path == "/api/dialog-rstdp":
                if not self._require_auth():
                    return
                self._send(200, service.dialog_rstdp.status())
                return
            if path == "/api/ans100":
                if not self._require_auth():
                    return
                self._send(200, service.ans100_status())
                return
            if path == "/api/browser":
                if not self._require_auth():
                    return
                self._send(200, service.browser_status())
                return
            if path == "/api/extension/state":
                if not self._require_auth():
                    return
                self._send(200, service.browser_extension_state())
                return
            if path == "/downloads/jarvis-browser-extension.zip":
                try:
                    archive = _build_browser_extension_archive()
                except (OSError, zipfile.BadZipFile):
                    self._error(503, "Архив расширения временно недоступен")
                    return
                self._send(
                    200,
                    archive,
                    "application/zip",
                    extra_headers={"Content-Disposition": 'attachment; filename="jarvis-browser-extension.zip"'},
                )
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
                else MAX_PEER_SYNC_BYTES
                if path == "/api/peer-sync/memories"
                else MAX_REQUEST_BYTES
            )
            if payload is None:
                return
            try:
                if path == "/api/extension/ask":
                    expected = {"question", "expected_revision", "title", "url", "text"}
                    if set(payload) != expected:
                        raise LocalConsoleError("ожидались поля question, expected_revision, title, url и text")
                    self._send(200, service.ask_about_extension_page(
                        payload["question"],
                        payload["expected_revision"],
                        payload["title"],
                        payload["url"],
                        payload["text"],
                    ))
                    return
                if path == "/api/browser/search":
                    if set(payload) != {"query"}:
                        raise LocalConsoleError("ожидалось поле query")
                    self._send(200, service.browser_search(payload["query"]))
                    return
                if path == "/api/browser/open":
                    if set(payload) != {"url"}:
                        raise LocalConsoleError("ожидалось поле url")
                    self._send(200, service.browser_open(payload["url"]))
                    return
                if path == "/api/browser/read":
                    if payload:
                        raise LocalConsoleError("чтение страницы не принимает параметры")
                    self._send(200, service.browser_read())
                    return
                if path == "/api/browser/close-tab":
                    if payload:
                        raise LocalConsoleError("закрытие вкладки не принимает параметры")
                    self._send(200, service.browser_close_tab())
                    return
                if path == "/api/folders":
                    if set(payload) != {"name"}:
                        raise LocalConsoleError("ожидалось поле name")
                    self._send(201, service.create_workspace_folder(payload["name"]))
                    return
                if path == "/api/browser/control":
                    if set(payload) != {"paused"}:
                        raise LocalConsoleError("ожидалось поле paused")
                    self._send(200, service.browser_control(payload["paused"]))
                    return
                if path == "/api/browser/stop":
                    if payload:
                        raise LocalConsoleError("остановка браузера не принимает параметры")
                    self._send(200, service.browser_stop())
                    return
                if path == "/api/browser/ask":
                    if set(payload) != {"question", "expected_revision"}:
                        raise LocalConsoleError("ожидались поля question и expected_revision")
                    self._send(200, service.ask_about_browser_page(
                        payload["question"], payload["expected_revision"]
                    ))
                    return

                if path == "/api/peer-sync/memories":
                    allowed = {"expected_revision", "memories"}
                    with_deletes = allowed | {"deleted_ids"}
                    if set(payload) not in (allowed, with_deletes):
                        raise LocalConsoleError("peer sync expects expected_revision, memories and optional deleted_ids")
                    self._send(200, service.merge_peer_memories(
                        payload["memories"],
                        payload["expected_revision"],
                        payload.get("deleted_ids", []),
                    ))
                    return
                if path == "/api/workspace/restore":
                    if set(payload) != {"expected_revision", "workspace"}:
                        raise WorkspaceError("ожидались поля expected_revision и workspace")
                    self._send(200, service.restore_workspace(
                        payload["workspace"], payload["expected_revision"]
                    ))
                    return
                if path == "/api/dialog-neurons/learn":
                    if set(payload) != {"text", "expected_label"}:
                        raise LocalConsoleError("ожидались поля text и expected_label")
                    self._send(200, service.dialog_neurons.learn(
                        payload["text"], payload["expected_label"]
                    ))
                    return
                if path == "/api/dialog-rstdp/learn":
                    required = {"text", "expected_label"}
                    allowed = required | {"feedback_id"}
                    if not required.issubset(payload) or set(payload) - allowed:
                        raise LocalConsoleError("ожидались поля text, expected_label и необязательное feedback_id")
                    self._send(200, service.dialog_rstdp.learn(
                        payload["text"], payload["expected_label"], payload.get("feedback_id")
                    ))
                    return
                if path == "/api/conversation":
                    allowed = {"text", "expected_revision", "reasoning_request"}
                    if not {"text", "expected_revision"}.issubset(payload) or set(payload) - allowed:
                        raise LocalConsoleError("ожидались поля text, expected_revision и необязательное reasoning_request")
                    self._send(200, service.send_conversation_message(
                        payload["text"],
                        payload["expected_revision"],
                        payload.get("reasoning_request"),
                    ))
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

                if path.startswith("/api/tasks/") and path.endswith("/run"):
                    task_id = path[len("/api/tasks/"):-len("/run")].strip("/")
                    if not task_id or "/" in task_id or set(payload) != {"expected_revision", "steps"}:
                        raise LocalConsoleError("invalid task path or run fields")
                    self._send(200, service.run_read_only_task(
                        task_id, payload["expected_revision"], payload["steps"]
                    ))
                    return
                if path.startswith("/api/tasks/") and path.endswith("/cancel"):
                    task_id = path[len("/api/tasks/"):-len("/cancel")].strip("/")
                    if not task_id or "/" in task_id or set(payload) != {"expected_revision"}:
                        raise LocalConsoleError("неверный путь или expected_revision")
                    result = service.cancel_queued_task(task_id, payload["expected_revision"])
                    self._send(200, {"task": result, "state": service.state()})
                    return
                if path == "/api/ans100/conversation-signal":
                    if set(payload) != {"prompt"}:
                        raise LocalConsoleError("ожидалось поле prompt")
                    self._send(200, service.analyze_conversation_signal(payload["prompt"]))
                    return
                if path == "/api/reasoning/preview":
                    self._send(200, service.preview_reasoning(payload))
                    return
                if path == "/api/reasoning/plan":
                    if set(payload) != {"reasoning_request"}:
                        raise LocalConsoleError("ожидалось поле reasoning_request")
                    self._send(200, service.plan_reasoning_task(payload["reasoning_request"]))
                    return
                if path == "/api/reasoning/verify":
                    expected = {
                        "reasoning_request", "plan_id", "completed_step_ids", "observations",
                    }
                    if set(payload) != expected:
                        raise LocalConsoleError("поля reasoning/verify не соответствуют контракту")
                    self._send(200, service.verify_reasoning_task(
                        payload["reasoning_request"],
                        payload["plan_id"],
                        payload["completed_step_ids"],
                        payload["observations"],
                    ))
                    return
                self._error(404, "Маршрут API не найден")
            except VisibleBrowserUnavailable as exc:
                self._error(503, str(exc))
            except VisibleBrowserResearchError as exc:
                self._error(400, str(exc))
            except (WorkspaceConflict, PeerMemorySyncConflict) as exc:
                self._error(409, str(exc))
            except LocalInferenceError as exc:
                self._error(503, str(exc))
            except (WorkspaceError, PeerMemorySyncError) as exc:
                self._error(400, str(exc))
            except (LocalConsoleError, DialogNeuronError, DialogRstdpError, TaskSessionError, TaskNotFound, TaskConflict) as exc:
                self._error(400, str(exc))
            except Exception:
                # Never return internal paths, checkpoint bytes, or tracebacks to the UI.
                self._error(503, "Локальная операция не выполнена; проверьте состояние хранилища")

    try:
        bind_address = ipaddress.ip_address(host)
    except ValueError as exc:
        raise ValueError("listen host must be an explicit IPv4 address") from exc
    if bind_address.version != 4 or bind_address.is_unspecified or bind_address.is_multicast:
        raise ValueError("listen host must be a specific IPv4 address")
    if not bind_address.is_loopback and (not bind_address.is_private or tls_context is None):
        raise ValueError("non-loopback binding requires a private IPv4 address and TLS")
    server = ThreadingHTTPServer((host, port), Handler)
    server.bound_host = host
    server.is_tls = tls_context is not None
    if tls_context is not None:
        server.socket = tls_context.wrap_socket(server.socket, server_side=True)
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
