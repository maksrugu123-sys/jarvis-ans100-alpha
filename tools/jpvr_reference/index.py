"""Strict JPVR v1 copy-on-write B+ tree node codec and snapshot lookup.

This module deliberately implements immutable node encoding/decoding and
read-only lookup only. It does not allocate pages, mutate trees, or commit.
"""
from __future__ import annotations

from dataclasses import dataclass
import struct
from collections.abc import Mapping

from .codec import (
    PAGE_PAYLOAD_BYTES, JPVRFormatError, PageType, decode_page, encode_page,
    _fixed_bytes, _require_uint,
)
from .manifest import PageRef


INDEX_NODE_MAGIC = b"JIDX"
INDEX_NODE_VERSION = 1
INDEX_NODE_HEADER = struct.Struct("<4sHBBHH16sQQ")
INDEX_NODE_HEADER_BYTES = INDEX_NODE_HEADER.size  # 44 bytes
LEAF_ENTRY_HEADER = struct.Struct("<HH")
INTERNAL_ENTRY_HEADER = struct.Struct("<H6xQQ")
MAX_INDEX_KEY_BYTES = 1024
MAX_INDEX_VALUE_BYTES = 2048
MAX_INDEX_DEPTH = 64


@dataclass(frozen=True)
class IndexEntry:
    key: bytes
    value: bytes


@dataclass(frozen=True)
class IndexChild:
    separator: bytes
    page_ref: PageRef


@dataclass(frozen=True)
class IndexNode:
    tree_id: bytes
    level: int
    entries: tuple[IndexEntry, ...] = ()
    first_child: PageRef | None = None
    children: tuple[IndexChild, ...] = ()

    @property
    def is_leaf(self) -> bool:
        return self.level == 0


@dataclass(frozen=True)
class DecodedIndexPage:
    page_no: int
    commit_generation: int
    transaction_id: bytes
    node: IndexNode


