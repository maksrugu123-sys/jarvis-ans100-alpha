package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidNeuronRamBudgetPreferencesTest {
    @Test fun budgetMapsToBoundedHotSetCapacity() {
        assertEquals(512, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(1))
        assertEquals(1_024, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(2))
        assertEquals(2_048, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(4))
        assertEquals(4_096, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(8))
        assertEquals(4_096, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedBudgetIsRejected() { AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(3) }
}
