"""Tests for bounded multi-neuron event routing."""
from __future__ import annotations

import unittest

from tools.neuron_runtime.network import (
    NetworkBackpressureError, NetworkConnection, NetworkRuntime, NetworkRuntimeError,
)
from tools.neuron_runtime.runtime import NeuronRuntime
from tools.numerical_oracle.jarvis_oracle import NeuronParams, NeuronState, SynapseState


def make_network(*, max_pending_events=64, max_events_per_run=64):
    neurons = {
        "source": NeuronRuntime({"external": 1.0}),
        "middle": NeuronRuntime({"from-source": 1.0}),
        "target": NeuronRuntime({"from-middle": 1.0}),
    }
    links = [
        NetworkConnection("s-m", "source", "middle", "from-source", 5),
        NetworkConnection("m-t", "middle", "target", "from-middle", 7),
    ]
    return NetworkRuntime(neurons, links, max_pending_events=max_pending_events,
                          max_events_per_run=max_events_per_run)


class NetworkTests(unittest.TestCase):
    def test_rejects_empty_network_and_zero_delay(self):
        with self.assertRaises(NetworkRuntimeError):
            NetworkRuntime({})
        with self.assertRaises(NetworkRuntimeError):
            NetworkConnection("c", "a", "b", "s", 0).validate()

    def test_rejects_unknown_endpoints_and_duplicate_target_synapse(self):
        with self.assertRaises(NetworkRuntimeError):
            NetworkRuntime({"a": NeuronRuntime({})}, [NetworkConnection("c", "x", "a", "s", 1)])
        a, b = NeuronRuntime({}), NeuronRuntime({"in": 1.0})
        links = [NetworkConnection("one", "a", "b", "in", 1),
                 NetworkConnection("two", "a", "b", "in", 2)]
        with self.assertRaises(NetworkRuntimeError):
            NetworkRuntime({"a": a, "b": b}, links)

    def test_queue_rejects_unknown_and_duplicate_pending_events(self):
        network = make_network()
        with self.assertRaises(NetworkRuntimeError):
            network.schedule_input(0, "missing", "external", "id", 1.0)
        network.schedule_input(0, "source", "external", "same", 0.2)
        with self.assertRaises(NetworkRuntimeError):
            network.schedule_input(1, "source", "external", "same", 0.2)

    def test_queue_capacity_applies_backpressure(self):
        network = make_network(max_pending_events=1)
        network.schedule_input(0, "source", "external", "one", 0.2)
        with self.assertRaises(NetworkBackpressureError):
            network.schedule_input(1, "source", "external", "two", 0.2)

    def test_network_clock_includes_later_synapse_reward_timestamp(self):
        runtime = NeuronRuntime(
            {"in": SynapseState(
                weight=0.5,
                trace_last_update_model_time_ns=20,
                eligibility_last_update_model_time_ns=20,
            )},
            neuron_state=NeuronState(last_update_model_time_ns=10),
            learning_rule="stdp",
        )
        network = NetworkRuntime({"n": runtime})
        self.assertEqual(network.current_time_ns, 20)
        with self.assertRaises(NetworkRuntimeError):
            network.schedule_input(19, "n", "in", "too-early", 0.2)
        network.schedule_input(20, "n", "in", "at-synapse-clock", 0.2)
        result = network.run()
        self.assertEqual(result.current_time_ns, 20)
        self.assertEqual(result.processed_events, 1)

    def test_idle_rstdp_network_needs_no_context(self):
        network = NetworkRuntime({"n": NeuronRuntime({"in": 0.5}, learning_rule="rstdp")})
        self.assertEqual(network.run().processed_events, 0)

    def test_same_timestamp_inputs_are_one_batch(self):
        network = NetworkRuntime({"n": NeuronRuntime({"in": 0.5}, learning_rule="stdp")})
        network.schedule_input(0, "n", "in", "a", 0.2)
        network.schedule_input(0, "n", "in", "b", 0.3)
        result = network.run()
        self.assertEqual((result.processed_events, result.processed_batches), (2, 1))
        self.assertEqual(network.synapse_states("n")["in"].pre_trace, 2.0)

    def test_spikes_propagate_across_multiple_delayed_neurons(self):
        network = make_network()
        network.schedule_input(0, "source", "external", "pulse", 1.0)
        result = network.run()
        self.assertEqual([(s.neuron_id, s.time_ns) for s in result.spikes],
                         [("source", 0), ("middle", 5), ("target", 12)])
        self.assertEqual(result.pending_events, 0)

    def test_external_input_cannot_share_connection_owned_synapse(self):
        network = make_network()
        with self.assertRaises(NetworkRuntimeError):
            network.schedule_input(0, "middle", "from-source", "external", 1.0)

    def test_run_budget_never_splits_same_time_batch(self):
        network = make_network(max_events_per_run=1)
        network.schedule_input(0, "source", "external", "a", 0.2)
        network.schedule_input(0, "source", "external", "b", 0.2)
        result = network.run()
        self.assertTrue(result.budget_exhausted)
        self.assertEqual(result.processed_events, 0)
        self.assertEqual(result.pending_events, 2)

    def test_fanout_backpressure_does_not_commit_source_spike(self):
        neurons = {"s": NeuronRuntime({"ext": 1.0}),
                   "a": NeuronRuntime({"in": 1.0}), "b": NeuronRuntime({"in": 1.0})}
        links = [NetworkConnection("a", "s", "a", "in", 1),
                 NetworkConnection("b", "s", "b", "in", 1)]
        network = NetworkRuntime(neurons, links, max_pending_events=1)
        network.schedule_input(0, "s", "ext", "pulse", 1.0)
        with self.assertRaises(NetworkBackpressureError):
            network.run()
        self.assertEqual(network.neuron_states["s"].spike_sequence, 0)
        self.assertEqual(network.pending_events, 1)

    def test_recurrent_topology_is_bounded_by_run_budget(self):
        # Disable threshold adaptation so this test isolates scheduler budgeting.
        fixed_threshold = NeuronParams(delta_a_spike=0.0)
        neurons = {
            "a": NeuronRuntime({"ext": 1.0, "from-b": 1.0}, neuron_params=fixed_threshold),
            "b": NeuronRuntime({"from-a": 1.0}, neuron_params=fixed_threshold),
        }
        links = [NetworkConnection("a-b", "a", "b", "from-a", 3_000_000),
                 NetworkConnection("b-a", "b", "a", "from-b", 3_000_000)]
        network = NetworkRuntime(neurons, links, max_pending_events=8, max_events_per_run=3)
        network.schedule_input(0, "a", "ext", "start", 1.0)
        result = network.run()
        self.assertTrue(result.budget_exhausted)
        self.assertEqual(result.processed_events, 3)
        self.assertGreater(result.pending_events, 0)

    def test_failed_timestamp_does_not_mutate_nodes_or_drop_input(self):
        network = NetworkRuntime(
            {"s": NeuronRuntime({"ext": 1.0}), "t": NeuronRuntime({"in": 1.0})},
            [NetworkConnection("route", "s", "t", "in", 1)],
        )
        network.schedule_input((1 << 64) - 1, "s", "ext", "last-time", 1.0)
        source_before = network.neuron_states["s"]
        target_before = network.neuron_states["t"]
        source_synapse_before = network.synapse_states("s")["ext"]
        clock_before = network.current_time_ns
        with self.assertRaises(NetworkRuntimeError):
            network.run()
        self.assertEqual(network.neuron_states["s"], source_before)
        self.assertEqual(network.neuron_states["t"], target_before)
        self.assertEqual(network.synapse_states("s")["ext"], source_synapse_before)
        self.assertEqual(network.current_time_ns, clock_before)
        self.assertEqual(network.pending_events, 1)

    def test_feature_sidecar_modulates_real_network_input_and_updates_memory(self):
        from tools.neuron_runtime.neuron_features import NeuronFeatureState

        network = NetworkRuntime(
            {"n": NeuronRuntime({"in": 1.0})},
            feature_states={"n": NeuronFeatureState(sensitivity=2.0)},
        )
        network.schedule_input(0, "n", "in", "feature-pulse", 0.60)
        result = network.run()

        self.assertEqual(result.spikes[0].neuron_id, "n")
        self.assertEqual(network.feature_states["n"].observations, 1)
        self.assertGreater(network.feature_states["n"].memory_trace, 0.0)
        self.assertGreater(network.synaptic_memories["n"]["in"].coactivation_trace, 0.0)

    def test_feature_counter_exhaustion_rolls_back_the_whole_timestamp(self):
        from tools.neuron_runtime.neuron_features import NeuronFeatureState

        initial = NeuronFeatureState(observations=(1 << 64) - 1)
        network = NetworkRuntime(
            {"n": NeuronRuntime({"in": 1.0})},
            feature_states={"n": initial},
        )
        network.schedule_input(0, "n", "in", "overflow-feature-counter", 1.0)
        neuron_before = network.neuron_states["n"]

        with self.assertRaises(NetworkRuntimeError):
            network.run()

        self.assertEqual(network.neuron_states["n"], neuron_before)
        self.assertEqual(network.feature_states["n"], initial)
        self.assertEqual(network.pending_events, 1)

    def test_feature_sidecar_can_inhibit_a_real_network_input(self):
        from tools.neuron_runtime.neuron_features import NeuronFeatureState

        network = NetworkRuntime(
            {"n": NeuronRuntime({"in": 1.0})},
            feature_states={"n": NeuronFeatureState(inhibition=0.75)},
        )
        network.schedule_input(0, "n", "in", "inhibited-pulse", 1.0)
        result = network.run()

        self.assertEqual(result.spikes, ())
        self.assertEqual(network.feature_states["n"].observations, 1)

    def test_caller_runtime_is_not_mutated_by_network(self):
        source = NeuronRuntime({"ext": 1.0})
        network = NetworkRuntime({"source": source})
        network.schedule_input(0, "source", "ext", "pulse", 1.0)
        network.run()
        self.assertEqual(source.neuron_state.spike_sequence, 0)
        self.assertEqual(network.neuron_states["source"].spike_sequence, 1)

    def test_network_order_is_deterministic_for_permuted_inputs(self):
        def run(ids):
            network = make_network()
            for event_id, value in ids:
                network.schedule_input(0, "source", "external", event_id, value)
            result = network.run()
            return result, network.neuron_states
        events = [("a", 0.2), ("b", 0.3), ("c", 0.4)]
        self.assertEqual(run(events), run(list(reversed(events))))


if __name__ == "__main__":
    unittest.main(verbosity=2)
