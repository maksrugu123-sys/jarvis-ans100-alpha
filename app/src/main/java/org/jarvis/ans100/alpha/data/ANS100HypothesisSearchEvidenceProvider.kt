package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Explicit-call adapter from a hypothesis review request to bounded web-search leads.
 *
 * Search-result snippets are untrusted discovery material, NOT verified evidence.
 * This class never writes the revision ledger, changes hypothesis status, or trains
 * neural connections. A caller must obtain user approval before invoking search.
 */
class ANS100HypothesisSearchEvidenceProvider(
    private val search: (String) -> List<AndroidWebSearchResult>,
) {
    enum class CandidateStatus {
        UNVERIFIED_SEARCH_LEAD,
    }

    data class SearchCandidate(
        val hypothesisId: String,
        val action: ANS100HypothesisRevisionEngine.ReviewAction,
        val title: String,
        val url: String,
        val snippet: String,
        val provenanceRef: String,
        val status: CandidateStatus = CandidateStatus.UNVERIFIED_SEARCH_LEAD,
    )

    data class SearchBatch(
        val query: String?,
        val candidates: List<SearchCandidate>,
        val skippedReason: String?,
    )

    /**
     * This method performs network I/O only when explicitly called by its owner.
     * The app's dialogue pipeline must not call it automatically on every turn.
     */
    fun search(
        request: ANS100HypothesisRevisionEngine.ReviewRequest,
        fromLabel: String,
        toLabel: String,
        maxResults: Int = AndroidWebSearchService.MAX_RESULTS,
    ): SearchBatch {
        require(maxResults in 1..AndroidWebSearchService.MAX_RESULTS)
        val action = request.action
        if (action == ANS100HypothesisRevisionEngine.ReviewAction.MANUAL_CONFLICT_REVIEW) {
            return SearchBatch(null, emptyList(), "manual_conflict_review")
        }
        if (action == ANS100HypothesisRevisionEngine.ReviewAction.EVIDENCE_CAPACITY_EXHAUSTED ||
            request.additionalEvidenceCapacity <= 0
        ) {
            return SearchBatch(null, emptyList(), "evidence_capacity_exhausted")
        }
        val from = normalizeLabel(fromLabel)
        val to = normalizeLabel(toLabel)
        require(from.isNotEmpty() && to.isNotEmpty()) { "concept labels must not be blank" }
        require(from != to) { "hypothesis endpoints must be different" }

        // The query is deliberately neutral. Review direction is metadata, not
        // a prompt to bias the search engine toward support or contradiction.
        val relation = request.relationType.name.lowercase().replace('_', ' ')
        val query = "\"$from\" \"$relation\" \"$to\""
            .take(AndroidWebSearchService.MAX_QUERY_CHARS)
        val results = search(query).take(maxResults)
        val candidates = results.mapIndexedNotNull { index, result ->
            val url = result.url.takeIf { it.startsWith("https://") } ?: return@mapIndexedNotNull null
            val title = result.title.trim().take(AndroidWebSearchService.MAX_TITLE_CHARS)
            if (title.isEmpty()) return@mapIndexedNotNull null
            val snippet = result.snippet.trim().take(AndroidWebSearchService.MAX_SNIPPET_CHARS)
            SearchCandidate(
                hypothesisId = request.hypothesisId,
                action = action,
                title = title,
                url = url,
                snippet = snippet,
                provenanceRef = "search-lead:${digest(request.hypothesisId + "|" + index + "|" + url)}",
            )
        }
        return SearchBatch(query, candidates, null)
    }

    private fun normalizeLabel(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").take(ANS100ConceptMemory.MAX_LABEL_CHARS)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
