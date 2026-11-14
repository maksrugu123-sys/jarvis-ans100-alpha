"""Persistent, explicit-feedback wrapper for the 10,000-feature dialogue prototype.

Only numeric weights and a training counter are persisted. Raw user utterances
are never written to the checkpoint. Learning happens only through the
explicit /learn API operation initiated by the user.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import threading
from typing import Any

from tools.neuron_runtime.dialog_neuron_prototype import (
    DEFAULT_FEATURE_NEURONS,
    DEFAULT_LABELS,
    DialogNeuronError,
    SparseDialogNeuronPrototype,
)


class DialogNeuronService:
    """Thread-safe local classifier/checkpoint adapter."""

    def __init__(self, checkpoint_path: str | os.PathLike[str]) -> None:
        self.checkpoint_path = Path(checkpoint_path).expanduser()
        self.checkpoint_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self._lock = threading.RLock()
        if self.checkpoint_path.exists():
            self._model = SparseDialogNeuronPrototype.load(self.checkpoint_path)
        else:
            self._model = SparseDialogNeuronPrototype(
                neuron_count=DEFAULT_FEATURE_NEURONS,
                labels=DEFAULT_LABELS,
            )

    @staticmethod
    def _prediction_view(prediction: Any, neuron_count: int) -> dict[str, object]:
        return {
            "predicted_label": prediction.label,
            "scores": dict(prediction.scores),
            "confidence_margin": prediction.confidence_margin,
            "active_feature_neurons": len(prediction.active_neurons),
            "trained_examples": prediction.trained_examples,
            "neuron_count": neuron_count,
        }

    def analyze(self, text: str) -> dict[str, object]:
        with self._lock:
            prediction = self._model.predict(text)
            return self._prediction_view(prediction, self._model.neuron_count)

    def status(self) -> dict[str, object]:
        with self._lock:
            return {
                "state": "experimental_sparse_dialogue_classifier",
                "neuron_count": self._model.neuron_count,
                "labels": list(self._model.labels),
                "trained_examples": self._model.trained_examples,
                "learning": "explicit_user_feedback_only",
                "raw_utterances_persisted": False,
                "semantic_understanding": False,
            }

    def _save_locked(self) -> None:
        payload = json.dumps(
            self._model.to_dict(),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ) + "\n"
        temporary = self.checkpoint_path.with_name(self.checkpoint_path.name + ".tmp")
        descriptor = None
        try:
            descriptor = os.open(
                temporary,
                os.O_WRONLY | os.O_CREAT | os.O_TRUNC,
                0o600,
            )
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                descriptor = None
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.checkpoint_path)
            os.chmod(self.checkpoint_path, 0o600)
        except OSError:
            if descriptor is not None:
                os.close(descriptor)
            try:
                temporary.unlink()
            except OSError:
                pass
            raise

    def learn(
        self,
        text: object,
        expected_label: object,
        reward: object = 1.0,
    ) -> dict[str, object]:
        if not isinstance(text, str) or not text.strip() or len(text) > 900 or "\x00" in text:
            raise DialogNeuronError("text must contain 1..900 non-NUL characters")
        if not isinstance(expected_label, str):
            raise DialogNeuronError("expected_label must be a label string")
        with self._lock:
            previous = self._model.to_dict()
            before = self._model.predict(text)
            try:
                self._model.learn(text, expected_label, reward=reward)
                self._save_locked()
            except (DialogNeuronError, OSError, TypeError, ValueError):
                self._model = SparseDialogNeuronPrototype.from_dict(previous)
                raise
            after = self._model.predict(text)
            return {
                "before": self._prediction_view(before, self._model.neuron_count),
                "after": self._prediction_view(after, self._model.neuron_count),
                "status": self.status(),
                "notice": "Сохранены только числовые веса; исходный текст примера не сохранялся.",
            }
