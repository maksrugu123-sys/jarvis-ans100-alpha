"""Focused JPVR typed transaction builder contract tests."""
from dataclasses import replace
import unittest

from .blob import decode_blob_pages
from .codec import JPVRFormatError, Superblock
from .index import IndexEntry, IndexNode, lookup_index
from .journal import JournalEntry, OperationStatus, decode_journal_page
from .manifest import INDEX_ROOT_NAMES, PageRef
from .outbox import OutboxEvent, decode_outbox_page, validate_outbox_commit_reference
from .typed_transaction import (
    BlobRecordInput,
    DataRecordInput,
    IndexPageInput,
    IndexMutationInput,
    JournalEntryInput,
    TypedTransactionRequest,
    build_typed_transaction,
    validate_typed_transaction_plan,
)


UUID = bytes.fromhex("00112233445566778899aabbccddeeff")
TX1 = bytes.fromhex("11111111111111111111111111111111")
TX2 = bytes.fromhex("22222222222222222222222222222222")
COMMIT1 = bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
COMMIT2 = bytes.fromhex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
OP1 = bytes.fromhex("cccccccccccccccccccccccccccccccc")
OP2 = bytes.fromhex("dddddddddddddddddddddddddddddddd")
EVENT1 = bytes.fromhex("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
IDEMP1 = bytes.fromhex("ffffffffffffffffffffffffffffffff")
OWNER = bytes.fromhex("abababababababababababababababab")
OBJECT1 = bytes.fromhex("01010101010101010101010101010101")
OBJECT2 = bytes.fromhex("02020202020202020202020202020202")


def genesis_superblock():
    return Superblock(UUID, 0, 0, 0, 1, bytes(16), 0)


def root_pages():
    return tuple(
        IndexPageInput(IndexNode(bytes((i + 1,)) * 16, 0), root_name=name)
        for i, name in enumerate(INDEX_ROOT_NAMES)
    )


def journal_entry(status, *, previous=None):
    return JournalEntry(
        operation_id=OP1,
        idempotency_key=IDEMP1,
        owner_module_id=OWNER,
        status=status,
        previous_entry_ref=previous,
    )


def outbox_event(
    *, event_id=EVENT1, idempotency_key=IDEMP1, sequence=1,
    commit_id=COMMIT1, previous=None,
):
    return OutboxEvent(
        event_id=event_id,
        idempotency_key=idempotency_key,
        operation_id=OP1,
        event_type="jarvis.state.changed",
        event_payload=b'{"value":1}',
        sequence=sequence,
        commit_id=commit_id,
        commit_ref=PageRef(500, 1),
        previous_event_ref=previous,
    )


def full_genesis_request(**overrides):
    values = dict(
        current_superblock=genesis_superblock(),
        page_catalog={},
        transaction_id=TX1,
        commit_id=COMMIT1,
        operation_id=OP1,
        data_records=(
            DataRecordInput(1, 1, 1, OBJECT1, 1, b"small-record"),
        ),
        blob_records=(
            BlobRecordInput(2, 1, 1, OBJECT2, 1, b"B" * 5000),
        ),
        index_pages=root_pages(),
        journal_entries=(
            JournalEntryInput(journal_entry(OperationStatus.QUEUED)),
            JournalEntryInput(journal_entry(OperationStatus.RUNNING)),
        ),
        outbox_events=(outbox_event(),),
    )
    values.update(overrides)
    return TypedTransactionRequest(**values)


def catalog_from_plan(plan):
    return dict(zip(
        plan.assembly.ordered_page_numbers,
        plan.assembly.ordered_page_bytes,
    ))


class TypedTransactionBuilderTests(unittest.TestCase):
    def test_builds_all_typed_families_in_canonical_page_order(self):
        plan = build_typed_transaction(full_genesis_request())
        self.assertEqual(plan.data_page_numbers, (2,))
        self.assertEqual(plan.blob_page_numbers, (3, 4))
        self.assertEqual(plan.index_page_numbers, tuple(range(5, 12)))
        self.assertEqual(plan.journal_page_numbers, (12, 13))
        self.assertEqual(plan.outbox_page_numbers, (14,))
        self.assertEqual(plan.manifest_page_no, 15)
        self.assertEqual(plan.commit_page_no, 16)
        self.assertEqual(plan.page_count, 15)
        self.assertEqual(plan.new_superblock.generation, 1)
        self.assertEqual(plan.manifest.journal_high_water_page_no, 13)
        self.assertEqual(plan.manifest.outbox_high_water_page_no, 14)

        pages = plan.assembly.ordered_page_bytes
        self.assertEqual(len(decode_blob_pages(pages[1:3]).payload), 5000)
        first_journal = decode_journal_page(pages[10], expected_page_no=12)
        second_journal = decode_journal_page(pages[11], expected_page_no=13)
        self.assertEqual(
            second_journal.entry.previous_entry_ref,
            PageRef(first_journal.page_no, first_journal.commit_generation),
        )
        event_page = decode_outbox_page(pages[12], expected_page_no=14)
        self.assertEqual(event_page.event.commit_ref, PageRef(16, 1))
        validate_outbox_commit_reference(event_page, pages[-1])

    def test_data_records_are_packed_without_crossing_page_boundaries(self):
        records = (
            DataRecordInput(1, 1, 1, OBJECT1, 1, b"A" * 3976),
            DataRecordInput(1, 1, 1, OBJECT2, 1, b"B" * 3976),
        )
        plan = build_typed_transaction(
            full_genesis_request(
                data_records=records, blob_records=(), journal_entries=(),
                outbox_events=(),
            )
        )
        self.assertEqual(plan.data_page_numbers, (2, 3))
        self.assertEqual(len(plan.index_page_numbers), 7)
        self.assertEqual(plan.manifest_page_no, 11)

    def test_second_generation_inherits_roots_and_high_water_marks(self):
        first = build_typed_transaction(full_genesis_request())
        second = build_typed_transaction(TypedTransactionRequest(
            current_superblock=first.new_superblock,
            page_catalog=catalog_from_plan(first),
            transaction_id=TX2,
            commit_id=COMMIT2,
            operation_id=OP2,
        ))
        self.assertEqual(dict(second.manifest.index_roots), dict(first.manifest.index_roots))
        self.assertEqual(second.manifest.journal_high_water_page_no, 13)
        self.assertEqual(second.manifest.outbox_high_water_page_no, 14)
        self.assertEqual(second.assembly.ordered_page_numbers, (17, 18))

    def test_new_named_index_page_replaces_only_that_manifest_root(self):
        first = build_typed_transaction(full_genesis_request())
        old_neuron = first.manifest.index_roots["neuron"]
        second = build_typed_transaction(TypedTransactionRequest(
            current_superblock=first.new_superblock,
            page_catalog=catalog_from_plan(first),
            transaction_id=TX2,
            commit_id=COMMIT2,
            operation_id=OP2,
            index_pages=(IndexPageInput(
                IndexNode(bytes.fromhex("12121212121212121212121212121212"), 0),
                root_name="neuron",
            ),),
        ))
        self.assertNotEqual(second.manifest.index_roots["neuron"], old_neuron)
        self.assertEqual(
            second.manifest.index_roots["neuron"],
            PageRef(second.index_page_numbers[0], 2),
        )
        for name in INDEX_ROOT_NAMES:
            if name != "neuron":
                self.assertEqual(
                    second.manifest.index_roots[name],
                    first.manifest.index_roots[name],
                )

    def test_genesis_rejects_missing_index_roots(self):
        request = full_genesis_request(index_pages=())
        with self.assertRaisesRegex(JPVRFormatError, "seven index roots"):
            build_typed_transaction(request)

    def test_rejects_unknown_index_root_name(self):
        request = full_genesis_request(
            index_pages=(IndexPageInput(IndexNode(bytes(16), 0), "unknown"),)
        )
        with self.assertRaisesRegex(JPVRFormatError, "unknown MANIFEST"):
            build_typed_transaction(request)

    def test_rejects_duplicate_root_assignment(self):
        pages = root_pages() + (
            IndexPageInput(IndexNode(bytes.fromhex("13131313131313131313131313131313"), 0),
                           root_name="neuron"),
        )
        with self.assertRaisesRegex(JPVRFormatError, "assigned more than once"):
            build_typed_transaction(full_genesis_request(index_pages=pages))

    def test_rejects_root_supplied_by_both_page_and_explicit_mapping(self):
        request = full_genesis_request(
            index_pages=root_pages(),
            index_roots={"neuron": PageRef(5, 1)},
        )
        with self.assertRaisesRegex(JPVRFormatError, "both by page and explicit"):
            build_typed_transaction(request)

    def test_outbox_events_chain_across_generations(self):
        first = build_typed_transaction(full_genesis_request())
        second_event = outbox_event(
            event_id=bytes.fromhex("12121212121212121212121212121212"),
            idempotency_key=bytes.fromhex("13131313131313131313131313131313"),
            sequence=2,
            commit_id=COMMIT2,
        )
        second = build_typed_transaction(TypedTransactionRequest(
            current_superblock=first.new_superblock,
            page_catalog=catalog_from_plan(first),
            transaction_id=TX2,
            commit_id=COMMIT2,
            operation_id=OP2,
            outbox_events=(second_event,),
        ))
        encoded = second.assembly.ordered_page_bytes[
            second.outbox_page_numbers[0] - second.assembly.ordered_page_numbers[0]
        ]
        decoded = decode_outbox_page(encoded, expected_page_no=second.outbox_page_numbers[0])
        self.assertEqual(decoded.event.sequence, 2)
        self.assertEqual(decoded.event.previous_event_ref, PageRef(14, 1))
        self.assertEqual(decoded.event.commit_ref, PageRef(second.commit_page_no, 2))

    def test_rejects_outbox_sequence_gap(self):
        request = full_genesis_request(outbox_events=(outbox_event(sequence=2),))
        with self.assertRaisesRegex(JPVRFormatError, "sequence is not contiguous"):
            build_typed_transaction(request)

    def test_rejects_outbox_event_for_another_commit(self):
        request = full_genesis_request(
            outbox_events=(outbox_event(commit_id=COMMIT2),)
        )
        with self.assertRaisesRegex(JPVRFormatError, "commit_id must match"):
            build_typed_transaction(request)

    def test_rejects_duplicate_outbox_idempotency_key_in_one_transaction(self):
        event2 = outbox_event(
            event_id=bytes.fromhex("14141414141414141414141414141414"),
            sequence=2,
        )
        request = full_genesis_request(outbox_events=(outbox_event(), event2))
        with self.assertRaisesRegex(JPVRFormatError, "unique in transaction"):
            build_typed_transaction(request)

    def test_journal_revisions_auto_link_same_operation(self):
        plan = build_typed_transaction(full_genesis_request())
        first = decode_journal_page(
            plan.assembly.ordered_page_bytes[10], expected_page_no=12
        )
        second = decode_journal_page(
            plan.assembly.ordered_page_bytes[11], expected_page_no=13
        )
        self.assertEqual(second.entry.status, OperationStatus.RUNNING)
        self.assertEqual(
            second.entry.previous_entry_ref,
            PageRef(first.page_no, first.commit_generation),
        )

    def test_rejects_missing_external_journal_predecessor(self):
        request = full_genesis_request(
            journal_entries=(JournalEntryInput(
                journal_entry(
                    OperationStatus.RUNNING,
                    previous=PageRef(99, 1),
                )
            ),),
        )
        with self.assertRaisesRegex(JPVRFormatError, "missing from page catalog"):
            build_typed_transaction(request)

    def test_rejects_blob_payload_that_should_be_a_small_record(self):
        request = full_genesis_request(
            blob_records=(BlobRecordInput(2, 1, 1, OBJECT2, 1, b"small"),)
        )
        with self.assertRaisesRegex(JPVRFormatError, "larger than 3976"):
            build_typed_transaction(request)

    def test_rejects_unsupported_data_record_flags(self):
        request = full_genesis_request(
            data_records=(DataRecordInput(1, 1, 1, OBJECT1, 1, b"x", flags=1),)
        )
        with self.assertRaisesRegex(JPVRFormatError, "record flags"):
            build_typed_transaction(request)

    def test_plan_revalidation_accepts_unchanged_inputs(self):
        request = full_genesis_request()
        plan = build_typed_transaction(request)
        self.assertIsNone(validate_typed_transaction_plan(plan, request))

    def test_plan_revalidation_rejects_tampered_page_ranges(self):
        request = full_genesis_request()
        plan = build_typed_transaction(request)
        tampered = replace(
            plan,
            outbox_page_numbers=plan.outbox_page_numbers + (plan.commit_page_no,),
        )
        with self.assertRaisesRegex(JPVRFormatError, "fields are inconsistent"):
            validate_typed_transaction_plan(tampered, request)

    def test_failed_build_does_not_mutate_caller_catalog(self):
        catalog = {}
        request = full_genesis_request(
            page_catalog=catalog,
            index_pages=(),
        )
        with self.assertRaises(JPVRFormatError):
            build_typed_transaction(request)
        self.assertEqual(catalog, {})

    def test_integrated_cow_batch_updates_tree_and_manifest_root(self):
        first = build_typed_transaction(full_genesis_request(
            data_records=(), blob_records=(), journal_entries=(), outbox_events=(),
        ))
        old_root = first.manifest.index_roots["neuron"]
        tree_id = bytes((2,)) * 16
        second = build_typed_transaction(TypedTransactionRequest(
            current_superblock=first.new_superblock, page_catalog=catalog_from_plan(first),
            transaction_id=TX2, commit_id=COMMIT2, operation_id=OP2,
            index_mutations=(
                IndexMutationInput("neuron", tree_id, b"alpha", b"A"),
                IndexMutationInput("neuron", tree_id, b"gamma", b"G"),
                IndexMutationInput("neuron", tree_id, b"beta", b"B"),
            ),
        ))
        combined = catalog_from_plan(first)
        combined.update(catalog_from_plan(second))
        new_root = second.manifest.index_roots["neuron"]
        self.assertNotEqual(new_root, old_root)
        self.assertEqual(len(second.index_mutation_plans), 1)
        self.assertEqual(second.index_mutation_plans[0].root_name, "neuron")
        self.assertEqual(second.index_mutation_plans[0].cow_plan.new_stats.entry_count, 3)
        self.assertEqual(second.index_page_numbers, (11,))
        for key, value in ((b"alpha", b"A"), (b"beta", b"B"), (b"gamma", b"G")):
            self.assertEqual(lookup_index(new_root, combined, key, tree_id=tree_id,
                durable_tail_page_no=second.new_superblock.durable_tail_page_no), value)
        self.assertIsNone(lookup_index(old_root, catalog_from_plan(first), b"alpha",
            tree_id=tree_id, durable_tail_page_no=first.new_superblock.durable_tail_page_no))

    def test_integrated_cow_updates_multiple_manifest_roots_in_canonical_order(self):
        first = build_typed_transaction(full_genesis_request(
            data_records=(), blob_records=(), journal_entries=(), outbox_events=(),
        ))
        neuron_tree, synapse_tree = bytes((2,)) * 16, bytes((3,)) * 16
        second = build_typed_transaction(TypedTransactionRequest(
            current_superblock=first.new_superblock, page_catalog=catalog_from_plan(first),
            transaction_id=TX2, commit_id=COMMIT2, operation_id=OP2,
            index_mutations=(
                IndexMutationInput("synapse", synapse_tree, b"s", b"S"),
                IndexMutationInput("neuron", neuron_tree, b"n", b"N"),
            ),
        ))
        self.assertEqual(tuple(item.root_name for item in second.index_mutation_plans),
                         ("neuron", "synapse"))
        self.assertEqual(second.index_page_numbers, (11, 12))
        self.assertEqual(second.manifest.index_roots["neuron"], PageRef(11, 2))
        self.assertEqual(second.manifest.index_roots["synapse"], PageRef(12, 2))

    def test_integrated_cow_can_replace_existing_key_without_mutating_old_snapshot(self):
        pages = list(root_pages())
        tree_id = bytes((2,)) * 16
        pages[1] = IndexPageInput(
            IndexNode(tree_id, 0, (IndexEntry(b"existing", b"old"),)), root_name="neuron"
        )
        first = build_typed_transaction(full_genesis_request(
            data_records=(), blob_records=(), journal_entries=(), outbox_events=(),
            index_pages=tuple(pages),
        ))
        old_root = first.manifest.index_roots["neuron"]
        second = build_typed_transaction(TypedTransactionRequest(
            current_superblock=first.new_superblock, page_catalog=catalog_from_plan(first),
            transaction_id=TX2, commit_id=COMMIT2, operation_id=OP2,
            index_mutations=(
                IndexMutationInput("neuron", tree_id, b"existing", b"new"),
                IndexMutationInput("neuron", tree_id, b"inserted", b"value"),
            ),
        ))
        combined = catalog_from_plan(first)
        combined.update(catalog_from_plan(second))
        self.assertTrue(second.index_mutation_plans[0].cow_plan.replaced_existing_key)
        self.assertEqual(lookup_index(second.manifest.index_roots["neuron"], combined,
            b"existing", tree_id=tree_id,
            durable_tail_page_no=second.new_superblock.durable_tail_page_no), b"new")
        self.assertEqual(lookup_index(old_root, catalog_from_plan(first), b"existing",
            tree_id=tree_id, durable_tail_page_no=first.new_superblock.durable_tail_page_no), b"old")

    def test_integrated_cow_rejects_duplicate_key_mutations_in_one_tree(self):
        first = build_typed_transaction(full_genesis_request(
            data_records=(), blob_records=(), journal_entries=(), outbox_events=(),
        ))
        tree_id = bytes((2,)) * 16
        request = TypedTransactionRequest(
            current_superblock=first.new_superblock, page_catalog=catalog_from_plan(first),
            transaction_id=TX2, commit_id=COMMIT2, operation_id=OP2,
            index_mutations=(
                IndexMutationInput("neuron", tree_id, b"k", b"one"),
                IndexMutationInput("neuron", tree_id, b"k", b"two"),
            ),
        )
        with self.assertRaisesRegex(JPVRFormatError, "must be unique"):
            build_typed_transaction(request)

    def test_integrated_cow_rejects_wrong_tree_identity_and_unknown_root(self):
        first = build_typed_transaction(full_genesis_request(
            data_records=(), blob_records=(), journal_entries=(), outbox_events=(),
        ))
        base_request = dict(
            current_superblock=first.new_superblock, page_catalog=catalog_from_plan(first),
            transaction_id=TX2, commit_id=COMMIT2, operation_id=OP2,
        )
        with self.assertRaisesRegex(JPVRFormatError, "tree ID"):
            build_typed_transaction(TypedTransactionRequest(**base_request,
                index_mutations=(IndexMutationInput("neuron", bytes(16), b"k", b"v"),)))
        with self.assertRaisesRegex(JPVRFormatError, "unknown MANIFEST"):
            build_typed_transaction(TypedTransactionRequest(**base_request,
                index_mutations=(IndexMutationInput("unknown", bytes((2,)) * 16, b"k", b"v"),)))

    def test_integrated_cow_plan_revalidation_rebuilds_mutation_pages(self):
        first = build_typed_transaction(full_genesis_request(
            data_records=(), blob_records=(), journal_entries=(), outbox_events=(),
        ))
        request = TypedTransactionRequest(
            current_superblock=first.new_superblock, page_catalog=catalog_from_plan(first),
            transaction_id=TX2, commit_id=COMMIT2, operation_id=OP2,
            index_mutations=(IndexMutationInput("neuron", bytes((2,)) * 16, b"k", b"v"),),
        )
        plan = build_typed_transaction(request)
        self.assertIsNone(validate_typed_transaction_plan(plan, request))
        tampered = replace(plan, index_page_numbers=())
        with self.assertRaisesRegex(JPVRFormatError, "fields are inconsistent"):
            validate_typed_transaction_plan(tampered, request)


if __name__ == "__main__":
    unittest.main()
