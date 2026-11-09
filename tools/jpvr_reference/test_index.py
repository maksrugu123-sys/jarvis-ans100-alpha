import unittest
import struct

from .codec import JPVRFormatError, PageType, encode_page
from .index import (
    INDEX_NODE_HEADER_BYTES, MAX_INDEX_DEPTH, IndexChild, IndexEntry, IndexNode, PageRef,
    decode_index_node, decode_index_page, encode_index_node, encode_index_page,
    lookup_index,
)


TREE = bytes.fromhex("00112233445566778899aabbccddeeff")
TX = bytes.fromhex("11111111111111111111111111111111")


class IndexNodeCodecTests(unittest.TestCase):
    def test_empty_leaf_round_trip(self):
        node = IndexNode(TREE, 0)
        self.assertEqual(decode_index_node(encode_index_node(node)), node)

    def test_leaf_round_trip_and_ordered_values(self):
        node = IndexNode(TREE, 0, (IndexEntry(b"a", b"one"), IndexEntry(b"b", b"two")))
        self.assertEqual(decode_index_node(encode_index_node(node)), node)

    def test_leaf_rejects_duplicate_or_unsorted_keys(self):
        for entries in (
            (IndexEntry(b"a", b"1"), IndexEntry(b"a", b"2")),
            (IndexEntry(b"b", b"1"), IndexEntry(b"a", b"2")),
        ):
            with self.subTest(entries=entries), self.assertRaisesRegex(JPVRFormatError, "strictly increasing"):
                encode_index_node(IndexNode(TREE, 0, entries))

    def test_internal_round_trip(self):
        node = IndexNode(
            TREE, 1, first_child=PageRef(2, 1),
            children=(IndexChild(b"m", PageRef(3, 1)), IndexChild(b"t", PageRef(4, 2))),
        )
        self.assertEqual(decode_index_node(encode_index_node(node)), node)

    def test_encoder_enforces_decoder_maximum_depth(self):
        max_node = IndexNode(
            TREE, MAX_INDEX_DEPTH - 1,
            first_child=PageRef(2, 1),
            children=(IndexChild(b"m", PageRef(3, 1)),),
        )
        self.assertEqual(decode_index_node(encode_index_node(max_node)), max_node)
        for level in (MAX_INDEX_DEPTH, 0xFF):
            node = IndexNode(
                TREE, level,
                first_child=PageRef(2, 1),
                children=(IndexChild(b"m", PageRef(3, 1)),),
            )
            with self.subTest(level=level):
                with self.assertRaisesRegex(JPVRFormatError, "maximum depth"):
                    encode_index_node(node)
    def test_leaf_rejects_nonzero_padding(self):
        raw = bytearray(encode_index_node(IndexNode(TREE, 0, (IndexEntry(b"a", b"x"),))))
        raw[-1] = 1
        with self.assertRaisesRegex(JPVRFormatError, "padding"):
            decode_index_node(raw)

    def test_rejects_unknown_kind_and_reserved(self):
        raw = bytearray(encode_index_node(IndexNode(TREE, 0)))
        raw[6] = 9
        with self.assertRaisesRegex(JPVRFormatError, "kind"):
            decode_index_node(raw)
        raw = bytearray(encode_index_node(IndexNode(TREE, 0)))
        struct.pack_into("<H", raw, 10, 1)
        with self.assertRaisesRegex(JPVRFormatError, "reserved"):
            decode_index_node(raw)

    def test_internal_rejects_nonzero_reserved_entry_bytes(self):
        node = IndexNode(TREE, 1, first_child=PageRef(2, 1), children=(IndexChild(b"m", PageRef(3, 1)),))
        raw = bytearray(encode_index_node(node))
        raw[INDEX_NODE_HEADER_BYTES + 2] = 1
        with self.assertRaisesRegex(JPVRFormatError, "reserved bytes"):
            decode_index_node(raw)

    def test_internal_rejects_zero_separator_count(self):
        node = IndexNode(TREE, 1, first_child=PageRef(2, 1), children=(IndexChild(b"m", PageRef(3, 1)),))
        raw = bytearray(encode_index_node(node))
        struct.pack_into("<H", raw, 8, 0)
        with self.assertRaisesRegex(JPVRFormatError, "separator children"):
            decode_index_node(raw)

    def test_rejects_truncated_payload(self):
        with self.assertRaisesRegex(JPVRFormatError, "length"):
            decode_index_node(b"JIDX")

    def test_page_round_trip(self):
        node = IndexNode(TREE, 0, (IndexEntry(b"key", b"value"),))
        page = encode_index_page(node, 8, 2, TX)
        decoded = decode_index_page(page, expected_page_no=8)
        self.assertEqual(decoded.node, node)
        self.assertEqual(decoded.commit_generation, 2)

    def test_page_rejects_wrong_type(self):
        page = encode_page(PageType.DATA, 8, 2, TX, b"")
        with self.assertRaisesRegex(JPVRFormatError, "not INDEX"):
            decode_index_page(page)

    def test_lookup_leaf_snapshot(self):
        node = IndexNode(TREE, 0, (IndexEntry(b"a", b"A"), IndexEntry(b"z", b"Z")))
        catalog = {2: encode_index_page(node, 2, 1, TX)}
        self.assertEqual(lookup_index(PageRef(2, 1), catalog, b"z", tree_id=TREE, durable_tail_page_no=2), b"Z")
        self.assertIsNone(lookup_index(PageRef(2, 1), catalog, b"m", tree_id=TREE, durable_tail_page_no=2))

    def test_lookup_descends_using_lower_bound_separators(self):
        left = IndexNode(TREE, 0, (IndexEntry(b"a", b"A"), IndexEntry(b"l", b"L")))
        right = IndexNode(TREE, 0, (IndexEntry(b"m", b"M"), IndexEntry(b"z", b"Z")))
        root = IndexNode(TREE, 1, first_child=PageRef(2, 1), children=(IndexChild(b"m", PageRef(3, 1)),))
        catalog = {
            2: encode_index_page(left, 2, 1, TX),
            3: encode_index_page(right, 3, 1, TX),
            4: encode_index_page(root, 4, 2, TX),
        }
        self.assertEqual(lookup_index(PageRef(4, 2), catalog, b"m", tree_id=TREE, durable_tail_page_no=4), b"M")
        self.assertEqual(lookup_index(PageRef(4, 2), catalog, b"l", tree_id=TREE, durable_tail_page_no=4), b"L")

    def test_lookup_rejects_stale_page_ref(self):
        node = IndexNode(TREE, 0)
        catalog = {2: encode_index_page(node, 2, 2, TX)}
        with self.assertRaisesRegex(JPVRFormatError, "generation mismatch"):
            lookup_index(PageRef(2, 1), catalog, b"x", tree_id=TREE, durable_tail_page_no=2)

    def test_lookup_rejects_child_level_mismatch(self):
        leaf = IndexNode(TREE, 0, (IndexEntry(b"a", b"A"),))
        # The root declares level 2, but its referenced child is a leaf (level 0).
        root = IndexNode(TREE, 2, first_child=PageRef(2, 1), children=(IndexChild(b"m", PageRef(3, 1)),))
        catalog = {
            2: encode_index_page(IndexNode(TREE, 1, first_child=PageRef(4, 1), children=(IndexChild(b"a", PageRef(5, 1)),)), 2, 1, TX),
            3: encode_index_page(leaf, 3, 1, TX),
            4: encode_index_page(leaf, 4, 1, TX),
            5: encode_index_page(leaf, 5, 1, TX),
            6: encode_index_page(root, 6, 2, TX),
        }
        with self.assertRaisesRegex(JPVRFormatError, "child level"):
            lookup_index(PageRef(6, 2), catalog, b"z", tree_id=TREE, durable_tail_page_no=6)

    def test_lookup_rejects_wrong_tree_id(self):
        catalog = {2: encode_index_page(IndexNode(TREE, 0), 2, 1, TX)}
        with self.assertRaisesRegex(JPVRFormatError, "tree ID mismatch"):
            lookup_index(PageRef(2, 1), catalog, b"x", tree_id=bytes(16), durable_tail_page_no=2)
