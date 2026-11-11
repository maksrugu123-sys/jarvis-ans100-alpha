"""Tests for the JNVM single-neuron checkpoint to JPVR adapter."""
import json
import unittest
from dataclasses import replace

from tools.jpvr_reference.blob import decode_blob_pages
from tools.jpvr_reference.codec import Superblock
from tools.jpvr_reference.data import decode_data_page
from tools.jpvr_reference.index import IndexNode
from tools.jpvr_reference.manifest import INDEX_ROOT_NAMES
from tools.jpvr_reference.typed_transaction import (
    BlobRecordInput,
    DataRecordInput,
    IndexPageInput,
    TypedTransactionRequest,
    build_typed_transaction,
)
from tools.numerical_oracle.jarvis_oracle import (
    NeuronState,
    RewardParams,
    SynapseParams,
    SynapseState,
    starter_neuron_params,
    starter_reward_params,
    starter_synapse_params,
)

from .jpvr_adapter import (
    JNVM_RUNTIME_RECORD_TYPE,
    JNVM_RUNTIME_SCHEMA_ID,
    NeuronRuntimePersistenceError,
    append_neuron_runtime_checkpoint,
    decode_neuron_runtime_record,
    decode_neuron_runtime_snapshot,
    encode_neuron_runtime_snapshot,
    make_neuron_runtime_record_input,
    neuron_runtime_object_id,
)
from .runtime import NeuronRuntime


UUID = bytes.fromhex("00112233445566778899aabbccddeeff")
TX_ID = bytes.fromhex("11111111111111111111111111111111")
COMMIT_ID = bytes.fromhex("22222222222222222222222222222222")
OPERATION_ID = bytes.fromhex("33333333333333333333333333333333")
CLOCK_EPOCH = bytes.fromhex("abcdefabcdefabcdefabcdefabcdefab")


def _runtime(*, synapse_count=1, learning_rule="none", processed_batches=0):
    states = {
        f"synapse-{index:03d}": SynapseState(weight=0.5)
        for index in range(synapse_count)
    }
    return NeuronRuntime(
        states,
        learning_rule=learning_rule,
        processed_batches=processed_batches,
    )


def _genesis_request():
    roots = tuple(
        IndexPageInput(IndexNode(bytes((index + 1,)) * 16, 0), root_name=name)
        for index, name in enumerate(INDEX_ROOT_NAMES)
    )
    return TypedTransactionRequest(
        current_superblock=Superblock(UUID, 0, 0, 0, 1, bytes(16), 0),
        page_catalog={},
        transaction_id=TX_ID,
        commit_id=COMMIT_ID,
        operation_id=OPERATION_ID,
        index_pages=roots,
    )


