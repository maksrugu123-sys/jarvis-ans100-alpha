"""Experimental sparse dialogue classifier backed by 10,000 feature neurons.

This is a research prototype, not a language model or a claim of semantic
understanding. It maps deterministic text features to a small intent set and
updates output weights only when a caller supplies an explicit label.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import math
import re
from pathlib import Path
from typing import Mapping

SCHEMA_VERSION = 1
DEFAULT_FEATURE_NEURONS = 10_000
DEFAULT_LABELS = ("greeting", "question", "thanks", "farewell")
_TOKEN_RE = re.compile(r"[^\W_]+", re.UNICODE)


class DialogNeuronError(ValueError):
    """Invalid prototype configuration, input or checkpoint."""


@dataclass(frozen=True)
class DialogPrediction:
    label: str | None
    scores: Mapping[str, float]
    confidence_margin: float
    active_neurons: tuple[int, ...]
    trained_examples: int


class SparseDialogNeuronPrototype:
    """Small online reward-labelled model with a fixed feature-neuron bank.

    Input tokens and adjacent token pairs activate hashed feature neurons.
    Output units are intent labels. Explicit feedback strengthens the expected
    output's active connections and weakens competing outputs (reward-modulated
    Hebbian/perceptron-style update). No user text is retained.
    """

    def __init__(
        self,
        *,
        neuron_count: int = DEFAULT_FEATURE_NEURONS,
        labels: tuple[str, ...] = DEFAULT_LABELS,
        learning_rate: float = 0.15,
        minimum_margin: float = 0.08,
    ) -> None:
        if isinstance(neuron_count, bool) or not isinstance(neuron_count, int) or not 16 <= neuron_count <= 10_000:
            raise DialogNeuronError("neuron_count must be an integer in [16, 10000]")
        if not isinstance(labels, tuple) or len(labels) < 2 or len(set(labels)) != len(labels):
            raise DialogNeuronError("labels must be a tuple of at least two unique labels")
        if any(not isinstance(x, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,31}", x) for x in labels):
            raise DialogNeuronError("labels must be lowercase identifiers")
        for name, value in (("learning_rate", learning_rate), ("minimum_margin", minimum_margin)):
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)) or value <= 0:
                raise DialogNeuronError(f"{name} must be a positive finite number")
        self.neuron_count = neuron_count
        self.labels = labels
        self.learning_rate = float(learning_rate)
        self.minimum_margin = float(minimum_margin)
        self._weights: dict[str, dict[int, float]] = {label: {} for label in labels}
        self._trained_examples = 0

    @property
    def trained_examples(self) -> int:
        return self._trained_examples

    def _features(self, text: str) -> dict[int, float]:
        if not isinstance(text, str):
            raise DialogNeuronError("text must be a string")
        tokens = [t.casefold() for t in _TOKEN_RE.findall(text)[:256]]
        if not tokens:
            return {}
        features = [f"w:{token}" for token in tokens]
        features.extend(f"b:{a}_{b}" for a, b in zip(tokens, tokens[1:]))
        counts: dict[int, float] = {}
        for feature in features:
            digest = hashlib.blake2b(feature.encode("utf-8"), digest_size=8, person=b"JARVIS-D1").digest()
            index = int.from_bytes(digest, "big") % self.neuron_count
            counts[index] = counts.get(index, 0.0) + 1.0
        scale = 1.0 / math.sqrt(sum(v * v for v in counts.values()))
        return {index: value * scale for index, value in counts.items()}

    def predict(self, text: str) -> DialogPrediction:
        active = self._features(text)
        if not active:
            return DialogPrediction(None, {label: 0.0 for label in self.labels}, 0.0, (), self._trained_examples)
        scores = {
            label: sum(self._weights[label].get(index, 0.0) * value for index, value in active.items())
            for label in self.labels
        }
        ranked = sorted(scores.items(), key=lambda item: (-item[1], item[0]))
        margin = ranked[0][1] - ranked[1][1]
        chosen = ranked[0][0] if self._trained_examples and margin >= self.minimum_margin else None
        return DialogPrediction(chosen, scores, margin, tuple(sorted(active)), self._trained_examples)

    def learn(self, text: str, expected_label: str, *, reward: float = 1.0) -> DialogPrediction:
        if expected_label not in self._weights:
            raise DialogNeuronError("expected_label is not in the configured label set")
        if isinstance(reward, bool) or not isinstance(reward, (int, float)) or not math.isfinite(float(reward)) or not 0.0 < reward <= 1.0:
            raise DialogNeuronError("reward must be finite and in (0, 1]")
        active = self._features(text)
        if not active:
            raise DialogNeuronError("cannot train on empty text")
        before = self.predict(text)
        step = self.learning_rate * float(reward)
        for label in self.labels:
            direction = 1.0 if label == expected_label else -1.0 / (len(self.labels) - 1)
            table = self._weights[label]
            for index, value in active.items():
                updated = table.get(index, 0.0) + direction * step * value
                table[index] = max(-4.0, min(4.0, updated))
        self._trained_examples += 1
        return before

    def to_dict(self) -> dict:
        return {
            "schema_version": SCHEMA_VERSION,
            "neuron_count": self.neuron_count,
            "labels": list(self.labels),
            "learning_rate": self.learning_rate,
            "minimum_margin": self.minimum_margin,
            "trained_examples": self._trained_examples,
            "weights": {label: {str(i): value for i, value in sorted(table.items())} for label, table in self._weights.items()},
        }

    @classmethod
    def from_dict(cls, data: object) -> "SparseDialogNeuronPrototype":
        if not isinstance(data, dict) or data.get("schema_version") != SCHEMA_VERSION:
            raise DialogNeuronError("unsupported or malformed checkpoint")
        try:
            model = cls(
                neuron_count=data["neuron_count"],
                labels=tuple(data["labels"]),
                learning_rate=data["learning_rate"],
                minimum_margin=data["minimum_margin"],
            )
            count = data["trained_examples"]
            weights = data["weights"]
            if isinstance(count, bool) or not isinstance(count, int) or count < 0 or not isinstance(weights, dict) or set(weights) != set(model.labels):
                raise ValueError
            parsed: dict[str, dict[int, float]] = {}
            for label, table in weights.items():
                if not isinstance(table, dict):
                    raise ValueError
                parsed[label] = {}
                for raw_index, raw_value in table.items():
                    index, value = int(raw_index), float(raw_value)
                    if str(index) != raw_index or not 0 <= index < model.neuron_count or not math.isfinite(value) or abs(value) > 4.0:
                        raise ValueError
                    parsed[label][index] = value
            model._weights = parsed
            model._trained_examples = count
            return model
        except (KeyError, TypeError, ValueError, OverflowError) as exc:
            raise DialogNeuronError("checkpoint fields are invalid") from exc

    def save(self, path: str | Path) -> None:
        destination = Path(path)
        destination.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(self.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        temporary = destination.with_name(destination.name + ".tmp")
        temporary.write_text(payload + "\n", encoding="utf-8")
        temporary.replace(destination)

    @classmethod
    def load(cls, path: str | Path) -> "SparseDialogNeuronPrototype":
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise DialogNeuronError("checkpoint cannot be read") from exc
        return cls.from_dict(data)
