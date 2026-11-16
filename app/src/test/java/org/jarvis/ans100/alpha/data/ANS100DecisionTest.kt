package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100DecisionTest {
    private val message = "Почему проект не запускается?"
    private val understanding = LocalUnderstandingPipeline().analyze(message)
    private val evidence = TypedEvidenceEncoder().encode(
        TypedEvidenceInput("turn-decision-1", message, understanding),
    )

    private fun selectedDecision(bundle: TypedEvidenceBundle = evidence) = ANS100Decision(
        turnId = bundle.turnId,
        topologyVersion = ANS100TopologyVersion.LEGACY_DIALOGUE_READOUT_V1,
        status = ANS100DecisionStatus.SELECTED,
        candidates = listOf(
            ANS100Candidate(
                candidateId = "answer_directly",
                summary = "Ответить по исходному запросу, не выдумывая причину сбоя",
                supportingEvidenceIds = listOf(
                    bundle.records.single { it.feature == EvidenceFeature.USER_MESSAGE_TEXT }.recordId,
                    bundle.records.single { it.feature == EvidenceFeature.CORE_INTENT_HINT }.recordId,
                ),
                activation = 0.4,
            ),
        ),
        selectedCandidateId = "answer_directly",
        responseGoal = "Объяснить, какие сведения нужны для диагностики",
        responseConstraints = listOf("Не утверждать причину сбоя без логов"),
        activePopulationIds = listOf("evidence_input", "critic"),
        processedEventCount = 12,
    )

    @Test
    fun selectedDecisionReferencesOnlyEvidenceFromItsTurn() {
        val decision = selectedDecision()
        decision.validateAgainst(evidence)
        assertEquals("answer_directly", decision.selectedCandidateId)
        assertEquals("UNCALIBRATED", decision.calibrationStatus)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEvidenceReferenceFromAnotherTurn() {
        val decision = selectedDecision().copy(
            candidates = listOf(
                ANS100Candidate(
                    candidateId = "bad",
                    summary = "cross-turn",
                    supportingEvidenceIds = listOf("not-in-this-turn"),
                ),
            ),
            selectedCandidateId = "bad",
        )
        decision.validateAgainst(evidence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSelectedStatusWithoutSelectedCandidate() {
        ANS100Decision(
            turnId = evidence.turnId,
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            status = ANS100DecisionStatus.SELECTED,
            candidates = emptyList(),
            responseGoal = "Answer",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun budgetExhaustionMustBeExplicitlyRepresentedAsUncertainty() {
        ANS100Decision(
            turnId = evidence.turnId,
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            status = ANS100DecisionStatus.ABSTAINED,
            candidates = emptyList(),
            abstentionReason = "Compute budget exhausted",
            responseGoal = "Ask user to retry",
            computeBudgetExhausted = true,
        )
    }

    @Test
    fun handoffPreservesOriginalMessageAndMarksQwenAsRealizerOnly() {
        val handoff = ANS100QwenHandoffBuilder().build(
            originalUserMessage = message,
            understanding = understanding,
            evidence = evidence,
            decision = selectedDecision(),
        )
        assertEquals(evidence.turnId, handoff.turnId)
        assertTrue(handoff.promptContext.contains(message))
        assertTrue(handoff.promptContext.contains("ANS-100 DECISION"))
        assertTrue(handoff.promptContext.contains("QWEN ROLE"))
        assertTrue(handoff.promptContext.contains("do not execute tools or actions"))
        assertTrue(handoff.promptContext.length <= ANS100QwenHandoff.MAX_CONTEXT_CHARS)
        assertEquals(evidence.records.map { it.recordId }, handoff.includedEvidenceIds)
    }

    @Test
    fun handoffCarriesUnresolvedConceptHypothesesAsExplicitlyUnverified() {
        val report = ANS100WorkspaceConceptInputBridge.HypothesisReport(
            hypothesisId = "hyp:r1:r2", fromLabel = "Собака",
            relationType = ANS100ConceptMemory.RelationType.IS_A, toLabel = "Животное",
            premiseEvidenceRefs = listOf("workspace-memory:r1", "workspace-memory:r2"),
            verificationStatus = ANS100ConceptMemory.VerificationStatus.UNRESOLVED,
            supportingEvidenceRefs = emptyList(), contradictingEvidenceRefs = emptyList(),
        )
        val handoff = ANS100QwenHandoffBuilder().build(
            originalUserMessage = message, understanding = understanding,
            evidence = evidence, decision = selectedDecision(), relationHypotheses = listOf(report),
        )
        assertTrue(handoff.promptContext.contains("Собака --IS_A--> Животное"))
        assertTrue(handoff.promptContext.contains("verification=UNRESOLVED"))
        assertTrue(handoff.promptContext.contains("Treat UNRESOLVED as unverified"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun handoffRejectsTooManyRelationHypotheses() {
        val report = ANS100WorkspaceConceptInputBridge.HypothesisReport(
            hypothesisId = "hyp:r1:r2", fromLabel = "A",
            relationType = ANS100ConceptMemory.RelationType.IS_A, toLabel = "B",
            premiseEvidenceRefs = listOf("r1", "r2"),
            verificationStatus = ANS100ConceptMemory.VerificationStatus.UNRESOLVED,
            supportingEvidenceRefs = emptyList(), contradictingEvidenceRefs = emptyList(),
        )
        ANS100QwenHandoffBuilder().build(
            originalUserMessage = message, understanding = understanding,
            evidence = evidence, decision = selectedDecision(),
            relationHypotheses = List(13) { report.copy(hypothesisId = "hyp:r${it}:x") },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun handoffRejectsMismatchedOriginalMessage() {
        ANS100QwenHandoffBuilder().build(
            originalUserMessage = "Другой запрос",
            understanding = understanding,
            evidence = evidence,
            decision = selectedDecision(),
        )
    }

    @Test
    fun abstentionHasNoSelectedCandidate() {
        val abstained = ANS100Decision(
            turnId = evidence.turnId,
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            status = ANS100DecisionStatus.ABSTAINED,
            candidates = emptyList(),
            abstentionReason = "Недостаточно подтверждённых данных",
            uncertainty = setOf(ANS100UncertaintyKind.MISSING_EVIDENCE),
            missingEvidence = listOf("build log"),
            responseGoal = "Ask for build log",
        )
        assertFalse(abstained.selectedCandidateId != null)
        assertEquals(ANS100DecisionStatus.ABSTAINED, abstained.status)
    }
}
