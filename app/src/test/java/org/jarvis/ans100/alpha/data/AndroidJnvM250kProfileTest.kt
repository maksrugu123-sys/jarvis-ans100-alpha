package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidJnvM250kProfileTest {
    @Test
    fun acceptsFirstAndLastNeuronIds() {
        AndroidJnvM250kProfile.requireNeuronId(0)
        AndroidJnvM250kProfile.requireNeuronId(AndroidJnvM250kProfile.NEURON_CAPACITY - 1)
    }

    @Test
    fun rejectsNeuronIdOutside250kRange() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidJnvM250kProfile.requireNeuronId(AndroidJnvM250kProfile.NEURON_CAPACITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidJnvM250kProfile.requireNeuronId(-1)
        }
    }

    @Test
    fun pageSizeIsAlwaysBounded() {
        AndroidJnvM250kProfile.requirePageSize(1)
        AndroidJnvM250kProfile.requirePageSize(AndroidJnvM250kProfile.MAX_PAGE_SIZE)
        assertThrows(IllegalArgumentException::class.java) {
            AndroidJnvM250kProfile.requirePageSize(AndroidJnvM250kProfile.MAX_PAGE_SIZE + 1)
        }
    }

    @Test
    fun legacyDialogueCapacityIsNotChanged() {
        assertEquals(10_000, AndroidSparseNeuronNetwork.MAX_NEURONS)
        assertEquals(250_000, AndroidJnvM250kProfile.NEURON_CAPACITY)
        assertEquals(4, AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_NEURONS)
    }
}
