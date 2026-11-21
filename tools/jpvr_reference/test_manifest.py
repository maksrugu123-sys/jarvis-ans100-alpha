import unittest
from dataclasses import replace

from .codec import JPVRFormatError, PageType, Superblock, encode_page
from .manifest import (
    INDEX_ROOT_NAMES,
    Manifest,
    PageRef,
    decode_manifest_page,
    decode_manifest_payload,
    encode_manifest_page,
    encode_manifest_payload,
    validate_manifest,
)
from .records import TLVField, WireType, decode_tlv, encode_tlv


class ManifestCodecTests(unittest.TestCase):
    CONTAINER_ID = bytes.fromhex("00112233445566778899aabbccddeeff")
    COMMIT_ID = bytes.fromhex("11111111111111111111111111111111")

    def make_manifest(self, generation=2):
        roots = {
            name: PageRef(index + 2, 1)
            for index, name in enumerate(INDEX_ROOT_NAMES)
        }
        return Manifest(
            self.CONTAINER_ID,
            generation,
            roots,
            checkpoint_page_no=9,
            journal_high_water_page_no=10,
            outbox_high_water_page_no=11,
        )

    def make_superblock(self, generation=2, tail=12, container_id=None):
        return Superblock(
            self.CONTAINER_ID if container_id is None else container_id,
            generation,
            12,
            9,
            tail,
            self.COMMIT_ID,
            1,
        )

    def make_page_catalog(self):
        catalog = {
            page_no: encode_page(PageType.INDEX, page_no, 1, self.COMMIT_ID, b"root")
            for page_no in range(2, 9)
        }
        catalog[9] = encode_page(PageType.CHECKPOINT, 9, 2, self.COMMIT_ID, b"checkpoint")
        catalog[10] = encode_page(PageType.JOURNAL, 10, 2, self.COMMIT_ID, b"journal")
        catalog[11] = encode_page(PageType.OUTBOX, 11, 2, self.COMMIT_ID, b"outbox")
        return catalog

    def test_manifest_tlv_round_trip(self):
        manifest = self.make_manifest()
        self.assertEqual(decode_manifest_payload(encode_manifest_payload(manifest)), manifest)

    def test_unknown_optional_manifest_field_is_skipped(self):
        payload = encode_manifest_payload(self.make_manifest())
        extension = encode_tlv([TLVField(100, WireType.UTF8, "future", optional=True)])
        self.assertEqual(decode_manifest_payload(payload + extension), self.make_manifest())

    def test_unknown_mandatory_manifest_field_is_rejected(self):
        payload = encode_manifest_payload(self.make_manifest())
        extension = encode_tlv([TLVField(100, WireType.U8, 1)])
        with self.assertRaisesRegex(JPVRFormatError, "unknown mandatory"):
            decode_manifest_payload(payload + extension)

    def test_missing_required_manifest_field_is_rejected(self):
        payload = encode_tlv([
            TLVField(1, WireType.ID128, self.CONTAINER_ID),
            TLVField(2, WireType.U64, 1),
        ])
        with self.assertRaisesRegex(JPVRFormatError, "missing"):
            decode_manifest_payload(payload)

    def test_manifest_rejects_optional_flag_on_required_field(self):
        fields = list(decode_tlv(encode_manifest_payload(self.make_manifest())))
        fields[0] = replace(fields[0], optional=True)
        payload = encode_tlv(fields)
        with self.assertRaisesRegex(JPVRFormatError, "cannot be marked OPTIONAL"):
            decode_manifest_payload(payload)

    def test_manifest_roots_must_be_exactly_the_required_set(self):
        manifest = self.make_manifest()
        roots = dict(manifest.index_roots)
        roots.pop("neuron")
        invalid = Manifest(manifest.container_uuid, manifest.generation, roots)
        with self.assertRaisesRegex(JPVRFormatError, "seven required"):
            encode_manifest_payload(invalid)

    def test_manifest_page_round_trip(self):
        manifest = self.make_manifest()
        page = encode_manifest_page(manifest, 30, self.COMMIT_ID)
        decoded = decode_manifest_page(page, expected_page_no=30)
        self.assertEqual(decoded.manifest, manifest)
        self.assertEqual(decoded.commit_generation, manifest.generation)
        self.assertEqual(decoded.transaction_id, self.COMMIT_ID)

    def test_manifest_page_rejects_wrong_page_type(self):
        data_page = encode_page(PageType.DATA, 30, 2, self.COMMIT_ID, bytes(4032))
        with self.assertRaisesRegex(JPVRFormatError, "not MANIFEST"):
            decode_manifest_page(data_page)

    def test_manifest_page_rejects_generation_mismatch(self):
        manifest = self.make_manifest()
        page = encode_page(PageType.MANIFEST, 30, 3, self.COMMIT_ID, encode_manifest_payload(manifest))
        with self.assertRaisesRegex(JPVRFormatError, "generation does not match"):
            decode_manifest_page(page)

    def test_manifest_page_checks_outer_crc(self):
        page = bytearray(encode_manifest_page(self.make_manifest(), 30, self.COMMIT_ID))
        page[-1] ^= 1
        with self.assertRaisesRegex(JPVRFormatError, "payload CRC32C"):
            decode_manifest_page(page)

    def test_manifest_validation_accepts_previous_generation_roots(self):
        manifest = self.make_manifest(generation=2)
        self.assertIsNone(validate_manifest(manifest, self.make_superblock(), self.make_page_catalog()))

    def test_manifest_rejects_container_uuid_mismatch(self):
        manifest = self.make_manifest()
        other_id = bytes.fromhex("ffeeddccbbaa99887766554433221100")
        with self.assertRaisesRegex(JPVRFormatError, "UUID"):
            validate_manifest(manifest, self.make_superblock(container_id=other_id), self.make_page_catalog())

    def test_manifest_rejects_generation_mismatch(self):
        with self.assertRaisesRegex(JPVRFormatError, "generation"):
            validate_manifest(self.make_manifest(1), self.make_superblock(), self.make_page_catalog())

    def test_manifest_rejects_missing_root_page(self):
        catalog = self.make_page_catalog()
        del catalog[2]
        with self.assertRaisesRegex(JPVRFormatError, "missing page"):
            validate_manifest(self.make_manifest(), self.make_superblock(), catalog)

    def test_manifest_rejects_wrong_index_page_type(self):
        catalog = self.make_page_catalog()
        catalog[2] = encode_page(PageType.DATA, 2, 1, self.COMMIT_ID, bytes(4032))
        with self.assertRaisesRegex(JPVRFormatError, "wrong page type"):
            validate_manifest(self.make_manifest(), self.make_superblock(), catalog)

    def test_manifest_rejects_page_ref_generation_mismatch(self):
        manifest = self.make_manifest()
        roots = dict(manifest.index_roots)
        roots["primary_object"] = PageRef(2, 2)
        invalid = Manifest(manifest.container_uuid, manifest.generation, roots,
                           manifest.checkpoint_page_no, manifest.journal_high_water_page_no,
                           manifest.outbox_high_water_page_no)
        with self.assertRaisesRegex(JPVRFormatError, "generation does not match"):
            validate_manifest(invalid, self.make_superblock(), self.make_page_catalog())

    def test_manifest_rejects_root_beyond_durable_tail(self):
        manifest = self.make_manifest()
        roots = dict(manifest.index_roots)
        roots["primary_object"] = PageRef(13, 1)
        invalid = Manifest(manifest.container_uuid, manifest.generation, roots)
        with self.assertRaisesRegex(JPVRFormatError, "durable prefix"):
            validate_manifest(invalid, self.make_superblock(tail=12), self.make_page_catalog())

    def test_manifest_validates_checkpoint_journal_and_outbox_types(self):
        for page_no in (9, 10, 11):
            catalog = self.make_page_catalog()
            catalog[page_no] = encode_page(PageType.INDEX, page_no, 2, self.COMMIT_ID, b"wrong")
            with self.assertRaisesRegex(JPVRFormatError, "wrong page type"):
                validate_manifest(self.make_manifest(), self.make_superblock(), catalog)

    def test_manifest_rejects_missing_high_water_page(self):
        catalog = self.make_page_catalog()
        del catalog[10]
        with self.assertRaisesRegex(JPVRFormatError, "missing page"):
            validate_manifest(self.make_manifest(), self.make_superblock(), catalog)


if __name__ == "__main__":
    unittest.main(verbosity=2)
