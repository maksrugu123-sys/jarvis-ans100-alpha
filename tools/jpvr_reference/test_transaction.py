import unittest
from dataclasses import replace

from .codec import JPVRFormatError, PageType, Superblock, encode_page
from .commit import CommitRecord, encode_commit_page
from .manifest import INDEX_ROOT_NAMES, Manifest, PageRef, encode_manifest_page
from .transaction import (
    TransactionAssemblyPlan,
    assemble_transaction,
    validate_transaction_assembly_plan,
)


UUID = bytes.fromhex("00112233445566778899aabbccddeeff")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")
COMMIT1 = bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
COMMIT2 = bytes.fromhex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
OP1 = bytes.fromhex("cccccccccccccccccccccccccccccccc")
OP2 = bytes.fromhex("dddddddddddddddddddddddddddddddd")


def genesis_superblock():
    return Superblock(UUID, 0, 0, 0, 1, bytes(16), 0)


def first_generation():
    catalog = {
        page_no: encode_page(PageType.INDEX, page_no, 1, TX1, b"index-root")
        for page_no in range(2, 9)
    }
    manifest = Manifest(
        UUID, 1,
        {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
    )
    manifest_page = encode_manifest_page(manifest, 9, TX1)
    commit_page = encode_commit_page(
        CommitRecord(1, TX1, COMMIT1, OP1, PageRef(9, 1), 10),
        10,
    )
    staged = tuple(catalog[i] for i in range(2, 9)) + (manifest_page,)
    plan = assemble_transaction(genesis_superblock(), staged, commit_page, {})
    catalog[9] = manifest_page
    catalog[10] = commit_page
    return plan, catalog, commit_page


class TransactionAssemblyTests(unittest.TestCase):
    def test_assembles_genesis_transaction_with_commit_last(self):
        plan, catalog, _ = first_generation()
        self.assertIsInstance(plan, TransactionAssemblyPlan)
        self.assertEqual(plan.ordered_page_numbers, tuple(range(2, 11)))
        self.assertEqual(plan.page_count, 9)
        self.assertEqual(plan.manifest_page_no, 9)
        self.assertEqual(plan.new_superblock.generation, 1)
        self.assertEqual(plan.new_superblock.active_commit_page_no, 10)
        self.assertEqual(plan.new_superblock.durable_tail_page_no, 10)
        self.assertEqual(plan.new_superblock.active_commit_id, COMMIT1)
        self.assertEqual(len(catalog), 9)

    def test_assembles_second_generation_against_previous_snapshot(self):
        first, catalog, previous_commit = first_generation()
        current = first.new_superblock
        manifest = Manifest(
            UUID, 2,
            {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
        )
        manifest_page = encode_manifest_page(manifest, 11, TX2)
        commit_page = encode_commit_page(
            CommitRecord(
                2, TX2, COMMIT2, OP2, PageRef(11, 2), 12,
                previous_commit_ref=PageRef(10, 1),
            ),
            12,
        )
        plan = assemble_transaction(
            current, (manifest_page,), commit_page, catalog,
            previous_commit_page_bytes=previous_commit,
        )
        self.assertEqual(plan.ordered_page_numbers, (11, 12))
        self.assertEqual(plan.new_superblock.generation, 2)
        self.assertEqual(plan.new_superblock.slot_id, 0)
        self.assertEqual(plan.new_superblock.active_commit_page_no, 12)

    def test_rejects_missing_manifest(self):
        index_page = encode_page(PageType.INDEX, 2, 1, TX1, b"root")
        commit_page = encode_commit_page(
            CommitRecord(1, TX1, COMMIT1, OP1, PageRef(2, 1), 3), 3
        )
        with self.assertRaisesRegex(JPVRFormatError, "MANIFEST"):
            assemble_transaction(genesis_superblock(), (index_page,), commit_page, {})

    def test_rejects_manifest_not_last_staged_page(self):
        index_pages = tuple(
            encode_page(PageType.INDEX, i, 1, TX1, b"root")
            for i in range(2, 9)
        )
        manifest = Manifest(
            UUID, 1,
            {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
        )
        manifest_page = encode_manifest_page(manifest, 9, TX1)
        trailing = encode_page(PageType.DATA, 10, 1, TX1, b"late-data")
        commit = encode_commit_page(
            CommitRecord(1, TX1, COMMIT1, OP1, PageRef(9, 1), 11), 11
        )
        with self.assertRaisesRegex(JPVRFormatError, "final staged"):
            assemble_transaction(genesis_superblock(), index_pages + (manifest_page, trailing), commit, {})

    def test_rejects_duplicate_manifest_pages(self):
        manifest = Manifest(
            UUID, 1,
            {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
        )
        pages = tuple(
            encode_page(PageType.INDEX, i, 1, TX1, b"root")
            for i in range(2, 9)
        )
        m1 = encode_manifest_page(manifest, 9, TX1)
        m2 = encode_manifest_page(manifest, 10, TX1)
        commit = encode_commit_page(
            CommitRecord(1, TX1, COMMIT1, OP1, PageRef(10, 1), 11), 11
        )
        with self.assertRaisesRegex(JPVRFormatError, "exactly one MANIFEST"):
            assemble_transaction(genesis_superblock(), pages + (m1, m2), commit, {})

    def test_rejects_page_number_gap(self):
        pages = tuple(
            encode_page(PageType.INDEX, i + 1, 1, TX1, b"root")
            for i in range(2, 9)
        )
        manifest = Manifest(
            UUID, 1,
            {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
        )
        mp = encode_manifest_page(manifest, 9, TX1)
        commit = encode_commit_page(
            CommitRecord(1, TX1, COMMIT1, OP1, PageRef(9, 1), 10), 10
        )
        with self.assertRaisesRegex(JPVRFormatError, "physical page position"):
            assemble_transaction(genesis_superblock(), pages + (mp,), commit, {})

    def test_rejects_staged_generation_mismatch(self):
        pages = tuple(
            encode_page(PageType.INDEX, i, 2, TX1, b"root")
            for i in range(2, 9)
        )
        manifest = Manifest(
            UUID, 1,
            {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
        )
        mp = encode_manifest_page(manifest, 9, TX1)
        commit = encode_commit_page(
            CommitRecord(1, TX1, COMMIT1, OP1, PageRef(9, 1), 10), 10
        )
        with self.assertRaisesRegex(JPVRFormatError, "generation"):
            assemble_transaction(genesis_superblock(), pages + (mp,), commit, {})

    def test_rejects_transaction_id_mismatch(self):
        pages = tuple(
            encode_page(PageType.INDEX, i, 1, TX1, b"root")
            for i in range(2, 9)
        )
        manifest = Manifest(
            UUID, 1,
            {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
        )
        mp = encode_manifest_page(manifest, 9, TX2)
        commit = encode_commit_page(
            CommitRecord(1, TX2, COMMIT1, OP1, PageRef(9, 1), 10), 10
        )
        with self.assertRaisesRegex(JPVRFormatError, "share one transaction_id"):
            assemble_transaction(genesis_superblock(), pages + (mp,), commit, {})

    def test_rejects_commit_not_at_final_physical_page(self):
        plan, _, commit = first_generation()
        with self.assertRaisesRegex(JPVRFormatError, "not COMMIT"):
            assemble_transaction(
                genesis_superblock(), plan.ordered_page_bytes[:-1],
                encode_page(PageType.DATA, 11, 1, TX1, b"not commit"), {},
            )

    def test_rejects_missing_previous_commit_bytes(self):
        first, catalog, _ = first_generation()
        manifest = Manifest(
            UUID, 2,
            {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
        )
        mp = encode_manifest_page(manifest, 11, TX2)
        cp = encode_commit_page(
            CommitRecord(2, TX2, COMMIT2, OP2, PageRef(11, 2), 12,
                         previous_commit_ref=PageRef(10, 1)),
            12,
        )
        with self.assertRaisesRegex(JPVRFormatError, "previous COMMIT page bytes are required"):
            assemble_transaction(first.new_superblock, (mp,), cp, catalog)

    def test_rejects_invalid_or_newer_page_catalog_entry(self):
        first, catalog, _ = first_generation()
        invalid = dict(catalog)
        invalid[10] = encode_page(PageType.DATA, 10, 2, TX2, b"future")
        manifest = Manifest(
            UUID, 2,
            {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)},
        )
        mp = encode_manifest_page(manifest, 11, TX2)
        cp = encode_commit_page(
            CommitRecord(2, TX2, COMMIT2, OP2, PageRef(11, 2), 12,
                         previous_commit_ref=PageRef(10, 1)),
            12,
        )
        with self.assertRaisesRegex(JPVRFormatError, "newer than the current"):
            assemble_transaction(
                first.new_superblock, (mp,), cp, invalid,
                previous_commit_page_bytes=catalog[10],
            )

    def test_rejects_manifest_root_outside_catalog(self):
        roots = {name: PageRef(99, 1) for name in INDEX_ROOT_NAMES}
        manifest = Manifest(UUID, 1, roots)
        mp = encode_manifest_page(manifest, 9, TX1)
        pages = tuple(
            encode_page(PageType.INDEX, i, 1, TX1, b"root")
            for i in range(2, 9)
        )
        cp = encode_commit_page(
            CommitRecord(1, TX1, COMMIT1, OP1, PageRef(9, 1), 10), 10
        )
        with self.assertRaisesRegex(JPVRFormatError, "outside durable prefix"):
            assemble_transaction(genesis_superblock(), pages + (mp,), cp, {})

    def test_plan_revalidation_accepts_unchanged_plan(self):
        plan, catalog, _ = first_generation()
        self.assertIsNone(
            validate_transaction_assembly_plan(plan, genesis_superblock(), {})
        )

    def test_plan_revalidation_rejects_tampered_metadata(self):
        plan, _, _ = first_generation()
        tampered = replace(plan, manifest_page_no=plan.manifest_page_no + 1)
        with self.assertRaisesRegex(JPVRFormatError, "fields are inconsistent"):
            validate_transaction_assembly_plan(tampered, genesis_superblock(), {})

    def test_failed_assembly_does_not_mutate_catalog(self):
        plan, catalog, _ = first_generation()
        before = dict(catalog)
        damaged_commit = bytearray(plan.ordered_page_bytes[-1])
        damaged_commit[-1] ^= 1
        with self.assertRaisesRegex(JPVRFormatError, "CRC32C"):
            assemble_transaction(
                genesis_superblock(), plan.ordered_page_bytes[:-1],
                bytes(damaged_commit), {},
            )
        self.assertEqual(catalog, before)

    def test_rejects_empty_staged_pages(self):
        with self.assertRaisesRegex(JPVRFormatError, "non-empty"):
            assemble_transaction(genesis_superblock(), (), b"", {})


if __name__ == "__main__":
    unittest.main(verbosity=2)
