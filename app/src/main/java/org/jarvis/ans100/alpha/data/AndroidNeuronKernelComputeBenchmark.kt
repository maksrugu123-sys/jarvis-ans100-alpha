package org.jarvis.ans100.alpha.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.Closeable
import kotlin.math.abs

data class AndroidNeuronKernelModeMeasurement(
    val mode: AndroidNeuronComputeMode,
    val backendName: String,
    val samplesNs: List<Long>,
    val medianLatencyNs: Long,
    val candidatesPerSecond: Double,
    val verifiedAgainstCpu: Boolean,
)

data class AndroidNeuronKernelModeAvailability(
    val mode: AndroidNeuronComputeMode,
    val available: Boolean,
    val reason: String? = null,
)

data class AndroidNeuronKernelBatchResult(
    val batchSize: Int,
    val cpu: AndroidNeuronKernelModeMeasurement,
    val gpu: AndroidNeuronKernelModeMeasurement?,
    val hybrid: AndroidNeuronKernelModeMeasurement?,
    val gpuAvailability: AndroidNeuronKernelModeAvailability,
    val hybridAvailability: AndroidNeuronKernelModeAvailability,
    val gpuSpeedupRatio: Double?,
    val gpuFasterThanCpuPercent: Double?,
    val hybridSpeedupRatio: Double?,
    val hybridFasterThanCpuPercent: Double?,
)

data class AndroidNeuronKernelBenchmarkReport(
    val deviceModel: String,
    val androidApiLevel: Int,
    val warmupRuns: Int,
    val measuredRuns: Int,
    val batches: List<AndroidNeuronKernelBatchResult>,
    val note: String = "Измеряется только LIF-DT batch kernel: включая GPU upload, dispatch, synchronization и readback; без SQLite и остального ANS runtime.",
)

/**
 * Compares the exact same immutable LIF-DT candidates on CPU, GPU and hybrid.
 * GPU timings include input upload, dispatch, synchronization and result readback.
 * Setup/qualification and warmups are excluded from samples. Every output is
 * checked against the CPU reference before its latency is accepted.
 */
class AndroidNeuronKernelComputeBenchmark(context: Context) : Closeable {
    private val appContext = context.applicationContext
    @Volatile private var closed = false

    fun run(
        batchSizes: List<Int> = DEFAULT_BATCH_SIZES,
        warmupRuns: Int = DEFAULT_WARMUP_RUNS,
        measuredRuns: Int = DEFAULT_MEASURED_RUNS,
    ): AndroidNeuronKernelBenchmarkReport {
        check(!closed) { "neuron compute benchmark is closed" }
        require(batchSizes.isNotEmpty() && batchSizes.size <= MAX_BATCH_SIZES)
        require(batchSizes.all { it in 1..MAX_BATCH_SIZE } && batchSizes.distinct().size == batchSizes.size)
        require(warmupRuns in MIN_WARMUP_RUNS..MAX_WARMUP_RUNS)
        require(measuredRuns in MIN_MEASURED_RUNS..MAX_MEASURED_RUNS)

        val results = batchSizes.map { batchSize -> benchmarkBatch(batchSize, warmupRuns, measuredRuns) }
        return AndroidNeuronKernelBenchmarkReport(
            deviceModel = Build.MODEL ?: "unknown",
            androidApiLevel = Build.VERSION.SDK_INT,
            warmupRuns = warmupRuns,
            measuredRuns = measuredRuns,
            batches = results,
        )
    }

