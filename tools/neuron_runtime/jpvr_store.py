"""Indexed, file-backed persistence facade for one JARVIS neuron checkpoint.

The checkpoint record and its neuron-index locator are published by one JPVR
COMMIT. This is a bounded Python reference facade, not the production JNVM
service or a NetworkRuntime persistence implementation.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from collections import OrderedDict
from collections.abc import Mapping
import hashlib
import struct
import uuid

from tools.jpvr_reference.blob import MAX_BLOB_PAGES, decode_blob_pages
from tools.jpvr_reference.codec import JPVRFormatError, Superblock
from tools.jpvr_reference.commit import decode_commit_page
from tools.jpvr_reference.data import decode_data_page
from tools.jpvr_reference.cow import validate_index_tree
from tools.jpvr_reference.index import IndexNode, decode_index_page, lookup_index
from tools.jpvr_reference.manifest import (
    INDEX_ROOT_NAMES,
    Manifest,
    decode_manifest_page,
)
from tools.jpvr_reference.storage import JPVRStorage
from tools.jpvr_reference.typed_transaction import (
    BlobRecordInput,
    DataRecordInput,
    IndexMutationInput,
    IndexPageInput,
    TypedTransactionRequest,
    build_typed_transaction,
)

from .jpvr_adapter import (
    DecodedNeuronRuntimeSnapshot,
    JNVM_RUNTIME_RECORD_TYPE,
    JNVM_RUNTIME_SCHEMA_ID,
    JNVM_RUNTIME_SCHEMA_VERSION,
    NeuronRuntimePersistenceError,
    decode_neuron_runtime_record,
    make_neuron_runtime_record_input,
    neuron_runtime_object_id,
)
from .runtime import NeuronRuntime


LOCATOR_MAGIC = b"JNLR"
LOCATOR_VERSION = 1
LOCATOR_DATA = 1
LOCATOR_BLOB = 2
_U64_MAX = (1 << 64) - 1
_LOCATOR = struct.Struct("<4sHBBHHI16sQQIQQ16s16s")
_TREE_ID_DOMAIN = b"JARVIS:JPVR:INDEX-TREE:V1\x00"

 
class _LazyPageCatalog(Mapping[int, bytes]):
    """Pinned append-only snapshot view with a bounded read-through page cache."""

    def __init__(self, storage: JPVRStorage, active: Superblock, *, cache_pages: int = 64):
        self._storage = storage
        self._tail = active.durable_tail_page_no
        self._cache_pages = cache_pages
        self._cache: OrderedDict[int, bytes] = OrderedDict()

    def __getitem__(self, page_no: int) -> bytes:
        if isinstance(page_no, bool) or not isinstance(page_no, int) or not 2 <= page_no <= self._tail:
            raise KeyError(page_no)
        cached = self._cache.get(page_no)
        if cached is not None:
            self._cache.move_to_end(page_no)
            return cached
        # JPVR V1 appends immutable committed pages. Later commits can append,
        # but cannot modify a page within the pinned Superblock prefix.
        encoded = self._storage.read_page(page_no)
        self._cache[page_no] = encoded
        self._cache.move_to_end(page_no)
        while len(self._cache) > self._cache_pages:
            self._cache.popitem(last=False)
        return encoded

    def __iter__(self):
        return iter(range(2, self._tail + 1))

    def __len__(self) -> int:
        return max(0, self._tail - 1)


class JPVRNeuronCheckpointError(ValueError):
    """Invalid neuron-index locator or checkpoint persistence operation."""


class JPVRNeuronCheckpointNotFound(JPVRNeuronCheckpointError):
    """No checkpoint exists for the requested logical neuron ID."""


class JPVRNeuronCheckpointConflict(JPVRNeuronCheckpointError):
    """The caller's expected checkpoint version no longer matches the index."""


