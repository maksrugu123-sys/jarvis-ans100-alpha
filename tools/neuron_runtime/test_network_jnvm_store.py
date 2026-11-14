"""Tests for atomic one-COMMIT multi-neuron network checkpoints."""
from __future__ import annotations
import os
import tempfile
import unittest
from unittest.mock import patch

from tools.jpvr_reference.storage import JPVRCommitUncertainError, JPVRStorage
from tools.neuron_runtime.network import NetworkConnection, NetworkRuntime
from tools.neuron_runtime.network_jnvm_store import JPVRNetworkJNVMConflict, JPVRNetworkJNVMStore
from tools.neuron_runtime.runtime import NeuronRuntime

EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def make_network():
    runtime = NetworkRuntime(
        {"source": NeuronRuntime({"external": 1.0}), "target": NeuronRuntime({"from-source": 1.0})},
        [NetworkConnection("source-target", "source", "target", "from-source", 5)],
        max_pending_events=32, max_events_per_run=16,
    )
    runtime.schedule_input(0, "source", "external", "pulse", 1.0)
    return runtime


class NetworkJNVMStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "network.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRNetworkJNVMStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def initial_save(self):
        runtime = make_network()
        saved = self.store.save_timestamp(
            "network-A", runtime, runtime.neuron_ids,
            clock_epoch_id=EPOCH,
            expected_network_record_version=0,
            expected_neuron_record_versions={key: 0 for key in runtime.neuron_ids},
        )
        return runtime, saved

    def test_initial_group_commit_restores_scheduler_and_neurons(self):
        runtime, saved = self.initial_save()
        restored = self.store.restore_network("network-A")
        self.assertEqual(saved.generation, 2)
        self.assertEqual(restored.network_record_version, 1)
        self.assertEqual(dict(restored.neuron_record_versions), {"source": 1, "target": 1})
        self.assertEqual(restored.runtime.neuron_ids, runtime.neuron_ids)
        self.assertEqual(restored.runtime.connections, runtime.connections)
        self.assertEqual(restored.runtime.pending_events, runtime.pending_events)
        self.assertEqual(restored.runtime.current_time_ns, runtime.current_time_ns)
        self.assertEqual(restored.clock_epoch_id, EPOCH)

    def test_changed_neuron_and_scheduler_share_one_commit(self):
        runtime, first = self.initial_save()
        runtime.run()
        second = self.store.save_timestamp(
            "network-A", runtime, ("source",), clock_epoch_id=EPOCH,
            expected_network_record_version=first.network_record_version,
            expected_neuron_record_versions={"source": first.neuron_record_versions["source"]},
        )
        scheduler = self.store.load_scheduler("network-A")
        neuron = self.store.load_neuron("source")
        self.assertEqual(second.generation, first.generation + 1)
        self.assertEqual(second.neuron_record_versions, {"source": 2})
        self.assertEqual(scheduler.locator.commit_id, second.commit_id)
        self.assertEqual(neuron.locator.commit_id, second.commit_id)
        self.assertEqual(scheduler.locator.record_generation, neuron.locator.record_generation)
        restored = self.store.restore_network("network-A")
        self.assertEqual(restored.runtime.neuron_states["source"].spike_sequence,
                         runtime.neuron_states["source"].spike_sequence)

    def test_stale_scheduler_version_rejects_without_commit(self):
        runtime, _ = self.initial_save()
        before = self.storage.get_superblock()
        with self.assertRaises(JPVRNetworkJNVMConflict):
            self.store.save_timestamp(
                "network-A", runtime, ("source",), clock_epoch_id=EPOCH,
                expected_network_record_version=0,
                expected_neuron_record_versions={"source": 1},
            )
        self.assertEqual(self.storage.get_superblock(), before)

    def test_stale_neuron_version_rejects_without_commit(self):
        runtime, first = self.initial_save()
        before = self.storage.get_superblock()
        with self.assertRaises(JPVRNetworkJNVMConflict):
            self.store.save_timestamp(
                "network-A", runtime, ("source",), clock_epoch_id=EPOCH,
                expected_network_record_version=first.network_record_version,
                expected_neuron_record_versions={"source": 0},
            )
        self.assertEqual(self.storage.get_superblock(), before)

    def test_topology_change_is_rejected(self):
        _, first = self.initial_save()
        altered = NetworkRuntime(
            {"source": NeuronRuntime({"external": 1.0}), "target": NeuronRuntime({"from-source": 1.0})},
            [NetworkConnection("different-route", "source", "target", "from-source", 5)],
            max_pending_events=32, max_events_per_run=16,
        )
        with self.assertRaises(JPVRNetworkJNVMConflict):
            self.store.save_timestamp(
                "network-A", altered, (), clock_epoch_id=EPOCH,
                expected_network_record_version=first.network_record_version,
                expected_neuron_record_versions={},
            )

    def test_uncertain_commit_is_reconciled_after_reopen(self):
        runtime, first = self.initial_save()
        runtime.run()
        original_commit = self.storage.commit

        def commit_then_raise(plan):
            published = original_commit(plan)
            raise JPVRCommitUncertainError(plan.assembly.commit_id, published.generation,
                                           "injected uncertainty after publication")

        with patch.object(self.storage, "commit", side_effect=commit_then_raise):
            with self.assertRaises(JPVRCommitUncertainError):
                self.store.save_timestamp(
                    "network-A", runtime, ("source",), clock_epoch_id=EPOCH,
                    expected_network_record_version=first.network_record_version,
                    expected_neuron_record_versions={"source": first.neuron_record_versions["source"]},
                )
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        restored = JPVRNetworkJNVMStore(reopened).restore_network("network-A")
        self.assertEqual(restored.network_record_version, 2)
        self.assertEqual(restored.neuron_record_versions["source"], 2)
        self.assertEqual(restored.runtime.neuron_states["source"].spike_sequence,
                         runtime.neuron_states["source"].spike_sequence)


if __name__ == "__main__":
    unittest.main(verbosity=2)
