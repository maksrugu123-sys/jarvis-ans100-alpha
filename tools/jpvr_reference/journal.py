"""Strict append-only JPVR v1 JOURNAL operation-state codec.

Each JOURNAL page stores one immutable operation-state entry. Revisions point
back to the preceding entry; this module does not provide a durable writer,
idempotency index, or recovery engine.
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import IntEnum
import struct

from .codec import (
    DecodedPage,
    JPVRFormatError,
    PageType,
    _fixed_bytes,
    _require_uint,
    decode_page,
    encode_page,
)
from .manifest import PageRef
from .records import TLVField, WireType, decode_tlv, encode_tlv


JOURNAL_MAGIC = b"JNRL"
JOURNAL_VERSION = 1
JOURNAL_PREFIX = struct.Struct("<4sHH")
JOURNAL_PREFIX_BYTES = JOURNAL_PREFIX.size


class OperationStatus(IntEnum):
    QUEUED = 1
    RUNNING = 2
    PREPARED = 3
    COMMITTED = 4
    COMPLETED = 5
    DEFERRED = 6
    REJECTED = 7
    FAILED = 8
    CANCELLED = 9
    COMMIT_UNKNOWN = 10


@dataclass(frozen=True)
class JournalEntry:
    operation_id: bytes
    idempotency_key: bytes
    owner_module_id: bytes
    status: OperationStatus
    expected_state_version: int | None = None
    new_state_version: int | None = None
    commit_id: bytes | None = None
    commit_ref: PageRef | None = None
    previous_entry_ref: PageRef | None = None
    result_object_id: bytes | None = None


@dataclass(frozen=True)
class DecodedJournalPage:
    page_no: int
    commit_generation: int
    transaction_id: bytes
    entry: JournalEntry


_JOURNAL_FIELD_TYPES = {
    1: WireType.ID128,  # operation_id
    2: WireType.ID128,  # idempotency_key
    3: WireType.ID128,  # owner_module_id
    4: WireType.U8,     # OperationStatus
    5: WireType.U64,    # expected_state_version
    6: WireType.U64,    # new_state_version
    7: WireType.ID128,  # commit_id
    8: WireType.BYTES,  # commit_ref
    9: WireType.BYTES,  # previous_entry_ref
    10: WireType.ID128, # result_object_id
}
_JOURNAL_REQUIRED_FIELDS = frozenset((1, 2, 3, 4))


def _status(value: object) -> OperationStatus:
    if isinstance(value, bool):
        raise JPVRFormatError("invalid JOURNAL operation status")
    try:
        return OperationStatus(value)
    except (TypeError, ValueError):
        raise JPVRFormatError("unknown JOURNAL operation status") from None


def _validate_page_ref(name: str, ref: PageRef) -> None:
    if not isinstance(ref, PageRef):
        raise JPVRFormatError(f"{name} must be a PageRef")
    _require_uint(f"{name}.page_no", ref.page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint(
        f"{name}.expected_commit_generation",
        ref.expected_commit_generation,
        0xFFFFFFFFFFFFFFFF,
    )
    if ref.page_no < 2 or ref.expected_commit_generation < 1:
        raise JPVRFormatError(f"{name} is invalid")


def _encode_page_ref(ref: PageRef) -> bytes:
    _validate_page_ref("PageRef", ref)
    return struct.pack("<QQ", ref.page_no, ref.expected_commit_generation)


def _decode_page_ref(raw: bytes, name: str) -> PageRef:
    if len(raw) != 16:
        raise JPVRFormatError(f"{name} must be exactly 16 bytes")
    page_no, generation = struct.unpack("<QQ", raw)
    ref = PageRef(page_no, generation)
    _validate_page_ref(name, ref)
    return ref


def _validate_entry(entry: JournalEntry) -> tuple[bytes, bytes, bytes]:
    if not isinstance(entry, JournalEntry):
        raise JPVRFormatError("entry must be a JournalEntry")
    operation_id = _fixed_bytes("operation_id", entry.operation_id, 16)
    idempotency_key = _fixed_bytes("idempotency_key", entry.idempotency_key, 16)
    owner_module_id = _fixed_bytes("owner_module_id", entry.owner_module_id, 16)
    if any(value == bytes(16) for value in (operation_id, idempotency_key, owner_module_id)):
        raise JPVRFormatError("operation_id, idempotency_key and owner_module_id must be nonzero")
    if not isinstance(entry.status, OperationStatus):
        raise JPVRFormatError("status must be an OperationStatus")
    status = entry.status

    if entry.expected_state_version is not None:
        _require_uint(
            "expected_state_version", entry.expected_state_version,
            0xFFFFFFFFFFFFFFFF,
        )
    if entry.new_state_version is not None:
        _require_uint(
            "new_state_version", entry.new_state_version,
            0xFFFFFFFFFFFFFFFF,
        )
        if entry.expected_state_version is None:
            raise JPVRFormatError("new_state_version requires expected_state_version")
        if entry.new_state_version <= entry.expected_state_version:
            raise JPVRFormatError("new_state_version must exceed expected_state_version")

    if (entry.commit_id is None) != (entry.commit_ref is None):
        raise JPVRFormatError("commit_id and commit_ref must be supplied together")
    if entry.commit_id is not None:
        commit_id = _fixed_bytes("commit_id", entry.commit_id, 16)
        if commit_id == bytes(16):
            raise JPVRFormatError("commit_id must be nonzero")
        _validate_page_ref("commit_ref", entry.commit_ref)
    elif status in (OperationStatus.COMMITTED, OperationStatus.COMPLETED):
        raise JPVRFormatError("COMMITTED and COMPLETED entries require commit_id and commit_ref")

    if entry.previous_entry_ref is not None:
        _validate_page_ref("previous_entry_ref", entry.previous_entry_ref)

    if entry.result_object_id is not None:
        result_object_id = _fixed_bytes("result_object_id", entry.result_object_id, 16)
        if result_object_id == bytes(16):
            raise JPVRFormatError("result_object_id must be nonzero")
        if status not in (
            OperationStatus.COMPLETED,
            OperationStatus.REJECTED,
            OperationStatus.FAILED,
        ):
            raise JPVRFormatError("result_object_id is only valid for a terminal result")

    if status not in (
        OperationStatus.COMMITTED,
        OperationStatus.COMPLETED,
        OperationStatus.COMMIT_UNKNOWN,
    ) and entry.commit_id is not None:
        raise JPVRFormatError("this operation status cannot carry a commit reference")

    return operation_id, idempotency_key, owner_module_id


def encode_journal_payload(entry: JournalEntry) -> bytes:
    """Encode one JNRL v1 operation-state entry using deterministic TLV."""
    _validate_entry(entry)
    fields = [
        TLVField(1, WireType.ID128, bytes(entry.operation_id)),
        TLVField(2, WireType.ID128, bytes(entry.idempotency_key)),
        TLVField(3, WireType.ID128, bytes(entry.owner_module_id)),
        TLVField(4, WireType.U8, int(_status(entry.status))),
    ]
    if entry.expected_state_version is not None:
        fields.append(TLVField(5, WireType.U64, entry.expected_state_version))
    if entry.new_state_version is not None:
        fields.append(TLVField(6, WireType.U64, entry.new_state_version))
    if entry.commit_id is not None:
        fields.extend((
            TLVField(7, WireType.ID128, bytes(entry.commit_id)),
            TLVField(8, WireType.BYTES, _encode_page_ref(entry.commit_ref)),
        ))
    if entry.previous_entry_ref is not None:
        fields.append(TLVField(9, WireType.BYTES, _encode_page_ref(entry.previous_entry_ref)))
    if entry.result_object_id is not None:
        fields.append(TLVField(10, WireType.ID128, bytes(entry.result_object_id)))
    return JOURNAL_PREFIX.pack(
        JOURNAL_MAGIC, JOURNAL_VERSION, JOURNAL_PREFIX_BYTES
    ) + encode_tlv(fields)


def decode_journal_payload(payload: bytes | bytearray | memoryview) -> JournalEntry:
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("JOURNAL payload must be bytes-like")
    raw = bytes(payload)
    if len(raw) < JOURNAL_PREFIX_BYTES or len(raw) > 4032:
        raise JPVRFormatError("invalid JOURNAL payload length")
    magic, version, prefix_bytes = JOURNAL_PREFIX.unpack_from(raw)
    if magic != JOURNAL_MAGIC or version != JOURNAL_VERSION:
        raise JPVRFormatError("invalid JOURNAL magic or version")
    if prefix_bytes != JOURNAL_PREFIX_BYTES:
        raise JPVRFormatError("invalid JOURNAL prefix size")

    fields = decode_tlv(raw[prefix_bytes:], known_fields=_JOURNAL_FIELD_TYPES)
    by_id = {field.field_id: field for field in fields}
    if not _JOURNAL_REQUIRED_FIELDS.issubset(by_id):
        raise JPVRFormatError("JOURNAL entry is missing required fields")
    if any(field.optional for field in fields):
        raise JPVRFormatError("known JOURNAL fields must not use the OPTIONAL flag")

    commit_id = by_id[7].value if 7 in by_id else None
    commit_ref = (
        _decode_page_ref(by_id[8].value, "commit_ref") if 8 in by_id else None
    )
    previous_ref = (
        _decode_page_ref(by_id[9].value, "previous_entry_ref")
        if 9 in by_id else None
    )
    if (7 in by_id) != (8 in by_id):
        raise JPVRFormatError("commit_id and commit_ref must be supplied together")

    entry = JournalEntry(
        operation_id=by_id[1].value,
        idempotency_key=by_id[2].value,
        owner_module_id=by_id[3].value,
        status=_status(by_id[4].value),
        expected_state_version=by_id[5].value if 5 in by_id else None,
        new_state_version=by_id[6].value if 6 in by_id else None,
        commit_id=commit_id,
        commit_ref=commit_ref,
        previous_entry_ref=previous_ref,
        result_object_id=by_id[10].value if 10 in by_id else None,
    )
    _validate_entry(entry)
    return entry


def _validate_entry_page_refs(
    entry: JournalEntry,
    page_no: int,
    commit_generation: int,
) -> None:
    _validate_entry(entry)
    _require_uint("page_no", page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("commit_generation", commit_generation, 0xFFFFFFFFFFFFFFFF)
    if page_no < 2 or commit_generation < 1:
        raise JPVRFormatError("invalid JOURNAL page number or generation")

    previous = entry.previous_entry_ref
    if previous is not None:
        if previous.page_no >= page_no:
            raise JPVRFormatError("previous JOURNAL entry must precede this page")
        if previous.expected_commit_generation > commit_generation:
            raise JPVRFormatError("previous JOURNAL entry cannot be newer than this page")

    commit_ref = entry.commit_ref
    if commit_ref is not None:
        if commit_ref.expected_commit_generation > commit_generation:
            raise JPVRFormatError("referenced COMMIT cannot be newer than JOURNAL entry")
        if commit_ref.expected_commit_generation == commit_generation:
            if commit_ref.page_no <= page_no:
                raise JPVRFormatError("same-generation COMMIT must follow JOURNAL page")
        elif commit_ref.page_no >= page_no:
            raise JPVRFormatError("older COMMIT must precede JOURNAL page")


def encode_journal_page(
    entry: JournalEntry,
    page_no: int,
    commit_generation: int,
    transaction_id: bytes,
) -> bytes:
    """Encode one JOURNAL page; entry links are checked against its physical order."""
    _validate_entry_page_refs(entry, page_no, commit_generation)
    tx_id = _fixed_bytes("transaction_id", transaction_id, 16)
    if tx_id == bytes(16):
        raise JPVRFormatError("JOURNAL transaction_id must be nonzero")
    return encode_page(
        PageType.JOURNAL,
        page_no,
        commit_generation,
        tx_id,
        encode_journal_payload(entry),
    )


def decode_journal_page(
    page_bytes: bytes | bytearray | memoryview,
    *,
    expected_page_no: int | None = None,
) -> DecodedJournalPage:
    page: DecodedPage = decode_page(page_bytes, expected_page_no=expected_page_no)
    if page.page_type is not PageType.JOURNAL:
        raise JPVRFormatError("page type is not JOURNAL")
    if page.transaction_id == bytes(16):
        raise JPVRFormatError("JOURNAL transaction_id must be nonzero")
    entry = decode_journal_payload(page.payload)
    _validate_entry_page_refs(entry, page.page_no, page.commit_generation)
    return DecodedJournalPage(
        page_no=page.page_no,
        commit_generation=page.commit_generation,
        transaction_id=page.transaction_id,
        entry=entry,
    )


_ALLOWED_TRANSITIONS = {
    OperationStatus.DEFERRED: frozenset((OperationStatus.QUEUED, OperationStatus.REJECTED)),
    OperationStatus.QUEUED: frozenset((
        OperationStatus.RUNNING,
        OperationStatus.DEFERRED,
        OperationStatus.REJECTED,
        OperationStatus.FAILED,
        OperationStatus.CANCELLED,
    )),
    OperationStatus.RUNNING: frozenset((
        OperationStatus.PREPARED,
        OperationStatus.REJECTED,
        OperationStatus.FAILED,
        OperationStatus.CANCELLED,
    )),
    OperationStatus.PREPARED: frozenset((
        OperationStatus.COMMITTED,
        OperationStatus.FAILED,
        OperationStatus.CANCELLED,
        OperationStatus.COMMIT_UNKNOWN,
    )),
    OperationStatus.COMMIT_UNKNOWN: frozenset((
        OperationStatus.COMMITTED,
        OperationStatus.FAILED,
    )),
    OperationStatus.COMMITTED: frozenset((OperationStatus.COMPLETED,)),
    OperationStatus.COMPLETED: frozenset(),
    OperationStatus.REJECTED: frozenset(),
    OperationStatus.FAILED: frozenset(),
    OperationStatus.CANCELLED: frozenset(),
}


def validate_journal_transition(
    previous: DecodedJournalPage,
    current: DecodedJournalPage,
) -> None:
    """Validate one append-only state transition for the same logical operation."""
    if not isinstance(previous, DecodedJournalPage) or not isinstance(current, DecodedJournalPage):
        raise JPVRFormatError("journal transition requires decoded JOURNAL pages")
    previous_tx_id = _fixed_bytes("previous.transaction_id", previous.transaction_id, 16)
    current_tx_id = _fixed_bytes("current.transaction_id", current.transaction_id, 16)
    if previous_tx_id == bytes(16) or current_tx_id == bytes(16):
        raise JPVRFormatError("JOURNAL transaction_id must be nonzero")
    if current.page_no <= previous.page_no:
        raise JPVRFormatError("JOURNAL revisions must be appended in page order")
    if current.commit_generation < previous.commit_generation:
        raise JPVRFormatError("JOURNAL generation cannot move backwards")
    _validate_entry_page_refs(
        previous.entry, previous.page_no, previous.commit_generation
    )
    _validate_entry_page_refs(
        current.entry, current.page_no, current.commit_generation
    )
    if current.entry.previous_entry_ref != PageRef(
        previous.page_no, previous.commit_generation
    ):
        raise JPVRFormatError("current JOURNAL entry does not reference previous entry")
    if (
        current.entry.operation_id != previous.entry.operation_id
        or current.entry.idempotency_key != previous.entry.idempotency_key
        or current.entry.owner_module_id != previous.entry.owner_module_id
    ):
        raise JPVRFormatError("JOURNAL operation identity cannot change")
    if current.entry.expected_state_version != previous.entry.expected_state_version:
        raise JPVRFormatError("expected_state_version cannot change during an operation")
    if (
        previous.entry.new_state_version is not None
        and current.entry.new_state_version != previous.entry.new_state_version
    ):
        raise JPVRFormatError("new_state_version cannot change after it is assigned")
    if previous.entry.commit_id is not None:
        if (
            current.entry.commit_id != previous.entry.commit_id
            or current.entry.commit_ref != previous.entry.commit_ref
        ):
            raise JPVRFormatError("commit identity cannot change after it is assigned")
    if (
        previous.entry.result_object_id is not None
        and current.entry.result_object_id != previous.entry.result_object_id
    ):
        raise JPVRFormatError("result_object_id cannot change after it is assigned")
    if current.commit_generation == previous.commit_generation:
        if current.transaction_id != previous.transaction_id:
            raise JPVRFormatError("same-generation JOURNAL pages must share transaction_id")
    elif current.transaction_id == previous.transaction_id:
        raise JPVRFormatError("new-generation JOURNAL pages must use a new transaction_id")
    allowed = _ALLOWED_TRANSITIONS[previous.entry.status]
    if current.entry.status not in allowed:
        raise JPVRFormatError(
            f"invalid JOURNAL status transition: {previous.entry.status.name} "
            f"to {current.entry.status.name}"
        )
