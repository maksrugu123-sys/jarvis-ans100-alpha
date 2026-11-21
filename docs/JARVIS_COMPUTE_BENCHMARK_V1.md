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

Eight JVM unit tests cover subthreshold decay, threshold crossing, refractory gating, invalid time/input, spike-counter overflow, batch ordering/reference equivalence, input-state isolation on failure, and rejection of invalid backend spike transitions. They are authored but have not been run.

## Experimental Android GPU backend

`app/src/main/java/org/jarvis/ans100/alpha/data/Gles31AndroidNeuronComputeBackend.kt` adds an OpenGL ES 3.1 compute-shader implementation for the floating-point part of the LIF-DT candidate update. It creates a headless EGL pbuffer/context, compiles a compute shader, uploads timestamp-aggregated candidate inputs in SSBOs, dispatches 64-wide workgroups, waits through the shader memory barrier and synchronous result mapping, then validates returned states before the network can commit them.

The shader computes membrane decay, adaptation decay, bounded potential, adaptive threshold, refractory-gated spike decision and post-spike adaptation. CPU retains timestamp grouping, integer timestamp/refractory/spike-sequence metadata, event scheduling, feature updates, outgoing-event generation, and the authoritative SQLite transaction. Therefore GPU-only means GPU execution of the per-neuron floating-point candidate arithmetic, not a GPU-only ANS brain or GPU ownership of the complete runtime.

The GPU uses FP32 while the reference kernel uses Kotlin Double. Before accepting real batches, the adapter runs deterministic qualification fixtures against the CPU reference. Spike decisions and integer metadata must match exactly; potential and adaptation must be within 0.00005. It also refuses candidates whose pre-reset threshold margin is within 0.0002, rather than making an uncertain FP32 spike decision. Unsupported GLES versions, shader failures, GL errors, invalid outputs and ambiguous thresholds fail closed; there is no silent CPU fallback. Qualification is a startup gate, not proof of parity for every possible state or long-run drift.

`AndroidNeuronComputeModes.kt` defines explicit `CPU_ONLY`, `GPU_ONLY` and `CPU_GPU` choices. GPU-only runs on a dedicated EGL worker thread. CPU+GPU partitions timestamp batches with at least two candidates into non-empty halves and executes the GPU and CPU partitions concurrently, then restores the original candidate order. A one-candidate batch is deliberately processed by CPU because GPU launch/readback overhead would dominate; this is counted in `AndroidHybridComputeStats.cpuOnlySmallBatches`, separately from actual GPU batches/candidates. GPU-only never falls back to CPU. The factory does not silently substitute another mode.

## Android CPU / GPU / hybrid kernel benchmark

`AndroidNeuronKernelComputeBenchmark.kt` adds a bounded in-app comparison launched from Settings → Speed. It runs deterministic candidate batches of 32, 128 and 256 neurons by default, with one untimed warm-up and three measured samples per backend. The sample order rotates between CPU, GPU and CPU + GPU to reduce systematic ordering/thermal bias. It reports median latency, candidates per second, speedup ratio and percentage faster/slower against the CPU median.

Every backend receives the same immutable input states, timestamps and already-aggregated input values. Each output is checked against the CPU reference: spike decisions and integer metadata must match exactly; potential and adaptation must remain within 0.00005. A backend that fails initialization, qualification, execution or parity is marked unavailable with its reason; its timing is not published. GPU timings include input upload, shader dispatch, synchronization and readback. Hybrid timings include worker scheduling and both concurrent partitions. The hybrid report also shows measured CPU/GPU candidate counts and GPU batch count; warm-up counters are reset before measured samples.

This benchmark isolates the LIF-DT batch kernel and backend overhead. It does **not** measure full ANS reasoning, SQLite persistence, event propagation, language-model inference, battery drain, or sustained thermal throttling. A faster kernel result must not be described as equivalent acceleration of the complete JARVIS application. Results are generated only on the user's device; no synthetic performance numbers are inserted.

## Current integration boundary and limitations

The backend is injectable through the existing `AndroidSparseNeuronNetwork(computeBackend=...)` constructor. The Settings → Speed panel now exposes CPU, GPU and CPU + GPU choices; `AndroidNeuronComputeModePreferences` persists the selection independently of the language-model GPU toggle. The normal hybrid ANS runtime reads the selected mode when its lazy network is initialized, and the network closes its owned backend with the SQLite store. If the network was already initialized when the user changes the selection, the app must be restarted for the new mode to take effect. `AndroidNeuronComputeModesTest.kt` adds JVM tests for actual partition assignment, output-order restoration, observable CPU scheduling for one-candidate hybrid batches, and propagation of GPU failures without CPU fallback. `Gles31AndroidNeuronComputeBackendInstrumentedTest.kt` adds an on-device shader/readback parity test that skips only when the device does not advertise GLES 3.1. Both test suites are authored but have not been run. The existing language-model GPU toggle is separate and does not control ANS neuron compute.

