import struct
import unittest

from .codec import JPVRFormatError, PageType, encode_page
from .journal import (
    JOURNAL_MAGIC,
    JOURNAL_PREFIX,
    JOURNAL_PREFIX_BYTES,
    JOURNAL_VERSION,
    DecodedJournalPage,
    JournalEntry,
    OperationStatus,
    decode_journal_page,
    decode_journal_payload,
    encode_journal_page,
    encode_journal_payload,
    validate_journal_transition,
)
from .manifest import PageRef
from .records import TLVField, WireType, encode_tlv


OP = bytes.fromhex("01010101010101010101010101010101")
IDEM = bytes.fromhex("02020202020202020202020202020202")
OWNER = bytes.fromhex("03030303030303030303030303030303")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")
COMMIT_ID = bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
RESULT_ID = bytes.fromhex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")


def entry(status=OperationStatus.QUEUED, **kwargs):
    return JournalEntry(OP, IDEM, OWNER, status, **kwargs)


class JournalPayloadTests(unittest.TestCase):
    def test_payload_round_trip_required_fields(self):
        value = entry()
        raw = encode_journal_payload(value)
        decoded = decode_journal_payload(raw)
        self.assertEqual(decoded, value)
        self.assertEqual(raw[:4], JOURNAL_MAGIC)

    def test_payload_round_trip_all_optional_fields(self):
        value = entry(
            OperationStatus.COMPLETED,
            expected_state_version=4,
            new_state_version=5,
            commit_id=COMMIT_ID,
            commit_ref=PageRef(20, 2),
            previous_entry_ref=PageRef(18, 1),
            result_object_id=RESULT_ID,
        )
        self.assertEqual(decode_journal_payload(encode_journal_payload(value)), value)

    def test_payload_rejects_bad_magic_version_and_prefix_size(self):
        raw = bytearray(encode_journal_payload(entry()))
        raw[:4] = b"NOPE"
        with self.assertRaisesRegex(JPVRFormatError, "magic or version"):
            decode_journal_payload(raw)
        raw = bytearray(encode_journal_payload(entry()))
        struct.pack_into("<H", raw, 4, JOURNAL_VERSION + 1)
        with self.assertRaisesRegex(JPVRFormatError, "magic or version"):
            decode_journal_payload(raw)
        raw = bytearray(encode_journal_payload(entry()))
        struct.pack_into("<H", raw, 6, JOURNAL_PREFIX_BYTES + 1)
        with self.assertRaisesRegex(JPVRFormatError, "prefix size"):
            decode_journal_payload(raw)

    def test_payload_rejects_invalid_lengths(self):
        for raw in (b"", bytes(7), bytes(4033)):
            with self.subTest(length=len(raw)):
                with self.assertRaises(JPVRFormatError):
                    decode_journal_payload(raw)

    def test_payload_rejects_missing_required_fields(self):
        raw = JOURNAL_PREFIX.pack(JOURNAL_MAGIC, 1, JOURNAL_PREFIX_BYTES) + encode_tlv([
            TLVField(1, WireType.ID128, OP),
            TLVField(2, WireType.ID128, IDEM),
            TLVField(3, WireType.ID128, OWNER),
        ])
        with self.assertRaisesRegex(JPVRFormatError, "missing required"):
            decode_journal_payload(raw)

    def test_payload_skips_unknown_optional_fields(self):
        raw = encode_journal_payload(entry())
        raw += encode_tlv([TLVField(500, WireType.U16, 7, optional=True)])
        decoded = decode_journal_payload(raw)
        self.assertEqual(decoded, entry())

    def test_payload_rejects_unknown_mandatory_fields(self):
        raw = encode_journal_payload(entry())
        raw += encode_tlv([TLVField(500, WireType.U16, 7)])
        with self.assertRaisesRegex(JPVRFormatError, "unknown mandatory"):
            decode_journal_payload(raw)

    def test_payload_rejects_unknown_status(self):
        raw = bytearray(encode_journal_payload(entry()))
        offset = JOURNAL_PREFIX_BYTES
        field_id, wire_type, flags, length = struct.unpack_from("<HBBI", raw, offset)
        self.assertEqual(field_id, 1)
        offset += 8 + length
        offset = (offset + 7) & ~7
        field_id, wire_type, flags, length = struct.unpack_from("<HBBI", raw, offset)
        self.assertEqual(field_id, 2)
        offset += 8 + length
        offset = (offset + 7) & ~7
        field_id, wire_type, flags, length = struct.unpack_from("<HBBI", raw, offset)
        self.assertEqual(field_id, 3)
        offset += 8 + length
        offset = (offset + 7) & ~7
        field_id, wire_type, flags, length = struct.unpack_from("<HBBI", raw, offset)
        self.assertEqual(field_id, 4)
        raw[offset + 8] = 255
        with self.assertRaisesRegex(JPVRFormatError, "unknown JOURNAL operation status"):
            decode_journal_payload(raw)

    def test_payload_rejects_optional_flag_on_known_field(self):
        raw = bytearray(encode_journal_payload(entry()))
        offset = JOURNAL_PREFIX_BYTES
        struct.pack_into("<B", raw, offset + 3, 1)
        with self.assertRaisesRegex(JPVRFormatError, "OPTIONAL flag"):
            decode_journal_payload(raw)

    def test_payload_rejects_invalid_page_ref_length(self):
        fields = [
            TLVField(1, WireType.ID128, OP),
            TLVField(2, WireType.ID128, IDEM),
            TLVField(3, WireType.ID128, OWNER),
            TLVField(4, WireType.U8, int(OperationStatus.COMMITTED)),
            TLVField(7, WireType.ID128, COMMIT_ID),
            TLVField(8, WireType.BYTES, b"short"),
        ]
        raw = JOURNAL_PREFIX.pack(JOURNAL_MAGIC, 1, JOURNAL_PREFIX_BYTES) + encode_tlv(fields)
        with self.assertRaisesRegex(JPVRFormatError, "exactly 16 bytes"):
            decode_journal_payload(raw)

    def test_payload_validates_identity_and_state_version(self):
        for field, value in (
            ("operation_id", bytes(16)),
            ("idempotency_key", bytes(16)),
            ("owner_module_id", bytes(16)),
        ):
            kwargs = {"status": OperationStatus.QUEUED, field: value}
            with self.subTest(field=field):
                with self.assertRaises(JPVRFormatError):
                    encode_journal_payload(JournalEntry(**{
                        "operation_id": kwargs.get("operation_id", OP),
                        "idempotency_key": kwargs.get("idempotency_key", IDEM),
                        "owner_module_id": kwargs.get("owner_module_id", OWNER),
                        "status": kwargs["status"],
                    }))
        with self.assertRaisesRegex(JPVRFormatError, "requires expected_state_version"):
            encode_journal_payload(entry(new_state_version=3))
        with self.assertRaisesRegex(JPVRFormatError, "must exceed"):
            encode_journal_payload(entry(
                expected_state_version=3, new_state_version=3
            ))

    def test_commit_fields_must_be_paired(self):
        with self.assertRaisesRegex(JPVRFormatError, "supplied together"):
            encode_journal_payload(entry(
                OperationStatus.COMMITTED, commit_id=COMMIT_ID
            ))

    def test_committed_status_requires_commit_reference(self):
        with self.assertRaisesRegex(JPVRFormatError, "require commit_id"):
            encode_journal_payload(entry(OperationStatus.COMPLETED))

    def test_uncommitted_status_cannot_carry_commit_reference(self):
        with self.assertRaisesRegex(JPVRFormatError, "cannot carry"):
            encode_journal_payload(entry(
                OperationStatus.RUNNING,
                commit_id=COMMIT_ID,
                commit_ref=PageRef(20, 2),
            ))

    def test_result_object_requires_terminal_result(self):
        with self.assertRaisesRegex(JPVRFormatError, "terminal result"):
            encode_journal_payload(entry(
                OperationStatus.RUNNING, result_object_id=RESULT_ID
            ))


