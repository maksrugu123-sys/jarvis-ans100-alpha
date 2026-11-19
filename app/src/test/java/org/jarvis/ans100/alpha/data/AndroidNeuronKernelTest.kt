package org.jarvis.ans100.alpha.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AndroidNeuronKernelTest {
    @Test
    fun subthresholdInputUpdatesDecayedStateWithoutSpike() {
        val initial = AndroidNeuronState(
            neuronId = 7,
            potential = 0.5,
            adaptation = 0.2,
            lastUpdateNs = 0L,
        )
        val result = AndroidNeuronKernel.process(initial, 10_000_000L, 0.1)
        assertFalse(result.spiked)
        assertEquals(7, result.state.neuronId)
        assertEquals(10_000_000L, result.state.lastUpdateNs)
        assertEquals(0L, result.state.spikeSequence)
        assertTrue(result.state.potential in 0.0..1.0)
    }

    @Test
    fun thresholdCrossingResetsPotentialAndStartsRefractoryPeriod() {
        val result = AndroidNeuronKernel.process(
            AndroidNeuronState(neuronId = 3),
            timestampNs = 5_000_000L,
            input = 1.2,
        )
        assertTrue(result.spiked)
        assertEquals(0.0, result.state.potential)
        assertEquals(1L, result.state.spikeSequence)
        assertEquals(7_000_000L, result.state.refractoryUntilNs)
        assertEquals(5_000_000L, result.state.lastUpdateNs)
    }

    @Test
    fun refractoryNeuronDoesNotSpikeEvenAboveThreshold() {
        val result = AndroidNeuronKernel.process(
            AndroidNeuronState(
                neuronId = 4,
                refractoryUntilNs = 10_000_000L,
            ),
            timestampNs = 9_000_000L,
            input = 1.5,
        )
        assertFalse(result.spiked)
        assertEquals(0L, result.state.spikeSequence)
        assertEquals(10_000_000L, result.state.refractoryUntilNs)
    }

    @Test
    fun backwardsTimeAndNonFiniteInputAreRejected() {
        assertFailsWith<AndroidNeuronNetworkException> {
            AndroidNeuronKernel.process(
                AndroidNeuronState(neuronId = 1, lastUpdateNs = 2L),
                timestampNs = 1L,
                input = 0.1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidNeuronKernel.process(AndroidNeuronState(neuronId = 1), 1L, Double.NaN)
        }
    }

    @Test
    fun overflowAtSpikeBoundaryIsRejected() {
        assertFailsWith<AndroidNeuronNetworkException> {
            AndroidNeuronKernel.process(
                AndroidNeuronState(neuronId = 1, spikeSequence = Long.MAX_VALUE),
                timestampNs = 1L,
                input = 1.5,
            )
        }
    }
    @Test
    fun batchBackendPreservesInputOrderAndMatchesSingleNeuronReference() {
        val inputs = listOf(
            AndroidNeuronKernel.Input(AndroidNeuronState(neuronId = 8), 1_000_000L, 0.4),
            AndroidNeuronKernel.Input(AndroidNeuronState(neuronId = 2), 2_000_000L, 1.2),
            AndroidNeuronKernel.Input(AndroidNeuronState(neuronId = 5, potential = 0.3), 3_000_000L, 0.2),
        )
        val batch = CpuAndroidNeuronComputeBackend.processBatch(inputs)
        assertEquals(inputs.size, batch.size)
        inputs.indices.forEach { index ->
            assertEquals(
                AndroidNeuronKernel.process(
                    inputs[index].state,
                    inputs[index].timestampNs,
                    inputs[index].input,
                ),
                batch[index],
            )
        }
        assertEquals(listOf(8, 2, 5), batch.map { it.state.neuronId })
    }

    @Test
    fun batchFailureDoesNotMutateInputStates() {
        val initial = AndroidNeuronState(neuronId = 11, potential = 0.4)
        val before = initial.copy()
        assertFailsWith<AndroidNeuronNetworkException> {
            AndroidNeuronKernel.processBatch(
                listOf(
                    AndroidNeuronKernel.Input(initial, 1_000_000L, 0.2),
                    AndroidNeuronKernel.Input(AndroidNeuronState(neuronId = 12, lastUpdateNs = 9L), 1L, 0.1),
                ),
            )
        }
        assertEquals(before, initial)
    }

}
