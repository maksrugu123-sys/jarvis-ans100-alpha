"""Contract tests for the first executable JARVIS neuron coordinator."""
from __future__ import annotations

import unittest
from unittest.mock import patch

from tools.neuron_runtime.runtime import NeuronRuntime, NeuronRuntimeError, SynapticInput
from tools.numerical_oracle.jarvis_oracle import (
    InputSignal, NeuronState, OracleInputError, SynapseParams, SynapseState,
    process_neuron_batch, starter_neuron_params, starter_synapse_params, update_stdp,
)

MS = 1_000_000


class RuntimeConfigurationTests(unittest.TestCase):
    def test_rejects_unknown_learning_rule(self):
        with self.assertRaises(NeuronRuntimeError):
            NeuronRuntime({}, learning_rule="backprop")  # type: ignore[arg-type]

    def test_rejects_invalid_weight_and_inhibitory_parameter_range(self):
        with self.assertRaises(OracleInputError):
            NeuronRuntime({"input": 1.1})
        with self.assertRaises(NeuronRuntimeError):
            NeuronRuntime({"input": 0.5}, synapse_params=SynapseParams(w_min=-1.0))

    def test_synapse_mapping_is_defensive(self):
        runtime = NeuronRuntime({"input": 0.5})
        external = runtime.synapses
        external.clear()
        self.assertIn("input", runtime.synapses)


