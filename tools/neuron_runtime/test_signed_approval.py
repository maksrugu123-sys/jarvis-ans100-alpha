"""Ed25519 signed-approval contract tests. AUTHORED, NOT RUN under current policy."""
import json
import unittest
from dataclasses import replace

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from tools.neuron_runtime.permission_safety_gate import (
    AuthorizationRequest,
    CapabilityRule,
    EffectClass,
    RequestedBudget,
    SafetyPolicy,
    build_approval_grant,
)
from tools.neuron_runtime.signed_approval import (
    ApprovalTrustKey,
    ApprovalTrustStore,
    SignedApprovalEnvelope,
    SignedApprovalError,
    TrustedApprovalSafetyGate,
    TrustKeyStatus,
    canonical_approval_signing_bytes,
    decode_signed_approval,
    encode_signed_approval,
    verify_signed_approval,
)
from tools.neuron_runtime.task_session import TaskSessionManager, TaskStatus


_NOW = 2_000_000_000
_ISSUED = 1_000_000_000
_EXPIRES = 200_000_000_000


def _fixture(*, key_status=TrustKeyStatus.ACTIVE, key_id="owner-key-1"):
    private = Ed25519PrivateKey.generate()
    public = private.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    manager = TaskSessionManager(manager_id="signed-manager")
    manager.create_session("signed-session")
    manager.create_task("signed-task", "signed-session", "signed-task-key", "Approve one exact action")
    manager.transition_task("signed-task", TaskStatus.RUNNING, expected_revision=1)
    task = manager.get_task("signed-task")
    session = manager.get_session("signed-session")
    request = AuthorizationRequest(
        manager_id=manager.manager_id,
        task_id=task.task_id,
        expected_task_revision=task.revision,
        expected_session_revision=session.revision,
        session_id=session.session_id,
        plan_id="signed-plan",
        step_id="signed-step",
        action_id="signed-action",
        capability="demo.write",
        scope="demo:item-1",
        idempotency_key="signed-idempotency-1",
        effect=EffectClass.EXTERNAL,
        budget=RequestedBudget(10, 4096, 1000, 128, 0),
    )
    rule = CapabilityRule(
        capability="demo.write",
        allowed_scopes=("demo:item-1",),
        allowed_effects=(EffectClass.EXTERNAL,),
        requires_approval=False,
        max_compute_units=20,
        max_memory_bytes=8192,
        max_timeout_ms=2000,
        max_output_bytes=256,
        max_retries=0,
    )
    policy = SafetyPolicy("signed-policy", 1, (rule,))
    grant = build_approval_grant(
        request, policy,
        approval_id="signed-approval-1",
        approved_by="owner-1",
        approval_ref="trusted-confirmation-1",
        expires_at_ns=_EXPIRES,
    )
    unsigned = SignedApprovalEnvelope(
        key_id=key_id,
        approver_id="owner-1",
        issued_at_utc_ns=_ISSUED,
        expires_at_utc_ns=_EXPIRES,
        grant=grant,
        signature=b"\x00" * 64,
    )
    signature = private.sign(canonical_approval_signing_bytes(unsigned))
    envelope = replace(unsigned, signature=signature)
    key = ApprovalTrustKey(
        key_id=key_id,
        approver_id="owner-1",
        public_key=public,
        valid_from_utc_ns=1,
        valid_until_utc_ns=500_000_000_000,
        status=key_status,
    )
    trust = ApprovalTrustStore(trust_epoch=1, keys=(key,))
    return manager, request, policy, grant, envelope, trust, private, key


