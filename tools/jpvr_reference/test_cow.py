import unittest

from .codec import JPVRFormatError
from .cow import cow_upsert_index, validate_index_tree
from .index import (
    IndexChild,
    IndexEntry,
    IndexNode,
    decode_index_page,
    encode_index_page,
    lookup_index,
)
from .manifest import PageRef


TREE = bytes.fromhex("00112233445566778899aabbccddeeff")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")


def leaf_page(page_no, entries, generation=1):
    node = IndexNode(TREE, 0, tuple(IndexEntry(key, value) for key, value in entries))
    return encode_index_page(node, page_no, generation, TX1)


def empty_snapshot():
    catalog = {2: leaf_page(2, [])}
    return PageRef(2, 1), catalog, 2


class IndexTreeValidationTests(unittest.TestCase):
    def test_validates_single_leaf_and_reports_stats(self):
        root = PageRef(2, 1)
        catalog = {2: leaf_page(2, [(b"a", b"A"), (b"z", b"Z")])}
        stats = validate_index_tree(
            root, catalog, tree_id=TREE, durable_tail_page_no=2
        )
        self.assertEqual((stats.page_count, stats.leaf_count, stats.internal_count), (1, 1, 0))
        self.assertEqual(stats.entry_count, 2)
        self.assertEqual((stats.min_key, stats.max_key, stats.height), (b"a", b"z", 1))

    def test_allows_empty_root_leaf_only(self):
        root, catalog, tail = empty_snapshot()
        stats = validate_index_tree(
            root, catalog, tree_id=TREE, durable_tail_page_no=tail
        )
        self.assertEqual(stats.entry_count, 0)
        self.assertIsNone(stats.min_key)

    def test_rejects_wrong_separator(self):
        catalog = {
            2: leaf_page(2, [(b"a", b"A")]),
            3: leaf_page(3, [(b"m", b"M")]),
            4: encode_index_page(
                IndexNode(TREE, 1, first_child=PageRef(2, 1),
                          children=(IndexChild(b"n", PageRef(3, 1)),)),
                4, 2, TX2,
            ),
        }
        with self.assertRaisesRegex(JPVRFormatError, "separator"):
            validate_index_tree(
                PageRef(4, 2), catalog, tree_id=TREE, durable_tail_page_no=4
            )

    def test_rejects_shared_child_page(self):
        catalog = {
            2: leaf_page(2, [(b"a", b"A")]),
            3: encode_index_page(
                IndexNode(TREE, 1, first_child=PageRef(2, 1),
                          children=(IndexChild(b"m", PageRef(2, 1)),)),
                3, 2, TX2,
            ),
        }
        with self.assertRaisesRegex(JPVRFormatError, "cycle or shared"):
            validate_index_tree(
                PageRef(3, 2), catalog, tree_id=TREE, durable_tail_page_no=3
            )

    def test_rejects_child_newer_than_parent(self):
        catalog = {
            2: leaf_page(2, [(b"a", b"A")], generation=3),
            3: encode_index_page(
                IndexNode(TREE, 1, first_child=PageRef(2, 3),
                          children=(IndexChild(b"m", PageRef(4, 1)),)),
                3, 2, TX2,
            ),
            4: leaf_page(4, [(b"m", b"M")], generation=1),
        }
        with self.assertRaisesRegex(JPVRFormatError, "newer than its parent"):
            validate_index_tree(
                PageRef(3, 2), catalog, tree_id=TREE, durable_tail_page_no=4
            )

    def test_rejects_child_level_mismatch(self):
        child = IndexNode(TREE, 1, first_child=PageRef(2, 1),
                          children=(IndexChild(b"z", PageRef(3, 1)),))
        catalog = {
            2: leaf_page(2, [(b"a", b"A")]),
            3: leaf_page(3, [(b"z", b"Z")]),
            4: encode_index_page(
                IndexNode(TREE, 2, first_child=PageRef(5, 1),
                          children=(IndexChild(b"z", PageRef(6, 1)),)),
                4, 2, TX2,
            ),
            5: encode_index_page(child, 5, 1, TX1),
            6: leaf_page(6, [(b"z", b"Z")]),
        }
        with self.assertRaisesRegex(JPVRFormatError, "child level"):
            validate_index_tree(
                PageRef(4, 2), catalog, tree_id=TREE, durable_tail_page_no=6
            )


