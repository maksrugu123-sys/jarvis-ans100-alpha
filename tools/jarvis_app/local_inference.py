"""Optional local LiteRT-LM backend for the desktop/PWA console."""
from __future__ import annotations

import importlib.util
from importlib.metadata import PackageNotFoundError, version as package_version
from pathlib import Path
import threading
from typing import Any, Callable

MODEL_FILE_NAME = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
MIB = 1024 * 1024
MIN_MODEL_BYTES = 300 * MIB
MAX_MODEL_BYTES = 400 * MIB
RUNTIME_VERSION = "0.17.1"
MAX_CONTEXT_TOKENS = 1280
MAX_OUTPUT_TOKENS = 96
MAX_PROMPT_CHARS = 900
MAX_HISTORY_MESSAGES = 4
MAX_HISTORY_MESSAGE_CHARS = 320

SYSTEM_MESSAGE = (
    "Ты — локальный помощник JARVIS. Отвечай по-русски, кратко и прямо. "
    "Если не знаешь ответ или не уверен — так и скажи. Не выдумывай факты "
    "и не утверждай, что выполнил действия вне чата. У тебя нет доступа к "
    "сети, файлам, приложениям или инструментам. Не проси пароли и секреты."
)


class LocalInferenceError(RuntimeError):
    """The local model is unavailable or generation failed."""


class LiteRtLmLocalEngine:
    """Lazy, serialized Python API wrapper for one user-supplied model file."""

    def __init__(
        self,
        model_path: str | Path,
        *,
        runtime_loader: Callable[[], Any] | None = None,
    ) -> None:
        path = Path(model_path).expanduser()
        if path.name != MODEL_FILE_NAME or not path.is_file():
            raise LocalInferenceError("Нужен файл " + MODEL_FILE_NAME + ".")
        try:
            size = path.stat().st_size
        except OSError as exc:
            raise LocalInferenceError("Не удалось прочитать файл модели.") from exc
        if not MIN_MODEL_BYTES <= size <= MAX_MODEL_BYTES:
            raise LocalInferenceError("Размер модели должен быть примерно 300–400 МиБ.")
        self.model_path = path.resolve(strict=True)
        self._runtime_loader = runtime_loader
        self._runtime: Any | None = None
        self._engine: Any | None = None
        self._lock = threading.RLock()

    @property
    def runtime_available(self) -> bool:
        if self._runtime_loader is not None:
            return True
        try:
            return (
                importlib.util.find_spec("litert_lm") is not None
                and package_version("litert-lm-api") == RUNTIME_VERSION
            )
        except (ImportError, ModuleNotFoundError, PackageNotFoundError, ValueError):
            return False

    @property
    def backend_label(self) -> str:
        return "LiteRT-LM · Qwen3-0.6B INT4 · CPU · offline"

    def _load_runtime(self) -> Any:
        if self._runtime is not None:
            return self._runtime
        if self._runtime_loader is not None:
            self._runtime = self._runtime_loader()
            return self._runtime
        try:
            installed = package_version("litert-lm-api")
        except PackageNotFoundError as exc:
            raise LocalInferenceError(
                "Не найден LiteRT-LM Python API. Установите: pip install -r requirements-litert-lm.txt"
            ) from exc
        if installed != RUNTIME_VERSION:
            raise LocalInferenceError(
                "Для этой модели нужна версия litert-lm-api==0.17.1; установлен пакет " + installed + "."
            )
        try:
            import litert_lm
        except ImportError as exc:
            raise LocalInferenceError("Пакет установлен, но модуль litert_lm недоступен.") from exc
        self._runtime = litert_lm
        return litert_lm

    def _ensure_engine(self) -> Any:
        if self._engine is not None:
            return self._engine
        runtime = self._load_runtime()
        try:
            self._engine = runtime.Engine(
                str(self.model_path),
                max_num_tokens=MAX_CONTEXT_TOKENS,
                cache_dir=str(self.model_path.parent / ".litert-cache"),
            )
        except Exception as exc:
            raise LocalInferenceError("Не удалось инициализировать локальный LiteRT-LM runtime.") from exc
        return self._engine

    def respond(self, prompt: str, history: list[dict[str, str]]) -> str:
        clean = prompt.strip()
        if not clean or len(clean) > MAX_PROMPT_CHARS or "\x00" in clean:
            raise LocalInferenceError("Сообщение должно содержать от 1 до 900 символов.")
        with self._lock:
            engine = self._ensure_engine()
            messages: list[dict[str, str]] = []
            for item in history[-MAX_HISTORY_MESSAGES:]:
                role = item.get("role")
                content = item.get("content")
                if role not in {"user", "assistant"} or not isinstance(content, str):
                    continue
                value = content.strip()
                if value:
                    messages.append({"role": role, "content": value[:MAX_HISTORY_MESSAGE_CHARS]})
            conversation = None
            try:
                conversation = engine.create_conversation(
                    messages=messages,
                    system_message=SYSTEM_MESSAGE,
                    automatic_tool_calling=False,
                    max_output_tokens=MAX_OUTPUT_TOKENS,
                )
                response = conversation.send_message(clean)
                parts = response.get("content", []) if isinstance(response, dict) else []
                answer = "".join(
                    part.get("text", "")
                    for part in parts
                    if isinstance(part, dict) and part.get("type") == "text"
                ).strip()
                if not answer:
                    raise LocalInferenceError("Локальная модель вернула пустой ответ.")
                return answer[:4_000]
            except LocalInferenceError:
                raise
            except Exception as exc:
                raise LocalInferenceError("Локальная модель не смогла ответить. Проверьте файл и доступную память.") from exc
            finally:
                if conversation is not None:
                    try:
                        conversation.close()
                    except Exception:
                        pass

    def close(self) -> None:
        with self._lock:
            active = self._engine
            self._engine = None
            if active is not None:
                try:
                    active.close()
                except Exception:
                    pass
