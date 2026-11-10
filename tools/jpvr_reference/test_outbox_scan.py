import unittest
from dataclasses import replace

from .codec import JPVRFormatError, PageType, Superblock, encode_page
from .commit import CommitRecord, encode_commit_page
from .manifest import INDEX_ROOT_NAMES, Manifest, PageRef, encode_manifest_page
from .outbox import OutboxEvent, encode_outbox_page
from .outbox_scan import scan_committed_outbox


UUID = bytes.fromhex("00112233445566778899aabbccddeeff")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")
COMMIT1 = bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
COMMIT2 = bytes.fromhex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
OP1 = bytes.fromhex("cccccccccccccccccccccccccccccccc")
OP2 = bytes.fromhex("dddddddddddddddddddddddddddddddd")
EVENT1 = bytes.fromhex("01010101010101010101010101010101")
EVENT2 = bytes.fromhex("02020202020202020202020202020202")
IDEM1 = bytes.fromhex("03030303030303030303030303030303")
IDEM2 = bytes.fromhex("04040404040404040404040404040404")


def make_event(
    *,
    event_id=EVENT1,
    idempotency_key=IDEM1,
    sequence=1,
    commit_id=COMMIT1,
    commit_ref=PageRef(11, 1),
    previous_event_ref=None,
):
    return OutboxEvent(
        event_id=event_id,
        idempotency_key=idempotency_key,
        operation_id=OP1 if sequence == 1 else OP2,
        event_type="neuron.state.changed",
        event_payload=b"snapshot",
        sequence=sequence,
        commit_id=commit_id,
        commit_ref=commit_ref,
        previous_event_ref=previous_event_ref,
    )


def make_snapshot(*, two_generations=True, second_event=None, high_water=None):
    catalog = {}
    roots = {
        name: PageRef(page_no, 1)
        for name, page_no in zip(INDEX_ROOT_NAMES, range(2, 9))
    }
    for page_no in range(2, 9):
        catalog[page_no] = encode_page(
            PageType.INDEX, page_no, 1, TX1, b"root"
        )

    first_event = make_event()
    catalog[9] = encode_outbox_page(first_event, 9, 1, TX1)
    manifest1 = Manifest(
        UUID, 1, roots, outbox_high_water_page_no=9
    )
    catalog[10] = encode_manifest_page(manifest1, 10, TX1)
    commit1 = CommitRecord(
        1, TX1, COMMIT1, OP1, PageRef(10, 1), 11
    )
    catalog[11] = encode_commit_page(commit1, 11)

    if not two_generations:
        return Superblock(UUID, 1, 11, 0, 11, COMMIT1, 1), catalog

    if second_event is None:
        second_event = make_event(
            event_id=EVENT2,
            idempotency_key=IDEM2,
            sequence=2,
            commit_id=COMMIT2,
            commit_ref=PageRef(14, 2),
            previous_event_ref=PageRef(9, 1),
        )
    catalog[12] = encode_outbox_page(second_event, 12, 2, TX2)
    manifest2 = Manifest(
        UUID,
        2,
        roots,
        outbox_high_water_page_no=12 if high_water is None else high_water,
    )
    catalog[13] = encode_manifest_page(manifest2, 13, TX2)
    commit2 = CommitRecord(
        2,
        TX2,
        COMMIT2,
        OP2,
        PageRef(13, 2),
        14,
        PageRef(11, 1),
    )
    catalog[14] = encode_commit_page(commit2, 14)
    return Superblock(UUID, 2, 14, 0, 14, COMMIT2, 1), catalog