No GPU kernel is enabled by default. CPU remains the default reference backend. The in-app kernel benchmark and report UI are implemented but have not yet been run on a device. No measured GPU/hybrid speedup, Android device execution, GLES shader compilation on the target phone, battery/thermal measurement, or APK build has been completed. The experimental GPU backend must not yet be treated as production-ready or as proof that this device supports compute shaders.

## Android integration gates

1. Run JVM tests for the pure kernel and hybrid partition contract.
2. Run the Android instrumentation test for EGL initialization, shader compilation, buffer mapping and CPU parity.
3. Run the in-app bounded kernel benchmark on the target phone and inspect which backends passed qualification.
4. Repeat at several batch sizes and compare median latency, throughput and measured CPU/GPU partition counts.
5. Check longer-run state drift, threshold-edge cases, spike sequence/refractory boundaries, and timestamp batches of varying sizes.
6. Separately measure full-network performance, SQLite costs, battery use and thermal throttling before making application-level speed claims.
7. Record device model, runtime/backend, precision, thermal/battery/RAM context, and sample distributions.
8. Keep device runs short and bounded; do not run long held-out experiments as part of a compute benchmark.

No Android device benchmark or real-device GPU acceleration claim has been made.


## User-controlled Android neuron RAM budget

The Speed settings expose a persisted 1 / 2 / 4 / 8 MiB budget for the managed neuron-state hot set. The device-aware policy hides values above the current device ceiling. The runtime converts the selected budget into a bounded LRU capacity using a planning allowance of 2,048 bytes per resident neuron and the current 4,096-entry ceiling. Capacities are 512 / 1,024 / 2,048 / 4,096 entries.

This is not a reservation or hard cap on total Android process memory. SQLite pages, connection/index structures, GPU driver allocations, temporary buffers, Kotlin/ART object overhead outside the managed hot set, and the rest of the app consume additional memory. The 2,048-byte figure is a planning allowance, not a measured per-neuron heap size. Android may still reclaim or terminate the process under system memory pressure. The selected budget is loaded when a new AndroidSparseNeuronNetwork instance is created and is re-evaluated by an existing network at the next compute-run boundary or stats refresh.

The current Android network remains limited to 10,000 neurons and 100,000 connections. This preference does not itself raise those structural limits or complete the planned 250,000-neuron migration. The SQLite schema is now V13 and adds an index on connections.target_id for incoming-edge counts and target-side graph queries. This is a backward-compatible lookup optimization only; it does not raise neuron, connection or event-queue limits. The staged migration requirements are documented in docs/ANDROID_JNVM_250K_SCALING_PLAN.md.


## Device-aware Android neuron RAM ceiling

The Android settings screen derives the selectable maximum from three runtime measurements:
- physical RAM reported by `ActivityManager.MemoryInfo.totalMem`;
- currently available RAM reported by `ActivityManager.MemoryInfo.availMem`;
- the app memory class reported by `ActivityManager.memoryClass`.

The conservative ceiling is the minimum of 8% of the app memory class, 10% of currently available RAM, and 3% of physical RAM, rounded down to a supported budget step (1/2/4/8 MB). The current implementation caps the managed hot set at 4,096 resident neurons (8 MB at the planning allowance of 2,048 bytes per resident neuron), so the selectable ceiling cannot exceed 8 MB until that structural limit is redesigned.

The profile is sampled when the app creates its settings state and again whenever a new Android sparse neuron network loads its hot-set capacity. A previously saved value above the current device ceiling is clamped at use time. This is a planning ceiling for the managed neuron-state cache, not a reservation, not a process-wide memory cap, and not a measured per-neuron heap footprint. Runtime memory pressure can still change after sampling.


## Dynamic JNVM hot-set resizing

An existing `AndroidSparseNeuronNetwork` now re-samples the device RAM profile before every compute run and before reporting network stats. It applies the currently selected preference against the latest device ceiling. The core-state and feature-state access-order caches are resized together under the network's synchronized API. Shrinking removes least-recently-used clean cache snapshots; timestamp updates are committed to SQLite before cache publication, so trimming does not discard uncommitted neuron changes. Growing the budget allows subsequent cache fills without deleting persisted state.

The public `refreshRamBudget()` method can be called after settings changes or when the app returns to the foreground. `updateHotSetCapacity()` is a bounded explicit override for controlled orchestration and tests; a later automatic refresh returns to the persisted device-aware preference. Runtime refresh occurs at compute-run boundaries, not in the middle of a timestamp transaction.
