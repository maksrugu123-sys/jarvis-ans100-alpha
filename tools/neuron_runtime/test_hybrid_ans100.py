"""Regression contract for the Windows/Python ANS-100 V4 topology. NOT RUN."""
from __future__ import annotations

import unittest

from tools.neuron_runtime.hybrid_ans100 import (
    EXPECTED_CONNECTION_COUNT,
    NEURON_COUNT,
    POPULATIONS,
    build_connections,
    validate_input_ids,
)


class HybridANS100TopologyTests(unittest.TestCase):
    def test_population_ranges_cover_exactly_ten_thousand_neurons(self):
        self.assertEqual(sum(population.size for population in POPULATIONS), NEURON_COUNT)
        self.assertEqual(POPULATIONS[0].start, 0)
        self.assertEqual(POPULATIONS[-1].end, NEURON_COUNT)
        for previous, current in zip(POPULATIONS, POPULATIONS[1:]):
            self.assertEqual(previous.end, current.start)

    def test_android_v4_connection_count_and_ids_are_deterministic(self):
        first = build_connections()
        second = build_connections()
        self.assertEqual(len(first), EXPECTED_CONNECTION_COUNT)
        self.assertEqual(first, second)
        self.assertEqual(len({edge.connection_id for edge in first}), EXPECTED_CONNECTION_COUNT)
        self.assertTrue(all(0 <= int(edge.source_neuron_id) < NEURON_COUNT for edge in first))
        self.assertTrue(all(0 <= int(edge.target_neuron_id) < NEURON_COUNT for edge in first))

    def test_candidate_input_routes_do_not_share_general_evidence_projection(self):
        edges = build_connections()
        candidate_inputs = [edge for edge in edges if edge.connection_id.startswith(
            "ans100-v4-candidate-input-"
        )]
        self.assertEqual(len(candidate_inputs), 18)
        self.assertTrue(all(1_056 <= int(edge.source_neuron_id) < 1_062 for edge in candidate_inputs))

    def test_evidence_inputs_are_bounded_to_the_android_input_population(self):
        self.assertEqual(validate_input_ids([0, 17, 1_055]), (0, 17, 1_055))
        for invalid in ([1_200], [1_056], [-1], [True], [], list(range(129)), [1, 1]):
            with self.subTest(value=invalid), self.assertRaises(ValueError):
                validate_input_ids(invalid)


if __name__ == "__main__":
    unittest.main()
