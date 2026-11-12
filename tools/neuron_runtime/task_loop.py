"""Bounded JARVIS task orchestration V0.1.

The loop composes TaskSessionManager, ReasoningEngine, PermissionSafetyGate and
an injected idempotent executor. It does not interpret natural language or own
external credentials/tools.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import re
from typing import Callable, Mapping, Protocol

from .permission_safety_gate import (
    ApprovalGrant,
    AuthorizationDecision,
    AuthorizationRequest,
    AuthorizationStatus,
    EffectClass,
    PermissionSafetyError,
    PermissionSafetyGate,
    RequestedBudget,
    SafetyPolicy,
)
from .reasoning_engine import (
    PlanStatus,
    PlanningAction,
    ReasoningEngine,
    ReasoningError,
    ReasoningProblem,
    ReasoningResult,
    VerificationStatus,
)
from .task_session import (
    SessionStatus,
    TaskSessionError,
    TaskSessionManager,
    TaskStatus,
)
from .task_session_store import (
    JPVRTaskSessionCheckpointError,
    JPVRTaskSessionCheckpointNotFound,
    JPVRTaskSessionCheckpointStore,
)


_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_U64_MAX = (1 << 64) - 1


class TaskLoopError(ValueError):
    """Invalid Task Loop input or illegal orchestration state."""


class TaskLoopPersistenceError(TaskLoopError):
    """A required JPVR task checkpoint could not be committed."""


class TaskLoopConflict(TaskLoopError):
    """The task/session lifecycle changed while the plan was being executed."""


class TaskLoopStatus(str, Enum):
    COMPLETED = "completed"
    WAITING = "waiting"
    FAILED = "failed"


class ExecutionOutcome(str, Enum):
    SUCCEEDED = "succeeded"
    FAILED_NO_EFFECT = "failed_no_effect"
    UNKNOWN = "unknown"


def _identifier(name: str, value: object) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise TaskLoopError(f"{name} must be a stable 1..128 character identifier")
    return value


@dataclass(frozen=True)
class ActionBinding:
    """Security/resource declaration that binds a pure planner action to a capability."""

    capability: str
    scope: str
    effect: EffectClass
    budget: RequestedBudget

    def __post_init__(self) -> None:
        _identifier("capability", self.capability)
        if not isinstance(self.scope, str) or not self.scope.strip() or len(self.scope.strip()) > 512 or "\x00" in self.scope:
            raise TaskLoopError("scope must contain 1..512 characters without NUL")
        object.__setattr__(self, "scope", self.scope.strip())
        try:
            object.__setattr__(self, "effect", EffectClass(self.effect))
        except (TypeError, ValueError) as exc:
            raise TaskLoopError("unsupported action effect class") from exc
        if not isinstance(self.budget, RequestedBudget):
            raise TaskLoopError("budget must be a RequestedBudget")


@dataclass(frozen=True)
class ActionExecutionResult:
    """Executor's full observed fact snapshot after one action attempt."""

    outcome: ExecutionOutcome
    observed_facts: tuple[str, ...] = ()
    failure_code: str | None = None

    def __post_init__(self) -> None:
        try:
            outcome = ExecutionOutcome(self.outcome)
        except (TypeError, ValueError) as exc:
            raise TaskLoopError("unsupported executor outcome") from exc
        object.__setattr__(self, "outcome", outcome)
        if not isinstance(self.observed_facts, (tuple, list)):
            raise TaskLoopError("observed_facts must be a tuple or list")
        facts = tuple(self.observed_facts)
        if len(facts) > 2048 or any(not isinstance(item, str) or not _ID.fullmatch(item) for item in facts):
            raise TaskLoopError("observed_facts contains invalid or excessive fact identifiers")
        if len(set(facts)) != len(facts):
            raise TaskLoopError("observed_facts must be unique")
        object.__setattr__(self, "observed_facts", tuple(sorted(facts)))
        if outcome is ExecutionOutcome.FAILED_NO_EFFECT:
            object.__setattr__(self, "failure_code", _identifier("failure_code", self.failure_code))
        elif self.failure_code is not None:
            raise TaskLoopError("failure_code is only valid for FAILED_NO_EFFECT")


