"""Contract tests for the bounded JARVIS Task Loop V0.1."""
import os
import tempfile
import unittest

from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.permission_safety_gate import (
    CapabilityRule,
    EffectClass,
    RequestedBudget,
    SafetyPolicy,
    build_approval_grant,
)
from tools.neuron_runtime.reasoning_engine import (
    PlanStatus,
    PlanningAction,
    ReasoningProblem,
)
from tools.neuron_runtime.task_loop import (
    ActionBinding,
    ActionExecutionResult,
    ExecutionOutcome,
    JARVISTaskLoop,
    TaskLoopError,
    TaskLoopPersistenceError,
    TaskLoopStatus,
)
from tools.neuron_runtime.task_session import TaskSessionManager, TaskStatus
from tools.neuron_runtime.task_session_store import JPVRTaskSessionCheckpointStore


def _manager():
    manager = TaskSessionManager(manager_id="loop-manager")
    manager.create_session("session-1")
    manager.create_task("task-1", "session-1", "task-key", "Run a bounded plan")
    return manager


def _problem():
    return ReasoningProblem(
        task_id="task-1",
        goal_description="Reach the goal",
        initial_facts=("start",),
        goal_facts=("goal",),
        actions=(
            PlanningAction("step-one", "Discover the intermediate state",
                           ("start",), ("middle",)),
            PlanningAction("step-two", "Reach the final state",
                           ("middle",), ("goal",)),
        ),
    )


def _bindings(effect=EffectClass.READ_ONLY):
    return {
        "step-one": ActionBinding(
            "demo.read", "demo:one", effect,
            RequestedBudget(10, 1024, 1000, 256, 0),
        ),
        "step-two": ActionBinding(
            "demo.read", "demo:two", effect,
            RequestedBudget(10, 1024, 1000, 256, 0),
        ),
    }


def _policy(effect=EffectClass.READ_ONLY):
    return SafetyPolicy(
        "loop-policy", 1,
        (
            CapabilityRule(
                "demo.read", ("demo:one", "demo:two"), (effect,),
                max_compute_units=20, max_memory_bytes=2048,
                max_timeout_ms=2000, max_output_bytes=512, max_retries=2,
            ),
        ),
    )


class MemoryExecutor:
    """Deterministic executor with a process-independent-style idempotency ledger."""

    def __init__(
        self, *, unknown_once=False, unknown_action_id=None,
        failed=False, omit_postcondition=False,
    ):
        self.facts = {"start"}
        self.ledger = {}
        self.keys = []
        self.unknown_once = unknown_once
        self.unknown_action_id = unknown_action_id
        self.failed = failed
        self.omit_postcondition = omit_postcondition

    def execute(self, action, request):
        key = request.idempotency_key
        self.keys.append(key)
        if key in self.ledger:
            return self.ledger[key]
        if self.failed:
            return ActionExecutionResult(
                ExecutionOutcome.FAILED_NO_EFFECT, tuple(sorted(self.facts)),
                "executor_rejected",
            )
        if not set(action.preconditions).issubset(self.facts):
            return ActionExecutionResult(
                ExecutionOutcome.FAILED_NO_EFFECT, tuple(sorted(self.facts)),
                "executor_precondition",
            )
        next_facts = set(self.facts)
        next_facts.difference_update(action.delete_facts)
        next_facts.update(action.add_facts)
        if self.omit_postcondition:
            next_facts.difference_update(action.add_facts)
        self.facts = next_facts
        result = ActionExecutionResult(
            ExecutionOutcome.SUCCEEDED, tuple(sorted(self.facts))
        )
        self.ledger[key] = result
        if self.unknown_once or self.unknown_action_id == action.action_id:
            self.unknown_once = False
            self.unknown_action_id = None
            raise RuntimeError("simulated lost executor response after effect commit")
        return result