@dataclass(frozen=True)
class NeuronCheckpointLocator:
    """Fixed-size index value that locates one immutable DATA/BLOB checkpoint."""

    storage_kind: int
    record_type: int
    schema_id: int
    schema_version: int
    object_id: bytes
    record_version: int
    first_page_no: int
    page_count: int
    record_generation: int
    commit_page_no: int
    commit_id: bytes
    transaction_id: bytes

    @property
    def storage_name(self) -> str:
        return "DATA" if self.storage_kind == LOCATOR_DATA else "BLOB"


@dataclass(frozen=True)
class SavedNeuronCheckpoint:
    neuron_id: str
    object_id: bytes
    record_version: int
    previous_record_version: int
    storage_kind: str
    first_page_no: int
    page_count: int
    commit_page_no: int
    commit_id: bytes
    generation: int
    initialized_indexes: bool
    superblock: Superblock


@dataclass(frozen=True)
class LoadedNeuronCheckpoint:
    snapshot: DecodedNeuronRuntimeSnapshot
    locator: NeuronCheckpointLocator
    commit_page_no: int


def _uint(name: str, value: object, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= maximum:
        raise JPVRNeuronCheckpointError(f"{name} is outside its unsigned integer range")
    return value


def _fixed(name: str, value: object, length: int) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)) or len(value) != length:
        raise JPVRNeuronCheckpointError(f"{name} must contain exactly {length} bytes")
    return bytes(value)


def _new_id() -> bytes:
    value = uuid.uuid4().bytes
    return value if value != bytes(16) else b"\x00" * 15 + b"\x01"


def _tree_id(root_name: str) -> bytes:
    return hashlib.sha256(_TREE_ID_DOMAIN + root_name.encode("ascii")).digest()[:16]


def _validate_locator(locator: NeuronCheckpointLocator) -> None:
    if not isinstance(locator, NeuronCheckpointLocator):
        raise JPVRNeuronCheckpointError("locator must be a NeuronCheckpointLocator")
    _uint("storage_kind", locator.storage_kind, 0xFF)
    _uint("record_type", locator.record_type, 0xFFFF)
    _uint("schema_id", locator.schema_id, 0xFFFFFFFF)
    _uint("schema_version", locator.schema_version, 0xFFFF)
    if locator.storage_kind not in (LOCATOR_DATA, LOCATOR_BLOB):
        raise JPVRNeuronCheckpointError("locator storage_kind must be DATA or BLOB")
    if locator.record_type != JNVM_RUNTIME_RECORD_TYPE:
        raise JPVRNeuronCheckpointError("locator record_type does not identify a JNVM runtime")
    if locator.schema_id != JNVM_RUNTIME_SCHEMA_ID:
        raise JPVRNeuronCheckpointError("locator schema_id does not identify JNVM")
    if locator.schema_version != JNVM_RUNTIME_SCHEMA_VERSION:
        raise JPVRNeuronCheckpointError("unsupported JNVM locator schema version")
    _fixed("object_id", locator.object_id, 16)
    if bytes(locator.object_id) == bytes(16):
        raise JPVRNeuronCheckpointError("locator object_id must be non-zero")
    _fixed("commit_id", locator.commit_id, 16)
    _fixed("transaction_id", locator.transaction_id, 16)
    if locator.commit_id == bytes(16) or locator.transaction_id == bytes(16):
        raise JPVRNeuronCheckpointError("locator commit and transaction IDs must be non-zero")
    _uint("record_version", locator.record_version, _U64_MAX)
    _uint("first_page_no", locator.first_page_no, _U64_MAX)
    _uint("page_count", locator.page_count, 0xFFFFFFFF)
    _uint("record_generation", locator.record_generation, _U64_MAX)
    _uint("commit_page_no", locator.commit_page_no, _U64_MAX)
    if locator.record_version == 0 or locator.first_page_no < 2:
        raise JPVRNeuronCheckpointError("locator record version or first page is invalid")
    if locator.page_count == 0 or locator.record_generation == 0:
        raise JPVRNeuronCheckpointError("locator page count and generation must be positive")
    if locator.commit_page_no < 2 or locator.commit_page_no <= locator.first_page_no + locator.page_count - 1:
        raise JPVRNeuronCheckpointError("locator COMMIT page must follow the record page range")
    if locator.storage_kind == LOCATOR_DATA and locator.page_count != 1:
        raise JPVRNeuronCheckpointError("a DATA checkpoint must occupy exactly one page")
    if locator.storage_kind == LOCATOR_BLOB and locator.page_count > MAX_BLOB_PAGES:
        raise JPVRNeuronCheckpointError("BLOB checkpoint page count exceeds the format limit")


