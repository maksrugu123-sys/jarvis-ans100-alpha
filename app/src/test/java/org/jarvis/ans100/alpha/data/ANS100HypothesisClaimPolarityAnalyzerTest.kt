package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100HypothesisClaimPolarityAnalyzerTest {
    private val analyzer = ANS100HypothesisClaimPolarityAnalyzer()

    private fun passage(text: String) = ANS100HypothesisPassageExtractor.PassageCandidate(
        hypothesisId = "revision:heat-expansion",
        passageRef = "page-passage:passage1",
        searchLeadRef = "search-lead:source1",
        sourceUrl = "https://example.org/article",
        sourceHost = "example.org",
        pageContentSha256 = "a".repeat(64),
        fetchedAtEpochMs = 100L,
        sentenceSha256 = "b".repeat(64),
        excerpt = text,
    )

    @Test
    fun explicitOrderedCauseCueBecomesOnlyPossibleSupport() {
        val result = analyzer.analyze(
            passage("Heat causes expansion."),
            "Heat",
            ANS100ConceptMemory.RelationType.CAUSES,
            "expansion",
        )
        assertEquals(ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.POSSIBLE_SUPPORT, result.polarityCandidate)
        assertEquals("causes", result.matchedCue)
        assertNull(result.detectedNegation)
        assertEquals("POLARITY_CANDIDATE_UNVERIFIED", result.status)
    }

    @Test
    fun explicitNegatedCauseCueBecomesPossibleContradiction() {
        val result = analyzer.analyze(
            passage("Heat does not cause expansion."),
            "Heat",
            ANS100ConceptMemory.RelationType.CAUSES,
            "expansion",
        )
        assertEquals(ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.POSSIBLE_CONTRADICTION, result.polarityCandidate)
        assertEquals("not", result.detectedNegation)
    }

    @Test
    fun notOnlyIsNotTreatedAsNegation() {
        val result = analyzer.analyze(
            passage("Heat not only causes expansion but also changes pressure."),
            "Heat",
            ANS100ConceptMemory.RelationType.CAUSES,
            "expansion",
        )
        assertEquals(ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.POSSIBLE_SUPPORT, result.polarityCandidate)
        assertNull(result.detectedNegation)
    }

    @Test
    fun coMentionWithoutRelationCueRemainsAmbiguous() {
        val result = analyzer.analyze(
            passage("Heat and expansion are often discussed together."),
            "Heat",
            ANS100ConceptMemory.RelationType.CAUSES,
            "expansion",
        )
        assertEquals(ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.AMBIGUOUS, result.polarityCandidate)
        assertNull(result.matchedCue)
    }

    @Test
    fun reversedEndpointOrderIsNotGuessed() {
        val result = analyzer.analyze(
            passage("Expansion is caused by heat."),
            "Heat",
            ANS100ConceptMemory.RelationType.CAUSES,
            "expansion",
        )
        assertEquals(ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.AMBIGUOUS, result.polarityCandidate)
    }

    @Test
    fun preservesPassageProvenanceAndSupportsRussianCue() {
        val result = analyzer.analyze(
            passage("Тепло вызывает расширение."),
            "Тепло",
            ANS100ConceptMemory.RelationType.CAUSES,
            "расширение",
        )
        assertEquals(ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.POSSIBLE_SUPPORT, result.polarityCandidate)
        assertEquals("page-passage:passage1", result.passageRef)
        assertEquals("a".repeat(64), result.pageContentSha256)
        assertEquals("example.org", result.sourceHost)
    }
}