def _validate_key(key: bytes) -> bytes:
    if not isinstance(key, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("index key must be bytes-like")
    result = bytes(key)
    if not result or len(result) > MAX_INDEX_KEY_BYTES:
        raise JPVRFormatError("index key length is outside supported bounds")
    return result


def _validate_ref(ref: PageRef) -> None:
    if not isinstance(ref, PageRef):
        raise JPVRFormatError("index child must be a PageRef")
    _require_uint("PageRef.page_no", ref.page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("PageRef.expected_commit_generation", ref.expected_commit_generation, 0xFFFFFFFFFFFFFFFF)
    if ref.page_no < 2 or ref.expected_commit_generation < 1:
        raise JPVRFormatError("invalid index child PageRef")


def _pad8(data: bytes) -> bytes:
    return data + bytes((-len(data)) % 8)


def _validate_node(node: IndexNode) -> bytes:
    if not isinstance(node, IndexNode):
        raise JPVRFormatError("node must be an IndexNode")
    tree_id = _fixed_bytes("tree_id", node.tree_id, 16)
    _require_uint("node.level", node.level, 255)
    if node.level == 0:
        if node.first_child is not None or node.children:
            raise JPVRFormatError("leaf node cannot contain child references")
        previous = None
        for entry in node.entries:
            if not isinstance(entry, IndexEntry):
                raise JPVRFormatError("leaf entries must be IndexEntry values")
            key = _validate_key(entry.key)
            if previous is not None and key <= previous:
                raise JPVRFormatError("index keys must be strictly increasing")
            if not isinstance(entry.value, (bytes, bytearray, memoryview)):
                raise JPVRFormatError("index value must be bytes-like")
            if len(entry.value) > MAX_INDEX_VALUE_BYTES:
                raise JPVRFormatError("index value exceeds supported bounds")
            previous = key
        if len(node.entries) > 0xFFFF:
            raise JPVRFormatError("too many leaf entries")
    else:
        if node.entries:
            raise JPVRFormatError("internal node cannot contain leaf values")
        _validate_ref(node.first_child)
        if not node.children:
            raise JPVRFormatError("internal node must contain separator children")
        if len(node.children) > 0xFFFF:
            raise JPVRFormatError("too many internal separators")
        previous = None
        for child in node.children:
            if not isinstance(child, IndexChild):
                raise JPVRFormatError("internal children must be IndexChild values")
            key = _validate_key(child.separator)
            _validate_ref(child.page_ref)
            if previous is not None and key <= previous:
                raise JPVRFormatError("index separators must be strictly increasing")
            previous = key
    return tree_id


def encode_index_node(node: IndexNode) -> bytes:
    """Encode one INDEX payload; internal separators are lower bounds of right children."""
    tree_id = _validate_node(node)
    first_page = node.first_child.page_no if node.level else 0
    first_generation = node.first_child.expected_commit_generation if node.level else 0
    count = len(node.entries) if node.level == 0 else len(node.children)
    out = bytearray(INDEX_NODE_HEADER.pack(
        INDEX_NODE_MAGIC, INDEX_NODE_VERSION, 0 if node.level == 0 else 1,
        node.level, count, 0, tree_id, first_page, first_generation,
    ))
    if node.level == 0:
        for entry in node.entries:
            key, value = bytes(entry.key), bytes(entry.value)
            raw = LEAF_ENTRY_HEADER.pack(len(key), len(value)) + key + value
            out.extend(_pad8(raw))
    else:
        for child in node.children:
            key = bytes(child.separator)
            ref = child.page_ref
            raw = INTERNAL_ENTRY_HEADER.pack(len(key), ref.page_no, ref.expected_commit_generation) + key
            out.extend(_pad8(raw))
    if len(out) > PAGE_PAYLOAD_BYTES:
        raise JPVRFormatError("index node exceeds one INDEX page payload")
    return bytes(out)


def decode_index_node(payload: bytes | bytearray | memoryview) -> IndexNode:
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("index payload must be bytes-like")
    raw = bytes(payload)
    if len(raw) < INDEX_NODE_HEADER_BYTES or len(raw) > PAGE_PAYLOAD_BYTES:
        raise JPVRFormatError("invalid index node payload length")
    magic, version, kind, level, count, reserved, tree_id, first_page, first_gen = INDEX_NODE_HEADER.unpack_from(raw)
    if magic != INDEX_NODE_MAGIC or version != INDEX_NODE_VERSION:
        raise JPVRFormatError("invalid index node magic or version")
    if reserved != 0 or kind not in (0, 1) or (kind == 0) != (level == 0) or level >= MAX_INDEX_DEPTH:
        raise JPVRFormatError("invalid index node kind, level, or reserved field")
    if kind == 1 and count == 0:
        raise JPVRFormatError("internal node must contain separator children")
    offset = INDEX_NODE_HEADER_BYTES
    if kind == 0:
        if first_page or first_gen:
            raise JPVRFormatError("leaf node child header fields must be zero")
        entries = []
        previous = None
        for _ in range(count):
            if offset + LEAF_ENTRY_HEADER.size > len(raw):
                raise JPVRFormatError("truncated leaf entry header")
            key_len, value_len = LEAF_ENTRY_HEADER.unpack_from(raw, offset)
            end = offset + LEAF_ENTRY_HEADER.size + key_len + value_len
            padded_end = offset + ((end - offset + 7) // 8) * 8
            if not key_len or key_len > MAX_INDEX_KEY_BYTES or value_len > MAX_INDEX_VALUE_BYTES or padded_end > len(raw):
                raise JPVRFormatError("invalid leaf entry lengths")
            key_start = offset + LEAF_ENTRY_HEADER.size
            key = raw[key_start:key_start + key_len]
            value = raw[key_start + key_len:end]
            if previous is not None and key <= previous:
                raise JPVRFormatError("index keys must be strictly increasing")
            if any(raw[end:padded_end]):
                raise JPVRFormatError("index entry padding must be zero")
            entries.append(IndexEntry(key, value))
            previous = key
            offset = padded_end
        if any(raw[offset:]):
            raise JPVRFormatError("index node trailing padding must be zero")
        return IndexNode(tree_id, 0, tuple(entries))
    if first_page < 2 or first_gen < 1:
        raise JPVRFormatError("invalid internal node first child PageRef")
    first_child = PageRef(first_page, first_gen)
    children = []
    previous = None
    for _ in range(count):
        if offset + INTERNAL_ENTRY_HEADER.size > len(raw):
            raise JPVRFormatError("truncated internal entry header")
        key_len, page_no, generation = INTERNAL_ENTRY_HEADER.unpack_from(raw, offset)
        if any(raw[offset + 2:offset + 8]):
            raise JPVRFormatError("internal entry reserved bytes must be zero")
        end = offset + INTERNAL_ENTRY_HEADER.size + key_len
        padded_end = offset + ((end - offset + 7) // 8) * 8
        if not key_len or key_len > MAX_INDEX_KEY_BYTES or padded_end > len(raw):
            raise JPVRFormatError("invalid internal entry lengths")
        key = raw[offset + INTERNAL_ENTRY_HEADER.size:end]
        ref = PageRef(page_no, generation)
        _validate_ref(ref)
        if previous is not None and key <= previous:
            raise JPVRFormatError("index separators must be strictly increasing")
        if any(raw[end:padded_end]):
            raise JPVRFormatError("index entry padding must be zero")
        children.append(IndexChild(key, ref))
        previous = key
        offset = padded_end
    if any(raw[offset:]):
        raise JPVRFormatError("index node trailing padding must be zero")
    return IndexNode(tree_id, level, (), first_child, tuple(children))


def encode_index_page(node: IndexNode, page_no: int, commit_generation: int, transaction_id: bytes) -> bytes:
    return encode_page(PageType.INDEX, page_no, commit_generation, transaction_id, encode_index_node(node))


def decode_index_page(page_bytes: bytes | bytearray | memoryview, *, expected_page_no: int | None = None) -> DecodedIndexPage:
    page = decode_page(page_bytes, expected_page_no=expected_page_no)
    if page.page_type is not PageType.INDEX:
        raise JPVRFormatError("page type is not INDEX")
    return DecodedIndexPage(page.page_no, page.commit_generation, page.transaction_id, decode_index_node(page.payload))


def lookup_index(
    root: PageRef,
    page_catalog: Mapping[int, bytes],
    key: bytes,
    *,
    tree_id: bytes,
    durable_tail_page_no: int,
) -> bytes | None:
    """Read-only lookup from a validated snapshot page catalog; detects cycles and stale refs."""
    target = _validate_key(key)
    expected_tree = _fixed_bytes("tree_id", tree_id, 16)
    _validate_ref(root)
    _require_uint("durable_tail_page_no", durable_tail_page_no, 0xFFFFFFFFFFFFFFFF)
    if not isinstance(page_catalog, Mapping):
        raise JPVRFormatError("page_catalog must be a mapping")
    ref = root
    visited = set()
    parent_generation = None
    expected_level = None
    for _ in range(MAX_INDEX_DEPTH):
        if ref.page_no > durable_tail_page_no:
            raise JPVRFormatError("index child is outside durable prefix")
        if ref.page_no in visited:
            raise JPVRFormatError("index tree contains a page cycle")
        visited.add(ref.page_no)
        encoded = page_catalog.get(ref.page_no)
        if encoded is None:
            raise JPVRFormatError("index references a missing page")
        page = decode_index_page(encoded, expected_page_no=ref.page_no)
        if page.commit_generation != ref.expected_commit_generation:
            raise JPVRFormatError("index PageRef generation mismatch")
        if parent_generation is not None and page.commit_generation > parent_generation:
            raise JPVRFormatError("child index page is newer than its parent")
        if page.node.tree_id != expected_tree:
            raise JPVRFormatError("index tree ID mismatch")
        node = page.node
        if expected_level is not None and node.level != expected_level:
            raise JPVRFormatError("index child level does not match parent")
        if node.level == 0:
            for entry in node.entries:
                if entry.key == target:
                    return entry.value
                if entry.key > target:
                    break
            return None
        child = node.first_child
        for separator in node.children:
            if target < separator.separator:
                break
            child = separator.page_ref
        ref = child
        expected_level = node.level - 1
        parent_generation = page.commit_generation
    raise JPVRFormatError("index tree exceeds maximum depth")
