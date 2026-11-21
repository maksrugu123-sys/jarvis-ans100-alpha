package org.jarvis.ans100.alpha.data
import org.junit.Assert.*
import org.junit.Test

class ANS100HypothesisEvidenceReviewPacketBuilderTest {
    private val builder = ANS100HypothesisEvidenceReviewPacketBuilder()
    private val analyzer = ANS100HypothesisClaimPolarityAnalyzer()
    private fun request() = ANS100HypothesisRevisionEngine.ReviewRequest(
        "revision:heat-expansion", "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion",
        ANS100HypothesisRevisionEngine.Status.CANDIDATE_UNVERIFIED,
        ANS100HypothesisRevisionEngine.ReviewAction.SEEK_BOTH_SIDES, 16, listOf("premise:one"),
        setOf(ANS100ConceptMemory.LearningSource.EXPLICIT_USER, ANS100ConceptMemory.LearningSource.VERIFIED_TOOL),
    )
    private fun page(id: String = "revision:heat-expansion", digest: String = "a".repeat(64)) =
        ANS100HypothesisPageVerifier.PageReport(
            id, "search-lead:one", "https://example.org/article", "https://example.org/article",
            "Heat and expansion", "A page about heat.", "Heat causes expansion. Heat and expansion are discussed.",
            digest, 1234L, ANS100HypothesisPageVerifier.Relevance.BOTH_ENDPOINTS_MENTIONED,
        )
    private data class Fixture(
        val page: ANS100HypothesisPageVerifier.PageReport,
        val extraction: ANS100HypothesisPassageExtractor.ExtractionReport,
        val audit: ANS100HypothesisSourceAudit.AuditReport,
        val analyses: List<ANS100HypothesisClaimPolarityAnalyzer.Analysis>,
    )
    private fun fixture(p: ANS100HypothesisPageVerifier.PageReport = page()): Fixture {
        val e = ANS100HypothesisPassageExtractor().extract(p, "heat", "expansion")
        val a = ANS100HypothesisSourceAudit().audit(e.candidates)
        val analyses = e.candidates.map {
            analyzer.analyze(it, "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion")
        }
        return Fixture(p, e, a, analyses)
    }
    @Test fun composesCandidateOnlyPacketWithoutPromotion() {
        val f = fixture()
        val packet = builder.build(request(), f.page, f.extraction, f.audit, f.analyses)
        assertEquals(ANS100HypothesisEvidenceReviewPacketBuilder.PacketStatus.MANUAL_REVIEW_REQUIRED, packet.status)
        assertEquals(2, packet.passages.size)
        assertTrue(packet.passages.all { it.disposition == ANS100HypothesisEvidenceReviewPacketBuilder.PassageDisposition.CANDIDATE_ONLY })
        assertFalse(packet.sourceIndependenceConfirmed); assertFalse(packet.directEvidenceCreated)
        assertFalse(packet.revisionEngineUpdated); assertFalse(packet.learningAllowed)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsHypothesisIdMismatch() {
        val f = fixture(page(id = "revision:other"))
        builder.build(request(), f.page, f.extraction, f.audit, f.analyses)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPageDigestMismatch() {
        val f = fixture()
        builder.build(request(), f.page.copy(contentSha256 = "d".repeat(64)), f.extraction, f.audit, f.analyses)
    }
    @Test fun keepsSourceIndependenceUnconfirmed() {
        val f = fixture()
        val packet = builder.build(request(), f.page, f.extraction, f.audit, f.analyses)
        assertEquals(1, packet.sources.size)
        assertFalse(packet.sources.single().independenceConfirmed)
        assertTrue(packet.warnings.contains(ANS100HypothesisEvidenceReviewPacketBuilder.Warning.SOURCE_INDEPENDENCE_UNCONFIRMED))
    }
    @Test fun ambiguousPolarityRemainsAmbiguous() {
        val p = page().copy(extractedText = "Heat and expansion are discussed together.")
        val f = fixture(p)
        val ambiguous = f.extraction.candidates.map {
            analyzer.analyze(it, "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion")
        }
        val packet = builder.build(request(), f.page, f.extraction, f.audit, ambiguous)
        assertTrue(packet.passages.all { it.polarity == ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.AMBIGUOUS })
        assertTrue(packet.warnings.contains(ANS100HypothesisEvidenceReviewPacketBuilder.Warning.PASSAGE_AMBIGUOUS))
    }
    @Test fun reportsExtractionTruncation() {
        val p = page()
        val e = ANS100HypothesisPassageExtractor(maxPassages = 1).extract(p, "heat", "expansion")
        val a = ANS100HypothesisSourceAudit().audit(e.candidates)
        val analyses = e.candidates.map { analyzer.analyze(it, "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion") }
        val packet = builder.build(request(), p, e, a, analyses)
        assertTrue(packet.extractionTruncated)
        assertTrue(packet.warnings.contains(ANS100HypothesisEvidenceReviewPacketBuilder.Warning.EXTRACTION_TRUNCATED))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPolarityDigestMismatch() {
        val f = fixture()
        builder.build(request(), f.page, f.extraction, f.audit, f.analyses.map { it.copy(pageContentSha256 = "f".repeat(64)) })
    }
}
