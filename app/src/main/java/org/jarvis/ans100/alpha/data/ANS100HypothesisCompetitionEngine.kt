package org.jarvis.ans100.alpha.data

import java.util.Locale

/**
 * Cross-checks neural co-activation pairs against typed explanations generated
 * independently by the approved workspace relation graph.
 *
 * It does not merge the two signals into a truth decision. Workspace support and
 * counter-evidence remain EXPLICIT_USER assertions, not external verification.
 */
class ANS100HypothesisCompetitionEngine {
    enum class Status {
        NEURAL_ONLY,
        MEMORY_PATH_UNRESOLVED,
        USER_ASSERTIONS_SUPPORT,
        USER_COUNTER_EVIDENCE,
        MIXED_USER_ASSERTIONS,
    }

    enum class Direction {
        SAME_DIRECTION,
        REVERSED_DIRECTION,
    }

    data class Explanation(
        val hypothesisId: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val direction: Direction,
        val verificationStatus: ANS100ConceptMemory.VerificationStatus,
        val premiseEvidenceCount: Int,
        val supportingEvidenceCount: Int,
        val contradictingEvidenceCount: Int,
    )

    data class Comparison(
        val neuralCandidateId: String,
        val status: Status,
        val explanations: List<Explanation>,
    )

    fun compare(
        neuralCandidates: List<ANS100NeuralAssociationDecoder.Candidate>,
        memoryHypotheses: List<ANS100WorkspaceConceptInputBridge.HypothesisReport>,
    ): List<Comparison> {
        require(neuralCandidates.size <= ANS100NeuralAssociationDecoder.MAX_CANDIDATES)
        require(memoryHypotheses.size <= ANS100ConceptMemory.MAX_HYPOTHESES)
        return neuralCandidates.map { candidate ->
            val first = normalize(candidate.firstLabel)
            val second = normalize(candidate.secondLabel)
            val explanations = memoryHypotheses.mapNotNull { report ->
                val from = normalize(report.fromLabel)
                val to = normalize(report.toLabel)
                val direction = when {
                    from == first && to == second -> Direction.SAME_DIRECTION
                    from == second && to == first -> Direction.REVERSED_DIRECTION
                    else -> return@mapNotNull null
                }
                Explanation(
                    hypothesisId = report.hypothesisId,
                    relationType = report.relationType,
                    direction = direction,
                    verificationStatus = report.verificationStatus,
                    premiseEvidenceCount = report.premiseEvidenceRefs.size,
                    supportingEvidenceCount = report.supportingEvidenceRefs.size,
                    contradictingEvidenceCount = report.contradictingEvidenceRefs.size,
                )
            }.sortedWith(compareBy<Explanation> { it.relationType.name }.thenBy { it.hypothesisId })

            val hasSupport = explanations.any {
                it.verificationStatus == ANS100ConceptMemory.VerificationStatus.SUPPORTED ||
                    it.verificationStatus == ANS100ConceptMemory.VerificationStatus.CONFLICTING
            }
            val hasCounterEvidence = explanations.any {
                it.verificationStatus == ANS100ConceptMemory.VerificationStatus.REFUTED ||
                    it.verificationStatus == ANS100ConceptMemory.VerificationStatus.CONFLICTING
            }
            val status = when {
                explanations.isEmpty() -> Status.NEURAL_ONLY
                hasSupport && hasCounterEvidence -> Status.MIXED_USER_ASSERTIONS
                hasSupport -> Status.USER_ASSERTIONS_SUPPORT
                hasCounterEvidence -> Status.USER_COUNTER_EVIDENCE
                else -> Status.MEMORY_PATH_UNRESOLVED
            }
            Comparison(candidate.candidateId, status, explanations)
        }
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
}
