"""Atomic local safety-policy and approval-revocation registry.

The registry persists policy snapshots and revoked approval IDs. It does not
authenticate approvers or verify grant signatures; it is a fail-closed reference
boundary for the Python Task Loop until a trusted signer is integrated.
"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
from typing import Iterator

from .permission_safety_gate import (
    ApprovalGrant,
    AuthorizationDecision,
    AuthorizationRequest,
    AuthorizationStatus,
    CapabilityRule,
    EffectClass,
    PermissionSafetyError,
    PermissionSafetyGate,
    RequestedBudget,
    SafetyPolicy,
    authorization_intent_digest,
    safety_policy_digest,
)


_SCHEMA_VERSION = 1
_MAX_FILE_BYTES = 512 * 1024
_MAX_REVOKED_APPROVALS = 8192
_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_DOMAIN = b"JARVIS:SAFETY-POLICY-REGISTRY:V1\x00"


class SafetyPolicyRegistryError(ValueError):
    """Base error for policy-registry storage and validation failures."""


class SafetyPolicyRegistryNotFound(SafetyPolicyRegistryError):
    """No policy registry has been initialized."""


class SafetyPolicyRegistryConflict(SafetyPolicyRegistryError):
    """The registry record version changed since the caller's snapshot."""


class SafetyPolicyRegistryCorrupt(SafetyPolicyRegistryError):
    """The persisted policy registry failed schema or digest validation."""


@dataclass(frozen=True)
class SafetyPolicyRegistrySnapshot:
    record_version: int
    policy: SafetyPolicy
    revoked_approval_ids: tuple[str, ...]
    state_digest: str


