"""Versioned lossless F64 packed state for a bounded JNVM resident cache."""
from __future__ import annotations
from dataclasses import asdict, dataclass
import hashlib, json, struct
from collections.abc import Mapping
from typing import TypeAlias
from tools.numerical_oracle.jarvis_oracle import NeuronParams, NeuronState, OracleInputError, RewardParams, SynapseParams, SynapseState
from .runtime import NeuronRuntime, NeuronRuntimeError

_MAGIC=b"JPCS"; _VERSION=1
_RULE_TO_CODE={"none":0,"stdp":1,"rstdp":2}; _CODE_TO_RULE={v:k for k,v in _RULE_TO_CODE.items()}
_HEADER=struct.Struct("<4sHBBI16sQ")
_NEURON=struct.Struct("<ddQQQQ")
_SYN_HEADER=struct.Struct("<HHB")
_SYN_STATE=struct.Struct("<ddddQQQ")
_MAX_SYNAPSES=100_000; _MAX_TEXT=512
_DOMAIN=b"JARVIS:JNVM:PARAMETER-SET:V1\x00"
ParameterBundle: TypeAlias=tuple[NeuronParams,SynapseParams,RewardParams]

class CompactNeuronStateError(ValueError):
    """Invalid or incompatible packed state."""

@dataclass(frozen=True, slots=True)
class PackedNeuronState:
    data: bytes
    parameter_set_id: bytes
    def __post_init__(self):
        if not isinstance(self.data,bytes):
            raise CompactNeuronStateError("packed data must be bytes")
        if not isinstance(self.parameter_set_id,bytes) or len(self.parameter_set_id)!=16:
            raise CompactNeuronStateError("parameter_set_id must contain 16 bytes")
    def __len__(self): return len(self.data)

def parameter_bundle(runtime:NeuronRuntime)->ParameterBundle:
    if not isinstance(runtime,NeuronRuntime): raise CompactNeuronStateError("runtime must be a NeuronRuntime")
    return runtime.neuron_params,runtime.synapse_params,runtime.reward_params

def parameter_set_id_from_bundle(bundle:ParameterBundle)->bytes:
    if (not isinstance(bundle,tuple) or len(bundle)!=3 or
        not isinstance(bundle[0],NeuronParams) or not isinstance(bundle[1],SynapseParams) or
        not isinstance(bundle[2],RewardParams)):
        raise CompactNeuronStateError("parameter bundle has an invalid shape")
    document={"neuron":asdict(bundle[0]),"synapse":asdict(bundle[1]),"reward":asdict(bundle[2])}
    try:
        raw=json.dumps(document,sort_keys=True,separators=(",",":"),ensure_ascii=True,allow_nan=False).encode("ascii")
    except (TypeError,ValueError,UnicodeEncodeError) as exc:
        raise CompactNeuronStateError(f"cannot fingerprint parameter bundle: {exc}") from exc
    return hashlib.sha256(_DOMAIN+raw).digest()[:16]

def parameter_set_id(runtime:NeuronRuntime)->bytes:
    return parameter_set_id_from_bundle(parameter_bundle(runtime))

def _text(name:str,value:str)->bytes:
    if not isinstance(value,str) or not value or "\x00" in value:
        raise CompactNeuronStateError(f"{name} must be a valid non-empty string")
    try: raw=value.encode("utf-8","strict")
    except UnicodeEncodeError as exc: raise CompactNeuronStateError(f"{name} must be valid UTF-8") from exc
    if len(raw)>_MAX_TEXT: raise CompactNeuronStateError(f"{name} exceeds {_MAX_TEXT} UTF-8 bytes")
    return raw