def encode_neuron_checkpoint_locator(locator: NeuronCheckpointLocator) -> bytes:
    """Encode the versioned 100-byte value stored under neuron object_id in INDEX."""
    _validate_locator(locator)
    return _LOCATOR.pack(
        LOCATOR_MAGIC,
        LOCATOR_VERSION,
        locator.storage_kind,
        0,
        locator.record_type,
        locator.schema_version,
        locator.schema_id,
        bytes(locator.object_id),
        locator.record_version,
        locator.first_page_no,
        locator.page_count,
        locator.record_generation,
        locator.commit_page_no,
        bytes(locator.commit_id),
        bytes(locator.transaction_id),
    )


def decode_neuron_checkpoint_locator(value: bytes | bytearray | memoryview) -> NeuronCheckpointLocator:
    """Decode a locator and reject unknown versions, flags and invalid page ranges."""
    if not isinstance(value, (bytes, bytearray, memoryview)) or len(value) != _LOCATOR.size:
        raise JPVRNeuronCheckpointError("neuron checkpoint locator has an invalid encoded size")
    (
        magic,
        version,
        storage_kind,
        reserved,
        record_type,
        schema_version,
        schema_id,
        object_id,
        record_version,
        first_page_no,
        page_count,
        record_generation,
        commit_page_no,
        commit_id,
        transaction_id,
    ) = _LOCATOR.unpack(bytes(value))
    if magic != LOCATOR_MAGIC or version != LOCATOR_VERSION or reserved != 0:
        raise JPVRNeuronCheckpointError("invalid neuron checkpoint locator magic, version or flags")
    locator = NeuronCheckpointLocator(
        storage_kind=storage_kind,
        record_type=record_type,
        schema_id=schema_id,
        schema_version=schema_version,
        object_id=object_id,
        record_version=record_version,
        first_page_no=first_page_no,
        page_count=page_count,
        record_generation=record_generation,
        commit_page_no=commit_page_no,
        commit_id=commit_id,
        transaction_id=transaction_id,
    )
    _validate_locator(locator)
    return locator


