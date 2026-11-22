package org.jarvis.ans100.alpha.data
import java.net.URI
import java.util.Locale

/**
 * Bounded review artifact only. It never creates evidence, mutates revision
 * state, or trains the neural runtime.
 */
class ANS100HypothesisEvidenceReviewPacketBuilder(
    private val maxPassages: Int = DEFAULT_MAX_PASSAGES,
    private val maxSourceAssessments: Int = DEFAULT_MAX_SOURCE_ASSESSMENTS,
) {
    enum class PacketStatus { MANUAL_REVIEW_REQUIRED }
    enum class PassageDisposition { CANDIDATE_ONLY }
    enum class Warning {
        EXTRACTION_TRUNCATED, SOURCE_AUDIT_TRUNCATED, SOURCE_DUPLICATE_CONTENT,
        SOURCE_DUPLICATE_URL, SOURCE_HOST_OVERLAP, SOURCE_INDEPENDENCE_UNCONFIRMED,
        PASSAGE_AMBIGUOUS, PASSAGE_POSSIBLE_SUPPORT_ONLY, PASSAGE_POSSIBLE_CONTRADICTION_ONLY,
        NO_PASSAGES_EXTRACTED,
    }
    data class PassageReview(
        val passageRef: String, val excerpt: String, val sentenceSha256: String,
        val disposition: PassageDisposition,
        val polarity: ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate,
        val polarityReason: ANS100HypothesisClaimPolarityAnalyzer.Reason,
        val matchedCue: String?, val detectedNegation: String?,
        val sourceAssessment: ANS100HypothesisSourceAudit.Assessment,
        val sourceUrl: String = "",
        val pageContentSha256: String = "",
    )
    data class SourceReview(
        val canonicalUrl: String, val host: String, val pageContentSha256: String,
        val searchLeadRef: String, val assessment: ANS100HypothesisSourceAudit.Assessment,
        val duplicateOfPageSha256: String?, val sameHostAsPageSha256: String?,
        val independenceConfirmed: Boolean,
    )
    data class Packet(
        val status: PacketStatus, val hypothesisId: String, val fromConceptId: String,
        val relationType: ANS100ConceptMemory.RelationType, val toConceptId: String,
        val requestedAction: ANS100HypothesisRevisionEngine.ReviewAction,
        val currentRevisionStatus: ANS100HypothesisRevisionEngine.Status,
        val additionalEvidenceCapacity: Int, val excludedEvidenceRefs: List<String>,
        val permittedSources: Set<ANS100ConceptMemory.LearningSource>,
        val pageTitle: String, val pageDescription: String, val pageUrl: String,
        val pageContentSha256: String, val fetchedAtEpochMs: Long, val pageStatus: String,
        val passages: List<PassageReview>, val sources: List<SourceReview>,
        val warnings: Set<Warning>, val extractionTruncated: Boolean,
        val sourceAuditTruncated: Boolean, val sourceIndependenceConfirmed: Boolean = false,
        val directEvidenceCreated: Boolean = false, val revisionEngineUpdated: Boolean = false,
        val learningAllowed: Boolean = false,
    )
    init {
        require(maxPassages in 1..MAX_PASSAGES_HARD_LIMIT)
        require(maxSourceAssessments in 1..MAX_SOURCE_ASSESSMENTS_HARD_LIMIT)
    }
    fun build(
        request: ANS100HypothesisRevisionEngine.ReviewRequest,
        page: ANS100HypothesisPageVerifier.PageReport,
        extraction: ANS100HypothesisPassageExtractor.ExtractionReport,
        sourceAudit: ANS100HypothesisSourceAudit.AuditReport,
        analyses: List<ANS100HypothesisClaimPolarityAnalyzer.Analysis>,
    ): Packet {
        require(request.hypothesisId.isNotBlank())
        require(request.hypothesisId == page.hypothesisId && request.hypothesisId == extraction.hypothesisId) {
            "hypothesis IDs do not match"
        }
        require(page.status == PAGE_STATUS)
        require(page.contentSha256.matches(SHA256))
        val digest = page.contentSha256.lowercase(Locale.ROOT)
        require(extraction.pageContentSha256 == digest) { "page digest mismatch" }
        require(extraction.candidates.size <= maxPassages)
        require(sourceAudit.assessments.size <= maxSourceAssessments)
        require(!sourceAudit.independenceConfirmed)
        require(request.additionalEvidenceCapacity in 0..ANS100HypothesisRevisionEngine.MAX_EVIDENCE_PER_HYPOTHESIS)
        require(request.excludedEvidenceRefs.size <= MAX_EXCLUDED_REFS &&
            request.excludedEvidenceRefs.distinct().size == request.excludedEvidenceRefs.size)
        val pageUri = https(page.finalUrl)
        val host = pageUri.host.lowercase(Locale.ROOT).trimEnd('.')
        val candidates = extraction.candidates
        require(candidates.map { it.passageRef }.distinct().size == candidates.size)
        require(candidates.all {
            it.hypothesisId == request.hypothesisId && it.pageContentSha256 == digest &&
                it.sourceHost == host && it.sourceUrl == page.finalUrl &&
                it.searchLeadRef == page.searchLeadRef && it.fetchedAtEpochMs == page.fetchedAtEpochMs &&
                it.status == ANS100HypothesisPassageExtractor.Status.UNCLASSIFIED_PASSAGE &&
                it.sentenceSha256.matches(SHA256) && it.excerpt.isNotBlank() &&
                it.excerpt.length <= ANS100HypothesisPassageExtractor.MAX_EXCERPT_CHARS_HARD_LIMIT
        }) { "passage provenance mismatch" }
        require(analyses.size == candidates.size && analyses.map { it.passageRef }.distinct().size == analyses.size)
        val byRef = analyses.associateBy { it.passageRef }
        require(byRef.keys == candidates.map { it.passageRef }.toSet())
        analyses.forEach { a ->
            val c = candidates.single { it.passageRef == a.passageRef }
            require(a.hypothesisId == request.hypothesisId && a.pageContentSha256 == digest &&
                a.sentenceSha256 == c.sentenceSha256 && a.sourceUrl == c.sourceUrl &&
                a.sourceHost == c.sourceHost && a.excerpt == c.excerpt &&
                a.relationType == request.relationType && a.status == POLARITY_STATUS) {
                "polarity provenance or relation mismatch"
            }
        }
        val sources = sourceAudit.assessments.map { item ->
            val s = item.source
            val uri = https(s.sourceUrl)
            require(s.pageContentSha256.matches(SHA256))
            require(s.sourceHost == uri.host.lowercase(Locale.ROOT).trimEnd('.'))
            SourceReview(
                canonicalUrl(uri), s.sourceHost, s.pageContentSha256, s.searchLeadRef,
                item.assessment, item.duplicateOfPageSha256, item.sameHostAsPageSha256, false,
            )
        }
        require(sources.any {
            it.canonicalUrl == canonicalUrl(pageUri) && it.pageContentSha256 == digest &&
                it.searchLeadRef == page.searchLeadRef
        }) { "source audit omits inspected page" }
        val passageReviews = candidates.map { c ->
            val source = sources.firstOrNull {
                it.canonicalUrl == canonicalUrl(pageUri) && it.pageContentSha256 == c.pageContentSha256 &&
                    it.searchLeadRef == c.searchLeadRef
            } ?: throw IllegalArgumentException("passage source missing from audit")
            val a = byRef.getValue(c.passageRef)
            PassageReview(
                c.passageRef, c.excerpt, c.sentenceSha256, PassageDisposition.CANDIDATE_ONLY,
                a.polarityCandidate, a.reason, a.matchedCue, a.detectedNegation, source.assessment,
                c.sourceUrl, c.pageContentSha256,
            )
        }
        val warnings = linkedSetOf<Warning>()
        if (extraction.truncated) warnings += Warning.EXTRACTION_TRUNCATED
        if (sourceAudit.truncated) warnings += Warning.SOURCE_AUDIT_TRUNCATED
        if (!sourceAudit.independenceConfirmed) warnings += Warning.SOURCE_INDEPENDENCE_UNCONFIRMED
        if (passageReviews.isEmpty()) warnings += Warning.NO_PASSAGES_EXTRACTED
        if (passageReviews.any { it.polarity == ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.AMBIGUOUS }) warnings += Warning.PASSAGE_AMBIGUOUS
        if (passageReviews.any { it.polarity == ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.POSSIBLE_SUPPORT }) warnings += Warning.PASSAGE_POSSIBLE_SUPPORT_ONLY
        if (passageReviews.any { it.polarity == ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.POSSIBLE_CONTRADICTION }) warnings += Warning.PASSAGE_POSSIBLE_CONTRADICTION_ONLY
        sources.forEach {
            when (it.assessment) {
                ANS100HypothesisSourceAudit.Assessment.DUPLICATE_PAGE_CONTENT -> warnings += Warning.SOURCE_DUPLICATE_CONTENT
                ANS100HypothesisSourceAudit.Assessment.DUPLICATE_URL -> warnings += Warning.SOURCE_DUPLICATE_URL
                ANS100HypothesisSourceAudit.Assessment.SAME_HOST_AS_EARLIER_SOURCE -> warnings += Warning.SOURCE_HOST_OVERLAP
                ANS100HypothesisSourceAudit.Assessment.DISTINCT_HOST_UNVERIFIED -> Unit
            }
        }
        return Packet(
            PacketStatus.MANUAL_REVIEW_REQUIRED, request.hypothesisId, request.fromConceptId,
            request.relationType, request.toConceptId, request.action, request.currentStatus,
            request.additionalEvidenceCapacity, request.excludedEvidenceRefs.toList(),
            request.permittedSources.toSet(), page.pageTitle.take(180), page.description.take(320),
            canonicalUrl(pageUri), digest, page.fetchedAtEpochMs, page.status, passageReviews,
            sources, warnings, extraction.truncated, sourceAudit.truncated,
        )
    }
    private fun https(raw: String): URI {
        val uri = runCatching { URI(raw) }.getOrNull() ?: throw IllegalArgumentException("invalid URL")
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null)
        return uri
    }
    private fun canonicalUrl(uri: URI): String {
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        val port = if (uri.port == -1 || uri.port == 443) "" else ":" + uri.port
        val path = uri.normalize().rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?" + it }.orEmpty()
        return "https://" + host + port + path + query
    }
    companion object {
        const val DEFAULT_MAX_PASSAGES = 12
        const val MAX_PASSAGES_HARD_LIMIT = 12
        const val DEFAULT_MAX_SOURCE_ASSESSMENTS = 24
        const val MAX_SOURCE_ASSESSMENTS_HARD_LIMIT = 48
        const val MAX_EXCLUDED_REFS = 64
        private const val PAGE_STATUS = "FETCHED_PAGE_UNVERIFIED"
        private const val POLARITY_STATUS = "POLARITY_CANDIDATE_UNVERIFIED"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
