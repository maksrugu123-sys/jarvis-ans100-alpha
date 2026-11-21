package org.jarvis.ans100.alpha.data

import android.os.Debug
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Samples this Android process' proportional set size (PSS) while one benchmark
 * inference is running. Values are process-wide, not a per-model heap attribution.
 */
object ProcessMemoryPeakSampler {
    data class Measurement<T>(
        val value: T,
        val baselinePssMb: Double,
        val peakPssMb: Double,
        val peakDeltaMb: Double,
        val sampleIntervalMs: Long,
    )

    fun <T> measure(block: () -> T): Measurement<T> {
        val baselineKb = readPssKb()
        val peakKb = AtomicInteger(baselineKb)
        val running = AtomicBoolean(true)
        val sampler = Thread({
            while (running.get()) {
                peakKb.updateAndGet { current -> maxOf(current, readPssKb()) }
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ans100-memory-sampler").apply { isDaemon = true }
        sampler.start()
        val value: T
        try {
            value = block()
        } finally {
            running.set(false)
            sampler.interrupt()
            try {
                sampler.join(SAMPLER_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            peakKb.updateAndGet { current -> maxOf(current, readPssKb()) }
        }
        val peak = peakKb.get()
        return Measurement(
            value = value,
            baselinePssMb = baselineKb / 1024.0,
            peakPssMb = peak / 1024.0,
            peakDeltaMb = (peak - baselineKb).coerceAtLeast(0) / 1024.0,
            sampleIntervalMs = SAMPLE_INTERVAL_MS,
        )
    }

    private fun readPssKb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.coerceAtLeast(0)
    }

    private const val SAMPLE_INTERVAL_MS = 20L
    private const val SAMPLER_JOIN_TIMEOUT_MS = 250L
}
