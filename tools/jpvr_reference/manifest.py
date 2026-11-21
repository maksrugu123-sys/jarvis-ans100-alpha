"""JPVR_FORMAT_V1 manifest payload codec and root-reference validator."""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
import struct

from .codec import (
    DecodedPage,
    JPVRFormatError,
    PageType,
    Superblock,
    decode_page,
    encode_page,
    _fixed_bytes,
    _require_uint,
    decode_page,
    encode_superblock,
)
from .records import TLVField, WireType, decode_tlv, encode_tlv


INDEX_ROOT_NAMES = (
    "primary_object",
    "neuron",
    "synapse",
    "outgoing_adjacency",
    "incoming_adjacency",
    "operation_deduplication",
    "outbox",
)

_MANIFEST_FIELD_IDS = {
    "container_uuid": 1,
    "generation": 2,
    "primary_object": 3,
    "neuron": 4,
    "synapse": 5,
    "outgoing_adjacency": 6,
    "incoming_adjacency": 7,
    "operation_deduplication": 8,
    "outbox": 9,
    "checkpoint_page_no": 10,
    "journal_high_water_page_no": 11,
    "outbox_high_water_page_no": 12,
}
_MANIFEST_KNOWN_FIELDS = {
    1: WireType.ID128,
    2: WireType.U64,
    **{field_id: WireType.BYTES for field_id in range(3, 10)},
    10: WireType.U64,
    11: WireType.U64,
    12: WireType.U64,
}


@dataclass(frozen=True)
class PageRef:
    page_no: int
    expected_commit_generation: int


@dataclass(frozen=True)
class Manifest:
    container_uuid: bytes
    generation: int
    index_roots: Mapping[str, PageRef]
    checkpoint_page_no: int = 0
    journal_high_water_page_no: int = 0
    outbox_high_water_page_no: int = 0


