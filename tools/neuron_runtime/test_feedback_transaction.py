"""Tests for all-or-nothing R-STDP feedback + composite JPVR publication."""
import os
import tempfile
import unittest

from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.feedback_transaction import (
    RewardFeedbackTransactionConflict,
    RewardFeedbackTransactionError,
    RewardSynapseTarget,
    process_reward_feedback,
)
from tools.neuron_runtime.network import NetworkRuntime
from tools.neuron_runtime.reward_baseline import RewardBaselineManager
from tools.neuron_runtime.runtime import NeuronRuntime
from tools.neuron_runtime.runtime_store import (
    JPVRRuntimeCheckpointConflict,
    JPVRRuntimeCheckpointStore,
)
from tools.numerical_oracle.jarvis_oracle import RewardParams, SynapseState


EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")
CONTEXT = "episode-7"
BETA = 0.01


def _eligible_synapse(context=CONTEXT):
    return SynapseState(
        weight=0.5,
        eligibility_trace=0.5,
        eligibility_context_id=context,
        eligibility_started_model_time_ns=1,
        trace_last_update_model_time_ns=1,
        eligibility_last_update_model_time_ns=1,
    )


def _network(*, second_context=None, beta=BETA, queued_event=False):
    neurons = {
        "node-a": NeuronRuntime(
            {"synapse-a": _eligible_synapse()},
            reward_params=RewardParams(beta_b=beta),
            learning_rule="rstdp",
        ),
    }
    if second_context is not None:
        neurons["node-b"] = NeuronRuntime(
            {"synapse-b": _eligible_synapse(second_context)},
            reward_params=RewardParams(beta_b=beta),
            learning_rule="rstdp",
        )
    network = NetworkRuntime(neurons, [])
    if queued_event:
        network.schedule_input(
            10, "node-a", "spare", "later-event", 0.5
        )
    return network


def _manager(beta=BETA):
    return RewardBaselineManager(
        manager_id="feedback-manager",
        clock_epoch_id=EPOCH,
        initial_baseline=0.5,
        beta_b=beta,
    )


class RewardFeedbackTransactionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "runtime.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRRuntimeCheckpointStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def _bootstrap(self, network=None, manager=None):
        network = _network() if network is None else network
        manager = _manager() if manager is None else manager
        saved = self.store.save("test-network", network, manager)
        return network, manager, saved.runtime_version

    def _process(self, network, manager, version, **overrides):
        args = {
            "store": self.store,
            "network_id": "test-network",
            "network": network,
            "reward_baseline_manager": manager,
            "reward_task_id": "task-main",
            "feedback_id": "feedback-1",
            "feedback_sequence": 1,
            "reward_value": 1.0,
            "model_time_ns": 10,
            "targets": (RewardSynapseTarget("node-a", "synapse-a"),),
            "learning_context_id": CONTEXT,
            "expected_runtime_version": version,
        }
        args.update(overrides)
        args.pop("store")
        return process_reward_feedback(self.store, **args)

    def test_success_updates_synapse_and_baseline_in_one_jpvr_commit(self):
        network, manager, version = self._bootstrap()
        old_generation = self.storage.get_superblock().generation
        result = self._process(network, manager, version)

        self.assertFalse(result.duplicate)
        self.assertEqual(result.runtime_version, version + 1)
        self.assertEqual(self.storage.get_superblock().generation, old_generation + 1)
        self.assertEqual(result.receipt.processing_status, "APPLIED")
        self.assertAlmostEqual(result.receipt.baseline_after, 0.505)
        self.assertGreater(
            result.network.synapse_states("node-a")["synapse-a"].weight, 0.5
        )
        self.assertEqual(
            result.network.synapse_states("node-a")["synapse-a"].eligibility_trace,
            0.0,
        )
        # Input objects are immutable from the transaction caller's perspective.
        self.assertEqual(network.synapse_states("node-a")["synapse-a"].weight, 0.5)
        self.assertEqual(manager.get_baseline("task-main"), 0.5)

        loaded = self.store.load("test-network", manager.manager_id)
        self.assertEqual(
            loaded.reward_baseline_manager.get_record("task-main").history[-1],
            result.receipt,
        )
        self.assertEqual(
            loaded.snapshot.runtime.synapse_states("node-a")["synapse-a"].weight,
            result.network.synapse_states("node-a")["synapse-a"].weight,
        )

    def test_second_target_failure_rolls_back_every_candidate_and_baseline(self):
        network, manager, version = self._bootstrap(
            _network(second_context="different-episode"), _manager()
        )
        generation = self.storage.get_superblock().generation
        before_a = network.synapse_states("node-a")["synapse-a"]
        before_b = network.synapse_states("node-b")["synapse-b"]

        with self.assertRaises(RewardFeedbackTransactionError):
            self._process(
                network, manager, version,
                targets=(
                    RewardSynapseTarget("node-a", "synapse-a"),
                    RewardSynapseTarget("node-b", "synapse-b"),
                ),
            )

        self.assertEqual(self.storage.get_superblock().generation, generation)
        self.assertEqual(network.synapse_states("node-a")["synapse-a"], before_a)
        self.assertEqual(network.synapse_states("node-b")["synapse-b"], before_b)
        self.assertEqual(manager.get_baseline("task-main"), 0.5)
        self.assertIsNone(manager.get_record("task-main").pending)

    def test_already_committed_feedback_replay_does_not_apply_twice(self):
        network, manager, version = self._bootstrap()
        first = self._process(network, manager, version)
        generation = self.storage.get_superblock().generation
        replay = self._process(
            first.network,
            first.reward_baseline_manager,
            first.runtime_version,
        )
        self.assertTrue(replay.duplicate)
        self.assertEqual(replay.updates, ())
        self.assertIsNone(replay.saved_checkpoint)
        self.assertEqual(replay.runtime_version, first.runtime_version)
        self.assertEqual(self.storage.get_superblock().generation, generation)
        self.assertEqual(
            replay.network.synapse_states("node-a")["synapse-a"].weight,
            first.network.synapse_states("node-a")["synapse-a"].weight,
        )

    def test_no_eligibility_is_a_terminal_no_op_with_baseline_commit(self):
        empty = NeuronRuntime(
            {"synapse-a": SynapseState(weight=0.5)},
            reward_params=RewardParams(beta_b=BETA),
            learning_rule="rstdp",
        )
        network = NetworkRuntime({"node-a": empty}, [])
        manager = _manager()
        network, manager, version = self._bootstrap(network, manager)
        result = self._process(network, manager, version)
        self.assertEqual(result.receipt.processing_status, "NO_ELIGIBILITY")
        self.assertEqual(
            result.network.synapse_states("node-a")["synapse-a"].weight, 0.5
        )
        self.assertAlmostEqual(manager.get_baseline("task-main"), 0.5)
        self.assertAlmostEqual(
            result.reward_baseline_manager.get_baseline("task-main"), 0.505
        )

    def test_reward_beta_mismatch_is_rejected_before_any_commit(self):
        network, manager, version = self._bootstrap(_network(beta=0.02), _manager())
        generation = self.storage.get_superblock().generation
        with self.assertRaises(RewardFeedbackTransactionError):
            self._process(network, manager, version)
        self.assertEqual(self.storage.get_superblock().generation, generation)
        self.assertEqual(network.synapse_states("node-a")["synapse-a"].weight, 0.5)
        self.assertEqual(manager.get_baseline("task-main"), 0.5)

    def test_stale_runtime_version_rejects_commit_without_mutating_inputs(self):
        network, manager, version = self._bootstrap()
        # Simulate another writer publishing a newer complete snapshot.
        external = self.store.load("test-network", manager.manager_id)
        other = self.store.save(
            "test-network",
            external.snapshot.runtime,
            external.reward_baseline_manager,
            expected_runtime_version=version,
        )
        generation = self.storage.get_superblock().generation
        with self.assertRaises(JPVRRuntimeCheckpointConflict):
            self._process(network, manager, version)
        self.assertEqual(self.storage.get_superblock().generation, generation)
        self.assertEqual(network.synapse_states("node-a")["synapse-a"].weight, 0.5)
        self.assertEqual(manager.get_baseline("task-main"), 0.5)
        self.assertEqual(other.runtime_version, version + 1)

    def test_duplicate_targets_are_rejected(self):
        network, manager, version = self._bootstrap()
        with self.assertRaises(RewardFeedbackTransactionError):
            self._process(
                network, manager, version,
                targets=(
                    RewardSynapseTarget("node-a", "synapse-a"),
                    RewardSynapseTarget("node-a", "synapse-a"),
                ),
            )
        self.assertEqual(network.synapse_states("node-a")["synapse-a"].weight, 0.5)

    def test_target_beta_must_match_manager_beta_exactly(self):
        network, manager, version = self._bootstrap(_network(beta=0.0100001), _manager())
        with self.assertRaises(RewardFeedbackTransactionError):
            self._process(network, manager, version)
        self.assertEqual(manager.get_baseline("task-main"), 0.5)


if __name__ == "__main__":
    unittest.main()
