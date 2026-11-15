"""Regression tests for Android-compatible ANS-100 V4 input routing. NOT RUN."""
from __future__ import annotations

import unittest

from tools.neuron_runtime.hybrid_ans100_runtime import (
    HybridANS100Runtime,
    _candidate_hints,
    _neuron_id_for,
    _typed_evidence_records,
)


class HybridANS100RuntimeInputTests(unittest.TestCase):
    def test_confident_non_ambiguous_intent_supports_answer_lane(self):
        understanding = {
            "intent": "question",
            "intent_confidence": 0.9,
            "task_kind": "answer_question",
            "reasoning_plan": {
                "goal": "Answer the user's question",
                "requires_clarification": False,
            },
            "refers_to_previous_context": False,
        }
        hints = _candidate_hints(understanding)
        self.assertTrue(hints[("answer", "support")])
        self.assertFalse(hints[("answer", "conflict")])
        self.assertFalse(hints[("clarification", "support")])
        self.assertTrue(hints[("clarification", "conflict")])
        self.assertFalse(hints[("abstain", "support")])
        self.assertTrue(hints[("abstain", "conflict")])

    def test_ambiguous_reference_routes_support_to_clarification(self):
        understanding = {
            "intent": "question",
            "intent_confidence": 0.8,
            "task_kind": "answer_question",
            "reasoning_plan": {
                "goal": "Answer the user's question",
                "requires_clarification": False,
            },
            "refers_to_previous_context": True,
            "context_reference": {"status": "ambiguous", "method": "tied_turn_matches"},
        }
        hints = _candidate_hints(understanding)
        self.assertFalse(hints[("answer", "support")])
        self.assertTrue(hints[("answer", "conflict")])
        self.assertTrue(hints[("clarification", "support")])
        self.assertFalse(hints[("clarification", "conflict")])
        self.assertFalse(hints[("abstain", "support")])
        self.assertTrue(hints[("abstain", "conflict")])

    def test_low_confidence_without_a_goal_supports_abstention(self):
        hints = _candidate_hints({
            "intent": "statement_or_unknown",
            "intent_confidence": 0.3,
            "task_kind": "interpret_and_respond",
            "reasoning_plan": {
                "goal": "",
                "requires_clarification": False,
            },
            "refers_to_previous_context": False,
        })
        self.assertFalse(hints[("answer", "support")])
        self.assertTrue(hints[("answer", "conflict")])
        self.assertFalse(hints[("clarification", "support")])
        self.assertTrue(hints[("clarification", "conflict")])
        self.assertTrue(hints[("abstain", "support")])
        self.assertFalse(hints[("abstain", "conflict")])

    def test_typed_feature_ordinals_match_android_stable_input_slots(self):
        records = _typed_evidence_records(
            "Как продолжить проект JARVIS?",
            {
                "language": "ru_or_cyrillic",
                "intent": "question",
                "intent_confidence": 0.8,
                "task_kind": "answer_question",
                "key_terms": ["продолжить", "проект"],
                "entities": ["JARVIS"],
                "contains_negation": False,
                "asks_for_explanation": False,
                "refers_to_previous_context": False,
                "context_reference": {"status": "unavailable", "method": "no_recent_user_turn"},
                "reasoning_plan": {
                    "goal": "Answer the question",
                    "steps": ["Review context"],
                    "requires_clarification": False,
                },
                "evidence": ["question_marker"],
            },
            "turn-test-001",
            approved_memories=[{"id": "memory-1", "category": "project"}],
        )
        by_feature = {record["feature"]: record for record in records}
        self.assertEqual(_neuron_id_for(by_feature["user.message.text"]), 0)
        self.assertEqual(_neuron_id_for(by_feature["message.structure.length_chars"]), 48)
        self.assertEqual(_neuron_id_for(by_feature["message.structure.has_question_mark"]), 97)
        self.assertEqual(_neuron_id_for(by_feature["core.intent_hint"]), 6 * 48)
        self.assertEqual(_neuron_id_for(by_feature["memory.approved_reference"]), 20 * 48)
        self.assertEqual(
            _neuron_id_for(by_feature["ans100.candidate.answer.support"]),
            1_056,
        )
        self.assertEqual(
            _neuron_id_for(by_feature["ans100.candidate.abstain.conflict"]),
            1_061,
        )
        self.assertTrue(all(record["turn_id"] == "turn-test-001" for record in records))

    def test_user_text_never_changes_its_feature_neuron_id(self):
        first = _typed_evidence_records(
            "Первое совершенно другое сообщение",
            {"reasoning_plan": {"goal": "", "requires_clarification": False}},
            "turn-a",
        )
        second = _typed_evidence_records(
            "Второе сообщение с другими словами и символами",
            {"reasoning_plan": {"goal": "", "requires_clarification": False}},
            "turn-b",
        )
        first_user = next(record for record in first if record["feature"] == "user.message.text")
        second_user = next(record for record in second if record["feature"] == "user.message.text")
        self.assertEqual(_neuron_id_for(first_user), 0)
        self.assertEqual(_neuron_id_for(second_user), 0)

    def test_runtime_status_matches_android_hybrid_topology_contract(self):
        runtime = HybridANS100Runtime()
        status = runtime.status()
        self.assertEqual(status["topology_id"], "ans100-hybrid-v2")
        self.assertEqual(status["neuron_count"], 10_000)
        self.assertEqual(status["connection_count"], 25_500)
        self.assertEqual(status["input_contract"], "ANDROID_TYPED_EVIDENCE_V1")
        self.assertTrue(status["active_in_chat"])
        self.assertFalse(status["learning_enabled"])
        self.assertFalse(status["persistent_state"])
        self.assertEqual(status["decision_authority"], "advisory_only")


if __name__ == "__main__":
    unittest.main()
