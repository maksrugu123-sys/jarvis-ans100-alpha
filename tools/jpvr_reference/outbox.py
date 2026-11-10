"""Strict JPVR v1 immutable OUTBOX event codec and sequence validator.

This module describes events committed atomically with state. It does not
publish events, persist subscriber acknowledgements, or provide a durable
outbox worker/recovery engine.
"""
from __future__ import annotations

from dataclasses import dataclass
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


OUTBOX_MAGIC = b"JOUT"
OUTBOX_VERSION = 1
OUTBOX_PREFIX = struct.Struct("<4sHH")
OUTBOX_PREFIX_BYTES = OUTBOX_PREFIX.size
MAX_OUTBOX_EVENT_TYPE_BYTES = 128
MAX_OUTBOX_EVENT_PAYLOAD_BYTES = 3500

_OUTBOX_FIELD_TYPES = {
    1: WireType.ID128,  # event_id
    2: WireType.ID128,  # idempotency_key
    3: WireType.ID128,  # operation_id
    4: WireType.UTF8,   # event_type
    5: WireType.BYTES,  # event_payload
    6: WireType.U64,    # sequence
    7: WireType.ID128,  # commit_id
    8: WireType.BYTES,  # commit_ref
    9: WireType.BYTES,  # previous_event_ref
}


@dataclass(frozen=True)
class OutboxEvent:
    event_id: bytes
    idempotency_key: bytes
    operation_id: bytes
    event_type: str
    event_payload: bytes
    sequence: int
    commit_id: bytes
    commit_ref: PageRef
    previous_event_ref: PageRef | None = None


@dataclass(frozen=True)
class DecodedOutboxPage:
    page_no: int
    commit_generation: int
    transaction_id: bytes
    event: OutboxEvent


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


