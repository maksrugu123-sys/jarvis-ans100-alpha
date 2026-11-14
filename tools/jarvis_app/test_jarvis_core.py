"""Focused tests for JARVIS Core turn orchestration and bounded memory recall."""
from __future__ import annotations

import unittest

from tools.jarvis_app.jarvis_core import JarvisCore


class FakeLanguageEngine:
    backend_label = "fake-local-language-model"
    runtime_available = True

    def __init__(self) -> None:
        self.prompt = ""
        self.history = []
        self.memory_context = []

    def respond(
        self, prompt, history, *, memory_context=(), signal_context=None,
        reasoning_context=None,
    ):
        self.prompt = prompt
        self.history = history
        self.memory_context = list(memory_context)
        self.signal_context = signal_context
        self.reasoning_context = reasoning_context
        return "Сформированный языковым модулем ответ."


class JarvisCoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = FakeLanguageEngine()
        self.core = JarvisCore(self.engine)

    def test_turn_routes_language_generation_through_core_and_retrieves_matching_memory(self):
        memories = [
            {
                "id": "mem-android",
                "user_approved": True,
                "category": "device",
                "content": "Пользователь использует Android и предпочитает локальную офлайн-работу.",
            },
            {
                "id": "mem-unrelated",
                "user_approved": True,
                "category": "food",
                "content": "Любимый десерт — яблочный пирог.",
            },
        ]
        answer, trace = self.core.respond(
            "Как настроить офлайн-работу на Android?",
            [{"role": "user", "content": "Ранее обсуждали JARVIS."}],
            memories,
        )

        self.assertIn("языковым модулем", answer)
        self.assertEqual(self.engine.prompt, "Как настроить офлайн-работу на Android?")
        self.assertEqual(len(self.engine.memory_context), 1)
        self.assertEqual(self.engine.memory_context[0]["id"], "mem-android")
        self.assertEqual(trace["retrieved_memory_count"], 1)
        self.assertFalse(trace["reasoning_engine_invoked"])
        self.assertTrue(trace["ans100_signal_invoked"])
        self.assertIsNotNone(self.engine.signal_context)
        self.assertEqual(
            self.engine.signal_context["runtime_version"],
            "JARVIS_ANS100_CONVERSATION_SIGNAL_V1",
        )
        self.assertFalse(trace["ans100_full_cognition_invoked"])
        self.assertFalse(trace["external_actions_invoked"])

    def test_status_does_not_claim_ans100_or_reasoning_are_in_chat_loop(self):
        status = self.core.status([
            {"id": "memory-1", "content": "A saved memory", "category": "test"}
        ])
        self.assertEqual(status["orchestration"], "active")
        self.assertTrue(status["language_engine"]["runtime_available"])
        self.assertEqual(status["long_term_memory"]["retrieval"], "bounded_lexical_overlap_v1")
        self.assertTrue(status["reasoning_engine"]["integrated_into_chat"])
        self.assertEqual(status["reasoning_engine"]["invocation_mode"], "explicit_structured_request_only")
        self.assertFalse(status["reasoning_engine"]["natural_language_decomposition"])
        self.assertFalse(status["reasoning_engine"]["execution_enabled"])
        self.assertTrue(status["ans100_network"]["signal_preprocessing_integrated_into_chat"])
        self.assertFalse(status["ans100_network"]["full_cognitive_loop_integrated"])
        self.assertFalse(status["ans100_network"]["learning_enabled"])
        self.assertFalse(status["external_actions"]["enabled"])

    def test_memory_retrieval_is_bounded_and_ignores_non_records(self):
        memories = [
            {"id": f"match-{index}", "user_approved": True, "content": "JARVIS Android offline runtime memory"}
            for index in range(8)
        ] + [None, {"id": "bad", "content": 7}]
        selected = JarvisCore.retrieve_memories("Android offline JARVIS", memories)
        self.assertLessEqual(len(selected), 3)
        self.assertTrue(all(isinstance(item["content"], str) for item in selected))

    def test_unapproved_memories_are_never_retrieved(self):
        selected = JarvisCore.retrieve_memories(
            "Android offline JARVIS",
            [{
                "id": "private-note",
                "user_approved": False,
                "content": "Android offline JARVIS private information",
            }],
        )
        self.assertEqual(selected, [])

    def test_signal_failure_does_not_block_conversation(self):
        class BrokenSignal:
            def analyze(self, prompt):
                raise RuntimeError("unavailable")

        core = JarvisCore(self.engine)
        core._signal_runtime = BrokenSignal()
        answer, trace = core.respond("Привет", [], [])

        self.assertTrue(answer)
        self.assertTrue(trace["ans100_signal_error"])
        self.assertFalse(trace["ans100_signal_invoked"])
        self.assertIsNone(self.engine.signal_context)

    def test_no_keyword_overlap_does_not_inject_unrelated_memory(self):
        selected = JarvisCore.retrieve_memories(
            "Как устроено квантовое туннелирование?",
            [{"id": "other", "user_approved": True, "content": "Пользователь предпочитает тёмную тему."}],
        )
        self.assertEqual(selected, [])


    @staticmethod
    def _reasoning_request():
        return {
            "request_id": "req-chat-1",
            "idempotency_key": "idem-chat-1",
            "objective": "Prepare a verified local report",
            "subtasks": [
                {
                    "task_id": "collect",
                    "title": "Collect source facts",
                    "criteria": [
                        {"criterion_id": "facts-ready", "description": "Required facts are collected"},
                    ],
                },
                {
                    "task_id": "review",
                    "title": "Review the facts",
                    "depends_on": ["collect"],
                    "criteria": [
                        {"criterion_id": "review-ready", "description": "Facts were checked"},
                    ],
                },
            ],
        }

    def test_explicit_structured_reasoning_is_routed_through_core_to_language_engine(self):
        answer, trace = self.core.respond(
            "Объясни план выполнения задачи",
            [],
            reasoning_request=self._reasoning_request(),
        )
        self.assertTrue(answer)
        self.assertTrue(trace["reasoning_engine_invoked"])
        self.assertEqual(trace["reasoning_status"], "plan_generated")
        self.assertEqual(trace["reasoning_step_count"], 2)
        self.assertFalse(trace["reasoning_execution_authorized"])
        self.assertEqual(
            self.engine.reasoning_context["steps"][1]["depends_on"],
            ["collect"],
        )
        self.assertFalse(self.engine.reasoning_context["execution_authorized"])

    def test_structured_reasoning_verification_requires_matching_plan_and_evidence(self):
        request = self._reasoning_request()
        plan = self.core.plan_structured_task(request)
        result = self.core.verify_structured_task(
            request,
            plan["plan_id"],
            ["collect", "review"],
            [
                {"criterion_id": "facts-ready", "passed": True, "evidence_refs": ["local:file:1"]},
                {"criterion_id": "review-ready", "passed": True, "evidence_refs": ["local:file:2"]},
            ],
        )
        self.assertEqual(result["status"], "verified")
        self.assertEqual(len(result["passed_criterion_ids"]), 2)
        with self.assertRaises(ValueError):
            self.core.verify_structured_task(
                request, "0" * 64, ["collect", "review"], [],
            )

    def test_reasoning_rejects_cyclic_structured_input(self):
        request = self._reasoning_request()
        request["subtasks"][0]["depends_on"] = ["review"]
        with self.assertRaises(ValueError):
            self.core.plan_structured_task(request)

    def test_reasoning_rejects_more_than_eight_subtasks(self):
        request = self._reasoning_request()
        request["subtasks"] = [
            {"task_id": f"task-{index}", "title": f"Task {index}"}
            for index in range(9)
        ]
        with self.assertRaises(ValueError):
            self.core.plan_structured_task(request)

    def test_ordinary_chat_does_not_infer_or_invoke_a_reasoning_plan(self):
        _, trace = self.core.respond("Привет", [])
        self.assertFalse(trace["reasoning_engine_invoked"])
        self.assertIsNone(self.engine.reasoning_context)


if __name__ == "__main__":
    unittest.main()
