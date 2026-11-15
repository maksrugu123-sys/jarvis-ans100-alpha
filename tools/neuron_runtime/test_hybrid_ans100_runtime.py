"""Regression tests for the per-turn ANS-100 V4 adapter. NOT RUN."""
from __future__ import annotations

import unittest

from tools.neuron_runtime.hybrid_ans100_runtime import (
    HybridANS100Runtime,
    _candidate_hints,
    _feature_neuron_ids,
)


class HybridANS100RuntimeInputTests(unittest.TestCase):
    def test_confident_non_ambiguous_intent_supports_answer_lane(self):
        understanding = {
            "intent": "question",
            "intent_confidence": 0.9,
            "reasoning_plan": {"requires_clarification": False},
            "refers_to_previous_context": False,
            "has_recent_user_context": False,
        }
        hints = _candidate_hints(understanding, None)
        self.assertTrue(hints[("answer", "support")])
        self.assertTrue(hints[("clarification", "conflict")])
        self.assertTrue(hints[("abstain", "conflict")])
        self.assertFalse(hints[("clarification", "support")])
        self.assertFalse(hints[("abstain", "support")])

    def test_ambiguous_reference_routes_support_to_clarification_and_abstention(self):
        understanding = {
            "intent": "question",
            "intent_confidence": 0.8,
            "reasoning_plan": {"requires_clarification": False},
            "refers_to_previous_context": True,
            "has_recent_user_context": False,
            "context_reference": {"status": "ambiguous"},
        }
        hints = _candidate_hints(understanding, None)
        self.assertFalse(hints[("answer", "support")])
        self.assertTrue(hints[("answer", "conflict")])
        self.assertTrue(hints[("clarification", "support")])
        self.assertTrue(hints[("abstain", "support")])

    def test_low_confidence_does_not_create_answer_support(self):
        hints = _candidate_hints({
            "intent": "unknown",
            "intent_confidence": 0.1,
            "reasoning_plan": {"requires_clarification": False},
            "refers_to_previous_context": False,
        }, None)
        self.assertFalse(hints[("answer", "support")])
        self.assertTrue(hints[("clarification", "support")])
        self.assertTrue(hints[("abstain", "support")])

    def test_feature_ids_are_bounded_and_do_not_use_candidate_input_reservations(self):
        ids = _feature_neuron_ids(
            "Как продолжить проект JARVIS? Вспомни прошлый шаг.",
            {
                "contains_negation": False,
                "refers_to_previous_context": True,
                "has_recent_user_context": True,
                "intent_confidence": 0.8,
                "context_reference": {"status": "selected"},
                "entities": ["JARVIS"],
                "key_terms": ["проект"],
            },
            {"plan_id": "bounded-plan"},
            None,
        )
        self.assertTrue(ids)
        self.assertLessEqual(len(ids), 96)
        self.assertTrue(all(0 <= item < 1_056 for item in ids))

    def test_runtime_status_is_explicitly_advisory_and_non_persistent(self):
        runtime = HybridANS100Runtime()
        status = runtime.status()
        self.assertEqual(status["neuron_count"], 10_000)
        self.assertEqual(status["connection_count"], 25_500)
        self.assertTrue(status["active_in_chat"])
        self.assertFalse(status["learning_enabled"])
        self.assertFalse(status["persistent_state"])
        self.assertEqual(status["decision_authority"], "advisory_only")


if __name__ == "__main__":
    unittest.main()
