"""Versioned, CAS-protected JPVR persistence for TaskSessionManager.

Use a dedicated JPVR container: MANIFEST has one active CHECKPOINT pointer.
The snapshot record and descriptor are published by one JPVR COMMIT.
"""
from __future__ import annotations

from dataclasses import dataclass
import struct
import uuid

from tools.jpvr_reference.blob import BLOB_FRAGMENT_DATA_BYTES, MAX_BLOB_PAGES, decode_blob_pages
from tools.jpvr_reference.codec import JPVRFormatError, PageType, Superblock, crc32c, decode_page
from tools.jpvr_reference.commit import decode_commit_page
from tools.jpvr_reference.data import decode_data_page
from tools.jpvr_reference.manifest import decode_manifest_page
from tools.jpvr_reference.storage import JPVRStorage
from tools.jpvr_reference.typed_transaction import (
    BlobRecordInput, CheckpointPageInput, DataRecordInput,
    TypedTransactionRequest, build_typed_transaction,
)

from .jpvr_store import JPVRNeuronCheckpointError, JPVRNeuronCheckpointStore
from .task_session import TaskSessionManager
from .task_session_adapter import (
    TASK_SESSION_RECORD_TYPE, TASK_SESSION_SCHEMA_ID, TASK_SESSION_SCHEMA_VERSION,
    TaskSessionPersistenceError, decode_task_session_snapshot,
    make_task_session_record_input, task_session_object_id,
)

_MAGIC = b"JTSC"
_VERSION = 1
_DESC = struct.Struct("<4sHHQ16sB7xQQQQI")
_U64_MAX = (1 << 64) - 1


class JPVRTaskSessionCheckpointError(ValueError):
    """Invalid task/session checkpoint or JPVR operation."""


class JPVRTaskSessionCheckpointNotFound(JPVRTaskSessionCheckpointError):
    """No task/session checkpoint exists for this manager identity."""


class JPVRTaskSessionCheckpointConflict(JPVRTaskSessionCheckpointError):
    """Task/session identity or expected checkpoint version conflicts."""


@dataclass(frozen=True)
class TaskSessionCheckpointDescriptor:
    record_version: int
    object_id: bytes
    storage_kind: str
    first_page_no: int
    page_count: int
    record_generation: int
    payload_length: int
    payload_crc32c: int


@dataclass(frozen=True)
class SavedTaskSessionCheckpoint:
    manager_id: str
    record_version: int
    previous_record_version: int
    storage_kind: str
    first_page_no: int
    page_count: int
    checkpoint_page_no: int
    commit_page_no: int
    commit_id: bytes
    generation: int
    initialized_indexes: bool
    superblock: Superblock


@dataclass(frozen=True)
class LoadedTaskSessionCheckpoint:
    manager: TaskSessionManager
    descriptor: TaskSessionCheckpointDescriptor
    checkpoint_page_no: int
    source_commit_page_no: int


def encode_task_session_checkpoint_descriptor(value: TaskSessionCheckpointDescriptor) -> bytes:
    if not isinstance(value, TaskSessionCheckpointDescriptor):
        raise JPVRTaskSessionCheckpointError("value must be a TaskSessionCheckpointDescriptor")
    if isinstance(value.record_version, bool) or not isinstance(value.record_version, int) or not 1 <= value.record_version <= _U64_MAX:
        raise JPVRTaskSessionCheckpointError("record_version must be a positive u64")
    if not isinstance(value.object_id, bytes) or len(value.object_id) != 16 or value.object_id == bytes(16):
        raise JPVRTaskSessionCheckpointError("object_id must be a non-zero 16-byte value")
    kind = {"DATA": 1, "BLOB": 2}.get(value.storage_kind)
    if kind is None:
        raise JPVRTaskSessionCheckpointError("storage_kind must be DATA or BLOB")
    nums = (value.first_page_no, value.page_count, value.record_generation, value.payload_length, value.payload_crc32c)
    if any(isinstance(x, bool) or not isinstance(x, int) or x < 0 or x > _U64_MAX for x in nums):
        raise JPVRTaskSessionCheckpointError("descriptor counters must be unsigned integers")
    if value.first_page_no < 2 or value.page_count < 1 or value.record_generation < 1 or value.payload_length < 1 or value.payload_crc32c > 0xFFFFFFFF:
        raise JPVRTaskSessionCheckpointError("descriptor counters are invalid")
    if kind == 1 and value.page_count != 1:
        raise JPVRTaskSessionCheckpointError("DATA descriptor must reference one page")
    if kind == 2 and value.page_count > MAX_BLOB_PAGES:
        raise JPVRTaskSessionCheckpointError("BLOB descriptor exceeds page limit")
    return _DESC.pack(_MAGIC, _VERSION, 0, value.record_version, value.object_id, kind,
                      value.first_page_no, value.page_count, value.record_generation,
                      value.payload_length, value.payload_crc32c)


