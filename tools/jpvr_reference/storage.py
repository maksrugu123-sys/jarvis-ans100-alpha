"""File-backed JPVR v1 container with ordered append and dual-Superblock recovery."""
from __future__ import annotations

from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import replace
import fcntl
import os
import threading
import tempfile
import uuid

from .codec import PAGE_SIZE, SUPERBLOCK_SIZE, JPVRFormatError, Superblock, _fixed_bytes, decode_page, decode_superblock, encode_superblock
from .commit import decode_commit_page, validate_commit_snapshot
from .cow import validate_index_tree
from .index import decode_index_page
from .manifest import INDEX_ROOT_NAMES, decode_manifest_page
from .transaction import TransactionAssemblyPlan, validate_transaction_assembly_plan
from .typed_transaction import TypedTransactionBuildPlan


SUPERBLOCK_AREA_BYTES = 2 * SUPERBLOCK_SIZE
FIRST_DATA_PAGE_NO = 2


class JPVRCommitUncertainError(OSError):
    """Superblock publication began, so the durable outcome must be resolved by reopen."""

    def __init__(self, commit_id: bytes, generation: int, message: str):
        super().__init__(message)
        self.commit_id = bytes(commit_id)
        self.generation = generation


class JPVRRecoveryCopyUncertainError(OSError):
    """A complete recovery copy was linked, but its directory sync was not confirmed."""

    def __init__(self, path: str, generation: int, commit_id: bytes, message: str):
        super().__init__(message)
        self.path = path
        self.generation = generation
        self.commit_id = bytes(commit_id)


class _DiskPageCatalog(Mapping[int, bytes]):
    """Lazy read-only catalog; validators fetch only pages they actually reference."""

    def __init__(self, storage: "JPVRStorage", tail: int, file_size: int):
        self.storage, self.tail, self.file_size = storage, tail, file_size

    def __getitem__(self, page_no: int) -> bytes:
        if isinstance(page_no, bool) or not isinstance(page_no, int) or not 2 <= page_no <= self.tail:
            raise KeyError(page_no)
        return self.storage._read_page_unlocked(
            page_no, tail=self.tail, file_size=self.file_size
        )

    def __iter__(self) -> Iterator[int]:
        return iter(range(2, self.tail + 1))

    def __len__(self) -> int:
        return max(0, self.tail - 1)


