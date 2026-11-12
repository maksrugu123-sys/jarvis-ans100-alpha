"""Contract tests for the bounded symbolic Reasoning Engine V0.1."""
import unittest

from tools.neuron_runtime.reasoning_engine import (
    EvidencePolarity,
    EvidenceQuality,
    EvidenceRecord,
    Hypothesis,
    HypothesisStatus,
    PlanStatus,
    PlanningAction,
    ReasoningEngine,
    ReasoningError,
    ReasoningProblem,
    VerificationStatus,
)


def _action(action_id, requires=(), adds=(), deletes=()):
    return PlanningAction(
        action_id, f"Action {action_id}", tuple(requires), tuple(adds),
        tuple(deletes), f"Reason for {action_id}",
    )


class ReasoningActionTests(unittest.TestCase):
    def test_action_rejects_no_state_change(self):
        with self.assertRaises(ReasoningError):
            _action("noop")

    def test_action_rejects_overlapping_add_and_delete(self):
        with self.assertRaises(ReasoningError):
            _action("invalid", adds=("ready",), deletes=("ready",))

    def test_action_normalizes_fact_order(self):
        action = PlanningAction("ordered", "Ordered facts", ("b", "a"), ("z", "y"))
        self.assertEqual(action.preconditions, ("a", "b"))
        self.assertEqual(action.add_facts, ("y", "z"))


class ReasoningPlanningTests(unittest.TestCase):
    def setUp(self):
        self.engine = ReasoningEngine()

    def test_breadth_first_search_returns_shortest_plan(self):
        problem = ReasoningProblem(
            "task-1", "Reach target", ("start",), ("target",),
            (
                _action("step-a", ("start",), ("middle",)),
                _action("step-b", ("middle",), ("target",)),
                _action("direct", ("start",), ("target",)),
            ),
        )
        result = self.engine.reason(problem)
        self.assertIs(result.status, PlanStatus.SOLVED)
        self.assertEqual([step.action_id for step in result.plan], ["direct"])
        self.assertEqual(result.predicted_final_facts, ("start", "target"))

    def test_action_order_makes_equal_length_plans_deterministic(self):
        problem = ReasoningProblem(
            "task-1", "Choose stable route", ("start",), ("goal",),
            (
                _action("z-route", ("start",), ("goal",)),
                _action("a-route", ("start",), ("goal",)),
            ),
        )
        self.assertEqual(
            self.engine.reason(problem).plan[0].action_id,
            self.engine.reason(problem).plan[0].action_id,
        )
        self.assertEqual(self.engine.reason(problem).plan[0].action_id, "a-route")

    def test_already_satisfied_goal_has_empty_plan(self):
        result = self.engine.reason(ReasoningProblem(
            "task-1", "Already ready", ("ready",), ("ready",), ()
        ))
        self.assertIs(result.status, PlanStatus.SOLVED)
        self.assertEqual(result.plan, ())
        self.assertEqual(result.expanded_states, 0)

    def test_unreachable_goal_returns_no_plan(self):
        result = self.engine.reason(ReasoningProblem(
            "task-1", "Unreachable", ("start",), ("missing",),
            (_action("irrelevant", ("start",), ("other",)),),
        ))
        self.assertIs(result.status, PlanStatus.NO_PLAN)
        self.assertEqual(result.plan, ())

    def test_depth_bound_is_reported(self):
        engine = ReasoningEngine(max_depth=1)
        result = engine.reason(ReasoningProblem(
            "task-1", "Two steps", ("start",), ("goal",),
            (
                _action("one", ("start",), ("middle",)),
                _action("two", ("middle",), ("goal",)),
            ),
        ))
        self.assertIs(result.status, PlanStatus.DEPTH_LIMIT)

    def test_state_bound_is_reported(self):
        engine = ReasoningEngine(max_states=1)
        result = engine.reason(ReasoningProblem(
            "task-1", "Bounded search", ("start",), ("goal",),
            (_action("one", ("start",), ("middle",)),),
        ))
        self.assertIs(result.status, PlanStatus.SEARCH_LIMIT)

    def test_delete_effect_is_applied_and_recorded(self):
        problem = ReasoningProblem(
            "task-1", "Replace state", ("unsafe",), ("safe",),
            (_action("secure", ("unsafe",), ("safe",), ("unsafe",)),),
        )
        result = self.engine.reason(problem)
        self.assertEqual(result.predicted_final_facts, ("safe",))
        self.assertEqual(result.plan[0].deleted_facts, ("unsafe",))
        self.assertEqual(result.plan[0].facts_after_step, ("safe",))

    def test_unsatisfied_preconditions_prevent_action(self):
        result = self.engine.reason(ReasoningProblem(
            "task-1", "Missing prerequisite", ("start",), ("goal",),
            (_action("blocked", ("permission",), ("goal",)),),
        ))
        self.assertIs(result.status, PlanStatus.NO_PLAN)

    def test_duplicate_action_ids_are_rejected(self):
        action = _action("same", ("start",), ("goal",))
        with self.assertRaises(ReasoningError):
            self.engine.reason(ReasoningProblem(
                "task-1", "Duplicate action", ("start",), ("goal",), (action, action)
            ))

    def test_duplicate_initial_facts_are_rejected(self):
        with self.assertRaises(ReasoningError):
            self.engine.reason(ReasoningProblem(
                "task-1", "Duplicate facts", ("start", "start"), ("goal",),
                (_action("move", ("start",), ("goal",)),),
            ))