def _validate_event(event: OutboxEvent) -> tuple[bytes, bytes, bytes, bytes]:
    if not isinstance(event, OutboxEvent):
        raise JPVRFormatError("event must be an OutboxEvent")
    event_id = _fixed_bytes("event_id", event.event_id, 16)
    idempotency_key = _fixed_bytes("idempotency_key", event.idempotency_key, 16)
    operation_id = _fixed_bytes("operation_id", event.operation_id, 16)
    commit_id = _fixed_bytes("commit_id", event.commit_id, 16)
    if any(value == bytes(16) for value in (
        event_id, idempotency_key, operation_id, commit_id
    )):
        raise JPVRFormatError(
            "event_id, idempotency_key, operation_id and commit_id must be nonzero"
        )
    if not isinstance(event.event_type, str) or not event.event_type:
        raise JPVRFormatError("event_type must be a non-empty string")
    try:
        event_type = event.event_type.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise JPVRFormatError("event_type contains an invalid Unicode scalar") from exc
    if len(event_type) > MAX_OUTBOX_EVENT_TYPE_BYTES:
        raise JPVRFormatError("event_type exceeds 128 UTF-8 bytes")
    if not isinstance(event.event_payload, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("event_payload must be bytes-like")
    payload = bytes(event.event_payload)
    if len(payload) > MAX_OUTBOX_EVENT_PAYLOAD_BYTES:
        raise JPVRFormatError("event_payload exceeds 3500 bytes")
    _require_uint("sequence", event.sequence, 0xFFFFFFFFFFFFFFFF)
    if event.sequence < 1:
        raise JPVRFormatError("OUTBOX sequence must be positive")
    _validate_page_ref("commit_ref", event.commit_ref)
    if event.previous_event_ref is not None:
        _validate_page_ref("previous_event_ref", event.previous_event_ref)
    return event_id, idempotency_key, operation_id, commit_id


def encode_outbox_payload(event: OutboxEvent) -> bytes:
    """Encode one immutable event as deterministic JOUT v1 TLV."""
    event_id, idempotency_key, operation_id, commit_id = _validate_event(event)
    fields = [
        TLVField(1, WireType.ID128, event_id),
        TLVField(2, WireType.ID128, idempotency_key),
        TLVField(3, WireType.ID128, operation_id),
        TLVField(4, WireType.UTF8, event.event_type),
        TLVField(5, WireType.BYTES, bytes(event.event_payload)),
        TLVField(6, WireType.U64, event.sequence),
        TLVField(7, WireType.ID128, commit_id),
        TLVField(8, WireType.BYTES, _encode_page_ref(event.commit_ref)),
    ]
    if event.previous_event_ref is not None:
        fields.append(TLVField(
            9, WireType.BYTES, _encode_page_ref(event.previous_event_ref)
        ))
    raw = OUTBOX_PREFIX.pack(
        OUTBOX_MAGIC, OUTBOX_VERSION, OUTBOX_PREFIX_BYTES
    ) + encode_tlv(fields)
    if len(raw) > 4032:
        raise JPVRFormatError("OUTBOX payload exceeds one page")
    return raw


def decode_outbox_payload(payload: bytes | bytearray | memoryview) -> OutboxEvent:
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("OUTBOX payload must be bytes-like")
    raw = bytes(payload)
    if len(raw) < OUTBOX_PREFIX_BYTES or len(raw) > 4032:
        raise JPVRFormatError("invalid OUTBOX payload length")
    magic, version, prefix_bytes = OUTBOX_PREFIX.unpack_from(raw)
    if magic != OUTBOX_MAGIC or version != OUTBOX_VERSION:
        raise JPVRFormatError("invalid OUTBOX magic or version")
    if prefix_bytes != OUTBOX_PREFIX_BYTES:
        raise JPVRFormatError("invalid OUTBOX prefix size")
    fields = decode_tlv(raw[prefix_bytes:], known_fields=_OUTBOX_FIELD_TYPES)
    by_id = {field.field_id: field for field in fields}
    if not set(range(1, 9)).issubset(by_id):
        raise JPVRFormatError("OUTBOX event is missing required fields")
    if any(field.optional for field in fields):
        raise JPVRFormatError("known OUTBOX fields must not use the OPTIONAL flag")
    event = OutboxEvent(
        event_id=by_id[1].value,
        idempotency_key=by_id[2].value,
        operation_id=by_id[3].value,
        event_type=by_id[4].value,
        event_payload=by_id[5].value,
        sequence=by_id[6].value,
        commit_id=by_id[7].value,
        commit_ref=_decode_page_ref(by_id[8].value, "commit_ref"),
        previous_event_ref=(
            _decode_page_ref(by_id[9].value, "previous_event_ref")
            if 9 in by_id else None
        ),
    )
    _validate_event(event)
    return event


def _validate_event_page_refs(
    event: OutboxEvent,
    page_no: int,
    commit_generation: int,
) -> None:
    _validate_event(event)
    _require_uint("page_no", page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("commit_generation", commit_generation, 0xFFFFFFFFFFFFFFFF)
    if page_no < 2 or commit_generation < 1:
        raise JPVRFormatError("invalid OUTBOX page number or generation")

    previous = event.previous_event_ref
    if previous is not None:
        if previous.page_no >= page_no:
            raise JPVRFormatError("previous OUTBOX event must precede this page")
        if previous.expected_commit_generation > commit_generation:
            raise JPVRFormatError("previous OUTBOX event cannot be newer than this page")

    commit_ref = event.commit_ref
    if commit_ref.expected_commit_generation != commit_generation:
        raise JPVRFormatError("OUTBOX event must reference its own commit generation")
    if commit_ref.page_no <= page_no:
        raise JPVRFormatError("OUTBOX source COMMIT must follow the event page")


def encode_outbox_page(
    event: OutboxEvent,
    page_no: int,
    commit_generation: int,
    transaction_id: bytes,
) -> bytes:
    """Encode one OUTBOX event page before its source transaction's COMMIT."""
    _validate_event_page_refs(event, page_no, commit_generation)
    tx_id = _fixed_bytes("transaction_id", transaction_id, 16)
    if tx_id == bytes(16):
        raise JPVRFormatError("OUTBOX transaction_id must be nonzero")
    return encode_page(
        PageType.OUTBOX,
        page_no,
        commit_generation,
        tx_id,
        encode_outbox_payload(event),
    )


def decode_outbox_page(
    page_bytes: bytes | bytearray | memoryview,
    *,
    expected_page_no: int | None = None,
) -> DecodedOutboxPage:
    page: DecodedPage = decode_page(page_bytes, expected_page_no=expected_page_no)
    if page.page_type is not PageType.OUTBOX:
        raise JPVRFormatError("page type is not OUTBOX")
    if page.transaction_id == bytes(16):
        raise JPVRFormatError("OUTBOX transaction_id must be nonzero")
    event = decode_outbox_payload(page.payload)
    _validate_event_page_refs(event, page.page_no, page.commit_generation)
    return DecodedOutboxPage(
        page_no=page.page_no,
        commit_generation=page.commit_generation,
        transaction_id=page.transaction_id,
        event=event,
    )


def validate_outbox_sequence(
    previous: DecodedOutboxPage,
    current: DecodedOutboxPage,
) -> None:
    """Validate adjacent immutable events in the container-wide sequence."""
    if not isinstance(previous, DecodedOutboxPage) or not isinstance(
        current, DecodedOutboxPage
    ):
        raise JPVRFormatError("OUTBOX sequence values must be decoded OUTBOX pages")
    for label, page in (("previous", previous), ("current", current)):
        _validate_event_page_refs(
            page.event, page.page_no, page.commit_generation
        )
        transaction_id = _fixed_bytes(f"{label}.transaction_id", page.transaction_id, 16)
        if transaction_id == bytes(16):
            raise JPVRFormatError("OUTBOX transaction_id must be nonzero")
    if current.page_no <= previous.page_no:
        raise JPVRFormatError("OUTBOX pages must be strictly append ordered")
    if current.commit_generation < previous.commit_generation:
        raise JPVRFormatError("OUTBOX commit generation cannot regress")
    if current.event.previous_event_ref != PageRef(
        previous.page_no, previous.commit_generation
    ):
        raise JPVRFormatError("OUTBOX event does not reference the previous sequence entry")
    if current.event.sequence != previous.event.sequence + 1:
        raise JPVRFormatError("OUTBOX sequence must increase by exactly one")
    if current.event.event_id == previous.event.event_id:
        raise JPVRFormatError("OUTBOX event_id must be unique in adjacent entries")
    if current.event.idempotency_key == previous.event.idempotency_key:
        raise JPVRFormatError("OUTBOX idempotency_key must be unique in adjacent entries")
    if current.commit_generation == previous.commit_generation:
        if current.transaction_id != previous.transaction_id:
            raise JPVRFormatError("same-generation OUTBOX pages must share transaction_id")
        if current.event.commit_ref != previous.event.commit_ref:
            raise JPVRFormatError("same-generation OUTBOX events must share source COMMIT")
        if current.event.commit_id != previous.event.commit_id:
            raise JPVRFormatError("same-generation OUTBOX events must share commit_id")
    else:
        if previous.event.commit_ref.page_no >= current.page_no:
            raise JPVRFormatError("previous generation COMMIT must precede the next OUTBOX page")
        if current.transaction_id == previous.transaction_id:
            raise JPVRFormatError("new-generation OUTBOX page must use a new transaction_id")
        if current.event.commit_id == previous.event.commit_id:
            raise JPVRFormatError("new-generation OUTBOX events must use a new commit_id")