def decode_task_session_checkpoint_descriptor(payload: bytes | bytearray | memoryview) -> TaskSessionCheckpointDescriptor:
    if not isinstance(payload, (bytes, bytearray, memoryview)) or len(payload) != _DESC.size:
        raise JPVRTaskSessionCheckpointError("task/session descriptor has invalid size")
    magic, version, flags, record_version, object_id, kind, first, count, generation, length, checksum = _DESC.unpack(bytes(payload))
    if magic != _MAGIC or version != _VERSION or flags != 0:
        raise JPVRTaskSessionCheckpointError("unsupported task/session descriptor")
    value = TaskSessionCheckpointDescriptor(record_version, object_id, {1: "DATA", 2: "BLOB"}.get(kind, ""), first, count, generation, length, checksum)
    encode_task_session_checkpoint_descriptor(value)
    return value


def _new_id() -> bytes:
    value = uuid.uuid4().bytes
    return value if value != bytes(16) else b"\x00" * 15 + b"\x01"


def _record_location(item, plan):
    if isinstance(item, DataRecordInput):
        by_no = dict(zip(plan.assembly.ordered_page_numbers, plan.assembly.ordered_page_bytes))
        found = []
        for number in plan.data_page_numbers:
            page = decode_data_page(by_no[number], expected_page_no=number)
            matches = [r for r in page.records if r.record_type == item.record_type and r.schema_id == item.schema_id and r.schema_version == item.schema_version and r.object_id == bytes(item.object_id) and r.record_version == item.record_version and r.payload == bytes(item.payload)]
            if len(matches) == 1:
                found.append(number)
        if len(found) != 1:
            raise JPVRTaskSessionCheckpointError("task/session DATA record did not resolve to one page")
        return "DATA", found[0], 1
    count = (len(item.payload) + BLOB_FRAGMENT_DATA_BYTES - 1) // BLOB_FRAGMENT_DATA_BYTES
    if not 1 <= count <= MAX_BLOB_PAGES or len(plan.blob_page_numbers) != count:
        raise JPVRTaskSessionCheckpointError("task/session BLOB range is invalid")
    numbers = plan.blob_page_numbers
    if numbers != tuple(range(numbers[0], numbers[0] + count)):
        raise JPVRTaskSessionCheckpointError("task/session BLOB pages are not contiguous")
    pages = tuple(dict(zip(plan.assembly.ordered_page_numbers, plan.assembly.ordered_page_bytes))[n] for n in numbers)
    blob = decode_blob_pages(pages, expected_first_page_no=numbers[0])
    if blob.payload != bytes(item.payload) or blob.object_id != bytes(item.object_id) or blob.record_version != item.record_version:
        raise JPVRTaskSessionCheckpointError("task/session BLOB does not match its record input")
    return "BLOB", numbers[0], count


