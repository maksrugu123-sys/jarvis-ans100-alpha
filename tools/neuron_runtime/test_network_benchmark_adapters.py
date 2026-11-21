"""Tests for the real ANS NetworkRuntime CPU benchmark adapter."""
import unittest

from tools.neuron_runtime.network import NetworkRuntime
from tools.neuron_runtime.runtime import NeuronRuntime
from tools.neuron_runtime.compute_benchmark import ComputeBenchmarkError
from tools.neuron_runtime.network_benchmark_adapters import (
    benchmark_network_cpu, make_network_cpu_backend,
)


class NetworkBenchmarkAdapterTests(unittest.TestCase):
    @staticmethod
    def make_network():
        return NetworkRuntime({
            "n1": NeuronRuntime({"s1": 0.5}),
            "n2": NeuronRuntime({"s2": 0.5}),
        })

    def test_cpu_adapter_runs_real_network_and_returns_stable_signature(self):
        events = (
            (1, "n1", "s1", "input-1", 0.7, 0.0),
            (2, "n2", "s2", "input-2", 0.6, 0.2),
        )
        backend = make_network_cpu_backend(self.make_network, events)
        first = backend()
        second = backend()
        self.assertEqual(first, second)
        self.assertEqual(first[0], 2)
        self.assertEqual(first[3], 0)

    def test_cpu_benchmark_reports_only_real_cpu_backend(self):
        events = ((1, "n1", "s1", "input-1", 0.7, 0.0),)
        result = benchmark_network_cpu(self.make_network, events, warmup_runs=0, measured_runs=3)
        self.assertEqual(tuple(result.measurements), ("cpu",))
        self.assertEqual(result.faster_than_baseline_percent["cpu"], 0.0)

    def test_factory_must_return_network_runtime(self):
        backend = make_network_cpu_backend(lambda: object(),
            ((1, "n1", "s1", "input-1", 0.7, 0.0),))
        with self.assertRaises(TypeError):
            backend()

    def test_rejects_malformed_input_event(self):
        with self.assertRaises(ValueError):
            make_network_cpu_backend(self.make_network, ((1, "n1"),))


if __name__ == "__main__":
    unittest.main()