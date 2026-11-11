"""JPVR checkpoint tests for the R-STDP RewardBaselineManager."""
import os
import tempfile
import unittest

from tools.jpvr_reference.codec import PAGE_SIZE, PageType, decode_page
from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.jpvr_store import JPVRNeuronCheckpointStore
from tools.neuron_runtime.reward_baseline import RewardBaselineManager
from tools.neuron_runtime.reward_baseline_store import (
    JPVRRewardBaselineConflict,
    JPVRRewardBaselineError,
    JPVRRewardBaselineNotFound,
    JPVRRewardBaselineStore,
    RewardBaselineCheckpointDescriptor,
    decode_reward_baseline_checkpoint_descriptor,
    encode_reward_baseline_checkpoint_descriptor,
)
from tools.neuron_runtime.runtime import NeuronRuntime


EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def _manager(manager_id="test-manager"):
    return RewardBaselineManager(
        manager_id=manager_id, clock_epoch_id=EPOCH,
        initial_baseline=0.5, beta_b=0.1,
    )


class RewardBaselineCheckpointDescriptorTests(unittest.TestCase):
    def test_descriptor_round_trip(self):
        descriptor = RewardBaselineCheckpointDescriptor(
            bytes.fromhex("11111111111111111111111111111111"),
            4, 12, 3, 5, 8192, 0x12345678,
        )
        self.assertEqual(
            decode_reward_baseline_checkpoint_descriptor(
                encode_reward_baseline_checkpoint_descriptor(descriptor)
            ),
            descriptor,
        )

    def test_unknown_version_is_rejected(self):
        descriptor = RewardBaselineCheckpointDescriptor(
            bytes.fromhex("11111111111111111111111111111111"),
            1, 12, 1, 2, 100, 1,
        )
        encoded = bytearray(encode_reward_baseline_checkpoint_descriptor(descriptor))
        encoded[4:6] = (2).to_bytes(2, "little")
        with self.assertRaises(ValueError):
            decode_reward_baseline_checkpoint_descriptor(encoded)

    def test_zero_object_id_is_rejected(self):
        descriptor = RewardBaselineCheckpointDescriptor(bytes(16), 1, 12, 1, 2, 100, 1)
        with self.assertRaises(ValueError):
            encode_reward_baseline_checkpoint_descriptor(descriptor)


class JPVRRewardBaselineStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "reward-baseline.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRRewardBaselineStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def test_first_save_publishes_a_complete_checkpoint(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        manager.commit_feedback(proposal, processing_status="APPLIED")
        saved = self.store.save(manager)
        self.assertTrue(saved.initialized_indexes)
        self.assertEqual(saved.record_version, 1)
        page = decode_page(self.storage.read_page(saved.checkpoint_page_no))
        self.assertIs(page.page_type, PageType.CHECKPOINT)
        loaded = self.store.load("test-manager")
        self.assertEqual(
            loaded.manager.export_checkpoint_state(), manager.export_checkpoint_state()
        )
        self.assertEqual(loaded.source_commit_page_no, saved.commit_page_no)

    def test_pending_feedback_survives_save_and_restart(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-pending", 1, 0.9, 10, clock_epoch_id=EPOCH)
        self.store.save(manager)
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        restored = JPVRRewardBaselineStore(reopened).load("test-manager").manager
        self.assertEqual(restored.get_record("task").pending, proposal)
        retry = restored.prepare_feedback(
            "task", "f-pending", 1, 0.9, 10, clock_epoch_id=EPOCH
        )
        restored.commit_feedback(retry, processing_status="APPLIED")
        self.assertEqual(restored.get_record("task").baseline_version, 1)

    def test_record_version_cas_and_stale_writer_conflict(self):
        manager = _manager()
        first = self.store.save(manager)
        proposal = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        manager.commit_feedback(proposal, processing_status="APPLIED")
        second = self.store.save(manager, expected_record_version=first.record_version)
        self.assertEqual(second.record_version, 2)
        with self.assertRaises(JPVRRewardBaselineConflict):
            self.store.save(manager, expected_record_version=1)

    def test_active_reward_checkpoint_survives_later_neuron_commit(self):
        self.store.save(_manager())
        JPVRNeuronCheckpointStore(self.storage).save(
            "independent-neuron", NeuronRuntime({"input": 0.5}),
            clock_epoch_id=EPOCH,
        )
        loaded = self.store.load("test-manager")
        self.assertEqual(loaded.manager.manager_id, "test-manager")

    def test_wrong_manager_id_does_not_return_other_checkpoint(self):
        self.store.save(_manager("manager-a"))
        with self.assertRaises(JPVRRewardBaselineNotFound):
            self.store.load("manager-b")

    def test_missing_checkpoint_is_reported(self):
        with self.assertRaises(JPVRRewardBaselineNotFound):
            self.store.load("empty")

    def test_different_manager_cannot_replace_active_pointer(self):
        self.store.save(_manager("manager-a"))
        with self.assertRaises(JPVRRewardBaselineConflict):
            self.store.save(_manager("manager-b"))

    def test_small_genesis_bound_fails_before_index_commit(self):
        with self.assertRaises(ValueError):
            self.store.save(_manager(), max_pages=8)
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_invalid_input_does_not_initialize_genesis_indexes(self):
        with self.assertRaises(ValueError):
            self.store.save(object())
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_corrupt_blob_page_is_rejected(self):
        saved = self.store.save(_manager())
        fd = os.open(self.path, os.O_RDWR)
        try:
            offset = saved.first_blob_page_no * PAGE_SIZE + 80
            byte = os.pread(fd, 1, offset)
            os.pwrite(fd, bytes([byte[0] ^ 0x01]), offset)
            os.fsync(fd)
        finally:
            os.close(fd)
        with self.assertRaises(ValueError):
            self.store.load("test-manager")

    def test_source_commit_and_blob_metadata_match_descriptor(self):
        saved = self.store.save(_manager())
        loaded = self.store.load("test-manager")
        self.assertEqual(loaded.descriptor.record_generation, saved.generation)
        self.assertEqual(loaded.descriptor.first_blob_page_no, saved.first_blob_page_no)
        self.assertEqual(loaded.descriptor.record_version, saved.record_version)

    def test_page_catalog_bound_is_enforced(self):
        manager = _manager()
        self.store.save(manager)
        with self.assertRaises(JPVRRewardBaselineError):
            self.store.load("test-manager", max_pages=8)


if __name__ == "__main__":
    unittest.main()
