package org.jarvis.ans100.alpha.data

import android.content.Context
import java.io.Closeable
import java.util.LinkedHashMap

/**
 * Bounded RAM working set for the independent 250k JNVM store.
 *
 * SQLite remains authoritative. Reads populate an access-order LRU cache;
 * writes go to SQLite before the cache is published. Timestamp commits are
 * delegated atomically to the store, then committed snapshots are copied into
 * the cache. No dirty state is ever evicted.
 *
 * All runtime state access must pass through this class. Direct writes to the
 * underlying store while this working set is alive can make cached values stale.
 */
class AndroidJnvM250kWorkingSet(
    context: Context,
    private val store: AndroidJnvM250kStore,
) : Closeable {
    private val appContext = context.applicationContext
    private val ramBudget = AndroidNeuronRamBudgetPreferences(appContext)
    private val hot = LinkedHashMap<Int, AndroidNeuronState>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var hotSetCapacity = ramBudget.loadHotSetCapacity()

    init {
        require(hotSetCapacity in 1..AndroidNeuronRamBudgetPreferences.MAX_HOT_SET_CAPACITY)
    }

    /** Current number of resident neuron states. */
    @Synchronized
    fun residentCount(): Int = hot.size

    /** Current bounded cache capacity, refreshed against device RAM. */
    @Synchronized
    fun capacity(): Int {
        refreshRamBudgetInternal()
        return hotSetCapacity
    }

    /**
     * Re-inspects current device memory and trims least-recently-used clean
     * snapshots if the configured budget has fallen.
     */
    @Synchronized
    fun refreshRamBudget(): Int = refreshRamBudgetInternal()

    /** Returns one state, loading only that row on an LRU miss. */
    @Synchronized
    fun state(neuronId: Int): AndroidNeuronState {
        AndroidJnvM250kProfile.requireNeuronId(neuronId)
        refreshRamBudgetInternal()
        hot[neuronId]?.let { return it }
        val loaded = store.loadNeuronPage(
            afterNeuronId = neuronId - 1,
            limit = 1,
        ).firstOrNull { it.neuronId == neuronId }
            ?: throw AndroidNeuronNetworkException("250k JNVM neuron state is missing")
        hot[neuronId] = loaded
        trimToCapacity()
        return loaded
    }

    /**
     * Write-through state update. SQLite commits before the resident snapshot
     * is inserted, so eviction never discards the only copy of a change.
     */
    @Synchronized
    fun updateState(state: AndroidNeuronState) {
        AndroidJnvM250kProfile.requireNeuronId(state.neuronId)
        refreshRamBudgetInternal()
        store.updateNeuron(state)
        hot[state.neuronId] = state
        trimToCapacity()
    }

    /**
     * Atomically commits a full timestamp in SQLite. Cache publication happens
     * only after the database transaction succeeds.
     */
    @Synchronized
    fun commitTimestamp(
        timestampNs: Long,
        consumed: List<AndroidNeuronEvent>,
        generated: List<AndroidNeuronEvent>,
        changedStates: Collection<AndroidNeuronState>,
    ) {
        refreshRamBudgetInternal()
        store.commitTimestamp(timestampNs, consumed, generated, changedStates)
        changedStates.forEach { hot[it.neuronId] = it }
        trimToCapacity()
    }

    /** Bounded page read for diagnostics; it does not populate the hot set. */
    @Synchronized
    fun page(
        afterNeuronId: Int = -1,
        limit: Int = AndroidJnvM250kProfile.DEFAULT_PAGE_SIZE,
    ): List<AndroidNeuronState> = store.loadNeuronPage(afterNeuronId, limit)

    @Synchronized
    private fun refreshRamBudgetInternal(): Int {
        hotSetCapacity = ramBudget.loadHotSetCapacity()
        trimToCapacity()
        return hotSetCapacity
    }

    private fun trimToCapacity() {
        AndroidNeuronHotSetCache.trimToCapacity(hot, hotSetCapacity)
    }

    override fun close() {
        synchronized(this) {
            hot.clear()
        }
        // Store ownership remains with the caller; this wrapper does not close it.
    }

    companion object {
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f

        /** Exposed for deterministic policy tests; this is a planning estimate. */
        fun capacityForBudgetMb(megabytes: Int): Int =
            AndroidNeuronRamBudgetPreferences.hotSetCapacityFor(megabytes)
    }
}