class TaskLoopExecutionTests(unittest.TestCase):
    def setUp(self):
        self.manager = _manager()
        self.loop = JARVISTaskLoop()
        self.problem = _problem()
        self.bindings = _bindings()
        self.policy = _policy()
        self.executor = MemoryExecutor()

    def test_two_step_plan_runs_only_after_authorization_and_completes(self):
        result = self.loop.run(
            self.manager, "task-1", self.problem, self.bindings,
            self.policy, self.executor, now_ns=100,
        )
        self.assertIs(result.status, TaskLoopStatus.COMPLETED)
        self.assertEqual(result.executed_step_ids, ("step-000001", "step-000002"))
        self.assertEqual(result.observed_facts, ("goal", "middle", "start"))
        self.assertEqual(self.manager.get_task("task-1").status, TaskStatus.COMPLETED)
        self.assertEqual(len(result.authorization_decisions), 2)
        self.assertTrue(all(item.allowed for item in result.authorization_decisions))

    def test_each_successful_action_has_a_terminal_child_task_record(self):
        result = self.loop.run(
            self.manager, "task-1", self.problem, self.bindings,
            self.policy, self.executor, now_ns=100,
        )
        self.assertIs(result.status, TaskLoopStatus.COMPLETED)
        step_tasks = tuple(
            item for item in self.manager.tasks if item.task_id.startswith("step-")
        )
        self.assertEqual(len(step_tasks), 2)
        self.assertTrue(all(item.status is TaskStatus.COMPLETED for item in step_tasks))
        self.assertTrue(all(
            item.result["schema"] == "jarvis.task-loop-step"
            and item.result["version"] == 1
            for item in step_tasks
        ))

    def test_unreachable_goal_fails_without_calling_executor(self):
        manager = self.manager
        problem = ReasoningProblem(
            "task-1", "No route", ("start",), ("missing",), (),
        )
        result = self.loop.run(
            manager, "task-1", problem, {}, self.policy,
            self.executor, now_ns=100,
        )
        self.assertIs(result.status, TaskLoopStatus.FAILED)
        self.assertIs(result.reasoning.status, PlanStatus.NO_PLAN)
        self.assertEqual(result.reason_code, "reasoning_no_plan")
        self.assertEqual(self.executor.keys, [])
        self.assertIs(manager.get_task("task-1").status, TaskStatus.FAILED)

    def test_missing_capability_waits_and_never_invokes_executor(self):
        policy = SafetyPolicy("empty", 1, ())
        result = self.loop.run(
            self.manager, "task-1", self.problem, self.bindings,
            policy, self.executor, now_ns=100,
        )
        self.assertIs(result.status, TaskLoopStatus.WAITING)
        self.assertEqual(result.reason_code, "capability_not_granted")
        self.assertEqual(result.pending_step_id, "step-000001")
        self.assertEqual(self.executor.keys, [])
        self.assertIs(self.manager.get_task("task-1").status, TaskStatus.RUNNING)

    def test_uncertain_executor_reply_retries_with_identical_idempotency_key(self):
        executor = MemoryExecutor(unknown_once=True)
        first = self.loop.run(
            self.manager, "task-1", self.problem, self.bindings,
            self.policy, executor, now_ns=100,
        )
        self.assertIs(first.status, TaskLoopStatus.WAITING)
        self.assertEqual(first.reason_code, "execution_outcome_unknown")
        self.assertEqual(self.manager.get_task("task-1").status, TaskStatus.RUNNING)

        second = self.loop.run(
            self.manager, "task-1", self.problem, self.bindings,
            self.policy, executor, now_ns=200,
        )
        self.assertIs(second.status, TaskLoopStatus.COMPLETED)
        self.assertEqual(executor.keys[0], executor.keys[1])
        self.assertEqual(len(executor.ledger), 2)
        self.assertEqual(self.manager.get_task("task-1").status, TaskStatus.COMPLETED)

    def test_known_no_effect_failure_marks_task_failed(self):
        executor = MemoryExecutor(failed=True)
        result = self.loop.run(
            self.manager, "task-1", self.problem, self.bindings,
            self.policy, executor, now_ns=100,
        )
        self.assertIs(result.status, TaskLoopStatus.FAILED)
        self.assertEqual(result.reason_code, "executor_rejected")
        self.assertIs(self.manager.get_task("task-1").status, TaskStatus.FAILED)

    def test_missing_postcondition_fails_closed(self):
        executor = MemoryExecutor(omit_postcondition=True)
        result = self.loop.run(
            self.manager, "task-1", self.problem, self.bindings,
            self.policy, executor, now_ns=100,
        )
        self.assertIs(result.status, TaskLoopStatus.FAILED)
        self.assertEqual(result.reason_code, "postcondition_not_met")
        self.assertIs(self.manager.get_task("task-1").status, TaskStatus.FAILED)

    def test_invalid_reasoning_problem_fails_before_executor(self):
        problem = ReasoningProblem(
            task_id="task-1", goal_description="Malformed facts",
            initial_facts=("duplicate", "duplicate"),
            goal_facts=("goal",), actions=(),
        )
        result = self.loop.run(
            self.manager, "task-1", problem, {}, self.policy,
            self.executor, now_ns=100,
        )
        self.assertIs(result.status, TaskLoopStatus.FAILED)
        self.assertIsNone(result.reasoning)
        self.assertEqual(result.reason_code, "reasoning_invalid_request")
        self.assertEqual(self.executor.keys, [])
        self.assertIs(self.manager.get_task("task-1").status, TaskStatus.FAILED)

    def test_in_memory_only_loop_rejects_side_effecting_binding(self):
        bindings = _bindings(EffectClass.REVERSIBLE)
        with self.assertRaises(TaskLoopError):
            self.loop.run(
                self.manager, "task-1", self.problem, bindings,
                _policy(EffectClass.REVERSIBLE), self.executor, now_ns=100,
            )
        self.assertIs(self.manager.get_task("task-1").status, TaskStatus.QUEUED)
        self.assertEqual(self.executor.keys, [])

    def test_invalid_binding_set_is_rejected_before_lifecycle_mutation(self):
        with self.assertRaises(TaskLoopError):
            self.loop.run(
                self.manager, "task-1", self.problem,
                {"step-one": self.bindings["step-one"]},
                self.policy, self.executor, now_ns=100,
            )
        self.assertIs(self.manager.get_task("task-1").status, TaskStatus.QUEUED)


class TaskLoopJPVRTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "task-loop.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRTaskSessionCheckpointStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def test_side_effecting_plan_requires_and_persists_jpvr_lifecycle(self):
        manager = _manager()
        executor = MemoryExecutor()
        bindings = _bindings(EffectClass.REVERSIBLE)
        policy = _policy(EffectClass.REVERSIBLE)
        result = JARVISTaskLoop().run(
            manager, "task-1", _problem(), bindings, policy, executor,
            now_ns=100, checkpoint_store=self.store,
            expected_checkpoint_version=0,
        )
        self.assertIs(result.status, TaskLoopStatus.COMPLETED)
        self.assertEqual(self.store.load("loop-manager").manager.get_task("task-1").status,
                         TaskStatus.COMPLETED)

    def test_step_intent_is_committed_before_executor_is_called(self):
        manager = _manager()
        self.store.save(manager)
        loop = JARVISTaskLoop()
        delegate = MemoryExecutor()
        step_task_id, _ = loop._step_task_identity(
            manager.manager_id, "task-1", loop._plan_id(_problem(), _bindings(EffectClass.REVERSIBLE)),
            "step-000001",
        )

        class CheckingExecutor:
            def execute(inner_self, action, request):
                persisted = self.store.load("loop-manager").manager
                self.assertIs(
                    persisted.get_task("task-1").status, TaskStatus.RUNNING
                )
                self.assertIs(
                    persisted.get_task(step_task_id).status, TaskStatus.RUNNING
                )
                return delegate.execute(action, request)

        result = loop.run(
            manager, "task-1", _problem(), _bindings(EffectClass.REVERSIBLE),
            _policy(EffectClass.REVERSIBLE), CheckingExecutor(),
            now_ns=100, checkpoint_store=self.store,
            expected_checkpoint_version=1,
        )
        self.assertIs(result.status, TaskLoopStatus.COMPLETED)

    def test_unknown_step_recovers_after_jpvr_reopen(self):
        manager = _manager()
        self.store.save(manager)
        loop = JARVISTaskLoop()
        executor = MemoryExecutor(unknown_once=True)
        bindings = _bindings(EffectClass.REVERSIBLE)
        policy = _policy(EffectClass.REVERSIBLE)
        first = loop.run(
            manager, "task-1", _problem(), bindings, policy, executor,
            now_ns=100, checkpoint_store=self.store,
            expected_checkpoint_version=1,
        )
        self.assertIs(first.status, TaskLoopStatus.WAITING)
        persisted = self.store.load("loop-manager")
        step_id, _ = loop._step_task_identity(
            "loop-manager", "task-1", first.plan_id, "step-000001"
        )
        self.assertIs(persisted.manager.get_task(step_id).status, TaskStatus.RUNNING)

        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        reopened_store = JPVRTaskSessionCheckpointStore(reopened)
        restored = reopened_store.load("loop-manager")
        second = loop.run(
            restored.manager, "task-1", _problem(), bindings, policy, executor,
            now_ns=200, checkpoint_store=reopened_store,
            expected_checkpoint_version=restored.descriptor.record_version,
        )
        self.assertIs(second.status, TaskLoopStatus.COMPLETED)
        self.assertEqual(executor.keys[0], executor.keys[1])
        final_state = reopened_store.load("loop-manager").manager
        self.assertIs(final_state.get_task("task-1").status, TaskStatus.COMPLETED)
        self.assertTrue(all(
            item.status is TaskStatus.COMPLETED
            for item in final_state.tasks if item.task_id.startswith("step-")
        ))

    def test_completed_step_result_is_replayed_and_compared_after_later_unknown(self):
        manager = _manager()
        self.store.save(manager)
        loop = JARVISTaskLoop()
        executor = MemoryExecutor(unknown_action_id="step-two")
        bindings = _bindings(EffectClass.REVERSIBLE)
        policy = _policy(EffectClass.REVERSIBLE)
        first = loop.run(
            manager, "task-1", _problem(), bindings, policy, executor,
            now_ns=100, checkpoint_store=self.store,
            expected_checkpoint_version=1,
        )
        self.assertIs(first.status, TaskLoopStatus.WAITING)
        persisted = self.store.load("loop-manager")
        first_step_id, _ = loop._step_task_identity(
            "loop-manager", "task-1", first.plan_id, "step-000001"
        )
        second_step_id, _ = loop._step_task_identity(
            "loop-manager", "task-1", first.plan_id, "step-000002"
        )
        self.assertIs(persisted.manager.get_task(first_step_id).status, TaskStatus.COMPLETED)
        self.assertIs(persisted.manager.get_task(second_step_id).status, TaskStatus.RUNNING)

        second = loop.run(
            persisted.manager, "task-1", _problem(), bindings, policy, executor,
            now_ns=200, checkpoint_store=self.store,
            expected_checkpoint_version=persisted.descriptor.record_version,
        )
        self.assertIs(second.status, TaskLoopStatus.COMPLETED)
        self.assertEqual(executor.keys[0], executor.keys[2])
        self.assertEqual(executor.keys[1], executor.keys[3])

    def test_external_effect_without_approval_waits_and_persists(self):
        manager = _manager()
        bindings = _bindings(EffectClass.EXTERNAL)
        policy = _policy(EffectClass.EXTERNAL)
        result = JARVISTaskLoop().run(
            manager, "task-1", _problem(), bindings, policy,
            MemoryExecutor(), now_ns=100, checkpoint_store=self.store,
            expected_checkpoint_version=0,
        )
        self.assertIs(result.status, TaskLoopStatus.WAITING)
        self.assertEqual(result.reason_code, "approval_required")
        self.assertIs(
            self.store.load("loop-manager").manager.get_task("task-1").status,
            TaskStatus.RUNNING,
        )


    def test_exact_approval_can_resume_without_changing_bound_task_revision(self):
        manager = _manager()
        loop = JARVISTaskLoop()
        problem = _problem()
        bindings = _bindings(EffectClass.EXTERNAL)
        policy = _policy(EffectClass.EXTERNAL)
        executor = MemoryExecutor()

        pending_first = loop.run(
            manager, "task-1", problem, bindings, policy, executor,
            now_ns=100, checkpoint_store=self.store,
            expected_checkpoint_version=0,
        )
        self.assertIs(pending_first.status, TaskLoopStatus.WAITING)
        first_request = pending_first.pending_authorization
        self.assertIsNotNone(first_request)
        self.assertEqual(manager.get_task("task-1").revision, first_request.expected_task_revision)

        first_approval = build_approval_grant(
            first_request, policy, approval_id="approval-step-1",
            approved_by="owner-1", approval_ref="confirm-step-1",
            expires_at_ns=10_000,
        )
        pending_second = loop.run(
            manager, "task-1", problem, bindings, policy, executor,
            now_ns=200, approvals={"step-000001": first_approval},
            checkpoint_store=self.store, expected_checkpoint_version=1,
        )
        self.assertIs(pending_second.status, TaskLoopStatus.WAITING)
        self.assertEqual(pending_second.pending_step_id, "step-000002")
        self.assertEqual(pending_second.executed_step_ids, ("step-000001",))
        self.assertEqual(manager.get_task("task-1").revision, first_request.expected_task_revision)
        second_request = pending_second.pending_authorization
        self.assertIsNotNone(second_request)

        second_approval = build_approval_grant(
            second_request, policy, approval_id="approval-step-2",
            approved_by="owner-1", approval_ref="confirm-step-2",
            expires_at_ns=10_000,
        )
        completed = loop.run(
            manager, "task-1", problem, bindings, policy, executor,
            now_ns=300,
            approvals={
                "step-000001": first_approval,
                "step-000002": second_approval,
            },
            checkpoint_store=self.store, expected_checkpoint_version=1,
        )
        self.assertIs(completed.status, TaskLoopStatus.COMPLETED)
        self.assertEqual(completed.executed_step_ids, ("step-000001", "step-000002"))
        self.assertEqual(len(executor.ledger), 2)
        self.assertIs(
            self.store.load("loop-manager").manager.get_task("task-1").status,
            TaskStatus.COMPLETED,
        )

    def test_stale_checkpoint_version_blocks_executor(self):
        manager = _manager()
        self.store.save(manager)  # Version 1 exists; caller supplies stale version 0.
        executor = MemoryExecutor()
        with self.assertRaises(TaskLoopPersistenceError):
            JARVISTaskLoop().run(
                manager, "task-1", _problem(),
                _bindings(EffectClass.REVERSIBLE),
                _policy(EffectClass.REVERSIBLE), executor,
                now_ns=100, checkpoint_store=self.store,
                expected_checkpoint_version=0,
            )
        self.assertEqual(executor.keys, [])
        self.assertIs(self.store.load("loop-manager").manager.get_task("task-1").status,
                      TaskStatus.QUEUED)

    def test_reopened_checkpoint_preserves_terminal_task_state(self):
        manager = _manager()
        JARVISTaskLoop().run(
            manager, "task-1", _problem(),
            _bindings(EffectClass.REVERSIBLE),
            _policy(EffectClass.REVERSIBLE), MemoryExecutor(),
            now_ns=100, checkpoint_store=self.store,
            expected_checkpoint_version=0,
        )
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        restored = JPVRTaskSessionCheckpointStore(reopened).load("loop-manager")
        self.assertIs(restored.manager.get_task("task-1").status, TaskStatus.COMPLETED)


if __name__ == "__main__":
    unittest.main()