def pack_neuron_runtime(runtime:NeuronRuntime)->PackedNeuronState:
    """Pack every mutable state field with exact IEEE-754 binary64 values."""
    if not isinstance(runtime,NeuronRuntime): raise CompactNeuronStateError("runtime must be a NeuronRuntime")
    try:
        runtime.neuron_params.validate(); runtime.synapse_params.validate(); runtime.reward_params.validate()
        runtime.neuron_state.validate(runtime.neuron_params)
        if runtime.learning_rule not in _RULE_TO_CODE: raise CompactNeuronStateError("unsupported learning rule")
        synapses=sorted(runtime.synapses.items(),key=lambda item:_text("synapse_id",item[0]))
        if len(synapses)>_MAX_SYNAPSES: raise CompactNeuronStateError("synapse count exceeds compact-state limit")
        state=runtime.neuron_state; parameter_id=parameter_set_id(runtime)
        chunks=[_HEADER.pack(_MAGIC,_VERSION,_RULE_TO_CODE[runtime.learning_rule],
            1 if state.last_spike_model_time_ns is not None else 0,len(synapses),parameter_id,runtime.processed_batches),
            _NEURON.pack(state.u,state.a,state.last_update_model_time_ns,state.refractory_until_model_time_ns,
                state.spike_sequence,state.last_spike_model_time_ns or 0)]
        for synapse_id,synapse in synapses:
            id_bytes=_text("synapse_id",synapse_id)
            synapse.validate(runtime.synapse_params,runtime.reward_params)
            context=b"" if synapse.eligibility_context_id is None else _text("eligibility_context_id",synapse.eligibility_context_id)
            flags=(1 if synapse.eligibility_started_model_time_ns is not None else 0)|(2 if synapse.eligibility_context_id is not None else 0)
            chunks.append(_SYN_HEADER.pack(len(id_bytes),len(context),flags))
            chunks.append(_SYN_STATE.pack(synapse.weight,synapse.pre_trace,synapse.post_trace,
                synapse.eligibility_trace,synapse.trace_last_update_model_time_ns,
                synapse.eligibility_started_model_time_ns or 0,synapse.eligibility_last_update_model_time_ns))
            chunks.extend((id_bytes,context))
        return PackedNeuronState(b"".join(chunks),parameter_id)
    except CompactNeuronStateError: raise
    except (OracleInputError,NeuronRuntimeError,OverflowError,struct.error,TypeError,ValueError) as exc:
        raise CompactNeuronStateError(f"cannot pack neuron runtime: {exc}") from exc

def _take(raw:memoryview,offset:int,size:int,name:str):
    end=offset+size
    if size<0 or end>len(raw): raise CompactNeuronStateError(f"packed state truncated at {name}")
    return raw[offset:end],end

def _decode_text(raw:memoryview,name:str)->str:
    if not raw or len(raw)>_MAX_TEXT: raise CompactNeuronStateError(f"{name} has invalid encoded length")
    try: value=bytes(raw).decode("utf-8","strict")
    except UnicodeDecodeError as exc: raise CompactNeuronStateError(f"{name} is not valid UTF-8") from exc
    if "\x00" in value: raise CompactNeuronStateError(f"{name} contains NUL")
    return value

