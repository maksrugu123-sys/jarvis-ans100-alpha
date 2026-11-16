package org.jarvis.ans100.alpha.data

/**
 * Reads only spikes from the dedicated canonical output groups.
 *
 * The legacy six Core-labelled output lanes are outside this address range and
 * are never translated into canonical answers. A winner is an uncalibrated
 * neural readout, not a probability or a production decision.
 */
class ANS100CanonicalReadoutDecoder {
    data class Result(
        val answer: ANS100CanonicalReadoutContract.Answer?,
        val spikeCounts: Map<ANS100CanonicalReadoutContract.Answer, Int>,
        val winningSpikeCount: Int,
        val marginSpikeCount: Int,
        val status: Status,
        val calibrationStatus: String = "NOT_CALIBRATED",
    ) {
        enum class Status {
            NO_LEARNED_SIGNAL,
            AMBIGUOUS_TIE,
            BELOW_MINIMUM_ACTIVITY,
            UNCALIBRATED_WINNER,
        }
    }

    fun decode(
        emittedSpikeNeuronIds: Set<Int>,
        coreTaintedSpikingNeuronIds: Set<Int> = emptySet(),
    ): Result {
        require(emittedSpikeNeuronIds.size <= AndroidHybridTopologyV2.NEURON_COUNT)
        require(emittedSpikeNeuronIds.all { it in 0 until AndroidHybridTopologyV2.NEURON_COUNT })
        require(coreTaintedSpikingNeuronIds.all { it in emittedSpikeNeuronIds }) {
            "tainted spike set must be a subset of emitted spikes"
        }
        // A readout spike is not admissible if any Core-tainted event or prior
        // tainted membrane state contributed to that output neuron's activity.
        val admissibleSpikes = emittedSpikeNeuronIds - coreTaintedSpikingNeuronIds
        val counts = ANS100CanonicalReadoutContract.Answer.values().associateWith { answer ->
            val group = ANS100CanonicalReadoutContract.outputGroup(answer)
            admissibleSpikes.count(group::contains)
        }
        val ranked = counts.entries.sortedWith(
            compareByDescending<Map.Entry<ANS100CanonicalReadoutContract.Answer, Int>> { it.value }
                .thenBy { it.key.ordinal },
        )
        val top = ranked.firstOrNull()?.value ?: 0
        val second = ranked.getOrNull(1)?.value ?: 0
        val winners = ranked.count { it.value == top }
        val status = when {
            top == 0 -> Result.Status.NO_LEARNED_SIGNAL
            winners != 1 -> Result.Status.AMBIGUOUS_TIE
            top < MIN_WINNING_SPIKES || top - second < MINIMUM_MARGIN_SPIKES ->
                Result.Status.BELOW_MINIMUM_ACTIVITY
            else -> Result.Status.UNCALIBRATED_WINNER
        }
        val answer = if (status == Result.Status.UNCALIBRATED_WINNER) ranked.first().key else null
        return Result(
            answer = answer,
            spikeCounts = counts,
            winningSpikeCount = top,
            marginSpikeCount = top - second,
            status = status,
        )
    }

    companion object {
        const val MIN_WINNING_SPIKES = 2
        const val MINIMUM_MARGIN_SPIKES = 1
    }
}
