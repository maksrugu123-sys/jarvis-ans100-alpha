"""Tests for the stateful, bounded ANS-100 conversation signal runtime."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.neuron_runtime.conversation_signal import (
    ConversationSignalError,
    ConversationSignalRuntime,
)


PROMPT = "Как подключить ANS-100 к JARVIS?"


class ConversationSignalRuntimeTests(unittest.TestCase):
    def test_question_flows_through_input_and_attention_neurons(self):
        runtime = ConversationSignalRuntime()
        try:
            result = runtime.analyze(PROMPT)
            self.assertEqual(result.runtime_version, "JARVIS_ANS100_CONVERSATION_SIGNAL_V1")
            self.assertEqual(result.processed_events, 4)
            self.assertEqual(result.processed_batches, 2)
            self.assertEqual(result.spike_count, 2)
            self.assertTrue(result.attention_gate_active)
            self.assertGreater(result.integrator_adaptation, 0.0)
            self.assertGreaterEqual(result.effective_threshold, 1.0)
            self.assertFalse(result.learning_enabled)
            self.assertFalse(result.state_persisted)
        finally:
            runtime.close()

    def test_adaptive_threshold_state_is_retained_between_turns(self):
        runtime = ConversationSignalRuntime()
        try:
            first = runtime.analyze(PROMPT)
            second = runtime.analyze(PROMPT)
            self.assertGreater(second.integrator_adaptation, first.integrator_adaptation)
            self.assertGreaterEqual(second.effective_threshold, first.effective_threshold)
            self.assertEqual(second.model_time_ns, first.model_time_ns + 10_000_000)
        finally:
            runtime.close()

    def test_adaptive_state_survives_service_restart_in_separate_jpvr_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ans100-state.jpvr"
            first_runtime = ConversationSignalRuntime(path)
            try:
                first = first_runtime.analyze(PROMPT)
                self.assertEqual(first.checkpoint_version, 1)
            finally:
                first_runtime.close()

            restarted = ConversationSignalRuntime(path)
            try:
                second = restarted.analyze(PROMPT)
                self.assertTrue(first.state_persisted)
                self.assertTrue(second.state_persisted)
                self.assertEqual(second.checkpoint_version, 2)
                self.assertGreater(second.integrator_adaptation, first.integrator_adaptation)
            finally:
                restarted.close()

    def test_short_non_question_does_not_force_attention_spike(self):
        runtime = ConversationSignalRuntime()
        try:
            result = runtime.analyze("hi")
            self.assertEqual(result.processed_events, 0)
            self.assertEqual(result.spike_count, 0)
            self.assertFalse(result.attention_gate_active)
        finally:
            runtime.close()

    def test_rejects_blank_and_oversized_prompts(self):
        runtime = ConversationSignalRuntime()
        try:
            with self.assertRaises(ConversationSignalError):
                runtime.analyze(" ")
            with self.assertRaises(ConversationSignalError):
                runtime.analyze("x" * 4_001)
        finally:
            runtime.close()


if __name__ == "__main__":
    unittest.main()
