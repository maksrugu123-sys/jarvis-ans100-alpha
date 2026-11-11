"""Streaming JPVR v1 historical transaction and recovery-prefix scanner.

The scanner is read-only. It validates page envelopes in physical order, each
generation's transaction range, every historical COMMIT/MANIFEST snapshot,
and the active OUTBOX event chain. It reports the last verified commit prefix
without rewriting the container or pretending that a prefix was repaired.
"""
from __future__ import annotations

from collections.abc import Iterator, Mapping
from dataclasses import dataclass

from .codec import (
    JPVRFormatError,
    PageType,
    Superblock,
    decode_page,
    encode_superblock,
)
from .commit import DecodedCommitPage, decode_commit_page, validate_commit_snapshot
from .cow import IndexTreeStats, validate_index_tree
from .index import decode_index_page
from .manifest import DecodedManifestPage, PageRef, decode_manifest_page
from .outbox import DecodedOutboxPage
from .outbox_scan import _scan_outbox_chain


@dataclass(frozen=True)
class JPVRRecoveryIssue:
    """First integrity/coverage issue found by the read-only historical scan."""

    code: str
    message: str
    page_no: int | None = None
    generation: int | None = None


@dataclass(frozen=True)
class JPVRHistoricalSnapshot:
    """One fully validated historical generation and its physical append range."""

    commit: DecodedCommitPage
    manifest: DecodedManifestPage
    superblock: Superblock
    first_page_no: int
    last_page_no: int
    page_count: int
    index_tree_stats: tuple[tuple[str, IndexTreeStats], ...] = ()


@dataclass(frozen=True)
class JPVRHistoryScanResult:
    """Historical verification result plus the last safe committed-prefix candidate."""

    active_superblock: Superblock
    snapshots: tuple[JPVRHistoricalSnapshot, ...]
    outbox_events: tuple[DecodedOutboxPage, ...]
    issue: JPVRRecoveryIssue | None
    commit_history_complete: bool
    outbox_history_complete: bool
    verified_durable_tail_page_no: int
    orphan_tail_bytes: int = 0

    @property
    def verified_generation(self) -> int:
        return self.snapshots[-1].commit.commit_generation if self.snapshots else 0

    @property
    def last_verified_snapshot(self) -> JPVRHistoricalSnapshot | None:
        return self.snapshots[-1] if self.snapshots else None

    @property
    def is_complete(self) -> bool:
        return self.commit_history_complete and self.outbox_history_complete and self.issue is None

    @property
    def has_recoverable_prefix(self) -> bool:
        return self.verified_durable_tail_page_no >= 1

    @property
    def has_orphan_tail(self) -> bool:
        return self.orphan_tail_bytes > 0


@dataclass(frozen=True)
class _PageHeader:
    page_type: PageType
    commit_generation: int
    transaction_id: bytes


class _PrefixPageCatalog(Mapping[int, bytes]):
    """Read-only view that prevents a historical manifest from seeing future pages."""

    def __init__(self, source: Mapping[int, bytes], tail: int):
        self._source = source
        self._tail = tail

    def __getitem__(self, page_no: int) -> bytes:
        if (
            isinstance(page_no, bool)
            or not isinstance(page_no, int)
            or page_no < 2
            or page_no > self._tail
        ):
            raise KeyError(page_no)
        raw = self._source.get(page_no)
        if raw is None:
            raise KeyError(page_no)
        if not isinstance(raw, (bytes, bytearray, memoryview)):
            raise JPVRFormatError("page catalog values must be bytes-like")
        return bytes(raw)

    def __iter__(self) -> Iterator[int]:
        return iter(range(2, self._tail + 1))

    def __len__(self) -> int:
        return max(0, self._tail - 1)


def _issue(
    code: str,
    exc: Exception | str,
    *,
    page_no: int | None = None,
    generation: int | None = None,
) -> JPVRRecoveryIssue:
    return JPVRRecoveryIssue(
        code=code,
        message=str(exc),
        page_no=page_no,
        generation=generation,
    )


