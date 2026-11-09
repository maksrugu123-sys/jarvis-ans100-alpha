import struct
import unittest

from .codec import JPVRFormatError, PageType, Superblock, decode_page, encode_page
from .commit import (
    COMMIT_HEADER,
    COMMIT_HEADER_BYTES,
    CommitRecord,
    decode_commit_page,
    decode_commit_payload,
    encode_commit_page,
    encode_commit_payload,
    validate_commit_snapshot,
)
from .manifest import INDEX_ROOT_NAMES, Manifest, PageRef, encode_manifest_page


UUID = bytes.fromhex("00112233445566778899aabbccddeeff")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")
COMMIT1 = bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
COMMIT2 = bytes.fromhex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
OP1 = bytes.fromhex("cccccccccccccccccccccccccccccccc")


def empty_manifest(generation=1, roots=None):
    if roots is None:
        roots = {name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)}
    return Manifest(UUID, generation, roots)


def first_record(manifest_page=10, commit_page=11, tail=11):
    return CommitRecord(
        generation=1,
        transaction_id=TX1,
        commit_id=COMMIT1,
        operation_id=OP1,
        manifest_ref=PageRef(manifest_page, 1),
        durable_tail_page_no=tail,
    )


def make_second_generation():
    manifest = empty_manifest(2, {
        name: PageRef(2 + i, 1) for i, name in enumerate(INDEX_ROOT_NAMES)
    })
    manifest_page = encode_manifest_page(manifest, 20, TX2)
    previous = first_record(manifest_page=10, commit_page=11)
    previous_page = encode_commit_page(previous, 11)
    current = CommitRecord(
        generation=2,
        transaction_id=TX2,
        commit_id=COMMIT2,
        operation_id=bytes.fromhex("dddddddddddddddddddddddddddddddd"),
        manifest_ref=PageRef(20, 2),
        durable_tail_page_no=21,
        previous_commit_ref=PageRef(11, 1),
    )
    return current, manifest_page, previous_page


class CommitPayloadTests(unittest.TestCase):
    def test_payload_round_trip(self):
        record = first_record()
        raw = encode_commit_payload(record)
        self.assertEqual(len(raw), COMMIT_HEADER_BYTES)
        self.assertEqual(COMMIT_HEADER_BYTES, 112)
        self.assertEqual(decode_commit_payload(raw), record)

    def test_payload_rejects_wrong_length(self):
        with self.assertRaisesRegex(JPVRFormatError, "112 bytes"):
            decode_commit_payload(bytes(COMMIT_HEADER_BYTES - 1))

    def test_payload_rejects_magic_version_and_header_size(self):
        raw = bytearray(encode_commit_payload(first_record()))
        raw[0:4] = b"NOPE"
        with self.assertRaisesRegex(JPVRFormatError, "magic or version"):
            decode_commit_payload(raw)
        raw = bytearray(encode_commit_payload(first_record()))
        struct.pack_into("<H", raw, 4, 2)
        with self.assertRaisesRegex(JPVRFormatError, "magic or version"):
            decode_commit_payload(raw)
        raw = bytearray(encode_commit_payload(first_record()))
        struct.pack_into("<H", raw, 6, 111)
        with self.assertRaisesRegex(JPVRFormatError, "header size"):
            decode_commit_payload(raw)

    def test_payload_rejects_nonzero_reserved(self):
        raw = bytearray(encode_commit_payload(first_record()))
        struct.pack_into("<Q", raw, COMMIT_HEADER_BYTES - 8, 1)
        with self.assertRaisesRegex(JPVRFormatError, "reserved"):
            decode_commit_payload(raw)

    def test_payload_rejects_zero_identifiers(self):
        for field_name in ("transaction_id", "commit_id", "operation_id"):
            kwargs = dict(
                generation=1, transaction_id=TX1, commit_id=COMMIT1,
                operation_id=OP1, manifest_ref=PageRef(10, 1),
                durable_tail_page_no=11,
            )
            kwargs[field_name] = bytes(16)
            with self.subTest(field=field_name):
                with self.assertRaisesRegex(JPVRFormatError, "must be nonzero"):
                    encode_commit_payload(CommitRecord(**kwargs))

    def test_generation_one_cannot_have_previous_commit(self):
        record = CommitRecord(
            1, TX1, COMMIT1, OP1, PageRef(10, 1), 11, PageRef(9, 0)
        )
        with self.assertRaisesRegex(JPVRFormatError, "generation 1"):
            encode_commit_payload(record)

    def test_later_generation_requires_immediate_previous_commit(self):
        for previous in (None, PageRef(1, 1), PageRef(9, 3)):
            record = CommitRecord(
                2, TX2, COMMIT2, OP1, PageRef(10, 2), 11, previous
            )
            with self.subTest(previous=previous):
                with self.assertRaises(JPVRFormatError):
                    encode_commit_payload(record)

    def test_manifest_reference_must_match_generation(self):
        record = CommitRecord(
            2, TX2, COMMIT2, OP1, PageRef(10, 1), 11, PageRef(9, 1)
        )
        with self.assertRaisesRegex(JPVRFormatError, "current commit generation"):
            encode_commit_payload(record)


