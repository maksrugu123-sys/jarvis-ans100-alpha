package org.jarvis.ans100.alpha.data

import android.content.Context
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit compute choices. No mode silently degrades into another mode. */
enum class AndroidNeuronComputeMode {
    CPU_ONLY,
    GPU_ONLY,
    CPU_GPU,
}

/**
 * Creates the requested backend without probing or silently changing the mode.
 * GPU initialization happens on the first batch; an unavailable GPU is reported
 * as an AndroidNeuronNetworkException by the selected backend.
 */
object AndroidNeuronComputeBackendFactory {
    fun create(
        context: Context,
        mode: AndroidNeuronComputeMode,
    ): AndroidNeuronComputeBackend = when (mode) {
        AndroidNeuronComputeMode.CPU_ONLY -> CpuAndroidNeuronComputeBackend
        AndroidNeuronComputeMode.GPU_ONLY -> DedicatedGpuAndroidNeuronComputeBackend(context)
        AndroidNeuronComputeMode.CPU_GPU -> CpuGpuAndroidNeuronComputeBackend(context)
    }
}

/**
 * Owns the GLES context on a dedicated worker so callers may submit batches
 * from the network thread without violating EGL thread confinement.
 */
class DedicatedGpuAndroidNeuronComputeBackend(context: Context) :
    AndroidNeuronComputeBackend, Closeable {

    override val backendName: String = "gpu-only-gles31"
    private val gpu = Gles31AndroidNeuronComputeBackend(context)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ANS100-GPU").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    @Synchronized
    override fun processBatch(
        inputs: List<AndroidNeuronKernel.Input>,
    ): List<AndroidNeuronKernel.Result> {
        check(!closed) { "GPU-only compute backend is closed" }
        if (inputs.isEmpty()) return emptyList()
        return try {
            executor.submit(Callable { gpu.processBatch(inputs) }).get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AndroidNeuronNetworkException("interrupted while waiting for GPU compute")
        } catch (error: ExecutionException) {
            throw AndroidNeuronNetworkException(
                "GPU-only mode unavailable: ${error.cause?.message ?: error.message}",
            )
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            executor.submit { gpu.close() }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Closing is best-effort after the worker has stopped accepting work.
        } finally {
            executor.shutdownNow()
        }
    }
}

data class AndroidHybridComputeStats(
    val gpuBatches: Int,
    val gpuCandidates: Int,
    val cpuCandidates: Int,
    val cpuOnlySmallBatches: Int,
)

/**
 * Genuine split execution: the GPU computes the first partition while the CPU
 * computes the remaining partition concurrently. Results are reassembled in
 * the caller's original order. Single-candidate batches are explicitly routed
 * to CPU and counted, because a GPU dispatch would cost more than the work.
 */
class CpuGpuAndroidNeuronComputeBackend private constructor(
    private val gpu: AndroidNeuronComputeBackend,
    workerName: String,
) : AndroidNeuronComputeBackend, Closeable {

    constructor(context: Context) : this(Gles31AndroidNeuronComputeBackend(context), "ANS100-GLES31")

    internal constructor(gpuBackend: AndroidNeuronComputeBackend) : this(gpuBackend, "ANS100-GPU-TEST")

    override val backendName: String = "cpu+gles31-hybrid"
    private val gpuExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, workerName).apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var gpuBatches = 0
    private var gpuCandidates = 0
    private var cpuCandidates = 0
    private var cpuOnlySmallBatches = 0

    @Synchronized
    override fun processBatch(
        inputs: List<AndroidNeuronKernel.Input>,
    ): List<AndroidNeuronKernel.Result> {
        check(!closed) { "CPU+GPU compute backend is closed" }
        if (inputs.isEmpty()) return emptyList()
        if (inputs.size == 1) {
            // Never dispatch a one-item GPU batch: launch/readback overhead dominates.
            // This is an explicit, observable hybrid scheduling decision, not GPU-only fallback.
            cpuOnlySmallBatches++
            cpuCandidates++
            return CpuAndroidNeuronComputeBackend.processBatch(inputs)
        }
        val gpuCount = (inputs.size + 1) / 2
        val gpuInputs = inputs.subList(0, gpuCount).toList()
        val cpuInputs = inputs.subList(gpuCount, inputs.size).toList()
        val gpuFuture = gpuExecutor.submit(Callable { gpu.processBatch(gpuInputs) })
        val cpuResults = CpuAndroidNeuronComputeBackend.processBatch(cpuInputs)
        val gpuResults = try {
            gpuFuture.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            gpuFuture.cancel(true)
            throw AndroidNeuronNetworkException("interrupted while waiting for GPU compute")
        } catch (error: ExecutionException) {
            val cause = error.cause
            throw AndroidNeuronNetworkException(
                "GPU partition failed: ${cause?.message ?: error.message}",
            )
        }
        gpuBatches++
        gpuCandidates += gpuInputs.size
        cpuCandidates += cpuInputs.size
        return gpuResults + cpuResults
    }

    @Synchronized
    fun stats(): AndroidHybridComputeStats = AndroidHybridComputeStats(
        gpuBatches = gpuBatches,
        gpuCandidates = gpuCandidates,
        cpuCandidates = cpuCandidates,
        cpuOnlySmallBatches = cpuOnlySmallBatches,
    )

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            gpuExecutor.submit { (gpu as? Closeable)?.close() }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The backend is being shut down; no future work can be submitted.
        } finally {
            gpuExecutor.shutdownNow()
        }
    }
}
