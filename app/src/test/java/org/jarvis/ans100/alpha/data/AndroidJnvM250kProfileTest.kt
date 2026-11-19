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
    fun sparseAndEventBudgetsAreExplicitlyBounded() {
        assertEquals(16, AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON)
        assertEquals(32, AndroidJnvM250kProfile.MAX_INCOMING_CONNECTIONS_PER_NEURON)
        assertEquals(100_000, AndroidJnvM250kProfile.MAX_PENDING_EVENTS)
        assertEquals(512, AndroidJnvM250kProfile.MAX_EVENTS_PER_TIMESTAMP)
        assertEquals(512, AndroidJnvM250kProfile.MAX_EVENTS_PER_ENQUEUE)
        assertEquals(8_192, AndroidJnvM250kProfile.MAX_GENERATED_EVENTS_PER_TIMESTAMP)
    }

    @Test
    fun defaultDatabaseIsSeparateFromLegacyDialogueStore() {
        assertEquals("jarvis_ans100_jnvm_sparse_250k_v1.db", AndroidJnvM250kProfile.DATABASE_NAME)
        assertEquals(false, AndroidJnvM250kProfile.DATABASE_NAME == AndroidSparseNeuronNetwork.DEFAULT_DATABASE_NAME)
    }

    @Test
    fun legacyDialogueCapacityIsNotChanged() {
        assertEquals(10_000, AndroidSparseNeuronNetwork.MAX_NEURONS)
        assertEquals(250_000, AndroidJnvM250kProfile.NEURON_CAPACITY)
        assertEquals(4, AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_NEURONS)
    }
}
