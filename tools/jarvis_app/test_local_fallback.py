"""Tests for the deterministic no-model JARVIS response engine."""
from __future__ import annotations

import unittest

from tools.jarvis_app.local_fallback import LocalFallbackResponseEngine


class LocalFallbackResponseEngineTests(unittest.TestCase):
    def setUp(self):
        self.engine = LocalFallbackResponseEngine()

    def test_is_available_without_claiming_a_language_model(self):
        self.assertTrue(self.engine.runtime_available)
        self.assertFalse(self.engine.language_model_configured)
        self.assertIn("без языковой модели", self.engine.backend_label)

    def test_help_and_status_are_available_offline(self):
        self.assertIn("экспериментальный JARVIS", self.engine.respond("/help", []))
        status = self.engine.respond(
            "/status",
            [],
            memory_context=[{"id": "m1", "category": "device", "content": "Android"}],
            signal_context={"processed_events": 3, "spike_count": 1, "neuron_count": 10},
            dialog_neuron_context={"neuron_count": 10_000, "trained_examples": 2},
            ans100_context={"selected_candidate": "clarification"},
        )
        self.assertIn("событий 3", status)
        self.assertIn("10 000", status)
        self.assertIn("кандидат сигнала — clarification", status)

    def test_unknown_question_does_not_fake_an_answer(self):
        answer = self.engine.respond("Как устроен квантовый компьютер?", [])
        self.assertIn("не умеет формировать свободные содержательные ответы", answer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
