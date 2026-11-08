import unittest
from dataclasses import replace

from .codec import JPVRFormatError, Superblock
from .layout import plan_append, validate_append_plan


class AppendPlannerTests(unittest.TestCase):
    CONTAINER_ID = bytes.fromhex("00112233445566778899aabbccddeeff")
    COMMIT_ID_1 = bytes.fromhex("11111111111111111111111111111111")
    COMMIT_ID_2 = bytes.fromhex("22222222222222222222222222222222")

    def genesis(self, slot_id=0):
        return Superblock(
            container_uuid=self.CONTAINER_ID,
            generation=0,
            active_commit_page_no=0,
            checkpoint_page_no=0,
            durable_tail_page_no=1,
            active_commit_id=bytes(16),
            slot_id=slot_id,
        )

    def test_genesis_append_plan_starts_at_page_two_and_commits_last(self):
        plan = plan_append(self.genesis(), 3, self.COMMIT_ID_1)
        self.assertEqual((plan.first_page_no, plan.last_page_no), (2, 4))
        self.assertEqual(plan.page_count, 3)
        self.assertEqual(list(plan.page_numbers), [2, 3, 4])
        self.assertEqual(plan.commit_page_no, 4)
        self.assertEqual(plan.target_superblock_slot_id, 1)
        self.assertEqual(plan.new_superblock.generation, 1)
        self.assertEqual(plan.new_superblock.durable_tail_page_no, 4)
        self.assertEqual(plan.new_superblock.active_commit_page_no, 4)

    def test_superblock_slots_alternate_after_each_commit(self):
        first = plan_append(self.genesis(slot_id=0), 2, self.COMMIT_ID_1)
        second = plan_append(first.new_superblock, 1, self.COMMIT_ID_2)
        self.assertEqual(first.target_superblock_slot_id, 1)
        self.assertEqual(second.target_superblock_slot_id, 0)
        self.assertEqual(second.new_superblock.generation, 2)
        self.assertEqual((second.first_page_no, second.last_page_no), (4, 4))

    def test_existing_checkpoint_is_preserved_by_default(self):
        current = Superblock(
            self.CONTAINER_ID, 3, 10, 7, 12, self.COMMIT_ID_1, 1
        )
        plan = plan_append(current, 2, self.COMMIT_ID_2)
        self.assertEqual(plan.new_superblock.checkpoint_page_no, 7)

    def test_checkpoint_can_move_into_new_append_range(self):
        plan = plan_append(self.genesis(), 4, self.COMMIT_ID_1, checkpoint_page_no=3)
        self.assertEqual(plan.new_superblock.checkpoint_page_no, 3)

    def test_checkpoint_beyond_planned_tail_is_rejected(self):
        with self.assertRaisesRegex(JPVRFormatError, "checkpoint page"):
            plan_append(self.genesis(), 2, self.COMMIT_ID_1, checkpoint_page_no=99)

    def test_zero_and_boolean_page_counts_are_rejected(self):
        with self.assertRaises(JPVRFormatError):
            plan_append(self.genesis(), 0, self.COMMIT_ID_1)
        with self.assertRaises(JPVRFormatError):
            plan_append(self.genesis(), True, self.COMMIT_ID_1)

    def test_invalid_commit_ids_are_rejected(self):
        with self.assertRaises(JPVRFormatError):
            plan_append(self.genesis(), 1, bytes(15))
        with self.assertRaisesRegex(JPVRFormatError, "nonzero"):
            plan_append(self.genesis(), 1, bytes(16))

    def test_page_number_overflow_is_rejected(self):
        current = Superblock(
            self.CONTAINER_ID, 1, 2, 0, 0xFFFFFFFFFFFFFFFF,
            self.COMMIT_ID_1, 1,
        )
        with self.assertRaisesRegex(JPVRFormatError, "exhausted"):
            plan_append(current, 1, self.COMMIT_ID_2)

    def test_append_range_overflow_is_rejected(self):
        current = Superblock(
            self.CONTAINER_ID, 1, 2, 0, 0xFFFFFFFFFFFFFFFE,
            self.COMMIT_ID_1, 1,
        )
        with self.assertRaisesRegex(JPVRFormatError, "overflows"):
            plan_append(current, 2, self.COMMIT_ID_2)

    def test_superblock_generation_overflow_is_rejected(self):
        current = Superblock(
            self.CONTAINER_ID, 0xFFFFFFFFFFFFFFFF, 2, 0, 2,
            self.COMMIT_ID_1, 1,
        )
        with self.assertRaisesRegex(JPVRFormatError, "generation overflows"):
            plan_append(current, 1, self.COMMIT_ID_2)

    def test_plan_validation_accepts_current_base(self):
        current = self.genesis()
        plan = plan_append(current, 2, self.COMMIT_ID_1)
        self.assertIsNone(validate_append_plan(plan, current))

    def test_plan_validation_rejects_stale_superblock(self):
        current = self.genesis()
        plan = plan_append(current, 2, self.COMMIT_ID_1)
        advanced = plan_append(current, 1, self.COMMIT_ID_2).new_superblock
        with self.assertRaisesRegex(JPVRFormatError, "stale"):
            validate_append_plan(plan, advanced)

    def test_plan_validation_rejects_tampered_range(self):
        current = self.genesis()
        plan = plan_append(current, 2, self.COMMIT_ID_1)
        tampered = replace(plan, last_page_no=plan.last_page_no + 1)
        with self.assertRaisesRegex(JPVRFormatError, "inconsistent"):
            validate_append_plan(tampered, current)

    def test_invalid_current_superblock_is_rejected(self):
        invalid = replace(self.genesis(), slot_id=2)
        with self.assertRaises(JPVRFormatError):
            plan_append(invalid, 1, self.COMMIT_ID_1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
