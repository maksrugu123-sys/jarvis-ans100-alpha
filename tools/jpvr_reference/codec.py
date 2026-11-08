"""Strict binary primitives for the JPVR_FORMAT_V1 reference format.

This module implements CRC32C, the 4 KiB superblock and the 4 KiB page
envelope only. It is not a container reader/writer or a durable commit engine.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum
import struct
from typing import Any


PAGE_SIZE = 4096
SUPERBLOCK_SIZE = 4096
SUPERBLOCK_HEADER_BYTES = 128
PAGE_HEADER_BYTES = 64
PAGE_PAYLOAD_BYTES = PAGE_SIZE - PAGE_HEADER_BYTES
FORMAT_MAJOR = 1
FORMAT_MINOR = 0
ENDIAN_MARKER = 0x01020304

SUPERBLOCK_MAGIC = b"JARVISJP"
PAGE_MAGIC = b"JPGP"

_SUPERBLOCK_CRC_OFFSET = 92
_PAGE_HEADER_CRC_OFFSET = 56


class JPVRFormatError(ValueError):
    """Raised when a JPVR v1 value or binary block is invalid."""


class PageType(IntEnum):
    DATA = 1
    BLOB = 2
    INDEX = 3
    MANIFEST = 4
    JOURNAL = 5
    OUTBOX = 6
    COMMIT = 7
    CHECKPOINT = 8


def _make_crc32c_table() -> tuple[int, ...]:
    table = []
    for value in range(256):
        crc = value
        for _ in range(8):
            crc = (crc >> 1) ^ (0x82F63B78 if crc & 1 else 0)
        table.append(crc & 0xFFFFFFFF)
    return tuple(table)


_CRC32C_TABLE = _make_crc32c_table()


def crc32c(data: bytes | bytearray | memoryview) -> int:
    """Return Castagnoli CRC32C (reflected polynomial 0x82F63B78)."""
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise TypeError("crc32c input must be bytes-like")
    crc = 0xFFFFFFFF
    try:
        view = memoryview(data).cast("B")
    except (TypeError, ValueError) as exc:
        raise TypeError("crc32c input must be a contiguous bytes-like object") from exc
    for value in view:
        crc = _CRC32C_TABLE[(crc ^ value) & 0xFF] ^ (crc >> 8)
    return (crc ^ 0xFFFFFFFF) & 0xFFFFFFFF


def _require_uint(name: str, value: Any, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= maximum:
        raise JPVRFormatError(f"{name} must be an unsigned integer in range")
    return value


def _fixed_bytes(name: str, value: Any, length: int) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise JPVRFormatError(f"{name} must be bytes-like")
    result = bytes(value)
    if len(result) != length:
        raise JPVRFormatError(f"{name} must contain exactly {length} bytes")
    return result


@dataclass(frozen=True)
class Superblock:
    container_uuid: bytes
    generation: int
    active_commit_page_no: int
    checkpoint_page_no: int
    durable_tail_page_no: int
    active_commit_id: bytes
    slot_id: int
    flags: int = 0


def _validate_superblock(block: Superblock) -> tuple[bytes, bytes]:
    if not isinstance(block, Superblock):
        raise JPVRFormatError("superblock must be a Superblock")
    container_uuid = _fixed_bytes("container_uuid", block.container_uuid, 16)
    active_commit_id = _fixed_bytes("active_commit_id", block.active_commit_id, 16)
    _require_uint("generation", block.generation, 0xFFFFFFFFFFFFFFFF)
    _require_uint("active_commit_page_no", block.active_commit_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("checkpoint_page_no", block.checkpoint_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("durable_tail_page_no", block.durable_tail_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("slot_id", block.slot_id, 1)
    _require_uint("flags", block.flags, 0xFFFF)
    if block.flags != 0:
        raise JPVRFormatError("unknown superblock flags are not supported")
    if block.durable_tail_page_no < 1:
        raise JPVRFormatError("durable_tail_page_no must be at least 1")

    if block.active_commit_page_no == 0:
        if (block.generation != 0 or active_commit_id != bytes(16)
                or block.checkpoint_page_no != 0 or block.durable_tail_page_no != 1):
            raise JPVRFormatError("invalid empty-container superblock state")
    else:
        if (block.generation < 1 or block.active_commit_page_no < 2
                or block.active_commit_page_no > block.durable_tail_page_no
                or active_commit_id == bytes(16)):
            raise JPVRFormatError("invalid active commit reference")
        if block.checkpoint_page_no != 0 and not (
                2 <= block.checkpoint_page_no <= block.durable_tail_page_no):
            raise JPVRFormatError("checkpoint page is outside the durable prefix")
    return container_uuid, active_commit_id


def encode_superblock(block: Superblock) -> bytes:
    """Encode one 4 KiB superblock copy with its CRC32C field populated."""
    container_uuid, active_commit_id = _validate_superblock(block)
    raw = bytearray(SUPERBLOCK_SIZE)
    struct.pack_into(
        "<8sHHHHII",
        raw,
        0,
        SUPERBLOCK_MAGIC,
        FORMAT_MAJOR,
        FORMAT_MINOR,
        SUPERBLOCK_HEADER_BYTES,
        block.flags,
        ENDIAN_MARKER,
        PAGE_SIZE,
    )
    raw[24:40] = container_uuid
    struct.pack_into(
        "<QQQQ",
        raw,
        40,
        block.generation,
        block.active_commit_page_no,
        block.checkpoint_page_no,
        block.durable_tail_page_no,
    )
    raw[72:88] = active_commit_id
    struct.pack_into("<II", raw, 88, block.slot_id, 0)
    struct.pack_into("<I", raw, _SUPERBLOCK_CRC_OFFSET, crc32c(raw))
    return bytes(raw)


def decode_superblock(
    data: bytes | bytearray | memoryview,
    *,
    expected_slot_id: int | None = None,
) -> Superblock:
    """Validate and decode a superblock; never guesses around corrupt fields."""
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("superblock input must be bytes-like")
    raw = bytes(data)
    if len(raw) != SUPERBLOCK_SIZE:
        raise JPVRFormatError("superblock must be exactly 4096 bytes")
    (
        magic, major, minor, header_bytes, flags, endian_marker, page_size,
    ) = struct.unpack_from("<8sHHHHII", raw, 0)
    if magic != SUPERBLOCK_MAGIC:
        raise JPVRFormatError("invalid superblock magic")
    if (major, minor) != (FORMAT_MAJOR, FORMAT_MINOR):
        raise JPVRFormatError("unsupported superblock format version")
    if header_bytes != SUPERBLOCK_HEADER_BYTES:
        raise JPVRFormatError("invalid superblock header size")
    if flags != 0:
        raise JPVRFormatError("unknown superblock flags are not supported")
    if endian_marker != ENDIAN_MARKER or page_size != PAGE_SIZE:
        raise JPVRFormatError("invalid endian marker or page size")
    if any(raw[96:]):
        raise JPVRFormatError("superblock reserved bytes must be zero")

    stored_crc = struct.unpack_from("<I", raw, _SUPERBLOCK_CRC_OFFSET)[0]
    crc_input = bytearray(raw)
    struct.pack_into("<I", crc_input, _SUPERBLOCK_CRC_OFFSET, 0)
    if crc32c(crc_input) != stored_crc:
        raise JPVRFormatError("superblock CRC32C mismatch")

    generation, active_page, checkpoint_page, durable_tail = struct.unpack_from("<QQQQ", raw, 40)
    slot_id = struct.unpack_from("<I", raw, 88)[0]
    block = Superblock(
        container_uuid=raw[24:40],
        generation=generation,
        active_commit_page_no=active_page,
        checkpoint_page_no=checkpoint_page,
        durable_tail_page_no=durable_tail,
        active_commit_id=raw[72:88],
        slot_id=slot_id,
        flags=flags,
    )
    _validate_superblock(block)
    if expected_slot_id is not None:
        _require_uint("expected_slot_id", expected_slot_id, 1)
        if slot_id != expected_slot_id:
            raise JPVRFormatError("superblock slot_id does not match physical slot")
    return block


@dataclass(frozen=True)
class DecodedPage:
    page_type: PageType
    flags: int
    page_no: int
    commit_generation: int
    transaction_id: bytes
    payload: bytes


def encode_page(
    page_type: PageType | int,
    page_no: int,
    commit_generation: int,
    transaction_id: bytes,
    payload: bytes | bytearray | memoryview,
    *,
    flags: int = 0,
) -> bytes:
    """Encode a complete 4 KiB page with a 64-byte header and zero padding."""
    if isinstance(page_type, bool) or not isinstance(page_type, int) or page_type not in range(1, 9):
        raise JPVRFormatError("unknown page type")
    _require_uint("page_no", page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("commit_generation", commit_generation, 0xFFFFFFFFFFFFFFFF)
    _require_uint("flags", flags, 0xFFFF)
    if page_no < 2 or commit_generation < 1:
        raise JPVRFormatError("data pages require page_no >= 2 and generation >= 1")
    if flags != 0:
        raise JPVRFormatError("unknown page flags are not supported")
    tx_id = _fixed_bytes("transaction_id", transaction_id, 16)
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("page payload must be bytes-like")
    payload_bytes = bytes(payload)
    if len(payload_bytes) > PAGE_PAYLOAD_BYTES:
        raise JPVRFormatError("page payload exceeds 4032 bytes")

    raw = bytearray(PAGE_SIZE)
    raw[PAGE_HEADER_BYTES:PAGE_HEADER_BYTES + len(payload_bytes)] = payload_bytes
    payload_crc = crc32c(raw[PAGE_HEADER_BYTES:])
    struct.pack_into(
        "<4sHHHHHHQQ16sIIII",
        raw,
        0,
        PAGE_MAGIC,
        FORMAT_MAJOR,
        FORMAT_MINOR,
        int(page_type),
        flags,
        PAGE_HEADER_BYTES,
        0,
        page_no,
        commit_generation,
        tx_id,
        len(payload_bytes),
        payload_crc,
        0,
        0,
    )
    struct.pack_into("<I", raw, _PAGE_HEADER_CRC_OFFSET, crc32c(raw[:PAGE_HEADER_BYTES]))
    return bytes(raw)


def decode_page(
    data: bytes | bytearray | memoryview,
    *,
    expected_page_no: int | None = None,
) -> DecodedPage:
    """Validate a complete page, including both CRCs and zero padding."""
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("page input must be bytes-like")
    raw = bytes(data)
    if len(raw) != PAGE_SIZE:
        raise JPVRFormatError("page must be exactly 4096 bytes")
    (
        magic, major, minor, page_type_value, flags, header_bytes, reserved16,
        page_no, generation, transaction_id, payload_length, payload_crc,
        header_crc, reserved32,
    ) = struct.unpack_from("<4sHHHHHHQQ16sIIII", raw, 0)
    if magic != PAGE_MAGIC:
        raise JPVRFormatError("invalid page magic")
    if (major, minor) != (FORMAT_MAJOR, FORMAT_MINOR):
        raise JPVRFormatError("unsupported page format version")
    if page_type_value not in range(1, 9):
        raise JPVRFormatError("unknown page type")
    if flags != 0 or header_bytes != PAGE_HEADER_BYTES or reserved16 != 0 or reserved32 != 0:
        raise JPVRFormatError("invalid page flags, header size or reserved fields")
    if page_no < 2 or generation < 1:
        raise JPVRFormatError("invalid page number or commit generation")
    if payload_length > PAGE_PAYLOAD_BYTES:
        raise JPVRFormatError("page payload length exceeds 4032 bytes")

    header = bytearray(raw[:PAGE_HEADER_BYTES])
    struct.pack_into("<I", header, _PAGE_HEADER_CRC_OFFSET, 0)
    if crc32c(header) != header_crc:
        raise JPVRFormatError("page header CRC32C mismatch")
    payload_area = raw[PAGE_HEADER_BYTES:]
    if crc32c(payload_area) != payload_crc:
        raise JPVRFormatError("page payload CRC32C mismatch")
    if any(payload_area[payload_length:]):
        raise JPVRFormatError("page payload padding must be zero")
    if expected_page_no is not None:
        _require_uint("expected_page_no", expected_page_no, 0xFFFFFFFFFFFFFFFF)
        if page_no != expected_page_no:
            raise JPVRFormatError("page_no does not match physical page position")

    return DecodedPage(
        page_type=PageType(page_type_value),
        flags=flags,
        page_no=page_no,
        commit_generation=generation,
        transaction_id=transaction_id,
        payload=payload_area[:payload_length],
    )
