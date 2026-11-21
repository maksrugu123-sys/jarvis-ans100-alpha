import math
import unittest

from .calibration_sweeps import run_sweeps
from .jarvis_oracle import (
    InputSignal, NeuronParams, NeuronState, OracleInputError, RewardParams,
    SynapseParams, SynapseState, apply_reward, attention_gain,
    compare_numeric_vectors, float32_roundtrip, decay_neuron_state,
    effective_threshold, process_neuron_batch, update_rstdp, update_stdp,
)

MS = 1_000_000


class NeuronOracleTests(unittest.TestCase):
    def test_analytical_decay_matches_reference_exponential(self):
        p = NeuronParams()
        got = decay_neuron_state(NeuronState(u=1.0, a=0.5), 20 * MS, p)
        self.assertAlmostEqual(got.u, 0.36787944117144233, places=15)
        self.assertAlmostEqual(got.a, 0.4093653765389909, places=15)

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

    def test_lif_multi_event_golden_trajectory(self):
        p = NeuronParams()
        state = NeuronState()
        trajectory = []
        for time_ns, signals in (
            (0, [InputSignal("A", 0.4, 1.0)]),
            (10 * MS, []),
            (20 * MS, [InputSignal("B", 0.3, 1.0)]),
            (40 * MS, []),
        ):
            step = process_neuron_batch(state, time_ns, signals, p)
            state = step.state
            trajectory.append(state.u)
        expected = (
            0.4,
            0.2426122638850534,
            0.44715177646857696,
            0.16449794564607778,
        )
        for actual, reference in zip(trajectory, expected):
            self.assertAlmostEqual(actual, reference, places=15)

    def test_same_timestamp_order_is_deterministic(self):
        p = NeuronParams()
        state = NeuronState()
        inputs = [InputSignal("a", 0.4, 1.0, 0.3), InputSignal("b", 0.6, 1.0, 0.8)]
        self.assertEqual(
            process_neuron_batch(state, 10 * MS, inputs, p),
            process_neuron_batch(state, 10 * MS, list(reversed(inputs)), p),
        )

    def test_refractory_inputs_are_retained_and_checked_at_expiry(self):
        p = NeuronParams()
        first = process_neuron_batch(NeuronState(), 0, [InputSignal("first", 1.0, 1.0)], p)
        self.assertTrue(first.spike_emitted)
        during = process_neuron_batch(first.state, 1 * MS, [InputSignal("during", 1.0, 1.0, 1.0)], p)
        self.assertFalse(during.spike_emitted)
        self.assertTrue(during.refractory_active)
        self.assertGreater(during.state.u, 1.0)
        expiry = process_neuron_batch(during.state, 2 * MS, [], p)
        self.assertTrue(expiry.spike_emitted)
        self.assertEqual(expiry.state.spike_sequence, 2)

    def test_late_event_is_rejected(self):
        with self.assertRaises(OracleInputError):
            process_neuron_batch(
                NeuronState(last_update_model_time_ns=10 * MS), 9 * MS, [], NeuronParams()
            )

    def test_nan_and_out_of_range_attention_are_rejected(self):
        with self.assertRaises(OracleInputError):
            process_neuron_batch(
                NeuronState(), 0, [InputSignal("x", 1.0, 1.0, math.nan)], NeuronParams()
            )
        with self.assertRaises(OracleInputError):
            attention_gain(1.1, NeuronParams())

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
        self.assertAlmostEqual(second.q_raw, 0.0009196986029286058, places=15)
        self.assertAlmostEqual(second.state.weight, 0.5009196986029286, places=15)
        self.assertGreater(second.state.weight, 0.5)

    def test_post_then_pre_causes_ltd(self):
        p = SynapseParams()
        first = update_stdp(SynapseState(), p, 0, n_pre=0, n_post=1)
        second = update_stdp(first.state, p, 20 * MS, n_pre=1, n_post=0)
        self.assertAlmostEqual(second.q_raw, -0.0015163266492815835, places=15)
        self.assertAlmostEqual(second.state.weight, 0.4984836733507184, places=15)
        self.assertLess(second.state.weight, 0.5)

    def test_simultaneous_first_pair_has_no_artificial_order(self):
        got = update_stdp(SynapseState(), SynapseParams(), 0, n_pre=1, n_post=1)
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

    def test_stdp_multi_event_golden_trajectory(self):
        p = SynapseParams()
        state = SynapseState()
        trajectory = []
        for time_ns, n_pre, n_post in (
            (0, 1, 0), (10 * MS, 0, 1), (20 * MS, 1, 0), (40 * MS, 0, 1),
        ):
            step = update_stdp(state, p, time_ns, n_pre, n_post)
            state = step.state
            trajectory.append(state.weight)
        expected = (
            0.5,
            0.5015163266492816,
            0.4995634201096938,
            0.5008225553878598,
        )
        for actual, reference in zip(trajectory, expected):
            self.assertAlmostEqual(actual, reference, places=14)

    def test_late_stdp_event_is_rejected(self):
        p = SynapseParams()
        with self.assertRaises(OracleInputError):
            update_stdp(
                SynapseState(trace_last_update_model_time_ns=5 * MS),
                p, 4 * MS, n_pre=1, n_post=0,
            )


