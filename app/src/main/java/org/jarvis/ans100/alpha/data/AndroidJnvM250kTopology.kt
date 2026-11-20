package org.jarvis.ans100.alpha.data

/**
 * Stable ID-range contract for the first 250k ANS specialization topology.
 *
 * Population ranges are mutually exclusive and cover the full profile.
 * Excitatory/inhibitory polarity is an orthogonal property within each
 * population; it must not be confused with a separate population.
 *
 * This is an allocation contract only. It does not yet create connections,
 * run learning, or imply that the 250k scheduler is integrated.
 */
object AndroidJnvM250kTopology {
    enum class Population {
        PERCEPTION,
        MEMORY,
        PATTERN_DETECTION,
        PREDICTION,
        ATTENTION,
        CONTRADICTION,
        CONTEXT,
        CONFIDENCE,
        COORDINATION,
        ACTION_SELECTION,
    }

    enum class Polarity {
        EXCITATORY,
        INHIBITORY,
    }

    data class Range(
        val population: Population,
        val firstNeuronId: Int,
        val lastNeuronIdInclusive: Int,
    ) {
        val size: Int
            get() = lastNeuronIdInclusive - firstNeuronId + 1

        operator fun contains(neuronId: Int): Boolean =
            neuronId in firstNeuronId..lastNeuronIdInclusive
    }

    // Initial engineering allocation, not a scientifically measured optimum.
    val POPULATION_RANGES: List<Range> = listOf(
        Range(Population.PERCEPTION, 0, 19_999),              // 20,000
        Range(Population.MEMORY, 20_000, 69_999),             // 50,000
        Range(Population.PATTERN_DETECTION, 70_000, 114_999), // 45,000
        Range(Population.PREDICTION, 115_000, 154_999),       // 40,000
        Range(Population.ATTENTION, 155_000, 174_999),        // 20,000
        Range(Population.CONTRADICTION, 175_000, 189_999),    // 15,000
        Range(Population.CONTEXT, 190_000, 214_999),          // 25,000
        Range(Population.CONFIDENCE, 215_000, 229_999),       // 15,000
        Range(Population.COORDINATION, 230_000, 239_999),     // 10,000
        Range(Population.ACTION_SELECTION, 240_000, 249_999), // 10,000
    )

    /**
     * One in every five IDs is provisionally inhibitory within its population
     * (20%); all remaining IDs are excitatory (80%). This deterministic rule
     * gives stable polarity without storing a second 250k lookup table.
     */
    const val INHIBITORY_PERIOD = 5
    private const val INHIBITORY_SLOT = INHIBITORY_PERIOD - 1

    fun populationFor(neuronId: Int): Population {
        AndroidJnvM250kProfile.requireNeuronId(neuronId)
        return POPULATION_RANGES.first { neuronId in it }.population
    }

    fun polarityFor(neuronId: Int): Polarity {
        AndroidJnvM250kProfile.requireNeuronId(neuronId)
        val range = POPULATION_RANGES.first { neuronId in it }
        return if ((neuronId - range.firstNeuronId) % INHIBITORY_PERIOD == INHIBITORY_SLOT) {
            Polarity.INHIBITORY
        } else {
            Polarity.EXCITATORY
        }
    }

    fun validate() {
        require(POPULATION_RANGES.size == Population.entries.size) {
            "every 250k population must have exactly one ID range"
        }
        var expectedFirst = 0
        val seen = HashSet<Population>()
        for (range in POPULATION_RANGES) {
            require(range.population !in seen) { "duplicate population range" }
            require(range.firstNeuronId == expectedFirst) { "population ranges must be contiguous and non-overlapping" }
            require(range.lastNeuronIdInclusive >= range.firstNeuronId) { "population range is empty" }
            seen += range.population
            expectedFirst = range.lastNeuronIdInclusive + 1
        }
        require(expectedFirst == AndroidJnvM250kProfile.NEURON_CAPACITY) {
            "population ranges must cover exactly the 250k profile"
        }
    }
}
