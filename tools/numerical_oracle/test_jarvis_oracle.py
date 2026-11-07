import math
import unittest

from .jarvis_oracle import (
    InputSignal,
    NeuronParams,
    NeuronState,
    OracleInputError,
    RewardParams,
    SynapseParams,
    SynapseState,
    apply_reward,
    attention_gain,
    compare_numeric_vectors,
    float32_roundtrip,
    decay_neuron_state,
    effective_threshold,
    process_neuron_batch,
    update_rstdp,
    update_stdp,
)

MS = 1_000_000


class NeuronOracleTests(unittest.TestCase):
    def test_analytical_decay_matches_reference_exponential(self):
        p = NeuronParams()
        s = NeuronState(u=1.0, a=0.5)
        got = decay_neuron_state(s, 20 * MS, p)
        self.assertAlmostEqual(got.u, math.exp(-1.0), places=15)
        self.assertAlmostEqual(got.a, 0.5 * math.exp(-0.2), places=15)

    def test_attention_gain_is_bounded(self):
        p = NeuronParams()
        self.assertEqual(attention_gain(0.0, p), 1.0)
        self.assertEqual(attention_gain(1.0, p), 1.5)
        self.assertAlmostEqual(effective_threshold(NeuronState(a=1.0), p), 1.5)

    def test_grouped_input_emits_one_spike(self):
        p = NeuronParams()
        signals = [
            InputSignal("input-b", 1.0, 1.0, 0.0),
            InputSignal("input-a", 1.0, 1.0, 0.0),
        ]
        got = process_neuron_batch(NeuronState(), 0, signals, p)
        self.assertTrue(got.spike_emitted)
        self.assertEqual(got.state.spike_sequence, 1)
        self.assertEqual(got.state.u, p.u_reset)
        self.assertEqual(got.state.a, p.delta_a_spike)

    def test_same_timestamp_order_is_deterministic(self):
        p = NeuronParams()
        s = NeuronState()
        a = [InputSignal("a", 0.4, 1.0, 0.3), InputSignal("b", 0.6, 1.0, 0.8)]
        b = list(reversed(a))
        one = process_neuron_batch(s, 10 * MS, a, p)
        two = process_neuron_batch(s, 10 * MS, b, p)
        self.assertEqual(one, two)

    def test_refractory_inputs_are_retained_and_checked_at_expiry(self):
        p = NeuronParams()
        first = process_neuron_batch(
            NeuronState(), 0, [InputSignal("first", 1.0, 1.0, 0.0)], p
        )
        self.assertTrue(first.spike_emitted)
        during = process_neuron_batch(
            first.state, 1 * MS, [InputSignal("during", 1.0, 1.0, 1.0)], p
        )
        self.assertFalse(during.spike_emitted)
        self.assertTrue(during.refractory_active)
        self.assertGreater(during.state.u, 1.0)
        expiry = process_neuron_batch(during.state, 2 * MS, [], p)
        self.assertTrue(expiry.spike_emitted)
        self.assertEqual(expiry.state.spike_sequence, 2)

    def test_late_event_is_rejected(self):
        p = NeuronParams()
        s = NeuronState(last_update_model_time_ns=10 * MS)
        with self.assertRaises(OracleInputError):
            process_neuron_batch(s, 9 * MS, [], p)

    def test_nan_and_out_of_range_attention_are_rejected(self):
        p = NeuronParams()
        with self.assertRaises(OracleInputError):
            process_neuron_batch(NeuronState(), 0, [InputSignal("x", 1.0, 1.0, math.nan)], p)
        with self.assertRaises(OracleInputError):
            attention_gain(1.1, p)

    def test_float32_comparison_helper_reports_error(self):
        reference = [0.1, math.exp(-1.0), 1.0]
        candidate = [float32_roundtrip(value) for value in reference]
        result = compare_numeric_vectors(reference, candidate, 1e-5, 1e-5)
        self.assertEqual(result.count, 3)
        self.assertTrue(result.within_tolerance)
        self.assertGreater(result.max_absolute_error, 0.0)

    def test_float32_comparison_detects_out_of_tolerance_value(self):
        result = compare_numeric_vectors([1.0, 0.0], [1.0, 0.01], 1e-5, 1e-5)
        self.assertFalse(result.within_tolerance)
        self.assertEqual(result.mismatched_indices, (1,))


class StdpOracleTests(unittest.TestCase):
    def test_pre_then_post_causes_ltp(self):
        p = SynapseParams()
        first = update_stdp(SynapseState(), p, 0, n_pre=1, n_post=0)
        second = update_stdp(first.state, p, 20 * MS, n_pre=0, n_post=1)
        expected_q = p.eta_plus * math.exp(-1.0) * (1.0 - 0.5)
        self.assertAlmostEqual(second.q_raw, expected_q, places=15)
        self.assertGreater(second.state.weight, 0.5)

    def test_post_then_pre_causes_ltd(self):
        p = SynapseParams()
        first = update_stdp(SynapseState(), p, 0, n_pre=0, n_post=1)
        second = update_stdp(first.state, p, 20 * MS, n_pre=1, n_post=0)
        expected_q = -p.eta_minus * math.exp(-0.5) * 0.5
        self.assertAlmostEqual(second.q_raw, expected_q, places=15)
        self.assertLess(second.state.weight, 0.5)

    def test_simultaneous_first_pair_has_no_artificial_order(self):
        p = SynapseParams()
        got = update_stdp(SynapseState(), p, 0, n_pre=1, n_post=1)
        self.assertEqual(got.q_raw, 0.0)
        self.assertEqual(got.state.weight, 0.5)
        self.assertEqual(got.state.pre_trace, 1.0)
        self.assertEqual(got.state.post_trace, 1.0)

    def test_trace_and_weight_limits_hold_under_large_batch(self):
        p = SynapseParams()
        got = update_stdp(SynapseState(weight=0.999), p, 0, n_pre=2**20, n_post=2**20)
        self.assertLessEqual(got.state.pre_trace, p.x_pre_max)
        self.assertLessEqual(got.state.post_trace, p.x_post_max)
        self.assertGreaterEqual(got.state.weight, p.w_min)
        self.assertLessEqual(got.state.weight, p.w_max)
        self.assertLessEqual(abs(got.weight_delta), p.delta_w_batch_max)

    def test_late_stdp_event_is_rejected(self):
        p = SynapseParams()
        s = SynapseState(trace_last_update_model_time_ns=5 * MS)
        with self.assertRaises(OracleInputError):
            update_stdp(s, p, 4 * MS, n_pre=1, n_post=0)


