package org.jarvis.ans100.alpha.data

/**
 * Bounded, turn-scoped reward rule for ANS-100 candidate-path synapses.
 *
 * This is deliberately a pure learning kernel. A caller must supply only
 * verified ANS-100 connection IDs and explicit feedback; it does not discover
 * edges, infer semantic labels, or persist weights by itself.
 */
class ANS100CandidateFeedbackLearner(
    private val learningRate: Double = DEFAULT_LEARNING_RATE,
    private val maxDeltaPerSynapse: Double = DEFAULT_MAX_DELTA_PER_SYNAPSE,
    private val minimumWeight: Double = MINIMUM_WEIGHT,
    private val maximumWeight: Double = MAXIMUM_WEIGHT,
) {
    enum class FeedbackSource {
        EXPLICIT_USER,
        VERIFIED_EXTERNAL_OUTCOME,
        CONTROLLED_BENCHMARK_LABEL,
        MODEL_GENERATED,
        IMPLICIT_BEHAVIOR,
    }

    data class EligibilityTrace(
        val turnId: String,
        val connectionId: String,
        val candidateId: String,
        val eligibility: Double,
    )

    data class Feedback(
        val turnId: String,
        val candidateId: String,
        val reward: Double,
        val source: FeedbackSource,
    )

    data class Update(
        val connectionId: String,
        val previousWeight: Double,
        val nextWeight: Double,
        val appliedDelta: Double,
    )

    init {
        require(learningRate.isFinite() && learningRate in 0.0..0.1)
        require(maxDeltaPerSynapse.isFinite() && maxDeltaPerSynapse in 0.0..0.05)
        require(minimumWeight.isFinite() && minimumWeight in 0.0..1.0)
        require(maximumWeight.isFinite() && maximumWeight in minimumWeight..1.0)
    }

    /**
     * Applies one explicit reward to traces from exactly one turn and candidate.
     * Generated text and implicit user behavior are never accepted as labels.
     */
    fun applyFeedback(
        traces: List<EligibilityTrace>,
        weights: Map<String, Double>,
        feedback: Feedback,
    ): List<Update> {
        require(feedback.turnId.isNotBlank())
        require(feedback.candidateId.isNotBlank())
        require(feedback.reward.isFinite() && feedback.reward in -1.0..1.0 && feedback.reward != 0.0)
        require(traces.size <= MAX_TRACES_PER_FEEDBACK)
        require(traces.map { it.connectionId }.toSet().size == traces.size) {
            "eligibility traces must have unique connection IDs"
        }
        require(traces.all {
            it.turnId.isNotBlank() &&
                it.connectionId.matches(CONNECTION_ID_PATTERN) &&
                it.candidateId.isNotBlank() &&
                it.eligibility.isFinite() && it.eligibility in 0.0..1.0
        })
        require(weights.keys.all { it.matches(CONNECTION_ID_PATTERN) })
        require(weights.values.all { it.isFinite() && it in 0.0..1.0 })

        if (feedback.source != FeedbackSource.EXPLICIT_USER &&
            feedback.source != FeedbackSource.VERIFIED_EXTERNAL_OUTCOME
        ) return emptyList()

        return traces.asSequence()
            .filter { it.turnId == feedback.turnId && it.candidateId == feedback.candidateId }
            .mapNotNull { trace ->
                val current = weights[trace.connectionId] ?: return@mapNotNull null
                val requestedDelta = learningRate * feedback.reward * trace.eligibility
                val boundedDelta = requestedDelta.coerceIn(-maxDeltaPerSynapse, maxDeltaPerSynapse)
                val next = (current + boundedDelta).coerceIn(minimumWeight, maximumWeight)
                val actualDelta = next - current
                if (actualDelta == 0.0) null else Update(
                    connectionId = trace.connectionId,
                    previousWeight = current,
                    nextWeight = next,
                    appliedDelta = actualDelta,
                )
            }
            .toList()
    }

    companion object {
        const val MAX_TRACES_PER_FEEDBACK = 2_000
        const val DEFAULT_LEARNING_RATE = 0.02
        const val DEFAULT_MAX_DELTA_PER_SYNAPSE = 0.01
        const val MINIMUM_WEIGHT = 0.05
        const val MAXIMUM_WEIGHT = 1.0
        private val CONNECTION_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
