package org.jarvis.ans100.alpha.data

/**
 * Deterministic, bounded-memory initializer for the first 250k sparse graph.
 *
 * It emits edges lazily in stable route/source/slot order. It never builds a
 * 250k-sized adjacency map or a complete edge list. A caller should persist
 * each returned batch and resume from the last completed batch boundary.
 *
 * The default one-edge-per-route policy is intentionally conservative. It is
 * a reproducible starting graph, not a learned or scientifically optimized
 * connectivity pattern.
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

    /**
     * Produces deterministic batches. Repeating the call with identical
     * arguments yields identical connection IDs, endpoints, delays and weights.
     */
    fun batches(
        seed: Long,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        fanoutPerRoute: Int = DEFAULT_FANOUT_PER_ROUTE,
        delayNs: Long = DEFAULT_DELAY_NS,
        weight: Double = DEFAULT_WEIGHT,
    ): Sequence<List<AndroidNeuronConnection>> {
        require(batchSize in 1..AndroidJnvM250kProfile.MAX_PAGE_SIZE) {
            "connection batch size must be between 1 and ${AndroidJnvM250kProfile.MAX_PAGE_SIZE}"
        }
        require(fanoutPerRoute in 1..AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON)
        require(delayNs > 0L)
        require(weight.isFinite() && weight in 0.0..1.0)
        validateDegreeBounds(fanoutPerRoute)

        return sequence {
            val batch = ArrayList<AndroidNeuronConnection>(batchSize)
            for ((routeIndex, route) in orderedRoutes.withIndex()) {
                val sourceRange = rangeFor(route.source)
                val targetRange = rangeFor(route.target)
                val seedShift = positiveModulo(mix64(seed xor routeIndex.toLong()), targetRange.size)
                for (sourceId in sourceRange.firstNeuronId..sourceRange.lastNeuronIdInclusive) {
                    val sourceOffset = sourceId - sourceRange.firstNeuronId
                    for (slot in 0 until fanoutPerRoute) {
                        val targetOffset = if (route.source == route.target) {
                            // A non-zero cyclic offset prevents self-edges and
                            // gives distinct targets for all allowed fanout slots.
                            val offset = 1 + positiveModulo(seedShift.toLong() + slot, targetRange.size - 1)
                            (sourceOffset + offset) % targetRange.size
                        } else {
                            positiveModulo(
                                sourceOffset.toLong() * fanoutPerRoute + seedShift + slot,
                                targetRange.size,
                            )
                        }
                        val targetId = targetRange.firstNeuronId + targetOffset
                        val connection = AndroidNeuronConnection(
                            connectionId = "j250k-${routeIndex}-${sourceId}-${slot}",
                            sourceNeuronId = sourceId,
                            targetNeuronId = targetId,
                            delayNs = delayNs,
                            weight = weight,
                        )
                        AndroidJnvM250kTopology.requireAllowedConnection(sourceId, targetId)
                        batch += connection
                        if (batch.size == batchSize) {
                            yield(batch.toList())
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) yield(batch.toList())
        }
    }

    /** Returns the exact number of edges without materializing them. */
    fun estimatedConnectionCount(fanoutPerRoute: Int = DEFAULT_FANOUT_PER_ROUTE): Long {
        require(fanoutPerRoute in 1..AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON)
        validateDegreeBounds(fanoutPerRoute)
        return orderedRoutes.sumOf { route ->
            rangeFor(route.source).size.toLong() * fanoutPerRoute
        }
    }

    private fun validateDegreeBounds(fanoutPerRoute: Int) {
        for (source in AndroidJnvM250kTopology.Population.values()) {
            val outgoingRoutes = orderedRoutes.filter { it.source == source }.size
            require(outgoingRoutes * fanoutPerRoute <= AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON) {
                "fanout would exceed the per-neuron outgoing-degree limit for $source"
            }
        }

        for (target in AndroidJnvM250kTopology.Population.values()) {
            val targetSize = rangeFor(target).size
            val worstCaseIncoming = orderedRoutes
                .filter { it.target == target }
                .sumOf { route ->
                    val sourceSize = rangeFor(route.source).size
                    ((sourceSize.toLong() * fanoutPerRoute + targetSize - 1) / targetSize)
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