class RewardStdpOracleTests(unittest.TestCase):
    def test_rstdp_accumulates_eligibility_without_changing_weight(self):
        p, rp = SynapseParams(), RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-1")
        second = update_rstdp(first.state, p, rp, 20 * MS, 0, 1, "episode-1")
        self.assertAlmostEqual(second.state.eligibility_trace, 0.0009196986029286058, places=15)
        self.assertEqual(second.state.weight, 0.5)
        self.assertEqual(second.weight_delta, 0.0)

    def test_reward_changes_weight_and_updates_task_baseline(self):
        p, rp = SynapseParams(), RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-1")
        eligible = update_rstdp(first.state, p, rp, 20 * MS, 0, 1, "episode-1")
        result = apply_reward(
            eligible.state, p, rp, 20 * MS, reward_value=1.0,
            baseline_before=0.5, learning_context_id="episode-1",
        )
        self.assertAlmostEqual(result.modulator, 0.5)
        self.assertAlmostEqual(result.baseline_after, 0.505)
        self.assertAlmostEqual(result.state.weight, 0.5000022992465073, places=15)
        self.assertEqual(result.status, "APPLIED")
        self.assertEqual(result.state.eligibility_trace, 0.0)
        self.assertIsNone(result.state.eligibility_context_id)

    def test_reward_with_zero_prediction_error_does_not_change_weight(self):
        p, rp = SynapseParams(), RewardParams()
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
        p, rp = SynapseParams(), RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        with self.assertRaises(OracleInputError):
            update_rstdp(first.state, p, rp, 1 * MS, 0, 1, "episode-b")

    def test_eligibility_expires_after_maximum_age(self):
        p, rp = SynapseParams(), RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        at_limit = update_rstdp(first.state, p, rp, rp.max_eligibility_age_ns, 0, 0, "episode-a")
        self.assertEqual(at_limit.state.eligibility_context_id, "episode-a")
        expired = update_rstdp(
            at_limit.state, p, rp, rp.max_eligibility_age_ns + 1, 0, 0, "episode-a"
        )
        self.assertIsNone(expired.state.eligibility_context_id)
        self.assertEqual(expired.state.eligibility_trace, 0.0)

    def test_reward_for_wrong_context_is_rejected(self):
        p, rp = SynapseParams(), RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        with self.assertRaises(OracleInputError):
            apply_reward(first.state, p, rp, 0, 1.0, 0.5, "episode-b")

    def test_expired_reward_is_a_noop_but_updates_baseline(self):
        p, rp = SynapseParams(), RewardParams()
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
        p, rp = SynapseParams(), RewardParams()
        first = update_rstdp(SynapseState(), p, rp, 0, 1, 0, "episode-a")
        with self.assertRaises(OracleInputError):
            update_rstdp(
                first.state, p, rp, rp.max_eligibility_age_ns + 1, 0, 1, "episode-a"
            )
        second = update_rstdp(
            first.state, p, rp, rp.max_eligibility_age_ns + 1, 1, 0, "episode-b"
        )
        self.assertEqual(second.state.eligibility_context_id, "episode-b")
        self.assertEqual(second.q_raw, 0.0)
        self.assertEqual(second.state.pre_trace, 1.0)
        self.assertEqual(second.state.post_trace, 0.0)

    def test_delayed_reward_golden_vector(self):
        p, rp = SynapseParams(), RewardParams()
        state = SynapseState()
        for time_ns, n_pre, n_post in (
            (0, 1, 0), (100 * MS, 0, 1), (1100 * MS, 0, 0),
        ):
            state = update_rstdp(
                state, p, rp, time_ns, n_pre, n_post, "golden-episode"
            ).state
        result = apply_reward(
            state, p, rp, 1100 * MS, reward_value=1.0,
            baseline_before=0.5, learning_context_id="golden-episode",
        )
        self.assertAlmostEqual(result.state.weight, 0.5000000154922011, places=15)
        self.assertAlmostEqual(result.weight_delta, 0.00000001549220107754934, places=18)
        self.assertAlmostEqual(result.baseline_after, 0.505, places=15)
        self.assertEqual(result.status, "APPLIED")

    def test_invalid_reward_is_rejected(self):
        p, rp = SynapseParams(), RewardParams()
        with self.assertRaises(OracleInputError):
            apply_reward(
                SynapseState(), p, rp, 0, reward_value=1.01,
                baseline_before=0.5, learning_context_id=None,
            )



