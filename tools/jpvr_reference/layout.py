"""In-memory append planning for JPVR_FORMAT_V1; no file I/O is performed."""
from __future__ import annotations

from dataclasses import dataclass

from .codec import JPVRFormatError, Superblock, _fixed_bytes, _require_uint, encode_superblock


@dataclass(frozen=True)
class AppendPlan:
    expected_generation: int
    expected_durable_tail_page_no: int
    expected_active_slot_id: int
    first_page_no: int
    last_page_no: int
    commit_page_no: int
    target_superblock_slot_id: int
    new_superblock: Superblock

    @property
    def page_count(self) -> int:
        return self.last_page_no - self.first_page_no + 1

    @property
    def page_numbers(self) -> range:
        return range(self.first_page_no, self.last_page_no + 1)


def plan_append(
    current: Superblock,
    page_count: int,
    active_commit_id: bytes,
    *,
    checkpoint_page_no: int | None = None,
) -> AppendPlan:
    """Plan a contiguous append and inactive-superblock update without mutating state."""
    encode_superblock(current)  # Reuse strict validation of the current durable root.
    _require_uint("page_count", page_count, 0xFFFFFFFFFFFFFFFF)
    if page_count == 0:
        raise JPVRFormatError("append page_count must be positive")
    commit_id = _fixed_bytes("active_commit_id", active_commit_id, 16)
    if commit_id == bytes(16):
        raise JPVRFormatError("active_commit_id must be nonzero")
    if current.generation == 0xFFFFFFFFFFFFFFFF:
        raise JPVRFormatError("superblock generation overflows u64")
    if current.durable_tail_page_no == 0xFFFFFFFFFFFFFFFF:
        raise JPVRFormatError("durable page number space is exhausted")

    first_page_no = current.durable_tail_page_no + 1
    last_page_no = first_page_no + page_count - 1
    if last_page_no > 0xFFFFFFFFFFFFFFFF:
        raise JPVRFormatError("append page range overflows u64")

    if checkpoint_page_no is None:
        next_checkpoint = current.checkpoint_page_no
    else:
        _require_uint("checkpoint_page_no", checkpoint_page_no, 0xFFFFFFFFFFFFFFFF)
        next_checkpoint = checkpoint_page_no
    if next_checkpoint != 0 and not 2 <= next_checkpoint <= last_page_no:
        raise JPVRFormatError("checkpoint page is outside the planned durable prefix")

    target_slot_id = 1 - current.slot_id
    next_superblock = Superblock(
        container_uuid=current.container_uuid,
        generation=current.generation + 1,
        active_commit_page_no=last_page_no,
        checkpoint_page_no=next_checkpoint,
        durable_tail_page_no=last_page_no,
        active_commit_id=commit_id,
        slot_id=target_slot_id,
        flags=0,
    )
    encode_superblock(next_superblock)
    return AppendPlan(
        expected_generation=current.generation,
        expected_durable_tail_page_no=current.durable_tail_page_no,
        expected_active_slot_id=current.slot_id,
        first_page_no=first_page_no,
        last_page_no=last_page_no,
        commit_page_no=last_page_no,
        target_superblock_slot_id=target_slot_id,
        new_superblock=next_superblock,
    )


def validate_append_plan(plan: AppendPlan, current: Superblock) -> None:
    """Reject stale or modified plans before a future writer uses them."""
    if not isinstance(plan, AppendPlan):
        raise JPVRFormatError("append plan must be an AppendPlan")
    encode_superblock(current)
    _require_uint("plan.expected_generation", plan.expected_generation, 0xFFFFFFFFFFFFFFFF)
    _require_uint("plan.expected_durable_tail_page_no", plan.expected_durable_tail_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("plan.expected_active_slot_id", plan.expected_active_slot_id, 1)
    _require_uint("plan.first_page_no", plan.first_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("plan.last_page_no", plan.last_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("plan.commit_page_no", plan.commit_page_no, 0xFFFFFFFFFFFFFFFF)
    _require_uint("plan.target_superblock_slot_id", plan.target_superblock_slot_id, 1)
    if not isinstance(plan.new_superblock, Superblock):
        raise JPVRFormatError("append plan new_superblock must be a Superblock")
    encode_superblock(plan.new_superblock)
    if plan.last_page_no < plan.first_page_no or plan.commit_page_no != plan.last_page_no:
        raise JPVRFormatError("append plan range or commit page is inconsistent")
    if (plan.expected_generation != current.generation
            or plan.expected_durable_tail_page_no != current.durable_tail_page_no
            or plan.expected_active_slot_id != current.slot_id):
        raise JPVRFormatError("append plan is stale for the current superblock")
    expected = plan_append(
        current,
        plan.page_count,
        plan.new_superblock.active_commit_id,
        checkpoint_page_no=plan.new_superblock.checkpoint_page_no,
    )
    if expected != plan:
        raise JPVRFormatError("append plan fields are inconsistent")
