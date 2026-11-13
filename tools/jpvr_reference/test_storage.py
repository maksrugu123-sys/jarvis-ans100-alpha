"""Contract tests for JPVR file layout, append ordering and recovery."""
from dataclasses import replace
import os
import tempfile
import unittest
from unittest.mock import patch

from .codec import PAGE_SIZE, SUPERBLOCK_SIZE, JPVRFormatError, PageType, Superblock, encode_page, encode_superblock
from .commit import CommitRecord, encode_commit_page
from .index import IndexNode
from .manifest import INDEX_ROOT_NAMES, Manifest, PageRef, decode_manifest_page, encode_manifest_page
from .transaction import assemble_transaction
from .typed_transaction import IndexMutationInput, IndexPageInput, TypedTransactionRequest, build_typed_transaction
from .platform_io import read_at, write_all_at
from .storage import JPVRCommitUncertainError, JPVRStorage


UUID = bytes.fromhex("00112233445566778899aabbccddeeff")
UUID_OTHER = bytes.fromhex("ffeeddccbbaa99887766554433221100")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")
COMMIT1 = bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
COMMIT2 = bytes.fromhex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
OP1 = bytes.fromhex("cccccccccccccccccccccccccccccccc")
OP2 = bytes.fromhex("dddddddddddddddddddddddddddddddd")


def genesis_superblock(slot_id=0, container_uuid=UUID):
    return Superblock(container_uuid, 0, 0, 0, 1, bytes(16), slot_id)


def catalog_from_plan(plan):
    assembly = plan.assembly if hasattr(plan, "assembly") else plan
    return dict(zip(assembly.ordered_page_numbers, assembly.ordered_page_bytes))


def build_genesis_plan():
    staged = []
    roots = {}
    for index, name in enumerate(INDEX_ROOT_NAMES, start=1):
        page_no = index + 1
        roots[name] = PageRef(page_no, 1)
        staged.append(encode_page(PageType.INDEX, page_no, 1, TX1, b"root"))
    manifest_page = encode_manifest_page(Manifest(UUID, 1, roots), 9, TX1)
    commit_page = encode_commit_page(
        CommitRecord(1, TX1, COMMIT1, OP1, PageRef(9, 1), 10), 10
    )
    return assemble_transaction(
        genesis_superblock(), tuple(staged) + (manifest_page,), commit_page, {}
    )


def build_second_plan(first):
    current = first.new_superblock
    old_catalog = catalog_from_plan(first)
    old_manifest = decode_manifest_page(old_catalog[9]).manifest
    data_no = current.durable_tail_page_no + 1
    data_page = encode_page(PageType.DATA, data_no, 2, TX2, b"generation-two")
    manifest_no = data_no + 1
    manifest_page = encode_manifest_page(replace(old_manifest, generation=2), manifest_no, TX2)
    commit_no = manifest_no + 1
    commit_page = encode_commit_page(
        CommitRecord(
            2, TX2, COMMIT2, OP2, PageRef(manifest_no, 2), commit_no,
            previous_commit_ref=PageRef(current.active_commit_page_no, current.generation),
        ), commit_no
    )
    return assemble_transaction(
        current, (data_page, manifest_page), commit_page, old_catalog,
        previous_commit_page_bytes=old_catalog[current.active_commit_page_no],
    )


def build_typed_genesis():
    pages = tuple(
        IndexPageInput(IndexNode(bytes((i,)) * 16, 0), root_name=name)
        for i, name in enumerate(INDEX_ROOT_NAMES, start=1)
    )
    return build_typed_transaction(TypedTransactionRequest(
        current_superblock=genesis_superblock(), page_catalog={},
        transaction_id=TX1, commit_id=COMMIT1, operation_id=OP1,
        index_pages=pages,
    ))


def build_typed_cow_append(first):
    return build_typed_transaction(TypedTransactionRequest(
        current_superblock=first.new_superblock, page_catalog=catalog_from_plan(first),
        transaction_id=TX2, commit_id=COMMIT2, operation_id=OP2,
        index_mutations=(
            IndexMutationInput("neuron", bytes((2,)) * 16, b"alpha", b"A"),
            IndexMutationInput("neuron", bytes((2,)) * 16, b"beta", b"B"),
        ),
    ))


class JPVRStorageTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.directory.name, "memory.jpvr")

    def tearDown(self):
        self.directory.cleanup()

    def create_store(self):
        return JPVRStorage.create(self.path, container_uuid=UUID)

    def corrupt(self, offset):
        fd = os.open(self.path, os.O_RDWR)
        try:
            old = read_at(fd, 1, offset)
            write_all_at(fd, bytes((old[0] ^ 0x80,)), offset)
            os.fsync(fd)
        finally:
            os.close(fd)

    def test_create_writes_both_genesis_superblock_slots(self):
        with self.create_store() as store:
            self.assertEqual(os.path.getsize(self.path), 2 * PAGE_SIZE)
            self.assertEqual(store.get_superblock(), genesis_superblock())
        from .codec import decode_superblock
        fd = os.open(self.path, os.O_RDONLY)
        try:
            self.assertEqual(
                decode_superblock(read_at(fd, SUPERBLOCK_SIZE, 0), expected_slot_id=0),
                genesis_superblock(0),
            )
            self.assertEqual(
                decode_superblock(read_at(fd, SUPERBLOCK_SIZE, PAGE_SIZE), expected_slot_id=1),
                genesis_superblock(1),
            )
        finally:
            os.close(fd)

    def test_create_does_not_overwrite_existing_path(self):
        with self.create_store():
            with self.assertRaises(FileExistsError):
                self.create_store()

    def test_open_rejects_truncated_superblock_area(self):
        with open(self.path, "wb") as stream:
            stream.write(bytes(PAGE_SIZE))
        with self.assertRaisesRegex(JPVRFormatError, "shorter than"):
            JPVRStorage.open(self.path)

    def test_genesis_commit_survives_close_and_reopen(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            result = store.commit(plan)
            self.assertEqual((result.generation, result.slot_id, result.active_commit_page_no),
                             (1, 1, 10))
            self.assertEqual(store.read_page(2), plan.ordered_page_bytes[0])
        with JPVRStorage.open(self.path) as reopened:
            self.assertEqual(reopened.get_superblock(), plan.new_superblock)
            self.assertEqual(reopened.read_page(10), plan.ordered_page_bytes[-1])

    def test_second_generation_alternates_superblock_slot(self):
        first = build_genesis_plan()
        second = build_second_plan(first)
        with self.create_store() as store:
            self.assertEqual(store.commit(first).slot_id, 1)
            result = store.commit(second)
            self.assertEqual((result.generation, result.slot_id, result.active_commit_page_no),
                             (2, 0, 13))
        with JPVRStorage.open(self.path) as reopened:
            self.assertEqual(reopened.get_superblock(), second.new_superblock)
            self.assertEqual(reopened.read_page(11), second.ordered_page_bytes[0])

    def test_stale_plan_is_rejected_without_rewriting_pages(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            store.commit(plan)
            before = os.path.getsize(self.path)
            with self.assertRaises(JPVRFormatError):
                store.commit(plan)
            self.assertEqual(os.path.getsize(self.path), before)

    def test_open_truncates_orphan_full_page(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            store.commit(plan)
        fd = os.open(self.path, os.O_RDWR)
        try:
            write_all_at(fd, bytes(PAGE_SIZE), 11 * PAGE_SIZE)
            os.fsync(fd)
        finally:
            os.close(fd)
        with JPVRStorage.open(self.path) as reopened:
            self.assertEqual(reopened.get_superblock().generation, 1)
            self.assertEqual(os.path.getsize(self.path), 11 * PAGE_SIZE)

    def test_open_truncates_partial_orphan_tail(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            store.commit(plan)
        with open(self.path, "ab") as stream:
            stream.write(b"partial-tail")
            stream.flush()
            os.fsync(stream.fileno())
        with JPVRStorage.open(self.path):
            self.assertEqual(os.path.getsize(self.path), 11 * PAGE_SIZE)

    def test_corrupt_newest_superblock_falls_back_and_truncates(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            store.commit(plan)
        self.corrupt(PAGE_SIZE + 24)
        with JPVRStorage.open(self.path) as reopened:
            self.assertEqual(reopened.get_superblock(), genesis_superblock())
            self.assertEqual(os.path.getsize(self.path), 2 * PAGE_SIZE)

    def test_corrupt_active_commit_page_falls_back_to_previous_slot(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            store.commit(plan)
        self.corrupt(10 * PAGE_SIZE + 80)
        with JPVRStorage.open(self.path) as reopened:
            self.assertEqual(reopened.get_superblock().generation, 0)
            self.assertEqual(os.path.getsize(self.path), 2 * PAGE_SIZE)

    def test_corrupt_root_page_invalidates_newest_snapshot(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            store.commit(plan)
        self.corrupt(2 * PAGE_SIZE + 64)
        with JPVRStorage.open(self.path) as reopened:
            self.assertEqual(reopened.get_superblock().generation, 0)

    def test_both_corrupt_superblocks_are_rejected(self):
        with self.create_store():
            pass
        self.corrupt(24)
        self.corrupt(PAGE_SIZE + 24)
        with self.assertRaisesRegex(JPVRFormatError, "no valid"):
            JPVRStorage.open(self.path)

    def test_conflicting_valid_superblocks_at_same_generation_are_rejected(self):
        with self.create_store():
            pass
        fd = os.open(self.path, os.O_RDWR)
        try:
            other = genesis_superblock(1, UUID_OTHER)
            write_all_at(fd, encode_superblock(other), PAGE_SIZE)
            os.fsync(fd)
        finally:
            os.close(fd)
        with self.assertRaisesRegex(JPVRFormatError, "same generation"):
            JPVRStorage.open(self.path)

    def test_read_page_rejects_page_outside_durable_prefix(self):
        with self.create_store() as store:
            with self.assertRaisesRegex(JPVRFormatError, "outside"):
                store.read_page(2)

    def test_typed_cow_pages_are_validated_before_publication(self):
        first = build_typed_genesis()
        second = build_typed_cow_append(first)
        with self.create_store() as store:
            store.commit(first)
            committed = store.commit(second)
            self.assertEqual(committed.generation, 2)
            self.assertEqual(second.index_page_numbers, (11,))
            self.assertEqual(committed.active_commit_page_no, second.commit_page_no)
        with JPVRStorage.open(self.path, verify_index_trees=True) as reopened:
            self.assertEqual(reopened.get_superblock().generation, 2)

    def test_typed_cow_metadata_must_match_physical_index_pages(self):
        first = build_typed_genesis()
        second = build_typed_cow_append(first)
        tampered = replace(second, index_page_numbers=())
        with self.create_store() as store:
            store.commit(first)
            with self.assertRaisesRegex(JPVRFormatError, "INDEX page range"):
                store.commit(tampered)
            self.assertEqual(store.get_superblock().generation, 1)

    def test_partial_superblock_publication_returns_commit_uncertain(self):
        plan = build_genesis_plan()
        original = JPVRStorage._pwrite_all
        calls = 0
        publication_call = len(plan.ordered_page_bytes) + 1

        def fail_during_superblock(fd, data, offset):
            nonlocal calls
            calls += 1
            if calls == publication_call:
                original(fd, data[:80], offset)
                raise OSError("injected interrupted Superblock write")
            return original(fd, data, offset)

        with self.create_store() as store:
            with patch.object(JPVRStorage, "_pwrite_all", new=staticmethod(fail_during_superblock)):
                with self.assertRaises(JPVRCommitUncertainError) as caught:
                    store.commit(plan)
            self.assertEqual(caught.exception.commit_id, COMMIT1)
            self.assertEqual(caught.exception.generation, 1)
        with JPVRStorage.open(self.path) as reopened:
            self.assertEqual(reopened.get_superblock().generation, 0)
            self.assertEqual(os.path.getsize(self.path), 2 * PAGE_SIZE)
 
    def test_load_page_catalog_returns_only_committed_pages(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            store.commit(plan)
            catalog = store.load_page_catalog()
            self.assertEqual(tuple(sorted(catalog)), tuple(range(2, 11)))
            self.assertEqual(catalog[10], plan.ordered_page_bytes[-1])

    def test_load_page_catalog_enforces_memory_page_limit(self):
        plan = build_genesis_plan()
        with self.create_store() as store:
            store.commit(plan)
            with self.assertRaisesRegex(JPVRFormatError, "max_pages"):
                store.load_page_catalog(max_pages=8)
            self.assertEqual(len(store.load_page_catalog(max_pages=9)), 9)


if __name__ == "__main__":
    unittest.main()
