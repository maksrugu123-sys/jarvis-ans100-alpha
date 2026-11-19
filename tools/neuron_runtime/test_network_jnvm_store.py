"""Tests for atomic one-COMMIT multi-neuron network checkpoints."""
from __future__ import annotations
import os
import tempfile
import unittest
from unittest.mock import patch

from tools.jpvr_reference.storage import JPVRCommitUncertainError, JPVRStorage
from tools.neuron_runtime.network import NetworkConnection, NetworkRuntime
from tools.neuron_runtime.network_jnvm_store import JPVRNetworkJNVMConflict, JPVRNetworkJNVMStore
from tools.neuron_runtime.runtime import NeuronRuntime
from tools.neuron_runtime.jpvr_store import JPVRNeuronCheckpointConflict

EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def make_network():
    runtime = NetworkRuntime(
        {"source": NeuronRuntime({"external": 1.0}), "target": NeuronRuntime({"from-source": 1.0})},
        [NetworkConnection("source-target", "source", "target", "from-source", 5)],
        max_pending_events=32, max_events_per_run=16,
    )
    runtime.schedule_input(0, "source", "external", "pulse", 1.0)
    return runtime


class NetworkJNVMStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "network.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRNetworkJNVMStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def initial_save(self):
        runtime = make_network()
        saved = self.store.save_timestamp(
            "network-A", runtime, runtime.neuron_ids,
            clock_epoch_id=EPOCH,
            expected_network_record_version=0,
            expected_neuron_record_versions={key: 0 for key in runtime.neuron_ids},
        )
        return runtime, saved

    def test_initial_group_commit_restores_scheduler_and_neurons(self):
        runtime, saved = self.initial_save()
        restored = self.store.restore_network("network-A")
        self.assertEqual(saved.generation, 2)
        self.assertEqual(restored.network_record_version, 1)
        self.assertEqual(dict(restored.neuron_record_versions), {"source": 1, "target": 1})
        self.assertEqual(restored.runtime.neuron_ids, runtime.neuron_ids)
        self.assertEqual(restored.runtime.connections, runtime.connections)
        self.assertEqual(restored.runtime.pending_events, runtime.pending_events)
        self.assertEqual(restored.runtime.current_time_ns, runtime.current_time_ns)
        self.assertEqual(restored.clock_epoch_id, EPOCH)
        scheduler = self.store.load_scheduler("network-A")
        self.assertEqual(dict(scheduler.snapshot.neuron_record_versions), {"source": 1, "target": 1})

    def test_changed_neuron_and_scheduler_share_one_commit(self):
        runtime, first = self.initial_save()
        runtime.run()
        second = self.store.save_timestamp(
            "network-A", runtime, ("source",), clock_epoch_id=EPOCH,
            expected_network_record_version=first.network_record_version,
            expected_neuron_record_versions={"source": first.neuron_record_versions["source"]},
        )
        scheduler = self.store.load_scheduler("network-A")
        neuron = self.store.load_neuron("source")
        self.assertEqual(second.generation, first.generation + 1)
        self.assertEqual(second.neuron_record_versions, {"source": 2})
        self.assertEqual(scheduler.locator.commit_id, second.commit_id)
        self.assertEqual(neuron.locator.commit_id, second.commit_id)
        self.assertEqual(scheduler.locator.record_generation, neuron.locator.record_generation)
        restored = self.store.restore_network("network-A")
        self.assertEqual(restored.runtime.neuron_states["source"].spike_sequence,
                         runtime.neuron_states["source"].spike_sequence)

    def test_feature_sidecars_survive_atomic_network_checkpoint_restore(self):
        from tools.neuron_runtime.neuron_features import NeuronFeatureState, NeuronRole, SynapticMemoryState

        runtime = NetworkRuntime(
            {"source": NeuronRuntime({"external": 1.0})},
            feature_states={"source": NeuronFeatureState(sensitivity=1.4, memory_trace=0.3,
                                                       pattern_signature=987654, pattern_trace=0.72,
                                                       pattern_observations=9)},
            population_roles={"source": NeuronRole.PATTERN},
            synaptic_memories={"source": {"external": SynapticMemoryState(
                coactivation_trace=0.2, predictive_trace=0.4, reward_trace=0.1,
            )}},
        )
        runtime.schedule_input(0, "source", "external", "feature-pulse", 0.4)
        runtime.run()
        saved = self.store.save_timestamp(
            "feature-network", runtime, ("source",),
            clock_epoch_id=EPOCH,
            expected_network_record_version=0,
            expected_neuron_record_versions={"source": 0},
        )
        restored = self.store.restore_network("feature-network")

        self.assertEqual(restored.generation, saved.generation)
        self.assertEqual(restored.runtime.feature_states["source"], runtime.feature_states["source"])
        self.assertEqual(
            restored.runtime.synaptic_memories["source"]["external"],
            runtime.synaptic_memories["source"]["external"],
        )

    def test_stale_scheduler_version_rejects_without_commit(self):
        runtime, _ = self.initial_save()
        before = self.storage.get_superblock()
        with self.assertRaises(JPVRNetworkJNVMConflict):
            self.store.save_timestamp(
                "network-A", runtime, ("source",), clock_epoch_id=EPOCH,
                expected_network_record_version=0,
                expected_neuron_record_versions={"source": 1},
            )
        self.assertEqual(self.storage.get_superblock(), before)

    def test_stale_neuron_version_rejects_without_commit(self):
        runtime, first = self.initial_save()
        before = self.storage.get_superblock()
        with self.assertRaises(JPVRNetworkJNVMConflict):
            self.store.save_timestamp(
                "network-A", runtime, ("source",), clock_epoch_id=EPOCH,
                expected_network_record_version=first.network_record_version,
                expected_neuron_record_versions={"source": 0},
            )
        self.assertEqual(self.storage.get_superblock(), before)

    def test_topology_change_is_rejected(self):
        _, first = self.initial_save()
        altered = NetworkRuntime(
            {"source": NeuronRuntime({"external": 1.0}), "target": NeuronRuntime({"from-source": 1.0})},
            [NetworkConnection("different-route", "source", "target", "from-source", 5)],
            max_pending_events=32, max_events_per_run=16,
        )
        with self.assertRaises(JPVRNetworkJNVMConflict):
            self.store.save_timestamp(
                "network-A", altered, (), clock_epoch_id=EPOCH,
                expected_network_record_version=first.network_record_version,
                expected_neuron_record_versions={},
            )

    def test_restore_rejects_neuron_version_changed_outside_group_commit(self):
        _, saved = self.initial_save()
        loaded = self.store._neurons.load("target")
        self.store._neurons.save(
            "target",
            loaded.snapshot.runtime,
            clock_epoch_id=EPOCH,
            expected_record_version=saved.neuron_record_versions["target"],
        )
        with self.assertRaises(JPVRNetworkJNVMConflict):
            self.store.restore_network("network-A")

    def test_group_save_rejects_neuron_version_drift(self):
        runtime, first = self.initial_save()
        loaded = self.store._neurons.load("target")
        self.store._neurons.save(
            "target",
            loaded.snapshot.runtime,
            clock_epoch_id=EPOCH,
            expected_record_version=first.neuron_record_versions["target"],
        )
        before = self.storage.get_superblock()
        runtime.run()
        with self.assertRaises(JPVRNetworkJNVMConflict):
            self.store.save_timestamp(
                "network-A", runtime, ("source",),
                clock_epoch_id=EPOCH,
                expected_network_record_version=first.network_record_version,
                expected_neuron_record_versions={"source": first.neuron_record_versions["source"]},
            )
        self.assertEqual(self.storage.get_superblock(), before)

    def test_uncertain_commit_is_reconciled_after_reopen(self):
        runtime, first = self.initial_save()
        runtime.run()
        original_commit = self.storage.commit

        def commit_then_raise(plan):
            published = original_commit(plan)
            raise JPVRCommitUncertainError(plan.assembly.commit_id, published.generation,
                                           "injected uncertainty after publication")

        with patch.object(self.storage, "commit", side_effect=commit_then_raise):
            with self.assertRaises(JPVRCommitUncertainError):
                self.store.save_timestamp(
                    "network-A", runtime, ("source",), clock_epoch_id=EPOCH,
                    expected_network_record_version=first.network_record_version,
                    expected_neuron_record_versions={"source": first.neuron_record_versions["source"]},
                )
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        restored = JPVRNetworkJNVMStore(reopened).restore_network("network-A")
        self.assertEqual(restored.network_record_version, 2)
        self.assertEqual(restored.neuron_record_versions["source"], 2)
        self.assertEqual(restored.runtime.neuron_states["source"].spike_sequence,
                         runtime.neuron_states["source"].spike_sequence)



    def test_population_roles_survive_atomic_scheduler_checkpoint_restore(self):
        from tools.neuron_runtime.neuron_features import NeuronRole

        runtime = NetworkRuntime(
            {
                "source": NeuronRuntime({"external": 1.0}),
                "target": NeuronRuntime({"input": 1.0}),
            },
            [NetworkConnection("route", "source", "target", "input", 5)],
            population_roles={
                "source": NeuronRole.ATTENTION,
                "target": NeuronRole.MEMORY,
            },
        )
        saved = self.store.save_timestamp(
            "population-network", runtime, runtime.neuron_ids,
            clock_epoch_id=EPOCH,
            expected_network_record_version=0,
            expected_neuron_record_versions={key: 0 for key in runtime.neuron_ids},
        )
        scheduler = self.store.load_scheduler("population-network")
        restored = self.store.restore_network("population-network")

        self.assertEqual(dict(scheduler.snapshot.population_roles), {
            "source": NeuronRole.ATTENTION.value,
            "target": NeuronRole.MEMORY.value,
        })
        self.assertEqual(dict(restored.runtime.population_registry.roles), {
            "source": NeuronRole.ATTENTION,
            "target": NeuronRole.MEMORY,
        })
        self.assertEqual(restored.generation, saved.generation)

    def test_default_population_roles_are_explicitly_persisted(self):
        from tools.neuron_runtime.neuron_features import NeuronRole

        runtime = make_network()
        self.store.save_timestamp(
            "default-populations", runtime, runtime.neuron_ids,
            clock_epoch_id=EPOCH,
            expected_network_record_version=0,
            expected_neuron_record_versions={key: 0 for key in runtime.neuron_ids},
        )
        snapshot = self.store.load_scheduler("default-populations").snapshot
        self.assertEqual(dict(snapshot.population_roles), {
            "source": NeuronRole.COORDINATOR.value,
            "target": NeuronRole.COORDINATOR.value,
        })

    def test_unknown_persisted_population_role_is_rejected(self):
        import json
        from tools.neuron_runtime.network_jnvm_store import (
            encode_network_scheduler_snapshot, decode_network_scheduler_snapshot,
        )

        runtime = make_network()
        payload = encode_network_scheduler_snapshot(
            "invalid-population", runtime, clock_epoch_id=EPOCH,
            neuron_record_versions={"source": 1, "target": 1},
        )
        document = json.loads(payload)
        document["population_roles"]["source"] = "not-a-real-role"
        with self.assertRaises(ValueError):
            decode_network_scheduler_snapshot(json.dumps(document).encode("utf-8"))


    def test_scheduler_v3_remains_readable_with_coordinator_defaults(self):
        import json
        from tools.neuron_runtime.network_jnvm_store import (
            encode_network_scheduler_snapshot, decode_network_scheduler_snapshot,
        )

        runtime = make_network()
        payload = encode_network_scheduler_snapshot(
            "legacy-v3", runtime, clock_epoch_id=EPOCH,
            neuron_record_versions={"source": 1, "target": 1},
        )
        document = json.loads(payload)
        document["format"] = "JARVIS_NETWORK_SCHEDULER_SNAPSHOT_V3"
        document["schema_version"] = 3
        document.pop("population_roles")
        snapshot = decode_network_scheduler_snapshot(json.dumps(document).encode("utf-8"))
        self.assertEqual(set(snapshot.population_roles.values()), {"coordinator"})

    def test_scheduler_v2_remains_readable_as_excitatory_only(self):
        import json
        from tools.neuron_runtime.network_jnvm_store import (
            encode_network_scheduler_snapshot, decode_network_scheduler_snapshot,
        )

        runtime = make_network()
        payload = encode_network_scheduler_snapshot(
            "legacy-v2", runtime, clock_epoch_id=EPOCH,
            neuron_record_versions={"source": 1, "target": 1},
        )
        document = json.loads(payload)
        document["format"] = "JARVIS_NETWORK_SCHEDULER_SNAPSHOT_V2"
        document["schema_version"] = 2
        document.pop("population_roles")
        for connection in document["connections"]:
            connection.pop("signal_type")
        for event in document["pending_events"]:
            event.pop("inhibitory")
        snapshot = decode_network_scheduler_snapshot(json.dumps(document).encode("utf-8"))
        self.assertEqual(set(snapshot.population_roles.values()), {"coordinator"})
        self.assertTrue(all(connection.signal_type == "excitatory" for connection in snapshot.connections))

    def test_pending_inhibitory_route_is_persisted_and_restored(self):
        runtime = NetworkRuntime(
            {
                "source": NeuronRuntime({"external": 1.0}),
                "target": NeuronRuntime({"input": 1.0}),
            },
            [NetworkConnection(
                "inhibitory-route", "source", "target", None, 5, 0.7, 0.0, "inhibitory",
            )],
            max_pending_events=32, max_events_per_run=16,
        )
        runtime.schedule_input(0, "source", "external", "pulse", 1.0)
        first = self.store.save_timestamp(
            "inhibitory-network", runtime, runtime.neuron_ids,
            clock_epoch_id=EPOCH,
            expected_network_record_version=0,
            expected_neuron_record_versions={key: 0 for key in runtime.neuron_ids},
        )

        runtime.run(max_timestamps=1)
        second = self.store.save_timestamp(
            "inhibitory-network", runtime, ("source",),
            clock_epoch_id=EPOCH,
            expected_network_record_version=first.network_record_version,
            expected_neuron_record_versions={"source": first.neuron_record_versions["source"]},
        )
        restored = self.store.restore_network("inhibitory-network")

        self.assertEqual(restored.network_record_version, second.network_record_version)
        self.assertEqual(restored.runtime.connections, runtime.connections)
        self.assertEqual(restored.runtime.pending_events, runtime.pending_events)
        result = restored.runtime.run()
        self.assertEqual(result.pending_events, 0)
        self.assertGreater(restored.runtime.feature_states["target"].inhibition, 0.0)



if __name__ == "__main__":
    unittest.main(verbosity=2)
