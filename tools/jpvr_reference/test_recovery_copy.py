"""Tests for non-destructive JPVR historical recovery-copy publication."""
import os
import tempfile
import unittest
from unittest.mock import patch

from .codec import PAGE_SIZE, SUPERBLOCK_SIZE, JPVRFormatError, decode_superblock
from .storage import JPVRRecoveryCopyUncertainError, JPVRStorage
from .test_storage import build_genesis_plan, build_second_plan


UUID = bytes.fromhex("00112233445566778899aabbccddeeff")
ZERO_ID = bytes(16)


def _create_history(path, *, generations=2):
    first = build_genesis_plan()
    second = build_second_plan(first) if generations >= 2 else None
    with JPVRStorage.create(path, container_uuid=UUID) as store:
        store.commit(first)
        if second is not None:
            store.commit(second)
    return first, second


def _damage_page(path, page_no):
    fd = os.open(path, os.O_RDWR)
    try:
        offset = page_no * PAGE_SIZE
        raw = bytearray(os.pread(fd, PAGE_SIZE, offset))
        raw[-1] ^= 0x01
        os.pwrite(fd, raw, offset)
        os.fsync(fd)
    finally:
        os.close(fd)


class JPVRRecoveryCopyTests(unittest.TestCase):
    def test_recovers_verified_prefix_without_modifying_source_and_rebuilds_both_slots(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "recovered.jpvr")
            first, _ = _create_history(source, generations=2)
            _damage_page(source, 11)
            with open(source, "rb") as stream:
                source_before = stream.read()

            with JPVRStorage.open(source, recover_tail=False) as store:
                report = store.scan_history()
                self.assertEqual(report.verified_generation, 1)
                self.assertTrue(report.outbox_history_complete)
                self.assertEqual(report.issue.code, "page-unreadable")
                result = store.recover_copy(
                    destination,
                    expected_commit_id=first.new_superblock.active_commit_id,
                )

            with open(source, "rb") as stream:
                self.assertEqual(stream.read(), source_before)
            self.assertTrue(result.rolled_back)
            self.assertEqual(result.source_active_generation, 2)
            self.assertEqual(result.source_verified_generation, 1)
            self.assertEqual(result.recovered_superblock, first.new_superblock)
            self.assertEqual(os.path.getsize(destination), 11 * PAGE_SIZE)

            with JPVRStorage.open(destination) as recovered:
                self.assertEqual(recovered.get_superblock(), first.new_superblock)
            fd = os.open(destination, os.O_RDONLY)
            try:
                slot0 = decode_superblock(
                    os.pread(fd, SUPERBLOCK_SIZE, 0), expected_slot_id=0
                )
                slot1 = decode_superblock(
                    os.pread(fd, SUPERBLOCK_SIZE, SUPERBLOCK_SIZE),
                    expected_slot_id=1,
                )
            finally:
                os.close(fd)
            self.assertEqual(slot0.generation, 0)
            self.assertEqual(slot1, first.new_superblock)

    def test_keeps_latest_generation_when_complete_history_is_valid(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            _, second = _create_history(source, generations=2)
            with JPVRStorage.open(source) as store:
                result = store.recover_copy(
                    destination,
                    expected_commit_id=second.new_superblock.active_commit_id,
                )
            self.assertFalse(result.rolled_back)
            self.assertEqual(result.recovered_superblock, second.new_superblock)
            with JPVRStorage.open(destination) as recovered:
                self.assertEqual(recovered.get_superblock(), second.new_superblock)

    def test_discards_only_physical_orphan_tail_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            first, _ = _create_history(source, generations=1)
            fd = os.open(source, os.O_WRONLY | os.O_APPEND)
            try:
                os.write(fd, b"orphan-tail")
                os.fsync(fd)
            finally:
                os.close(fd)
            source_size = os.path.getsize(source)
            with JPVRStorage.open(source, recover_tail=False) as store:
                result = store.recover_copy(
                    destination,
                    expected_commit_id=first.new_superblock.active_commit_id,
                )
            self.assertEqual(result.discarded_orphan_tail_bytes, len(b"orphan-tail"))
            self.assertEqual(os.path.getsize(source), source_size)
            self.assertEqual(os.path.getsize(destination), 11 * PAGE_SIZE)

    def test_refuses_to_overwrite_an_existing_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "keep.jpvr")
            first, _ = _create_history(source, generations=1)
            with open(destination, "wb") as stream:
                stream.write(b"keep-existing-data")
            with open(destination, "rb") as stream:
                before = stream.read()
            with JPVRStorage.open(source) as store:
                with self.assertRaises(FileExistsError):
                    store.recover_copy(
                        destination,
                        expected_commit_id=first.new_superblock.active_commit_id,
                    )
            with open(destination, "rb") as stream:
                self.assertEqual(stream.read(), before)

    def test_requires_exact_expected_commit_id(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            _create_history(source, generations=1)
            with JPVRStorage.open(source) as store:
                with self.assertRaises(JPVRFormatError):
                    store.recover_copy(destination, expected_commit_id=ZERO_ID)
            self.assertFalse(os.path.exists(destination))

    def test_rejects_prefix_when_outbox_history_is_invalid(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            first, _ = _create_history(source, generations=1)
            with JPVRStorage.open(source) as store:
                with patch(
                    "tools.jpvr_reference.recovery._scan_outbox_chain",
                    side_effect=JPVRFormatError("injected invalid OUTBOX history"),
                ):
                    with self.assertRaisesRegex(JPVRFormatError, "OUTBOX chain"):
                        store.recover_copy(
                            destination,
                            expected_commit_id=first.new_superblock.active_commit_id,
                        )
            self.assertFalse(os.path.exists(destination))
            self.assertEqual(os.listdir(directory), ["source.jpvr"])

    def test_rejects_source_path_as_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            first, _ = _create_history(source, generations=1)
            with open(source, "rb") as stream:
                before = stream.read()
            with JPVRStorage.open(source) as store:
                with self.assertRaises(JPVRFormatError):
                    store.recover_copy(
                        source,
                        expected_commit_id=first.new_superblock.active_commit_id,
                    )
            with open(source, "rb") as stream:
                self.assertEqual(stream.read(), before)

    def test_failed_no_replace_publication_cleans_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            first, _ = _create_history(source, generations=1)
            with JPVRStorage.open(source) as store:
                with patch(
                    "tools.jpvr_reference.storage.os.link",
                    side_effect=FileExistsError(destination),
                ):
                    with self.assertRaises(FileExistsError):
                        store.recover_copy(
                            destination,
                            expected_commit_id=first.new_superblock.active_commit_id,
                        )
            self.assertFalse(os.path.exists(destination))
            self.assertEqual(os.listdir(directory), ["source.jpvr"])

    def test_refuses_copy_when_no_superblock_slot_can_be_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            first, _ = _create_history(source, generations=1)
            with JPVRStorage.open(source) as store:
                fd = os.open(source, os.O_RDWR)
                try:
                    os.pwrite(fd, bytes(SUPERBLOCK_SIZE), 0)
                    os.pwrite(fd, bytes(SUPERBLOCK_SIZE), SUPERBLOCK_SIZE)
                    os.fsync(fd)
                finally:
                    os.close(fd)
                with self.assertRaises(JPVRFormatError):
                    store.recover_copy(
                        destination,
                        expected_commit_id=first.new_superblock.active_commit_id,
                    )
            self.assertFalse(os.path.exists(destination))
            self.assertEqual(os.listdir(directory), ["source.jpvr"])

    def test_refuses_copy_when_no_superblock_slot_can_be_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            first, _ = _create_history(source, generations=1)
            with JPVRStorage.open(source) as store:
                fd = os.open(source, os.O_RDWR)
                try:
                    os.pwrite(fd, bytes(SUPERBLOCK_SIZE), 0)
                    os.pwrite(fd, bytes(SUPERBLOCK_SIZE), SUPERBLOCK_SIZE)
                    os.fsync(fd)
                finally:
                    os.close(fd)
                with self.assertRaises(JPVRFormatError):
                    store.recover_copy(
                        destination,
                        expected_commit_id=first.new_superblock.active_commit_id,
                    )
            self.assertFalse(os.path.exists(destination))
            self.assertEqual(os.listdir(directory), ["source.jpvr"])

    def test_reports_uncertain_result_when_parent_directory_sync_fails_after_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            first, _ = _create_history(source, generations=1)
            with JPVRStorage.open(source) as store:
                with patch.object(
                    JPVRStorage,
                    "_fsync_parent",
                    side_effect=OSError("injected directory fsync failure"),
                ):
                    with self.assertRaises(JPVRRecoveryCopyUncertainError) as caught:
                        store.recover_copy(
                            destination,
                            expected_commit_id=first.new_superblock.active_commit_id,
                        )
            self.assertEqual(caught.exception.path, destination)
            self.assertEqual(caught.exception.generation, 1)
            self.assertEqual(
                caught.exception.commit_id,
                first.new_superblock.active_commit_id,
            )
            self.assertTrue(os.path.exists(destination))
            with JPVRStorage.open(destination) as recovered:
                self.assertEqual(
                    recovered.get_superblock(),
                    first.new_superblock,
                )

    def test_rejects_malformed_expected_commit_id_before_creating_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.jpvr")
            destination = os.path.join(directory, "copy.jpvr")
            _create_history(source, generations=1)
            with JPVRStorage.open(source) as store:
                with self.assertRaises(JPVRFormatError):
                    store.recover_copy(destination, expected_commit_id=b"short")
            self.assertFalse(os.path.exists(destination))
            self.assertEqual(os.listdir(directory), ["source.jpvr"])


if __name__ == "__main__":
    unittest.main()
