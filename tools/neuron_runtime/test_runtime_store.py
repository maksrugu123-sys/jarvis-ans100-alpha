"""Tests for one-commit NetworkRuntime + RewardBaselineManager checkpoints."""
import os
import tempfile
import unittest

from tools.jpvr_reference.codec import PAGE_SIZE, PageType, decode_page
from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.network import NetworkRuntime
from tools.neuron_runtime.runtime import NeuronRuntime
from tools.neuron_runtime.reward_baseline import RewardBaselineManager
from tools.neuron_runtime.runtime_store import (
    JPVRRuntimeCheckpointConflict,
    JPVRRuntimeCheckpointError,
    JPVRRuntimeCheckpointNotFound,
    JPVRRuntimeCheckpointStore,
    RuntimeCheckpointDescriptor,
    decode_runtime_checkpoint_descriptor,
    encode_runtime_checkpoint_descriptor,
)


EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def _network():
    network = NetworkRuntime(
        {"node": NeuronRuntime({"input": 0.5})},
        [],
        max_pending_events=8,
        max_events_per_run=4,
    )
    network.schedule_input(5, "node", "input", "event-1", 1.0)
    return network


def _manager(manager_id="runtime-manager"):
    return RewardBaselineManager(
        manager_id=manager_id,
        clock_epoch_id=EPOCH,
        initial_baseline=0.5,
        beta_b=0.1,
    )


class RuntimeCheckpointDescriptorTests(unittest.TestCase):
    def test_composite_descriptor_round_trip(self):
        store = _new_store()
        try:
            saved = store.save("net", _network(), _manager())
            loaded = store.load("net", "runtime-manager")
            encoded = encode_runtime_checkpoint_descriptor(loaded.descriptor)
            self.assertEqual(decode_runtime_checkpoint_descriptor(encoded), loaded.descriptor)
            self.assertEqual(saved.runtime_version, 1)
        finally:
            store.storage.close()
            store.temp.cleanup()

    def test_unknown_composite_version_is_rejected(self):
        store = _new_store()
        try:
            saved = store.save("net", _network(), _manager())
            loaded = store.load("net", "runtime-manager")
            encoded = bytearray(encode_runtime_checkpoint_descriptor(loaded.descriptor))
            encoded[4:6] = (2).to_bytes(2, "little")
            with self.assertRaises(ValueError):
                decode_runtime_checkpoint_descriptor(encoded)
        finally:
            store.storage.close()
            store.temp.cleanup()


def _new_store():
    temp = tempfile.TemporaryDirectory()
    path = os.path.join(temp.name, "runtime.jpvr")
    storage = JPVRStorage.create(path)
    store = JPVRRuntimeCheckpointStore(storage)
    store.temp = temp
    return store


class JPVRRuntimeCheckpointStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "runtime.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRRuntimeCheckpointStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def test_first_save_commits_network_and_manager_together(self):
        network = _network()
        manager = _manager()
        manager.prepare_feedback("task", "feedback-1", 1, 0.9, 5, clock_epoch_id=EPOCH)
        saved = self.store.save("main-network", network, manager)
        self.assertTrue(saved.initialized_indexes)
        self.assertEqual(saved.runtime_version, 1)
        page = decode_page(self.storage.read_page(saved.checkpoint_page_no))
        self.assertIs(page.page_type, PageType.CHECKPOINT)
        loaded = self.store.load("main-network", manager.manager_id)
        self.assertEqual(loaded.snapshot.runtime.neuron_ids, network.neuron_ids)
        self.assertEqual(loaded.snapshot.runtime.pending_events, network.pending_events)
        self.assertEqual(
            loaded.reward_baseline_manager.export_checkpoint_state(),
            manager.export_checkpoint_state(),
        )
        self.assertEqual(loaded.source_commit_page_no, saved.commit_page_no)
        self.assertEqual(
            loaded.descriptor.network.record_generation,
            loaded.descriptor.reward_baseline.record_generation,
        )

    def test_checkpoint_reopens_with_pending_network_events_and_feedback(self):
        network = _network()
        manager = _manager()
        proposal = manager.prepare_feedback(
            "task", "feedback-pending", 1, 0.8, 5, clock_epoch_id=EPOCH
        )
        self.store.save("restartable", network, manager)
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        loaded = JPVRRuntimeCheckpointStore(reopened).load("restartable", manager.manager_id)
        self.assertEqual(loaded.snapshot.runtime.pending_events, 1)
        self.assertEqual(
            loaded.reward_baseline_manager.get_record("task").pending, proposal
        )

    def test_runtime_version_cas_rejects_stale_writer(self):
        first = self.store.save("versioned", _network(), _manager())
        second = self.store.save(
            "versioned", _network(), _manager(),
            expected_runtime_version=first.runtime_version,
        )
        self.assertEqual(second.runtime_version, 2)
        self.assertEqual(second.network_record_version, 2)
        self.assertEqual(second.reward_baseline_record_version, 2)
        with self.assertRaises(JPVRRuntimeCheckpointConflict):
            self.store.save(
                "versioned", _network(), _manager(), expected_runtime_version=1
            )

    def test_identity_mismatch_never_loads_another_runtime(self):
        self.store.save("network-a", _network(), _manager("manager-a"))
        with self.assertRaises(JPVRRuntimeCheckpointNotFound):
            self.store.load("network-b", "manager-a")
        with self.assertRaises(JPVRRuntimeCheckpointNotFound):
            self.store.load("network-a", "manager-b")

    def test_non_composite_checkpoint_cannot_be_replaced(self):
        from tools.neuron_runtime.network_store import JPVRNetworkCheckpointStore
        network_store = JPVRNetworkCheckpointStore(self.storage)
        network_store.save("legacy-network", _network(), clock_epoch_id=EPOCH)
        generation = self.storage.get_superblock().generation
        with self.assertRaises(JPVRRuntimeCheckpointConflict):
            self.store.save("legacy-network", _network(), _manager())
        self.assertEqual(self.storage.get_superblock().generation, generation)

    def test_small_manager_data_record_round_trips(self):
        saved = self.store.save("small", _network(), _manager())
        loaded = self.store.load("small", "runtime-manager")
        self.assertEqual(loaded.descriptor.reward_baseline.storage_kind, "DATA")
        self.assertEqual(loaded.reward_baseline_manager.task_count, 0)
        self.assertGreaterEqual(saved.checkpoint_page_no, 2)

    def test_corrupted_component_page_is_rejected(self):
        saved = self.store.save("corrupt", _network(), _manager())
        descriptor = self.store.load("corrupt", "runtime-manager").descriptor
        page_no = descriptor.reward_baseline.first_blob_page_no
        fd = os.open(self.path, os.O_RDWR)
        try:
            offset = page_no * PAGE_SIZE + 80
            byte = os.pread(fd, 1, offset)
            os.pwrite(fd, bytes([byte[0] ^ 0x01]), offset)
            os.fsync(fd)
        finally:
            os.close(fd)
        with self.assertRaises(ValueError):
            self.store.load("corrupt", "runtime-manager")

    def test_component_clock_epoch_is_shared(self):
        manager = _manager()
        network = _network()
        saved = self.store.save("epoch", network, manager)
        self.assertEqual(
            self.store.load("epoch", manager.manager_id).snapshot.clock_epoch_id,
            manager.clock_epoch_id,
        )


    def test_large_baseline_uses_blob_inside_same_runtime_commit(self):
        manager = _manager()
        for index in range(40):
            proposal = manager.prepare_feedback(
                f"task-{index}", f"feedback-{index}", 1, 0.75,
                index + 1, clock_epoch_id=EPOCH
            )
            manager.commit_feedback(proposal, processing_status="NO_ELIGIBILITY")
        saved = self.store.save("mixed-layout", _network(), manager)
        loaded = self.store.load("mixed-layout", manager.manager_id)
        self.assertEqual(loaded.descriptor.reward_baseline.storage_kind, "BLOB")
        self.assertEqual(loaded.descriptor.network.storage_kind, "DATA")
        self.assertEqual(
            loaded.reward_baseline_manager.export_checkpoint_state(),
            manager.export_checkpoint_state()
        )
        self.assertEqual(saved.generation, loaded.descriptor.network.record_generation)

    def test_expected_runtime_version_is_checked_before_commit(self):
        with self.assertRaises(JPVRRuntimeCheckpointConflict):
            self.store.save(
                "empty", _network(), _manager(), expected_runtime_version=3
            )
        self.assertEqual(self.storage.get_superblock().generation, 0)


if __name__ == "__main__":
    unittest.main()
