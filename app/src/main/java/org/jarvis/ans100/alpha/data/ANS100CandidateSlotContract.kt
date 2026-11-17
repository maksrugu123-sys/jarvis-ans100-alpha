package org.jarvis.ans100.alpha.data

/**
 * Stable six-lane candidate routing contract for hybrid topology version 4.
 *
 * Each candidate owns a support lane and a conflict lane. Candidate signals
 * originate from explicitly labelled heuristic Core evidence. The network
 * propagates these signals through isolated sparse routes; it does not infer
 * the support/conflict labels itself.
 */
object ANS100CandidateSlotContract {
    enum class Slot(val candidateId: String, val index: Int) {
        ANSWER("respond_to_original_request", 0),
        CLARIFICATION("request_clarification", 1),
        ABSTAIN("abstain_without_supported_candidate", 2),
    }

    enum class Polarity(val index: Int) {
        SUPPORT(0),
        CONFLICT(1),
    }

    const val INPUT_START = 1_056
    const val INPUT_SIGNAL_COUNT = 6
    const val ASSOCIATION_LANE_SIZE = 300
    const val WORKING_LANE_SIZE = 250
    const val CANDIDATE_LANE_SIZE = 250
    const val CRITIC_LANE_SIZE = 200
    const val READOUT_SLOT_SIZE = 200
    const val READOUT_POLARITY_SIZE = 100

    fun inputNeuronId(slot: Slot, polarity: Polarity): Int =
        INPUT_START + slot.index * 2 + polarity.index

    fun associationLane(slot: Slot, polarity: Polarity): AndroidHybridTopologyV2.Population =
        lane(AndroidHybridTopologyV2.populations[1], slot, polarity, ASSOCIATION_LANE_SIZE)

    fun workingLane(slot: Slot, polarity: Polarity): AndroidHybridTopologyV2.Population =
        lane(AndroidHybridTopologyV2.populations[2], slot, polarity, WORKING_LANE_SIZE)

    fun candidateLane(slot: Slot, polarity: Polarity): AndroidHybridTopologyV2.Population =
        lane(AndroidHybridTopologyV2.populations[3], slot, polarity, CANDIDATE_LANE_SIZE)

    fun criticLane(slot: Slot, polarity: Polarity): AndroidHybridTopologyV2.Population =
        lane(AndroidHybridTopologyV2.populations[4], slot, polarity, CRITIC_LANE_SIZE)

    fun readoutLane(slot: Slot, polarity: Polarity): AndroidHybridTopologyV2.Population {
        val readout = AndroidHybridTopologyV2.populations[7]
        val start = readout.startInclusive + slot.index * READOUT_SLOT_SIZE +
            polarity.index * READOUT_POLARITY_SIZE
        return AndroidHybridTopologyV2.Population(
            id = "readout_${slot.name.lowercase()}_${polarity.name.lowercase()}",
            startInclusive = start,
            endExclusive = start + READOUT_POLARITY_SIZE,
        )
    }

    fun readoutSpikeCount(
        spikedNeuronIds: Set<Int>,
        slot: Slot,
        polarity: Polarity,
    ): Int = spikedNeuronIds.count(readoutLane(slot, polarity)::contains)

    fun evidenceFeature(slot: Slot, polarity: Polarity): EvidenceFeature = when (slot to polarity) {
        Slot.ANSWER to Polarity.SUPPORT -> EvidenceFeature.CANDIDATE_ANSWER_SUPPORT
        Slot.ANSWER to Polarity.CONFLICT -> EvidenceFeature.CANDIDATE_ANSWER_CONFLICT
        Slot.CLARIFICATION to Polarity.SUPPORT -> EvidenceFeature.CANDIDATE_CLARIFICATION_SUPPORT
        Slot.CLARIFICATION to Polarity.CONFLICT -> EvidenceFeature.CANDIDATE_CLARIFICATION_CONFLICT
        Slot.ABSTAIN to Polarity.SUPPORT -> EvidenceFeature.CANDIDATE_ABSTAIN_SUPPORT
        Slot.ABSTAIN to Polarity.CONFLICT -> EvidenceFeature.CANDIDATE_ABSTAIN_CONFLICT
        else -> throw IllegalArgumentException("unknown candidate slot/polarity pair")
    }

    fun slotForCandidateId(candidateId: String): Slot? =
        Slot.values().firstOrNull { it.candidateId == candidateId }

    private fun lane(
        population: AndroidHybridTopologyV2.Population,
        slot: Slot,
        polarity: Polarity,
        laneSize: Int,
    ): AndroidHybridTopologyV2.Population {
        val laneIndex = slot.index * 2 + polarity.index
        val sharedPrefix = when (population.id) {
            "association" -> 1_000
            "working_state" -> 300
            else -> 0
        }
        val start = population.startInclusive + sharedPrefix + laneIndex * laneSize
        return AndroidHybridTopologyV2.Population(
            id = "${population.id}_${slot.name.lowercase()}_${polarity.name.lowercase()}",
            startInclusive = start,
            endExclusive = start + laneSize,
        )
    }
}
