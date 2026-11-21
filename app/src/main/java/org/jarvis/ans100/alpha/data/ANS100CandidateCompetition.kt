package org.jarvis.ans100.alpha.data

/**
 * Deterministic bounded winner-take-most competition for candidate readouts.
 *
 * The score is derived only from actual support/conflict readout activity.
 * It is an engineering activation, not a probability or a semantic confidence.
 * Learned synaptic parameters are deliberately not introduced in this stage.
 */
class ANS100CandidateCompetition(
    private val lateralInhibition: Double = DEFAULT_LATERAL_INHIBITION,
    private val minimumActivation: Double = DEFAULT_MINIMUM_ACTIVATION,
    private val minimumWinnerMargin: Double = DEFAULT_MINIMUM_WINNER_MARGIN,
) {
    data class Input(
        val candidateId: String,
        val supportSpikes: Int,
        val conflictSpikes: Int,
        val supportCapacity: Int,
        val conflictCapacity: Int,
    ) {
        init {
            require(candidateId.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,63}$")))
            require(supportCapacity > 0 && conflictCapacity > 0)
            require(supportSpikes in 0..supportCapacity)
            require(conflictSpikes in 0..conflictCapacity)
        }
    }

    data class Result(
        val activations: Map<String, Double>,
        val winnerCandidateId: String?,
        val winnerMargin: Double,
    )

    init {
        require(lateralInhibition.isFinite() && lateralInhibition in 0.0..1.0)
        require(minimumActivation.isFinite() && minimumActivation in 0.0..1.0)
        require(minimumWinnerMargin.isFinite() && minimumWinnerMargin in 0.0..1.0)
    }

    fun evaluate(inputs: List<Input>): Result {
        require(inputs.isNotEmpty() && inputs.size <= MAX_CANDIDATES)
        require(inputs.map { it.candidateId }.distinct().size == inputs.size) {
            "candidate IDs must be unique"
        }

        val net = inputs.associate { input ->
            val support = input.supportSpikes.toDouble() / input.supportCapacity
            val conflict = input.conflictSpikes.toDouble() / input.conflictCapacity
            input.candidateId to (support - conflict).coerceIn(-1.0, 1.0)
        }
        val activations = inputs.associate { input ->
            val strongestCompetitor = net.asSequence()
                .filter { it.key != input.candidateId }
                .maxOfOrNull { it.value }
                ?.coerceAtLeast(0.0) ?: 0.0
            val inhibited = net.getValue(input.candidateId) -
                lateralInhibition * strongestCompetitor
            input.candidateId to inhibited.coerceIn(0.0, 1.0)
        }

        val ranked = activations.entries.sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }
                .thenBy { it.key },
        )
        val top = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
        val margin = (top.value - runnerUp).coerceIn(0.0, 1.0)
        val winner = top.key.takeIf {
            top.value >= minimumActivation && margin >= minimumWinnerMargin &&
                ranked.count { entry -> entry.value == top.value } == 1
        }
        return Result(
            activations = activations,
            winnerCandidateId = winner,
            winnerMargin = margin,
        )
    }

    companion object {
        const val MAX_CANDIDATES = 8
        const val DEFAULT_LATERAL_INHIBITION = 0.25
        const val DEFAULT_MINIMUM_ACTIVATION = 0.05
        const val DEFAULT_MINIMUM_WINNER_MARGIN = 0.05
    }
}
