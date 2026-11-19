"""Comparable CPU/GPU/hybrid compute benchmark primitives.

Backend callables must complete and synchronize their work before returning.
This module measures supplied backends; it does not itself provide a GPU kernel.
"""
from __future__ import annotations

from dataclasses import dataclass
from time import perf_counter_ns
from typing import Callable, Mapping


class ComputeBenchmarkError(ValueError):
    """Invalid benchmark configuration or backend result."""


@dataclass(frozen=True)
class BackendMeasurement:
    mode: str
    samples_ns: tuple[int, ...]
    median_ns: int
    operations: int
    operations_per_second: float


@dataclass(frozen=True)
class BenchmarkComparison:
    baseline_mode: str
    measurements: Mapping[str, BackendMeasurement]
    speedup_percent: Mapping[str, float]
    faster_than_baseline_percent: Mapping[str, float]


def _validate_mode(mode: object) -> str:
    if not isinstance(mode, str) or mode not in ("cpu", "gpu", "cpu_gpu"):
        raise ComputeBenchmarkError("mode must be cpu, gpu, or cpu_gpu")
    return mode


def run_compute_benchmark(backends: Mapping[str, Callable[[], object]], *,
                          operations: int, warmup_runs: int = 2,
                          measured_runs: int = 7, baseline_mode: str = "cpu") -> BenchmarkComparison:
    """Measure identical workloads; callbacks must return only after device sync.

    Warmups are excluded. Median latency reduces outlier impact. All backend
    outputs must equal the first backend output or the comparison is rejected.
    Positive faster_than_baseline_percent means faster; negative means slower.
    """
    if not isinstance(backends, Mapping) or not backends:
        raise ComputeBenchmarkError("backends must be a non-empty mapping")
    if isinstance(operations, bool) or not isinstance(operations, int) or operations <= 0:
        raise ComputeBenchmarkError("operations must be a positive integer")
    if isinstance(warmup_runs, bool) or not isinstance(warmup_runs, int) or not 0 <= warmup_runs <= 100:
        raise ComputeBenchmarkError("warmup_runs must be in [0, 100]")
    if isinstance(measured_runs, bool) or not isinstance(measured_runs, int) or not 3 <= measured_runs <= 101:
        raise ComputeBenchmarkError("measured_runs must be in [3, 101]")
    baseline_mode = _validate_mode(baseline_mode)
    normalized = {}
    for raw_mode, callback in backends.items():
        mode = _validate_mode(raw_mode)
        if mode in normalized or not callable(callback):
            raise ComputeBenchmarkError("backend modes must be unique and callbacks callable")
        normalized[mode] = callback
    if baseline_mode not in normalized:
        raise ComputeBenchmarkError("baseline_mode must have an available backend")

    results = {}
    reference_set, reference = False, None
    for mode in ("cpu", "gpu", "cpu_gpu"):
        callback = normalized.get(mode)
        if callback is None:
            continue
        try:
            for _ in range(warmup_runs):
                callback()
            samples = []
            for _ in range(measured_runs):
                started = perf_counter_ns()
                output = callback()
                elapsed = perf_counter_ns() - started
                if elapsed <= 0:
                    raise ComputeBenchmarkError("backend returned a non-positive measured duration")
                if not reference_set:
                    reference, reference_set = output, True
                elif output != reference:
                    raise ComputeBenchmarkError(f"{mode} produced output different from the reference backend")
                samples.append(elapsed)
        except ComputeBenchmarkError:
            raise
        except Exception as exc:
            raise ComputeBenchmarkError(f"{mode} backend failed: {exc}") from exc
        ordered = sorted(samples)
        median = ordered[len(ordered) // 2]
        results[mode] = BackendMeasurement(mode, tuple(samples), median, operations,
                                            operations * 1_000_000_000 / median)

    baseline_ns = results[baseline_mode].median_ns
    speedup, faster = {}, {}
    for mode, measurement in results.items():
        ratio = baseline_ns / measurement.median_ns
        speedup[mode] = ratio
        faster[mode] = (ratio - 1.0) * 100.0
    return BenchmarkComparison(baseline_mode, results, speedup, faster)
