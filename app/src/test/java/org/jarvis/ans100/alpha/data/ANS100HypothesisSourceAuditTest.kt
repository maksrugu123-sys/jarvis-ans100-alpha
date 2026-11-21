package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100HypothesisSourceAuditTest {
    private fun passage(url: String, host: String, digest: String) =
        ANS100HypothesisPassageExtractor.PassageCandidate(
            hypothesisId = "hypothesis:heat-expansion",
            passageRef = "page-passage:$digest",
            searchLeadRef = "search-lead:$host",
            sourceUrl = url,
            sourceHost = host,
            pageContentSha256 = digest,
            fetchedAtEpochMs = 100L,
            sentenceSha256 = "b".repeat(64),
            excerpt = "Heat causes expansion.",
        )

    @Test
    fun flagsSameContentAcrossDifferentUrlsAsDuplicate() {
        val digest = "a".repeat(64)
        val report = ANS100HypothesisSourceAudit().audit(listOf(
            passage("https://one.example/a", "one.example", digest),
            passage("https://two.example/copied", "two.example", digest),
        ))
        assertEquals(2, report.assessments.size)
        assertEquals(ANS100HypothesisSourceAudit.Assessment.DISTINCT_HOST_UNVERIFIED, report.assessments[0].assessment)
        assertEquals(ANS100HypothesisSourceAudit.Assessment.DUPLICATE_PAGE_CONTENT, report.assessments[1].assessment)
        assertFalse(report.independenceConfirmed)
    }

    @Test
    fun flagsSameHostAndDoesNotCallDifferentHostsIndependent() {
        val report = ANS100HypothesisSourceAudit().audit(listOf(
            passage("https://example.org/a", "example.org", "a".repeat(64)),
            passage("https://example.org/b", "example.org", "c".repeat(64)),
            passage("https://other.org/c", "other.org", "d".repeat(64)),
        ))
        assertEquals(ANS100HypothesisSourceAudit.Assessment.SAME_HOST_AS_EARLIER_SOURCE, report.assessments[1].assessment)
        assertEquals(ANS100HypothesisSourceAudit.Assessment.DISTINCT_HOST_UNVERIFIED, report.assessments[2].assessment)
        assertEquals(2, report.distinctHostCount)
        assertFalse(report.independenceConfirmed)
    }

    @Test
    fun deduplicatesRepeatedPassagesFromOnePage() {
        val digest = "e".repeat(64)
        val item = passage("https://example.org/article#section", "example.org", digest)
        val report = ANS100HypothesisSourceAudit().audit(listOf(item, item.copy(passageRef = "page-passage:other")))
        assertEquals(1, report.uniquePageCount)
        assertEquals(1, report.assessments.size)
    }

    @Test
    fun capsPagesAndRejectsHostMismatch() {
        val inputs = (1..3).map { index ->
            val host = "host" + index + ".example"
            passage("https://" + host + "/article", host, index.toString().repeat(64))
        }
        val capped = ANS100HypothesisSourceAudit(maxPages = 2).audit(inputs)
        assertEquals(2, capped.uniquePageCount)
        assertTrue(capped.truncated)

        val invalid = passage("https://actual.example/a", "claimed.example", "f".repeat(64))
        assertFalse(runCatching { ANS100HypothesisSourceAudit().audit(listOf(invalid)) }.isSuccess)
    }
}
