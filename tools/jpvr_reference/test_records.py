import struct
import unittest

from .codec import JPVRFormatError
from .records import (
    MAX_SMALL_RECORD_PAYLOAD,
    RECORD_HEADER_BYTES,
    TLVField,
    WireType,
    decode_record,
    decode_tlv,
    encode_record,
    encode_tlv,
)


class TLVCodecTests(unittest.TestCase):
    def test_all_wire_types_round_trip(self):
        fields = [
            TLVField(11, WireType.UTF8, "JARVIS ✓"),
            TLVField(10, WireType.BYTES, b"\x00\xff"),
            TLVField(9, WireType.ID128, bytes(range(16))),
            TLVField(8, WireType.F64, 3.125),
            TLVField(7, WireType.F32, 1.5),
            TLVField(6, WireType.BOOL, True),
            TLVField(5, WireType.I64, -(1 << 40)),
            TLVField(4, WireType.U64, (1 << 63) + 3),
            TLVField(3, WireType.U32, 0xFEDCBA98),
            TLVField(2, WireType.U16, 65530),
            TLVField(1, WireType.U8, 255),
        ]
        encoded = encode_tlv(fields)
        decoded = decode_tlv(encoded)
        self.assertEqual([field.field_id for field in decoded], list(range(1, 12)))
        self.assertEqual(decoded[0].value, 255)
        self.assertEqual(decoded[4].value, -(1 << 40))
        self.assertEqual(decoded[5].value, True)
        self.assertEqual(decoded[8].value, bytes(range(16)))
        self.assertEqual(decoded[10].value, "JARVIS ✓")

    def test_unknown_optional_field_is_skipped_with_schema_map(self):
        encoded = encode_tlv([
            TLVField(1, WireType.U16, 7),
            TLVField(99, WireType.BYTES, b"future", optional=True),
        ])
        decoded = decode_tlv(encoded, known_fields={1: WireType.U16})
        self.assertEqual(decoded, (TLVField(1, WireType.U16, 7),))

    def test_unknown_mandatory_field_is_rejected_with_schema_map(self):
        encoded = encode_tlv([TLVField(99, WireType.U8, 1)])
        with self.assertRaisesRegex(JPVRFormatError, "unknown mandatory"):
            decode_tlv(encoded, known_fields={1: WireType.U8})

    def test_schema_wire_type_mismatch_is_rejected(self):
        encoded = encode_tlv([TLVField(1, WireType.U32, 7)])
        with self.assertRaisesRegex(JPVRFormatError, "does not match schema"):
            decode_tlv(encoded, known_fields={1: WireType.U16})

    def test_duplicate_or_zero_field_ids_are_rejected(self):
        with self.assertRaises(JPVRFormatError):
            encode_tlv([TLVField(1, WireType.U8, 1), TLVField(1, WireType.U8, 2)])
        with self.assertRaises(JPVRFormatError):
            encode_tlv([TLVField(0, WireType.U8, 1)])

    def test_decoder_rejects_non_increasing_field_ids(self):
        first = TLVField(2, WireType.U8, 1)
        second = TLVField(3, WireType.U8, 2)
        encoded = encode_tlv([first, second])
        second_header_offset = len(encode_tlv([first]))

        # Each one-byte value is padded to a 16-byte TLV entry. Mutate the
        # second header, not byte 8 (which belongs to the first value).
        for invalid_second_id in (2, 1):
            with self.subTest(invalid_second_id=invalid_second_id):
                raw = bytearray(encoded)
                struct.pack_into("<H", raw, second_header_offset, invalid_second_id)
                with self.assertRaisesRegex(JPVRFormatError, "strictly increasing"):
                    decode_tlv(raw)

    def test_unknown_wire_type_is_rejected_even_when_optional(self):
        raw = bytearray(struct.pack("<HBBI", 1, 99, 1, 1) + b"x" + bytes(7))
        with self.assertRaisesRegex(JPVRFormatError, "unknown TLV wire"):
            decode_tlv(raw)

    def test_tlv_padding_must_be_zero(self):
        raw = bytearray(encode_tlv([TLVField(1, WireType.U8, 1)]))
        raw[-1] = 1
        with self.assertRaisesRegex(JPVRFormatError, "padding"):
            decode_tlv(raw)

    def test_boolean_wire_value_must_be_zero_or_one(self):
        raw = bytearray(encode_tlv([TLVField(1, WireType.BOOL, True)]))
        raw[8] = 2
        with self.assertRaisesRegex(JPVRFormatError, "0 or 1"):
            decode_tlv(raw)

    def test_nonfinite_float_and_negative_zero_are_rejected(self):
        with self.assertRaises(JPVRFormatError):
            encode_tlv([TLVField(1, WireType.F64, float("nan"))])
        raw = bytearray(encode_tlv([TLVField(1, WireType.F64, 0.0)]))
        struct.pack_into("<d", raw, 8, -0.0)
        with self.assertRaisesRegex(JPVRFormatError, "negative zero"):
            decode_tlv(raw)

    def test_utf8_is_strict(self):
        raw = bytearray(struct.pack("<HBBI", 1, int(WireType.UTF8), 0, 1) + b"\xff" + bytes(7))
        with self.assertRaisesRegex(JPVRFormatError, "invalid UTF8"):
            decode_tlv(raw)

    def test_empty_tlv_payload_is_valid(self):
        self.assertEqual(decode_tlv(b""), ())