class JPVRStorage:
    """POSIX/Android file-backed JPVR store.

    Slot 0 starts at byte 0, slot 1 at byte 4096, and page N at N * 4096.
    """

    def __init__(self, fd: int, path: str, *, verify_index_trees: bool = False):
        self._fd = fd
        self.path = path
        self.verify_index_trees = verify_index_trees
        self._superblock: Superblock | None = None
        self._mutex = threading.RLock()

    @classmethod
    def create(
        cls, path: str | os.PathLike[str], *, container_uuid: bytes | None = None,
        file_mode: int = 0o600,
    ) -> "JPVRStorage":
        """Create a new container without replacing an existing path."""
        target = os.path.abspath(os.fspath(path))
        identifier = (
            uuid.uuid4().bytes
            if container_uuid is None
            else _fixed_bytes("container_uuid", container_uuid, 16)
        )
        fd = os.open(target, os.O_RDWR | os.O_CREAT | os.O_EXCL, file_mode)
        locked = False
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            locked = True
            os.ftruncate(fd, SUPERBLOCK_AREA_BYTES)
            for slot in (0, 1):
                block = Superblock(identifier, 0, 0, 0, 1, bytes(16), slot)
                cls._pwrite_all(fd, encode_superblock(block), slot * SUPERBLOCK_SIZE)
            os.fsync(fd)
            fcntl.flock(fd, fcntl.LOCK_UN)
            locked = False
            cls._fsync_parent(target)
            store = cls(fd, target)
            store._superblock = Superblock(identifier, 0, 0, 0, 1, bytes(16), 0)
            return store
        except Exception:
            if locked:
                try:
                    fcntl.flock(fd, fcntl.LOCK_UN)
                except OSError:
                    pass
            os.close(fd)
            try:
                os.unlink(target)
            except OSError:
                pass
            raise

    @classmethod
    def open(
        cls, path: str | os.PathLike[str], *, recover_tail: bool = True,
        verify_index_trees: bool = False,
    ) -> "JPVRStorage":
        """Select the newest valid Superblock and optionally discard unpublished tail bytes."""
        if not isinstance(recover_tail, bool) or not isinstance(verify_index_trees, bool):
            raise JPVRFormatError("open options must be bool")
        target = os.path.abspath(os.fspath(path))
        store = cls(os.open(target, os.O_RDWR), target, verify_index_trees=verify_index_trees)
        try:
            with store._locked(fcntl.LOCK_EX):
                size = os.fstat(store._fd).st_size
                if size < SUPERBLOCK_AREA_BYTES:
                    raise JPVRFormatError("container is shorter than the two Superblock slots")
                active = store._select_active_unlocked(file_size=size)
                committed_size = (active.durable_tail_page_no + 1) * PAGE_SIZE
                if size < committed_size:
                    raise JPVRFormatError("container is shorter than the selected durable prefix")
                if recover_tail and size > committed_size:
                    os.ftruncate(store._fd, committed_size)
                    os.fsync(store._fd)
                store._superblock = active
            return store
        except Exception:
            store.close()
            raise

    @staticmethod
    def _fsync_parent(path: str) -> None:
        parent = os.path.dirname(path) or "."
        fd = os.open(parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    @staticmethod
    def _pwrite_all(fd: int, data: bytes, offset: int) -> None:
        view = memoryview(data)
        done = 0
        while done < len(view):
            try:
                count = os.pwrite(fd, view[done:], offset + done)
            except InterruptedError:
                continue
            if count <= 0:
                raise OSError("short write to JPVR container")
            done += count

    def _pread_exact(self, offset: int, size: int) -> bytes:
        result = bytearray()
        while len(result) < size:
            try:
                part = os.pread(self._fd, size - len(result), offset + len(result))
            except InterruptedError:
                continue
            if not part:
                raise JPVRFormatError("unexpected end of JPVR container")
            result.extend(part)
        return bytes(result)

    def _ensure_open(self) -> None:
        if self._fd < 0:
            raise ValueError("JPVRStorage is closed")

    @contextmanager
    def _locked(self, kind: int):
        with self._mutex:
            self._ensure_open()
            fcntl.flock(self._fd, kind)
            try:
                yield
            finally:
                fcntl.flock(self._fd, fcntl.LOCK_UN)

    @staticmethod
    def _offset(page_no: int) -> int:
        return page_no * PAGE_SIZE

    def _read_page_unlocked(self, page_no: int, *, tail: int, file_size: int) -> bytes:
        if isinstance(page_no, bool) or not isinstance(page_no, int) or not 2 <= page_no <= tail:
            raise JPVRFormatError("page number is outside the selected durable prefix")
        offset = self._offset(page_no)
        if offset + PAGE_SIZE > file_size:
            raise JPVRFormatError("page is truncated in the JPVR container")
        encoded = self._pread_exact(offset, PAGE_SIZE)
        decode_page(encoded, expected_page_no=page_no)
        return encoded

    def _validate_candidate(self, block: Superblock, *, file_size: int) -> None:
        if file_size < (block.durable_tail_page_no + 1) * PAGE_SIZE:
            raise JPVRFormatError("Superblock durable prefix extends beyond the file")
        if block.generation == 0:
            return

        catalog = _DiskPageCatalog(self, block.durable_tail_page_no, file_size)
        commit_bytes = catalog[block.active_commit_page_no]
        commit = decode_commit_page(commit_bytes, expected_page_no=block.active_commit_page_no)
        manifest_bytes = catalog[commit.record.manifest_ref.page_no]
        manifest_page = decode_manifest_page(
            manifest_bytes, expected_page_no=commit.record.manifest_ref.page_no
        )
        previous_bytes = (
            None if commit.record.previous_commit_ref is None
            else catalog[commit.record.previous_commit_ref.page_no]
        )
        validate_commit_snapshot(
            commit_bytes, manifest_bytes,
            previous_commit_page_bytes=previous_bytes,
            expected_commit_page_no=block.active_commit_page_no,
            superblock=block, page_catalog=catalog,
        )
        if self.verify_index_trees:
            for name in INDEX_ROOT_NAMES:
                root = manifest_page.manifest.index_roots[name]
                root_page = decode_index_page(catalog[root.page_no], expected_page_no=root.page_no)
                validate_index_tree(
                    root, catalog, tree_id=root_page.node.tree_id,
                    durable_tail_page_no=block.durable_tail_page_no,
                )

    def _select_active_unlocked(self, *, file_size: int | None = None) -> Superblock:
        self._ensure_open()
        size = os.fstat(self._fd).st_size if file_size is None else file_size
        if size < SUPERBLOCK_AREA_BYTES:
            raise JPVRFormatError("container is shorter than the two Superblock slots")
        valid: list[Superblock] = []
        errors: list[str] = []
        for slot in (0, 1):
            try:
                raw = self._pread_exact(slot * SUPERBLOCK_SIZE, SUPERBLOCK_SIZE)
                block = decode_superblock(raw, expected_slot_id=slot)
                self._validate_candidate(block, file_size=size)
                valid.append(block)
            except (JPVRFormatError, OSError, KeyError) as exc:
                errors.append(f"slot {slot}: {exc}")
        if not valid:
            raise JPVRFormatError("no valid JPVR Superblock slot (" + "; ".join(errors) + ")")
        generation = max(item.generation for item in valid)
        newest = [item for item in valid if item.generation == generation]
        if len(newest) > 1:
            canonical = replace(newest[0], slot_id=0)
            if any(replace(item, slot_id=0) != canonical for item in newest[1:]):
                raise JPVRFormatError("conflicting valid Superblocks have the same generation")
        return min(newest, key=lambda item: item.slot_id)

    def get_superblock(self) -> Superblock:
        """Refresh and return the currently selected durable root."""
        with self._locked(fcntl.LOCK_SH):
            self._superblock = self._select_active_unlocked()
            return self._superblock

    def read_page(self, page_no: int) -> bytes:
        """Read one page and validate its envelope CRCs and physical page number."""
        with self._locked(fcntl.LOCK_SH):
            active = self._select_active_unlocked()
            encoded = self._read_page_unlocked(
                page_no, tail=active.durable_tail_page_no,
                file_size=os.fstat(self._fd).st_size,
            )
            self._superblock = active
            return encoded

    def _assembly_catalog(
        self, assembly: TransactionAssemblyPlan, current: Superblock, *, file_size: int,
    ) -> tuple[dict[int, bytes], bytes | None]:
        if len(assembly.ordered_page_bytes) < 2:
            raise JPVRFormatError("transaction must contain MANIFEST and COMMIT pages")
        manifest_page = decode_manifest_page(
            assembly.ordered_page_bytes[-2], expected_page_no=assembly.manifest_page_no
        )
        catalog: dict[int, bytes] = {}
        if current.generation:
            catalog[current.active_commit_page_no] = self._read_page_unlocked(
                current.active_commit_page_no, tail=current.durable_tail_page_no,
                file_size=file_size,
            )
        old_refs = {
            ref.page_no for ref in manifest_page.manifest.index_roots.values()
            if ref.page_no <= current.durable_tail_page_no
        }
        old_refs.update(
            no for no in (
                manifest_page.manifest.checkpoint_page_no,
                manifest_page.manifest.journal_high_water_page_no,
                manifest_page.manifest.outbox_high_water_page_no,
            ) if no and no <= current.durable_tail_page_no
        )
        for page_no in sorted(old_refs):
            if page_no not in catalog:
                catalog[page_no] = self._read_page_unlocked(
                    page_no, tail=current.durable_tail_page_no, file_size=file_size
                )
        previous = None if current.generation == 0 else catalog[current.active_commit_page_no]
        return catalog, previous

    def _validate_typed_cow(
        self, plan: TypedTransactionBuildPlan, current: Superblock, *, file_size: int,
    ) -> None:
        assembly = plan.assembly
        staged = dict(zip(assembly.ordered_page_numbers, assembly.ordered_page_bytes))
        derived_indexes = tuple(
            page_no for page_no, encoded in zip(
                assembly.ordered_page_numbers, assembly.ordered_page_bytes
            ) if decode_page(encoded, expected_page_no=page_no).page_type.name == "INDEX"
        )
        if derived_indexes != plan.index_page_numbers:
            raise JPVRFormatError("typed plan INDEX page range does not match append bytes")
        for page_no in plan.index_page_numbers:
            decode_index_page(staged[page_no], expected_page_no=page_no)
        if not plan.index_mutation_plans:
            return
        if current.generation == 0:
            raise JPVRFormatError("COW mutations require a committed base MANIFEST")

        active_bytes = self._read_page_unlocked(
            current.active_commit_page_no, tail=current.durable_tail_page_no,
            file_size=file_size,
        )
        active = decode_commit_page(active_bytes, expected_page_no=current.active_commit_page_no)
        old_manifest_bytes = self._read_page_unlocked(
            active.record.manifest_ref.page_no, tail=current.durable_tail_page_no,
            file_size=file_size,
        )
        old_manifest = decode_manifest_page(
            old_manifest_bytes, expected_page_no=active.record.manifest_ref.page_no
        ).manifest
        new_manifest = decode_manifest_page(
            staged[assembly.manifest_page_no], expected_page_no=assembly.manifest_page_no
        ).manifest

        seen: set[str] = set()
        for item in plan.index_mutation_plans:
            if item.root_name not in INDEX_ROOT_NAMES or item.root_name in seen:
                raise JPVRFormatError("typed COW mutation plan contains an invalid/duplicate root")
            seen.add(item.root_name)
            cow = item.cow_plan
            if old_manifest.index_roots[item.root_name] != cow.old_root:
                raise JPVRFormatError("typed COW old root does not match active MANIFEST")
            if new_manifest.index_roots[item.root_name] != cow.new_root:
                raise JPVRFormatError("typed COW new root does not match append MANIFEST")
            if cow.commit_generation != assembly.new_superblock.generation:
                raise JPVRFormatError("typed COW commit generation does not match transaction")
            for page in cow.pages:
                if staged.get(page.page_no) != page.page_bytes:
                    raise JPVRFormatError("typed COW page bytes are absent from append plan")
                if page.page_no not in plan.index_page_numbers:
                    raise JPVRFormatError("typed COW page is absent from INDEX page range")

            old_catalog = {
                no: self._read_page_unlocked(
                    no, tail=current.durable_tail_page_no, file_size=file_size
                ) for no in cow.old_stats.page_numbers
            }
            old_stats = validate_index_tree(
                cow.old_root, old_catalog, tree_id=item.tree_id,
                durable_tail_page_no=current.durable_tail_page_no,
            )
            if old_stats != cow.old_stats:
                raise JPVRFormatError("typed COW old-tree statistics do not match disk")

            new_catalog: dict[int, bytes] = {}
            for no in cow.new_stats.page_numbers:
                encoded = staged.get(no)
                if encoded is None:
                    encoded = self._read_page_unlocked(
                        no, tail=current.durable_tail_page_no, file_size=file_size
                    )
                new_catalog[no] = encoded
            new_stats = validate_index_tree(
                cow.new_root, new_catalog, tree_id=item.tree_id,
                durable_tail_page_no=assembly.append_plan.commit_page_no,
            )
            if new_stats != cow.new_stats:
                raise JPVRFormatError("typed COW new-tree statistics do not match append plan")

    def commit(
        self, plan: TransactionAssemblyPlan | TypedTransactionBuildPlan,
    ) -> Superblock:
        """Append immutable pages, fsync, then publish the inactive Superblock and fsync."""
        typed = plan if isinstance(plan, TypedTransactionBuildPlan) else None
        if typed is not None:
            assembly = typed.assembly
        elif isinstance(plan, TransactionAssemblyPlan):
            assembly = plan
        else:
            raise JPVRFormatError("commit requires a transaction assembly or typed build plan")

        with self._locked(fcntl.LOCK_EX):
            current = self._select_active_unlocked()
            file_size = os.fstat(self._fd).st_size
            committed_size = (current.durable_tail_page_no + 1) * PAGE_SIZE
            if file_size < committed_size:
                raise JPVRFormatError("container is shorter than active durable prefix")
            if file_size > committed_size:
                os.ftruncate(self._fd, committed_size)
                os.fsync(self._fd)
                file_size = committed_size

            catalog, previous = self._assembly_catalog(assembly, current, file_size=file_size)
            validate_transaction_assembly_plan(
                assembly, current, catalog, previous_commit_page_bytes=previous
            )
            if typed is not None:
                self._validate_typed_cow(typed, current, file_size=file_size)

            for page_no, encoded in zip(assembly.ordered_page_numbers, assembly.ordered_page_bytes):
                self._pwrite_all(self._fd, encoded, self._offset(page_no))
            os.fsync(self._fd)

            next_superblock = assembly.new_superblock
            try:
                self._pwrite_all(
                    self._fd, encode_superblock(next_superblock),
                    next_superblock.slot_id * SUPERBLOCK_SIZE,
                )
                os.fsync(self._fd)
                selected = self._select_active_unlocked()
                if selected != next_superblock:
                    raise JPVRFormatError("new Superblock did not become active after publication")
                self._superblock = selected
                return selected
            except Exception as exc:
                raise JPVRCommitUncertainError(
                    assembly.commit_id, next_superblock.generation,
                    "Superblock publication started but commit outcome is uncertain; "
                    "reopen and inspect active_commit_id before retrying",
                ) from exc


    def scan_history(self, *, verify_index_trees: bool | None = None):
        """Audit every committed generation without materializing the page catalog.

        The scan is read-only. It reports the last fully validated commit
        boundary and the number of bytes beyond the selected durable prefix.
        Those tail bytes are not automatically truncated by this method.
        """
        from .recovery import scan_jpvr_history

        if verify_index_trees is None:
            verify_trees = self.verify_index_trees
        elif isinstance(verify_index_trees, bool):
            verify_trees = verify_index_trees
        else:
            raise JPVRFormatError("verify_index_trees must be bool or None")

        with self._locked(fcntl.LOCK_SH):
            active = self._select_active_unlocked()
            file_size = os.fstat(self._fd).st_size
            catalog = _DiskPageCatalog(
                self, active.durable_tail_page_no, file_size
            )
            result = scan_jpvr_history(
                active, catalog, verify_index_trees=verify_trees
            )
            committed_size = (active.durable_tail_page_no + 1) * PAGE_SIZE
            orphan_tail_bytes = max(0, file_size - committed_size)
            self._superblock = active
            if orphan_tail_bytes:
                result = replace(result, orphan_tail_bytes=orphan_tail_bytes)
            return result


    def recover_copy(
        self,
        destination: str | os.PathLike[str],
        *,
        expected_commit_id: bytes,
        verify_index_trees: bool | None = None,
    ):
        """Publish a verified historical prefix as a new, never-overwritten file.

        The source is held under a shared lock and is never modified. The
        expected commit ID is mandatory so callers explicitly pin the recovery
        point. Only a prefix whose OUTBOX chain also validates can be copied.

        A same-directory temporary file is fully written, synced and read back
        before an atomic hard-link publication. The final hard link refuses an
        existing destination. Filesystems without hard-link support cannot use
        this publication method.
        """
        from .recovery import JPVRRecoveryCopyResult, scan_jpvr_history

        expected_id = _fixed_bytes("expected_commit_id", expected_commit_id, 16)
        target = os.path.abspath(os.fspath(destination))
        if target == self.path:
            raise JPVRFormatError("recovery destination must differ from the source path")
        if os.path.lexists(target):
            raise FileExistsError(target)

        parent = os.path.dirname(target) or "."
        basename = os.path.basename(target)
        if not basename:
            raise JPVRFormatError("recovery destination must name a file")

        with self._locked(fcntl.LOCK_SH):
            active = self._select_active_unlocked()
            source_size = os.fstat(self._fd).st_size
            catalog = _DiskPageCatalog(
                self, active.durable_tail_page_no, source_size
            )
            report = scan_jpvr_history(
                active,
                catalog,
                verify_index_trees=(
                    self.verify_index_trees
                    if verify_index_trees is None
                    else verify_index_trees
                ),
            )

            # OUTBOX is part of the authoritative committed history. A commit
            # prefix is not eligible for publication while its event chain is
            # unknown or invalid.
            if not report.outbox_history_complete:
                raise JPVRFormatError(
                    "recovery copy refused: the verified prefix OUTBOX chain is incomplete or invalid"
                )

            if report.snapshots:
                latest = report.snapshots[-1]
                recovered = latest.superblock
                if latest.commit.record.commit_id != expected_id:
                    raise JPVRFormatError(
                        "expected_commit_id does not match the last verified snapshot"
                    )
                if latest.last_page_no != report.verified_durable_tail_page_no:
                    raise JPVRFormatError("verified recovery boundary is inconsistent")
                if recovered.generation > active.generation:
                    raise JPVRFormatError("verified recovery generation exceeds source generation")

                if recovered.generation == 1:
                    previous = Superblock(
                        active.container_uuid, 0, 0, 0, 1, bytes(16), 0
                    )
                else:
                    if len(report.snapshots) < 2:
                        raise JPVRFormatError(
                            "previous verified Superblock is missing from recovery history"
                        )
                    previous = report.snapshots[-2].superblock
                slot_blocks = {previous.slot_id: previous, recovered.slot_id: recovered}
                if set(slot_blocks) != {0, 1}:
                    raise JPVRFormatError("recovery Superblock slots are not complementary")
                if previous.generation + 1 != recovered.generation:
                    raise JPVRFormatError("recovery Superblock generations are not adjacent")
                if previous.container_uuid != recovered.container_uuid:
                    raise JPVRFormatError("recovery Superblocks have different container UUIDs")
                tail = report.verified_durable_tail_page_no
            else:
                if active.generation != 0 or not report.commit_history_complete:
                    raise JPVRFormatError(
                        "recovery copy refused: no complete verified genesis snapshot is available"
                    )
                if expected_id != bytes(16):
                    raise JPVRFormatError(
                        "empty-container recovery requires the all-zero commit ID"
                    )
                recovered = Superblock(
                    active.container_uuid, 0, 0, 0, 1, bytes(16), 0
                )
                previous = replace(recovered, slot_id=1)
                slot_blocks = {0: recovered, 1: previous}
                tail = 1

            committed_size = (active.durable_tail_page_no + 1) * PAGE_SIZE
            if source_size < committed_size:
                raise JPVRFormatError("source is shorter than its selected durable prefix")
            orphan_tail_bytes = max(0, source_size - committed_size)
            target_size = (tail + 1) * PAGE_SIZE

            temp_fd = -1
            temp_path: str | None = None
            published = False
            try:
                temp_fd, temp_path = tempfile.mkstemp(
                    prefix=f".{basename}.jpvr-recovery-",
                    suffix=".tmp",
                    dir=parent,
                )
                os.ftruncate(temp_fd, target_size)

                # Copy only pages through the last verified COMMIT boundary.
                for page_no in range(2, tail + 1):
                    raw = self._pread_exact(self._offset(page_no), PAGE_SIZE)
                    decode_page(raw, expected_page_no=page_no)
                    self._pwrite_all(temp_fd, raw, self._offset(page_no))

                # Persist immutable page bytes before publishing either slot.
                os.fsync(temp_fd)

                # Read back every copied page before making the new path visible.
                for page_no in range(2, tail + 1):
                    offset = self._offset(page_no)
                    expected = self._pread_exact(offset, PAGE_SIZE)
                    actual = os.pread(temp_fd, PAGE_SIZE, offset)
                    if len(actual) != PAGE_SIZE or actual != expected:
                        raise OSError(f"recovery-copy page {page_no} readback mismatch")
                    decode_page(actual, expected_page_no=page_no)

                for slot in (0, 1):
                    block = slot_blocks[slot]
                    self._pwrite_all(
                        temp_fd,
                        encode_superblock(block),
                        slot * SUPERBLOCK_SIZE,
                    )
                os.fsync(temp_fd)

                for slot in (0, 1):
                    encoded = os.pread(temp_fd, SUPERBLOCK_SIZE, slot * SUPERBLOCK_SIZE)
                    decoded = decode_superblock(encoded, expected_slot_id=slot)
                    if decoded != slot_blocks[slot]:
                        raise OSError(f"recovery-copy Superblock slot {slot} readback mismatch")

                os.close(temp_fd)
                temp_fd = -1

                # Same-directory hard link is atomic and, unlike os.replace(),
                # cannot overwrite a path created by another process meanwhile.
                os.link(temp_path, target)
                published = True
                os.unlink(temp_path)
                temp_path = None
                try:
                    self._fsync_parent(target)
                except OSError as exc:
                    raise JPVRRecoveryCopyUncertainError(
                        target,
                        recovered.generation,
                        recovered.active_commit_id,
                        "recovery copy is present, but parent-directory fsync failed; "
                        "verify the destination before relying on it",
                    ) from exc
            except JPVRRecoveryCopyUncertainError:
                raise
            except Exception as exc:
                if published:
                    raise JPVRRecoveryCopyUncertainError(
                        target,
                        recovered.generation,
                        recovered.active_commit_id,
                        "recovery copy was published, but finalization is uncertain; "
                        "inspect the destination before retrying",
                    ) from exc
                raise
            finally:
                if temp_fd >= 0:
                    os.close(temp_fd)
                if temp_path is not None:
                    try:
                        os.unlink(temp_path)
                    except FileNotFoundError:
                        pass

            return JPVRRecoveryCopyResult(
                destination_path=target,
                recovered_superblock=recovered,
                source_active_generation=active.generation,
                source_verified_generation=recovered.generation,
                source_issue=report.issue,
                discarded_orphan_tail_bytes=orphan_tail_bytes,
            )

    def load_snapshot(
        self, *, max_pages: int = 4096
    ) -> tuple[Superblock, dict[int, bytes]]:
        """Return one active Superblock and its bounded page catalog under one shared lock.

        Unlike calling get_superblock() and load_page_catalog() separately,
        this method guarantees that both outputs describe the same selected
        committed snapshot. The catalog remains materialized and bounded.
        """
        if isinstance(max_pages, bool) or not isinstance(max_pages, int) or max_pages < 0:
            raise JPVRFormatError("max_pages must be a non-negative integer")
        with self._locked(fcntl.LOCK_SH):
            active = self._select_active_unlocked()
            page_count = max(0, active.durable_tail_page_no - 1)
            if page_count > max_pages:
                raise JPVRFormatError("page catalog exceeds max_pages memory bound")
            size = os.fstat(self._fd).st_size
            catalog = {
                page_no: self._read_page_unlocked(
                    page_no, tail=active.durable_tail_page_no, file_size=size
                )
                for page_no in range(2, active.durable_tail_page_no + 1)
            }
            self._superblock = active
            return active, catalog

    def load_page_catalog(self, *, max_pages: int = 4096) -> dict[int, bytes]:
        """Materialize a bounded page catalog for the typed in-memory builder."""
        return self.load_snapshot(max_pages=max_pages)[1]

    def close(self) -> None:
        with self._mutex:
            if self._fd >= 0:
                os.close(self._fd)
                self._fd = -1

    def __enter__(self) -> "JPVRStorage":
        self._ensure_open()
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        self.close()
