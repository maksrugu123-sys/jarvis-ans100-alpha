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

## Android CPU adapter

`app/src/main/java/org/jarvis/ans100/alpha/data/AndroidCpuComputeBenchmark.kt` adds a bounded CPU-only benchmark over the real isolated Android `ANS100StandaloneBenchmarkRuntime`. It resets the isolated lexical benchmark database before each warm-up and measured sample, runs the same input without supervised labels, and checks a deterministic result signature across samples. It reports the median of the runtime's inference-duration measurements, processed neural events per second, device model, and Android API level. Database reset/topology initialization are excluded from inference timing; the timed runtime duration includes lexical encoding, the SQLite-backed neural event loop, readout, and hypothesis update.

The default is one warm-up and five measured runs. Inputs are limited to 2,048 characters, warm-ups to three, and measured runs to 3–9. This is a bounded diagnostic benchmark, not a long held-out evaluation. The CPU baseline is always 1.0x / 0% relative to itself.

## Extracted neuron compute kernel

`AndroidNeuronKernel.kt` contains the pure deterministic LIF-DT candidate calculation used by `AndroidSparseNeuronNetwork`. Its batch input contains detached neuron states, a model timestamp per candidate, and the already-aggregated input for each neuron. Its output is an ordered list of candidate states plus spike decisions. It has no SQLite, LRU, event-queue, clock, or persistence side effects.

`AndroidNeuronComputeBackend` is now the injected batch boundary. The default `CpuAndroidNeuronComputeBackend` calls the reference kernel. `AndroidSparseNeuronNetwork` builds one candidate per target neuron for each timestamp, calls the backend once for that timestamp, verifies output count, candidate identity, and legal spike-state transitions, and only then proceeds to feature updates and outgoing event generation. A future GPU backend can be injected without moving the event queue or transaction ownership out of the existing runtime.

The event loop still groups all events at one timestamp, sums each target neuron's inputs, calculates feature updates and generated events, and commits the entire timestamp through the existing atomic store transaction. Only after that commit succeeds are changed states published into the hot LRU. The kernel extraction does not move or weaken that transaction boundary.

This pure function is the CPU reference contract for a future GPU implementation. GPU parity must match potential decay, adaptation decay, input clamping, threshold comparison, refractory gating, spike sequence increment, reset potential, and overflow rejection. Simultaneous inputs remain aggregated by the CPU before the kernel call; a future accelerator must receive the same aggregate and cannot process simultaneous events sequentially.

Eight JVM unit tests cover subthreshold decay, threshold crossing, refractory gating, invalid time/input, spike-counter overflow, batch ordering/reference equivalence, input-state isolation on failure, and rejection of invalid backend spike transitions. They are authored but have not been run. No GPU kernel exists yet.

## GPU and hybrid status

No GPU kernel, Android GPU adapter, or hybrid partitioner is implemented. GPU-only and CPU+GPU therefore remain unavailable; the adapter explicitly reports this rather than silently substituting CPU.

Android supports GPU compute through Vulkan (NDK) and OpenGL ES 3.1 compute. Vulkan requires native initialization, selection of a physical device and compute queue, shader compilation, dispatch, and completion synchronization. GLES 3.1 compute is accessible through Android SDK bindings. The project should choose one real compute path after runtime device capability checks, not infer compute support from the Android version alone.

The existing Android ANS runtime is SQLite-backed and commits each timestamp atomically. A practical first acceleration boundary is the deterministic per-neuron candidate calculation for one timestamp: CPU retains queue grouping, synapse lookup/event generation, SQLite transaction and commit; a GPU kernel may calculate candidate neuron state/spike decisions for the same grouped inputs. The GPU result must be returned and validated before the existing atomic commit. This makes CPU+GPU a genuine split. A GPU-only neural-arithmetic mode would require the entire neuron-state update kernel to execute on GPU, while CPU still performs host orchestration and persistence; it must never be labelled GPU-only if only a small unrelated calculation is offloaded.

For hybrid mode, partitioning overhead, input/output transfer, synchronization, and queue wait time must be included. A GPU path is enabled only after its output matches CPU results on deterministic parity fixtures, including simultaneous same-timestamp inputs, refractory boundaries, threshold crossings, and generated spikes.

## Android integration gates

1. Run the Android CPU adapter on-device and verify repeatable signatures and bounded resource use.
2. Extract a pure per-neuron candidate calculation from the Android timestamp loop without changing its transaction boundary.
3. Add deterministic CPU parity fixtures for the extracted calculation.
4. Implement and synchronize a Vulkan or GLES 3.1 compute adapter; do not enable it based only on a feature flag.
5. Add hybrid partitioning with transfer-inclusive timing and CPU/GPU result equivalence.
6. Record device model, runtime/backend, precision, thermal/battery/RAM context, and sample distributions.
7. Expose the three modes in Android UI only after the corresponding adapters pass correctness checks.
8. Keep device runs short and bounded; do not run long held-out experiments as part of a compute benchmark.

No Android device benchmark or GPU acceleration claim has been made.