class SmallRecordCodecTests(unittest.TestCase):
    OBJECT_ID = bytes.fromhex("00112233445566778899aabbccddeeff")

    def test_record_round_trip(self):
        payload = encode_tlv([
            TLVField(1, WireType.U64, 42),
            TLVField(2, WireType.UTF8, "neuron-state"),
        ])
        encoded = encode_record(1, 1001, 1, self.OBJECT_ID, 3, payload)
        decoded = decode_record(encoded)
        self.assertEqual(decoded.record_type, 1)
        self.assertEqual(decoded.schema_id, 1001)
        self.assertEqual(decoded.schema_version, 1)
        self.assertEqual(decoded.object_id, self.OBJECT_ID)
        self.assertEqual(decoded.record_version, 3)
        self.assertEqual(decoded.payload, payload)
        self.assertEqual(decoded.next_offset, len(encoded))
        self.assertEqual(decode_tlv(decoded.payload), (
            TLVField(1, WireType.U64, 42),
            TLVField(2, WireType.UTF8, "neuron-state"),
        ))

    def test_multiple_records_pack_on_eight_byte_boundaries(self):
        first = encode_record(1, 1001, 1, self.OBJECT_ID, 1, b"abc")
        second = encode_record(2, 1002, 1, self.OBJECT_ID, 1, b"12345678")
        page_payload = first + second
        decoded_first = decode_record(page_payload)
        decoded_second = decode_record(page_payload, offset=decoded_first.next_offset)
        self.assertEqual(decoded_first.payload, b"abc")
        self.assertEqual(decoded_second.payload, b"12345678")
        self.assertEqual(decoded_second.next_offset, len(page_payload))

    def test_empty_record_payload_round_trip(self):
        encoded = encode_record(1, 1, 1, self.OBJECT_ID, 1, b"")
        self.assertEqual(decode_record(encoded).payload, b"")

    def test_record_rejects_payload_above_small_record_limit(self):
        with self.assertRaises(JPVRFormatError):
            encode_record(1, 1, 1, self.OBJECT_ID, 1, bytes(MAX_SMALL_RECORD_PAYLOAD + 1))

    def test_record_rejects_boolean_version_fields(self):
        with self.assertRaises(JPVRFormatError):
            encode_record(1, 1, True, self.OBJECT_ID, 1, b"")
        with self.assertRaises(JPVRFormatError):
            encode_record(1, 1, 1, self.OBJECT_ID, True, b"")

    def test_record_rejects_payload_crc_corruption(self):
        raw = bytearray(encode_record(1, 1, 1, self.OBJECT_ID, 1, b"abc"))
        raw[RECORD_HEADER_BYTES] ^= 1
        with self.assertRaisesRegex(JPVRFormatError, "payload CRC32C"):
            decode_record(raw)

    def test_record_rejects_header_crc_corruption(self):
        raw = bytearray(encode_record(1, 1, 1, self.OBJECT_ID, 1, b"abc"))
        raw[16] ^= 1
        with self.assertRaisesRegex(JPVRFormatError, "header CRC32C"):
            decode_record(raw)

    def test_record_rejects_nonzero_alignment_padding(self):
        raw = bytearray(encode_record(1, 1, 1, self.OBJECT_ID, 1, b"x"))
        raw[-1] = 1
        with self.assertRaisesRegex(JPVRFormatError, "padding"):
            decode_record(raw)

    def test_record_rejects_nonzero_reserved_field(self):
        raw = bytearray(encode_record(1, 1, 1, self.OBJECT_ID, 1, b"x"))
        struct.pack_into("<I", raw, 52, 1)
        with self.assertRaisesRegex(JPVRFormatError, "reserved"):
            decode_record(raw)

    def test_record_rejects_unaligned_offset_and_page_overflow(self):
        encoded = encode_record(1, 1, 1, self.OBJECT_ID, 1, b"x")
        with self.assertRaisesRegex(JPVRFormatError, "aligned"):
            decode_record(encoded, offset=1)
        with self.assertRaises(JPVRFormatError):
            decode_record(bytes(4033))

    def test_record_rejects_invalid_magic_and_flags(self):
        raw = bytearray(encode_record(1, 1, 1, self.OBJECT_ID, 1, b"x"))
        raw[0:4] = b"BAD!"
        with self.assertRaisesRegex(JPVRFormatError, "magic"):
            decode_record(raw)
        with self.assertRaises(JPVRFormatError):
            encode_record(1, 1, 1, self.OBJECT_ID, 1, b"x", flags=1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
