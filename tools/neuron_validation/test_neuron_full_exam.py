"""Large deterministic contract and adversarial tests for the one-neuron oracle."""
from __future__ import annotations

import math
import random
import unittest
from dataclasses import replace

from tools.numerical_oracle.jarvis_oracle import (
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
    decay_neuron_state,
    effective_threshold,
    float32_roundtrip,
    process_neuron_batch,
    starter_neuron_params,
    starter_reward_params,
    starter_synapse_params,
    update_rstdp,
    update_stdp,
)
from tools.neuron_validation.noise_benchmark import build_dataset, run_seed

MS = 1_000_000
U64_MAX = (1 << 64) - 1


class NeuronDynamicsExam(unittest.TestCase):
    def test_analytical_membrane_and_adaptation_decay_grid(self):
        params = NeuronParams()
        for membrane in (-1.5, -0.25, 0.25, 1.5):
            for adaptation in (0.0, 0.1, 0.5, 1.0):
                for delta in (0, 1, MS, 20 * MS, 500 * MS):
                    with self.subTest(membrane=membrane, adaptation=adaptation, delta=delta):
                        state = NeuronState(u=membrane, a=adaptation)
                        actual = decay_neuron_state(state, delta, params)
                        expected_u = params.u_rest + (membrane - params.u_rest) * math.exp(
                            -delta / params.tau_m_ns
                        )
                        expected_a = adaptation * math.exp(-delta / params.tau_a_ns)
                        self.assertAlmostEqual(actual.u, expected_u, places=14)
                        self.assertAlmostEqual(actual.a, expected_a, places=14)
                        self.assertEqual(actual.last_update_model_time_ns, delta)

    def test_zero_elapsed_time_is_exact_noop_for_dynamic_values(self):
        state = NeuronState(u=0.4, a=0.3, spike_sequence=7)
        actual = decay_neuron_state(state, 0, NeuronParams())
        self.assertEqual(actual, state)

    def test_long_idle_time_converges_to_rest_without_invalid_values(self):
        params = NeuronParams()
        state = NeuronState(u=1.75, a=0.95)
        actual = decay_neuron_state(state, 10_000 * params.tau_m_ns, params)
        self.assertAlmostEqual(actual.u, params.u_rest, places=12)
        self.assertAlmostEqual(actual.a, 0.0, places=12)
        actual.validate(params)

    def test_effective_threshold_is_monotonic_and_clamped(self):
        params = NeuronParams()
        thresholds = [
            effective_threshold(NeuronState(a=value), params)
            for value in (0.0, 0.1, 0.25, 0.5, 0.75, 1.0)
        ]
        self.assertEqual(thresholds, sorted(thresholds))
        self.assertEqual(thresholds[0], params.theta_0)
        self.assertEqual(thresholds[-1], params.theta_max)

    def test_attention_gain_endpoints_and_interior(self):
        params = NeuronParams()
        self.assertEqual(attention_gain(0.0, params), params.g_min)
        self.assertEqual(attention_gain(1.0, params), params.g_max)
        self.assertGreaterEqual(attention_gain(0.5, params), params.g_min)
        self.assertLessEqual(attention_gain(0.5, params), params.g_max)

    def test_simultaneous_batch_is_permutation_invariant(self):
        params = NeuronParams()
        signals = (
            InputSignal("source-c", 0.4, 0.8, 0.2),
            InputSignal("source-a", 0.7, 0.5, 0.9),
            InputSignal("source-b", -0.2, 0.4, 0.1),
        )
        forward = process_neuron_batch(NeuronState(), 5 * MS, signals, params)
        reverse = process_neuron_batch(NeuronState(), 5 * MS, tuple(reversed(signals)), params)
        self.assertEqual(forward, reverse)

    def test_simultaneous_input_sum_uses_all_events_and_clips_potential(self):
        signals = tuple(
            InputSignal(f"source-{index:02d}", 1.0, 1.0)
            for index in range(20)
        )
        step = process_neuron_batch(NeuronState(), 0, signals, NeuronParams())
        self.assertAlmostEqual(step.total_input_delta, 20.0, places=12)
        self.assertTrue(step.spike_emitted)
        self.assertEqual(step.state.u, 0.0)

    def test_inhibitory_input_is_bounded_at_lower_potential_limit(self):
        state = NeuronState(u=-1.9)
        step = process_neuron_batch(
            state, 0, [InputSignal("inhibitory", -1.0, 1.0)],
            NeuronParams(),
        )
        self.assertEqual(step.state.u, -2.0)
        self.assertFalse(step.spike_emitted)

    def test_duplicate_source_ids_in_one_batch_are_rejected(self):
        signals = (
            InputSignal("same", 0.2, 0.2),
            InputSignal("same", 0.3, 0.3),
        )
        with self.assertRaises(OracleInputError):
            process_neuron_batch(NeuronState(), 0, signals, NeuronParams())

    def test_non_signal_objects_and_invalid_signal_domains_are_rejected(self):
        with self.assertRaises(OracleInputError):
            process_neuron_batch(NeuronState(), 0, [object()], NeuronParams())
        for signal in (
            InputSignal("", 0.1, 0.1),
            InputSignal("x", 1.01, 0.1),
            InputSignal("x", 0.1, -0.01),
            InputSignal("x", 0.1, 0.1, 1.01),
            InputSignal("x", float("nan"), 0.1),
            InputSignal("x", 0.1, float("inf")),
        ):
            with self.subTest(signal=signal):
                with self.assertRaises(OracleInputError):
                    signal.validate()

    def test_refractory_is_active_before_boundary_and_inactive_at_boundary(self):
        params = NeuronParams()
        initial = NeuronState(
            u=1.1,
            last_update_model_time_ns=0,
            refractory_until_model_time_ns=10,
            last_spike_model_time_ns=0,
            spike_sequence=1,
        )
        blocked = process_neuron_batch(initial, 9, [], params)
        self.assertTrue(blocked.refractory_active)
        self.assertFalse(blocked.spike_emitted)
        released = process_neuron_batch(blocked.state, 10, [], params)
        self.assertFalse(released.refractory_active)
        self.assertTrue(released.spike_emitted)
        self.assertEqual(released.state.spike_sequence, 2)

    def test_spike_resets_potential_and_increases_adaptation_threshold(self):
        params = NeuronParams()
        step = process_neuron_batch(
            NeuronState(u=0.99),
            0,
            [InputSignal("drive", 1.0, 0.1)],
            params,
        )
        self.assertTrue(step.spike_emitted)
        self.assertEqual(step.state.u, params.u_reset)
        self.assertAlmostEqual(step.state.a, params.delta_a_spike)
        self.assertGreater(effective_threshold(step.state, params), params.theta_0)
        self.assertEqual(step.state.spike_sequence, 1)
        self.assertEqual(step.state.last_spike_model_time_ns, 0)

    def test_repeated_spikes_cannot_exceed_adaptation_or_threshold_limits(self):
        params = NeuronParams(delta_a_spike=0.8, a_max=0.9, refractory_ns=0)
        state = NeuronState()
        for index in range(50):
            state = process_neuron_batch(
                state, index * MS,
                [InputSignal("drive", 1.0, 1.0)], params,
            ).state
            state.validate(params)
            self.assertLessEqual(state.a, params.a_max)
            self.assertLessEqual(effective_threshold(state, params), params.theta_max)

    def test_monotonic_time_and_u64_overflow_are_rejected(self):
        state = NeuronState(last_update_model_time_ns=100)
        with self.assertRaises(OracleInputError):
            process_neuron_batch(state, 99, [], NeuronParams())
        near_limit = NeuronState(u=1.5, last_update_model_time_ns=U64_MAX)
        with self.assertRaises(OracleInputError):
            process_neuron_batch(
                near_limit, U64_MAX,
                [InputSignal("edge", 1.0, 1.0)],
                NeuronParams(refractory_ns=1),
            )

    def test_randomized_event_stress_preserves_state_invariants(self):
        for seed in (104729, 130363, 155921, 181081):
            rng = random.Random(seed)
            params = starter_neuron_params()
            state = NeuronState()
            last_time = 0
            total_spikes = 0
            for index in range(750):
                last_time += rng.randint(1, 2 * MS)
                signals = [
                    InputSignal(
                        source_id=f"{index}-{slot}",
                        signed_weight=rng.uniform(-1.0, 1.0),
                        strength=rng.random(),
                        attention=rng.random(),
                    )
                    for slot in range(rng.randint(0, 4))
                ]
                step = process_neuron_batch(state, last_time, signals, params)
                state = step.state
                state.validate(params)
                self.assertGreaterEqual(state.last_update_model_time_ns, last_time)
                self.assertGreaterEqual(state.a, 0.0)
                self.assertLessEqual(state.a, params.a_max)
                self.assertTrue(math.isfinite(state.u))
                self.assertTrue(math.isfinite(step.total_input_delta))
                total_spikes += int(step.spike_emitted)
            self.assertEqual(state.spike_sequence, total_spikes)


