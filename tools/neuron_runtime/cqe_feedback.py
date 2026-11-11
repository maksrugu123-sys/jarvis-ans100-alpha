"""Durable CQE reward-feedback inbox over the atomic JRCM checkpoint.

The caller must perform any required source authorization through its upstream
policy/security layer before calling this adapter. This module records
provenance; it does not implement that layer, issue capabilities, or
independently authorize untrusted sources.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import hashlib
import json
import math
import uuid
from typing import Any

from tools.cqe_reference.cqe import MessageEnvelope, MessageType
from tools.numerical_oracle.jarvis_oracle import OracleInputError

from .feedback_transaction import (
    RewardFeedbackTransactionResult,
    RewardSynapseTarget,
    process_reward_feedback,
)
from .network import NetworkRuntime, NetworkRuntimeError
from .reward_baseline import RewardBaselineManager
from .runtime import NeuronRuntimeError
from .runtime_store import (
    JPVRRuntimeCheckpointConflict,
    JPVRRuntimeCheckpointNotFound,
    JPVRRuntimeCheckpointStore,
)


CQE_REWARD_FEEDBACK_SCHEMA = "jarvis.reward.feedback"
CQE_REWARD_FEEDBACK_SCHEMA_VERSION = 1
_U64_MAX = (1 << 64) - 1


class CQEFeedbackError(ValueError):
    """Invalid or unrecoverable CQE reward-feedback envelope."""


class CQEFeedbackConflict(CQEFeedbackError):
    """A message/event identity or durable runtime version conflicts."""


@dataclass(frozen=True)
class CQEFeedbackResult:
    transaction: RewardFeedbackTransactionResult
    message_id: str
    event_id: str
    inbox_runtime_version: int | None

    @property
    def duplicate(self) -> bool:
        return self.transaction.duplicate

    @property
    def runtime_version(self) -> int:
        return self.transaction.runtime_version


def _uuid(name: str, value: object) -> str:
    if not isinstance(value, str):
        raise CQEFeedbackError(f"{name} must be a canonical UUID")
    try:
        canonical = str(uuid.UUID(value))
    except (ValueError, AttributeError, TypeError) as exc:
        raise CQEFeedbackError(f"{name} must be a canonical UUID") from exc
    if canonical != value:
        raise CQEFeedbackError(f"{name} must be a lowercase canonical UUID")
    return value


def _text(name: str, value: object, *, maximum: int = 512) -> str:
    if (
        not isinstance(value, str) or not value or value != value.strip()
        or "\x00" in value
    ):
        raise CQEFeedbackError(f"{name} must be a trimmed non-empty string")
    try:
        encoded = value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise CQEFeedbackError(f"{name} must be valid UTF-8") from exc
    if len(encoded) > maximum or any(ord(char) < 32 for char in value):
        raise CQEFeedbackError(f"{name} exceeds its identifier contract")
    return value


def _u64(name: str, value: object, *, minimum: int = 0) -> int:
    if (
        isinstance(value, bool) or not isinstance(value, int)
        or not minimum <= value <= _U64_MAX
    ):
        raise CQEFeedbackError(f"{name} must be an unsigned 64-bit integer")
    return value


def _canonical(value: Any) -> str:
    try:
        return json.dumps(
            value, ensure_ascii=False, allow_nan=False,
            sort_keys=True, separators=(",", ":"),
        )
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise CQEFeedbackError(
            "CQE envelope payload must contain finite JSON values"
        ) from exc


def _normalize_envelope(
    envelope: MessageEnvelope,
    *,
    policy_version: int,
    authorization_receipt_id: str,
    clock_epoch_id: bytes,
):
    if not isinstance(envelope, MessageEnvelope):
        raise CQEFeedbackError("envelope must be a CQE MessageEnvelope")
    protocol_version = _u64(
        "protocol_version", envelope.protocol_version, minimum=1
    )
    schema_version = _u64(
        "schema_version", envelope.schema_version, minimum=1
    )
    if (
        protocol_version != 1
        or envelope.message_type is not MessageType.EVENT
        or envelope.schema_id != CQE_REWARD_FEEDBACK_SCHEMA
        or schema_version != CQE_REWARD_FEEDBACK_SCHEMA_VERSION
        or envelope.operation_id is not None
    ):
        raise CQEFeedbackError(
            "only CQE EVENT jarvis.reward.feedback schema v1 is accepted"
        )

    message_id = _uuid("message_id", envelope.message_id)
    event_id = _uuid("event_id", envelope.event_id)
    source = _text("source_module_id", envelope.source_module_id)
    auth_id = _uuid("authorization_receipt_id", authorization_receipt_id)
    policy = _u64("policy_version", policy_version, minimum=1)

    if not hasattr(envelope.payload, "keys"):
        raise CQEFeedbackError("CQE reward-feedback payload must be a mapping")
    raw = dict(envelope.payload)
    required = {
        "reward_task_id", "feedback_id", "feedback_sequence", "reward_value",
        "model_time_ns", "clock_epoch_id", "learning_context_id", "targets",
    }
    if set(raw) != required:
        raise CQEFeedbackError(
            "CQE reward-feedback payload has missing or unknown fields"
        )
    task_id = _text("reward_task_id", raw["reward_task_id"])
    feedback_id = _text("feedback_id", raw["feedback_id"])
    sequence = _u64("feedback_sequence", raw["feedback_sequence"], minimum=1)
    reward_value = raw["reward_value"]
    if (
        isinstance(reward_value, bool)
        or not isinstance(reward_value, (int, float))
        or not math.isfinite(float(reward_value))
        or not 0.0 <= float(reward_value) <= 1.0
    ):
        raise CQEFeedbackError("reward_value must be finite and in [0, 1]")
    model_time = _u64("model_time_ns", raw["model_time_ns"])

    epoch_text = raw["clock_epoch_id"]
    if not isinstance(epoch_text, str) or len(epoch_text) != 32:
        raise CQEFeedbackError(
            "clock_epoch_id must be 32 lowercase hexadecimal characters"
        )
    try:
        epoch = bytes.fromhex(epoch_text)
    except ValueError as exc:
        raise CQEFeedbackError("clock_epoch_id is not hexadecimal") from exc
    if (
        epoch.hex() != epoch_text
        or epoch == bytes(16)
        or epoch != bytes(clock_epoch_id)
    ):
        raise CQEFeedbackError(
            "CQE feedback clock epoch differs from the active runtime"
        )

    context_id = raw["learning_context_id"]
    if context_id is not None:
        context_id = _text("learning_context_id", context_id)
    targets = raw["targets"]
    if not isinstance(targets, list) or not 1 <= len(targets) <= 128:
        raise CQEFeedbackError("targets must contain 1..128 target entries")
    ordered = []
    for item in targets:
        if not isinstance(item, dict) or set(item) != {
            "neuron_id", "synapse_id"
        }:
            raise CQEFeedbackError(
                "each target must contain neuron_id and synapse_id only"
            )
        ordered.append({
            "neuron_id": _text(
                "target neuron_id", item["neuron_id"], maximum=128
            ),
            "synapse_id": _text(
                "target synapse_id", item["synapse_id"], maximum=128
            ),
        })
    ordered.sort(key=lambda item: (item["neuron_id"], item["synapse_id"]))
    target_keys = [
        (item["neuron_id"], item["synapse_id"]) for item in ordered
    ]
    if len(target_keys) != len(set(target_keys)):
        raise CQEFeedbackError("CQE feedback target set contains duplicates")
    if len(_canonical(raw).encode("utf-8")) > 4 * 1024:
        raise CQEFeedbackError("CQE feedback payload exceeds the 4 KiB inline limit")

    def optional_text(name: str, value: object) -> str | None:
        return None if value is None else _text(name, value)

    commit_id = optional_text("commit_id", envelope.commit_id)
    if commit_id is not None:
        _uuid("commit_id", commit_id)
    if (commit_id is None) != (envelope.state_version is None):
        raise CQEFeedbackError(
            "state-change event requires both commit_id and state_version"
        )
    state_version = (
        None if envelope.state_version is None
        else _u64("state_version", envelope.state_version)
    )
    expected_state_version = (
        None if envelope.expected_state_version is None
        else _u64("expected_state_version", envelope.expected_state_version)
    )
    deadline = (
        None if envelope.deadline_budget_ms is None
        else _u64(
            "deadline_budget_ms", envelope.deadline_budget_ms, minimum=1
        )
    )
    if deadline is not None and deadline > 60_000:
        raise CQEFeedbackError("deadline_budget_ms exceeds CQE v1 maximum")
    priority = (
        None if envelope.requested_priority is None
        else getattr(envelope.requested_priority, "value", None)
    )
    if envelope.requested_priority is not None and priority not in {
        "P0", "P1", "P2", "P3", "P4"
    }:
        raise CQEFeedbackError("requested_priority is invalid")

    normalized_payload = {
        "reward_task_id": task_id,
        "feedback_id": feedback_id,
        "feedback_sequence": sequence,
        "reward_value": float(reward_value),
        "model_time_ns": model_time,
        "clock_epoch_id": epoch_text,
        "learning_context_id": context_id,
        "targets": ordered,
    }
    document = {
        "protocol_version": 1,
        "message_id": message_id,
        "event_id": event_id,
        "message_type": "EVENT",
        "schema_id": CQE_REWARD_FEEDBACK_SCHEMA,
        "schema_version": CQE_REWARD_FEEDBACK_SCHEMA_VERSION,
        "source_module_id": source,
        "target_module_id": optional_text(
            "target_module_id", envelope.target_module_id
        ),
        "commit_id": commit_id,
        "operation_id": None,
        "correlation_id": optional_text(
            "correlation_id", envelope.correlation_id
        ),
        "causation_id": optional_text("causation_id", envelope.causation_id),
        "idempotency_key": optional_text(
            "idempotency_key", envelope.idempotency_key
        ),
        "expected_state_version": expected_state_version,
        "deadline_budget_ms": deadline,
        "requested_priority": priority,
        "capability_id": optional_text(
            "capability_id", envelope.capability_id
        ),
        "state_version": state_version,
        "payload": normalized_payload,
        "policy_version": policy,
        "authorization_receipt_id": auth_id,
    }
    envelope_json = _canonical(document)
    if len(envelope_json.encode("utf-8")) > 8 * 1024:
        raise CQEFeedbackError("normalized CQE envelope exceeds 8 KiB")

    context_document = {
        "learning_context_id": context_id,
        "targets": ordered,
    }
    context_digest = hashlib.sha256(
        _canonical(context_document).encode("utf-8")
    ).hexdigest()
    transaction_targets = tuple(
        RewardSynapseTarget(item["neuron_id"], item["synapse_id"])
        for item in ordered
    )
    return (
        envelope_json, context_digest, task_id, feedback_id, sequence,
        float(reward_value), model_time, context_id, transaction_targets,
    )


def _check_identity_reuse(
    manager: RewardBaselineManager,
    *,
    reward_task_id: str,
    envelope_json: str,
) -> None:
    incoming = json.loads(envelope_json)
    for record in manager.export_checkpoint_state()["records"]:
        rows = []
        if record["pending"] is not None:
            rows.append(record["pending"])
        rows.extend(record["history"])
        for row in rows:
            encoded = row.get("cqe_envelope_json")
            if encoded is None:
                continue
            previous = json.loads(encoded)
            reused = (
                previous["message_id"] == incoming["message_id"]
                or previous["event_id"] == incoming["event_id"]
            )
            if reused and (
                encoded != envelope_json
                or record["reward_task_id"] != reward_task_id
            ):
                raise CQEFeedbackConflict(
                    "CQE message_id/event_id is already bound to different feedback content"
                )


def _preflight_reward_feedback(
    network: NetworkRuntime,
    manager: RewardBaselineManager,
    *,
    targets: tuple[RewardSynapseTarget, ...],
    model_time_ns: int,
    reward_value: float,
    baseline_before: float,
    baseline_after: float,
    expected_modulator: float,
    learning_context_id: str | None,
) -> None:
    """Run the deterministic reward path on a detached copy before inbox acceptance."""
    try:
        state = network.export_checkpoint_state()
        neurons = state["neurons"]
        for target in targets:
            runtime = neurons.get(target.neuron_id)
            if runtime is None:
                raise CQEFeedbackError(
                    f"unknown reward target neuron: {target.neuron_id}"
                )
            if runtime.learning_rule != "rstdp":
                raise CQEFeedbackError(
                    f"reward target neuron {target.neuron_id} is not configured for R-STDP"
                )
            if target.synapse_id not in runtime.synapses:
                raise CQEFeedbackError(
                    f"unknown reward target synapse: {target.neuron_id}/{target.synapse_id}"
                )
            if runtime.reward_params.beta_b != manager.beta_b:
                raise CQEFeedbackError(
                    "RewardBaselineManager.beta_b must exactly match every target RewardParams.beta_b"
                )

        candidate = deepcopy(network)
        for target in targets:
            update = candidate.apply_reward(
                target.neuron_id, target.synapse_id, model_time_ns,
                reward_value, baseline_before, learning_context_id,
            )
            if (
                update.result.baseline_after != baseline_after
                or update.result.modulator != expected_modulator
            ):
                raise CQEFeedbackError(
                    "Oracle reward result differs from the prepared baseline proposal"
                )
    except CQEFeedbackError:
        raise
    except (
        NetworkRuntimeError, NeuronRuntimeError, OracleInputError,
        TypeError, ValueError,
    ) as exc:
        raise CQEFeedbackError(
            f"reward-feedback preflight was rejected: {exc}"
        ) from exc


def process_cqe_reward_feedback(
    store: JPVRRuntimeCheckpointStore,
    *,
    network_id: str,
    network: NetworkRuntime,
    reward_baseline_manager: RewardBaselineManager,
    envelope: MessageEnvelope,
    policy_version: int,
    authorization_receipt_id: str,
    expected_runtime_version: int,
    max_pages: int = 4096,
) -> CQEFeedbackResult:
    """Preflight, durably accept a CQE reward EVENT, then atomically finish it.

    All targets are first evaluated on a detached network copy. After inbox
    acceptance, the real transaction repeats the deterministic calculation
    and commits network+baseline+receipt together. After interruption, reopen
    JRCM and retry the same envelope with the loaded state and latest version.
    A completed duplicate is reconciled without applying reward a second time.
    """
    if not isinstance(store, JPVRRuntimeCheckpointStore):
        raise CQEFeedbackError("store must be a JPVRRuntimeCheckpointStore")
    if not isinstance(network, NetworkRuntime):
        raise CQEFeedbackError("network must be a NetworkRuntime")
    if not isinstance(reward_baseline_manager, RewardBaselineManager):
        raise CQEFeedbackError(
            "reward_baseline_manager must be a RewardBaselineManager"
        )
    version = _u64("expected_runtime_version", expected_runtime_version)
    candidate_manager = RewardBaselineManager.from_checkpoint_state(
        reward_baseline_manager.export_checkpoint_state()
    )
    try:
        (
            envelope_json, context_digest, task_id, feedback_id,
            sequence, reward, model_time, context_id, targets,
        ) = _normalize_envelope(
            envelope,
            policy_version=policy_version,
            authorization_receipt_id=authorization_receipt_id,
            clock_epoch_id=candidate_manager.clock_epoch_id,
        )
        _check_identity_reuse(
            candidate_manager,
            reward_task_id=task_id,
            envelope_json=envelope_json,
        )
        proposal = candidate_manager.prepare_feedback(
            task_id, feedback_id, sequence, reward, model_time,
            clock_epoch_id=candidate_manager.clock_epoch_id,
            cqe_envelope_json=envelope_json,
            feedback_context_digest=context_digest,
        )
    except ValueError as exc:
        if isinstance(exc, CQEFeedbackError):
            raise
        raise CQEFeedbackError(
            f"CQE feedback reservation was rejected: {exc}"
        ) from exc

    if proposal.status == "DUPLICATE":
        transaction = process_reward_feedback(
            store,
            network_id=network_id,
            network=network,
            reward_baseline_manager=candidate_manager,
            reward_task_id=task_id,
            feedback_id=feedback_id,
            feedback_sequence=sequence,
            reward_value=reward,
            model_time_ns=model_time,
            targets=targets,
            learning_context_id=context_id,
            expected_runtime_version=version,
            cqe_envelope_json=envelope_json,
            feedback_context_digest=context_digest,
            max_pages=max_pages,
        )
        return CQEFeedbackResult(
            transaction, envelope.message_id, envelope.event_id, None
        )

    # Reject malformed or presently unprocessable targets before accepting
    # a durable pending inbox item. This probe mutates only a detached clone.
    _preflight_reward_feedback(
        network, candidate_manager, targets=targets,
        model_time_ns=model_time, reward_value=reward,
        baseline_before=proposal.baseline_before,
        baseline_after=proposal.baseline_after,
        expected_modulator=proposal.modulator,
        learning_context_id=context_id,
    )

    # Persist the prepared proposal before any synapse work. A matching
    # already-durable pending proposal is resumed without another inbox write.
    inbox_version = None
    try:
        active = store.load(
            network_id, candidate_manager.manager_id, max_pages=max_pages
        )
    except JPVRRuntimeCheckpointNotFound:
        active = None

    if active is not None:
        if active.descriptor.runtime_version != version:
            raise JPVRRuntimeCheckpointConflict(
                "expected runtime version does not match the active checkpoint"
            )
        if (
            active.snapshot.runtime.export_checkpoint_state()
            != network.export_checkpoint_state()
        ):
            raise CQEFeedbackConflict(
                "supplied NetworkRuntime differs from the active durable checkpoint"
            )
        durable_manager_state = (
            active.reward_baseline_manager.export_checkpoint_state()
        )
        candidate_manager_state = candidate_manager.export_checkpoint_state()
        if durable_manager_state == candidate_manager_state:
            working_network = active.snapshot.runtime
            working_manager = active.reward_baseline_manager
        else:
            caller_manager_state = reward_baseline_manager.export_checkpoint_state()
            if durable_manager_state != caller_manager_state:
                raise CQEFeedbackConflict(
                    "supplied RewardBaselineManager differs from active durable state"
                )
            pending = store.save(
                network_id, network, candidate_manager,
                expected_runtime_version=version, max_pages=max_pages,
            )
            inbox_version = pending.runtime_version
            working_network = network
            working_manager = candidate_manager
            version = pending.runtime_version
    else:
        if version != 0:
            raise JPVRRuntimeCheckpointConflict(
                "no active runtime checkpoint exists for the supplied non-zero version"
            )
        pending = store.save(
            network_id, network, candidate_manager,
            expected_runtime_version=0, max_pages=max_pages,
        )
        inbox_version = pending.runtime_version
        working_network = network
        working_manager = candidate_manager
        version = pending.runtime_version

    transaction = process_reward_feedback(
        store,
        network_id=network_id,
        network=working_network,
        reward_baseline_manager=working_manager,
        reward_task_id=task_id,
        feedback_id=feedback_id,
        feedback_sequence=sequence,
        reward_value=reward,
        model_time_ns=model_time,
        targets=targets,
        learning_context_id=context_id,
        expected_runtime_version=version,
        cqe_envelope_json=envelope_json,
        feedback_context_digest=context_digest,
        max_pages=max_pages,
    )
    return CQEFeedbackResult(
        transaction, envelope.message_id, envelope.event_id, inbox_version
    )
