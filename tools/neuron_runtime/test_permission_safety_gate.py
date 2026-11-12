"""Contract tests for the deny-by-default Permission & Safety Gate V1."""
import unittest
from dataclasses import replace

from tools.neuron_runtime.permission_safety_gate import (
    ApprovalGrant,
    AuthorizationRequest,
    AuthorizationStatus,
    CapabilityRule,
    EffectClass,
    PermissionSafetyError,
    PermissionSafetyGate,
    RequestedBudget,
    SafetyPolicy,
    build_approval_grant,
)
from tools.neuron_runtime.task_session import (
    SessionStatus,
    TaskSessionManager,
    TaskStatus,
)


def _manager(*, running=True):
    manager = TaskSessionManager(manager_id="gate-manager")
    manager.create_session("session-1")
    manager.create_task("task-1", "session-1", "task-key", "Authorize a bounded action")
    if running:
        manager.transition_task("task-1", TaskStatus.RUNNING, expected_revision=1)
    return manager


def _request(manager=None, **changes):
    manager = manager or _manager()
    task = manager.get_task("task-1")
    session = manager.get_session(task.session_id)
    values = {
        "task_id": task.task_id,
        "expected_task_revision": task.revision,
        "expected_session_revision": session.revision,
        "session_id": session.session_id,
        "plan_id": "plan-1",
        "step_id": "step-1",
        "action_id": "action-1",
        "capability": "repo.read",
        "scope": "repo:demo",
        "idempotency_key": "idem-action-1",
        "effect": EffectClass.READ_ONLY,
        "budget": RequestedBudget(10, 1024, 500, 128, 0),
    }
    values.update(changes)
    return AuthorizationRequest(**values)


def _rule(**changes):
    values = {
        "capability": "repo.read",
        "allowed_scopes": ("repo:demo",),
        "allowed_effects": (EffectClass.READ_ONLY,),
        "max_compute_units": 20,
        "max_memory_bytes": 2048,
        "max_timeout_ms": 1000,
        "max_output_bytes": 256,
        "max_retries": 1,
    }
    values.update(changes)
    return CapabilityRule(**values)


def _policy(rule=None, **changes):
    values = {"policy_id": "policy-1", "version": 1, "rules": (_rule() if rule is None else rule,)}
    values.update(changes)
    return SafetyPolicy(**values)


class PermissionSafetyGatePolicyTests(unittest.TestCase):
    def setUp(self):
        self.manager = _manager()
        self.gate = PermissionSafetyGate()
        self.request = _request(self.manager)

    def test_exact_granted_read_only_action_is_allowed(self):
        decision = self.gate.authorize(
            self.manager, _policy(), self.request, now_ns=100
        )
        self.assertIs(decision.status, AuthorizationStatus.ALLOWED)
        self.assertEqual(decision.reason_code, "authorized")
        self.assertIsNone(decision.approval_id)

    def test_missing_capability_is_denied_by_default(self):
        decision = self.gate.authorize(
            self.manager, SafetyPolicy("empty-policy", 1, ()),
            self.request, now_ns=100,
        )
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason_code, "capability_not_granted")

    def test_disabled_policy_denies_even_a_matching_rule(self):
        decision = self.gate.authorize(
            self.manager, _policy(enabled=False), self.request, now_ns=100
        )
        self.assertEqual(decision.reason_code, "policy_disabled")

    def test_scope_match_is_exact_not_prefix_based(self):
        request = _request(self.manager, scope="repo:demo/subpath")
        decision = self.gate.authorize(self.manager, _policy(), request, now_ns=100)
        self.assertEqual(decision.reason_code, "scope_not_granted")

    def test_unlisted_effect_is_denied(self):
        request = _request(self.manager, effect=EffectClass.REVERSIBLE)
        decision = self.gate.authorize(self.manager, _policy(), request, now_ns=100)
        self.assertEqual(decision.reason_code, "effect_not_granted")

    def test_each_resource_ceiling_is_enforced(self):
        policy = _policy()
        requests = (
            _request(self.manager, budget=RequestedBudget(21, 1024, 500, 128, 0)),
            _request(self.manager, budget=RequestedBudget(10, 2049, 500, 128, 0)),
            _request(self.manager, budget=RequestedBudget(10, 1024, 1001, 128, 0)),
            _request(self.manager, budget=RequestedBudget(10, 1024, 500, 257, 0)),
        )
        for request in requests:
            with self.subTest(budget=request.budget):
                decision = self.gate.authorize(self.manager, policy, request, now_ns=100)
                self.assertEqual(decision.reason_code, "resource_budget_exceeded")

    def test_retry_budget_is_enforced(self):
        request = _request(self.manager, budget=RequestedBudget(10, 1024, 500, 128, 2))
        decision = self.gate.authorize(self.manager, _policy(), request, now_ns=100)
        self.assertEqual(decision.reason_code, "retry_budget_exceeded")

    def test_stale_task_revision_is_denied(self):
        request = _request(self.manager, expected_task_revision=1)
        decision = self.gate.authorize(self.manager, _policy(), request, now_ns=100)
        self.assertEqual(decision.reason_code, "stale_task_revision")

    def test_stale_session_revision_is_denied(self):
        request = _request(self.manager, expected_session_revision=2)
        decision = self.gate.authorize(self.manager, _policy(), request, now_ns=100)
        self.assertEqual(decision.reason_code, "stale_session_revision")

    def test_task_session_identity_must_match(self):
        request = _request(self.manager, session_id="session-other")
        decision = self.gate.authorize(self.manager, _policy(), request, now_ns=100)
        self.assertEqual(decision.reason_code, "task_session_mismatch")

    def test_non_running_task_is_denied(self):
        manager = _manager(running=False)
        request = _request(manager)
        decision = self.gate.authorize(manager, _policy(), request, now_ns=100)
        self.assertEqual(decision.reason_code, "task_not_running")

    def test_paused_session_is_denied(self):
        self.manager.set_session_status("session-1", SessionStatus.PAUSED, expected_revision=1)
        request = _request(
            self.manager, expected_session_revision=self.manager.get_session("session-1").revision
        )
        decision = self.gate.authorize(self.manager, _policy(), request, now_ns=100)
        self.assertEqual(decision.reason_code, "session_not_open")

    def test_authorization_is_pure_and_does_not_change_lifecycle(self):
        before_revision = self.manager.revision
        before_events = self.manager.events
        self.gate.authorize(self.manager, _policy(), self.request, now_ns=100)
        self.assertEqual(self.manager.revision, before_revision)
        self.assertEqual(self.manager.events, before_events)

    def test_duplicate_capability_rules_are_rejected(self):
        rule = _rule()
        with self.assertRaises(PermissionSafetyError):
            SafetyPolicy("policy-dup", 1, (rule, rule))


