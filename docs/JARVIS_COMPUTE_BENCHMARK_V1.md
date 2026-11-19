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

## Experimental Android GPU backend

`app/src/main/java/org/jarvis/ans100/alpha/data/Gles31AndroidNeuronComputeBackend.kt` adds an OpenGL ES 3.1 compute-shader implementation for the floating-point part of the LIF-DT candidate update. It creates a headless EGL pbuffer/context, compiles a compute shader, uploads timestamp-aggregated candidate inputs in SSBOs, dispatches 64-wide workgroups, waits through the shader memory barrier and synchronous result mapping, then validates returned states before the network can commit them.

The shader computes membrane decay, adaptation decay, bounded potential, adaptive threshold, refractory-gated spike decision and post-spike adaptation. CPU retains timestamp grouping, integer timestamp/refractory/spike-sequence metadata, event scheduling, feature updates, outgoing-event generation, and the authoritative SQLite transaction. Therefore GPU-only means GPU execution of the per-neuron floating-point candidate arithmetic, not a GPU-only ANS brain or GPU ownership of the complete runtime.

The GPU uses FP32 while the reference kernel uses Kotlin Double. Before accepting real batches, the adapter runs deterministic qualification fixtures against the CPU reference. Spike decisions and integer metadata must match exactly; potential and adaptation must be within 0.00005. It also refuses candidates whose pre-reset threshold margin is within 0.0002, rather than making an uncertain FP32 spike decision. Unsupported GLES versions, shader failures, GL errors, invalid outputs and ambiguous thresholds fail closed; there is no silent CPU fallback. Qualification is a startup gate, not proof of parity for every possible state or long-run drift.

`AndroidNeuronComputeModes.kt` defines explicit `CPU_ONLY`, `GPU_ONLY` and `CPU_GPU` choices. GPU-only runs on a dedicated EGL worker thread. CPU+GPU partitions timestamp batches with at least two candidates into non-empty halves and executes the GPU and CPU partitions concurrently, then restores the original candidate order. A one-candidate batch is deliberately processed by CPU because GPU launch/readback overhead would dominate; this is counted in `AndroidHybridComputeStats.cpuOnlySmallBatches`, separately from actual GPU batches/candidates. GPU-only never falls back to CPU. The factory does not silently substitute another mode.

## Current integration boundary and limitations

The backend is injectable through the existing `AndroidSparseNeuronNetwork(computeBackend=...)` constructor. `AndroidNeuronComputeModesTest.kt` adds JVM tests for actual partition assignment, output-order restoration, observable CPU scheduling for one-candidate hybrid batches, and propagation of GPU failures without CPU fallback; these tests are authored but have not been run. The new factory creates the requested backend, but the app's user-facing settings, persisted mode selection and normal runtime construction are not yet wired to that factory. The existing language-model GPU toggle is separate and does not control ANS neuron compute.

No GPU kernel is enabled by default. CPU remains the default reference backend. No GPU/hybrid benchmark, Android device execution, GLES shader compilation on a real device, battery/thermal measurement, or APK build has been run. The experimental GPU backend must not yet be treated as production-ready or as proof that this device supports compute shaders.

## Android integration gates

1. Run the Android CPU adapter on-device and verify repeatable signatures and bounded resource use.
2. Run JVM tests for the pure kernel and Android instrumentation tests for EGL initialization, shader compilation, buffer mapping, and candidate parity.
3. Verify qualification and fail-closed behavior on the target device; record exact GLES version and GPU renderer.
4. Integrate persisted mode selection into the ANS runtime construction path and expose it separately from the language-model GPU setting.
5. Measure identical workloads in CPU-only, GPU-only and true concurrent CPU+GPU modes, including upload, dispatch, synchronization, readback and partition overhead.
6. Check longer-run state drift, threshold-edge cases, spike sequence/refractory boundaries, and timestamp batches of varying sizes.
7. Record device model, runtime/backend, precision, thermal/battery/RAM context, and sample distributions.
8. Keep device runs short and bounded; do not run long held-out experiments as part of a compute benchmark.

No Android device benchmark or real-device GPU acceleration claim has been made.
