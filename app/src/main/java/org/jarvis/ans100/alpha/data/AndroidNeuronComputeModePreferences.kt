package org.jarvis.ans100.alpha.data

import android.content.Context

/** Persistent ANS neuron-compute selection, separate from the language-model GPU preference. */
class AndroidNeuronComputeModePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): AndroidNeuronComputeMode {
        val stored = preferences.getString(KEY_MODE, null) ?: return AndroidNeuronComputeMode.CPU_ONLY
        return runCatching { AndroidNeuronComputeMode.valueOf(stored) }
            .getOrDefault(AndroidNeuronComputeMode.CPU_ONLY)
    }

    fun save(mode: AndroidNeuronComputeMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "ans100_neuron_compute_v1"
        private const val KEY_MODE = "mode"
    }
}
