package org.jarvis.ans100.alpha.data

/**
 * Evaluates an association only from independently identified task outcomes.
 * Repeated activations are not outcomes, and unresolved tasks do not count as
 * either success or failure. This class never changes synaptic weights.
 */
class ANS100NeuralAssociationAssessment {
    enum class Outcome { REPRODUCED, CONTRADICTED, INCONCLUSIVE }
    enum class Stability { INSUFFICIENT_EVIDENCE, STABLE, UNSTABLE, MIXED }
    enum class ExplicitFeedback { POSITIVE, NEGATIVE, NONE }
    enum class FeedbackAgreement { NO_FEEDBACK, ALIGNED, CONFLICTS, NOT_YET_DECIDABLE }

    data class Observation(
        val taskId: String,
        /** Same value means tasks share a source/template and are not independent samples. */
        val independenceKey: String,
        val outcome: Outcome,
    )

    data class Assessment(
        val independentTaskCount: Int,
        val successfulReproductions: Int,
        val contradictions: Int,
        val inconclusiveTasks: Int,
        val stabilityRatio: Double?,
        val stability: Stability,
        val explicitFeedback: ExplicitFeedback,
        val feedbackAgreement: FeedbackAgreement,
    )

    fun assess(
        observations: List<Observation>,
        feedback: ExplicitFeedback = ExplicitFeedback.NONE,
    ): Assessment {
        require(observations.size <= MAX_OBSERVATIONS)
        require(observations.all {
            it.taskId.matches(ID_PATTERN) && it.independenceKey.matches(ID_PATTERN)
        })
        require(observations.map { it.taskId }.distinct().size == observations.size) {
            "a task may contribute only one outcome"
        }
        require(observations.map { it.independenceKey }.distinct().size == observations.size) {
            "non-independent tasks cannot be counted as separate reproductions"
        }

        val successes = observations.count { it.outcome == Outcome.REPRODUCED }
        val contradictions = observations.count { it.outcome == Outcome.CONTRADICTED }
        val inconclusive = observations.count { it.outcome == Outcome.INCONCLUSIVE }
        val decisive = successes + contradictions
        val ratio = if (decisive == 0) null else successes.toDouble() / decisive
        val stability = when {
            decisive < MIN_DECISIVE_TASKS -> Stability.INSUFFICIENT_EVIDENCE
            successes >= MIN_SUCCESSFUL_REPRODUCTIONS && ratio!! >= STABLE_RATIO -> Stability.STABLE
            ratio!! <= UNSTABLE_RATIO -> Stability.UNSTABLE
            else -> Stability.MIXED
        }
        val agreement = when {
            feedback == ExplicitFeedback.NONE -> FeedbackAgreement.NO_FEEDBACK
            stability == Stability.INSUFFICIENT_EVIDENCE || stability == Stability.MIXED ->
                FeedbackAgreement.NOT_YET_DECIDABLE
            (feedback == ExplicitFeedback.POSITIVE && stability == Stability.STABLE) ||
                (feedback == ExplicitFeedback.NEGATIVE && stability == Stability.UNSTABLE) ->
                FeedbackAgreement.ALIGNED
            else -> FeedbackAgreement.CONFLICTS
        }
        return Assessment(
            independentTaskCount = observations.size,
            successfulReproductions = successes,
            contradictions = contradictions,
            inconclusiveTasks = inconclusive,
            stabilityRatio = ratio,
            stability = stability,
            explicitFeedback = feedback,
            feedbackAgreement = agreement,
        )
    }

    companion object {
        const val MAX_OBSERVATIONS = 2_000
        const val MIN_DECISIVE_TASKS = 3
        const val MIN_SUCCESSFUL_REPRODUCTIONS = 3
        const val STABLE_RATIO = 0.8
        const val UNSTABLE_RATIO = 0.4
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
