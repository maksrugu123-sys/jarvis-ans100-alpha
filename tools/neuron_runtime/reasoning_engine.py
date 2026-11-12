"""Bounded symbolic planning and evidence review for JARVIS Reasoning Engine V0.1.

This is a deterministic reference engine. It plans over caller-supplied facts and
actions; it does not execute actions, retrieve evidence, or interpret natural
language.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from enum import Enum
import re
from typing import Iterable


_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_MAX_TEXT = 1024


class ReasoningError(ValueError):
    """Invalid reasoning request, plan, evidence record, or verification input."""


class EvidencePolarity(str, Enum):
    SUPPORTS = "supports"
    REFUTES = "refutes"


class EvidenceQuality(str, Enum):
    UNVERIFIED = "unverified"
    PRIMARY = "primary"
    INDEPENDENT = "independent"
    DERIVED = "derived"


class HypothesisStatus(str, Enum):
    INSUFFICIENT = "insufficient"
    SUPPORT_ONLY = "support_only"
    REFUTE_ONLY = "refute_only"
    MIXED = "mixed"


class PlanStatus(str, Enum):
    SOLVED = "solved"
    NO_PLAN = "no_plan"
    DEPTH_LIMIT = "depth_limit"
    SEARCH_LIMIT = "search_limit"


class VerificationStatus(str, Enum):
    GOAL_SATISFIED = "goal_satisfied"
    GOAL_INCOMPLETE = "goal_incomplete"


def _identifier(name: str, value: object) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise ReasoningError(f"{name} must be a stable 1..128 character identifier")
    return value


def _text(name: str, value: object, limit: int = _MAX_TEXT) -> str:
    if not isinstance(value, str):
        raise ReasoningError(f"{name} must be text")
    clean = value.strip()
    if not clean or len(clean) > limit or "\x00" in clean:
        raise ReasoningError(f"{name} must contain 1..{limit} characters without NUL")
    return clean


def _facts(name: str, values: object, *, maximum: int) -> tuple[str, ...]:
    if not isinstance(values, (tuple, list)):
        raise ReasoningError(f"{name} must be a tuple or list")
    result = tuple(_identifier(f"{name} item", item) for item in values)
    if len(result) > maximum or len(set(result)) != len(result):
        raise ReasoningError(f"{name} exceeds its limit or contains duplicates")
    return tuple(sorted(result))


@dataclass(frozen=True)
class PlanningAction:
    """A pure state transition; no Python callable or external side effect is stored."""

    action_id: str
    summary: str
    preconditions: tuple[str, ...]
    add_facts: tuple[str, ...]
    delete_facts: tuple[str, ...] = ()
    rationale: str = ""

    def __post_init__(self) -> None:
        _identifier("action_id", self.action_id)
        _text("action summary", self.summary, 256)
        _text("action rationale", self.rationale, 512) if self.rationale else None
        pre = _facts("preconditions", self.preconditions, maximum=256)
        add = _facts("add_facts", self.add_facts, maximum=256)
        delete = _facts("delete_facts", self.delete_facts, maximum=256)
        if not add and not delete:
            raise ReasoningError("an action must change at least one fact")
        if set(add) & set(delete):
            raise ReasoningError("one action cannot both add and delete the same fact")
        object.__setattr__(self, "preconditions", pre)
        object.__setattr__(self, "add_facts", add)
        object.__setattr__(self, "delete_facts", delete)


@dataclass(frozen=True)
class Hypothesis:
    hypothesis_id: str
    statement: str

    def __post_init__(self) -> None:
        _identifier("hypothesis_id", self.hypothesis_id)
        _text("hypothesis statement", self.statement, 512)


@dataclass(frozen=True)
class EvidenceRecord:
    evidence_id: str
    hypothesis_id: str
    polarity: EvidencePolarity
    source_ref: str
    quality: EvidenceQuality
    summary: str

    def __post_init__(self) -> None:
        _identifier("evidence_id", self.evidence_id)
        _identifier("hypothesis_id", self.hypothesis_id)
        try:
            object.__setattr__(self, "polarity", EvidencePolarity(self.polarity))
            object.__setattr__(self, "quality", EvidenceQuality(self.quality))
        except ValueError as exc:
            raise ReasoningError("unsupported evidence polarity or quality") from exc
        _text("evidence source_ref", self.source_ref, 512)
        _text("evidence summary", self.summary, 512)


@dataclass(frozen=True)
class ReasoningProblem:
    task_id: str
    goal_description: str
    initial_facts: tuple[str, ...]
    goal_facts: tuple[str, ...]
    actions: tuple[PlanningAction, ...]
    hypotheses: tuple[Hypothesis, ...] = ()
    evidence: tuple[EvidenceRecord, ...] = ()


@dataclass(frozen=True)
class HypothesisAssessment:
    hypothesis_id: str
    status: HypothesisStatus
    supporting_evidence_ids: tuple[str, ...]
    refuting_evidence_ids: tuple[str, ...]
    quality_labels: tuple[str, ...]


@dataclass(frozen=True)
class ReasoningStep:
    index: int
    action_id: str
    summary: str
    rationale: str
    required_facts: tuple[str, ...]
    added_facts: tuple[str, ...]
    deleted_facts: tuple[str, ...]
    facts_after_step: tuple[str, ...]


@dataclass(frozen=True)
class ReasoningResult:
    task_id: str
    goal_description: str
    status: PlanStatus
    goal_facts: tuple[str, ...]
    initial_facts: tuple[str, ...]
    predicted_final_facts: tuple[str, ...]
    plan: tuple[ReasoningStep, ...]
    hypothesis_assessments: tuple[HypothesisAssessment, ...]
    expanded_states: int


@dataclass(frozen=True)
class ResultVerification:
    task_id: str
    status: VerificationStatus
    missing_goal_facts: tuple[str, ...]
    missing_predicted_facts: tuple[str, ...]
    unexpected_observed_facts: tuple[str, ...]


def _assess_hypotheses(
    hypotheses: tuple[Hypothesis, ...],
    evidence: tuple[EvidenceRecord, ...],
) -> tuple[HypothesisAssessment, ...]:
    hypothesis_ids = {item.hypothesis_id for item in hypotheses}
    if len(hypothesis_ids) != len(hypotheses):
        raise ReasoningError("hypothesis IDs must be unique")
    evidence_ids = [item.evidence_id for item in evidence]
    if len(set(evidence_ids)) != len(evidence_ids):
        raise ReasoningError("evidence IDs must be unique")
    for item in evidence:
        if item.hypothesis_id not in hypothesis_ids:
            raise ReasoningError("evidence references an unknown hypothesis")
    results = []
    for hypothesis in sorted(hypotheses, key=lambda item: item.hypothesis_id):
        related = [item for item in evidence if item.hypothesis_id == hypothesis.hypothesis_id]
        supporting = tuple(sorted(item.evidence_id for item in related if item.polarity is EvidencePolarity.SUPPORTS))
        refuting = tuple(sorted(item.evidence_id for item in related if item.polarity is EvidencePolarity.REFUTES))
        if supporting and refuting:
            status = HypothesisStatus.MIXED
        elif supporting:
            status = HypothesisStatus.SUPPORT_ONLY
        elif refuting:
            status = HypothesisStatus.REFUTE_ONLY
        else:
            status = HypothesisStatus.INSUFFICIENT
        labels = tuple(sorted({item.quality.value for item in related}))
        results.append(HypothesisAssessment(
            hypothesis.hypothesis_id, status, supporting, refuting, labels
        ))
    return tuple(results)


class ReasoningEngine:
    """Bounded deterministic breadth-first planner and evidence assessor."""

    def __init__(
        self,
        *,
        max_states: int = 2048,
        max_depth: int = 32,
        max_actions: int = 256,
        max_facts: int = 2048,
        max_hypotheses: int = 128,
        max_evidence: int = 1024,
    ) -> None:
        limits = {
            "max_states": (max_states, 1, 100_000),
            "max_depth": (max_depth, 1, 256),
            "max_actions": (max_actions, 1, 4096),
            "max_facts": (max_facts, 1, 16_384),
            "max_hypotheses": (max_hypotheses, 1, 4096),
            "max_evidence": (max_evidence, 1, 16_384),
        }
        for name, (value, low, high) in limits.items():
            if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
                raise ReasoningError(f"{name} must be in {low}..{high}")
            setattr(self, name, value)

    def _validate_problem(self, problem: ReasoningProblem):
        if not isinstance(problem, ReasoningProblem):
            raise ReasoningError("problem must be a ReasoningProblem")
        task_id = _identifier("task_id", problem.task_id)
        description = _text("goal_description", problem.goal_description, 512)
        initial = _facts("initial_facts", problem.initial_facts, maximum=self.max_facts)
        goals = _facts("goal_facts", problem.goal_facts, maximum=self.max_facts)
        if not goals:
            raise ReasoningError("goal_facts must not be empty")
        if len(initial) > self.max_facts:
            raise ReasoningError("initial_facts exceeds max_facts")
        if not isinstance(problem.actions, (tuple, list)) or len(problem.actions) > self.max_actions:
            raise ReasoningError("actions must be a bounded tuple or list")
        actions = tuple(problem.actions)
        if any(not isinstance(item, PlanningAction) for item in actions):
            raise ReasoningError("actions contains a non-PlanningAction item")
        if len({item.action_id for item in actions}) != len(actions):
            raise ReasoningError("action IDs must be unique")
        if not isinstance(problem.hypotheses, (tuple, list)) or len(problem.hypotheses) > self.max_hypotheses:
            raise ReasoningError("hypotheses must be a bounded tuple or list")
        if not isinstance(problem.evidence, (tuple, list)) or len(problem.evidence) > self.max_evidence:
            raise ReasoningError("evidence must be a bounded tuple or list")
        hypotheses = tuple(problem.hypotheses)
        evidence = tuple(problem.evidence)
        if any(not isinstance(item, Hypothesis) for item in hypotheses):
            raise ReasoningError("hypotheses contains an invalid record")
        if any(not isinstance(item, EvidenceRecord) for item in evidence):
            raise ReasoningError("evidence contains an invalid record")
        assessments = _assess_hypotheses(hypotheses, evidence)
        for action in actions:
            all_facts = set(action.preconditions) | set(action.add_facts) | set(action.delete_facts)
            if len(all_facts) > self.max_facts:
                raise ReasoningError("one action references more than max_facts facts")
        return task_id, description, initial, goals, tuple(sorted(actions, key=lambda a: a.action_id)), assessments

    def reason(self, problem: ReasoningProblem) -> ReasoningResult:
        """Build a shortest deterministic plan without executing its actions."""
        task_id, description, initial, goals, actions, assessments = self._validate_problem(problem)
        initial_state = frozenset(initial)
        goal_state = frozenset(goals)
        queue = deque([(initial_state, ())])
        seen = {initial_state}
        expanded = 0
        hit_depth_limit = False
        hit_state_limit = False
        hit_fact_limit = False

        while queue:
            state, path = queue.popleft()
            if goal_state.issubset(state):
                steps = []
                current = set(initial_state)
                for index, action in enumerate(path, start=1):
                    next_state = (current - set(action.delete_facts)) | set(action.add_facts)
                    steps.append(ReasoningStep(
                        index=index,
                        action_id=action.action_id,
                        summary=action.summary,
                        rationale=action.rationale,
                        required_facts=action.preconditions,
                        added_facts=action.add_facts,
                        deleted_facts=action.delete_facts,
                        facts_after_step=tuple(sorted(next_state)),
                    ))
                    current = next_state
                return ReasoningResult(
                    task_id, description, PlanStatus.SOLVED, goals, initial,
                    tuple(sorted(state)), tuple(steps), assessments, expanded,
                )

            if len(path) >= self.max_depth:
                for action in actions:
                    if not set(action.preconditions).issubset(state):
                        continue
                    next_state = (state - set(action.delete_facts)) | set(action.add_facts)
                    if next_state == state or next_state in seen:
                        continue
                    if len(next_state) > self.max_facts:
                        hit_fact_limit = True
                    else:
                        hit_depth_limit = True
                continue

            expanded += 1
            for action in actions:
                if not set(action.preconditions).issubset(state):
                    continue
                next_state = (state - set(action.delete_facts)) | set(action.add_facts)
                if next_state == state or next_state in seen:
                    continue
                if len(next_state) > self.max_facts:
                    hit_fact_limit = True
                    continue
                if len(seen) >= self.max_states:
                    hit_state_limit = True
                    break
                seen.add(next_state)
                queue.append((next_state, path + (action,)))
            if hit_state_limit:
                break

        status = (
            PlanStatus.SEARCH_LIMIT if hit_state_limit or hit_fact_limit
            else PlanStatus.DEPTH_LIMIT if hit_depth_limit
            else PlanStatus.NO_PLAN
        )
        return ReasoningResult(
            task_id, description, status, goals, initial, initial, (),
            assessments, expanded,
        )

    def verify_result(
        self,
        result: ReasoningResult,
        observed_facts: Iterable[str],
    ) -> ResultVerification:
        """Compare caller-supplied observed facts with the plan's declared goal."""
        if not isinstance(result, ReasoningResult):
            raise ReasoningError("result must be a ReasoningResult")
        if result.status is not PlanStatus.SOLVED:
            raise ReasoningError("only a solved plan can be result-verified")
        if isinstance(observed_facts, (str, bytes)):
            raise ReasoningError("observed_facts must be an iterable of fact identifiers")
        try:
            observed = _facts("observed_facts", tuple(observed_facts), maximum=self.max_facts)
        except TypeError as exc:
            raise ReasoningError("observed_facts must be iterable") from exc
        observed_set = set(observed)
        goal_set = set(result.goal_facts)
        predicted_set = set(result.predicted_final_facts)
        missing_goal = tuple(sorted(goal_set - observed_set))
        missing_predicted = tuple(sorted(predicted_set - observed_set))
        unexpected = tuple(sorted(observed_set - predicted_set))
        return ResultVerification(
            result.task_id,
            VerificationStatus.GOAL_INCOMPLETE if missing_goal else VerificationStatus.GOAL_SATISFIED,
            missing_goal,
            missing_predicted,
            unexpected,
        )
