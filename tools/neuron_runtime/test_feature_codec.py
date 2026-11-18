"""Focused tests for versioned cognitive sidecar serialization."""
import unittest

from tools.neuron_runtime.neuron_features import NeuronFeatureState, SynapticMemoryState
from tools.neuron_runtime.feature_codec import (
    FeatureCodecError, decode_feature_sidecar, encode_feature_sidecar,
)


class FeatureCodecTests(unittest.TestCase):
    def test_round_trip_preserves_all_feature_fields(self):
        state = NeuronFeatureState(
            sensitivity=1.2, memory_trace=0.4, prediction_error=0.3,
            confidence=0.7, uncertainty=0.2, plasticity=0.8,
            inhibition=0.1, context_match=0.9, observations=42,
        )
        traces = {
            "syn-b": SynapticMemoryState(0.2, 0.3, 0.4),
            "syn-a": SynapticMemoryState(0.5, 0.6, 0.7),
        }
        encoded = encode_feature_sidecar("neuron-17", state, traces)
        neuron_id, restored, restored_traces = decode_feature_sidecar(
            encoded, expected_neuron_id="neuron-17"
        )
        self.assertEqual(neuron_id, "neuron-17")
        self.assertEqual(restored, state)
        self.assertEqual(restored_traces, traces)
        self.assertEqual(encoded, encode_feature_sidecar("neuron-17", state, traces))

    def test_empty_sidecar_round_trip(self):
        encoded = encode_feature_sidecar("n", NeuronFeatureState())
        self.assertEqual(
            decode_feature_sidecar(encoded),
            ("n", NeuronFeatureState(), {}),
        )

    def test_rejects_wrong_neuron_identity(self):
        encoded = encode_feature_sidecar("a", NeuronFeatureState())
        with self.assertRaises(FeatureCodecError):
            decode_feature_sidecar(encoded, expected_neuron_id="b")

    def test_rejects_unknown_version_and_duplicate_keys(self):
        with self.assertRaises(FeatureCodecError):
            decode_feature_sidecar(b'{"format":"JARVIS_NEURON_FEATURE_SIDECAR","version":2}')
        encoded = b'{"format":"x","format":"y"}'
        with self.assertRaises(FeatureCodecError):
            decode_feature_sidecar(encoded)

    def test_rejects_invalid_numeric_state(self):
        encoded = b'{"format":"JARVIS_NEURON_FEATURE_SIDECAR","version":1,"neuron_id":"n","neuron_features":{"sensitivity":NaN},"synaptic_memory":[]}'
        with self.assertRaises(FeatureCodecError):
            decode_feature_sidecar(encoded)


if __name__ == "__main__":
    unittest.main(verbosity=2)
