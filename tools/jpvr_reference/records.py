"""JPVR_FORMAT_V1 small-record and deterministic TLV reference codec."""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import IntEnum
import math
import struct
from typing import Any

from .codec import (
    PAGE_PAYLOAD_BYTES,
    JPVRFormatError,
    _fixed_bytes,
    _require_uint,
    crc32c,
)


RECORD_MAGIC = b"JREC"
RECORD_HEADER_BYTES = 56
MAX_SMALL_RECORD_PAYLOAD = PAGE_PAYLOAD_BYTES - RECORD_HEADER_BYTES
TLV_OPTIONAL_FLAG = 0x01


class WireType(IntEnum):
    U8 = 1
    U16 = 2
    U32 = 3
    U64 = 4
    I64 = 5
    BOOL = 6
    F32 = 7
    F64 = 8
    ID128 = 9
    BYTES = 10
    UTF8 = 11


@dataclass(frozen=True)
class TLVField:
    field_id: int
    wire_type: WireType
    value: Any
    optional: bool = False


@dataclass(frozen=True)
class DecodedRecord:
    record_type: int
    flags: int
    schema_id: int
    schema_version: int
    object_id: bytes
    record_version: int
    payload: bytes
    next_offset: int


def _coerce_wire_type(value: Any) -> WireType:
    if isinstance(value, bool):
        raise JPVRFormatError("invalid TLV wire type")
    try:
        return WireType(value)
    except (ValueError, TypeError):
        raise JPVRFormatError("unknown TLV wire type") from None


def _encode_tlv_value(wire_type: WireType, value: Any) -> bytes:
    if wire_type is WireType.U8:
        return struct.pack("<B", _require_uint("U8", value, 0xFF))
    if wire_type is WireType.U16:
        return struct.pack("<H", _require_uint("U16", value, 0xFFFF))
    if wire_type is WireType.U32:
        return struct.pack("<I", _require_uint("U32", value, 0xFFFFFFFF))
    if wire_type is WireType.U64:
        return struct.pack("<Q", _require_uint("U64", value, 0xFFFFFFFFFFFFFFFF))
    if wire_type is WireType.I64:
        if isinstance(value, bool) or not isinstance(value, int) or not -(1 << 63) <= value < (1 << 63):
            raise JPVRFormatError("I64 must be a signed 64-bit integer")
        return struct.pack("<q", value)
    if wire_type is WireType.BOOL:
        if not isinstance(value, bool):
            raise JPVRFormatError("BOOL must be a bool")
        return b"\x01" if value else b"\x00"
    if wire_type in (WireType.F32, WireType.F64):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise JPVRFormatError("floating TLV values must be numeric")
        try:
            number = float(value)
        except (OverflowError, ValueError) as exc:
            raise JPVRFormatError("floating TLV value is outside the wire range") from exc
        if not math.isfinite(number):
            raise JPVRFormatError("NaN and infinity are forbidden in JPVR")
        if number == 0.0:
            number = 0.0  # Canonicalize negative zero to positive zero.
        try:
            return struct.pack("<f" if wire_type is WireType.F32 else "<d", number)
        except (OverflowError, struct.error) as exc:
            raise JPVRFormatError("floating TLV value is outside the wire range") from exc
    if wire_type is WireType.ID128:
        return _fixed_bytes("ID128", value, 16)
    if wire_type is WireType.BYTES:
        if not isinstance(value, (bytes, bytearray, memoryview)):
            raise JPVRFormatError("BYTES value must be bytes-like")
        return bytes(value)
    if wire_type is WireType.UTF8:
        if not isinstance(value, str):
            raise JPVRFormatError("UTF8 value must be a string")
        try:
            return value.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise JPVRFormatError("UTF8 value contains an invalid Unicode scalar") from exc
    raise JPVRFormatError("unsupported TLV wire type")