class NeuronRuntimeJPVRAdapterTests(unittest.TestCase):
    def test_round_trip_preserves_all_neuron_and_synapse_state(self):
        neuron_params = replace(starter_neuron_params(), tau_m_ns=25_000_000)
        synapse_params = replace(starter_synapse_params(), eta_plus=0.007)
        reward_params = replace(starter_reward_params(), eta_r=0.012)
        neuron_state = NeuronState(
            u=0.25, a=0.1, last_update_model_time_ns=100,
            refractory_until_model_time_ns=95, spike_sequence=2,
            last_spike_model_time_ns=90,
        )
        synapse_state = SynapseState(
            weight=0.7, pre_trace=0.2, post_trace=0.3,
            trace_last_update_model_time_ns=90,
            eligibility_last_update_model_time_ns=90,
        )
        runtime = NeuronRuntime(
            {"input-A": synapse_state},
            neuron_state=neuron_state,
            neuron_params=neuron_params,
            synapse_params=synapse_params,
            reward_params=reward_params,
            learning_rule="stdp",
            processed_batches=17,
        )
        restored = decode_neuron_runtime_snapshot(
            encode_neuron_runtime_snapshot(
                "neuron-A", runtime, clock_epoch_id=CLOCK_EPOCH
            )
        )
        self.assertEqual(restored.neuron_id, "neuron-A")
        self.assertEqual(restored.clock_epoch_id, CLOCK_EPOCH)
        self.assertEqual(restored.runtime.neuron_params, neuron_params)
        self.assertEqual(restored.runtime.synapse_params, synapse_params)
        self.assertEqual(restored.runtime.reward_params, reward_params)
        self.assertEqual(restored.runtime.neuron_state, neuron_state)
        self.assertEqual(restored.runtime.synapses, {"input-A": synapse_state})
        self.assertEqual(restored.runtime.learning_rule, "stdp")
        self.assertEqual(restored.runtime.processed_batches, 17)

    def test_round_trip_preserves_active_rstdp_eligibility_context(self):
        state = SynapseState(
            weight=0.6, pre_trace=0.1, post_trace=0.2,
            trace_last_update_model_time_ns=60,
            eligibility_trace=0.35,
            eligibility_context_id="episode-7",
            eligibility_started_model_time_ns=50,
            eligibility_last_update_model_time_ns=60,
        )
        runtime = NeuronRuntime(
            {"feedback": state},
            neuron_state=NeuronState(last_update_model_time_ns=60),
            learning_rule="rstdp",
        )
        restored = decode_neuron_runtime_snapshot(
            encode_neuron_runtime_snapshot("n", runtime, clock_epoch_id=CLOCK_EPOCH)
        )
        self.assertEqual(restored.runtime.synapses["feedback"], state)
        self.assertEqual(restored.runtime.learning_rule, "rstdp")

    def test_small_snapshot_selects_data_record(self):
        runtime = _runtime()
        record = make_neuron_runtime_record_input("small", runtime, clock_epoch_id=CLOCK_EPOCH)
        self.assertIsInstance(record, DataRecordInput)
        decoded = decode_neuron_runtime_record(record)
        self.assertEqual(decoded.neuron_id, "small")
        self.assertEqual(decoded.runtime.synapses, runtime.synapses)

    def test_large_snapshot_selects_blob_record(self):
        synapses = {
            f"input-{index:03d}-" + ("x" * 100): SynapseState(weight=0.5)
            for index in range(48)
        }
        runtime = NeuronRuntime(synapses)
        record = make_neuron_runtime_record_input("large", runtime, clock_epoch_id=CLOCK_EPOCH)
        self.assertIsInstance(record, BlobRecordInput)
        self.assertEqual(decode_neuron_runtime_record(record).runtime.synapses, runtime.synapses)

    def test_encoding_is_deterministic_for_equal_state(self):
        self.assertEqual(
            encode_neuron_runtime_snapshot("n", _runtime(synapse_count=3), clock_epoch_id=CLOCK_EPOCH),
            encode_neuron_runtime_snapshot("n", _runtime(synapse_count=3), clock_epoch_id=CLOCK_EPOCH),
        )

    def test_rejects_empty_or_overlong_neuron_id(self):
        runtime = _runtime()
        with self.assertRaises(NeuronRuntimePersistenceError):
            encode_neuron_runtime_snapshot("", runtime, clock_epoch_id=CLOCK_EPOCH)
        with self.assertRaises(NeuronRuntimePersistenceError):
            encode_neuron_runtime_snapshot("x" * 513, runtime, clock_epoch_id=CLOCK_EPOCH)

    def test_rejects_zero_or_wrong_sized_clock_epoch(self):
        runtime = _runtime()
        for epoch in (bytes(16), b"short", b"x" * 17):
            with self.subTest(epoch_length=len(epoch)):
                with self.assertRaises(NeuronRuntimePersistenceError):
                    encode_neuron_runtime_snapshot("n", runtime, clock_epoch_id=epoch)

    def test_rejects_invalid_record_version(self):
        runtime = _runtime()
        for version in (0, -1, True, 1 << 64):
            with self.subTest(version=version):
                with self.assertRaises(NeuronRuntimePersistenceError):
                    make_neuron_runtime_record_input(
                        "n", runtime, clock_epoch_id=CLOCK_EPOCH,
                        record_version=version,
                    )

    def test_rejects_duplicate_json_keys(self):
        payload = (
            b'{"format":"JARVIS_NEURON_RUNTIME_SNAPSHOT_V1",'
            b'"format":"JARVIS_NEURON_RUNTIME_SNAPSHOT_V1"}'
        )
        with self.assertRaisesRegex(NeuronRuntimePersistenceError, "duplicate JSON key"):
            decode_neuron_runtime_snapshot(payload)

    def test_rejects_unknown_or_missing_snapshot_fields(self):
        payload = encode_neuron_runtime_snapshot("n", _runtime(), clock_epoch_id=CLOCK_EPOCH)
        document = json.loads(payload)
        document["unexpected"] = 1
        with self.assertRaises(NeuronRuntimePersistenceError):
            decode_neuron_runtime_snapshot(json.dumps(document).encode("utf-8"))
        document.pop("unexpected")
        document.pop("processed_batches")
        with self.assertRaises(NeuronRuntimePersistenceError):
            decode_neuron_runtime_snapshot(json.dumps(document).encode("utf-8"))

    def test_rejects_nonfinite_state_values(self):
        payload = encode_neuron_runtime_snapshot("n", _runtime(), clock_epoch_id=CLOCK_EPOCH)
        document = json.loads(payload)
        document["neuron_state"]["u"] = 1e999
        with self.assertRaises(NeuronRuntimePersistenceError):
            decode_neuron_runtime_snapshot(json.dumps(document).encode("utf-8"))

    def test_rejects_record_metadata_or_object_id_mismatch(self):
        record = make_neuron_runtime_record_input("n", _runtime(), clock_epoch_id=CLOCK_EPOCH)
        with self.assertRaises(NeuronRuntimePersistenceError):
            decode_neuron_runtime_record(
                replace(record, object_id=bytes.fromhex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            )
        with self.assertRaises(NeuronRuntimePersistenceError):
            decode_neuron_runtime_record(
                replace(record, schema_id=JNVM_RUNTIME_SCHEMA_ID + 1)
            )
        self.assertEqual(record.record_type, JNVM_RUNTIME_RECORD_TYPE)

    def test_attaches_small_snapshot_to_typed_data_and_decodes_transaction_page(self):
        original = _genesis_request()
        updated = append_neuron_runtime_checkpoint(
            original, "small", _runtime(), clock_epoch_id=CLOCK_EPOCH
        )
        self.assertEqual(original.data_records, ())
        self.assertEqual(original.blob_records, ())
        self.assertEqual(len(updated.data_records), 1)
        plan = build_typed_transaction(updated)
        catalog = dict(zip(plan.assembly.ordered_page_numbers, plan.assembly.ordered_page_bytes))
        matches = []
        for page_no in plan.data_page_numbers:
            page = decode_data_page(catalog[page_no], expected_page_no=page_no)
            matches.extend(item for item in page.records if item.object_id == neuron_runtime_object_id("small"))
        self.assertEqual(len(matches), 1)
        restored = decode_neuron_runtime_record(matches[0])
        self.assertEqual(restored.neuron_id, "small")
        self.assertEqual(restored.runtime.synapses, _runtime().synapses)

    def test_attaches_large_snapshot_to_typed_blob_and_decodes_transaction_pages(self):
        original = _genesis_request()
        runtime = NeuronRuntime({
            f"input-{index:03d}-" + ("x" * 100): SynapseState(weight=0.5)
            for index in range(48)
        })
        updated = append_neuron_runtime_checkpoint(
            original, "large", runtime, clock_epoch_id=CLOCK_EPOCH
        )
        self.assertEqual(updated.data_records, ())
        self.assertEqual(len(updated.blob_records), 1)
        plan = build_typed_transaction(updated)
        catalog = dict(zip(plan.assembly.ordered_page_numbers, plan.assembly.ordered_page_bytes))
        blob = decode_blob_pages(
            tuple(catalog[page_no] for page_no in plan.blob_page_numbers),
            expected_first_page_no=plan.blob_page_numbers[0],
        )
        restored = decode_neuron_runtime_record(blob)
        self.assertEqual(restored.runtime.synapses, runtime.synapses)

    def test_rejects_duplicate_neuron_checkpoint_in_one_transaction(self):
        request = append_neuron_runtime_checkpoint(
            _genesis_request(), "n", _runtime(), clock_epoch_id=CLOCK_EPOCH
        )
        with self.assertRaises(NeuronRuntimePersistenceError):
            append_neuron_runtime_checkpoint(
                request, "n", _runtime(), clock_epoch_id=CLOCK_EPOCH, record_version=2
            )

    def test_processed_batch_counter_is_unsigned_64_bit_and_restorable(self):
        with self.assertRaises(ValueError):
            NeuronRuntime({}, processed_batches=1 << 64)
        restored = decode_neuron_runtime_snapshot(
            encode_neuron_runtime_snapshot(
                "n", _runtime(processed_batches=(1 << 64) - 1), clock_epoch_id=CLOCK_EPOCH
            )
        )
        self.assertEqual(restored.runtime.processed_batches, (1 << 64) - 1)


if __name__ == "__main__":
    unittest.main()
