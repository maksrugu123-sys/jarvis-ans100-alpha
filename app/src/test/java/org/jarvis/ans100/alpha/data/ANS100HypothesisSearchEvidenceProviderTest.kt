package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100HypothesisSearchEvidenceProviderTest {
    private fun request(
        action: ANS100HypothesisRevisionEngine.ReviewAction =
            ANS100HypothesisRevisionEngine.ReviewAction.SEEK_BOTH_SIDES,
        capacity: Int = 4,
    ) = ANS100HypothesisRevisionEngine.ReviewRequest(
        hypothesisId = "hypothesis:one",
        fromConceptId = "concept:from",
        relationType = ANS100ConceptMemory.RelationType.CAUSES,
        toConceptId = "concept:to",
        currentStatus = ANS100HypothesisRevisionEngine.Status.CANDIDATE_UNVERIFIED,
        action = action,
        additionalEvidenceCapacity = capacity,
        excludedEvidenceRefs = emptyList(),
        permittedSources = setOf(ANS100ConceptMemory.LearningSource.EXPLICIT_USER),
    )

    @Test
    fun searchResultsRemainUnverifiedAndKeepSourceProvenance() {
        var seenQuery: String? = null
        val provider = ANS100HypothesisSearchEvidenceProvider { query ->
            seenQuery = query
            listOf(
                AndroidWebSearchResult("Example", "https://example.org/fact", "A short snippet"),
                AndroidWebSearchResult("Second", "https://example.net/page", "Another snippet"),
            )
        }
        val batch = provider.search(request(), "Heat", "Expansion", userApproved = true)
        assertTrue(seenQuery!!.contains("Heat"))
        assertTrue(seenQuery!!.contains("CAUSES".lowercase()))
        assertEquals(2, batch.candidates.size)
        assertEquals(
            ANS100HypothesisSearchEvidenceProvider.CandidateStatus.UNVERIFIED_SEARCH_LEAD,
            batch.candidates.first().status,
        )
        assertTrue(batch.candidates.first().provenanceRef.startsWith("search-lead:"))
        assertEquals("https://example.org/fact", batch.candidates.first().url)
        assertNull(batch.skippedReason)
    }

    @Test
    fun conflictAndExhaustedRequestsNeverCallSearch() {
        var calls = 0
        val provider = ANS100HypothesisSearchEvidenceProvider {
            calls++
            emptyList()
        }
        val conflict = provider.search(
            request(ANS100HypothesisRevisionEngine.ReviewAction.MANUAL_CONFLICT_REVIEW),
            "A", "B", userApproved = true,
        )
        val exhausted = provider.search(
            request(ANS100HypothesisRevisionEngine.ReviewAction.EVIDENCE_CAPACITY_EXHAUSTED, 0),
            "A", "B", userApproved = true,
        )
        assertEquals(0, calls)
        assertEquals("manual_conflict_review", conflict.skippedReason)
        assertEquals("evidence_capacity_exhausted", exhausted.skippedReason)
    }

    @Test
    fun resultCountIsBoundedAndUnsafeUrlsAreDiscarded() {
        val provider = ANS100HypothesisSearchEvidenceProvider {
            listOf(
                AndroidWebSearchResult("One", "https://one.example/page", "1"),
                AndroidWebSearchResult("Unsafe", "http://two.example/page", "2"),
                AndroidWebSearchResult("Three", "https://three.example/page", "3"),
            )
        }
        val batch = provider.search(request(), "A", "B", userApproved = true, maxResults = 2)
        assertEquals(1, batch.candidates.size)
        assertEquals("One", batch.candidates.single().title)
    }

    @Test
    fun searchRequiresExplicitApprovalBeforeNetworkCall() {
        var calls = 0
        val provider = ANS100HypothesisSearchEvidenceProvider {
            calls++
            emptyList()
        }
        assertFalse(runCatching {
            provider.search(request(), "A", "B", userApproved = false)
        }.isSuccess)
        assertEquals(0, calls)
    }

    @Test
    fun blankOrIdenticalLabelsAreRejectedBeforeNetworkCall() {
        var calls = 0
        val provider = ANS100HypothesisSearchEvidenceProvider {
            calls++
            emptyList()
        }
        assertFalse(runCatching { provider.search(request(), " ", "B", userApproved = true) }.isSuccess)
        assertFalse(runCatching { provider.search(request(), "A", "A", userApproved = true) }.isSuccess)
        assertEquals(0, calls)
    }
}