class RewardStdpOracleTests(unittest.TestCase):
    def test_rstdp_accumulates_eligibility_without_changing_weight(self):
        p = SynapseParams()
        rp = RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-1")
        second = update_rstdp(first.state, p, rp, 20 * MS, 0, 1, "episode-1")
        self.assertGreater(second.state.eligibility_trace, 0.0)
        self.assertEqual(second.state.weight, 0.5)
        self.assertEqual(second.weight_delta, 0.0)

    def test_reward_changes_weight_and_updates_task_baseline(self):
        p = SynapseParams()
        rp = RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-1")
        eligible = update_rstdp(first.state, p, rp, 20 * MS, 0, 1, "episode-1")
        result = apply_reward(
            eligible.state, p, rp, 20 * MS, reward_value=1.0,
            baseline_before=0.5, learning_context_id="episode-1",
        )
        self.assertAlmostEqual(result.modulator, 0.5)
        self.assertAlmostEqual(result.baseline_after, 0.505)
        self.assertGreater(result.state.weight, 0.5)
        self.assertEqual(result.status, "APPLIED")
        self.assertEqual(result.state.eligibility_trace, 0.0)
        self.assertIsNone(result.state.eligibility_context_id)

    def test_reward_with_zero_prediction_error_does_not_change_weight(self):
        p = SynapseParams()
        rp = RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-1")
        eligible = update_rstdp(first.state, p, rp, 20 * MS, 0, 1, "episode-1")
        result = apply_reward(
            eligible.state, p, rp, 20 * MS, reward_value=0.5,
            baseline_before=0.5, learning_context_id="episode-1",
        )
        self.assertEqual(result.modulator, 0.0)
        self.assertEqual(result.state.weight, 0.5)
        self.assertEqual(result.status, "APPLIED_NO_CHANGE")

    def test_context_cannot_be_overwritten(self):
        p = SynapseParams()
        rp = RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        with self.assertRaises(OracleInputError):
            update_rstdp(first.state, p, rp, 1 * MS, 0, 1, "episode-b")

    def test_eligibility_expires_after_maximum_age(self):
        p = SynapseParams()
        rp = RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        # At the exact configured age it is still eligible; one nanosecond later it expires.
        at_limit = update_rstdp(first.state, p, rp, rp.max_eligibility_age_ns, 0, 0, "episode-a")
        self.assertEqual(at_limit.state.eligibility_context_id, "episode-a")
        expired = update_rstdp(at_limit.state, p, rp, rp.max_eligibility_age_ns + 1, 0, 0, "episode-a")
        self.assertIsNone(expired.state.eligibility_context_id)
        self.assertEqual(expired.state.eligibility_trace, 0.0)

    def test_reward_for_wrong_context_is_rejected(self):
        p = SynapseParams()
        rp = RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        with self.assertRaises(OracleInputError):
            apply_reward(first.state, p, rp, 0, 1.0, 0.5, "episode-b")

    def test_expired_reward_is_a_noop_but_updates_baseline(self):
        p = SynapseParams()
        rp = RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        result = apply_reward(
            first.state, p, rp, rp.max_eligibility_age_ns + 1,
            reward_value=1.0, baseline_before=0.5, learning_context_id="episode-a",
        )
        self.assertEqual(result.status, "EXPIRED")
        self.assertEqual(result.weight_delta, 0.0)
        self.assertEqual(result.state.weight, 0.5)
        self.assertAlmostEqual(result.baseline_after, 0.505)

    def test_expired_context_cannot_be_reopened_and_new_context_starts_clean(self):
        p = SynapseParams()
        rp = RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        with self.assertRaises(OracleInputError):
            update_rstdp(
                first.state, p, rp, rp.max_eligibility_age_ns + 1,
                0, 1, "episode-a",
            )
        second = update_rstdp(
            first.state, p, rp, rp.max_eligibility_age_ns + 1,
            1, 0, "episode-b",
        )
        self.assertEqual(second.state.eligibility_context_id, "episode-b")
        self.assertEqual(second.q_raw, 0.0)
        self.assertEqual(second.state.pre_trace, 1.0)
        self.assertEqual(second.state.post_trace, 0.0)

    def test_invalid_reward_is_rejected(self):
        p = SynapseParams()
        rp = RewardParams()
        with self.assertRaises(OracleInputError):
            apply_reward(SynapseState(), p, rp, 0, reward_value=1.01, baseline_before=0.5, learning_context_id=None)


if __name__ == "__main__":
    unittest.main(verbosity=2)
