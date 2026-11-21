"""JPVR_FORMAT_V1 binary reference codecs and in-memory planners."""

from .codec import (
    DecodedPage,
    PageType,
    Superblock,
    crc32c,
    decode_page,
    decode_superblock,
    encode_page,
    encode_superblock,
)
from .records import (
    DecodedRecord,
    TLVField,
    WireType,
    decode_record,
    decode_tlv,
    encode_record,
    encode_tlv,
)
from .blob import DecodedBlobRecord, decode_blob_pages, encode_blob_pages
from .data import DecodedDataPage, decode_data_page, encode_data_page
from .layout import AppendPlan, plan_append, validate_append_plan
from .manifest import (
    DecodedManifestPage,
    Manifest,
    PageRef,
    decode_manifest_page,
    decode_manifest_payload,
    encode_manifest_page,
    encode_manifest_payload,
    validate_manifest,
)
from .index import (
    DecodedIndexPage,
    IndexChild,
    IndexEntry,
    IndexNode,
    decode_index_node,
    decode_index_page,
    encode_index_node,
    encode_index_page,
    lookup_index,
)
from .cow import (
    CowIndexPlan,
    IndexTreeStats,
    NewIndexPage,
    cow_apply_index_mutations,
    cow_upsert_index,
    validate_index_tree,
)
from .commit import (
    CommitRecord,
    DecodedCommitPage,
    decode_commit_page,
    decode_commit_payload,
    encode_commit_page,
    encode_commit_payload,
    validate_commit_snapshot,
)
from .journal import (
    DecodedJournalPage,
    JournalEntry,
    OperationStatus,
    decode_journal_page,
    decode_journal_payload,
    encode_journal_page,
    encode_journal_payload,
    validate_journal_transition,
)
from .outbox import (
    DecodedOutboxPage,
    OutboxEvent,
    decode_outbox_page,
    decode_outbox_payload,
    encode_outbox_page,
    encode_outbox_payload,
    validate_outbox_sequence,
    validate_outbox_commit_reference,
    validate_outbox_genesis,
)
from .outbox_scan import OutboxScanResult, scan_committed_outbox
from .transaction import (
    TransactionAssemblyPlan,
    assemble_transaction,
    validate_transaction_assembly_plan,
)
from .typed_transaction import (
    BlobRecordInput,
    CheckpointPageInput,
    DataRecordInput,
    IndexPageInput,
    IndexMutationBuildPlan,
    IndexMutationInput,
    JournalEntryInput,
    TypedTransactionBuildPlan,
    TypedTransactionRequest,
    build_typed_transaction,
    validate_typed_transaction_plan,
)

__all__ = [
    "AppendPlan",
    "BlobRecordInput",
    "CheckpointPageInput",
    "CommitRecord",
    "CowIndexPlan",
    "DataRecordInput",
    "DecodedBlobRecord",
    "DecodedCommitPage",
    "DecodedDataPage",
    "DecodedJournalPage",
    "DecodedIndexPage",
    "DecodedManifestPage",
    "DecodedPage",
    "DecodedOutboxPage",
    "DecodedRecord",
    "IndexChild",
    "IndexEntry",
    "IndexNode",
    "IndexPageInput",
    "IndexMutationBuildPlan",
    "IndexMutationInput",
    "IndexTreeStats",
    "JournalEntry",
    "JournalEntryInput",
    "OperationStatus",
    "OutboxEvent",
    "OutboxScanResult",
    "Manifest",
    "NewIndexPage",
    "PageRef",
    "PageType",
    "Superblock",
    "TLVField",
    "TransactionAssemblyPlan",
    "TypedTransactionBuildPlan",
    "TypedTransactionRequest",
    "WireType",
    "assemble_transaction",
    "build_typed_transaction",
    "cow_apply_index_mutations",
    "cow_upsert_index",
    "crc32c",
    "decode_blob_pages",
    "decode_commit_page",
    "decode_commit_payload",
    "decode_data_page",
    "decode_index_node",
    "decode_index_page",
    "decode_journal_page",
    "decode_journal_payload",
    "decode_manifest_page",
    "decode_manifest_payload",
    "decode_outbox_page",
    "decode_outbox_payload",
    "decode_page",
    "decode_record",
    "decode_superblock",
    "decode_tlv",
    "encode_blob_pages",
    "encode_commit_page",
    "encode_commit_payload",
    "encode_data_page",
    "encode_index_node",
    "encode_index_page",
    "encode_journal_page",
    "encode_journal_payload",
    "encode_manifest_page",
    "encode_manifest_payload",
    "encode_outbox_page",
    "encode_outbox_payload",
    "encode_page",
    "encode_record",
    "encode_superblock",
    "encode_tlv",
    "lookup_index",
    "plan_append",
    "scan_committed_outbox",
    "validate_append_plan",
    "validate_commit_snapshot",
    "validate_index_tree",
    "validate_journal_transition",
    "validate_manifest",
    "validate_outbox_sequence",
    "validate_outbox_commit_reference",
    "validate_outbox_genesis",
    "validate_transaction_assembly_plan",
    "validate_typed_transaction_plan",
]


from .storage import JPVRCommitUncertainError, JPVRStorage

__all__ += ["JPVRCommitUncertainError", "JPVRStorage"]


from .recovery import (
    JPVRHistoricalSnapshot,
    JPVRHistoryScanResult,
    JPVRRecoveryCopyResult,
    JPVRRecoveryIssue,
    scan_jpvr_history,
)

__all__ += [
    "JPVRHistoricalSnapshot",
    "JPVRHistoryScanResult",
    "JPVRRecoveryCopyResult",
    "JPVRRecoveryIssue",
    "scan_jpvr_history",
]

from .storage import JPVRRecoveryCopyUncertainError

__all__ += ["JPVRRecoveryCopyUncertainError"]
