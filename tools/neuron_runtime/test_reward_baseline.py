"""Contracts for ordered, idempotent R-STDP reward-baseline ownership."""
import json
import unittest

from tools.neuron_runtime.reward_baseline import (
    RewardBaselineConflict,
    RewardBaselineDeferred,
    RewardBaselineError,
    RewardBaselineManager,
)
from tools.neuron_runtime.reward_baseline_adapter import (
    REWARD_BASELINE_SCHEMA_VERSION,
    RewardBaselinePersistenceError,
    decode_reward_baseline_snapshot,
    encode_reward_baseline_snapshot,
)


EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def _manager(**kwargs):
    return RewardBaselineManager(clock_epoch_id=EPOCH, **kwargs)


class RewardBaselineManagerTests(unittest.TestCase):
    def test_prepare_uses_old_baseline_and_commit_advances_afterward(self):
        manager = _manager(initial_baseline=0.5, beta_b=0.1)
        proposal = manager.prepare_feedback(
            "task-a", "feedback-1", 1, 1.0, 10, clock_epoch_id=EPOCH
        )
        self.assertEqual(proposal.baseline_before, 0.5)
        self.assertEqual(proposal.modulator, 0.5)
        self.assertAlmostEqual(proposal.baseline_after, 0.55)
        self.assertEqual(manager.get_baseline("task-a"), 0.5)
        self.assertEqual(manager.get_record("task-a").baseline_version, 0)
        receipt = manager.commit_feedback(proposal, processing_status="APPLIED")
        self.assertEqual(receipt.baseline_version, 1)
        self.assertAlmostEqual(manager.get_baseline("task-a"), 0.55)

    def test_baselines_are_isolated_per_reward_task(self):
        manager = _manager(initial_baseline=0.4, beta_b=0.5)
        a = manager.prepare_feedback("a", "a-1", 1, 1.0, 1, clock_epoch_id=EPOCH)
        manager.commit_feedback(a, processing_status="NO_ELIGIBILITY")
        self.assertEqual(manager.get_baseline("a"), 0.7)
        self.assertEqual(manager.get_baseline("b"), 0.4)

    def test_feedback_sequence_gap_is_deferred_without_state_change(self):
        manager = _manager()
        with self.assertRaises(RewardBaselineDeferred) as caught:
            manager.prepare_feedback("task", "f-2", 2, 0.9, 20, clock_epoch_id=EPOCH)
        self.assertEqual(caught.exception.expected_sequence, 1)
        self.assertEqual(manager.task_count, 0)

    def test_exact_pending_retry_returns_same_proposal(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 0.9, 10, clock_epoch_id=EPOCH)
        retry = manager.prepare_feedback("task", "f-1", 1, 0.9, 10, clock_epoch_id=EPOCH)
        self.assertEqual(retry, proposal)
        self.assertEqual(manager.get_record("task").pending, proposal)

    def test_different_feedback_cannot_replace_pending_reservation(self):
        manager = _manager()
        manager.prepare_feedback("task", "f-1", 1, 0.9, 10, clock_epoch_id=EPOCH)
        with self.assertRaises(RewardBaselineConflict):
            manager.prepare_feedback("task", "f-other", 1, 0.1, 11, clock_epoch_id=EPOCH)

    def test_committed_feedback_retry_is_idempotent(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        first = manager.commit_feedback(proposal, processing_status="APPLIED")
        duplicate = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        self.assertEqual(duplicate.status, "DUPLICATE")
        second = manager.commit_feedback(duplicate, processing_status="APPLIED")
        self.assertEqual(second, first)
        self.assertEqual(manager.get_record("task").baseline_version, 1)

    def test_same_sequence_with_different_id_or_payload_conflicts(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        manager.commit_feedback(proposal, processing_status="APPLIED")
        with self.assertRaises(RewardBaselineConflict):
            manager.prepare_feedback("task", "other-id", 1, 1.0, 10, clock_epoch_id=EPOCH)
        with self.assertRaises(RewardBaselineConflict):
            manager.prepare_feedback("task", "f-1", 1, 0.0, 10, clock_epoch_id=EPOCH)

    def test_non_terminal_processing_status_cannot_advance_baseline(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        with self.assertRaises(RewardBaselineError):
            manager.commit_feedback(proposal, processing_status="RETRY_REQUIRED")
        self.assertEqual(manager.get_baseline("task"), 0.5)
        self.assertEqual(manager.get_record("task").pending, proposal)

    def test_approved_no_op_status_can_commit_baseline(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 0.0, 10, clock_epoch_id=EPOCH)
        receipt = manager.commit_feedback(proposal, processing_status="NO_ELIGIBILITY")
        self.assertEqual(receipt.processing_status, "NO_ELIGIBILITY")
        self.assertLess(manager.get_baseline("task"), 0.5)

    def test_clock_epoch_mismatch_is_rejected(self):
        manager = _manager()
        with self.assertRaises(RewardBaselineConflict):
            manager.prepare_feedback(
                "task", "f-1", 1, 0.5, 10, clock_epoch_id=b"\x01" * 16
            )
        self.assertEqual(manager.task_count, 0)

    def test_model_time_must_not_move_backward_per_task(self):
        manager = _manager()
        first = manager.prepare_feedback("task", "f-1", 1, 0.5, 20, clock_epoch_id=EPOCH)
        manager.commit_feedback(first, processing_status="NO_ELIGIBILITY")
        with self.assertRaises(RewardBaselineConflict):
            manager.prepare_feedback("task", "f-2", 2, 0.5, 19, clock_epoch_id=EPOCH)

    def test_task_catalog_limit_is_enforced(self):
        manager = _manager(max_tasks=1)
        proposal = manager.prepare_feedback("a", "a-1", 1, 0.5, 1, clock_epoch_id=EPOCH)
        manager.commit_feedback(proposal, processing_status="NO_ELIGIBILITY")
        with self.assertRaises(RewardBaselineError):
            manager.prepare_feedback("b", "b-1", 1, 0.5, 1, clock_epoch_id=EPOCH)

    def test_invalid_values_and_boolean_integers_are_rejected(self):
        manager = _manager()
        invalid = (
            ("reward_value", 1.1, 1, 0),
            ("reward_value", float("nan"), 1, 0),
            ("feedback_sequence", 1, True, 0),
            ("model_time_ns", 1, 1, True),
        )
        for name, reward, sequence, timestamp in invalid:
            with self.subTest(name=name, reward=reward, sequence=sequence, timestamp=timestamp):
                with self.assertRaises(RewardBaselineError):
                    manager.prepare_feedback(
                        "task", "f", sequence, reward, timestamp, clock_epoch_id=EPOCH
                    )

    def test_checkpoint_state_restores_pending_feedback_for_retry(self):
        manager = _manager(beta_b=0.2)
        proposal = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        restored = RewardBaselineManager.from_checkpoint_state(manager.export_checkpoint_state())
        self.assertEqual(restored.get_record("task").pending, proposal)
        retry = restored.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        self.assertEqual(retry, proposal)
        restored.commit_feedback(retry, processing_status="APPLIED")
        self.assertAlmostEqual(restored.get_baseline("task"), 0.6)

    def test_committed_history_is_bounded(self):
        manager = _manager(history_limit=1)
        for sequence in (1, 2):
            proposal = manager.prepare_feedback(
                "task", f"f-{sequence}", sequence, 0.5, sequence, clock_epoch_id=EPOCH
            )
            manager.commit_feedback(proposal, processing_status="NO_ELIGIBILITY")
        self.assertEqual(len(manager.get_record("task").history), 1)
        with self.assertRaises(RewardBaselineConflict):
            manager.prepare_feedback("task", "f-1", 1, 0.5, 1, clock_epoch_id=EPOCH)




    def test_feedback_id_cannot_be_reused_for_another_sequence(self):
        manager = _manager()
        first = manager.prepare_feedback(
            "task", "same-feedback-id", 1, 1.0, 10, clock_epoch_id=EPOCH
        )
        manager.commit_feedback(first, processing_status="APPLIED")
        with self.assertRaises(RewardBaselineConflict):
            manager.prepare_feedback(
                "task", "same-feedback-id", 2, 1.0, 20, clock_epoch_id=EPOCH
            )
        self.assertEqual(manager.get_record("task").baseline_version, 1)

    def test_proposal_from_another_manager_is_rejected(self):
        first = _manager(manager_id="manager-a")
        second = _manager(manager_id="manager-b")
        proposal = first.prepare_feedback(
            "task", "f-1", 1, 0.9, 10, clock_epoch_id=EPOCH
        )
        second.prepare_feedback(
            "task", "f-1", 1, 0.9, 10, clock_epoch_id=EPOCH
        )
        with self.assertRaises(RewardBaselineConflict):
            second.commit_feedback(proposal, processing_status="APPLIED")
        self.assertEqual(second.get_baseline("task"), 0.5)

    def test_non_string_processing_status_is_rejected(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 0.9, 10, clock_epoch_id=EPOCH)
        with self.assertRaises(RewardBaselineError):
            manager.commit_feedback(proposal, processing_status=[])
        self.assertEqual(manager.get_baseline("task"), 0.5)
        self.assertEqual(manager.get_record("task").pending, proposal)


    def test_restore_rejects_reused_feedback_id_in_history(self):
        manager = _manager(beta_b=0.5)
        for sequence, reward in ((1, 1.0), (2, 0.0)):
            proposal = manager.prepare_feedback(
                "task", f"feedback-{sequence}", sequence, reward,
                sequence * 10, clock_epoch_id=EPOCH,
            )
            manager.commit_feedback(proposal, processing_status="APPLIED")
        state = manager.export_checkpoint_state()
        state["records"][0]["history"][1]["feedback_id"] = "feedback-1"
        with self.assertRaises(RewardBaselineError):
            RewardBaselineManager.from_checkpoint_state(state)

    def test_restore_rejects_non_monotonic_or_broken_history_chain(self):
        manager = _manager(beta_b=0.5)
        first = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        manager.commit_feedback(first, processing_status="APPLIED")
        second = manager.prepare_feedback("task", "f-2", 2, 0.0, 20, clock_epoch_id=EPOCH)
        manager.commit_feedback(second, processing_status="APPLIED")
        state = manager.export_checkpoint_state()
        state["records"][0]["history"][1]["baseline_before"] = 0.123
        state["records"][0]["history"][1]["modulator"] = -0.123
        state["records"][0]["history"][1]["baseline_after"] = 0.0615
        with self.assertRaises(RewardBaselineError):
            RewardBaselineManager.from_checkpoint_state(state)

    def test_restore_rejects_tampered_baseline_history(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 1.0, 10, clock_epoch_id=EPOCH)
        manager.commit_feedback(proposal, processing_status="APPLIED")
        state = manager.export_checkpoint_state()
        state["records"][0]["expected_reward"] = 0.1
        with self.assertRaises(RewardBaselineError):
            RewardBaselineManager.from_checkpoint_state(state)


class RewardBaselineSnapshotCodecTests(unittest.TestCase):
    def test_canonical_snapshot_round_trip(self):
        manager = _manager()
        proposal = manager.prepare_feedback("task", "f-1", 1, 0.8, 10, clock_epoch_id=EPOCH)
        manager.commit_feedback(proposal, processing_status="APPLIED")
        first = encode_reward_baseline_snapshot(manager)
        second = encode_reward_baseline_snapshot(manager)
        self.assertEqual(first, second)
        decoded = decode_reward_baseline_snapshot(first)
        self.assertEqual(decoded.manager_id, manager.manager_id)
        self.assertEqual(decoded.clock_epoch_id, EPOCH)
        self.assertEqual(
            decoded.manager.export_checkpoint_state(), manager.export_checkpoint_state()
        )

    def test_snapshot_codec_preserves_pending_work(self):
        manager = _manager()
        manager.prepare_feedback("task", "f-1", 1, 0.8, 10, clock_epoch_id=EPOCH)
        decoded = decode_reward_baseline_snapshot(encode_reward_baseline_snapshot(manager))
        self.assertEqual(
            decoded.manager.get_record("task").pending,
            manager.get_record("task").pending,
        )

    def test_duplicate_json_keys_are_rejected(self):
        payload = (
            b'{"format":"JARVIS_REWARD_BASELINE_MANAGER_V1",'
            b'"format":"JARVIS_REWARD_BASELINE_MANAGER_V1",'
            b'"schema_version":1,"manager_id":"x",'
            b'"clock_epoch_id":"abcdefabcdefabcdefabcdefabcdefab","state":{}}'
        )
        with self.assertRaises(RewardBaselinePersistenceError):
            decode_reward_baseline_snapshot(payload)

    def test_unknown_schema_version_is_rejected(self):
        document = json.loads(encode_reward_baseline_snapshot(_manager()))
        document["schema_version"] = REWARD_BASELINE_SCHEMA_VERSION + 1
        with self.assertRaises(RewardBaselinePersistenceError):
            decode_reward_baseline_snapshot(
                json.dumps(document, sort_keys=True, separators=(",", ":")).encode()
            )

    def test_non_finite_json_constant_is_rejected(self):
        payload = (
            b'{"format":"JARVIS_REWARD_BASELINE_MANAGER_V1",'
            b'"schema_version":1,"manager_id":"x",'
            b'"clock_epoch_id":"abcdefabcdefabcdefabcdefabcdefab",'
            b'"state":NaN}'
        )
        with self.assertRaises(RewardBaselinePersistenceError):
            decode_reward_baseline_snapshot(payload)


if __name__ == "__main__":
    unittest.main()
