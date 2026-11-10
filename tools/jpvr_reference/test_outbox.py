import struct
import unittest

from .codec import JPVRFormatError, PageType, encode_page
from . import (
    DecodedOutboxPage as PackageDecodedOutboxPage,
    OutboxEvent as PackageOutboxEvent,
    decode_outbox_page as package_decode_outbox_page,
    decode_outbox_payload as package_decode_outbox_payload,
    encode_outbox_page as package_encode_outbox_page,
    encode_outbox_payload as package_encode_outbox_payload,
    validate_outbox_sequence as package_validate_outbox_sequence,
)
from .manifest import PageRef
from .outbox import (
    MAX_OUTBOX_EVENT_PAYLOAD_BYTES,
    OUTBOX_MAGIC,
    OUTBOX_PREFIX,
    OUTBOX_PREFIX_BYTES,
    OUTBOX_VERSION,
    DecodedOutboxPage,
    OutboxEvent,
    decode_outbox_page,
    decode_outbox_payload,
    encode_outbox_page,
    encode_outbox_payload,
    validate_outbox_sequence,
)
from .records import TLVField, WireType, encode_tlv


EVENT = bytes.fromhex("01010101010101010101010101010101")
IDEM = bytes.fromhex("02020202020202020202020202020202")
OP = bytes.fromhex("03030303030303030303030303030303")
COMMIT = bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")


def event(**kwargs):
    values = dict(
        event_id=EVENT,
        idempotency_key=IDEM,
        operation_id=OP,
        event_type="neuron.state.changed",
        event_payload=b"state-v2",
        sequence=1,
        commit_id=COMMIT,
        commit_ref=PageRef(20, 2),
    )
    values.update(kwargs)
    return OutboxEvent(**values)


class OutboxPackageExportTests(unittest.TestCase):
    def test_outbox_public_api_is_exported_from_package(self):
        self.assertIs(PackageOutboxEvent, OutboxEvent)
        self.assertIs(PackageDecodedOutboxPage, DecodedOutboxPage)
        self.assertIs(package_encode_outbox_payload, encode_outbox_payload)
        self.assertIs(package_decode_outbox_payload, decode_outbox_payload)
        self.assertIs(package_encode_outbox_page, encode_outbox_page)
        self.assertIs(package_decode_outbox_page, decode_outbox_page)
        self.assertIs(package_validate_outbox_sequence, validate_outbox_sequence)

