"""Canonical versioned JNVM snapshot codec for RewardBaselineManager."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import math

from tools.jpvr_reference.blob import MAX_BLOB_PAGES
from tools.jpvr_reference.records import MAX_LOGICAL_RECORD_BYTES
from tools.jpvr_reference.typed_transaction import BlobRecordInput

from .reward_baseline import RewardBaselineError, RewardBaselineManager

REWARD_BASELINE_RECORD_TYPE = 0x0103
REWARD_BASELINE_SCHEMA_ID = 0x4A52424D  # ASCII "JRBM"
REWARD_BASELINE_SCHEMA_VERSION = 1
_REWARD_FORMAT = "JARVIS_REWARD_BASELINE_MANAGER_V1"
_OBJECT_ID_DOMAIN = b"JARVIS:JNVM:REWARD-BASELINE:V1\x00"
_U64_MAX = (1 << 64) - 1


class RewardBaselinePersistenceError(ValueError):
    """Invalid or incompatible reward-baseline JPVR snapshot."""


@dataclass(frozen=True)
class DecodedRewardBaselineSnapshot:
    manager_id: str
    clock_epoch_id: bytes
    manager: RewardBaselineManager


def reward_baseline_object_id(manager_id: str) -> bytes:
    if not isinstance(manager_id, str) or not manager_id or manager_id != manager_id.strip():
        raise RewardBaselinePersistenceError("manager_id must be a non-empty trimmed string")
    try:
        encoded = manager_id.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise RewardBaselinePersistenceError("manager_id must be valid UTF-8") from exc
    if len(encoded) > 512:
        raise RewardBaselinePersistenceError("manager_id exceeds 512 UTF-8 bytes")
    return hashlib.sha256(_OBJECT_ID_DOMAIN + encoded).digest()[:16]


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise RewardBaselinePersistenceError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str):
    raise RewardBaselinePersistenceError(f"non-finite JSON constant is forbidden: {value}")


def _exact(name: str, value: object, keys: set[str]) -> dict:
    if not isinstance(value, dict) or set(value) != keys:
        raise RewardBaselinePersistenceError(f"{name} fields do not match the schema")
    return value


def encode_reward_baseline_snapshot(manager: RewardBaselineManager) -> bytes:
    if not isinstance(manager, RewardBaselineManager):
        raise RewardBaselinePersistenceError("manager must be a RewardBaselineManager")
    try:
        state = manager.export_checkpoint_state()
        doc = {
            "format": _REWARD_FORMAT,
            "schema_version": REWARD_BASELINE_SCHEMA_VERSION,
            "manager_id": manager.manager_id,
            "clock_epoch_id": manager.clock_epoch_id.hex(),
            "state": state,
        }
        payload = json.dumps(
            doc, ensure_ascii=False, allow_nan=False, sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8", errors="strict")
    except (RewardBaselineError, TypeError, ValueError, UnicodeEncodeError) as exc:
        raise RewardBaselinePersistenceError(f"cannot encode reward-baseline state: {exc}") from exc
    if not payload or len(payload) > MAX_LOGICAL_RECORD_BYTES:
        raise RewardBaselinePersistenceError("reward-baseline snapshot exceeds JPVR logical-record limits")
    return payload


def decode_reward_baseline_snapshot(
    payload: bytes | bytearray | memoryview,
) -> DecodedRewardBaselineSnapshot:
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise RewardBaselinePersistenceError("snapshot payload must be bytes-like")
    raw = bytes(payload)
    if not raw or len(raw) > MAX_LOGICAL_RECORD_BYTES:
        raise RewardBaselinePersistenceError("snapshot payload length is outside JPVR limits")
    try:
        doc = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except RewardBaselinePersistenceError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise RewardBaselinePersistenceError(f"invalid reward-baseline JSON: {exc}") from exc
    top = _exact("reward-baseline snapshot", doc, {
        "format", "schema_version", "manager_id", "clock_epoch_id", "state",
    })
    version = top["schema_version"]
    if top["format"] != _REWARD_FORMAT or isinstance(version, bool) or not isinstance(version, int) or version != REWARD_BASELINE_SCHEMA_VERSION:
        raise RewardBaselinePersistenceError("unsupported reward-baseline format/version")
    manager_id = top["manager_id"]
    object_id = reward_baseline_object_id(manager_id)
    clock_text = top["clock_epoch_id"]
    if not isinstance(clock_text, str) or len(clock_text) != 32:
        raise RewardBaselinePersistenceError("clock_epoch_id must be 32 lowercase hex characters")
    try:
        epoch = bytes.fromhex(clock_text)
    except ValueError as exc:
        raise RewardBaselinePersistenceError("clock_epoch_id is not hexadecimal") from exc
    if epoch.hex() != clock_text or epoch == bytes(16):
        raise RewardBaselinePersistenceError("clock_epoch_id is non-canonical or zero")
    try:
        manager = RewardBaselineManager.from_checkpoint_state(top["state"])
    except RewardBaselineError as exc:
        raise RewardBaselinePersistenceError(f"invalid reward-baseline checkpoint state: {exc}") from exc
    if manager.manager_id != manager_id or manager.clock_epoch_id != epoch or object_id == bytes(16):
        raise RewardBaselinePersistenceError("manager identity or clock epoch differs from its snapshot")
    return DecodedRewardBaselineSnapshot(manager_id, epoch, manager)


def make_reward_baseline_record_input(
    manager: RewardBaselineManager,
    *,
    record_version: int,
) -> BlobRecordInput:
    if isinstance(record_version, bool) or not isinstance(record_version, int) or not 1 <= record_version <= _U64_MAX:
        raise RewardBaselinePersistenceError("record_version must be a non-zero unsigned 64-bit integer")
    payload = encode_reward_baseline_snapshot(manager)
    return BlobRecordInput(
        REWARD_BASELINE_RECORD_TYPE,
        REWARD_BASELINE_SCHEMA_ID,
        REWARD_BASELINE_SCHEMA_VERSION,
        reward_baseline_object_id(manager.manager_id),
        record_version,
        payload,
    )
