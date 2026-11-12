"""Fail-closed capability and approval gate for the JARVIS reference task loop.

This module only authorizes an action request. It never executes the action.
Approval metadata is caller-supplied and must come from a separately trusted
approval channel; this Python reference gate does not authenticate identities.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import re

from .task_session import (
    SessionStatus,
    TaskSessionError,
    TaskSessionManager,
    TaskStatus,
)


_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_DIGEST = re.compile(r"^[0-9a-f]{64}$")
_U64_MAX = (1 << 64) - 1


class PermissionSafetyError(ValueError):
    """Malformed policy, action request, budget or approval record."""


class EffectClass(str, Enum):
    READ_ONLY = "read_only"
    REVERSIBLE = "reversible"
    EXTERNAL = "external"
    IRREVERSIBLE = "irreversible"


class AuthorizationStatus(str, Enum):
    ALLOWED = "allowed"
    DENIED = "denied"


def _identifier(name: str, value: object) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise PermissionSafetyError(f"{name} must be a stable 1..128 character identifier")
    return value


def _scope(name: str, value: object) -> str:
    if not isinstance(value, str):
        raise PermissionSafetyError(f"{name} must be text")
    clean = value.strip()
    if not clean or len(clean) > 512 or "\x00" in clean:
        raise PermissionSafetyError(f"{name} must contain 1..512 characters without NUL")
    return clean


def _uint(name: str, value: object, *, minimum: int = 0, maximum: int = _U64_MAX) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise PermissionSafetyError(f"{name} must be an unsigned integer in {minimum}..{maximum}")
    return value


def _bool(name: str, value: object) -> bool:
    if not isinstance(value, bool):
        raise PermissionSafetyError(f"{name} must be bool")
    return value


@dataclass(frozen=True)
class RequestedBudget:
    """Maximum resources requested for one action attempt."""

    compute_units: int
    memory_bytes: int
    timeout_ms: int
    output_bytes: int = 0
    retry_index: int = 0

    def __post_init__(self) -> None:
        _uint("compute_units", self.compute_units, minimum=1)
        _uint("memory_bytes", self.memory_bytes, minimum=1)
        _uint("timeout_ms", self.timeout_ms, minimum=1)
        _uint("output_bytes", self.output_bytes)
        _uint("retry_index", self.retry_index, maximum=256)


@dataclass(frozen=True)
class CapabilityRule:
    """An explicit capability allow-list entry with exact scope and hard ceilings."""

    capability: str
    allowed_scopes: tuple[str, ...]
    allowed_effects: tuple[EffectClass, ...] = (EffectClass.READ_ONLY,)
    requires_approval: bool = False
    max_compute_units: int = 1
    max_memory_bytes: int = 1
    max_timeout_ms: int = 1
    max_output_bytes: int = 0
    max_retries: int = 0

    def __post_init__(self) -> None:
        _identifier("capability", self.capability)
        if not isinstance(self.allowed_scopes, (tuple, list)) or not self.allowed_scopes:
            raise PermissionSafetyError("allowed_scopes must be a non-empty tuple or list")
        scopes = tuple(_scope("allowed scope", item) for item in self.allowed_scopes)
        if len(set(scopes)) != len(scopes):
            raise PermissionSafetyError("allowed_scopes must be unique")
        if not isinstance(self.allowed_effects, (tuple, list)) or not self.allowed_effects:
            raise PermissionSafetyError("allowed_effects must be a non-empty tuple or list")
        try:
            effects = tuple(EffectClass(item) for item in self.allowed_effects)
        except (TypeError, ValueError) as exc:
            raise PermissionSafetyError("allowed_effects contains an unsupported effect class") from exc
        if len(set(effects)) != len(effects):
            raise PermissionSafetyError("allowed_effects must be unique")
        _bool("requires_approval", self.requires_approval)
        _uint("max_compute_units", self.max_compute_units, minimum=1)
        _uint("max_memory_bytes", self.max_memory_bytes, minimum=1)
        _uint("max_timeout_ms", self.max_timeout_ms, minimum=1)
        _uint("max_output_bytes", self.max_output_bytes)
        _uint("max_retries", self.max_retries, maximum=256)
        object.__setattr__(self, "allowed_scopes", tuple(sorted(scopes)))
        object.__setattr__(self, "allowed_effects", effects)


@dataclass(frozen=True)
class SafetyPolicy:
    """Versioned deny-by-default policy snapshot."""

    policy_id: str
    version: int
    rules: tuple[CapabilityRule, ...]
    enabled: bool = True

    def __post_init__(self) -> None:
        _identifier("policy_id", self.policy_id)
        _uint("policy version", self.version, minimum=1)
        _bool("policy enabled", self.enabled)
        if not isinstance(self.rules, (tuple, list)):
            raise PermissionSafetyError("policy rules must be a tuple or list")
        rules = tuple(self.rules)
        if any(not isinstance(rule, CapabilityRule) for rule in rules):
            raise PermissionSafetyError("policy rules contain an invalid record")
        identifiers = [rule.capability for rule in rules]
        if len(set(identifiers)) != len(identifiers):
            raise PermissionSafetyError("policy must contain at most one rule per capability")
        object.__setattr__(self, "rules", tuple(sorted(rules, key=lambda item: item.capability)))


@dataclass(frozen=True)
class AuthorizationRequest:
    """One proposed action bound to exact task/session revisions and an idempotency key."""

    task_id: str
    expected_task_revision: int
    expected_session_revision: int
    session_id: str
    plan_id: str
    step_id: str
    action_id: str
    capability: str
    scope: str
    idempotency_key: str
    effect: EffectClass
    budget: RequestedBudget

    def __post_init__(self) -> None:
        for name in ("task_id", "session_id", "plan_id", "step_id", "action_id", "capability", "idempotency_key"):
            _identifier(name, getattr(self, name))
        _uint("expected_task_revision", self.expected_task_revision, minimum=1)
        _uint("expected_session_revision", self.expected_session_revision, minimum=1)
        object.__setattr__(self, "scope", _scope("scope", self.scope))
        try:
            object.__setattr__(self, "effect", EffectClass(self.effect))
        except (TypeError, ValueError) as exc:
            raise PermissionSafetyError("unsupported effect class") from exc
        if not isinstance(self.budget, RequestedBudget):
            raise PermissionSafetyError("budget must be a RequestedBudget")


@dataclass(frozen=True)
class ApprovalGrant:
    """Exact, expiring approval receipt supplied by a trusted approval boundary."""

    approval_id: str
    approved_by: str
    approval_ref: str
    task_id: str
    task_revision: int
    session_id: str
    session_revision: int
    plan_id: str
    step_id: str
    action_id: str
    capability: str
    scope: str
    idempotency_key: str
    policy_id: str
    policy_version: int
    policy_digest: str
    intent_digest: str
    expires_at_ns: int

    def __post_init__(self) -> None:
        for name in (
            "approval_id", "approved_by", "task_id", "session_id", "plan_id", "step_id",
            "action_id", "capability", "idempotency_key", "policy_id",
        ):
            _identifier(name, getattr(self, name))
        object.__setattr__(self, "approval_ref", _scope("approval_ref", self.approval_ref))
        object.__setattr__(self, "scope", _scope("scope", self.scope))
        _uint("task_revision", self.task_revision, minimum=1)
        _uint("session_revision", self.session_revision, minimum=1)
        _uint("policy_version", self.policy_version, minimum=1)
        _uint("expires_at_ns", self.expires_at_ns, minimum=1)
        if not isinstance(self.policy_digest, str) or not _DIGEST.fullmatch(self.policy_digest):
            raise PermissionSafetyError("policy_digest must be 64 lowercase hexadecimal characters")
        if not isinstance(self.intent_digest, str) or not _DIGEST.fullmatch(self.intent_digest):
            raise PermissionSafetyError("intent_digest must be 64 lowercase hexadecimal characters")


@dataclass(frozen=True)
class AuthorizationDecision:
    status: AuthorizationStatus
    reason_code: str
    policy_id: str
    policy_version: int
    policy_digest: str
    task_id: str
    task_revision: int
    intent_digest: str
    approval_id: str | None = None

    @property
    def allowed(self) -> bool:
        return self.status is AuthorizationStatus.ALLOWED


def safety_policy_digest(policy: SafetyPolicy) -> str:
    """Canonical digest of the complete effective policy, not only its version."""
    if not isinstance(policy, SafetyPolicy):
        raise PermissionSafetyError("policy must be a SafetyPolicy")
    document = {
        "policy_id": policy.policy_id,
        "version": policy.version,
        "enabled": policy.enabled,
        "rules": [
            {
                "capability": rule.capability,
                "allowed_scopes": list(rule.allowed_scopes),
                "allowed_effects": sorted(effect.value for effect in rule.allowed_effects),
                "requires_approval": rule.requires_approval,
                "max_compute_units": rule.max_compute_units,
                "max_memory_bytes": rule.max_memory_bytes,
                "max_timeout_ms": rule.max_timeout_ms,
                "max_output_bytes": rule.max_output_bytes,
                "max_retries": rule.max_retries,
            }
            for rule in policy.rules
        ],
    }
    encoded = json.dumps(
        document, sort_keys=True, ensure_ascii=True, separators=(",", ":"), allow_nan=False
    ).encode("ascii")
    return hashlib.sha256(b"JARVIS:SAFETY-POLICY:V1\\x00" + encoded).hexdigest()


def authorization_intent_digest(request: AuthorizationRequest) -> str:
    """Hash the exact action intent; retries retain the same intent digest."""
    if not isinstance(request, AuthorizationRequest):
        raise PermissionSafetyError("request must be an AuthorizationRequest")
    document = {
        "task_id": request.task_id,
        "task_revision": request.expected_task_revision,
        "session_revision": request.expected_session_revision,
        "plan_id": request.plan_id,
        "step_id": request.step_id,
        "action_id": request.action_id,
        "capability": request.capability,
        "scope": request.scope,
        "idempotency_key": request.idempotency_key,
        "effect": request.effect.value,
        "budget": {
            "compute_units": request.budget.compute_units,
            "memory_bytes": request.budget.memory_bytes,
            "timeout_ms": request.budget.timeout_ms,
            "output_bytes": request.budget.output_bytes,
        },
    }
    encoded = json.dumps(
        document, sort_keys=True, ensure_ascii=True, separators=(",", ":"), allow_nan=False
    ).encode("ascii")
    return hashlib.sha256(b"JARVIS:AUTH-INTENT:V1\x00" + encoded).hexdigest()


def build_approval_grant(
    request: AuthorizationRequest,
    policy: SafetyPolicy,
    *,
    approval_id: str,
    approved_by: str,
    approval_ref: str,
    expires_at_ns: int,
) -> ApprovalGrant:
    """Build a bound approval receipt for a separately trusted approval provider."""
    if not isinstance(policy, SafetyPolicy):
        raise PermissionSafetyError("policy must be a SafetyPolicy")
    digest = authorization_intent_digest(request)
    return ApprovalGrant(
        approval_id=approval_id,
        approved_by=approved_by,
        approval_ref=approval_ref,
        task_id=request.task_id,
        task_revision=request.expected_task_revision,
        session_id=request.session_id,
        session_revision=request.expected_session_revision,
        plan_id=request.plan_id,
        step_id=request.step_id,
        action_id=request.action_id,
        capability=request.capability,
        scope=request.scope,
        idempotency_key=request.idempotency_key,
        policy_id=policy.policy_id,
        policy_version=policy.version,
        policy_digest=safety_policy_digest(policy),
        intent_digest=digest,
        expires_at_ns=expires_at_ns,
    )


class PermissionSafetyGate:
    """Pure, deny-by-default authorization check; never invokes a capability."""

    _APPROVAL_EFFECTS = frozenset({EffectClass.EXTERNAL, EffectClass.IRREVERSIBLE})

    def authorize(
        self,
        task_manager: TaskSessionManager,
        policy: SafetyPolicy,
        request: AuthorizationRequest,
        *,
        now_ns: int,
        approval: ApprovalGrant | None = None,
    ) -> AuthorizationDecision:
        if not isinstance(task_manager, TaskSessionManager):
            raise PermissionSafetyError("task_manager must be a TaskSessionManager")
        if not isinstance(policy, SafetyPolicy):
            raise PermissionSafetyError("policy must be a SafetyPolicy")
        if not isinstance(request, AuthorizationRequest):
            raise PermissionSafetyError("request must be an AuthorizationRequest")
        now = _uint("now_ns", now_ns)

        digest = authorization_intent_digest(request)
        policy_digest = safety_policy_digest(policy)

        def deny(reason: str, revision: int = request.expected_task_revision):
            return AuthorizationDecision(
                AuthorizationStatus.DENIED, reason, policy.policy_id, policy.version,
                policy_digest, request.task_id, revision, digest, None,
            )

        if not policy.enabled:
            return deny("policy_disabled")
        try:
            task = task_manager.get_task(request.task_id)
            session = task_manager.get_session(task.session_id)
        except TaskSessionError:
            return deny("task_or_session_not_found", 0)
        if task.session_id != request.session_id:
            return deny("task_session_mismatch", task.revision)
        if task.revision != request.expected_task_revision:
            return deny("stale_task_revision", task.revision)
        if session.revision != request.expected_session_revision:
            return deny("stale_session_revision", task.revision)
        if task.status is not TaskStatus.RUNNING:
            return deny("task_not_running", task.revision)
        if session.status is not SessionStatus.OPEN:
            return deny("session_not_open", task.revision)

        rule = next((item for item in policy.rules if item.capability == request.capability), None)
        if rule is None:
            return deny("capability_not_granted", task.revision)
        if request.scope not in rule.allowed_scopes:
            return deny("scope_not_granted", task.revision)
        if request.effect not in rule.allowed_effects:
            return deny("effect_not_granted", task.revision)

        budget = request.budget
        if (
            budget.compute_units > rule.max_compute_units
            or budget.memory_bytes > rule.max_memory_bytes
            or budget.timeout_ms > rule.max_timeout_ms
            or budget.output_bytes > rule.max_output_bytes
        ):
            return deny("resource_budget_exceeded", task.revision)
        if budget.retry_index > rule.max_retries:
            return deny("retry_budget_exceeded", task.revision)

        approval_required = (
            rule.requires_approval or request.effect in self._APPROVAL_EFFECTS
        )
        if not approval_required:
            return AuthorizationDecision(
                AuthorizationStatus.ALLOWED, "authorized", policy.policy_id,
                policy.version, policy_digest, request.task_id, task.revision, digest, None,
            )
        if approval is None:
            return deny("approval_required", task.revision)
        if not isinstance(approval, ApprovalGrant):
            raise PermissionSafetyError("approval must be an ApprovalGrant or None")
        bindings = (
            approval.task_id == request.task_id
            and approval.task_revision == task.revision
            and approval.session_id == session.session_id
            and approval.session_revision == session.revision
            and approval.plan_id == request.plan_id
            and approval.step_id == request.step_id
            and approval.action_id == request.action_id
            and approval.capability == request.capability
            and approval.scope == request.scope
            and approval.idempotency_key == request.idempotency_key
            and approval.policy_id == policy.policy_id
            and approval.policy_version == policy.version
            and approval.policy_digest == policy_digest
            and approval.intent_digest == digest
        )
        if not bindings:
            return deny("approval_binding_mismatch", task.revision)
        if now >= approval.expires_at_ns:
            return deny("approval_expired", task.revision)
        return AuthorizationDecision(
            AuthorizationStatus.ALLOWED, "authorized_with_approval",
            policy.policy_id, policy.version, policy_digest, request.task_id,
            task.revision, digest, approval.approval_id,
        )
