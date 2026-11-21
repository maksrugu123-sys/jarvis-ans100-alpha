"""Atomic JPVR checkpoint for NetworkRuntime and RewardBaselineManager together.

This is a bounded Python reference store. Both logical records and the composite
CHECKPOINT descriptor are published by one JPVR COMMIT; it does not make reward
processing plus synapse mutation atomic at runtime.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
import math
import struct
import uuid

from tools.jpvr_reference.blob import (
    BLOB_FRAGMENT_DATA_BYTES, MAX_BLOB_PAGES, decode_blob_pages,
)
from tools.jpvr_reference.codec import (
    JPVRFormatError, PageType, Superblock, crc32c, decode_page,
)
from tools.jpvr_reference.commit import decode_commit_page
from tools.jpvr_reference.data import decode_data_page
from tools.jpvr_reference.manifest import decode_manifest_page
from tools.jpvr_reference.storage import JPVRStorage
from tools.jpvr_reference.typed_transaction import (
    BlobRecordInput, CheckpointPageInput, DataRecordInput,
    TypedTransactionRequest, build_typed_transaction,
)

from .jpvr_adapter import NeuronRuntimePersistenceError
from .jpvr_store import JPVRNeuronCheckpointError, JPVRNeuronCheckpointStore
from .network import NetworkRuntime
from .network_adapter import (
    DecodedNetworkRuntimeSnapshot, NETWORK_RUNTIME_RECORD_TYPE,
    NETWORK_RUNTIME_SCHEMA_ID, NETWORK_RUNTIME_SCHEMA_VERSION,
    NetworkRuntimePersistenceError, decode_network_runtime_snapshot,
    make_network_runtime_record_input, network_runtime_object_id,
)
from .network_store import (
    JPVRNetworkCheckpointError, JPVRNetworkCheckpointNotFound,
    NetworkCheckpointDescriptor, decode_network_checkpoint_descriptor,
    encode_network_checkpoint_descriptor,
)
from .reward_baseline import RewardBaselineError, RewardBaselineManager
from .reward_baseline_adapter import (
    REWARD_BASELINE_RECORD_TYPE, REWARD_BASELINE_SCHEMA_ID,
    REWARD_BASELINE_SCHEMA_VERSION, RewardBaselinePersistenceError,
    decode_reward_baseline_snapshot, make_reward_baseline_record_input,
    reward_baseline_object_id,
)
from .reward_baseline_store import (
    JPVRRewardBaselineError, JPVRRewardBaselineNotFound,
    RewardBaselineCheckpointDescriptor,
    decode_reward_baseline_checkpoint_descriptor,
    encode_reward_baseline_checkpoint_descriptor,
)


RUNTIME_CHECKPOINT_MAGIC = b"JRCM"
RUNTIME_CHECKPOINT_VERSION = 1
_RUNTIME_HEADER = struct.Struct("<4sHHQ")
_COMPONENT_DESCRIPTOR_BYTES = 72
_U64_MAX = (1 << 64) - 1


class JPVRRuntimeCheckpointError(ValueError):
    """Invalid composite runtime descriptor or JPVR save/load operation."""


class JPVRRuntimeCheckpointNotFound(JPVRRuntimeCheckpointError):
    """The active JPVR snapshot has no composite runtime checkpoint for these IDs."""


class JPVRRuntimeCheckpointConflict(JPVRRuntimeCheckpointError):
    """Runtime checkpoint identity or compare-and-swap version conflicts."""


@dataclass(frozen=True)
class RuntimeCheckpointDescriptor:
    runtime_version: int
    network: NetworkCheckpointDescriptor
    reward_baseline: RewardBaselineCheckpointDescriptor


@dataclass(frozen=True)
class SavedRuntimeCheckpoint:
    network_id: str
    manager_id: str
    runtime_version: int
    previous_runtime_version: int
    network_record_version: int
    reward_baseline_record_version: int
    checkpoint_page_no: int
    commit_page_no: int
    commit_id: bytes
    generation: int
    initialized_indexes: bool
    superblock: Superblock


@dataclass(frozen=True)
class LoadedRuntimeCheckpoint:
    snapshot: DecodedNetworkRuntimeSnapshot
    reward_baseline_manager: RewardBaselineManager
    descriptor: RuntimeCheckpointDescriptor
    checkpoint_page_no: int
    source_commit_page_no: int


def _new_id() -> bytes:
    value = uuid.uuid4().bytes
    return value if value != bytes(16) else b"\x00" * 15 + b"\x01"


def encode_runtime_checkpoint_descriptor(
    value: RuntimeCheckpointDescriptor,
) -> bytes:
    if not isinstance(value, RuntimeCheckpointDescriptor):
        raise JPVRRuntimeCheckpointError("value must be a RuntimeCheckpointDescriptor")
    if (
        isinstance(value.runtime_version, bool)
        or not isinstance(value.runtime_version, int)
        or not 1 <= value.runtime_version <= _U64_MAX
    ):
        raise JPVRRuntimeCheckpointError("runtime_version must be a non-zero unsigned 64-bit integer")
    try:
        network = encode_network_checkpoint_descriptor(value.network)
        baseline = encode_reward_baseline_checkpoint_descriptor(value.reward_baseline)
    except (JPVRNetworkCheckpointError, JPVRRewardBaselineError) as exc:
        raise JPVRRuntimeCheckpointError(str(exc)) from exc
    if len(network) != _COMPONENT_DESCRIPTOR_BYTES or len(baseline) != _COMPONENT_DESCRIPTOR_BYTES:
        raise JPVRRuntimeCheckpointError("component descriptor size differs from the JRCM V1 contract")
    if (
        value.network.record_generation != value.reward_baseline.record_generation
        or value.network.storage_kind not in ("DATA", "BLOB")
        or value.reward_baseline.storage_kind not in ("DATA", "BLOB")
    ):
        raise JPVRRuntimeCheckpointError("component descriptors must share one commit generation")
    return (
        _RUNTIME_HEADER.pack(
            RUNTIME_CHECKPOINT_MAGIC, RUNTIME_CHECKPOINT_VERSION, 0,
            value.runtime_version,
        )
        + network
        + baseline
    )


def decode_runtime_checkpoint_descriptor(
    payload: bytes | bytearray | memoryview,
) -> RuntimeCheckpointDescriptor:
    expected = _RUNTIME_HEADER.size + 2 * _COMPONENT_DESCRIPTOR_BYTES
    if not isinstance(payload, (bytes, bytearray, memoryview)) or len(payload) != expected:
        raise JPVRRuntimeCheckpointError("JRCM descriptor has an invalid encoded size")
    raw = bytes(payload)
    magic, version, flags, runtime_version = _RUNTIME_HEADER.unpack_from(raw, 0)
    if (
        magic != RUNTIME_CHECKPOINT_MAGIC
        or version != RUNTIME_CHECKPOINT_VERSION
        or flags != 0
        or isinstance(runtime_version, bool)
        or runtime_version < 1
    ):
        raise JPVRRuntimeCheckpointError("invalid JRCM magic, version, flags or runtime_version")
    start = _RUNTIME_HEADER.size
    try:
        network = decode_network_checkpoint_descriptor(
            raw[start:start + _COMPONENT_DESCRIPTOR_BYTES]
        )
        baseline = decode_reward_baseline_checkpoint_descriptor(
            raw[start + _COMPONENT_DESCRIPTOR_BYTES:]
        )
    except (JPVRNetworkCheckpointError, JPVRRewardBaselineError) as exc:
        raise JPVRRuntimeCheckpointError(str(exc)) from exc
    if network.record_generation != baseline.record_generation:
        raise JPVRRuntimeCheckpointError("JRCM component generations differ")
    return RuntimeCheckpointDescriptor(runtime_version, network, baseline)


def _record_key(item: DataRecordInput | BlobRecordInput) -> tuple:
    return (
        item.record_type, item.schema_id, item.schema_version,
        bytes(item.object_id), item.record_version,
    )


def _record_location(
    item: DataRecordInput | BlobRecordInput,
    plan,
    *,
    page_bytes: dict[int, bytes],
    blob_locations: dict[tuple, tuple[int, int]],
) -> tuple[str, int, int]:
    key = _record_key(item)
    if isinstance(item, DataRecordInput):
        matches_found: list[int] = []
        for page_no in plan.data_page_numbers:
            encoded = page_bytes.get(page_no)
            if encoded is None:
                raise JPVRRuntimeCheckpointError("planned DATA page is missing from its transaction")
            page = decode_data_page(encoded, expected_page_no=page_no)
            matches = [
                record for record in page.records
                if (
                    record.record_type, record.schema_id, record.schema_version,
                    record.object_id, record.record_version,
                ) == key and record.payload == bytes(item.payload)
            ]
            if matches:
                if page.commit_generation != plan.commit_record.generation:
                    raise JPVRRuntimeCheckpointError("DATA page generation differs from transaction")
                if page.transaction_id != plan.assembly.transaction_id:
                    raise JPVRRuntimeCheckpointError("DATA page transaction ID differs from transaction")
                if len(matches) != 1:
                    raise JPVRRuntimeCheckpointError("DATA page contains duplicate logical record identities")
                matches_found.append(page_no)
        if len(matches_found) != 1:
            raise JPVRRuntimeCheckpointError("DATA record does not resolve to exactly one DATA page")
        return "DATA", matches_found[0], 1
    location = blob_locations.get(key)
    if location is None:
        raise JPVRRuntimeCheckpointError("BLOB record does not resolve to a BLOB page range")
    return "BLOB", location[0], location[1]


def _build_blob_locations(
    items: tuple[BlobRecordInput, ...],
    plan,
) -> dict[tuple, tuple[int, int]]:
    numbers = plan.blob_page_numbers
    cursor = 0
    locations: dict[tuple, tuple[int, int]] = {}
    generation = plan.commit_record.generation
    transaction_id = plan.assembly.transaction_id
    for item in items:
        count = (len(item.payload) + BLOB_FRAGMENT_DATA_BYTES - 1) // BLOB_FRAGMENT_DATA_BYTES
        if not 1 <= count <= MAX_BLOB_PAGES or cursor + count > len(numbers):
            raise JPVRRuntimeCheckpointError("BLOB page count does not match the typed transaction plan")
        group_numbers = numbers[cursor:cursor + count]
        if group_numbers != tuple(range(group_numbers[0], group_numbers[0] + count)):
            raise JPVRRuntimeCheckpointError("one BLOB record is not stored in a contiguous page range")
        # Read the immutable planned page bytes from the assembly candidate.
        by_no = dict(zip(plan.assembly.ordered_page_numbers, plan.assembly.ordered_page_bytes))
        pages = tuple(by_no[number] for number in group_numbers)
        decoded = decode_blob_pages(pages, expected_first_page_no=group_numbers[0])
        if (
            decoded.record_type != item.record_type
            or decoded.schema_id != item.schema_id
            or decoded.schema_version != item.schema_version
            or decoded.object_id != bytes(item.object_id)
            or decoded.record_version != item.record_version
            or decoded.payload != bytes(item.payload)
            or decoded.commit_generation != generation
            or decoded.transaction_id != transaction_id
        ):
            raise JPVRRuntimeCheckpointError("BLOB metadata differs from its typed record input")
        key = _record_key(item)
        if key in locations:
            raise JPVRRuntimeCheckpointError("duplicate logical BLOB record identity")
        locations[key] = (group_numbers[0], count)
        cursor += count
    if cursor != len(numbers):
        raise JPVRRuntimeCheckpointError("typed transaction contains unassigned BLOB pages")
    return locations


def _payload_from_catalog(
    descriptor,
    *,
    catalog: dict[int, bytes],
    checkpoint_page,
    record_type: int,
    schema_id: int,
    schema_version: int,
) -> bytes:
    last_page = descriptor.first_blob_page_no + descriptor.blob_page_count - 1
    if descriptor.first_blob_page_no >= checkpoint_page.page_no or last_page >= checkpoint_page.page_no:
        raise JPVRRuntimeCheckpointError("component record pages must precede the composite CHECKPOINT")
    if descriptor.storage_kind == "BLOB":
        pages = []
        for page_no in range(descriptor.first_blob_page_no, last_page + 1):
            encoded = catalog.get(page_no)
            if encoded is None:
                raise JPVRRuntimeCheckpointError(f"component BLOB page {page_no} is missing")
            pages.append(encoded)
        blob = decode_blob_pages(tuple(pages), expected_first_page_no=descriptor.first_blob_page_no)
        if (
            blob.page_count != descriptor.blob_page_count
            or blob.first_page_no != descriptor.first_blob_page_no
            or blob.commit_generation != descriptor.record_generation
            or blob.transaction_id != checkpoint_page.transaction_id
            or blob.record_type != record_type
            or blob.schema_id != schema_id
            or blob.schema_version != schema_version
            or blob.object_id != descriptor.object_id
            or blob.record_version != descriptor.record_version
        ):
            raise JPVRRuntimeCheckpointError("component BLOB metadata differs from the composite descriptor")
        payload = blob.payload
    elif descriptor.storage_kind == "DATA":
        if descriptor.blob_page_count != 1:
            raise JPVRRuntimeCheckpointError("component DATA record must reference exactly one DATA page")
        encoded = catalog.get(descriptor.first_blob_page_no)
        if encoded is None:
            raise JPVRRuntimeCheckpointError("component DATA page is missing")
        page = decode_data_page(encoded, expected_page_no=descriptor.first_blob_page_no)
        matches = [
            record for record in page.records
            if (
                record.record_type == record_type
                and record.schema_id == schema_id
                and record.schema_version == schema_version
                and record.object_id == descriptor.object_id
                and record.record_version == descriptor.record_version
            )
        ]
        if (
            page.commit_generation != descriptor.record_generation
            or page.transaction_id != checkpoint_page.transaction_id
            or len(matches) != 1
        ):
            raise JPVRRuntimeCheckpointError("component DATA metadata differs from the composite descriptor")
        payload = matches[0].payload
    else:
        raise JPVRRuntimeCheckpointError("component record has an unsupported storage kind")
    if len(payload) != descriptor.payload_length or crc32c(payload) != descriptor.payload_crc32c:
        raise JPVRRuntimeCheckpointError("component payload length or CRC differs from the composite descriptor")
    return payload


class JPVRRuntimeCheckpointStore:
    """One atomic NetworkRuntime + RewardBaselineManager checkpoint per JPVR container."""

    def __init__(self, storage: JPVRStorage):
        if not isinstance(storage, JPVRStorage):
            raise JPVRRuntimeCheckpointError("storage must be a JPVRStorage instance")
        self.storage = storage
        self._base = JPVRNeuronCheckpointStore(storage)

    def _read_snapshot(self, max_pages: int):
        try:
            active, catalog = self.storage.load_snapshot(max_pages=max_pages)
            manifest = self._base._manifest(active, catalog)
            return active, catalog, manifest
        except (JPVRFormatError, JPVRNeuronCheckpointError) as exc:
            raise JPVRRuntimeCheckpointError(str(exc)) from exc

    @staticmethod
    def _source_commit(
        active: Superblock,
        catalog: dict[int, bytes],
        checkpoint_page,
        descriptor: RuntimeCheckpointDescriptor,
    ):
        expected_generation = descriptor.network.record_generation
        page_end = max(
            descriptor.network.first_blob_page_no + descriptor.network.blob_page_count - 1,
            descriptor.reward_baseline.first_blob_page_no + descriptor.reward_baseline.blob_page_count - 1,
            checkpoint_page.page_no,
        )
        page_no, generation, seen = active.active_commit_page_no, active.generation, set()
        while page_no:
            if page_no in seen:
                raise JPVRRuntimeCheckpointError("COMMIT predecessor chain contains a cycle")
            seen.add(page_no)
            raw = catalog.get(page_no)
            if raw is None:
                raise JPVRRuntimeCheckpointError("COMMIT history page is missing from the bounded snapshot")
            commit = decode_commit_page(raw, expected_page_no=page_no)
            if commit.record.generation != generation:
                raise JPVRRuntimeCheckpointError("COMMIT history generation is not contiguous")
            if generation == expected_generation:
                manifest_raw = catalog.get(commit.record.manifest_ref.page_no)
                if manifest_raw is None:
                    raise JPVRRuntimeCheckpointError("source COMMIT MANIFEST is missing")
                manifest_page = decode_manifest_page(
                    manifest_raw, expected_page_no=commit.record.manifest_ref.page_no
                )
                if (
                    commit.transaction_id != checkpoint_page.transaction_id
                    or manifest_page.transaction_id != commit.transaction_id
                    or manifest_page.manifest.generation != generation
                    or manifest_page.manifest.container_uuid != active.container_uuid
                    or manifest_page.manifest.checkpoint_page_no != checkpoint_page.page_no
                    or commit.record.durable_tail_page_no < page_end
                    or checkpoint_page.page_no >= commit.record.manifest_ref.page_no
                ):
                    raise JPVRRuntimeCheckpointError("composite checkpoint is not published by its exact source COMMIT")
                return commit
            previous = commit.record.previous_commit_ref
            if previous is None:
                break
            if previous.expected_commit_generation != generation - 1:
                raise JPVRRuntimeCheckpointError("COMMIT predecessor generation is invalid")
            page_no, generation = previous.page_no, generation - 1
        raise JPVRRuntimeCheckpointError("composite checkpoint source COMMIT is not an ancestor of the active snapshot")

    def _load_from_snapshot(
        self,
        network_id: str,
        manager_id: str,
        active: Superblock,
        catalog: dict[int, bytes],
        manifest,
    ) -> LoadedRuntimeCheckpoint:
        if manifest is None or manifest.checkpoint_page_no == 0:
            raise JPVRRuntimeCheckpointNotFound("active JPVR snapshot has no composite runtime CHECKPOINT")
        checkpoint_no = manifest.checkpoint_page_no
        if active.checkpoint_page_no != checkpoint_no:
            raise JPVRRuntimeCheckpointError("Superblock and MANIFEST checkpoint pointers differ")
        raw = catalog.get(checkpoint_no)
        if raw is None:
            raise JPVRRuntimeCheckpointError("composite runtime CHECKPOINT page is missing")
        checkpoint_page = decode_page(raw, expected_page_no=checkpoint_no)
        if checkpoint_page.page_type is not PageType.CHECKPOINT:
            raise JPVRRuntimeCheckpointError("MANIFEST pointer does not reference a CHECKPOINT page")
        descriptor = decode_runtime_checkpoint_descriptor(checkpoint_page.payload)
        if (
            descriptor.network.object_id != network_runtime_object_id(network_id)
            or descriptor.reward_baseline.object_id != reward_baseline_object_id(manager_id)
        ):
            raise JPVRRuntimeCheckpointNotFound("active composite checkpoint belongs to different runtime IDs")
        if (
            checkpoint_page.commit_generation != descriptor.network.record_generation
            or descriptor.network.record_generation != descriptor.reward_baseline.record_generation
        ):
            raise JPVRRuntimeCheckpointError("component checkpoint generations differ from the CHECKPOINT page")
        source_commit = self._source_commit(active, catalog, checkpoint_page, descriptor)
        network_payload = _payload_from_catalog(
            descriptor.network, catalog=catalog, checkpoint_page=checkpoint_page,
            record_type=NETWORK_RUNTIME_RECORD_TYPE,
            schema_id=NETWORK_RUNTIME_SCHEMA_ID,
            schema_version=NETWORK_RUNTIME_SCHEMA_VERSION,
        )
        baseline_payload = _payload_from_catalog(
            descriptor.reward_baseline, catalog=catalog, checkpoint_page=checkpoint_page,
            record_type=REWARD_BASELINE_RECORD_TYPE,
            schema_id=REWARD_BASELINE_SCHEMA_ID,
            schema_version=REWARD_BASELINE_SCHEMA_VERSION,
        )
        try:
            snapshot = decode_network_runtime_snapshot(network_payload)
            baseline = decode_reward_baseline_snapshot(baseline_payload)
        except (NetworkRuntimePersistenceError, NeuronRuntimePersistenceError,
                RewardBaselinePersistenceError) as exc:
            raise JPVRRuntimeCheckpointError(str(exc)) from exc
        if (
            snapshot.network_id != network_id
            or baseline.manager_id != manager_id
            or snapshot.clock_epoch_id != baseline.clock_epoch_id
        ):
            raise JPVRRuntimeCheckpointError("runtime component IDs or clock epochs do not match")
        return LoadedRuntimeCheckpoint(
            snapshot, baseline.manager, descriptor, checkpoint_no, source_commit.page_no,
        )

    def load(
        self,
        network_id: str,
        manager_id: str = "jarvis-rstdp-baselines",
        *,
        max_pages: int = 4096,
    ) -> LoadedRuntimeCheckpoint:
        network_runtime_object_id(network_id)
        reward_baseline_object_id(manager_id)
        active, catalog, manifest = self._read_snapshot(max_pages)
        return self._load_from_snapshot(network_id, manager_id, active, catalog, manifest)

    def save(
        self,
        network_id: str,
        network: NetworkRuntime,
        reward_baseline_manager: RewardBaselineManager,
        *,
        expected_runtime_version: int | None = None,
        max_pages: int = 4096,
    ) -> SavedRuntimeCheckpoint:
        if not isinstance(network, NetworkRuntime):
            raise JPVRRuntimeCheckpointError("network must be a NetworkRuntime")
        if not isinstance(reward_baseline_manager, RewardBaselineManager):
            raise JPVRRuntimeCheckpointError("reward_baseline_manager must be a RewardBaselineManager")
        if expected_runtime_version is not None and (
            isinstance(expected_runtime_version, bool)
            or not isinstance(expected_runtime_version, int)
            or not 0 <= expected_runtime_version <= _U64_MAX
        ):
            raise JPVRRuntimeCheckpointError("expected_runtime_version must be an unsigned 64-bit integer")
        manager_id = reward_baseline_manager.manager_id
        epoch = reward_baseline_manager.clock_epoch_id
        try:
            network_input = make_network_runtime_record_input(
                network_id, network, clock_epoch_id=epoch, record_version=1
            )
            baseline_input = make_reward_baseline_record_input(
                reward_baseline_manager, record_version=1
            )
        except (
            NetworkRuntimePersistenceError, NeuronRuntimePersistenceError,
            RewardBaselinePersistenceError, RewardBaselineError,
        ) as exc:
            raise JPVRRuntimeCheckpointError(str(exc)) from exc

        active, catalog, manifest = self._read_snapshot(max_pages)
        initialized = False
        if manifest is None:
            if max_pages < 9:
                raise JPVRRuntimeCheckpointError("initial JPVR INDEX setup requires max_pages >= 9")
            if expected_runtime_version not in (None, 0):
                raise JPVRRuntimeCheckpointConflict("expected runtime version does not match an empty container")
            try:
                self._base._initialize_indexes(active, catalog)
            except (JPVRFormatError, JPVRNeuronCheckpointError) as exc:
                raise JPVRRuntimeCheckpointError(str(exc)) from exc
            initialized = True
            active, catalog, manifest = self._read_snapshot(max_pages)

        previous = None
        if manifest is not None and manifest.checkpoint_page_no:
            try:
                previous = self._load_from_snapshot(
                    network_id, manager_id, active, catalog, manifest
                )
            except JPVRRuntimeCheckpointNotFound as exc:
                raise JPVRRuntimeCheckpointConflict(
                    "the container already has a checkpoint for another runtime identity"
                ) from exc
            except JPVRRuntimeCheckpointError as exc:
                raise JPVRRuntimeCheckpointConflict(
                    "the active checkpoint is not a valid JRCM composite runtime checkpoint; use a dedicated runtime container"
                ) from exc
        previous_version = 0 if previous is None else previous.descriptor.runtime_version
        if expected_runtime_version is not None and expected_runtime_version != previous_version:
            raise JPVRRuntimeCheckpointConflict(
                f"expected runtime version {expected_runtime_version}, current version is {previous_version}"
            )
        if previous_version == _U64_MAX:
            raise JPVRRuntimeCheckpointError("runtime checkpoint version space is exhausted")
        network_version = 1 if previous is None else previous.descriptor.network.record_version + 1
        baseline_version = 1 if previous is None else previous.descriptor.reward_baseline.record_version + 1
        if network_version > _U64_MAX or baseline_version > _U64_MAX:
            raise JPVRRuntimeCheckpointError("component checkpoint record version space is exhausted")
        runtime_version = previous_version + 1
        try:
            network_input = make_network_runtime_record_input(
                network_id, network, clock_epoch_id=epoch, record_version=network_version
            )
            baseline_input = make_reward_baseline_record_input(
                reward_baseline_manager, record_version=baseline_version
            )
        except (
            NetworkRuntimePersistenceError, NeuronRuntimePersistenceError,
            RewardBaselinePersistenceError, RewardBaselineError,
        ) as exc:
            raise JPVRRuntimeCheckpointError(str(exc)) from exc

        inputs = (network_input, baseline_input)
        data_records = tuple(item for item in inputs if isinstance(item, DataRecordInput))
        blob_records = tuple(item for item in inputs if isinstance(item, BlobRecordInput))
        base_request = TypedTransactionRequest(
            current_superblock=active,
            page_catalog=catalog,
            transaction_id=_new_id(),
            commit_id=_new_id(),
            operation_id=_new_id(),
            data_records=data_records,
            blob_records=blob_records,
        )
        record_plan = build_typed_transaction(base_request)
        page_bytes = dict(zip(
            record_plan.assembly.ordered_page_numbers,
            record_plan.assembly.ordered_page_bytes,
        ))
        blob_locations = _build_blob_locations(blob_records, record_plan)

        network_kind, network_page, network_count = _record_location(
            network_input, record_plan, page_bytes=page_bytes,
            blob_locations=blob_locations,
        )
        baseline_kind, baseline_page, baseline_count = _record_location(
            baseline_input, record_plan, page_bytes=page_bytes,
            blob_locations=blob_locations,
        )
        generation = active.generation + 1
        network_descriptor = NetworkCheckpointDescriptor(
            object_id=network_runtime_object_id(network_id),
            record_version=network_version,
            first_blob_page_no=network_page,
            blob_page_count=network_count,
            record_generation=generation,
            payload_length=len(network_input.payload),
            payload_crc32c=crc32c(network_input.payload),
            storage_kind=network_kind,
        )
        baseline_descriptor = RewardBaselineCheckpointDescriptor(
            object_id=reward_baseline_object_id(manager_id),
            record_version=baseline_version,
            first_blob_page_no=baseline_page,
            blob_page_count=baseline_count,
            record_generation=generation,
            payload_length=len(baseline_input.payload),
            payload_crc32c=crc32c(baseline_input.payload),
            storage_kind=baseline_kind,
        )
        descriptor = RuntimeCheckpointDescriptor(
            runtime_version, network_descriptor, baseline_descriptor
        )
        final_request = replace(
            base_request,
            checkpoint_pages=(CheckpointPageInput(
                encode_runtime_checkpoint_descriptor(descriptor)
            ),),
        )
        final_plan = build_typed_transaction(final_request)
        if (
            final_plan.data_page_numbers != record_plan.data_page_numbers
            or final_plan.blob_page_numbers != record_plan.blob_page_numbers
            or len(final_plan.checkpoint_page_numbers) != 1
            or final_plan.manifest.checkpoint_page_no != final_plan.checkpoint_page_numbers[0]
            or final_plan.commit_page_no <= final_plan.checkpoint_page_numbers[0]
        ):
            raise JPVRRuntimeCheckpointError("composite CHECKPOINT planning changed component record layout")
        if final_plan.new_superblock.durable_tail_page_no - 1 > max_pages:
            raise JPVRRuntimeCheckpointError("runtime checkpoint exceeds the configured page-catalog bound")
        published = self.storage.commit(final_plan)
        return SavedRuntimeCheckpoint(
            network_id=network_id,
            manager_id=manager_id,
            runtime_version=runtime_version,
            previous_runtime_version=previous_version,
            network_record_version=network_version,
            reward_baseline_record_version=baseline_version,
            checkpoint_page_no=final_plan.checkpoint_page_numbers[0],
            commit_page_no=final_plan.commit_page_no,
            commit_id=final_plan.commit_record.commit_id,
            generation=published.generation,
            initialized_indexes=initialized,
            superblock=published,
        )
