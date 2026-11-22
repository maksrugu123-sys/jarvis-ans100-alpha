package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100HypothesisReviewHistoryComparatorTest {
    private fun entry(
        id: String,
        time: Long,
        outcome: ANS100HypothesisManualReviewDecision.Outcome,
        fingerprint: String,
        selected: List<String>,
        snapshot: ANS100HypothesisManualReviewLedger.ReviewSnapshot?,
    ) = ANS100HypothesisManualReviewLedger.Entry(
        decision = ANS100HypothesisManualReviewDecision.Decision(
            decisionId = id,
            packetFingerprint = fingerprint,
            hypothesisId = "hypothesis:heat-expansion",
            fromConceptId = "heat",
            relationType = ANS100ConceptMemory.RelationType.CAUSES,
            toConceptId = "expansion",
            outcome = outcome,
            selectedPassageRefs = selected,
            rationale = "manual review",
            reviewedAtEpochMs = time,
            decisionDigest = "digest-$id",
        ),
        evidence = null,
        state = ANS100HypothesisManualReviewLedger.State.RECORDED_NO_EVIDENCE,
        snapshot = snapshot,
    )

    private fun snapshot(
        sources: List<ANS100HypothesisManualReviewLedger.SourceSnapshot>,
        passages: List<ANS100HypothesisManualReviewLedger.PassageSnapshot>,
        warnings: List<String> = emptyList(),
    ) = ANS100HypothesisManualReviewLedger.ReviewSnapshot(
        hypothesisId = "hypothesis:heat-expansion",
        pageTitle = "Heat and expansion",
        pageUrl = "https://example.org",
        pageContentSha256 = "a".repeat(64),
        fetchedAtEpochMs = 1L,
        sources = sources,
        passages = passages,
        warnings = warnings,
    )

    private fun source(url: String, digest: String) =
        ANS100HypothesisManualReviewLedger.SourceSnapshot(
            canonicalUrl = url, host = "example.org", pageContentSha256 = digest,
            searchLeadRef = "lead:$url", assessment = "DISTINCT_HOST_UNVERIFIED",
        )

    private fun passage(ref: String, digest: String, polarity: String = "POSSIBLE_SUPPORT") =
        ANS100HypothesisManualReviewLedger.PassageSnapshot(
            passageRef = ref, excerpt = "excerpt-$digest", sentenceSha256 = digest,
            polarity = polarity, polarityReason = "CAUSAL_CUE",
            sourceAssessment = "DISTINCT_HOST_UNVERIFIED",
            sourceUrl = "https://example.org/$ref", pageContentSha256 = "b".repeat(64),
        )

    @Test
    fun reportsSourcePassageSelectionAndOutcomeDeltasWithoutMutatingEntries() {
        val old = entry(
            "review-old", 10L, ANS100HypothesisManualReviewDecision.Outcome.INSUFFICIENT_EVIDENCE,
            "old-fingerprint", listOf("p1"),
            snapshot(
                listOf(source("https://example.org/a", "1".repeat(64)), source("https://example.org/b", "2".repeat(64))),
                listOf(passage("p1", "3".repeat(64)), passage("p2", "4".repeat(64))),
                listOf("WARNING_OLD"),
            ),
        )
        val newer = entry(
            "review-new", 20L, ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            "new-fingerprint", listOf("p2", "p3"),
            snapshot(
                listOf(source("https://example.org/a", "9".repeat(64)), source("https://example.org/c", "5".repeat(64))),
                listOf(passage("p2", "8".repeat(64)), passage("p3", "6".repeat(64))),
                listOf("WARNING_NEW"),
            ),
        )

        val diff = ANS100HypothesisReviewHistoryComparator.compare(old, newer)

        assertTrue(diff.outcomeChanged)
        assertTrue(diff.packetChanged)
        assertEquals(listOf("p2", "p3"), diff.addedSelectedPassageRefs)
        assertEquals(listOf("p1"), diff.removedSelectedPassageRefs)
        assertEquals(listOf("https://example.org/c"), diff.addedSourceUrls)
        assertEquals(listOf("https://example.org/b"), diff.removedSourceUrls)
        assertEquals(listOf("https://example.org/a"), diff.changedSourceUrls)
        assertEquals(listOf("p3"), diff.addedPassageRefs)
        assertEquals(listOf("p1"), diff.removedPassageRefs)
        assertEquals(listOf("p2"), diff.changedPassageRefs)
        assertEquals(listOf("WARNING_NEW"), diff.addedWarnings)
        assertEquals(listOf("WARNING_OLD"), diff.removedWarnings)
        assertTrue(diff.snapshotsComparable)
        assertEquals("review-old", old.decision.decisionId)
        assertEquals(listOf("p1"), old.decision.selectedPassageRefs)
    }

    @Test
    fun missingOldSnapshotDoesNotPretendThatSourcesWereRemoved() {
        val old = entry(
            "review-old", 10L, ANS100HypothesisManualReviewDecision.Outcome.INSUFFICIENT_EVIDENCE,
            "old", emptyList(), null,
        )
        val newer = entry(
            "review-new", 20L, ANS100HypothesisManualReviewDecision.Outcome.INSUFFICIENT_EVIDENCE,
            "new", emptyList(), snapshot(listOf(source("https://example.org/a", "1".repeat(64))), emptyList()),
        )

        val diff = ANS100HypothesisReviewHistoryComparator.compare(old, newer)

        assertFalse(diff.snapshotsComparable)
        assertTrue(diff.addedSourceUrls.isEmpty())
        assertTrue(diff.removedSourceUrls.isEmpty())
        assertTrue(diff.addedPassageRefs.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsComparisonAcrossDifferentHypotheses() {
        val old = entry("review-old", 10L, ANS100HypothesisManualReviewDecision.Outcome.INSUFFICIENT_EVIDENCE, "a", emptyList(), null)
        val other = old.copy(decision = old.decision.copy(hypothesisId = "other"))
        ANS100HypothesisReviewHistoryComparator.compare(old, other)
    }
}
