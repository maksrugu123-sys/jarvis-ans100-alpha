"""Bounded, ordered owner for R-STDP reward baselines.

This module coordinates feedback preparation and baseline advancement. It does
not apply synaptic weight updates; callers commit a proposal only after the
corresponding synapse work has reached a terminal success/no-op outcome.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
import hashlib
import json
import math
import uuid
from typing import Literal

_U64_MAX = (1 << 64) - 1
_COMPLETED_STATUSES = frozenset({
    "APPLIED", "APPLIED_NO_CHANGE", "NO_ELIGIBILITY", "EXPIRED",
    "DISABLED_NO_OP",
})


class RewardBaselineError(ValueError):
    """Invalid reward-baseline input or state transition."""


class RewardBaselineConflict(RewardBaselineError):
    """Feedback identity, sequence or optimistic version conflicts."""


class RewardBaselineDeferred(RewardBaselineError):
    """A feedback sequence has a gap and must wait for its predecessor."""

    def __init__(self, expected_sequence: int, received_sequence: int):
        self.expected_sequence = expected_sequence
        self.received_sequence = received_sequence
        super().__init__(
            f"feedback sequence gap: expected {expected_sequence}, received {received_sequence}"
        )


def _identifier(name: str, value: object) -> str:
    if not isinstance(value, str) or not value or value != value.strip():
        raise RewardBaselineError(f"{name} must be a non-empty trimmed string")
    try:
        encoded = value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise RewardBaselineError(f"{name} must be valid UTF-8") from exc
    if len(encoded) > 512 or any(ord(ch) < 32 for ch in value):
        raise RewardBaselineError(f"{name} exceeds the identifier contract")
    return value


def _u64(name: str, value: object, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= _U64_MAX:
        raise RewardBaselineError(f"{name} must be an unsigned 64-bit integer")
    return value


def _unit(name: str, value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RewardBaselineError(f"{name} must be a finite number in [0, 1]")
    result = float(value)
    if not math.isfinite(result) or not 0.0 <= result <= 1.0:
        raise RewardBaselineError(f"{name} must be a finite number in [0, 1]")
    return result


def _epoch(value: object) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)) or len(value) != 16:
        raise RewardBaselineError("clock_epoch_id must contain exactly 16 bytes")
    result = bytes(value)
    if result == bytes(16):
        raise RewardBaselineError("clock_epoch_id must be non-zero")
    return result


def _canonical_envelope(text: object) -> dict:
    if not isinstance(text, str):
        raise RewardBaselineError("cqe_envelope_json must be a canonical JSON string")
    try:
        if not text or len(text.encode("utf-8", errors="strict")) > 8 * 1024:
            raise RewardBaselineError("CQE envelope exceeds the 8 KiB bound")
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise RewardBaselineError("CQE envelope contains duplicate JSON keys")
                result[key] = value
            return result
        def reject(value):
            raise RewardBaselineError("CQE envelope contains a non-finite JSON constant")
        document = json.loads(text, object_pairs_hook=unique, parse_constant=reject)
        canonical = json.dumps(document, ensure_ascii=False, allow_nan=False,
                               sort_keys=True, separators=(",", ":"))
    except RewardBaselineError:
        raise
    except (UnicodeError, ValueError, TypeError) as exc:
        raise RewardBaselineError("CQE envelope is invalid JSON") from exc
    if not isinstance(document, dict) or canonical != text:
        raise RewardBaselineError("CQE envelope is not a canonical JSON object")
    return document


def _cqe_metadata(
    envelope_json: object,
    context_digest: object,
) -> tuple[str | None, str | None]:
    if envelope_json is None and context_digest is None:
        return None, None
    if envelope_json is None or context_digest is None:
        raise RewardBaselineError("CQE envelope and context digest must be supplied together")
    document = _canonical_envelope(envelope_json)
    if (not isinstance(context_digest, str) or len(context_digest) != 64
            or any(char not in "0123456789abcdef" for char in context_digest)):
        raise RewardBaselineError("feedback_context_digest must be 64 lowercase hexadecimal characters")
    required = {
        "protocol_version", "message_id", "event_id", "message_type",
        "schema_id", "schema_version", "source_module_id", "target_module_id",
        "commit_id", "operation_id", "correlation_id", "causation_id",
        "idempotency_key", "expected_state_version", "deadline_budget_ms",
        "requested_priority", "capability_id", "state_version", "payload",
        "policy_version", "authorization_receipt_id",
    }
    if set(document) != required:
        raise RewardBaselineError("CQE envelope fields do not match the durable feedback schema")
    def canonical_uuid(name: str, value: object) -> None:
        if not isinstance(value, str):
            raise RewardBaselineError(f"{name} must be a canonical UUID")
        try:
            canonical = str(uuid.UUID(value))
        except (ValueError, AttributeError, TypeError) as exc:
            raise RewardBaselineError(f"{name} must be a canonical UUID") from exc
        if canonical != value:
            raise RewardBaselineError(f"{name} must be a lowercase canonical UUID")
    protocol_version = _u64("protocol_version", document["protocol_version"], minimum=1)
    schema_version = _u64("schema_version", document["schema_version"], minimum=1)
    if (
        protocol_version != 1
        or document["message_type"] != "EVENT"
        or document["schema_id"] != "jarvis.reward.feedback"
        or schema_version != 1
    ):
        raise RewardBaselineError("unsupported CQE reward-feedback protocol or schema")
    canonical_uuid("message_id", document["message_id"])
    canonical_uuid("event_id", document["event_id"])
    canonical_uuid("authorization_receipt_id", document["authorization_receipt_id"])
    _identifier("source_module_id", document["source_module_id"])
    _u64("policy_version", document["policy_version"], minimum=1)
    if document["operation_id"] is not None:
        raise RewardBaselineError("reward-feedback EVENT must not carry operation_id")
    if document["commit_id"] is None:
        if document["state_version"] is not None:
            raise RewardBaselineError("state_version requires commit_id")
    else:
        canonical_uuid("commit_id", document["commit_id"])
        if document["state_version"] is None:
            raise RewardBaselineError("commit_id requires state_version")
    for field_name in (
        "target_module_id", "correlation_id", "causation_id",
        "idempotency_key", "capability_id",
    ):
        optional_value = document[field_name]
        if optional_value is not None:
            _identifier(field_name, optional_value)
    for field_name in ("expected_state_version", "state_version"):
        optional_value = document[field_name]
        if optional_value is not None:
            _u64(field_name, optional_value)
    deadline = document["deadline_budget_ms"]
    if deadline is not None:
        _u64("deadline_budget_ms", deadline, minimum=1)
        if deadline > 60_000:
            raise RewardBaselineError("deadline_budget_ms exceeds CQE v1 maximum")
    priority = document["requested_priority"]
    if priority is not None and priority not in {"P0", "P1", "P2", "P3", "P4"}:
        raise RewardBaselineError("requested_priority is invalid")
    payload = document["payload"]
    if not isinstance(payload, dict) or set(payload) != {
        "reward_task_id", "feedback_id", "feedback_sequence", "reward_value",
        "model_time_ns", "clock_epoch_id", "learning_context_id", "targets",
    }:
        raise RewardBaselineError("CQE reward-feedback payload fields are invalid")
    _identifier("reward_task_id", payload["reward_task_id"])
    _identifier("feedback_id", payload["feedback_id"])
    _u64("feedback_sequence", payload["feedback_sequence"], minimum=1)
    _unit("reward_value", payload["reward_value"])
    _u64("model_time_ns", payload["model_time_ns"])
    epoch_text = payload["clock_epoch_id"]
    if not isinstance(epoch_text, str) or len(epoch_text) != 32:
        raise RewardBaselineError("CQE feedback clock epoch is invalid")
    try:
        epoch = bytes.fromhex(epoch_text)
    except ValueError as exc:
        raise RewardBaselineError("CQE feedback clock epoch is not hexadecimal") from exc
    if epoch.hex() != epoch_text or epoch == bytes(16):
        raise RewardBaselineError("CQE feedback clock epoch is not canonical or is zero")
    context_id = payload["learning_context_id"]
    if context_id is not None:
        _identifier("learning_context_id", context_id)
    targets = payload["targets"]
    if not isinstance(targets, list) or not 1 <= len(targets) <= 128:
        raise RewardBaselineError("CQE feedback targets are empty or exceed 128 entries")
    normalized_targets = []
    for item in targets:
        if not isinstance(item, dict) or set(item) != {"neuron_id", "synapse_id"}:
            raise RewardBaselineError("CQE feedback target fields are invalid")
        neuron_id = _identifier("target neuron_id", item["neuron_id"])
        synapse_id = _identifier("target synapse_id", item["synapse_id"])
        if len(neuron_id.encode("utf-8")) > 128 or len(synapse_id.encode("utf-8")) > 128:
            raise RewardBaselineError("CQE feedback target ID exceeds 128 UTF-8 bytes")
        normalized_targets.append({
            "neuron_id": neuron_id,
            "synapse_id": synapse_id,
        })
    target_keys = [
        (item["neuron_id"], item["synapse_id"])
        for item in normalized_targets
    ]
    if (
        len(target_keys) != len(set(target_keys))
        or normalized_targets != sorted(
            normalized_targets,
            key=lambda item: (item["neuron_id"], item["synapse_id"]),
        )
    ):
        raise RewardBaselineError("CQE feedback targets are duplicated or not in canonical order")
    if len(json.dumps(
        payload, ensure_ascii=False, allow_nan=False,
        sort_keys=True, separators=(",", ":"),
    ).encode("utf-8", errors="strict")) > 4 * 1024:
        raise RewardBaselineError("CQE feedback payload exceeds the 4 KiB inline bound")
    context = {"learning_context_id": context_id, "targets": normalized_targets}
    encoded_context = json.dumps(
        context, ensure_ascii=False, allow_nan=False, sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8", errors="strict")
    if hashlib.sha256(encoded_context).hexdigest() != context_digest:
        raise RewardBaselineError("CQE target/context digest does not match its envelope")
    return envelope_json, context_digest


def _validate_cqe_binding(
    envelope_json: str | None,
    *,
    reward_task_id: str,
    feedback_id: str,
    feedback_sequence: int,
    reward_value: float,
    model_time_ns: int,
    clock_epoch_id: bytes,
) -> None:
    if envelope_json is None:
        return
    payload = _canonical_envelope(envelope_json)["payload"]
    if (payload["reward_task_id"] != reward_task_id
            or payload["feedback_id"] != feedback_id
            or payload["feedback_sequence"] != feedback_sequence
            or payload["reward_value"] != reward_value
            or payload["model_time_ns"] != model_time_ns
            or payload["clock_epoch_id"] != clock_epoch_id.hex()):
        raise RewardBaselineError("CQE envelope payload differs from its baseline feedback record")


@dataclass(frozen=True)
class RewardBaselineReceipt:
    reward_task_id: str
    feedback_id: str
    feedback_sequence: int
    reward_value: float
    model_time_ns: int
    baseline_version: int
    baseline_before: float
    baseline_after: float
    modulator: float
    processing_status: str
    cqe_envelope_json: str | None = None
    feedback_context_digest: str | None = None


@dataclass(frozen=True)
class RewardBaselineProposal:
    manager_id: str
    reward_task_id: str
    feedback_id: str
    feedback_sequence: int
    reward_value: float
    model_time_ns: int
    clock_epoch_id: bytes
    baseline_version: int
    baseline_before: float
    baseline_after: float
    modulator: float
    status: Literal["PREPARED", "DUPLICATE"]
    duplicate_processing_status: str | None = None
    cqe_envelope_json: str | None = None
    feedback_context_digest: str | None = None


@dataclass(frozen=True)
class RewardBaselineRecord:
    reward_task_id: str
    expected_reward: float
    baseline_version: int = 0
    last_committed_feedback_sequence: int = 0
    last_committed_feedback_id: str | None = None
    last_model_time_ns: int = 0
    pending: RewardBaselineProposal | None = None
    history: tuple[RewardBaselineReceipt, ...] = ()


class RewardBaselineManager:
    """Single-writer bounded manager with per-task ordered feedback sequences.

    A task's baseline advances only through commit_feedback(), after its caller
    confirms that all relevant synapse work completed or produced an approved
    no-op. A prepared proposal is retained across save/restart until committed.
    """

    def __init__(
        self,
        *,
        manager_id: str = "jarvis-rstdp-baselines",
        clock_epoch_id: bytes,
        initial_baseline: float = 0.5,
        beta_b: float = 0.01,
        max_tasks: int = 1024,
        history_limit: int = 16,
    ) -> None:
        self.manager_id = _identifier("manager_id", manager_id)
        self.clock_epoch_id = _epoch(clock_epoch_id)
        self.initial_baseline = _unit("initial_baseline", initial_baseline)
        self.beta_b = _unit("beta_b", beta_b)
        self.max_tasks = _u64("max_tasks", max_tasks, minimum=1)
        self.history_limit = _u64("history_limit", history_limit, minimum=1)
        if self.max_tasks > 100_000 or self.history_limit > 256:
            raise RewardBaselineError("manager bounds exceed the reference implementation limits")
        self._records: dict[str, RewardBaselineRecord] = {}

    @property
    def task_count(self) -> int:
        return len(self._records)

    def get_record(self, reward_task_id: str) -> RewardBaselineRecord:
        task_id = _identifier("reward_task_id", reward_task_id)
        return self._records.get(
            task_id, RewardBaselineRecord(task_id, self.initial_baseline)
        )

    def get_baseline(self, reward_task_id: str) -> float:
        return self.get_record(reward_task_id).expected_reward

    @staticmethod
    def _proposal_matches(
        proposal: RewardBaselineProposal,
        *,
        feedback_id: str,
        sequence: int,
        reward_value: float,
        model_time_ns: int,
        cqe_envelope_json: str | None,
        feedback_context_digest: str | None,
    ) -> bool:
        return (
            proposal.feedback_id == feedback_id
            and proposal.feedback_sequence == sequence
            and proposal.reward_value == reward_value
            and proposal.model_time_ns == model_time_ns
            and proposal.cqe_envelope_json == cqe_envelope_json
            and proposal.feedback_context_digest == feedback_context_digest
        )

    def prepare_feedback(
        self,
        reward_task_id: str,
        feedback_id: str,
        feedback_sequence: int,
        reward_value: float,
        model_time_ns: int,
        *,
        clock_epoch_id: bytes,
        cqe_envelope_json: str | None = None,
        feedback_context_digest: str | None = None,
    ) -> RewardBaselineProposal:
        task_id = _identifier("reward_task_id", reward_task_id)
        feedback = _identifier("feedback_id", feedback_id)
        sequence = _u64("feedback_sequence", feedback_sequence, minimum=1)
        reward = _unit("reward_value", reward_value)
        timestamp = _u64("model_time_ns", model_time_ns)
        epoch = _epoch(clock_epoch_id)
        cqe_envelope_json, feedback_context_digest = _cqe_metadata(
            cqe_envelope_json, feedback_context_digest
        )
        if epoch != self.clock_epoch_id:
            raise RewardBaselineConflict("feedback clock epoch differs from the manager epoch")
        try:
            _validate_cqe_binding(
                cqe_envelope_json, reward_task_id=task_id,
                feedback_id=feedback, feedback_sequence=sequence,
                reward_value=reward, model_time_ns=timestamp,
                clock_epoch_id=epoch,
            )
        except RewardBaselineError as exc:
            raise RewardBaselineConflict(str(exc)) from exc

        current = self.get_record(task_id)
        for receipt in current.history:
            if receipt.feedback_id == feedback and receipt.feedback_sequence != sequence:
                raise RewardBaselineConflict(
                    "feedback_id was already committed at a different sequence"
                )
            if receipt.feedback_sequence == sequence:
                if (
                    receipt.feedback_id != feedback
                    or receipt.reward_value != reward
                    or receipt.model_time_ns != timestamp
                    or receipt.cqe_envelope_json != cqe_envelope_json
                    or receipt.feedback_context_digest != feedback_context_digest
                ):
                    raise RewardBaselineConflict(
                        "feedback sequence was already committed with different content"
                    )
                return RewardBaselineProposal(
                    self.manager_id, task_id, feedback, sequence, reward, timestamp, epoch,
                    receipt.baseline_version - 1, receipt.baseline_before,
                    receipt.baseline_after, receipt.modulator, "DUPLICATE",
                    receipt.processing_status, receipt.cqe_envelope_json,
                    receipt.feedback_context_digest,
                )

        if current.pending is not None:
            pending = current.pending
            if self._proposal_matches(
                pending, feedback_id=feedback, sequence=sequence,
                reward_value=reward, model_time_ns=timestamp,
                cqe_envelope_json=cqe_envelope_json,
                feedback_context_digest=feedback_context_digest,
            ):
                return pending
            raise RewardBaselineConflict(
                "another feedback is pending for this reward_task_id"
            )

        expected = current.last_committed_feedback_sequence + 1
        if sequence > expected:
            raise RewardBaselineDeferred(expected, sequence)
        if sequence < expected:
            raise RewardBaselineConflict(
                "feedback sequence is older than the retained idempotency history"
            )
        if timestamp < current.last_model_time_ns:
            raise RewardBaselineConflict("feedback model time moved backwards within one clock epoch")
        if task_id not in self._records and len(self._records) >= self.max_tasks:
            raise RewardBaselineError("reward task catalog is at its configured capacity")
        if current.baseline_version >= _U64_MAX:
            raise RewardBaselineError("baseline_version is exhausted")

        baseline_before = current.expected_reward
        modulator = max(-1.0, min(1.0, reward - baseline_before))
        baseline_after = max(
            0.0, min(1.0, baseline_before + self.beta_b * (reward - baseline_before))
        )
        proposal = RewardBaselineProposal(
            self.manager_id, task_id, feedback, sequence, reward, timestamp, epoch,
            current.baseline_version, baseline_before, baseline_after,
            modulator, "PREPARED", None,
            cqe_envelope_json, feedback_context_digest,
        )
        self._records[task_id] = replace(current, pending=proposal)
        return proposal

    def commit_feedback(
        self,
        proposal: RewardBaselineProposal,
        *,
        processing_status: str,
    ) -> RewardBaselineReceipt:
        if not isinstance(proposal, RewardBaselineProposal):
            raise RewardBaselineError("proposal must be a RewardBaselineProposal")
        if proposal.manager_id != self.manager_id:
            raise RewardBaselineConflict("feedback proposal belongs to a different manager")
        if not isinstance(processing_status, str) or processing_status not in _COMPLETED_STATUSES:
            raise RewardBaselineError(
                "baseline can advance only after a terminal success or approved no-op status"
            )
        if proposal.status == "DUPLICATE":
            record = self.get_record(proposal.reward_task_id)
            for receipt in record.history:
                if (
                    receipt.feedback_sequence == proposal.feedback_sequence
                    and receipt.feedback_id == proposal.feedback_id
                ):
                    return receipt
            raise RewardBaselineConflict("duplicate proposal is no longer in retained history")
        if proposal.status != "PREPARED":
            raise RewardBaselineError("unsupported proposal status")
        current = self.get_record(proposal.reward_task_id)
        if current.pending != proposal:
            raise RewardBaselineConflict("prepared proposal is not the active task reservation")
        if (
            proposal.clock_epoch_id != self.clock_epoch_id
            or proposal.baseline_version != current.baseline_version
            or proposal.baseline_before != current.expected_reward
            or proposal.feedback_sequence != current.last_committed_feedback_sequence + 1
        ):
            raise RewardBaselineConflict("prepared proposal version or sequence is stale")
        next_version = current.baseline_version + 1
        receipt = RewardBaselineReceipt(
            proposal.reward_task_id, proposal.feedback_id,
            proposal.feedback_sequence, proposal.reward_value,
            proposal.model_time_ns, next_version,
            proposal.baseline_before, proposal.baseline_after,
            proposal.modulator, processing_status,
            proposal.cqe_envelope_json, proposal.feedback_context_digest,
        )
        history = (current.history + (receipt,))[-self.history_limit:]
        self._records[proposal.reward_task_id] = replace(
            current,
            expected_reward=proposal.baseline_after,
            baseline_version=next_version,
            last_committed_feedback_sequence=proposal.feedback_sequence,
            last_committed_feedback_id=proposal.feedback_id,
            last_model_time_ns=proposal.model_time_ns,
            pending=None,
            history=history,
        )
        return receipt

    def export_checkpoint_state(self) -> dict[str, object]:
        """Return a detached, deterministic state tree for the persistence adapter."""
        def proposal_doc(value: RewardBaselineProposal | None):
            if value is None:
                return None
            return {
                "manager_id": value.manager_id,
                "reward_task_id": value.reward_task_id,
                "feedback_id": value.feedback_id,
                "feedback_sequence": value.feedback_sequence,
                "reward_value": value.reward_value,
                "model_time_ns": value.model_time_ns,
                "clock_epoch_id": value.clock_epoch_id.hex(),
                "baseline_version": value.baseline_version,
                "baseline_before": value.baseline_before,
                "baseline_after": value.baseline_after,
                "modulator": value.modulator,
                "status": value.status,
                "duplicate_processing_status": value.duplicate_processing_status,
                "cqe_envelope_json": value.cqe_envelope_json,
                "feedback_context_digest": value.feedback_context_digest,
            }

        records = []
        for task_id in sorted(self._records):
            item = self._records[task_id]
            records.append({
                "reward_task_id": item.reward_task_id,
                "expected_reward": item.expected_reward,
                "baseline_version": item.baseline_version,
                "last_committed_feedback_sequence": item.last_committed_feedback_sequence,
                "last_committed_feedback_id": item.last_committed_feedback_id,
                "last_model_time_ns": item.last_model_time_ns,
                "pending": proposal_doc(item.pending),
                "history": [
                    {
                        "reward_task_id": row.reward_task_id,
                        "feedback_id": row.feedback_id,
                        "feedback_sequence": row.feedback_sequence,
                        "reward_value": row.reward_value,
                        "model_time_ns": row.model_time_ns,
                        "baseline_version": row.baseline_version,
                        "baseline_before": row.baseline_before,
                        "baseline_after": row.baseline_after,
                        "modulator": row.modulator,
                        "processing_status": row.processing_status,
                        "cqe_envelope_json": row.cqe_envelope_json,
                        "feedback_context_digest": row.feedback_context_digest,
                    }
                    for row in item.history
                ],
            })
        return {
            "manager_id": self.manager_id,
            "clock_epoch_id": self.clock_epoch_id.hex(),
            "initial_baseline": self.initial_baseline,
            "beta_b": self.beta_b,
            "max_tasks": self.max_tasks,
            "history_limit": self.history_limit,
            "records": records,
        }

    @classmethod
    def from_checkpoint_state(cls, state: object) -> "RewardBaselineManager":
        """Strictly restore an exported state tree; rejects unknown/malformed data."""
        def exact(name: str, value: object, keys: set[str]) -> dict:
            if not isinstance(value, dict) or set(value) != keys:
                raise RewardBaselineError(f"{name} fields do not match the checkpoint schema")
            return value

        def parse_epoch(text: object) -> bytes:
            if not isinstance(text, str) or len(text) != 32:
                raise RewardBaselineError("checkpoint clock epoch must be 32 lowercase hex characters")
            try:
                value = bytes.fromhex(text)
            except ValueError as exc:
                raise RewardBaselineError("checkpoint clock epoch is not hexadecimal") from exc
            if value.hex() != text:
                raise RewardBaselineError("checkpoint clock epoch is not canonical")
            return _epoch(value)

        top = exact("manager state", state, {
            "manager_id", "clock_epoch_id", "initial_baseline", "beta_b",
            "max_tasks", "history_limit", "records",
        })
        manager = cls(
            manager_id=top["manager_id"],
            clock_epoch_id=parse_epoch(top["clock_epoch_id"]),
            initial_baseline=top["initial_baseline"],
            beta_b=top["beta_b"],
            max_tasks=top["max_tasks"],
            history_limit=top["history_limit"],
        )
        raw_records = top["records"]
        if not isinstance(raw_records, list) or len(raw_records) > manager.max_tasks:
            raise RewardBaselineError("checkpoint records exceed the configured task bound")
        for raw in raw_records:
            row = exact("reward task record", raw, {
                "reward_task_id", "expected_reward", "baseline_version",
                "last_committed_feedback_sequence", "last_committed_feedback_id",
                "last_model_time_ns", "pending", "history",
            })
            task_id = _identifier("reward_task_id", row["reward_task_id"])
            if task_id in manager._records:
                raise RewardBaselineError("checkpoint contains duplicate reward_task_id values")
            baseline = _unit("expected_reward", row["expected_reward"])
            version = _u64("baseline_version", row["baseline_version"])
            last_sequence = _u64(
                "last_committed_feedback_sequence", row["last_committed_feedback_sequence"]
            )
            last_id = row["last_committed_feedback_id"]
            if last_id is not None:
                last_id = _identifier("last_committed_feedback_id", last_id)
            last_time = _u64("last_model_time_ns", row["last_model_time_ns"])
            if version != last_sequence:
                raise RewardBaselineError("baseline_version must equal committed feedback sequence in V1")
            raw_history = row["history"]
            if not isinstance(raw_history, list) or len(raw_history) > manager.history_limit:
                raise RewardBaselineError("checkpoint history exceeds its configured bound")
            history: list[RewardBaselineReceipt] = []
            seen_feedback_ids: set[str] = set()
            previous_sequence = 0
            previous_receipt: RewardBaselineReceipt | None = None
            for raw_receipt in raw_history:
                receipt_base_keys = {
                    "reward_task_id", "feedback_id", "feedback_sequence",
                    "reward_value", "model_time_ns", "baseline_version",
                    "baseline_before", "baseline_after", "modulator",
                    "processing_status",
                }
                receipt_extra_keys = {"cqe_envelope_json", "feedback_context_digest"}
                if set(raw_receipt) not in (
                    receipt_base_keys, receipt_base_keys | receipt_extra_keys
                ):
                    raise RewardBaselineError("feedback receipt fields do not match V1 or its CQE extension")
                receipt = raw_receipt
                receipt_envelope, receipt_digest = _cqe_metadata(
                    receipt.get("cqe_envelope_json"),
                    receipt.get("feedback_context_digest"),
                )
                receipt_task = _identifier("receipt reward_task_id", receipt["reward_task_id"])
                feedback_id = _identifier("feedback_id", receipt["feedback_id"])
                sequence = _u64("feedback_sequence", receipt["feedback_sequence"], minimum=1)
                reward = _unit("reward_value", receipt["reward_value"])
                model_time = _u64("model_time_ns", receipt["model_time_ns"])
                receipt_version = _u64("receipt baseline_version", receipt["baseline_version"], minimum=1)
                before = _unit("baseline_before", receipt["baseline_before"])
                after = _unit("baseline_after", receipt["baseline_after"])
                modulator = receipt["modulator"]
                if isinstance(modulator, bool) or not isinstance(modulator, (int, float)) or not math.isfinite(float(modulator)) or not -1.0 <= float(modulator) <= 1.0:
                    raise RewardBaselineError("receipt modulator is outside [-1, 1]")
                status = receipt["processing_status"]
                expected_modulator = max(-1.0, min(1.0, reward - before))
                expected_after = max(
                    0.0, min(1.0, before + manager.beta_b * (reward - before))
                )
                if (
                    receipt_task != task_id or sequence <= previous_sequence
                    or sequence > last_sequence or receipt_version != sequence
                    or not isinstance(status, str) or status not in _COMPLETED_STATUSES
                    or float(modulator) != expected_modulator or after != expected_after
                    or feedback_id in seen_feedback_ids
                ):
                    raise RewardBaselineError("checkpoint feedback history is inconsistent")
                if previous_receipt is not None and (
                    sequence != previous_receipt.feedback_sequence + 1
                    or before != previous_receipt.baseline_after
                    or model_time < previous_receipt.model_time_ns
                ):
                    raise RewardBaselineError("checkpoint feedback history is not a contiguous monotonic chain")
                _validate_cqe_binding(
                    receipt_envelope, reward_task_id=task_id,
                    feedback_id=feedback_id, feedback_sequence=sequence,
                    reward_value=reward, model_time_ns=model_time,
                    clock_epoch_id=manager.clock_epoch_id,
                )
                restored_receipt = RewardBaselineReceipt(
                    task_id, feedback_id, sequence, reward, model_time,
                    receipt_version, before, after, float(modulator), status,
                    receipt_envelope, receipt_digest,
                )
                history.append(restored_receipt)
                seen_feedback_ids.add(feedback_id)
                previous_receipt = restored_receipt
                previous_sequence = sequence
            if last_sequence:
                if not history or history[-1].feedback_sequence != last_sequence or history[-1].feedback_id != last_id or history[-1].baseline_after != baseline or history[-1].model_time_ns != last_time:
                    raise RewardBaselineError("latest committed feedback does not match task record")
            elif last_id is not None or history or baseline != manager.initial_baseline:
                raise RewardBaselineError("empty task record contains committed feedback state")

            pending = None
            if row["pending"] is not None:
                pending_base_keys = {
                    "manager_id", "reward_task_id", "feedback_id", "feedback_sequence",
                    "reward_value", "model_time_ns", "clock_epoch_id",
                    "baseline_version", "baseline_before", "baseline_after",
                    "modulator", "status", "duplicate_processing_status",
                }
                pending_extra_keys = {"cqe_envelope_json", "feedback_context_digest"}
                if set(row["pending"]) not in (
                    pending_base_keys, pending_base_keys | pending_extra_keys
                ):
                    raise RewardBaselineError("pending feedback fields do not match V1 or its CQE extension")
                raw_pending = row["pending"]
                pending_envelope, pending_digest = _cqe_metadata(
                    raw_pending.get("cqe_envelope_json"),
                    raw_pending.get("feedback_context_digest"),
                )
                pending_epoch = parse_epoch(raw_pending["clock_epoch_id"])
                if pending_epoch != manager.clock_epoch_id:
                    raise RewardBaselineError("pending feedback clock epoch mismatch")
                modulator = raw_pending["modulator"]
                if isinstance(modulator, bool) or not isinstance(modulator, (int, float)) or not math.isfinite(float(modulator)) or not -1.0 <= float(modulator) <= 1.0:
                    raise RewardBaselineError("pending feedback modulator is invalid")
                pending = RewardBaselineProposal(
                    _identifier("pending manager_id", raw_pending["manager_id"]),
                    _identifier("pending reward_task_id", raw_pending["reward_task_id"]),
                    _identifier("pending feedback_id", raw_pending["feedback_id"]),
                    _u64("pending feedback_sequence", raw_pending["feedback_sequence"], minimum=1),
                    _unit("pending reward_value", raw_pending["reward_value"]),
                    _u64("pending model_time_ns", raw_pending["model_time_ns"]),
                    pending_epoch,
                    _u64("pending baseline_version", raw_pending["baseline_version"]),
                    _unit("pending baseline_before", raw_pending["baseline_before"]),
                    _unit("pending baseline_after", raw_pending["baseline_after"]),
                    float(modulator),
                    raw_pending["status"],
                    raw_pending["duplicate_processing_status"],
                    pending_envelope,
                    pending_digest,
                )
                _validate_cqe_binding(
                    pending_envelope, reward_task_id=task_id,
                    feedback_id=pending.feedback_id,
                    feedback_sequence=pending.feedback_sequence,
                    reward_value=pending.reward_value,
                    model_time_ns=pending.model_time_ns,
                    clock_epoch_id=manager.clock_epoch_id,
                )
                expected_pending_modulator = max(
                    -1.0, min(1.0, pending.reward_value - pending.baseline_before)
                )
                expected_pending_after = max(
                    0.0, min(
                        1.0,
                        pending.baseline_before
                        + manager.beta_b * (pending.reward_value - pending.baseline_before),
                    )
                )
                if (
                    pending.manager_id != manager.manager_id
                    or pending.feedback_id in seen_feedback_ids
                    or pending.reward_task_id != task_id
                    or pending.status != "PREPARED"
                    or pending.duplicate_processing_status is not None
                    or pending.feedback_sequence != last_sequence + 1
                    or pending.baseline_version != version
                    or pending.baseline_before != baseline
                    or pending.model_time_ns < last_time
                    or pending.modulator != expected_pending_modulator
                    or pending.baseline_after != expected_pending_after
                ):
                    raise RewardBaselineError("pending feedback does not continue the task record")
            manager._records[task_id] = RewardBaselineRecord(
                task_id, baseline, version, last_sequence, last_id,
                last_time, pending, tuple(history),
            )
        if [item["reward_task_id"] for item in raw_records] != sorted(manager._records):
            raise RewardBaselineError("checkpoint reward tasks are not in canonical ID order")
        return manager
