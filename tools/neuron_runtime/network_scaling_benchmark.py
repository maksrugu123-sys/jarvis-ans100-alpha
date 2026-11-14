"""Measure bounded sparse-chain NetworkRuntime scaling.

Run from the repository root:
    python3 -m tools.neuron_runtime.network_scaling_benchmark --profile smoke
    python3 -m tools.neuron_runtime.network_scaling_benchmark --profile full

This is a Python reference benchmark, not a device or production-runtime test.
"""
from __future__ import annotations

import argparse
import gc
import json
import platform
import sys
import time
import tracemalloc
from typing import Any

from .network import (
    MAX_NETWORK_CONNECTIONS,
    MAX_NETWORK_NEURONS,
    NetworkConnection,
    NetworkRuntime,
)
from .runtime import NeuronRuntime

PROFILES = {
    "smoke": (1, 10),
    "pilot": (1, 10, 100, 1_000),
    "full": (1, 10, 100, 1_000, 10_000),
}


def build_sparse_chain(count: int) -> NetworkRuntime:
    """Build a deterministic one-input-per-node chain without dense adjacency."""
    if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= MAX_NETWORK_NEURONS:
        raise ValueError(f"count must be in [1, {MAX_NETWORK_NEURONS}]")
    if count - 1 > MAX_NETWORK_CONNECTIONS:
        raise ValueError(f"chain exceeds {MAX_NETWORK_CONNECTIONS} connections")

    neurons: dict[str, NeuronRuntime] = {}
    connections: list[NetworkConnection] = []
    for index in range(count):
        neuron_id = f"n{index:05d}"
        synapse_id = "external" if index == 0 else "incoming"
        neurons[neuron_id] = NeuronRuntime({synapse_id: 1.0})
        if index:
            previous_id = f"n{index - 1:05d}"
            connections.append(NetworkConnection(
                connection_id=f"edge-{index - 1:05d}",
                source_neuron_id=previous_id,
                target_neuron_id=neuron_id,
                target_synapse_id="incoming",
                delay_ns=1,
            ))

    return NetworkRuntime(
        neurons,
        connections,
        max_pending_events=16,
        max_events_per_run=count,
    )


def measure_population(count: int) -> dict[str, Any]:
    """Construct a chain, propagate one spike through it, and record Python costs."""
    gc.collect()
    if tracemalloc.is_tracing():
        raise RuntimeError("run the scaling measurement in an isolated process without active tracemalloc")

    tracemalloc.start()
    started = time.perf_counter()
    try:
        network = build_sparse_chain(count)
        constructed_at = time.perf_counter()
        network.schedule_input(0, "n00000", "external", "seed", 1.0)
        result = network.run()
        finished_at = time.perf_counter()
        current_bytes, peak_bytes = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()

    if (
        result.processed_events != count
        or len(result.spikes) != count
        or result.pending_events != 0
        or result.budget_exhausted
    ):
        raise RuntimeError(
            f"{count}-node chain did not fully propagate: "
            f"events={result.processed_events}, spikes={len(result.spikes)}, "
            f"pending={result.pending_events}, exhausted={result.budget_exhausted}"
        )

    return {
        "neurons": count,
        "connections": count - 1,
        "processed_events": result.processed_events,
        "spikes": len(result.spikes),
        "pending_events": result.pending_events,
        "construction_seconds": round(constructed_at - started, 6),
        "propagation_seconds": round(finished_at - constructed_at, 6),
        "total_seconds": round(finished_at - started, 6),
        "tracemalloc_current_bytes": current_bytes,
        "tracemalloc_peak_bytes": peak_bytes,
        "scope": "Python NetworkRuntime reference only; not Android or production NCC",
    }


def run_scaling_benchmark(profile: str = "smoke") -> dict[str, Any]:
    if profile not in PROFILES:
        raise ValueError(f"unknown profile {profile!r}; choose one of {tuple(PROFILES)}")
    rows = [measure_population(count) for count in PROFILES[profile]]
    return {
        "benchmark": "JARVIS_NETWORK_SCALING_V1",
        "status": "COMPLETED_REFERENCE_MEASUREMENTS" if rows else "NO_DATA",
        "profile": profile,
        "population_sizes": list(PROFILES[profile]),
        "python": sys.version,
        "platform": platform.platform(),
        "measurements": rows,
        "limits": {
            "max_neurons": MAX_NETWORK_NEURONS,
            "max_connections": MAX_NETWORK_CONNECTIONS,
        },
        "limitations": [
            "The benchmark uses a sparse chain, not a dense or learned topology.",
            "The entire topology remains resident in Python memory.",
            "tracemalloc excludes some native allocator and process-level memory costs.",
            "No CPU energy, thermal, flash I/O, JPVR checkpoint, Android or production scheduler behavior is measured.",
            "Successful propagation is not evidence of useful learning or semantic intelligence.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=tuple(PROFILES), default="smoke")
    args = parser.parse_args()
    report = run_scaling_benchmark(args.profile)
    json.dump(report, sys.stdout, indent=2, sort_keys=True, allow_nan=False)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