    private fun benchmarkBatch(
        batchSize: Int,
        warmupRuns: Int,
        measuredRuns: Int,
    ): AndroidNeuronKernelBatchResult {
        val inputs = createInputs(batchSize)
        val cpuReference = CpuAndroidNeuronComputeBackend.processBatch(inputs)
        val cpuSamples = ArrayList<Long>()
        val gpuSamples = ArrayList<Long>()
        val hybridSamples = ArrayList<Long>()
        var gpuBackend: AndroidNeuronComputeBackend? = null
        var hybridBackend: AndroidNeuronComputeBackend? = null
        var gpuFailure: String? = null
        var hybridFailure: String? = null

        try {
            gpuBackend = runCatching {
                AndroidNeuronComputeBackendFactory.create(appContext, AndroidNeuronComputeMode.GPU_ONLY)
            }.getOrElse {
                gpuFailure = conciseError(it)
                null
            }
            hybridBackend = runCatching {
                AndroidNeuronComputeBackendFactory.create(appContext, AndroidNeuronComputeMode.CPU_GPU)
            }.getOrElse {
                hybridFailure = conciseError(it)
                null
            }

            // Warm each available backend first. GPU context creation, shader compile,
            // CPU-reference qualification and JIT effects are deliberately excluded.
            repeat(warmupRuns) {
                verify(cpuReference, CpuAndroidNeuronComputeBackend.processBatch(inputs))
                gpuBackend?.let { backend ->
                    try {
                        verify(cpuReference, backend.processBatch(inputs))
                    } catch (error: Throwable) {
                        gpuFailure = conciseError(error)
                        (backend as? Closeable)?.close()
                        gpuBackend = null
                    }
                }
                hybridBackend?.let { backend ->
                    try {
                        verify(cpuReference, backend.processBatch(inputs))
                    } catch (error: Throwable) {
                        hybridFailure = conciseError(error)
                        (backend as? Closeable)?.close()
                        hybridBackend = null
                    }
                }
            }

            // Interleave modes and rotate their order on each repetition to reduce
            // systematic thermal/order bias. Every measured sample includes the
            // complete backend call, including GPU transfers and readback.
            repeat(measuredRuns) { sampleIndex ->
                val order = listOf(
                    AndroidNeuronComputeMode.CPU_ONLY,
                    AndroidNeuronComputeMode.GPU_ONLY,
                    AndroidNeuronComputeMode.CPU_GPU,
                ).let { modes ->
                    modes.drop(sampleIndex % modes.size) + modes.take(sampleIndex % modes.size)
                }
                for (mode in order) {
                    when (mode) {
                        AndroidNeuronComputeMode.CPU_ONLY -> {
                            val startNs = SystemClock.elapsedRealtimeNanos()
                            val output = CpuAndroidNeuronComputeBackend.processBatch(inputs)
                            cpuSamples += SystemClock.elapsedRealtimeNanos() - startNs
                            verify(cpuReference, output)
                        }
                        AndroidNeuronComputeMode.GPU_ONLY -> {
                            val backend = gpuBackend
                            if (backend != null) {
                                try {
                                    val startNs = SystemClock.elapsedRealtimeNanos()
                                    val output = backend.processBatch(inputs)
                                    val elapsed = SystemClock.elapsedRealtimeNanos() - startNs
                                    verify(cpuReference, output)
                                    check(elapsed > 0L)
                                    gpuSamples += elapsed
                                } catch (error: Throwable) {
                                    gpuFailure = conciseError(error)
                                    gpuSamples.clear()
                                    (backend as? Closeable)?.close()
                                    gpuBackend = null
                                }
                            }
                        }
                        AndroidNeuronComputeMode.CPU_GPU -> {
                            val backend = hybridBackend
                            if (backend != null) {
                                try {
                                    val startNs = SystemClock.elapsedRealtimeNanos()
                                    val output = backend.processBatch(inputs)
                                    val elapsed = SystemClock.elapsedRealtimeNanos() - startNs
                                    verify(cpuReference, output)
                                    check(elapsed > 0L)
                                    hybridSamples += elapsed
                                } catch (error: Throwable) {
                                    hybridFailure = conciseError(error)
                                    hybridSamples.clear()
                                    (backend as? Closeable)?.close()
                                    hybridBackend = null
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            (gpuBackend as? Closeable)?.close()
            (hybridBackend as? Closeable)?.close()
        }

        val cpu = measurement(AndroidNeuronComputeMode.CPU_ONLY, CpuAndroidNeuronComputeBackend.backendName, cpuSamples, batchSize)
        val gpu = if (gpuFailure == null && gpuSamples.size == measuredRuns) {
            measurement(AndroidNeuronComputeMode.GPU_ONLY, "gles31-compute-fp32", gpuSamples, batchSize)
        } else null
        val hybrid = if (hybridFailure == null && hybridSamples.size == measuredRuns) {
            measurement(AndroidNeuronComputeMode.CPU_GPU, "cpu+gles31-hybrid", hybridSamples, batchSize)
        } else null

        return AndroidNeuronKernelBatchResult(
            batchSize = batchSize,
            cpu = cpu,
            gpu = gpu,
            hybrid = hybrid,
            gpuAvailability = AndroidNeuronKernelModeAvailability(
                AndroidNeuronComputeMode.GPU_ONLY, gpu != null, gpuFailure,
            ),
            hybridAvailability = AndroidNeuronKernelModeAvailability(
                AndroidNeuronComputeMode.CPU_GPU, hybrid != null, hybridFailure,
            ),
            gpuSpeedupRatio = gpu?.let { cpu.medianLatencyNs.toDouble() / it.medianLatencyNs.toDouble() },
            gpuFasterThanCpuPercent = gpu?.let { fasterPercent(cpu.medianLatencyNs, it.medianLatencyNs) },
            hybridSpeedupRatio = hybrid?.let { cpu.medianLatencyNs.toDouble() / it.medianLatencyNs.toDouble() },
            hybridFasterThanCpuPercent = hybrid?.let { fasterPercent(cpu.medianLatencyNs, it.medianLatencyNs) },
        )
    }

    private fun verify(
        expected: List<AndroidNeuronKernel.Result>,
        actual: List<AndroidNeuronKernel.Result>,
    ) {
        check(actual.size == expected.size) { "backend returned an incomplete candidate batch" }
        expected.indices.forEach { index ->
            val left = expected[index]
            val right = actual[index]
            check(left.spiked == right.spiked) { "spike decision mismatch at candidate $index" }
            check(left.state.neuronId == right.state.neuronId &&
                left.state.lastUpdateNs == right.state.lastUpdateNs &&
                left.state.refractoryUntilNs == right.state.refractoryUntilNs &&
                left.state.spikeSequence == right.state.spikeSequence
            ) { "integer neuron metadata mismatch at candidate $index" }
            check(abs(left.state.potential - right.state.potential) <= STATE_TOLERANCE &&
                abs(left.state.adaptation - right.state.adaptation) <= STATE_TOLERANCE
            ) { "floating-point state mismatch at candidate $index" }
        }
    }

    private fun createInputs(size: Int): List<AndroidNeuronKernel.Input> {
        val timestampNs = 20_000_000L
        return List(size) { index ->
            val adaptation = 0.1 + (index % 3) * 0.05
            val shouldCrossThreshold = index % 4 == 1
            val potential = if (shouldCrossThreshold) 0.2 else 0.15
            val refractoryClosed = shouldCrossThreshold && index % 8 == 1
            AndroidNeuronKernel.Input(
                state = AndroidNeuronState(
                    neuronId = index,
                    potential = potential,
                    adaptation = adaptation,
                    lastUpdateNs = 0L,
                    refractoryUntilNs = if (refractoryClosed) timestampNs + 1L else 0L,
                ),
                timestampNs = timestampNs,
                input = if (shouldCrossThreshold) 1.2 else 0.1,
            )
        }
    }

    private fun measurement(
        mode: AndroidNeuronComputeMode,
        backendName: String,
        samples: List<Long>,
        operations: Int,
    ): AndroidNeuronKernelModeMeasurement {
        val sorted = samples.sorted()
        val median = medianOfSortedSamples(sorted)
        return AndroidNeuronKernelModeMeasurement(
            mode = mode,
            backendName = backendName,
            samplesNs = samples.toList(),
            medianLatencyNs = median,
            candidatesPerSecond = operations.toDouble() * NANOS_PER_SECOND / median.toDouble(),
            verifiedAgainstCpu = true,
        )
    }

    private fun fasterPercent(cpuMedianNs: Long, candidateMedianNs: Long): Double =
        ((cpuMedianNs.toDouble() / candidateMedianNs.toDouble()) - 1.0) * 100.0

    private fun conciseError(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)

    override fun close() {
        closed = true
    }

    companion object {
        val DEFAULT_BATCH_SIZES = listOf(32, 128, 256)
        const val DEFAULT_WARMUP_RUNS = 1
        const val MIN_WARMUP_RUNS = 1
        const val DEFAULT_MEASURED_RUNS = 3
        const val MAX_WARMUP_RUNS = 2
        const val MIN_MEASURED_RUNS = 3
        const val MAX_MEASURED_RUNS = 5
        const val MAX_BATCH_SIZES = 5
        const val MAX_BATCH_SIZE = 512
        const val STATE_TOLERANCE = 0.00005
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MAX_ERROR_CHARS = 240

        internal fun medianOfSortedSamples(sortedSamples: List<Long>): Long {
            require(sortedSamples.isNotEmpty() && sortedSamples.all { it > 0L })
            require(sortedSamples.zipWithNext().all { (left, right) -> left <= right })
            val middle = sortedSamples.size / 2
            return if (sortedSamples.size % 2 == 1) sortedSamples[middle]
            else sortedSamples[middle - 1] + (sortedSamples[middle] - sortedSamples[middle - 1]) / 2L
        }
    }
}
