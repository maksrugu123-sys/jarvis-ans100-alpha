package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Decodes bounded concept co-activation from the actual ANS-100 association
 * population. A pair is emitted only when both concept-to-association synapses
 * are recorded as activated and their shared target actually spiked.
 *
 * This is a neural co-activation candidate, not a typed relation, semantic
 * interpretation, causal claim, learned probability, or verified fact.
 */
class ANS100NeuralAssociationDecoder {
    data class Candidate(
        val candidateId: String,
        val firstConceptId: String,
        val firstLabel: String,
        val secondConceptId: String,
        val secondLabel: String,
        val convergentNeuronIds: List<Int>,
        val activatedConnectionIds: List<String>,
        val evidenceRefs: List<String>,
        /** Shared spiking targets / three projection slots; not a probability. */
        val activationHint: Double,
    ) {
        init {
            require(candidateId.matches(Regex("^neural-association:[a-f0-9]{32}$")))
            require(firstConceptId.isNotBlank() && secondConceptId.isNotBlank())
            require(firstConceptId != secondConceptId)
            require(firstLabel.isNotBlank() && firstLabel.length <= ANS100ConceptMemory.MAX_LABEL_CHARS)
            require(secondLabel.isNotBlank() && secondLabel.length <= ANS100ConceptMemory.MAX_LABEL_CHARS)
            require(convergentNeuronIds.isNotEmpty() && convergentNeuronIds.size <= MAX_CONVERGENT_TARGETS)
            require(convergentNeuronIds.distinct().size == convergentNeuronIds.size)
            require(convergentNeuronIds.all {
                it in GENERAL_ASSOCIATION_START until GENERAL_ASSOCIATION_END
            })
            require(activatedConnectionIds.size in 2..MAX_ACTIVATED_CONNECTIONS)
            require(activatedConnectionIds.distinct().size == activatedConnectionIds.size)
            require(activatedConnectionIds.all {
                (it.startsWith("ans100-v4-evidence-general-association-") || it.startsWith("ans100-v5-grown-association-")) && it.length <= 128
            })
            require(evidenceRefs.size in 2..5 && evidenceRefs.distinct().size == evidenceRefs.size)
            require(evidenceRefs.all { it.isNotBlank() && it.length <= TypedEvidenceRecord.MAX_PROVENANCE_CHARS })
            require(activationHint.isFinite() && activationHint in 0.0..1.0)
        }
    }

    data class Result(
        val activeConceptCount: Int,
        val associationSpikeCount: Int,
        val consideredPairCount: Int,
        val candidates: List<Candidate>,
    ) {
        init {
            require(activeConceptCount >= 0)
            require(associationSpikeCount >= 0)
            require(consideredPairCount >= 0)
            require(candidates.size <= MAX_CANDIDATES)
            require(candidates.map { it.candidateId }.distinct().size == candidates.size)
        }
    }

    private data class ActiveConcept(
        val trace: ANS100ConceptNeuralInputAdapter.TraceEntry,
        val projection: Map<Int, String>,
        val spikingTargets: Set<Int>,
    )

