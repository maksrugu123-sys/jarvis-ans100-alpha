package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100HypothesisPassageExtractorTest {
    private fun page(text: String) = ANS100HypothesisPageVerifier.PageReport(
        hypothesisId = "hypothesis:heat-expansion",
        searchLeadRef = "search-lead:abc123",
        requestedUrl = "https://example.org/article",
        finalUrl = "https://example.org/article",
        pageTitle = "Heat and expansion",
        description = "",
        extractedText = text,
        contentSha256 = "a".repeat(64),
        fetchedAtEpochMs = 1234L,
        relevance = ANS100HypothesisPageVerifier.Relevance.BOTH_ENDPOINTS_MENTIONED,
    )

    @Test
    fun extractsOnlySentencesMentioningBothEndpointsAndKeepsProvenance() {
        val report = ANS100HypothesisPassageExtractor().extract(
            page("Heat is energy transfer. Heating a metal causes expansion. Expansion can occur in solids."),
            "Heat",
            "Expansion",
        )
        assertEquals(1, report.candidates.size)
        val candidate = report.candidates.single()
        assertTrue(candidate.excerpt.contains("Heating a metal causes expansion"))
        assertEquals("example.org", candidate.sourceHost)
        assertEquals("search-lead:abc123", candidate.searchLeadRef)
        assertEquals("a".repeat(64), candidate.pageContentSha256)
        assertEquals(64, candidate.sentenceSha256.length)
        assertTrue(candidate.passageRef.startsWith("page-passage:"))
        assertEquals(ANS100HypothesisPassageExtractor.Status.UNCLASSIFIED_PASSAGE, candidate.status)
    }

    @Test
    fun deduplicatesRepeatedSentencesAndUsesStableReferences() {
        val text = "Heat causes expansion.  heat causes expansion."
        val extractor = ANS100HypothesisPassageExtractor()
        val first = extractor.extract(page(text), "Heat", "Expansion")
        val second = extractor.extract(page(text), "Heat", "Expansion")
        assertEquals(1, first.candidates.size)
        assertEquals(first.candidates.single().passageRef, second.candidates.single().passageRef)
    }

    @Test
    fun boundsCandidateCountAndExcerptLength() {
        val text = (1..4).joinToString(" ") { "Heat causes expansion number $it." }
        val report = ANS100HypothesisPassageExtractor(
            maxPassages = 2,
            maxExcerptChars = 80,
        ).extract(page(text), "Heat", "Expansion")
        assertEquals(2, report.candidates.size)
        assertTrue(report.truncated)
        assertTrue(report.candidates.all { it.excerpt.length <= 80 })
    }

    @Test
    fun rejectsInvalidDigestAndNonHttpsProvenance() {
        val invalidDigest = page("Heat causes expansion.").copy(contentSha256 = "bad")
        assertFalse(runCatching {
            ANS100HypothesisPassageExtractor().extract(invalidDigest, "Heat", "Expansion")
        }.isSuccess)

        val insecureUrl = page("Heat causes expansion.").copy(finalUrl = "http://example.org/article")
        assertFalse(runCatching {
            ANS100HypothesisPassageExtractor().extract(insecureUrl, "Heat", "Expansion")
        }.isSuccess)
    }

    @Test
    fun neverCreatesCandidatesWhenOnlyOneEndpointIsMentioned() {
        val report = ANS100HypothesisPassageExtractor().extract(
            page("Heat is energy transfer. Metal rods change shape."),
            "Heat",
            "Expansion",
        )
        assertTrue(report.candidates.isEmpty())
    }
}