class JournalPageTests(unittest.TestCase):
    def test_page_round_trip(self):
        value = entry(
            OperationStatus.COMMITTED,
            expected_state_version=4,
            new_state_version=5,
            commit_id=COMMIT_ID,
            commit_ref=PageRef(20, 2),
        )
        page = encode_journal_page(value, 18, 2, TX2)
        decoded = decode_journal_page(page, expected_page_no=18)
        self.assertEqual(decoded.entry, value)
        self.assertEqual(decoded.transaction_id, TX2)
        self.assertEqual(decoded.commit_generation, 2)

    def test_page_rejects_wrong_page_type(self):
        raw = encode_page(PageType.DATA, 18, 2, TX2, encode_journal_payload(entry()))
        with self.assertRaisesRegex(JPVRFormatError, "not JOURNAL"):
            decode_journal_page(raw)

    def test_page_rejects_invalid_previous_entry_order(self):
        value = entry(previous_entry_ref=PageRef(18, 1))
        with self.assertRaisesRegex(JPVRFormatError, "must precede"):
            encode_journal_page(value, 18, 2, TX2)

    def test_page_rejects_previous_entry_from_newer_generation(self):
        value = entry(previous_entry_ref=PageRef(17, 3))
        with self.assertRaisesRegex(JPVRFormatError, "cannot be newer"):
            encode_journal_page(value, 18, 2, TX2)

    def test_page_rejects_same_generation_commit_before_journal(self):
        value = entry(
            OperationStatus.COMMITTED,
            commit_id=COMMIT_ID,
            commit_ref=PageRef(17, 2),
        )
        with self.assertRaisesRegex(JPVRFormatError, "must follow JOURNAL"):
            encode_journal_page(value, 18, 2, TX2)

    def test_page_allows_commit_page_after_journal_in_same_generation(self):
        value = entry(
            OperationStatus.COMMITTED,
            commit_id=COMMIT_ID,
            commit_ref=PageRef(20, 2),
        )
        self.assertEqual(
            decode_journal_page(encode_journal_page(value, 18, 2, TX2)).entry,
            value,
        )

    def test_page_allows_prior_generation_commit_reference(self):
        value = entry(
            OperationStatus.COMPLETED,
            commit_id=COMMIT_ID,
            commit_ref=PageRef(11, 1),
        )
        self.assertEqual(
            decode_journal_page(encode_journal_page(value, 18, 2, TX2)).entry,
            value,
        )

    def test_page_rejects_zero_transaction_id(self):
        with self.assertRaisesRegex(JPVRFormatError, "transaction_id must be nonzero"):
            encode_journal_page(entry(), 18, 2, bytes(16))


