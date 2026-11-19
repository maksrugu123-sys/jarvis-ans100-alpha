package org.jarvis.ans100.alpha.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidNeuronKernelComputeBenchmarkTest {

    @Test
    fun medianUsesMiddleSampleForOddCount() {
        assertEquals(
            20L,
            AndroidNeuronKernelComputeBenchmark.medianOfSortedSamples(listOf(10L, 20L, 90L)),
        )
    }

    @Test
    fun medianAveragesMiddleSamplesForEvenCountWithoutOverflowProneAddition() {
        assertEquals(
            25L,
            AndroidNeuronKernelComputeBenchmark.medianOfSortedSamples(listOf(10L, 20L, 30L, 90L)),
        )
    }

    @Test
    fun medianRejectsEmptyOrUnsortedSamples() {
        assertFailsWith<IllegalArgumentException> {
            AndroidNeuronKernelComputeBenchmark.medianOfSortedSamples(emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidNeuronKernelComputeBenchmark.medianOfSortedSamples(listOf(20L, 10L))
        }
    }
}
