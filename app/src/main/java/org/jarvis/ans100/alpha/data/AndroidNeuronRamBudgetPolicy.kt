package org.jarvis.ans100.alpha.data

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import kotlin.math.min

/**
 * Conservative device-aware ceiling for the managed ANS neuron-state cache.
 * This is a policy estimate, not a reservation or a total-process RAM limit.
 */
data class AndroidNeuronRamProfile(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val appMemoryClassMb: Int,
    val maximumBudgetMb: Int,
) {
    val summary: String
        get() = "RAM устройства: " +
            String.format(java.util.Locale.ROOT, "%.1f", totalRamMb / 1024.0) +
            " ГБ · свободно при запуске: ${availableRamMb} МБ · класс памяти приложения: ${appMemoryClassMb} МБ"
}

object AndroidNeuronRamBudgetPolicy {
    private const val APP_HEAP_FRACTION = 0.08
    private const val AVAILABLE_RAM_FRACTION = 0.10
    private const val TOTAL_RAM_FRACTION = 0.03
    private const val MEBIBYTE = 1024L * 1024L

    fun inspect(context: Context): AndroidNeuronRamProfile {
        val manager = context.applicationContext.getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            ?: return profileFromMeasurements(0L, 0L, 16)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return profileFromMeasurements(
            totalRamBytes = info.totalMem,
            availableRamBytes = info.availMem,
            appMemoryClassMb = manager.memoryClass.coerceAtLeast(16),
        )
    }

    internal fun profileFromMeasurements(
        totalRamBytes: Long,
        availableRamBytes: Long,
        appMemoryClassMb: Int,
    ): AndroidNeuronRamProfile {
        val totalMb = (totalRamBytes.coerceAtLeast(0L) / MEBIBYTE).coerceAtLeast(1L)
        val availableMb = (availableRamBytes.coerceAtLeast(0L) / MEBIBYTE).coerceAtLeast(1L)
        val heapMb = appMemoryClassMb.coerceAtLeast(16)
        val rawCeilingMb = min(
            min(heapMb * APP_HEAP_FRACTION, availableMb * AVAILABLE_RAM_FRACTION),
            totalMb * TOTAL_RAM_FRACTION,
        )
        val rounded = AndroidNeuronRamBudgetPreferences.ALLOWED_BUDGETS_MB
            .filter { it <= rawCeilingMb }
            .maxOrNull()
            ?: AndroidNeuronRamBudgetPreferences.ALLOWED_BUDGETS_MB.first()
        return AndroidNeuronRamProfile(totalMb, availableMb, heapMb, rounded)
    }
}
