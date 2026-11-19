package org.jarvis.ans100.alpha.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidNeuronComputeModesTest {

    @Test
    fun hybridSplitsWorkAcrossGpuAndCpuAndRestoresOriginalOrder() {
        val gpu = RecordingBackend()
        val backend = CpuGpuAndroidNeuronComputeBackend(gpu)
        try {
            val inputs = listOf(1, 2, 3, 4, 5).map { id ->
                AndroidNeuronKernel.Input(
                    AndroidNeuronState(neuronId = id, potential = 0.1),
                    timestampNs = 1_000_000L,
                    input = 0.1,
                )
            }

            val results = backend.processBatch(inputs)

            assertEquals(listOf(1, 2, 3), gpu.receivedNeuronIds)
            assertEquals(listOf(1, 2, 3, 4, 5), results.map { it.state.neuronId })
            assertEquals(3, gpu.receivedCount)
        } finally {
            backend.close()
        }
    }

    @Test
    fun hybridRejectsSingleCandidateInsteadOfPretendingBothBackendsWorked() {
        val gpu = RecordingBackend()
        val backend = CpuGpuAndroidNeuronComputeBackend(gpu)
        try {
            val input = AndroidNeuronKernel.Input(
                AndroidNeuronState(neuronId = 9),
                timestampNs = 1L,
                input = 0.1,
            )
            assertFailsWith<AndroidNeuronNetworkException> {
                backend.processBatch(listOf(input))
            }
            assertEquals(emptyList(), gpu.receivedNeuronIds)
        } finally {
            backend.close()
        }
    }

    @Test
    fun gpuPartitionFailureIsPropagatedWithoutCpuFallback() {
        val gpu = RecordingBackend(fail = true)
        val backend = CpuGpuAndroidNeuronComputeBackend(gpu)
        try {
            val inputs = listOf(10, 11).map { id ->
                AndroidNeuronKernel.Input(AndroidNeuronState(neuronId = id), 1L, 0.1)
            }
            assertFailsWith<AndroidNeuronNetworkException> {
                backend.processBatch(inputs)
            }
            assertEquals(listOf(10), gpu.receivedNeuronIds)
        } finally {
            backend.close()
        }
    }

    private class RecordingBackend(
        private val fail: Boolean = false,
    ) : AndroidNeuronComputeBackend {
        override val backendName: String = "test-gpu"
        @Volatile var receivedNeuronIds: List<Int> = emptyList()
            private set
        val receivedCount: Int get() = receivedNeuronIds.size

        override fun processBatch(
            inputs: List<AndroidNeuronKernel.Input>,
        ): List<AndroidNeuronKernel.Result> {
            receivedNeuronIds = inputs.map { it.state.neuronId }
            if (fail) throw AndroidNeuronNetworkException("injected GPU failure")
            return CpuAndroidNeuronComputeBackend.processBatch(inputs)
        }
    }
}
