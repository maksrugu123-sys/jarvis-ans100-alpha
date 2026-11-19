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

/**
 * Genuine split execution: the GPU computes the first partition while the CPU
 * computes the remaining partition concurrently. Results are reassembled in
 * the caller's original order. This is intentionally not used for batches with
 * fewer than two candidates because both processors must receive real work.
 */
class CpuGpuAndroidNeuronComputeBackend(context: Context) :
    AndroidNeuronComputeBackend, Closeable {

    override val backendName: String = "cpu+gles31-hybrid"
    private val gpu = Gles31AndroidNeuronComputeBackend(context)
    private val gpuExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ANS100-GLES31").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    @Synchronized
    override fun processBatch(
        inputs: List<AndroidNeuronKernel.Input>,
    ): List<AndroidNeuronKernel.Result> {
        check(!closed) { "CPU+GPU compute backend is closed" }
        if (inputs.isEmpty()) return emptyList()
        if (inputs.size < 2) {
            throw AndroidNeuronNetworkException(
                "CPU+GPU mode requires at least two neuron candidates in a timestamp batch",
            )
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
        return gpuResults + cpuResults
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            gpuExecutor.submit { gpu.close() }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The backend is being shut down; no future work can be submitted.
        } finally {
            gpuExecutor.shutdownNow()
        }
    }
}