class SynapticLearningExam(unittest.TestCase):
    def setUp(self):
        self.synapse = starter_synapse_params()
        self.reward = starter_reward_params()

    def test_stdp_pre_before_post_potentiates(self):
        first = update_stdp(SynapseState(weight=0.5), self.synapse, 0, 1, 0)
        second = update_stdp(first.state, self.synapse, MS, 0, 1)
        self.assertGreater(second.weight_delta, 0.0)
        self.assertGreater(second.state.weight, 0.5)

    def test_stdp_post_before_pre_depresses(self):
        first = update_stdp(SynapseState(weight=0.5), self.synapse, 0, 0, 1)
        second = update_stdp(first.state, self.synapse, MS, 1, 0)
        self.assertLess(second.weight_delta, 0.0)
        self.assertLess(second.state.weight, 0.5)

    def test_stdp_simultaneous_events_do_not_create_order_artifact(self):
        step = update_stdp(SynapseState(weight=0.5), self.synapse, 0, 1, 1)
        self.assertEqual(step.weight_delta, 0.0)
        self.assertEqual(step.q_raw, 0.0)

    def test_stdp_trace_decay_matches_exponential_reference(self):
        first = update_stdp(SynapseState(weight=0.5), self.synapse, 0, 2, 1)
        second = update_stdp(first.state, self.synapse, 3 * MS, 0, 0)
        self.assertAlmostEqual(
            second.state.pre_trace,
            2.0 * math.exp(-3 * MS / self.synapse.tau_pre_ns),
            places=14,
        )
        self.assertAlmostEqual(
            second.state.post_trace,
            math.exp(-3 * MS / self.synapse.tau_post_ns),
            places=14,
        )

    def test_stdp_zero_events_only_decay_traces(self):
        first = update_stdp(SynapseState(weight=0.5), self.synapse, 0, 1, 1)
        second = update_stdp(first.state, self.synapse, 4 * MS, 0, 0)
        self.assertEqual(second.weight_delta, 0.0)
        self.assertEqual(second.state.weight, first.state.weight)
        self.assertLess(second.state.pre_trace, first.state.pre_trace)

    def test_stdp_weight_and_trace_limits_survive_long_adversarial_sequence(self):
        state = SynapseState(weight=0.5)
        for index in range(5_000):
            state = update_stdp(
                state, self.synapse, (index + 1) * MS,
                8 if index % 2 else 0,
                8 if index % 3 else 0,
            ).state
            state.validate(self.synapse)
            self.assertGreaterEqual(state.weight, self.synapse.w_min)
            self.assertLessEqual(state.weight, self.synapse.w_max)
            self.assertLessEqual(state.pre_trace, self.synapse.x_pre_max)
            self.assertLessEqual(state.post_trace, self.synapse.x_post_max)

    def _positive_eligibility_state(self, context="episode-1"):
        first = update_rstdp(
            SynapseState(weight=0.5), self.synapse, self.reward,
            0, 1, 0, context,
        )
        second = update_rstdp(
            first.state, self.synapse, self.reward,
            MS, 0, 1, context,
        )
        self.assertGreater(second.state.eligibility_trace, 0.0)
        self.assertEqual(second.weight_delta, 0.0)
        return second.state

    def test_rstdp_accumulates_eligibility_without_changing_weight(self):
        state = self._positive_eligibility_state()
        self.assertEqual(state.weight, 0.5)
        self.assertIsNotNone(state.eligibility_context_id)

    def test_positive_negative_and_neutral_rewards_have_expected_directions(self):
        positive = apply_reward(
            self._positive_eligibility_state(), self.synapse, self.reward,
            2 * MS, 1.0, 0.5, "episode-1",
        )
        negative = apply_reward(
            self._positive_eligibility_state(), self.synapse, self.reward,
            2 * MS, 0.0, 0.5, "episode-1",
        )
        neutral = apply_reward(
            self._positive_eligibility_state(), self.synapse, self.reward,
            2 * MS, 0.5, 0.5, "episode-1",
        )
        self.assertGreater(positive.weight_delta, 0.0)
        self.assertLess(negative.weight_delta, 0.0)
        self.assertEqual(neutral.weight_delta, 0.0)
        self.assertEqual(neutral.modulator, 0.0)
        for result in (positive, negative, neutral):
            self.assertEqual(result.status, "APPLIED" if result.weight_delta else "APPLIED_NO_CHANGE")
            self.assertIsNone(result.state.eligibility_context_id)
            self.assertEqual(result.state.eligibility_trace, 0.0)

    def test_rstdp_updates_baseline_with_bounded_exponential_step(self):
        result = apply_reward(
            self._positive_eligibility_state(), self.synapse, self.reward,
            2 * MS, 1.0, 0.5, "episode-1",
        )
        expected = 0.5 + self.reward.beta_b * (1.0 - 0.5)
        self.assertAlmostEqual(result.baseline_after, expected, places=15)
        self.assertGreaterEqual(result.baseline_after, 0.0)
        self.assertLessEqual(result.baseline_after, 1.0)

    def test_rstdp_rejects_cross_episode_context_and_wrong_reward_context(self):
        state = self._positive_eligibility_state()
        with self.assertRaises(OracleInputError):
            update_rstdp(
                state, self.synapse, self.reward, 2 * MS, 1, 0, "episode-2"
            )
        with self.assertRaises(OracleInputError):
            apply_reward(
                state, self.synapse, self.reward, 2 * MS,
                1.0, 0.5, "episode-2",
            )

    def test_rstdp_expired_eligibility_is_discarded(self):
        short = replace(self.reward, max_eligibility_age_ns=MS)
        first = update_rstdp(
            SynapseState(weight=0.5), self.synapse, short, 0, 1, 0, "old",
        )
        expired = apply_reward(
            first.state, self.synapse, short, 2 * MS,
            1.0, 0.5, "old",
        )
        self.assertEqual(expired.status, "EXPIRED")
        self.assertEqual(expired.weight_delta, 0.0)
        self.assertEqual(expired.state.weight, 0.5)
        self.assertIsNone(expired.state.eligibility_context_id)

    def test_reward_without_eligibility_cannot_change_weight(self):
        result = apply_reward(
            SynapseState(weight=0.6), self.synapse, self.reward,
            0, 1.0, 0.5, None,
        )
        self.assertEqual(result.status, "NO_ELIGIBILITY")
        self.assertEqual(result.weight_delta, 0.0)
        self.assertEqual(result.state.weight, 0.6)

    def test_rstdp_random_reward_stress_preserves_weight_and_trace_bounds(self):
        rng = random.Random(314159)
        state = SynapseState(weight=0.5)
        baseline = 0.5
        for index in range(1_000):
            context = f"stress-{index}"
            start = index * 2 * MS
            first = update_rstdp(
                state, self.synapse, self.reward,
                start, rng.randint(0, 3), 0, context,
            )
            second = update_rstdp(
                first.state, self.synapse, self.reward,
                start + MS, 0, rng.randint(0, 3), context,
            )
            reward_value = float(rng.randint(0, 1))
            reward_step = apply_reward(
                second.state, self.synapse, self.reward,
                start + 2 * MS, reward_value, baseline, context,
            )
            state = reward_step.state
            baseline = reward_step.baseline_after
            state.validate(self.synapse, self.reward)
            self.assertGreaterEqual(state.weight, self.synapse.w_min)
            self.assertLessEqual(state.weight, self.synapse.w_max)
            self.assertGreaterEqual(baseline, 0.0)
            self.assertLessEqual(baseline, 1.0)
            self.assertEqual(state.eligibility_trace, 0.0)
            self.assertIsNone(state.eligibility_context_id)