class OutboxPayloadTests(unittest.TestCase):
    def test_payload_round_trip_without_previous_reference(self):
        value = event()
        self.assertEqual(decode_outbox_payload(encode_outbox_payload(value)), value)

    def test_payload_round_trip_with_previous_reference(self):
        value = event(sequence=2, previous_event_ref=PageRef(18, 1))
        self.assertEqual(decode_outbox_payload(encode_outbox_payload(value)), value)

    def test_payload_skips_unknown_optional_field(self):
        raw = encode_outbox_payload(event())
        raw += encode_tlv([TLVField(500, WireType.U16, 9, optional=True)])
        self.assertEqual(decode_outbox_payload(raw), event())

    def test_payload_rejects_unknown_mandatory_field(self):
        raw = encode_outbox_payload(event())
        raw += encode_tlv([TLVField(500, WireType.U16, 9)])
        with self.assertRaisesRegex(JPVRFormatError, "unknown mandatory"):
            decode_outbox_payload(raw)

    def test_payload_rejects_bad_magic_version_and_prefix(self):
        raw = bytearray(encode_outbox_payload(event()))
        raw[:4] = b"NOPE"
        with self.assertRaisesRegex(JPVRFormatError, "magic or version"):
            decode_outbox_payload(raw)
        raw = bytearray(encode_outbox_payload(event()))
        struct.pack_into("<H", raw, 4, OUTBOX_VERSION + 1)
        with self.assertRaisesRegex(JPVRFormatError, "magic or version"):
            decode_outbox_payload(raw)
        raw = bytearray(encode_outbox_payload(event()))
        struct.pack_into("<H", raw, 6, OUTBOX_PREFIX_BYTES + 1)
        with self.assertRaisesRegex(JPVRFormatError, "prefix size"):
            decode_outbox_payload(raw)

    def test_payload_rejects_invalid_lengths(self):
        for raw in (b"", bytes(7), bytes(4033)):
            with self.subTest(length=len(raw)):
                with self.assertRaises(JPVRFormatError):
                    decode_outbox_payload(raw)

    def test_payload_requires_all_mandatory_fields(self):
        raw = OUTBOX_PREFIX.pack(OUTBOX_MAGIC, 1, OUTBOX_PREFIX_BYTES) + encode_tlv([
            TLVField(1, WireType.ID128, EVENT),
            TLVField(2, WireType.ID128, IDEM),
        ])
        with self.assertRaisesRegex(JPVRFormatError, "missing required"):
            decode_outbox_payload(raw)

    def test_payload_rejects_optional_flag_on_known_field(self):
        raw = bytearray(encode_outbox_payload(event()))
        struct.pack_into("<B", raw, OUTBOX_PREFIX_BYTES + 3, 1)
        with self.assertRaisesRegex(JPVRFormatError, "OPTIONAL flag"):
            decode_outbox_payload(raw)

    def test_payload_rejects_bad_page_ref_length(self):
        fields = [
            TLVField(1, WireType.ID128, EVENT),
            TLVField(2, WireType.ID128, IDEM),
            TLVField(3, WireType.ID128, OP),
            TLVField(4, WireType.UTF8, "event"),
            TLVField(5, WireType.BYTES, b""),
            TLVField(6, WireType.U64, 1),
            TLVField(7, WireType.ID128, COMMIT),
            TLVField(8, WireType.BYTES, b"short"),
        ]
        raw = OUTBOX_PREFIX.pack(OUTBOX_MAGIC, 1, OUTBOX_PREFIX_BYTES) + encode_tlv(fields)
        with self.assertRaisesRegex(JPVRFormatError, "exactly 16 bytes"):
            decode_outbox_payload(raw)

    def test_payload_validates_nonzero_ids(self):
        for field in ("event_id", "idempotency_key", "operation_id", "commit_id"):
            values = dict(event_id=EVENT, idempotency_key=IDEM, operation_id=OP, commit_id=COMMIT)
            values[field] = bytes(16)
            with self.subTest(field=field):
                with self.assertRaisesRegex(JPVRFormatError, "must be nonzero"):
                    encode_outbox_payload(event(**values))

    def test_payload_validates_event_type_and_size(self):
        for value in ("", "x" * 129):
            with self.subTest(value_length=len(value)):
                with self.assertRaisesRegex(JPVRFormatError, "event_type"):
                    encode_outbox_payload(event(event_type=value))
        with self.assertRaisesRegex(JPVRFormatError, "event_type"):
            encode_outbox_payload(event(event_type="x" * 65 + "я" * 32))

    def test_payload_rejects_non_bytes_event_payload(self):
        with self.assertRaisesRegex(JPVRFormatError, "bytes-like"):
            encode_outbox_payload(event(event_payload="not bytes"))

    def test_payload_enforces_event_payload_limit(self):
        with self.assertRaisesRegex(JPVRFormatError, "exceeds 3500"):
            encode_outbox_payload(event(event_payload=bytes(MAX_OUTBOX_EVENT_PAYLOAD_BYTES + 1)))

    def test_payload_accepts_exact_event_payload_limit(self):
        value = event(event_payload=bytes(MAX_OUTBOX_EVENT_PAYLOAD_BYTES))
        self.assertEqual(decode_outbox_payload(encode_outbox_payload(value)), value)

    def test_payload_rejects_zero_sequence(self):
        with self.assertRaisesRegex(JPVRFormatError, "sequence must be positive"):
            encode_outbox_payload(event(sequence=0))


