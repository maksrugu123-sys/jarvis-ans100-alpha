package org.jarvis.ans100.alpha.data

/**
 * Capacity contract for the independent 250k JNVM storage profile.
 *
 * This profile is deliberately separate from the legacy 10k dialogue and
 * hybrid topologies. It defines disk-backed structural capacity, not a
 * promise that all neuron objects or edges are resident in memory.
 */
object AndroidJnvM250kProfile {
    const val PROFILE_ID = "ans100-jnvm-sparse-250k"
    const val PROFILE_VERSION = 1
    const val DATABASE_NAME = "jarvis_ans100_jnvm_sparse_250k_v1.db"

    const val NEURON_CAPACITY = 250_000
    const val MAX_OUTGOING_CONNECTIONS_PER_NEURON = 16
    const val MAX_INCOMING_CONNECTIONS_PER_NEURON = 32

    /** Bounded keyset page sizes; callers cannot request an unbounded read. */
    const val DEFAULT_PAGE_SIZE = 128
    const val MAX_PAGE_SIZE = 512
    const val INITIALIZATION_BATCH_SIZE = 1_000

    /** Disk-backed queue limits; each timestamp remains one atomic unit. */
    const val MAX_PENDING_EVENTS = 100_000
    const val MAX_EVENTS_PER_TIMESTAMP = 512
    const val MAX_EVENTS_PER_ENQUEUE = 512
    const val MAX_GENERATED_EVENTS_PER_TIMESTAMP =
        MAX_EVENTS_PER_TIMESTAMP * MAX_OUTGOING_CONNECTIONS_PER_NEURON

    fun requireNeuronId(neuronId: Int) {
        require(neuronId in 0 until NEURON_CAPACITY) {
            "neuron ID is outside the 250,000-neuron profile"
        }
    }

    fun requirePageSize(limit: Int) {
        require(limit in 1..MAX_PAGE_SIZE) {
            "page size must be between 1 and $MAX_PAGE_SIZE"
        }
    }

    fun requireDatabaseName(name: String) {
        require(name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.db$"))) {
            "invalid JNVM database name"
        }
    }
}
