package org.jarvis.ans100.alpha.data

/**
 * Durable cursor for deterministic sparse graph generation.
 *
 * The cursor identifies the next edge, not the last emitted edge. A completed
 * cursor uses routeIndex == routeCount and zero source/slot values.
 */
data class AndroidJnvM250kInitializationCursor(
    val routeIndex: Int,
    val sourceNeuronId: Int,
    val slotIndex: Int,
) {
    init {
        require(routeIndex >= 0)
        require(sourceNeuronId >= 0)
        require(slotIndex >= 0)
    }
}

/** Generator configuration and next-edge cursor persisted atomically with each edge batch. */
data class AndroidJnvM250kInitializationCheckpoint(
    val seed: Long,
    val fanoutPerRoute: Int,
    val delayNs: Long,
    val weight: Double,
    val cursor: AndroidJnvM250kInitializationCursor,
    val completed: Boolean,
)

data class AndroidJnvM250kConnectionBatch(
    val connections: List<AndroidNeuronConnection>,
    val checkpoint: AndroidJnvM250kInitializationCheckpoint,
)

/**
 * Deterministic, bounded-memory initializer for the first 250k sparse graph.
 * Generation is resumable from a small cursor and never materializes the graph.
 */
object AndroidJnvM250kSparseConnectionInitializer {
    const val DEFAULT_BATCH_SIZE = 256
    const val DEFAULT_FANOUT_PER_ROUTE = 1
    const val DEFAULT_DELAY_NS = 1_000_000L
    const val DEFAULT_WEIGHT = 0.5

    private val orderedRoutes = AndroidJnvM250kTopology.ALLOWED_ROUTES.sortedWith(
        compareBy<AndroidJnvM250kTopology.PopulationRoute> { it.source.ordinal }
            .thenBy { it.target.ordinal },
    )

    fun initialCheckpoint(
        seed: Long,
        fanoutPerRoute: Int = DEFAULT_FANOUT_PER_ROUTE,
        delayNs: Long = DEFAULT_DELAY_NS,
        weight: Double = DEFAULT_WEIGHT,
    ): AndroidJnvM250kInitializationCheckpoint {
        validateConfiguration(fanoutPerRoute, delayNs, weight)
        return AndroidJnvM250kInitializationCheckpoint(
            seed = seed,
            fanoutPerRoute = fanoutPerRoute,
            delayNs = delayNs,
            weight = weight,
            cursor = cursorAt(0, 0, 0),
            completed = orderedRoutes.isEmpty(),
        )
    }

    /**
     * Emits at most batchSize edges and returns the cursor for the next edge.
     * The same seed, configuration and cursor always produce the same batch.
     */
    fun nextBatch(
        checkpoint: AndroidJnvM250kInitializationCheckpoint,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): AndroidJnvM250kConnectionBatch {
        require(batchSize in 1..AndroidJnvM250kProfile.MAX_PAGE_SIZE) {
            "connection batch size must be between 1 and ${AndroidJnvM250kProfile.MAX_PAGE_SIZE}"
        }
        validateConfiguration(checkpoint.fanoutPerRoute, checkpoint.delayNs, checkpoint.weight)
        validateCursor(checkpoint.cursor, checkpoint.fanoutPerRoute)
        require(checkpoint.completed == (checkpoint.cursor.routeIndex == orderedRoutes.size)) {
            "checkpoint completion flag does not match its cursor"
        }
        if (checkpoint.completed) return AndroidJnvM250kConnectionBatch(emptyList(), checkpoint)

        val result = ArrayList<AndroidNeuronConnection>(batchSize)
        var cursor = checkpoint.cursor
        while (result.size < batchSize && cursor.routeIndex < orderedRoutes.size) {
            val route = orderedRoutes[cursor.routeIndex]
            val sourceRange = rangeFor(route.source)
            val targetRange = rangeFor(route.target)
            val seedShift = positiveModulo(mix64(checkpoint.seed xor cursor.routeIndex.toLong()), targetRange.size)
            val sourceOffset = cursor.sourceNeuronId - sourceRange.firstNeuronId
            val targetOffset = if (route.source == route.target) {
                val offset = 1 + positiveModulo(seedShift.toLong() + cursor.slotIndex, targetRange.size - 1)
                (sourceOffset + offset) % targetRange.size
            } else {
                positiveModulo(
                    sourceOffset.toLong() * checkpoint.fanoutPerRoute + seedShift + cursor.slotIndex,
                    targetRange.size,
                )
            }
            val targetId = targetRange.firstNeuronId + targetOffset
            AndroidJnvM250kTopology.requireAllowedConnection(cursor.sourceNeuronId, targetId)
            result += AndroidNeuronConnection(
                connectionId = "j250k-${cursor.routeIndex}-${cursor.sourceNeuronId}-${cursor.slotIndex}",
                sourceNeuronId = cursor.sourceNeuronId,
                targetNeuronId = targetId,
                delayNs = checkpoint.delayNs,
                weight = checkpoint.weight,
            )
            cursor = advance(cursor, checkpoint.fanoutPerRoute)
        }
        val nextCheckpoint = checkpoint.copy(
            cursor = cursor,
            completed = cursor.routeIndex == orderedRoutes.size,
        )
        return AndroidJnvM250kConnectionBatch(result, nextCheckpoint)
    }