class OutboxPageTests(unittest.TestCase):
    def test_page_round_trip_and_envelope(self):
        value = event()
        raw = encode_outbox_page(value, 18, 2, TX1)
        decoded = decode_outbox_page(raw, expected_page_no=18)
        self.assertEqual(decoded, DecodedOutboxPage(18, 2, TX1, value))

    def test_page_rejects_wrong_page_type(self):
        raw = encode_page(PageType.DATA, 18, 2, TX1, encode_outbox_payload(event()))
        with self.assertRaisesRegex(JPVRFormatError, "not OUTBOX"):
            decode_outbox_page(raw)

    def test_page_requires_source_commit_after_event(self):
        with self.assertRaisesRegex(JPVRFormatError, "must follow"):
            encode_outbox_page(event(commit_ref=PageRef(18, 2)), 18, 2, TX1)

    def test_page_requires_source_commit_generation_match(self):
        with self.assertRaisesRegex(JPVRFormatError, "own commit generation"):
            encode_outbox_page(event(commit_ref=PageRef(20, 1)), 18, 2, TX1)

    def test_page_rejects_invalid_previous_event_order(self):
        with self.assertRaisesRegex(JPVRFormatError, "must precede"):
            encode_outbox_page(event(previous_event_ref=PageRef(18, 1)), 18, 2, TX1)

    def test_page_rejects_previous_event_from_newer_generation(self):
        with self.assertRaisesRegex(JPVRFormatError, "cannot be newer"):
            encode_outbox_page(event(previous_event_ref=PageRef(17, 3)), 18, 2, TX1)

    def test_page_rejects_zero_transaction_id(self):
        with self.assertRaisesRegex(JPVRFormatError, "transaction_id must be nonzero"):
            encode_outbox_page(event(), 18, 2, bytes(16))

    def test_page_checks_outer_crc(self):
        raw = bytearray(encode_outbox_page(event(), 18, 2, TX1))
        raw[-1] ^= 1
        with self.assertRaises(JPVRFormatError):
            decode_outbox_page(raw)


