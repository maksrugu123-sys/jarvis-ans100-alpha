"""Tests for fail-closed timestamp execution through the atomic JNVM group store."""
from __future__ import annotations

import os
import tempfile
import unittest
from unittest.mock import patch

from tools.jpvr_reference.storage import JPVRCommitUncertainError, JPVRStorage
from tools.neuron_runtime.network import NetworkConnection, NetworkRuntime
from tools.neuron_runtime.network_jnvm_coordinator import (
    NetworkJNVMCoordinator,
    NetworkJNVMCoordinatorFrozenError,
)
from tools.neuron_runtime.network_jnvm_store import JPVRNetworkJNVMConflict, JPVRNetworkJNVMStore
from tools.neuron_runtime.runtime import NeuronRuntime

_NETWORK_ID = "coordinator-test-network"
_EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def _network(*, queue_input: bool) -> NetworkRuntime:
    runtime = NetworkRuntime(
        {
            "source": NeuronRuntime({"external": 1.0}),
            "source-b": NeuronRuntime({"external": 1.0}),
            "target": NeuronRuntime({"from-source": 1.0, "from-source-b": 1.0}),
        },
        (
            NetworkConnection(
                "source-target",
                "source",
                "target",
                "from-source",
                delay_ns=5,
            ),
            NetworkConnection(
                "source-b-target",
                "source-b",
                "target",
                "from-source-b",
                delay_ns=5,
            ),
        ),
        max_pending_events=32,
        max_events_per_run=16,
    )
    if queue_input:
        runtime.schedule_input(0, "source", "external", "input-0", 1.0)
        runtime.schedule_input(0, "source-b", "external", "input-1", 1.0)
    return runtime


def _initial_checkpoint(store: JPVRNetworkJNVMStore, *, queue_input: bool):
    runtime = _network(queue_input=queue_input)
    saved = store.save_timestamp(
        _NETWORK_ID,
        runtime,
        runtime.neuron_ids,
        clock_epoch_id=_EPOCH,
        expected_network_record_version=0,
        expected_neuron_record_versions={key: 0 for key in runtime.neuron_ids},
    )
    return runtime, saved


class NetworkJNVMCoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.directory.name, "network.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRNetworkJNVMStore(self.storage)
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(self.storage.close)

    def test_each_timestamp_is_one_group_commit_and_publishes_after_durability(self):
        _initial_checkpoint(self.store, queue_input=True)
        coordinator = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)

        first = coordinator.run_next_timestamp()
        self.assertEqual(first.run.processed_events, 2)
        self.assertEqual(first.run.processed_batches, 2)
        self.assertEqual(first.run.pending_events, 2)
        self.assertIsNotNone(first.commit)
        self.assertEqual(first.commit.neuron_record_versions, {"source": 2, "source-b": 2, "target": 1})

        first_restored = self.store.restore_network(_NETWORK_ID)
        self.assertEqual(first_restored.commit_id, first.commit.commit_id)
        self.assertEqual(first_restored.runtime.current_time_ns, 0)
        self.assertEqual(first_restored.runtime.pending_events, 2)
        self.assertEqual(
            self.store.load_scheduler(_NETWORK_ID).locator.commit_id,
            self.store.load_neuron("source").locator.commit_id,
        )

        second = coordinator.run_next_timestamp()
        self.assertEqual(second.run.processed_events, 2)
        self.assertEqual(second.run.processed_batches, 1)
        self.assertEqual(second.run.pending_events, 0)
        self.assertEqual(second.commit.neuron_record_versions, {"source": 2, "source-b": 2, "target": 2})

        final = self.store.restore_network(_NETWORK_ID)
        self.assertEqual(final.runtime.pending_events, 0)
        self.assertEqual(final.runtime.current_time_ns, 5)
        self.assertEqual(dict(final.neuron_record_versions), {"source": 2, "source-b": 2, "target": 2})
        self.assertEqual(final.runtime.neuron_states["source"].spike_sequence, 1)
        self.assertEqual(final.runtime.neuron_states["source-b"].spike_sequence, 1)
        self.assertEqual(final.runtime.neuron_states["target"].spike_sequence, 1)

    def test_external_input_is_committed_before_it_is_reported_accepted(self):
        _initial_checkpoint(self.store, queue_input=False)
        coordinator = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)

        saved = coordinator.schedule_input(
            0, "source", "external", "durable-input", 1.0,
        )

        self.assertEqual(saved.network_record_version, 2)
        self.assertEqual(saved.neuron_record_versions, {"source": 1, "source-b": 1, "target": 1})
        reopened = self.store.restore_network(_NETWORK_ID)
        self.assertEqual(reopened.runtime.pending_events, 1)
        self.assertEqual(reopened.runtime.current_time_ns, 0)
        self.assertEqual(reopened.runtime.export_checkpoint_state()["pending_events"][0].event_id,
                         "durable-input")

    def test_storage_failure_before_commit_does_not_publish_candidate_memory(self):
        _initial_checkpoint(self.store, queue_input=True)
        coordinator = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)
        before = coordinator.status()
        with patch.object(self.storage, "commit", side_effect=OSError("injected disk failure")):
            with self.assertRaises(OSError):
                coordinator.run_next_timestamp()

        after = coordinator.status()
        self.assertEqual(after.network_record_version, before.network_record_version)
        self.assertEqual(after.neuron_record_versions, before.neuron_record_versions)
        self.assertEqual(after.current_time_ns, before.current_time_ns)
        self.assertEqual(after.pending_events, before.pending_events)
        self.assertFalse(after.frozen)
        self.assertFalse(after.stale)
        self.assertEqual(self.store.restore_network(_NETWORK_ID).runtime.pending_events, 2)

        # The exact same queued timestamp remains available for a normal retry.
        retried = coordinator.run_next_timestamp()
        self.assertEqual(retried.run.processed_events, 2)
        self.assertEqual(retried.run.pending_events, 2)

    def test_uncertain_publication_freezes_until_reopen_confirms_commit(self):
        _initial_checkpoint(self.store, queue_input=True)
        coordinator = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)
        original_commit = self.storage.commit

        def commit_then_raise(plan):
            published = original_commit(plan)
            raise JPVRCommitUncertainError(
                plan.assembly.commit_id,
                published.generation,
                "injected uncertainty after Superblock publication",
            )

        with patch.object(self.storage, "commit", side_effect=commit_then_raise):
            with self.assertRaises(JPVRCommitUncertainError):
                coordinator.run_next_timestamp()

        self.assertTrue(coordinator.status().frozen)
        with self.assertRaises(NetworkJNVMCoordinatorFrozenError):
            coordinator.run_next_timestamp()

        reopened_storage = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened_storage.close)
        reopened_store = JPVRNetworkJNVMStore(reopened_storage)
        outcome = coordinator.reconcile_after_reopen(reopened_store)

        self.assertEqual(outcome, "COMMITTED")
        self.assertFalse(coordinator.status().frozen)
        self.assertEqual(coordinator.status().network_record_version, 2)
        self.assertEqual(coordinator.status().pending_events, 2)
        self.assertEqual(reopened_store.restore_network(_NETWORK_ID).runtime.pending_events, 2)

    def test_uncertain_but_unpublished_candidate_reconciles_to_prior_state(self):
        _initial_checkpoint(self.store, queue_input=True)
        coordinator = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)
        original_status = coordinator.status()

        def raise_without_publication(plan):
            raise JPVRCommitUncertainError(
                plan.assembly.commit_id,
                plan.assembly.new_superblock.generation,
                "injected unknown outcome before publication",
            )

        with patch.object(self.storage, "commit", side_effect=raise_without_publication):
            with self.assertRaises(JPVRCommitUncertainError):
                coordinator.run_next_timestamp()

        reopened_storage = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened_storage.close)
        outcome = coordinator.reconcile_after_reopen(
            JPVRNetworkJNVMStore(reopened_storage)
        )

        self.assertEqual(outcome, "NOT_COMMITTED")
        self.assertFalse(coordinator.status().frozen)
        self.assertEqual(coordinator.status().network_record_version,
                         original_status.network_record_version)
        self.assertEqual(coordinator.status().neuron_record_versions,
                         original_status.neuron_record_versions)
        self.assertEqual(coordinator.status().pending_events, 2)

        # The not-committed timestamp may be retried from the restored prior state.
        retry = coordinator.run_next_timestamp()
        self.assertEqual(retry.run.processed_events, 2)
        self.assertEqual(retry.commit.network_record_version, 2)

    def test_empty_queue_is_a_noop_without_a_fabricated_commit(self):
        _initial_checkpoint(self.store, queue_input=False)
        coordinator = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)
        before = coordinator.status()

        result = coordinator.run_next_timestamp()

        self.assertEqual(result.run.processed_events, 0)
        self.assertIsNone(result.commit)
        after = coordinator.status()
        self.assertEqual(after.network_record_version, before.network_record_version)
        self.assertEqual(after.generation, before.generation)


    def test_stale_writer_reloads_only_a_complete_snapshot_from_a_new_handle(self):
        _initial_checkpoint(self.store, queue_input=False)
        first = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)
        second = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)

        second.schedule_input(0, "source", "external", "writer-two", 1.0)
        with self.assertRaises(JPVRNetworkJNVMConflict):
            first.schedule_input(0, "source", "external", "writer-one", 1.0)

        self.assertTrue(first.status().stale)
        self.assertEqual(first.status().pending_events, 0)

        reopened_storage = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened_storage.close)
        reopened_store = JPVRNetworkJNVMStore(reopened_storage)
        first.reload_after_conflict(reopened_store)

        self.assertFalse(first.status().stale)
        self.assertEqual(first.status().network_record_version, 2)
        self.assertEqual(first.status().pending_events, 1)
        self.assertEqual(
            first.runtime_snapshot().export_checkpoint_state()["pending_events"][0].event_id,
            "writer-two",
        )



    def test_reward_update_is_group_committed_before_in_memory_publication(self):
        runtime = NetworkRuntime(
            {"neuron": NeuronRuntime({"pre": 0.5, "drive": 1.0}, learning_rule="rstdp")},
            (),
            max_pending_events=16,
            max_events_per_run=16,
        )
        runtime.schedule_input(0, "neuron", "pre", "pre-event", 0.4)
        runtime.schedule_input(20_000_000, "neuron", "drive", "post-event", 1.0)
        saved = self.store.save_timestamp(
            _NETWORK_ID,
            runtime,
            runtime.neuron_ids,
            clock_epoch_id=_EPOCH,
            expected_network_record_version=0,
            expected_neuron_record_versions={"neuron": 0},
        )
        coordinator = NetworkJNVMCoordinator.restore(self.store, _NETWORK_ID)

        first = coordinator.run_next_timestamp(learning_context_id="episode-1")
        second = coordinator.run_next_timestamp(learning_context_id="episode-1")
        self.assertEqual(first.run.processed_events, 1)
        self.assertEqual(second.run.processed_events, 1)
        self.assertEqual(second.run.pending_events, 0)

        reward = coordinator.apply_reward(
            "neuron", "pre", 20_000_000, 1.0, 0.5, "episode-1",
        )
        self.assertEqual(reward.update.result.status, "APPLIED")
        self.assertGreater(
            coordinator.runtime_snapshot().synapse_states("neuron")["pre"].weight,
            0.5,
        )
        restored = self.store.restore_network(_NETWORK_ID)
        self.assertEqual(restored.network_record_version, saved.network_record_version + 3)
        self.assertGreater(restored.runtime.synapse_states("neuron")["pre"].weight, 0.5)



if __name__ == "__main__":
    unittest.main(verbosity=2)
