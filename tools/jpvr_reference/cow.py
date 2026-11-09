"""Pure in-memory copy-on-write B+ tree upsert planner for JPVR v1.

The planner creates immutable INDEX pages and a new root reference. It does not
write files, reserve physical offsets, flush data, or commit a container.
"""
from __future__ import annotations

from bisect import bisect_left
from collections.abc import Mapping
from dataclasses import dataclass

from .codec import (
    JPVRFormatError,
    _fixed_bytes,
    _require_uint,
)
from .index import (
    MAX_INDEX_DEPTH,
    MAX_INDEX_VALUE_BYTES,
    IndexChild,
    IndexEntry,
    IndexNode,
    _validate_key,
    _validate_ref,
    decode_index_page,
    encode_index_node,
    encode_index_page,
)
from .manifest import PageRef


@dataclass(frozen=True)
class IndexTreeStats:
    page_count: int
    leaf_count: int
    internal_count: int
    entry_count: int
    height: int
    min_key: bytes | None
    max_key: bytes | None
    page_numbers: tuple[int, ...]


@dataclass(frozen=True)
class NewIndexPage:
    page_no: int
    page_bytes: bytes
    node: IndexNode


@dataclass(frozen=True)
class CowIndexPlan:
    old_root: PageRef
    new_root: PageRef
    commit_generation: int
    pages: tuple[NewIndexPage, ...]
    replaced_existing_key: bool
    old_stats: IndexTreeStats
    new_stats: IndexTreeStats

    @property
    def page_count(self) -> int:
        return len(self.pages)

    @property
    def first_page_no(self) -> int:
        return self.pages[0].page_no

    @property
    def last_page_no(self) -> int:
        return self.pages[-1].page_no

    @property
    def page_numbers(self) -> tuple[int, ...]:
        return tuple(page.page_no for page in self.pages)


def validate_index_tree(
    root: PageRef,
    page_catalog: Mapping[int, bytes],
    *,
    tree_id: bytes,
    durable_tail_page_no: int,
) -> IndexTreeStats:
    """Validate every reachable node and all B+ tree separator/range invariants."""
    _validate_ref(root)
    expected_tree = _fixed_bytes("tree_id", tree_id, 16)
    _require_uint("durable_tail_page_no", durable_tail_page_no, 0xFFFFFFFFFFFFFFFF)
    if not isinstance(page_catalog, Mapping):
        raise JPVRFormatError("page_catalog must be a mapping")

    visited: set[int] = set()
    leaf_count = 0
    internal_count = 0
    entry_count = 0
    max_level = 0

    def walk(
        ref: PageRef,
        *,
        parent_generation: int | None,
        expected_level: int | None,
        is_root: bool,
    ) -> tuple[bytes | None, bytes | None]:
        nonlocal leaf_count, internal_count, entry_count, max_level
        _validate_ref(ref)
        if ref.page_no > durable_tail_page_no:
            raise JPVRFormatError("index page is outside durable prefix")
        if ref.page_no in visited:
            raise JPVRFormatError("index tree contains a cycle or shared child page")
        visited.add(ref.page_no)

        encoded = page_catalog.get(ref.page_no)
        if encoded is None:
            raise JPVRFormatError("index tree references a missing page")
        page = decode_index_page(encoded, expected_page_no=ref.page_no)
        if page.commit_generation != ref.expected_commit_generation:
            raise JPVRFormatError("index PageRef generation mismatch")
        if parent_generation is not None and page.commit_generation > parent_generation:
            raise JPVRFormatError("child index page is newer than its parent")
        node = page.node
        if node.tree_id != expected_tree:
            raise JPVRFormatError("index tree ID mismatch")
        if expected_level is not None and node.level != expected_level:
            raise JPVRFormatError("index child level does not match parent")
        max_level = max(max_level, node.level)

        if node.level == 0:
            leaf_count += 1
            entry_count += len(node.entries)
            if not node.entries:
                if not is_root:
                    raise JPVRFormatError("non-root index leaf cannot be empty")
                return None, None
            return node.entries[0].key, node.entries[-1].key

        internal_count += 1
        child_refs = [(None, node.first_child)] + [
            (child.separator, child.page_ref) for child in node.children
        ]
        child_ranges = []
        for _, child_ref in child_refs:
            child_ranges.append(walk(
                child_ref,
                parent_generation=page.commit_generation,
                expected_level=node.level - 1,
                is_root=False,
            ))

        previous_max: bytes | None = None
        for index, ((lower_bound, _), (child_min, child_max)) in enumerate(
            zip(child_refs, child_ranges)
        ):
            if child_min is None or child_max is None:
                raise JPVRFormatError("internal index node cannot reference an empty subtree")
            if index:
                if lower_bound != child_min:
                    raise JPVRFormatError("separator does not equal right subtree minimum")
                if previous_max is None or previous_max >= lower_bound:
                    raise JPVRFormatError("adjacent index child key ranges overlap")
            previous_max = child_max
        return child_ranges[0][0], child_ranges[-1][1]

    min_key, max_key = walk(
        root, parent_generation=None, expected_level=None, is_root=True
    )
    return IndexTreeStats(
        page_count=len(visited),
        leaf_count=leaf_count,
        internal_count=internal_count,
        entry_count=entry_count,
        height=max_level + 1,
        min_key=min_key,
        max_key=max_key,
        page_numbers=tuple(sorted(visited)),
    )


