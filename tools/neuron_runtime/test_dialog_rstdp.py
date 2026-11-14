"""Persistent explicit-feedback dialogue R-STDP experiment tests."""
import tempfile
import unittest
import uuid
from unittest.mock import patch

from tools.neuron_runtime.dialog_rstdp import (
    DialogRstdpError,
    DialogRstdpLearningService,
    LABELS,
)


class DialogRstdpLearningServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.service = DialogRstdpLearningService(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.service.close)

    def test_explicit_label_reward_changes_real_synapses_and_survives_restart(self):
        text = "hello jarvis good morning"
        before = self.service.analyze(text)
        self.assertIsNone(before["predicted_label"])
        feedback_id = str(uuid.uuid4())
        result = self.service.learn(text, "greeting", feedback_id)

        self.assertEqual(result["status"], "APPLIED")
        self.assertEqual(result["expected_label"], "greeting")
        self.assertEqual(result["output_spikes"], sorted([f"dlg_{label}" for label in LABELS]))
        self.assertGreater(result["scores_after"]["greeting"], result["scores_before"]["greeting"])
        self.assertEqual(self.service.status()["trained_examples_per_label"], {label: 1 for label in LABELS})
        for path in (self.service.network_path, self.service.baseline_path):
            self.assertNotIn(text.encode("utf-8"), path.read_bytes())

        self.service.close()
        restored = DialogRstdpLearningService(self.temp.name)
        self.assertEqual(restored.status()["trained_examples_per_label"], {label: 1 for label in LABELS})
        self.assertEqual(restored.analyze(text)["predicted_label"], "greeting")
        restored.close()

    def test_duplicate_feedback_id_is_idempotent(self):
        feedback_id = str(uuid.uuid4())
        first = self.service.learn("thank you jarvis", "thanks", feedback_id)
        version = self.service.status()["network_record_version"]
        second = self.service.learn("thank you jarvis", "thanks", feedback_id)
        self.assertEqual(first["status"], "APPLIED")
        self.assertEqual(second["status"], "DUPLICATE")
        self.assertEqual(self.service.status()["network_record_version"], version)

    def test_invalid_label_and_empty_text_are_rejected_before_learning(self):
        with self.assertRaises(DialogRstdpError):
            self.service.learn("hello", "unsupported", str(uuid.uuid4()))
        with self.assertRaises(DialogRstdpError):
            self.service.learn("   ", "greeting", str(uuid.uuid4()))
        self.assertEqual(self.service.status()["trained_examples_per_label"], {label: 0 for label in LABELS})

    def test_pending_feedback_recovers_after_interrupted_network_reward(self):
        feedback_id = str(uuid.uuid4())
        with patch.object(
            self.service._coordinator, "apply_rewards_batch",
            side_effect=OSError("injected before network reward commit"),
        ):
            with self.assertRaises(OSError):
                self.service.learn("hello jarvis", "greeting", feedback_id)
        self.service.close()

        restored = DialogRstdpLearningService(self.temp.name)
        self.assertEqual(restored.status()["trained_examples_per_label"], {label: 1 for label in LABELS})
        self.assertEqual(restored.learn("hello jarvis", "greeting", feedback_id)["status"], "DUPLICATE")
        restored.close()

    def test_baseline_commit_recovery_does_not_apply_network_reward_twice(self):
        feedback_id = str(uuid.uuid4())
        original_save = self.service._baseline_store.save
        calls = {"count": 0}

        def fail_second_save(*args, **kwargs):
            calls["count"] += 1
            if calls["count"] == 2:
                raise OSError("injected after atomic neural reward")
            return original_save(*args, **kwargs)

        with patch.object(self.service._baseline_store, "save", side_effect=fail_second_save):
            with self.assertRaises(OSError):
                self.service.learn("thanks for your help", "thanks", feedback_id)
        self.service.close()

        restored = DialogRstdpLearningService(self.temp.name)
        self.assertEqual(restored.status()["trained_examples_per_label"], {label: 1 for label in LABELS})
        self.assertEqual(restored.learn("thanks for your help", "thanks", feedback_id)["status"], "DUPLICATE")
        restored.close()


if __name__ == "__main__":
    unittest.main()