class JPVRTaskSessionCheckpointStore:
    """Single-writer task/session checkpoint store for one dedicated JPVR container."""

    def __init__(self, storage: JPVRStorage):
        if not isinstance(storage, JPVRStorage):
            raise JPVRTaskSessionCheckpointError("storage must be a JPVRStorage instance")
        self.storage = storage
        self._base = JPVRNeuronCheckpointStore(storage)

    def _read(self, max_pages):
        try:
            active, catalog = self.storage.load_snapshot(max_pages=max_pages)
            return active, catalog, self._base._manifest(active, catalog)
        except (JPVRFormatError, JPVRNeuronCheckpointError) as exc:
            raise JPVRTaskSessionCheckpointError(str(exc)) from exc

    def _payload(self, descriptor, checkpoint, catalog):
        end = descriptor.first_page_no + descriptor.page_count - 1
        if end >= checkpoint.page_no:
            raise JPVRTaskSessionCheckpointError("task/session record must precede its CHECKPOINT")
        if descriptor.storage_kind == "DATA":
            if descriptor.page_count != 1:
                raise JPVRTaskSessionCheckpointError("DATA record must occupy one page")
            raw = catalog.get(descriptor.first_page_no)
            if raw is None:
                raise JPVRTaskSessionCheckpointError("task/session DATA page is missing")
            page = decode_data_page(raw, expected_page_no=descriptor.first_page_no)
            matches = [r for r in page.records if r.record_type == TASK_SESSION_RECORD_TYPE and r.schema_id == TASK_SESSION_SCHEMA_ID and r.schema_version == TASK_SESSION_SCHEMA_VERSION and r.object_id == descriptor.object_id and r.record_version == descriptor.record_version]
            if page.commit_generation != descriptor.record_generation or page.transaction_id != checkpoint.transaction_id or len(matches) != 1:
                raise JPVRTaskSessionCheckpointError("task/session DATA metadata mismatch")
            payload = matches[0].payload
        else:
            pages = []
            for number in range(descriptor.first_page_no, end + 1):
                raw = catalog.get(number)
                if raw is None:
                    raise JPVRTaskSessionCheckpointError("task/session BLOB page is missing")
                pages.append(raw)
            blob = decode_blob_pages(tuple(pages), expected_first_page_no=descriptor.first_page_no)
            if blob.page_count != descriptor.page_count or blob.commit_generation != descriptor.record_generation or blob.transaction_id != checkpoint.transaction_id or blob.record_type != TASK_SESSION_RECORD_TYPE or blob.schema_id != TASK_SESSION_SCHEMA_ID or blob.schema_version != TASK_SESSION_SCHEMA_VERSION or blob.object_id != descriptor.object_id or blob.record_version != descriptor.record_version:
                raise JPVRTaskSessionCheckpointError("task/session BLOB metadata mismatch")
            payload = blob.payload
        if len(payload) != descriptor.payload_length or crc32c(payload) != descriptor.payload_crc32c:
            raise JPVRTaskSessionCheckpointError("task/session payload length or CRC mismatch")
        return payload

    def _load_snapshot(self, manager_id, active, catalog, manifest):
        if manifest is None or not manifest.checkpoint_page_no:
            raise JPVRTaskSessionCheckpointNotFound("container has no task/session checkpoint")
        checkpoint_no = manifest.checkpoint_page_no
        if active.checkpoint_page_no != checkpoint_no:
            raise JPVRTaskSessionCheckpointError("Superblock and MANIFEST checkpoint pointers differ")
        raw = catalog.get(checkpoint_no)
        if raw is None:
            raise JPVRTaskSessionCheckpointError("task/session CHECKPOINT page is missing")
        checkpoint = decode_page(raw, expected_page_no=checkpoint_no)
        if checkpoint.page_type is not PageType.CHECKPOINT:
            raise JPVRTaskSessionCheckpointError("MANIFEST pointer is not a CHECKPOINT page")
        descriptor = decode_task_session_checkpoint_descriptor(checkpoint.payload)
        expected_object = task_session_object_id(manager_id)
        if descriptor.object_id != expected_object:
            raise JPVRTaskSessionCheckpointNotFound("checkpoint belongs to another manager_id")
        if checkpoint.commit_generation != descriptor.record_generation:
            raise JPVRTaskSessionCheckpointError("record and CHECKPOINT generations differ")
        source_page_no = active.active_commit_page_no
        generation = active.generation
        seen = set()
        source = None
        while source_page_no:
            if source_page_no in seen:
                raise JPVRTaskSessionCheckpointError("COMMIT chain contains a cycle")
            seen.add(source_page_no)
            encoded = catalog.get(source_page_no)
            if encoded is None:
                raise JPVRTaskSessionCheckpointError("COMMIT history page is missing")
            commit = decode_commit_page(encoded, expected_page_no=source_page_no)
            if commit.record.generation != generation:
                raise JPVRTaskSessionCheckpointError("COMMIT history generation is discontinuous")
            if generation == descriptor.record_generation:
                manifest_raw = catalog.get(commit.record.manifest_ref.page_no)
                if manifest_raw is None:
                    raise JPVRTaskSessionCheckpointError("source MANIFEST is missing")
                source_manifest = decode_manifest_page(manifest_raw, expected_page_no=commit.record.manifest_ref.page_no)
                if commit.transaction_id != checkpoint.transaction_id or source_manifest.manifest.checkpoint_page_no != checkpoint_no or commit.record.durable_tail_page_no < checkpoint_no:
                    raise JPVRTaskSessionCheckpointError("checkpoint is not published by its exact source COMMIT")
                source = commit
                break
            previous = commit.record.previous_commit_ref
            if previous is None:
                break
            if previous.expected_commit_generation != generation - 1:
                raise JPVRTaskSessionCheckpointError("COMMIT predecessor reference is invalid")
            source_page_no, generation = previous.page_no, generation - 1
        if source is None:
            raise JPVRTaskSessionCheckpointError("checkpoint source COMMIT is not an ancestor of active state")
        payload = self._payload(descriptor, checkpoint, catalog)
        try:
            decoded = decode_task_session_snapshot(payload)
        except TaskSessionPersistenceError as exc:
            raise JPVRTaskSessionCheckpointError(str(exc)) from exc
        if decoded.manager_id != manager_id:
            raise JPVRTaskSessionCheckpointNotFound("snapshot manager_id does not match requested identity")
        return LoadedTaskSessionCheckpoint(decoded.manager, descriptor, checkpoint_no, source.page_no)

    def load(self, manager_id: str, *, max_pages: int = 4096) -> LoadedTaskSessionCheckpoint:
        task_session_object_id(manager_id)
        active, catalog, manifest = self._read(max_pages)
        return self._load_snapshot(manager_id, active, catalog, manifest)

    def save(self, manager: TaskSessionManager, *, expected_record_version: int | None = None, max_pages: int = 4096) -> SavedTaskSessionCheckpoint:
        if not isinstance(manager, TaskSessionManager):
            raise JPVRTaskSessionCheckpointError("manager must be a TaskSessionManager")
        if expected_record_version is not None and (isinstance(expected_record_version, bool) or not isinstance(expected_record_version, int) or not 0 <= expected_record_version <= _U64_MAX):
            raise JPVRTaskSessionCheckpointError("expected_record_version must be an unsigned 64-bit integer")
        manager_id = manager.manager_id
        active, catalog, manifest = self._read(max_pages)
        initialized = False
        if manifest is None:
            if max_pages < 9:
                raise JPVRTaskSessionCheckpointError("initial INDEX setup requires max_pages >= 9")
            if expected_record_version not in (None, 0):
                raise JPVRTaskSessionCheckpointConflict("expected version does not match an empty container")
            self._base._initialize_indexes(active, catalog)
            initialized = True
            active, catalog, manifest = self._read(max_pages)
        previous = None
        if manifest.checkpoint_page_no:
            try:
                previous = self._load_snapshot(manager_id, active, catalog, manifest)
            except JPVRTaskSessionCheckpointNotFound as exc:
                raise JPVRTaskSessionCheckpointConflict("dedicated container already belongs to another manager") from exc
            except JPVRTaskSessionCheckpointError as exc:
                raise JPVRTaskSessionCheckpointConflict("active checkpoint is not a valid task/session checkpoint; use a dedicated container") from exc
        previous_version = 0 if previous is None else previous.descriptor.record_version
        if expected_record_version is not None and expected_record_version != previous_version:
            raise JPVRTaskSessionCheckpointConflict(f"expected version {expected_record_version}, current version is {previous_version}")
        if previous_version >= _U64_MAX:
            raise JPVRTaskSessionCheckpointError("task/session record version space is exhausted")
        record_version = previous_version + 1
        try:
            item = make_task_session_record_input(manager, record_version=record_version)
        except TaskSessionPersistenceError as exc:
            raise JPVRTaskSessionCheckpointError(str(exc)) from exc
        base_request = TypedTransactionRequest(
            current_superblock=active, page_catalog=catalog,
            transaction_id=_new_id(), commit_id=_new_id(), operation_id=_new_id(),
            data_records=(item,) if isinstance(item, DataRecordInput) else (),
            blob_records=(item,) if isinstance(item, BlobRecordInput) else (),
        )
        record_plan = build_typed_transaction(base_request)
        kind, first, count = _record_location(item, record_plan)
        descriptor = TaskSessionCheckpointDescriptor(
            record_version, task_session_object_id(manager_id), kind, first, count,
            active.generation + 1, len(item.payload), crc32c(item.payload),
        )
        final_request = TypedTransactionRequest(
            current_superblock=active, page_catalog=catalog,
            transaction_id=base_request.transaction_id, commit_id=base_request.commit_id,
            operation_id=base_request.operation_id,
            data_records=base_request.data_records, blob_records=base_request.blob_records,
            checkpoint_pages=(CheckpointPageInput(encode_task_session_checkpoint_descriptor(descriptor)),),
        )
        plan = build_typed_transaction(final_request)
        if plan.commit_record.generation != descriptor.record_generation or len(plan.checkpoint_page_numbers) != 1:
            raise JPVRTaskSessionCheckpointError("task/session checkpoint transaction layout changed unexpectedly")
        if plan.new_superblock.durable_tail_page_no - 1 > max_pages:
            raise JPVRTaskSessionCheckpointError("task/session checkpoint exceeds page catalog bound")
        published = self.storage.commit(plan)
        return SavedTaskSessionCheckpoint(
            manager_id, record_version, previous_version, kind, first, count,
            plan.checkpoint_page_numbers[0], plan.commit_page_no,
            plan.commit_record.commit_id, published.generation, initialized, published,
        )
