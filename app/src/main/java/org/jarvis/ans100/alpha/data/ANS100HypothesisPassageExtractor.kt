package org.jarvis.ans100.alpha.data
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Bounded passage candidates; never direct evidence. */
class ANS100HypothesisPassageExtractor(
    private val maxPassages: Int = DEFAULT_MAX_PASSAGES,
    private val maxExcerptChars: Int = DEFAULT_MAX_EXCERPT_CHARS,
) {
    enum class Status { UNCLASSIFIED_PASSAGE }
    data class PassageCandidate(
        val hypothesisId: String, val passageRef: String, val searchLeadRef: String,
        val sourceUrl: String, val sourceHost: String, val pageContentSha256: String,
        val fetchedAtEpochMs: Long, val sentenceSha256: String, val excerpt: String,
        val status: Status = Status.UNCLASSIFIED_PASSAGE,
    )
    data class ExtractionReport(
        val hypothesisId: String, val pageContentSha256: String,
        val candidates: List<PassageCandidate>, val scannedSentenceCount: Int, val truncated: Boolean,
    )
    init {
        require(maxPassages in 1..MAX_PASSAGES_HARD_LIMIT)
        require(maxExcerptChars in 80..MAX_EXCERPT_CHARS_HARD_LIMIT)
    }
    fun extract(page: ANS100HypothesisPageVerifier.PageReport, fromLabel: String, toLabel: String): ExtractionReport {
        require(page.hypothesisId.isNotBlank())
        // PageReport exposes contentSha256; downstream DTO names it pageContentSha256.
        require(page.contentSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "invalid page SHA-256" }
        val uri = runCatching { URI(page.finalUrl) }.getOrNull() ?: throw IllegalArgumentException("invalid page URL")
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        require(uri.scheme.equals("https", true) && !host.isNullOrBlank() && uri.userInfo == null)
        val from = terms(fromLabel); val to = terms(toLabel)
        require(from.isNotEmpty() && to.isNotEmpty())
        val unique = LinkedHashMap<String, String>()
        split(page.extractedText).forEach { sentence ->
            val normalized = sentence.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
            if (normalized.isNotEmpty()) unique.putIfAbsent(sha(normalized), sentence.trim())
        }
        val matching = unique.entries.filter { (_, sentence) ->
            val tokens = terms(sentence); from.any { it in tokens } && to.any { it in tokens }
        }
        val digest = page.contentSha256.lowercase(Locale.ROOT)
        val candidates = matching.take(maxPassages).map { (sentenceDigest, sentence) ->
            PassageCandidate(
                hypothesisId = page.hypothesisId,
                passageRef = "page-passage:" + sha(page.hypothesisId + "|" + canonicalSourceUrl(uri) + "|" + digest + "|" + sentenceDigest).take(32),
                searchLeadRef = page.searchLeadRef, sourceUrl = page.finalUrl, sourceHost = host,
                pageContentSha256 = digest, fetchedAtEpochMs = page.fetchedAtEpochMs,
                sentenceSha256 = sentenceDigest, excerpt = sentence.take(maxExcerptChars),
            )
        }
        return ExtractionReport(page.hypothesisId, digest, candidates, unique.size, matching.size > maxPassages)
    }
    private fun canonicalSourceUrl(uri: URI): String {
        val normalized = uri.normalize()
        val host = normalized.host.lowercase(Locale.ROOT).trimEnd('.')
        val port = if (normalized.port == -1 || normalized.port == 443) "" else ":" + normalized.port
        val path = normalized.rawPath.orEmpty().ifEmpty { "/" }
        val query = normalized.rawQuery?.let { "?" + it }.orEmpty()
        return "https://" + host + port + path + query
    }
    private fun split(value: String) = value.split(Regex("(?<=[.!?。！？])\\s+|\\n+")).map(String::trim).filter(String::isNotEmpty)
    private fun terms(value: String) = TOKEN.findAll(value.lowercase(Locale.ROOT)).map { it.value }
        .filter { it.length >= 2 && it !in STOP_WORDS }.toSet()
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    companion object {
        const val DEFAULT_MAX_PASSAGES = 6
        const val MAX_PASSAGES_HARD_LIMIT = 12
        const val DEFAULT_MAX_EXCERPT_CHARS = 600
        const val MAX_EXCERPT_CHARS_HARD_LIMIT = 1_000
        private val TOKEN = Regex("[\\p{L}\\p{N}]{2,}")
        private val STOP_WORDS = setOf("и","в","во","на","по","для","как","что","это","the","and","for","with","from","into")
    }
}