class PermissionSafetyGateApprovalTests(unittest.TestCase):
    def setUp(self):
        self.manager = _manager()
        self.gate = PermissionSafetyGate()
        self.rule = _rule(
            allowed_effects=(EffectClass.READ_ONLY, EffectClass.EXTERNAL),
        )
        self.policy = _policy(self.rule)
        self.request = _request(self.manager, effect=EffectClass.EXTERNAL)

    def _approval(self, *, expires_at_ns=1000):
        return build_approval_grant(
            self.request, self.policy,
            approval_id="approval-1", approved_by="owner-1",
            approval_ref="user-confirmation-1", expires_at_ns=expires_at_ns,
        )

    def test_external_action_requires_approval_even_when_rule_does_not(self):
        decision = self.gate.authorize(
            self.manager, self.policy, self.request, now_ns=100
        )
        self.assertEqual(decision.reason_code, "approval_required")

    def test_exact_unexpired_approval_allows_bound_action(self):
        approval = self._approval()
        decision = self.gate.authorize(
            self.manager, self.policy, self.request, now_ns=100, approval=approval
        )
        self.assertTrue(decision.allowed)
        self.assertEqual(decision.reason_code, "authorized_with_approval")
        self.assertEqual(decision.approval_id, "approval-1")

    def test_expired_approval_is_denied(self):
        decision = self.gate.authorize(
            self.manager, self.policy, self.request, now_ns=1000,
            approval=self._approval(expires_at_ns=1000),
        )
        self.assertEqual(decision.reason_code, "approval_expired")

    def test_approval_cannot_be_reused_for_another_intent(self):
        approval = self._approval()
        changed = _request(self.manager, effect=EffectClass.EXTERNAL, action_id="action-2")
        decision = self.gate.authorize(
            self.manager, self.policy, changed, now_ns=100, approval=approval
        )
        self.assertEqual(decision.reason_code, "approval_binding_mismatch")

    def test_policy_version_change_invalidates_approval(self):
        approval = self._approval()
        changed_policy = _policy(
            self.rule, version=2,
        )
        decision = self.gate.authorize(
            self.manager, changed_policy, self.request, now_ns=100, approval=approval
        )
        self.assertEqual(decision.reason_code, "approval_binding_mismatch")

    def test_session_revision_change_invalidates_approval(self):
        approval = self._approval()
        self.manager.set_session_status("session-1", SessionStatus.PAUSED, expected_revision=1)
        self.manager.set_session_status("session-1", SessionStatus.OPEN, expected_revision=2)
        changed = _request(
            self.manager,
            expected_session_revision=self.manager.get_session("session-1").revision,
            effect=EffectClass.EXTERNAL,
        )
        decision = self.gate.authorize(
            self.manager, self.policy, changed, now_ns=100, approval=approval
        )
        self.assertEqual(decision.reason_code, "approval_binding_mismatch")

    def test_empty_approval_reference_is_rejected(self):
        with self.assertRaises(PermissionSafetyError):
            ApprovalGrant(
                "approval-1", "owner-1", "", "task-1", 2, "session-1", 1,
                "plan-1", "step-1", "action-1", "repo.read", "repo:demo",
                "idem-action-1", "policy-1", 1, "0" * 64, 1000,
            )


class PermissionSafetyGateValidationTests(unittest.TestCase):
    def test_invalid_budget_cannot_be_constructed(self):
        with self.assertRaises(PermissionSafetyError):
            RequestedBudget(True, 1024, 500)

    def test_invalid_capability_identifier_is_rejected(self):
        with self.assertRaises(PermissionSafetyError):
            _rule(capability="write all *")

    def test_approval_grant_is_not_created_for_malformed_request(self):
        with self.assertRaises(PermissionSafetyError):
            _request(expected_task_revision=True)


if __name__ == "__main__":
    unittest.main()