def _canonical_json(value: object) -> bytes:
    try:
        return json.dumps(
            value, sort_keys=True, ensure_ascii=True,
            separators=(",", ":"), allow_nan=False,
        ).encode("ascii")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise SafetyPolicyRegistryError("registry value cannot be canonicalized") from exc


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise SafetyPolicyRegistryCorrupt(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _policy_to_dict(policy: SafetyPolicy) -> dict[str, object]:
    if not isinstance(policy, SafetyPolicy):
        raise SafetyPolicyRegistryError("policy must be a SafetyPolicy")
    return {
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


def _policy_from_dict(value: object) -> SafetyPolicy:
    if not isinstance(value, dict) or set(value) != {
        "policy_id", "version", "enabled", "rules"
    }:
        raise SafetyPolicyRegistryCorrupt("policy object has unexpected fields")
    raw_rules = value["rules"]
    if not isinstance(raw_rules, list) or len(raw_rules) > 256:
        raise SafetyPolicyRegistryCorrupt("policy rules must be a list of at most 256 entries")
    rules: list[CapabilityRule] = []
    expected_rule_fields = {
        "capability", "allowed_scopes", "allowed_effects", "requires_approval",
        "max_compute_units", "max_memory_bytes", "max_timeout_ms",
        "max_output_bytes", "max_retries",
    }
    try:
        for raw in raw_rules:
            if not isinstance(raw, dict) or set(raw) != expected_rule_fields:
                raise SafetyPolicyRegistryCorrupt("capability rule has unexpected fields")
            if not isinstance(raw["allowed_scopes"], list):
                raise SafetyPolicyRegistryCorrupt("allowed_scopes must be a list")
            if not isinstance(raw["allowed_effects"], list):
                raise SafetyPolicyRegistryCorrupt("allowed_effects must be a list")
            rules.append(CapabilityRule(
                capability=raw["capability"],
                allowed_scopes=tuple(raw["allowed_scopes"]),
                allowed_effects=tuple(EffectClass(item) for item in raw["allowed_effects"]),
                requires_approval=raw["requires_approval"],
                max_compute_units=raw["max_compute_units"],
                max_memory_bytes=raw["max_memory_bytes"],
                max_timeout_ms=raw["max_timeout_ms"],
                max_output_bytes=raw["max_output_bytes"],
                max_retries=raw["max_retries"],
            ))
        return SafetyPolicy(
            policy_id=value["policy_id"],
            version=value["version"],
            rules=tuple(rules),
            enabled=value["enabled"],
        )
    except (PermissionSafetyError, TypeError, ValueError) as exc:
        if isinstance(exc, SafetyPolicyRegistryCorrupt):
            raise
        raise SafetyPolicyRegistryCorrupt("persisted policy violates the Gate V1 schema") from exc


class PersistentSafetyPolicyRegistry:
    """Single-file CAS registry with process locks, atomic replacement and digest checks.

    Place this file in a private app directory. The implementation targets the
    POSIX file semantics used by Linux/Android Termux; it is not a network store.
    """

    def __init__(self, path: str | os.PathLike[str]) -> None:
        self.path = Path(path)
        self.lock_path = self.path.with_name(self.path.name + ".lock")
        try:
            self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            os.chmod(self.path.parent, 0o700)
        except OSError as exc:
            raise SafetyPolicyRegistryError("cannot secure policy-registry directory") from exc

    @contextmanager
    def _locked(self, *, exclusive: bool) -> Iterator[None]:
        flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(self.lock_path, flags, 0o600)
        except OSError as exc:
            raise SafetyPolicyRegistryError("cannot open policy-registry lock") from exc
        try:
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode):
                raise SafetyPolicyRegistryError("policy-registry lock is not a regular file")
            if hasattr(os, "geteuid") and metadata.st_uid != os.geteuid():
                raise SafetyPolicyRegistryError("policy-registry lock has an unexpected owner")
            os.fchmod(descriptor, 0o600)
            fcntl.flock(descriptor, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
            yield
        except OSError as exc:
            raise SafetyPolicyRegistryError("policy-registry lock operation failed") from exc
        finally:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)

    def _read_optional_locked(self) -> SafetyPolicyRegistrySnapshot | None:
        try:
            metadata = self.path.lstat()
        except FileNotFoundError:
            return None
        except OSError as exc:
            raise SafetyPolicyRegistryError("cannot inspect policy-registry file") from exc
        if not stat.S_ISREG(metadata.st_mode):
            raise SafetyPolicyRegistryCorrupt("policy-registry path must be a regular file")
        if hasattr(os, "geteuid") and metadata.st_uid != os.geteuid():
            raise SafetyPolicyRegistryCorrupt("policy-registry file has an unexpected owner")
        try:
            descriptor = os.open(self.path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(descriptor, "rb") as stream:
                raw = stream.read(_MAX_FILE_BYTES + 1)
        except OSError as exc:
            raise SafetyPolicyRegistryError("cannot read policy-registry file") from exc
        if len(raw) > _MAX_FILE_BYTES:
            raise SafetyPolicyRegistryCorrupt("policy-registry file exceeds its size limit")
        if metadata.st_mode & 0o077:
            try:
                os.chmod(self.path, 0o600)
            except OSError as exc:
                raise SafetyPolicyRegistryError("cannot restrict policy-registry file permissions") from exc
        try:
            envelope = json.loads(
                raw.decode("ascii"),
                object_pairs_hook=_unique_object,
                parse_constant=lambda item: (_ for _ in ()).throw(
                    SafetyPolicyRegistryCorrupt(f"invalid JSON constant: {item}")
                ),
            )
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise SafetyPolicyRegistryCorrupt("policy-registry JSON is invalid") from exc
        expected_fields = {
            "schema_version", "record_version", "policy", "policy_digest",
            "revoked_approval_ids", "state_digest",
        }
        if not isinstance(envelope, dict) or set(envelope) != expected_fields:
            raise SafetyPolicyRegistryCorrupt("policy-registry envelope has unexpected fields")
        if envelope["schema_version"] != _SCHEMA_VERSION:
            raise SafetyPolicyRegistryCorrupt("unsupported policy-registry schema")
        record_version = envelope["record_version"]
        if isinstance(record_version, bool) or not isinstance(record_version, int) or record_version < 1:
            raise SafetyPolicyRegistryCorrupt("record_version must be a positive integer")

        policy = _policy_from_dict(envelope["policy"])
        policy_digest = envelope["policy_digest"]
        if not isinstance(policy_digest, str) or not _DIGEST_PATTERN.fullmatch(policy_digest):
            raise SafetyPolicyRegistryCorrupt("policy_digest has an invalid format")
        if safety_policy_digest(policy) != policy_digest:
            raise SafetyPolicyRegistryCorrupt("policy digest does not match the stored policy")

        revoked = envelope["revoked_approval_ids"]
        if not isinstance(revoked, list) or len(revoked) > _MAX_REVOKED_APPROVALS:
            raise SafetyPolicyRegistryCorrupt("revocation list exceeds its bound")
        if any(not isinstance(item, str) or not _ID_PATTERN.fullmatch(item) for item in revoked):
            raise SafetyPolicyRegistryCorrupt("revocation list contains an invalid approval ID")
        if revoked != sorted(set(revoked)):
            raise SafetyPolicyRegistryCorrupt("revocation list must be sorted and unique")

        state_digest = envelope["state_digest"]
        if not isinstance(state_digest, str) or not _DIGEST_PATTERN.fullmatch(state_digest):
            raise SafetyPolicyRegistryCorrupt("state_digest has an invalid format")
        payload = {key: value for key, value in envelope.items() if key != "state_digest"}
        expected_digest = hashlib.sha256(_DOMAIN + _canonical_json(payload)).hexdigest()
        if not secrets.compare_digest(state_digest, expected_digest):
            raise SafetyPolicyRegistryCorrupt("policy-registry state digest mismatch")

        return SafetyPolicyRegistrySnapshot(
            record_version=record_version,
            policy=policy,
            revoked_approval_ids=tuple(revoked),
            state_digest=state_digest,
        )

    def _read_locked(self) -> SafetyPolicyRegistrySnapshot:
        snapshot = self._read_optional_locked()
        if snapshot is None:
            raise SafetyPolicyRegistryNotFound("policy registry has not been initialized")
        return snapshot

    def _write_locked(self, snapshot: SafetyPolicyRegistrySnapshot) -> None:
        policy_dict = _policy_to_dict(snapshot.policy)
        payload: dict[str, object] = {
            "schema_version": _SCHEMA_VERSION,
            "record_version": snapshot.record_version,
            "policy": policy_dict,
            "policy_digest": safety_policy_digest(snapshot.policy),
            "revoked_approval_ids": list(snapshot.revoked_approval_ids),
        }
        state_digest = hashlib.sha256(_DOMAIN + _canonical_json(payload)).hexdigest()
        envelope = dict(payload)
        envelope["state_digest"] = state_digest
        raw = _canonical_json(envelope)
        if len(raw) > _MAX_FILE_BYTES:
            raise SafetyPolicyRegistryError("policy-registry snapshot exceeds its size limit")

        temporary = self.path.with_name(self.path.name + "." + secrets.token_hex(8) + ".tmp")
        descriptor: int | None = None
        try:
            descriptor = os.open(
                temporary,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
                0o600,
            )
            with os.fdopen(descriptor, "wb") as stream:
                descriptor = None
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            os.chmod(self.path, 0o600)
            directory_fd = os.open(self.path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError as exc:
            if descriptor is not None:
                os.close(descriptor)
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
            raise SafetyPolicyRegistryError("cannot atomically publish policy-registry snapshot") from exc

    @staticmethod
    def _expected_version(value: object) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise SafetyPolicyRegistryError("expected_record_version must be an unsigned integer")
        return value

    def load(self) -> SafetyPolicyRegistrySnapshot:
        with self._locked(exclusive=False):
            return self._read_locked()

    def save_policy(
        self,
        policy: SafetyPolicy,
        *,
        expected_record_version: int,
    ) -> SafetyPolicyRegistrySnapshot:
        if not isinstance(policy, SafetyPolicy):
            raise SafetyPolicyRegistryError("policy must be a SafetyPolicy")
        expected = self._expected_version(expected_record_version)
        policy_digest = safety_policy_digest(policy)
        with self._locked(exclusive=True):
            current = self._read_optional_locked()
            actual = 0 if current is None else current.record_version
            if actual != expected:
                raise SafetyPolicyRegistryConflict(
                    f"expected policy record version {expected}, current version is {actual}"
                )
            if current is not None:
                current_digest = safety_policy_digest(current.policy)
                if policy_digest == current_digest:
                    return current
                if policy.version <= current.policy.version:
                    raise SafetyPolicyRegistryError(
                        "a changed policy must strictly increase its policy version"
                    )
                revoked = current.revoked_approval_ids
            else:
                revoked = ()
            snapshot = SafetyPolicyRegistrySnapshot(
                record_version=actual + 1,
                policy=policy,
                revoked_approval_ids=revoked,
                state_digest="0" * 64,
            )
            self._write_locked(snapshot)
            return self._read_locked()

    def revoke_approval(
        self,
        approval_id: str,
        *,
        expected_record_version: int,
    ) -> SafetyPolicyRegistrySnapshot:
        if not isinstance(approval_id, str) or not _ID_PATTERN.fullmatch(approval_id):
            raise SafetyPolicyRegistryError("approval_id must be a stable identifier")
        expected = self._expected_version(expected_record_version)
        with self._locked(exclusive=True):
            current = self._read_locked()
            if current.record_version != expected:
                raise SafetyPolicyRegistryConflict(
                    f"expected policy record version {expected}, current version is {current.record_version}"
                )
            if approval_id in current.revoked_approval_ids:
                return current
            if len(current.revoked_approval_ids) >= _MAX_REVOKED_APPROVALS:
                raise SafetyPolicyRegistryError("approval revocation capacity is exhausted")
            updated = SafetyPolicyRegistrySnapshot(
                record_version=current.record_version + 1,
                policy=current.policy,
                revoked_approval_ids=tuple(sorted((*current.revoked_approval_ids, approval_id))),
                state_digest="0" * 64,
            )
            self._write_locked(updated)
            return self._read_locked()


class PersistentPolicySafetyGate(PermissionSafetyGate):
    """Gate V1 wrapper that requires the supplied policy to match durable state.

    Revoked approval IDs are rejected before the base Gate. Grant signatures and
    approver identity are intentionally not claimed by this wrapper.
    """

    def __init__(self, registry: PersistentSafetyPolicyRegistry) -> None:
        if not isinstance(registry, PersistentSafetyPolicyRegistry):
            raise SafetyPolicyRegistryError("registry must be a PersistentSafetyPolicyRegistry")
        super().__init__()
        self.registry = registry

    def authorize(
        self,
        task_manager,
        policy: SafetyPolicy,
        request: AuthorizationRequest,
        *,
        now_ns: int,
        approval: ApprovalGrant | None = None,
    ) -> AuthorizationDecision:
        if not isinstance(policy, SafetyPolicy) or not isinstance(request, AuthorizationRequest):
            return super().authorize(task_manager, policy, request, now_ns=now_ns, approval=approval)

        current_policy_digest = safety_policy_digest(policy)
        intent_digest = authorization_intent_digest(request)

        def deny(reason: str) -> AuthorizationDecision:
            return AuthorizationDecision(
                status=AuthorizationStatus.DENIED,
                reason_code=reason,
                policy_id=policy.policy_id,
                policy_version=policy.version,
                policy_digest=current_policy_digest,
                task_id=request.task_id,
                task_revision=request.expected_task_revision,
                intent_digest=intent_digest,
                approval_id=None,
            )

        try:
            snapshot = self.registry.load()
        except SafetyPolicyRegistryNotFound:
            return deny("policy_registry_missing")
        except SafetyPolicyRegistryError:
            return deny("policy_registry_unavailable")

        if safety_policy_digest(snapshot.policy) != current_policy_digest:
            return deny("policy_registry_mismatch")
        if isinstance(approval, ApprovalGrant) and approval.approval_id in snapshot.revoked_approval_ids:
            return deny("approval_revoked")
        return super().authorize(
            task_manager, policy, request, now_ns=now_ns, approval=approval
        )
