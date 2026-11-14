"""JARVIS Core conversation orchestrator for the local desktop/PWA runtime.

The language model generates wording; this module owns turn routing, bounded
workspace-memory retrieval, capability reporting and explicit integration limits.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
import re
from typing import Any

from tools.neuron_runtime.conversation_signal import ConversationSignalRuntime

MAX_MEMORY_SCAN = 2_000
MAX_RETRIEVED_MEMORIES = 3
MAX_MEMORY_CHARS = 700
MAX_MEMORY_CONTEXT_CHARS = 1_800
_TOKEN = re.compile(r"[A-Za-zА-Яа-яЁё0-9]{3,}", re.UNICODE)
_STOP_WORDS = frozenset({
    "the", "and", "for", "with", "that", "this", "from", "have", "what",
    "как", "это", "для", "или", "что", "чтобы", "мне", "могу", "можно",
    "когда", "где", "почему", "который", "которая", "которые", "есть",
})


class JarvisCore:
    """Single owner of a conversational turn; never executes external actions."""

    VERSION = "1.0"

    def __init__(self, language_engine: Any | None) -> None:
        self._language_engine = language_engine
        self._signal_runtime = ConversationSignalRuntime()

    @staticmethod
    def _tokens(value: str) -> set[str]:
        return {
            token.casefold()
            for token in _TOKEN.findall(value)
            if token.casefold() not in _STOP_WORDS
        }

    @classmethod
    def retrieve_memories(
        cls,
        prompt: str,
        memories: Sequence[Mapping[str, object]] | object,
    ) -> list[dict[str, str]]:
        """Bounded lexical recall over approved workspace memories; not semantic search."""
        if not isinstance(memories, (list, tuple)):
            return []
        query_terms = cls._tokens(prompt)
        if not query_terms:
            return []
        ranked: list[tuple[int, int, dict[str, str]]] = []
        total_chars = 0
        for index, record in enumerate(memories[:MAX_MEMORY_SCAN]):
            if not isinstance(record, Mapping):
                continue
            # Only explicit, user-approved memories may enter the model context.
            if record.get("user_approved") is not True:
                continue
            content = record.get("content")
            if not isinstance(content, str) or not content.strip():
                continue
            clean_content = content.strip().replace(chr(0), "")[:MAX_MEMORY_CHARS]
            overlap = len(query_terms & cls._tokens(clean_content))
            if overlap == 0:
                continue
            category = record.get("category")
            memory_id = record.get("id")
            ranked.append((
                overlap,
                index,
                {
                    "id": memory_id if isinstance(memory_id, str) else "",
                    "category": category if isinstance(category, str) else "",
                    "content": clean_content,
                },
            ))
        ranked.sort(key=lambda item: (-item[0], item[1]))
        selected: list[dict[str, str]] = []
        for _, _, item in ranked:
            size = len(item["content"]) + len(item["category"]) + 32
            if len(selected) >= MAX_RETRIEVED_MEMORIES:
                break
            if total_chars + size > MAX_MEMORY_CONTEXT_CHARS:
                continue
            selected.append(item)
            total_chars += size
        return selected

    def analyze_signal(self, prompt: str) -> dict[str, object]:
        """Run the bounded ALIF-DT/NetworkRuntime pre-pass without persistence."""
        return self._signal_runtime.analyze(prompt).to_dict()

    def status(
        self,
        memories: Sequence[Mapping[str, object]] | object = (),
    ) -> dict[str, object]:
        engine = self._language_engine
        configured = engine is not None
        runtime_available = bool(getattr(engine, "runtime_available", False)) if configured else False
        backend_label = (
            str(getattr(engine, "backend_label", "Локальный языковой модуль"))
            if configured else "Модель не настроена"
        )
        memory_count = len(memories) if isinstance(memories, (list, tuple)) else 0
        return {
            "core_version": self.VERSION,
            "orchestration": "active",
            "language_engine": {
                "configured": configured,
                "runtime_available": runtime_available,
                "inference_verified": False,
                "backend_label": backend_label,
            },
            "conversation_memory": {
                "state": "active",
                "source": "bounded_conversation_history",
            },
            "long_term_memory": {
                "state": "available" if memory_count else "available_empty",
                "source": "workspace.memories",
                "stored_records": memory_count,
                "retrieval": "bounded_lexical_overlap_v1",
            },
            "reasoning_engine": {
                "state": "preview_only",
                "integrated_into_chat": False,
            },
            "ans100_network": {
                "state": "signal_preprocessor_only",
                "signal_preprocessing_integrated_into_chat": True,
                "full_cognitive_loop_integrated": False,
                "learning_enabled": False,
                "persistent_state": False,
            },
            "external_actions": {"state": "disabled", "enabled": False},
        }

    def respond(
        self,
        prompt: str,
        history: list[dict[str, str]],
        memories: Sequence[Mapping[str, object]] | object = (),
    ) -> tuple[str, dict[str, object]]:
        engine = self._language_engine
        if engine is None:
            raise RuntimeError("JARVIS Core has no configured language engine")
        recalled = self.retrieve_memories(prompt, memories)
        signal_context = None
        signal_error = False
        try:
            signal_context = self.analyze_signal(prompt)
        except Exception:
            # The neural pre-pass is optional; a failure must not break local chat.
            signal_error = True
        answer = engine.respond(
            prompt, history, memory_context=recalled, signal_context=signal_context,
        )
        if not isinstance(answer, str) or not answer.strip() or len(answer) > 4_000 or chr(0) in answer:
            raise RuntimeError("The language component returned an invalid response")
        return answer, {
            "core_version": self.VERSION,
            "retrieved_memory_count": len(recalled),
            "ans100_signal_invoked": signal_context is not None,
            "ans100_attention_gate_active": (
                bool(signal_context["attention_gate_active"])
                if signal_context is not None else False
            ),
            "ans100_signal_error": signal_error,
            "reasoning_engine_invoked": False,
            "ans100_full_cognition_invoked": False,
            "external_actions_invoked": False,
        }
