"""Focused tests for durable JPVR TaskSessionManager checkpoints."""
import os
import tempfile
import unittest

from tools.jpvr_reference.codec import PAGE_SIZE
from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.task_session import TaskSessionManager, TaskStatus
from tools.neuron_runtime.task_session_adapter import (
    TaskSessionPersistenceError,
    decode_task_session_snapshot,
    encode_task_session_snapshot,
    task_session_object_id,
)
from tools.neuron_runtime.task_session_store import (
    JPVRTaskSessionCheckpointConflict,
    JPVRTaskSessionCheckpointError,
    JPVRTaskSessionCheckpointNotFound,
    JPVRTaskSessionCheckpointStore,
    TaskSessionCheckpointDescriptor,
    decode_task_session_checkpoint_descriptor,
    encode_task_session_checkpoint_descriptor,
)


def _manager(manager_id="session-manager"):
    manager = TaskSessionManager(manager_id=manager_id)
    manager.create_session("session-1", metadata={"purpose": "test"})
    manager.create_task("task-1", "session-1", "idem-1", "Persist this task")
    return manager


class TaskSessionDescriptorTests(unittest.TestCase):
    def test_descriptor_round_trip(self):
        value = TaskSessionCheckpointDescriptor(
            2, b"o" * 16, "DATA", 12, 1, 3, 256, 0x12345678
        )
        self.assertEqual(decode_task_session_checkpoint_descriptor(
            encode_task_session_checkpoint_descriptor(value)), value)

    def test_unknown_descriptor_version_is_rejected(self):
        value = TaskSessionCheckpointDescriptor(
            1, b"o" * 16, "DATA", 12, 1, 3, 256, 1
        )
        raw = bytearray(encode_task_session_checkpoint_descriptor(value))
        raw[4:6] = (2).to_bytes(2, "little")
        with self.assertRaises(ValueError):
            decode_task_session_checkpoint_descriptor(raw)

    def test_nonzero_reserved_descriptor_bytes_are_rejected(self):
        value = TaskSessionCheckpointDescriptor(
            1, b"o" * 16, "DATA", 12, 1, 3, 256, 1
        )
        raw = bytearray(encode_task_session_checkpoint_descriptor(value))
        raw[33] = 1
        with self.assertRaises(ValueError):
            decode_task_session_checkpoint_descriptor(raw)

    def test_blob_page_count_must_match_payload_length(self):
        from tools.jpvr_reference.records import MAX_SMALL_RECORD_PAYLOAD
        value = TaskSessionCheckpointDescriptor(
            1, b"o" * 16, "BLOB", 12, 1, 3,
            MAX_SMALL_RECORD_PAYLOAD + 1, 1
        )
        with self.assertRaises(ValueError):
            encode_task_session_checkpoint_descriptor(value)


class JPVRTaskSessionStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "sessions.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRTaskSessionCheckpointStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def test_save_load_round_trip(self):
        manager = _manager()
        saved = self.store.save(manager)
        loaded = self.store.load(manager.manager_id)
        self.assertEqual(saved.record_version, 1)
        self.assertEqual(loaded.manager.encode_snapshot(), manager.encode_snapshot())
        self.assertEqual(loaded.source_commit_page_no, saved.commit_page_no)

    def test_adapter_round_trip_and_stable_identity_validation(self):
        manager = _manager()
        payload = encode_task_session_snapshot(manager)
        restored = decode_task_session_snapshot(payload)
        self.assertEqual(restored.manager_id, manager.manager_id)
        self.assertEqual(restored.manager.encode_snapshot(), payload)
        self.assertEqual(task_session_object_id(manager.manager_id), task_session_object_id("session-manager"))
        with self.assertRaises(TaskSessionPersistenceError):
            task_session_object_id("invalid manager id")

    def test_noncanonical_snapshot_bytes_are_rejected(self):
        manager = _manager()
        payload = encode_task_session_snapshot(manager)
        with self.assertRaises(TaskSessionPersistenceError):
            decode_task_session_snapshot(payload + b" ")

    def test_invalid_internal_state_fails_before_jpvr_initialization(self):
        manager = _manager()
        manager._order = -1
        with self.assertRaises(JPVRTaskSessionCheckpointError):
            self.store.save(manager)
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_reopen_restores_tasks_and_sessions(self):
        manager = _manager()
        self.store.save(manager)
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        loaded = JPVRTaskSessionCheckpointStore(reopened).load(manager.manager_id)
        self.assertEqual(loaded.manager.get_task("task-1").status, TaskStatus.QUEUED)
        self.assertEqual(loaded.manager.get_session("session-1").metadata["purpose"], "test")

    def test_record_version_cas_rejects_stale_writer(self):
        first = self.store.save(_manager())
        second = self.store.save(_manager(), expected_record_version=first.record_version)
        self.assertEqual(second.record_version, 2)
        with self.assertRaises(JPVRTaskSessionCheckpointConflict):
            self.store.save(_manager(), expected_record_version=1)

    def test_manager_identity_isolated(self):
        self.store.save(_manager("manager-a"))
        with self.assertRaises(JPVRTaskSessionCheckpointNotFound):
            self.store.load("manager-b")
        with self.assertRaises(JPVRTaskSessionCheckpointConflict):
            self.store.save(_manager("manager-b"))

    def test_empty_container_expected_version_must_be_zero(self):
        with self.assertRaises(JPVRTaskSessionCheckpointConflict):
            self.store.save(_manager(), expected_record_version=4)
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_corrupt_record_page_is_rejected(self):
        saved = self.store.save(_manager())
        fd = os.open(self.path, os.O_RDWR)
        try:
            offset = saved.first_page_no * PAGE_SIZE + 80
            original = os.pread(fd, 1, offset)
            os.pwrite(fd, bytes([original[0] ^ 1]), offset)
            os.fsync(fd)
        finally:
            os.close(fd)
        with self.assertRaises(ValueError):
            self.store.load("session-manager")

    def test_large_snapshot_uses_blob_and_round_trips(self):
        manager = TaskSessionManager(manager_id="large-manager")
        for index in range(12):
            manager.create_session(f"session-{index:02d}", metadata={"payload": "x" * 700})
        saved = self.store.save(manager)
        loaded = self.store.load("large-manager")
        self.assertEqual(saved.storage_kind, "BLOB")
        self.assertEqual(loaded.manager.encode_snapshot(), manager.encode_snapshot())


if __name__ == "__main__":
    unittest.main()
