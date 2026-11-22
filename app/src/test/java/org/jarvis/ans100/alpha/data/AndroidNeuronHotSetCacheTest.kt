package org.jarvis.ans100.alpha.data

import java.util.LinkedHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidNeuronHotSetCacheTest {
    @Test
    fun shrinkEvictsLeastRecentlyUsedEntryAndKeepsRecentState() {
        val cache = LinkedHashMap<Int, String>(4, 0.75f, true)
        cache[1] = "one"
        cache[2] = "two"
        cache[3] = "three"
        cache[1] // Access makes neuron 1 most recently used.

        AndroidNeuronHotSetCache.trimToCapacity(cache, 2)

        assertEquals(listOf(3, 1), cache.keys.toList())
        assertEquals("one", cache[1])
        assertEquals("three", cache[3])
    }

    @Test
    fun growDoesNotDiscardCachedStates() {
        val cache = LinkedHashMap<Int, String>(4, 0.75f, true)
        cache[7] = "persisted"
        cache[8] = "also-persisted"

        AndroidNeuronHotSetCache.trimToCapacity(cache, 4)

        assertEquals(mapOf(7 to "persisted", 8 to "also-persisted"), cache)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacityIsRejected() {
        AndroidNeuronHotSetCache.trimToCapacity(LinkedHashMap<Int, String>(), 0)
    }
}
