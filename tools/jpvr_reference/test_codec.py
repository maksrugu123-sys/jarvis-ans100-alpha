import struct
import unittest

from .codec import (
    PAGE_HEADER_BYTES,
    PAGE_PAYLOAD_BYTES,
    PAGE_SIZE,
    JPVRFormatError,
    PageType,
    Superblock,
    crc32c,
    decode_page,
    decode_superblock,
    encode_page,
    encode_superblock,
)


class CRC32CTests(unittest.TestCase):
    def test_castagnoli_check_vector(self):
        self.assertEqual(crc32c(b"123456789"), 0xE3069283)

    def test_crc32c_accepts_bytes_like_inputs(self):
        expected = crc32c(b"jpvr")
        self.assertEqual(crc32c(bytearray(b"jpvr")), expected)
        self.assertEqual(crc32c(memoryview(b"jpvr")), expected)


class SuperblockCodecTests(unittest.TestCase):
    def make_genesis(self, slot_id=0):
        return Superblock(
            container_uuid=bytes.fromhex("00112233445566778899aabbccddeeff"),
            generation=0,
            active_commit_page_no=0,
            checkpoint_page_no=0,
            durable_tail_page_no=1,
            active_commit_id=bytes(16),
            slot_id=slot_id,
        )

    def test_genesis_superblock_round_trip_for_both_slots(self):
        for slot_id in (0, 1):
            encoded = encode_superblock(self.make_genesis(slot_id))
            self.assertEqual(len(encoded), PAGE_SIZE)
            decoded = decode_superblock(encoded, expected_slot_id=slot_id)
            self.assertEqual(decoded, self.make_genesis(slot_id))

    def test_committed_superblock_round_trip(self):
        block = Superblock(
            container_uuid=bytes.fromhex("00112233445566778899aabbccddeeff"),
            generation=7,
            active_commit_page_no=40,
            checkpoint_page_no=24,
            durable_tail_page_no=44,
            active_commit_id=bytes.fromhex("ffeeddccbbaa99887766554433221100"),
            slot_id=1,
        )
        self.assertEqual(decode_superblock(encode_superblock(block)), block)

    def test_superblock_rejects_wrong_size(self):
        with self.assertRaises(JPVRFormatError):
            decode_superblock(bytes(PAGE_SIZE - 1))

    def test_superblock_rejects_crc_corruption(self):
        raw = bytearray(encode_superblock(self.make_genesis()))
        raw[24] ^= 0x01
        with self.assertRaisesRegex(JPVRFormatError, "CRC32C"):
            decode_superblock(raw)

    def test_superblock_rejects_nonzero_reserved_bytes(self):
        raw = bytearray(encode_superblock(self.make_genesis()))
        raw[4095] = 1
        with self.assertRaisesRegex(JPVRFormatError, "reserved"):
            decode_superblock(raw)

    def test_superblock_rejects_invalid_empty_state(self):
        invalid = Superblock(
            container_uuid=bytes(16),
            generation=1,
            active_commit_page_no=0,
            checkpoint_page_no=0,
            durable_tail_page_no=1,
            active_commit_id=bytes(16),
            slot_id=0,
        )
        with self.assertRaises(JPVRFormatError):
            encode_superblock(invalid)

    def test_superblock_rejects_wrong_physical_slot(self):
        raw = encode_superblock(self.make_genesis(slot_id=0))
        with self.assertRaisesRegex(JPVRFormatError, "physical slot"):
            decode_superblock(raw, expected_slot_id=1)

    def test_superblock_rejects_boolean_integer_fields(self):
        invalid = Superblock(
            container_uuid=bytes(16),
            generation=True,
            active_commit_page_no=0,
            checkpoint_page_no=0,
            durable_tail_page_no=1,
            active_commit_id=bytes(16),
            slot_id=0,
        )
        with self.assertRaises(JPVRFormatError):
            encode_superblock(invalid)


