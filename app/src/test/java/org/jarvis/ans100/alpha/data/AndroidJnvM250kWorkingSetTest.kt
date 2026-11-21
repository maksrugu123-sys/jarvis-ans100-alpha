package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidJnvM250kWorkingSetTest {
    @Test
    fun mapsSharedRamBudgetsToBoundedResidentCapacities() {
        assertEquals(512, AndroidJnvM250kWorkingSet.capacityForBudgetMb(1))
        assertEquals(1_024, AndroidJnvM250kWorkingSet.capacityForBudgetMb(2))
        assertEquals(2_048, AndroidJnvM250kWorkingSet.capacityForBudgetMb(4))
        assertEquals(4_096, AndroidJnvM250kWorkingSet.capacityForBudgetMb(8))
    }

    @Test
    fun capacityNeverExceedsManagedHotSetLimit() {
        assertEquals(
            AndroidNeuronRamBudgetPreferences.MAX_HOT_SET_CAPACITY,
            AndroidJnvM250kWorkingSet.capacityForBudgetMb(8),
        )
    }
}
