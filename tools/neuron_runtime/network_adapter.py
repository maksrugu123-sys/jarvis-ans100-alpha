"""Versioned serialization for a complete bounded NetworkRuntime checkpoint."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from typing import Any

from tools.jpvr_reference.records import MAX_LOGICAL_RECORD_BYTES
from tools.jpvr_reference.typed_transaction import BlobRecordInput

from .jpvr_adapter import (
    NeuronRuntimePersistenceError,
    decode_neuron_runtime_snapshot,
    encode_neuron_runtime_snapshot,
)
from .network import NetworkConnection, NetworkRuntime, NetworkRuntimeError


NETWORK_RUNTIME_RECORD_TYPE = 0x0102
NETWORK_RUNTIME_SCHEMA_ID = 0x4A4E4E57  # application tag: JNNW
NETWORK_RUNTIME_SCHEMA_VERSION = 1
_NETWORK_FORMAT = "JARVIS_NETWORK_RUNTIME_SNAPSHOT_V1"
_NETWORK_ID_DOMAIN = b"JARVIS:JNVM:NETWORK-RUNTIME:V1\x00"
_U64_MAX = (1 << 64) - 1
_MAX_NEURONS = 10_000
_MAX_CONNECTIONS = 100_000


class NetworkRuntimePersistenceError(ValueError):
    """Invalid, oversized, or incompatible persisted NetworkRuntime state."""


@dataclass(frozen=True)
class DecodedNetworkRuntimeSnapshot:
    network_id: str
    clock_epoch_id: bytes
    runtime: NetworkRuntime


def _identifier(name: str, value: object, *, max_bytes: int = 512) -> str:
    if not isinstance(value, str) or not value.strip() or "\x00" in value:
        raise NetworkRuntimePersistenceError(f"{name} must be a non-empty string without NUL")
    try:
        encoded = value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise NetworkRuntimePersistenceError(f"{name} must be valid UTF-8") from exc
    if len(encoded) > max_bytes:
        raise NetworkRuntimePersistenceError(f"{name} exceeds {max_bytes} UTF-8 bytes")
    return value


def _clock_epoch(value: object) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise NetworkRuntimePersistenceError("clock_epoch_id must be bytes-like")
    result = bytes(value)
    if len(result) != 16 or result == bytes(16):
        raise NetworkRuntimePersistenceError("clock_epoch_id must be a non-zero 16-byte identifier")
    return result


def _u64(name: str, value: object, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= _U64_MAX:
        raise NetworkRuntimePersistenceError(f"{name} is outside its unsigned 64-bit range")
    return value


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise NetworkRuntimePersistenceError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise NetworkRuntimePersistenceError(f"non-finite JSON constant is forbidden: {value}")


def _exact_object(name: str, value: object, expected: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise NetworkRuntimePersistenceError(f"{name} must be a JSON object")
    actual = set(value)
    if actual != expected:
        raise NetworkRuntimePersistenceError(
            f"{name} fields mismatch (missing={sorted(expected - actual)}, unknown={sorted(actual - expected)})"
        )
    return value


def network_runtime_object_id(network_id: str) -> bytes:
    identifier = _identifier("network_id", network_id)
    return hashlib.sha256(
        _NETWORK_ID_DOMAIN + identifier.encode("utf-8")
    ).digest()[:16]


def encode_network_runtime_snapshot(
    network_id: str,
    runtime: NetworkRuntime,
    *,
    clock_epoch_id: bytes,
) -> bytes:
    """Encode neuron states, topology, scheduler limits, clock and pending events."""
    identifier = _identifier("network_id", network_id)
    epoch = _clock_epoch(clock_epoch_id)
    if not isinstance(runtime, NetworkRuntime):
        raise NetworkRuntimePersistenceError("runtime must be a NetworkRuntime")
    try:
        state = runtime.export_checkpoint_state()
        neurons = []
        for neuron_id, neuron in sorted(state["neurons"].items()):
            neuron_bytes = encode_neuron_runtime_snapshot(
                neuron_id, neuron, clock_epoch_id=epoch
            )
            neurons.append({
                "neuron_id": neuron_id,
                "snapshot_hex": neuron_bytes.hex(),
            })
        if not 1 <= len(neurons) <= _MAX_NEURONS:
            raise NetworkRuntimePersistenceError("network neuron count is outside the snapshot limit")

        connections = []
        for item in sorted(state["connections"], key=lambda value: value.connection_id):
            connections.append({
                "connection_id": item.connection_id,
                "source_neuron_id": item.source_neuron_id,
                "target_neuron_id": item.target_neuron_id,
                "target_synapse_id": item.target_synapse_id,
                "delay_ns": item.delay_ns,
                "strength": float(item.strength),
                "attention": float(item.attention),
            })
        if len(connections) > _MAX_CONNECTIONS:
            raise NetworkRuntimePersistenceError("network connection count exceeds the snapshot limit")

        pending = []
        for event in state["pending_events"]:
            pending.append({
                "time_ns": event.time_ns,
                "event_id": event.event_id,
                "target_neuron_id": event.target_neuron_id,
                "target_synapse_id": event.target_synapse_id,
                "strength": float(event.strength),
                "attention": float(event.attention),
            })
        document = {
            "format": _NETWORK_FORMAT,
            "schema_version": NETWORK_RUNTIME_SCHEMA_VERSION,
            "network_id": identifier,
            "clock_epoch_id": epoch.hex(),
            "max_pending_events": state["max_pending_events"],
            "max_events_per_run": state["max_events_per_run"],
            "current_time_ns": state["current_time_ns"],
            "has_processed": state["has_processed"],
            "neurons": neurons,
            "connections": connections,
            "pending_events": pending,
        }
        encoded = json.dumps(
            document,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8", errors="strict")
    except NetworkRuntimePersistenceError:
        raise
    except (NetworkRuntimeError, NeuronRuntimePersistenceError, TypeError, ValueError, UnicodeEncodeError) as exc:
        raise NetworkRuntimePersistenceError(f"cannot encode network runtime snapshot: {exc}") from exc
    if not encoded or len(encoded) > MAX_LOGICAL_RECORD_BYTES:
        raise NetworkRuntimePersistenceError(
            f"network snapshot exceeds the {MAX_LOGICAL_RECORD_BYTES}-byte JPVR logical-record limit"
        )
    return encoded


def decode_network_runtime_snapshot(
    payload: bytes | bytearray | memoryview,
) -> DecodedNetworkRuntimeSnapshot:
    """Strictly validate and reconstruct a complete NetworkRuntime snapshot."""
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise NetworkRuntimePersistenceError("network snapshot payload must be bytes-like")
    raw = bytes(payload)
    if not raw or len(raw) > MAX_LOGICAL_RECORD_BYTES:
        raise NetworkRuntimePersistenceError("network snapshot payload length is outside JPVR limits")
    try:
        doc = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except NetworkRuntimePersistenceError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise NetworkRuntimePersistenceError(f"invalid network snapshot JSON: {exc}") from exc

    top = _exact_object("network snapshot", doc, {
        "format", "schema_version", "network_id", "clock_epoch_id",
        "max_pending_events", "max_events_per_run", "current_time_ns",
        "has_processed", "neurons", "connections", "pending_events",
    })
    schema_version = top["schema_version"]
    if (
        top["format"] != _NETWORK_FORMAT
        or isinstance(schema_version, bool)
        or not isinstance(schema_version, int)
        or schema_version != NETWORK_RUNTIME_SCHEMA_VERSION
    ):
        raise NetworkRuntimePersistenceError("unsupported network snapshot format/version")
    network_id = _identifier("network_id", top["network_id"])
    clock_text = top["clock_epoch_id"]
    if not isinstance(clock_text, str) or len(clock_text) != 32:
        raise NetworkRuntimePersistenceError("clock_epoch_id must be 32 lowercase hex characters")
    try:
        epoch = bytes.fromhex(clock_text)
    except ValueError as exc:
        raise NetworkRuntimePersistenceError("clock_epoch_id is not hexadecimal") from exc
    if epoch.hex() != clock_text or epoch == bytes(16):
        raise NetworkRuntimePersistenceError("clock_epoch_id is not canonical or is zero")

    queue_limit = top["max_pending_events"]
    run_limit = top["max_events_per_run"]
    _u64("max_pending_events", queue_limit, minimum=1)
    _u64("max_events_per_run", run_limit, minimum=1)
    if queue_limit > 1_000_000 or run_limit > 1_000_000:
        raise NetworkRuntimePersistenceError("network scheduler limit exceeds the reference runtime maximum")
    current_time = _u64("current_time_ns", top["current_time_ns"])
    has_processed = top["has_processed"]
    if not isinstance(has_processed, bool):
        raise NetworkRuntimePersistenceError("has_processed must be bool")

    raw_neurons = top["neurons"]
    if not isinstance(raw_neurons, list) or not 1 <= len(raw_neurons) <= _MAX_NEURONS:
        raise NetworkRuntimePersistenceError("neurons must be a non-empty bounded array")
    neurons = {}
    neuron_names = []
    for item in raw_neurons:
        entry = _exact_object("neuron entry", item, {"neuron_id", "snapshot_hex"})
        neuron_id = _identifier("neuron_id", entry["neuron_id"], max_bytes=512)
        snapshot_hex = entry["snapshot_hex"]
        if not isinstance(snapshot_hex, str) or len(snapshot_hex) % 2:
            raise NetworkRuntimePersistenceError("neuron snapshot_hex must be an even-length hex string")
        try:
            neuron_bytes = bytes.fromhex(snapshot_hex)
        except ValueError as exc:
            raise NetworkRuntimePersistenceError("neuron snapshot_hex is not hexadecimal") from exc
        if neuron_bytes.hex() != snapshot_hex:
            raise NetworkRuntimePersistenceError("neuron snapshot_hex is not canonical lowercase hex")
        decoded = decode_neuron_runtime_snapshot(neuron_bytes)
        if decoded.neuron_id != neuron_id or decoded.clock_epoch_id != epoch:
            raise NetworkRuntimePersistenceError("nested neuron identity or clock epoch differs from network")
        if neuron_id in neurons:
            raise NetworkRuntimePersistenceError("network snapshot contains duplicate neuron IDs")
        neurons[neuron_id] = decoded.runtime
        neuron_names.append(neuron_id)
    if neuron_names != sorted(neuron_names):
        raise NetworkRuntimePersistenceError("neuron entries are not in canonical ID order")

    raw_connections = top["connections"]
    if not isinstance(raw_connections, list) or len(raw_connections) > _MAX_CONNECTIONS:
        raise NetworkRuntimePersistenceError("connections must be a bounded array")
    connections = []
    connection_ids = []
    connection_keys = {
        "connection_id", "source_neuron_id", "target_neuron_id",
        "target_synapse_id", "delay_ns", "strength", "attention",
    }
    for item in raw_connections:
        entry = _exact_object("connection entry", item, connection_keys)
        if isinstance(entry["delay_ns"], bool) or not isinstance(entry["delay_ns"], int):
            raise NetworkRuntimePersistenceError("connection delay_ns must be an integer")
        connection = NetworkConnection(
            connection_id=entry["connection_id"],
            source_neuron_id=entry["source_neuron_id"],
            target_neuron_id=entry["target_neuron_id"],
            target_synapse_id=entry["target_synapse_id"],
            delay_ns=entry["delay_ns"],
            strength=entry["strength"],
            attention=entry["attention"],
        )
        connection.validate()
        connections.append(connection)
        connection_ids.append(connection.connection_id)
    if connection_ids != sorted(connection_ids) or len(connection_ids) != len(set(connection_ids)):
        raise NetworkRuntimePersistenceError("connection entries must be uniquely sorted by connection_id")

    raw_pending = top["pending_events"]
    if not isinstance(raw_pending, list) or len(raw_pending) > queue_limit:
        raise NetworkRuntimePersistenceError("pending_events exceeds the configured queue bound")
    pending = []
    pending_keys = []
    event_keys = {
        "time_ns", "event_id", "target_neuron_id",
        "target_synapse_id", "strength", "attention",
    }
    for item in raw_pending:
        entry = _exact_object("pending event", item, event_keys)
        time_ns = _u64("pending event time_ns", entry["time_ns"])
        pending.append((
            time_ns,
            entry["event_id"],
            entry["target_neuron_id"],
            entry["target_synapse_id"],
            entry["strength"],
            entry["attention"],
        ))
        pending_keys.append((
            time_ns,
            entry["event_id"],
            entry["target_neuron_id"],
            entry["target_synapse_id"],
        ))
    if pending_keys != sorted(pending_keys):
        raise NetworkRuntimePersistenceError("pending events are not in canonical scheduler order")

    try:
        runtime = NetworkRuntime.restore_checkpoint_state(
            neurons,
            connections,
            pending,
            max_pending_events=queue_limit,
            max_events_per_run=run_limit,
            current_time_ns=current_time,
            has_processed=has_processed,
        )
    except NetworkRuntimeError as exc:
        raise NetworkRuntimePersistenceError(f"restored network state is invalid: {exc}") from exc
    return DecodedNetworkRuntimeSnapshot(network_id, epoch, runtime)


def make_network_runtime_record_input(
    network_id: str,
    runtime: NetworkRuntime,
    *,
    clock_epoch_id: bytes,
    record_version: int = 1,
) -> BlobRecordInput:
    """Create the bounded JNNW BLOB record persisted by the network checkpoint store."""
    identifier = _identifier("network_id", network_id)
    version = _u64("record_version", record_version, minimum=1)
    payload = encode_network_runtime_snapshot(
        identifier, runtime, clock_epoch_id=clock_epoch_id
    )
    return BlobRecordInput(
        NETWORK_RUNTIME_RECORD_TYPE,
        NETWORK_RUNTIME_SCHEMA_ID,
        NETWORK_RUNTIME_SCHEMA_VERSION,
        network_runtime_object_id(identifier),
        version,
        payload,
    )
