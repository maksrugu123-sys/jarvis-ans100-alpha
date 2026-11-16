"""Tests for the experimental sparse canonical readout learner."""
import unittest

from tools.neuron_runtime.canonical_readout import (
    CanonicalClass,
    FeedbackEvent,
    FeedbackProvenance,
    SparseCanonicalReadout,
)


def event(event_id, ids, target, provenance=FeedbackProvenance.EXPLICIT_USER_FEEDBACK):
    return FeedbackEvent(
        event_id=event_id,
        input_sha256="a" * 64,
        active_neuron_ids=tuple(ids),
        target=target,
        provenance=provenance,
    )


class SparseCanonicalReadoutTests(unittest.TestCase):
    def test_untrained_readout_abstains(self):
        result = SparseCanonicalReadout().predict([10, 11])
        self.assertIsNone(result.answer)
        self.assertEqual(result.status, "NO_LEARNED_SIGNAL")

    def test_prediction_uses_spikes_not_a_supplied_label(self):
        model = SparseCanonicalReadout()
        model.learn(event("e1", [10, 11], CanonicalClass.YES))
        self.assertEqual(model.predict([10, 11]).answer, CanonicalClass.YES)

    def test_disjoint_patterns_can_learn_four_classes(self):
        model = SparseCanonicalReadout()
        examples = [
            ([10, 11], CanonicalClass.YES),
            ([20, 21], CanonicalClass.NO),
            ([30, 31], CanonicalClass.UNKNOWN),
            ([40, 41], CanonicalClass.CONFLICT),
        ]
        for repeat in range(4):
            for index, (ids, target) in enumerate(examples):
                model.learn(event(f"e{repeat}-{index}", ids, target))
        for ids, target in examples:
            self.assertEqual(model.predict(ids).answer, target)

    def test_duplicate_feedback_is_idempotent(self):
        model = SparseCanonicalReadout()
        first = event("same-id", [10], CanonicalClass.YES)
        self.assertTrue(model.learn(first))
        before = model.state_sha256()
        self.assertFalse(model.learn(first))
        self.assertEqual(model.state_sha256(), before)

    def test_model_generated_or_inferred_provenance_is_rejected(self):
        model = SparseCanonicalReadout()
        invalid = FeedbackEvent(
            "e1", "a" * 64, (10,), CanonicalClass.YES, "MODEL_GENERATED"
        )
        with self.assertRaises(ValueError):
            model.learn(invalid)

    def test_core_candidate_input_neurons_are_rejected(self):
        with self.assertRaises(ValueError):
            SparseCanonicalReadout().predict([1056])

    def test_output_and_out_of_range_neurons_are_rejected(self):
        with self.assertRaises(ValueError):
            SparseCanonicalReadout().predict([9400])

    def test_duplicate_or_non_integer_neuron_ids_are_rejected(self):
        with self.assertRaises(ValueError):
            SparseCanonicalReadout().predict([10, 10])
        with self.assertRaises(ValueError):
            SparseCanonicalReadout().predict([True])

    def test_empty_or_oversized_active_sets_are_rejected(self):
        with self.assertRaises(ValueError):
            SparseCanonicalReadout().predict([])
        with self.assertRaises(ValueError):
            SparseCanonicalReadout().predict(range(513))

    def test_tied_scores_abstain(self):
        model = SparseCanonicalReadout()
        for index in range(4):
            model.learn(event(f"yes-{index}", [10], CanonicalClass.YES))
            model.learn(event(f"no-{index}", [10], CanonicalClass.NO))
        result = model.predict([10])
        self.assertIsNone(result.answer)
        self.assertEqual(result.status, "AMBIGUOUS_TIE")

    def test_weights_are_bounded(self):
        model = SparseCanonicalReadout(learning_rate=0.1)
        for index in range(100):
            model.learn(event(f"e{index}", [10], CanonicalClass.YES))
        self.assertLessEqual(max(abs(value) for value in model._weights.values()), 1.0)

    def test_verified_tool_feedback_is_counted_separately(self):
        model = SparseCanonicalReadout()
        model.learn(event(
            "verified", [10], CanonicalClass.UNKNOWN,
            FeedbackProvenance.VERIFIED_TOOL_OUTCOME,
        ))
        counts = model.diagnostics()["feedback_counts_by_provenance"]
        self.assertEqual(counts["VERIFIED_TOOL_OUTCOME"], 1)
        self.assertEqual(counts["EXPLICIT_USER_FEEDBACK"], 0)

    def test_model_does_not_claim_calibration_or_production_integration(self):
        diagnostics = SparseCanonicalReadout().diagnostics()
        self.assertEqual(diagnostics["calibration_status"], "NOT_CALIBRATED")
        self.assertFalse(diagnostics["production_integrated"])


if __name__ == "__main__":
    unittest.main()
