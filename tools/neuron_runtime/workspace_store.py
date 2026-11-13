"""Shared, versioned JARVIS workspace model and JPVR checkpoint store.

The JSON document is the platform-neutral contract. Desktop Python and Android
Kotlin adapters must preserve the same canonical bytes and revision semantics.
This module is a reference implementation, not a claim that Android is already
running this code in-process.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import re
import struct
import uuid

from tools.jpvr_reference.blob import BLOB_FRAGMENT_DATA_BYTES, MAX_BLOB_PAGES, decode_blob_pages
from tools.jpvr_reference.codec import (
    JPVRFormatError, PageType, crc32c, decode_page,
)
from tools.jpvr_reference.commit import decode_commit_page
from tools.jpvr_reference.data import decode_data_page
from tools.jpvr_reference.manifest import decode_manifest_page
from tools.jpvr_reference.records import MAX_LOGICAL_RECORD_BYTES, MAX_SMALL_RECORD_PAYLOAD
from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.jpvr_store import JPVRNeuronCheckpointStore
from tools.jpvr_reference.typed_transaction import (
    BlobRecordInput, CheckpointPageInput, DataRecordInput,
    TypedTransactionRequest, build_typed_transaction,
)

_U64_MAX = (1 << 64) - 1
_MAGIC = b"JWSP"
_VERSION = 1
_DESCRIPTOR = struct.Struct("<4sHHQ16sB7xQQQQI")
_RECORD_TYPE = 0x31
_SCHEMA_ID = 0x31
_SCHEMA_VERSION = 1
_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_SETTING_PATTERN = re.compile(r"^[a-z][a-z0-9._-]{0,63}$")
_SECRET_SETTING_PARTS = ("password", "passwd", "secret", "token", "credential", "api_key", "private_key", "auth")


class WorkspaceError(ValueError):
    """Invalid shared workspace document or persisted checkpoint."""


class WorkspaceNotFound(WorkspaceError):
    """The JPVR container has no published workspace checkpoint."""


class WorkspaceConflict(WorkspaceError):
    """A compare-and-swap revision or workspace identity did not match."""


@dataclass(frozen=True)
class WorkspaceSnapshot:
    workspace_id: str
    revision: int
    exported_at: str
    source_platform: str
    records: dict


@dataclass(frozen=True)
class WorkspaceCheckpoint:
    revision: int
    first_page_no: int
    page_count: int
    storage_kind: str
    generation: int
    commit_page_no: int


def _utc_now() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def _check_id(value, name):
    if not isinstance(value, str) or not _ID_PATTERN.fullmatch(value):
        raise WorkspaceError(f"{name} is not a valid workspace identifier")
    return value


def _check_datetime(value, name):
    if not isinstance(value, str):
        raise WorkspaceError(f"{name} must be an ISO-8601 date-time")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise WorkspaceError(f"{name} must be an ISO-8601 date-time") from exc
    if parsed.tzinfo is None:
        raise WorkspaceError(f"{name} must include a timezone")


def _canonical(value) -> bytes:
    try:
        return json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise WorkspaceError("workspace contains a non-canonical JSON value") from exc


def _base_document(snapshot: WorkspaceSnapshot) -> dict:
    return {
        "format": "jarvis.workspace",
        "version": {"major": 1, "minor": 0},
        "workspace_id": snapshot.workspace_id,
        "export_id": "workspace-current",
        "exported_at": snapshot.exported_at,
        "source_platform": snapshot.source_platform,
        "records": snapshot.records,
    }


def encode_workspace(snapshot: WorkspaceSnapshot) -> bytes:
    if not isinstance(snapshot, WorkspaceSnapshot):
        raise WorkspaceError("snapshot must be a WorkspaceSnapshot")
    _check_id(snapshot.workspace_id, "workspace_id")
    if isinstance(snapshot.revision, bool) or not isinstance(snapshot.revision, int) or not 0 <= snapshot.revision <= _U64_MAX:
        raise WorkspaceError("revision must be an unsigned 64-bit integer")
    if snapshot.source_platform not in ("desktop", "android"):
        raise WorkspaceError("source_platform must be desktop or android")
    try:
        parsed = datetime.fromisoformat(snapshot.exported_at.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as exc:
        raise WorkspaceError("exported_at must be an ISO-8601 date-time") from exc
    if parsed.tzinfo is None:
        raise WorkspaceError("exported_at must include a timezone")
    if not isinstance(snapshot.records, dict) or set(snapshot.records) != {"conversations", "memories", "tasks", "settings"}:
        raise WorkspaceError("records must contain conversations, memories, tasks, and settings")
    records = snapshot.records
    for key, maximum in (("conversations", 10000), ("memories", 10000), ("tasks", 10000), ("settings", 1000)):
        if not isinstance(records[key], list) or len(records[key]) > maximum:
            raise WorkspaceError(f"{key} must be a bounded array")
    seen = set()
    for collection in ("conversations", "memories", "tasks"):
        for item in records[collection]:
            if not isinstance(item, dict):
                raise WorkspaceError(f"{collection} entries must be objects")
            _check_id(item.get("id"), f"{collection}.id")
            if item["id"] in seen:
                raise WorkspaceError("record IDs must be unique across collections")
            seen.add(item["id"])
            if isinstance(item.get("revision"), bool) or not isinstance(item.get("revision"), int) or not 0 <= item["revision"] <= 2147483647:
                raise WorkspaceError(f"{collection}.revision is invalid")
            _check_datetime(item.get("updated_at"), f"{collection}.updated_at")
            if collection == "memories":
                if set(item) - {"id", "revision", "updated_at", "content", "category", "user_approved"}:
                    raise WorkspaceError("memory contains unsupported fields")
                if item.get("user_approved") is not True or not isinstance(item.get("content"), str) or not item["content"].strip() or len(item["content"]) > 10000:
                    raise WorkspaceError("memories must be explicit user-approved text")
                if not isinstance(item.get("category"), str) or len(item["category"]) > 64:
                    raise WorkspaceError("memory category is invalid")
            elif collection == "conversations":
                if set(item) - {"id", "revision", "updated_at", "title", "messages"}:
                    raise WorkspaceError("conversation contains unsupported fields")
                if not isinstance(item.get("title"), str) or len(item["title"]) > 160:
                    raise WorkspaceError("conversation title is invalid")
                messages = item.get("messages")
                if not isinstance(messages, list) or len(messages) > 50000:
                    raise WorkspaceError("conversation messages must be a bounded array")
                message_ids = set()
                for message in messages:
                    if not isinstance(message, dict) or set(message) != {"id", "role", "created_at", "content"}:
                        raise WorkspaceError("conversation message fields are invalid")
                    _check_id(message["id"], "message.id")
                    if message["id"] in message_ids:
                        raise WorkspaceError("message IDs must be unique within a conversation")
                    message_ids.add(message["id"])
                    _check_datetime(message["created_at"], "message.created_at")
                    if message["role"] not in ("user", "assistant", "system") or not isinstance(message["content"], str) or len(message["content"]) > 100000:
                        raise WorkspaceError("conversation message is invalid")
            else:
                if set(item) - {"id", "revision", "updated_at", "title", "status", "result_summary"}:
                    raise WorkspaceError("task contains unsupported fields")
                if not isinstance(item.get("title"), str) or not item["title"].strip() or len(item["title"]) > 512:
                    raise WorkspaceError("task title is invalid")
                if item.get("status") not in ("queued", "running", "paused", "completed", "failed", "cancelled"):
                    raise WorkspaceError("task status is invalid")
                if "result_summary" in item and (not isinstance(item["result_summary"], str) or len(item["result_summary"]) > 4000):
                    raise WorkspaceError("task result_summary is invalid")
    setting_keys = set()
    for setting in records["settings"]:
        if not isinstance(setting, dict) or set(setting) != {"key", "value"}:
            raise WorkspaceError("setting must contain only key and value")
        key = setting["key"]
        if not isinstance(key, str) or not _SETTING_PATTERN.fullmatch(key) or key in setting_keys:
            raise WorkspaceError("setting key is invalid or duplicated")
        if any(part in key for part in _SECRET_SETTING_PARTS):
            raise WorkspaceError("credentials, tokens, and secrets are forbidden in portable workspace settings")
        setting_keys.add(key)
        value = setting["value"]
        if value is not None and not isinstance(value, (str, int, float, bool)):
            raise WorkspaceError("setting value must be a JSON scalar")
        if isinstance(value, float) and (value != value or abs(value) == float("inf")):
            raise WorkspaceError("setting value must be finite")
    body = _base_document(snapshot)
    digest = hashlib.sha256(_canonical(body)).hexdigest()
    body["integrity"] = {"algorithm": "sha-256", "digest": digest}
    encoded = _canonical(body)
    if not encoded or len(encoded) > MAX_LOGICAL_RECORD_BYTES:
        raise WorkspaceError("workspace exceeds the JPVR logical-record size limit")
    return encoded


def decode_workspace(payload: bytes) -> WorkspaceSnapshot:
    if not isinstance(payload, bytes) or not payload or len(payload) > MAX_LOGICAL_RECORD_BYTES:
        raise WorkspaceError("workspace payload size is invalid")
    try:
        value = json.loads(payload.decode("utf-8", errors="strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise WorkspaceError("workspace is not valid UTF-8 JSON") from exc
    if not isinstance(value, dict) or set(value) != {
        "format", "version", "workspace_id", "export_id", "exported_at",
        "source_platform", "records", "integrity",
    }:
        raise WorkspaceError("workspace document fields are invalid")
    if value["format"] != "jarvis.workspace" or value["version"] != {"major": 1, "minor": 0}:
        raise WorkspaceError("unsupported workspace format version")
    integrity = value["integrity"]
    if not isinstance(integrity, dict) or set(integrity) != {"algorithm", "digest"} or integrity.get("algorithm") != "sha-256":
        raise WorkspaceError("workspace integrity metadata is invalid")
    unsigned = dict(value)
    unsigned.pop("integrity")
    expected = hashlib.sha256(_canonical(unsigned)).hexdigest()
    if integrity.get("digest") != expected:
        raise WorkspaceError("workspace SHA-256 integrity check failed")
    snapshot = WorkspaceSnapshot(
        value["workspace_id"], 0, value["exported_at"],
        value["source_platform"], value["records"],
    )
    # Revalidate the semantic contract and canonical encoding.
    canonical_snapshot = encode_workspace(snapshot)
    if canonical_snapshot != payload:
        raise WorkspaceError("workspace JSON is not in canonical form")
    return snapshot


def _object_id(workspace_id: str) -> bytes:
    raw = hashlib.sha256(("jarvis.workspace:" + workspace_id).encode("utf-8")).digest()[:16]
    return raw if raw != bytes(16) else b"\x00" * 15 + b"\x01"


def _new_id() -> bytes:
    return uuid.uuid4().bytes


def _encode_descriptor(revision, object_id, kind, first, count, generation, length, checksum):
    kind_id = {"DATA": 1, "BLOB": 2}.get(kind)
    if kind_id is None or not 1 <= revision <= _U64_MAX or len(object_id) != 16:
        raise WorkspaceError("workspace checkpoint descriptor fields are invalid")
    if first < 2 or count < 1 or generation < 1 or length < 1 or checksum < 0 or checksum > 0xFFFFFFFF:
        raise WorkspaceError("workspace checkpoint descriptor counters are invalid")
    if kind == "DATA" and (count != 1 or length > MAX_SMALL_RECORD_PAYLOAD):
        raise WorkspaceError("workspace DATA descriptor is invalid")
    if kind == "BLOB":
        if count > MAX_BLOB_PAGES or length <= MAX_SMALL_RECORD_PAYLOAD:
            raise WorkspaceError("workspace BLOB descriptor is invalid")
        if count != (length + BLOB_FRAGMENT_DATA_BYTES - 1) // BLOB_FRAGMENT_DATA_BYTES:
            raise WorkspaceError("workspace BLOB page count is invalid")
    return _DESCRIPTOR.pack(_MAGIC, _VERSION, 0, revision, object_id, kind_id,
                            first, count, generation, length, checksum)


def _decode_descriptor(raw):
    if len(raw) != _DESCRIPTOR.size:
        raise WorkspaceError("workspace checkpoint descriptor has invalid size")
    magic, version, flags, revision, object_id, kind, first, count, generation, length, checksum = _DESCRIPTOR.unpack(raw)
    if magic != _MAGIC or version != _VERSION or flags != 0 or kind not in (1, 2):
        raise WorkspaceError("unsupported workspace checkpoint descriptor")
    if any(raw[33:40]):
        raise WorkspaceError("workspace checkpoint reserved bytes must be zero")
    storage_kind = "DATA" if kind == 1 else "BLOB"
    _encode_descriptor(revision, object_id, storage_kind, first, count, generation, length, checksum)
    return revision, object_id, storage_kind, first, count, generation, length, checksum


class JPVRWorkspaceStore:
    """CAS-protected durable store for one shared desktop/Android workspace."""

    def __init__(self, storage: JPVRStorage):
        if not isinstance(storage, JPVRStorage):
            raise WorkspaceError("storage must be JPVRStorage")
        self.storage = storage

    def _snapshot(self, max_pages):
        active, catalog = self.storage.load_snapshot(max_pages=max_pages)
        if active.generation == 0:
            raise WorkspaceNotFound("workspace has no checkpoint")
        commit = decode_commit_page(catalog[active.active_commit_page_no], expected_page_no=active.active_commit_page_no)
        manifest = decode_manifest_page(catalog[commit.record.manifest_ref.page_no], expected_page_no=commit.record.manifest_ref.page_no).manifest
        if not manifest.checkpoint_page_no or active.checkpoint_page_no != manifest.checkpoint_page_no:
            raise WorkspaceNotFound("workspace has no active checkpoint")
        page = decode_page(catalog[manifest.checkpoint_page_no], expected_page_no=manifest.checkpoint_page_no)
        if page.page_type is not PageType.CHECKPOINT:
            raise WorkspaceError("MANIFEST checkpoint pointer is not a CHECKPOINT page")
        desc = _decode_descriptor(page.payload)
        revision, object_id, kind, first, count, generation, length, checksum = desc
        if generation != page.commit_generation:
            raise WorkspaceError("workspace checkpoint generation mismatch")
        payload = self._read_payload(catalog, page, desc)
        decoded = decode_workspace(payload)
        if object_id != _object_id(decoded.workspace_id):
            raise WorkspaceError("workspace checkpoint identity mismatch")
        self._verify_source_commit(active, catalog, page, generation)
        return active, decoded, revision, payload

    def _read_payload(self, catalog, checkpoint, desc):
        revision, object_id, kind, first, count, generation, length, checksum = desc
        if kind == "DATA":
            page = decode_data_page(catalog[first], expected_page_no=first)
            if page.commit_generation != generation or page.transaction_id != checkpoint.transaction_id:
                raise WorkspaceError("workspace DATA transaction metadata mismatch")
            matches = [r for r in page.records if r.record_type == _RECORD_TYPE and r.schema_id == _SCHEMA_ID and r.schema_version == _SCHEMA_VERSION and r.object_id == object_id and r.record_version == revision]
            if len(matches) != 1:
                raise WorkspaceError("workspace DATA record metadata mismatch")
            payload = matches[0].payload
        else:
            pages = tuple(catalog[n] for n in range(first, first + count))
            blob = decode_blob_pages(pages, expected_first_page_no=first)
            if blob.commit_generation != generation or blob.transaction_id != checkpoint.transaction_id or blob.record_type != _RECORD_TYPE or blob.schema_id != _SCHEMA_ID or blob.schema_version != _SCHEMA_VERSION or blob.object_id != object_id or blob.record_version != revision or blob.page_count != count:
                raise WorkspaceError("workspace BLOB record metadata mismatch")
            payload = blob.payload
        if len(payload) != length or crc32c(payload) != checksum:
            raise WorkspaceError("workspace payload length or CRC mismatch")
        return payload

    @staticmethod
    def _verify_source_commit(active, catalog, checkpoint, expected_generation):
        page_no = active.active_commit_page_no
        generation = active.generation
        visited = set()
        while page_no:
            if page_no in visited:
                raise WorkspaceError("workspace COMMIT chain contains a cycle")
            visited.add(page_no)
            raw = catalog.get(page_no)
            if raw is None:
                raise WorkspaceError("workspace COMMIT history page is missing")
            commit = decode_commit_page(raw, expected_page_no=page_no)
            if commit.record.generation != generation:
                raise WorkspaceError("workspace COMMIT history is discontinuous")
            if generation == expected_generation:
                source_manifest_raw = catalog.get(commit.record.manifest_ref.page_no)
                if source_manifest_raw is None:
                    raise WorkspaceError("workspace source MANIFEST is missing")
                source_manifest = decode_manifest_page(
                    source_manifest_raw, expected_page_no=commit.record.manifest_ref.page_no
                )
                if (
                    commit.transaction_id != checkpoint.transaction_id
                    or source_manifest.transaction_id != commit.transaction_id
                    or source_manifest.manifest.checkpoint_page_no != checkpoint.page_no
                    or commit.record.durable_tail_page_no != page_no
                ):
                    raise WorkspaceError("workspace checkpoint is not published by its source COMMIT")
                return
            previous = commit.record.previous_commit_ref
            if previous is None or previous.expected_commit_generation != generation - 1:
                break
            page_no, generation = previous.page_no, generation - 1
        raise WorkspaceError("workspace checkpoint source COMMIT is not an ancestor of active state")

    def load(self, *, max_pages=4096):
        active, snapshot, revision, _ = self._snapshot(max_pages)
        return snapshot, revision, active

    def save(self, snapshot: WorkspaceSnapshot, *, expected_revision=None, max_pages=4096):
        if not isinstance(snapshot, WorkspaceSnapshot):
            raise WorkspaceError("snapshot must be WorkspaceSnapshot")
        payload = encode_workspace(snapshot)
        workspace_id = snapshot.workspace_id
        object_id = _object_id(workspace_id)
        try:
            active, catalog = self.storage.load_snapshot(max_pages=max_pages)
        except JPVRFormatError as exc:
            raise WorkspaceError(str(exc)) from exc
        if active.generation == 0:
            if max_pages < 9:
                raise WorkspaceError("initial JPVR INDEX setup requires max_pages >= 9")
            JPVRNeuronCheckpointStore(self.storage)._initialize_indexes(active, catalog)
            active, catalog = self.storage.load_snapshot(max_pages=max_pages)
        previous_revision = 0
        if active.generation > 1:
            try:
                current, previous_revision, _ = self.load(max_pages=max_pages)
            except WorkspaceNotFound as exc:
                raise WorkspaceConflict("JPVR file contains no workspace checkpoint") from exc
            if current.workspace_id != workspace_id:
                raise WorkspaceConflict("JPVR file belongs to another workspace_id")
        elif active.generation == 1:
            commit = decode_commit_page(catalog[active.active_commit_page_no], expected_page_no=active.active_commit_page_no)
            manifest = decode_manifest_page(catalog[commit.record.manifest_ref.page_no], expected_page_no=commit.record.manifest_ref.page_no).manifest
            if manifest.checkpoint_page_no:
                current, previous_revision, _ = self.load(max_pages=max_pages)
                if current.workspace_id != workspace_id:
                    raise WorkspaceConflict("JPVR file belongs to another workspace_id")
        if expected_revision is not None:
            if isinstance(expected_revision, bool) or not isinstance(expected_revision, int) or expected_revision != previous_revision:
                raise WorkspaceConflict(f"expected revision {expected_revision}, current revision is {previous_revision}")
        if previous_revision >= _U64_MAX:
            raise WorkspaceError("workspace revision space is exhausted")
        revision = previous_revision + 1
        generation = active.generation + 1
        transaction_id, commit_id, operation_id = _new_id(), _new_id(), _new_id()
        if len(payload) <= MAX_SMALL_RECORD_PAYLOAD:
            item = DataRecordInput(_RECORD_TYPE, _SCHEMA_ID, _SCHEMA_VERSION, object_id, revision, payload)
            base = TypedTransactionRequest(active, catalog, transaction_id, commit_id, operation_id, data_records=(item,))
            plan = build_typed_transaction(base)
            matches = []
            for page_no in plan.data_page_numbers:
                page = decode_data_page(dict(zip(plan.assembly.ordered_page_numbers, plan.assembly.ordered_page_bytes))[page_no], expected_page_no=page_no)
                if any(r.record_type == _RECORD_TYPE and r.object_id == object_id and r.record_version == revision for r in page.records):
                    matches.append(page_no)
            if len(matches) != 1:
                raise WorkspaceError("workspace DATA record did not resolve to one page")
            kind, first, count = "DATA", matches[0], 1
        else:
            item = BlobRecordInput(_RECORD_TYPE, _SCHEMA_ID, _SCHEMA_VERSION, object_id, revision, payload)
            base = TypedTransactionRequest(active, catalog, transaction_id, commit_id, operation_id, blob_records=(item,))
            plan = build_typed_transaction(base)
            numbers = plan.blob_page_numbers
            if not numbers or numbers != tuple(range(numbers[0], numbers[0] + len(numbers))):
                raise WorkspaceError("workspace BLOB pages are not contiguous")
            kind, first, count = "BLOB", numbers[0], len(numbers)
        descriptor = _encode_descriptor(revision, object_id, kind, first, count, generation, len(payload), crc32c(payload))
        final = TypedTransactionRequest(
            active, catalog, transaction_id, commit_id, operation_id,
            data_records=base.data_records, blob_records=base.blob_records,
            checkpoint_pages=(CheckpointPageInput(descriptor),),
        )
        final_plan = build_typed_transaction(final)
        if final_plan.commit_record.generation != generation or len(final_plan.checkpoint_page_numbers) != 1:
            raise WorkspaceError("workspace checkpoint transaction layout is invalid")
        if final_plan.new_superblock.durable_tail_page_no - 1 > max_pages:
            raise WorkspaceError("workspace exceeds configured JPVR page bound")
        published = self.storage.commit(final_plan)
        return WorkspaceCheckpoint(revision, first, count, kind, published.generation, final_plan.commit_page_no)
