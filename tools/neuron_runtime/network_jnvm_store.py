"""Single-container atomic JNVM group checkpoints for multi-neuron networks."""
from __future__ import annotations
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
import hashlib, json, math, struct
from types import MappingProxyType

from tools.jpvr_reference.blob import MAX_BLOB_PAGES, decode_blob_pages
from tools.jpvr_reference.codec import JPVRFormatError
from tools.jpvr_reference.cow import validate_index_tree
from tools.jpvr_reference.data import decode_data_page
from tools.jpvr_reference.index import decode_index_page, lookup_index
from tools.jpvr_reference.records import MAX_LOGICAL_RECORD_BYTES, MAX_SMALL_RECORD_PAYLOAD
from tools.jpvr_reference.storage import JPVRStorage
from tools.jpvr_reference.typed_transaction import BlobRecordInput, DataRecordInput, IndexMutationInput, TypedTransactionRequest, build_typed_transaction
from .jpvr_adapter import make_neuron_runtime_record_input, neuron_runtime_object_id
from .jpvr_store import LOCATOR_BLOB, LOCATOR_DATA, JPVRNeuronCheckpointStore, NeuronCheckpointLocator, _new_id, _tree_id, encode_neuron_checkpoint_locator
from .network import MAX_NETWORK_CONNECTIONS, MAX_NETWORK_NEURONS, NetworkConnection, NetworkRuntime, NetworkRuntimeError

SCHEDULER_RECORD_TYPE = 0x0103
SCHEDULER_SCHEMA_ID = 0x4A4E5343
SCHEDULER_SCHEMA_VERSION = 2
SCHEDULER_FORMAT = "JARVIS_NETWORK_SCHEDULER_SNAPSHOT_V2"
SCHEDULER_LOCATOR_MAGIC = b"JNSL"
SCHEDULER_LOCATOR_VERSION = 2
SCHEDULER_LOCATOR = struct.Struct("<4sHBBHHI16sQQIQQ16s16s")
SCHEDULER_ID_DOMAIN = b"JARVIS:JNVM:NETWORK-SCHEDULER:V1\x00"
INTERNAL_EVENT_PREFIX = "jarvis-network:"
_U64_MAX = (1 << 64) - 1


class JPVRNetworkJNVMError(ValueError):
    """Invalid scheduler record or group checkpoint operation."""


class JPVRNetworkJNVMNotFound(JPVRNetworkJNVMError):
    """No scheduler checkpoint exists for this network."""


class JPVRNetworkJNVMConflict(JPVRNetworkJNVMError):
    """Scheduler, topology, epoch, or neuron version conflicts."""


@dataclass(frozen=True)
class NetworkSchedulerSnapshot:
    network_id: str
    clock_epoch_id: bytes
    neuron_ids: tuple[str, ...]
    neuron_record_versions: Mapping[str, int]
    connections: tuple[NetworkConnection, ...]
    pending_events: tuple[tuple, ...]
    max_pending_events: int
    max_events_per_run: int
    current_time_ns: int
    has_processed: bool


@dataclass(frozen=True)
class NetworkSchedulerLocator:
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


@dataclass(frozen=True)
class LoadedNetworkSchedulerCheckpoint:
    snapshot: NetworkSchedulerSnapshot
    locator: NetworkSchedulerLocator
    commit_page_no: int


@dataclass(frozen=True)
class SavedNetworkTimestamp:
    network_id: str
    network_record_version: int
    previous_network_record_version: int
    neuron_record_versions: Mapping[str, int]
    commit_id: bytes
    commit_page_no: int
    generation: int
    initialized_indexes: bool


@dataclass(frozen=True)
class RestoredNetworkCheckpoint:
    network_id: str
    clock_epoch_id: bytes
    runtime: NetworkRuntime
    network_record_version: int
    neuron_record_versions: Mapping[str, int]
    commit_id: bytes
    generation: int


def _id(name: str, value: object, limit: int = 512) -> str:
    if not isinstance(value, str) or not value.strip() or "\x00" in value or len(value) > limit:
        raise JPVRNetworkJNVMError(f"{name} must be a non-empty bounded string without NUL")
    try:
        value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise JPVRNetworkJNVMError(f"{name} must be valid UTF-8") from exc
    return value


