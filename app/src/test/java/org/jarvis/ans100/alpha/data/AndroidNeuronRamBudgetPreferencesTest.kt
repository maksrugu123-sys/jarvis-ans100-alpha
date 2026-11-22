package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidNeuronRamBudgetPreferencesTest {
    @Test fun budgetMapsToBoundedHotSetCapacity() {
        assertEquals(512, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(1))
        assertEquals(1_024, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(2))
        assertEquals(1_536, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(3))
        assertEquals(2_048, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(4))
        assertEquals(2_560, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(5))
        assertEquals(4_096, AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroBudgetIsRejected() { AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(0) }

    @Test(expected = IllegalArgumentException::class)
    fun budgetAboveImplementedHotSetLimitIsRejected() { AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(16) }
}
