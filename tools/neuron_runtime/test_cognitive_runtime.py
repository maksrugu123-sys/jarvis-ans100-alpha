"""Tests for cognitive sidecar updates driven by real ALIF-DT batches."""
import unittest

from tools.neuron_runtime.cognitive_runtime import CognitiveNeuron, CognitiveNeuronError
from tools.neuron_runtime.neuron_features import NeuronFeatureState, SynapticMemoryState
from tools.neuron_runtime.runtime import NeuronRuntime, SynapticInput


def runtime():
    return NeuronRuntime({"input": 1.0})


class CognitiveNeuronTests(unittest.TestCase):
    def test_real_batch_updates_features_and_sparse_synaptic_memory(self):
        neuron = CognitiveNeuron(runtime(), neuron_id="n-1")
        result = neuron.process_batch(
            0, [SynapticInput("e-1", "input", 1.0)],
            predicted_activity=0.0, context_similarity=0.8, reward=0.5,
        )
        self.assertTrue(result.spike_emitted)
        self.assertEqual(neuron.feature_state.observations, 1)
        self.assertGreater(neuron.feature_state.memory_trace, 0.0)
        self.assertIn("input", neuron.synaptic_memory)
        self.assertGreater(neuron.synaptic_memory["input"].coactivation_trace, 0.0)
        # The feature sidecar does not overwrite the canonical synaptic weight.
        self.assertEqual(neuron.runtime.synapses["input"].weight, 1.0)

    def test_feature_state_is_not_mutated_when_input_validation_fails(self):
        neuron = CognitiveNeuron(runtime(), neuron_id="n-2")
        before = neuron.feature_state
        with self.assertRaises(CognitiveNeuronError):
            neuron.process_batch(
                0, [SynapticInput("bad", "missing", 1.0)],
                predicted_activity=0.0,
            )
        self.assertEqual(neuron.feature_state, before)
        self.assertEqual(neuron.feature_state.observations, 0)

    def test_invalid_prediction_is_rejected_before_runtime_step(self):
        neuron = CognitiveNeuron(runtime(), neuron_id="n-3")
        with self.assertRaises(ValueError):
            neuron.process_batch(
                0, [SynapticInput("e", "input", 1.0)],
                predicted_activity=2.0,
            )
        self.assertEqual(neuron.runtime.processed_batches, 0)
        self.assertEqual(neuron.feature_state, NeuronFeatureState())

    def test_restores_supplied_sparse_feature_state(self):
        features = NeuronFeatureState(observations=7, confidence=0.6)
        memories = {"input": SynapticMemoryState(0.1, 0.2, 0.3)}
        neuron = CognitiveNeuron(
            runtime(), neuron_id="n-4",
            feature_state=features, synaptic_memory=memories,
        )
        self.assertEqual(neuron.feature_state, features)
        self.assertEqual(neuron.synaptic_memory, memories)


if __name__ == "__main__":
    unittest.main(verbosity=2)