class JournalTransitionTests(unittest.TestCase):
    def decoded(self, page_no, generation, txid, value):
        return DecodedJournalPage(page_no, generation, txid, value)

    def test_valid_lifecycle(self):
        queued = self.decoded(
            10, 1, TX1,
            entry(expected_state_version=7),
        )
        running = self.decoded(
            11, 1, TX1, entry(
                OperationStatus.RUNNING,
                expected_state_version=7,
                previous_entry_ref=PageRef(10, 1),
            )
        )
        prepared = self.decoded(
            12, 1, TX1, entry(
                OperationStatus.PREPARED,
                expected_state_version=7,
                new_state_version=8,
                previous_entry_ref=PageRef(11, 1),
            )
        )
        committed = self.decoded(
            13, 1, TX1, entry(
                OperationStatus.COMMITTED,
                expected_state_version=7,
                new_state_version=8,
                commit_id=COMMIT_ID,
                commit_ref=PageRef(14, 1),
                previous_entry_ref=PageRef(12, 1),
            )
        )
        completed = self.decoded(
            20, 2, TX2, entry(
                OperationStatus.COMPLETED,
                expected_state_version=7,
                new_state_version=8,
                commit_id=COMMIT_ID,
                commit_ref=PageRef(14, 1),
                result_object_id=RESULT_ID,
                previous_entry_ref=PageRef(13, 1),
            )
        )
        chain = (queued, running, prepared, committed, completed)
        for previous, current in zip(chain, chain[1:]):
            validate_journal_transition(previous, current)

    def test_transition_requires_previous_page_reference(self):
        previous = self.decoded(10, 1, TX1, entry())
        current = self.decoded(11, 1, TX1, entry(OperationStatus.RUNNING))
        with self.assertRaisesRegex(JPVRFormatError, "does not reference"):
            validate_journal_transition(previous, current)

    def test_transition_rejects_wrong_operation_identity(self):
        previous = self.decoded(10, 1, TX1, entry())
        current = self.decoded(
            11, 1, TX1,
            JournalEntry(bytes.fromhex("04040404040404040404040404040404"),
                         IDEM, OWNER, OperationStatus.RUNNING,
                         previous_entry_ref=PageRef(10, 1)),
        )
        with self.assertRaisesRegex(JPVRFormatError, "identity cannot change"):
            validate_journal_transition(previous, current)

    def test_transition_rejects_illegal_status_jump(self):
        previous = self.decoded(10, 1, TX1, entry())
        current = self.decoded(
            11, 1, TX1, entry(
                OperationStatus.COMMITTED,
                commit_id=COMMIT_ID,
                commit_ref=PageRef(12, 1),
                previous_entry_ref=PageRef(10, 1),
            )
        )
        with self.assertRaisesRegex(JPVRFormatError, "invalid JOURNAL status transition"):
            validate_journal_transition(previous, current, confirmed_no_commit=True)

    def test_commit_unknown_failed_requires_explicit_confirmation(self):
        previous = self.decoded(10, 1, TX1, entry(OperationStatus.COMMIT_UNKNOWN))
        current = self.decoded(
            20, 2, TX2,
            entry(
                OperationStatus.FAILED,
                previous_entry_ref=PageRef(10, 1),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "requires confirmed_no_commit"):
            validate_journal_transition(previous, current)

    def test_queued_operation_cannot_be_deferred_after_acceptance(self):
        previous = self.decoded(10, 1, TX1, entry(OperationStatus.QUEUED))
        current = self.decoded(
            11, 1, TX1,
            entry(
                OperationStatus.DEFERRED,
                previous_entry_ref=PageRef(10, 1),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "invalid JOURNAL status transition"):
            validate_journal_transition(previous, current)

    def test_commit_unknown_can_resolve_to_committed(self):
        previous = self.decoded(10, 1, TX1, entry(OperationStatus.COMMIT_UNKNOWN))
        current = self.decoded(
            11, 1, TX1,
            entry(
                OperationStatus.COMMITTED,
                commit_id=COMMIT_ID,
                commit_ref=PageRef(12, 1),
                previous_entry_ref=PageRef(10, 1),
            ),
        )
        validate_journal_transition(previous, current)

    def test_commit_unknown_can_resolve_to_failed_after_no_commit_is_confirmed(self):
        previous = self.decoded(
            10, 1, TX1,
            entry(
                OperationStatus.COMMIT_UNKNOWN,
                commit_id=COMMIT_ID,
                commit_ref=PageRef(11, 1),
            ),
        )
        current = self.decoded(
            20, 2, TX2,
            entry(
                OperationStatus.FAILED,
                previous_entry_ref=PageRef(10, 1),
            ),
        )
        validate_journal_transition(previous, current)

    def test_transition_rejects_expected_version_change(self):
        previous = self.decoded(
            10, 1, TX1, entry(OperationStatus.RUNNING, expected_state_version=7)
        )
        current = self.decoded(
            11, 1, TX1, entry(
                OperationStatus.PREPARED,
                expected_state_version=8,
                new_state_version=9,
                previous_entry_ref=PageRef(10, 1),
            )
        )
        with self.assertRaisesRegex(JPVRFormatError, "expected_state_version cannot change"):
            validate_journal_transition(previous, current)

    def test_transition_rejects_page_order_regression(self):
        previous = self.decoded(10, 1, TX1, entry())
        current = self.decoded(
            9, 1, TX1, entry(
                OperationStatus.RUNNING, previous_entry_ref=PageRef(10, 1)
            )
        )
        with self.assertRaisesRegex(JPVRFormatError, "page order"):
            validate_journal_transition(previous, current)

    def test_transition_rejects_generation_regression(self):
        previous = self.decoded(10, 2, TX2, entry())
        current = self.decoded(
            11, 1, TX1, entry(
                OperationStatus.RUNNING, previous_entry_ref=PageRef(10, 1)
            )
        )
        with self.assertRaisesRegex(JPVRFormatError, "generation cannot move backwards"):
            validate_journal_transition(previous, current)


if __name__ == "__main__":
    unittest.main()
