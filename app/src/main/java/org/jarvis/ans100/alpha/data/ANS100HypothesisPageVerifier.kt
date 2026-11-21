package org.jarvis.ans100.alpha.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Reads a search-result page under strict transport and size limits.
 *
 * A fetched page is still untrusted. This component reports textual relevance
 * only; it does not decide whether the page supports or refutes a hypothesis.
 */
class ANS100HypothesisPageVerifier(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val resolvesOnlyToPublicAddresses: (String) -> Boolean = { host ->
        runCatching {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all { address ->
                !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isMulticastAddress
            }
        }.getOrDefault(false)
    },
) {
    enum class Relevance {
        BOTH_ENDPOINTS_MENTIONED,
        ONLY_FROM_ENDPOINT_MENTIONED,
        ONLY_TO_ENDPOINT_MENTIONED,
        NEITHER_ENDPOINT_MENTIONED,
    }

    data class PageReport(
        val hypothesisId: String,
        val searchLeadRef: String,
        val requestedUrl: String,
        val finalUrl: String,
        val pageTitle: String,
        val description: String,
        val extractedText: String,
        val contentSha256: String,
        val fetchedAtEpochMs: Long,
        val relevance: Relevance,
        val status: String = "FETCHED_PAGE_UNVERIFIED",
    )

    fun inspect(
        lead: ANS100HypothesisSearchEvidenceProvider.SearchCandidate,
        fromLabel: String,
        toLabel: String,
        userApproved: Boolean,
    ): PageReport {
        require(userApproved) { "opening a hypothesis source requires explicit user approval" }
        val uri = validateUrl(lead.url)
        val from = terms(fromLabel)
        val to = terms(toLabel)
        require(from.isNotEmpty() && to.isNotEmpty()) { "concept labels must not be blank" }

        val connection = connectionFactory(URL(uri.toASCIIString()))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Language", "ru,en;q=0.8")

            val statusCode = connection.responseCode
            if (statusCode in 300..399) {
                throw AndroidWebSearchException("Источник перенаправляет запрос; автоматические перенаправления запрещены.")
            }
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw AndroidWebSearchException("Не удалось открыть источник (HTTP $statusCode).")
            }
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            if (!contentType.startsWith("text/html") &&
                !contentType.startsWith("application/xhtml+xml")
            ) {
                throw AndroidWebSearchException("Источник не является HTML-страницей.")
            }

            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_PAGE_BYTES) {
                        throw AndroidWebSearchException("Страница превышает допустимый размер.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val html = bytes.toString(Charsets.UTF_8.name())
            val pageTitle = extractTitle(html)
            val description = extractDescription(html)
            val text = extractText(html)
            val pageTerms = terms("$pageTitle $description $text")
            val hasFrom = from.any { it in pageTerms }
            val hasTo = to.any { it in pageTerms }
            val relevance = when {
                hasFrom && hasTo -> Relevance.BOTH_ENDPOINTS_MENTIONED
                hasFrom -> Relevance.ONLY_FROM_ENDPOINT_MENTIONED
                hasTo -> Relevance.ONLY_TO_ENDPOINT_MENTIONED
                else -> Relevance.NEITHER_ENDPOINT_MENTIONED
            }
            return PageReport(
                hypothesisId = lead.hypothesisId,
                searchLeadRef = lead.provenanceRef,
                requestedUrl = uri.toASCIIString(),
                finalUrl = uri.toASCIIString(),
                pageTitle = pageTitle,
                description = description,
                extractedText = text,
                contentSha256 = sha256(bytes),
                fetchedAtEpochMs = nowMillis(),
                relevance = relevance,
            )
        } catch (error: AndroidWebSearchException) {
            throw error
        } catch (error: Exception) {
            throw AndroidWebSearchException("Не удалось безопасно прочитать страницу источника.")
        } finally {
            connection.disconnect()
        }
    }

    private fun validateUrl(raw: String): URI {
        if (raw.isBlank() || raw.length > MAX_URL_CHARS) {
            throw AndroidWebSearchException("Некорректная ссылка источника.")
        }
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw AndroidWebSearchException("Некорректная ссылка источника.")
        }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            host.isNullOrBlank() ||
            uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) ||
            !host.contains('.') ||
            host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host == "bing.com" ||
            host.endsWith(".bing.com") ||
            host.matches(Regex("[0-9.]+"))
        ) {
            throw AndroidWebSearchException("Ссылка не прошла проверку безопасности.")
        }
        if (!resolvesOnlyToPublicAddresses(host)) {
            throw AndroidWebSearchException("Домен не прошёл проверку публичного сетевого адреса.")
        }
        return uri
    }

    private fun extractTitle(html: String): String =
        clean(Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1).orEmpty(), MAX_TITLE_CHARS)

    private fun extractDescription(html: String): String {
        val tags = Regex("(?is)<meta\\b[^>]*>").findAll(html).map { it.value }
        for (tag in tags) {
            val name = attribute(tag, "name") ?: attribute(tag, "property") ?: continue
            if (!name.equals("description", true) && !name.equals("og:description", true)) continue
            return clean(attribute(tag, "content").orEmpty(), MAX_DESCRIPTION_CHARS)
        }
        return ""
    }

    private fun attribute(tag: String, name: String): String? {
        val regex = Regex("(?is)\\b" + Regex.escape(name) + """\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        val match = regex.find(tag) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun extractText(html: String): String {
        val body = Regex("(?is)<body\\b[^>]*>(.*?)</body>").find(html)?.groupValues?.get(1) ?: html
        return clean(
            body.replace(Regex("(?is)<(script|style|noscript|svg|iframe)\\b[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?s)<!--.*?-->"), " ")
                .replace(Regex("(?s)<[^>]+>"), " "),
            MAX_EXTRACTED_TEXT_CHARS,
        )
    }

    private fun clean(value: String, limit: Int): String =
        decodeEntities(value)
            .replace(Regex("\\s+"), " ")
            .filter { it.code >= 0x20 }
            .trim()
            .take(limit)

    private fun decodeEntities(value: String): String = ENTITY.replace(value) { match ->
        val entity = match.groupValues[1]
        when (entity.lowercase(Locale.ROOT)) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> when {
                entity.startsWith("#x", true) ->
                    entity.drop(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
                entity.startsWith("#") ->
                    entity.drop(1).toIntOrNull()?.let(::codePoint) ?: match.value
                else -> match.value
            }
        }
    }

    private fun codePoint(value: Int): String =
        if (Character.isValidCodePoint(value) && value !in 0xD800..0xDFFF) {
            String(Character.toChars(value))
        } else {
            " "
        }

    private fun terms(value: String): Set<String> =
        TOKEN.findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val MAX_PAGE_BYTES = 512_000
        const val MAX_EXTRACTED_TEXT_CHARS = 12_000
        const val MAX_TITLE_CHARS = 180
        const val MAX_DESCRIPTION_CHARS = 320
        const val MAX_URL_CHARS = 2_048
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
        private val ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#\\d{1,7}|[A-Za-z][A-Za-z0-9]{0,31});")
        private val TOKEN = Regex("[\\p{L}\\p{N}]{2,}")
        private val STOP_WORDS = setOf("и", "в", "во", "на", "по", "для", "как", "что", "это", "the", "and", "for", "with")
    }
}
