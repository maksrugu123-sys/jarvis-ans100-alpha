"""Tests for the first sparse dialogue-neuron experiment."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.neuron_runtime.dialog_neuron_prototype import (
    DEFAULT_FEATURE_NEURONS,
    DialogNeuronError,
    SparseDialogNeuronPrototype,
)


class DialogNeuronPrototypeTests(unittest.TestCase):
    def test_default_bank_has_ten_thousand_addressable_feature_neurons(self):
        model = SparseDialogNeuronPrototype()
        prediction = model.predict("Привет, Джарвис!")
        self.assertEqual(model.neuron_count, DEFAULT_FEATURE_NEURONS)
        self.assertTrue(all(0 <= index < 10_000 for index in prediction.active_neurons))
        self.assertLessEqual(len(prediction.active_neurons), 512)

    def test_untrained_model_does_not_guess_intent(self):
        self.assertIsNone(SparseDialogNeuronPrototype().predict("Привет").label)

    def test_explicit_feedback_changes_prediction(self):
        model = SparseDialogNeuronPrototype(minimum_margin=0.01)
        for _ in range(8):
            model.learn("привет джарвис здравствуй", "greeting")
        self.assertEqual(model.predict("привет джарвис").label, "greeting")

    def test_feedback_is_reproducible(self):
        a, b = SparseDialogNeuronPrototype(), SparseDialogNeuronPrototype()
        for _ in range(3):
            a.learn("спасибо большое", "thanks")
            b.learn("спасибо большое", "thanks")
        self.assertEqual(a.to_dict(), b.to_dict())

    def test_empty_or_unknown_feedback_is_rejected(self):
        model = SparseDialogNeuronPrototype()
        with self.assertRaises(DialogNeuronError):
            model.learn("   ", "greeting")
        with self.assertRaises(DialogNeuronError):
            model.learn("hello", "made_up")

    def test_checkpoint_round_trip_preserves_predictions(self):
        model = SparseDialogNeuronPrototype()
        model.learn("пока до свидания", "farewell")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dialog.json"
            model.save(path)
            restored = SparseDialogNeuronPrototype.load(path)
        self.assertEqual(model.to_dict(), restored.to_dict())
        self.assertEqual(model.predict("пока").label, restored.predict("пока").label)

    def test_corrupt_checkpoint_is_rejected(self):
        with self.assertRaises(DialogNeuronError):
            SparseDialogNeuronPrototype.from_dict({"schema_version": 999})


if __name__ == "__main__":
    unittest.main(verbosity=2)
