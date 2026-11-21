package org.jarvis.ans100.alpha.data

import org.junit.Assert.*
import org.junit.Test

class ANS100HypothesisManualReviewDecisionTest {
    private val packetBuilder = ANS100HypothesisEvidenceReviewPacketBuilder()
    private val analyzer = ANS100HypothesisClaimPolarityAnalyzer()
    private val decisions = ANS100HypothesisManualReviewDecision()

    private fun packet(): ANS100HypothesisEvidenceReviewPacketBuilder.Packet {
        val page = ANS100HypothesisPageVerifier.PageReport(
            "revision:heat-expansion", "search-lead:one",
            "https://example.org/article", "https://example.org/article",
            "Heat and expansion", "A page about heat.",
            "Heat causes expansion. Heat and expansion are discussed.",
            "a".repeat(64), 1234L,
            ANS100HypothesisPageVerifier.Relevance.BOTH_ENDPOINTS_MENTIONED,
        )
        val extraction = ANS100HypothesisPassageExtractor().extract(page, "heat", "expansion")
        val audit = ANS100HypothesisSourceAudit().audit(extraction.candidates)
        val analyses = extraction.candidates.map {
            analyzer.analyze(it, "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion")
        }
        val request = ANS100HypothesisRevisionEngine.ReviewRequest(
            "revision:heat-expansion", "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion",
            ANS100HypothesisRevisionEngine.Status.CANDIDATE_UNVERIFIED,
            ANS100HypothesisRevisionEngine.ReviewAction.SEEK_BOTH_SIDES, 16,
            listOf("premise:one"),
            setOf(ANS100ConceptMemory.LearningSource.EXPLICIT_USER, ANS100ConceptMemory.LearningSource.VERIFIED_TOOL),
        )
        return packetBuilder.build(request, page, extraction, audit, analyses)
    }

    @Test fun confirmedSupportBecomesOneExplicitUserObservation() {
        val packet = packet()
        val decision = decisions.record(
            packet, "decision-001",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            listOf(packet.passages.first().passageRef), "Я проверил фрагмент и подтверждаю связь.",
            10_000L,
        )
        val evidence = decisions.toRelationEvidence(packet, decision)
        assertNotNull(evidence)
        assertEquals("heat", evidence!!.fromConceptId)
        assertEquals(ANS100ConceptMemory.RelationType.CAUSES, evidence.type)
        assertEquals("expansion", evidence.toConceptId)
        assertTrue(evidence.supports)
        assertEquals(ANS100ConceptMemory.LearningSource.EXPLICIT_USER, evidence.source)
        assertTrue(evidence.evidenceRef.startsWith("manual-review:"))
    }

    @Test fun confirmedContradictionBecomesNegativeObservation() {
        val packet = packet()
        val decision = decisions.record(
            packet, "decision-002",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_CONTRADICTION,
            listOf(packet.passages.first().passageRef), "Я проверил контекст и подтверждаю противоречие.",
            10_001L,
        )
        assertFalse(decisions.toRelationEvidence(packet, decision)!!.supports)
    }

    @Test fun insufficientEvidenceNeverBecomesRelationEvidence() {
        val packet = packet()
        val decision = decisions.record(
            packet, "decision-003",
            ANS100HypothesisManualReviewDecision.Outcome.INSUFFICIENT_EVIDENCE,
            emptyList(), "Недостаточно контекста для вывода.", 10_002L,
        )
        assertNull(decisions.toRelationEvidence(packet, decision))
    }

    @Test fun untrustedSourceNeverBecomesRelationEvidence() {
        val packet = packet()
        val decision = decisions.record(
            packet, "decision-004",
            ANS100HypothesisManualReviewDecision.Outcome.SOURCE_UNTRUSTED,
            emptyList(), "Источник не заслуживает доверия.", 10_003L,
        )
        assertNull(decisions.toRelationEvidence(packet, decision))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPassageNotPresentInPacket() {
        decisions.record(
            packet(), "decision-005",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            listOf("passage:invented"), "Проверено.", 10_004L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPacketChangedAfterReview() {
        val packet = packet()
        val decision = decisions.record(
            packet, "decision-006",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            listOf(packet.passages.first().passageRef), "Проверено.", 10_005L,
        )
        val changed = packet.copy(pageTitle = "Changed title")
        decisions.toRelationEvidence(changed, decision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicatePassageReferences() {
        val packet = packet()
        val ref = packet.passages.first().passageRef
        decisions.record(
            packet, "decision-007",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            listOf(ref, ref), "Проверено.", 10_006L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun confirmationRequiresPassageReference() {
        decisions.record(
            packet(), "decision-008",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            emptyList(), "Проверено.", 10_007L,
        )
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsModifiedDecisionOutcome() {
        val packet = packet()
        val decision = decisions.record(
            packet, "decision-009",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            listOf(packet.passages.first().passageRef), "Проверено.", 10_008L,
        )
        decisions.toRelationEvidence(
            packet,
            decision.copy(outcome = ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_CONTRADICTION),
        )
    }

}
