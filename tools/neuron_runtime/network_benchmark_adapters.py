"""CPU adapter for benchmarking the actual ANS NetworkRuntime reference path.

Each invocation creates a fresh network so measured runs start from identical
state. GPU and hybrid adapters can later implement the same zero-argument
callable contract and be compared against this exact workload.
"""
from __future__ import annotations

from typing import Callable, Sequence

from .compute_benchmark import BenchmarkComparison, run_compute_benchmark
from .network import NetworkRuntime


InputEvent = tuple[int, str, str, str, float, float]


def make_network_cpu_backend(
    network_factory: Callable[[], NetworkRuntime],
    input_events: Sequence[InputEvent],
    *,
    learning_context_id: str | None = None,
    max_events: int | None = None,
) -> Callable[[], tuple]:
    """Build a deterministic CPU workload callback over the real network loop.

    InputEvent fields are (time_ns, neuron_id, synapse_id, event_id, strength,
    attention). The factory must return a fresh, identically configured network
    on every call. The callback returns a stable result signature.
    """
    if not callable(network_factory):
        raise ValueError("network_factory must be callable")
    if not isinstance(input_events, (tuple, list)) or not input_events:
        raise ValueError("input_events must be a non-empty tuple or list")
    frozen_events = tuple(input_events)
    for event in frozen_events:
        if not isinstance(event, tuple) or len(event) != 6:
            raise ValueError("each input event must be a six-item tuple")
    if max_events is not None and (isinstance(max_events, bool) or not isinstance(max_events, int) or max_events < 1):
        raise ValueError("max_events must be a positive integer or None")

    def execute() -> tuple:
        network = network_factory()
        if not isinstance(network, NetworkRuntime):
            raise TypeError("network_factory must return NetworkRuntime")
        for time_ns, neuron_id, synapse_id, event_id, strength, attention in frozen_events:
            network.schedule_input(time_ns, neuron_id, synapse_id, event_id, strength, attention)
        result = network.run(learning_context_id=learning_context_id, max_events=max_events)
        states = tuple(
            (neuron_id, state.voltage, state.adaptation, state.spike_sequence, state.last_update_model_time_ns)
            for neuron_id, state in sorted(network.neuron_states.items())
        )
        return (result.processed_events, result.processed_batches, result.spikes,
                result.pending_events, result.budget_exhausted, result.current_time_ns, states)

    return execute


def benchmark_network_cpu(
    network_factory: Callable[[], NetworkRuntime],
    input_events: Sequence[InputEvent],
    *,
    warmup_runs: int = 2,
    measured_runs: int = 7,
    learning_context_id: str | None = None,
    max_events: int | None = None,
) -> BenchmarkComparison:
    """Benchmark the real CPU NetworkRuntime path with reproducible inputs."""
    backend = make_network_cpu_backend(
        network_factory, input_events,
        learning_context_id=learning_context_id, max_events=max_events,
    )
    return run_compute_benchmark(
        {"cpu": backend}, operations=len(input_events),
        warmup_runs=warmup_runs, measured_runs=measured_runs,
    )