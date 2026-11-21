"""Durable CQE reward-feedback inbox and replay regressions."""
from dataclasses import replace
import json
import os
import tempfile
import unittest
import uuid
from unittest.mock import patch

from tools.cqe_reference.cqe import MessageEnvelope, MessageType
from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.cqe_feedback import (
    CQEFeedbackConflict,
    CQEFeedbackError,
    process_cqe_reward_feedback,
)
from tools.neuron_runtime.feedback_transaction import RewardFeedbackTransactionError
from tools.neuron_runtime.network import NetworkRuntime
from tools.neuron_runtime.reward_baseline import RewardBaselineManager
from tools.neuron_runtime.runtime import NeuronRuntime
from tools.neuron_runtime.runtime_store import JPVRRuntimeCheckpointStore
from tools.numerical_oracle.jarvis_oracle import RewardParams, SynapseState


EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")
CONTEXT = "episode-7"
AUTH_ID = "11111111-1111-4111-8111-111111111111"


def _network():
    synapse = SynapseState(
        weight=0.5,
        eligibility_trace=0.5,
        eligibility_context_id=CONTEXT,
        eligibility_started_model_time_ns=1,
        trace_last_update_model_time_ns=1,
        eligibility_last_update_model_time_ns=1,
    )
    neuron = NeuronRuntime(
        {"synapse-a": synapse},
        reward_params=RewardParams(beta_b=0.01),
        learning_rule="rstdp",
    )
    return NetworkRuntime({"node-a": neuron}, [])


def _manager():
    return RewardBaselineManager(
        manager_id="cqe-feedback-manager",
        clock_epoch_id=EPOCH,
        initial_baseline=0.5,
        beta_b=0.01,
    )


def _message(**changes):
    payload = {
        "reward_task_id": "task-main",
        "feedback_id": "feedback-1",
        "feedback_sequence": 1,
        "reward_value": 1.0,
        "model_time_ns": 10,
        "clock_epoch_id": EPOCH.hex(),
        "learning_context_id": CONTEXT,
        "targets": [{"neuron_id": "node-a", "synapse_id": "synapse-a"}],
    }
    fields = {
        "protocol_version": 1,
        "message_id": str(uuid.uuid4()),
        "message_type": MessageType.EVENT,
        "schema_id": "jarvis.reward.feedback",
        "schema_version": 1,
        "source_module_id": "feedback-critic",
        "payload": payload,
        "event_id": str(uuid.uuid4()),
        "correlation_id": str(uuid.uuid4()),
        "causation_id": str(uuid.uuid4()),
    }
    fields.update(changes)
    return MessageEnvelope(**fields)


class DurableCQEFeedbackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "runtime.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRRuntimeCheckpointStore(self.storage)
        self.network = _network()
        self.manager = _manager()
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def _process(
        self, envelope, *, network=None, manager=None, version=0, **changes
    ):
        args = {
            "network_id": "test-network",
            "network": self.network if network is None else network,
            "reward_baseline_manager": (
                self.manager if manager is None else manager
            ),
            "envelope": envelope,
            "policy_version": 7,
            "authorization_receipt_id": AUTH_ID,
            "expected_runtime_version": version,
        }
        args.update(changes)
        return process_cqe_reward_feedback(self.store, **args)

    def test_event_is_inboxed_before_reward_commit(self):
        message = _message()
        result = self._process(message)
        self.assertEqual(result.inbox_runtime_version, 1)
        self.assertEqual(result.runtime_version, 2)
        self.assertFalse(result.duplicate)

        loaded = self.store.load("test-network", self.manager.manager_id)
        receipt = loaded.reward_baseline_manager.get_record("task-main").history[-1]
        self.assertEqual(receipt, result.transaction.receipt)
        stored = json.loads(receipt.cqe_envelope_json)
        self.assertEqual(stored["message_id"], message.message_id)
        self.assertEqual(stored["event_id"], message.event_id)
        self.assertEqual(stored["source_module_id"], "feedback-critic")
        self.assertEqual(stored["policy_version"], 7)
        self.assertEqual(stored["authorization_receipt_id"], AUTH_ID)
        self.assertEqual(len(receipt.feedback_context_digest), 64)
        self.assertEqual(loaded.descriptor.runtime_version, 2)

    def test_interrupted_processing_recovers_pending_inbox_without_reinboxing(self):
        message = _message()
        with patch(
            "tools.neuron_runtime.cqe_feedback.process_reward_feedback",
            side_effect=RewardFeedbackTransactionError("simulated transient failure"),
        ):
            with self.assertRaises(RewardFeedbackTransactionError):
                self._process(message)

        pending = self.store.load("test-network", self.manager.manager_id)
        self.assertEqual(pending.descriptor.runtime_version, 1)
        proposal = pending.reward_baseline_manager.get_record("task-main").pending
        self.assertIsNotNone(proposal)
        self.assertIsNotNone(proposal.cqe_envelope_json)

        resumed = self._process(
            message,
            network=pending.snapshot.runtime,
            manager=pending.reward_baseline_manager,
            version=pending.descriptor.runtime_version,
        )
        self.assertIsNone(resumed.inbox_runtime_version)
        self.assertEqual(resumed.runtime_version, 2)
        self.assertFalse(resumed.duplicate)

    def test_exact_message_replay_is_a_no_write_duplicate(self):
        message = _message()
        first = self._process(message)
        generation = self.storage.get_superblock().generation
        duplicate = self._process(
            message,
            network=first.transaction.network,
            manager=first.transaction.reward_baseline_manager,
            version=first.runtime_version,
        )
        self.assertTrue(duplicate.duplicate)
        self.assertEqual(
            self.storage.get_superblock().generation, generation
        )
        self.assertEqual(duplicate.runtime_version, first.runtime_version)

    def test_changed_target_set_conflicts_for_same_message_identity(self):
        message = _message()
        first = self._process(message)
        generation = self.storage.get_superblock().generation
        payload = dict(message.payload)
        payload["targets"] = [
            {"neuron_id": "node-a", "synapse_id": "other-synapse"}
        ]
        changed = replace(message, payload=payload)
        with self.assertRaises(CQEFeedbackConflict):
            self._process(
                changed,
                network=first.transaction.network,
                manager=first.transaction.reward_baseline_manager,
                version=first.runtime_version,
            )
        self.assertEqual(
            self.storage.get_superblock().generation, generation
        )

    def test_message_identity_cannot_be_rebound_to_another_task(self):
        message = _message()
        first = self._process(message)
        payload = dict(message.payload)
        payload.update({
            "reward_task_id": "another-task",
            "feedback_id": "feedback-other",
            "reward_value": 0.2,
            "model_time_ns": 11,
        })
        changed = replace(message, payload=payload)
        with self.assertRaises(CQEFeedbackConflict):
            self._process(
                changed,
                network=first.transaction.network,
                manager=first.transaction.reward_baseline_manager,
                version=first.runtime_version,
            )

    def test_policy_receipt_and_source_provenance_are_persisted(self):
        with self.assertRaises(CQEFeedbackError):
            self._process(
                _message(), authorization_receipt_id="not-a-uuid"
            )
        message = _message()
        result = self._process(message)
        stored = json.loads(result.transaction.receipt.cqe_envelope_json)
        self.assertEqual(stored["policy_version"], 7)
        self.assertEqual(stored["authorization_receipt_id"], AUTH_ID)
        self.assertEqual(stored["source_module_id"], "feedback-critic")

    def test_wrong_event_schema_is_rejected_before_any_commit(self):
        with self.assertRaises(CQEFeedbackError):
            self._process(_message(schema_id="arbitrary.event"))
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_non_event_message_is_rejected(self):
        with self.assertRaises(CQEFeedbackError):
            self._process(_message(message_type=MessageType.COMMAND))
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_duplicate_targets_are_rejected_before_any_commit(self):
        message = _message()
        payload = dict(message.payload)
        payload["targets"] = [
            {"neuron_id": "node-a", "synapse_id": "synapse-a"},
            {"neuron_id": "node-a", "synapse_id": "synapse-a"},
        ]
        with self.assertRaises(CQEFeedbackError):
            self._process(replace(message, payload=payload))
        self.assertEqual(self.storage.get_superblock().generation, 0)

    def test_tampered_context_digest_is_rejected_during_restore(self):
        self._process(_message())
        loaded = self.store.load("test-network", self.manager.manager_id)
        state = loaded.reward_baseline_manager.export_checkpoint_state()
        state["records"][0]["history"][0]["feedback_context_digest"] = "0" * 64
        with self.assertRaises(ValueError):
            RewardBaselineManager.from_checkpoint_state(state)


    def test_legacy_jrbm_pending_and_receipt_fields_still_restore(self):
        manager = _manager()
        proposal = manager.prepare_feedback(
            "legacy-task", "legacy-feedback", 1, 0.8, 5,
            clock_epoch_id=EPOCH,
        )
        pending_state = manager.export_checkpoint_state()
        pending_state["records"][0]["pending"].pop("cqe_envelope_json")
        pending_state["records"][0]["pending"].pop("feedback_context_digest")
        restored_pending = RewardBaselineManager.from_checkpoint_state(
            pending_state
        )
        self.assertEqual(
            restored_pending.get_record("legacy-task").pending, proposal
        )

        restored_pending.commit_feedback(
            proposal, processing_status="NO_ELIGIBILITY"
        )
        receipt_state = restored_pending.export_checkpoint_state()
        receipt = receipt_state["records"][0]["history"][0]
        receipt.pop("cqe_envelope_json")
        receipt.pop("feedback_context_digest")
        restored_receipt = RewardBaselineManager.from_checkpoint_state(
            receipt_state
        )
        self.assertEqual(
            restored_receipt.export_checkpoint_state(),
            restored_pending.export_checkpoint_state(),
        )


    def test_unprocessable_target_is_rejected_before_inbox_acceptance(self):
        message = _message()
        payload = dict(message.payload)
        payload["targets"] = [
            {"neuron_id": "node-a", "synapse_id": "missing-synapse"}
        ]
        with self.assertRaises(CQEFeedbackError):
            self._process(replace(message, payload=payload))
        self.assertEqual(self.storage.get_superblock().generation, 0)


if __name__ == "__main__":
    unittest.main()