class RuntimeBatchTests(unittest.TestCase):
    def test_neuron_result_matches_oracle(self):
        params = starter_neuron_params()
        state = NeuronState()
        runtime = NeuronRuntime({"a": 0.5, "b": 0.7}, neuron_params=params)
        inputs = [
            SynapticInput("event-b", "b", 0.4, 0.2),
            SynapticInput("event-a", "a", 0.3, 0.8),
        ]
        expected = process_neuron_batch(state, 5 * MS, [
            InputSignal("event-b", 0.7, 0.4, 0.2),
            InputSignal("event-a", 0.5, 0.3, 0.8),
        ], params)
        actual = runtime.process_batch(5 * MS, inputs)
        self.assertEqual(actual.neuron, expected)
        self.assertEqual(runtime.neuron_state, expected.state)
        self.assertEqual(runtime.processed_batches, 1)

    def test_simultaneous_input_order_is_invariant(self):
        events = [
            SynapticInput("a-event", "a", 0.45, 0.3),
            SynapticInput("b-event", "b", 0.35, 0.8),
        ]
        left = NeuronRuntime({"a": 0.5, "b": 0.7})
        right = NeuronRuntime({"a": 0.5, "b": 0.7})
        self.assertEqual(left.process_batch(10 * MS, events),
                         right.process_batch(10 * MS, list(reversed(events))))
        self.assertEqual(left.synapses, right.synapses)

    def test_spike_is_reported_and_sequence_increments(self):
        runtime = NeuronRuntime({"drive": 1.0})
        result = runtime.process_batch(0, [SynapticInput("pulse", "drive", 1.0)])
        self.assertTrue(result.spike_emitted)
        self.assertEqual(result.spike_sequence, 1)
        self.assertEqual(runtime.neuron_state.u, starter_neuron_params().u_reset)

    def test_unknown_synapse_and_duplicate_ids_fail_without_mutation(self):
        runtime = NeuronRuntime({"a": 0.5})
        before = runtime.neuron_state
        with self.assertRaises(NeuronRuntimeError):
            runtime.process_batch(0, [SynapticInput("e", "missing", 0.5)])
        with self.assertRaises(NeuronRuntimeError):
            runtime.process_batch(0, [
                SynapticInput("same", "a", 0.2), SynapticInput("same", "a", 0.3)
            ])
        self.assertEqual(runtime.neuron_state, before)
        self.assertEqual(runtime.processed_batches, 0)

    def test_late_event_fails_without_mutation(self):
        runtime = NeuronRuntime({"a": 0.5})
        runtime.process_batch(10 * MS, [])
        before = runtime.neuron_state
        with self.assertRaises(OracleInputError):
            runtime.process_batch(9 * MS, [])
        self.assertEqual(runtime.neuron_state, before)
        self.assertEqual(runtime.processed_batches, 1)

    def test_multiple_pre_events_are_grouped_for_one_synapse(self):
        runtime = NeuronRuntime({"a": 0.5}, learning_rule="stdp")
        result = runtime.process_batch(0, [
            SynapticInput("one", "a", 0.2), SynapticInput("two", "a", 0.3)
        ])
        self.assertEqual(len(result.synapse_updates), 1)
        self.assertEqual(result.synapse_updates[0].n_pre, 2)
        self.assertEqual(runtime.synapses["a"].pre_trace, 2.0)

    def test_meta_plasticity_scales_stdp_rates_but_not_safety_caps(self):
        runtime = NeuronRuntime({"a": 0.5}, learning_rule="stdp")
        with patch("tools.neuron_runtime.runtime.update_stdp", wraps=update_stdp) as update:
            runtime.process_batch(
                0, [SynapticInput("pre", "a", 0.5)], plasticity_scale=1.5,
            )

        effective = update.call_args.args[1]
        self.assertAlmostEqual(effective.eta_plus, runtime.synapse_params.eta_plus * 1.5)
        self.assertAlmostEqual(effective.eta_minus, runtime.synapse_params.eta_minus * 1.5)
        self.assertEqual(effective.q_batch_max, runtime.synapse_params.q_batch_max)
        self.assertEqual(effective.delta_w_batch_max, runtime.synapse_params.delta_w_batch_max)

    def test_meta_plasticity_scales_rstdp_reward_rate(self):
        runtime = NeuronRuntime({"a": 0.5}, learning_rule="rstdp")
        runtime.process_batch(
            0, [SynapticInput("pre", "a", 0.5)],
            learning_context_id="episode",
        )
        with patch("tools.neuron_runtime.runtime.apply_reward", wraps=__import__(
            "tools.numerical_oracle.jarvis_oracle", fromlist=["apply_reward"]
        ).apply_reward) as apply:
            runtime.apply_reward(
                "a", 1, 1.0, 0.5, "episode", plasticity_scale=1.5,
            )

        effective_reward_params = apply.call_args.args[2]
        self.assertAlmostEqual(
            effective_reward_params.eta_r, runtime.reward_params.eta_r * 1.5,
        )
        self.assertEqual(
            effective_reward_params.delta_w_reward_max,
            runtime.reward_params.delta_w_reward_max,
        )

    def test_invalid_meta_plasticity_scale_does_not_mutate_runtime(self):
        runtime = NeuronRuntime({"a": 0.5}, learning_rule="stdp")
        before = runtime.neuron_state
        with self.assertRaises(NeuronRuntimeError):
            runtime.process_batch(
                0, [SynapticInput("pre", "a", 0.5)], plasticity_scale=float("nan"),
            )
        self.assertEqual(runtime.neuron_state, before)
        self.assertEqual(runtime.processed_batches, 0)

    def test_later_reward_can_advance_synapse_clock_past_membrane_clock(self):
        runtime = NeuronRuntime({"a": 0.5}, learning_rule="rstdp")
        runtime.process_batch(
            10 * MS, [SynapticInput("pre", "a", 0.5)],
            learning_context_id="episode-1",
        )
        runtime.apply_reward("a", 20 * MS, 1.0, 0.5, "episode-1")
        self.assertEqual(runtime.neuron_state.last_update_model_time_ns, 10 * MS)
        self.assertEqual(runtime.synapses["a"].trace_last_update_model_time_ns, 20 * MS)
        restored = NeuronRuntime(
            runtime.synapses,
            neuron_state=runtime.neuron_state,
            neuron_params=runtime.neuron_params,
            synapse_params=runtime.synapse_params,
            reward_params=runtime.reward_params,
            learning_rule=runtime.learning_rule,
            processed_batches=runtime.processed_batches,
        )
        self.assertEqual(restored.synapses, runtime.synapses)
        self.assertEqual(restored.neuron_state, runtime.neuron_state)
        restored.process_batch(30 * MS, [], learning_context_id="episode-1")

    def test_rstdp_requires_context_before_any_state_commit(self):
        runtime = NeuronRuntime({"a": 0.5}, learning_rule="rstdp")
        before = runtime.neuron_state
        with self.assertRaises(NeuronRuntimeError):
            runtime.process_batch(0, [SynapticInput("event", "a", 0.2)])
        self.assertEqual(runtime.neuron_state, before)
        self.assertEqual(runtime.processed_batches, 0)


