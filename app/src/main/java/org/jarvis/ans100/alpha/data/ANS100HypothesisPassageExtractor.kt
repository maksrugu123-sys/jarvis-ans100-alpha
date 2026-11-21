package org.jarvis.ans100.alpha.data

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Extracts bounded, traceable passages that mention both endpoints of a relation.
 *
 * A passage is only a review candidate. This class deliberately does not assign
 * support/refutation polarity, authenticate a publisher, or create direct evidence.
 */
class ANS100HypothesisPassageExtractor(
    private val maxPassages: Int = DEFAULT_MAX_PASSAGES,
    private val maxExcerptChars: Int = DEFAULT_MAX_EXCERPT_CHARS,
) {
    enum class Status {
        UNCLASSIFIED_PASSAGE,
    }

    data class PassageCandidate(
        val hypothesisId: String,
        val passageRef: String,
        val searchLeadRef: String,
        val sourceUrl: String,
        val sourceHost: String,
        val pageContentSha256: String,
        val fetchedAtEpochMs: Long,
        val sentenceSha256: String,
        val excerpt: String,
        val status: Status = Status.UNCLASSIFIED_PASSAGE,
    )

    data class ExtractionReport(
        val hypothesisId: String,
        val pageContentSha256: String,
        val candidates: List<PassageCandidate>,
        val scannedSentenceCount: Int,
        val truncated: Boolean,
    )

    init {
        require(maxPassages in 1..MAX_PASSAGES_HARD_LIMIT)
        require(maxExcerptChars in 80..MAX_EXCERPT_CHARS_HARD_LIMIT)
    }

    fun extract(
        page: ANS100HypothesisPageVerifier.PageReport,
        fromLabel: String,
        toLabel: String,
    ): ExtractionReport {
        require(page.hypothesisId.isNotBlank())
        require(page.pageContentSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "page content digest must be a SHA-256 hex value"
        }
        val uri = runCatching { URI(page.finalUrl) }.getOrNull()
            ?: throw IllegalArgumentException("page URL is invalid")
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        require(uri.scheme.equals("https", true) && !host.isNullOrBlank() && uri.userInfo == null) {
            "page provenance must contain an HTTPS URL with a hostname"
        }
        val fromTerms = terms(fromLabel)
        val toTerms = terms(toLabel)
        require(fromTerms.isNotEmpty() && toTerms.isNotEmpty()) {
            "hypothesis endpoint labels must not be blank"
        }

        val sentences = splitSentences(page.extractedText)
        val unique = LinkedHashMap<String, String>()
        sentences.forEach { sentence ->
            val normalized = normalize(sentence)
            if (normalized.isNotEmpty()) {
                val digest = sha256(normalized)
                unique.putIfAbsent(digest, sentence.trim())
            }
        }

        val matching = unique.entries.filter { (_, sentence) ->
            val sentenceTerms = terms(sentence)
            fromTerms.any { it in sentenceTerms } && toTerms.any { it in sentenceTerms }
        }
        val candidates = matching.take(maxPassages).map { (sentenceDigest, sentence) ->
            val excerpt = sentence.trim().take(maxExcerptChars)
            val passageRef = "page-passage:" + sha256(
                page.hypothesisId + "|" + page.pageContentSha256.lowercase(Locale.ROOT) + "|" + sentenceDigest,
            ).take(32)
            PassageCandidate(
                hypothesisId = page.hypothesisId,
                passageRef = passageRef,
                searchLeadRef = page.searchLeadRef,
                sourceUrl = page.finalUrl,
                sourceHost = host,
                pageContentSha256 = page.pageContentSha256.lowercase(Locale.ROOT),
                fetchedAtEpochMs = page.fetchedAtEpochMs,
                sentenceSha256 = sentenceDigest,
                excerpt = excerpt,
            )
        }
        return ExtractionReport(
            hypothesisId = page.hypothesisId,
            pageContentSha256 = page.pageContentSha256.lowercase(Locale.ROOT),
            candidates = candidates,
            scannedSentenceCount = unique.size,
            truncated = matching.size > maxPassages,
        )
    }

    private fun splitSentences(value: String): List<String> =
        value.split(Regex("(?<=[.!?。！？])\\s+|\\n+"))
            .map(String::trim)
            .filter(String::isNotEmpty)

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun terms(value: String): Set<String> =
        TOKEN.findAll(value.lowercase(Locale.ROOT)).map { it.value }
            .filter { it.length >= 2 && it !in STOP_WORDS }.toSet()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_MAX_PASSAGES = 6
        const val MAX_PASSAGES_HARD_LIMIT = 12
        const val DEFAULT_MAX_EXCERPT_CHARS = 600
        const val MAX_EXCERPT_CHARS_HARD_LIMIT = 1_000
        private val TOKEN = Regex("[\\p{L}\\p{N}]{2,}")
        private val STOP_WORDS = setOf(
            "и", "в", "во", "на", "по", "для", "как", "что", "это",
            "the", "and", "for", "with", "from", "into",
        )
    }
}