def scan_jpvr_history(
    superblock: Superblock,
    page_catalog: Mapping[int, bytes],
    *,
    verify_index_trees: bool = False,
) -> JPVRHistoryScanResult:
    """Scan a physical page prefix and validate every committed generation.

    The input mapping may be lazy/file-backed. Encoded pages are read one at a
    time; only compact page-header metadata and the validated snapshot records
    are retained. A failure returns the preceding verified committed prefix
    plus one structured issue. No file bytes are changed.

    `verify_index_trees=True` fully traverses all seven INDEX trees in every
    historical MANIFEST. This can be expensive for long histories.
    """
    encode_superblock(superblock)
    if not isinstance(page_catalog, Mapping):
        raise JPVRFormatError("page_catalog must be a mapping of page numbers to bytes")
    if not isinstance(verify_index_trees, bool):
        raise JPVRFormatError("verify_index_trees must be bool")

    headers: dict[int, _PageHeader] = {}
    first_issue: JPVRRecoveryIssue | None = None
    durable_tail = superblock.durable_tail_page_no

    # Stream the physical prefix. Do not retain every encoded page in RAM.
    for page_no in range(2, durable_tail + 1):
        try:
            raw = page_catalog.get(page_no)
            if raw is None:
                raise JPVRFormatError("durable page is missing from page_catalog")
            decoded = decode_page(raw, expected_page_no=page_no)
            headers[page_no] = _PageHeader(
                page_type=decoded.page_type,
                commit_generation=decoded.commit_generation,
                transaction_id=decoded.transaction_id,
            )
        except Exception as exc:
            first_issue = _issue("page-unreadable", exc, page_no=page_no)
            break

    # Reject hidden/out-of-range entries without retaining their page contents.
    if first_issue is None:
        try:
            for page_no in page_catalog:
                if (
                    isinstance(page_no, bool)
                    or not isinstance(page_no, int)
                    or page_no < 2
                    or page_no > durable_tail
                ):
                    first_issue = _issue(
                        "catalog-page-outside-prefix",
                        "page_catalog contains a page outside the active durable prefix",
                        page_no=page_no if isinstance(page_no, int) and not isinstance(page_no, bool) else None,
                    )
                    break
        except Exception as exc:
            first_issue = _issue("catalog-iteration-failed", exc)

    commits: list[DecodedCommitPage] = []
    snapshots: list[JPVRHistoricalSnapshot] = []
    seen_commit_ids: set[bytes] = set()
    seen_transaction_ids: set[bytes] = set()
    previous_commit: DecodedCommitPage | None = None

    commit_page_numbers = [
        page_no for page_no, header in headers.items()
        if header.page_type is PageType.COMMIT
    ]

    for commit_page_no in commit_page_numbers:
        generation: int | None = None
        try:
            raw_commit = page_catalog.get(commit_page_no)
            if raw_commit is None:
                raise JPVRFormatError("COMMIT page disappeared from page_catalog")
            current = decode_commit_page(raw_commit, expected_page_no=commit_page_no)
            generation = current.commit_generation

            expected_generation = len(snapshots) + 1
            if generation != expected_generation:
                raise JPVRFormatError(
                    f"COMMIT generation is {generation}, expected {expected_generation}"
                )
            if current.record.commit_id in seen_commit_ids:
                raise JPVRFormatError("COMMIT history contains a duplicate commit_id")
            if current.transaction_id in seen_transaction_ids:
                raise JPVRFormatError("COMMIT history contains a duplicate transaction_id")

            previous_ref = current.record.previous_commit_ref
            if previous_commit is None:
                if previous_ref is not None:
                    raise JPVRFormatError("genesis COMMIT must not reference a predecessor")
                first_page_no = 2
            else:
                expected_previous = PageRef(
                    previous_commit.page_no, previous_commit.commit_generation
                )
                if previous_ref != expected_previous:
                    raise JPVRFormatError("COMMIT predecessor does not match the previous physical generation")
                first_page_no = previous_commit.page_no + 1

            if commit_page_no < first_page_no:
                raise JPVRFormatError("COMMIT page precedes its transaction range")
            if current.record.manifest_ref.page_no != commit_page_no - 1:
                raise JPVRFormatError("MANIFEST must be the final staged page immediately before COMMIT")

            manifest_positions: list[int] = []
            for page_no in range(first_page_no, commit_page_no + 1):
                header = headers.get(page_no)
                if header is None:
                    raise JPVRFormatError(
                        f"transaction page {page_no} is missing or unreadable"
                    )
                if header.commit_generation != generation:
                    raise JPVRFormatError(
                        f"page {page_no} generation does not match its COMMIT"
                    )
                if header.transaction_id != current.transaction_id:
                    raise JPVRFormatError(
                        f"page {page_no} transaction_id does not match its COMMIT"
                    )
                if page_no == commit_page_no:
                    if header.page_type is not PageType.COMMIT:
                        raise JPVRFormatError("transaction does not end with a COMMIT page")
                elif header.page_type is PageType.COMMIT:
                    raise JPVRFormatError("transaction range contains an intermediate COMMIT page")
                if header.page_type is PageType.MANIFEST:
                    manifest_positions.append(page_no)

            if manifest_positions != [current.record.manifest_ref.page_no]:
                raise JPVRFormatError(
                    "transaction must contain exactly its referenced final MANIFEST page"
                )

            raw_manifest = page_catalog.get(current.record.manifest_ref.page_no)
            if raw_manifest is None:
                raise JPVRFormatError("historical MANIFEST page is missing")
            manifest = decode_manifest_page(
                raw_manifest,
                expected_page_no=current.record.manifest_ref.page_no,
            )
            if manifest.commit_generation != generation:
                raise JPVRFormatError("historical MANIFEST generation does not match COMMIT")
            if manifest.transaction_id != current.transaction_id:
                raise JPVRFormatError("historical MANIFEST transaction_id does not match COMMIT")

            previous_bytes = None
            if previous_commit is not None:
                previous_bytes = page_catalog.get(previous_commit.page_no)
                if previous_bytes is None:
                    raise JPVRFormatError("previous COMMIT page bytes are missing")

            historical_superblock = Superblock(
                container_uuid=superblock.container_uuid,
                generation=generation,
                active_commit_page_no=current.page_no,
                checkpoint_page_no=manifest.manifest.checkpoint_page_no,
                durable_tail_page_no=current.record.durable_tail_page_no,
                active_commit_id=current.record.commit_id,
                slot_id=generation % 2,
            )
            prefix_catalog = _PrefixPageCatalog(
                page_catalog, current.record.durable_tail_page_no
            )
            validate_commit_snapshot(
                raw_commit,
                raw_manifest,
                previous_commit_page_bytes=previous_bytes,
                expected_commit_page_no=current.page_no,
                superblock=historical_superblock,
                page_catalog=prefix_catalog,
            )

            tree_stats: list[tuple[str, IndexTreeStats]] = []
            if verify_index_trees:
                for root_name, root in manifest.manifest.index_roots.items():
                    root_bytes = prefix_catalog[root.page_no]
                    root_page = decode_index_page(
                        root_bytes, expected_page_no=root.page_no
                    )
                    stats = validate_index_tree(
                        root,
                        prefix_catalog,
                        tree_id=root_page.node.tree_id,
                        durable_tail_page_no=current.record.durable_tail_page_no,
                    )
                    tree_stats.append((root_name, stats))

            snapshot = JPVRHistoricalSnapshot(
                commit=current,
                manifest=manifest,
                superblock=historical_superblock,
                first_page_no=first_page_no,
                last_page_no=commit_page_no,
                page_count=commit_page_no - first_page_no + 1,
                index_tree_stats=tuple(tree_stats),
            )
            snapshots.append(snapshot)
            commits.append(current)
            seen_commit_ids.add(current.record.commit_id)
            seen_transaction_ids.add(current.transaction_id)
            previous_commit = current
        except Exception as exc:
            if first_issue is None:
                first_issue = _issue(
                    "historical-transaction-invalid",
                    exc,
                    page_no=commit_page_no,
                    generation=generation,
                )
            break

    commit_history_complete = first_issue is None

    # Every COMMIT header in the durable prefix must correspond to one complete
    # generation, and the final physical commit must equal the active Superblock.
    if commit_history_complete:
        if superblock.generation == 0:
            if (
                superblock.active_commit_page_no != 0
                or superblock.durable_tail_page_no != 1
                or snapshots
            ):
                first_issue = _issue(
                    "active-superblock-mismatch",
                    "genesis Superblock does not describe an empty physical history",
                )
        elif not snapshots:
            first_issue = _issue(
                "active-superblock-mismatch",
                "active Superblock references a history with no valid COMMIT",
            )
        else:
            last = snapshots[-1]
            if (
                last.commit.commit_generation != superblock.generation
                or last.commit.page_no != superblock.active_commit_page_no
                or last.commit.record.commit_id != superblock.active_commit_id
                or last.commit.record.durable_tail_page_no != superblock.durable_tail_page_no
                or last.manifest.manifest.checkpoint_page_no != superblock.checkpoint_page_no
                or last.manifest.manifest.container_uuid != superblock.container_uuid
                or last.last_page_no != durable_tail
                or superblock.slot_id != superblock.generation % 2
            ):
                first_issue = _issue(
                    "active-superblock-mismatch",
                    "active Superblock does not match the final validated historical COMMIT",
                    page_no=superblock.active_commit_page_no,
                    generation=superblock.generation,
                )

    commit_history_complete = first_issue is None
    outbox_events: tuple[DecodedOutboxPage, ...] = ()
    outbox_history_complete = False

    if commit_history_complete:
        if not snapshots:
            outbox_history_complete = True
        else:
            try:
                from .outbox_scan import _scan_outbox_chain

                outbox_events = _scan_outbox_chain(
                    snapshots[-1].manifest,
                    snapshots[-1].commit,
                    tuple(commits),
                    page_catalog,
                    headers,
                )
                outbox_history_complete = True
            except Exception as exc:
                first_issue = _issue(
                    "outbox-history-invalid",
                    exc,
                    generation=superblock.generation,
                )

    verified_tail = (
        snapshots[-1].last_page_no if snapshots else 1
    )
    return JPVRHistoryScanResult(
        active_superblock=superblock,
        snapshots=tuple(snapshots),
        outbox_events=outbox_events,
        issue=first_issue,
        commit_history_complete=commit_history_complete,
        outbox_history_complete=outbox_history_complete,
        verified_durable_tail_page_no=verified_tail,
    )