class CowIndexUpsertTests(unittest.TestCase):
    def test_insert_into_empty_root_leaf(self):
        root, catalog, tail = empty_snapshot()
        plan = cow_upsert_index(
            root, catalog, b"hello", b"world",
            tree_id=TREE, durable_tail_page_no=tail,
            first_page_no=tail + 1, commit_generation=2, transaction_id=TX2,
        )
        self.assertEqual(plan.page_count, 1)
        self.assertEqual(plan.new_root, PageRef(3, 2))
        new_catalog = dict(catalog)
        new_catalog.update({page.page_no: page.page_bytes for page in plan.pages})
        self.assertEqual(
            lookup_index(plan.new_root, new_catalog, b"hello", tree_id=TREE,
                         durable_tail_page_no=3),
            b"world",
        )
        self.assertEqual(plan.new_stats.entry_count, 1)

    def test_replace_value_preserves_old_snapshot(self):
        root = PageRef(2, 1)
        catalog = {2: leaf_page(2, [(b"k", b"old"), (b"x", b"X")])}
        plan = cow_upsert_index(
            root, catalog, b"k", b"new",
            tree_id=TREE, durable_tail_page_no=2, first_page_no=3,
            commit_generation=2, transaction_id=TX2,
        )
        new_catalog = dict(catalog)
        new_catalog.update({page.page_no: page.page_bytes for page in plan.pages})
        self.assertTrue(plan.replaced_existing_key)
        self.assertEqual(plan.new_stats.entry_count, 2)
        self.assertEqual(
            lookup_index(root, catalog, b"k", tree_id=TREE, durable_tail_page_no=2),
            b"old",
        )
        self.assertEqual(
            lookup_index(plan.new_root, new_catalog, b"k", tree_id=TREE,
                         durable_tail_page_no=plan.last_page_no),
            b"new",
        )

    def test_leaf_split_creates_new_root_and_keeps_old_root(self):
        root = PageRef(2, 1)
        catalog = {2: leaf_page(2, [(b"a", b"A" * 1800), (b"z", b"Z" * 1800)])}
        plan = cow_upsert_index(
            root, catalog, b"m", b"M" * 1800,
            tree_id=TREE, durable_tail_page_no=2, first_page_no=3,
            commit_generation=2, transaction_id=TX2,
        )
        self.assertEqual(plan.page_count, 3)
        root_page = decode_index_page(plan.pages[-1].page_bytes)
        self.assertEqual(root_page.node.level, 1)
        new_catalog = dict(catalog)
        new_catalog.update({page.page_no: page.page_bytes for page in plan.pages})
        for key, expected in ((b"a", b"A" * 1800), (b"m", b"M" * 1800), (b"z", b"Z" * 1800)):
            self.assertEqual(
                lookup_index(plan.new_root, new_catalog, key, tree_id=TREE,
                             durable_tail_page_no=plan.last_page_no),
                expected,
            )
        self.assertEqual(
            lookup_index(root, catalog, b"z", tree_id=TREE, durable_tail_page_no=2),
            b"Z" * 1800,
        )

    def test_path_copy_reuses_unmodified_subtree(self):
        catalog = {
            2: leaf_page(2, [(b"a", b"A")]),
            3: leaf_page(3, [(b"m", b"M")]),
            4: encode_index_page(
                IndexNode(TREE, 1, first_child=PageRef(2, 1),
                          children=(IndexChild(b"m", PageRef(3, 1)),)),
                4, 1, TX1,
            ),
        }
        plan = cow_upsert_index(
            PageRef(4, 1), catalog, b"b", b"B",
            tree_id=TREE, durable_tail_page_no=4, first_page_no=5,
            commit_generation=2, transaction_id=TX2,
        )
        self.assertIn(3, plan.new_stats.page_numbers)
        self.assertNotIn(4, plan.new_stats.page_numbers)
        self.assertEqual(plan.page_count, 2)
        self.assertEqual(plan.old_stats.page_numbers, (2, 3, 4))

    def test_internal_split_grows_root_without_losing_old_snapshot(self):
        a0 = b"a" + b"0" * 1023
        a1 = b"a" + b"1" * 1023
        b0 = b"b" + b"0" * 1023
        c0 = b"c" + b"0" * 1023
        d0 = b"d" + b"0" * 1023
        catalog = {
            2: leaf_page(2, [(a0, b"A" * 2048)]),
            3: leaf_page(3, [(b0, b"B" * 2048)]),
            4: leaf_page(4, [(c0, b"C" * 2048)]),
            5: leaf_page(5, [(d0, b"D" * 2048)]),
            6: encode_index_page(
                IndexNode(
                    TREE, 1, first_child=PageRef(2, 1),
                    children=(
                        IndexChild(b0, PageRef(3, 1)),
                        IndexChild(c0, PageRef(4, 1)),
                        IndexChild(d0, PageRef(5, 1)),
                    ),
                ),
                6, 1, TX1,
            ),
        }
        old_root = PageRef(6, 1)
        plan = cow_upsert_index(
            old_root, catalog, a1, b"A1",
            tree_id=TREE, durable_tail_page_no=6, first_page_no=7,
            commit_generation=2, transaction_id=TX2,
        )
        self.assertEqual(plan.new_stats.height, 3)
        self.assertEqual(plan.new_stats.entry_count, 5)
        self.assertEqual(plan.page_count, 5)
        self.assertEqual(plan.page_numbers, (7, 8, 9, 10, 11))
        new_catalog = dict(catalog)
        new_catalog.update({page.page_no: page.page_bytes for page in plan.pages})
        for key, expected in ((a0, b"A" * 2048), (a1, b"A1"), (b0, b"B" * 2048), (c0, b"C" * 2048), (d0, b"D" * 2048)):
            self.assertEqual(
                lookup_index(plan.new_root, new_catalog, key, tree_id=TREE,
                             durable_tail_page_no=plan.last_page_no),
                expected,
            )
        self.assertIsNone(
            lookup_index(old_root, catalog, a1, tree_id=TREE, durable_tail_page_no=6)
        )
        self.assertEqual(plan.new_stats.page_count, 8)

    def test_rejects_old_or_equal_commit_generation(self):
        root = PageRef(2, 1)
        catalog = {2: leaf_page(2, [])}
        with self.assertRaisesRegex(JPVRFormatError, "must advance"):
            cow_upsert_index(
                root, catalog, b"k", b"v", tree_id=TREE,
                durable_tail_page_no=2, first_page_no=3,
                commit_generation=1, transaction_id=TX2,
            )

    def test_rejects_non_contiguous_page_allocation(self):
        root = PageRef(2, 1)
        catalog = {2: leaf_page(2, [])}
        with self.assertRaisesRegex(JPVRFormatError, "immediately follow"):
            cow_upsert_index(
                root, catalog, b"k", b"v", tree_id=TREE,
                durable_tail_page_no=2, first_page_no=4,
                commit_generation=2, transaction_id=TX2,
            )

    def test_rejects_wrong_tree_identity(self):
        root = PageRef(2, 1)
        catalog = {2: leaf_page(2, [])}
        with self.assertRaisesRegex(JPVRFormatError, "tree ID"):
            cow_upsert_index(
                root, catalog, b"k", b"v", tree_id=bytes(16),
                durable_tail_page_no=2, first_page_no=3,
                commit_generation=2, transaction_id=TX2,
            )

    def test_rejects_value_over_limit_without_mutating_catalog(self):
        root = PageRef(2, 1)
        catalog = {2: leaf_page(2, [])}
        original = dict(catalog)
        with self.assertRaisesRegex(JPVRFormatError, "value exceeds"):
            cow_upsert_index(
                root, catalog, b"k", b"x" * 2049, tree_id=TREE,
                durable_tail_page_no=2, first_page_no=3,
                commit_generation=2, transaction_id=TX2,
            )
        self.assertEqual(catalog, original)

    def test_new_pages_are_contiguous_and_use_new_generation(self):
        root = PageRef(2, 1)
        catalog = {2: leaf_page(2, [(b"a", b"A" * 1800), (b"z", b"Z" * 1800)])}
        plan = cow_upsert_index(
            root, catalog, b"m", b"M" * 1800,
            tree_id=TREE, durable_tail_page_no=2, first_page_no=3,
            commit_generation=9, transaction_id=TX2,
        )
        self.assertEqual(plan.page_numbers, tuple(range(3, 3 + plan.page_count)))
        self.assertTrue(all(decode_index_page(p.page_bytes).commit_generation == 9 for p in plan.pages))
        self.assertEqual(plan.new_root.expected_commit_generation, 9)


if __name__ == "__main__":
    unittest.main()
