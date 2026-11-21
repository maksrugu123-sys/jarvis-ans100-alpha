package org.jarvis.ans100.alpha.data

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class Gles31AndroidNeuronComputeBackendInstrumentedTest {

    @Test
    fun computeShaderMatchesCpuReferenceForDeterministicBatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val requiredGlVersion = activityManager.deviceConfigurationInfo.reqGlEsVersion
        assumeTrue(
            "Device does not advertise OpenGL ES 3.1",
            requiredGlVersion >= 0x00030001,
        )

        val inputs = listOf(
            AndroidNeuronKernel.Input(
                AndroidNeuronState(neuronId = 201, potential = 0.25, adaptation = 0.2),
                10_000_000L, 0.2,
            ),
            AndroidNeuronKernel.Input(
                AndroidNeuronState(neuronId = 202, potential = 0.2, adaptation = 0.1),
                5_000_000L, 1.2,
            ),
            AndroidNeuronKernel.Input(
                AndroidNeuronState(
                    neuronId = 203, potential = 0.4, adaptation = 0.3,
                    refractoryUntilNs = 10_000_000L,
                ),
                9_000_000L, 1.4,
            ),
            AndroidNeuronKernel.Input(
                AndroidNeuronState(neuronId = 204, potential = -0.5, adaptation = 0.8),
                100_000_000L, 0.25,
            ),
        )
        val backend = Gles31AndroidNeuronComputeBackend(context)
        try {
            val actual = backend.processBatch(inputs)
            val expected = CpuAndroidNeuronComputeBackend.processBatch(inputs)
            assertEquals(expected.size, actual.size)
            for (index in expected.indices) {
                assertEquals(expected[index].spiked, actual[index].spiked)
                assertEquals(expected[index].state.spikeSequence, actual[index].state.spikeSequence)
                assertEquals(expected[index].state.refractoryUntilNs, actual[index].state.refractoryUntilNs)
                assertEquals(expected[index].state.lastUpdateNs, actual[index].state.lastUpdateNs)
                assertEquals(
                    expected[index].state.potential,
                    actual[index].state.potential,
                    0.00005,
                )
                assertEquals(
                    expected[index].state.adaptation,
                    actual[index].state.adaptation,
                    0.00005,
                )
            }
        } finally {
            backend.close()
        }
    }
}
