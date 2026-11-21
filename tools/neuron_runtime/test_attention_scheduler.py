"""Tests for the pure, deterministic attention-budget policy."""
from __future__ import annotations

import unittest

from tools.neuron_runtime.attention_scheduler import (
    AttentionCandidate, AttentionSchedulerError, schedule_attention_batches,
)


class AttentionSchedulerTests(unittest.TestCase):
    def test_high_salience_is_selected_first(self):
        result = schedule_attention_batches([
            AttentionCandidate("quiet", 0.1, 0.0, 0.0),
            AttentionCandidate("urgent", 1.0, 0.0, 0.0),
        ], budget=1)
        self.assertEqual(result.selected, ("urgent",))
        self.assertEqual(result.deferred, ("quiet",))

    def test_uncertainty_and_prediction_error_contribute_to_priority(self):
        result = schedule_attention_batches([
            AttentionCandidate("certain", 0.0, 0.0, 0.0),
            AttentionCandidate("uncertain", 0.0, 1.0, 1.0),
        ], budget=1)
        self.assertEqual(result.selected, ("uncertain",))

    def test_aging_eventually_overcomes_repeated_high_priority_arrivals(self):
        low = AttentionCandidate("waiting", 0.0, 0.0, 0.0)
        high = AttentionCandidate("new-high", 1.0, 0.0, 0.0)
        ages = {"waiting": 0, "new-high": 0}
        first = schedule_attention_batches([low, high], budget=1, wait_cycles=ages)
        self.assertEqual(first.selected, ("new-high",))
        ages = dict(first.wait_cycles)
        selected = None
        for _ in range(25):
            result = schedule_attention_batches([low, high], budget=1, wait_cycles=ages)
            ages = dict(result.wait_cycles)
            if "waiting" in result.selected:
                selected = result.selected
                break
        self.assertEqual(selected, ("waiting",))

    def test_selection_and_ties_are_deterministic(self):
        candidates = [
            AttentionCandidate("z", 0.5, 0.5, 0.5),
            AttentionCandidate("a", 0.5, 0.5, 0.5),
            AttentionCandidate("m", 0.5, 0.5, 0.5),
        ]
        first = schedule_attention_batches(candidates, budget=2)
        second = schedule_attention_batches(list(reversed(candidates)), budget=2)
        self.assertEqual(first, second)
        self.assertEqual(first.selected, ("a", "m"))

    def test_selected_age_resets_and_deferred_age_increments(self):
        result = schedule_attention_batches([
            AttentionCandidate("a", 1.0, 0.0, 0.0),
            AttentionCandidate("b", 0.0, 0.0, 0.0),
        ], budget=1, wait_cycles={"a": 7, "b": 4})
        self.assertEqual(result.wait_cycles["a"], 0)
        self.assertEqual(result.wait_cycles["b"], 5)

    def test_rejects_invalid_and_duplicate_candidates(self):
        with self.assertRaises(AttentionSchedulerError):
            AttentionCandidate("bad", 1.1, 0.0, 0.0).validate()
        candidate = AttentionCandidate("same", 0.0, 0.0, 0.0)
        with self.assertRaises(AttentionSchedulerError):
            schedule_attention_batches([candidate, candidate], budget=1)

    def test_rejects_unknown_wait_state_and_bad_budget(self):
        with self.assertRaises(AttentionSchedulerError):
            schedule_attention_batches(
                [AttentionCandidate("a", 0.0, 0.0, 0.0)],
                budget=1, wait_cycles={"missing": 3},
            )
        with self.assertRaises(AttentionSchedulerError):
            schedule_attention_batches([], budget=0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
