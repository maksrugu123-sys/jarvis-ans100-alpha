"""Atomic candidate-state R-STDP feedback transaction for NetworkRuntime + JPVR.

All synapse reward calculations run against a detached network copy. The matching
RewardBaselineManager is prepared and advanced on a detached copy too. Only after
every target reaches a terminal Oracle outcome are both snapshots published by
one JPVRRuntimeCheckpointStore COMMIT.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from typing import Sequence

from tools.numerical_oracle.jarvis_oracle import OracleInputError

from .network import NetworkRuntime, NetworkRuntimeError
from .network_adapter import network_runtime_object_id
from .runtime import NeuronRuntimeError, RewardUpdate
from .reward_baseline import (
    RewardBaselineManager,
    RewardBaselineProposal,
    RewardBaselineReceipt,
)
from .runtime_store import (
    JPVRRuntimeCheckpointError,
    JPVRRuntimeCheckpointNotFound,
    JPVRRuntimeCheckpointStore,
    SavedRuntimeCheckpoint,
)


_U64_MAX = (1 << 64) - 1
_TERMINAL_REWARD_STATUSES = frozenset({
    "APPLIED", "APPLIED_NO_CHANGE", "NO_ELIGIBILITY", "EXPIRED",
})


class RewardFeedbackTransactionError(ValueError):
    """Invalid feedback transaction or a rejected candidate-state operation."""


class RewardFeedbackTransactionConflict(RewardFeedbackTransactionError):
    """A duplicate cannot be reconciled with the active durable runtime snapshot."""


@dataclass(frozen=True)
class RewardSynapseTarget:
    neuron_id: str
    synapse_id: str


@dataclass(frozen=True)
class AppliedSynapseReward:
    neuron_id: str
    synapse_id: str
    update: RewardUpdate


@dataclass(frozen=True)
class RewardFeedbackTransactionResult:
    network: NetworkRuntime
    reward_baseline_manager: RewardBaselineManager
    receipt: RewardBaselineReceipt
    updates: tuple[AppliedSynapseReward, ...]
    runtime_version: int
    saved_checkpoint: SavedRuntimeCheckpoint | None
    duplicate: bool


def _validate_expected_version(value: object) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or not 0 <= value <= _U64_MAX
    ):
        raise RewardFeedbackTransactionError(
            "expected_runtime_version must be an unsigned 64-bit integer"
        )
    return value


def _prepare_targets(
    targets: Sequence[RewardSynapseTarget],
) -> tuple[RewardSynapseTarget, ...]:
    if not isinstance(targets, (tuple, list)) or not targets:
        raise RewardFeedbackTransactionError(
            "targets must be a non-empty list or tuple of RewardSynapseTarget values"
        )
    prepared = []
    for target in targets:
        if not isinstance(target, RewardSynapseTarget):
            raise RewardFeedbackTransactionError(
                "every target must be a RewardSynapseTarget"
            )
        for name, value in (
            ("neuron_id", target.neuron_id),
            ("synapse_id", target.synapse_id),
        ):
            if (
                not isinstance(value, str)
                or not value.strip()
                or value != value.strip()
                or len(value) > 128
                or "\x00" in value
            ):
                raise RewardFeedbackTransactionError(
                    f"target {name} must be a trimmed non-empty ID of at most 128 characters"
                )
        prepared.append(target)
    ordered = tuple(sorted(prepared, key=lambda item: (item.neuron_id, item.synapse_id)))
    keys = tuple((item.neuron_id, item.synapse_id) for item in ordered)
    if len(keys) != len(set(keys)):
        raise RewardFeedbackTransactionError("reward targets must be unique")
    return ordered


def _processing_status(updates: tuple[AppliedSynapseReward, ...]) -> str:
    statuses = {item.update.result.status for item in updates}
    if not statuses or not statuses <= _TERMINAL_REWARD_STATUSES:
        raise RewardFeedbackTransactionError(
            "Oracle returned an unsupported or non-terminal reward status"
        )
    if "APPLIED" in statuses:
        return "APPLIED"
    if "APPLIED_NO_CHANGE" in statuses:
        return "APPLIED_NO_CHANGE"
    if statuses == {"EXPIRED"}:
        return "EXPIRED"
    return "NO_ELIGIBILITY"


def _reconcile_duplicate(
    store: JPVRRuntimeCheckpointStore,
    *,
    network_id: str,
    manager_id: str,
    reward_task_id: str,
    feedback_id: str,
    feedback_sequence: int,
    reward_value: float,
    model_time_ns: int,
    expected_runtime_version: int,
    proposal: RewardBaselineProposal,
    manager: RewardBaselineManager,
    max_pages: int,
) -> RewardFeedbackTransactionResult:
    try:
        active = store.load(network_id, manager_id, max_pages=max_pages)
    except (JPVRRuntimeCheckpointError, JPVRRuntimeCheckpointNotFound) as exc:
        raise RewardFeedbackTransactionConflict(
            "duplicate feedback has no matching active durable runtime checkpoint"
        ) from exc
    if active.descriptor.runtime_version != expected_runtime_version:
        raise RewardFeedbackTransactionConflict(
            "duplicate feedback was supplied with a stale runtime version"
        )
    persisted_record = active.reward_baseline_manager.get_record(reward_task_id)
    persisted_receipt = next(
        (
            item for item in persisted_record.history
            if item.feedback_id == feedback_id
            and item.feedback_sequence == feedback_sequence
        ),
        None,
    )
    if (
        persisted_receipt is None
        or persisted_receipt.reward_value != float(reward_value)
        or persisted_receipt.model_time_ns != model_time_ns
    ):
        raise RewardFeedbackTransactionConflict(
            "manager replay history does not match the active durable runtime"
        )
    if proposal.duplicate_processing_status is None:
        raise RewardFeedbackTransactionConflict(
            "duplicate proposal has no retained terminal processing status"
        )
    try:
        candidate_receipt = manager.commit_feedback(
            proposal,
            processing_status=proposal.duplicate_processing_status,
        )
    except ValueError as exc:
        raise RewardFeedbackTransactionConflict(
            f"duplicate proposal cannot be reconciled: {exc}"
        ) from exc
    if candidate_receipt != persisted_receipt:
        raise RewardFeedbackTransactionConflict(
            "duplicate feedback receipt differs from the active durable receipt"
        )
    return RewardFeedbackTransactionResult(
        network=active.snapshot.runtime,
        reward_baseline_manager=active.reward_baseline_manager,
        receipt=persisted_receipt,
        updates=(),
        runtime_version=active.descriptor.runtime_version,
        saved_checkpoint=None,
        duplicate=True,
    )


def process_reward_feedback(
    store: JPVRRuntimeCheckpointStore,
    *,
    network_id: str,
    network: NetworkRuntime,
    reward_baseline_manager: RewardBaselineManager,
    reward_task_id: str,
    feedback_id: str,
    feedback_sequence: int,
    reward_value: float,
    model_time_ns: int,
    targets: Sequence[RewardSynapseTarget],
    learning_context_id: str | None,
    expected_runtime_version: int,
    max_pages: int = 4096,
) -> RewardFeedbackTransactionResult:
    """Apply a reward to all targets and publish network+baseline in one COMMIT.

    This function never mutates the caller's network or manager. On success,
    callers must replace both references with the returned objects. A retry of
    an already committed feedback returns the active durable objects and does
    not apply the reward again.
    """
    if not isinstance(store, JPVRRuntimeCheckpointStore):
        raise RewardFeedbackTransactionError(
            "store must be a JPVRRuntimeCheckpointStore"
        )
    if not isinstance(network, NetworkRuntime):
        raise RewardFeedbackTransactionError("network must be a NetworkRuntime")
    if not isinstance(reward_baseline_manager, RewardBaselineManager):
        raise RewardFeedbackTransactionError(
            "reward_baseline_manager must be a RewardBaselineManager"
        )
    if learning_context_id is not None and (
        not isinstance(learning_context_id, str)
        or not learning_context_id.strip()
        or learning_context_id != learning_context_id.strip()
    ):
        raise RewardFeedbackTransactionError(
            "learning_context_id must be None or a trimmed non-empty string"
        )
    version = _validate_expected_version(expected_runtime_version)
    network_runtime_object_id(network_id)

    try:
        candidate_manager = RewardBaselineManager.from_checkpoint_state(
            reward_baseline_manager.export_checkpoint_state()
        )
        proposal = candidate_manager.prepare_feedback(
            reward_task_id,
            feedback_id,
            feedback_sequence,
            reward_value,
            model_time_ns,
            clock_epoch_id=candidate_manager.clock_epoch_id,
        )
    except ValueError as exc:
        raise RewardFeedbackTransactionError(
            f"reward feedback proposal was rejected: {exc}"
        ) from exc

    if proposal.status == "DUPLICATE":
        return _reconcile_duplicate(
            store,
            network_id=network_id,
            manager_id=candidate_manager.manager_id,
            reward_task_id=reward_task_id,
            feedback_id=feedback_id,
            feedback_sequence=feedback_sequence,
            reward_value=float(reward_value),
            model_time_ns=model_time_ns,
            expected_runtime_version=version,
            proposal=proposal,
            manager=candidate_manager,
            max_pages=max_pages,
        )

    ordered_targets = _prepare_targets(targets)
    try:
        state = network.export_checkpoint_state()
    except NetworkRuntimeError as exc:
        raise RewardFeedbackTransactionError(
            f"network cannot enter a feedback transaction: {exc}"
        ) from exc
    neurons = state["neurons"]
    for target in ordered_targets:
        runtime = neurons.get(target.neuron_id)
        if runtime is None:
            raise RewardFeedbackTransactionError(
                f"unknown reward target neuron: {target.neuron_id}"
            )
        if runtime.learning_rule != "rstdp":
            raise RewardFeedbackTransactionError(
                f"reward target neuron {target.neuron_id} is not configured for R-STDP"
            )
        if target.synapse_id not in runtime.synapses:
            raise RewardFeedbackTransactionError(
                f"unknown reward target synapse: {target.neuron_id}/{target.synapse_id}"
            )
        if runtime.reward_params.beta_b != candidate_manager.beta_b:
            raise RewardFeedbackTransactionError(
                "RewardBaselineManager.beta_b must exactly match every target RewardParams.beta_b"
            )

    candidate_network = deepcopy(network)
    applied: list[AppliedSynapseReward] = []
    for target in ordered_targets:
        try:
            update = candidate_network.apply_reward(
                target.neuron_id,
                target.synapse_id,
                proposal.model_time_ns,
                proposal.reward_value,
                proposal.baseline_before,
                learning_context_id,
            )
        except (NetworkRuntimeError, NeuronRuntimeError, OracleInputError) as exc:
            raise RewardFeedbackTransactionError(
                f"reward target {target.neuron_id}/{target.synapse_id} was rejected: {exc}"
            ) from exc
        if (
            update.result.baseline_after != proposal.baseline_after
            or update.result.modulator != proposal.modulator
        ):
            raise RewardFeedbackTransactionError(
                "Oracle reward baseline result differs from the prepared manager proposal"
            )
        applied.append(AppliedSynapseReward(
            target.neuron_id, target.synapse_id, update
        ))

    updates = tuple(applied)
    status = _processing_status(updates)
    try:
        receipt = candidate_manager.commit_feedback(
            proposal, processing_status=status
        )
    except ValueError as exc:
        raise RewardFeedbackTransactionError(
            f"reward baseline commit was rejected: {exc}"
        ) from exc

    # The JPVR store re-reads the current descriptor and enforces this CAS
    # before publishing either component. The source objects remain untouched.
    saved = store.save(
        network_id,
        candidate_network,
        candidate_manager,
        expected_runtime_version=version,
        max_pages=max_pages,
    )
    return RewardFeedbackTransactionResult(
        network=candidate_network,
        reward_baseline_manager=candidate_manager,
        receipt=receipt,
        updates=updates,
        runtime_version=saved.runtime_version,
        saved_checkpoint=saved,
        duplicate=False,
    )
