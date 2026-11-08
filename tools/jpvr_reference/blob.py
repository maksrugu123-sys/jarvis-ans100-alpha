"""JPVR_FORMAT_V1 BLOB-fragment reference codec."""
from __future__ import annotations

from dataclasses import dataclass
import math
import struct

from .codec import (
    PAGE_PAYLOAD_BYTES,
    DecodedPage,
    JPVRFormatError,
    PageType,
    _fixed_bytes,
    _require_uint,
    crc32c,
    decode_page,
    encode_page,
)
from .records import MAX_LOGICAL_RECORD_BYTES, MAX_SMALL_RECORD_PAYLOAD


BLOB_MAGIC = b"JBLB"
BLOB_FRAGMENT_HEADER_BYTES = 64
BLOB_FRAGMENT_DATA_BYTES = PAGE_PAYLOAD_BYTES - BLOB_FRAGMENT_HEADER_BYTES
MAX_BLOB_PAGES = 8192


@dataclass(frozen=True)
class DecodedBlobRecord:
    record_type: int
    schema_id: int
    schema_version: int
    object_id: bytes
    record_version: int
    transaction_id: bytes
    commit_generation: int
    first_page_no: int
    page_count: int
    payload: bytes


def encode_blob_pages(
    record_type: int,
    schema_id: int,
    schema_version: int,
    object_id: bytes,
    record_version: int,
    transaction_id: bytes,
    first_page_no: int,
    commit_generation: int,
    payload: bytes | bytearray | memoryview,
) -> tuple[bytes, ...]:
    """Encode one large logical record into consecutive full BLOB pages."""
    _require_uint("record_type", record_type, 0xFFFF)
    _require_uint("schema_id", schema_id, 0xFFFFFFFF)
    _require_uint("schema_version", schema_version, 0xFFFF)
    _require_uint("record_version", record_version, 0xFFFFFFFFFFFFFFFF)
    _require_uint("first_page_no", first_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("commit_generation", commit_generation, 0xFFFFFFFFFFFFFFFF)
    if (record_type == 0 or schema_id == 0 or schema_version == 0
            or record_version == 0 or first_page_no < 2 or commit_generation < 1):
        raise JPVRFormatError("invalid BLOB record identity or page range")
    object_bytes = _fixed_bytes("object_id", object_id, 16)
    tx_bytes = _fixed_bytes("transaction_id", transaction_id, 16)
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise JPVRFormatError("BLOB payload must be bytes-like")
    payload_bytes = bytes(payload)
    total_length = len(payload_bytes)
    if total_length <= MAX_SMALL_RECORD_PAYLOAD:
        raise JPVRFormatError("BLOB is reserved for records larger than 3976 bytes")
    if total_length > MAX_LOGICAL_RECORD_BYTES:
        raise JPVRFormatError("BLOB logical record exceeds 16 MiB")

    fragment_count = math.ceil(total_length / BLOB_FRAGMENT_DATA_BYTES)
    if fragment_count > MAX_BLOB_PAGES:
        raise JPVRFormatError("BLOB page count exceeds 8192")
    if first_page_no + fragment_count - 1 > 0xFFFFFFFFFFFFFFFF:
        raise JPVRFormatError("BLOB page number range overflows u64")

    full_crc = crc32c(payload_bytes)
    pages = []
    for fragment_index in range(fragment_count):
        start = fragment_index * BLOB_FRAGMENT_DATA_BYTES
        fragment = payload_bytes[start:start + BLOB_FRAGMENT_DATA_BYTES]
        fragment_payload = bytearray(PAGE_PAYLOAD_BYTES)
        struct.pack_into(
            "<4sHHIHH16sQIIQII",
            fragment_payload,
            0,
            BLOB_MAGIC,
            record_type,
            0,
            schema_id,
            schema_version,
            BLOB_FRAGMENT_HEADER_BYTES,
            object_bytes,
            record_version,
            fragment_index,
            fragment_count,
            total_length,
            len(fragment),
            full_crc,
        )
        fragment_payload[BLOB_FRAGMENT_HEADER_BYTES:BLOB_FRAGMENT_HEADER_BYTES + len(fragment)] = fragment
        pages.append(encode_page(
            PageType.BLOB,
            first_page_no + fragment_index,
            commit_generation,
            tx_bytes,
            fragment_payload,
        ))
    return tuple(pages)


def decode_blob_pages(
    pages: tuple[bytes, ...] | list[bytes],
    *,
    expected_first_page_no: int | None = None,
) -> DecodedBlobRecord:
    """Validate an entire ordered BLOB page run and reassemble its logical record."""
    if not isinstance(pages, (tuple, list)) or not pages:
        raise JPVRFormatError("BLOB pages must be a non-empty tuple or list")
    if len(pages) > MAX_BLOB_PAGES:
        raise JPVRFormatError("BLOB page count exceeds 8192")
    if expected_first_page_no is not None:
        _require_uint("expected_first_page_no", expected_first_page_no, 0xFFFFFFFFFFFFFFFF)

    decoded_pages: list[DecodedPage] = []
    for index, page_bytes in enumerate(pages):
        expected_page_no = None
        if expected_first_page_no is not None:
            expected_page_no = expected_first_page_no + index
            if expected_page_no > 0xFFFFFFFFFFFFFFFF:
                raise JPVRFormatError("expected BLOB page range overflows u64")
        decoded = decode_page(page_bytes, expected_page_no=expected_page_no)
        if decoded.page_type is not PageType.BLOB:
            raise JPVRFormatError("BLOB run contains a non-BLOB page")
        if len(decoded.payload) != PAGE_PAYLOAD_BYTES:
            raise JPVRFormatError("BLOB page payload area must be exactly 4032 bytes")
        decoded_pages.append(decoded)

    first = decoded_pages[0]
    if first.page_no < 2 or first.commit_generation < 1:
        raise JPVRFormatError("invalid first BLOB page metadata")
    if expected_first_page_no is not None and first.page_no != expected_first_page_no:
        raise JPVRFormatError("first BLOB page does not match expected page number")

    assembled = bytearray()
    common = None
    for index, page in enumerate(decoded_pages):
        if (page.page_no != first.page_no + index
                or page.commit_generation != first.commit_generation
                or page.transaction_id != first.transaction_id):
            raise JPVRFormatError("BLOB pages must be contiguous and share transaction metadata")

        (
            magic, record_type, flags, schema_id, schema_version, header_bytes,
            object_id, record_version, fragment_index, fragment_count,
            total_length, fragment_length, full_crc,
        ) = struct.unpack_from("<4sHHIHH16sQIIQII", page.payload, 0)
        if magic != BLOB_MAGIC:
            raise JPVRFormatError("invalid BLOB fragment magic")
        if (record_type == 0 or flags != 0 or schema_id == 0 or schema_version == 0
                or header_bytes != BLOB_FRAGMENT_HEADER_BYTES or record_version == 0):
            raise JPVRFormatError("invalid BLOB fragment header fields")
        if (fragment_count == 0 or fragment_count > MAX_BLOB_PAGES
                or total_length <= MAX_SMALL_RECORD_PAYLOAD
                or total_length > MAX_LOGICAL_RECORD_BYTES):
            raise JPVRFormatError("invalid BLOB total length or fragment count")
        expected_count = math.ceil(total_length / BLOB_FRAGMENT_DATA_BYTES)
        if fragment_count != expected_count or fragment_count != len(decoded_pages):
            raise JPVRFormatError("BLOB fragment count does not match total payload length")
        if fragment_index != index:
            raise JPVRFormatError("BLOB fragment order/index mismatch")

        if common is None:
            common = (
                record_type, schema_id, schema_version, object_id,
                record_version, fragment_count, total_length, full_crc,
            )
        elif common != (
            record_type, schema_id, schema_version, object_id,
            record_version, fragment_count, total_length, full_crc,
        ):
            raise JPVRFormatError("BLOB fragment metadata is inconsistent")

        expected_length = min(
            BLOB_FRAGMENT_DATA_BYTES,
            total_length - index * BLOB_FRAGMENT_DATA_BYTES,
        )
        if fragment_length != expected_length:
            raise JPVRFormatError("BLOB fragment payload length is invalid")
        data_start = BLOB_FRAGMENT_HEADER_BYTES
        data_end = data_start + fragment_length
        if any(page.payload[data_end:]):
            raise JPVRFormatError("BLOB fragment padding must be zero")
        assembled.extend(page.payload[data_start:data_end])

    record_type, schema_id, schema_version, object_id, record_version, fragment_count, total_length, full_crc = common
    if len(assembled) != total_length:
        raise JPVRFormatError("reassembled BLOB length mismatch")
    if crc32c(assembled) != full_crc:
        raise JPVRFormatError("BLOB full-record CRC32C mismatch")

    return DecodedBlobRecord(
        record_type=record_type,
        schema_id=schema_id,
        schema_version=schema_version,
        object_id=object_id,
        record_version=record_version,
        transaction_id=first.transaction_id,
        commit_generation=first.commit_generation,
        first_page_no=first.page_no,
        page_count=fragment_count,
        payload=bytes(assembled),
    )