class JPVRNeuronCheckpointStore:
    """Small bounded save/load facade over JPVRStorage and the neuron COW INDEX.

    Save builds a complete checkpoint record, predicts its DATA/BLOB page range,
    then builds the fixed-size locator into the same COW transaction. A second
    planning pass resolves the final COMMIT page number embedded in that locator.
    JPVRStorage.commit() publishes both the immutable record and locator together.
    """

    def __init__(self, storage: JPVRStorage):
        if not isinstance(storage, JPVRStorage):
            raise JPVRNeuronCheckpointError("storage must be a JPVRStorage instance")
        self.storage = storage

    @staticmethod
    def _manifest(
        active: Superblock, catalog: Mapping[int, bytes]
    ) -> Manifest | None:
        if active.generation == 0:
            return None
        raw_commit = catalog.get(active.active_commit_page_no)
        if raw_commit is None:
            raise JPVRNeuronCheckpointError("active COMMIT page is missing from the bounded snapshot")
        commit = decode_commit_page(raw_commit, expected_page_no=active.active_commit_page_no)
        raw_manifest = catalog.get(commit.record.manifest_ref.page_no)
        if raw_manifest is None:
            raise JPVRNeuronCheckpointError("active MANIFEST page is missing from the bounded snapshot")
        decoded = decode_manifest_page(
            raw_manifest, expected_page_no=commit.record.manifest_ref.page_no
        )
        if (
            commit.record.generation != active.generation
            or commit.record.commit_id != active.active_commit_id
            or decoded.manifest.container_uuid != active.container_uuid
            or decoded.manifest.generation != active.generation
            or commit.record.durable_tail_page_no != active.durable_tail_page_no
            or active.active_commit_page_no != active.durable_tail_page_no
        ):
            raise JPVRNeuronCheckpointError("active COMMIT/MANIFEST does not match its Superblock")
        return decoded.manifest

    def _read_snapshot(
        self, max_pages: int
    ) -> tuple[Superblock, dict[int, bytes], Manifest | None]:
        active, catalog = self.storage.load_snapshot(max_pages=max_pages)
        return active, catalog, self._manifest(active, catalog)


    def _read_lazy_snapshot(
        self, max_pages: int
    ) -> tuple[Superblock, _LazyPageCatalog, Manifest | None]:
        """Pin one active prefix and resolve its referenced pages on demand."""
        active = self.storage.get_superblock()
        page_count = max(0, active.durable_tail_page_no - 1)
        if page_count > max_pages:
            raise JPVRFormatError("JPVR page prefix exceeds max_pages read bound")
        catalog = _LazyPageCatalog(self.storage, active)
        return active, catalog, self._manifest(active, catalog)

    def _initialize_indexes(
        self, active: Superblock, catalog: Mapping[int, bytes]
    ) -> None:
        if active.generation != 0:
            raise JPVRNeuronCheckpointError("INDEX initialization is only valid for genesis")
        index_pages = tuple(
            IndexPageInput(IndexNode(_tree_id(name), 0), root_name=name)
            for name in INDEX_ROOT_NAMES
        )
        request = TypedTransactionRequest(
            current_superblock=active,
            page_catalog=catalog,
            transaction_id=_new_id(),
            commit_id=_new_id(),
            operation_id=_new_id(),
            index_pages=index_pages,
        )
        self.storage.commit(build_typed_transaction(request))

    @staticmethod
    def _lookup_existing(
        neuron_id: str,
        object_id: bytes,
        manifest: Manifest,
        active: Superblock,
        catalog: Mapping[int, bytes],
    ) -> tuple[NeuronCheckpointLocator | None, int]:
        root = manifest.index_roots["neuron"]
        root_bytes = catalog.get(root.page_no)
        if root_bytes is None:
            raise JPVRNeuronCheckpointError("neuron INDEX root is absent from the snapshot")
        root_page = decode_index_page(root_bytes, expected_page_no=root.page_no)
        validate_index_tree(
            root,
            catalog,
            tree_id=root_page.node.tree_id,
            durable_tail_page_no=active.durable_tail_page_no,
        )
        value = lookup_index(
            root,
            catalog,
            object_id,
            tree_id=root_page.node.tree_id,
            durable_tail_page_no=active.durable_tail_page_no,
        )
        if value is None:
            return None, 0
        locator = decode_neuron_checkpoint_locator(value)
        if locator.object_id != object_id:
            raise JPVRNeuronCheckpointError("neuron INDEX key and locator object_id differ")
        snapshot = JPVRNeuronCheckpointStore._decode_locator_record(
            neuron_id, locator, active, catalog
        )
        if snapshot.neuron_id != neuron_id:
            raise JPVRNeuronCheckpointError("stored checkpoint neuron_id does not match its INDEX key")
        return locator, locator.record_version

    @staticmethod
    def _verify_commit_ancestor(
        locator: NeuronCheckpointLocator,
        active: Superblock,
        catalog: Mapping[int, bytes],
    ):
        if locator.record_generation > active.generation:
            raise JPVRNeuronCheckpointError("checkpoint generation is newer than the active snapshot")
        page_no = active.active_commit_page_no
        expected_generation = active.generation
        visited: set[int] = set()
        for _ in range(active.generation):
            if page_no in visited:
                raise JPVRNeuronCheckpointError("COMMIT predecessor chain contains a cycle")
            visited.add(page_no)
            raw = catalog.get(page_no)
            if raw is None:
                raise JPVRNeuronCheckpointError("COMMIT predecessor page is missing")
            current = decode_commit_page(raw, expected_page_no=page_no)
            if current.record.generation != expected_generation:
                raise JPVRNeuronCheckpointError("COMMIT predecessor generation is discontinuous")
            if page_no == locator.commit_page_no:
                if (
                    current.record.commit_id != locator.commit_id
                    or current.transaction_id != locator.transaction_id
                    or current.record.generation != locator.record_generation
                    or current.record.durable_tail_page_no != locator.commit_page_no
                    or current.record.durable_tail_page_no != page_no
                    or current.record.durable_tail_page_no
                    < locator.first_page_no + locator.page_count - 1
                ):
                    raise JPVRNeuronCheckpointError("checkpoint locator does not match its source COMMIT")
                return current
            predecessor = current.record.previous_commit_ref
            if predecessor is None:
                break
            if (
                expected_generation <= 1
                or predecessor.page_no >= page_no
                or predecessor.expected_commit_generation != expected_generation - 1
            ):
                raise JPVRNeuronCheckpointError("COMMIT predecessor reference is invalid")
            page_no = predecessor.page_no
            expected_generation -= 1
        raise JPVRNeuronCheckpointError("checkpoint source COMMIT is not an ancestor of the active snapshot")

    @staticmethod
    def _decode_locator_record(
        neuron_id: str,
        locator: NeuronCheckpointLocator,
        active: Superblock,
        catalog: Mapping[int, bytes],
    ) -> DecodedNeuronRuntimeSnapshot:
        _validate_locator(locator)
        source_commit = JPVRNeuronCheckpointStore._verify_commit_ancestor(
            locator, active, catalog
        )
        last_page = locator.first_page_no + locator.page_count - 1
        if last_page > active.durable_tail_page_no:
            raise JPVRNeuronCheckpointError("checkpoint page range is outside the active durable prefix")

        if locator.storage_kind == LOCATOR_DATA:
            raw = catalog.get(locator.first_page_no)
            if raw is None:
                raise JPVRNeuronCheckpointError("checkpoint DATA page is missing")
            page = decode_data_page(raw, expected_page_no=locator.first_page_no)
            if (
                page.commit_generation != locator.record_generation
                or page.transaction_id != locator.transaction_id
            ):
                raise JPVRNeuronCheckpointError("checkpoint DATA page transaction metadata mismatch")
            matches = [
                record for record in page.records
                if record.object_id == locator.object_id
                and record.record_type == locator.record_type
            ]
            if len(matches) != 1:
                raise JPVRNeuronCheckpointError("checkpoint DATA page must contain exactly one matching record")
            record = matches[0]
            if (
                record.schema_id != locator.schema_id
                or record.schema_version != locator.schema_version
                or record.record_version != locator.record_version
            ):
                raise JPVRNeuronCheckpointError("checkpoint JREC metadata differs from its neuron locator")
        else:
            pages = []
            for page_no in range(locator.first_page_no, last_page + 1):
                raw = catalog.get(page_no)
                if raw is None:
                    raise JPVRNeuronCheckpointError(f"checkpoint BLOB page {page_no} is missing")
                pages.append(raw)
            record = decode_blob_pages(
                tuple(pages), expected_first_page_no=locator.first_page_no
            )
            if (
                record.page_count != locator.page_count
                or record.first_page_no != locator.first_page_no
                or record.commit_generation != locator.record_generation
                or record.transaction_id != locator.transaction_id
                or record.object_id != locator.object_id
                or record.record_type != locator.record_type
                or record.schema_id != locator.schema_id
                or record.schema_version != locator.schema_version
                or record.record_version != locator.record_version
            ):
                raise JPVRNeuronCheckpointError("checkpoint BLOB metadata differs from its neuron locator")

        if source_commit.record.commit_id != locator.commit_id:
            raise JPVRNeuronCheckpointError("checkpoint source COMMIT identity mismatch")
        snapshot = decode_neuron_runtime_record(record)
        if snapshot.neuron_id != neuron_id:
            raise JPVRNeuronCheckpointError("checkpoint payload neuron_id does not match the requested neuron")
        return snapshot

    def save(
        self,
        neuron_id: str,
        runtime: NeuronRuntime,
        *,
        clock_epoch_id: bytes,
        expected_record_version: int | None = None,
        max_pages: int = 4096,
    ) -> SavedNeuronCheckpoint:
        """Persist a complete neuron snapshot and its locator in one JPVR commit.

        A genesis container is initialized with seven empty INDEX roots first;
        that one-time schema initialization is a separate valid commit. The
        checkpoint record plus updated neuron locator are then one transaction.
        Stale concurrent writers are rejected by JPVRStorage.commit().
        """
        if (
            expected_record_version is not None
            and (
                isinstance(expected_record_version, bool)
                or not isinstance(expected_record_version, int)
                or not 0 <= expected_record_version <= _U64_MAX
            )
        ):
            raise JPVRNeuronCheckpointError("expected_record_version must be an unsigned 64-bit integer")
        object_id = neuron_runtime_object_id(neuron_id)
        # Validate the complete runtime before making even the one-time INDEX initialization commit.
        make_neuron_runtime_record_input(
            neuron_id, runtime, clock_epoch_id=clock_epoch_id, record_version=1
        )

        active, catalog, manifest = self._read_snapshot(max_pages)
        initialized = False
        if manifest is None:
            # Seven empty root pages plus MANIFEST and COMMIT are needed for
            # the first schema snapshot. Refuse before writing a partial init
            # when the caller's bounded catalog cannot read that snapshot back.
            if max_pages < len(INDEX_ROOT_NAMES) + 2:
                raise JPVRFormatError(
                    "initial neuron INDEX setup requires a page catalog limit of at least 9"
                )
            if expected_record_version not in (None, 0):
                raise JPVRNeuronCheckpointConflict("expected checkpoint version does not match an empty neuron INDEX")
            self._initialize_indexes(active, catalog)
            initialized = True
            active, catalog, manifest = self._read_snapshot(max_pages)
            if manifest is None:
                raise JPVRNeuronCheckpointError("INDEX initialization did not publish a MANIFEST")

        previous_locator, previous_version = self._lookup_existing(
            neuron_id, object_id, manifest, active, catalog
        )
        if expected_record_version is not None and expected_record_version != previous_version:
            raise JPVRNeuronCheckpointConflict(
                f"expected checkpoint version {expected_record_version}, current version is {previous_version}"
            )
        if previous_version == _U64_MAX:
            raise JPVRNeuronCheckpointError("neuron checkpoint record_version space is exhausted")
        next_version = previous_version + 1

        record_input = make_neuron_runtime_record_input(
            neuron_id, runtime, clock_epoch_id=clock_epoch_id, record_version=next_version
        )
        transaction_id, commit_id, operation_id = _new_id(), _new_id(), _new_id()
        base_request = TypedTransactionRequest(
            current_superblock=active,
            page_catalog=catalog,
            transaction_id=transaction_id,
            commit_id=commit_id,
            operation_id=operation_id,
            data_records=(record_input,) if isinstance(record_input, DataRecordInput) else (),
            blob_records=(record_input,) if isinstance(record_input, BlobRecordInput) else (),
        )

        record_plan = build_typed_transaction(base_request)
        if isinstance(record_input, DataRecordInput):
            if len(record_plan.data_page_numbers) != 1:
                raise JPVRNeuronCheckpointError("one small checkpoint must occupy exactly one DATA page")
            storage_kind = LOCATOR_DATA
            first_page_no = record_plan.data_page_numbers[0]
            page_count = 1
        else:
            blob_numbers = record_plan.blob_page_numbers
            if not blob_numbers or blob_numbers != tuple(range(blob_numbers[0], blob_numbers[0] + len(blob_numbers))):
                raise JPVRNeuronCheckpointError("checkpoint BLOB pages are not one contiguous range")
            storage_kind = LOCATOR_BLOB
            first_page_no = blob_numbers[0]
            page_count = len(blob_numbers)

        root = manifest.index_roots["neuron"]
        root_page_bytes = catalog.get(root.page_no)
        if root_page_bytes is None:
            raise JPVRNeuronCheckpointError("neuron INDEX root page is missing")
        tree_id = decode_index_page(root_page_bytes, expected_page_no=root.page_no).node.tree_id
        record_generation = active.generation + 1
        placeholder = NeuronCheckpointLocator(
            storage_kind=storage_kind,
            record_type=JNVM_RUNTIME_RECORD_TYPE,
            schema_id=JNVM_RUNTIME_SCHEMA_ID,
            schema_version=JNVM_RUNTIME_SCHEMA_VERSION,
            object_id=object_id,
            record_version=next_version,
            first_page_no=first_page_no,
            page_count=page_count,
            record_generation=record_generation,
            commit_page_no=record_plan.commit_page_no,
            commit_id=commit_id,
            transaction_id=transaction_id,
        )
        mutation = IndexMutationInput(
            root_name="neuron",
            tree_id=tree_id,
            key=object_id,
            value=encode_neuron_checkpoint_locator(placeholder),
        )
        placeholder_request = replace(base_request, index_mutations=(mutation,))
        placeholder_plan = build_typed_transaction(placeholder_request)
        final_locator = replace(placeholder, commit_page_no=placeholder_plan.commit_page_no)
        final_mutation = replace(
            mutation, value=encode_neuron_checkpoint_locator(final_locator)
        )
        final_request = replace(base_request, index_mutations=(final_mutation,))
        final_plan = build_typed_transaction(final_request)
        if (
            final_plan.commit_page_no != placeholder_plan.commit_page_no
            or final_plan.data_page_numbers != record_plan.data_page_numbers
            or final_plan.blob_page_numbers != record_plan.blob_page_numbers
            or final_plan.page_count != placeholder_plan.page_count
        ):
            raise JPVRNeuronCheckpointError("locator resolution changed the planned checkpoint page layout")

        published = self.storage.commit(final_plan)
        return SavedNeuronCheckpoint(
            neuron_id=neuron_id,
            object_id=object_id,
            record_version=next_version,
            previous_record_version=previous_version,
            storage_kind=final_locator.storage_name,
            first_page_no=first_page_no,
            page_count=page_count,
            commit_page_no=final_locator.commit_page_no,
            commit_id=commit_id,
            generation=published.generation,
            initialized_indexes=initialized,
            superblock=published,
        )

    def load(self, neuron_id: str, *, max_pages: int = 4096) -> LoadedNeuronCheckpoint:
        """Resolve the active neuron INDEX locator and restore a verified runtime."""
        object_id = neuron_runtime_object_id(neuron_id)
        active, catalog, manifest = self._read_lazy_snapshot(max_pages)
        if manifest is None:
            raise JPVRNeuronCheckpointNotFound(f"no checkpoint exists for neuron {neuron_id!r}")
        root = manifest.index_roots["neuron"]
        root_bytes = catalog.get(root.page_no)
        if root_bytes is None:
            raise JPVRNeuronCheckpointError("neuron INDEX root page is missing")
        root_page = decode_index_page(root_bytes, expected_page_no=root.page_no)
        validate_index_tree(
            root,
            catalog,
            tree_id=root_page.node.tree_id,
            durable_tail_page_no=active.durable_tail_page_no,
        )
        value = lookup_index(
            root,
            catalog,
            object_id,
            tree_id=root_page.node.tree_id,
            durable_tail_page_no=active.durable_tail_page_no,
        )
        if value is None:
            raise JPVRNeuronCheckpointNotFound(f"no checkpoint exists for neuron {neuron_id!r}")
        locator = decode_neuron_checkpoint_locator(value)
        if locator.object_id != object_id:
            raise JPVRNeuronCheckpointError("neuron INDEX key and locator object_id differ")
        snapshot = self._decode_locator_record(neuron_id, locator, active, catalog)
        return LoadedNeuronCheckpoint(snapshot, locator, locator.commit_page_no)
