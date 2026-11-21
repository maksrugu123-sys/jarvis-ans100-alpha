"""JPVR-backed atomic save/load for a RewardBaselineManager snapshot.

Use a dedicated JPVR container for the reward manager in V1. The MANIFEST has
one active CHECKPOINT pointer; sharing that pointer with the network snapshot
would replace one checkpoint with the other. A later composite Runtime store
will publish both in one higher-level snapshot.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
import struct
import uuid

from tools.jpvr_reference.blob import MAX_BLOB_PAGES, decode_blob_pages
from tools.jpvr_reference.data import decode_data_page
from tools.jpvr_reference.codec import (
    JPVRFormatError, PageType, Superblock, crc32c, decode_page,
)
from tools.jpvr_reference.commit import decode_commit_page
from tools.jpvr_reference.manifest import decode_manifest_page
from tools.jpvr_reference.records import MAX_LOGICAL_RECORD_BYTES
from tools.jpvr_reference.storage import JPVRStorage
from tools.jpvr_reference.typed_transaction import (
    BlobRecordInput, CheckpointPageInput, DataRecordInput,
    TypedTransactionRequest, build_typed_transaction,
)

from .jpvr_adapter import NeuronRuntimePersistenceError
from .jpvr_store import JPVRNeuronCheckpointError, JPVRNeuronCheckpointStore
from .reward_baseline import RewardBaselineError, RewardBaselineManager
from .reward_baseline_adapter import (
    REWARD_BASELINE_RECORD_TYPE, REWARD_BASELINE_SCHEMA_ID,
    REWARD_BASELINE_SCHEMA_VERSION, RewardBaselinePersistenceError,
    decode_reward_baseline_snapshot, make_reward_baseline_record_input,
    reward_baseline_object_id,
)

REWARD_BASELINE_CHECKPOINT_MAGIC = b"JRCP"
REWARD_BASELINE_CHECKPOINT_VERSION = 1
_REWARD_CHECKPOINT = struct.Struct("<4sHHHHI16sQQIQQI")
_U64_MAX = (1 << 64) - 1


class JPVRRewardBaselineError(ValueError):
    """Invalid reward-baseline checkpoint descriptor or JPVR store operation."""


class JPVRRewardBaselineNotFound(JPVRRewardBaselineError):
    """The active JPVR snapshot contains no checkpoint for the requested manager."""


class JPVRRewardBaselineConflict(JPVRRewardBaselineError):
    """The requested manager identity or expected record version conflicts."""


@dataclass(frozen=True)
class RewardBaselineCheckpointDescriptor:
    object_id: bytes
    record_version: int
    first_blob_page_no: int
    blob_page_count: int
    record_generation: int
    payload_length: int
    payload_crc32c: int
    storage_kind: str = "BLOB"


@dataclass(frozen=True)
class SavedRewardBaselineCheckpoint:
    manager_id: str
    object_id: bytes
    record_version: int
    previous_record_version: int
    first_blob_page_no: int
    blob_page_count: int
    checkpoint_page_no: int
    commit_page_no: int
    commit_id: bytes
    generation: int
    initialized_indexes: bool
    superblock: Superblock


@dataclass(frozen=True)
class LoadedRewardBaselineCheckpoint:
    manager: RewardBaselineManager
    descriptor: RewardBaselineCheckpointDescriptor
    checkpoint_page_no: int
    source_commit_page_no: int


def _new_id() -> bytes:
    value = uuid.uuid4().bytes
    return value if value != bytes(16) else b"\x00" * 15 + b"\x01"


def _validate_descriptor(value: RewardBaselineCheckpointDescriptor) -> None:
    if not isinstance(value, RewardBaselineCheckpointDescriptor):
        raise JPVRRewardBaselineError("descriptor must be a RewardBaselineCheckpointDescriptor")
    if not isinstance(value.object_id, (bytes, bytearray, memoryview)) or len(value.object_id) != 16:
        raise JPVRRewardBaselineError("reward-baseline object_id must contain exactly 16 bytes")
    if bytes(value.object_id) == bytes(16):
        raise JPVRRewardBaselineError("reward-baseline object_id must be non-zero")
    for name, item, maximum in (
        ("record_version", value.record_version, _U64_MAX),
        ("first_blob_page_no", value.first_blob_page_no, _U64_MAX),
        ("blob_page_count", value.blob_page_count, MAX_BLOB_PAGES),
        ("record_generation", value.record_generation, _U64_MAX),
        ("payload_length", value.payload_length, MAX_LOGICAL_RECORD_BYTES),
        ("payload_crc32c", value.payload_crc32c, 0xFFFFFFFF),
    ):
        if isinstance(item, bool) or not isinstance(item, int) or not 0 <= item <= maximum:
            raise JPVRRewardBaselineError(f"{name} is outside its allowed range")
    if value.storage_kind not in ("BLOB", "DATA"):
        raise JPVRRewardBaselineError("reward-baseline storage_kind must be BLOB or DATA")
    if (
        value.record_version == 0 or value.first_blob_page_no < 2
        or value.blob_page_count == 0
        or (value.storage_kind == "DATA" and value.blob_page_count != 1)
        or value.record_generation == 0 or value.payload_length == 0
    ):
        raise JPVRRewardBaselineError("reward-baseline descriptor contains a zero or invalid required value")


def encode_reward_baseline_checkpoint_descriptor(
    value: RewardBaselineCheckpointDescriptor,
) -> bytes:
    _validate_descriptor(value)
    return _REWARD_CHECKPOINT.pack(
        REWARD_BASELINE_CHECKPOINT_MAGIC,
        REWARD_BASELINE_CHECKPOINT_VERSION,
        0 if value.storage_kind == "BLOB" else 1,
        REWARD_BASELINE_RECORD_TYPE,
        REWARD_BASELINE_SCHEMA_VERSION,
        REWARD_BASELINE_SCHEMA_ID,
        bytes(value.object_id),
        value.record_version,
        value.first_blob_page_no,
        value.blob_page_count,
        value.record_generation,
        value.payload_length,
        value.payload_crc32c,
    )


def decode_reward_baseline_checkpoint_descriptor(
    payload: bytes | bytearray | memoryview,
) -> RewardBaselineCheckpointDescriptor:
    if not isinstance(payload, (bytes, bytearray, memoryview)) or len(payload) != _REWARD_CHECKPOINT.size:
        raise JPVRRewardBaselineError("JRCP checkpoint descriptor has an invalid encoded size")
    (
        magic, version, flags, record_type, schema_version, schema_id,
        object_id, record_version, first_page_no, page_count,
        generation, payload_length, payload_crc,
    ) = _REWARD_CHECKPOINT.unpack(bytes(payload))
    if (
        magic != REWARD_BASELINE_CHECKPOINT_MAGIC
        or version != REWARD_BASELINE_CHECKPOINT_VERSION
        or flags not in (0, 1)
        or record_type != REWARD_BASELINE_RECORD_TYPE
        or schema_version != REWARD_BASELINE_SCHEMA_VERSION
        or schema_id != REWARD_BASELINE_SCHEMA_ID
    ):
        raise JPVRRewardBaselineError("invalid JRCP magic, version, flags or reward-baseline schema identity")
    result = RewardBaselineCheckpointDescriptor(
        object_id, record_version, first_page_no, page_count,
        generation, payload_length, payload_crc,
        storage_kind="BLOB" if flags == 0 else "DATA",
    )
    _validate_descriptor(result)
    return result


class JPVRRewardBaselineStore:
    """One active reward-baseline manager checkpoint per dedicated JPVR container."""

    def __init__(self, storage: JPVRStorage):
        if not isinstance(storage, JPVRStorage):
            raise JPVRRewardBaselineError("storage must be a JPVRStorage instance")
        self.storage = storage
        self._base = JPVRNeuronCheckpointStore(storage)

    def _read_snapshot(self, max_pages: int):
        try:
            return self._base._read_snapshot(max_pages)
        except (JPVRFormatError, JPVRNeuronCheckpointError) as exc:
            raise JPVRRewardBaselineError(str(exc)) from exc

    @staticmethod
    def _source_commit(
        active: Superblock,
        catalog: dict[int, bytes],
        checkpoint_page_no: int,
        checkpoint_page,
        descriptor: RewardBaselineCheckpointDescriptor,
    ):
        expected_generation = descriptor.record_generation
        page_no = active.active_commit_page_no
        generation = active.generation
        seen = set()
        blob_last = descriptor.first_blob_page_no + descriptor.blob_page_count - 1
        while page_no:
            if page_no in seen:
                raise JPVRRewardBaselineError("COMMIT predecessor chain contains a cycle")
            seen.add(page_no)
            raw = catalog.get(page_no)
            if raw is None:
                raise JPVRRewardBaselineError("COMMIT history page is missing from the bounded snapshot")
            commit = decode_commit_page(raw, expected_page_no=page_no)
            if commit.record.generation != generation:
                raise JPVRRewardBaselineError("COMMIT history generation is not contiguous")
            if generation == expected_generation:
                manifest_raw = catalog.get(commit.record.manifest_ref.page_no)
                if manifest_raw is None:
                    raise JPVRRewardBaselineError("source COMMIT MANIFEST is missing")
                source_manifest_page = decode_manifest_page(
                    manifest_raw, expected_page_no=commit.record.manifest_ref.page_no
                )
                if (
                    commit.transaction_id != checkpoint_page.transaction_id
                    or source_manifest_page.transaction_id != commit.transaction_id
                    or source_manifest_page.manifest.generation != commit.record.generation
                    or source_manifest_page.manifest.container_uuid != active.container_uuid
                    or source_manifest_page.manifest.checkpoint_page_no != checkpoint_page_no
                    or commit.record.durable_tail_page_no < max(checkpoint_page_no, blob_last)
                    or checkpoint_page_no >= commit.record.manifest_ref.page_no
                ):
                    raise JPVRRewardBaselineError(
                        "reward-baseline checkpoint is not published by its exact source COMMIT"
                    )
                return commit
            predecessor = commit.record.previous_commit_ref
            if predecessor is None:
                break
            if predecessor.expected_commit_generation != generation - 1:
                raise JPVRRewardBaselineError("COMMIT predecessor generation is invalid")
            page_no = predecessor.page_no
            generation -= 1
        raise JPVRRewardBaselineError(
            "reward-baseline checkpoint source COMMIT is not an ancestor of the active snapshot"
        )

    def _load_from_snapshot(
        self,
        manager_id: str,
        active: Superblock,
        catalog: dict[int, bytes],
        manifest,
    ) -> LoadedRewardBaselineCheckpoint:
        if manifest is None or manifest.checkpoint_page_no == 0:
            raise JPVRRewardBaselineNotFound("active JPVR snapshot has no reward-baseline CHECKPOINT")
        checkpoint_page_no = manifest.checkpoint_page_no
        if active.checkpoint_page_no != checkpoint_page_no:
            raise JPVRRewardBaselineError("Superblock and MANIFEST checkpoint pointers differ")
        raw_checkpoint = catalog.get(checkpoint_page_no)
        if raw_checkpoint is None:
            raise JPVRRewardBaselineError("active reward-baseline CHECKPOINT page is missing")
        checkpoint_page = decode_page(raw_checkpoint, expected_page_no=checkpoint_page_no)
        if checkpoint_page.page_type is not PageType.CHECKPOINT:
            raise JPVRRewardBaselineError("MANIFEST checkpoint pointer does not reference a CHECKPOINT page")
        descriptor = decode_reward_baseline_checkpoint_descriptor(checkpoint_page.payload)
        expected_object_id = reward_baseline_object_id(manager_id)
        if descriptor.object_id != expected_object_id:
            raise JPVRRewardBaselineNotFound(
                "active checkpoint belongs to a different reward-baseline manager ID"
            )
        if checkpoint_page.commit_generation != descriptor.record_generation:
            raise JPVRRewardBaselineError("CHECKPOINT generation differs from its descriptor")
        blob_last = descriptor.first_blob_page_no + descriptor.blob_page_count - 1
        if descriptor.first_blob_page_no >= checkpoint_page_no or blob_last >= checkpoint_page_no:
            raise JPVRRewardBaselineError("reward-baseline BLOB range must precede its CHECKPOINT page")
        source_commit = self._source_commit(
            active, catalog, checkpoint_page_no, checkpoint_page, descriptor
        )
        if descriptor.storage_kind == "BLOB":
            blob_pages = []
            for page_no in range(descriptor.first_blob_page_no, blob_last + 1):
                encoded = catalog.get(page_no)
                if encoded is None:
                    raise JPVRRewardBaselineError(f"reward-baseline BLOB page {page_no} is missing")
                blob_pages.append(encoded)
            blob = decode_blob_pages(
                tuple(blob_pages), expected_first_page_no=descriptor.first_blob_page_no
            )
            if (
                blob.page_count != descriptor.blob_page_count
                or blob.first_page_no != descriptor.first_blob_page_no
                or blob.commit_generation != descriptor.record_generation
                or blob.transaction_id != checkpoint_page.transaction_id
                or blob.record_type != REWARD_BASELINE_RECORD_TYPE
                or blob.schema_id != REWARD_BASELINE_SCHEMA_ID
                or blob.schema_version != REWARD_BASELINE_SCHEMA_VERSION
                or blob.object_id != descriptor.object_id
                or blob.record_version != descriptor.record_version
            ):
                raise JPVRRewardBaselineError("reward-baseline BLOB metadata differs from descriptor")
            record_payload = blob.payload
        else:
            if descriptor.blob_page_count != 1:
                raise JPVRRewardBaselineError("reward-baseline DATA record must occupy one page")
            encoded = catalog.get(descriptor.first_blob_page_no)
            if encoded is None:
                raise JPVRRewardBaselineError("reward-baseline DATA page is missing")
            data_page = decode_data_page(encoded, expected_page_no=descriptor.first_blob_page_no)
            matches = [
                item for item in data_page.records
                if item.record_type == REWARD_BASELINE_RECORD_TYPE
                and item.schema_id == REWARD_BASELINE_SCHEMA_ID
                and item.schema_version == REWARD_BASELINE_SCHEMA_VERSION
                and item.object_id == descriptor.object_id
                and item.record_version == descriptor.record_version
            ]
            if (
                data_page.commit_generation != descriptor.record_generation
                or data_page.transaction_id != checkpoint_page.transaction_id
                or len(matches) != 1
            ):
                raise JPVRRewardBaselineError("reward-baseline DATA metadata differs from descriptor")
            record_payload = matches[0].payload
        if (
            len(record_payload) != descriptor.payload_length
            or crc32c(record_payload) != descriptor.payload_crc32c
        ):
            raise JPVRRewardBaselineError("reward-baseline record length or CRC differs from descriptor")
        try:
            decoded = decode_reward_baseline_snapshot(record_payload)
        except RewardBaselinePersistenceError as exc:
            raise JPVRRewardBaselineError(str(exc)) from exc
        if (
            decoded.manager_id != manager_id
            or reward_baseline_object_id(decoded.manager_id) != descriptor.object_id
        ):
            raise JPVRRewardBaselineError(
                "reward-baseline snapshot identity differs from its CHECKPOINT descriptor"
            )
        return LoadedRewardBaselineCheckpoint(
            decoded.manager, descriptor, checkpoint_page_no, source_commit.page_no,
        )

    def load(
        self, manager_id: str = "jarvis-rstdp-baselines", *, max_pages: int = 4096,
    ) -> LoadedRewardBaselineCheckpoint:
        reward_baseline_object_id(manager_id)
        active, catalog, manifest = self._read_snapshot(max_pages)
        return self._load_from_snapshot(manager_id, active, catalog, manifest)

    def save(
        self,
        manager: RewardBaselineManager,
        *,
        expected_record_version: int | None = None,
        max_pages: int = 4096,
    ) -> SavedRewardBaselineCheckpoint:
        if not isinstance(manager, RewardBaselineManager):
            raise JPVRRewardBaselineError("manager must be a RewardBaselineManager")
        object_id = reward_baseline_object_id(manager.manager_id)
        if expected_record_version is not None and (
            isinstance(expected_record_version, bool)
            or not isinstance(expected_record_version, int)
            or not 0 <= expected_record_version <= _U64_MAX
        ):
            raise JPVRRewardBaselineError(
                "expected_record_version must be an unsigned 64-bit integer"
            )
        try:
            record_input = make_reward_baseline_record_input(manager, record_version=1)
        except (RewardBaselinePersistenceError, RewardBaselineError) as exc:
            raise JPVRRewardBaselineError(str(exc)) from exc

        active, catalog, manifest = self._read_snapshot(max_pages)
        initialized = False
        if manifest is None:
            if max_pages < 9:
                raise JPVRRewardBaselineError(
                    "initial JPVR INDEX setup requires a catalog limit of at least 9 pages"
                )
            if expected_record_version not in (None, 0):
                raise JPVRRewardBaselineConflict(
                    "expected checkpoint version does not match an empty container"
                )
            try:
                self._base._initialize_indexes(active, catalog)
            except (JPVRFormatError, JPVRNeuronCheckpointError) as exc:
                raise JPVRRewardBaselineError(str(exc)) from exc
            initialized = True
            active, catalog, manifest = self._read_snapshot(max_pages)

        previous_version = 0
        if manifest is not None and manifest.checkpoint_page_no:
            try:
                previous = self._load_from_snapshot(
                    manager.manager_id, active, catalog, manifest
                )
            except JPVRRewardBaselineNotFound as exc:
                raise JPVRRewardBaselineConflict(
                    "the container already has an active checkpoint for another object ID"
                ) from exc
            previous_version = previous.descriptor.record_version
        if expected_record_version is not None and expected_record_version != previous_version:
            raise JPVRRewardBaselineConflict(
                f"expected checkpoint version {expected_record_version}, current version is {previous_version}"
            )
        if previous_version == _U64_MAX:
            raise JPVRRewardBaselineError("reward-baseline record_version space is exhausted")
        next_version = previous_version + 1
        record_input = make_reward_baseline_record_input(
            manager, record_version=next_version
        )

        record_fields = (
            {"data_records": (record_input,)}
            if isinstance(record_input, DataRecordInput)
            else {"blob_records": (record_input,)}
        )
        base_request = TypedTransactionRequest(
            current_superblock=active,
            page_catalog=catalog,
            transaction_id=_new_id(),
            commit_id=_new_id(),
            operation_id=_new_id(),
            **record_fields,
        )
        record_plan = build_typed_transaction(base_request)
        if isinstance(record_input, DataRecordInput):
            numbers = record_plan.data_page_numbers
            if len(numbers) != 1:
                raise JPVRRewardBaselineError("one reward-baseline DATA record must resolve to one page")
            storage_kind = "DATA"
        else:
            numbers = record_plan.blob_page_numbers
            if not numbers or numbers != tuple(range(numbers[0], numbers[0] + len(numbers))):
                raise JPVRRewardBaselineError("reward-baseline BLOB pages are not contiguous")
            storage_kind = "BLOB"
        descriptor = RewardBaselineCheckpointDescriptor(
            object_id=object_id,
            record_version=next_version,
            first_blob_page_no=numbers[0],
            blob_page_count=len(numbers),
            record_generation=active.generation + 1,
            payload_length=len(record_input.payload),
            payload_crc32c=crc32c(record_input.payload),
            storage_kind=storage_kind,
        )
        final_request = replace(
            base_request,
            checkpoint_pages=(CheckpointPageInput(
                encode_reward_baseline_checkpoint_descriptor(descriptor)
            ),),
        )
        final_plan = build_typed_transaction(final_request)
        if (
            (
                final_plan.data_page_numbers != numbers
                if storage_kind == "DATA"
                else final_plan.blob_page_numbers != numbers
            )
            or len(final_plan.checkpoint_page_numbers) != 1
            or final_plan.manifest.checkpoint_page_no != final_plan.checkpoint_page_numbers[0]
            or final_plan.commit_page_no <= final_plan.checkpoint_page_numbers[0]
        ):
            raise JPVRRewardBaselineError(
                "reward-baseline CHECKPOINT planning changed the BLOB layout"
            )
        if final_plan.new_superblock.durable_tail_page_no - 1 > max_pages:
            raise JPVRRewardBaselineError(
                "reward-baseline checkpoint would exceed the configured page-catalog bound"
            )
        published = self.storage.commit(final_plan)
        return SavedRewardBaselineCheckpoint(
            manager_id=manager.manager_id,
            object_id=object_id,
            record_version=next_version,
            previous_record_version=previous_version,
            first_blob_page_no=descriptor.first_blob_page_no,
            blob_page_count=descriptor.blob_page_count,
            checkpoint_page_no=final_plan.checkpoint_page_numbers[0],
            commit_page_no=final_plan.commit_page_no,
            commit_id=final_plan.commit_record.commit_id,
            generation=published.generation,
            initialized_indexes=initialized,
            superblock=published,
        )
