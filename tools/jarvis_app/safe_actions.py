"""Strict allow-list executor for explicit, read-only JARVIS tasks.

No shell, filesystem mutation, messaging, purchases, uploads, downloads or other
external side effects are available through this executor.
"""
from __future__ import annotations

import re
from typing import Callable, Mapping

from tools.neuron_runtime.permission_safety_gate import EffectClass
from tools.neuron_runtime.task_loop import (
    ActionExecutionResult,
    ExecutionOutcome,
)


_TOKEN = re.compile(r"[a-zа-яё0-9]{2,}", re.IGNORECASE)
_ALLOWED = frozenset({"memory.search", "browser.search", "browser.open", "browser.read"})


class SafeActionExecutor:
    """One task's bounded GET/read-only action adapter."""

    def __init__(
        self,
        *,
        platform: str,
        browser,
        workspace_provider: Callable[[], Mapping[str, object]],
    ) -> None:
        self.platform = platform
        self.browser = browser
        self.workspace_provider = workspace_provider
        self._outputs: dict[str, list[dict[str, object]]] = {}

    def clear_task(self, task_id: str) -> None:
        self._outputs.pop(task_id, None)

    def outputs_for(self, task_id: str) -> list[dict[str, object]]:
        return [dict(item) for item in self._outputs.get(task_id, [])]

    def execute(self, action, request) -> ActionExecutionResult:
        observed = set(action.preconditions)
        observed.difference_update(action.delete_facts)
        if request.effect is not EffectClass.READ_ONLY:
            return ActionExecutionResult(
                ExecutionOutcome.FAILED_NO_EFFECT,
                tuple(sorted(observed)),
                "read_only_action_required",
            )
        if request.capability not in _ALLOWED:
            return ActionExecutionResult(
                ExecutionOutcome.FAILED_NO_EFFECT,
                tuple(sorted(observed)),
                "capability_not_allowlisted",
            )

        try:
            if request.capability == "memory.search":
                output = self._search_memory(action.rationale)
            elif request.capability == "browser.search":
                self._require_desktop_browser()
                output = self.browser.search(action.rationale)
            elif request.capability == "browser.open":
                self._require_desktop_browser()
                output = self.browser.open(action.rationale)
            else:
                self._require_desktop_browser()
                if action.rationale.strip():
                    raise ValueError("browser.read does not accept input text")
                output = self.browser.read()

            bounded = self._bound_output(output)
            self._outputs.setdefault(request.task_id, []).append({
                "action_id": action.action_id,
                "capability": request.capability,
                "result": bounded,
            })
            observed.update(action.add_facts)
            return ActionExecutionResult(
                ExecutionOutcome.SUCCEEDED,
                tuple(sorted(observed)),
            )
        except Exception:
            # All exposed actions are read-only. A navigation/read failure has
            # no application-side mutation and is recorded as a known no-effect.
            return ActionExecutionResult(
                ExecutionOutcome.FAILED_NO_EFFECT,
                tuple(sorted(set(action.preconditions) - set(action.delete_facts))),
                "read_only_action_failed",
            )

    def _require_desktop_browser(self) -> None:
        if self.platform != "desktop":
            raise ValueError("visible browser actions are only available on the desktop platform")

    def _search_memory(self, query: str) -> dict[str, object]:
        clean = query.strip()
        if not clean or len(clean) > 240 or "\x00" in clean:
            raise ValueError("memory.search query is invalid")
        query_terms = set(_TOKEN.findall(clean.lower()))
        if not query_terms:
            raise ValueError("memory.search query has no searchable terms")
        workspace = self.workspace_provider()
        records = workspace.get("records", {})
        memories = records.get("memories", []) if isinstance(records, Mapping) else []
        if not isinstance(memories, list):
            raise ValueError("workspace memories are malformed")
        ranked = []
        for index, record in enumerate(memories[:4096]):
            if not isinstance(record, Mapping):
                continue
            approved = record.get("user_approved") is True or record.get("approved") is True
            content = record.get("content")
            category = record.get("category", "")
            if not approved or not isinstance(content, str) or not isinstance(category, str):
                continue
            terms = set(_TOKEN.findall((content + " " + category).lower()))
            overlap = len(query_terms & terms)
            if overlap:
                ranked.append((overlap, index, {
                    "id": str(record.get("id", record.get("memory_id", "")))[:128],
                    "category": category[:64],
                    "excerpt": content[:280],
                }))
        ranked.sort(key=lambda item: (-item[0], item[1]))
        return {
            "kind": "memory.search",
            "query": clean,
            "matches": [item[2] for item in ranked[:5]],
        }

    @staticmethod
    def _bound_output(value: object) -> object:
        import json
        if isinstance(value, (dict, list)):
            encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)
            if len(encoded) <= 12_000:
                return value
            return {"truncated": True, "preview": encoded[:11_500]}
        if isinstance(value, str):
            return value[:12_000]
        return str(value)[:12_000]