class RuntimePlasticityTests(unittest.TestCase):
    def test_stdp_postsynaptic_event_updates_all_synapses(self):
        neuron_params = starter_neuron_params()
        synapse_params = starter_synapse_params()
        runtime = NeuronRuntime(
            {"a": 0.5, "b": 1.0},
            neuron_params=neuron_params,
            synapse_params=synapse_params,
            learning_rule="stdp",
        )
        first = runtime.process_batch(0, [SynapticInput("a-pre", "a", 0.4)])
        self.assertFalse(first.spike_emitted)
        second = runtime.process_batch(20 * MS, [SynapticInput("b-drive", "b", 1.0)])
        expected_state = process_neuron_batch(
            NeuronState(), 0, [InputSignal("a-pre", 0.5, 0.4)], neuron_params
        ).state
        a_first = update_stdp(SynapseState(weight=0.5), synapse_params, 0, 1, 0).state
        b_first = update_stdp(SynapseState(weight=1.0), synapse_params, 0, 0, 0).state
        neuron_step = process_neuron_batch(
            expected_state, 20 * MS, [InputSignal("b-drive", 1.0, 1.0)], neuron_params
        )
        a_expected = update_stdp(
            a_first, synapse_params, 20 * MS, 0, int(neuron_step.spike_emitted)
        ).state
        b_expected = update_stdp(
            b_first, synapse_params, 20 * MS, 1, int(neuron_step.spike_emitted)
        ).state
        self.assertTrue(second.spike_emitted)
        self.assertEqual(runtime.synapses["a"], a_expected)
        self.assertEqual(runtime.synapses["b"], b_expected)
        self.assertGreater(runtime.synapses["a"].weight, 0.5)

    def test_rstdp_weight_waits_until_reward(self):
        runtime = NeuronRuntime({"a": 0.5, "b": 1.0}, learning_rule="rstdp")
        runtime.process_batch(0, [SynapticInput("a-pre", "a", 0.4)],
                              learning_context_id="episode-1")
        step = runtime.process_batch(20 * MS, [SynapticInput("b-drive", "b", 1.0)],
                                     learning_context_id="episode-1")
        self.assertTrue(step.spike_emitted)
        self.assertEqual(runtime.synapses["a"].weight, 0.5)
        self.assertGreater(runtime.synapses["a"].eligibility_trace, 0.0)
        reward = runtime.apply_reward("a", 20 * MS, 1.0, 0.5, "episode-1")
        self.assertEqual(reward.result.status, "APPLIED")
        self.assertAlmostEqual(reward.result.baseline_after, 0.505)
        self.assertGreater(runtime.synapses["a"].weight, 0.5)
        self.assertEqual(runtime.synapses["a"].eligibility_trace, 0.0)
        self.assertIsNone(runtime.synapses["a"].eligibility_context_id)

    def test_wrong_reward_context_is_rejected_atomically(self):
        runtime = NeuronRuntime({"a": 0.5}, learning_rule="rstdp")
        runtime.process_batch(0, [SynapticInput("pre", "a", 0.5)],
                              learning_context_id="episode-a")
        before = runtime.synapses["a"]
        with self.assertRaises(OracleInputError):
            runtime.apply_reward("a", MS, 1.0, 0.5, "episode-b")
        self.assertEqual(runtime.synapses["a"], before)

    def test_reward_is_rejected_outside_rstdp_mode(self):
        runtime = NeuronRuntime({"a": 0.5}, learning_rule="stdp")
        with self.assertRaises(NeuronRuntimeError):
            runtime.apply_reward("a", 0, 1.0, 0.5, None)


if __name__ == "__main__":
    unittest.main(verbosity=2)
