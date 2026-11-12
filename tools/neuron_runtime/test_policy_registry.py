"""Tests for atomic policy snapshots and persisted approval revocation."""
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest

from tools.neuron_runtime.permission_safety_gate import (
    AuthorizationRequest,
    AuthorizationStatus,
    CapabilityRule,
    EffectClass,
    RequestedBudget,
    SafetyPolicy,
    build_approval_grant,
)
from tools.neuron_runtime.policy_registry import (
    PersistentPolicySafetyGate,
    PersistentSafetyPolicyRegistry,
    SafetyPolicyRegistryConflict,
    SafetyPolicyRegistryCorrupt,
    SafetyPolicyRegistryError,
    SafetyPolicyRegistryNotFound,
)
from tools.neuron_runtime.task_session import TaskSessionManager, TaskStatus


def _policy(version=1, *, max_timeout_ms=1000):
    return SafetyPolicy(
        policy_id="policy-1",
        version=version,
        rules=(
            CapabilityRule(
                capability="local.inspect",
                allowed_scopes=("workspace:demo",),
                allowed_effects=(EffectClass.READ_ONLY, EffectClass.EXTERNAL),
                requires_approval=False,
                max_compute_units=20,
                max_memory_bytes=4096,
                max_timeout_ms=max_timeout_ms,
                max_output_bytes=1024,
                max_retries=1,
            ),
        ),
    )


def _manager_and_request():
    manager = TaskSessionManager(manager_id="registry-manager")
    manager.create_session("session-1")
    manager.create_task("task-1", "session-1", "task-key-1", "Inspect a bounded resource")
    manager.transition_task("task-1", TaskStatus.RUNNING, expected_revision=1)
    task = manager.get_task("task-1")
    session = manager.get_session("session-1")
    request = AuthorizationRequest(
        manager_id=manager.manager_id,
        task_id=task.task_id,
        expected_task_revision=task.revision,
        expected_session_revision=session.revision,
        session_id=session.session_id,
        plan_id="plan-1",
        step_id="step-1",
        action_id="action-1",
        capability="local.inspect",
        scope="workspace:demo",
        idempotency_key="idem-1",
        effect=EffectClass.EXTERNAL,
        budget=RequestedBudget(5, 1024, 500, 128, 0),
    )
    return manager, request


class PersistentSafetyPolicyRegistryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "policy-registry.json"
        self.registry = PersistentSafetyPolicyRegistry(self.path)

    def test_policy_snapshot_round_trips_with_private_permissions(self):
        saved = self.registry.save_policy(_policy(), expected_record_version=0)
        reopened = PersistentSafetyPolicyRegistry(self.path).load()
        self.assertEqual(reopened.record_version, 1)
        self.assertEqual(reopened.policy, _policy())
        self.assertEqual(reopened.revoked_approval_ids, ())
        self.assertEqual(reopened.state_digest, saved.state_digest)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(self.registry.lock_path.stat().st_mode), 0o600)

    def test_uninitialized_registry_is_not_treated_as_an_empty_allow_policy(self):
        with self.assertRaises(SafetyPolicyRegistryNotFound):
            self.registry.load()

    def test_record_version_compare_and_swap_rejects_stale_writer(self):
        self.registry.save_policy(_policy(), expected_record_version=0)
        with self.assertRaises(SafetyPolicyRegistryConflict):
            self.registry.save_policy(_policy(version=2), expected_record_version=0)
        self.assertEqual(self.registry.load().record_version, 1)

    def test_changed_policy_must_increase_policy_version(self):
        self.registry.save_policy(_policy(), expected_record_version=0)
        with self.assertRaises(SafetyPolicyRegistryError):
            self.registry.save_policy(
                _policy(max_timeout_ms=400), expected_record_version=1,
            )
        self.assertEqual(self.registry.load().policy.version, 1)

    def test_new_policy_version_is_persisted_and_old_policy_is_no_longer_current(self):
        self.registry.save_policy(_policy(), expected_record_version=0)
        updated = self.registry.save_policy(_policy(version=2), expected_record_version=1)
        self.assertEqual(updated.record_version, 2)
        self.assertEqual(updated.policy.version, 2)

    def test_approval_revocation_survives_reopen(self):
        self.registry.save_policy(_policy(), expected_record_version=0)
        revoked = self.registry.revoke_approval("approval-1", expected_record_version=1)
        reopened = PersistentSafetyPolicyRegistry(self.path).load()
        self.assertEqual(revoked.record_version, 2)
        self.assertEqual(reopened.revoked_approval_ids, ("approval-1",))

    def test_repeating_existing_revocation_is_idempotent(self):
        self.registry.save_policy(_policy(), expected_record_version=0)
        self.registry.revoke_approval("approval-1", expected_record_version=1)
        again = self.registry.revoke_approval("approval-1", expected_record_version=2)
        self.assertEqual(again.record_version, 2)
        self.assertEqual(again.revoked_approval_ids, ("approval-1",))

    def test_damaged_state_digest_is_rejected(self):
        self.registry.save_policy(_policy(), expected_record_version=0)
        envelope = json.loads(self.path.read_text(encoding="ascii"))
        envelope["state_digest"] = "0" * 64
        self.path.write_text(json.dumps(envelope), encoding="ascii")
        with self.assertRaises(SafetyPolicyRegistryCorrupt):
            self.registry.load()

    def test_symlink_policy_path_is_rejected(self):
        target = Path(self.temp.name) / "target.json"
        target.write_text("{}", encoding="ascii")
        self.path.symlink_to(target)
        with self.assertRaises(SafetyPolicyRegistryCorrupt):
            self.registry.load()


class PersistentPolicySafetyGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "policy-registry.json"
        self.registry = PersistentSafetyPolicyRegistry(self.path)
        self.policy = _policy()
        self.manager, self.request = _manager_and_request()
        self.approval = build_approval_grant(
            self.request,
            self.policy,
            approval_id="approval-1",
            approved_by="local-approver",
            approval_ref="ui-confirmation-1",
            expires_at_ns=10_000,
        )
        self.gate = PersistentPolicySafetyGate(self.registry)

    def test_gate_fails_closed_when_registry_is_missing(self):
        decision = self.gate.authorize(
            self.manager, self.policy, self.request, now_ns=100, approval=self.approval,
        )
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason_code, "policy_registry_missing")

    def test_gate_rejects_policy_not_equal_to_persisted_snapshot(self):
        self.registry.save_policy(self.policy, expected_record_version=0)
        changed = _policy(version=2)
        decision = self.gate.authorize(
            self.manager, changed, self.request, now_ns=100, approval=self.approval,
        )
        self.assertEqual(decision.reason_code, "policy_registry_mismatch")

    def test_gate_rejects_revoked_approval(self):
        self.registry.save_policy(self.policy, expected_record_version=0)
        allowed = self.gate.authorize(
            self.manager, self.policy, self.request, now_ns=100, approval=self.approval,
        )
        self.assertIs(allowed.status, AuthorizationStatus.ALLOWED)

        self.registry.revoke_approval("approval-1", expected_record_version=1)
        denied = self.gate.authorize(
            self.manager, self.policy, self.request, now_ns=100, approval=self.approval,
        )
        self.assertFalse(denied.allowed)
        self.assertEqual(denied.reason_code, "approval_revoked")

    def test_gate_uses_persisted_policy_and_allows_nonrevoked_matching_grant(self):
        self.registry.save_policy(self.policy, expected_record_version=0)
        decision = self.gate.authorize(
            self.manager, self.policy, self.request, now_ns=100, approval=self.approval,
        )
        self.assertTrue(decision.allowed)
        self.assertEqual(decision.reason_code, "authorized_with_approval")


if __name__ == "__main__":
    unittest.main()
