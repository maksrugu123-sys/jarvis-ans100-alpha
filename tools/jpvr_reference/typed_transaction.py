"""JPVR v1 typed in-memory transaction builder.

The builder turns typed DATA/BLOB/INDEX/JOURNAL/OUTBOX inputs into a strictly
ordered page sequence and delegates final snapshot validation to transaction.py.
It does not write a container or establish physical durability.
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, replace
from types import MappingProxyType
from typing import Sequence

from .blob import decode_blob_pages, encode_blob_pages
from .cow import CowIndexPlan, cow_apply_index_mutations
from .codec import (
    PAGE_PAYLOAD_BYTES,
    JPVRFormatError,
    Superblock,
    _fixed_bytes,
    encode_superblock,
)
from .commit import (
    CommitRecord,
    decode_commit_page,
    encode_commit_page,
    validate_commit_snapshot,
)
from .data import decode_data_page, encode_data_page
from .index import IndexNode, decode_index_page, encode_index_page
from .journal import (
    DecodedJournalPage,
    JournalEntry,
    decode_journal_page,
    encode_journal_page,
    validate_journal_transition,
)
from .manifest import (
    INDEX_ROOT_NAMES,
    Manifest,
    PageRef,
    decode_manifest_page,
    encode_manifest_page,
)
from .outbox import (
    OutboxEvent,
    decode_outbox_page,
    encode_outbox_page,
    validate_outbox_commit_reference,
    validate_outbox_genesis,
    validate_outbox_sequence,
)
from .records import encode_record
from .transaction import (
    TransactionAssemblyPlan,
    assemble_transaction,
    validate_transaction_assembly_plan,
)


@dataclass(frozen=True)
class DataRecordInput:
    """One logical small record to be packed into a DATA page."""

    record_type: int
    schema_id: int
    schema_version: int
    object_id: bytes
    record_version: int
    payload: bytes
    flags: int = 0


@dataclass(frozen=True)
class BlobRecordInput:
    """One logical large record to be fragmented across consecutive BLOB pages."""

    record_type: int
    schema_id: int
    schema_version: int
    object_id: bytes
    record_version: int
    payload: bytes


@dataclass(frozen=True)
class IndexPageInput:
    """One immutable INDEX node; root_name optionally publishes it as a MANIFEST root."""

    node: IndexNode
    root_name: str | None = None


@dataclass(frozen=True)
class IndexMutationInput:
    """One key/value upsert against a named MANIFEST index tree."""

    root_name: str
    tree_id: bytes
    key: bytes
    value: bytes


@dataclass(frozen=True)
class IndexMutationBuildPlan:
    """COW result for all mutations targeting one MANIFEST root."""

    root_name: str
    tree_id: bytes
    cow_plan: CowIndexPlan


@dataclass(frozen=True)
class JournalEntryInput:
    """A JOURNAL revision and its explicit COMMIT_UNKNOWN resolution evidence."""

    entry: JournalEntry
    confirmed_no_commit: bool = False


@dataclass(frozen=True)
class TypedTransactionRequest:
    """All typed inputs required to assemble one new JPVR generation."""

    current_superblock: Superblock
    page_catalog: Mapping[int, bytes]
    transaction_id: bytes
    commit_id: bytes
    operation_id: bytes
    data_records: Sequence[DataRecordInput] = ()
    blob_records: Sequence[BlobRecordInput] = ()
    index_pages: Sequence[IndexPageInput] = ()
    index_mutations: Sequence[IndexMutationInput] = ()
    journal_entries: Sequence[JournalEntryInput] = ()
    outbox_events: Sequence[OutboxEvent] = ()
    index_roots: Mapping[str, PageRef] | None = None
    checkpoint_page_no: int | None = None


@dataclass(frozen=True)
class TypedTransactionBuildPlan:
    """Immutable typed-page ranges plus the fully validated assembly candidate."""

    assembly: TransactionAssemblyPlan
    manifest: Manifest
    commit_record: CommitRecord
    data_page_numbers: tuple[int, ...]
    blob_page_numbers: tuple[int, ...]
    index_page_numbers: tuple[int, ...]
    index_mutation_plans: tuple[IndexMutationBuildPlan, ...]
    journal_page_numbers: tuple[int, ...]
    outbox_page_numbers: tuple[int, ...]

    @property
    def manifest_page_no(self) -> int:
        return self.assembly.manifest_page_no

    @property
    def commit_page_no(self) -> int:
        return self.assembly.append_plan.commit_page_no

    @property
    def new_superblock(self) -> Superblock:
        return self.assembly.new_superblock

    @property
    def page_count(self) -> int:
        return self.assembly.page_count


def _sequence(name: str, value: object, expected_type: type) -> tuple:
    if not isinstance(value, (tuple, list)):
        raise JPVRFormatError(f"{name} must be a tuple or list")
    result = tuple(value)
    if any(not isinstance(item, expected_type) for item in result):
        raise JPVRFormatError(f"{name} contains an item of the wrong type")
    return result


def _load_base_manifest(
    current: Superblock,
    catalog: Mapping[int, bytes],
) -> tuple[Manifest | None, bytes | None]:
    """Validate the active base snapshot and return its MANIFEST and COMMIT bytes."""
    if current.generation == 0:
        if current.active_commit_page_no != 0 or current.active_commit_id != bytes(16):
            raise JPVRFormatError("genesis Superblock must not identify an active COMMIT")
        return None, None

    active_commit_bytes = catalog.get(current.active_commit_page_no)
    if active_commit_bytes is None:
        raise JPVRFormatError("page_catalog is missing the active COMMIT")
    active_commit = decode_commit_page(
        active_commit_bytes, expected_page_no=current.active_commit_page_no
    )
    manifest_ref = active_commit.record.manifest_ref
    manifest_bytes = catalog.get(manifest_ref.page_no)
    if manifest_bytes is None:
        raise JPVRFormatError("page_catalog is missing the active MANIFEST")
    previous_bytes = None
    previous_ref = active_commit.record.previous_commit_ref
    if previous_ref is not None:
        previous_bytes = catalog.get(previous_ref.page_no)
        if previous_bytes is None:
            raise JPVRFormatError("page_catalog is missing the predecessor COMMIT")
    validate_commit_snapshot(
        active_commit_bytes,
        manifest_bytes,
        previous_commit_page_bytes=previous_bytes,
        expected_commit_page_no=current.active_commit_page_no,
        superblock=current,
        page_catalog=catalog,
    )
    decoded_manifest = decode_manifest_page(
        manifest_bytes, expected_page_no=manifest_ref.page_no
    )
    return decoded_manifest.manifest, bytes(active_commit_bytes)


def build_typed_transaction(
    request: TypedTransactionRequest,
) -> TypedTransactionBuildPlan:
    """Encode typed pages, attach cross-page references, and assemble one generation.

    Physical append order is DATA, BLOB, INDEX, JOURNAL, OUTBOX, MANIFEST, COMMIT.
    DATA records are packed greedily without crossing a page. BLOB fragments stay
    contiguous. New JOURNAL/OUTBOX references to this transaction's COMMIT are
    resolved after the final COMMIT page number is known.
    """
    if not isinstance(request, TypedTransactionRequest):
        raise JPVRFormatError("request must be a TypedTransactionRequest")
    current = request.current_superblock
    encode_superblock(current)
    catalog = request.page_catalog
    if not isinstance(catalog, Mapping):
        raise JPVRFormatError("page_catalog must be a mapping")
    current_bytes: dict[int, bytes] = {}
    for page_no, encoded in catalog.items():
        if isinstance(page_no, bool) or not isinstance(page_no, int):
            raise JPVRFormatError("page_catalog keys must be integer page numbers")
        if not isinstance(encoded, (bytes, bytearray, memoryview)):
            raise JPVRFormatError("page_catalog values must be bytes-like")
        current_bytes[page_no] = bytes(encoded)

    data_records = _sequence("data_records", request.data_records, DataRecordInput)
    blob_records = _sequence("blob_records", request.blob_records, BlobRecordInput)
    index_pages = _sequence("index_pages", request.index_pages, IndexPageInput)
    index_mutations = _sequence(
        "index_mutations", request.index_mutations, IndexMutationInput
    )
    journal_inputs = _sequence(
        "journal_entries", request.journal_entries, JournalEntryInput
    )
    outbox_events = _sequence("outbox_events", request.outbox_events, OutboxEvent)
    if any(not isinstance(item.confirmed_no_commit, bool) for item in journal_inputs):
        raise JPVRFormatError("JournalEntryInput.confirmed_no_commit must be bool")

    base_manifest, previous_commit_bytes = _load_base_manifest(current, current_bytes)
    next_generation = current.generation + 1
    if next_generation > 0xFFFFFFFFFFFFFFFF:
        raise JPVRFormatError("commit generation overflows u64")
    first_page_no = current.durable_tail_page_no + 1
    transaction_id = _fixed_bytes("transaction_id", request.transaction_id, 16)
    commit_id = _fixed_bytes("commit_id", request.commit_id, 16)
    operation_id = _fixed_bytes("operation_id", request.operation_id, 16)
    if transaction_id == bytes(16) or commit_id == bytes(16) or operation_id == bytes(16):
        raise JPVRFormatError("transaction_id, commit_id and operation_id must be nonzero")

    # DATA: encode one JREC per logical input, then greedily pack whole records.
    encoded_records = tuple(
        encode_record(
            item.record_type,
            item.schema_id,
            item.schema_version,
            item.object_id,
            item.record_version,
            item.payload,
            flags=item.flags,
        )
        for item in data_records
    )
    data_groups: list[tuple[bytes, ...]] = []
    group: list[bytes] = []
    group_size = 0
    for encoded in encoded_records:
        if group and group_size + len(encoded) > PAGE_PAYLOAD_BYTES:
            data_groups.append(tuple(group))
            group = []
            group_size = 0
        if len(encoded) > PAGE_PAYLOAD_BYTES:
            raise JPVRFormatError("one DATA record exceeds page capacity")
        group.append(encoded)
        group_size += len(encoded)
    if group:
        data_groups.append(tuple(group))

    staged: list[bytes] = []
    next_page_no = first_page_no
    data_numbers: list[int] = []
    for records in data_groups:
        data_numbers.append(next_page_no)
        staged.append(
            encode_data_page(records, next_page_no, next_generation, transaction_id)
        )
        next_page_no += 1

    # BLOB: each logical record owns one consecutive fragment run.
    blob_numbers: list[int] = []
    blob_groups: list[tuple[bytes, ...]] = []
    for item in blob_records:
        encoded_pages = encode_blob_pages(
            item.record_type,
            item.schema_id,
            item.schema_version,
            item.object_id,
            item.record_version,
            transaction_id,
            next_page_no,
            next_generation,
            item.payload,
        )
        blob_groups.append(encoded_pages)
        blob_numbers.extend(range(next_page_no, next_page_no + len(encoded_pages)))
        staged.extend(encoded_pages)
        next_page_no += len(encoded_pages)

    # INDEX: caller supplies immutable nodes in child-before-parent append order.
    index_numbers: list[int] = []
    root_assignments: dict[str, PageRef] = {}
    for item in index_pages:
        if item.root_name is not None:
            if item.root_name not in INDEX_ROOT_NAMES:
                raise JPVRFormatError("unknown MANIFEST index root name")
            if item.root_name in root_assignments:
                raise JPVRFormatError("MANIFEST index root is assigned more than once")
        encoded = encode_index_page(
            item.node, next_page_no, next_generation, transaction_id
        )
        index_numbers.append(next_page_no)
        staged.append(encoded)
        if item.root_name is not None:
            root_assignments[item.root_name] = PageRef(next_page_no, next_generation)
        next_page_no += 1

    # Preserve old roots/high-water marks unless this transaction replaces them.
    roots = {} if base_manifest is None else dict(base_manifest.index_roots)
    if request.index_roots is not None:
        if not isinstance(request.index_roots, Mapping):
            raise JPVRFormatError("index_roots must be a mapping")
        for name, ref in request.index_roots.items():
            if name not in INDEX_ROOT_NAMES:
                raise JPVRFormatError("unknown MANIFEST index root name")
            if name in root_assignments:
                raise JPVRFormatError("index root is supplied both by page and explicit mapping")
            if not isinstance(ref, PageRef):
                raise JPVRFormatError("index_roots values must be PageRef values")
            roots[name] = ref
    roots.update(root_assignments)

    # Group key/value upserts by MANIFEST tree and rewrite each affected tree
    # once, so every new page shares this transaction's commit generation.
    mutation_groups: dict[str, list[IndexMutationInput]] = {}
    mutation_tree_ids: dict[str, bytes] = {}
    for item in index_mutations:
        if item.root_name not in INDEX_ROOT_NAMES:
            raise JPVRFormatError("unknown MANIFEST index root name in mutation")
        tree_id = _fixed_bytes("INDEX mutation tree_id", item.tree_id, 16)
        previous_tree_id = mutation_tree_ids.get(item.root_name)
        if previous_tree_id is not None and previous_tree_id != tree_id:
            raise JPVRFormatError("mutations for one MANIFEST root must use one tree_id")
        mutation_tree_ids[item.root_name] = tree_id
        mutation_groups.setdefault(item.root_name, []).append(item)

    mutation_plans: list[IndexMutationBuildPlan] = []
    for root_name in INDEX_ROOT_NAMES:
        grouped_mutations = mutation_groups.get(root_name)
        if not grouped_mutations:
            continue
        old_root = roots.get(root_name)
        if not isinstance(old_root, PageRef):
            raise JPVRFormatError("COW mutation requires an existing MANIFEST root")
        cow_catalog = dict(current_bytes)
        cow_catalog.update({
            page_no: encoded
            for page_no, encoded in zip(
                range(first_page_no, next_page_no), staged
            )
        })
        cow_plan = cow_apply_index_mutations(
            old_root,
            cow_catalog,
            tuple((item.key, item.value) for item in grouped_mutations),
            tree_id=mutation_tree_ids[root_name],
            durable_tail_page_no=next_page_no - 1,
            first_page_no=next_page_no,
            commit_generation=next_generation,
            transaction_id=transaction_id,
        )
        for page in cow_plan.pages:
            if page.page_no != next_page_no:
                raise JPVRFormatError("COW INDEX allocator returned a non-contiguous page")
            staged.append(page.page_bytes)
            cow_catalog[page.page_no] = page.page_bytes
            index_numbers.append(page.page_no)
            next_page_no += 1
        roots[root_name] = cow_plan.new_root
        mutation_plans.append(IndexMutationBuildPlan(
            root_name=root_name,
            tree_id=mutation_tree_ids[root_name],
            cow_plan=cow_plan,
        ))

    if set(roots) != set(INDEX_ROOT_NAMES):
        raise JPVRFormatError("MANIFEST must resolve all seven index roots")

    # Resolve the final COMMIT number before creating JOURNAL/OUTBOX references.
    journal_start = next_page_no
    outbox_start = journal_start + len(journal_inputs)
    manifest_page_no = outbox_start + len(outbox_events)
    commit_page_no = manifest_page_no + 1
    commit_ref = PageRef(commit_page_no, next_generation)

    # JOURNAL: automatically chain consecutive revisions for each operation.
    journal_numbers: list[int] = []
    latest_journal_by_operation: dict[bytes, DecodedJournalPage] = {}
    decoded_journal_by_page: dict[int, DecodedJournalPage] = {}
    for offset, item in enumerate(journal_inputs):
        entry = item.entry
        operation_key = _fixed_bytes("JOURNAL operation_id", entry.operation_id, 16)
        _fixed_bytes("JOURNAL idempotency_key", entry.idempotency_key, 16)
        _fixed_bytes("JOURNAL owner_module_id", entry.owner_module_id, 16)
        if entry.commit_id is not None:
            entry_commit_id = _fixed_bytes("JOURNAL commit_id", entry.commit_id, 16)
            if entry_commit_id == commit_id:
                entry = replace(entry, commit_ref=commit_ref)

        previous_decoded = latest_journal_by_operation.get(operation_key)
        if previous_decoded is not None:
            expected_previous = PageRef(
                previous_decoded.page_no, previous_decoded.commit_generation
            )
            if entry.previous_entry_ref not in (None, expected_previous):
                raise JPVRFormatError("JOURNAL revision does not link to prior staged entry")
            entry = replace(entry, previous_entry_ref=expected_previous)
        elif entry.previous_entry_ref is not None:
            previous_ref = entry.previous_entry_ref
            previous_decoded = decoded_journal_by_page.get(previous_ref.page_no)
            if previous_decoded is None:
                previous_encoded = current_bytes.get(previous_ref.page_no)
                if previous_encoded is None:
                    raise JPVRFormatError(
                        "JOURNAL previous_entry_ref is missing from page catalog"
                    )
                previous_decoded = decode_journal_page(
                    previous_encoded, expected_page_no=previous_ref.page_no
                )
            if previous_decoded.commit_generation != previous_ref.expected_commit_generation:
                raise JPVRFormatError("JOURNAL previous_entry_ref generation mismatch")

        page_no = journal_start + offset
        encoded = encode_journal_page(entry, page_no, next_generation, transaction_id)
        decoded = decode_journal_page(encoded, expected_page_no=page_no)
        if previous_decoded is not None:
            validate_journal_transition(
                previous_decoded,
                decoded,
                confirmed_no_commit=item.confirmed_no_commit,
            )
        elif item.confirmed_no_commit:
            raise JPVRFormatError(
                "confirmed_no_commit requires a COMMIT_UNKNOWN predecessor"
            )
        journal_numbers.append(page_no)
        staged.append(encoded)
        latest_journal_by_operation[operation_key] = decoded
        decoded_journal_by_page[page_no] = decoded

    # OUTBOX: anchor the new sequence to the active MANIFEST high-water page.
    previous_outbox = None
    if base_manifest is not None and base_manifest.outbox_high_water_page_no:
        previous_no = base_manifest.outbox_high_water_page_no
        previous_bytes = current_bytes.get(previous_no)
        if previous_bytes is None:
            raise JPVRFormatError("page_catalog is missing the prior OUTBOX high-water page")
        previous_outbox = decode_outbox_page(
            previous_bytes, expected_page_no=previous_no
        )
        source_commit_bytes = current_bytes.get(previous_outbox.event.commit_ref.page_no)
        if source_commit_bytes is None:
            raise JPVRFormatError("page_catalog is missing the prior OUTBOX source COMMIT")
        validate_outbox_commit_reference(previous_outbox, source_commit_bytes)

    outbox_numbers: list[int] = []
    staged_event_ids: set[bytes] = set()
    staged_idempotency_keys: set[bytes] = set()
    for offset, original_event in enumerate(outbox_events):
        event_commit_id = _fixed_bytes("OUTBOX commit_id", original_event.commit_id, 16)
        _fixed_bytes("OUTBOX event_id", original_event.event_id, 16)
        _fixed_bytes("OUTBOX idempotency_key", original_event.idempotency_key, 16)
        _fixed_bytes("OUTBOX operation_id", original_event.operation_id, 16)
        if event_commit_id != commit_id:
            raise JPVRFormatError("OUTBOX event commit_id must match the new transaction")
        expected_previous = (
            None
            if previous_outbox is None
            else PageRef(previous_outbox.page_no, previous_outbox.commit_generation)
        )
        if original_event.previous_event_ref not in (None, expected_previous):
            raise JPVRFormatError("OUTBOX previous_event_ref does not match sequence tail")
        event = replace(
            original_event,
            commit_ref=commit_ref,
            previous_event_ref=expected_previous,
        )
        expected_sequence = (
            1 if previous_outbox is None else previous_outbox.event.sequence + 1
        )
        if event.sequence != expected_sequence:
            raise JPVRFormatError(
                "OUTBOX sequence is not contiguous with committed high-water"
            )
        event_id = _fixed_bytes("OUTBOX event_id", event.event_id, 16)
        idempotency_key = _fixed_bytes("OUTBOX idempotency_key", event.idempotency_key, 16)
        if (
            event_id in staged_event_ids
            or idempotency_key in staged_idempotency_keys
        ):
            raise JPVRFormatError(
                "OUTBOX event_id and idempotency_key must be unique in transaction"
            )
        page_no = outbox_start + offset
        encoded = encode_outbox_page(event, page_no, next_generation, transaction_id)
        decoded = decode_outbox_page(encoded, expected_page_no=page_no)
        if previous_outbox is None:
            validate_outbox_genesis(decoded)
        else:
            validate_outbox_sequence(previous_outbox, decoded)
        outbox_numbers.append(page_no)
        staged.append(encoded)
        staged_event_ids.add(event_id)
        staged_idempotency_keys.add(idempotency_key)
        previous_outbox = decoded

    old_journal_high_water = (
        0 if base_manifest is None else base_manifest.journal_high_water_page_no
    )
    old_outbox_high_water = (
        0 if base_manifest is None else base_manifest.outbox_high_water_page_no
    )
    checkpoint_page_no = (
        current.checkpoint_page_no
        if request.checkpoint_page_no is None
        else request.checkpoint_page_no
    )
    manifest = Manifest(
        container_uuid=current.container_uuid,
        generation=next_generation,
        index_roots=MappingProxyType(dict(roots)),
        checkpoint_page_no=checkpoint_page_no,
        journal_high_water_page_no=(
            journal_numbers[-1] if journal_numbers else old_journal_high_water
        ),
        outbox_high_water_page_no=(
            outbox_numbers[-1] if outbox_numbers else old_outbox_high_water
        ),
    )
    if len(staged) != manifest_page_no - first_page_no:
        raise JPVRFormatError("internal typed-page layout calculation is inconsistent")
    manifest_bytes = encode_manifest_page(manifest, manifest_page_no, transaction_id)
    staged.append(manifest_bytes)

    commit_record = CommitRecord(
        generation=next_generation,
        transaction_id=transaction_id,
        commit_id=commit_id,
        operation_id=operation_id,
        manifest_ref=PageRef(manifest_page_no, next_generation),
        durable_tail_page_no=commit_page_no,
        previous_commit_ref=(
            None
            if current.generation == 0
            else PageRef(current.active_commit_page_no, current.generation)
        ),
    )
    commit_bytes = encode_commit_page(commit_record, commit_page_no)
    staged_catalog = {
        page_no: encoded
        for page_no, encoded in zip(range(first_page_no, commit_page_no), staged)
    }

    # Confirm each typed family with its own decoder before final snapshot assembly.
    for page_no in data_numbers:
        decode_data_page(staged_catalog[page_no], expected_page_no=page_no)
    for item, group in zip(blob_records, blob_groups):
        decoded_blob = decode_blob_pages(group)
        if decoded_blob.payload != bytes(item.payload):
            raise JPVRFormatError("BLOB round-trip payload mismatch")
    for page_no in index_numbers:
        decode_index_page(staged_catalog[page_no], expected_page_no=page_no)
    for page_no in journal_numbers:
        decode_journal_page(staged_catalog[page_no], expected_page_no=page_no)
    for page_no in outbox_numbers:
        decoded = decode_outbox_page(staged_catalog[page_no], expected_page_no=page_no)
        validate_outbox_commit_reference(decoded, commit_bytes)

    assembly = assemble_transaction(
        current,
        tuple(staged),
        commit_bytes,
        current_bytes,
        previous_commit_page_bytes=previous_commit_bytes,
    )
    if assembly.ordered_page_numbers[-1] != commit_page_no:
        raise JPVRFormatError("typed transaction COMMIT placement is inconsistent")
    return TypedTransactionBuildPlan(
        assembly=assembly,
        manifest=manifest,
        commit_record=commit_record,
        data_page_numbers=tuple(data_numbers),
        blob_page_numbers=tuple(blob_numbers),
        index_page_numbers=tuple(index_numbers),
        index_mutation_plans=tuple(mutation_plans),
        journal_page_numbers=tuple(journal_numbers),
        outbox_page_numbers=tuple(outbox_numbers),
    )


def validate_typed_transaction_plan(
    plan: TypedTransactionBuildPlan,
    request: TypedTransactionRequest,
) -> None:
    """Rebuild the typed plan and reject changes to bytes, ranges, or metadata."""
    if not isinstance(plan, TypedTransactionBuildPlan):
        raise JPVRFormatError("plan must be a TypedTransactionBuildPlan")
    rebuilt = build_typed_transaction(request)
    if rebuilt != plan:
        raise JPVRFormatError("typed transaction plan fields are inconsistent")
    validate_transaction_assembly_plan(
        plan.assembly,
        request.current_superblock,
        request.page_catalog,
        previous_commit_page_bytes=(
            None
            if request.current_superblock.generation == 0
            else request.page_catalog.get(
                request.current_superblock.active_commit_page_no
            )
        ),
    )