class CommitPageTests(unittest.TestCase):
    def test_commit_page_round_trip_and_page_envelope(self):
        record = first_record()
        encoded = encode_commit_page(record, 11)
        decoded = decode_commit_page(encoded, expected_page_no=11)
        self.assertEqual(decoded.record, record)
        page = decode_page(encoded)
        self.assertIs(page.page_type, PageType.COMMIT)
        self.assertEqual(page.transaction_id, TX1)

    def test_commit_page_must_be_durable_tail(self):
        with self.assertRaisesRegex(JPVRFormatError, "equal durable_tail"):
            encode_commit_page(first_record(tail=12), 11)

    def test_manifest_and_previous_commit_must_precede_commit(self):
        with self.assertRaisesRegex(JPVRFormatError, "precede COMMIT"):
            encode_commit_page(first_record(manifest_page=11, commit_page=11), 11)
        record = CommitRecord(
            2, TX2, COMMIT2, OP1, PageRef(10, 2), 11, PageRef(10, 1)
        )
        with self.assertRaisesRegex(JPVRFormatError, "previous COMMIT"):
            encode_commit_page(record, 11)

    def test_decode_rejects_wrong_outer_page_type(self):
        raw = encode_page(PageType.DATA, 11, 1, TX1, encode_commit_payload(first_record()))
        with self.assertRaisesRegex(JPVRFormatError, "not COMMIT"):
            decode_commit_page(raw)

    def test_decode_rejects_outer_payload_generation_and_transaction_mismatch(self):
        payload = encode_commit_payload(first_record())
        wrong_generation = encode_page(PageType.COMMIT, 11, 2, TX1, payload)
        with self.assertRaisesRegex(JPVRFormatError, "generation"):
            decode_commit_page(wrong_generation)
        wrong_tx = encode_page(PageType.COMMIT, 11, 1, TX2, payload)
        with self.assertRaisesRegex(JPVRFormatError, "transaction_id"):
            decode_commit_page(wrong_tx)

    def test_decode_rejects_wrong_expected_physical_page(self):
        raw = encode_commit_page(first_record(), 11)
        with self.assertRaisesRegex(JPVRFormatError, "physical page"):
            decode_commit_page(raw, expected_page_no=12)


class CommitSnapshotTests(unittest.TestCase):
    def test_validates_genesis_commit_and_manifest(self):
        manifest = empty_manifest()
        manifest_page = encode_manifest_page(manifest, 10, TX1)
        commit_page = encode_commit_page(first_record(), 11)
        decoded = validate_commit_snapshot(commit_page, manifest_page)
        self.assertEqual(decoded.record.commit_id, COMMIT1)

    def test_rejects_manifest_transaction_id_or_generation_mismatch(self):
        manifest = encode_manifest_page(empty_manifest(), 10, TX2)
        commit = encode_commit_page(first_record(), 11)
        with self.assertRaisesRegex(JPVRFormatError, "transaction_id"):
            validate_commit_snapshot(commit, manifest)
        manifest = encode_manifest_page(empty_manifest(2), 10, TX1)
        with self.assertRaisesRegex(JPVRFormatError, "generation"):
            validate_commit_snapshot(commit, manifest)

    def test_validates_previous_commit_chain(self):
        record, manifest_page, previous_page = make_second_generation()
        commit_page = encode_commit_page(record, 21)
        decoded = validate_commit_snapshot(
            commit_page, manifest_page, previous_commit_page_bytes=previous_page
        )
        self.assertEqual(decoded.record.generation, 2)

    def test_requires_and_checks_previous_commit(self):
        record, manifest_page, previous_page = make_second_generation()
        commit_page = encode_commit_page(record, 21)
        with self.assertRaisesRegex(JPVRFormatError, "required"):
            validate_commit_snapshot(commit_page, manifest_page)
        wrong_previous = encode_commit_page(
            CommitRecord(1, TX1, COMMIT2, OP1, PageRef(10, 1), 11), 11
        )
        with self.assertRaisesRegex(JPVRFormatError, "must change"):
            validate_commit_snapshot(
                commit_page, manifest_page, previous_commit_page_bytes=wrong_previous
            )

    def test_superblock_crosscheck(self):
        manifest = empty_manifest()
        manifest_page = encode_manifest_page(manifest, 10, TX1)
        commit_page = encode_commit_page(first_record(), 11)
        catalog = {2 + i: encode_page(PageType.INDEX, 2 + i, 1, TX1, b"") for i in range(7)}
        sb = Superblock(UUID, 1, 11, 0, 11, COMMIT1, 0)
        decoded = validate_commit_snapshot(
            commit_page, manifest_page, superblock=sb, page_catalog=catalog
        )
        self.assertEqual(decoded.page_no, 11)
        wrong_sb = Superblock(UUID, 2, 11, 0, 11, COMMIT1, 0)
        with self.assertRaisesRegex(JPVRFormatError, "generation"):
            validate_commit_snapshot(
                commit_page, manifest_page, superblock=wrong_sb, page_catalog=catalog
            )

    def test_superblock_and_page_catalog_must_be_paired(self):
        manifest_page = encode_manifest_page(empty_manifest(), 10, TX1)
        commit_page = encode_commit_page(first_record(), 11)
        with self.assertRaisesRegex(JPVRFormatError, "supplied together"):
            validate_commit_snapshot(
                commit_page, manifest_page,
                superblock=Superblock(UUID, 1, 11, 0, 11, COMMIT1, 0),
            )


if __name__ == "__main__":
    unittest.main()
