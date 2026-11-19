package org.jarvis.ans100.alpha.data

import android.content.Context

/**
 * User-controlled budget for the Android ANS neuron-state hot set.
 * This is not a reservation or hard cap on total process RAM.
 */
class AndroidNeuronRamBudgetPreferences(context: Context) {
    private val preferencesContext = context.applicationContext
    private val preferences = preferencesContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadMegabytes(): Int {
        val saved = preferences.getInt(KEY_BUDGET_MB, DEFAULT_BUDGET_MB)
        return ALLOWED_BUDGETS_MB.firstOrNull { it == saved } ?: DEFAULT_BUDGET_MB
    }

    fun saveMegabytes(megabytes: Int) {
        require(megabytes in ALLOWED_BUDGETS_MB) { "Unsupported ANS RAM budget" }
        preferences.edit().putInt(KEY_BUDGET_MB, megabytes).apply()
    }

    fun loadHotSetCapacity(): Int {
        val deviceMaximum = AndroidNeuronRamBudgetPolicy.inspect(preferencesContext).maximumBudgetMb
        return hotSetCapacityFor(loadMegabytes(deviceMaximum))
    }

    fun loadMegabytes(maximumAllowed: Int): Int {
        require(maximumAllowed in ALLOWED_BUDGETS_MB) { "Unsupported device RAM ceiling" }
        return loadMegabytes().coerceAtMost(maximumAllowed)
    }

    companion object {
        const val DEFAULT_BUDGET_MB = 4
        const val ESTIMATED_BYTES_PER_RESIDENT_NEURON = 2_048
        const val MAX_HOT_SET_CAPACITY = 4_096
        val ALLOWED_BUDGETS_MB = listOf(1, 2, 4, 8)
        private const val PREFERENCES_NAME = "ans_neuron_ram_budget_v1"
        private const val KEY_BUDGET_MB = "managed_hot_set_budget_mb"

        fun hotSetCapacityFor(megabytes: Int): Int {
            require(megabytes in ALLOWED_BUDGETS_MB) { "Unsupported ANS RAM budget" }
            val budgetBytes = megabytes.toLong() * 1024L * 1024L
            val slots = (budgetBytes / ESTIMATED_BYTES_PER_RESIDENT_NEURON).coerceAtLeast(1L)
            return minOf(slots, MAX_HOT_SET_CAPACITY.toLong()).toInt()
        }
    }
}
