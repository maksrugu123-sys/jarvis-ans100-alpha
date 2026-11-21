package org.jarvis.ans100.alpha.data

import java.net.URI
import java.util.Locale

/**
 * Audits duplicate pages and host overlap across extracted passage candidates.
 * Different hosts are not treated as independent publishers.
 */
class ANS100HypothesisSourceAudit(
    private val maxPages: Int = DEFAULT_MAX_PAGES,
) {
    enum class Assessment {
        DUPLICATE_PAGE_CONTENT,
        DUPLICATE_URL,
        SAME_HOST_AS_EARLIER_SOURCE,
        DISTINCT_HOST_UNVERIFIED,
    }

    data class SourceRecord(
        val sourceUrl: String,
        val sourceHost: String,
        val pageContentSha256: String,
        val searchLeadRef: String,
        val fetchedAtEpochMs: Long,
    )

    data class SourceAssessment(
        val source: SourceRecord,
        val assessment: Assessment,
        val duplicateOfPageSha256: String?,
        val sameHostAsPageSha256: String?,
    )

    data class AuditReport(
        val assessments: List<SourceAssessment>,
        val uniquePageCount: Int,
        val distinctHostCount: Int,
        val truncated: Boolean,
        val independenceConfirmed: Boolean = false,
    )

    init { require(maxPages in 1..MAX_PAGES_HARD_LIMIT) }

    fun audit(passages: List<ANS100HypothesisPassageExtractor.PassageCandidate>): AuditReport {
        require(passages.size <= MAX_INPUT_PASSAGES) { "source audit input exceeds its bounded capacity" }
        val pages = LinkedHashMap<String, SourceRecord>()
        passages.forEach { passage ->
            require(passage.pageContentSha256.matches(Regex("[0-9a-f]{64}"))) {
                "invalid page content digest"
            }
            val uri = runCatching { URI(passage.sourceUrl) }.getOrNull()
                ?: throw IllegalArgumentException("source URL is invalid")
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            require(uri.scheme.equals("https", true) && !host.isNullOrBlank() && uri.userInfo == null) {
                "source URL must be HTTPS and include a hostname"
            }
            require(host == passage.sourceHost.lowercase(Locale.ROOT).trimEnd('.')) {
                "source host does not match the source URL"
            }
            val key = passage.pageContentSha256 + "|" + canonicalUrl(uri)
            pages.putIfAbsent(
                key,
                SourceRecord(
                    sourceUrl = canonicalUrl(uri),
                    sourceHost = host,
                    pageContentSha256 = passage.pageContentSha256,
                    searchLeadRef = passage.searchLeadRef,
                    fetchedAtEpochMs = passage.fetchedAtEpochMs,
                ),
            )
        }

        val retained = pages.values.take(maxPages)
        val seenDigests = LinkedHashMap<String, SourceRecord>()
        val seenUrls = LinkedHashMap<String, SourceRecord>()
        val firstByHost = LinkedHashMap<String, SourceRecord>()
        val assessments = retained.map { source ->
            val sameContent = seenDigests[source.pageContentSha256]
            val sameUrl = seenUrls[source.sourceUrl]
            val sameHost = firstByHost[source.sourceHost]
            val assessment = when {
                sameContent != null -> Assessment.DUPLICATE_PAGE_CONTENT
                sameUrl != null -> Assessment.DUPLICATE_URL
                sameHost != null -> Assessment.SAME_HOST_AS_EARLIER_SOURCE
                else -> Assessment.DISTINCT_HOST_UNVERIFIED
            }
            if (sameContent == null) seenDigests[source.pageContentSha256] = source
            if (sameUrl == null) seenUrls[source.sourceUrl] = source
            if (sameHost == null) firstByHost[source.sourceHost] = source
            SourceAssessment(
                source = source,
                assessment = assessment,
                duplicateOfPageSha256 = sameContent?.pageContentSha256 ?: sameUrl?.pageContentSha256,
                sameHostAsPageSha256 = sameHost?.pageContentSha256,
            )
        }
        return AuditReport(
            assessments = assessments,
            uniquePageCount = retained.size,
            distinctHostCount = retained.map { it.sourceHost }.distinct().size,
            truncated = pages.size > maxPages,
        )
    }

    private fun canonicalUrl(uri: URI): String {
        val normalized = uri.normalize()
        val host = normalized.host.lowercase(Locale.ROOT).trimEnd('.')
        val port = if (normalized.port == 443 || normalized.port == -1) "" else ":" + normalized.port
        val path = normalized.rawPath.orEmpty().ifEmpty { "/" }
        val query = normalized.rawQuery?.let { "?" + it }.orEmpty()
        return "https://" + host + port + path + query
    }

    companion object {
        const val DEFAULT_MAX_PAGES = 24
        const val MAX_PAGES_HARD_LIMIT = 48
        const val MAX_INPUT_PASSAGES = 288
    }
}
