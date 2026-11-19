"""Deterministic attention-budget policy for bounded neuron-batch scheduling.

This module is a pure policy layer. NetworkRuntime does not yet use it:
execution integration requires resumable per-neuron timestamp transactions so
that a same-time batch can be budgeted without losing simultaneity or rollback.
"""
from __future__ import annotations

from dataclasses import dataclass
import math
from types import MappingProxyType
from typing import Mapping, Sequence

_U64_MAX = (1 << 64) - 1


class AttentionSchedulerError(ValueError):
    """Invalid attention candidate, budget, or scheduler state."""


def _unit(name: str, value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise AttentionSchedulerError(f"{name} must be numeric")
    number = float(value)
    if not math.isfinite(number) or not 0.0 <= number <= 1.0:
        raise AttentionSchedulerError(f"{name} must be finite and in [0, 1]")
    return number


@dataclass(frozen=True)
class AttentionCandidate:
    neuron_id: str
    salience: float
    uncertainty: float
    prediction_error: float

    def validate(self) -> None:
        if not isinstance(self.neuron_id, str) or not self.neuron_id.strip() or len(self.neuron_id) > 128:
            raise AttentionSchedulerError("neuron_id must be a non-empty string of at most 128 characters")
        _unit("salience", self.salience)
        _unit("uncertainty", self.uncertainty)
        _unit("prediction_error", self.prediction_error)

    @property
    def base_priority(self) -> float:
        self.validate()
        return (
            0.50 * float(self.salience)
            + 0.25 * float(self.uncertainty)
            + 0.25 * float(self.prediction_error)
        )


@dataclass(frozen=True)
class AttentionSchedule:
    selected: tuple[str, ...]
    deferred: tuple[str, ...]
    wait_cycles: Mapping[str, int]


def schedule_attention_batches(
    candidates: Sequence[AttentionCandidate],
    *,
    budget: int,
    wait_cycles: Mapping[str, int] | None = None,
    aging_step: float = 0.05,
) -> AttentionSchedule:
    """Select at most budget batches; aging makes deferred work gain priority.

    The policy is deterministic: effective priority is base priority plus
    aging_step * wait_cycles; ties use greater wait age, then base priority,
    then ascending neuron ID. Selected candidates reset their wait age to zero.
    """
    if not isinstance(candidates, (tuple, list)):
        raise AttentionSchedulerError("candidates must be a list or tuple")
    if isinstance(budget, bool) or not isinstance(budget, int) or not 1 <= budget <= 1_000_000:
        raise AttentionSchedulerError("budget must be an integer in [1, 1000000]")
    if isinstance(aging_step, bool) or not isinstance(aging_step, (int, float)):
        raise AttentionSchedulerError("aging_step must be numeric")
    aging_step = float(aging_step)
    if not math.isfinite(aging_step) or not 0.0 < aging_step <= 1.0:
        raise AttentionSchedulerError("aging_step must be finite and in (0, 1]")
    if wait_cycles is None:
        wait_cycles = {}
    if not isinstance(wait_cycles, Mapping):
        raise AttentionSchedulerError("wait_cycles must be a mapping")

    by_id: dict[str, AttentionCandidate] = {}
    for candidate in candidates:
        if not isinstance(candidate, AttentionCandidate):
            raise AttentionSchedulerError("candidates must contain AttentionCandidate values")
        candidate.validate()
        if candidate.neuron_id in by_id:
            raise AttentionSchedulerError("candidate neuron IDs must be unique")
        by_id[candidate.neuron_id] = candidate

    if set(wait_cycles) - set(by_id):
        raise AttentionSchedulerError("wait_cycles contains an unknown candidate")
    ages: dict[str, int] = {}
    for neuron_id in by_id:
        age = wait_cycles.get(neuron_id, 0)
        if isinstance(age, bool) or not isinstance(age, int) or not 0 <= age <= _U64_MAX:
            raise AttentionSchedulerError("wait cycle counts must be unsigned 64-bit integers")
        ages[neuron_id] = age

    ranked = sorted(
        by_id.values(),
        key=lambda item: (
            -(item.base_priority + aging_step * ages[item.neuron_id]),
            -ages[item.neuron_id],
            -item.base_priority,
            item.neuron_id,
        ),
    )
    selected = tuple(item.neuron_id for item in ranked[:budget])
    deferred = tuple(item.neuron_id for item in ranked[budget:])
    next_ages = {}
    selected_set = set(selected)
    for neuron_id in by_id:
        age = ages[neuron_id]
        next_ages[neuron_id] = 0 if neuron_id in selected_set else min(_U64_MAX, age + 1)
    return AttentionSchedule(selected, deferred, MappingProxyType(next_ages))
