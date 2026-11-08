"""Measure the current Numerical Oracle Python-object baseline.

This is a resource-observation harness, not a packed-state candidate and not
a production runtime. It reports object-graph, pickle-reference, tracemalloc
and /proc measurements separately.
"""
from __future__ import annotations

import argparse
import dataclasses
import gc
import json
import platform
import pickle
import sys
import tracemalloc
from typing import Any

from tools.numerical_oracle.jarvis_oracle import (
    InputSignal, NeuronParams, NeuronState, RewardParams,
    SynapseParams, SynapseState,
)

PROFILES = {
    "smoke": (1, 10),
    "pilot": (1, 10, 100, 1000),
    "full": (1, 10, 100, 1000, 10000),
}


def deep_sizeof(root: Any) -> int:
    """Return an object-graph size, counting shared objects only once."""
    seen: set[int] = set()

    def visit(value: Any) -> int:
        identity = id(value)
        if identity in seen:
            return 0
        seen.add(identity)
        size = sys.getsizeof(value)

        if dataclasses.is_dataclass(value) and not isinstance(value, type):
            children = [vars(value)]
        elif isinstance(value, dict):
            children = []
            for key, item in value.items():
                children.extend((key, item))
        elif isinstance(value, (list, tuple, set, frozenset)):
            children = list(value)
        else:
            children = []

        return size + sum(visit(child) for child in children)

    return visit(root)


def build_reference_population(count: int) -> dict[str, Any]:
    """Create deterministic Oracle-shaped reference objects."""
    if isinstance(count, bool) or not isinstance(count, int) or count < 1:
        raise ValueError("count must be a positive integer")

    neurons = []
    none_synapses = []
    stdp_synapses = []
    rstdp_synapses = []
    input_signals = []

    for index in range(count):
        time_ns = index * 1000
        weight = 0.5 + (index % 100) / 1000.0
        neurons.append(NeuronState(
            u=(index % 20) / 100.0,
            a=(index % 10) / 100.0,
            last_update_model_time_ns=time_ns,
        ))
        none_synapses.append(SynapseState(weight=weight))
        stdp_synapses.append(SynapseState(
            weight=weight,
            pre_trace=(index % 8) / 10.0,
            post_trace=(index % 7) / 10.0,
            trace_last_update_model_time_ns=time_ns,
        ))
        rstdp_synapses.append(SynapseState(
            weight=weight,
            pre_trace=(index % 8) / 10.0,
            post_trace=(index % 7) / 10.0,
            trace_last_update_model_time_ns=time_ns,
            eligibility_trace=0.001,
            eligibility_context_id=f"context-{index}",
            eligibility_started_model_time_ns=time_ns,
            eligibility_last_update_model_time_ns=time_ns,
        ))
        input_signals.append(InputSignal(
            source_id=f"synapse-{index}",
            signed_weight=weight,
            strength=0.5 + (index % 10) / 20.0,
            attention=(index % 5) / 10.0,
        ))

    return {
        "neurons": neurons,
        "synapses_none": none_synapses,
        "synapses_stdp": stdp_synapses,
        "synapses_rstdp": rstdp_synapses,
        "input_signals": input_signals,
    }


def _proc_memory_kib() -> dict[str, int] | None:
    """Read Linux/Android VmRSS and VmHWM, when procfs is available."""
    try:
        values: dict[str, int] = {}
        with open("/proc/self/status", encoding="ascii") as stream:
            for line in stream:
                if line.startswith(("VmRSS:", "VmHWM:")):
                    fields = line.split()
                    if len(fields) >= 2:
                        values[fields[0].rstrip(":")] = int(fields[1])
        return values or None
    except (OSError, UnicodeError, ValueError):
        return None