class NumericAndResourceBoundaryExam(unittest.TestCase):
    def test_float32_helper_and_vector_comparison_are_consistent(self):
        values = (0.0, 0.1, -0.25, 1.0, 2.0)
        candidate = tuple(float32_roundtrip(value) for value in values)
        comparison = compare_numeric_vectors(
            values, candidate, absolute_tolerance=1e-6, relative_tolerance=1e-6,
        )
        self.assertTrue(comparison.within_tolerance)
        self.assertEqual(comparison.count, len(values))
        self.assertEqual(comparison.mismatched_indices, ())

    def test_numeric_comparison_rejects_nan_and_length_mismatch(self):
        with self.assertRaises(OracleInputError):
            compare_numeric_vectors((1.0,), ())
        with self.assertRaises(OracleInputError):
            compare_numeric_vectors((float("nan"),), (0.0,))

    def test_invalid_neuron_and_synapse_parameters_are_rejected(self):
        invalid_neurons = (
            replace(NeuronParams(), tau_m_ns=0),
            replace(NeuronParams(), theta_min=0.0),
            replace(NeuronParams(), u_max=-1.0),
            replace(NeuronParams(), k_a=-0.1),
            replace(NeuronParams(), g_min=2.0, g_max=1.0),
        )
        for params in invalid_neurons:
            with self.subTest(params=params):
                with self.assertRaises(OracleInputError):
                    params.validate()
        invalid_synapses = (
            replace(SynapseParams(), w_min=1.0, w_max=1.0),
            replace(SynapseParams(), tau_pre_ns=0),
            replace(SynapseParams(), eta_plus=-0.1),
        )
        for params in invalid_synapses:
            with self.subTest(params=params):
                with self.assertRaises(OracleInputError):
                    params.validate()

    def test_invalid_state_ranges_and_boolean_counters_are_rejected(self):
        with self.assertRaises(OracleInputError):
            NeuronState(u=3.0).validate(NeuronParams())
        with self.assertRaises(OracleInputError):
            NeuronState(spike_sequence=True).validate(NeuronParams())
        with self.assertRaises(OracleInputError):
            SynapseState(weight=2.0).validate(SynapseParams())
        with self.assertRaises(OracleInputError):
            update_stdp(SynapseState(), SynapseParams(), 0, True, 0)


