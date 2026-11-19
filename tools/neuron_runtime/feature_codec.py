"""Versioned, strict codec for sparse cognitive neuron sidecars.

This codec is deliberately separate from the 40-byte ALIF hot state. It is
embedded in the optional feature field of a versioned JNVM neuron checkpoint;
it does not claim that
the current network scheduler persists or applies these features.
"""
from __future__ import annotations

import json
from dataclasses import asdict
from typing import Any

from .neuron_features import (
    NeuronFeatureError, NeuronFeatureState, SynapticMemoryState,
)

_FORMAT = "JARVIS_NEURON_FEATURE_SIDECAR"
_VERSION = 2


class FeatureCodecError(ValueError):
    """Malformed, unsupported, or inconsistent feature sidecar."""


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise FeatureCodecError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise FeatureCodecError(f"non-finite JSON constant is forbidden: {value}")


def encode_feature_sidecar(
    neuron_id: str,
    state: NeuronFeatureState,
    synaptic_memory: dict[str, SynapticMemoryState] | None = None,
) -> bytes:
    """Encode one neuron's optional cognitive state as canonical UTF-8 JSON."""
    if not isinstance(neuron_id, str) or not neuron_id.strip() or "\x00" in neuron_id:
        raise FeatureCodecError("neuron_id must be a non-empty string without NUL")
    if len(neuron_id.encode("utf-8", "strict")) > 512:
        raise FeatureCodecError("neuron_id exceeds 512 UTF-8 bytes")
    if not isinstance(state, NeuronFeatureState):
        raise FeatureCodecError("state must be NeuronFeatureState")
    state.validate()
    if synaptic_memory is None:
        synaptic_memory = {}
    if not isinstance(synaptic_memory, dict):
        raise FeatureCodecError("synaptic_memory must be a dictionary")
    entries = []
    for synapse_id, trace in sorted(synaptic_memory.items()):
        if (not isinstance(synapse_id, str) or not synapse_id.strip()
                or "\x00" in synapse_id
                or len(synapse_id.encode("utf-8", "strict")) > 512):
            raise FeatureCodecError("synapse IDs must be valid non-empty UTF-8 strings up to 512 bytes")
        if not isinstance(trace, SynapticMemoryState):
            raise FeatureCodecError("synaptic memory values must be SynapticMemoryState")
        trace.validate()
        entries.append({"synapse_id": synapse_id, "state": asdict(trace)})
    document = {
        "format": _FORMAT,
        "version": _VERSION,
        "neuron_id": neuron_id,
        "neuron_features": asdict(state),
        "synaptic_memory": entries,
    }
    try:
        return json.dumps(
            document, ensure_ascii=False, allow_nan=False,
            sort_keys=True, separators=(",", ":"),
        ).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise FeatureCodecError(f"cannot encode feature sidecar: {exc}") from exc


def decode_feature_sidecar(
    payload: bytes | bytearray | memoryview,
    *,
    expected_neuron_id: str | None = None,
) -> tuple[str, NeuronFeatureState, dict[str, SynapticMemoryState]]:
    """Strictly decode and validate a version-1 cognitive sidecar."""
    if not isinstance(payload, (bytes, bytearray, memoryview)):
        raise FeatureCodecError("payload must be bytes-like")
    raw = bytes(payload)
    if not raw or len(raw) > 1_048_576:
        raise FeatureCodecError("payload length must be in [1, 1048576]")
    try:
        doc = json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except FeatureCodecError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise FeatureCodecError(f"invalid sidecar JSON: {exc}") from exc
    if not isinstance(doc, dict) or set(doc) != {
        "format", "version", "neuron_id", "neuron_features", "synaptic_memory"
    }:
        raise FeatureCodecError("sidecar top-level fields mismatch")
    version = doc["version"]
    if doc["format"] != _FORMAT or isinstance(version, bool) or version not in (1, _VERSION):
        raise FeatureCodecError("unsupported feature sidecar format/version")
    neuron_id = doc["neuron_id"]
    if (not isinstance(neuron_id, str) or not neuron_id.strip() or "\x00" in neuron_id
            or len(neuron_id.encode("utf-8", "strict")) > 512):
        raise FeatureCodecError("invalid neuron_id")
    if expected_neuron_id is not None and neuron_id != expected_neuron_id:
        raise FeatureCodecError("sidecar neuron_id does not match requested neuron")
    raw_state = doc["neuron_features"]
    expected_state = set(NeuronFeatureState.__dataclass_fields__)
    legacy_state = expected_state - {"pattern_signature", "pattern_trace", "pattern_observations"}
    accepted_state = legacy_state if version == 1 else expected_state
    if not isinstance(raw_state, dict) or set(raw_state) != accepted_state:
        raise FeatureCodecError("neuron feature fields mismatch")
    try:
        state = NeuronFeatureState(**raw_state)
        state.validate()
    except (TypeError, NeuronFeatureError) as exc:
        raise FeatureCodecError(f"invalid neuron feature state: {exc}") from exc
    raw_synapses = doc["synaptic_memory"]
    if not isinstance(raw_synapses, list):
        raise FeatureCodecError("synaptic_memory must be an array")
    traces: dict[str, SynapticMemoryState] = {}
    names: list[str] = []
    expected_trace = set(SynapticMemoryState.__dataclass_fields__)
    for item in raw_synapses:
        if not isinstance(item, dict) or set(item) != {"synapse_id", "state"}:
            raise FeatureCodecError("synaptic memory entry fields mismatch")
        name, values = item["synapse_id"], item["state"]
        if (not isinstance(name, str) or not name.strip() or "\x00" in name
                or len(name.encode("utf-8", "strict")) > 512):
            raise FeatureCodecError("invalid synapse_id")
        if name in traces or not isinstance(values, dict) or set(values) != expected_trace:
            raise FeatureCodecError("duplicate synapse ID or invalid synaptic state fields")
        try:
            trace = SynapticMemoryState(**values)
            trace.validate()
        except (TypeError, NeuronFeatureError) as exc:
            raise FeatureCodecError(f"invalid synaptic memory state: {exc}") from exc
        traces[name] = trace
        names.append(name)
    if names != sorted(names):
        raise FeatureCodecError("synaptic memory entries are not in canonical order")
    return neuron_id, state, traces
