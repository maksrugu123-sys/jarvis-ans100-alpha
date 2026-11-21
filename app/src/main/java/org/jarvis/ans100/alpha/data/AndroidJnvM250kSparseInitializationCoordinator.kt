package org.jarvis.ans100.alpha.data

/**
 * Executes one bounded initialization step. Invoke from a worker and schedule
 * another step only while complete == false; each step commits edges and its
 * next cursor atomically, so process death cannot advance only one of them.
 */
class AndroidJnvM250kSparseInitializationCoordinator(
    private val store: AndroidJnvM250kStore,
) {
    data class StepResult(
        val edgesCommitted: Int,
        val totalPersistedConnections: Int,
        val estimatedTargetConnections: Long,
        val complete: Boolean,
        val checkpoint: AndroidJnvM250kInitializationCheckpoint,
    )

    @Synchronized
    fun runNextBatch(
        seed: Long,
        batchSize: Int = AndroidJnvM250kSparseConnectionInitializer.DEFAULT_BATCH_SIZE,
        fanoutPerRoute: Int = AndroidJnvM250kSparseConnectionInitializer.DEFAULT_FANOUT_PER_ROUTE,
        delayNs: Long = AndroidJnvM250kSparseConnectionInitializer.DEFAULT_DELAY_NS,
        weight: Double = AndroidJnvM250kSparseConnectionInitializer.DEFAULT_WEIGHT,
    ): StepResult {
        val persisted = store.loadConnectionInitializationCheckpoint()
        val current = persisted ?: AndroidJnvM250kSparseConnectionInitializer.initialCheckpoint(
            seed = seed,
            fanoutPerRoute = fanoutPerRoute,
            delayNs = delayNs,
            weight = weight,
        )
        require(
            current.seed == seed &&
                current.fanoutPerRoute == fanoutPerRoute &&
                current.delayNs == delayNs &&
                current.weight == weight
        ) {
            "requested sparse graph configuration differs from the persisted initialization"
        }

        if (current.completed) {
            val stats = store.stats()
            return StepResult(
                edgesCommitted = 0,
                totalPersistedConnections = stats.connectionCount,
                estimatedTargetConnections = AndroidJnvM250kSparseConnectionInitializer
                    .estimatedConnectionCount(fanoutPerRoute),
                complete = true,
                checkpoint = current,
            )
        }

        val batch = AndroidJnvM250kSparseConnectionInitializer.nextBatch(current, batchSize)
        check(batch.connections.isNotEmpty()) { "initializer produced an empty non-terminal batch" }
        store.persistConnectionBatch(
            connections = batch.connections,
            expectedCheckpoint = persisted,
            nextCheckpoint = batch.checkpoint,
        )
        val stats = store.stats()
        return StepResult(
            edgesCommitted = batch.connections.size,
            totalPersistedConnections = stats.connectionCount,
            estimatedTargetConnections = AndroidJnvM250kSparseConnectionInitializer
                .estimatedConnectionCount(fanoutPerRoute),
            complete = batch.checkpoint.completed,
            checkpoint = batch.checkpoint,
        )
    }

    fun currentCheckpoint(): AndroidJnvM250kInitializationCheckpoint? =
        store.loadConnectionInitializationCheckpoint()
}
