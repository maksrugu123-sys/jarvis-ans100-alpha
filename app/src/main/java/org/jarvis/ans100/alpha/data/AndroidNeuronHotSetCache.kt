package org.jarvis.ans100.alpha.data

import java.util.LinkedHashMap

/**
 * Bounded access-order cache trimming shared by core and feature-state JNVM
 * working sets. Values are expected to be clean, durably committed snapshots.
 */
internal object AndroidNeuronHotSetCache {
    fun <T> trimToCapacity(cache: LinkedHashMap<Int, T>, capacity: Int) {
        require(capacity >= 1)
        while (cache.size > capacity) {
            val eldest = cache.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }
}
