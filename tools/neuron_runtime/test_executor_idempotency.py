"""Tests for the durable executor invocation/result ledger."""
from dataclasses import replace
import os
from pathlib import Path
import sqlite3
import stat
import tempfile
import threading
import unittest

from tools.neuron_runtime.executor_idempotency import (
    DurableExecutorLedgerConflict,
    DurableExecutorLedgerCorrupt,
    DurableExecutorLedgerError,
    DurableIdempotencyExecutor,
)
from tools.neuron_runtime.permission_safety_gate import (
    AuthorizationRequest,
    EffectClass,
    RequestedBudget,
)
from tools.neuron_runtime.reasoning_engine import PlanningAction
from tools.neuron_runtime.task_loop import ActionExecutionResult, ExecutionOutcome


def _action():
    return PlanningAction(
        action_id="inspect",
        summary="Inspect local resource",
        rationale="Read a bounded local status",
        preconditions=("available",),
        add_facts=("inspected",),
    )


def _request(**changes):
    values = {
        "manager_id": "manager-1",
        "task_id": "task-1",
        "expected_task_revision": 2,
        "expected_session_revision": 1,
        "session_id": "session-1",
        "plan_id": "plan-1",
        "step_id": "step-1",
        "action_id": "inspect",
        "capability": "local.inspect",
        "scope": "workspace:demo",
        "idempotency_key": "idem-1",
        "effect": EffectClass.READ_ONLY,
        "budget": RequestedBudget(5, 1024, 500, 128, 0),
    }
    values.update(changes)
    return AuthorizationRequest(**values)


class CountingExecutor:
    def __init__(self, result=None, error=None):
        self.result = result or ActionExecutionResult(
            outcome=ExecutionOutcome.SUCCEEDED,
            observed_facts=("available", "inspected"),
        )
        self.error = error
        self.calls = 0

    def execute(self, action, request):
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.result


class BlockingExecutor(CountingExecutor):
    def __init__(self, entered, release):
        super().__init__()
        self.entered = entered
        self.release = release

    def execute(self, action, request):
        self.calls += 1
        self.entered.set()
        if not self.release.wait(timeout=5):
            raise TimeoutError("test executor release timed out")
        return self.result


class DurableIdempotencyExecutorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "executor-ledger.sqlite3"
        self.action = _action()
        self.request = _request()

    def _executor(self, delegate):
        return DurableIdempotencyExecutor(self.path, delegate)

    def test_first_success_is_persisted_and_replayed_after_reopen(self):
        delegate = CountingExecutor()
        ledger = self._executor(delegate)
        first = ledger.execute(self.action, self.request)
        reopened = self._executor(CountingExecutor())
        replay = reopened.execute(self.action, self.request)

        self.assertEqual(first.outcome, ExecutionOutcome.SUCCEEDED)
        self.assertEqual(replay, first)
        self.assertEqual(delegate.calls, 1)
        record = reopened.lookup(self.request.idempotency_key)
        self.assertEqual(record.state, "succeeded")
        self.assertEqual(record.result, first)

    def test_failed_no_effect_result_is_persisted_and_replayed(self):
        failure = ActionExecutionResult(
            outcome=ExecutionOutcome.FAILED_NO_EFFECT,
            failure_code="resource_not_found",
        )
        delegate = CountingExecutor(result=failure)
        ledger = self._executor(delegate)
        first = ledger.execute(self.action, self.request)
        replay = ledger.execute(self.action, self.request)
        self.assertEqual(first, failure)
        self.assertEqual(replay, failure)
        self.assertEqual(delegate.calls, 1)
        self.assertEqual(ledger.lookup("idem-1").state, "failed_no_effect")

    def test_same_key_cannot_be_reused_for_changed_scope_or_action(self):
        ledger = self._executor(CountingExecutor())
        ledger.execute(self.action, self.request)
        changed_request = replace(self.request, scope="workspace:other")
        with self.assertRaises(DurableExecutorLedgerConflict):
            ledger.execute(self.action, changed_request)
        changed_action = replace(self.action, summary="Different intent")
        with self.assertRaises(DurableExecutorLedgerConflict):
            ledger.execute(changed_action, self.request)

    def test_delegate_exception_becomes_unknown_and_is_never_blindly_redispatched(self):
        delegate = CountingExecutor(error=RuntimeError("lost response"))
        ledger = self._executor(delegate)
        first = ledger.execute(self.action, self.request)
        second = ledger.execute(self.action, self.request)
        self.assertEqual(first.outcome, ExecutionOutcome.UNKNOWN)
        self.assertEqual(second.outcome, ExecutionOutcome.UNKNOWN)
        self.assertEqual(delegate.calls, 1)
        self.assertEqual(ledger.lookup("idem-1").state, "unknown")

    def test_explicit_unknown_result_is_not_stored_as_final(self):
        delegate = CountingExecutor(result=ActionExecutionResult(
            outcome=ExecutionOutcome.UNKNOWN,
        ))
        ledger = self._executor(delegate)
        result = ledger.execute(self.action, self.request)
        self.assertEqual(result.outcome, ExecutionOutcome.UNKNOWN)
        record = ledger.lookup("idem-1")
        self.assertEqual(record.state, "unknown")
        self.assertIsNone(record.result)
        self.assertIsNone(record.result_digest)

    def test_unknown_result_can_be_completed_by_explicit_reconciliation(self):
        delegate = CountingExecutor(error=RuntimeError("provider response was lost"))
        ledger = self._executor(delegate)
        self.assertEqual(
            ledger.execute(self.action, self.request).outcome,
            ExecutionOutcome.UNKNOWN,
        )
        known = ActionExecutionResult(
            outcome=ExecutionOutcome.SUCCEEDED,
            observed_facts=("available", "inspected"),
        )
        reconciled = ledger.record_reconciled_result(
            self.action,
            self.request,
            known,
            reconciliation_ref="provider-check-1",
        )
        replay = ledger.execute(self.action, self.request)
        record = ledger.lookup("idem-1")
        self.assertEqual(reconciled, known)
        self.assertEqual(replay, known)
        self.assertEqual(record.reconciliation_ref, "provider-check-1")
        self.assertEqual(delegate.calls, 1)

    def test_reconciliation_requires_existing_exact_request(self):
        ledger = self._executor(CountingExecutor())
        with self.assertRaises(DurableExecutorLedgerConflict):
            ledger.record_reconciled_result(
                self.action,
                self.request,
                ActionExecutionResult(outcome=ExecutionOutcome.SUCCEEDED),
                reconciliation_ref="external-check-1",
            )
        ledger.execute(self.action, self.request)
        changed = replace(self.request, scope="workspace:elsewhere")
        with self.assertRaises(DurableExecutorLedgerConflict):
            ledger.record_reconciled_result(
                self.action,
                changed,
                ActionExecutionResult(outcome=ExecutionOutcome.SUCCEEDED),
                reconciliation_ref="external-check-2",
            )

    def test_unknown_cannot_be_recorded_as_a_reconciled_final_result(self):
        ledger = self._executor(CountingExecutor(error=RuntimeError("unknown")))
        ledger.execute(self.action, self.request)
        with self.assertRaises(DurableExecutorLedgerError):
            ledger.record_reconciled_result(
                self.action,
                self.request,
                ActionExecutionResult(outcome=ExecutionOutcome.UNKNOWN),
                reconciliation_ref="check-unknown",
            )

    def test_reconciliation_cannot_overwrite_a_different_final_result(self):
        ledger = self._executor(CountingExecutor())
        success = ledger.execute(self.action, self.request)
        with self.assertRaises(DurableExecutorLedgerConflict):
            ledger.record_reconciled_result(
                self.action,
                self.request,
                ActionExecutionResult(
                    outcome=ExecutionOutcome.FAILED_NO_EFFECT,
                    failure_code="contradictory",
                ),
                reconciliation_ref="contradictory-check",
            )
        self.assertEqual(ledger.lookup("idem-1").result, success)

    def test_two_concurrent_same_key_calls_dispatch_delegate_only_once(self):
        entered = threading.Event()
        release = threading.Event()
        delegate = BlockingExecutor(entered, release)
        ledger_a = self._executor(delegate)
        ledger_b = self._executor(delegate)
        first_result = []
        first_error = []

        def first_call():
            try:
                first_result.append(ledger_a.execute(self.action, self.request))
            except Exception as exc:
                first_error.append(exc)

        thread = threading.Thread(target=first_call)
        thread.start()
        self.assertTrue(entered.wait(timeout=3))
        second = ledger_b.execute(self.action, self.request)
        release.set()
        thread.join(timeout=5)

        self.assertFalse(thread.is_alive())
        self.assertEqual(first_error, [])
        self.assertEqual(second.outcome, ExecutionOutcome.UNKNOWN)
        self.assertEqual(first_result[0].outcome, ExecutionOutcome.SUCCEEDED)
        self.assertEqual(delegate.calls, 1)

    def test_database_and_parent_directory_are_private(self):
        ledger = self._executor(CountingExecutor())
        ledger.execute(self.action, self.request)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        self.assertEqual(ledger.lookup("idem-1").state, "succeeded")

    def test_symlink_database_path_is_rejected(self):
        target = Path(self.temp.name) / "target.sqlite3"
        target.write_bytes(b"not-a-database")
        link = Path(self.temp.name) / "linked.sqlite3"
        link.symlink_to(target)
        with self.assertRaises(DurableExecutorLedgerError):
            DurableIdempotencyExecutor(link, CountingExecutor())

    def test_shared_parent_directory_is_rejected(self):
        shared = Path(self.temp.name) / "shared"
        shared.mkdir(mode=0o755)
        os.chmod(shared, 0o755)
        with self.assertRaises(DurableExecutorLedgerError):
            DurableIdempotencyExecutor(shared / "ledger.sqlite3", CountingExecutor())

    def test_database_schema_version_mismatch_is_rejected(self):
        ledger = self._executor(CountingExecutor())
        connection = sqlite3.connect(self.path)
        connection.execute("PRAGMA user_version=99")
        connection.commit()
        connection.close()
        with self.assertRaises(DurableExecutorLedgerCorrupt):
            self._executor(CountingExecutor())

    def test_corrupted_final_result_digest_is_rejected(self):
        ledger = self._executor(CountingExecutor())
        ledger.execute(self.action, self.request)
        connection = sqlite3.connect(self.path)
        connection.execute(
            "UPDATE action_results SET result_digest=? WHERE idempotency_key=?",
            ("0" * 64, "idem-1"),
        )
        connection.commit()
        connection.close()
        with self.assertRaises(DurableExecutorLedgerCorrupt):
            ledger.lookup("idem-1")

    def test_action_id_must_match_authorization_request(self):
        ledger = self._executor(CountingExecutor())
        other_action = replace(self.action, action_id="another-action")
        with self.assertRaises(DurableExecutorLedgerError):
            ledger.execute(other_action, self.request)

    def test_record_capacity_fails_closed_without_dispatch(self):
        delegate = CountingExecutor()
        ledger = DurableIdempotencyExecutor(
            self.path, delegate, max_records=1,
        )
        ledger.execute(self.action, self.request)
        second_request = replace(self.request, idempotency_key="idem-2")
        with self.assertRaises(DurableExecutorLedgerError):
            ledger.execute(self.action, second_request)
        self.assertEqual(delegate.calls, 1)

    def test_maximum_record_capacity_cannot_be_disabled(self):
        with self.assertRaises(DurableExecutorLedgerError):
            DurableIdempotencyExecutor(
                self.path, CountingExecutor(), max_records=0,
            )


if __name__ == "__main__":
    unittest.main()
