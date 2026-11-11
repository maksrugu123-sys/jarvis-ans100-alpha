"""Regression tests for the complete NetworkRuntime snapshot codec."""
import json
import unittest
from dataclasses import replace

from tools.neuron_runtime.network import NetworkConnection, NetworkRuntime
from tools.neuron_runtime.network_adapter import (
    NETWORK_RUNTIME_SCHEMA_VERSION,
    NetworkRuntimePersistenceError,
    decode_network_runtime_snapshot,
    encode_network_runtime_snapshot,
    network_runtime_object_id,
)
from tools.neuron_runtime.runtime import NeuronRuntime


CLOCK_EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def _network(*, reverse=False):
    items = [
        ("source", NeuronRuntime({"external": 1.0})),
        ("target", NeuronRuntime({"from-source": 1.0})),
    ]
    links = [NetworkConnection("source-target", "source", "target", "from-source", 5)]
    if reverse:
        items.reverse()
        links.reverse()
    network = NetworkRuntime(dict(items), links, max_pending_events=12, max_events_per_run=6)
    network.schedule_input(0, "source", "external", "pulse", 1.0)
    network.run()
    return network


class NetworkSnapshotCodecTests(unittest.TestCase):
    def test_round_trip_preserves_topology_pending_event_and_clock(self):
        original = _network()
        encoded = encode_network_runtime_snapshot("main-network", original, clock_epoch_id=CLOCK_EPOCH)
        restored = decode_network_runtime_snapshot(encoded)
        self.assertEqual(restored.network_id, "main-network")
        self.assertEqual(restored.clock_epoch_id, CLOCK_EPOCH)
        self.assertEqual(restored.runtime.neuron_ids, original.neuron_ids)
        self.assertEqual(restored.runtime.connections, original.connections)
        self.assertEqual(restored.runtime.current_time_ns, original.current_time_ns)
        self.assertEqual(restored.runtime.pending_events, original.pending_events)
        self.assertEqual(restored.runtime.run().processed_events, 1)

    def test_encoding_is_deterministic_for_permuted_constructor_order(self):
        first = encode_network_runtime_snapshot("stable", _network(), clock_epoch_id=CLOCK_EPOCH)
        second = encode_network_runtime_snapshot("stable", _network(reverse=True), clock_epoch_id=CLOCK_EPOCH)
        self.assertEqual(first, second)

    def test_preserves_scheduler_limits(self):
        decoded = decode_network_runtime_snapshot(
            encode_network_runtime_snapshot("limits", _network(), clock_epoch_id=CLOCK_EPOCH)
        )
        state = decoded.runtime.export_checkpoint_state()
        self.assertEqual(state["max_pending_events"], 12)
        self.assertEqual(state["max_events_per_run"], 6)

    def test_object_id_is_stable_and_domain_separated(self):
        self.assertEqual(network_runtime_object_id("network-A"), network_runtime_object_id("network-A"))
        self.assertNotEqual(network_runtime_object_id("network-A"), network_runtime_object_id("network-B"))

    def test_rejects_wrong_clock_epoch_in_nested_neuron(self):
        document = json.loads(encode_network_runtime_snapshot(
            "epoch", _network(), clock_epoch_id=CLOCK_EPOCH
        ))
        wrong = bytes.fromhex("11111111111111111111111111111111")
        inner = json.loads(bytes.fromhex(document["neurons"][0]["snapshot_hex"]))
        inner["clock_epoch_id"] = wrong.hex()
        document["neurons"][0]["snapshot_hex"] = json.dumps(
            inner, sort_keys=True, separators=(",", ":")
        ).encode().hex()
        with self.assertRaises(NetworkRuntimePersistenceError):
            decode_network_runtime_snapshot(json.dumps(document).encode())

    def test_rejects_duplicate_neuron_ids(self):
        document = json.loads(encode_network_runtime_snapshot(
            "duplicate", _network(), clock_epoch_id=CLOCK_EPOCH
        ))
        document["neurons"].append(dict(document["neurons"][0]))
        with self.assertRaises(NetworkRuntimePersistenceError):
            decode_network_runtime_snapshot(json.dumps(document).encode())

    def test_rejects_pending_event_for_unknown_neuron(self):
        document = json.loads(encode_network_runtime_snapshot(
            "unknown-event", _network(), clock_epoch_id=CLOCK_EPOCH
        ))
        document["pending_events"][0]["target_neuron_id"] = "missing"
        with self.assertRaises(NetworkRuntimePersistenceError):
            decode_network_runtime_snapshot(json.dumps(document).encode())

    def test_rejects_duplicate_json_keys(self):
        raw = (
            b'{"format":"JARVIS_NETWORK_RUNTIME_SNAPSHOT_V1",'
            b'"format":"JARVIS_NETWORK_RUNTIME_SNAPSHOT_V1"}'
        )
        with self.assertRaises(NetworkRuntimePersistenceError):
            decode_network_runtime_snapshot(raw)

    def test_rejects_unknown_snapshot_version(self):
        document = json.loads(encode_network_runtime_snapshot(
            "version", _network(), clock_epoch_id=CLOCK_EPOCH
        ))
        document["schema_version"] = NETWORK_RUNTIME_SCHEMA_VERSION + 1
        with self.assertRaises(NetworkRuntimePersistenceError):
            decode_network_runtime_snapshot(json.dumps(document).encode())

    def test_rejects_oversized_or_zero_clock_epoch(self):
        with self.assertRaises(NetworkRuntimePersistenceError):
            encode_network_runtime_snapshot("bad-epoch", _network(), clock_epoch_id=bytes(16))

    def test_rejects_duplicate_pending_event_ids(self):
        document = json.loads(encode_network_runtime_snapshot(
            "ordering", _network(), clock_epoch_id=CLOCK_EPOCH
        ))
        document["pending_events"].append(dict(document["pending_events"][0]))
        with self.assertRaises(NetworkRuntimePersistenceError):
            decode_network_runtime_snapshot(json.dumps(document).encode())


if __name__ == "__main__":
    unittest.main()
