package org.jarvis.ans100.alpha.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidCpuComputeBenchmarkTest {
    @Test
    fun medianUsesMiddleSampleForOddCount() {
        assertEquals(5L, AndroidCpuComputeBenchmark.medianOfSortedSamples(listOf(1L, 5L, 9L)))
    }

    @Test
    fun medianUsesOverflowSafeMeanForEvenCount() {
        assertEquals(
            Long.MAX_VALUE - 1L,
            AndroidCpuComputeBenchmark.medianOfSortedSamples(
                listOf(Long.MAX_VALUE - 3L, Long.MAX_VALUE - 1L, Long.MAX_VALUE, Long.MAX_VALUE),
            ),
        )
    }

    @Test
    fun medianRejectsUnsortedOrEmptySamples() {
        assertFailsWith<IllegalArgumentException> {
            AndroidCpuComputeBenchmark.medianOfSortedSamples(listOf(2L, 1L))
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidCpuComputeBenchmark.medianOfSortedSamples(emptyList())
        }
    }
}