def _node_payload_size(node: IndexNode) -> int | None:
    try:
        return len(encode_index_node(node))
    except JPVRFormatError as exc:
        if str(exc) == "index node exceeds one INDEX page payload":
            return None
        raise


def _internal_node(tree_id: bytes, level: int, pairs: list[tuple[bytes | None, PageRef]]) -> IndexNode:
    if len(pairs) < 2 or pairs[0][0] is not None:
        raise JPVRFormatError("internal split would create an invalid child layout")
    if any(separator is None for separator, _ in pairs[1:]):
        raise JPVRFormatError("internal child separator is missing")
    return IndexNode(
        tree_id,
        level,
        first_child=pairs[0][1],
        children=tuple(
            IndexChild(separator, ref)
            for separator, ref in pairs[1:]
        ),
    )


def cow_upsert_index(
    root: PageRef,
    page_catalog: Mapping[int, bytes],
    key: bytes,
    value: bytes,
    *,
    tree_id: bytes,
    durable_tail_page_no: int,
    first_page_no: int,
    commit_generation: int,
    transaction_id: bytes,
) -> CowIndexPlan:
    """Plan one insert/replace by path-copying nodes; the input snapshot stays intact."""
    _validate_ref(root)
    target = _validate_key(key)
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("index value must be bytes-like")
    payload_value = bytes(value)
    if len(payload_value) > MAX_INDEX_VALUE_BYTES:
        raise JPVRFormatError("index value exceeds supported bounds")
    expected_tree = _fixed_bytes("tree_id", tree_id, 16)
    tx_id = _fixed_bytes("transaction_id", transaction_id, 16)
    _require_uint("durable_tail_page_no", durable_tail_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("first_page_no", first_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("commit_generation", commit_generation, 0xFFFFFFFFFFFFFFFF)
    if durable_tail_page_no == 0xFFFFFFFFFFFFFFFF or first_page_no != durable_tail_page_no + 1:
        raise JPVRFormatError("first_page_no must immediately follow the durable prefix")
    if commit_generation <= root.expected_commit_generation:
        raise JPVRFormatError("new commit generation must advance beyond the old root")
    if not isinstance(page_catalog, Mapping):
        raise JPVRFormatError("page_catalog must be a mapping")

    old_stats = validate_index_tree(
        root, page_catalog, tree_id=expected_tree,
        durable_tail_page_no=durable_tail_page_no,
    )
    staged: list[NewIndexPage] = []
    next_page_no = first_page_no

    def allocate(node: IndexNode) -> PageRef:
        nonlocal next_page_no
        if next_page_no > 0xFFFFFFFFFFFFFFFF:
            raise JPVRFormatError("INDEX page number space is exhausted")
        page_no = next_page_no
        page_bytes = encode_index_page(
            node, page_no, commit_generation, tx_id
        )
        staged.append(NewIndexPage(page_no, page_bytes, node))
        next_page_no += 1
        return PageRef(page_no, commit_generation)

    def split_leaf(entries: list[IndexEntry]) -> tuple[PageRef, bytes, PageRef]:
        choices = []
        for split_at in range(1, len(entries)):
            left_node = IndexNode(expected_tree, 0, tuple(entries[:split_at]))
            right_node = IndexNode(expected_tree, 0, tuple(entries[split_at:]))
            left_size = _node_payload_size(left_node)
            right_size = _node_payload_size(right_node)
            if left_size is not None and right_size is not None:
                choices.append((abs(left_size - right_size), split_at, left_node, right_node))
        if not choices:
            raise JPVRFormatError("leaf cannot be split into two valid INDEX pages")
        _, split_at, left_node, right_node = min(choices, key=lambda item: (item[0], item[1]))
        separator = entries[split_at].key
        return allocate(left_node), separator, allocate(right_node)

    def split_internal(
        level: int,
        pairs: list[tuple[bytes | None, PageRef]],
    ) -> tuple[PageRef, bytes, PageRef]:
        # Each resulting INTERNAL node must retain at least two child pointers.
        choices = []
        for split_at in range(2, len(pairs) - 1):
            promoted = pairs[split_at][0]
            if promoted is None:
                continue
            left_node = _internal_node(expected_tree, level, pairs[:split_at])
            right_node = _internal_node(expected_tree, level, pairs[split_at:])
            left_size = _node_payload_size(left_node)
            right_size = _node_payload_size(right_node)
            if left_size is not None and right_size is not None:
                choices.append((
                    abs(left_size - right_size), split_at,
                    promoted, left_node, right_node,
                ))
        if not choices:
            raise JPVRFormatError("internal node overflow cannot be split without unary nodes")
        _, _, promoted, left_node, right_node = min(
            choices, key=lambda item: (item[0], item[1])
        )
        return allocate(left_node), promoted, allocate(right_node)

    def rewrite(ref: PageRef) -> tuple[PageRef, bytes | None, PageRef | None, bool]:
        encoded = page_catalog.get(ref.page_no)
        if encoded is None:
            raise JPVRFormatError("index path references a missing old page")
        page = decode_index_page(encoded, expected_page_no=ref.page_no)
        node = page.node
        if node.level == 0:
            entries = list(node.entries)
            keys = [entry.key for entry in entries]
            position = bisect_left(keys, target)
            replaced = position < len(entries) and entries[position].key == target
            updated = IndexEntry(target, payload_value)
            if replaced:
                entries[position] = updated
            else:
                entries.insert(position, updated)
            candidate = IndexNode(expected_tree, 0, tuple(entries))
            if _node_payload_size(candidate) is not None:
                return allocate(candidate), None, None, replaced
            left_ref, separator, right_ref = split_leaf(entries)
            return left_ref, separator, right_ref, replaced

        pairs: list[tuple[bytes | None, PageRef]] = [
            (None, node.first_child)
        ] + [(child.separator, child.page_ref) for child in node.children]
        child_index = 0
        for index in range(1, len(pairs)):
            separator = pairs[index][0]
            if separator is not None and target >= separator:
                child_index = index
            else:
                break
        old_lower, child_ref = pairs[child_index]
        new_left, promoted, new_right, replaced = rewrite(child_ref)
        pairs[child_index] = (old_lower, new_left)
        if promoted is not None and new_right is not None:
            pairs.insert(child_index + 1, (promoted, new_right))

        candidate = _internal_node(expected_tree, node.level, pairs)
        if _node_payload_size(candidate) is not None:
            return allocate(candidate), None, None, replaced
        left_ref, separator, right_ref = split_internal(node.level, pairs)
        return left_ref, separator, right_ref, replaced

    new_left, promoted, new_right, replaced = rewrite(root)
    if promoted is None:
        new_root = new_left
    else:
        if new_right is None:
            raise JPVRFormatError("internal split did not produce a right sibling")
        old_page = page_catalog[root.page_no]
        old_level = decode_index_page(old_page, expected_page_no=root.page_no).node.level
        if old_level + 1 >= MAX_INDEX_DEPTH:
            raise JPVRFormatError("index root would exceed maximum depth")
        new_root_node = IndexNode(
            expected_tree,
            old_level + 1,
            first_child=new_left,
            children=(IndexChild(promoted, new_right),),
        )
        new_root = allocate(new_root_node)

    if not staged:
        raise JPVRFormatError("copy-on-write planner produced no new INDEX pages")
    final_catalog = dict(page_catalog)
    final_catalog.update({page.page_no: page.page_bytes for page in staged})
    new_stats = validate_index_tree(
        new_root, final_catalog, tree_id=expected_tree,
        durable_tail_page_no=staged[-1].page_no,
    )
    if new_stats.entry_count != old_stats.entry_count + (0 if replaced else 1):
        raise JPVRFormatError("copy-on-write result has an inconsistent entry count")
    old_pages = set(old_stats.page_numbers)
    new_pages = set(new_stats.page_numbers)
    planned_pages = set(page.page_no for page in staged)
    if new_pages - old_pages != planned_pages:
        raise JPVRFormatError("copy-on-write plan contains unreachable or missing INDEX pages")

    return CowIndexPlan(
        old_root=root,
        new_root=new_root,
        commit_generation=commit_generation,
        pages=tuple(staged),
        replaced_existing_key=replaced,
        old_stats=old_stats,
        new_stats=new_stats,
    )
