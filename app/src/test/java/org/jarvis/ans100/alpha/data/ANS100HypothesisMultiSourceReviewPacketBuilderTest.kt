package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100HypothesisMultiSourceReviewPacketBuilderTest {
    private val extractor = ANS100HypothesisPassageExtractor()
    private val analyzer = ANS100HypothesisClaimPolarityAnalyzer()
    private val builder = ANS100HypothesisMultiSourceReviewPacketBuilder()

    private fun request() = ANS100HypothesisRevisionEngine.ReviewRequest(
        "revision:heat-expansion",
        "heat",
        ANS100ConceptMemory.RelationType.CAUSES,
        "expansion",
        ANS100HypothesisRevisionEngine.Status.CANDIDATE_UNVERIFIED,
        ANS100HypothesisRevisionEngine.ReviewAction.SEEK_BOTH_SIDES,
        16,
        listOf("premise:one"),
        setOf(
            ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
            ANS100ConceptMemory.LearningSource.VERIFIED_TOOL,
        ),
    )

    private fun inspected(
        url: String,
        host: String,
        lead: String,
        digest: String,
        text: String,
    ): ANS100HypothesisMultiSourceReviewPacketBuilder.InspectedPage {
        val page = ANS100HypothesisPageVerifier.PageReport(
            hypothesisId = "revision:heat-expansion",
            searchLeadRef = lead,
            requestedUrl = url,
            finalUrl = url,
            pageTitle = "Heat and expansion",
            description = "A bounded test page.",
            extractedText = text,
            contentSha256 = digest,
            fetchedAtEpochMs = 1_000L + lead.length,
            relevance = ANS100HypothesisPageVerifier.Relevance.BOTH_ENDPOINTS_MENTIONED,
        )
        val extraction = extractor.extract(page, "heat", "expansion")
        val analyses = extraction.candidates.map {
            analyzer.analyze(it, "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion")
        }
        return ANS100HypothesisMultiSourceReviewPacketBuilder.InspectedPage(page, extraction, analyses)
    }

    @Test
    fun combinesPassagesAndAuditsAllSeparatelyInspectedPages() {
        val pages = listOf(
            inspected(
                "https://one.example/a", "one.example", "search-lead:a", "a".repeat(64),
                "Heat causes expansion.",
            ),
            inspected(
                "https://one.example/b", "one.example", "search-lead:b", "b".repeat(64),
                "Heat does not cause expansion.",
            ),
            inspected(
                "https://two.example/copied", "two.example", "search-lead:c", "a".repeat(64),
                "Heat causes expansion.",
            ),
        )

        val packet = builder.build(request(), pages)

        assertEquals(3, packet.sources.size)
        assertEquals(3, packet.sources.map { it.searchLeadRef }.distinct().size)
        assertEquals(3, packet.passages.size)
        assertTrue(packet.sources.any {
            it.assessment == ANS100HypothesisSourceAudit.Assessment.SAME_HOST_AS_EARLIER_SOURCE
        })
        assertTrue(packet.sources.any {
            it.assessment == ANS100HypothesisSourceAudit.Assessment.DUPLICATE_PAGE_CONTENT
        })
        assertFalse(packet.sourceIndependenceConfirmed)
        assertFalse(packet.directEvidenceCreated)
        assertFalse(packet.revisionEngineUpdated)
        assertFalse(packet.learningAllowed)
    }

    @Test
    fun includesInspectedPagesEvenWhenNoPassagesMatch() {
        val pages = listOf(
            inspected(
                "https://one.example/a", "one.example", "search-lead:a", "a".repeat(64),
                "Heat causes expansion.",
            ),
            inspected(
                "https://two.example/b", "two.example", "search-lead:b", "b".repeat(64),
                "A page mentioning heat but not the target concept.",
            ),
        )

        val packet = builder.build(request(), pages)

        assertEquals(2, packet.sources.size)
        assertEquals(1, packet.passages.size)
    }

    @Test
    fun rejectsRepeatedSearchLeadAsMultipleSources() {
        val page = inspected(
            "https://one.example/a", "one.example", "search-lead:a", "a".repeat(64),
            "Heat causes expansion.",
        )
        assertFalse(runCatching { builder.build(request(), listOf(page, page)) }.isSuccess)
    }

    @Test
    fun enforcesPageAndPassageBounds() {
        val page = inspected(
            "https://one.example/a", "one.example", "search-lead:a", "a".repeat(64),
            "Heat causes expansion.",
        )
        val capped = ANS100HypothesisMultiSourceReviewPacketBuilder(maxPages = 1, maxPassages = 1)
            .build(request(), listOf(page))
        assertTrue(capped.passages.size <= 1)
    }
}
