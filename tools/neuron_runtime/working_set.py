"""Bounded LRU JNVM resident cache over versioned JPVR neuron checkpoints."""
from __future__ import annotations
from collections import OrderedDict
from collections.abc import Sequence
from dataclasses import dataclass
import hashlib, threading
from pathlib import Path
from typing import Protocol
from tools.jpvr_reference.storage import JPVRCommitUncertainError, JPVRStorage
from .compact_state import PackedNeuronState,ParameterBundle,pack_neuron_runtime,parameter_bundle,parameter_set_id,unpack_neuron_runtime
from .jpvr_store import JPVRNeuronCheckpointConflict,JPVRNeuronCheckpointNotFound,JPVRNeuronCheckpointStore
from .runtime import NeuronRuntime,RewardUpdate,RuntimeStep,SynapticInput

_SHARDS=128; _SHARD_DOMAIN=b"JARVIS:JNVM:SHARD:V1\x00"; _MAX_PAGES=4096; _MAX_RESIDENT=10_000

class NeuronWorkingSetError(RuntimeError): """Invalid working-set operation."""
class NeuronWorkingSetPersistenceError(NeuronWorkingSetError): """JPVR operation failed; dirty state is retained."""
class NeuronWorkingSetConflict(NeuronWorkingSetError): """Persisted neuron version conflicts with resident state."""
class NeuronWorkingSetCommitUncertain(NeuronWorkingSetError): """Commit outcome needs reconciliation."""

class NeuronCheckpointBackend(Protocol):
    def load(self,neuron_id:str,*,max_pages:int=_MAX_PAGES): ...
    def save(self,neuron_id:str,runtime:NeuronRuntime,*,clock_epoch_id:bytes,
             expected_record_version:int|None=None,max_pages:int=_MAX_PAGES): ...

class ShardedJPVRNeuronStore:
    """Stable 128-way shard mapping over the existing JPVR per-neuron COW index."""
    def __init__(self,directory:str|Path,*,max_pages:int=_MAX_PAGES):
        if isinstance(max_pages,bool) or not isinstance(max_pages,int) or max_pages<9:
            raise NeuronWorkingSetError("max_pages must be an integer of at least 9")
        self.directory=Path(directory).expanduser(); self.max_pages=max_pages
        self.directory.mkdir(parents=True,exist_ok=True)
    def shard_index(self,neuron_id:str)->int:
        from .jpvr_adapter import neuron_runtime_object_id
        try: identity=neuron_runtime_object_id(neuron_id)
        except Exception as exc: raise NeuronWorkingSetError(f"invalid neuron ID: {exc}") from exc
        return int.from_bytes(hashlib.sha256(_SHARD_DOMAIN+identity).digest()[:4],"big")%_SHARDS
    def shard_path(self,neuron_id:str)->Path:
        return self.directory/f"neurons-v1-{self.shard_index(neuron_id):03d}.jpvr"
    @staticmethod
    def _open(path:Path,create:bool)->JPVRStorage:
        if path.exists(): return JPVRStorage.open(path,recover_tail=True)
        if not create: raise JPVRNeuronCheckpointNotFound("neuron shard does not exist")
        try: return JPVRStorage.create(path)
        except FileExistsError: return JPVRStorage.open(path,recover_tail=True)
    def load(self,neuron_id:str,*,max_pages:int=_MAX_PAGES):
        path=self.shard_path(neuron_id)
        if not path.exists(): raise JPVRNeuronCheckpointNotFound(f"no shard for neuron {neuron_id!r}")
        store=self._open(path,False)
        try: return JPVRNeuronCheckpointStore(store).load(neuron_id,max_pages=max_pages)
        finally: store.close()
    def save(self,neuron_id:str,runtime:NeuronRuntime,*,clock_epoch_id:bytes,
             expected_record_version:int|None=None,max_pages:int=_MAX_PAGES):
        path=self.shard_path(neuron_id)
        if not path.exists() and expected_record_version not in (None,0):
            raise JPVRNeuronCheckpointConflict("cannot update a neuron whose shard does not exist")
        store=self._open(path,True)
        try:
            return JPVRNeuronCheckpointStore(store).save(neuron_id,runtime,
                clock_epoch_id=clock_epoch_id,expected_record_version=expected_record_version,
                max_pages=max_pages)
        finally: store.close()

@dataclass
class _Resident:
    packed:PackedNeuronState
    record_version:int
    dirty:bool=False
    uncertain:bool=False

