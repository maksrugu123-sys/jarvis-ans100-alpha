"""Fail-closed coordinator for atomic JPVR persistence of NetworkRuntime timestamps.

This is a single-writer Python reference coordinator. It does not turn JPVR
storage into RAM and it does not provide concurrent/distributed NCC semantics.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import threading
from types import MappingProxyType
from typing import Mapping

from tools.jpvr_reference.storage import JPVRCommitUncertainError
from .network import NetworkRunResult, NetworkRuntime, NetworkRuntimeError, RewardUpdate
from .network_jnvm_store import (
    JPVRNetworkJNVMConflict,
    JPVRNetworkJNVMStore,
    RestoredNetworkCheckpoint,
    SavedNetworkTimestamp,
)


class NetworkJNVMCoordinatorError(RuntimeError):
    """Invalid coordinator state or unsupported lifecycle operation."""


class NetworkJNVMCoordinatorFrozenError(NetworkJNVMCoordinatorError):
    """Writes are frozen until an uncertain or stale state is reconciled."""


class NetworkJNVMReconciliationError(NetworkJNVMCoordinatorError):
    """Reopened JPVR state does not prove either commit outcome."""


@dataclass(frozen=True)
class CoordinatedNetworkRun:
    """Result of at most one fully processed and durably published timestamp."""

    run: NetworkRunResult
    commit: SavedNetworkTimestamp | None


@dataclass(frozen=True)
class CoordinatedReward:
    update: RewardUpdate
    commit: SavedNetworkTimestamp


@dataclass(frozen=True)
class NetworkInput:
    time_ns: int
    neuron_id: str
    synapse_id: str
    event_id: str
    strength: float
    attention: float = 0.0


@dataclass(frozen=True)
class NetworkRewardInput:
    neuron_id: str
    synapse_id: str
    time_ns: int
    reward_value: float
    baseline_before: float
    learning_context_id: str | None


@dataclass(frozen=True)
class CoordinatedRewardBatch:
    updates: tuple[tuple[str, str, RewardUpdate], ...]
    commit: SavedNetworkTimestamp


@dataclass(frozen=True)
class NetworkJNVMCoordinatorStatus:
    network_id: str
    network_record_version: int
    neuron_record_versions: Mapping[str, int]
    generation: int
    commit_id: bytes
    current_time_ns: int
    pending_events: int
    frozen: bool
    stale: bool


@dataclass(frozen=True)
class _CommittedIdentity:
    network_record_version: int
    neuron_record_versions: tuple[tuple[str, int], ...]
    commit_id: bytes
    generation: int


@dataclass(frozen=True)
class _UncertainPublication:
    commit_id: bytes
    generation: int
    previous: _CommittedIdentity


class NetworkJNVMCoordinator:
    """Persist every state-changing operation through one atomic JPVR group COMMIT.

    The coordinator owns an isolated NetworkRuntime snapshot. It prepares changes
    in a deep copy, commits the changed neuron records and complete scheduler
    snapshot through JPVRNetworkJNVMStore, and publishes the copy in memory only
    after a confirmed commit. An uncertain Superblock publication freezes all
    further mutations until the same container is reopened and reconciled.
    """

    def __init__(
        self,
        store: JPVRNetworkJNVMStore,
        restored: RestoredNetworkCheckpoint,
    ) -> None:
        if not isinstance(store, JPVRNetworkJNVMStore):
            raise NetworkJNVMCoordinatorError("store must be a JPVRNetworkJNVMStore")
        if not isinstance(restored, RestoredNetworkCheckpoint):
            raise NetworkJNVMCoordinatorError("restored must be a RestoredNetworkCheckpoint")
        if restored.network_id == "":
            raise NetworkJNVMCoordinatorError("network_id must not be empty")
        self._store = store
        self._network_id = restored.network_id
        self._clock_epoch_id = bytes(restored.clock_epoch_id)
        self._runtime = deepcopy(restored.runtime)
        self._network_record_version = restored.network_record_version
        self._neuron_record_versions = dict(restored.neuron_record_versions)
        self._commit_id = bytes(restored.commit_id)
        self._generation = restored.generation
        self._uncertain: _UncertainPublication | None = None
        self._stale = False
        self._lock = threading.RLock()

    @classmethod
    def restore(
        cls,
        store: JPVRNetworkJNVMStore,
        network_id: str,
        *,
        max_pages: int = 4096,
    ) -> "NetworkJNVMCoordinator":
        """Restore scheduler and every neuron from one pinned JPVR snapshot."""
        try:
            restored = store.restore_network(network_id, max_pages=max_pages)
        except Exception as exc:
            raise NetworkJNVMCoordinatorError(
                "cannot initialize coordinator from a complete network checkpoint"
            ) from exc
        return cls(store, restored)

    @property
    def network_id(self) -> str:
        return self._network_id

    @property
    def neuron_ids(self) -> tuple[str, ...]:
        with self._lock:
            return self._runtime.neuron_ids

    def runtime_snapshot(self) -> NetworkRuntime:
        """Return a detached runtime copy; callers cannot mutate authoritative state."""
        with self._lock:
            return deepcopy(self._runtime)

    def status(self) -> NetworkJNVMCoordinatorStatus:
        with self._lock:
            return NetworkJNVMCoordinatorStatus(
                network_id=self._network_id,
                network_record_version=self._network_record_version,
                neuron_record_versions=MappingProxyType(dict(self._neuron_record_versions)),
                generation=self._generation,
                commit_id=self._commit_id,
                current_time_ns=self._runtime.current_time_ns,
                pending_events=self._runtime.pending_events,
                frozen=self._uncertain is not None,
                stale=self._stale,
            )

    def _require_writable(self) -> None:
        if self._uncertain is not None:
            raise NetworkJNVMCoordinatorFrozenError(
                "network writes are frozen until uncertain JPVR publication is reconciled"
            )
        if self._stale:
            raise NetworkJNVMCoordinatorFrozenError(
                "network snapshot is stale after a JPVR version conflict; reload before writing"
            )

    def _identity(self) -> _CommittedIdentity:
        return _CommittedIdentity(
            network_record_version=self._network_record_version,
            neuron_record_versions=tuple(sorted(self._neuron_record_versions.items())),
            commit_id=self._commit_id,
            generation=self._generation,
        )

    def _commit_candidate(
        self,
        candidate: NetworkRuntime,
        changed_neuron_ids: tuple[str, ...],
    ) -> SavedNetworkTimestamp:
        expected_versions = {
            neuron_id: self._neuron_record_versions[neuron_id]
            for neuron_id in changed_neuron_ids
        }
        try:
            saved = self._store.save_timestamp(
                self._network_id,
                candidate,
                changed_neuron_ids,
                clock_epoch_id=self._clock_epoch_id,
                expected_network_record_version=self._network_record_version,
                expected_neuron_record_versions=expected_versions,
            )
        except JPVRCommitUncertainError as exc:
            self._uncertain = _UncertainPublication(
                commit_id=bytes(exc.commit_id),
                generation=exc.generation,
                previous=self._identity(),
            )
            raise
        except JPVRNetworkJNVMConflict:
            # A concurrent/out-of-band writer invalidated our expected version map.
            # Do not try to repair a mixed snapshot by overwriting it.
            self._stale = True
            raise

        # The in-memory publication boundary is strictly after the durable COMMIT.
        self._runtime = candidate
        self._network_record_version = saved.network_record_version
        self._neuron_record_versions = dict(saved.neuron_record_versions)
        self._commit_id = bytes(saved.commit_id)
        self._generation = saved.generation
        return saved

    def schedule_input(
        self,
        time_ns: int,
        neuron_id: str,
        synapse_id: str,
        event_id: str,
        strength: float,
        attention: float = 0.0,
    ) -> SavedNetworkTimestamp:
        """Durably enqueue an external input before reporting it accepted."""
        with self._lock:
            self._require_writable()
            candidate = deepcopy(self._runtime)
            candidate.schedule_input(
                time_ns, neuron_id, synapse_id, event_id, strength, attention,
            )
            return self._commit_candidate(candidate, ())

    def schedule_inputs(self, inputs: tuple[NetworkInput, ...] | list[NetworkInput]) -> SavedNetworkTimestamp:
        """Durably enqueue a bounded batch of external inputs in one JPVR COMMIT."""
        if not isinstance(inputs, (tuple, list)) or not 1 <= len(inputs) <= 100_000:
            raise NetworkJNVMCoordinatorError("inputs must contain 1..100000 NetworkInput records")
        if any(not isinstance(item, NetworkInput) for item in inputs):
            raise NetworkJNVMCoordinatorError("every input must be a NetworkInput")
        event_ids = [item.event_id for item in inputs]
        if len(event_ids) != len(set(event_ids)):
            raise NetworkJNVMCoordinatorError("input batch contains duplicate event IDs")
        with self._lock:
            self._require_writable()
            candidate = deepcopy(self._runtime)
            for item in inputs:
                candidate.schedule_input(
                    item.time_ns,
                    item.neuron_id,
                    item.synapse_id,
                    item.event_id,
                    item.strength,
                    item.attention,
                )
            return self._commit_candidate(candidate, ())

    def apply_rewards_batch(
        self,
        rewards: tuple[NetworkRewardInput, ...] | list[NetworkRewardInput],
    ) -> CoordinatedRewardBatch:
        """Apply many same-time R-STDP rewards and publish all affected neurons atomically."""
        if not isinstance(rewards, (tuple, list)) or not 1 <= len(rewards) <= 100_000:
            raise NetworkJNVMCoordinatorError("rewards must contain 1..100000 NetworkRewardInput records")
        if any(not isinstance(item, NetworkRewardInput) for item in rewards):
            raise NetworkJNVMCoordinatorError("every reward must be a NetworkRewardInput")
        timestamps = {item.time_ns for item in rewards}
        if len(timestamps) != 1:
            raise NetworkJNVMCoordinatorError("one reward batch must use a single model timestamp")
        targets = [(item.neuron_id, item.synapse_id) for item in rewards]
        if len(targets) != len(set(targets)):
            raise NetworkJNVMCoordinatorError("reward batch contains duplicate neuron/synapse targets")
        with self._lock:
            self._require_writable()
            candidate = deepcopy(self._runtime)
            updates = []
            changed_neurons = set()
            for item in rewards:
                update = candidate.apply_reward(
                    item.neuron_id,
                    item.synapse_id,
                    item.time_ns,
                    item.reward_value,
                    item.baseline_before,
                    item.learning_context_id,
                )
                updates.append((item.neuron_id, item.synapse_id, update))
                changed_neurons.add(item.neuron_id)
            saved = self._commit_candidate(candidate, tuple(sorted(changed_neurons)))
            return CoordinatedRewardBatch(tuple(updates), saved)

    def apply_reward(
        self,
        neuron_id: str,
        synapse_id: str,
        time_ns: int,
        reward_value: float,
        baseline_before: float,
        learning_context_id: str | None,
    ) -> CoordinatedReward:
        """Persist a reward-driven neuron/synapse mutation before publishing it."""
        with self._lock:
            self._require_writable()
            candidate = deepcopy(self._runtime)
            try:
                update = candidate.apply_reward(
                    neuron_id, synapse_id, time_ns, reward_value,
                    baseline_before, learning_context_id,
                )
            except NetworkRuntimeError:
                raise
            saved = self._commit_candidate(candidate, (neuron_id,))
            return CoordinatedReward(update=update, commit=saved)

    def run_next_timestamp(
        self,
        *,
        learning_context_id: str | None = None,
        max_events: int | None = None,
    ) -> CoordinatedNetworkRun:
        """Process no more than one complete timestamp, then atomically persist it."""
        with self._lock:
            self._require_writable()
            before = self._runtime.export_checkpoint_state()
            candidate = deepcopy(self._runtime)
            result = candidate.run(
                learning_context_id=learning_context_id,
                max_events=max_events,
                max_timestamps=1,
            )
            if result.processed_events == 0:
                return CoordinatedNetworkRun(run=result, commit=None)

            after = candidate.export_checkpoint_state()
            changed = tuple(sorted(
                neuron_id
                for neuron_id, runtime in after["neurons"].items()
                if runtime.processed_batches > before["neurons"][neuron_id].processed_batches
            ))
            if not changed:
                raise NetworkJNVMCoordinatorError(
                    "scheduler advanced but no neuron state was marked as changed"
                )
            saved = self._commit_candidate(candidate, changed)
            return CoordinatedNetworkRun(run=result, commit=saved)


    def reload_after_conflict(
        self,
        reopened_store: JPVRNetworkJNVMStore,
        *,
        max_pages: int = 4096,
    ) -> None:
        """Reload a fresh coherent snapshot after a definite CAS/version conflict."""
        with self._lock:
            if self._uncertain is not None:
                raise NetworkJNVMCoordinatorFrozenError(
                    "use reconcile_after_reopen for an uncertain publication"
                )
            if not self._stale:
                raise NetworkJNVMCoordinatorError(
                    "there is no stale coordinator snapshot to reload"
                )
            if not isinstance(reopened_store, JPVRNetworkJNVMStore):
                raise NetworkJNVMCoordinatorError(
                    "reopened_store must be a JPVRNetworkJNVMStore"
                )
            if reopened_store.storage is self._store.storage:
                raise NetworkJNVMCoordinatorError(
                    "reload requires a newly reopened JPVRStorage handle"
                )
            if reopened_store.storage.path != self._store.storage.path:
                raise NetworkJNVMCoordinatorError(
                    "reopened JPVR store does not point to the same container path"
                )
            try:
                restored = reopened_store.restore_network(
                    self._network_id, max_pages=max_pages,
                )
            except Exception as exc:
                raise NetworkJNVMReconciliationError(
                    "reopened JPVR state is not a coherent network snapshot"
                ) from exc
            self._store = reopened_store
            self._runtime = deepcopy(restored.runtime)
            self._network_record_version = restored.network_record_version
            self._neuron_record_versions = dict(restored.neuron_record_versions)
            self._commit_id = bytes(restored.commit_id)
            self._generation = restored.generation
            self._clock_epoch_id = bytes(restored.clock_epoch_id)
            self._stale = False

    def reconcile_after_reopen(
        self,
        reopened_store: JPVRNetworkJNVMStore,
        *,
        max_pages: int = 4096,
    ) -> str:
        """Adopt only a proven committed or not-committed outcome after reopen.

        Returns COMMITTED or NOT_COMMITTED. Any different/newer state remains
        fail-closed and requires an explicit higher-level recovery decision.
        """
        with self._lock:
            uncertain = self._uncertain
            if uncertain is None:
                raise NetworkJNVMCoordinatorError(
                    "there is no uncertain publication to reconcile"
                )
            if not isinstance(reopened_store, JPVRNetworkJNVMStore):
                raise NetworkJNVMCoordinatorError(
                    "reopened_store must be a JPVRNetworkJNVMStore"
                )
            if reopened_store.storage is self._store.storage:
                raise NetworkJNVMCoordinatorError(
                    "reconciliation requires a newly reopened JPVRStorage handle"
                )
            if reopened_store.storage.path != self._store.storage.path:
                raise NetworkJNVMCoordinatorError(
                    "reopened JPVR store does not point to the same container path"
                )

            try:
                restored = reopened_store.restore_network(
                    self._network_id, max_pages=max_pages,
                )
            except Exception as exc:
                raise NetworkJNVMReconciliationError(
                    "reopened JPVR state could not be validated; coordinator remains frozen"
                ) from exc

            if (
                restored.commit_id == uncertain.commit_id
                and restored.generation == uncertain.generation
            ):
                outcome = "COMMITTED"
            else:
                observed = _CommittedIdentity(
                    network_record_version=restored.network_record_version,
                    neuron_record_versions=tuple(sorted(restored.neuron_record_versions.items())),
                    commit_id=bytes(restored.commit_id),
                    generation=restored.generation,
                )
                if observed != uncertain.previous:
                    raise NetworkJNVMReconciliationError(
                        "reopened JPVR snapshot is neither the uncertain commit nor the exact prior commit"
                    )
                outcome = "NOT_COMMITTED"

            # Both outcomes are authoritative only after restoring one coherent snapshot.
            self._store = reopened_store
            self._runtime = deepcopy(restored.runtime)
            self._network_record_version = restored.network_record_version
            self._neuron_record_versions = dict(restored.neuron_record_versions)
            self._clock_epoch_id = bytes(restored.clock_epoch_id)
            self._commit_id = bytes(restored.commit_id)
            self._generation = restored.generation
            self._uncertain = None
            self._stale = False
            return outcome
