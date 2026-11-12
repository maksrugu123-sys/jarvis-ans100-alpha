"""Versioned JPVR record encoding for bounded task/session lifecycle snapshots."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import re

from tools.jpvr_reference.records import MAX_LOGICAL_RECORD_BYTES, MAX_SMALL_RECORD_PAYLOAD
from tools.jpvr_reference.typed_transaction import BlobRecordInput, DataRecordInput

from .task_session import TaskSessionError, TaskSessionManager

TASK_SESSION_RECORD_TYPE = 0x0104
TASK_SESSION_SCHEMA_ID = 0x4A54534C  # JTSL
TASK_SESSION_SCHEMA_VERSION = 1
_DOMAIN = b"JARVIS:JPVR:TASK-SESSION:V1\x00"


class TaskSessionPersistenceError(ValueError):
    """Invalid or incompatible persisted task/session state."""


@dataclass(frozen=True)
class DecodedTaskSessionSnapshot:
    manager_id: str
    manager: TaskSessionManager


def task_session_object_id(manager_id: str) -> bytes:
    if not isinstance(manager_id, str) or not _MANAGER_ID.fullmatch(manager_id):
        raise TaskSessionPersistenceError("manager_id must match the stable 1..128 character task/session ID contract")
    return hashlib.sha256(_DOMAIN + manager_id.encode("ascii")).digest()[:16]


def encode_task_session_snapshot(manager: TaskSessionManager) -> bytes:
    if not isinstance(manager, TaskSessionManager):
        raise TaskSessionPersistenceError("manager must be a TaskSessionManager")
    try:
        payload = manager.encode_snapshot()
    except TaskSessionError as exc:
        raise TaskSessionPersistenceError(str(exc)) from exc
    if not payload or len(payload) > MAX_LOGICAL_RECORD_BYTES:
        raise TaskSessionPersistenceError("task/session snapshot exceeds JPVR logical-record limit")
    return payload


def decode_task_session_snapshot(payload: bytes | bytearray | memoryview) -> DecodedTaskSessionSnapshot:
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise TaskSessionPersistenceError("payload must be bytes-like")
    raw = bytes(payload)
    if not raw or len(raw) > MAX_LOGICAL_RECORD_BYTES:
        raise TaskSessionPersistenceError("payload length is outside JPVR limits")
    try:
        manager = TaskSessionManager.restore_snapshot(raw)
    except TaskSessionError as exc:
        raise TaskSessionPersistenceError(str(exc)) from exc
    return DecodedTaskSessionSnapshot(manager.manager_id, manager)


def make_task_session_record_input(
    manager: TaskSessionManager, *, record_version: int = 1
) -> DataRecordInput | BlobRecordInput:
    if isinstance(record_version, bool) or not isinstance(record_version, int) or not 1 <= record_version <= (1 << 64) - 1:
        raise TaskSessionPersistenceError("record_version must be a positive unsigned 64-bit integer")
    payload = encode_task_session_snapshot(manager)
    cls = DataRecordInput if len(payload) <= MAX_SMALL_RECORD_PAYLOAD else BlobRecordInput
    return cls(
        TASK_SESSION_RECORD_TYPE, TASK_SESSION_SCHEMA_ID,
        TASK_SESSION_SCHEMA_VERSION, task_session_object_id(manager.manager_id),
        record_version, payload,
    )
