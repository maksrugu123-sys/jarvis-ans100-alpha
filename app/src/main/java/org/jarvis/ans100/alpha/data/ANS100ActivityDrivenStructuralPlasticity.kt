package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Experimental activity-driven structural plasticity for concept assemblies.
 * No concept-pair catalogue is embedded: a pair becomes eligible only after
 * repeated co-activation in distinct turns. A shared target is then selected
 * by bounded least-loaded competition in the association population.
 *
 * The caller must persist/apply the returned synapses. V1 is deliberately not
 * wired into live answer selection.
 */
class ANS100ActivityDrivenStructuralPlasticity(
    private val minDistinctTurns: Int = DEFAULT_MIN_DISTINCT_TURNS,
    private val maxTrackedPairs: Int = DEFAULT_MAX_TRACKED_PAIRS,
    private val maxGrownPairs: Int = DEFAULT_MAX_GROWN_PAIRS,
    private val maxGrownEdgesPerConcept: Int = DEFAULT_MAX_GROWN_EDGES_PER_CONCEPT,
    private val maxIncomingEdgesPerTarget: Int = DEFAULT_MAX_INCOMING_EDGES_PER_TARGET,
    private val observationWindowTurns: Long = DEFAULT_OBSERVATION_WINDOW_TURNS,
    private val initialWeight: Double = DEFAULT_INITIAL_WEIGHT,
) {
    data class ActiveConcept(
        val conceptId: String,
        val neuronId: Int,
        val label: String,
        val provenanceRef: String,
    ) {
        init {
            require(conceptId.matches(Regex("^[a-z][a-z0-9_.:-]{0,95}$")))
            require(neuronId in CONCEPT_INPUT_START until CONCEPT_INPUT_END)
            require(label.isNotBlank() && label.length <= ANS100ConceptMemory.MAX_LABEL_CHARS)
            require(provenanceRef.isNotBlank() && provenanceRef.length <= TypedEvidenceRecord.MAX_PROVENANCE_CHARS)
        }
    }

    data class GrowthProposal(
        val proposalId: String,
        val firstConceptId: String,
        val firstLabel: String,
        val firstNeuronId: Int,
        val secondConceptId: String,
        val secondLabel: String,
        val secondNeuronId: Int,
        val targetNeuronId: Int,
        val supportingDistinctTurns: Int,
        val triggerTurnId: String,
        val evidenceRefs: List<String>,
        val connections: List<AndroidNeuronConnection>,
    ) {
        init {
            require(proposalId.matches(Regex("^ans100-growth:[a-f0-9]{32}$")))
            require(firstConceptId < secondConceptId)
            require(firstNeuronId != secondNeuronId)
            require(targetNeuronId in ASSOCIATION_START until ASSOCIATION_END)
            require(supportingDistinctTurns >= 1)
            require(triggerTurnId.isNotBlank())
            require(evidenceRefs.size == 2 && evidenceRefs.all { it.isNotBlank() })
            require(connections.size == 2)
            require(connections.map { it.sourceNeuronId }.toSet() == setOf(firstNeuronId, secondNeuronId))
            require(connections.all { it.targetNeuronId == targetNeuronId })
            require(connections.map { it.connectionId }.distinct().size == 2)
        }
    }

    data class ObservationResult(
        val acceptedTurn: Boolean,
        val consideredPairCount: Int,
        val pendingPairCount: Int,
        val emittedProposals: List<GrowthProposal>,
        val capacityBlockedPairCount: Int,
    )

    data class TurnSnapshot(
        val turnId: String,
        val activeConcepts: List<ActiveConcept>,
    ) {
        init {
            require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
            require(activeConcepts.size <= MAX_ACTIVE_CONCEPTS_PER_TURN)
            require(activeConcepts.map { it.conceptId }.distinct().size == activeConcepts.size)
            require(activeConcepts.map { it.neuronId }.distinct().size == activeConcepts.size)
        }
    }

    data class GraphSnapshot(
        val existingTargetsByConceptNeuron: Map<Int, Set<Int>>,
        val targetIncomingEdgeCounts: Map<Int, Int>,
        val grownConceptPairs: Set<Pair<String, String>>,
        val grownEdgesByConcept: Map<String, Int>,
    )

    private data class PairKey(val firstId: String, val secondId: String) {
        val stableText: String get() = "${firstId}|${secondId}"
    }

    private data class PairTrace(
        var first: ActiveConcept,
        var second: ActiveConcept,
        val seenTurns: LinkedHashSet<String> = LinkedHashSet(),
        var lastSequence: Long = 0L,
    )

    private val traces = LinkedHashMap<PairKey, PairTrace>()
    private val processedTurns = LinkedHashSet<String>()
    private val grownPairs = LinkedHashSet<PairKey>()
    private val grownEdgesByConcept = HashMap<String, Int>()
    private var sequence = 0L

    init {
        require(minDistinctTurns in 2..16)
        require(maxTrackedPairs in 1..MAX_TRACKED_PAIRS_HARD_LIMIT)
        require(maxGrownPairs in 1..MAX_GROWN_PAIRS_HARD_LIMIT)
        require(maxGrownEdgesPerConcept in 1..MAX_GROWN_EDGES_PER_CONCEPT_HARD_LIMIT)
        require(maxIncomingEdgesPerTarget in 2..MAX_INCOMING_EDGES_PER_TARGET_HARD_LIMIT)
        require(observationWindowTurns in minDistinctTurns.toLong()..MAX_OBSERVATION_WINDOW_TURNS)
        require(initialWeight.isFinite() && initialWeight in MIN_INITIAL_WEIGHT..MAX_INITIAL_WEIGHT)
    }

    /**
     * Observe one completed neural turn. Replaying a turnId is idempotently
     * ignored. Existing shared targets are supplied from the current graph so
     * growth is attempted only for pairs the graph cannot already associate.
     * Target incoming counts must include fixed and previously grown synapses.
     */
    @Synchronized
    fun observeTurn(
        turnId: String,
        activeConcepts: List<ActiveConcept>,
        existingTargetsByConceptNeuron: Map<Int, Set<Int>>,
        targetIncomingEdgeCounts: Map<Int, Int>,
    ): ObservationResult {
        require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(activeConcepts.size <= MAX_ACTIVE_CONCEPTS_PER_TURN)
        require(activeConcepts.map { it.conceptId }.distinct().size == activeConcepts.size)
        require(activeConcepts.map { it.neuronId }.distinct().size == activeConcepts.size)
        require(existingTargetsByConceptNeuron.keys.all { it in CONCEPT_INPUT_START until CONCEPT_INPUT_END })
        require(existingTargetsByConceptNeuron.values.flatten().all { it in ASSOCIATION_START until ASSOCIATION_END })
        require(targetIncomingEdgeCounts.all { (target, count) ->
            target in ASSOCIATION_START until ASSOCIATION_END && count >= 0
        })

        if (!processedTurns.add(turnId)) {
            return ObservationResult(false, 0, pendingCount(), emptyList(), 0)
        }
        while (processedTurns.size > MAX_REMEMBERED_TURN_IDS) {
            processedTurns.remove(processedTurns.first())
        }
        if (sequence == Long.MAX_VALUE) {
            throw IllegalStateException("structural-plasticity observation sequence exhausted")
        }
        sequence++

        val ordered = activeConcepts.sortedBy { it.conceptId }
        val localLoads = HashMap(targetIncomingEdgeCounts)
        val localTargets = existingTargetsByConceptNeuron.mapValues { it.value.toMutableSet() }.toMutableMap()
        var considered = 0
        var blocked = 0
        val proposals = ArrayList<GrowthProposal>()

        for (leftIndex in ordered.indices) {
            for (rightIndex in leftIndex + 1 until ordered.size) {
                considered++
                val left = ordered[leftIndex]
                val right = ordered[rightIndex]
                val leftTargets = localTargets[left.neuronId].orEmpty()
                val rightTargets = localTargets[right.neuronId].orEmpty()
                if (leftTargets.intersect(rightTargets).isNotEmpty()) continue

                val key = PairKey(left.conceptId, right.conceptId)
                if (key in grownPairs) continue
                val trace = traces.getOrPut(key) { PairTrace(left, right) }
                if (sequence - trace.lastSequence > observationWindowTurns) trace.seenTurns.clear()
                trace.first = left
                trace.second = right
                trace.lastSequence = sequence
                if (trace.seenTurns.add(turnId)) {
                    while (trace.seenTurns.size > minDistinctTurns) {
                        trace.seenTurns.remove(trace.seenTurns.first())
                    }
                }
                if (trace.seenTurns.size < minDistinctTurns) continue

                if (grownPairs.size >= maxGrownPairs ||
                    (grownEdgesByConcept[left.conceptId] ?: 0) + 1 > maxGrownEdgesPerConcept ||
                    (grownEdgesByConcept[right.conceptId] ?: 0) + 1 > maxGrownEdgesPerConcept
                ) {
                    blocked++
                    continue
                }

                val excludedTargets = leftTargets + rightTargets
                val target = selectLeastLoadedTarget(key, localLoads, excludedTargets)
                if (target == null) {
                    blocked++
                    continue
                }
                proposals += createProposal(trace, target, turnId)
                localTargets.getOrPut(left.neuronId) { LinkedHashSet() }.add(target)
                localTargets.getOrPut(right.neuronId) { LinkedHashSet() }.add(target)
                localLoads[target] = (localLoads[target] ?: 0) + 2
                grownPairs += key
                grownEdgesByConcept[left.conceptId] = (grownEdgesByConcept[left.conceptId] ?: 0) + 1
                grownEdgesByConcept[right.conceptId] = (grownEdgesByConcept[right.conceptId] ?: 0) + 1
                traces.remove(key)
            }
        }

        pruneTraces()
        return ObservationResult(true, considered, pendingCount(), proposals, blocked)
    }

    /** Restore durable growth limits before replaying persisted observations. */
    @Synchronized
    fun restoreGrownState(
        grownConceptPairs: Set<Pair<String, String>>,
        grownEdgesByConcept: Map<String, Int>,
    ) {
        require(grownConceptPairs.size <= MAX_GROWN_PAIRS_HARD_LIMIT)
        require(grownConceptPairs.all { (first, second) -> first.isNotBlank() && first < second })
        require(grownEdgesByConcept.values.all { it in 0..MAX_GROWN_EDGES_PER_CONCEPT_HARD_LIMIT })
        grownPairs.clear()
        grownPairs.addAll(grownConceptPairs.map { PairKey(it.first, it.second) })
        this.grownEdgesByConcept.clear()
        this.grownEdgesByConcept.putAll(grownEdgesByConcept)
        require(this.grownEdgesByConcept.values.sum() == grownPairs.size * 2)
    }

    @Synchronized
    fun pendingCount(): Int = traces.size

    @Synchronized
    fun grownPairCount(): Int = grownPairs.size

    private fun selectLeastLoadedTarget(
        key: PairKey,
        loads: Map<Int, Int>,
        excludedTargets: Set<Int>,
    ): Int? {
        val start = digest(key.stableText).take(8).toLong(16).rem(ASSOCIATION_SIZE).toInt()
        var bestTarget: Int? = null
        var bestLoad = Int.MAX_VALUE
        for (offset in 0 until ASSOCIATION_SIZE) {
            val target = ASSOCIATION_START + ((start + offset) % ASSOCIATION_SIZE)
            if (target in excludedTargets) continue
            val load = loads[target] ?: 0
            if (load < maxIncomingEdgesPerTarget && load < bestLoad) {
                bestTarget = target
                bestLoad = load
                if (load == 0) break
            }
        }
        return bestTarget
    }

    private fun createProposal(trace: PairTrace, target: Int, turnId: String): GrowthProposal {
        val first = trace.first
        val second = trace.second
        val pairDigest = digest("${first.conceptId}|${second.conceptId}")
        val proposalId = "ans100-growth:${pairDigest}"
        val connections = listOf(first, second).map { concept ->
            AndroidNeuronConnection(
                connectionId = "ans100-v5-grown-association-${pairDigest}-${concept.neuronId}-${target}",
                sourceNeuronId = concept.neuronId,
                targetNeuronId = target,
                delayNs = GROWN_CONNECTION_DELAY_NS,
                weight = initialWeight,
            )
        }
        return GrowthProposal(
            proposalId = proposalId,
            firstConceptId = first.conceptId,
            firstLabel = first.label,
            firstNeuronId = first.neuronId,
            secondConceptId = second.conceptId,
            secondLabel = second.label,
            secondNeuronId = second.neuronId,
            targetNeuronId = target,
            supportingDistinctTurns = trace.seenTurns.size,
            triggerTurnId = turnId,
            evidenceRefs = listOf(first.provenanceRef, second.provenanceRef),
            connections = connections,
        )
    }

    private fun pruneTraces() {
        while (traces.size > maxTrackedPairs) {
            val oldest = traces.entries.minByOrNull { it.value.lastSequence } ?: break
            traces.remove(oldest.key)
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val CONCEPT_INPUT_START = 1_062
        const val CONCEPT_INPUT_END = 1_200
        const val ASSOCIATION_START = 1_200
        const val ASSOCIATION_END = 2_200
        const val ASSOCIATION_SIZE = ASSOCIATION_END - ASSOCIATION_START
        const val MAX_ACTIVE_CONCEPTS_PER_TURN = 64
        const val MAX_REMEMBERED_TURN_IDS = 256
        const val MAX_TRACKED_PAIRS_HARD_LIMIT = 4_096
        const val MAX_GROWN_PAIRS_HARD_LIMIT = 512
        const val MAX_GROWN_EDGES_PER_CONCEPT_HARD_LIMIT = 8
        const val MAX_INCOMING_EDGES_PER_TARGET_HARD_LIMIT = 16
        const val MAX_OBSERVATION_WINDOW_TURNS = 64L
        const val DEFAULT_MIN_DISTINCT_TURNS = 3
        const val DEFAULT_MAX_TRACKED_PAIRS = 2_048
        const val DEFAULT_MAX_GROWN_PAIRS = 256
        const val DEFAULT_MAX_GROWN_EDGES_PER_CONCEPT = 4
        const val DEFAULT_MAX_INCOMING_EDGES_PER_TARGET = 12
        const val DEFAULT_OBSERVATION_WINDOW_TURNS = 16L
        const val DEFAULT_INITIAL_WEIGHT = 0.25
        const val MIN_INITIAL_WEIGHT = 0.05
        const val MAX_INITIAL_WEIGHT = 0.50
        const val GROWN_CONNECTION_DELAY_NS = 1_000_000L
    }
}
