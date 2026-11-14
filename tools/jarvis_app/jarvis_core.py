"""JARVIS Core conversation orchestrator for the local desktop/PWA runtime.

The language model generates wording; this module owns turn routing, bounded
workspace-memory retrieval, capability reporting and explicit integration limits.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
import re
from typing import Any

from tools.neuron_runtime.conversation_signal import ConversationSignalRuntime
from tools.jarvis_app.understanding import analyze_text
from tools.reasoning_engine.engine import (
    CriterionObservation, EvidenceItem, HypothesisSpec, ReasoningEngineError,
    ReasoningEngineV01, ReasoningRequest, SubtaskSpec, SuccessCriterion,
)

MAX_MEMORY_SCAN = 2_000
MAX_RETRIEVED_MEMORIES = 3
MAX_MEMORY_CHARS = 700
MAX_MEMORY_CONTEXT_CHARS = 1_800
MAX_REASONING_SUBTASKS = 8
MAX_REASONING_HYPOTHESES = 8
MAX_REASONING_EVIDENCE = 32
MAX_REASONING_CRITERIA = 16
MAX_REASONING_TEXT_CHARS = 6_000
MAX_EXTERNAL_PAGE_CHARS = 1_800
_TOKEN = re.compile(r"[A-Za-zА-Яа-яЁё0-9]{3,}", re.UNICODE)
_STOP_WORDS = frozenset({
    "the", "and", "for", "with", "that", "this", "from", "have", "what",
    "как", "это", "для", "или", "что", "чтобы", "мне", "могу", "можно",
    "когда", "где", "почему", "который", "которая", "которые", "есть",
})


class JarvisCore:
    """Single owner of a conversational turn; never executes external actions."""

    VERSION = "1.2"

    def __init__(
        self,
        language_engine: Any | None,
        signal_runtime: ConversationSignalRuntime | None = None,
        reasoning_engine: ReasoningEngineV01 | None = None,
        dialog_neuron_service: Any | None = None,
    ) -> None:
        self._language_engine = language_engine
        self._signal_runtime = signal_runtime or ConversationSignalRuntime()
        self._dialog_neuron_service = dialog_neuron_service
        self._reasoning_engine = reasoning_engine or ReasoningEngineV01(
            max_subtasks=MAX_REASONING_SUBTASKS,
            max_hypotheses=MAX_REASONING_HYPOTHESES,
            max_evidence=MAX_REASONING_EVIDENCE,
            max_criteria=MAX_REASONING_CRITERIA,
            max_plan_depth=8,
            max_text_chars=MAX_REASONING_TEXT_CHARS,
        )

    def close(self) -> None:
        self._signal_runtime.close()

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


    @staticmethod
    def _reasoning_object(
        value: object, name: str, *, required: set[str], allowed: set[str],
    ) -> Mapping[str, object]:
        if not isinstance(value, Mapping):
            raise ReasoningEngineError(f"{name} must be an object")
        keys = set(value)
        if keys - allowed or required - keys:
            raise ReasoningEngineError(f"{name} fields do not match the reasoning contract")
        return value

    @staticmethod
    def _reasoning_items(value: object, name: str, maximum: int) -> tuple[object, ...]:
        if not isinstance(value, (list, tuple)) or len(value) > maximum:
            raise ReasoningEngineError(f"{name} must contain at most {maximum} items")
        return tuple(value)

    def _build_reasoning_request(self, payload: object) -> ReasoningRequest:
        root = self._reasoning_object(
            payload, "reasoning_request",
            required={"request_id", "idempotency_key", "objective"},
            allowed={
                "request_id", "idempotency_key", "objective", "subtasks",
                "hypotheses", "evidence", "constraints", "assumptions",
            },
        )
        subtasks = []
        for index, raw_task in enumerate(self._reasoning_items(
            root.get("subtasks", ()), "subtasks", MAX_REASONING_SUBTASKS
        )):
            task = self._reasoning_object(
                raw_task, f"subtasks[{index}]",
                required={"task_id", "title"},
                allowed={"task_id", "title", "depends_on", "criteria"},
            )
            criteria = []
            for cindex, raw_criterion in enumerate(self._reasoning_items(
                task.get("criteria", ()), f"subtasks[{index}].criteria", MAX_REASONING_CRITERIA
            )):
                criterion = self._reasoning_object(
                    raw_criterion, f"subtasks[{index}].criteria[{cindex}]",
                    required={"criterion_id", "description"},
                    allowed={"criterion_id", "description"},
                )
                criteria.append(SuccessCriterion(criterion["criterion_id"], criterion["description"]))
            dependencies = self._reasoning_items(
                task.get("depends_on", ()), f"subtasks[{index}].depends_on",
                MAX_REASONING_SUBTASKS,
            )
            subtasks.append(SubtaskSpec(
                task["task_id"], task["title"], dependencies, tuple(criteria),
            ))

        hypotheses = []
        for index, raw_hypothesis in enumerate(self._reasoning_items(
            root.get("hypotheses", ()), "hypotheses", MAX_REASONING_HYPOTHESES
        )):
            item = self._reasoning_object(
                raw_hypothesis, f"hypotheses[{index}]",
                required={"hypothesis_id", "statement"},
                allowed={"hypothesis_id", "statement"},
            )
            hypotheses.append(HypothesisSpec(item["hypothesis_id"], item["statement"]))

        evidence = []
        for index, raw_evidence in enumerate(self._reasoning_items(
            root.get("evidence", ()), "evidence", MAX_REASONING_EVIDENCE
        )):
            item = self._reasoning_object(
                raw_evidence, f"evidence[{index}]",
                required={"evidence_id", "hypothesis_id", "polarity", "weight", "source_ref"},
                allowed={"evidence_id", "hypothesis_id", "polarity", "weight", "source_ref", "context"},
            )
            evidence.append(EvidenceItem(
                item["evidence_id"], item["hypothesis_id"], item["polarity"],
                item["weight"], item["source_ref"], item.get("context", ""),
            ))

        return ReasoningRequest(
            root["request_id"], root["idempotency_key"], root["objective"],
            tuple(subtasks), tuple(hypotheses), tuple(evidence),
            self._reasoning_items(root.get("constraints", ()), "constraints", 32),
            self._reasoning_items(root.get("assumptions", ()), "assumptions", 32),
        )

    @staticmethod
    def _plan_view(plan: object) -> dict[str, object]:
        return {
            "engine": "JARVIS_REASONING_ENGINE_V0_1",
            "request_id": plan.request_id,
            "idempotency_key": plan.idempotency_key,
            "plan_id": plan.plan_id,
            "objective": plan.objective,
            "constraints": list(plan.constraints),
            "assumptions": list(plan.assumptions),
            "steps": [
                {
                    "ordinal": item.ordinal,
                    "task_id": item.task_id,
                    "title": item.title,
                    "depends_on": list(item.depends_on),
                    "criteria": [
                        {"criterion_id": c.criterion_id, "description": c.description}
                        for c in item.criteria
                    ],
                }
                for item in plan.steps
            ],
            "hypotheses": [
                {
                    "hypothesis_id": item.hypothesis_id,
                    "statement": item.statement,
                    "status": item.status.value,
                    "support_weight": item.support_weight,
                    "contradiction_weight": item.contradiction_weight,
                    "balance_score": item.balance_score,
                    "supporting_evidence_ids": list(item.supporting_evidence_ids),
                    "contradicting_evidence_ids": list(item.contradicting_evidence_ids),
                    "has_conflicting_evidence": item.has_conflicting_evidence,
                }
                for item in plan.hypotheses
            ],
            "unresolved_contradictions": [
                {
                    "hypothesis_id": item.hypothesis_id,
                    "supporting_evidence_ids": list(item.supporting_evidence_ids),
                    "contradicting_evidence_ids": list(item.contradicting_evidence_ids),
                }
                for item in plan.unresolved_contradictions
            ],
            "source_evidence_refs": list(plan.source_evidence_refs),
            "execution_authorized": False,
            "scope": "structured_symbolic_planning_only",
        }

    def plan_structured_task(self, payload: object) -> dict[str, object]:
        """Analyze a caller-supplied structured request; never execute its steps."""
        plan = self._reasoning_engine.analyze(self._build_reasoning_request(payload))
        return self._plan_view(plan)

    @staticmethod
    def _language_plan_context(plan: Mapping[str, object]) -> dict[str, object]:
        """Keep the prompt-side plan summary small enough for the mobile-sized LLM."""
        raw_steps = plan.get("steps", [])
        steps = []
        if isinstance(raw_steps, list):
            for item in raw_steps[:MAX_REASONING_SUBTASKS]:
                if not isinstance(item, Mapping):
                    continue
                raw_criteria = item.get("criteria", [])
                criteria = []
                if isinstance(raw_criteria, list) and raw_criteria:
                    first = raw_criteria[0]
                    if isinstance(first, Mapping):
                        criteria.append({
                            "criterion_id": str(first.get("criterion_id", ""))[:128],
                            "description": str(first.get("description", ""))[:140],
                        })
                dependencies = item.get("depends_on", [])
                steps.append({
                    "task_id": str(item.get("task_id", ""))[:128],
                    "title": str(item.get("title", ""))[:140],
                    "depends_on": list(dependencies[:MAX_REASONING_SUBTASKS])
                    if isinstance(dependencies, list) else [],
                    "criteria": criteria,
                })
        return {
            "plan_id": str(plan.get("plan_id", "")),
            "objective": str(plan.get("objective", ""))[:240],
            "steps": steps,
            "omitted_step_count": max(0, len(raw_steps) - len(steps))
            if isinstance(raw_steps, list) else 0,
            "execution_authorized": False,
            "scope": "bounded_symbolic_plan_summary",
        }

    def verify_structured_task(
        self, payload: object, plan_id: object,
        completed_step_ids: object, observations: object,
    ) -> dict[str, object]:
        """Rebuild a deterministic plan and assess caller-supplied outcome evidence."""
        plan = self._reasoning_engine.analyze(self._build_reasoning_request(payload))
        if not isinstance(plan_id, str) or plan_id != plan.plan_id:
            raise ReasoningEngineError("plan_id does not match the submitted reasoning request")
        completed = self._reasoning_items(completed_step_ids, "completed_step_ids", MAX_REASONING_SUBTASKS)
        raw_observations = self._reasoning_items(observations, "observations", MAX_REASONING_CRITERIA)
        checks = []
        for index, raw in enumerate(raw_observations):
            item = self._reasoning_object(
                raw, f"observations[{index}]",
                required={"criterion_id", "passed"},
                allowed={"criterion_id", "passed", "evidence_refs", "note"},
            )
            checks.append(CriterionObservation(
                item["criterion_id"], item["passed"],
                self._reasoning_items(item.get("evidence_refs", ()), f"observations[{index}].evidence_refs", 32),
                item.get("note", ""),
            ))
        result = self._reasoning_engine.verify_outcome(
            plan, completed_step_ids=completed, observations=tuple(checks),
        )
        return {
            "plan_id": result.plan_id,
            "status": result.status.value,
            "completed_step_ids": list(result.completed_step_ids),
            "passed_criterion_ids": list(result.passed_criterion_ids),
            "failed_criterion_ids": list(result.failed_criterion_ids),
            "missing_criterion_ids": list(result.missing_criterion_ids),
            "reasons": list(result.reasons),
            "scope": "caller_supplied_observation_review",
        }

    def analyze_signal(self, prompt: str) -> dict[str, object]:
        """Run the bounded ALIF-DT/NetworkRuntime pre-pass without persistence."""
        return self._signal_runtime.analyze(prompt).to_dict()

    def status(
        self,
        memories: Sequence[Mapping[str, object]] | object = (),
    ) -> dict[str, object]:
        engine = self._language_engine
        configured = engine is not None
        model_configured = bool(getattr(engine, "language_model_configured", configured)) if configured else False
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
                "language_model_configured": model_configured,
                "mode": "local_model" if model_configured else "deterministic_fallback" if configured else "unavailable",
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
            "understanding": {
                "state": "bounded_heuristic_pre_generation_analysis",
                "integrated_into_chat": True,
                "semantic_model": False,
                "language_generation_owner": "configured_language_engine",
            },
            "reasoning_engine": {
                "state": "structured_planning_available",
                "integrated_into_chat": True,
                "invocation_mode": "explicit_structured_request_only",
                "natural_language_decomposition": False,
                "outcome_review": "caller_supplied_observations_only",
                "plan_persistence": False,
                "execution_enabled": False,
            },
            "ans100_network": {
                "state": "ten_neuron_signal_preprocessor_only",
                "runtime_version": "JARVIS_ANS100_CONVERSATION_SIGNAL_V2",
                "neuron_count": self._signal_runtime.neuron_count,
                "signal_preprocessing_integrated_into_chat": True,
                "full_cognitive_loop_integrated": False,
                "learning_enabled": False,
                "adaptive_threshold_enabled": True,
                "persistent_state": self._signal_runtime.persistent_state_enabled,
            },
            "dialogue_neurons": (
                self._dialog_neuron_service.status()
                if self._dialog_neuron_service is not None
                else {"state": "unavailable", "neuron_count": 0}
            ),
            "external_actions": {"state": "disabled", "enabled": False},
        }

    def respond(
        self,
        prompt: str,
        history: list[dict[str, str]],
        memories: Sequence[Mapping[str, object]] | object = (),
        reasoning_request: Mapping[str, object] | None = None,
        external_context: Mapping[str, object] | None = None,
    ) -> tuple[str, dict[str, object]]:
        engine = self._language_engine
        if engine is None:
            raise RuntimeError("JARVIS Core has no configured language engine")
        understanding_context = analyze_text(prompt, history)
        recalled = self.retrieve_memories(prompt, memories)
        natural_language_plan = understanding_context.get("reasoning_plan", {})
        plan_steps = (
            natural_language_plan.get("steps", [])
            if isinstance(natural_language_plan, Mapping) else []
        )
        reasoning_context = None
        if reasoning_request is not None:
            reasoning_plan = self.plan_structured_task(reasoning_request)
            reasoning_context = self._language_plan_context(reasoning_plan)
        signal_context = None
        signal_error = False
        try:
            signal_context = self.analyze_signal(prompt)
        except Exception:
            # The neural pre-pass is optional; a failure must not break local chat.
            signal_error = True
        dialog_neuron_context = None
        dialog_neuron_error = False
        if self._dialog_neuron_service is not None:
            try:
                dialog_neuron_context = self._dialog_neuron_service.analyze(prompt)
            except Exception:
                # Experimental intent classification must never block ordinary chat.
                dialog_neuron_error = True
        safe_external_context = None
        if external_context is not None:
            if not isinstance(external_context, Mapping):
                raise ValueError("external_context must be a mapping")
            title = external_context.get("title")
            url = external_context.get("url")
            text = external_context.get("text")
            retrieved_at = external_context.get("retrieved_at", "")
            if (
                not isinstance(title, str) or not title.strip() or len(title) > 300
                or not isinstance(url, str) or not url.startswith(("http://", "https://")) or len(url) > 2_048
                or not isinstance(text, str) or not text.strip()
                or not isinstance(retrieved_at, str) or len(retrieved_at) > 80
            ):
                raise ValueError("external_context does not match the bounded browser-page contract")
            clean_text = text.replace(chr(0), "").strip()[:MAX_EXTERNAL_PAGE_CHARS]
            clean_title = "".join(
                char for char in title if ord(char) >= 0x20 and ord(char) != 0x7F
            ).strip()
            if not clean_text or not clean_title:
                raise ValueError("external_context title or page text is empty")
            safe_external_context = {
                "title": clean_title,
                "url": url,
                "retrieved_at": retrieved_at,
                "text": clean_text,
                "scope": "untrusted_external_web_page",
            }
        engine_arguments = {
            "memory_context": recalled,
            "understanding_context": understanding_context,
            "signal_context": signal_context,
            "reasoning_context": reasoning_context,
            "dialog_neuron_context": dialog_neuron_context,
        }
        # Preserve compatibility with older test/adaptor engines on ordinary chat.
        if safe_external_context is not None:
            engine_arguments["external_context"] = safe_external_context
        answer = engine.respond(prompt, history, **engine_arguments)
        if not isinstance(answer, str) or not answer.strip() or len(answer) > 4_000 or chr(0) in answer:
            raise RuntimeError("The language component returned an invalid response")
        return answer, {
            "core_version": self.VERSION,
            "retrieved_memory_count": len(recalled),
            "understanding_analysis_invoked": True,
            "understanding_intent": understanding_context["intent"],
            "understanding_confidence": understanding_context["intent_confidence"],
            "understanding_scope": understanding_context["scope"],
            "understanding_has_recent_user_context": understanding_context["has_recent_user_context"],
            "natural_language_plan_generated": (
                isinstance(natural_language_plan, Mapping)
                and natural_language_plan.get("schema") == "JARVIS_REASONING_PLAN_V1"
            ),
            "natural_language_plan_step_count": len(plan_steps) if isinstance(plan_steps, list) else 0,
            "natural_language_plan_requires_clarification": (
                natural_language_plan.get("requires_clarification") is True
                if isinstance(natural_language_plan, Mapping) else False
            ),
            "natural_language_plan_context_available": (
                natural_language_plan.get("context_reference_resolved") is True
                if isinstance(natural_language_plan, Mapping) else False
            ),
            "natural_language_plan_execution_authorized": False,
            "ans100_signal_invoked": signal_context is not None,
            "ans100_attention_gate_active": (
                bool(signal_context["attention_gate_active"])
                if signal_context is not None else False
            ),
            "ans100_signal_error": signal_error,
            "reasoning_engine_invoked": reasoning_context is not None,
            "reasoning_status": "plan_generated" if reasoning_context is not None else "not_requested",
            "reasoning_plan_id": reasoning_context["plan_id"] if reasoning_context is not None else None,
            "reasoning_step_count": len(reasoning_context["steps"]) if reasoning_context is not None else 0,
            "reasoning_execution_authorized": False,
            "dialog_neuron_context_invoked": dialog_neuron_context is not None,
            "dialog_neuron_error": dialog_neuron_error,
            "dialog_neuron_count": (
                dialog_neuron_context.get("neuron_count", 0)
                if dialog_neuron_context is not None else 0
            ),
            "dialog_neuron_active_features": (
                dialog_neuron_context.get("active_feature_neurons", 0)
                if dialog_neuron_context is not None else 0
            ),
            "dialog_neuron_predicted_label": (
                dialog_neuron_context.get("predicted_label")
                if dialog_neuron_context is not None else None
            ),
            "dialog_neuron_trained_examples": (
                dialog_neuron_context.get("trained_examples", 0)
                if dialog_neuron_context is not None else 0
            ),
            "external_context_used": safe_external_context is not None,
            "external_context_source": (
                {"title": safe_external_context["title"], "url": safe_external_context["url"]}
                if safe_external_context is not None else None
            ),
            "ans100_full_cognition_invoked": False,
            "external_actions_invoked": False,
        }