def _uint(name: str, value: object, maximum: int = _U64_MAX, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise JPVRNetworkJNVMError(f"{name} is outside its unsigned range")
    return value


def _epoch(value: object) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise JPVRNetworkJNVMError("clock_epoch_id must be bytes-like")
    result = bytes(value)
    if len(result) != 16 or result == bytes(16):
        raise JPVRNetworkJNVMError("clock_epoch_id must be a non-zero 16-byte identifier")
    return result


def _unit(name: str, value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise JPVRNetworkJNVMError(f"{name} must be numeric")
    number = float(value)
    if not math.isfinite(number) or not 0.0 <= number <= 1.0:
        raise JPVRNetworkJNVMError(f"{name} must be finite and in [0, 1]")
    return number


def network_scheduler_object_id(network_id: str) -> bytes:
    name = _id("network_id", network_id)
    return hashlib.sha256(SCHEDULER_ID_DOMAIN + name.encode("utf-8")).digest()[:16]


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise JPVRNetworkJNVMError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str):
    raise JPVRNetworkJNVMError(f"non-finite JSON constant is forbidden: {value}")


def _exact_object(name: str, value: object, fields: set[str]) -> dict:
    if not isinstance(value, dict) or set(value) != fields:
        raise JPVRNetworkJNVMError(f"{name} has missing or unknown fields")
    return value


def encode_network_scheduler_snapshot(
    network_id: str,
    runtime: NetworkRuntime,
    *,
    clock_epoch_id: bytes,
    neuron_record_versions: Mapping[str, int],
) -> bytes:
    name, epoch = _id("network_id", network_id), _epoch(clock_epoch_id)
    if not isinstance(runtime, NetworkRuntime):
        raise JPVRNetworkJNVMError("runtime must be a NetworkRuntime")
    try:
        state = runtime.export_checkpoint_state()
        neuron_ids = tuple(sorted(state["neurons"]))
        connections = tuple(sorted(state["connections"], key=lambda item: item.connection_id))
        pending = tuple(sorted(state["pending_events"]))
        if not 1 <= len(neuron_ids) <= MAX_NETWORK_NEURONS or len(connections) > MAX_NETWORK_CONNECTIONS:
            raise JPVRNetworkJNVMError("scheduler topology exceeds network limits")
        if not isinstance(neuron_record_versions, Mapping) or set(neuron_record_versions) != set(neuron_ids):
            raise JPVRNetworkJNVMError("neuron_record_versions must cover the complete network topology")
        version_map = {
            neuron_id: _uint(f"neuron record version for {neuron_id}", neuron_record_versions[neuron_id], minimum=1)
            for neuron_id in neuron_ids
        }
        document = {
            "format": SCHEDULER_FORMAT,
            "schema_version": SCHEDULER_SCHEMA_VERSION,
            "network_id": name,
            "clock_epoch_id": epoch.hex(),
            "neuron_ids": list(neuron_ids),
            "neuron_record_versions": version_map,
            "connections": [{
                "connection_id": c.connection_id,
                "source_neuron_id": c.source_neuron_id,
                "target_neuron_id": c.target_neuron_id,
                "target_synapse_id": c.target_synapse_id,
                "delay_ns": c.delay_ns,
                "strength": float(c.strength),
                "attention": float(c.attention),
            } for c in connections],
            "pending_events": [{
                "time_ns": e.time_ns,
                "event_id": e.event_id,
                "target_neuron_id": e.target_neuron_id,
                "target_synapse_id": e.target_synapse_id,
                "strength": float(e.strength),
                "attention": float(e.attention),
            } for e in pending],
            "max_pending_events": state["max_pending_events"],
            "max_events_per_run": state["max_events_per_run"],
            "current_time_ns": state["current_time_ns"],
            "has_processed": state["has_processed"],
        }
        encoded = json.dumps(document, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8", errors="strict")
    except JPVRNetworkJNVMError:
        raise
    except (NetworkRuntimeError, TypeError, ValueError, UnicodeEncodeError) as exc:
        raise JPVRNetworkJNVMError(f"cannot encode scheduler: {exc}") from exc
    if not encoded or len(encoded) > MAX_LOGICAL_RECORD_BYTES:
        raise JPVRNetworkJNVMError("scheduler exceeds JPVR logical-record limit")
    return encoded


def decode_network_scheduler_snapshot(payload: bytes | bytearray | memoryview) -> NetworkSchedulerSnapshot:
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise JPVRNetworkJNVMError("scheduler payload must be bytes-like")
    raw = bytes(payload)
    if not raw or len(raw) > MAX_LOGICAL_RECORD_BYTES:
        raise JPVRNetworkJNVMError("scheduler payload length is outside JPVR limits")
    try:
        doc = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except JPVRNetworkJNVMError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise JPVRNetworkJNVMError(f"invalid scheduler JSON: {exc}") from exc
    top = _exact_object("scheduler", doc, {
        "format", "schema_version", "network_id", "clock_epoch_id", "neuron_ids",
        "neuron_record_versions", "connections", "pending_events", "max_pending_events", "max_events_per_run",
        "current_time_ns", "has_processed",
    })
    version = top["schema_version"]
    if top["format"] != SCHEDULER_FORMAT or isinstance(version, bool) or version != SCHEDULER_SCHEMA_VERSION:
        raise JPVRNetworkJNVMError("unsupported scheduler format/version")
    network_id = _id("network_id", top["network_id"])
    epoch_text = top["clock_epoch_id"]
    if not isinstance(epoch_text, str) or len(epoch_text) != 32:
        raise JPVRNetworkJNVMError("clock_epoch_id must be 32 lowercase hex characters")
    try:
        epoch = bytes.fromhex(epoch_text)
    except ValueError as exc:
        raise JPVRNetworkJNVMError("clock_epoch_id is not hexadecimal") from exc
    if epoch.hex() != epoch_text or epoch == bytes(16):
        raise JPVRNetworkJNVMError("clock_epoch_id is not canonical or is zero")
    raw_ids = top["neuron_ids"]
    if not isinstance(raw_ids, list) or not 1 <= len(raw_ids) <= MAX_NETWORK_NEURONS:
        raise JPVRNetworkJNVMError("neuron_ids must be a bounded non-empty array")
    neuron_ids = tuple(_id("neuron_id", item, 128) for item in raw_ids)
    if neuron_ids != tuple(sorted(set(neuron_ids))):
        raise JPVRNetworkJNVMError("neuron_ids must be uniquely sorted")
    raw_versions = top["neuron_record_versions"]
    if not isinstance(raw_versions, dict) or set(raw_versions) != set(neuron_ids):
        raise JPVRNetworkJNVMError("neuron_record_versions must cover every neuron exactly once")
    neuron_record_versions = {
        neuron_id: _uint(f"neuron record version for {neuron_id}", raw_versions[neuron_id], minimum=1)
        for neuron_id in neuron_ids
    }
    queue_limit = _uint("max_pending_events", top["max_pending_events"], 1_000_000, 1)
    run_limit = _uint("max_events_per_run", top["max_events_per_run"], 1_000_000, 1)
    current_time = _uint("current_time_ns", top["current_time_ns"])
    has_processed = top["has_processed"]
    if not isinstance(has_processed, bool):
        raise JPVRNetworkJNVMError("has_processed must be bool")
    raw_connections = top["connections"]
    if not isinstance(raw_connections, list) or len(raw_connections) > MAX_NETWORK_CONNECTIONS:
        raise JPVRNetworkJNVMError("connections must be a bounded array")
    connections, ids, owned = [], [], set()
    for item in raw_connections:
        row = _exact_object("connection", item, {
            "connection_id", "source_neuron_id", "target_neuron_id",
            "target_synapse_id", "delay_ns", "strength", "attention",
        })
        c = NetworkConnection(
            _id("connection_id", row["connection_id"], 128),
            _id("source_neuron_id", row["source_neuron_id"], 128),
            _id("target_neuron_id", row["target_neuron_id"], 128),
            _id("target_synapse_id", row["target_synapse_id"], 128),
            _uint("delay_ns", row["delay_ns"], minimum=1),
            _unit("strength", row["strength"]),
            _unit("attention", row["attention"]),
        )
        c.validate()
        if c.source_neuron_id not in neuron_ids or c.target_neuron_id not in neuron_ids:
            raise JPVRNetworkJNVMError("connection endpoint is outside neuron_ids")
        target = (c.target_neuron_id, c.target_synapse_id)
        if target in owned:
            raise JPVRNetworkJNVMError("target synapse has multiple connection owners")
        owned.add(target)
        connections.append(c)
        ids.append(c.connection_id)
    if ids != sorted(set(ids)):
        raise JPVRNetworkJNVMError("connection IDs must be unique and sorted")
    raw_pending = top["pending_events"]
    if not isinstance(raw_pending, list) or len(raw_pending) > queue_limit:
        raise JPVRNetworkJNVMError("pending_events exceeds queue limit")
    pending, keys, seen = [], [], set()
    for item in raw_pending:
        row = _exact_object("pending event", item, {
            "time_ns", "event_id", "target_neuron_id", "target_synapse_id",
            "strength", "attention",
        })
        t = _uint("event time_ns", row["time_ns"])
        event_id = _id("event_id", row["event_id"], 256)
        target_neuron = _id("target_neuron_id", row["target_neuron_id"], 128)
        target_synapse = _id("target_synapse_id", row["target_synapse_id"], 128)
        strength, attention = _unit("event strength", row["strength"]), _unit("event attention", row["attention"])
        if target_neuron not in neuron_ids:
            raise JPVRNetworkJNVMError("pending event targets unknown neuron")
        internal = event_id.startswith(INTERNAL_EVENT_PREFIX)
        if internal != ((target_neuron, target_synapse) in owned):
            raise JPVRNetworkJNVMError("event origin does not match synapse ownership")
        if internal:
            digest = event_id[len(INTERNAL_EVENT_PREFIX):]
            if len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest):
                raise JPVRNetworkJNVMError("internal event ID is not canonical SHA-256")
        if event_id in seen:
            raise JPVRNetworkJNVMError("pending event IDs must be unique")
        seen.add(event_id)
        if (has_processed and t <= current_time) or (not has_processed and t < current_time):
            raise JPVRNetworkJNVMError("pending event is not later than network clock")
        pending.append((t, event_id, target_neuron, target_synapse, strength, attention))
        keys.append((t, event_id, target_neuron, target_synapse))
    if keys != sorted(keys):
        raise JPVRNetworkJNVMError("pending events are not in canonical order")
    return NetworkSchedulerSnapshot(
        network_id, epoch, neuron_ids, MappingProxyType(neuron_record_versions),
        tuple(connections), tuple(pending), queue_limit, run_limit, current_time, has_processed,
    )


def _validate_locator(locator: NetworkSchedulerLocator) -> None:
    if not isinstance(locator, NetworkSchedulerLocator):
        raise JPVRNetworkJNVMError("locator must be NetworkSchedulerLocator")
    _uint("storage_kind", locator.storage_kind, 255)
    _uint("record_type", locator.record_type, 65535)
    _uint("schema_id", locator.schema_id, 0xFFFFFFFF)
    _uint("schema_version", locator.schema_version, 65535)
    if locator.storage_kind not in (LOCATOR_DATA, LOCATOR_BLOB):
        raise JPVRNetworkJNVMError("unknown scheduler storage kind")
    if (locator.record_type, locator.schema_id, locator.schema_version) != (SCHEDULER_RECORD_TYPE, SCHEDULER_SCHEMA_ID, SCHEDULER_SCHEMA_VERSION):
        raise JPVRNetworkJNVMError("scheduler locator schema mismatch")
    for name, value in (("object_id", locator.object_id), ("commit_id", locator.commit_id), ("transaction_id", locator.transaction_id)):
        if not isinstance(value, bytes) or len(value) != 16 or value == bytes(16):
            raise JPVRNetworkJNVMError(f"{name} must be non-zero 16-byte value")
    _uint("record_version", locator.record_version, minimum=1)
    _uint("first_page_no", locator.first_page_no, minimum=2)
    _uint("page_count", locator.page_count, 0xFFFFFFFF, 1)
    _uint("record_generation", locator.record_generation, minimum=1)
    _uint("commit_page_no", locator.commit_page_no, minimum=2)
    if locator.commit_page_no < locator.first_page_no + locator.page_count:
        raise JPVRNetworkJNVMError("scheduler COMMIT must follow record pages")
    if locator.storage_kind == LOCATOR_DATA and locator.page_count != 1:
        raise JPVRNetworkJNVMError("DATA scheduler record must occupy one page")
    if locator.storage_kind == LOCATOR_BLOB and locator.page_count > MAX_BLOB_PAGES:
        raise JPVRNetworkJNVMError("scheduler BLOB page count exceeds limit")


def encode_network_scheduler_locator(locator: NetworkSchedulerLocator) -> bytes:
    _validate_locator(locator)
    return SCHEDULER_LOCATOR.pack(
        SCHEDULER_LOCATOR_MAGIC, SCHEDULER_LOCATOR_VERSION, locator.storage_kind, 0,
        locator.record_type, locator.schema_version, locator.schema_id, locator.object_id,
        locator.record_version, locator.first_page_no, locator.page_count,
        locator.record_generation, locator.commit_page_no, locator.commit_id, locator.transaction_id,
    )


def decode_network_scheduler_locator(payload: bytes | bytearray | memoryview) -> NetworkSchedulerLocator:
    if not isinstance(payload, (bytes, bytearray, memoryview)) or len(payload) != SCHEDULER_LOCATOR.size:
        raise JPVRNetworkJNVMError("scheduler locator has invalid size")
    fields = SCHEDULER_LOCATOR.unpack(bytes(payload))
    if fields[0] != SCHEDULER_LOCATOR_MAGIC or fields[1] != SCHEDULER_LOCATOR_VERSION or fields[3] != 0:
        raise JPVRNetworkJNVMError("invalid scheduler locator magic/version/flags")
    locator = NetworkSchedulerLocator(
        fields[2], fields[4], fields[6], fields[5], fields[7], fields[8],
        fields[9], fields[10], fields[11], fields[12], fields[13], fields[14],
    )
    _validate_locator(locator)
    return locator


def _decode_scheduler_record(network_id, locator, active, catalog):
    source_commit = JPVRNeuronCheckpointStore._verify_commit_ancestor(locator, active, catalog)
    last_page = locator.first_page_no + locator.page_count - 1
    if last_page > active.durable_tail_page_no:
        raise JPVRNetworkJNVMError("scheduler record is outside durable prefix")
    if locator.storage_kind == LOCATOR_DATA:
        raw = catalog.get(locator.first_page_no)
        if raw is None:
            raise JPVRNetworkJNVMError("scheduler DATA page is missing")
        page = decode_data_page(raw, expected_page_no=locator.first_page_no)
        if page.commit_generation != locator.record_generation or page.transaction_id != locator.transaction_id:
            raise JPVRNetworkJNVMError("scheduler DATA transaction metadata mismatch")
        matches = [r for r in page.records if r.object_id == locator.object_id and r.record_type == locator.record_type]
        if len(matches) != 1:
            raise JPVRNetworkJNVMError("scheduler DATA must have one matching JREC")
        record = matches[0]
        if (record.schema_id, record.schema_version, record.record_version) != (locator.schema_id, locator.schema_version, locator.record_version):
            raise JPVRNetworkJNVMError("scheduler JREC metadata differs from locator")
        payload = record.payload
    else:
        pages = []
        for page_no in range(locator.first_page_no, last_page + 1):
            raw = catalog.get(page_no)
            if raw is None:
                raise JPVRNetworkJNVMError("scheduler BLOB page is missing")
            pages.append(raw)
        record = decode_blob_pages(tuple(pages), expected_first_page_no=locator.first_page_no)
        if (
            record.page_count != locator.page_count or record.first_page_no != locator.first_page_no
            or record.commit_generation != locator.record_generation or record.transaction_id != locator.transaction_id
            or record.object_id != locator.object_id or record.record_type != locator.record_type
            or record.schema_id != locator.schema_id or record.schema_version != locator.schema_version
            or record.record_version != locator.record_version
        ):
            raise JPVRNetworkJNVMError("scheduler BLOB metadata differs from locator")
        payload = record.payload
    if source_commit.record.commit_id != locator.commit_id:
        raise JPVRNetworkJNVMError("scheduler source COMMIT identity mismatch")
    result = decode_network_scheduler_snapshot(payload)
    if result.network_id != network_id or locator.object_id != network_scheduler_object_id(network_id):
        raise JPVRNetworkJNVMError("scheduler network identity mismatch")
    return result


def _make_locator(record, kind, first_page, count, generation, commit_page, commit_id, tx_id, scheduler):
    values = (kind, record.record_type, record.schema_id, record.schema_version, bytes(record.object_id),
              record.record_version, first_page, count, generation, commit_page, commit_id, tx_id)
    return NetworkSchedulerLocator(*values) if scheduler else NeuronCheckpointLocator(*values)


def _encode_any_locator(locator):
    return encode_network_scheduler_locator(locator) if isinstance(locator, NetworkSchedulerLocator) else encode_neuron_checkpoint_locator(locator)


class JPVRNetworkJNVMStore:
    """One JPVR container owns a shared neuron INDEX and scheduler primary_object INDEX."""

    def __init__(self, storage: JPVRStorage):
        if not isinstance(storage, JPVRStorage):
            raise JPVRNetworkJNVMError("storage must be JPVRStorage")
        self.storage = storage
        self._neurons = JPVRNeuronCheckpointStore(storage)

    def _read_snapshot(self, max_pages):
        if isinstance(max_pages, bool) or not isinstance(max_pages, int) or max_pages < 0:
            raise JPVRFormatError("max_pages must be a non-negative integer")
        active, catalog = self.storage.load_snapshot(max_pages=max_pages)
        return active, catalog, self._neurons._manifest(active, catalog)

    def _load_scheduler_from_snapshot(self, network_id, active, catalog, manifest):
        if manifest is None:
            raise JPVRNetworkJNVMNotFound(f"network {network_id!r} has no scheduler checkpoint")
        root = manifest.index_roots["primary_object"]
        raw_root = catalog.get(root.page_no)
        if raw_root is None:
            raise JPVRNetworkJNVMError("primary_object INDEX root is missing")
        tree_id = _tree_id("primary_object")
        decode_index_page(raw_root, expected_page_no=root.page_no)
        validate_index_tree(root, catalog, tree_id=tree_id, durable_tail_page_no=active.durable_tail_page_no)
        value = lookup_index(root, catalog, network_scheduler_object_id(network_id),
                             tree_id=tree_id, durable_tail_page_no=active.durable_tail_page_no)
        if value is None:
            raise JPVRNetworkJNVMNotFound(f"network {network_id!r} has no scheduler checkpoint")
        locator = decode_network_scheduler_locator(value)
        result = _decode_scheduler_record(network_id, locator, active, catalog)
        return LoadedNetworkSchedulerCheckpoint(result, locator, locator.commit_page_no)

    def load_scheduler(self, network_id: str, *, max_pages: int = 4096):
        network_scheduler_object_id(network_id)
        active, catalog, manifest = self._read_snapshot(max_pages)
        return self._load_scheduler_from_snapshot(network_id, active, catalog, manifest)

    def load_neuron(self, neuron_id: str, *, max_pages: int = 4096):
        return self._neurons.load(neuron_id, max_pages=max_pages)

    def restore_network(self, network_id: str, *, max_pages: int = 4096):
        # One active snapshot is used for the scheduler and every neuron.
        active, catalog, manifest = self._read_snapshot(max_pages)
        scheduler = self._load_scheduler_from_snapshot(network_id, active, catalog, manifest)
        runtimes, versions = {}, {}
        for neuron_id in scheduler.snapshot.neuron_ids:
            locator, version = self._neurons._lookup_existing(neuron_id, neuron_runtime_object_id(neuron_id), manifest, active, catalog)
            if locator is None:
                raise JPVRNetworkJNVMNotFound(f"network neuron {neuron_id!r} has no checkpoint")
            expected_version = scheduler.snapshot.neuron_record_versions[neuron_id]
            if version != expected_version:
                raise JPVRNetworkJNVMConflict(
                    f"neuron {neuron_id!r} index version {version} differs from scheduler version {expected_version}"
                )
            decoded = self._neurons._decode_locator_record(neuron_id, locator, active, catalog)
            if decoded.clock_epoch_id != scheduler.snapshot.clock_epoch_id:
                raise JPVRNetworkJNVMConflict(f"neuron {neuron_id!r} has a different clock epoch")
            runtimes[neuron_id] = decoded.runtime
            versions[neuron_id] = version
        try:
            runtime = NetworkRuntime.restore_checkpoint_state(
                runtimes, scheduler.snapshot.connections, scheduler.snapshot.pending_events,
                max_pending_events=scheduler.snapshot.max_pending_events,
                max_events_per_run=scheduler.snapshot.max_events_per_run,
                current_time_ns=scheduler.snapshot.current_time_ns,
                has_processed=scheduler.snapshot.has_processed,
            )
        except NetworkRuntimeError as exc:
            raise JPVRNetworkJNVMError(f"persisted scheduler is incompatible with neuron states: {exc}") from exc
        return RestoredNetworkCheckpoint(
            network_id, scheduler.snapshot.clock_epoch_id, runtime,
            scheduler.locator.record_version, MappingProxyType(versions),
            scheduler.locator.commit_id, scheduler.locator.record_generation,
        )

    def save_timestamp(
        self, network_id: str, runtime: NetworkRuntime, changed_neuron_ids: Sequence[str], *,
        clock_epoch_id: bytes, expected_network_record_version: int,
        expected_neuron_record_versions: Mapping[str, int], max_pages: int = 4096,
    ):
        """Persist changed neuron records and the scheduler in one JPVR COMMIT."""
        scheduler_id = network_scheduler_object_id(network_id)
        epoch = _epoch(clock_epoch_id)
        expected_network_version = _uint("expected_network_record_version", expected_network_record_version)
        if not isinstance(runtime, NetworkRuntime):
            raise JPVRNetworkJNVMError("runtime must be NetworkRuntime")
        if not isinstance(changed_neuron_ids, (tuple, list)):
            raise JPVRNetworkJNVMError("changed_neuron_ids must be tuple or list")
        changed = tuple(sorted(_id("changed neuron ID", item, 128) for item in changed_neuron_ids))
        if len(changed) != len(set(changed)):
            raise JPVRNetworkJNVMError("changed neuron IDs must be unique")
        if not isinstance(expected_neuron_record_versions, Mapping) or set(expected_neuron_record_versions) != set(changed):
            raise JPVRNetworkJNVMError("expected neuron-version keys must match changed_neuron_ids")
        expected_versions = {key: _uint(f"expected neuron version for {key}", value)
                             for key, value in expected_neuron_record_versions.items()}
        try:
            state = runtime.export_checkpoint_state()
            neurons = state["neurons"]
            if any(key not in neurons for key in changed):
                raise JPVRNetworkJNVMError("changed_neuron_ids contains unknown neuron")
        except JPVRNetworkJNVMError:
            raise
        except NetworkRuntimeError as exc:
            raise JPVRNetworkJNVMError(f"cannot export network candidate: {exc}") from exc

        active, catalog, manifest = self._read_snapshot(max_pages)
        initialized = False
        if manifest is None:
            if max_pages < 9:
                raise JPVRFormatError("initial JPVR INDEX setup requires at least nine pages")
            self._neurons._initialize_indexes(active, catalog)
            initialized = True
            active, catalog, manifest = self._read_snapshot(max_pages)
            if manifest is None:
                raise JPVRNetworkJNVMError("INDEX initialization did not publish MANIFEST")

        previous = None
        old_network_version = 0
        try:
            previous = self._load_scheduler_from_snapshot(network_id, active, catalog, manifest)
            old_network_version = previous.locator.record_version
        except JPVRNetworkJNVMNotFound:
            pass
        if previous is None:
            if expected_network_version != 0:
                raise JPVRNetworkJNVMConflict("scheduler version does not match empty network")
            if set(changed) != set(neurons):
                raise JPVRNetworkJNVMConflict("initial group checkpoint must include every neuron")
        else:
            old = previous.snapshot
            if old.clock_epoch_id != epoch:
                raise JPVRNetworkJNVMConflict("clock epoch is immutable in JNVM V2")
            if old.neuron_ids != tuple(sorted(neurons)):
                raise JPVRNetworkJNVMConflict("network neuron set is immutable in JNVM V2")
            if old.connections != tuple(sorted(state["connections"], key=lambda item: item.connection_id)):
                raise JPVRNetworkJNVMConflict("network topology is immutable in JNVM V2")
        if expected_network_version != old_network_version:
            raise JPVRNetworkJNVMConflict(
                f"expected network version {expected_network_version}, current version is {old_network_version}"
            )
        if old_network_version == _U64_MAX:
            raise JPVRNetworkJNVMError("scheduler record version is exhausted")
        next_network_version = old_network_version + 1

        # The scheduler's complete version map is the consistency manifest.
        current_neuron_versions = {}
        for neuron_id in sorted(neurons):
            locator, version = self._neurons._lookup_existing(
                neuron_id, neuron_runtime_object_id(neuron_id), manifest, active, catalog,
            )
            if previous is None:
                if locator is not None or version != 0:
                    raise JPVRNetworkJNVMConflict(
                        f"initial group checkpoint found an existing record for neuron {neuron_id!r}"
                    )
            else:
                expected_persisted = previous.snapshot.neuron_record_versions[neuron_id]
                if version != expected_persisted:
                    raise JPVRNetworkJNVMConflict(
                        f"neuron {neuron_id!r} index version {version} differs from scheduler version {expected_persisted}"
                    )
            current_neuron_versions[neuron_id] = version

        next_neuron_versions = dict(current_neuron_versions)
        for neuron_id in changed:
            version = current_neuron_versions[neuron_id]
            if expected_versions[neuron_id] != version:
                raise JPVRNetworkJNVMConflict(
                    f"neuron {neuron_id!r} expected version {expected_versions[neuron_id]}, current {version}"
                )
            if version == _U64_MAX:
                raise JPVRNetworkJNVMError(f"neuron {neuron_id!r} record version is exhausted")
            next_neuron_versions[neuron_id] = version + 1

        scheduler_payload = encode_network_scheduler_snapshot(
            network_id, runtime, clock_epoch_id=epoch,
            neuron_record_versions=next_neuron_versions,
        )

        data_inputs, data_owners, blob_inputs, blob_owners = [], [], [], []
        for neuron_id in changed:
            record = make_neuron_runtime_record_input(
                neuron_id, neurons[neuron_id], clock_epoch_id=epoch,
                record_version=next_neuron_versions[neuron_id],
            )
            owner = ("neuron", neuron_id)
            if isinstance(record, DataRecordInput):
                data_inputs.append(record); data_owners.append(owner)
            elif isinstance(record, BlobRecordInput):
                blob_inputs.append(record); blob_owners.append(owner)
            else:
                raise JPVRNetworkJNVMError("neuron record factory returned unknown input kind")

        common = (SCHEDULER_RECORD_TYPE, SCHEDULER_SCHEMA_ID, SCHEDULER_SCHEMA_VERSION, scheduler_id, next_network_version)
        scheduler_record = DataRecordInput(*common, scheduler_payload) if len(scheduler_payload) <= MAX_SMALL_RECORD_PAYLOAD else BlobRecordInput(*common, scheduler_payload)
        if isinstance(scheduler_record, DataRecordInput):
            data_inputs.append(scheduler_record); data_owners.append(("scheduler", network_id))
        else:
            blob_inputs.append(scheduler_record); blob_owners.append(("scheduler", network_id))

        tx_id, commit_id, op_id = _new_id(), _new_id(), _new_id()
        base = TypedTransactionRequest(
            current_superblock=active, page_catalog=catalog,
            transaction_id=tx_id, commit_id=commit_id, operation_id=op_id,
            data_records=tuple(data_inputs), blob_records=tuple(blob_inputs),
        )
        record_plan = build_typed_transaction(base)
        if len(record_plan.data_record_page_numbers) != len(data_inputs) or len(record_plan.blob_record_page_ranges) != len(blob_inputs):
            raise JPVRNetworkJNVMError("JPVR placement maps differ from logical records")

        locators = {}
        generation = active.generation + 1
        for owner, record, page_no in zip(data_owners, data_inputs, record_plan.data_record_page_numbers):
            values = (LOCATOR_DATA, record.record_type, record.schema_id, record.schema_version,
                      bytes(record.object_id), record.record_version, page_no, 1,
                      generation, record_plan.commit_page_no, commit_id, tx_id)
            locators[owner] = NetworkSchedulerLocator(*values) if owner[0] == "scheduler" else NeuronCheckpointLocator(*values)
        for owner, record, (first_page, count) in zip(blob_owners, blob_inputs, record_plan.blob_record_page_ranges):
            values = (LOCATOR_BLOB, record.record_type, record.schema_id, record.schema_version,
                      bytes(record.object_id), record.record_version, first_page, count,
                      generation, record_plan.commit_page_no, commit_id, tx_id)
            locators[owner] = NetworkSchedulerLocator(*values) if owner[0] == "scheduler" else NeuronCheckpointLocator(*values)

        def mutations(resolved):
            result = []
            for owner in sorted(resolved):
                kind, identity = owner
                root = "primary_object" if kind == "scheduler" else "neuron"
                key = scheduler_id if kind == "scheduler" else neuron_runtime_object_id(identity)
                locator = resolved[owner]
                value = encode_network_scheduler_locator(locator) if isinstance(locator, NetworkSchedulerLocator) else encode_neuron_checkpoint_locator(locator)
                result.append(IndexMutationInput(root, _tree_id(root), key, value))
            return tuple(result)

        placeholder = build_typed_transaction(replace(base, index_mutations=mutations(locators)))
        resolved = {key: replace(value, commit_page_no=placeholder.commit_page_no) for key, value in locators.items()}
        final_plan = build_typed_transaction(replace(base, index_mutations=mutations(resolved)))
        if (
            final_plan.commit_page_no != placeholder.commit_page_no
            or final_plan.data_record_page_numbers != record_plan.data_record_page_numbers
            or final_plan.blob_record_page_ranges != record_plan.blob_record_page_ranges
            or final_plan.data_page_numbers != record_plan.data_page_numbers
            or final_plan.blob_page_numbers != record_plan.blob_page_numbers
            or final_plan.page_count != placeholder.page_count
        ):
            raise JPVRNetworkJNVMError("final locator resolution changed page layout")

        # Uncertain publication propagates; reopen and reconcile before retry.
        published = self.storage.commit(final_plan)
        return SavedNetworkTimestamp(
            network_id, next_network_version, old_network_version,
            MappingProxyType(dict(next_neuron_versions)), commit_id,
            final_plan.commit_page_no, published.generation, initialized,
        )