class SignedApprovalVerificationTests(unittest.TestCase):
    def test_valid_signature_verifies_exact_grant(self):
        manager, request, policy, grant, envelope, trust, *_ = _fixture()
        verified = verify_signed_approval(envelope, trust, now_utc_ns=_NOW)
        self.assertEqual(verified, grant)
        decision = TrustedApprovalSafetyGate(trust).authorize(
            manager, policy, request, now_ns=_NOW, approval=envelope,
        )
        self.assertTrue(decision.allowed)
        self.assertEqual(decision.reason_code, "authorized_with_approval")

    def test_signature_tampering_is_rejected(self):
        _, _, _, _, envelope, trust, *_ = _fixture()
        tampered = replace(envelope, signature=bytes([envelope.signature[0] ^ 1]) + envelope.signature[1:])
        with self.assertRaisesRegex(SignedApprovalError, "signature verification failed"):
            verify_signed_approval(tampered, trust, now_utc_ns=_NOW)

    def test_any_grant_field_change_invalidates_signature(self):
        _, _, _, _, envelope, trust, *_ = _fixture()
        changed_grant = replace(envelope.grant, scope="demo:item-2")
        changed = replace(envelope, grant=changed_grant)
        with self.assertRaisesRegex(SignedApprovalError, "signature verification failed"):
            verify_signed_approval(changed, trust, now_utc_ns=_NOW)

    def test_unknown_signing_key_is_rejected(self):
        _, _, _, _, envelope, trust, *_ = _fixture()
        other = replace(envelope, key_id="unknown-key")
        with self.assertRaisesRegex(SignedApprovalError, "not in the pinned trust snapshot"):
            verify_signed_approval(other, trust, now_utc_ns=_NOW)

    def test_revoked_signing_key_is_rejected(self):
        _, _, _, _, envelope, _, _, key = _fixture(key_status=TrustKeyStatus.REVOKED)
        trust = ApprovalTrustStore(trust_epoch=2, keys=(key,))
        with self.assertRaisesRegex(SignedApprovalError, "signing key is revoked"):
            verify_signed_approval(envelope, trust, now_utc_ns=_NOW)

    def test_signing_key_must_match_approver_identity(self):
        _, _, _, _, envelope, _, _, key = _fixture()
        mismatched = replace(key, approver_id="other-owner")
        trust = ApprovalTrustStore(trust_epoch=1, keys=(mismatched,))
        with self.assertRaisesRegex(SignedApprovalError, "not bound to this approver"):
            verify_signed_approval(envelope, trust, now_utc_ns=_NOW)

    def test_expired_approval_is_rejected(self):
        _, _, _, _, envelope, trust, *_ = _fixture()
        with self.assertRaisesRegex(SignedApprovalError, "approval has expired"):
            verify_signed_approval(envelope, trust, now_utc_ns=_EXPIRES)

    def test_future_issue_time_outside_clock_skew_is_rejected(self):
        _, _, _, _, envelope, trust, *_ = _fixture()
        future = replace(envelope, issued_at_utc_ns=_NOW + 31_000_000_000)
        with self.assertRaisesRegex(SignedApprovalError, "too far in the future"):
            verify_signed_approval(future, trust, now_utc_ns=_NOW)

    def test_trust_key_validity_is_enforced_at_verification_time(self):
        _, _, _, _, envelope, _, _, key = _fixture()
        expired_key = replace(key, valid_until_utc_ns=_NOW)
        trust = ApprovalTrustStore(trust_epoch=2, keys=(expired_key,))
        with self.assertRaisesRegex(SignedApprovalError, "not currently valid"):
            verify_signed_approval(envelope, trust, now_utc_ns=_NOW)

    def test_untrusted_plain_grant_is_rejected_by_trusted_gate(self):
        manager, request, policy, grant, _, trust, *_ = _fixture()
        decision = TrustedApprovalSafetyGate(trust).authorize(
            manager, policy, request, now_ns=_NOW, approval=grant,
        )
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason_code, "approval_signature_required")

    def test_missing_approval_remains_denied_for_external_action(self):
        manager, request, policy, _, _, trust, *_ = _fixture()
        decision = TrustedApprovalSafetyGate(trust).authorize(
            manager, policy, request, now_ns=_NOW,
        )
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason_code, "approval_required")

    def test_envelope_round_trip_preserves_signed_bytes(self):
        _, _, _, _, envelope, trust, *_ = _fixture()
        encoded = encode_signed_approval(envelope)
        decoded = decode_signed_approval(encoded)
        self.assertEqual(decoded, envelope)
        self.assertEqual(verify_signed_approval(decoded, trust, now_utc_ns=_NOW), envelope.grant)

    def test_duplicate_json_keys_are_rejected(self):
        _, _, _, _, envelope, *_ = _fixture()
        encoded = encode_signed_approval(envelope).decode("ascii")
        duplicated = encoded[:-1] + ',"version":1}'
        with self.assertRaisesRegex(SignedApprovalError, "duplicate JSON object key"):
            decode_signed_approval(duplicated)

    def test_unknown_envelope_fields_are_rejected(self):
        _, _, _, _, envelope, *_ = _fixture()
        document = json.loads(encode_signed_approval(envelope))
        document["unexpected"] = True
        with self.assertRaisesRegex(SignedApprovalError, "fields do not match V1"):
            decode_signed_approval(json.dumps(document))

    def test_invalid_signature_length_is_rejected(self):
        _, _, _, _, envelope, *_ = _fixture()
        with self.assertRaisesRegex(SignedApprovalError, "exactly 64 bytes"):
            replace(envelope, signature=b"short")

    def test_duplicate_trust_key_ids_are_rejected(self):
        *_, key = _fixture()
        with self.assertRaisesRegex(SignedApprovalError, "key IDs must be unique"):
            ApprovalTrustStore(trust_epoch=1, keys=(key, key))


if __name__ == "__main__":
    unittest.main()
