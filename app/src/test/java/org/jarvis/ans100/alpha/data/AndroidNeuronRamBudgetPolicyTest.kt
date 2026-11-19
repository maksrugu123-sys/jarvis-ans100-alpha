package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidNeuronRamBudgetPolicyTest {
    @Test
    fun deviceCeilingUsesTheMostRestrictiveMemorySignal() {
        val profile = AndroidNeuronRamBudgetPolicy.profileFromMeasurements(
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            availableRamBytes = 512L * 1024L * 1024L,
            appMemoryClassMb = 256,
        )
        assertEquals(16, profile.maximumBudgetMb)
    }

    @Test
    fun lowMemoryDeviceGetsSmallerCeiling() {
        val profile = AndroidNeuronRamBudgetPolicy.profileFromMeasurements(
            totalRamBytes = 2L * 1024L * 1024L * 1024L,
            availableRamBytes = 256L * 1024L * 1024L,
            appMemoryClassMb = 64,
        )
        assertEquals(4, profile.maximumBudgetMb)
    }

    @Test
    fun zeroMeasurementsFallBackToSmallestBudget() {
        val profile = AndroidNeuronRamBudgetPolicy.profileFromMeasurements(0L, 0L, 16)
        assertEquals(1, profile.maximumBudgetMb)
    }
}
