package org.jarvis.ans100.alpha.data

/**
 * Shared public-boundary validation for neural-association eligibility and
 * weight reads. The SQLite store performs the authoritative endpoint and
 * grown-proposal registration checks; this contract only admits known ID
 * namespaces before those checks.
 */
internal object ANS100NeuralAssociationEligibilityContract {
    private const val FIXED_PREFIX = "ans100-v4-evidence-general-association-"
    private const val GROWN_PREFIX = "ans100-v5-grown-association-"

    fun validateEligibilityIds(connectionIds: List<String>) {
        require(
            connectionIds.size in 2..ANS100NeuralAssociationFeedbackLearner.MAX_CONNECTIONS_PER_CANDIDATE &&
                connectionIds.size % 2 == 0
        ) { "neural association eligibility must contain complete target pairs" }
        validateIds(connectionIds)
    }

    fun validateWeightQueryIds(connectionIds: List<String>) {
        require(connectionIds.size in 1..ANS100NeuralAssociationFeedbackLearner.MAX_CONNECTIONS_PER_CANDIDATE)
        validateIds(connectionIds)
    }

    private fun validateIds(connectionIds: List<String>) {
        require(connectionIds.distinct().size == connectionIds.size) {
            "neural association connection IDs must be unique"
        }
        require(connectionIds.all {
            it.length <= 128 &&
                (it.startsWith(FIXED_PREFIX) || it.startsWith(GROWN_PREFIX))
        }) { "connection ID is outside the fixed or registered-grown association namespace" }
    }
}
