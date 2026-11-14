"""Tests for the stateful, bounded 10-neuron ANS-100 conversation signal runtime."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.conversation_signal import (
    ConversationSignalError, ConversationSignalRuntime, _NETWORK_ID,
)
from tools.neuron_runtime.network import NetworkConnection, NetworkRuntime
from tools.neuron_runtime.network_store import JPVRNetworkCheckpointStore
from tools.neuron_runtime.runtime import NeuronRuntime
from tools.numerical_oracle.jarvis_oracle import NeuronParams

PROMPT = "Как подключить ANS-100 к JARVIS?"
_TIME_STEP_NS = 10_000_000


def _legacy_two_neuron_network() -> NetworkRuntime:
    input_params = NeuronParams(
        theta_0=1.0, theta_min=1.0, theta_max=1.5,
        delta_a_spike=0.2, tau_a_ns=100_000_000, k_a=0.5,
    )
    output_params = NeuronParams(
        theta_0=1.0, theta_min=1.0, theta_max=1.0, delta_a_spike=0.0,
    )
    return NetworkRuntime(
        {
            "request_integrator": NeuronRuntime(
                {"token_volume": 1.0, "lexical_diversity": 1.0, "question_marker": 1.0},
                neuron_params=input_params, learning_rule="none",
            ),
            "attention_gate": NeuronRuntime(
                {"request_signal": 1.0}, neuron_params=output_params, learning_rule="none",
            ),
        },
        (NetworkConnection(
            "request-to-attention", "request_integrator", "attention_gate",
            "request_signal", delay_ns=1, strength=1.0,
        ),),
        max_pending_events=8, max_events_per_run=8,
    )


class ConversationSignalRuntimeTests(unittest.TestCase):
    def test_question_flows_through_all_ten_neurons_within_the_event_budget(self):
        runtime = ConversationSignalRuntime()
        try:
            result = runtime.analyze(PROMPT)
            self.assertEqual(result.runtime_version, "JARVIS_ANS100_CONVERSATION_SIGNAL_V2")
            self.assertEqual(result.network_neuron_count, 10)
            self.assertEqual(result.active_neuron_count, 10)
            self.assertEqual(result.processed_events, 15)
            self.assertEqual(result.processed_batches, 10)
            self.assertEqual(result.spike_count, 10)
            self.assertTrue(result.attention_gate_active)
            self.assertGreater(result.integrator_adaptation, 0.0)
            self.assertGreaterEqual(result.effective_threshold, 1.5)
            self.assertAlmostEqual(result.token_volume, 5.0 / 12.0)
            self.assertAlmostEqual(result.lexical_diversity, 1.0)
            self.assertEqual(result.question_marker, 0.35)
            self.assertGreater(result.punctuation_density, 0.0)
            self.assertGreater(result.sentence_density, 0.0)
            self.assertGreater(result.uppercase_ratio, 0.0)
            self.assertFalse(result.learning_enabled)
            self.assertFalse(result.state_persisted)
        finally:
            runtime.close()

    def test_adaptive_integrator_state_is_retained_between_turns(self):
        runtime = ConversationSignalRuntime()
        try:
            first = runtime.analyze(PROMPT)
            second = runtime.analyze(PROMPT)
            self.assertGreater(second.integrator_adaptation, first.integrator_adaptation)
            self.assertGreater(second.effective_threshold, first.effective_threshold)
            self.assertEqual(
                second.model_time_ns,
                first.model_time_ns + _TIME_STEP_NS + 3,
            )
            self.assertEqual(second.network_neuron_count, 10)
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
                self.assertEqual(second.network_neuron_count, 10)
                self.assertGreater(second.integrator_adaptation, first.integrator_adaptation)
            finally:
                restarted.close()

    def test_legacy_two_neuron_checkpoint_migrates_without_losing_adaptation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy-ans100.jpvr"
            legacy = _legacy_two_neuron_network()
            legacy.schedule_input(
                10_000_000, "request_integrator", "token_volume", "legacy-token", 1.0,
            )
            legacy.run()
            legacy.run()  # drain the routed gate event before taking the checkpoint
            storage = JPVRStorage.create(path)
            try:
                JPVRNetworkCheckpointStore(storage).save(
                    _NETWORK_ID, legacy, clock_epoch_id=b"\x01" * 16,
                    expected_record_version=0,
                )
            finally:
                storage.close()
            migrated = ConversationSignalRuntime(path)
            try:
                result = migrated.analyze(PROMPT)
                self.assertEqual(result.network_neuron_count, 10)
                self.assertEqual(result.checkpoint_version, 2)
                self.assertGreater(result.integrator_adaptation, 0.2)
                self.assertTrue(result.state_persisted)
            finally:
                migrated.close()

    def test_short_text_without_supported_features_does_not_advance_clock(self):
        runtime = ConversationSignalRuntime()
        try:
            result = runtime.analyze("hi")
            self.assertEqual(result.processed_events, 0)
            self.assertEqual(result.processed_batches, 0)
            self.assertEqual(result.spike_count, 0)
            self.assertEqual(result.active_neuron_count, 0)
            self.assertEqual(result.network_neuron_count, 10)
            self.assertFalse(result.attention_gate_active)
            self.assertEqual(result.model_time_ns, 0)
            self.assertFalse(result.state_persisted)
        finally:
            runtime.close()

    def test_question_marker_and_punctuation_alone_do_not_activate_output_gate(self):
        runtime = ConversationSignalRuntime()
        try:
            result = runtime.analyze("?")
            self.assertEqual(result.processed_events, 7)
            self.assertEqual(result.processed_batches, 5)
            self.assertEqual(result.spike_count, 4)
            self.assertEqual(result.active_neuron_count, 4)
            self.assertFalse(result.attention_gate_active)
            self.assertEqual(result.integrator_adaptation, 0.0)
            self.assertEqual(result.question_marker, 0.35)
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