class ActionExecutor(Protocol):
    """Executor contract.

    Repeating the same request.idempotency_key must return the previously
    committed result without applying the external effect again. If the
    executor cannot determine the outcome, it must raise or return UNKNOWN.
    """

    def execute(
        self,
        action: PlanningAction,
        request: AuthorizationRequest,
    ) -> ActionExecutionResult:
        ...


@dataclass(frozen=True)
class TaskLoopResult:
    task_id: str
    status: TaskLoopStatus
    plan_id: str
    reasoning: ReasoningResult | None
    executed_step_ids: tuple[str, ...]
    pending_step_id: str | None
    observed_facts: tuple[str, ...]
    reason_code: str
    task_revision: int
    authorization_decisions: tuple[AuthorizationDecision, ...]
    pending_authorization: AuthorizationRequest | None = None


class JARVISTaskLoop:
    """Bounded, sequential reference task loop with a deny-by-default gate."""

    def __init__(
        self,
        *,
        reasoning_engine: ReasoningEngine | None = None,
        permission_gate: PermissionSafetyGate | None = None,
    ) -> None:
        self.reasoning_engine = reasoning_engine or ReasoningEngine()
        self.permission_gate = permission_gate or PermissionSafetyGate()
        if not isinstance(self.reasoning_engine, ReasoningEngine):
            raise TaskLoopError("reasoning_engine must be a ReasoningEngine")
        if not isinstance(self.permission_gate, PermissionSafetyGate):
            raise TaskLoopError("permission_gate must be a PermissionSafetyGate")

    @staticmethod
    def _plan_id(problem: ReasoningProblem, bindings: Mapping[str, ActionBinding]) -> str:
        # Retry counters and resource ceilings are intentionally excluded:
        # they change authorization, not the logical action/idempotency identity.
        actions = []
        for action in sorted(problem.actions, key=lambda item: item.action_id):
            binding = bindings[action.action_id]
            actions.append({
                "action_id": action.action_id,
                "summary": action.summary,
                "rationale": action.rationale,
                "preconditions": list(action.preconditions),
                "add_facts": list(action.add_facts),
                "delete_facts": list(action.delete_facts),
                "capability": binding.capability,
                "scope": binding.scope,
                "effect": binding.effect.value,
            })
        document = {
            "task_id": problem.task_id,
            "goal_description": problem.goal_description,
            "initial_facts": sorted(problem.initial_facts),
            "goal_facts": sorted(problem.goal_facts),
            "actions": actions,
        }
        canonical = json.dumps(
            document, sort_keys=True, ensure_ascii=True, separators=(",", ":")
        ).encode("ascii")
        return "plan-" + hashlib.sha256(b"JARVIS:TASK-LOOP:PLAN:V1\x00" + canonical).hexdigest()[:48]

    @staticmethod
    def _step_id(index: int) -> str:
        return f"step-{index:06d}"

    @staticmethod
    def _action_idempotency_key(
        manager_id: str, task_id: str, task_idempotency_key: str,
        plan_id: str, step_id: str, action_id: str,
    ) -> str:
        material = "\x00".join((
            manager_id, task_id, task_idempotency_key, plan_id, step_id, action_id,
        )).encode("ascii")
        return "act-" + hashlib.sha256(b"JARVIS:TASK-LOOP:ACTION:V1\x00" + material).hexdigest()

    @staticmethod
    def _step_task_identity(
        manager_id: str, task_id: str, plan_id: str, step_id: str,
    ) -> tuple[str, str]:
        material = "\\x00".join((manager_id, task_id, plan_id, step_id)).encode("ascii")
        digest = hashlib.sha256(
            b"JARVIS:TASK-LOOP:STEP-LEDGER:V1\\x00" + material
        ).hexdigest()
        return "step-" + digest[:48], "step-idem-" + digest

    @staticmethod
    def _step_result_digest(execution: ActionExecutionResult) -> str:
        document = {
            "outcome": execution.outcome.value,
            "observed_facts": list(execution.observed_facts),
        }
        payload = json.dumps(
            document, sort_keys=True, ensure_ascii=True, separators=(",", ":")
        ).encode("ascii")
        return hashlib.sha256(
            b"JARVIS:TASK-LOOP:STEP-RESULT:V1\\x00" + payload
        ).hexdigest()

    @staticmethod
    def _step_result_record(
        *, task_id: str, plan_id: str, step_id: str, action_id: str,
        action_idempotency_key: str, result_digest: str,
    ) -> dict[str, object]:
        return {
            "schema": "jarvis.task-loop-step",
            "version": 1,
            "parent_task_id": task_id,
            "plan_id": plan_id,
            "step_id": step_id,
            "action_id": action_id,
            "idempotency_key": action_idempotency_key,
            "result_digest": result_digest,
        }

    def run(
        self,
        task_manager: TaskSessionManager,
        task_id: str,
        problem: ReasoningProblem,
        action_bindings: Mapping[str, ActionBinding],
        policy: SafetyPolicy,
        executor: ActionExecutor,
        *,
        now_ns: int | Callable[[], int],
        approvals: Mapping[str, ApprovalGrant] | None = None,
        checkpoint_store: JPVRTaskSessionCheckpointStore | None = None,
        expected_checkpoint_version: int | None = None,
    ) -> TaskLoopResult:
        """Plan and execute one bounded task.

        Side-effecting actions require a dedicated JPVR task/session store. On
        restart, callers must replay the same structured problem and bindings;
        the executor's idempotency ledger is responsible for deduplicating an
        action whose external result was committed before a crash.
        """
        if not isinstance(task_manager, TaskSessionManager):
            raise TaskLoopError("task_manager must be a TaskSessionManager")
        _identifier("task_id", task_id)
        if not isinstance(problem, ReasoningProblem) or problem.task_id != task_id:
            raise TaskLoopError("problem must be a ReasoningProblem for the requested task")
        if not isinstance(policy, SafetyPolicy):
            raise TaskLoopError("policy must be a SafetyPolicy")
        if not callable(getattr(executor, "execute", None)):
            raise TaskLoopError("executor must provide execute(action, request)")
        if not isinstance(action_bindings, Mapping):
            raise TaskLoopError("action_bindings must be a mapping")
        if not isinstance(problem.actions, (tuple, list)):
            raise TaskLoopError("problem.actions must be a tuple or list")
        action_ids = {item.action_id for item in problem.actions if isinstance(item, PlanningAction)}
        if len(action_ids) != len(problem.actions):
            raise TaskLoopError("problem contains an invalid or duplicate planning action")
        if set(action_bindings) != action_ids or any(
            not isinstance(value, ActionBinding) for value in action_bindings.values()
        ):
            raise TaskLoopError("action_bindings must exactly cover every planning action")
        if approvals is not None:
            if not isinstance(approvals, Mapping):
                raise TaskLoopError("approvals must map step IDs to ApprovalGrant objects")
            if any(
                not isinstance(step_id, str) or not _ID.fullmatch(step_id)
                or not isinstance(grant, ApprovalGrant)
                for step_id, grant in approvals.items()
            ):
                raise TaskLoopError("approvals contains an invalid step ID or grant")
        if checkpoint_store is not None:
            if not isinstance(checkpoint_store, JPVRTaskSessionCheckpointStore):
                raise TaskLoopError("checkpoint_store must be a JPVRTaskSessionCheckpointStore")
            if (
                isinstance(expected_checkpoint_version, bool)
                or not isinstance(expected_checkpoint_version, int)
                or not 0 <= expected_checkpoint_version <= _U64_MAX
            ):
                raise TaskLoopError("expected_checkpoint_version is required when a checkpoint store is supplied")
        elif expected_checkpoint_version is not None:
            raise TaskLoopError("expected_checkpoint_version requires checkpoint_store")
        if any(
            binding.effect is not EffectClass.READ_ONLY
            for binding in action_bindings.values()
        ) and checkpoint_store is None:
            raise TaskLoopError("side-effecting actions require a durable JPVR task checkpoint store")
        if isinstance(now_ns, bool) or (not isinstance(now_ns, int) and not callable(now_ns)):
            raise TaskLoopError("now_ns must be an integer or a zero-argument clock callable")

        try:
            task = task_manager.get_task(task_id)
            session = task_manager.get_session(task.session_id)
        except TaskSessionError as exc:
            raise TaskLoopError("task or owning session was not found") from exc
        if session.status is not SessionStatus.OPEN:
            raise TaskLoopError("task session must be open before the task loop starts")
        if task.status not in (TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING):
            raise TaskLoopError("task must be queued, running, or waiting")

        checkpoint_version = expected_checkpoint_version

        if checkpoint_store is not None:
            # Confirm both the caller's CAS token and the exact in-memory manager
            # snapshot before resuming a RUNNING task or performing any effect.
            try:
                persisted = checkpoint_store.load(task_manager.manager_id)
            except JPVRTaskSessionCheckpointNotFound:
                if task.status is not TaskStatus.QUEUED or checkpoint_version != 0:
                    raise TaskLoopPersistenceError(
                        "a non-queued task requires an existing matching JPVR checkpoint"
                    )
            except (JPVRTaskSessionCheckpointError, OSError, ValueError) as exc:
                raise TaskLoopPersistenceError(
                    "cannot verify the active task/session JPVR checkpoint"
                ) from exc
            else:
                if persisted.descriptor.record_version != checkpoint_version:
                    raise TaskLoopPersistenceError(
                        "caller checkpoint version does not match the active JPVR record version"
                    )
                try:
                    same_snapshot = persisted.manager.encode_snapshot() == task_manager.encode_snapshot()
                except TaskSessionError as exc:
                    raise TaskLoopPersistenceError(
                        "in-memory task/session snapshot cannot be canonically encoded"
                    ) from exc
                if not same_snapshot:
                    raise TaskLoopPersistenceError(
                        "in-memory task/session state differs from the active JPVR checkpoint"
                    )

        def persist_lifecycle() -> None:
            nonlocal checkpoint_version
            if checkpoint_store is None:
                return
            try:
                saved = checkpoint_store.save(
                    task_manager, expected_record_version=checkpoint_version
                )
            except (JPVRTaskSessionCheckpointError, OSError, ValueError) as exc:
                raise TaskLoopPersistenceError(
                    "task lifecycle changed in memory but its JPVR checkpoint was not confirmed"
                ) from exc
            checkpoint_version = saved.record_version

        if task.status in (TaskStatus.QUEUED, TaskStatus.WAITING):
            try:
                task = task_manager.transition_task(
                    task_id, TaskStatus.RUNNING, expected_revision=task.revision
                )
            except TaskSessionError as exc:
                raise TaskLoopConflict("task could not enter RUNNING") from exc
            persist_lifecycle()
        running_task_revision = task.revision
        session_revision = session.revision

        try:
            reasoning = self.reasoning_engine.reason(problem)
        except ReasoningError:
            return self._finish_failed(
                task_manager, task_id, running_task_revision, "reasoning_invalid_request",
                "plan-invalid", None, (), (), (),
                persist_lifecycle,
            )
        plan_id = self._plan_id(problem, action_bindings)
        if reasoning.status is not PlanStatus.SOLVED:
            reason_code = "reasoning_" + reasoning.status.value
            return self._finish_failed(
                task_manager, task_id, running_task_revision, reason_code,
                plan_id, reasoning, (), problem.initial_facts, (),
                persist_lifecycle,
            )

        executed: list[str] = []
        decisions: list[AuthorizationDecision] = []
        observed_facts = tuple(sorted(problem.initial_facts))

        for step in reasoning.plan:
            step_id = self._step_id(step.index)
            action = next((item for item in problem.actions if item.action_id == step.action_id), None)
            if action is None:
                return self._finish_failed(
                    task_manager, task_id, running_task_revision, "plan_action_missing",
                    plan_id, reasoning, executed, observed_facts, decisions, persist_lifecycle,
                )
            if not set(step.required_facts).issubset(observed_facts):
                return self._finish_failed(
                    task_manager, task_id, running_task_revision, "plan_precondition_mismatch",
                    plan_id, reasoning, executed, observed_facts, decisions, persist_lifecycle,
                )
            binding = action_bindings[step.action_id]
            idempotency_key = self._action_idempotency_key(
                task_manager.manager_id, task_id, task.idempotency_key,
                plan_id, step_id, step.action_id,
            )
            request = AuthorizationRequest(
                manager_id=task_manager.manager_id,
                task_id=task_id,
                expected_task_revision=running_task_revision,
                expected_session_revision=session_revision,
                session_id=task.session_id,
                plan_id=plan_id,
                step_id=step_id,
                action_id=step.action_id,
                capability=binding.capability,
                scope=binding.scope,
                idempotency_key=idempotency_key,
                effect=binding.effect,
                budget=binding.budget,
            )
            approval = None if approvals is None else approvals.get(step_id)
            current_time = now_ns() if callable(now_ns) else now_ns
            try:
                decision = self.permission_gate.authorize(
                    task_manager, policy, request, now_ns=current_time, approval=approval
                )
            except PermissionSafetyError as exc:
                raise TaskLoopError("permission gate rejected malformed authorization input") from exc
            decisions.append(decision)
            if decision.status is not AuthorizationStatus.ALLOWED:
                return self._finish_waiting(
                    task_manager, task_id, running_task_revision,
                    decision.reason_code, plan_id, reasoning, executed,
                    step_id, observed_facts, decisions, persist_lifecycle, request,
                )

            # A deterministic child task is the durable per-step intent/result
            # ledger. The child shares the parent's TaskSessionManager snapshot,
            # so the intent and lifecycle state are committed in one JPVR record.
            step_task_id, step_task_key = self._step_task_identity(
                task_manager.manager_id, task_id, plan_id, step_id
            )
            step_inputs = (
                f"parent:{task_id}", f"plan:{plan_id}",
                f"step:{step_id}", f"action:{action.action_id}",
            )
            try:
                step_task = task_manager.create_task(
                    step_task_id, task.session_id, step_task_key,
                    action.summary, input_refs=step_inputs,
                )
                if step_task.status is TaskStatus.QUEUED:
                    step_task = task_manager.transition_task(
                        step_task_id, TaskStatus.RUNNING,
                        expected_revision=step_task.revision,
                    )
                    # This commit must finish before the external executor is called.
                    persist_lifecycle()
                elif step_task.status not in (
                    TaskStatus.RUNNING, TaskStatus.COMPLETED,
                    TaskStatus.FAILED, TaskStatus.CANCELLED,
                ):
                    raise TaskLoopConflict("step ledger has an unsupported lifecycle state")
            except TaskSessionError as exc:
                raise TaskLoopConflict("cannot create or validate the durable step intent") from exc

            if step_task.status in (TaskStatus.FAILED, TaskStatus.CANCELLED):
                return self._finish_failed(
                    task_manager, task_id, running_task_revision,
                    "step_ledger_terminal_failure", plan_id, reasoning,
                    executed, observed_facts, decisions, persist_lifecycle,
                )

            try:
                execution = executor.execute(action, request)
            except Exception:
                # The executor may have committed the effect before losing its reply.
                # Never mark this as a known failure or silently generate a new key.
                return self._finish_waiting(
                    task_manager, task_id, running_task_revision,
                    "execution_outcome_unknown", plan_id, reasoning, executed,
                    step_id, observed_facts, decisions, persist_lifecycle, request,
                )
            if not isinstance(execution, ActionExecutionResult):
                return self._finish_waiting(
                    task_manager, task_id, running_task_revision,
                    "invalid_executor_result_outcome_unknown", plan_id, reasoning,
                    executed, step_id, observed_facts, decisions, persist_lifecycle, request,
                )
            if execution.outcome is ExecutionOutcome.UNKNOWN:
                return self._finish_waiting(
                    task_manager, task_id, running_task_revision,
                    "execution_outcome_unknown", plan_id, reasoning, executed,
                    step_id, observed_facts, decisions, persist_lifecycle, request,
                )
            if execution.outcome is ExecutionOutcome.FAILED_NO_EFFECT:
                if step_task.status is TaskStatus.COMPLETED:
                    return self._finish_failed(
                        task_manager, task_id, running_task_revision,
                        "step_replay_outcome_mismatch", plan_id, reasoning,
                        executed, observed_facts, decisions, persist_lifecycle,
                    )
                try:
                    task_manager.transition_task(
                        step_task_id, TaskStatus.FAILED,
                        expected_revision=step_task.revision,
                        failure_code=execution.failure_code,
                    )
                except TaskSessionError as exc:
                    raise TaskLoopConflict("cannot persist the known no-effect step failure") from exc
                persist_lifecycle()
                return self._finish_failed(
                    task_manager, task_id, running_task_revision, execution.failure_code,
                    plan_id, reasoning, executed, observed_facts, decisions,
                    persist_lifecycle,
                )
            result_digest = self._step_result_digest(execution)
            expected_step_record = self._step_result_record(
                task_id=task_id, plan_id=plan_id, step_id=step_id,
                action_id=action.action_id,
                action_idempotency_key=idempotency_key,
                result_digest=result_digest,
            )
            if step_task.status is TaskStatus.COMPLETED:
                if step_task.result != expected_step_record:
                    return self._finish_failed(
                        task_manager, task_id, running_task_revision,
                        "step_replay_result_mismatch", plan_id, reasoning,
                        executed, execution.observed_facts, decisions, persist_lifecycle,
                    )
            else:
                try:
                    task_manager.transition_task(
                        step_task_id, TaskStatus.COMPLETED,
                        expected_revision=step_task.revision,
                        result=expected_step_record,
                    )
                except TaskSessionError as exc:
                    raise TaskLoopConflict("cannot persist the confirmed step result") from exc
                persist_lifecycle()
            observed_facts = execution.observed_facts
            if not set(step.facts_after_step).issubset(observed_facts):
                return self._finish_failed(
                    task_manager, task_id, running_task_revision, "postcondition_not_met",
                    plan_id, reasoning, executed + [step_id], observed_facts, decisions,
                    persist_lifecycle,
                )
            executed.append(step_id)

        try:
            verification = self.reasoning_engine.verify_result(reasoning, observed_facts)
        except ReasoningError:
            return self._finish_failed(
                task_manager, task_id, running_task_revision, "result_verification_invalid",
                plan_id, reasoning, executed, observed_facts, decisions, persist_lifecycle,
            )
        if verification.status is not VerificationStatus.GOAL_SATISFIED:
            return self._finish_failed(
                task_manager, task_id, running_task_revision, "goal_not_satisfied",
                plan_id, reasoning, executed, observed_facts, decisions, persist_lifecycle,
            )
        try:
            completed = task_manager.transition_task(
                task_id, TaskStatus.COMPLETED,
                expected_revision=running_task_revision,
                result={
                    "plan_id": plan_id,
                    "steps_executed": len(executed),
                    "verification": VerificationStatus.GOAL_SATISFIED.value,
                },
            )
        except TaskSessionError as exc:
            raise TaskLoopConflict("task lifecycle changed before completion could be committed") from exc
        persist_lifecycle()
        return TaskLoopResult(
            task_id, TaskLoopStatus.COMPLETED, plan_id, reasoning,
            tuple(executed), None, observed_facts, "goal_satisfied",
            completed.revision, tuple(decisions),
        )

    def _finish_waiting(
        self, manager, task_id, expected_revision, reason_code, plan_id,
        reasoning, executed, pending_step_id, observed_facts, decisions, persist,
        pending_authorization=None,
    ) -> TaskLoopResult:
        # Keep the RUNNING revision stable while approval/reconciliation is pending.
        # The exact request and any ApprovalGrant remain bound to this revision.
        try:
            current = manager.get_task(task_id)
        except TaskSessionError as exc:
            raise TaskLoopConflict("task disappeared while awaiting authorization or reconciliation") from exc
        if current.status is not TaskStatus.RUNNING or current.revision != expected_revision:
            raise TaskLoopConflict("task lifecycle changed while awaiting authorization or reconciliation")
        return TaskLoopResult(
            task_id, TaskLoopStatus.WAITING, plan_id, reasoning,
            tuple(executed), pending_step_id, tuple(observed_facts), reason_code,
            current.revision, tuple(decisions), pending_authorization,
        )

    def _finish_failed(
        self, manager, task_id, expected_revision, failure_code, plan_id,
        reasoning, executed, observed_facts, decisions, persist,
    ) -> TaskLoopResult:
        code = _identifier("failure_code", failure_code)
        try:
            failed = manager.transition_task(
                task_id, TaskStatus.FAILED,
                expected_revision=expected_revision, failure_code=code,
            )
        except TaskSessionError as exc:
            raise TaskLoopConflict("task lifecycle changed before FAILED could be committed") from exc
        persist()
        return TaskLoopResult(
            task_id, TaskLoopStatus.FAILED, plan_id, reasoning,
            tuple(executed), None, tuple(observed_facts), code,
            failed.revision, tuple(decisions),
        )