def unpack_neuron_runtime(packed:PackedNeuronState|bytes|bytearray|memoryview,
                          parameter_bank:Mapping[bytes,ParameterBundle])->NeuronRuntime:
    """Restore a complete runtime, rejecting missing parameters and malformed bytes."""
    if not isinstance(packed,PackedNeuronState):
        if not isinstance(packed,(bytes,bytearray,memoryview)): raise CompactNeuronStateError("packed state must be bytes-like")
        raw_bytes=bytes(packed)
        if len(raw_bytes)<_HEADER.size: raise CompactNeuronStateError("packed state is shorter than its header")
        try: key=_HEADER.unpack_from(raw_bytes)[5]
        except struct.error as exc: raise CompactNeuronStateError("packed header is truncated") from exc
        packed=PackedNeuronState(raw_bytes,key)
    raw=memoryview(packed.data)
    if len(raw)<_HEADER.size+_NEURON.size: raise CompactNeuronStateError("packed state is truncated")
    try:
        magic,version,rule,flags,count,key,processed=_HEADER.unpack_from(raw,0)
        if magic!=_MAGIC or version!=_VERSION: raise CompactNeuronStateError("unsupported packed magic/version")
        if rule not in _CODE_TO_RULE or flags&~1: raise CompactNeuronStateError("unknown packed rule or flags")
        if count>_MAX_SYNAPSES: raise CompactNeuronStateError("packed synapse count exceeds limit")
        key=bytes(key)
        if key!=packed.parameter_set_id: raise CompactNeuronStateError("packed parameter key is inconsistent")
        try: params=parameter_bank[key]
        except (KeyError,TypeError) as exc: raise CompactNeuronStateError("packed state references a missing parameter set") from exc
        if parameter_set_id_from_bundle(params)!=key: raise CompactNeuronStateError("parameter bank content does not match its key")
        offset=_HEADER.size
        state_raw,offset=_take(raw,offset,_NEURON.size,"neuron state")
        u,a,last,refractory,spikes,last_spike=_NEURON.unpack(state_raw)
        neuron_state=NeuronState(u=u,a=a,last_update_model_time_ns=last,
            refractory_until_model_time_ns=refractory,spike_sequence=spikes,
            last_spike_model_time_ns=last_spike if flags&1 else None)
        synapses={}; previous_id=None
        for index in range(count):
            head,offset=_take(raw,offset,_SYN_HEADER.size,f"synapse[{index}] header")
            id_len,ctx_len,sflags=_SYN_HEADER.unpack(head)
            if id_len==0 or id_len>_MAX_TEXT or ctx_len>_MAX_TEXT or sflags&~3:
                raise CompactNeuronStateError(f"synapse[{index}] has invalid lengths or flags")
            numbers,offset=_take(raw,offset,_SYN_STATE.size,f"synapse[{index}] numeric state")
            weight,pre,post,eligibility,trace_time,start_time,eligibility_time=_SYN_STATE.unpack(numbers)
            id_raw,offset=_take(raw,offset,id_len,f"synapse[{index}] ID")
            ctx_raw,offset=_take(raw,offset,ctx_len,f"synapse[{index}] context")
            synapse_id=_decode_text(id_raw,f"synapse[{index}] ID")
            if previous_id is not None and bytes(id_raw)<=previous_id:
                raise CompactNeuronStateError("synapse IDs are not uniquely sorted")
            previous_id=bytes(id_raw)
            has_context=bool(sflags&2); has_start=bool(sflags&1)
            if has_context!=bool(ctx_len) or has_context!=has_start:
                raise CompactNeuronStateError("synapse context length/flags mismatch")
            context=_decode_text(ctx_raw,f"synapse[{index}] context") if has_context else None
            synapses[synapse_id]=SynapseState(weight=weight,pre_trace=pre,post_trace=post,
                trace_last_update_model_time_ns=trace_time,eligibility_trace=eligibility,
                eligibility_context_id=context,
                eligibility_started_model_time_ns=start_time if has_start else None,
                eligibility_last_update_model_time_ns=eligibility_time)
        if offset!=len(raw): raise CompactNeuronStateError("packed state has trailing bytes")
        runtime=NeuronRuntime(synapses,neuron_state=neuron_state,neuron_params=params[0],
            synapse_params=params[1],reward_params=params[2],learning_rule=_CODE_TO_RULE[rule],
            processed_batches=processed)
        runtime.neuron_state.validate(runtime.neuron_params)
        for item in runtime.synapses.values(): item.validate(runtime.synapse_params,runtime.reward_params)
        return runtime
    except CompactNeuronStateError: raise
    except (OracleInputError,NeuronRuntimeError,OverflowError,struct.error,TypeError,ValueError) as exc:
        raise CompactNeuronStateError(f"cannot unpack neuron runtime: {exc}") from exc
