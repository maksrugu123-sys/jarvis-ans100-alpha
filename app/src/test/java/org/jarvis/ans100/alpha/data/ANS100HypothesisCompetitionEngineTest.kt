package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ANS100HypothesisCompetitionEngineTest {
    private fun candidate() = ANS100NeuralAssociationDecoder.Candidate(
        candidateId = "neural-association:" + "b".repeat(32),
        firstConceptId = "concept.dog",
        firstLabel = "Собака",
        secondConceptId = "concept.animal",
        secondLabel = "Животное",
        convergentNeuronIds = listOf(1200),
        activatedConnectionIds = listOf(
            "ans100-v4-evidence-general-association-left",
            "ans100-v4-evidence-general-association-right",
        ),
        evidenceRefs = listOf("memory:dog", "memory:animal"),
        activationHint = 1.0 / 3.0,
    )

    private fun report(status: ANS100ConceptMemory.VerificationStatus, from: String = "Собака") =
        ANS100WorkspaceConceptInputBridge.HypothesisReport(
            hypothesisId = "hyp:$status",
            fromLabel = from,
            relationType = ANS100ConceptMemory.RelationType.IS_A,
            toLabel = if (from == "Собака") "Животное" else "Собака",
            premiseEvidenceRefs = listOf("premise:a", "premise:b"),
            verificationStatus = status,
            supportingEvidenceRefs = if (status == ANS100ConceptMemory.VerificationStatus.SUPPORTED ||
                status == ANS100ConceptMemory.VerificationStatus.CONFLICTING) listOf("support") else emptyList(),
            contradictingEvidenceRefs = if (status == ANS100ConceptMemory.VerificationStatus.REFUTED ||
                status == ANS100ConceptMemory.VerificationStatus.CONFLICTING) listOf("counter") else emptyList(),
            supportingEvidenceSources = if (status == ANS100ConceptMemory.VerificationStatus.SUPPORTED ||
                status == ANS100ConceptMemory.VerificationStatus.CONFLICTING)
                setOf(ANS100ConceptMemory.LearningSource.EXPLICIT_USER) else emptySet(),
            contradictingEvidenceSources = if (status == ANS100ConceptMemory.VerificationStatus.REFUTED ||
                status == ANS100ConceptMemory.VerificationStatus.CONFLICTING)
                setOf(ANS100ConceptMemory.LearningSource.EXPLICIT_USER) else emptySet(),
        )

    @Test fun noMatchingMemoryPathRemainsNeuralOnly() {
        val result = ANS100HypothesisCompetitionEngine().compare(listOf(candidate()), emptyList()).single()
        assertEquals(ANS100HypothesisCompetitionEngine.Status.NEURAL_ONLY, result.status)
        assertEquals(0, result.explanations.size)
    }

    @Test fun unresolvedTypedPathRemainsAnAlternativeNotAProof() {
        val result = ANS100HypothesisCompetitionEngine().compare(
            listOf(candidate()),
            listOf(report(ANS100ConceptMemory.VerificationStatus.UNRESOLVED)),
        ).single()
        assertEquals(ANS100HypothesisCompetitionEngine.Status.MEMORY_PATH_UNRESOLVED, result.status)
        assertEquals(ANS100HypothesisCompetitionEngine.Direction.SAME_DIRECTION, result.explanations.single().direction)
    }

    @Test fun explicitSupportAndCounterEvidenceRemainDistinctStatuses() {
        val engine = ANS100HypothesisCompetitionEngine()
        assertEquals(
            ANS100HypothesisCompetitionEngine.Status.USER_ASSERTIONS_SUPPORT,
            engine.compare(listOf(candidate()), listOf(report(ANS100ConceptMemory.VerificationStatus.SUPPORTED))).single().status,
        )
        assertEquals(
            ANS100HypothesisCompetitionEngine.Status.USER_COUNTER_EVIDENCE,
            engine.compare(listOf(candidate()), listOf(report(ANS100ConceptMemory.VerificationStatus.REFUTED))).single().status,
        )
        assertEquals(
            ANS100HypothesisCompetitionEngine.Status.MIXED_USER_ASSERTIONS,
            engine.compare(listOf(candidate()), listOf(report(ANS100ConceptMemory.VerificationStatus.CONFLICTING))).single().status,
        )
    }

    @Test fun verifiedToolSupportIsNotMislabelledAsAUserAssertion() {
        val toolReport = report(ANS100ConceptMemory.VerificationStatus.SUPPORTED).copy(
            supportingEvidenceSources = setOf(ANS100ConceptMemory.LearningSource.VERIFIED_TOOL),
        )
        val result = ANS100HypothesisCompetitionEngine().compare(
            listOf(candidate()), listOf(toolReport),
        ).single()
        assertEquals(ANS100HypothesisCompetitionEngine.Status.VERIFIED_TOOL_SUPPORT, result.status)
        assertEquals(
            setOf(ANS100ConceptMemory.LearningSource.VERIFIED_TOOL),
            result.explanations.single().supportingEvidenceSources,
        )
    }

    @Test fun mixedUserAndToolEvidenceKeepsItsMixedProvenance() {
        val mixed = report(ANS100ConceptMemory.VerificationStatus.SUPPORTED).copy(
            supportingEvidenceSources = setOf(
                ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
                ANS100ConceptMemory.LearningSource.VERIFIED_TOOL,
            ),
        )
        val result = ANS100HypothesisCompetitionEngine().compare(
            listOf(candidate()), listOf(mixed),
        ).single()
        assertEquals(ANS100HypothesisCompetitionEngine.Status.MIXED_SOURCE_SUPPORT, result.status)
    }

    @Test fun verifiedToolCounterEvidenceIsNotLabelledAsUserEvidence() {
        val toolReport = report(ANS100ConceptMemory.VerificationStatus.REFUTED).copy(
            contradictingEvidenceSources = setOf(ANS100ConceptMemory.LearningSource.VERIFIED_TOOL),
        )
        val result = ANS100HypothesisCompetitionEngine().compare(
            listOf(candidate()), listOf(toolReport),
        ).single()
        assertEquals(ANS100HypothesisCompetitionEngine.Status.VERIFIED_TOOL_COUNTER_EVIDENCE, result.status)
    }

    @Test fun unclassifiedEvidenceCannotBeSilentlyRelabelledAsAUserAssertion() {
        val partiallyUnclassified = report(ANS100ConceptMemory.VerificationStatus.CONFLICTING).copy(
            supportingEvidenceSources = emptySet(),
        )
        val result = ANS100HypothesisCompetitionEngine().compare(
            listOf(candidate()), listOf(partiallyUnclassified),
        ).single()
        assertEquals(ANS100HypothesisCompetitionEngine.Status.MIXED_EVIDENCE, result.status)
    }

    @Test fun reversedTypedPathIsRetainedAsDifferentExplanationDirection() {
        val result = ANS100HypothesisCompetitionEngine().compare(
            listOf(candidate()),
            listOf(report(ANS100ConceptMemory.VerificationStatus.UNRESOLVED, from = "Животное")),
        ).single()
        assertEquals(ANS100HypothesisCompetitionEngine.Direction.REVERSED_DIRECTION, result.explanations.single().direction)
    }
}
