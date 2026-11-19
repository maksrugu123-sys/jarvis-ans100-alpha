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

    def test_sensitivity_amplifies_input_with_a_hard_unit_ceiling(self):
        from tools.neuron_runtime.neuron_features import NeuronFeatureState

        baseline = NetworkRuntime({"n": NeuronRuntime({"in": 1.0})})
        amplified = NetworkRuntime(
            {"n": NeuronRuntime({"in": 1.0})},
            feature_states={"n": NeuronFeatureState(sensitivity=2.0)},
        )
        baseline.schedule_input(0, "n", "in", "baseline", 0.60)
        amplified.schedule_input(0, "n", "in", "amplified", 0.60)

        self.assertEqual(baseline.run().spikes, ())
        self.assertEqual(len(amplified.run().spikes), 1)

    def test_adaptive_sensitivity_uses_negative_feedback(self):
        from tools.neuron_runtime.neuron_features import (
            NeuronFeatureInput, NeuronFeatureState, update_neuron_features,
        )

        low_activity = update_neuron_features(
            NeuronFeatureState(),
            NeuronFeatureInput(activity=0.0, predicted_activity=0.0),
        )
        high_activity = update_neuron_features(
            NeuronFeatureState(),
            NeuronFeatureInput(activity=1.0, predicted_activity=1.0),
        )

        self.assertGreater(low_activity.sensitivity, 1.0)
        self.assertLess(high_activity.sensitivity, 1.0)

    def test_meta_plasticity_sidecar_reaches_stdp_runtime(self):
        from unittest.mock import patch
        from tools.neuron_runtime.neuron_features import NeuronFeatureState
        from tools.numerical_oracle.jarvis_oracle import update_stdp

        runtime = NeuronRuntime({"in": 0.5}, learning_rule="stdp")
        network = NetworkRuntime(
            {"n": runtime},
            feature_states={"n": NeuronFeatureState(plasticity=1.0)},
        )
        network.schedule_input(0, "n", "in", "meta-plasticity", 0.4)
        with patch("tools.neuron_runtime.runtime.update_stdp", wraps=update_stdp) as update:
            network.run()

        effective_params = update.call_args.args[1]
        self.assertAlmostEqual(
            effective_params.eta_plus, runtime.synapse_params.eta_plus * 1.5,
        )

    def test_sensitivity_rejects_boolean_and_non_finite_values(self):
        from tools.neuron_runtime.neuron_features import NeuronFeatureError, NeuronFeatureState

        for value in (True, float("nan"), float("inf")):
            with self.subTest(value=value):
                with self.assertRaises(NeuronFeatureError):
                    NeuronFeatureState(sensitivity=value).validate()

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


    def test_prior_synaptic_memory_facilitates_later_activation(self):
        from unittest.mock import patch
        from tools.neuron_runtime.neuron_features import (
            NeuronFeatureState, SynapticMemoryState,
        )
        from tools.numerical_oracle.jarvis_oracle import process_neuron_batch

        network = NetworkRuntime(
            {"n": NeuronRuntime({"in": 1.0})},
            feature_states={"n": NeuronFeatureState()},
            synaptic_memories={"n": {"in": SynapticMemoryState(
                coactivation_trace=1.0, predictive_trace=1.0, reward_trace=1.0,
            )}},
        )
        network.schedule_input(0, "n", "in", "remembered-input", 0.5)
        with patch(
            "tools.neuron_runtime.runtime.process_neuron_batch",
            wraps=process_neuron_batch,
        ) as process:
            network.run()

        delivered = process.call_args.args[2][0].strength
        self.assertAlmostEqual(delivered, 0.725)

    def test_absent_synaptic_memory_preserves_baseline_input_strength(self):
        from unittest.mock import patch
        from tools.numerical_oracle.jarvis_oracle import process_neuron_batch

        network = NetworkRuntime({"n": NeuronRuntime({"in": 1.0})})
        network.schedule_input(0, "n", "in", "baseline-input", 0.5)
        with patch(
            "tools.neuron_runtime.runtime.process_neuron_batch",
            wraps=process_neuron_batch,
        ) as process:
            network.run()

        delivered = process.call_args.args[2][0].strength
        self.assertEqual(delivered, 0.5)

    def test_synaptic_memory_gain_is_bounded_and_validated(self):
        from tools.neuron_runtime.neuron_features import (
            NeuronFeatureError, SynapticMemoryState, effective_synaptic_memory_gain,
        )

        maximum = effective_synaptic_memory_gain(SynapticMemoryState(
            coactivation_trace=1.0, predictive_trace=1.0, reward_trace=1.0,
        ))
        self.assertEqual(maximum, 1.45)
        with self.assertRaises(NeuronFeatureError):
            effective_synaptic_memory_gain(SynapticMemoryState(coactivation_trace=2.0))



    def test_inhibitory_connection_has_no_excitatory_synapse_target(self):
        route = NetworkConnection(
            "inh", "source", "target", None, 5, 0.8, 0.0, "inhibitory",
        )
        route.validate()
        with self.assertRaises(NetworkRuntimeError):
            NetworkConnection(
                "bad-inh", "source", "target", "input", 5, 0.8, 0.0, "inhibitory",
            ).validate()
        with self.assertRaises(NetworkRuntimeError):
            NetworkConnection(
                "bad-kind", "source", "target", None, 5, 0.8, 0.0, "unknown",
            ).validate()

    def test_inhibitory_route_suppresses_same_timestamp_excitation(self):
        neurons = {
            "source": NeuronRuntime({"external": 1.0}),
            "target": NeuronRuntime({"input": 1.0}),
        }
        route = NetworkConnection(
            "source-target-inhibition", "source", "target", None,
            5, 1.0, 0.0, "inhibitory",
        )
        network = NetworkRuntime(neurons, [route])
        network.schedule_input(0, "source", "external", "source-pulse", 1.0)
        network.schedule_input(5, "target", "input", "target-excitation", 1.0)

        result = network.run()

        self.assertEqual([(spike.neuron_id, spike.time_ns) for spike in result.spikes],
                         [("source", 0)])
        self.assertGreater(network.feature_states["target"].inhibition, 0.0)
        self.assertEqual(network.pending_events, 0)

    def test_inhibitory_route_creates_feature_state_without_prior_sidecar(self):
        network = NetworkRuntime(
            {
                "source": NeuronRuntime({"external": 1.0}),
                "target": NeuronRuntime({"input": 1.0}),
            },
            [NetworkConnection(
                "inhibit", "source", "target", None, 2, 0.6, 0.0, "inhibitory",
            )],
        )
        network.schedule_input(0, "source", "external", "source", 1.0)
        network.run()

        self.assertEqual(network.feature_states["target"].observations, 1)
        self.assertGreater(network.feature_states["target"].inhibition, 0.0)

    def test_pending_inhibitory_event_survives_runtime_checkpoint_roundtrip(self):
        network = NetworkRuntime(
            {
                "source": NeuronRuntime({"external": 1.0}),
                "target": NeuronRuntime({"input": 1.0}),
            },
            [NetworkConnection(
                "inhibit", "source", "target", None, 2, 0.6, 0.0, "inhibitory",
            )],
        )
        network.schedule_input(0, "source", "external", "source", 1.0)
        first = network.run(max_timestamps=1)
        self.assertEqual(first.pending_events, 1)
        state = network.export_checkpoint_state()
        restored = NetworkRuntime.restore_checkpoint_state(
            state["neurons"], state["connections"], state["pending_events"],
            max_pending_events=state["max_pending_events"],
            max_events_per_run=state["max_events_per_run"],
            current_time_ns=state["current_time_ns"],
            has_processed=state["has_processed"],
            feature_states=state["feature_states"],
            synaptic_memories=state["synaptic_memories"],
        )
        self.assertEqual(restored.pending_events, 1)
        result = restored.run()
        self.assertEqual(result.pending_events, 0)
        self.assertGreater(restored.feature_states["target"].inhibition, 0.0)



    def test_failed_inhibitory_timestamp_rolls_back_signal_and_feature_state(self):
        from tools.neuron_runtime.neuron_features import NeuronFeatureState

        exhausted = NeuronFeatureState(observations=(1 << 64) - 1)
        network = NetworkRuntime(
            {
                "source": NeuronRuntime({"external": 1.0}),
                "target": NeuronRuntime({"input": 1.0}),
            },
            [NetworkConnection(
                "inhibit", "source", "target", None, 2, 0.8, 0.0, "inhibitory",
            )],
            feature_states={"target": exhausted},
        )
        network.schedule_input(0, "source", "external", "source", 1.0)
        network.run(max_timestamps=1)
        before = network.neuron_states["target"]
        clock = network.current_time_ns

        with self.assertRaises(NetworkRuntimeError):
            network.run()

        self.assertEqual(network.neuron_states["target"], before)
        self.assertEqual(network.feature_states["target"], exhausted)
        self.assertEqual(network.current_time_ns, clock)
        self.assertEqual(network.pending_events, 1)