def _parameter_sharing_bytes(count: int) -> dict[str, int | str]:
    shared = {
        "neuron": NeuronParams(),
        "synapse": SynapseParams(),
        "reward": RewardParams(),
    }
    replicated = [
        (NeuronParams(), SynapseParams(), RewardParams())
        for _ in range(count)
    ]
    return {
        "one_shared_parameter_bank_deep_bytes": deep_sizeof(shared),
        "one_parameter_triple_per_population_item_deep_bytes": deep_sizeof(replicated),
        "population_items": count,
        "scope_note": (
            "illustrative sharing scenario only; the Numerical Oracle passes "
            "parameter objects into functions and does not embed a parameter "
            "triple in every State dataclass"
        ),
    }


def measure_population(count: int, include_process_metrics: bool = True) -> dict[str, Any]:
    gc.collect()
    if tracemalloc.is_tracing():
        raise RuntimeError("run this measurement in an isolated process without active tracemalloc")
    tracemalloc.start()
    try:
        before_current, _ = tracemalloc.get_traced_memory()
        tracemalloc.reset_peak()
        rss_before = _proc_memory_kib() if include_process_metrics else None
        objects = build_reference_population(count)
        object_graph_bytes = deep_sizeof(objects)
        pickle_bytes = len(pickle.dumps(objects, protocol=5))
        current_bytes, peak_bytes = tracemalloc.get_traced_memory()
        rss_after = _proc_memory_kib() if include_process_metrics else None
    finally:
        tracemalloc.stop()

    parameter_scenario = _parameter_sharing_bytes(count)
    return {
        "population_items": count,
        "objects": {name: len(value) for name, value in objects.items()},
        "reference_object_graph_deep_bytes": object_graph_bytes,
        "reference_pickle_protocol_5_bytes": pickle_bytes,
        "tracemalloc_current_delta_bytes": max(0, current_bytes - before_current),
        "tracemalloc_peak_delta_bytes": max(0, peak_bytes - before_current),
        "process_memory_before_kib": rss_before,
        "process_memory_after_kib": rss_after,
        "process_memory_delta_kib": (
            {
                key: rss_after[key] - rss_before[key]
                for key in ("VmRSS", "VmHWM")
                if key in rss_before and key in rss_after
            }
            if rss_before is not None and rss_after is not None
            else None
        ),
        "parameter_sharing_scenario": parameter_scenario,
        "scope": (
            "Python reference-object measurement only; pickle bytes are a diagnostic "
            "comparison, not a JPVR or production persistence format"
        ),
    }


def run_baseline(profile: str = "smoke", include_process_metrics: bool = True) -> dict[str, Any]:
    if profile not in PROFILES:
        raise ValueError(f"unknown profile {profile!r}; choose one of {tuple(PROFILES)}")
    rows = [
        measure_population(count, include_process_metrics)
        for count in PROFILES[profile]
    ]
    return {
        "benchmark": "JARVIS_V5_REFERENCE_RESOURCE_BASELINE_V1",
        "status": "REFERENCE_OBJECT_BASELINE_ONLY",
        "profile": profile,
        "python": sys.version,
        "implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "population_sizes": list(PROFILES[profile]),
        "measurements": rows,
        "limitations": [
            "No packed-state candidate is measured in this benchmark.",
            "Deep object size, pickle bytes, tracemalloc and process RSS are distinct metrics.",
            "Pickle is used only as a diagnostic serialization-size measure, not as a trusted storage format.",
            "The illustrative shared-parameter scenario is not the current Oracle object layout.",
            "No event latency, CPU time, energy, JPVR storage or production-runtime performance is measured.",
            "A lower theoretical payload size is not evidence of lower total RAM or preserved model quality.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=tuple(PROFILES), default="smoke")
    args = parser.parse_args()
    for index, count in enumerate(PROFILES[args.profile], start=1):
        print(
            f"[V5 baseline] population {index}/{len(PROFILES[args.profile])}: {count}",
            file=sys.stderr,
            flush=True,
        )
    report = run_baseline(args.profile)
    json.dump(report, sys.stdout, indent=2, sort_keys=True, allow_nan=False)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
