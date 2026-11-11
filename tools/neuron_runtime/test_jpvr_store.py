"""Regressions for atomic, indexed one-neuron JPVR checkpoint persistence."""
import os
import tempfile
import unittest
from dataclasses import replace

from tools.jpvr_reference.codec import PAGE_SIZE, JPVRFormatError
from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.jpvr_store import (
    JPVRNeuronCheckpointConflict,
    JPVRNeuronCheckpointNotFound,
    JPVRNeuronCheckpointStore,
    LOCATOR_DATA,
    LOCATOR_BLOB,
    NeuronCheckpointLocator,
    decode_neuron_checkpoint_locator,
    encode_neuron_checkpoint_locator,
)
from tools.neuron_runtime.runtime import NeuronRuntime, SynapticInput
from tools.numerical_oracle.jarvis_oracle import SynapseState


CLOCK_EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def _runtime(*, synapse_count=1, learning_rule="none"):
    synapses = {
        f"input-{index:03d}" + ("x" * 100 if synapse_count > 1 else ""):
            SynapseState(weight=0.5 + (index % 10) / 100.0)
        for index in range(synapse_count)
    }
    return NeuronRuntime(synapses, learning_rule=learning_rule)


class NeuronCheckpointLocatorTests(unittest.TestCase):
    def test_fixed_locator_round_trip(self):
        locator = NeuronCheckpointLocator(
            storage_kind=LOCATOR_DATA,
            record_type=0x0101,
            schema_id=0x4A4E564D,
            schema_version=1,
            object_id=bytes.fromhex("11111111111111111111111111111111"),
            record_version=7,
            first_page_no=12,
            page_count=1,
            record_generation=4,
            commit_page_no=14,
            commit_id=bytes.fromhex("22222222222222222222222222222222"),
            transaction_id=bytes.fromhex("33333333333333333333333333333333"),
        )
        encoded = encode_neuron_checkpoint_locator(locator)
        self.assertEqual(len(encoded), 100)
        self.assertEqual(decode_neuron_checkpoint_locator(encoded), locator)

    def test_rejects_unknown_wire_version(self):
        locator = NeuronCheckpointLocator(
            LOCATOR_BLOB, 0x0101, 0x4A4E564D, 1,
            bytes.fromhex("11111111111111111111111111111111"),
            1, 12, 2, 4, 15,
            bytes.fromhex("22222222222222222222222222222222"),
            bytes.fromhex("33333333333333333333333333333333"),
        )
        encoded = bytearray(encode_neuron_checkpoint_locator(locator))
        encoded[4:6] = (2).to_bytes(2, "little")
        with self.assertRaises(ValueError):
            decode_neuron_checkpoint_locator(encoded)

    def test_rejects_zero_object_identity(self):
        locator = NeuronCheckpointLocator(
            LOCATOR_DATA, 0x0101, 0x4A4E564D, 1, bytes(16),
            1, 12, 1, 4, 14,
            bytes.fromhex("22222222222222222222222222222222"),
            bytes.fromhex("33333333333333333333333333333333"),
        )
        with self.assertRaises(ValueError):
            encode_neuron_checkpoint_locator(locator)

    def test_rejects_commit_page_inside_checkpoint_range(self):
        locator = NeuronCheckpointLocator(
            LOCATOR_BLOB, 0x0101, 0x4A4E564D, 1,
            bytes.fromhex("11111111111111111111111111111111"),
            1, 12, 3, 4, 14,
            bytes.fromhex("22222222222222222222222222222222"),
            bytes.fromhex("33333333333333333333333333333333"),
        )
        with self.assertRaises(ValueError):
            encode_neuron_checkpoint_locator(locator)


class JPVRNeuronCheckpointStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "jarvis.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)
        self.checkpoints = JPVRNeuronCheckpointStore(self.storage)

    def test_small_catalog_limit_does_not_publish_partial_index_initialization(self):
        with self.assertRaises(JPVRFormatError):
            self.checkpoints.save(
                "bounded-genesis", _runtime(),
                clock_epoch_id=CLOCK_EPOCH, max_pages=8,
            )
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_first_save_initializes_indexes_and_commits_checkpoint(self):
        runtime = _runtime()
        saved = self.checkpoints.save(
            "neuron-A", runtime, clock_epoch_id=CLOCK_EPOCH
        )
        self.assertTrue(saved.initialized_indexes)
        self.assertEqual(saved.generation, 2)
        self.assertEqual(saved.previous_record_version, 0)
        self.assertEqual(saved.record_version, 1)
        self.assertEqual(saved.storage_kind, "DATA")
        loaded = self.checkpoints.load("neuron-A")
        self.assertEqual(loaded.snapshot.neuron_id, "neuron-A")
        self.assertEqual(loaded.snapshot.clock_epoch_id, CLOCK_EPOCH)
        self.assertEqual(loaded.snapshot.runtime.synapses, runtime.synapses)
        self.assertEqual(loaded.locator.commit_id, saved.commit_id)
        self.assertEqual(loaded.locator.storage_kind, LOCATOR_DATA)

    def test_load_after_reopening_container(self):
        runtime = _runtime()
        self.checkpoints.save("persistent", runtime, clock_epoch_id=CLOCK_EPOCH)
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        restored = JPVRNeuronCheckpointStore(reopened).load("persistent")
        self.assertEqual(restored.snapshot.runtime.synapses, runtime.synapses)
        self.assertEqual(restored.snapshot.clock_epoch_id, CLOCK_EPOCH)

    def test_update_advances_record_version_and_publishes_new_locator(self):
        first = self.checkpoints.save(
            "neuron-A", _runtime(), clock_epoch_id=CLOCK_EPOCH
        )
        updated_runtime = NeuronRuntime({"input-000": SynapseState(weight=0.81)})
        second = self.checkpoints.save(
            "neuron-A",
            updated_runtime,
            clock_epoch_id=CLOCK_EPOCH,
            expected_record_version=1,
        )
        self.assertEqual(second.previous_record_version, 1)
        self.assertEqual(second.record_version, 2)
        self.assertGreater(second.generation, first.generation)
        loaded = self.checkpoints.load("neuron-A")
        self.assertEqual(loaded.locator.record_version, 2)
        self.assertEqual(loaded.snapshot.runtime.synapses, updated_runtime.synapses)

    def test_expected_version_conflict_does_not_commit(self):
        self.checkpoints.save("neuron-A", _runtime(), clock_epoch_id=CLOCK_EPOCH)
        before = self.storage.get_superblock()
        with self.assertRaises(JPVRNeuronCheckpointConflict):
            self.checkpoints.save(
                "neuron-A",
                _runtime(),
                clock_epoch_id=CLOCK_EPOCH,
                expected_record_version=0,
            )
        self.assertEqual(self.storage.get_superblock(), before)

    def test_large_checkpoint_uses_blob_and_restores_all_synapses(self):
        runtime = _runtime(synapse_count=48)
        saved = self.checkpoints.save(
            "large-neuron", runtime, clock_epoch_id=CLOCK_EPOCH
        )
        self.assertEqual(saved.storage_kind, "BLOB")
        self.assertGreater(saved.page_count, 1)
        loaded = self.checkpoints.load("large-neuron")
        self.assertEqual(loaded.locator.storage_kind, LOCATOR_BLOB)
        self.assertEqual(loaded.locator.page_count, saved.page_count)
        self.assertEqual(loaded.snapshot.runtime.synapses, runtime.synapses)

    def test_unknown_neuron_returns_not_found(self):
        with self.assertRaises(JPVRNeuronCheckpointNotFound):
            self.checkpoints.load("missing")

    def test_neuron_ids_are_isolated_by_index_key(self):
        first_runtime = _runtime()
        second_runtime = NeuronRuntime({"other": SynapseState(weight=0.73)})
        self.checkpoints.save("neuron-A", first_runtime, clock_epoch_id=CLOCK_EPOCH)
        self.checkpoints.save("neuron-B", second_runtime, clock_epoch_id=CLOCK_EPOCH)
        self.assertEqual(
            self.checkpoints.load("neuron-A").snapshot.runtime.synapses,
            first_runtime.synapses,
        )
        self.assertEqual(
            self.checkpoints.load("neuron-B").snapshot.runtime.synapses,
            second_runtime.synapses,
        )

    def test_future_rstdp_reward_timestamp_survives_persistent_round_trip(self):
        runtime = _runtime(learning_rule="rstdp")
        runtime.process_batch(
            10, [SynapticInput("pre", "input-000", 0.5)],
            learning_context_id="episode-1",
        )
        runtime.apply_reward("input-000", 20, 1.0, 0.5, "episode-1")
        saved = self.checkpoints.save(
            "rewarded-neuron", runtime, clock_epoch_id=CLOCK_EPOCH
        )
        loaded = self.checkpoints.load("rewarded-neuron")
        restored = loaded.snapshot.runtime
        self.assertEqual(restored.neuron_state.last_update_model_time_ns, 10)
        self.assertEqual(restored.synapses["input-000"].trace_last_update_model_time_ns, 20)
        self.assertEqual(loaded.locator.record_version, saved.record_version)
        restored.process_batch(30, [], learning_context_id="episode-1")

    def test_corrupted_checkpoint_page_is_rejected_on_load(self):
        saved = self.checkpoints.save(
            "damaged-neuron", _runtime(), clock_epoch_id=CLOCK_EPOCH
        )
        offset = saved.first_page_no * PAGE_SIZE + PAGE_SIZE - 1
        original = os.pread(self.storage._fd, 1, offset)
        self.assertEqual(len(original), 1)
        os.pwrite(self.storage._fd, bytes((original[0] ^ 1,)), offset)
        os.fsync(self.storage._fd)
        with self.assertRaises(JPVRFormatError):
            self.checkpoints.load("damaged-neuron")

    def test_page_catalog_memory_bound_rejects_before_new_commit(self):
        self.checkpoints.save("bounded", _runtime(), clock_epoch_id=CLOCK_EPOCH)
        before = self.storage.get_superblock()
        with self.assertRaises(JPVRFormatError):
            self.checkpoints.save(
                "bounded",
                _runtime(),
                clock_epoch_id=CLOCK_EPOCH,
                expected_record_version=1,
                max_pages=1,
            )
        self.assertEqual(self.storage.get_superblock(), before)

    def test_data_locator_contains_exact_commit_and_page_range(self):
        saved = self.checkpoints.save(
            "metadata-neuron", _runtime(), clock_epoch_id=CLOCK_EPOCH
        )
        loaded = self.checkpoints.load("metadata-neuron")
        self.assertEqual(loaded.locator.commit_page_no, saved.commit_page_no)
        self.assertEqual(loaded.locator.commit_id, saved.commit_id)
        self.assertEqual(loaded.locator.first_page_no, saved.first_page_no)
        self.assertEqual(loaded.locator.page_count, 1)
        self.assertEqual(loaded.locator.record_generation, saved.generation)

    def test_save_validates_runtime_before_initializing_empty_indexes(self):
        invalid = object()
        with self.assertRaises(ValueError):
            self.checkpoints.save(
                "invalid", invalid, clock_epoch_id=CLOCK_EPOCH
            )
        self.assertEqual(self.storage.get_superblock().generation, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
