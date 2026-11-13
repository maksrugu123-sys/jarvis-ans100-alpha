"""Ed25519 verification for exact-request JARVIS approval envelopes.

This module verifies signed approvals against an explicitly provisioned trust
snapshot. It does not create signatures, provision trust roots, persist key
rotation/revocation, or execute actions. Missing crypto support fails closed.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass, replace, fields
from enum import Enum
import json
import re

from .permission_safety_gate import (
    ApprovalGrant,
    AuthorizationDecision,
    AuthorizationRequest,
    PermissionSafetyError,
    PermissionSafetyGate,
    SafetyPolicy,
)
from .task_session import TaskSessionManager


_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_U64_MAX = (1 << 64) - 1
_DOMAIN = b"JARVIS:SIGNED-APPROVAL:V1\x00"
_SCHEMA = "jarvis.signed-approval"
_VERSION = 1
_ALGORITHM = "Ed25519"
_SIGNATURE_BYTES = 64
_PUBLIC_KEY_BYTES = 32
_MAX_ENVELOPE_BYTES = 32 * 1024
_MAX_APPROVAL_LIFETIME_NS = 5 * 60 * 1_000_000_000
_MAX_CLOCK_SKEW_NS = 30 * 1_000_000_000


class SignedApprovalError(ValueError):
    """A signed approval is malformed, untrusted, expired, or unverifiable."""

    def __init__(self, reason_code: str, message: str) -> None:
        super().__init__(message)
        self.reason_code = reason_code


class TrustKeyStatus(str, Enum):
    ACTIVE = "active"
    REVOKED = "revoked"


def _identifier(name: str, value: object) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise SignedApprovalError("approval_malformed", f"{name} must be a stable identifier")
    return value


def _uint(name: str, value: object, *, minimum: int = 0, maximum: int = _U64_MAX) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise SignedApprovalError("approval_malformed", f"{name} is outside its unsigned integer range")
    return value


@dataclass(frozen=True)
class ApprovalTrustKey:
    """A public key pinned by an out-of-band trusted enrollment process."""

    key_id: str
    approver_id: str
    public_key: bytes
    valid_from_utc_ns: int
    valid_until_utc_ns: int
    status: TrustKeyStatus = TrustKeyStatus.ACTIVE

    def __post_init__(self) -> None:
        _identifier("key_id", self.key_id)
        _identifier("approver_id", self.approver_id)
        if not isinstance(self.public_key, bytes) or len(self.public_key) != _PUBLIC_KEY_BYTES:
            raise SignedApprovalError("approval_malformed", "Ed25519 public key must be exactly 32 bytes")
        _uint("valid_from_utc_ns", self.valid_from_utc_ns)
        _uint("valid_until_utc_ns", self.valid_until_utc_ns, minimum=1)
        if self.valid_until_utc_ns <= self.valid_from_utc_ns:
            raise SignedApprovalError("approval_malformed", "key validity interval is empty")
        try:
            object.__setattr__(self, "status", TrustKeyStatus(self.status))
        except (TypeError, ValueError) as exc:
            raise SignedApprovalError("approval_malformed", "unsupported trust-key status") from exc


@dataclass(frozen=True)
class ApprovalTrustStore:
    """Immutable in-memory trust snapshot; secure provisioning is a separate gate."""

    trust_epoch: int
    keys: tuple[ApprovalTrustKey, ...]

    def __post_init__(self) -> None:
        _uint("trust_epoch", self.trust_epoch, minimum=1)
        if not isinstance(self.keys, (tuple, list)) or not self.keys:
            raise SignedApprovalError("approval_malformed", "trust store must contain at least one key")
        keys = tuple(self.keys)
        if any(not isinstance(key, ApprovalTrustKey) for key in keys):
            raise SignedApprovalError("approval_malformed", "trust store contains an invalid key")
        identifiers = [key.key_id for key in keys]
        if len(set(identifiers)) != len(identifiers):
            raise SignedApprovalError("approval_malformed", "trust store key IDs must be unique")
        object.__setattr__(self, "keys", tuple(sorted(keys, key=lambda item: item.key_id)))

    def get_key(self, key_id: str) -> ApprovalTrustKey | None:
        return next((key for key in self.keys if key.key_id == key_id), None)


@dataclass(frozen=True)
class SignedApprovalEnvelope:
    """A signed, portable, exact-request approval receipt."""

    key_id: str
    approver_id: str
    issued_at_utc_ns: int
    expires_at_utc_ns: int
    grant: ApprovalGrant
    signature: bytes
    algorithm: str = _ALGORITHM
    version: int = _VERSION

    def __post_init__(self) -> None:
        _identifier("key_id", self.key_id)
        _identifier("approver_id", self.approver_id)
        _uint("issued_at_utc_ns", self.issued_at_utc_ns, minimum=1)
        _uint("expires_at_utc_ns", self.expires_at_utc_ns, minimum=1)
        if self.expires_at_utc_ns <= self.issued_at_utc_ns:
            raise SignedApprovalError("approval_malformed", "approval expiry must follow issue time")
        if self.expires_at_utc_ns - self.issued_at_utc_ns > _MAX_APPROVAL_LIFETIME_NS:
            raise SignedApprovalError("approval_lifetime_exceeded", "approval lifetime exceeds five minutes")
        if not isinstance(self.grant, ApprovalGrant):
            raise SignedApprovalError("approval_malformed", "grant must be an ApprovalGrant")
        if self.grant.approved_by != self.approver_id:
            raise SignedApprovalError("approval_approver_mismatch", "grant approver and envelope approver differ")
        if self.grant.expires_at_ns != self.expires_at_utc_ns:
            raise SignedApprovalError("approval_expiry_mismatch", "grant and envelope expiry differ")
        if self.algorithm != _ALGORITHM or self.version != _VERSION:
            raise SignedApprovalError("approval_format_unsupported", "unsupported signed approval version or algorithm")
        if not isinstance(self.signature, bytes) or len(self.signature) != _SIGNATURE_BYTES:
            raise SignedApprovalError("approval_malformed", "Ed25519 signature must be exactly 64 bytes")


def _grant_document(grant: ApprovalGrant) -> dict[str, object]:
    return {
        "approval_id": grant.approval_id,
        "approved_by": grant.approved_by,
        "approval_ref": grant.approval_ref,
        "manager_id": grant.manager_id,
        "task_id": grant.task_id,
        "task_revision": grant.task_revision,
        "session_id": grant.session_id,
        "session_revision": grant.session_revision,
        "plan_id": grant.plan_id,
        "step_id": grant.step_id,
        "action_id": grant.action_id,
        "capability": grant.capability,
        "scope": grant.scope,
        "idempotency_key": grant.idempotency_key,
        "policy_id": grant.policy_id,
        "policy_version": grant.policy_version,
        "policy_digest": grant.policy_digest,
        "intent_digest": grant.intent_digest,
        "expires_at_ns": grant.expires_at_ns,
    }


def _envelope_unsigned_document(envelope: SignedApprovalEnvelope) -> dict[str, object]:
    return {
        "schema": _SCHEMA,
        "version": envelope.version,
        "algorithm": envelope.algorithm,
        "key_id": envelope.key_id,
        "approver_id": envelope.approver_id,
        "issued_at_utc_ns": envelope.issued_at_utc_ns,
        "expires_at_utc_ns": envelope.expires_at_utc_ns,
        "grant": _grant_document(envelope.grant),
    }


def canonical_approval_signing_bytes(envelope: SignedApprovalEnvelope) -> bytes:
    """Return domain-separated canonical bytes; the signature itself is excluded."""
    if not isinstance(envelope, SignedApprovalEnvelope):
        raise SignedApprovalError("approval_malformed", "envelope has an invalid type")
    encoded = json.dumps(
        _envelope_unsigned_document(envelope),
        sort_keys=True,
        ensure_ascii=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("ascii")
    return _DOMAIN + encoded


def encode_signed_approval(envelope: SignedApprovalEnvelope) -> bytes:
    """Encode the portable UTF-8 JSON envelope with unpadded Base64URL signature."""
    document = _envelope_unsigned_document(envelope)
    document["signature"] = base64.urlsafe_b64encode(envelope.signature).decode("ascii").rstrip("=")
    encoded = json.dumps(
        document, sort_keys=True, ensure_ascii=True, separators=(",", ":"), allow_nan=False
    ).encode("ascii")
    if len(encoded) > _MAX_ENVELOPE_BYTES:
        raise SignedApprovalError("approval_malformed", "signed approval envelope is too large")
    return encoded


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise SignedApprovalError("approval_malformed", "duplicate JSON object key")
        result[key] = value
    return result


def decode_signed_approval(payload: bytes | str) -> SignedApprovalEnvelope:
    """Strictly decode one bounded envelope; unknown and duplicate fields fail closed."""
    if isinstance(payload, str):
        raw = payload.encode("utf-8")
    elif isinstance(payload, bytes):
        raw = payload
    else:
        raise SignedApprovalError("approval_malformed", "payload must be UTF-8 bytes or text")
    if not raw or len(raw) > _MAX_ENVELOPE_BYTES:
        raise SignedApprovalError("approval_malformed", "payload is empty or exceeds the envelope limit")
    try:
        document = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicate_keys)
    except SignedApprovalError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SignedApprovalError("approval_malformed", "payload is not valid UTF-8 JSON") from exc
    expected_fields = {
        "schema", "version", "algorithm", "key_id", "approver_id",
        "issued_at_utc_ns", "expires_at_utc_ns", "grant", "signature",
    }
    if not isinstance(document, dict) or set(document) != expected_fields:
        raise SignedApprovalError("approval_malformed", "envelope fields do not match V1")
    if document["schema"] != _SCHEMA:
        raise SignedApprovalError("approval_format_unsupported", "unsupported approval schema")
    grant_doc = document["grant"]
    grant_fields = {item.name for item in fields(ApprovalGrant)}
    if not isinstance(grant_doc, dict) or set(grant_doc) != grant_fields:
        raise SignedApprovalError("approval_malformed", "grant fields do not match the V1 contract")
    signature_text = document["signature"]
    if not isinstance(signature_text, str) or not re.fullmatch(r"[A-Za-z0-9_-]{86}", signature_text):
        raise SignedApprovalError("approval_malformed", "signature must be unpadded Base64URL")
    try:
        signature = base64.b64decode(signature_text + "==", altchars=b"-_", validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise SignedApprovalError("approval_malformed", "signature encoding is invalid") from exc
    try:
        grant = ApprovalGrant(**grant_doc)
        return SignedApprovalEnvelope(
            key_id=document["key_id"],
            approver_id=document["approver_id"],
            issued_at_utc_ns=document["issued_at_utc_ns"],
            expires_at_utc_ns=document["expires_at_utc_ns"],
            grant=grant,
            signature=signature,
            algorithm=document["algorithm"],
            version=document["version"],
        )
    except (TypeError, PermissionSafetyError) as exc:
        raise SignedApprovalError("approval_malformed", "grant or envelope fields are invalid") from exc


def _verify_ed25519(public_key: bytes, message: bytes, signature: bytes) -> bool:
    """Use pyca/cryptography when available; never substitute a weaker algorithm."""
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError as exc:
        raise SignedApprovalError(
            "approval_crypto_unavailable",
            "Ed25519 verification requires the optional 'cryptography' package",
        ) from exc
    try:
        Ed25519PublicKey.from_public_bytes(public_key).verify(signature, message)
        return True
    except InvalidSignature:
        return False
    except (TypeError, ValueError) as exc:
        raise SignedApprovalError("approval_malformed", "Ed25519 key or signature is invalid") from exc


def verify_signed_approval(
    envelope: SignedApprovalEnvelope,
    trust_store: ApprovalTrustStore,
    *,
    now_utc_ns: int,
) -> ApprovalGrant:
    """Verify key trust, identity, bounded UTC validity and Ed25519 signature."""
    if not isinstance(envelope, SignedApprovalEnvelope):
        raise SignedApprovalError("approval_malformed", "envelope has an invalid type")
    if not isinstance(trust_store, ApprovalTrustStore):
        raise SignedApprovalError("approval_untrusted_key", "a trusted approval key snapshot is required")
    now = _uint("now_utc_ns", now_utc_ns, minimum=1)
    key = trust_store.get_key(envelope.key_id)
    if key is None:
        raise SignedApprovalError("approval_key_unknown", "signing key is not in the pinned trust snapshot")
    if key.status is TrustKeyStatus.REVOKED:
        raise SignedApprovalError("approval_key_revoked", "signing key is revoked")
    if key.approver_id != envelope.approver_id or envelope.grant.approved_by != key.approver_id:
        raise SignedApprovalError("approval_approver_mismatch", "signing key is not bound to this approver")
    if not (key.valid_from_utc_ns <= envelope.issued_at_utc_ns < key.valid_until_utc_ns):
        raise SignedApprovalError("approval_key_outside_validity", "signing key was not valid at issue time")
    if not (key.valid_from_utc_ns <= now < key.valid_until_utc_ns):
        raise SignedApprovalError("approval_key_outside_validity", "signing key is not currently valid")
    if envelope.issued_at_utc_ns > now + _MAX_CLOCK_SKEW_NS:
        raise SignedApprovalError("approval_issued_in_future", "approval issue time is too far in the future")
    if now >= envelope.expires_at_utc_ns:
        raise SignedApprovalError("approval_expired", "approval has expired")
    valid = _verify_ed25519(
        key.public_key, canonical_approval_signing_bytes(envelope), envelope.signature
    )
    if valid is not True:
        raise SignedApprovalError("approval_signature_invalid", "approval signature verification failed")
    return envelope.grant


class TrustedApprovalSafetyGate(PermissionSafetyGate):
    """Gate variant that accepts only signatures from its pinned approver key set.

    The now_ns argument to authorize() is UTC Unix nanoseconds for this gate.
    The ordinary PermissionSafetyGate remains a legacy reference-only unsigned API.
    """

    def __init__(self, trust_store: ApprovalTrustStore) -> None:
        super().__init__()
        if not isinstance(trust_store, ApprovalTrustStore):
            raise PermissionSafetyError("trust_store must be an ApprovalTrustStore")
        self.trust_store = trust_store

    def authorize(
        self,
        task_manager: TaskSessionManager,
        policy: SafetyPolicy,
        request: AuthorizationRequest,
        *,
        now_ns: int,
        approval: SignedApprovalEnvelope | ApprovalGrant | None = None,
    ) -> AuthorizationDecision:
        if approval is None:
            return super().authorize(task_manager, policy, request, now_ns=now_ns, approval=None)
        if not isinstance(approval, SignedApprovalEnvelope):
            pending = super().authorize(task_manager, policy, request, now_ns=now_ns, approval=None)
            if pending.reason_code == "approval_required":
                return replace(pending, reason_code="approval_signature_required")
            return pending
        try:
            grant = verify_signed_approval(approval, self.trust_store, now_utc_ns=now_ns)
        except SignedApprovalError as exc:
            pending = super().authorize(task_manager, policy, request, now_ns=now_ns, approval=None)
            if pending.reason_code == "approval_required":
                return replace(pending, reason_code=exc.reason_code)
            return pending
        return super().authorize(task_manager, policy, request, now_ns=now_ns, approval=grant)