    fun decode(
        conceptTrace: List<ANS100ConceptNeuralInputAdapter.TraceEntry>,
        emittedSpikeNeuronIds: Set<Int>,
        activatedConnectionIds: Set<String>,
        maxCandidates: Int = MAX_CANDIDATES,
        grownConnections: List<AndroidNeuronConnection> = emptyList(),
    ): Result {
        require(conceptTrace.size <= ANS100ConceptNeuralInputAdapter.MAX_CONCEPTS_PER_TURN)
        require(maxCandidates in 0..MAX_CANDIDATES)
        require(conceptTrace.map { it.conceptId }.distinct().size == conceptTrace.size) {
            "concept IDs must be unique within a neural input frame"
        }
        require(conceptTrace.map { it.neuronId }.distinct().size == conceptTrace.size) {
            "concept input neurons must be unique within a neural input frame"
        }
        require(conceptTrace.all {
            it.conceptId.isNotBlank() &&
                it.neuronId in ANS100ConceptMemory.NEURAL_INPUT_START until
                    ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS &&
                it.matchedAlias.isNotBlank() &&
                it.matchedAlias.length <= ANS100ConceptMemory.MAX_LABEL_CHARS &&
                it.provenanceRef.isNotBlank()
        })
        require(emittedSpikeNeuronIds.size <= AndroidHybridTopologyV2.NEURON_COUNT)
        require(emittedSpikeNeuronIds.all { it in 0 until AndroidHybridTopologyV2.NEURON_COUNT })
        require(activatedConnectionIds.size <= AndroidHybridTopologyV2.MAX_CONNECTION_COUNT)
        require(grownConnections.size <= conceptTrace.size * 8)
        require(grownConnections.all {
            it.sourceNeuronId in ANS100ConceptMemory.NEURAL_INPUT_START until
                ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS &&
            it.targetNeuronId in GENERAL_ASSOCIATION_START until GENERAL_ASSOCIATION_END &&
            it.connectionId.startsWith("ans100-v5-grown-association-")
        })
        val generalActivatedConnections = activatedConnectionIds.filterTo(LinkedHashSet()) {
            it.startsWith("ans100-v4-evidence-general-association-")
        }
        require(generalActivatedConnections.all { it.length <= 128 })

        val associationSpikes = emittedSpikeNeuronIds.filterTo(LinkedHashSet()) {
            it in GENERAL_ASSOCIATION_START until GENERAL_ASSOCIATION_END
        }
        val grownBySource = grownConnections.groupBy { it.sourceNeuronId }
        val active = conceptTrace.sortedBy { it.conceptId }.mapNotNull { trace ->
            val projection = AndroidHybridTopologyV2.conceptAssociationProjection(trace.neuronId).toMutableMap()
            grownBySource[trace.neuronId].orEmpty().forEach { connection ->
                projection[connection.targetNeuronId] = connection.connectionId
            }
            val spiking = projection.filter { (targetId, connectionId) ->
                targetId in associationSpikes && connectionId in activatedConnectionIds
            }.keys
            if (spiking.isEmpty()) null else ActiveConcept(trace, projection, spiking)
        }

        val candidates = ArrayList<Candidate>()
        var consideredPairs = 0
        for (leftIndex in active.indices) {
            for (rightIndex in leftIndex + 1 until active.size) {
                consideredPairs++
                val left = active[leftIndex]
                val right = active[rightIndex]
                // Both source synapses must have reached the same spiking target.
                val convergent = left.spikingTargets.intersect(right.spikingTargets).sorted().take(MAX_CONVERGENT_TARGETS)
                if (convergent.isEmpty()) continue

                val activatedSynapses = convergent.flatMap { targetId ->
                    listOf(left.projection.getValue(targetId), right.projection.getValue(targetId))
                }.distinct().sorted()
                val refs = buildList {
                    add(left.trace.provenanceRef.substringBefore(":features:").take(160))
                    add(right.trace.provenanceRef.substringBefore(":features:").take(160))
                    convergent.forEach { add("ans100-neuron:$it") }
                }.distinct().take(5)
                val digestInput = listOf(
                    left.trace.conceptId,
                    right.trace.conceptId,
                    convergent.joinToString(","),
                ).joinToString("|")
                candidates += Candidate(
                    candidateId = "neural-association:${digest(digestInput)}",
                    firstConceptId = left.trace.conceptId,
                    firstLabel = left.trace.matchedAlias,
                    secondConceptId = right.trace.conceptId,
                    secondLabel = right.trace.matchedAlias,
                    convergentNeuronIds = convergent,
                    activatedConnectionIds = activatedSynapses,
                    evidenceRefs = refs,
                    activationHint = (convergent.size.toDouble() / PROJECTION_FAN_OUT).coerceIn(0.0, 1.0),
                )
            }
        }

        val bounded = candidates.sortedWith(
            compareByDescending<Candidate> { it.convergentNeuronIds.size }
                .thenBy { it.candidateId },
        ).take(maxCandidates)
        return Result(
            activeConceptCount = active.size,
            associationSpikeCount = associationSpikes.size,
            consideredPairCount = consideredPairs,
            candidates = bounded,
        )
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val MAX_CANDIDATES = 3
        private const val MAX_CONVERGENT_TARGETS = 6
        private const val MAX_ACTIVATED_CONNECTIONS = MAX_CONVERGENT_TARGETS * 2
        private const val PROJECTION_FAN_OUT = 3
        private const val GENERAL_ASSOCIATION_START = 1_200
        private const val GENERAL_ASSOCIATION_END = 2_200
    }
}