    /** Compatibility convenience for callers that want a lazy sequence. */
    fun batches(
        seed: Long,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        fanoutPerRoute: Int = DEFAULT_FANOUT_PER_ROUTE,
        delayNs: Long = DEFAULT_DELAY_NS,
        weight: Double = DEFAULT_WEIGHT,
    ): Sequence<List<AndroidNeuronConnection>> = sequence {
        var checkpoint = initialCheckpoint(seed, fanoutPerRoute, delayNs, weight)
        while (!checkpoint.completed) {
            val batch = nextBatch(checkpoint, batchSize)
            if (batch.connections.isNotEmpty()) yield(batch.connections)
            checkpoint = batch.checkpoint
        }
    }

    fun estimatedConnectionCount(fanoutPerRoute: Int = DEFAULT_FANOUT_PER_ROUTE): Long {
        validateConfiguration(fanoutPerRoute, DEFAULT_DELAY_NS, DEFAULT_WEIGHT)
        return orderedRoutes.sumOf { route ->
            rangeFor(route.source).size.toLong() * fanoutPerRoute
        }
    }

    private fun advance(
        cursor: AndroidJnvM250kInitializationCursor,
        fanoutPerRoute: Int,
    ): AndroidJnvM250kInitializationCursor {
        val route = orderedRoutes[cursor.routeIndex]
        val sourceRange = rangeFor(route.source)
        val nextSlot = cursor.slotIndex + 1
        if (nextSlot < fanoutPerRoute) return cursor.copy(slotIndex = nextSlot)
        val nextSource = cursor.sourceNeuronId + 1
        if (nextSource <= sourceRange.lastNeuronIdInclusive) {
            return cursor.copy(sourceNeuronId = nextSource, slotIndex = 0)
        }
        return cursorAt(cursor.routeIndex + 1, 0, 0)
    }

    private fun cursorAt(
        routeIndex: Int,
        sourceNeuronId: Int,
        slotIndex: Int,
    ): AndroidJnvM250kInitializationCursor {
        if (routeIndex == orderedRoutes.size) {
            return AndroidJnvM250kInitializationCursor(routeIndex, 0, 0)
        }
        val first = rangeFor(orderedRoutes[routeIndex].source).firstNeuronId
        return AndroidJnvM250kInitializationCursor(routeIndex, if (sourceNeuronId == 0) first else sourceNeuronId, slotIndex)
    }

    private fun validateCursor(cursor: AndroidJnvM250kInitializationCursor, fanoutPerRoute: Int) {
        require(cursor.routeIndex <= orderedRoutes.size) { "initializer route cursor is out of range" }
        if (cursor.routeIndex == orderedRoutes.size) {
            require(cursor.sourceNeuronId == 0 && cursor.slotIndex == 0) { "completed cursor must be canonical" }
            return
        }
        val sourceRange = rangeFor(orderedRoutes[cursor.routeIndex].source)
        require(cursor.sourceNeuronId in sourceRange.firstNeuronId..sourceRange.lastNeuronIdInclusive) {
            "initializer source cursor is outside the current route"
        }
        require(cursor.slotIndex < fanoutPerRoute) {
            "initializer slot cursor is out of range"
        }
    }

    private fun validateConfiguration(fanoutPerRoute: Int, delayNs: Long, weight: Double) {
        require(fanoutPerRoute in 1..AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON)
        require(delayNs > 0L)
        require(weight.isFinite() && weight in 0.0..1.0)
        validateDegreeBounds(fanoutPerRoute)
    }

    private fun validateDegreeBounds(fanoutPerRoute: Int) {
        for (source in AndroidJnvM250kTopology.Population.values()) {
            val outgoingRoutes = orderedRoutes.count { it.source == source }
            require(outgoingRoutes * fanoutPerRoute <= AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON) {
                "fanout would exceed the per-neuron outgoing-degree limit for $source"
            }
        }
        for (target in AndroidJnvM250kTopology.Population.values()) {
            val targetSize = rangeFor(target).size
            val worstCaseIncoming = orderedRoutes.filter { it.target == target }.sumOf { route ->
                val sourceSize = rangeFor(route.source).size
                (sourceSize.toLong() * fanoutPerRoute + targetSize - 1) / targetSize
            }
            require(worstCaseIncoming <= AndroidJnvM250kProfile.MAX_INCOMING_CONNECTIONS_PER_NEURON) {
                "fanout would exceed the per-neuron incoming-degree limit for $target"
            }
        }
    }

    private fun rangeFor(population: AndroidJnvM250kTopology.Population) =
        AndroidJnvM250kTopology.POPULATION_RANGES.first { it.population == population }

    private fun positiveModulo(value: Long, modulus: Int): Int =
        Math.floorMod(value, modulus.toLong()).toInt()

    /** Fixed SplitMix64 finalizer: stable across Kotlin/JVM Random versions. */
    private fun mix64(input: Long): Long {
        var value = input
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }
}
