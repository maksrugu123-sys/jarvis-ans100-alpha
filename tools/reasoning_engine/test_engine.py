"""Deterministic contract tests for Reasoning Engine V0.1."""
import unittest

from .engine import (
    CriterionObservation,
    EvidenceItem,
    EvidencePolarity,
    HypothesisSpec,
    HypothesisStatus,
    ReasoningBudgetExceeded,
    ReasoningEngineError,
    ReasoningEngineV01,
    ReasoningRequest,
    SubtaskSpec,
    SuccessCriterion,
    VerificationStatus,
)


def request(**changes):
    fields = {
        "request_id": "request-1",
        "idempotency_key": "idem-1",
        "objective": "Prepare a verified local report",
        "subtasks": (
            SubtaskSpec(
                "collect",
                "Collect source facts",
                (),
                (SuccessCriterion("facts-ready", "Required facts are collected"),),
            ),
            SubtaskSpec(
                "review",
                "Review the facts",
                ("collect",),
                (SuccessCriterion("review-ready", "Facts were checked"),),
            ),
        ),
    }
    fields.update(changes)
    return ReasoningRequest(**fields)


class ReasoningPlanTests(unittest.TestCase):
    def setUp(self):
        self.engine = ReasoningEngineV01()

    def test_empty_subtasks_become_one_atomic_objective_step(self):
        plan = self.engine.analyze(request(subtasks=()))
        self.assertEqual(len(plan.steps), 1)
        self.assertEqual(plan.steps[0].task_id, "task-main")
        self.assertEqual(plan.steps[0].title, "Prepare a verified local report")
        self.assertFalse(plan.execution_authorized)

    def test_dependency_graph_is_topologically_ordered(self):
        plan = self.engine.analyze(request())
        self.assertEqual(
            tuple(item.task_id for item in plan.steps),
            ("collect", "review"),
        )
        self.assertEqual(plan.steps[1].depends_on, ("collect",))

    def test_cycle_is_rejected(self):
        cyclic = (
            SubtaskSpec("a", "A", ("b",)),
            SubtaskSpec("b", "B", ("a",)),
        )
        with self.assertRaises(ReasoningEngineError):
            self.engine.analyze(request(subtasks=cyclic))

    def test_missing_dependency_is_rejected(self):
        with self.assertRaises(ReasoningEngineError):
            self.engine.analyze(request(subtasks=(
                SubtaskSpec("a", "A", ("missing",)),
            )))

    def test_plan_depth_is_bounded(self):
        engine = ReasoningEngineV01(max_plan_depth=2)
        chain = (
            SubtaskSpec("a", "A"),
            SubtaskSpec("b", "B", ("a",)),
            SubtaskSpec("c", "C", ("b",)),
        )
        with self.assertRaises(ReasoningBudgetExceeded):
            engine.analyze(request(subtasks=chain))

    def test_plan_id_is_deterministic(self):
        first = self.engine.analyze(request())
        second = self.engine.analyze(request())
        self.assertEqual(first.plan_id, second.plan_id)

    def test_duplicate_task_ids_are_rejected(self):
        with self.assertRaises(ReasoningEngineError):
            self.engine.analyze(request(subtasks=(
                SubtaskSpec("a", "A"),
                SubtaskSpec("a", "Again"),
            )))

    def test_criterion_ids_must_be_globally_unique(self):
        with self.assertRaises(ReasoningEngineError):
            self.engine.analyze(request(subtasks=(
                SubtaskSpec("a", "A", criteria=(SuccessCriterion("same", "One"),)),
                SubtaskSpec("b", "B", criteria=(SuccessCriterion("same", "Two"),)),
            )))

    def test_subtask_budget_is_enforced(self):
        engine = ReasoningEngineV01(max_subtasks=1)
        with self.assertRaises(ReasoningBudgetExceeded):
            engine.analyze(request(subtasks=(
                SubtaskSpec("a", "A"),
                SubtaskSpec("b", "B"),
            )))


class HypothesisEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.engine = ReasoningEngineV01(min_evidence_weight=0.2)

    def test_supporting_evidence_marks_hypothesis_supported(self):
        result = self.engine.analyze(request(
            subtasks=(),
            hypotheses=(HypothesisSpec("h1", "The local file is complete"),),
            evidence=(EvidenceItem(
                "e1", "h1", EvidencePolarity.SUPPORTS, 0.8, "file:sha256:abc"
            ),),
        ))
        item = result.hypotheses[0]
        self.assertEqual(item.status, HypothesisStatus.SUPPORTED)
        self.assertEqual(item.balance_score, 1.0)

    def test_contradicting_evidence_marks_hypothesis_refuted(self):
        result = self.engine.analyze(request(
            subtasks=(),
            hypotheses=(HypothesisSpec("h1", "The file is complete"),),
            evidence=(EvidenceItem(
                "e1", "h1", EvidencePolarity.CONTRADICTS, 0.9, "file:sha256:def"
            ),),
        ))
        self.assertEqual(result.hypotheses[0].status, HypothesisStatus.REFUTED)

    def test_no_evidence_is_insufficient_not_supported(self):
        result = self.engine.analyze(request(
            subtasks=(),
            hypotheses=(HypothesisSpec("h1", "Maybe true"),),
        ))
        self.assertEqual(result.hypotheses[0].status, HypothesisStatus.INSUFFICIENT)
        self.assertEqual(result.hypotheses[0].balance_score, 0.0)

    def test_conflicting_evidence_is_preserved(self):
        result = self.engine.analyze(request(
            subtasks=(),
            hypotheses=(HypothesisSpec("h1", "Claim"),),
            evidence=(
                EvidenceItem("e1", "h1", EvidencePolarity.SUPPORTS, 0.5, "source:a"),
                EvidenceItem("e2", "h1", EvidencePolarity.CONTRADICTS, 0.5, "source:b"),
            ),
        ))
        self.assertEqual(result.hypotheses[0].status, HypothesisStatus.INSUFFICIENT)
        self.assertEqual(len(result.unresolved_contradictions), 1)
        self.assertEqual(
            result.unresolved_contradictions[0].supporting_evidence_ids, ("e1",)
        )
        self.assertEqual(
            result.unresolved_contradictions[0].contradicting_evidence_ids, ("e2",)
        )

    def test_evidence_for_unknown_hypothesis_is_rejected(self):
        with self.assertRaises(ReasoningEngineError):
            self.engine.analyze(request(
                hypotheses=(HypothesisSpec("known", "Claim"),),
                evidence=(EvidenceItem(
                    "e1", "unknown", EvidencePolarity.SUPPORTS, 0.8, "source:a"
                ),),
            ))

    def test_nan_evidence_weight_is_rejected(self):
        with self.assertRaises(ReasoningEngineError):
            self.engine.analyze(request(
                hypotheses=(HypothesisSpec("h1", "Claim"),),
                evidence=(EvidenceItem(
                    "e1", "h1", EvidencePolarity.SUPPORTS, float("nan"), "source:a"
                ),),
            ))


class OutcomeVerificationTests(unittest.TestCase):
    def setUp(self):
        self.engine = ReasoningEngineV01()
        self.plan = self.engine.analyze(request())

    def test_all_steps_and_criteria_with_evidence_verify(self):
        result = self.engine.verify_outcome(
            self.plan,
            completed_step_ids=("collect", "review"),
            observations=(
                CriterionObservation("facts-ready", True, ("evidence:file:1",)),
                CriterionObservation("review-ready", True, ("evidence:file:2",)),
            ),
        )
        self.assertEqual(result.status, VerificationStatus.VERIFIED)
        self.assertEqual(len(result.passed_criterion_ids), 2)

    def test_missing_observation_needs_evidence(self):
        result = self.engine.verify_outcome(
            self.plan,
            completed_step_ids=("collect", "review"),
            observations=(CriterionObservation(
                "facts-ready", True, ("evidence:file:1",)
            ),),
        )
        self.assertEqual(result.status, VerificationStatus.NEEDS_EVIDENCE)
        self.assertEqual(result.missing_criterion_ids, ("review-ready",))

    def test_failed_criterion_is_not_verified(self):
        result = self.engine.verify_outcome(
            self.plan,
            completed_step_ids=("collect", "review"),
            observations=(
                CriterionObservation("facts-ready", True, ("e1",)),
                CriterionObservation("review-ready", False, ("e2",), "Review found a mismatch"),
            ),
        )
        self.assertEqual(result.status, VerificationStatus.NOT_VERIFIED)
        self.assertEqual(result.failed_criterion_ids, ("review-ready",))

    def test_success_without_evidence_reference_is_not_verified(self):
        result = self.engine.verify_outcome(
            self.plan,
            completed_step_ids=("collect", "review"),
            observations=(
                CriterionObservation("facts-ready", True),
                CriterionObservation("review-ready", True, ("e2",)),
            ),
        )
        self.assertEqual(result.status, VerificationStatus.NEEDS_EVIDENCE)
        self.assertIn("facts-ready", result.missing_criterion_ids)

    def test_incomplete_plan_cannot_be_verified(self):
        result = self.engine.verify_outcome(
            self.plan,
            completed_step_ids=("collect",),
            observations=(
                CriterionObservation("facts-ready", True, ("e1",)),
                CriterionObservation("review-ready", True, ("e2",)),
            ),
        )
        self.assertEqual(result.status, VerificationStatus.NEEDS_EVIDENCE)

    def test_completion_cannot_claim_steps_outside_plan(self):
        with self.assertRaises(ReasoningEngineError):
            self.engine.verify_outcome(
                self.plan,
                completed_step_ids=("collect", "review", "delete-all"),
                observations=(),
            )

    def test_verification_rejects_duplicate_observations(self):
        with self.assertRaises(ReasoningEngineError):
            self.engine.verify_outcome(
                self.plan,
                completed_step_ids=("collect", "review"),
                observations=(
                    CriterionObservation("facts-ready", True, ("e1",)),
                    CriterionObservation("facts-ready", True, ("e1",)),
                ),
            )

    def test_plan_without_criteria_needs_evidence(self):
        plan = self.engine.analyze(request(subtasks=()))
        result = self.engine.verify_outcome(
            plan,
            completed_step_ids=("task-main",),
            observations=(),
        )
        self.assertEqual(result.status, VerificationStatus.NEEDS_EVIDENCE)


if __name__ == "__main__":
    unittest.main()