class OutboxSnapshotScannerTests(unittest.TestCase):
    def test_scans_one_generation_and_one_committed_event(self):
        superblock, catalog = make_snapshot(two_generations=False)
        result = scan_committed_outbox(superblock, catalog)
        self.assertEqual([item.record.generation for item in result.commits], [1])
        self.assertEqual([item.event.sequence for item in result.events], [1])
        self.assertEqual(result.events[0].page_no, 9)

    def test_scans_full_commit_and_outbox_chains_across_generations(self):
        superblock, catalog = make_snapshot()
        result = scan_committed_outbox(superblock, catalog)
        self.assertEqual([item.record.generation for item in result.commits], [1, 2])
        self.assertEqual([item.event.sequence for item in result.events], [1, 2])
        self.assertEqual([item.page_no for item in result.events], [9, 12])

    def test_accepts_empty_outbox_when_high_water_is_zero(self):
        superblock, catalog = make_snapshot(two_generations=False)
        catalog[9] = encode_page(PageType.DATA, 9, 1, TX1, b"unused")
        manifest = Manifest(
            UUID,
            1,
            {name: PageRef(page_no, 1) for name, page_no in zip(INDEX_ROOT_NAMES, range(2, 9))},
            outbox_high_water_page_no=0,
        )
        catalog[10] = encode_manifest_page(manifest, 10, TX1)
        catalog[11] = encode_commit_page(
            CommitRecord(1, TX1, COMMIT1, OP1, PageRef(10, 1), 11), 11
        )
        result = scan_committed_outbox(superblock, catalog)
        self.assertEqual(result.events, ())

    def test_rejects_missing_page_from_durable_catalog(self):
        superblock, catalog = make_snapshot()
        del catalog[7]
        with self.assertRaisesRegex(JPVRFormatError, "not complete|has a gap"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_page_catalog_entry_beyond_active_tail(self):
        superblock, catalog = make_snapshot()
        catalog[15] = encode_page(PageType.DATA, 15, 2, TX2, b"tail")
        with self.assertRaisesRegex(JPVRFormatError, "beyond the active durable tail"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_corrupt_page_even_when_not_an_outbox_page(self):
        superblock, catalog = make_snapshot()
        damaged = bytearray(catalog[2])
        damaged[-1] ^= 1
        catalog[2] = bytes(damaged)
        with self.assertRaisesRegex(JPVRFormatError, "CRC32C"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_superblock_that_does_not_select_catalog_commit(self):
        superblock, catalog = make_snapshot()
        wrong = replace(superblock, active_commit_id=COMMIT1)
        with self.assertRaisesRegex(JPVRFormatError, "active_commit_id"):
            scan_committed_outbox(wrong, catalog)

    def test_rejects_missing_commit_from_active_chain(self):
        superblock, catalog = make_snapshot()
        del catalog[11]
        with self.assertRaisesRegex(JPVRFormatError, "not complete|has a gap"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_outbox_sequence_gap(self):
        broken = make_event(
            event_id=EVENT2,
            idempotency_key=IDEM2,
            sequence=3,
            commit_id=COMMIT2,
            commit_ref=PageRef(14, 2),
            previous_event_ref=PageRef(9, 1),
        )
        superblock, catalog = make_snapshot(second_event=broken)
        with self.assertRaisesRegex(JPVRFormatError, "increase by exactly one"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_duplicate_event_id_across_chain(self):
        duplicate = make_event(
            event_id=EVENT1,
            idempotency_key=IDEM2,
            sequence=2,
            commit_id=COMMIT2,
            commit_ref=PageRef(14, 2),
            previous_event_ref=PageRef(9, 1),
        )
        superblock, catalog = make_snapshot(second_event=duplicate)
        with self.assertRaisesRegex(JPVRFormatError, "duplicate event_id"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_duplicate_idempotency_key_across_chain(self):
        duplicate = make_event(
            event_id=EVENT2,
            idempotency_key=IDEM1,
            sequence=2,
            commit_id=COMMIT2,
            commit_ref=PageRef(14, 2),
            previous_event_ref=PageRef(9, 1),
        )
        superblock, catalog = make_snapshot(second_event=duplicate)
        with self.assertRaisesRegex(JPVRFormatError, "duplicate idempotency_key"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_manifest_high_water_that_skips_durable_event(self):
        superblock, catalog = make_snapshot(high_water=9)
        with self.assertRaisesRegex(JPVRFormatError, "does not cover every durable OUTBOX page"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_missing_predecessor_link(self):
        unlinked = make_event(
            event_id=EVENT2,
            idempotency_key=IDEM2,
            sequence=1,
            commit_id=COMMIT2,
            commit_ref=PageRef(14, 2),
        )
        superblock, catalog = make_snapshot(second_event=unlinked)
        with self.assertRaisesRegex(JPVRFormatError, "does not cover every durable OUTBOX page"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_non_outbox_high_water_page(self):
        superblock, catalog = make_snapshot(two_generations=False)
        catalog[9] = encode_page(PageType.DATA, 9, 1, TX1, b"not outbox")
        with self.assertRaisesRegex(JPVRFormatError, "not OUTBOX"):
            scan_committed_outbox(superblock, catalog)

    def test_rejects_source_commit_not_in_active_commit_chain(self):
        superblock, catalog = make_snapshot()
        # Point the first event at a valid page of the wrong type/generation.
        invalid_first = make_event(commit_ref=PageRef(10, 1))
        catalog[9] = encode_outbox_page(invalid_first, 9, 1, TX1)
        with self.assertRaisesRegex(JPVRFormatError, "not COMMIT"):
            scan_committed_outbox(superblock, catalog)


if __name__ == "__main__":
    unittest.main()
