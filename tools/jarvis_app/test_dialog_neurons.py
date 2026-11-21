"""Tests for explicit, local-only dialogue-neuron training."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.jarvis_app.dialog_neurons import DialogNeuronService
from tools.neuron_runtime.dialog_neuron_prototype import DialogNeuronError


class DialogNeuronServiceTests(unittest.TestCase):
    def test_status_exposes_10k_sparse_bank_without_claiming_semantics(self):
        with tempfile.TemporaryDirectory() as directory:
            service = DialogNeuronService(Path(directory) / "dialog.json")
            status = service.status()
        self.assertEqual(status["neuron_count"], 10_000)
        self.assertFalse(status["semantic_understanding"])
        self.assertFalse(status["raw_utterances_persisted"])

    def test_only_explicit_feedback_trains_and_checkpoint_stores_weights_not_text(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dialog.json"
            service = DialogNeuronService(path)
            service.learn("привет джарвис", "greeting")
            payload = path.read_text(encoding="utf-8")
            self.assertNotIn("привет джарвис", payload)
            restored = DialogNeuronService(path)
            self.assertEqual(restored.status()["trained_examples"], 1)

    def test_invalid_label_is_rejected_without_incrementing_examples(self):
        with tempfile.TemporaryDirectory() as directory:
            service = DialogNeuronService(Path(directory) / "dialog.json")
            with self.assertRaises(DialogNeuronError):
                service.learn("привет", "unsupported")
            self.assertEqual(service.status()["trained_examples"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
