package org.jarvis.ans100.alpha.data

/**
 * Versioned neural-only four-class output contract.
 *
 * The canonical outputs occupy 8800..9399. Legacy Core-labelled six-lane
 * outputs remain isolated at 9400..9999. Only general concept/association/
 * working-state spikes may drive learned canonical synapses.
 */
object ANS100CanonicalReadoutContract {
    enum class Answer(val wireValue: String) {
        YES("YES"),
        NO("NO"),
        UNKNOWN("UNKNOWN"),
        CONFLICT("CONFLICT"),
    }

    data class OutputGroup(
        val answer: Answer,
        val startInclusive: Int,
        val endExclusive: Int,
    ) {
        init {
            require(startInclusive >= OUTPUT_START)
            require(endExclusive <= OUTPUT_END)
            require(startInclusive < endExclusive)
        }
        val size: Int get() = endExclusive - startInclusive
        fun contains(neuronId: Int): Boolean = neuronId in startInclusive until endExclusive
    }

    const val OUTPUT_START = 8_800
    const val OUTPUT_END = 9_400
    const val OUTPUT_GROUP_SIZE = 150
    const val MAX_SOURCE_NEURONS_PER_TURN = 512
    const val MAX_PERSISTED_SOURCE_NEURONS = 1_024
    const val MAX_PERSISTED_SYNAPSES = MAX_PERSISTED_SOURCE_NEURONS * 4
    const val INITIAL_SYNAPSE_WEIGHT = 0.25
    const val MIN_SYNAPSE_WEIGHT = 0.05
    const val MAX_SYNAPSE_WEIGHT = 1.0
    const val LEARNING_RATE = 0.02
    const val MAX_WEIGHT_DELTA_PER_EVENT = 0.01
    const val SYNAPSE_DELAY_NS = AndroidHybridTopologyV2.CONNECTION_DELAY_NS

    val outputGroups: List<OutputGroup> = Answer.values().mapIndexed { index, answer ->
        val start = OUTPUT_START + index * OUTPUT_GROUP_SIZE
        OutputGroup(answer, start, start + OUTPUT_GROUP_SIZE)
    }

    fun outputGroup(answer: Answer): OutputGroup =
        outputGroups.first { it.answer == answer }

    fun isEligibleSourceNeuron(neuronId: Int): Boolean =
        neuronId in 1_062 until 1_200 ||
            neuronId in 1_200 until 2_200 ||
            neuronId in 4_000 until 4_300

    fun validateSourceNeuronIds(neuronIds: List<Int>): List<Int> {
        require(neuronIds.size <= MAX_SOURCE_NEURONS_PER_TURN)
        require(neuronIds.distinct().size == neuronIds.size)
        require(neuronIds.all(::isEligibleSourceNeuron)) {
            "canonical readout source contains Core-labelled or non-general population neurons"
        }
        return neuronIds.sorted()
    }

    fun outputNeuronId(answer: Answer, sourceNeuronId: Int): Int {
        require(isEligibleSourceNeuron(sourceNeuronId))
        val salt = (answer.ordinal + 1) * 53L
        val offset = Math.floorMod(sourceNeuronId.toLong() * 37L + salt, OUTPUT_GROUP_SIZE.toLong()).toInt()
        return outputGroup(answer).startInclusive + offset
    }

    fun connectionId(answer: Answer, sourceNeuronId: Int): String {
        val target = outputNeuronId(answer, sourceNeuronId)
        return "ans100-v9-canonical-readout-${answer.wireValue.lowercase()}-$sourceNeuronId-$target"
    }
}
