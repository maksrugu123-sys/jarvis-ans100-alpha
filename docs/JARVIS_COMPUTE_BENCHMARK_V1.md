# JARVIS ANS-100 Compute Benchmark V1

## Purpose

Provide a repeatable, correctness-gated comparison of CPU-only (cpu), GPU-only (gpu), and CPU + GPU (cpu_gpu) execution modes. Report median latency, throughput, speedup ratio, and percentage faster/slower than the CPU baseline. CPU is 0% by definition.

## Fair comparison contract

All backend adapters must run the exact same deterministic workload with identical model state, input batch, neuron/connection counts, precision, and operation count. A backend callback must synchronize its device queue before returning; otherwise asynchronous GPU launch time would be measured instead of completed work.

The benchmark performs warm-ups separately, collects multiple samples, uses median latency, checks that backend outputs match, and refuses to report a comparison if a backend fails or produces a different result. Only supplied and working backends are reported.

Speedup ratio is CPU median latency divided by the selected mode median latency. Percentage faster/slower is (speedup ratio - 1) * 100. For example, 1.25x is 25% faster; 0.80x is 20% slower.

## Integration boundary

tools/neuron_runtime/compute_benchmark.py is a backend-agnostic measurement primitive. It does not implement GPU kernels, discover Android GPU capabilities, or connect to Android UI. It must not fabricate GPU measurements when no GPU adapter exists. The current Python reference network is not a GPU implementation, and no CPU/GPU performance claim is made.

Next gates: implement and verify a CPU adapter; implement a real GPU adapter using supported device APIs and explicit synchronization; implement a hybrid adapter that accounts for partitioning and transfer costs; add device/thermal/battery/RAM metadata; then expose the three modes and benchmark screen in Android. Start with short bounded tests.

No performance benchmark or Android device measurement has been run for this increment.