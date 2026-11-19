# JARVIS ANS-100 Compute Benchmark V1

## Purpose

Provide a correctness-gated comparison of CPU-only (`cpu`), GPU-only (`gpu`), and CPU + GPU (`cpu_gpu`) execution. Report median latency, throughput, speedup ratio, and percentage faster/slower than the CPU baseline. CPU is 0% by definition.

## CPU adapter

`network_benchmark_adapters.py` provides a CPU adapter over the actual Python `NetworkRuntime` event loop. Each invocation constructs a fresh network from a caller-supplied factory, schedules the same immutable event set, executes the normal network run loop, and returns a deterministic signature containing run counters, spikes, clock, and neuron state. This avoids benchmarking a reused or already-mutated network.

`benchmark_network_cpu` reports the CPU baseline only. It does not pretend that GPU or hybrid execution exists.

## Fair comparison contract

All backend adapters must run the exact same deterministic workload with identical model state, input batch, neuron/connection counts, precision, and operation count. A backend callback must synchronize its device queue before returning; otherwise asynchronous GPU launch time would be measured instead of completed work.

The benchmark performs warm-ups separately, collects multiple samples, uses median latency, checks that backend outputs match, and refuses a comparison if a backend fails or produces a different result. Only supplied and working backends are reported.

Speedup ratio is CPU median latency divided by the selected mode median latency. Percentage faster/slower is `(speedup ratio - 1) * 100`. For example, 1.25x is 25% faster; 0.80x is 20% slower.

## GPU and hybrid status

No GPU kernel, Android GPU adapter, or hybrid partitioner is implemented by this increment. The Python reference network is CPU-executed. GPU-only and CPU+GPU must remain unavailable in the UI until actual device adapters execute the same workload and pass result-equivalence checks. GPU timing must include synchronization and, for hybrid mode, relevant data-transfer and partition overhead.

## Android integration gates

1. Implement a CPU adapter over Android ANS runtime and verify deterministic outputs.
2. Implement a real GPU adapter only with supported Android APIs/kernels and explicit completion synchronization.
3. Implement a hybrid adapter with documented partitioning and transfer costs.
4. Record device model, runtime/backend, precision, thermal/battery/RAM context, and sample distributions.
5. Expose the three modes and benchmark screen only after adapters pass correctness checks.
6. Start with short bounded tests; do not run long held-out experiments as part of a benchmark.

No Android device benchmark or GPU acceleration claim has been made.