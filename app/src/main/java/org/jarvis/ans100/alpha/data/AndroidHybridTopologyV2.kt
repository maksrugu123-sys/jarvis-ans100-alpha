package org.jarvis.ans100.alpha.data

import android.content.Context

/**
 * Deterministic sparse topology for the selected seven-population ANS-100 design.
 *
 * This is a topology/bootstrap contract only. It does not claim semantic learning,
 * useful reasoning, calibrated confidence, or runtime participation by every unit.
 *
 * Version 3 raises feed-forward pulses to the LIF threshold so a single verified
 * upstream spike can cross each declared stage. Recurrent/feedback edges remain
 * subthreshold modulators; they cannot sustain an unbounded spike loop on their own.
 */
object AndroidHybridTopologyV2 {
    const val TOPOLOGY_ID = "ans100-hybrid-v2"
    const val TOPOLOGY_VERSION = 3
    const val DATABASE_NAME = "jarvis_ans100_hybrid_v3.db"
    const val NEURON_COUNT = 10_000
    const val MAX_CONNECTION_COUNT = 100_000
    const val EXPECTED_CONNECTION_COUNT = 27_000
    const val CONNECTION_DELAY_NS = 1_000_000L
    const val FEED_FORWARD_WEIGHT = 1.0
    const val RECURRENT_WEIGHT = 0.18

    data class Population(
        val id: String,
        val startInclusive: Int,
        val endExclusive: Int,
    ) {
        val size: Int get() = endExclusive - startInclusive
        fun contains(neuronId: Int): Boolean = neuronId in startInclusive until endExclusive
    }

    val populations: List<Population> = listOf(
        Population("evidence_input", 0, 1_200),
        Population("association", 1_200, 4_000),
        Population("working_state", 4_000, 5_800),
        Population("candidate", 5_800, 7_300),
        Population("critic_uncertainty", 7_300, 8_500),
        Population("goal_planning", 8_500, 9_400),
        Population("decision_readout", 9_400, 10_000),
    )

    /**
     * Opens a dedicated hybrid V3 SQLite namespace. The existing legacy dialogue DB is
     * never migrated, cleared, or reused by this factory.
     */
    fun open(
        context: Context,
        databaseName: String = DATABASE_NAME,
        hotSetCapacity: Int = AndroidSparseNeuronNetwork.DEFAULT_HOT_SET_CAPACITY,
    ): AndroidSparseNeuronNetwork {
        require(databaseName.matches(Regex("^[A-Za-z0-9._-]{1,128}\\.db$")))
        val network = AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity)
        try {
            network.initializeHybridTopologyV2()
            return network
        } catch (failure: Throwable) {
            network.close()
            throw failure
        }
    }

    /** Returns the same ordered edge list for every call and application process. */
    internal fun connections(): List<AndroidNeuronConnection> = buildList {
        addProjection("evidence-association", populations[0], populations[1], fanOut = 3, salt = 11)
        addProjection("association-recurrent", populations[1], populations[1], fanOut = 1, salt = 29, recurrent = true)
        addProjection("association-working", populations[1], populations[2], fanOut = 2, salt = 43)
        addProjection("working-recurrent", populations[2], populations[2], fanOut = 1, salt = 61, recurrent = true)
        addProjection("working-candidate", populations[2], populations[3], fanOut = 2, salt = 79)
        addProjection("working-goal", populations[2], populations[5], fanOut = 1, salt = 97)
        addProjection("candidate-critic", populations[3], populations[4], fanOut = 2, salt = 113)
        addProjection("critic-candidate", populations[4], populations[3], fanOut = 1, salt = 131, recurrent = true)
        addProjection("candidate-decision", populations[3], populations[6], fanOut = 1, salt = 149)
        addProjection("critic-decision", populations[4], populations[6], fanOut = 1, salt = 167)
        addProjection("goal-decision", populations[5], populations[6], fanOut = 1, salt = 181)
    }.also { edges ->
        require(edges.size == EXPECTED_CONNECTION_COUNT)
        require(edges.size <= MAX_CONNECTION_COUNT)
        require(edges.map { it.connectionId }.toSet().size == edges.size)
    }

    private fun MutableList<AndroidNeuronConnection>.addProjection(
        kind: String,
        source: Population,
        target: Population,
        fanOut: Int,
        salt: Int,
        recurrent: Boolean = false,
    ) {
        require(fanOut in 1..8)
        for (sourceOffset in 0 until source.size) {
            val sourceId = source.startInclusive + sourceOffset
            val usedTargets = HashSet<Int>(fanOut)
            for (slot in 0 until fanOut) {
                var targetOffset = (sourceOffset.toLong() * 37L + slot.toLong() * 113L + salt) % target.size
                var targetId = target.startInclusive + targetOffset.toInt()
                if (recurrent && targetId == sourceId) {
                    targetOffset = (targetOffset + 1L) % target.size
                    targetId = target.startInclusive + targetOffset.toInt()
                }
                while (!usedTargets.add(targetId)) {
                    targetOffset = (targetOffset + 1L) % target.size
                    targetId = target.startInclusive + targetOffset.toInt()
                }
                add(
                    AndroidNeuronConnection(
                        connectionId = "ans100-v2-$kind-$sourceId-$targetId",
                        sourceNeuronId = sourceId,
                        targetNeuronId = targetId,
                        delayNs = CONNECTION_DELAY_NS,
                        weight = if (recurrent) RECURRENT_WEIGHT else FEED_FORWARD_WEIGHT,
                    ),
                )
            }
        }
    }
}
