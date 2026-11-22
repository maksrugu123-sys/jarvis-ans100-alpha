package org.jarvis.ans100.alpha.data

/**
 * Combines separately inspected pages into one bounded, provenance-preserving
 * manual-review packet. It never authenticates publishers, creates evidence,
 * changes hypothesis status, or trains the neural runtime.
 */
class ANS100HypothesisMultiSourceReviewPacketBuilder(
    private val sourceAudit: ANS100HypothesisSourceAudit = ANS100HypothesisSourceAudit(),
    private val pagePacketBuilder: ANS100HypothesisEvidenceReviewPacketBuilder =
        ANS100HypothesisEvidenceReviewPacketBuilder(),
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val maxPassages: Int = DEFAULT_MAX_PASSAGES,
) {
    data class InspectedPage(
        val page: ANS100HypothesisPageVerifier.PageReport,
        val extraction: ANS100HypothesisPassageExtractor.ExtractionReport,
        val analyses: List<ANS100HypothesisClaimPolarityAnalyzer.Analysis>,
    )

    init {
        require(maxPages in 1..MAX_PAGES_HARD_LIMIT)
        require(maxPassages in 1..MAX_PASSAGES_HARD_LIMIT)
    }

    fun build(
        request: ANS100HypothesisRevisionEngine.ReviewRequest,
        inspectedPages: List<InspectedPage>,
    ): ANS100HypothesisEvidenceReviewPacketBuilder.Packet {
        require(inspectedPages.isNotEmpty()) { "at least one inspected page is required" }
        require(inspectedPages.size <= maxPages) { "too many inspected pages" }
        require(inspectedPages.map { it.page.searchLeadRef }.distinct().size == inspectedPages.size) {
            "the same search lead cannot be counted as multiple inspected sources"
        }
        require(inspectedPages.all {
            it.page.hypothesisId == request.hypothesisId &&
                it.extraction.hypothesisId == request.hypothesisId &&
                it.extraction.pageContentSha256 == it.page.contentSha256.lowercase()
        }) { "inspected pages do not belong to this hypothesis or have mismatched digests" }

        // Multiple search results may resolve to the exact same URL and bytes.
        // Keep one page in the packet; distinct URLs with identical bytes remain
        // separate audit records and are flagged as duplicate content.
        val uniquePages = inspectedPages.distinctBy { item -> pageKey(item.page) }
        require(uniquePages.size <= maxPages) { "too many unique inspected pages" }

        val records = uniquePages.map { item ->
            val uri = java.net.URI(item.page.finalUrl)
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null)
            ANS100HypothesisSourceAudit.SourceRecord(
                sourceUrl = item.page.finalUrl,
                sourceHost = uri.host.lowercase(java.util.Locale.ROOT).trimEnd('.'),
                pageContentSha256 = item.page.contentSha256.lowercase(java.util.Locale.ROOT),
                searchLeadRef = item.page.searchLeadRef,
                fetchedAtEpochMs = item.page.fetchedAtEpochMs,
            )
        }
        val audit = sourceAudit.auditRecords(records)
        require(audit.assessments.size == uniquePages.size) {
            "source audit omitted an inspected page"
        }

        var remainingPassages = maxPassages
        val pagePackets = uniquePages.map { item ->
            val retained = item.extraction.candidates.take(remainingPassages)
            remainingPassages -= retained.size
            val extraction = item.extraction.copy(
                candidates = retained,
                truncated = item.extraction.truncated || retained.size < item.extraction.candidates.size,
            )
            val retainedRefs = retained.map { it.passageRef }.toSet()
            val analyses = item.analyses.filter { it.passageRef in retainedRefs }
            require(analyses.size == retained.size && analyses.map { it.passageRef }.distinct().size == analyses.size) {
                "each retained passage must have exactly one polarity analysis"
            }
            pagePacketBuilder.build(request, item.page, extraction, audit, analyses)
        }

        val first = pagePackets.first()
        val combinedPassages = pagePackets.flatMap { it.passages }
        require(combinedPassages.size <= maxPassages)
        require(combinedPassages.map { it.passageRef }.distinct().size == combinedPassages.size)
        val combinedSources = first.sources
        require(combinedSources.size == uniquePages.size)

        return first.copy(
            passages = combinedPassages,
            sources = combinedSources,
            warnings = pagePackets.flatMap { it.warnings }.toSet(),
            extractionTruncated = pagePackets.any { it.extractionTruncated },
            sourceAuditTruncated = audit.truncated,
            sourceIndependenceConfirmed = false,
            directEvidenceCreated = false,
            revisionEngineUpdated = false,
            learningAllowed = false,
        )
    }

    private fun pageKey(page: ANS100HypothesisPageVerifier.PageReport): String {
        val uri = java.net.URI(page.finalUrl).normalize()
        val host = uri.host?.lowercase(java.util.Locale.ROOT)?.trimEnd('.')
            ?: throw IllegalArgumentException("inspected page has no hostname")
        val port = if (uri.port == -1 || uri.port == 443) "" else ":" + uri.port
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?" + it }.orEmpty()
        return page.contentSha256.lowercase(java.util.Locale.ROOT) + "|https://" + host + port + path + query
    }
    companion object {
        const val DEFAULT_MAX_PAGES = 12
        const val MAX_PAGES_HARD_LIMIT = 12
        const val DEFAULT_MAX_PASSAGES = 12
        const val MAX_PASSAGES_HARD_LIMIT = 12
    }
}