class NeuronWorkingSet:
    """Single-writer per-neuron write-back LRU; not a network timestamp transaction."""
    def __init__(self,backend:NeuronCheckpointBackend,*,clock_epoch_id:bytes,
                 max_resident_neurons:int=64,max_pages:int=_MAX_PAGES):
        if not isinstance(clock_epoch_id,(bytes,bytearray,memoryview)):
            raise NeuronWorkingSetError("clock_epoch_id must be 16 bytes")
        epoch=bytes(clock_epoch_id)
        if len(epoch)!=16 or epoch==bytes(16): raise NeuronWorkingSetError("clock epoch must be non-zero 16 bytes")
        if isinstance(max_resident_neurons,bool) or not isinstance(max_resident_neurons,int) or not 1<=max_resident_neurons<=_MAX_RESIDENT:
            raise NeuronWorkingSetError(f"resident capacity must be in [1,{_MAX_RESIDENT}]")
        if isinstance(max_pages,bool) or not isinstance(max_pages,int) or max_pages<9:
            raise NeuronWorkingSetError("max_pages must be at least 9")
        if not hasattr(backend,"load") or not hasattr(backend,"save"): raise NeuronWorkingSetError("backend must implement load/save")
        self._backend=backend; self._epoch=epoch; self._capacity=max_resident_neurons; self._max_pages=max_pages
        self._resident:OrderedDict[str,_Resident]=OrderedDict()
        self._params:dict[bytes,ParameterBundle]={}; self._refs:dict[bytes,int]={}
        self._lock=threading.RLock(); self._closed=False
    def _ensure_open(self):
        if self._closed: raise NeuronWorkingSetError("working set is closed")
    def _intern(self,runtime:NeuronRuntime):
        key=parameter_set_id(runtime); bundle=parameter_bundle(runtime); old=self._params.get(key)
        if old is not None and old!=bundle: raise NeuronWorkingSetError("parameter digest collision")
        if old is None: self._params[key]=bundle; self._refs[key]=0
        self._refs[key]+=1
    def _release(self,key:bytes):
        count=self._refs.get(key,0)
        if count<=0: raise NeuronWorkingSetError("parameter reference count is inconsistent")
        if count==1: self._refs.pop(key); self._params.pop(key)
        else: self._refs[key]=count-1
    def _decode(self,entry:_Resident)->NeuronRuntime:
        return unpack_neuron_runtime(entry.packed,self._params)
    def _flush(self,neuron_id:str,entry:_Resident)->int:
        if not entry.dirty:return entry.record_version
        if entry.uncertain:raise NeuronWorkingSetCommitUncertain(f"neuron {neuron_id!r} needs reconciliation")
        try:
            result=self._backend.save(neuron_id,self._decode(entry),clock_epoch_id=self._epoch,
                expected_record_version=entry.record_version,max_pages=self._max_pages)
        except JPVRCommitUncertainError as exc:
            entry.uncertain=True
            raise NeuronWorkingSetCommitUncertain(f"commit for neuron {neuron_id!r} is uncertain") from exc
        except JPVRNeuronCheckpointConflict as exc:
            raise NeuronWorkingSetConflict(f"JPVR version conflict for neuron {neuron_id!r}") from exc
        except Exception as exc:
            raise NeuronWorkingSetPersistenceError(f"could not persist neuron {neuron_id!r}; dirty state remains resident") from exc
        entry.record_version=result.record_version; entry.dirty=False
        return entry.record_version
    def _evict_lru(self):
        neuron_id,entry=next(iter(self._resident.items()))
        self._flush(neuron_id,entry)
        self._resident.pop(neuron_id); self._release(entry.packed.parameter_set_id)
    def _capacity_check(self):
        while len(self._resident)>=self._capacity:self._evict_lru()
    def _load_entry(self,neuron_id:str)->_Resident:
        self._ensure_open()
        if not isinstance(neuron_id,str) or not neuron_id.strip():raise NeuronWorkingSetError("neuron_id must be non-empty")
        if neuron_id in self._resident:self._resident.move_to_end(neuron_id);return self._resident[neuron_id]
        try: loaded=self._backend.load(neuron_id,max_pages=self._max_pages)
        except JPVRNeuronCheckpointNotFound:raise
        except Exception as exc:raise NeuronWorkingSetPersistenceError(f"could not load neuron {neuron_id!r}") from exc
        if loaded.snapshot.clock_epoch_id!=self._epoch:raise NeuronWorkingSetConflict("clock epoch mismatch")
        packed=pack_neuron_runtime(loaded.snapshot.runtime)
        self._capacity_check();self._intern(loaded.snapshot.runtime)
        entry=_Resident(packed,loaded.locator.record_version);self._resident[neuron_id]=entry
        return entry
    def register(self,neuron_id:str,runtime:NeuronRuntime)->int:
        with self._lock:
            self._ensure_open()
            if not isinstance(neuron_id,str) or not neuron_id.strip():raise NeuronWorkingSetError("neuron_id must be non-empty")
            if neuron_id in self._resident:raise NeuronWorkingSetConflict("neuron is already resident")
            try:self._backend.load(neuron_id,max_pages=self._max_pages)
            except JPVRNeuronCheckpointNotFound:pass
            except Exception as exc:raise NeuronWorkingSetPersistenceError("could not check existing neuron") from exc
            else:raise NeuronWorkingSetConflict("neuron already exists in JPVR")
            packed=pack_neuron_runtime(runtime);self._capacity_check()
            try:
                saved=self._backend.save(neuron_id,runtime,clock_epoch_id=self._epoch,
                    expected_record_version=0,max_pages=self._max_pages)
            except JPVRCommitUncertainError as exc:
                self._intern(runtime);self._resident[neuron_id]=_Resident(packed,0,True,True)
                raise NeuronWorkingSetCommitUncertain("initial neuron checkpoint commit is uncertain") from exc
            except JPVRNeuronCheckpointConflict as exc:raise NeuronWorkingSetConflict("neuron was concurrently created") from exc
            except Exception as exc:raise NeuronWorkingSetPersistenceError("could not register neuron") from exc
            self._intern(runtime);self._resident[neuron_id]=_Resident(packed,saved.record_version)
            return saved.record_version
    def get(self,neuron_id:str)->NeuronRuntime:
        with self._lock:return self._decode(self._load_entry(neuron_id))
    def _require_reconciled(self,neuron_id:str,entry:_Resident)->None:
        if entry.uncertain:
            raise NeuronWorkingSetCommitUncertain(
                f"neuron {neuron_id!r} must be reconciled before further mutation"
            )
    def process_batch(self,neuron_id:str,time_ns:int,inputs:Sequence[SynapticInput],*,learning_context_id:str|None=None)->RuntimeStep:
        with self._lock:
            entry=self._load_entry(neuron_id);self._require_reconciled(neuron_id,entry);candidate=self._decode(entry)
            result=candidate.process_batch(time_ns,inputs,learning_context_id=learning_context_id)
            packed=pack_neuron_runtime(candidate)
            if packed.parameter_set_id!=entry.packed.parameter_set_id:raise NeuronWorkingSetError("parameter changes require migration")
            entry.packed=packed;entry.dirty=True;self._resident.move_to_end(neuron_id)
            return result
    def apply_reward(self,neuron_id:str,synapse_id:str,time_ns:int,reward_value:float,
                     baseline_before:float,learning_context_id:str|None)->RewardUpdate:
        with self._lock:
            entry=self._load_entry(neuron_id);self._require_reconciled(neuron_id,entry);candidate=self._decode(entry)
            result=candidate.apply_reward(synapse_id,time_ns,reward_value,baseline_before,learning_context_id)
            packed=pack_neuron_runtime(candidate)
            if packed.parameter_set_id!=entry.packed.parameter_set_id:raise NeuronWorkingSetError("parameter changes require migration")
            entry.packed=packed;entry.dirty=True;self._resident.move_to_end(neuron_id)
            return result
    def flush_neuron(self,neuron_id:str)->int:
        with self._lock:
            self._ensure_open();entry=self._resident.get(neuron_id)
            if entry is None:return self._load_entry(neuron_id).record_version
            return self._flush(neuron_id,entry)
    def flush_all(self)->dict[str,int]:
        with self._lock:
            self._ensure_open()
            return {key:self._flush(key,value) for key,value in tuple(self._resident.items())}
    def evict(self,neuron_id:str)->bool:
        with self._lock:
            self._ensure_open();entry=self._resident.get(neuron_id)
            if entry is None:return False
            self._flush(neuron_id,entry);self._resident.pop(neuron_id);self._release(entry.packed.parameter_set_id)
            return True
    def reconcile_neuron(self,neuron_id:str)->str:
        with self._lock:
            self._ensure_open();entry=self._resident.get(neuron_id)
            if entry is None or not entry.uncertain:raise NeuronWorkingSetError("neuron has no uncertain commit")
            try:loaded=self._backend.load(neuron_id,max_pages=self._max_pages)
            except JPVRNeuronCheckpointNotFound:
                entry.uncertain=False;entry.dirty=True;return "NOT_COMMITTED"
            except Exception as exc:raise NeuronWorkingSetPersistenceError("could not reconcile neuron") from exc
            if loaded.snapshot.clock_epoch_id!=self._epoch:raise NeuronWorkingSetConflict("reconciled clock epoch mismatch")
            persisted=pack_neuron_runtime(loaded.snapshot.runtime)
            if persisted.data==entry.packed.data:
                entry.record_version=loaded.locator.record_version;entry.dirty=False;entry.uncertain=False;return "COMMITTED"
            if loaded.locator.record_version==entry.record_version:
                entry.uncertain=False;entry.dirty=True;return "NOT_COMMITTED"
            raise NeuronWorkingSetConflict("different newer JPVR version; automatic overwrite is forbidden")
    @property
    def resident_neuron_ids(self)->tuple[str,...]:
        with self._lock:return tuple(self._resident)
    @property
    def statistics(self)->dict[str,int]:
        with self._lock:
            return {"resident_neurons":len(self._resident),"resident_capacity":self._capacity,
                "dirty_neurons":sum(x.dirty for x in self._resident.values()),
                "uncertain_commits":sum(x.uncertain for x in self._resident.values()),
                "packed_payload_bytes":sum(len(x.packed) for x in self._resident.values()),
                "shared_parameter_sets":len(self._params)}
    def close(self):
        with self._lock:
            if self._closed:return
            self.flush_all()
            for entry in self._resident.values():self._release(entry.packed.parameter_set_id)
            self._resident.clear();self._closed=True
