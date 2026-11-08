import unittest

from tools.numerical_oracle.jarvis_oracle import (
    NeuronParams, NeuronState, OracleInputError, SynapseParams,
)
from tools.super_neuron_btcc.credit_oracle import (
    BTCCEvent, audit_episode, compare_credit_to_finite_difference,
    central_difference_weight_gradient, replay_episode,
)

MS = 1_000_000
TEMPERATURE = 0.25


def simple_events():
    return (
        BTCCEvent("pulse-1", "synapse-a", 0, 0.2),
        BTCCEvent("pulse-2", "synapse-a", 5 * MS, 0.2),
        BTCCEvent("pulse-3", "synapse-a", 10 * MS, 0.2),
    )


def common():
    return (
        {"synapse-a": 0.5},
        NeuronState(),
        NeuronParams(),
        SynapseParams(),
    )


class BTCCReplayTests(unittest.TestCase):
    def test_replay_is_deterministic_and_input_order_independent(self):
        weights, state, neuron, synapse = common()
        events = simple_events()
        forward = replay_episode(
            events, weights, state, neuron, synapse, 1,
            temperature=TEMPERATURE,
        )
        reverse = replay_episode(
            tuple(reversed(events)), weights, state, neuron, synapse, 1,
            temperature=TEMPERATURE,
        )
        self.assertEqual(forward, reverse)
        self.assertEqual(forward.processed_event_count, 3)

    def test_simultaneous_batch_is_deterministic(self):
        weights = {"synapse-a": 0.5, "synapse-b": 0.5}
        state, neuron, synapse = NeuronState(), NeuronParams(), SynapseParams()
        events = (
            BTCCEvent("input-b", "synapse-b", 0, 0.2),
            BTCCEvent("input-a", "synapse-a", 0, 0.2),
        )
        one = replay_episode(events, weights, state, neuron, synapse, 1)
        two = replay_episode(tuple(reversed(events)), weights, state, neuron, synapse, 1)
        self.assertEqual(one, two)

    def test_single_event_synapse_can_be_removed_without_stale_weight_key(self):
        weights = {"synapse-a": 0.5, "synapse-b": 0.5}
        state, neuron, synapse = NeuronState(), NeuronParams(), SynapseParams()
        events = (
            BTCCEvent("only-a", "synapse-a", 0, 0.2),
            BTCCEvent("only-b", "synapse-b", 5 * MS, 0.2),
        )
        audit = audit_episode(
            events, weights, state, neuron, synapse, 1,
            mode="always", temperature=TEMPERATURE,
        )
        self.assertEqual(len(audit.event_credits), 2)
        self.assertEqual(
            {item.event_id for item in audit.event_credits},
            {"only-a", "only-b"},
        )

    def test_leave_one_out_credit_is_positive_for_helpful_fast_events(self):
        weights, state, neuron, synapse = common()
        audit = audit_episode(
            simple_events(), weights, state, neuron, synapse, 1,
            mode="always", temperature=TEMPERATURE,
        )
        self.assertTrue(audit.audited)
        self.assertFalse(audit.baseline.predicted_fast)
        self.assertTrue(all(item.delta_loss > 0.0 for item in audit.event_credits))

    def test_leave_one_out_credit_is_negative_for_harmful_slow_events(self):
        weights, state, neuron, synapse = common()
        audit = audit_episode(
            simple_events(), weights, state, neuron, synapse, 0,
            mode="always", temperature=TEMPERATURE,
        )
        self.assertTrue(audit.audited)
        self.assertTrue(all(item.delta_loss < 0.0 for item in audit.event_credits))

    def test_btcc_descent_direction_matches_central_difference_for_fast_target(self):
        weights, state, neuron, synapse = common()
        events = simple_events()
        audit = audit_episode(
            events, weights, state, neuron, synapse, 1,
            mode="always", temperature=TEMPERATURE,
        )
        comparison = compare_credit_to_finite_difference(
            audit, events, weights, "synapse-a", state, neuron, synapse, 1,
            temperature=TEMPERATURE,
        )
        self.assertLess(comparison.central_difference_gradient, 0.0)
        self.assertGreater(comparison.estimated_descent_direction, 0.0)
        self.assertTrue(comparison.sign_aligned)

    def test_btcc_descent_direction_matches_central_difference_for_slow_target(self):
        weights, state, neuron, synapse = common()
        events = simple_events()
        audit = audit_episode(
            events, weights, state, neuron, synapse, 0,
            mode="always", temperature=TEMPERATURE,
        )
        comparison = compare_credit_to_finite_difference(
            audit, events, weights, "synapse-a", state, neuron, synapse, 0,
            temperature=TEMPERATURE,
        )
        self.assertGreater(comparison.central_difference_gradient, 0.0)
        self.assertLess(comparison.estimated_descent_direction, 0.0)
        self.assertTrue(comparison.sign_aligned)

    def test_gated_mode_skips_confident_correct_episode(self):
        weights, state, neuron, synapse = common()
        audit = audit_episode(
            simple_events(), weights, state, neuron, synapse, 0,
            mode="gated", uncertainty_band=0.1,
            temperature=TEMPERATURE,
        )
        self.assertFalse(audit.audited)
        self.assertEqual(audit.trigger_reason, "confident_correct")
        self.assertEqual(audit.event_credits, ())

    def test_gated_mode_audits_wrong_prediction(self):
        weights, state, neuron, synapse = common()
        audit = audit_episode(
            simple_events(), weights, state, neuron, synapse, 1,
            mode="gated", uncertainty_band=0.0,
            temperature=TEMPERATURE,
        )
        self.assertTrue(audit.audited)
        self.assertEqual(audit.trigger_reason, "prediction_error")
        self.assertEqual(len(audit.event_credits), 3)

    def test_gated_mode_audits_correct_near_boundary_prediction(self):
        events = (
            BTCCEvent("pulse-1", "synapse-a", 0, 0.7),
            BTCCEvent("pulse-2", "synapse-a", 5 * MS, 0.7),
            BTCCEvent("pulse-3", "synapse-a", 10 * MS, 0.7),
        )
        weights = {"synapse-a": 0.7}
        state, neuron, synapse = NeuronState(), NeuronParams(), SynapseParams()
        baseline = replay_episode(
            events, weights, state, neuron, synapse, 1,
            temperature=TEMPERATURE,
        )
        self.assertTrue(baseline.predicted_fast)
        self.assertLessEqual(abs(baseline.decision_margin), 0.25)
        audit = audit_episode(
            events, weights, state, neuron, synapse, 1,
            mode="gated", uncertainty_band=0.25,
            temperature=TEMPERATURE,
        )
        self.assertTrue(audit.audited)
        self.assertTrue(audit.baseline.correct)
        self.assertEqual(audit.trigger_reason, "near_decision_boundary")

    def test_audit_does_not_mutate_inputs_or_repeat_differently(self):
        weights, state, neuron, synapse = common()
        events = simple_events()
        before_weights = dict(weights)
        first = audit_episode(
            events, weights, state, neuron, synapse, 1,
            mode="always", temperature=TEMPERATURE,
        )
        second = audit_episode(
            events, weights, state, neuron, synapse, 1,
            mode="always", temperature=TEMPERATURE,
        )
        self.assertEqual(first, second)
        self.assertEqual(weights, before_weights)
        self.assertEqual(state, NeuronState())

    def test_redundant_independent_events_expose_leave_one_out_blind_spot(self):
        weights = {"synapse-a": 0.5}
        state, neuron, synapse = NeuronState(), NeuronParams(), SynapseParams()
        events = (
            BTCCEvent("early", "synapse-a", 0, 1.0),
            BTCCEvent("late", "synapse-a", 2_000 * MS, 1.0),
        )
        audit = audit_episode(
            events, weights, state, neuron, synapse, 1,
            mode="always", temperature=TEMPERATURE,
        )
        self.assertTrue(audit.audited)
        self.assertTrue(all(abs(item.delta_loss) < 1e-12 for item in audit.event_credits))
        gradient = central_difference_weight_gradient(
            events, weights, "synapse-a", state, neuron, synapse, 1,
            temperature=TEMPERATURE,
        )
        self.assertLess(gradient, 0.0)
        self.assertEqual(audit.synapse_directions[0].estimated_descent_direction, 0.0)

    def test_rejects_duplicate_ids_missing_weights_and_bad_targets(self):
        weights, state, neuron, synapse = common()
        events = simple_events()
        duplicate = (events[0], BTCCEvent("pulse-1", "synapse-a", 1, 0.1))
        with self.assertRaises(OracleInputError):
            replay_episode(duplicate, weights, state, neuron, synapse, 1)
        with self.assertRaises(OracleInputError):
            replay_episode(events, {}, state, neuron, synapse, 1)
        with self.assertRaises(OracleInputError):
            replay_episode(events, weights, state, neuron, synapse, 2)

    def test_rejects_event_after_deadline_and_unbounded_episode(self):
        weights, state, neuron, synapse = common()
        events = simple_events()
        with self.assertRaises(OracleInputError):
            replay_episode(
                events, weights, state, neuron, synapse, 1,
                deadline_ns=9 * MS,
            )
        with self.assertRaises(OracleInputError):
            audit_episode(
                events + (BTCCEvent("pulse-4", "synapse-a", 15 * MS, 0.2),),
                weights, state, neuron, synapse, 1, max_events=3,
            )
        with self.assertRaises(OracleInputError):
            replay_episode(
                events, weights, state, neuron, synapse, 1, max_events=65,
            )

    def test_decision_deadline_processes_retained_refractory_input(self):
        events = (
            BTCCEvent("first-spike", "synapse-a", 0, 1.0),
            BTCCEvent("refractory-input", "synapse-a", 1 * MS, 1.0, 1.0),
        )
        result = replay_episode(
            events, {"synapse-a": 1.0}, NeuronState(),
            NeuronParams(), SynapseParams(), 1,
            deadline_ns=2 * MS, temperature=TEMPERATURE,
        )
        self.assertTrue(result.predicted_fast)
        self.assertEqual(result.first_spike_time_ns, 2 * MS)
        self.assertEqual(result.margin_evaluation_time_ns, 2 * MS)
        self.assertEqual(result.processed_event_count, 2)

    def test_empty_episode_evaluates_margin_at_deadline(self):
        import math
        neuron = NeuronParams(tau_a_ns=5 * MS)
        result = replay_episode(
            (), {}, NeuronState(a=1.0), neuron, SynapseParams(), 0,
            deadline_ns=20 * MS, temperature=TEMPERATURE,
        )
        self.assertFalse(result.predicted_fast)
        self.assertEqual(result.margin_evaluation_time_ns, 20 * MS)
        self.assertAlmostEqual(
            result.decision_margin,
            -1.0 - 0.5 * math.exp(-4.0),
            places=12,
        )

    def test_finite_difference_rejects_boundary_crossing_step(self):
        weights, state, neuron, synapse = common()
        with self.assertRaises(OracleInputError):
            central_difference_weight_gradient(
                simple_events(), {"synapse-a": 0.0}, "synapse-a",
                state, neuron, synapse, 1, step_size=1e-4,
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
