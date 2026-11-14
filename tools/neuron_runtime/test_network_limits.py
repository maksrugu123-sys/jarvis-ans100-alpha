"""Contract tests for explicit multi-neuron topology ceilings."""
from __future__ import annotations

import unittest
from collections.abc import Mapping

from tools.neuron_runtime.network import (
    MAX_NETWORK_CONNECTIONS,
    MAX_NETWORK_NEURONS,
    MAX_NETWORK_SYNAPSES,
    NetworkRuntime,
    NetworkRuntimeError,
)
from tools.neuron_runtime.runtime import NeuronRuntime


class _OversizedMapping(Mapping):
    def __len__(self):
        return MAX_NETWORK_NEURONS + 1

    def __iter__(self):
        return iter(())

    def __getitem__(self, key):
        raise KeyError(key)


class _OversizedConnections(list):
    def __len__(self):
        return MAX_NETWORK_CONNECTIONS + 1


class _OversizedSynapseRuntime(NeuronRuntime):
    @property
    def synapse_count(self):
        return MAX_NETWORK_SYNAPSES + 1


class NetworkTopologyLimitTests(unittest.TestCase):
    def test_accepts_exact_neuron_limit(self):
        neurons = {f"n{index:05d}": NeuronRuntime({}) for index in range(MAX_NETWORK_NEURONS)}
        network = NetworkRuntime(neurons)
        self.assertEqual(len(network.neuron_ids), MAX_NETWORK_NEURONS)

    def test_rejects_neuron_count_above_limit_before_iteration(self):
        with self.assertRaisesRegex(NetworkRuntimeError, "neuron topology limit"):
            NetworkRuntime(_OversizedMapping())

    def test_rejects_connection_count_above_limit_before_iteration(self):
        with self.assertRaisesRegex(NetworkRuntimeError, "connection topology limit"):
            NetworkRuntime({"n": NeuronRuntime({})}, _OversizedConnections())

    def test_rejects_total_synapse_count_above_limit(self):
        with self.assertRaisesRegex(NetworkRuntimeError, "synapse topology limit"):
            NetworkRuntime({"n": _OversizedSynapseRuntime({})})


if __name__ == "__main__":
    unittest.main(verbosity=2)