class NoisyTemporalGeneralizationExam(unittest.TestCase):
    def test_train_and_heldout_are_balanced_disjoint_and_deterministic(self):
        first = build_dataset(104729, 32, 16, 3)
        second = build_dataset(104729, 32, 16, 3)
        self.assertEqual(first, second)
        train, held = first
        for split, pair_count in ((train, 32), (held, 16)):
            self.assertEqual(len(split), pair_count * 2)
            self.assertEqual(sum(item.label == "FAST" for item in split), pair_count)
            self.assertEqual(sum(item.label == "SLOW" for item in split), pair_count)
        train_signatures = {
            (row.strengths, row.distractors) for row in train
        }
        held_signatures = {
            (row.strengths, row.distractors) for row in held
        }
        self.assertTrue(train_signatures.isdisjoint(held_signatures))

    def test_matched_pair_has_identical_noise_but_different_timing(self):
        train, _ = build_dataset(130363, 24, 12, 4)
        pairs = {}
        for episode in train:
            pairs.setdefault(episode.pair_id, []).append(episode)
        self.assertTrue(pairs)
        for members in pairs.values():
            self.assertEqual(len(members), 2)
            self.assertEqual(members[0].distractors, members[1].distractors)
            self.assertEqual(members[0].strengths, members[1].strengths)
            self.assertNotEqual(members[0].gaps_ms, members[1].gaps_ms)

    def test_noise_benchmark_smoke_is_reproducible_and_keeps_test_weights_frozen(self):
        first = run_seed(104729, 16, 8, 2)
        second = run_seed(104729, 16, 8, 2)
        self.assertEqual(first, second)
        self.assertFalse(first["train_held_out_signature_overlap"])
        self.assertEqual(len(first["conditions"]), 4)
        for row in first["conditions"]:
            self.assertTrue(row["held_out_weights_frozen"])
            self.assertEqual(
                row["train"]["episodes"], first["train_episode_count"]
            )
            self.assertEqual(
                row["held_out"]["episodes"], first["held_out_episode_count"]
            )
            self.assertEqual(row["initial_weight"], 0.70)

    def test_noise_count_limits_are_enforced(self):
        for invalid in (-1, 9, True):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    build_dataset(1, 2, 1, invalid)


if __name__ == "__main__":
    unittest.main(verbosity=2)
