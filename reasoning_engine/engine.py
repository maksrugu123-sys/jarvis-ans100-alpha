"""Bounded, deterministic Reasoning Engine V0.1 reference implementation.

This module produces plans and evaluates supplied evidence. It does not interpret
free-form language with an ML model, retrieve data, execute tools, authorize
capabilities, or mutate JARVIS runtime state.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import math
import re
from typing import Iterable


_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_HEX_256 = re.compile(r"^[0-9a-f]{64}$")


class ReasoningEngineError(ValueError):
    """Invalid reasoning request or inconsistent plan/evidence."""


class ReasoningBudgetExceeded(ReasoningEngineError):
    """A configured deterministic reasoning budget was exceeded."""


class EvidencePolarity(str, Enum):
    SUPPORTS = "supports"
    CONTRADICTS = "contradicts"


class HypothesisStatus(str, Enum):
    SUPPORTED = "supported"
    REFUTED = "refuted"
    INSUFFICIENT = "insufficient_evidence"


class VerificationStatus(str, Enum):
    VERIFIED = "verified"
    NOT_VERIFIED = "not_verified"
    NEEDS_EVIDENCE = "needs_evidence"


@dataclass(frozen=True)
class SuccessCriterion:
    criterion_id: str
    description: str


@dataclass(frozen=True)
class SubtaskSpec:
    task_id: str
    title: str
    depends_on: tuple[str, ...] = ()
    criteria: tuple[SuccessCriterion, ...] = ()


@dataclass(frozen=True)
class HypothesisSpec:
    hypothesis_id: str
    statement: str


@dataclass(frozen=True)
class EvidenceItem:
    evidence_id: str
    hypothesis_id: str
    polarity: EvidencePolarity
    weight: float
    source_ref: str
    context: str = ""


@dataclass(frozen=True)
class ReasoningRequest:
    request_id: str
    idempotency_key: str
    objective: str
    subtasks: tuple[SubtaskSpec, ...] = ()
    hypotheses: tuple[HypothesisSpec, ...] = ()
    evidence: tuple[EvidenceItem, ...] = ()
    constraints: tuple[str, ...] = ()
    assumptions: tuple[str, ...] = ()


@dataclass(frozen=True)
class PlanStep:
    ordinal: int
    task_id: str
    title: str
    depends_on: tuple[str, ...]
    criteria: tuple[SuccessCriterion, ...]


@dataclass(frozen=True)
class HypothesisAssessment:
    hypothesis_id: str
    statement: str
    status: HypothesisStatus
    support_weight: float
    contradiction_weight: float
    balance_score: float
    supporting_evidence_ids: tuple[str, ...]
    contradicting_evidence_ids: tuple[str, ...]
    has_conflicting_evidence: bool


@dataclass(frozen=True)
class UnresolvedContradiction:
    hypothesis_id: str
    supporting_evidence_ids: tuple[str, ...]
    contradicting_evidence_ids: tuple[str, ...]


@dataclass(frozen=True)
class ReasoningPlan:
    request_id: str
    idempotency_key: str
    plan_id: str
    objective: str
    constraints: tuple[str, ...]
    assumptions: tuple[str, ...]
    steps: tuple[PlanStep, ...]
    hypotheses: tuple[HypothesisAssessment, ...]
    unresolved_contradictions: tuple[UnresolvedContradiction, ...]
    source_evidence_refs: tuple[str, ...]
    execution_authorized: bool = False


@dataclass(frozen=True)
class CriterionObservation:
    criterion_id: str
    passed: bool | None
    evidence_refs: tuple[str, ...] = ()
    note: str = ""


@dataclass(frozen=True)
class OutcomeVerification:
    plan_id: str
    status: VerificationStatus
    completed_step_ids: tuple[str, ...]
    passed_criterion_ids: tuple[str, ...]
    failed_criterion_ids: tuple[str, ...]
    missing_criterion_ids: tuple[str, ...]
    reasons: tuple[str, ...]


def _identifier(name: str, value: object) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise ReasoningEngineError(
            f"{name} must be a stable 1..128 character ASCII identifier"
        )
    return value


def _text(name: str, value: object, limit: int) -> str:
    if not isinstance(value, str):
        raise ReasoningEngineError(f"{name} must be text")
    clean = value.strip()
    if not clean or len(clean) > limit or "\x00" in clean:
        raise ReasoningEngineError(f"{name} must contain 1..{limit} valid characters")
    return clean


def _items(name: str, value: object, item_type: type, limit: int) -> tuple:
    if not isinstance(value, (tuple, list)):
        raise ReasoningEngineError(f"{name} must be a tuple or list")
    result = tuple(value)
    if len(result) > limit:
        raise ReasoningBudgetExceeded(f"{name} exceeds its configured limit ({limit})")
    if any(not isinstance(item, item_type) for item in result):
        raise ReasoningEngineError(f"{name} contains an item of the wrong type")
    return result


def _strings(name: str, values: object, *, limit: int, text_limit: int) -> tuple[str, ...]:
    if not isinstance(values, (tuple, list)):
        raise ReasoningEngineError(f"{name} must be a tuple or list")
    result = tuple(_text(f"{name} item", item, text_limit) for item in values)
    if len(result) > limit:
        raise ReasoningBudgetExceeded(f"{name} exceeds its configured limit ({limit})")
    if len(set(result)) != len(result):
        raise ReasoningEngineError(f"{name} must not contain duplicates")
    return result


class ReasoningEngineV01:
    """Pure bounded planner and evidence evaluator.

    Decomposition is explicit: callers supply structured subtasks. When no
    subtasks are supplied, the engine creates one atomic step for the objective.
    Evidence scores are ranking heuristics, not calibrated probabilities.
    """

    VERSION = "JARVIS_REASONING_ENGINE_V0_1"

    def __init__(
        self,
        *,
        max_subtasks: int = 32,
        max_hypotheses: int = 16,
        max_evidence: int = 128,
        max_criteria: int = 64,
        max_plan_depth: int = 16,
        max_text_chars: int = 4096,
        support_threshold: float = 0.25,
        min_evidence_weight: float = 0.25,
    ) -> None:
        limits = {
            "max_subtasks": (max_subtasks, 128),
            "max_hypotheses": (max_hypotheses, 64),
            "max_evidence": (max_evidence, 1024),
            "max_criteria": (max_criteria, 512),
            "max_plan_depth": (max_plan_depth, 32),
            "max_text_chars": (max_text_chars, 65536),
        }
        for name, (value, maximum) in limits.items():
            if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= maximum:
                raise ReasoningEngineError(f"{name} must be in 1..{maximum}")
        for name, value in (
            ("support_threshold", support_threshold),
            ("min_evidence_weight", min_evidence_weight),
        ):
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ReasoningEngineError(f"{name} must be a finite number in [0, 1]")
            if not math.isfinite(float(value)) or not 0.0 <= float(value) <= 1.0:
                raise ReasoningEngineError(f"{name} must be a finite number in [0, 1]")
        self.max_subtasks = max_subtasks
        self.max_hypotheses = max_hypotheses
        self.max_evidence = max_evidence
        self.max_criteria = max_criteria
        self.max_plan_depth = max_plan_depth
        self.max_text_chars = max_text_chars
        self.support_threshold = float(support_threshold)
        self.min_evidence_weight = float(min_evidence_weight)

    def _normalize_request(self, request: ReasoningRequest) -> ReasoningRequest:
        if not isinstance(request, ReasoningRequest):
            raise ReasoningEngineError("request must be a ReasoningRequest")
        request_id = _identifier("request_id", request.request_id)
        idempotency_key = _identifier("idempotency_key", request.idempotency_key)
        objective = _text("objective", request.objective, 2048)

        raw_tasks = _items("subtasks", request.subtasks, SubtaskSpec, self.max_subtasks)
        if not raw_tasks:
            raw_tasks = (SubtaskSpec("task-main", objective),)

        task_ids: set[str] = set()
        criterion_ids: set[str] = set()
        criteria_count = 0
        tasks: list[SubtaskSpec] = []
        for item in raw_tasks:
            task_id = _identifier("task_id", item.task_id)
            if task_id in task_ids:
                raise ReasoningEngineError("duplicate task_id")
            task_ids.add(task_id)
            title = _text("subtask title", item.title, 512)
            dependencies = _strings(
                "dependencies", item.depends_on, limit=self.max_subtasks, text_limit=128
            )
            raw_criteria = _items(
                "criteria", item.criteria, SuccessCriterion, self.max_criteria
            )
            clean_criteria: list[SuccessCriterion] = []
            for criterion in raw_criteria:
                criterion_id = _identifier("criterion_id", criterion.criterion_id)
                if criterion_id in criterion_ids:
                    raise ReasoningEngineError("criterion IDs must be globally unique")
                criterion_ids.add(criterion_id)
                clean_criteria.append(SuccessCriterion(
                    criterion_id,
                    _text("criterion description", criterion.description, 1024),
                ))
            criteria_count += len(clean_criteria)
            if criteria_count > self.max_criteria:
                raise ReasoningBudgetExceeded("total success criteria exceed configured limit")
            tasks.append(SubtaskSpec(task_id, title, dependencies, tuple(clean_criteria)))

        for item in tasks:
            if item.task_id in item.depends_on:
                raise ReasoningEngineError("a task cannot depend on itself")
            missing = set(item.depends_on) - task_ids
            if missing:
                raise ReasoningEngineError(
                    f"task {item.task_id} references missing dependencies: {sorted(missing)}"
                )

        raw_hypotheses = _items(
            "hypotheses", request.hypotheses, HypothesisSpec, self.max_hypotheses
        )
        hypothesis_ids: set[str] = set()
        hypotheses: list[HypothesisSpec] = []
        for item in raw_hypotheses:
            hypothesis_id = _identifier("hypothesis_id", item.hypothesis_id)
            if hypothesis_id in hypothesis_ids:
                raise ReasoningEngineError("duplicate hypothesis_id")
            hypothesis_ids.add(hypothesis_id)
            hypotheses.append(HypothesisSpec(
                hypothesis_id, _text("hypothesis statement", item.statement, 2048)
            ))

        raw_evidence = _items("evidence", request.evidence, EvidenceItem, self.max_evidence)
        evidence_ids: set[str] = set()
        evidence: list[EvidenceItem] = []
        for item in raw_evidence:
            evidence_id = _identifier("evidence_id", item.evidence_id)
            if evidence_id in evidence_ids:
                raise ReasoningEngineError("duplicate evidence_id")
            evidence_ids.add(evidence_id)
            hypothesis_id = _identifier("evidence hypothesis_id", item.hypothesis_id)
            if hypothesis_id not in hypothesis_ids:
                raise ReasoningEngineError("evidence references an unknown hypothesis")
            try:
                polarity = EvidencePolarity(item.polarity)
            except (TypeError, ValueError) as exc:
                raise ReasoningEngineError("unsupported evidence polarity") from exc
            weight = item.weight
            if isinstance(weight, bool) or not isinstance(weight, (int, float)):
                raise ReasoningEngineError("evidence weight must be a finite number in [0, 1]")
            if not math.isfinite(float(weight)) or not 0.0 <= float(weight) <= 1.0:
                raise ReasoningEngineError("evidence weight must be a finite number in [0, 1]")
            source_ref = _text("evidence source_ref", item.source_ref, 512)
            context = "" if item.context == "" else _text("evidence context", item.context, 1024)
            evidence.append(EvidenceItem(
                evidence_id, hypothesis_id, polarity, float(weight), source_ref, context
            ))

        constraints = _strings(
            "constraints", request.constraints, limit=32, text_limit=1024
        )
        assumptions = _strings(
            "assumptions", request.assumptions, limit=32, text_limit=1024
        )

        total_text = (
            len(objective)
            + sum(len(item.title) for item in tasks)
            + sum(len(c.description) for item in tasks for c in item.criteria)
            + sum(len(item.statement) for item in hypotheses)
            + sum(len(item.source_ref) + len(item.context) for item in evidence)
            + sum(map(len, constraints))
            + sum(map(len, assumptions))
        )
        if total_text > self.max_text_chars:
            raise ReasoningBudgetExceeded(
                f"reasoning text context exceeds {self.max_text_chars} characters"
            )
        return ReasoningRequest(
            request_id, idempotency_key, objective, tuple(tasks),
            tuple(hypotheses), tuple(evidence), constraints, assumptions,
        )

    @staticmethod
    def _topological_order(tasks: tuple[SubtaskSpec, ...]) -> tuple[tuple[SubtaskSpec, ...], int]:
        by_id = {item.task_id: item for item in tasks}
        indegree = {item.task_id: len(item.depends_on) for item in tasks}
        successors: dict[str, list[str]] = {item.task_id: [] for item in tasks}
        for item in tasks:
            for parent in item.depends_on:
                successors[parent].append(item.task_id)
        ready = sorted(task_id for task_id, degree in indegree.items() if degree == 0)
        ordered: list[SubtaskSpec] = []
        depth: dict[str, int] = {}
        while ready:
            task_id = ready.pop(0)
            item = by_id[task_id]
            ordered.append(item)
            depth[task_id] = 1 + max((depth[parent] for parent in item.depends_on), default=0)
            for child in sorted(successors[task_id]):
                indegree[child] -= 1
                if indegree[child] == 0:
                    ready.append(child)
                    ready.sort()
        if len(ordered) != len(tasks):
            raise ReasoningEngineError("subtask dependency graph contains a cycle")
        return tuple(ordered), max(depth.values(), default=0)

    def _assess_hypotheses(
        self,
        hypotheses: tuple[HypothesisSpec, ...],
        evidence: tuple[EvidenceItem, ...],
    ) -> tuple[HypothesisAssessment, ...]:
        by_hypothesis: dict[str, list[EvidenceItem]] = {
            item.hypothesis_id: [] for item in hypotheses
        }
        for item in evidence:
            by_hypothesis[item.hypothesis_id].append(item)

        assessments: list[HypothesisAssessment] = []
        for hypothesis in hypotheses:
            related = by_hypothesis[hypothesis.hypothesis_id]
            supporting = tuple(sorted(
                item.evidence_id for item in related
                if item.polarity == EvidencePolarity.SUPPORTS
            ))
            contradicting = tuple(sorted(
                item.evidence_id for item in related
                if item.polarity == EvidencePolarity.CONTRADICTS
            ))
            support_weight = sum(
                item.weight for item in related if item.polarity == EvidencePolarity.SUPPORTS
            )
            contradiction_weight = sum(
                item.weight for item in related if item.polarity == EvidencePolarity.CONTRADICTS
            )
            total = support_weight + contradiction_weight
            score = 0.0 if total == 0.0 else (support_weight - contradiction_weight) / total
            if total < self.min_evidence_weight:
                status = HypothesisStatus.INSUFFICIENT
            elif score >= self.support_threshold:
                status = HypothesisStatus.SUPPORTED
            elif score <= -self.support_threshold:
                status = HypothesisStatus.REFUTED
            else:
                status = HypothesisStatus.INSUFFICIENT
            assessments.append(HypothesisAssessment(
                hypothesis.hypothesis_id, hypothesis.statement, status,
                support_weight, contradiction_weight, score, supporting,
                contradicting, bool(supporting and contradicting),
            ))
        assessments.sort(key=lambda item: (
            -item.balance_score,
            -(item.support_weight + item.contradiction_weight),
            item.hypothesis_id,
        ))
        return tuple(assessments)

    def analyze(self, request: ReasoningRequest) -> ReasoningPlan:
        """Validate a structured task, construct a bounded DAG and rank hypotheses."""
        clean = self._normalize_request(request)
        ordered, depth = self._topological_order(clean.subtasks)
        if depth > self.max_plan_depth:
            raise ReasoningBudgetExceeded(
                f"plan depth {depth} exceeds configured limit {self.max_plan_depth}"
            )
        steps = tuple(
            PlanStep(
                index + 1, item.task_id, item.title,
                tuple(sorted(item.depends_on)), tuple(item.criteria),
            )
            for index, item in enumerate(ordered)
        )
        assessments = self._assess_hypotheses(clean.hypotheses, clean.evidence)
        contradictions = tuple(
            UnresolvedContradiction(
                item.hypothesis_id,
                item.supporting_evidence_ids,
                item.contradicting_evidence_ids,
            )
            for item in assessments
            if item.has_conflicting_evidence and item.status == HypothesisStatus.INSUFFICIENT
        )
        payload = {
            "engine": self.VERSION,
            "request_id": clean.request_id,
            "idempotency_key": clean.idempotency_key,
            "objective": clean.objective,
            "constraints": list(clean.constraints),
            "assumptions": list(clean.assumptions),
            "steps": [
                {
                    "ordinal": item.ordinal,
                    "task_id": item.task_id,
                    "title": item.title,
                    "depends_on": list(item.depends_on),
                    "criteria": [
                        [criterion.criterion_id, criterion.description]
                        for criterion in item.criteria
                    ],
                }
                for item in steps
            ],
            "hypotheses": [
                {
                    "hypothesis_id": item.hypothesis_id,
                    "status": item.status.value,
                    "support_weight": item.support_weight,
                    "contradiction_weight": item.contradiction_weight,
                    "balance_score": item.balance_score,
                    "supporting": list(item.supporting_evidence_ids),
                    "contradicting": list(item.contradicting_evidence_ids),
                }
                for item in assessments
            ],
        }
        canonical = json.dumps(
            payload, sort_keys=True, separators=(",", ":"),
            ensure_ascii=False, allow_nan=False,
        ).encode("utf-8")
        plan_id = hashlib.sha256(canonical).hexdigest()
        return ReasoningPlan(
            clean.request_id, clean.idempotency_key, plan_id, clean.objective,
            clean.constraints, clean.assumptions, steps, assessments,
            contradictions,
            tuple(sorted(item.source_ref for item in clean.evidence)),
            False,
        )

    def verify_outcome(
        self,
        plan: ReasoningPlan,
        *,
        completed_step_ids: Iterable[str],
        observations: Iterable[CriterionObservation],
    ) -> OutcomeVerification:
        """Verify caller-supplied observations; never infer success from dispatch."""
        if not isinstance(plan, ReasoningPlan) or not _HEX_256.fullmatch(plan.plan_id):
            raise ReasoningEngineError("plan must be a valid ReasoningPlan")
        try:
            completed = tuple(_identifier("completed_step_id", item) for item in completed_step_ids)
            checks = tuple(observations)
        except TypeError as exc:
            raise ReasoningEngineError("verification inputs must be iterable") from exc
        if len(set(completed)) != len(completed):
            raise ReasoningEngineError("completed_step_ids must be unique")
        step_ids = {item.task_id for item in plan.steps}
        if set(completed) - step_ids:
            raise ReasoningEngineError("completed_step_ids contains a step outside the plan")

        criteria = {
            criterion.criterion_id
            for step in plan.steps
            for criterion in step.criteria
        }
        if len(checks) > self.max_criteria:
            raise ReasoningBudgetExceeded("observations exceed max_criteria")
        by_criterion: dict[str, CriterionObservation] = {}
        for item in checks:
            if not isinstance(item, CriterionObservation):
                raise ReasoningEngineError("observations contain an item of the wrong type")
            criterion_id = _identifier("criterion_id", item.criterion_id)
            if criterion_id not in criteria:
                raise ReasoningEngineError("observation references a criterion outside the plan")
            if criterion_id in by_criterion:
                raise ReasoningEngineError("duplicate criterion observation")
            if item.passed not in (True, False, None) or type(item.passed) not in (bool, type(None)):
                raise ReasoningEngineError("criterion passed must be bool or None")
            refs = _strings(
                "evidence_refs", item.evidence_refs, limit=32, text_limit=512
            )
            note = "" if item.note == "" else _text("verification note", item.note, 512)
            by_criterion[criterion_id] = CriterionObservation(
                criterion_id, item.passed, refs, note
            )

        all_step_ids = tuple(item.task_id for item in plan.steps)
        all_criteria = tuple(sorted(criteria))
        passed: list[str] = []
        failed: list[str] = []
        missing: list[str] = []
        reasons: list[str] = []

        missing_steps = tuple(item for item in all_step_ids if item not in completed)
        if missing_steps:
            reasons.append("not all planned steps are reported complete")
        if not all_criteria:
            reasons.append("the plan has no explicit acceptance criteria")
        for criterion_id in all_criteria:
            observation = by_criterion.get(criterion_id)
            if observation is None or observation.passed is None:
                missing.append(criterion_id)
            elif observation.passed is False:
                failed.append(criterion_id)
            elif not observation.evidence_refs:
                missing.append(criterion_id)
                reasons.append(f"criterion {criterion_id} has no evidence reference")
            else:
                passed.append(criterion_id)

        if failed:
            status = VerificationStatus.NOT_VERIFIED
            reasons.insert(0, "one or more acceptance criteria failed")
        elif missing_steps or missing or not all_criteria:
            status = VerificationStatus.NEEDS_EVIDENCE
        else:
            status = VerificationStatus.VERIFIED
            reasons.append("all planned steps and acceptance criteria have supplied evidence references")

        return OutcomeVerification(
            plan.plan_id, status, tuple(sorted(completed)), tuple(passed),
            tuple(failed), tuple(missing), tuple(reasons),
        )


__all__ = [
    "CriterionObservation",
    "EvidenceItem",
    "EvidencePolarity",
    "HypothesisAssessment",
    "HypothesisSpec",
    "HypothesisStatus",
    "OutcomeVerification",
    "PlanStep",
    "ReasoningBudgetExceeded",
    "ReasoningEngineError",
    "ReasoningEngineV01",
    "ReasoningPlan",
    "ReasoningRequest",
    "SubtaskSpec",
    "SuccessCriterion",
    "UnresolvedContradiction",
    "VerificationStatus",
]
