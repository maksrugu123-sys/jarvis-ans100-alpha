"""Regression tests for streaming JPVR historical transaction recovery."""
from dataclasses import replace
import os
import tempfile
import unittest
from collections.abc import Mapping

from .codec import JPVRFormatError, PageType, Superblock, encode_page
from .commit import CommitRecord, encode_commit_page
from .manifest import INDEX_ROOT_NAMES, Manifest, PageRef, encode_manifest_page
from .outbox import OutboxEvent, encode_outbox_page
from .recovery import scan_jpvr_history
from .transaction import assemble_transaction
from .storage import JPVRStorage


UUID = bytes.fromhex("00112233445566778899aabbccddeeff")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")
TX3 = bytes.fromhex("33333333333333333333333333333333")
COMMIT1 = bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
COMMIT2 = bytes.fromhex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
COMMIT3 = bytes.fromhex("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
OP1 = bytes.fromhex("cccccccccccccccccccccccccccccccc")
OP2 = bytes.fromhex("dddddddddddddddddddddddddddddddd")
OP3 = bytes.fromhex("ffffffffffffffffffffffffffffffff")
EVENT1 = bytes.fromhex("01010101010101010101010101010101")
EVENT2 = bytes.fromhex("02020202020202020202020202020202")
IDEM1 = bytes.fromhex("03030303030303030303030303030303")
IDEM2 = bytes.fromhex("04040404040404040404040404040404")


def genesis_superblock():
    return Superblock(UUID, 0, 0, 0, 1, bytes(16), 0)


def build_history(generations=3, *, bad_manifest_generation=None):
    """Build deterministic one-page append transactions with immutable roots."""
    catalog = {}
    roots = {
        name: PageRef(page_no, 1)
        for name, page_no in zip(INDEX_ROOT_NAMES, range(2, 9))
    }
    pages = [
        encode_page(PageType.INDEX, page_no, 1, TX1, b"index-root")
        for page_no in range(2, 9)
    ]
    manifest_no = 9
    manifest = Manifest(UUID, 1, roots)
    if bad_manifest_generation == 1:
        broken_roots = dict(roots)
        broken_roots["neuron"] = PageRef(99, 1)
        manifest = Manifest(UUID, 1, broken_roots)
    catalog.update({page_no: page for page_no, page in zip(range(2, 9), pages)})
    catalog[manifest_no] = encode_manifest_page(manifest, manifest_no, TX1)
    commit_no = 10
    catalog[commit_no] = encode_commit_page(
        CommitRecord(1, TX1, COMMIT1, OP1, PageRef(manifest_no, 1), commit_no),
        commit_no,
    )
    previous_commit_no = commit_no
    previous_commit_id = COMMIT1
    tx_ids = {1: TX1}
    commit_ids = {1: COMMIT1}

    for generation in range(2, generations + 1):
        tx_id = TX2 if generation == 2 else TX3
        commit_id = COMMIT2 if generation == 2 else COMMIT3
        operation_id = OP2 if generation == 2 else OP3
        start = previous_commit_no + 1
        data_page_no = start
        manifest_page_no = start + 1
        commit_page_no = start + 2
        catalog[data_page_no] = encode_page(
            PageType.DATA, data_page_no, generation, tx_id, f"payload-{generation}".encode()
        )
        next_roots = dict(roots)
        if bad_manifest_generation == generation:
            next_roots["neuron"] = PageRef(99, 1)
        next_manifest = Manifest(UUID, generation, next_roots)
        catalog[manifest_page_no] = encode_manifest_page(
            next_manifest, manifest_page_no, tx_id
        )
        catalog[commit_page_no] = encode_commit_page(
            CommitRecord(
                generation, tx_id, commit_id, operation_id,
                PageRef(manifest_page_no, generation), commit_page_no,
                previous_commit_ref=PageRef(previous_commit_no, generation - 1),
            ),
            commit_page_no,
        )
        previous_commit_no = commit_page_no
        previous_commit_id = commit_id
        tx_ids[generation] = tx_id
        commit_ids[generation] = commit_id

    generation = generations
    if generation == 0:
        return genesis_superblock(), {}
    return (
        Superblock(
            UUID, generation, previous_commit_no, 0, previous_commit_no,
            previous_commit_id, generation % 2,
        ),
        catalog,
    )


def build_non_final_manifest_history():
    sb1, catalog = build_history(1)
    start = sb1.durable_tail_page_no + 1
    tx_id = TX2
    catalog[start] = encode_page(PageType.DATA, start, 2, tx_id, b"pre-manifest")
    manifest_no = start + 1
    manifest = Manifest(
        UUID, 2,
        {name: PageRef(page_no, 1) for name, page_no in zip(INDEX_ROOT_NAMES, range(2, 9))}
    )
    catalog[manifest_no] = encode_manifest_page(manifest, manifest_no, tx_id)
    data_after_manifest = manifest_no + 1
    catalog[data_after_manifest] = encode_page(
        PageType.DATA, data_after_manifest, 2, tx_id, b"late-data"
    )
    commit_no = data_after_manifest + 1
    catalog[commit_no] = encode_commit_page(
        CommitRecord(
            2, tx_id, COMMIT2, OP2, PageRef(manifest_no, 2), commit_no,
            previous_commit_ref=PageRef(sb1.active_commit_page_no, 1),
        ), commit_no
    )
    return Superblock(UUID, 2, commit_no, 0, commit_no, COMMIT2, 0), catalog


class _FailingCatalog(Mapping):
    def __init__(self, source, fail_page_no):
        self.source = source
        self.fail_page_no = fail_page_no

    def __getitem__(self, key):
        if key == self.fail_page_no:
            raise OSError("injected page read failure")
        return self.source[key]

    def __iter__(self):
        return iter(self.source)

    def __len__(self):
        return len(self.source)


class JPVRHistoricalRecoveryTests(unittest.TestCase):
    def test_empty_genesis_container_is_a_complete_history(self):
        result = scan_jpvr_history(genesis_superblock(), {})
        self.assertTrue(result.is_complete)
        self.assertEqual(result.verified_generation, 0)
        self.assertEqual(result.verified_durable_tail_page_no, 1)
        self.assertEqual(result.snapshots, ())
        self.assertEqual(result.outbox_events, ())

    def test_scans_one_generation_and_returns_snapshot_metadata(self):
        sb, catalog = build_history(1)
        result = scan_jpvr_history(sb, catalog)
        self.assertTrue(result.is_complete)
        self.assertEqual(result.verified_generation, 1)
        self.assertEqual(result.snapshots[0].first_page_no, 2)
        self.assertEqual(result.snapshots[0].last_page_no, 10)
        self.assertEqual(result.snapshots[0].page_count, 9)
        self.assertEqual(result.snapshots[0].manifest.manifest.generation, 1)

    def test_scans_three_generations_and_validates_each_historical_manifest(self):
        sb, catalog = build_history(3)
        result = scan_jpvr_history(sb, catalog)
        self.assertTrue(result.is_complete)
        self.assertEqual([item.commit.commit_generation for item in result.snapshots], [1, 2, 3])
        self.assertEqual([item.first_page_no for item in result.snapshots], [2, 11, 14])
        self.assertEqual([item.last_page_no for item in result.snapshots], [10, 13, 16])
        self.assertEqual(result.verified_durable_tail_page_no, 16)

    def test_corrupt_page_returns_previous_verified_generation_as_prefix(self):
        sb, catalog = build_history(3)
        damaged = bytearray(catalog[11])
        damaged[-1] ^= 0x01
        catalog[11] = bytes(damaged)
        result = scan_jpvr_history(sb, catalog)
        self.assertFalse(result.is_complete)
        self.assertEqual(result.verified_generation, 1)
        self.assertEqual(result.verified_durable_tail_page_no, 10)
        self.assertEqual(result.issue.code, "page-unreadable")
        self.assertEqual(result.issue.page_no, 11)

    def test_missing_page_returns_last_verified_commit_prefix(self):
        sb, catalog = build_history(3)
        del catalog[12]
        result = scan_jpvr_history(sb, catalog)
        self.assertFalse(result.commit_history_complete)
        self.assertEqual(result.verified_generation, 1)
        self.assertEqual(result.issue.page_no, 12)

    def test_page_read_error_returns_last_verified_prefix(self):
        sb, catalog = build_history(2)
        result = scan_jpvr_history(sb, _FailingCatalog(catalog, 11))
        self.assertEqual(result.verified_generation, 1)
        self.assertEqual(result.issue.code, "page-unreadable")
        self.assertIn("injected page read failure", result.issue.message)

    def test_corrupt_historical_manifest_root_stops_before_next_generation(self):
        sb, catalog = build_history(3, bad_manifest_generation=2)
        result = scan_jpvr_history(sb, catalog)
        self.assertFalse(result.commit_history_complete)
        self.assertEqual(result.verified_generation, 1)
        self.assertEqual(result.issue.code, "historical-transaction-invalid")
        self.assertEqual(result.issue.generation, 2)
        self.assertIn("outside durable prefix", result.issue.message)

    def test_rejects_transaction_page_with_wrong_generation(self):
        sb, catalog = build_history(2)
        catalog[11] = encode_page(PageType.DATA, 11, 3, TX2, b"payload-2")
        result = scan_jpvr_history(sb, catalog)
        self.assertEqual(result.verified_generation, 1)
        self.assertIn("generation does not match", result.issue.message)

    def test_rejects_transaction_page_with_wrong_transaction_id(self):
        sb, catalog = build_history(2)
        catalog[11] = encode_page(PageType.DATA, 11, 2, TX3, b"payload-2")
        result = scan_jpvr_history(sb, catalog)
        self.assertEqual(result.verified_generation, 1)
        self.assertIn("transaction_id does not match", result.issue.message)

    def test_requires_manifest_to_be_final_staged_page(self):
        sb, catalog = build_non_final_manifest_history()
        result = scan_jpvr_history(sb, catalog)
        self.assertEqual(result.verified_generation, 1)
        self.assertIn("immediately before COMMIT", result.issue.message)

    def test_rejects_duplicate_commit_identity_across_generations(self):
        sb, catalog = build_history(2)
        catalog[13] = encode_commit_page(
            CommitRecord(
                2, TX2, COMMIT1, OP2, PageRef(12, 2), 13,
                previous_commit_ref=PageRef(10, 1),
            ), 13
        )
        bad_sb = replace(sb, active_commit_id=COMMIT1)
        result = scan_jpvr_history(bad_sb, catalog)
        self.assertEqual(result.verified_generation, 1)
        self.assertIn("duplicate commit_id", result.issue.message)

    def test_rejects_duplicate_transaction_identity_across_generations(self):
        sb, catalog = build_history(2)
        catalog[11] = encode_page(PageType.DATA, 11, 2, TX1, b"payload-2")
        manifest = Manifest(
            UUID, 2,
            {name: PageRef(page_no, 1) for name, page_no in zip(INDEX_ROOT_NAMES, range(2, 9))}
        )
        catalog[12] = encode_manifest_page(manifest, 12, TX1)
        catalog[13] = encode_commit_page(
            CommitRecord(
                2, TX1, COMMIT2, OP2, PageRef(12, 2), 13,
                previous_commit_ref=PageRef(10, 1),
            ), 13
        )
        result = scan_jpvr_history(sb, catalog)
        self.assertEqual(result.verified_generation, 1)
        self.assertIn("duplicate transaction_id", result.issue.message)

    def test_rejects_generation_gap(self):
        sb, catalog = build_history(2)
        catalog[11] = encode_page(PageType.DATA, 11, 3, TX2, b"payload-2")
        manifest = Manifest(
            UUID, 3,
            {name: PageRef(page_no, 1) for name, page_no in zip(INDEX_ROOT_NAMES, range(2, 9))}
        )
        catalog[12] = encode_manifest_page(manifest, 12, TX2)
        catalog[13] = encode_commit_page(
            CommitRecord(
                3, TX2, COMMIT2, OP2, PageRef(12, 3), 13,
                previous_commit_ref=PageRef(10, 1),
            ), 13
        )
        bad_sb = Superblock(UUID, 3, 13, 0, 13, COMMIT2, 1)
        result = scan_jpvr_history(bad_sb, catalog)
        self.assertEqual(result.verified_generation, 1)
        self.assertIn("expected 2", result.issue.message)

    def test_rejects_active_superblock_that_points_to_old_commit(self):
        sb, catalog = build_history(3)
        bad_sb = replace(
            sb,
            generation=2,
            active_commit_page_no=13,
            active_commit_id=COMMIT2,
            durable_tail_page_no=16,
            slot_id=0,
        )
        result = scan_jpvr_history(bad_sb, catalog)
        self.assertFalse(result.commit_history_complete)
        self.assertEqual(result.verified_generation, 3)
        self.assertEqual(result.issue.code, "active-superblock-mismatch")


    def test_rejects_active_superblock_checkpoint_mismatch(self):
        sb, catalog = build_history(3)
        bad_sb = replace(sb, checkpoint_page_no=2)
        result = scan_jpvr_history(bad_sb, catalog)
        self.assertEqual(result.verified_generation, 3)
        self.assertEqual(result.issue.code, "active-superblock-mismatch")

    def test_rejects_uncommitted_pages_after_last_commit(self):
        sb, catalog = build_history(3)
        catalog[17] = encode_page(PageType.DATA, 17, 3, TX3, b"orphan")
        extended = replace(sb, durable_tail_page_no=17)
        result = scan_jpvr_history(extended, catalog)
        self.assertEqual(result.verified_generation, 3)
        self.assertEqual(result.verified_durable_tail_page_no, 16)
        self.assertEqual(result.issue.code, "active-superblock-mismatch")

    def test_reports_catalog_entry_outside_active_durable_prefix(self):
        sb, catalog = build_history(3)
        catalog[17] = encode_page(PageType.DATA, 17, 3, TX3, b"outside")
        result = scan_jpvr_history(sb, catalog)
        self.assertEqual(result.verified_generation, 3)
        self.assertEqual(result.issue.code, "catalog-page-outside-prefix")

    def test_optional_full_index_tree_validation_is_applied_to_each_generation(self):
        sb, catalog = build_history(2)
        result = scan_jpvr_history(sb, catalog, verify_index_trees=True)
        self.assertEqual(result.verified_generation, 0)
        self.assertEqual(result.issue.code, "historical-transaction-invalid")

    def test_scans_outbox_events_after_historical_commit_validation(self):
        from .test_outbox_scan import make_snapshot
        sb, catalog = make_snapshot()
        sb = replace(sb, slot_id=0)
        result = scan_jpvr_history(sb, catalog)
        self.assertTrue(result.is_complete)
        self.assertEqual([item.event.sequence for item in result.outbox_events], [1, 2])

    def test_outbox_error_does_not_erase_verified_commit_prefix(self):
        from .test_outbox_scan import make_event, make_snapshot
        bad_event = make_event(previous_event_ref=PageRef(8, 1))
        sb, catalog = make_snapshot(second_event=bad_event)
        sb = replace(sb, slot_id=0)
        result = scan_jpvr_history(sb, catalog)
        self.assertTrue(result.commit_history_complete)
        self.assertFalse(result.outbox_history_complete)
        self.assertEqual(result.verified_generation, 2)
        self.assertEqual(result.issue.code, "outbox-history-invalid")

    def test_storage_method_scans_without_materializing_catalog(self):
        from unittest.mock import patch
        from .test_storage import build_genesis_plan
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "history.jpvr")
            with JPVRStorage.create(path, container_uuid=UUID) as storage:
                storage.commit(build_genesis_plan())
                with patch.object(
                    JPVRStorage, "load_page_catalog",
                    side_effect=AssertionError("must use lazy file-backed catalog"),
                ):
                    result = storage.scan_history()
                self.assertTrue(result.is_complete)
                self.assertEqual(result.verified_generation, 1)
                self.assertEqual(result.verified_durable_tail_page_no, 10)

    def test_storage_history_scan_reports_orphan_tail_without_truncating(self):
        from .test_storage import build_genesis_plan
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "history.jpvr")
            with JPVRStorage.create(path, container_uuid=UUID) as storage:
                storage.commit(build_genesis_plan())
            fd = os.open(path, os.O_RDWR)
            try:
                os.pwrite(fd, bytes(4096), 11 * 4096)
                os.fsync(fd)
            finally:
                os.close(fd)
            with JPVRStorage.open(path, recover_tail=False) as storage:
                size_before = os.path.getsize(path)
                result = storage.scan_history()
                self.assertTrue(result.commit_history_complete)
                self.assertTrue(result.outbox_history_complete)
                self.assertTrue(result.has_orphan_tail)
                self.assertEqual(result.orphan_tail_bytes, 4096)
                self.assertEqual(os.path.getsize(path), size_before)


if __name__ == "__main__":
    unittest.main()
