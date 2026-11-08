import struct
import unittest

from .blob import (
    BLOB_FRAGMENT_DATA_BYTES,
    BLOB_FRAGMENT_HEADER_BYTES,
    MAX_BLOB_PAGES,
    decode_blob_pages,
    encode_blob_pages,
)
from .codec import JPVRFormatError, PageType, decode_page, encode_page


class BlobCodecTests(unittest.TestCase):
    OBJECT_ID = bytes.fromhex("00112233445566778899aabbccddeeff")
    TX_ID = bytes.fromhex("1234567890abcdef1234567890abcdef")

    @staticmethod
    def payload(size):
        return bytes(index % 251 for index in range(size))

    def make_pages(self, payload):
        return encode_blob_pages(
            record_type=2,
            schema_id=1002,
            schema_version=1,
            object_id=self.OBJECT_ID,
            record_version=9,
            transaction_id=self.TX_ID,
            first_page_no=20,
            commit_generation=4,
            payload=payload,
        )

    def test_two_fragment_blob_round_trip(self):
        payload = self.payload(5000)
        pages = self.make_pages(payload)
        self.assertEqual(len(pages), 2)
        decoded = decode_blob_pages(pages, expected_first_page_no=20)
        self.assertEqual(decoded.payload, payload)
        self.assertEqual(decoded.record_type, 2)
        self.assertEqual(decoded.schema_id, 1002)
        self.assertEqual(decoded.object_id, self.OBJECT_ID)
        self.assertEqual(decoded.record_version, 9)
        self.assertEqual(decoded.transaction_id, self.TX_ID)
        self.assertEqual(decoded.commit_generation, 4)
        self.assertEqual(decoded.page_count, 2)

    def test_fragment_boundary_lengths(self):
        payload = self.payload(BLOB_FRAGMENT_DATA_BYTES + 9)
        pages = self.make_pages(payload)
        self.assertEqual(len(pages), 2)
        first = decode_page(pages[0])
        second = decode_page(pages[1])
        self.assertEqual(struct.unpack_from("<I", first.payload, 56)[0], BLOB_FRAGMENT_DATA_BYTES)
        self.assertEqual(struct.unpack_from("<I", second.payload, 56)[0], 9)
        self.assertEqual(decode_blob_pages(pages).payload, payload)

    def test_exact_small_record_limit_is_not_encoded_as_blob(self):
        with self.assertRaisesRegex(JPVRFormatError, "larger than 3976"):
            self.make_pages(self.payload(3976))

    def test_blob_page_order_is_required(self):
        pages = self.make_pages(self.payload(5000))
        with self.assertRaisesRegex(JPVRFormatError, "contiguous"):
            decode_blob_pages(tuple(reversed(pages)))

    def test_expected_first_page_number_is_enforced(self):
        pages = self.make_pages(self.payload(5000))
        with self.assertRaisesRegex(JPVRFormatError, "physical page"):
            decode_blob_pages(pages, expected_first_page_no=21)

    def test_non_blob_page_is_rejected(self):
        data_page = encode_page(PageType.DATA, 20, 4, self.TX_ID, bytes(4032))
        with self.assertRaisesRegex(JPVRFormatError, "non-BLOB"):
            decode_blob_pages((data_page,))

    def test_outer_page_crc_is_checked(self):
        pages = list(self.make_pages(self.payload(5000)))
        damaged = bytearray(pages[0])
        damaged[-1] ^= 1
        pages[0] = bytes(damaged)
        with self.assertRaisesRegex(JPVRFormatError, "payload CRC32C"):
            decode_blob_pages(pages)

    def test_full_record_crc_detects_rechecksummed_fragment_changes(self):
        pages = list(self.make_pages(self.payload(5000)))
        decoded = decode_page(pages[0])
        fragment_payload = bytearray(decoded.payload)
        fragment_payload[BLOB_FRAGMENT_HEADER_BYTES] ^= 1
        pages[0] = encode_page(
            PageType.BLOB, decoded.page_no, decoded.commit_generation,
            decoded.transaction_id, fragment_payload,
        )
        with self.assertRaisesRegex(JPVRFormatError, "full-record CRC32C"):
            decode_blob_pages(pages)

    def test_fragment_padding_must_be_zero(self):
        pages = list(self.make_pages(self.payload(5000)))
        decoded = decode_page(pages[-1])
        fragment_payload = bytearray(decoded.payload)
        fragment_payload[-1] = 1
        pages[-1] = encode_page(
            PageType.BLOB, decoded.page_no, decoded.commit_generation,
            decoded.transaction_id, fragment_payload,
        )
        with self.assertRaisesRegex(JPVRFormatError, "padding"):
            decode_blob_pages(pages)

    def test_transaction_id_must_match_across_fragments(self):
        pages = list(self.make_pages(self.payload(5000)))
        decoded = decode_page(pages[1])
        pages[1] = encode_page(
            PageType.BLOB, decoded.page_no, decoded.commit_generation,
            bytes.fromhex("ffeeddccbbaa99887766554433221100"), decoded.payload,
        )
        with self.assertRaisesRegex(JPVRFormatError, "transaction metadata"):
            decode_blob_pages(pages)

    def test_generation_must_match_across_fragments(self):
        pages = list(self.make_pages(self.payload(5000)))
        decoded = decode_page(pages[1])
        pages[1] = encode_page(
            PageType.BLOB, decoded.page_no, decoded.commit_generation + 1,
            decoded.transaction_id, decoded.payload,
        )
        with self.assertRaisesRegex(JPVRFormatError, "transaction metadata"):
            decode_blob_pages(pages)

    def test_fragment_header_metadata_must_match(self):
        pages = list(self.make_pages(self.payload(5000)))
        decoded = decode_page(pages[1])
        fragment_payload = bytearray(decoded.payload)
        struct.pack_into("<I", fragment_payload, 8, 2000)
        pages[1] = encode_page(
            PageType.BLOB, decoded.page_no, decoded.commit_generation,
            decoded.transaction_id, fragment_payload,
        )
        with self.assertRaisesRegex(JPVRFormatError, "metadata is inconsistent"):
            decode_blob_pages(pages)

    def test_fragment_index_and_count_are_checked(self):
        pages = list(self.make_pages(self.payload(5000)))
        decoded = decode_page(pages[1])
        fragment_payload = bytearray(decoded.payload)
        struct.pack_into("<I", fragment_payload, 40, 0)
        pages[1] = encode_page(
            PageType.BLOB, decoded.page_no, decoded.commit_generation,
            decoded.transaction_id, fragment_payload,
        )
        with self.assertRaisesRegex(JPVRFormatError, "order/index"):
            decode_blob_pages(pages)

    def test_encoder_rejects_payload_above_logical_record_limit(self):
        with self.assertRaisesRegex(JPVRFormatError, "16 MiB"):
            self.make_pages(bytes(16 * 1024 * 1024 + 1))

    def test_page_count_limit_is_exposed(self):
        self.assertEqual(MAX_BLOB_PAGES, 8192)


if __name__ == "__main__":
    unittest.main(verbosity=2)
