package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Reads hypothesis candidates directly from the 250k JNVM runtime's actual
 * spikes and transmitted sparse synapses.
 *
 * A candidate requires two distinct, stable evidence slots to transmit to the
 * same target neuron and that target to spike at the corresponding arrival
 * timestamp. Ambiguous target spikes and incomplete transmission capture are
 * excluded from negative/reproduction conclusions.
 *
 * This detects repeatable feature co-activation only. It does not understand
 * the contents of text values, infer semantic relations, or prove causality.
 */
class AndroidJnvM250kHypothesisReadout(
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) {
    data class Candidate(
        val candidateId: String,
        val firstConceptId: String,
        val firstLabel: String,
        val secondConceptId: String,
        val secondLabel: String,
        val convergentNeuronIds: List<Int>,
        val activatedConnectionIds: List<String>,
        val evidenceRefs: List<String>,
    ) {
        init {
            require(candidateId.matches(Regex("^jnvm250k-association:[a-f0-9]{32}$")))
            require(firstConceptId.isNotBlank() && firstConceptId.length <= 96)
            require(secondConceptId.isNotBlank() && secondConceptId.length <= 96)
            require(firstConceptId != secondConceptId)
            require(firstLabel.isNotBlank() && firstLabel.length <= 96)
            require(secondLabel.isNotBlank() && secondLabel.length <= 96)
            require(convergentNeuronIds.isNotEmpty() && convergentNeuronIds.size <= MAX_TARGETS_PER_PAIR)
            require(convergentNeuronIds.distinct().size == convergentNeuronIds.size)
            require(convergentNeuronIds.all { it in 0 until AndroidJnvM250kProfile.NEURON_COUNT })
            require(activatedConnectionIds.size >= 2 && activatedConnectionIds.size <= MAX_CONNECTIONS_PER_PAIR)
            require(activatedConnectionIds.distinct().size == activatedConnectionIds.size)
            require(activatedConnectionIds.all { it.isNotBlank() && it.length <= 128 })
            require(evidenceRefs.size <= 2)
            require(evidenceRefs.all { it.isNotBlank() && it.length <= TypedEvidenceRecord.MAX_PROVENANCE_CHARS })
        }
    }

    data class Result(
        val activeConceptIds: Set<String>,
        val candidates: List<Candidate>,
        val consideredPairCount: Int,
        val candidateCountBeforeLimit: Int,
        val pairScanComplete: Boolean,
        val omittedCausalLinks: Int,
        val excludedAmbiguousTargetCount: Int,
    ) {
        init {
            require(consideredPairCount >= 0)
            require(candidateCountBeforeLimit >= candidates.size)
            require(omittedCausalLinks >= 0)
            require(excludedAmbiguousTargetCount >= 0)
        }
    }

    private data class Arrival(val targetNeuronId: Int, val timestampNs: Long)

    init {
        require(maxCandidates in 0..MAX_CANDIDATES_HARD_LIMIT)
    }

    fun decode(
        encoding: AndroidJnvM250kEvidenceEncoder.Encoding,
        emittedSpikes: List<AndroidJnvM250kSpike>,
        causalLinks: List<AndroidJnvM250kCausalLink>,
        omittedCausalLinks: Int,
        ambiguousTargetSpikeNeuronIds: Set<Int>,
    ): Result {
        require(omittedCausalLinks >= 0)
        require(encoding.events.size <= TypedEvidenceBundle.MAX_RECORDS_PER_TURN)
        require(emittedSpikes.size <= MAX_SPIKES_PER_TURN)
        require(causalLinks.size <= MAX_CAUSAL_LINKS_PER_TURN)
        require(ambiguousTargetSpikeNeuronIds.all {
            it in 0 until AndroidJnvM250kProfile.NEURON_COUNT
        })

        val recordsByNeuron = encoding.recordToNeuronId.entries.associate { (recordId, neuronId) ->
            neuronId to recordId
        }
        require(recordsByNeuron.size == encoding.recordToNeuronId.size)
        val recordById = encoding.events.associateBy { event ->
            encoding.recordToNeuronId.entries.single { it.value == event.targetNeuronId }.key
        }
        val sourceSpikeIds = emittedSpikes.map { it.neuronId }.toSet()
        val activeRecords = recordsByNeuron.filter { (neuronId, _) ->
            neuronId in sourceSpikeIds
        }
        val activeConceptIds = activeRecords.values.toSortedSet()

        val spikeTimes = emittedSpikes.groupBy { it.neuronId }
            .mapValues { (_, spikes) -> spikes.map { it.timestampNs }.toSet() }
        val sourceByArrival = LinkedHashMap<Arrival, MutableList<Pair<String, AndroidJnvM250kCausalLink>>>()
        causalLinks.forEach { link ->
            val recordId = recordsByNeuron[link.sourceNeuronId] ?: return@forEach
            if (link.sourceNeuronId !in sourceSpikeIds) return@forEach
            if (link.targetNeuronId in ambiguousTargetSpikeNeuronIds) return@forEach
            if (link.arrivalTimestampNs !in spikeTimes[link.targetNeuronId].orEmpty()) return@forEach
            sourceByArrival.getOrPut(Arrival(link.targetNeuronId, link.arrivalTimestampNs)) {
                ArrayList()
            }.add(recordId to link)
        }

        val pairTargets = LinkedHashMap<Pair<String, String>, MutableMap<Int, MutableSet<String>>>()
        val pairRefs = HashMap<Pair<String, String>, MutableSet<String>>()
        var consideredPairs = 0
        sourceByArrival.values.forEach { arrivals ->
            val distinctSources = arrivals.distinctBy { it.first }
            for (leftIndex in distinctSources.indices) {
                for (rightIndex in leftIndex + 1 until distinctSources.size) {
                    val left = distinctSources[leftIndex]
                    val right = distinctSources[rightIndex]
                    if (left.first == right.first) continue
                    consideredPairs++
                    val key = listOf(left.first, right.first).sorted().let { it[0] to it[1] }
                    val target = arrivals.first().second.targetNeuronId
                    val connections = pairTargets.getOrPut(key) { LinkedHashMap() }
                        .getOrPut(target) { LinkedHashSet() }
                    connections += left.second.connectionId
                    connections += right.second.connectionId
                    val refs = pairRefs.getOrPut(key) { LinkedHashSet() }
                    encoding.recordToNeuronId.entries
                        .filter { it.key == left.first || it.key == right.first }
                        .forEach { entry ->
                            val sourceEvent = encoding.events.firstOrNull {
                                it.targetNeuronId == entry.value
                            }
                            if (sourceEvent != null) refs += "record:${entry.key}"
                        }
                }
            }
        }

        val candidates = pairTargets.mapNotNull { (pair, targetMap) ->
            val targets = targetMap.keys.sorted().take(MAX_TARGETS_PER_PAIR)
            val connections = targets.flatMap { targetMap.getValue(it) }.distinct().sorted()
            if (targets.isEmpty() || connections.size < 2) return@mapNotNull null
            val refs = pairRefs[pair].orEmpty().sorted().take(2)
            Candidate(
                candidateId = "jnvm250k-association:${digest(pair.first + "|" + pair.second)}",
                firstConceptId = pair.first,
                firstLabel = pair.first.substringBeforeLast(':'),
                secondConceptId = pair.second,
                secondLabel = pair.second.substringBeforeLast(':'),
                convergentNeuronIds = targets,
                activatedConnectionIds = connections.take(MAX_CONNECTIONS_PER_PAIR),
                evidenceRefs = refs,
            )
        }.sortedWith(
            compareByDescending<Candidate> { it.convergentNeuronIds.size }
                .thenBy { it.candidateId },
        )

        val complete = omittedCausalLinks == 0 && candidates.size <= maxCandidates
        return Result(
            activeConceptIds = activeConceptIds,
            candidates = candidates.take(maxCandidates),
            consideredPairCount = consideredPairs,
            candidateCountBeforeLimit = candidates.size,
            pairScanComplete = complete,
            omittedCausalLinks = omittedCausalLinks,
            excludedAmbiguousTargetCount = ambiguousTargetSpikeNeuronIds.size,
        )
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_MAX_CANDIDATES = 3
        const val MAX_CANDIDATES_HARD_LIMIT = 16
        const val MAX_TARGETS_PER_PAIR = 6
        const val MAX_CONNECTIONS_PER_PAIR = 12
        const val MAX_SPIKES_PER_TURN = 16_384
        const val MAX_CAUSAL_LINKS_PER_TURN = 16_384
    }
}
