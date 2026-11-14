"""Tests for the bounded ANS-100 conversation signal pass."""
from __future__ import annotations

import unittest

from tools.neuron_runtime.conversation_signal import (
    ConversationSignalError,
    ConversationSignalRuntime,
)


class ConversationSignalRuntimeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.runtime = ConversationSignalRuntime()

    def test_question_flows_through_input_and_attention_neurons(self):
        result = self.runtime.analyze("Как подключить ANS-100 к JARVIS?")

        self.assertEqual(result.runtime_version, "JARVIS_ANS100_CONVERSATION_SIGNAL_V1")
        self.assertEqual(result.processed_events, 4)
        self.assertEqual(result.processed_batches, 2)
        self.assertEqual(result.spike_count, 2)
        self.assertTrue(result.attention_gate_active)
        self.assertFalse(result.learning_enabled)
        self.assertFalse(result.state_persisted)

    def test_short_non_question_does_not_force_attention_spike(self):
        result = self.runtime.analyze("hi")
        self.assertEqual(result.processed_events, 0)
        self.assertEqual(result.spike_count, 0)
        self.assertFalse(result.attention_gate_active)

    def test_each_turn_starts_from_fresh_network_state(self):
        first = self.runtime.analyze("Explain JARVIS Core and ANS-100 integration?")
        second = self.runtime.analyze("Explain JARVIS Core and ANS-100 integration?")
        self.assertEqual(first, second)

    def test_rejects_blank_and_oversized_prompts(self):
        with self.assertRaises(ConversationSignalError):
            self.runtime.analyze(" ")
        with self.assertRaises(ConversationSignalError):
            self.runtime.analyze("x" * 4_001)


if __name__ == "__main__":
    unittest.main()
