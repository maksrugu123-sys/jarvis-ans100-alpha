package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class ANS100HypothesisPageVerifierTest {
    private class FakeConnection(
        url: URL,
        private val status: Int = 200,
        private val body: ByteArray = ByteArrayInputStream(
            """
                <html><head><title>Heat causes expansion</title>
                <meta name="description" content="A basic science example"></head>
                <body><p>Heating a metal rod causes thermal expansion.</p>
                <script>ignore this dangerous text</script></body></html>
            """.trimIndent().toByteArray()
        ).readBytes(),
        private val type: String = "text/html; charset=utf-8",
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun connect() {}
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentType(): String = type
    }

    private fun lead(url: String = "https://example.org/article") =
        ANS100HypothesisSearchEvidenceProvider.SearchCandidate(
            hypothesisId = "hypothesis:heat-expansion",
            action = ANS100HypothesisRevisionEngine.ReviewAction.SEEK_BOTH_SIDES,
            title = "Search title",
            url = url,
            snippet = "Search snippet",
            provenanceRef = "search-lead:abc123",
        )

    @Test
    fun readsBoundedPageAndReportsRelevanceWithoutVerifyingTruth() {
        lateinit var connection: FakeConnection
        val verifier = ANS100HypothesisPageVerifier(
            connectionFactory = { url -> FakeConnection(url).also { connection = it } },
            nowMillis = { 1234L },
            resolvesOnlyToPublicAddresses = { true },
        )
        val report = verifier.inspect(lead(), "Heat", "Expansion", userApproved = true)
        assertEquals("Heat causes expansion", report.pageTitle)
        assertEquals("A basic science example", report.description)
        assertTrue(report.extractedText.contains("thermal expansion"))
        assertFalse(report.extractedText.contains("ignore this dangerous text"))
        assertEquals(
            ANS100HypothesisPageVerifier.Relevance.BOTH_ENDPOINTS_MENTIONED,
            report.relevance,
        )
        assertEquals("FETCHED_PAGE_UNVERIFIED", report.status)
        assertEquals(1234L, report.fetchedAtEpochMs)
        assertEquals(64, report.contentSha256.length)
        assertTrue(connection.disconnected)
    }

    @Test
    fun requiresApprovalBeforeOpeningPage() {
        var calls = 0
        val verifier = ANS100HypothesisPageVerifier(
            connectionFactory = { calls++; FakeConnection(it) },
            resolvesOnlyToPublicAddresses = { true },
        )
        assertFalse(runCatching {
            verifier.inspect(lead(), "Heat", "Expansion", userApproved = false)
        }.isSuccess)
        assertEquals(0, calls)
    }

    @Test
    fun refusesRedirectsNonHtmlAndUnsafeHosts() {
        val redirect = ANS100HypothesisPageVerifier(
            connectionFactory = { FakeConnection(it, status = 302) },
            resolvesOnlyToPublicAddresses = { true },
        )
        assertFalse(runCatching {
            redirect.inspect(lead(), "Heat", "Expansion", true)
        }.isSuccess)

        val pdf = ANS100HypothesisPageVerifier(
            connectionFactory = { FakeConnection(it, type = "application/pdf") },
            resolvesOnlyToPublicAddresses = { true },
        )
        assertFalse(runCatching {
            pdf.inspect(lead(), "Heat", "Expansion", true)
        }.isSuccess)

        val unsafe = ANS100HypothesisPageVerifier()
        assertFalse(runCatching {
            unsafe.inspect(lead("https://127.0.0.1/private"), "Heat", "Expansion", true)
        }.isSuccess)
    }


    @Test
    fun refusesDomainsThatResolveToPrivateAddresses() {
        var calls = 0
        val verifier = ANS100HypothesisPageVerifier(
            connectionFactory = { calls++; FakeConnection(it) },
            resolvesOnlyToPublicAddresses = { false },
        )
        assertFalse(runCatching {
            verifier.inspect(lead(), "Heat", "Expansion", true)
        }.isSuccess)
        assertEquals(0, calls)
    }

    @Test
    fun enforcesPageByteLimit() {
        val oversized = ByteArray(ANS100HypothesisPageVerifier.MAX_PAGE_BYTES + 1) { 65 }
        val verifier = ANS100HypothesisPageVerifier(
            connectionFactory = { FakeConnection(it, body = oversized) },
            resolvesOnlyToPublicAddresses = { true },
        )
        assertFalse(runCatching {
            verifier.inspect(lead(), "Heat", "Expansion", true)
        }.isSuccess)
    }
}