def _decode_tlv_value(wire_type: WireType, raw: bytes) -> Any:
    fixed_lengths = {
        WireType.U8: 1,
        WireType.U16: 2,
        WireType.U32: 4,
        WireType.U64: 8,
        WireType.I64: 8,
        WireType.BOOL: 1,
        WireType.F32: 4,
        WireType.F64: 8,
        WireType.ID128: 16,
    }
    expected = fixed_lengths.get(wire_type)
    if expected is not None and len(raw) != expected:
        raise JPVRFormatError(f"{wire_type.name} has an invalid encoded length")
    if wire_type is WireType.U8:
        return struct.unpack("<B", raw)[0]
    if wire_type is WireType.U16:
        return struct.unpack("<H", raw)[0]
    if wire_type is WireType.U32:
        return struct.unpack("<I", raw)[0]
    if wire_type is WireType.U64:
        return struct.unpack("<Q", raw)[0]
    if wire_type is WireType.I64:
        return struct.unpack("<q", raw)[0]
    if wire_type is WireType.BOOL:
        if raw[0] not in (0, 1):
            raise JPVRFormatError("BOOL wire value must be 0 or 1")
        return raw[0] == 1
    if wire_type in (WireType.F32, WireType.F64):
        number = struct.unpack("<f" if wire_type is WireType.F32 else "<d", raw)[0]
        if not math.isfinite(number):
            raise JPVRFormatError("non-finite floating value in JPVR")
        if number == 0.0 and math.copysign(1.0, number) < 0:
            raise JPVRFormatError("negative zero is not canonical in JPVR")
        return number
    if wire_type is WireType.ID128:
        return raw
    if wire_type is WireType.BYTES:
        return raw
    if wire_type is WireType.UTF8:
        try:
            return raw.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise JPVRFormatError("invalid UTF8 TLV value") from exc
    raise JPVRFormatError("unsupported TLV wire type")


def encode_tlv(fields: tuple[TLVField, ...] | list[TLVField]) -> bytes:
    """Encode sorted, unique TLV fields with 8-byte zero alignment."""
    if not isinstance(fields, (tuple, list)):
        raise JPVRFormatError("TLV fields must be a tuple or list")
    normalized = []
    seen: set[int] = set()
    for field in fields:
        if not isinstance(field, TLVField):
            raise JPVRFormatError("TLV entries must be TLVField records")
        field_id = _require_uint("field_id", field.field_id, 0xFFFF)
        if field_id == 0 or field_id in seen:
            raise JPVRFormatError("TLV field IDs must be nonzero and unique")
        if not isinstance(field.optional, bool):
            raise JPVRFormatError("TLV optional flag must be bool")
        wire_type = _coerce_wire_type(field.wire_type)
        value = _encode_tlv_value(wire_type, field.value)
        seen.add(field_id)
        normalized.append((field_id, wire_type, field.optional, value))

    normalized.sort(key=lambda item: item[0])
    output = bytearray()
    for field_id, wire_type, optional, value in normalized:
        flags = TLV_OPTIONAL_FLAG if optional else 0
        output.extend(struct.pack("<HBBI", field_id, int(wire_type), flags, len(value)))
        output.extend(value)
        padding = (- (8 + len(value))) % 8
        if padding:
            output.extend(bytes(padding))
    return bytes(output)


def decode_tlv(
    data: bytes | bytearray | memoryview,
    *,
    known_fields: Mapping[int, WireType] | None = None,
) -> tuple[TLVField, ...]:
    """Decode TLV fields; unknown fields require OPTIONAL when a schema map is supplied."""
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("TLV input must be bytes-like")
    raw = bytes(data)
    if len(raw) % 8:
        raise JPVRFormatError("TLV payload length must be 8-byte aligned")
    if known_fields is not None and not isinstance(known_fields, Mapping):
        raise JPVRFormatError("known_fields must be a mapping")

    result = []
    offset = 0
    previous_field_id = 0
    while offset < len(raw):
        if len(raw) - offset < 8:
            raise JPVRFormatError("truncated TLV header")
        field_id, wire_type_value, flags, value_length = struct.unpack_from("<HBBI", raw, offset)
        if field_id == 0 or field_id <= previous_field_id:
            raise JPVRFormatError("TLV field IDs must be strictly increasing")
        if flags & ~TLV_OPTIONAL_FLAG:
            raise JPVRFormatError("unknown TLV flags")
        wire_type = _coerce_wire_type(wire_type_value)
        value_start = offset + 8
        value_end = value_start + value_length
        padded_end = offset + ((8 + value_length + 7) & ~7)
        if value_end > len(raw) or padded_end > len(raw):
            raise JPVRFormatError("TLV value exceeds payload bounds")
        value_raw = raw[value_start:value_end]
        if any(raw[value_end:padded_end]):
            raise JPVRFormatError("TLV alignment padding must be zero")
        value = _decode_tlv_value(wire_type, value_raw)
        optional = bool(flags & TLV_OPTIONAL_FLAG)

        if known_fields is not None:
            expected_type = known_fields.get(field_id)
            if expected_type is None:
                if not optional:
                    raise JPVRFormatError("unknown mandatory TLV field")
            elif _coerce_wire_type(expected_type) is not wire_type:
                raise JPVRFormatError("TLV wire type does not match schema")
            else:
                result.append(TLVField(field_id, wire_type, value, optional))
        else:
            result.append(TLVField(field_id, wire_type, value, optional))

        previous_field_id = field_id
        offset = padded_end
    return tuple(result)


