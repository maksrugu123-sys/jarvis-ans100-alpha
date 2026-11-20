package org.jarvis.ans100.alpha.data

/**
 * Fail-closed policy for converting counterfactual replay sensitivity into
 * per-synapse reward multipliers.
 *
 * This is deliberately separate from episode attribution: callers must provide
 * the same episode's response scores and a counterfactual report built from a
 * matching, detached episode snapshot. It never runs a replay or writes to JNVM.
 */
object AndroidJnvM250kCounterfactualRewardGate {
    enum class Rejection {
        ZERO_REWARD,
        BASELINE_INCOMPLETE,
        CANDIDATE_MISSING,
        REPLAY_INCOMPLETE,
        NO_OUTCOME_CHANGE,
        WRONG_OUTCOME_DIRECTION,
        SENSITIVITY_TOO_LOW,
        COMBINED_CONFIDENCE_TOO_LOW,
    }

    data class Policy(
        val minimumSensitivity: Double = DEFAULT_MINIMUM_SENSITIVITY,
        val minimumCombinedConfidence: Double = DEFAULT_MINIMUM_COMBINED_CONFIDENCE,
        val minimumAbsoluteOutcomeDelta: Int = 1,
    ) {
        init {
            require(minimumSensitivity.isFinite() && minimumSensitivity in 0.0..1.0)
            require(minimumCombinedConfidence.isFinite() && minimumCombinedConfidence in 0.0..1.0)
            require(minimumAbsoluteOutcomeDelta >= 1)
        }
    }

    data class Decision(
        /** Exact connection IDs and bounded confidence multipliers safe to submit. */
        val acceptedCredits: Map<String, Double>,
        val rejected: Map<String, Rejection>,
        val baselineAccepted: Boolean,
        val rewardAccepted: Boolean,
    ) {
        val mayApplyReward: Boolean
            get() = baselineAccepted && rewardAccepted && acceptedCredits.isNotEmpty()
    }

    /**
     * Positive reward requires that disabling an edge reduces outcome spikes.
     * Negative reward requires that disabling it increases outcome spikes.
     * Weak, incomplete, missing, or directionally inconsistent evidence is rejected.
     */
    fun evaluate(
        reward: Double,
        responseScores: Map<String, Double>,
        report: AndroidJnvM250kCounterfactualEvaluator.Report,
        policy: Policy = Policy(),
    ): Decision {
        require(reward.isFinite() && reward in -1.0..1.0)
        require(responseScores.keys.all { it.matches(CONNECTION_ID) })
        require(responseScores.values.all { it.isFinite() && it in 0.0..1.0 })
        require(report.candidates.map { it.connectionId }.toSet().size == report.candidates.size) {
            "counterfactual report contains duplicate candidate IDs"
        }
        require(report.candidates.all { effect ->
            effect.connectionId.matches(CONNECTION_ID) &&
                effect.baselineOutcomeSpikes >= 0 &&
                effect.counterfactualOutcomeSpikes >= 0 &&
                effect.outcomeSpikeDelta == effect.baselineOutcomeSpikes - effect.counterfactualOutcomeSpikes &&
                effect.sensitivityScore.isFinite() && effect.sensitivityScore in 0.0..1.0
        }) { "counterfactual report contains invalid effect data" }
        require(report.baselineProcessedEvents >= 0 && report.baselineProcessedTimestamps >= 0)

        if (!report.baselineComplete) {
            return Decision(
                acceptedCredits = emptyMap(),
                rejected = responseScores.keys.associateWith { Rejection.BASELINE_INCOMPLETE },
                baselineAccepted = false,
                rewardAccepted = reward != 0.0,
            )
        }
        if (reward == 0.0) {
            return Decision(
                acceptedCredits = emptyMap(),
                rejected = responseScores.keys.associateWith { Rejection.ZERO_REWARD },
                baselineAccepted = true,
                rewardAccepted = false,
            )
        }

        val effects = report.candidates.associateBy { it.connectionId }
        val accepted = LinkedHashMap<String, Double>()
        val rejected = LinkedHashMap<String, Rejection>()
        for ((connectionId, responseScore) in responseScores.toSortedMap()) {
            val effect = effects[connectionId]
            val rejection = when {
                effect == null -> Rejection.CANDIDATE_MISSING
                !effect.baselineComplete || !effect.counterfactualComplete -> Rejection.REPLAY_INCOMPLETE
                kotlin.math.abs(effect.outcomeSpikeDelta) < policy.minimumAbsoluteOutcomeDelta ->
                    Rejection.NO_OUTCOME_CHANGE
                effect.outcomeSpikeDelta.toDouble() * reward <= 0.0 ->
                    Rejection.WRONG_OUTCOME_DIRECTION
                effect.sensitivityScore < policy.minimumSensitivity ->
                    Rejection.SENSITIVITY_TOO_LOW
                responseScore * effect.sensitivityScore < policy.minimumCombinedConfidence ->
                    Rejection.COMBINED_CONFIDENCE_TOO_LOW
                else -> null
            }
            if (rejection != null) {
                rejected[connectionId] = rejection
            } else {
                accepted[connectionId] = (responseScore * effect!!.sensitivityScore).coerceIn(0.0, 1.0)
            }
        }
        return Decision(
            acceptedCredits = accepted,
            rejected = rejected,
            baselineAccepted = true,
            rewardAccepted = accepted.isNotEmpty(),
        )
    }

    const val DEFAULT_MINIMUM_SENSITIVITY = 0.25
    const val DEFAULT_MINIMUM_COMBINED_CONFIDENCE = 0.10

    private val CONNECTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
}
