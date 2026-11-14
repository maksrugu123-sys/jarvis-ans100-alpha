"""Tests for LRU residency, durable write-back and recovery boundaries."""
from __future__ import annotations
from copy import deepcopy
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from tools.jpvr_reference.storage import JPVRCommitUncertainError
from .jpvr_store import JPVRNeuronCheckpointNotFound
from .runtime import NeuronRuntime,SynapticInput
from .working_set import NeuronWorkingSet,NeuronWorkingSetCommitUncertain,NeuronWorkingSetPersistenceError,ShardedJPVRNeuronStore

EPOCH=bytes.fromhex("00112233445566778899aabbccddeeff")

class MemoryCheckpointBackend:
    def __init__(self):self.rows={};self.fail_next_save=False;self.uncertain_after_commit=False
    def load(self,neuron_id,*,max_pages=4096):
        row=self.rows.get(neuron_id)
        if row is None:raise JPVRNeuronCheckpointNotFound(neuron_id)
        runtime,epoch,version=row
        return SimpleNamespace(snapshot=SimpleNamespace(neuron_id=neuron_id,clock_epoch_id=epoch,runtime=deepcopy(runtime)),
            locator=SimpleNamespace(record_version=version))
    def save(self,neuron_id,runtime,*,clock_epoch_id,expected_record_version=None,max_pages=4096):
        current=self.rows.get(neuron_id);version=0 if current is None else current[2]
        if expected_record_version is not None and expected_record_version!=version:
            from .jpvr_store import JPVRNeuronCheckpointConflict
            raise JPVRNeuronCheckpointConflict("stale record")
        if self.fail_next_save:self.fail_next_save=False;raise OSError("injected persistence failure")
        next_version=version+1;self.rows[neuron_id]=(deepcopy(runtime),bytes(clock_epoch_id),next_version)
        if self.uncertain_after_commit:
            self.uncertain_after_commit=False
            raise JPVRCommitUncertainError(bytes.fromhex("11111111111111111111111111111111"),next_version,"injected uncertain outcome")
        return SimpleNamespace(record_version=next_version)

def make_runtime():return NeuronRuntime({"external":1.0})

class NeuronWorkingSetTests(unittest.TestCase):
    def test_register_get_and_shared_parameter_interning(self):
        backend=MemoryCheckpointBackend();working=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=2)
        working.register("a",make_runtime());working.register("b",make_runtime())
        self.assertEqual(working.get("a").synapse_count,1)
        self.assertEqual(working.statistics["shared_parameter_sets"],1)
        self.assertEqual(working.statistics["resident_neurons"],2)
    def test_lru_evicts_and_reloads_cold_neuron(self):
        backend=MemoryCheckpointBackend();working=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=1)
        working.register("a",make_runtime());working.register("b",make_runtime())
        self.assertEqual(working.resident_neuron_ids,("b",))
        self.assertEqual(working.get("a").synapse_count,1)
        self.assertEqual(working.resident_neuron_ids,("a",))
    def test_dirty_state_commits_before_eviction(self):
        backend=MemoryCheckpointBackend();working=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=1)
        working.register("a",make_runtime())
        self.assertTrue(working.process_batch("a",0,[SynapticInput("pulse","external",1.0)]).spike_emitted)
        self.assertEqual(working.statistics["dirty_neurons"],1)
        working.register("b",make_runtime())
        self.assertEqual(working.statistics["dirty_neurons"],0)
        self.assertEqual(backend.rows["a"][0].neuron_state.spike_sequence,1)
        self.assertEqual(working.get("a").neuron_state.spike_sequence,1)
    def test_failed_flush_keeps_dirty_entry_and_blocks_admission(self):
        backend=MemoryCheckpointBackend();working=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=1)
        working.register("a",make_runtime());working.process_batch("a",0,[SynapticInput("pulse","external",1.0)])
        backend.fail_next_save=True
        with self.assertRaises(NeuronWorkingSetPersistenceError):working.register("b",make_runtime())
        self.assertEqual(working.resident_neuron_ids,("a",));self.assertEqual(working.statistics["dirty_neurons"],1)
        self.assertNotIn("b",backend.rows)
    def test_candidate_failure_does_not_mutate_resident_state(self):
        backend=MemoryCheckpointBackend();working=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=1)
        working.register("a",make_runtime());before=working.get("a").neuron_state
        with self.assertRaises(ValueError):working.process_batch("a",0,[SynapticInput("bad","missing",1.0)])
        self.assertEqual(working.get("a").neuron_state,before);self.assertEqual(working.statistics["dirty_neurons"],0)
    def test_uncertain_commit_requires_reconciliation(self):
        backend=MemoryCheckpointBackend();working=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=1)
        working.register("a",make_runtime());working.process_batch("a",0,[SynapticInput("pulse","external",1.0)])
        backend.uncertain_after_commit=True
        with self.assertRaises(NeuronWorkingSetCommitUncertain):working.flush_neuron("a")
        with self.assertRaises(NeuronWorkingSetCommitUncertain):working.flush_neuron("a")
        before=working.get("a").neuron_state
        with self.assertRaises(NeuronWorkingSetCommitUncertain):
            working.process_batch("a",1,[SynapticInput("pulse-2","external",1.0)])
        self.assertEqual(working.get("a").neuron_state,before)
        self.assertEqual(working.reconcile_neuron("a"),"COMMITTED")
        self.assertEqual(working.statistics["dirty_neurons"],0);self.assertEqual(working.flush_neuron("a"),2)
    def test_clock_epoch_mismatch_is_rejected(self):
        backend=MemoryCheckpointBackend();working=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=1)
        working.register("a",make_runtime());working.evict("a")
        backend.rows["a"]=(backend.rows["a"][0],bytes.fromhex("ffeeddccbbaa99887766554433221100"),1)
        with self.assertRaises(RuntimeError):working.get("a")
    def test_real_sharded_jpvr_round_trip(self):
        with TemporaryDirectory() as directory:
            backend=ShardedJPVRNeuronStore(directory)
            working=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=1)
            working.register("node-01",make_runtime())
            working.process_batch("node-01",0,[SynapticInput("pulse","external",1.0)])
            working.flush_neuron("node-01");working.evict("node-01")
            reopened=NeuronWorkingSet(backend,clock_epoch_id=EPOCH,max_resident_neurons=1)
            self.assertEqual(reopened.get("node-01").neuron_state.spike_sequence,1)

if __name__=="__main__":unittest.main(verbosity=2)