class OutboxSequenceTests(unittest.TestCase):
    def decoded(self, page_no, generation, txid, value):
        return DecodedOutboxPage(page_no, generation, txid, value)

    def test_valid_adjacent_sequence_same_generation(self):
        first = self.decoded(18, 2, TX1, event(commit_ref=PageRef(23, 2)))
        second = self.decoded(
            21, 2, TX1,
            event(
                event_id=bytes.fromhex("04040404040404040404040404040404"),
                idempotency_key=bytes.fromhex("05050505050505050505050505050505"),
                sequence=2,
                commit_ref=PageRef(23, 2),
                previous_event_ref=PageRef(18, 2),
            ),
        )
        self.assertIsNone(validate_outbox_sequence(first, second))

    def test_same_generation_events_share_source_commit(self):
        first = self.decoded(18, 2, TX1, event(commit_ref=PageRef(23, 2)))
        second = self.decoded(
            21, 2, TX1,
            event(
                event_id=bytes.fromhex("04040404040404040404040404040404"),
                idempotency_key=bytes.fromhex("05050505050505050505050505050505"),
                sequence=2,
                commit_ref=PageRef(24, 2),
                previous_event_ref=PageRef(18, 2),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "share source COMMIT"):
            validate_outbox_sequence(first, second)

    def test_sequence_requires_previous_reference(self):
        first = self.decoded(18, 2, TX1, event())
        second = self.decoded(21, 2, TX1, event(sequence=2, commit_ref=PageRef(23, 2)))
        with self.assertRaisesRegex(JPVRFormatError, "does not reference"):
            validate_outbox_sequence(first, second)

    def test_sequence_must_be_contiguous(self):
        first = self.decoded(18, 2, TX1, event())
        second = self.decoded(
            21, 2, TX1,
            event(sequence=3, commit_ref=PageRef(23, 2), previous_event_ref=PageRef(18, 2)),
        )
        with self.assertRaisesRegex(JPVRFormatError, "increase by exactly one"):
            validate_outbox_sequence(first, second)

    def test_sequence_rejects_page_order_regression(self):
        first = self.decoded(18, 2, TX1, event())
        current = self.decoded(
            17, 2, TX1,
            event(sequence=2, commit_ref=PageRef(23, 2), previous_event_ref=PageRef(18, 2)),
        )
        with self.assertRaisesRegex(JPVRFormatError, "previous OUTBOX event must precede"):
            validate_outbox_sequence(first, current)

    def test_sequence_rejects_generation_regression(self):
        first = self.decoded(18, 2, TX1, event())
        current = self.decoded(
            21, 1, TX2,
            event(
                event_id=bytes.fromhex("04040404040404040404040404040404"),
                idempotency_key=bytes.fromhex("05050505050505050505050505050505"),
                sequence=2,
                commit_ref=PageRef(23, 1),
                previous_event_ref=PageRef(18, 2),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "cannot be newer than this page"):
            validate_outbox_sequence(first, current)

    def test_sequence_rejects_adjacent_duplicate_event_id(self):
        first = self.decoded(18, 2, TX1, event())
        current = self.decoded(
            21, 2, TX1,
            event(
                idempotency_key=bytes.fromhex("05050505050505050505050505050505"),
                sequence=2,
                commit_ref=PageRef(23, 2),
                previous_event_ref=PageRef(18, 2),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "event_id must be unique"):
            validate_outbox_sequence(first, current)

    def test_sequence_rejects_adjacent_duplicate_idempotency_key(self):
        first = self.decoded(18, 2, TX1, event())
        current = self.decoded(
            21, 2, TX1,
            event(
                event_id=bytes.fromhex("04040404040404040404040404040404"),
                sequence=2,
                commit_ref=PageRef(23, 2),
                previous_event_ref=PageRef(18, 2),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "idempotency_key must be unique"):
            validate_outbox_sequence(first, current)

    def test_same_generation_requires_same_transaction_id(self):
        first = self.decoded(18, 2, TX1, event())
        current = self.decoded(
            21, 2, TX2,
            event(
                event_id=bytes.fromhex("04040404040404040404040404040404"),
                idempotency_key=bytes.fromhex("05050505050505050505050505050505"),
                sequence=2,
                commit_ref=PageRef(23, 2),
                previous_event_ref=PageRef(18, 2),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "same-generation OUTBOX pages"):
            validate_outbox_sequence(first, current)

    def test_same_generation_events_share_commit_id(self):
        first = self.decoded(18, 2, TX1, event(commit_ref=PageRef(23, 2)))
        second = self.decoded(
            21, 2, TX1,
            event(
                event_id=bytes.fromhex("04040404040404040404040404040404"),
                idempotency_key=bytes.fromhex("05050505050505050505050505050505"),
                commit_id=bytes.fromhex("cccccccccccccccccccccccccccccccc"),
                sequence=2,
                commit_ref=PageRef(23, 2),
                previous_event_ref=PageRef(18, 2),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "share commit_id"):
            validate_outbox_sequence(first, second)

    def test_sequence_rejects_malformed_decoded_page(self):
        malformed = self.decoded(1, 2, TX1, event())
        current = self.decoded(
            21, 2, TX1,
            event(sequence=2, commit_ref=PageRef(23, 2), previous_event_ref=PageRef(1, 2)),
        )
        with self.assertRaisesRegex(JPVRFormatError, "invalid OUTBOX page number"):
            validate_outbox_sequence(malformed, current)

    def test_previous_generation_commit_must_precede_next_event(self):
        first = self.decoded(18, 1, TX1, event(commit_ref=PageRef(25, 1)))
        current = self.decoded(
            21, 2, TX2,
            event(
                event_id=bytes.fromhex("04040404040404040404040404040404"),
                idempotency_key=bytes.fromhex("05050505050505050505050505050505"),
                sequence=2,
                commit_ref=PageRef(23, 2),
                previous_event_ref=PageRef(18, 1),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "previous generation COMMIT"):
            validate_outbox_sequence(first, current)

    def test_new_generation_requires_new_transaction_id(self):
        first = self.decoded(18, 1, TX1, event(commit_ref=PageRef(20, 1)))
        second = self.decoded(
            21, 2, TX1,
            event(
                event_id=bytes.fromhex("04040404040404040404040404040404"),
                idempotency_key=bytes.fromhex("05050505050505050505050505050505"),
                sequence=2,
                commit_ref=PageRef(23, 2),
                previous_event_ref=PageRef(18, 1),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "new-generation"):
            validate_outbox_sequence(first, second)