def _validate_page_ref(ref: PageRef) -> None:
    if not isinstance(ref, PageRef):
        raise JPVRFormatError("manifest index roots must be PageRef values")
    _require_uint("PageRef.page_no", ref.page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint(
        "PageRef.expected_commit_generation",
        ref.expected_commit_generation,
        0xFFFFFFFFFFFFFFFF,
    )
    if ref.page_no < 2 or ref.expected_commit_generation < 1:
        raise JPVRFormatError("invalid manifest PageRef")


def _validate_manifest_shape(manifest: Manifest) -> tuple[bytes, dict[str, PageRef]]:
    if not isinstance(manifest, Manifest):
        raise JPVRFormatError("manifest must be a Manifest")
    container_uuid = _fixed_bytes("container_uuid", manifest.container_uuid, 16)
    _require_uint("manifest.generation", manifest.generation, 0xFFFFFFFFFFFFFFFF)
    if manifest.generation < 1:
        raise JPVRFormatError("manifest generation must be positive")
    if not isinstance(manifest.index_roots, Mapping):
        raise JPVRFormatError("manifest index_roots must be a mapping")
    roots = dict(manifest.index_roots)
    if set(roots) != set(INDEX_ROOT_NAMES):
        raise JPVRFormatError("manifest must contain exactly the seven required index roots")
    for name in INDEX_ROOT_NAMES:
        _validate_page_ref(roots[name])
        if roots[name].expected_commit_generation > manifest.generation:
            raise JPVRFormatError("index root generation is newer than the manifest")
    for name in ("checkpoint_page_no", "journal_high_water_page_no", "outbox_high_water_page_no"):
        _require_uint(name, getattr(manifest, name), 0xFFFFFFFFFFFFFFFF)
    return container_uuid, roots


def _encode_page_ref(ref: PageRef) -> bytes:
    _validate_page_ref(ref)
    return struct.pack("<QQ", ref.page_no, ref.expected_commit_generation)


def _decode_page_ref(raw: bytes) -> PageRef:
    if len(raw) != 16:
        raise JPVRFormatError("manifest PageRef must be exactly 16 bytes")
    page_no, generation = struct.unpack("<QQ", raw)
    ref = PageRef(page_no, generation)
    _validate_page_ref(ref)
    return ref


def encode_manifest_payload(manifest: Manifest) -> bytes:
    """Encode the initial manifest schema as deterministic TLV."""
    container_uuid, roots = _validate_manifest_shape(manifest)
    fields = [
        TLVField(_MANIFEST_FIELD_IDS["container_uuid"], WireType.ID128, container_uuid),
        TLVField(_MANIFEST_FIELD_IDS["generation"], WireType.U64, manifest.generation),
    ]
    for name in INDEX_ROOT_NAMES:
        fields.append(TLVField(
            _MANIFEST_FIELD_IDS[name],
            WireType.BYTES,
            _encode_page_ref(roots[name]),
        ))
    fields.extend((
        TLVField(10, WireType.U64, manifest.checkpoint_page_no),
        TLVField(11, WireType.U64, manifest.journal_high_water_page_no),
        TLVField(12, WireType.U64, manifest.outbox_high_water_page_no),
    ))
    return encode_tlv(fields)


def decode_manifest_payload(payload: bytes | bytearray | memoryview) -> Manifest:
    """Decode mandatory manifest fields; unknown optional TLV fields are skipped."""
    fields = decode_tlv(payload, known_fields=_MANIFEST_KNOWN_FIELDS)
    by_id = {field.field_id: field for field in fields}
    required_ids = set(_MANIFEST_KNOWN_FIELDS)
    if set(by_id) != required_ids:
        raise JPVRFormatError("manifest is missing one or more required fields")
    if any(field.optional for field in fields):
        raise JPVRFormatError("manifest required fields cannot be marked OPTIONAL")

    roots = {
        name: _decode_page_ref(by_id[field_id].value)
        for name, field_id in _MANIFEST_FIELD_IDS.items()
        if name in INDEX_ROOT_NAMES
    }
    manifest = Manifest(
        container_uuid=by_id[1].value,
        generation=by_id[2].value,
        index_roots=roots,
        checkpoint_page_no=by_id[10].value,
        journal_high_water_page_no=by_id[11].value,
        outbox_high_water_page_no=by_id[12].value,
    )
    _validate_manifest_shape(manifest)
    return manifest


@dataclass(frozen=True)
class DecodedManifestPage:
    page_no: int
    transaction_id: bytes
    commit_generation: int
    manifest: Manifest


def encode_manifest_page(
    manifest: Manifest,
    page_no: int,
    transaction_id: bytes,
) -> bytes:
    """Encode a MANIFEST payload inside a standard 4-KiB page envelope."""
    _validate_manifest_shape(manifest)
    _require_uint("page_no", page_no, 0xFFFFFFFFFFFFFFFF)
    if page_no < 2:
        raise JPVRFormatError("manifest page number must be at least 2")
    tx_id = _fixed_bytes("transaction_id", transaction_id, 16)
    return encode_page(
        PageType.MANIFEST,
        page_no,
        manifest.generation,
        tx_id,
        encode_manifest_payload(manifest),
    )


def decode_manifest_page(
    page_bytes: bytes | bytearray | memoryview,
    *,
    expected_page_no: int | None = None,
) -> DecodedManifestPage:
    """Decode and cross-check the MANIFEST page envelope and its TLV generation."""
    page: DecodedPage = decode_page(page_bytes, expected_page_no=expected_page_no)
    if page.page_type is not PageType.MANIFEST:
        raise JPVRFormatError("page type is not MANIFEST")
    manifest = decode_manifest_payload(page.payload)
    if manifest.generation != page.commit_generation:
        raise JPVRFormatError("manifest generation does not match page header")
    return DecodedManifestPage(
        page_no=page.page_no,
        transaction_id=page.transaction_id,
        commit_generation=page.commit_generation,
        manifest=manifest,
    )


def validate_manifest(
    manifest: Manifest,
    superblock: Superblock,
    page_catalog: Mapping[int, bytes],
) -> None:
    """Validate manifest identity and every referenced root/high-water page."""
    container_uuid, roots = _validate_manifest_shape(manifest)
    encode_superblock(superblock)
    if container_uuid != superblock.container_uuid:
        raise JPVRFormatError("manifest container UUID does not match superblock")
    if manifest.generation != superblock.generation:
        raise JPVRFormatError("manifest generation does not match superblock")
    if not isinstance(page_catalog, Mapping):
        raise JPVRFormatError("page_catalog must be a mapping of page_no to encoded page")

    def resolve(page_no: int, expected_type: PageType):
        if page_no < 2 or page_no > superblock.durable_tail_page_no:
            raise JPVRFormatError("manifest page reference is outside durable prefix")
        encoded = page_catalog.get(page_no)
        if encoded is None:
            raise JPVRFormatError("manifest references a missing page")
        page = decode_page(encoded, expected_page_no=page_no)
        if page.page_type is not expected_type:
            raise JPVRFormatError("manifest page reference has the wrong page type")
        if page.commit_generation > manifest.generation:
            raise JPVRFormatError("referenced page generation is newer than manifest")
        return page

    for name in INDEX_ROOT_NAMES:
        ref = roots[name]
        if ref.page_no > superblock.durable_tail_page_no:
            raise JPVRFormatError("index root is outside durable prefix")
        page = resolve(ref.page_no, PageType.INDEX)
        if page.commit_generation != ref.expected_commit_generation:
            raise JPVRFormatError("index PageRef generation does not match page header")

    if manifest.checkpoint_page_no:
        resolve(manifest.checkpoint_page_no, PageType.CHECKPOINT)
    if manifest.journal_high_water_page_no:
        resolve(manifest.journal_high_water_page_no, PageType.JOURNAL)
    if manifest.outbox_high_water_page_no:
        resolve(manifest.outbox_high_water_page_no, PageType.OUTBOX)