class LongSequenceOracleTests(unittest.TestCase):
    def test_lif_long_sequence_is_bounded_and_reproducible(self):
        params = NeuronParams()

        def run_probe():
            state = NeuronState()
            spike_times = []
            for index in range(2500):
                signals = []
                if index % 5 == 0:
                    signals.append(InputSignal("periodic-excitation", 1.0, 0.45, 0.2))
                if index % 13 == 0:
                    signals.append(InputSignal("periodic-inhibition", -0.35, 0.5, 0.0))
                step = process_neuron_batch(state, index * MS, signals, params)
                state = step.state
                state.validate(params)
                if step.spike_emitted:
                    spike_times.append(index)
            return state, tuple(spike_times)

        first_state, first_spikes = run_probe()
        second_state, second_spikes = run_probe()
        self.assertEqual(first_state, second_state)
        self.assertEqual(first_spikes, second_spikes)
        self.assertGreater(len(first_spikes), 0)
        self.assertEqual(first_state.spike_sequence, len(first_spikes))
        self.assertTrue(math.isfinite(first_state.u))
        self.assertTrue(math.isfinite(first_state.a))

    def test_stdp_long_sequence_keeps_weight_and_traces_bounded(self):
        params = SynapseParams()
        state = SynapseState()
        for index in range(5000):
            n_pre = 1 if index % 2 == 0 else 0
            n_post = 1 if index % 2 == 1 else 0
            step = update_stdp(state, params, index * MS, n_pre, n_post)
            state = step.state
            state.validate(params)
            self.assertGreaterEqual(state.weight, params.w_min)
            self.assertLessEqual(state.weight, params.w_max)
            self.assertGreaterEqual(state.pre_trace, 0.0)
            self.assertLessEqual(state.pre_trace, params.x_pre_max)
            self.assertGreaterEqual(state.post_trace, 0.0)
            self.assertLessEqual(state.post_trace, params.x_post_max)


class CalibrationSweepTests(unittest.TestCase):
    def test_sweeps_are_deterministic_and_cover_required_grid(self):
        report = run_sweeps()
        self.assertEqual(run_sweeps(), report)
        self.assertEqual(report["schema"], "JARVIS_NUMERICAL_SWEEP_V0_1")
        self.assertEqual(report["profile_id"], "JARVIS_SINGLE_NEURON_STARTER_V1")
        self.assertEqual(report["counts"], {"lif": 81, "stdp": 12, "rstdp": 6})
        self.assertEqual(len(report["lif"]), 81)
        self.assertEqual(len(report["stdp"]), 12)
        self.assertEqual(len(report["rstdp"]), 6)
        boundary = next(
            row for row in report["rstdp"]
            if row["age_from_context_start_ms"] == 5000
        )
        expired = next(
            row for row in report["rstdp"]
            if row["age_from_context_start_ms"] == 5001
        )
        self.assertEqual(boundary["status"], "APPLIED")
        self.assertEqual(expired["status"], "EXPIRED")


if __name__ == "__main__":
    unittest.main(verbosity=2)
