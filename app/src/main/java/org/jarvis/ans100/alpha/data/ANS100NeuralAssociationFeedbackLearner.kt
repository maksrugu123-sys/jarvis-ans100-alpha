package org.jarvis.ans100.alpha.data

/**
 * Converts an observed neural co-activation candidate plus an explicit reward
 * into bounded updates for the exact concept-to-association synapses involved.
 *
 * This is a pure learning adapter: callers must load current persisted weights
 * and commit returned updates themselves. It never accepts generated text or
 * implicit behavior as a teaching signal.
 */
class ANS100NeuralAssociationFeedbackLearner(
    private val rule: ANS100CandidateFeedbackLearner = ANS100CandidateFeedbackLearner(),
) {
    data class Feedback(
        val turnId: String,
        val candidateId: String,
        val reward: Double,
        val source: Source,
    )

    enum class Source {
        EXPLICIT_USER,
        VERIFIED_EXTERNAL_OUTCOME,
        MODEL_GENERATED,
        IMPLICIT_BEHAVIOR,
    }

    data class Result(
        val accepted: Boolean,
        val updates: List<ANS100CandidateFeedbackLearner.Update>,
        val reason: RejectionReason?,
    )

    enum class RejectionReason {
        UNTRUSTED_FEEDBACK_SOURCE,
        CANDIDATE_ID_MISMATCH,
        MISSING_CURRENT_WEIGHT,
    }

    fun apply(
        candidate: ANS100NeuralAssociationDecoder.Candidate,
        currentWeights: Map<String, Double>,
        feedback: Feedback,
    ): Result {
        require(feedback.turnId.isNotBlank())
        require(feedback.candidateId.isNotBlank())
        require(feedback.reward.isFinite() && feedback.reward in -1.0..1.0 && feedback.reward != 0.0)
        require(candidate.activatedConnectionIds.size <= MAX_CONNECTIONS_PER_CANDIDATE)
        require(currentWeights.size <= MAX_CONNECTIONS_PER_CANDIDATE)
        require(currentWeights.values.all { it.isFinite() && it in 0.0..1.0 })

        if (feedback.source != Source.EXPLICIT_USER &&
            feedback.source != Source.VERIFIED_EXTERNAL_OUTCOME
        ) return Result(false, emptyList(), RejectionReason.UNTRUSTED_FEEDBACK_SOURCE)

        if (feedback.candidateId != candidate.candidateId) {
            return Result(false, emptyList(), RejectionReason.CANDIDATE_ID_MISMATCH)
        }

        val expectedByTarget = LinkedHashMap<Int, MutableList<Pair<Int, String>>>()
        for (sourceNeuronId in ANS100ConceptMemory.NEURAL_INPUT_START until
            ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
        ) {
            AndroidHybridTopologyV2.conceptAssociationProjection(sourceNeuronId).forEach { (targetId, connectionId) ->
                expectedByTarget.getOrPut(targetId) { ArrayList() }.add(sourceNeuronId to connectionId)
            }
        }

        val expectedConnections = candidate.convergentNeuronIds.flatMap { targetId ->
            val incoming = expectedByTarget[targetId].orEmpty()
            require(incoming.size >= 2)
            val candidateEdges = incoming.filter { it.second in candidate.activatedConnectionIds }
            require(candidateEdges.size == 2 && candidateEdges.map { it.first }.distinct().size == 2) {
                "each convergent target must have exactly two distinct concept input synapses"
            }
            candidateEdges.map { it.second }
        }.toSet()

        require(expectedConnections.size == candidate.activatedConnectionIds.size &&
            expectedConnections == candidate.activatedConnectionIds.toSet()
        ) { "candidate synapses must exactly match the persisted concept projection" }

        val missing = candidate.activatedConnectionIds.filterNot { it in currentWeights }
        if (missing.isNotEmpty()) {
            return Result(false, emptyList(), RejectionReason.MISSING_CURRENT_WEIGHT)
        }

        val source = when (feedback.source) {
            Source.EXPLICIT_USER -> ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER
            Source.VERIFIED_EXTERNAL_OUTCOME -> ANS100CandidateFeedbackLearner.FeedbackSource.VERIFIED_EXTERNAL_OUTCOME
            Source.MODEL_GENERATED -> ANS100CandidateFeedbackLearner.FeedbackSource.MODEL_GENERATED
            Source.IMPLICIT_BEHAVIOR -> ANS100CandidateFeedbackLearner.FeedbackSource.IMPLICIT_BEHAVIOR
        }
        val traces = candidate.activatedConnectionIds.sorted().map { connectionId ->
            ANS100CandidateFeedbackLearner.EligibilityTrace(
                turnId = feedback.turnId,
                connectionId = connectionId,
                candidateId = candidate.candidateId,
                eligibility = candidate.activationHint,
            )
        }
        val updates = rule.applyFeedback(
            traces = traces,
            weights = currentWeights,
            feedback = ANS100CandidateFeedbackLearner.Feedback(
                turnId = feedback.turnId,
                candidateId = feedback.candidateId,
                reward = feedback.reward,
                source = source,
            ),
        )
        return Result(true, updates, null)
    }

    companion object {
        const val MAX_CONNECTIONS_PER_CANDIDATE = 6
    }
}
