"""Versioned JPVR checkpoint adapter for the in-memory one-neuron runtime.

This is an application-level record codec and typed-transaction adapter. It
does not publish a commit, maintain the neuron INDEX, or persist a NetworkRuntime
event queue. Model timestamps remain in their supplied clock epoch.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, fields, replace
import hashlib
import json
from typing import Any

from tools.jpvr_reference.blob import DecodedBlobRecord
from tools.jpvr_reference.records import (
    DecodedRecord,
    MAX_LOGICAL_RECORD_BYTES,
    MAX_SMALL_RECORD_PAYLOAD,
)
from tools.jpvr_reference.typed_transaction import (
    BlobRecordInput,
    DataRecordInput,
    TypedTransactionRequest,
)
from tools.numerical_oracle.jarvis_oracle import (
    NeuronParams,
    NeuronState,
    RewardParams,
    SynapseParams,
    SynapseState,
)

from .runtime import NeuronRuntime


JNVM_RUNTIME_RECORD_TYPE = 0x0101
JNVM_RUNTIME_SCHEMA_ID = 0x4A4E564D  # ASCII "JNVM"
JNVM_RUNTIME_SCHEMA_VERSION = 1
_SNAPSHOT_FORMAT = "JARVIS_NEURON_RUNTIME_SNAPSHOT_V1"
_U64_MAX = (1 << 64) - 1
_OBJECT_ID_DOMAIN = b"JARVIS:JNVM:NEURON-RUNTIME:V1\x00"


class NeuronRuntimePersistenceError(ValueError):
    """Invalid or incompatible one-neuron JPVR checkpoint data."""


@dataclass(frozen=True)
class DecodedNeuronRuntimeSnapshot:
    """A validated runtime and the clock epoch to which its model times belong."""

    neuron_id: str
    clock_epoch_id: bytes
    runtime: NeuronRuntime


def _identifier(name: str, value: object) -> str:
    if not isinstance(value, str) or not value.strip() or "\x00" in value:
        raise NeuronRuntimePersistenceError(
            f"{name} must be a non-empty string without NUL"
        )
    try:
        encoded = value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise NeuronRuntimePersistenceError(f"{name} must be valid UTF-8") from exc
    if len(encoded) > 512:
        raise NeuronRuntimePersistenceError(f"{name} exceeds 512 UTF-8 bytes")
    return value


def neuron_runtime_object_id(neuron_id: str) -> bytes:
    """Return the stable 128-bit JPVR object identity for a logical neuron ID."""
    identifier = _identifier("neuron_id", neuron_id)
    return hashlib.sha256(_OBJECT_ID_DOMAIN + identifier.encode("utf-8")).digest()[:16]


def _clock_epoch(value: object) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise NeuronRuntimePersistenceError("clock_epoch_id must be 16 bytes")
    result = bytes(value)
    if len(result) != 16 or result == bytes(16):
        raise NeuronRuntimePersistenceError(
            "clock_epoch_id must be a non-zero 16-byte identifier"
        )
    return result


def _record_version(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= _U64_MAX:
        raise NeuronRuntimePersistenceError(
            "record_version must be a non-zero unsigned 64-bit integer"
        )
    return value


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise NeuronRuntimePersistenceError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise NeuronRuntimePersistenceError(f"non-finite JSON constant is forbidden: {value}")


def _require_keys(name: str, value: object, expected: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise NeuronRuntimePersistenceError(f"{name} must be a JSON object")
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        unknown = sorted(actual - expected)
        raise NeuronRuntimePersistenceError(
            f"{name} fields mismatch (missing={missing}, unknown={unknown})"
        )
    return value


def _dataclass_fields(cls: type) -> set[str]:
    return {item.name for item in fields(cls)}


def _dataclass_payload(name: str, value: object, cls: type) -> dict[str, Any]:
    return _require_keys(name, value, _dataclass_fields(cls))


def encode_neuron_runtime_snapshot(
    neuron_id: str,
    runtime: NeuronRuntime,
    *,
    clock_epoch_id: bytes,
) -> bytes:
    """Encode all authoritative one-neuron runtime state in canonical JSON bytes.

    The model timestamps are meaningful only in the explicitly supplied
    128-bit clock epoch. The same epoch must be resumed after process restart;
    a new OS monotonic-clock origin must not be substituted silently.
    """
    identifier = _identifier("neuron_id", neuron_id)
    epoch = _clock_epoch(clock_epoch_id)
    if not isinstance(runtime, NeuronRuntime):
        raise NeuronRuntimePersistenceError("runtime must be a NeuronRuntime")

    runtime.neuron_state.validate(runtime.neuron_params)
    runtime.synapse_params.validate()
    runtime.reward_params.validate()
    synapse_items = []
    for synapse_id, state in sorted(runtime.synapses.items()):
        synapse_name = _identifier("synapse_id", synapse_id)
        state.validate(runtime.synapse_params, runtime.reward_params)
        # Per-synapse trace/reward timestamps are independent from the last
        # membrane update. A delayed R-STDP reward may advance the former
        # without forcing a synthetic neuron integration step.
        synapse_items.append({"synapse_id": synapse_name, "state": asdict(state)})

    payload = {
        "format": _SNAPSHOT_FORMAT,
        "schema_version": JNVM_RUNTIME_SCHEMA_VERSION,
        "neuron_id": identifier,
        "clock_epoch_id": epoch.hex(),
        "learning_rule": runtime.learning_rule,
        "processed_batches": runtime.processed_batches,
        "neuron_params": asdict(runtime.neuron_params),
        "synapse_params": asdict(runtime.synapse_params),
        "reward_params": asdict(runtime.reward_params),
        "neuron_state": asdict(runtime.neuron_state),
        "synapses": synapse_items,
    }
    try:
        encoded = json.dumps(
            payload,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8", errors="strict")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise NeuronRuntimePersistenceError(f"cannot encode runtime snapshot: {exc}") from exc
    if not encoded or len(encoded) > MAX_LOGICAL_RECORD_BYTES:
        raise NeuronRuntimePersistenceError(
            f"runtime snapshot must fit the {MAX_LOGICAL_RECORD_BYTES}-byte JPVR record limit"
        )
    return encoded


def decode_neuron_runtime_snapshot(payload: bytes | bytearray | memoryview) -> DecodedNeuronRuntimeSnapshot:
    """Strictly decode a snapshot and reconstruct a validated NeuronRuntime."""
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise NeuronRuntimePersistenceError("snapshot payload must be bytes-like")
    raw = bytes(payload)
    if not raw or len(raw) > MAX_LOGICAL_RECORD_BYTES:
        raise NeuronRuntimePersistenceError("snapshot payload length is outside JPVR limits")
    try:
        document = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_json_constant,
        )
    except NeuronRuntimePersistenceError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise NeuronRuntimePersistenceError(f"invalid snapshot JSON: {exc}") from exc

    expected_top = {
        "format", "schema_version", "neuron_id", "clock_epoch_id",
        "learning_rule", "processed_batches", "neuron_params",
        "synapse_params", "reward_params", "neuron_state", "synapses",
    }
    top = _require_keys("snapshot", document, expected_top)
    schema_version = top["schema_version"]
    if (
        isinstance(schema_version, bool)
        or not isinstance(schema_version, int)
        or top["format"] != _SNAPSHOT_FORMAT
        or schema_version != JNVM_RUNTIME_SCHEMA_VERSION
    ):
        raise NeuronRuntimePersistenceError("unsupported neuron runtime snapshot format/version")
    neuron_id = _identifier("neuron_id", top["neuron_id"])
    clock_text = top["clock_epoch_id"]
    if not isinstance(clock_text, str) or len(clock_text) != 32:
        raise NeuronRuntimePersistenceError("clock_epoch_id must be 32 lowercase hex characters")
    try:
        epoch = bytes.fromhex(clock_text)
    except ValueError as exc:
        raise NeuronRuntimePersistenceError("clock_epoch_id is not hexadecimal") from exc
    if epoch.hex() != clock_text or epoch == bytes(16):
        raise NeuronRuntimePersistenceError("clock_epoch_id is not canonical or is zero")

    processed = top["processed_batches"]
    if isinstance(processed, bool) or not isinstance(processed, int) or not 0 <= processed <= _U64_MAX:
        raise NeuronRuntimePersistenceError("processed_batches is outside unsigned 64-bit range")
    learning_rule = top["learning_rule"]
    if learning_rule not in ("none", "stdp", "rstdp"):
        raise NeuronRuntimePersistenceError("unsupported learning_rule")

    neuron_params = NeuronParams(**_dataclass_payload("neuron_params", top["neuron_params"], NeuronParams))
    synapse_params = SynapseParams(**_dataclass_payload("synapse_params", top["synapse_params"], SynapseParams))
    reward_params = RewardParams(**_dataclass_payload("reward_params", top["reward_params"], RewardParams))
    neuron_state = NeuronState(**_dataclass_payload("neuron_state", top["neuron_state"], NeuronState))

    raw_synapses = top["synapses"]
    if not isinstance(raw_synapses, list):
        raise NeuronRuntimePersistenceError("synapses must be a JSON array")
    synapses: dict[str, SynapseState] = {}
    names: list[str] = []
    for item in raw_synapses:
        entry = _require_keys("synapse entry", item, {"synapse_id", "state"})
        synapse_id = _identifier("synapse_id", entry["synapse_id"])
        if synapse_id in synapses:
            raise NeuronRuntimePersistenceError("snapshot contains duplicate synapse IDs")
        state_values = _dataclass_payload("synapse state", entry["state"], SynapseState)
        synapses[synapse_id] = SynapseState(**state_values)
        names.append(synapse_id)
    if names != sorted(names):
        raise NeuronRuntimePersistenceError("synapse entries are not in canonical ID order")

    try:
        runtime = NeuronRuntime(
            synapses,
            neuron_state=neuron_state,
            neuron_params=neuron_params,
            synapse_params=synapse_params,
            reward_params=reward_params,
            learning_rule=learning_rule,
            processed_batches=processed,
        )
    except (TypeError, ValueError) as exc:
        raise NeuronRuntimePersistenceError(f"reconstructed runtime is invalid: {exc}") from exc
    return DecodedNeuronRuntimeSnapshot(neuron_id, epoch, runtime)


def decode_neuron_runtime_record(
    record: DecodedRecord | DecodedBlobRecord | DataRecordInput | BlobRecordInput,
) -> DecodedNeuronRuntimeSnapshot:
    """Validate JPVR record metadata and decode its logical neuron snapshot."""
    required = ("record_type", "schema_id", "schema_version", "object_id", "record_version", "payload")
    if any(not hasattr(record, name) for name in required):
        raise NeuronRuntimePersistenceError("record does not expose the JREC/BLOB metadata contract")
    if (
        record.record_type != JNVM_RUNTIME_RECORD_TYPE
        or record.schema_id != JNVM_RUNTIME_SCHEMA_ID
        or record.schema_version != JNVM_RUNTIME_SCHEMA_VERSION
    ):
        raise NeuronRuntimePersistenceError("JPVR record type/schema does not identify a JNVM runtime snapshot")
    _record_version(record.record_version)
    if not isinstance(record.object_id, (bytes, bytearray, memoryview)) or len(record.object_id) != 16:
        raise NeuronRuntimePersistenceError("JPVR runtime record object_id must be 16 bytes")
    decoded = decode_neuron_runtime_snapshot(record.payload)
    if bytes(record.object_id) != neuron_runtime_object_id(decoded.neuron_id):
        raise NeuronRuntimePersistenceError("JPVR object_id does not match the snapshot neuron_id")
    return decoded


def make_neuron_runtime_record_input(
    neuron_id: str,
    runtime: NeuronRuntime,
    *,
    clock_epoch_id: bytes,
    record_version: int = 1,
) -> DataRecordInput | BlobRecordInput:
    """Create a DATA/JREC or BLOB input depending on the encoded snapshot size."""
    identifier = _identifier("neuron_id", neuron_id)
    version = _record_version(record_version)
    payload = encode_neuron_runtime_snapshot(
        identifier, runtime, clock_epoch_id=clock_epoch_id
    )
    common = (
        JNVM_RUNTIME_RECORD_TYPE,
        JNVM_RUNTIME_SCHEMA_ID,
        JNVM_RUNTIME_SCHEMA_VERSION,
        neuron_runtime_object_id(identifier),
        version,
    )
    if len(payload) <= MAX_SMALL_RECORD_PAYLOAD:
        return DataRecordInput(*common, payload)
    return BlobRecordInput(*common, payload)


def append_neuron_runtime_checkpoint(
    request: TypedTransactionRequest,
    neuron_id: str,
    runtime: NeuronRuntime,
    *,
    clock_epoch_id: bytes,
    record_version: int = 1,
) -> TypedTransactionRequest:
    """Return a new transaction request carrying one complete neuron snapshot.

    The request is not mutated. This only creates a typed DATA/BLOB input; the
    caller still builds and durably commits the transaction through JPVRStorage.
    """
    if not isinstance(request, TypedTransactionRequest):
        raise NeuronRuntimePersistenceError("request must be a TypedTransactionRequest")
    if not isinstance(request.data_records, (tuple, list)) or not isinstance(request.blob_records, (tuple, list)):
        raise NeuronRuntimePersistenceError("request DATA/BLOB inputs must be tuple or list")
    if any(not isinstance(item, DataRecordInput) for item in request.data_records):
        raise NeuronRuntimePersistenceError("request contains an invalid DATA record input")
    if any(not isinstance(item, BlobRecordInput) for item in request.blob_records):
        raise NeuronRuntimePersistenceError("request contains an invalid BLOB record input")

    new_record = make_neuron_runtime_record_input(
        neuron_id,
        runtime,
        clock_epoch_id=clock_epoch_id,
        record_version=record_version,
    )
    identity = (new_record.record_type, new_record.schema_id, new_record.object_id)
    for item in (*request.data_records, *request.blob_records):
        if (item.record_type, item.schema_id, item.object_id) == identity:
            raise NeuronRuntimePersistenceError(
                "transaction already contains a checkpoint for this neuron"
            )
    if isinstance(new_record, DataRecordInput):
        return replace(
            request,
            data_records=tuple(request.data_records) + (new_record,),
        )
    return replace(
        request,
        blob_records=tuple(request.blob_records) + (new_record,),
    )