class ReasoningEvidenceTests(unittest.TestCase):
    def test_evidence_is_grouped_without_claiming_truth(self):
        problem = ReasoningProblem(
            "task-1", "Assess claim", (), ("done",), (),
            hypotheses=(Hypothesis("h1", "The claim is true"),),
            evidence=(
                EvidenceRecord("e1", "h1", EvidencePolarity.SUPPORTS, "source:1", EvidenceQuality.PRIMARY, "Supports claim"),
                EvidenceRecord("e2", "h1", EvidencePolarity.REFUTES, "source:2", EvidenceQuality.INDEPENDENT, "Contradicts claim"),
            ),
        )
        result = ReasoningEngine().reason(problem)
        assessment = result.hypothesis_assessments[0]
        self.assertIs(assessment.status, HypothesisStatus.MIXED)
        self.assertEqual(assessment.supporting_evidence_ids, ("e1",))
        self.assertEqual(assessment.refuting_evidence_ids, ("e2",))

    def test_no_evidence_is_insufficient(self):
        result = ReasoningEngine().reason(ReasoningProblem(
            "task-1", "No evidence", (), ("done",), (),
            hypotheses=(Hypothesis("h1", "Unknown"),),
        ))
        self.assertIs(result.hypothesis_assessments[0].status, HypothesisStatus.INSUFFICIENT)

    def test_support_only_is_not_called_proven(self):
        result = ReasoningEngine().reason(ReasoningProblem(
            "task-1", "One source", (), ("done",), (),
            hypotheses=(Hypothesis("h1", "Candidate"),),
            evidence=(EvidenceRecord(
                "e1", "h1", EvidencePolarity.SUPPORTS, "unverified:source",
                EvidenceQuality.UNVERIFIED, "Unverified report",
            ),),
        ))
        assessment = result.hypothesis_assessments[0]
        self.assertIs(assessment.status, HypothesisStatus.SUPPORT_ONLY)
        self.assertEqual(assessment.quality_labels, ("unverified",))

    def test_evidence_for_unknown_hypothesis_is_rejected(self):
        with self.assertRaises(ReasoningError):
            ReasoningEngine().reason(ReasoningProblem(
                "task-1", "Dangling evidence", (), ("done",), (),
                hypotheses=(Hypothesis("h1", "Candidate"),),
                evidence=(EvidenceRecord(
                    "e1", "missing", EvidencePolarity.SUPPORTS, "source",
                    EvidenceQuality.PRIMARY, "Evidence",
                ),),
            ))

    def test_duplicate_evidence_ids_are_rejected(self):
        evidence = EvidenceRecord(
            "e1", "h1", EvidencePolarity.SUPPORTS, "source",
            EvidenceQuality.PRIMARY, "Evidence",
        )
        with self.assertRaises(ReasoningError):
            ReasoningEngine().reason(ReasoningProblem(
                "task-1", "Duplicate evidence", (), ("done",), (),
                hypotheses=(Hypothesis("h1", "Candidate"),),
                evidence=(evidence, evidence),
            ))


class ReasoningVerificationTests(unittest.TestCase):
    def setUp(self):
        self.engine = ReasoningEngine()
        self.result = self.engine.reason(ReasoningProblem(
            "task-verify", "Produce output", ("input",), ("output",),
            (_action("transform", ("input",), ("output",)),),
        ))

    def test_goal_and_predicted_state_verification(self):
        checked = self.engine.verify_result(self.result, ("input", "output"))
        self.assertIs(checked.status, VerificationStatus.GOAL_SATISFIED)
        self.assertEqual(checked.missing_goal_facts, ())
        self.assertEqual(checked.missing_predicted_facts, ())
        self.assertEqual(checked.unexpected_observed_facts, ())

    def test_goal_can_be_satisfied_even_when_other_predicted_facts_are_missing(self):
        checked = self.engine.verify_result(self.result, ("output",))
        self.assertIs(checked.status, VerificationStatus.GOAL_SATISFIED)
        self.assertEqual(checked.missing_predicted_facts, ("input",))

    def test_missing_goal_is_not_verified(self):
        checked = self.engine.verify_result(self.result, ("input",))
        self.assertIs(checked.status, VerificationStatus.GOAL_INCOMPLETE)
        self.assertEqual(checked.missing_goal_facts, ("output",))

    def test_extra_observed_fact_is_reported(self):
        checked = self.engine.verify_result(self.result, ("input", "output", "unexpected"))
        self.assertEqual(checked.unexpected_observed_facts, ("unexpected",))

    def test_unsolved_plan_cannot_be_verified(self):
        unsolved = self.engine.reason(ReasoningProblem(
            "task-no-plan", "No path", ("start",), ("goal",), ()
        ))
        with self.assertRaises(ReasoningError):
            self.engine.verify_result(unsolved, ("goal",))

    def test_observed_facts_reject_string_input(self):
        with self.assertRaises(ReasoningError):
            self.engine.verify_result(self.result, "output")


if __name__ == "__main__":
    unittest.main()
