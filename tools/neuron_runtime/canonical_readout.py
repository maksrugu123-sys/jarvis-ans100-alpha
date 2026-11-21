"""Experimental sparse supervised readout for ANS-100.

This is a reference learner, not wired into Android production inference.
Predictions use only observed neuron IDs. Labels enter only through a separate,
explicitly-provenanced feedback method.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import math
import re
from typing import Iterable


class CanonicalClass(str, Enum):
    YES = "YES"
    NO = "NO"
    UNKNOWN = "UNKNOWN"
    CONFLICT = "CONFLICT"


class FeedbackProvenance(str, Enum):
    EXPLICIT_USER_FEEDBACK = "EXPLICIT_USER_FEEDBACK"
    VERIFIED_TOOL_OUTCOME = "VERIFIED_TOOL_OUTCOME"


@dataclass(frozen=True)
class FeedbackEvent:
    event_id: str
    input_sha256: str
    active_neuron_ids: tuple[int, ...]
    target: CanonicalClass
    provenance: FeedbackProvenance


@dataclass(frozen=True)
class ReadoutPrediction:
    answer: CanonicalClass | None
    scores: dict[str, float]
    active_neuron_count: int
    margin: float
    status: str


class SparseCanonicalReadout:
    """Four-channel sparse Hebbian readout with fail-closed abstention.

    The four channels are learned output units, not calibrated probabilities.
    Only explicit feedback or verified external outcomes may update weights.
    """

    MIN_NEURON_ID = 0
    MAX_NEURON_ID_EXCLUSIVE = 9_400
    EXCLUDED_CORE_CANDIDATE_INPUTS = frozenset(range(1_056, 1_062))
    MAX_ACTIVE_NEURONS = 512
    MAX_WEIGHT_ENTRIES = 100_000
    MAX_FEEDBACK_IDS = 4_096
    EVENT_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
    SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")

    def __init__(
        self,
        *,
        learning_rate: float = 0.01,
        weight_limit: float = 1.0,
        minimum_score: float = 0.02,
        minimum_margin: float = 0.01,
    ) -> None:
        for name, value in (
            ("learning_rate", learning_rate),
            ("weight_limit", weight_limit),
            ("minimum_score", minimum_score),
            ("minimum_margin", minimum_margin),
        ):
            if not math.isfinite(value) or value <= 0:
                raise ValueError(f"{name} must be finite and positive")
        if learning_rate > 0.1 or weight_limit > 4.0:
            raise ValueError("learning parameters exceed conservative limits")
        self.learning_rate = learning_rate
        self.weight_limit = weight_limit
        self.minimum_score = minimum_score
        self.minimum_margin = minimum_margin
        self._weights: dict[tuple[int, CanonicalClass], float] = {}
        self._feedback_ids: list[str] = []
        self._feedback_id_set: set[str] = set()
        self._provenance_counts = {item.value: 0 for item in FeedbackProvenance}

    @classmethod
    def _validate_active_neurons(cls, values: Iterable[int]) -> tuple[int, ...]:
        try:
            ids = tuple(values)
        except TypeError as exc:
            raise ValueError("active neuron IDs must be iterable") from exc
        if not ids or len(ids) > cls.MAX_ACTIVE_NEURONS:
            raise ValueError("active neuron count is outside the supported bound")
        if any(type(item) is not int for item in ids):
            raise ValueError("neuron IDs must be integers")
        if len(ids) != len(set(ids)):
            raise ValueError("active neuron IDs must be unique")
        if any(item < cls.MIN_NEURON_ID or item >= cls.MAX_NEURON_ID_EXCLUSIVE for item in ids):
            raise ValueError("active neuron ID is outside the permitted source populations")
        if any(item in cls.EXCLUDED_CORE_CANDIDATE_INPUTS for item in ids):
            raise ValueError("Core-labelled candidate input neurons are forbidden")
        return tuple(sorted(ids))

    def predict(self, active_neuron_ids: Iterable[int]) -> ReadoutPrediction:
        """Infer from observed neural activity only; no target label is accepted."""
        ids = self._validate_active_neurons(active_neuron_ids)
        scores = {
            label.value: math.fsum(self._weights.get((neuron_id, label), 0.0) for neuron_id in ids)
            for label in CanonicalClass
        }
        ranked = sorted(scores.items(), key=lambda item: (-item[1], item[0]))
        best_label, best_score = ranked[0]
        second_score = ranked[1][1]
        margin = best_score - second_score
        if best_score < self.minimum_score:
            return ReadoutPrediction(None, scores, len(ids), margin, "NO_LEARNED_SIGNAL")
        if margin < self.minimum_margin or math.isclose(margin, 0.0, abs_tol=1e-12):
            return ReadoutPrediction(None, scores, len(ids), margin, "AMBIGUOUS_TIE")
        return ReadoutPrediction(CanonicalClass(best_label), scores, len(ids), margin, "UN_CALIBRATED_WINNER")

    def learn(self, event: FeedbackEvent) -> bool:
        """Apply one idempotent reward-modulated update.

        Returns False for a duplicate event ID. It never accepts inferred or
        model-generated labels, and never updates from a prediction alone.
        """
        if not isinstance(event, FeedbackEvent):
            raise ValueError("a FeedbackEvent is required")
        if not isinstance(event.event_id, str) or not self.EVENT_ID_PATTERN.fullmatch(event.event_id):
            raise ValueError("invalid feedback event ID")
        if not isinstance(event.input_sha256, str) or not self.SHA256_PATTERN.fullmatch(event.input_sha256):
            raise ValueError("input_sha256 must be lowercase SHA-256")
        if not isinstance(event.target, CanonicalClass):
            raise ValueError("target must be a canonical class")
        if not isinstance(event.provenance, FeedbackProvenance):
            raise ValueError("untrusted feedback provenance")
        ids = self._validate_active_neurons(event.active_neuron_ids)
        if event.event_id in self._feedback_id_set:
            return False
        new_keys = sum((neuron_id, label) not in self._weights
                       for neuron_id in ids for label in CanonicalClass)
        if len(self._weights) + new_keys > self.MAX_WEIGHT_ENTRIES:
            raise ValueError("readout synapse capacity would be exceeded")

        # One-vs-rest reward modulation. The per-event delta is bounded and
        # symmetric; no score is interpreted as a probability.
        negative_delta = self.learning_rate / (len(CanonicalClass) - 1)
        for neuron_id in ids:
            for label in CanonicalClass:
                delta = self.learning_rate if label is event.target else -negative_delta
                key = (neuron_id, label)
                self._weights[key] = max(
                    -self.weight_limit,
                    min(self.weight_limit, self._weights.get(key, 0.0) + delta),
                )

        self._feedback_ids.append(event.event_id)
        self._feedback_id_set.add(event.event_id)
        if len(self._feedback_ids) > self.MAX_FEEDBACK_IDS:
            expired = self._feedback_ids.pop(0)
            self._feedback_id_set.remove(expired)
        self._provenance_counts[event.provenance.value] += 1
        return True

    def diagnostics(self) -> dict:
        return {
            "schema": "ANS100_SPARSE_CANONICAL_READOUT_LEARNER_V1",
            "synapse_count": len(self._weights),
            "remembered_feedback_ids": len(self._feedback_ids),
            "feedback_counts_by_provenance": dict(self._provenance_counts),
            "learning_rate": self.learning_rate,
            "weight_limit": self.weight_limit,
            "calibration_status": "NOT_CALIBRATED",
            "production_integrated": False,
        }

    def state_sha256(self) -> str:
        rows = [
            f"{neuron_id}:{label.value}:{weight:.12g}"
            for (neuron_id, label), weight in sorted(
                self._weights.items(), key=lambda item: (item[0][0], item[0][1].value)
            )
        ]
        return hashlib.sha256("\n".join(rows).encode("utf-8")).hexdigest()