class PageCodecTests(unittest.TestCase):
    TX_ID = bytes.fromhex("1234567890abcdef1234567890abcdef")

    def test_page_round_trip_and_zero_padding(self):
        payload = b"hello JPVR"
        encoded = encode_page(PageType.DATA, 2, 1, self.TX_ID, payload)
        self.assertEqual(len(encoded), PAGE_SIZE)
        self.assertEqual(encoded[PAGE_HEADER_BYTES:PAGE_HEADER_BYTES + len(payload)], payload)
        self.assertEqual(encoded[PAGE_HEADER_BYTES + len(payload):], bytes(PAGE_PAYLOAD_BYTES - len(payload)))
        decoded = decode_page(encoded, expected_page_no=2)
        self.assertEqual(decoded.page_type, PageType.DATA)
        self.assertEqual(decoded.page_no, 2)
        self.assertEqual(decoded.commit_generation, 1)
        self.assertEqual(decoded.transaction_id, self.TX_ID)
        self.assertEqual(decoded.payload, payload)

    def test_page_round_trip_full_payload(self):
        payload = bytes((index % 251 for index in range(PAGE_PAYLOAD_BYTES)))
        decoded = decode_page(encode_page(PageType.BLOB, 3, 2, self.TX_ID, payload))
        self.assertEqual(decoded.payload, payload)

    def test_page_rejects_wrong_size(self):
        with self.assertRaises(JPVRFormatError):
            decode_page(bytes(PAGE_SIZE + 1))

    def test_page_rejects_payload_crc_corruption(self):
        raw = bytearray(encode_page(PageType.DATA, 2, 1, self.TX_ID, b"x"))
        raw[-1] ^= 1
        with self.assertRaisesRegex(JPVRFormatError, "payload CRC32C"):
            decode_page(raw)

    def test_page_rejects_header_crc_corruption(self):
        raw = bytearray(encode_page(PageType.DATA, 2, 1, self.TX_ID, b"x"))
        raw[16] ^= 1
        with self.assertRaisesRegex(JPVRFormatError, "header CRC32C"):
            decode_page(raw)

    def test_page_rejects_nonzero_padding_even_with_valid_crcs(self):
        raw = bytearray(encode_page(PageType.DATA, 2, 1, self.TX_ID, b"x"))
        raw[PAGE_HEADER_BYTES + 1] = 1
        struct.pack_into("<I", raw, 52, crc32c(raw[PAGE_HEADER_BYTES:]))
        struct.pack_into("<I", raw, 56, 0)
        struct.pack_into("<I", raw, 56, crc32c(raw[:PAGE_HEADER_BYTES]))
        with self.assertRaisesRegex(JPVRFormatError, "padding"):
            decode_page(raw)

    def test_page_rejects_physical_page_number_mismatch(self):
        raw = encode_page(PageType.DATA, 2, 1, self.TX_ID, b"x")
        with self.assertRaisesRegex(JPVRFormatError, "physical page"):
            decode_page(raw, expected_page_no=3)

    def test_page_rejects_unknown_type_and_invalid_bounds(self):
        with self.assertRaises(JPVRFormatError):
            encode_page(9, 2, 1, self.TX_ID, b"x")
        with self.assertRaises(JPVRFormatError):
            encode_page(PageType.DATA, 1, 1, self.TX_ID, b"x")
        with self.assertRaises(JPVRFormatError):
            encode_page(PageType.DATA, 2, 0, self.TX_ID, b"x")
        with self.assertRaises(JPVRFormatError):
            encode_page(PageType.DATA, 2, 1, self.TX_ID, bytes(PAGE_PAYLOAD_BYTES + 1))

    def test_page_rejects_boolean_numeric_fields(self):
        with self.assertRaises(JPVRFormatError):
            encode_page(PageType.DATA, True, 1, self.TX_ID, b"x")
        with self.assertRaises(JPVRFormatError):
            encode_page(PageType.DATA, 2, True, self.TX_ID, b"x")


if __name__ == "__main__":
    unittest.main(verbosity=2)
