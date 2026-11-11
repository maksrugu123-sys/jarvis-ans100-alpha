"""Regressions for atomic JPVR NetworkRuntime checkpoint save/load."""
import os
import tempfile
import unittest

from tools.jpvr_reference.codec import PAGE_SIZE, PageType, decode_page
from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.jpvr_store import JPVRNeuronCheckpointStore
from tools.neuron_runtime.network import NetworkConnection, NetworkRuntime
from tools.neuron_runtime.network_store import (
    JPVRNetworkCheckpointConflict,
    JPVRNetworkCheckpointNotFound,
    JPVRNetworkCheckpointStore,
    NetworkCheckpointDescriptor,
    decode_network_checkpoint_descriptor,
    encode_network_checkpoint_descriptor,
)
from tools.neuron_runtime.runtime import NeuronRuntime


CLOCK_EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def _network():
    runtime = NetworkRuntime(
        {
            "source": NeuronRuntime({"external": 1.0}),
            "target": NeuronRuntime({"from-source": 1.0}),
        },
        [NetworkConnection("source-target", "source", "target", "from-source", 5)],
        max_pending_events=12,
        max_events_per_run=6,
    )
    runtime.schedule_input(0, "source", "external", "pulse", 1.0)
    runtime.run()
    return runtime


class NetworkCheckpointDescriptorTests(unittest.TestCase):
    def test_descriptor_round_trip(self):
        descriptor = NetworkCheckpointDescriptor(
            bytes.fromhex("11111111111111111111111111111111"),
            4, 12, 3, 5, 8192, 0x12345678,
        )
        encoded = encode_network_checkpoint_descriptor(descriptor)
        self.assertEqual(decode_network_checkpoint_descriptor(encoded), descriptor)

    def test_rejects_unknown_version(self):
        descriptor = NetworkCheckpointDescriptor(
            bytes.fromhex("11111111111111111111111111111111"),
            1, 12, 1, 2, 100, 1,
        )
        encoded = bytearray(encode_network_checkpoint_descriptor(descriptor))
        encoded[4:6] = (2).to_bytes(2, "little")
        with self.assertRaises(ValueError):
            decode_network_checkpoint_descriptor(encoded)

    def test_rejects_zero_object_id(self):
        descriptor = NetworkCheckpointDescriptor(bytes(16), 1, 12, 1, 2, 100, 1)
        with self.assertRaises(ValueError):
            encode_network_checkpoint_descriptor(descriptor)


class JPVRNetworkCheckpointStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "network.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRNetworkCheckpointStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def test_first_save_initializes_indexes_and_commits_complete_network(self):
        network = _network()
        saved = self.store.save("main-network", network, clock_epoch_id=CLOCK_EPOCH)
        self.assertTrue(saved.initialized_indexes)
        self.assertEqual(saved.record_version, 1)
        self.assertEqual(saved.previous_record_version, 0)
        page = decode_page(self.storage.read_page(saved.checkpoint_page_no))
        self.assertIs(page.page_type, PageType.CHECKPOINT)
        loaded = self.store.load("main-network")
        self.assertEqual(loaded.snapshot.clock_epoch_id, CLOCK_EPOCH)
        self.assertEqual(loaded.snapshot.runtime.neuron_ids, network.neuron_ids)
        self.assertEqual(loaded.snapshot.runtime.connections, network.connections)
        self.assertEqual(loaded.snapshot.runtime.pending_events, network.pending_events)
        self.assertEqual(loaded.snapshot.runtime.current_time_ns, network.current_time_ns)
        self.assertEqual(loaded.source_commit_page_no, saved.commit_page_no)

    def test_small_network_snapshot_uses_a_data_page(self):
        network = NetworkRuntime({"small": NeuronRuntime({})}, [])
        saved = self.store.save("small-network", network, clock_epoch_id=CLOCK_EPOCH)
        loaded = self.store.load("small-network")
        self.assertEqual(loaded.descriptor.storage_kind, "DATA")
        self.assertEqual(loaded.snapshot.runtime.neuron_ids, ("small",))
        self.assertEqual(saved.record_version, loaded.descriptor.record_version)

    def test_checkpoint_survives_file_reopen(self):
        self.store.save("restartable", _network(), clock_epoch_id=CLOCK_EPOCH)
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        loaded = JPVRNetworkCheckpointStore(reopened).load("restartable")
        self.assertEqual(loaded.snapshot.runtime.pending_events, 1)
        self.assertEqual(loaded.snapshot.clock_epoch_id, CLOCK_EPOCH)

    def test_record_version_increments_and_compare_and_swap_rejects_stale_writer(self):
        first = self.store.save("versioned", _network(), clock_epoch_id=CLOCK_EPOCH)
        second = self.store.save(
            "versioned", _network(), clock_epoch_id=CLOCK_EPOCH,
            expected_record_version=first.record_version,
        )
        self.assertEqual(second.record_version, 2)
        self.assertEqual(second.previous_record_version, 1)
        with self.assertRaises(JPVRNetworkCheckpointConflict):
            self.store.save(
                "versioned", _network(), clock_epoch_id=CLOCK_EPOCH,
                expected_record_version=1,
            )

    def test_active_checkpoint_is_preserved_by_later_neuron_checkpoint_commit(self):
        self.store.save("persistent-network", _network(), clock_epoch_id=CLOCK_EPOCH)
        neuron_store = JPVRNeuronCheckpointStore(self.storage)
        neuron_store.save(
            "independent-neuron", NeuronRuntime({"input": 0.5}),
            clock_epoch_id=CLOCK_EPOCH,
        )
        loaded = self.store.load("persistent-network")
        self.assertEqual(loaded.snapshot.network_id, "persistent-network")
        self.assertEqual(loaded.snapshot.runtime.pending_events, 1)

    def test_wrong_network_id_does_not_return_another_network_checkpoint(self):
        self.store.save("one-network", _network(), clock_epoch_id=CLOCK_EPOCH)
        with self.assertRaises(JPVRNetworkCheckpointNotFound):
            self.store.load("different-network")

    def test_missing_checkpoint_is_reported(self):
        with self.assertRaises(JPVRNetworkCheckpointNotFound):
            self.store.load("empty")

    def test_small_genesis_catalog_bound_refuses_before_schema_commit(self):
        with self.assertRaises(ValueError):
            self.store.save(
                "too-small", _network(), clock_epoch_id=CLOCK_EPOCH, max_pages=8
            )
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_network_snapshot_is_restored_and_pending_events_continue(self):
        self.store.save("continue", _network(), clock_epoch_id=CLOCK_EPOCH)
        loaded = self.store.load("continue")
        result = loaded.snapshot.runtime.run()
        self.assertEqual(result.processed_events, 1)
        self.assertEqual(result.pending_events, 0)
        self.assertEqual(result.current_time_ns, 5)

    def test_corrupt_blob_page_is_rejected(self):
        saved = self.store.save("corrupt-blob", _network(), clock_epoch_id=CLOCK_EPOCH)
        fd = os.open(self.path, os.O_RDWR)
        try:
            offset = saved.first_blob_page_no * PAGE_SIZE + 80
            byte = os.pread(fd, 1, offset)
            os.pwrite(fd, bytes([byte[0] ^ 0x01]), offset)
            os.fsync(fd)
        finally:
            os.close(fd)
        with self.assertRaises(ValueError):
            self.store.load("corrupt-blob")

    def test_checkpoint_descriptor_must_match_source_commit_and_blob(self):
        saved = self.store.save("source-check", _network(), clock_epoch_id=CLOCK_EPOCH)
        loaded = self.store.load("source-check")
        self.assertEqual(loaded.descriptor.record_generation, saved.generation)
        self.assertEqual(loaded.descriptor.first_blob_page_no, saved.first_blob_page_no)
        self.assertEqual(loaded.descriptor.record_version, saved.record_version)

    def test_invalid_runtime_does_not_initialize_genesis_indexes(self):
        with self.assertRaises(ValueError):
            self.store.save("invalid", object(), clock_epoch_id=CLOCK_EPOCH)
        self.assertEqual(self.storage.get_superblock().generation, 0)


if __name__ == "__main__":
    unittest.main()
