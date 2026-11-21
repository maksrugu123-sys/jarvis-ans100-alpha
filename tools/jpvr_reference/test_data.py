import unittest

from .codec import JPVRFormatError, PageType, decode_page, encode_page
from .data import decode_data_page, encode_data_page
from .records import decode_record, encode_record


class DataPageCodecTests(unittest.TestCase):
    TX_ID = bytes.fromhex("1234567890abcdef1234567890abcdef")
    OBJECT_ID = bytes.fromhex("00112233445566778899aabbccddeeff")

    def record(self, record_type=1, payload=b"state"):
        return encode_record(record_type, 1001, 1, self.OBJECT_ID, 1, payload)

    def test_single_record_round_trip(self):
        encoded_record = self.record()
        page = encode_data_page((encoded_record,), 12, 3, self.TX_ID)
        decoded = decode_data_page(page, expected_page_no=12)
        self.assertEqual(len(decoded.records), 1)
        self.assertEqual(decoded.records[0].payload, b"state")
        self.assertEqual(decoded.records[0].record_type, 1)
        self.assertEqual(decoded.transaction_id, self.TX_ID)
        self.assertEqual(decoded.commit_generation, 3)

    def test_multiple_records_preserve_order(self):
        records = (self.record(1, b"first"), self.record(2, b"second"), self.record(3, b"third"))
        decoded = decode_data_page(encode_data_page(records, 12, 3, self.TX_ID))
        self.assertEqual([item.record_type for item in decoded.records], [1, 2, 3])
        self.assertEqual([item.payload for item in decoded.records], [b"first", b"second", b"third"])

    def test_empty_data_page_round_trip(self):
        decoded = decode_data_page(encode_data_page((), 12, 3, self.TX_ID))
        self.assertEqual(decoded.records, ())

    def test_encoder_rejects_noncanonical_record_with_trailing_bytes(self):
        record = self.record()
        with self.assertRaisesRegex(JPVRFormatError, "exactly one"):
            encode_data_page((record + bytes(8),), 12, 3, self.TX_ID)

    def test_encoder_rejects_invalid_record(self):
        with self.assertRaises(JPVRFormatError):
            encode_data_page((b"not-a-record",), 12, 3, self.TX_ID)

    def test_encoder_rejects_non_bytes_record(self):
        with self.assertRaisesRegex(JPVRFormatError, "bytes-like"):
            encode_data_page((object(),), 12, 3, self.TX_ID)

    def test_encoder_rejects_records_over_page_capacity(self):
        large_a = self.record(payload=b"a" * 2000)
        large_b = self.record(payload=b"b" * 2000)
        with self.assertRaisesRegex(JPVRFormatError, "capacity"):
            encode_data_page((large_a, large_b), 12, 3, self.TX_ID)

    def test_decoder_rejects_non_data_page(self):
        blob_like = encode_page(PageType.BLOB, 12, 3, self.TX_ID, bytes(4032))
        with self.assertRaisesRegex(JPVRFormatError, "not DATA"):
            decode_data_page(blob_like)

    def test_decoder_rejects_short_data_payload(self):
        short_page = encode_page(PageType.DATA, 12, 3, self.TX_ID, b"short")
        with self.assertRaisesRegex(JPVRFormatError, "exactly 4032"):
            decode_data_page(short_page)

    def test_decoder_checks_physical_page_number(self):
        page = encode_data_page((self.record(),), 12, 3, self.TX_ID)
        with self.assertRaisesRegex(JPVRFormatError, "physical page"):
            decode_data_page(page, expected_page_no=13)

    def test_outer_page_crc_is_checked(self):
        page = bytearray(encode_data_page((self.record(),), 12, 3, self.TX_ID))
        page[-1] ^= 1
        with self.assertRaisesRegex(JPVRFormatError, "payload CRC32C"):
            decode_data_page(page)

    def test_record_crc_is_checked_after_outer_page_rechecksum(self):
        encoded_record = bytearray(self.record())
        encoded_record[56] ^= 1
        payload = bytearray(4032)
        payload[:len(encoded_record)] = encoded_record
        page = encode_page(PageType.DATA, 12, 3, self.TX_ID, payload)
        with self.assertRaisesRegex(JPVRFormatError, "JREC payload CRC32C"):
            decode_data_page(page)

    def test_nonzero_trailing_bytes_are_not_silently_ignored(self):
        encoded_record = self.record()
        decoded = decode_record(encoded_record)
        payload = bytearray(4032)
        payload[:len(encoded_record)] = encoded_record
        payload[decoded.next_offset + 3] = 1
        page = encode_page(PageType.DATA, 12, 3, self.TX_ID, payload)
        with self.assertRaises(JPVRFormatError):
            decode_data_page(page)

    def test_multiple_record_page_never_exceeds_data_payload(self):
        records = tuple(self.record(index + 1, bytes([index]) * 16) for index in range(20))
        page = encode_data_page(records, 12, 3, self.TX_ID)
        self.assertEqual(len(page), 4096)
        self.assertEqual(len(decode_data_page(page).records), 20)


if __name__ == "__main__":
    unittest.main(verbosity=2)