def encode_record(
    record_type: int,
    schema_id: int,
    schema_version: int,
    object_id: bytes,
    record_version: int,
    payload: bytes | bytearray | memoryview,
    *,
    flags: int = 0,
) -> bytes:
    """Encode one JREC record and its zero padding, without crossing a DATA page."""
    _require_uint("record_type", record_type, 0xFFFF)
    _require_uint("flags", flags, 0xFFFF)
    _require_uint("schema_id", schema_id, 0xFFFFFFFF)
    _require_uint("schema_version", schema_version, 0xFFFF)
    _require_uint("record_version", record_version, 0xFFFFFFFFFFFFFFFF)
    if record_type == 0 or schema_id == 0 or schema_version == 0 or record_version == 0:
        raise JPVRFormatError("record type/schema/version fields must be nonzero")
    if flags != 0:
        raise JPVRFormatError("unknown record flags are not supported")
    object_bytes = _fixed_bytes("object_id", object_id, 16)
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("record payload must be bytes-like")
    payload_bytes = bytes(payload)
    if len(payload_bytes) > MAX_SMALL_RECORD_PAYLOAD:
        raise JPVRFormatError("small record payload exceeds 3976 bytes")

    padding = (-(RECORD_HEADER_BYTES + len(payload_bytes))) % 8
    total_length = RECORD_HEADER_BYTES + len(payload_bytes) + padding
    if total_length > PAGE_PAYLOAD_BYTES:
        raise JPVRFormatError("record cannot cross a DATA page")
    raw = bytearray(total_length)
    raw[RECORD_HEADER_BYTES:RECORD_HEADER_BYTES + len(payload_bytes)] = payload_bytes
    struct.pack_into(
        "<4sHHIHH16sQIIII",
        raw,
        0,
        RECORD_MAGIC,
        record_type,
        flags,
        schema_id,
        schema_version,
        RECORD_HEADER_BYTES,
        object_bytes,
        record_version,
        len(payload_bytes),
        crc32c(payload_bytes),
        0,
        0,
    )
    struct.pack_into("<I", raw, 48, crc32c(raw[:RECORD_HEADER_BYTES]))
    return bytes(raw)


def decode_record(
    data: bytes | bytearray | memoryview,
    *,
    offset: int = 0,
) -> DecodedRecord:
    """Decode one JREC at an 8-byte-aligned offset in a DATA page payload."""
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("record input must be bytes-like")
    raw = bytes(data)
    if len(raw) > PAGE_PAYLOAD_BYTES:
        raise JPVRFormatError("DATA page payload cannot exceed 4032 bytes")
    _require_uint("record offset", offset, PAGE_PAYLOAD_BYTES)
    if offset % 8:
        raise JPVRFormatError("record offset must be 8-byte aligned")
    if offset + RECORD_HEADER_BYTES > len(raw):
        raise JPVRFormatError("truncated JREC header")

    (
        magic, record_type, flags, schema_id, schema_version, header_bytes,
        object_id, record_version, payload_length, payload_crc, header_crc,
        reserved,
    ) = struct.unpack_from("<4sHHIHH16sQIIII", raw, offset)
    if magic != RECORD_MAGIC:
        raise JPVRFormatError("invalid JREC magic")
    if reserved != 0:
        raise JPVRFormatError("JREC reserved field must be zero")
    if (record_type == 0 or schema_id == 0 or schema_version == 0
            or record_version == 0 or flags != 0 or header_bytes != RECORD_HEADER_BYTES):
        raise JPVRFormatError("invalid JREC header fields")
    if payload_length > MAX_SMALL_RECORD_PAYLOAD:
        raise JPVRFormatError("JREC payload exceeds 3976 bytes")
    value_start = offset + RECORD_HEADER_BYTES
    value_end = value_start + payload_length
    next_offset = offset + ((RECORD_HEADER_BYTES + payload_length + 7) & ~7)
    if value_end > len(raw) or next_offset > len(raw) or next_offset > PAGE_PAYLOAD_BYTES:
        raise JPVRFormatError("JREC record crosses DATA page boundary")

    header = bytearray(raw[offset:offset + RECORD_HEADER_BYTES])
    struct.pack_into("<I", header, 48, 0)
    if crc32c(header) != header_crc:
        raise JPVRFormatError("JREC header CRC32C mismatch")
    payload = raw[value_start:value_end]
    if crc32c(payload) != payload_crc:
        raise JPVRFormatError("JREC payload CRC32C mismatch")
    if any(raw[value_end:next_offset]):
        raise JPVRFormatError("JREC alignment padding must be zero")

    return DecodedRecord(
        record_type=record_type,
        flags=flags,
        schema_id=schema_id,
        schema_version=schema_version,
        object_id=object_id,
        record_version=record_version,
        payload=payload,
        next_offset=next_offset,
    )
