"""JPVR_FORMAT_V1 DATA-page packing reference helpers."""
from __future__ import annotations

from dataclasses import dataclass

from .codec import (
    PAGE_PAYLOAD_BYTES,
    DecodedPage,
    JPVRFormatError,
    PageType,
    decode_page,
    encode_page,
)
from .records import DecodedRecord, decode_record


@dataclass(frozen=True)
class DecodedDataPage:
    page_no: int
    commit_generation: int
    transaction_id: bytes
    records: tuple[DecodedRecord, ...]


def encode_data_page(
    records: tuple[bytes, ...] | list[bytes],
    page_no: int,
    commit_generation: int,
    transaction_id: bytes,
) -> bytes:
    """Pack canonical padded JREC records into one zero-filled DATA page."""
    if not isinstance(records, (tuple, list)):
        raise JPVRFormatError("DATA records must be a tuple or list")
    payload = bytearray(PAGE_PAYLOAD_BYTES)
    offset = 0
    for record_bytes in records:
        if not isinstance(record_bytes, (bytes, bytearray, memoryview)):
            raise JPVRFormatError("DATA page records must be bytes-like")
        encoded = bytes(record_bytes)
        decoded = decode_record(encoded)
        if decoded.next_offset != len(encoded):
            raise JPVRFormatError("DATA record bytes must contain exactly one padded JREC")
        if offset + len(encoded) > PAGE_PAYLOAD_BYTES:
            raise JPVRFormatError("JREC records exceed DATA page capacity")
        payload[offset:offset + len(encoded)] = encoded
        offset += len(encoded)
    return encode_page(PageType.DATA, page_no, commit_generation, transaction_id, payload)


def decode_data_page(
    page_bytes: bytes | bytearray | memoryview,
    *,
    expected_page_no: int | None = None,
) -> DecodedDataPage:
    """Decode sequential JREC records until the remaining DATA payload is all zero."""
    page: DecodedPage = decode_page(page_bytes, expected_page_no=expected_page_no)
    if page.page_type is not PageType.DATA:
        raise JPVRFormatError("page type is not DATA")
    if len(page.payload) != PAGE_PAYLOAD_BYTES:
        raise JPVRFormatError("DATA page payload area must be exactly 4032 bytes")

    records = []
    offset = 0
    while offset < PAGE_PAYLOAD_BYTES:
        if not any(page.payload[offset:]):
            break
        record = decode_record(page.payload, offset=offset)
        if record.next_offset <= offset:
            raise JPVRFormatError("DATA record decoder did not advance")
        records.append(record)
        offset = record.next_offset

    return DecodedDataPage(
        page_no=page.page_no,
        commit_generation=page.commit_generation,
        transaction_id=page.transaction_id,
        records=tuple(records),
    )