class PopulationRegistryTests(unittest.TestCase):
    def test_registry_groups_specialized_roles(self):
        from tools.neuron_runtime.neuron_features import NeuronRole
        from tools.neuron_runtime.population import NeuronPopulationRegistry
        registry = NeuronPopulationRegistry(("m", "p", "a"), {"m": "memory", "p": "pattern", "a": "attention"})
        self.assertEqual(registry.members(NeuronRole.MEMORY), ("m",))
        self.assertEqual(registry.members("pattern"), ("p",))
        self.assertEqual(registry.role_for("a"), NeuronRole.ATTENTION)

    def test_excitatory_and_inhibitory_roles_constrain_routes(self):
        from tools.neuron_runtime.neuron_features import NeuronRole
        neurons = {"s": NeuronRuntime({"ext": 1.0}), "t": NeuronRuntime({"in": 1.0})}
        with self.assertRaises(NetworkRuntimeError):
            NetworkRuntime(neurons, [NetworkConnection("bad", "s", "t", None, 1, signal_type="inhibitory")], population_roles={"s": NeuronRole.EXCITATORY})
        with self.assertRaises(NetworkRuntimeError):
            NetworkRuntime(neurons, [NetworkConnection("bad", "s", "t", "in", 1)], population_roles={"s": NeuronRole.INHIBITORY})

    def test_contradiction_role_requires_inhibitory_route(self):
        from tools.neuron_runtime.neuron_features import NeuronRole
        neurons = {"c": NeuronRuntime({"ext": 1.0}), "t": NeuronRuntime({"in": 1.0})}
        with self.assertRaises(NetworkRuntimeError):
            NetworkRuntime(neurons, population_roles={"c": NeuronRole.CONTRADICTION})
        network = NetworkRuntime(neurons, [NetworkConnection("s", "c", "t", None, 1, signal_type="inhibitory")], population_roles={"c": NeuronRole.CONTRADICTION})
        self.assertEqual(network.population_registry.role_for("c"), NeuronRole.CONTRADICTION)

    def test_attention_role_propagates_salience(self):
        from tools.neuron_runtime.neuron_features import NeuronRole
        neurons = {"a": NeuronRuntime({"ext": 1.0}), "t": NeuronRuntime({"in": 1.0})}
        network = NetworkRuntime(neurons, [NetworkConnection("focus", "a", "t", "in", 1, strength=0.4, attention=0.1)], population_roles={"a": NeuronRole.ATTENTION})
        network.schedule_input(0, "a", "ext", "start", 1.0)
        network.run(max_timestamps=1)
        pending = network.export_checkpoint_state()["pending_events"]
        self.assertEqual(pending[0].attention, 0.4)


    def test_memory_role_uses_cross_synaptic_association(self):
        from tools.neuron_runtime.neuron_features import NeuronRole, SynapticMemoryState
        neurons = {"memory": NeuronRuntime({"seen": 1.0, "cue": 1.0})}
        traces = {"memory": {
            "seen": SynapticMemoryState(coactivation_trace=1.0),
            "cue": SynapticMemoryState(),
        }}
        memory = NetworkRuntime(neurons, population_roles={"memory": NeuronRole.MEMORY},
                                synaptic_memories=traces)
        baseline = NetworkRuntime(neurons, population_roles={"memory": NeuronRole.COORDINATOR},
                                  synaptic_memories=traces)
        memory.schedule_input(1, "memory", "cue", "cue-memory", 0.5)
        baseline.schedule_input(1, "memory", "cue", "cue-baseline", 0.5)
        memory.run()
        baseline.run()
        self.assertGreater(
            memory.neuron_states["memory"].membrane_potential,
            baseline.neuron_states["memory"].membrane_potential,
        )

    def test_pattern_role_recognizes_repeated_exact_coactivation(self):
        from tools.neuron_runtime.neuron_features import NeuronRole
        network = NetworkRuntime(
            {"pattern": NeuronRuntime({"left": 1.0, "right": 1.0})},
            population_roles={"pattern": NeuronRole.PATTERN},
        )
        network.schedule_input(1, "pattern", "left", "left-1", 0.4)
        network.schedule_input(1, "pattern", "right", "right-1", 0.4)
        network.run()
        first = network.feature_states["pattern"]
        self.assertNotEqual(first.pattern_signature, 0)
        self.assertEqual(first.pattern_observations, 1)
        self.assertGreater(first.pattern_trace, 0.0)

        network.schedule_input(2, "pattern", "left", "left-2", 0.4)
        network.schedule_input(2, "pattern", "right", "right-2", 0.4)
        network.run()
        second = network.feature_states["pattern"]
        self.assertEqual(second.pattern_signature, first.pattern_signature)
        self.assertEqual(second.pattern_observations, 2)
        self.assertGreater(second.pattern_trace, first.pattern_trace)

    def test_pattern_state_is_carried_by_runtime_checkpoint(self):
        from tools.neuron_runtime.neuron_features import NeuronRole
        network = NetworkRuntime(
            {"pattern": NeuronRuntime({"left": 1.0, "right": 1.0})},
            population_roles={"pattern": NeuronRole.PATTERN},
        )
        network.schedule_input(1, "pattern", "left", "left", 0.4)
        network.schedule_input(1, "pattern", "right", "right", 0.4)
        network.run()
        state = network.export_checkpoint_state()
        self.assertEqual(state["feature_states"]["pattern"].pattern_observations, 1)

    def test_runtime_checkpoint_carries_population_roles(self):
        from tools.neuron_runtime.neuron_features import NeuronRole
        network = NetworkRuntime({"n": NeuronRuntime({"in": 1.0})}, population_roles={"n": NeuronRole.MEMORY})
        state = network.export_checkpoint_state()
        self.assertEqual(state["population_roles"]["n"], NeuronRole.MEMORY)
if __name__ == "__main__":
    unittest.main(verbosity=2)
