package org.jarvis.ans100.alpha.data

import android.text.Html
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class AndroidWebSearchIntent(val query: String) {
    companion object {
        private val prefixes = listOf(
            "найди в интернете",
            "поищи в интернете",
            "найди информацию",
            "поищи информацию",
            "найди",
            "поищи",
            "загугли",
            "/search",
            "search:",
        )

        fun extract(message: String): AndroidWebSearchIntent? {
            val clean = message.trim()
            val normalized = clean.lowercase(Locale.ROOT)
            val prefix = prefixes.firstOrNull { normalized == it || normalized.startsWith("$it ") }
                ?: return null
            val query = clean.drop(prefix.length).trim().trimStart(':', '-', '—').trim()
            if (query.isBlank() || query.length > AndroidWebSearchService.MAX_QUERY_CHARS) return null
            return AndroidWebSearchIntent(query)
        }
    }
}

data class AndroidWebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

class AndroidWebSearchException(message: String) : IllegalStateException(message)

/**
 * Lightweight, read-only Android web search. It fetches only Bing's search-results HTML;
 * result pages are never opened or read. Ranking is lexical and is not a trust assessment.
 */
class AndroidWebSearchService(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    fun search(query: String): List<AndroidWebSearchResult> {
        val clean = query.trim()
        if (clean.isEmpty() || clean.length > MAX_QUERY_CHARS ||
            clean.any { it == '\u0000' || it.code < 0x20 }
        ) {
            throw AndroidWebSearchException("Запрос поиска должен содержать от 1 до " + MAX_QUERY_CHARS + " символов.")
        }
        val encoded = URLEncoder.encode(clean, Charsets.UTF_8.name())
        val connection = connectionFactory(URL(SEARCH_ENDPOINT + encoded))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Language", "ru,en;q=0.8")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw AndroidWebSearchException(
                    "Поисковик временно недоступен (HTTP " + status + "). Попробуй позже.",
                )
            }
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) {
                        throw AndroidWebSearchException("Ответ поисковика превысил допустимый размер.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            return parseResults(body, clean)
        } catch (error: AndroidWebSearchException) {
            throw error
        } catch (error: Exception) {
            throw AndroidWebSearchException(
                "Не удалось подключиться к поисковику. Проверь интернет и попробуй ещё раз.",
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseResults(
        html: String,
        query: String = "",
    ): List<AndroidWebSearchResult> {
        if (html.length > MAX_RESPONSE_BYTES) {
            throw AndroidWebSearchException("Страница результатов слишком большая.")
        }
        val unique = LinkedHashMap<String, AndroidWebSearchResult>()
        for (blockMatch in RESULT_BLOCK.findAll(html)) {
            if (unique.size >= MAX_CANDIDATES) break
            val block = blockMatch.value
            val anchor = RESULT_ANCHOR.find(block) ?: continue
            val rawUrl = decodeHtml(anchor.groupValues[1]).trim()
            val url = safeResultUrl(rawUrl) ?: continue
            val title = cleanHtmlText(anchor.groupValues[2], MAX_TITLE_CHARS)
            if (title.isBlank()) continue
            val snippetRaw = RESULT_SNIPPET.find(block)?.groupValues?.get(1).orEmpty()
            val snippet = cleanHtmlText(snippetRaw, MAX_SNIPPET_CHARS)
            val canonicalUrl = canonicalResultKey(url) ?: continue
            unique.putIfAbsent(canonicalUrl, AndroidWebSearchResult(title, url, snippet))
        }
        return rankAndDiversify(query, unique.values.toList())
    }

    private fun rankAndDiversify(
        query: String,
        candidates: List<AndroidWebSearchResult>,
    ): List<AndroidWebSearchResult> {
        val terms = androidSearchTerms(query)
        val ranked = candidates.mapIndexed { index, result ->
            val titleMatches = matchingTermCount(result.title, terms)
            val snippetMatches = matchingTermCount(result.snippet, terms)
            RankedResult(
                result = result,
                score = (titleMatches * TITLE_MATCH_WEIGHT) + snippetMatches,
                originalIndex = index,
            )
        }.sortedWith(
            compareByDescending<RankedResult> { it.score }
                .thenBy { it.originalIndex },
        )

        // First pass: one result per normalized host, preserving relevance order.
        val selected = mutableListOf<AndroidWebSearchResult>()
        val seenHosts = mutableSetOf<String>()
        for (candidate in ranked) {
            if (selected.size >= MAX_RESULTS) break
            val host = androidSearchHost(candidate.result.url)
            if (seenHosts.add(host)) selected += candidate.result
        }

        // If fewer than five hosts exist, fill remaining slots with the next best results.
        for (candidate in ranked) {
            if (selected.size >= MAX_RESULTS) break
            if (candidate.result !in selected) selected += candidate.result
        }
        return selected
    }

    private fun safeResultUrl(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_URL_CHARS) return null
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            if (scheme != "https" || host.isNullOrBlank() ||
                uri.userInfo != null || (uri.port != -1 && uri.port != 443) ||
                host == "bing.com" || host.endsWith(".bing.com") ||
                host == "localhost" || host.endsWith(".localhost") ||
                host.endsWith(".local") || host.endsWith(".internal") ||
                !host.contains('.')
            ) return null
            val normalized = uri.toASCIIString()
            if (normalized.length > MAX_URL_CHARS) null else normalized
        } catch (_: Exception) {
            null
        }
    }

    private fun canonicalResultKey(raw: String): String? {
        return try {
            val uri = URI(raw).normalize()
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removePrefix("www.")
                ?: return null
            val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
            val query = uri.rawQuery
                ?.split('&')
                ?.filterNot { part ->
                    val encodedKey = part.substringBefore('=')
                    val key = URLDecoder.decode(encodedKey, Charsets.UTF_8.name())
                        .lowercase(Locale.ROOT)
                    key.startsWith("utm_") ||
                        key in setOf("gclid", "fbclid", "msclkid")
                }
                ?.joinToString("&")
                ?.takeIf { it.isNotEmpty() }
            buildString {
                append("https://")
                append(host)
                if (uri.port != -1 && uri.port != 443) {
                    append(':')
                    append(uri.port)
                }
                append(path)
                if (query != null) {
                    append('?')
                    append(query)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun cleanHtmlText(value: String, limit: Int): String {
        val withoutActiveContent = value
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
        return decodeHtml(withoutActiveContent)
            .replace(Regex("\\s+"), " ")
            .filter { it == '\t' || it == '\n' || it.code >= 0x20 }
            .trim()
            .take(limit)
    }

    private data class RankedResult(
        val result: AndroidWebSearchResult,
        val score: Int,
        val originalIndex: Int,
    )

    companion object {
        const val MAX_QUERY_CHARS = 240
        const val MAX_RESULTS = 5
        const val MAX_RESPONSE_BYTES = 1_500_000
        const val MAX_TITLE_CHARS = 180
        const val MAX_SNIPPET_CHARS = 260
        const val MAX_URL_CHARS = 2_048
        private const val MAX_CANDIDATES = 30
        private const val TITLE_MATCH_WEIGHT = 4
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val SEARCH_ENDPOINT = "https://www.bing.com/search?q="
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
        private val RESULT_BLOCK = Regex(
            """<li\b[^>]*class\s*=\s*["'][^"']*\bb_algo\b[^"']*["'][^>]*>[\s\S]*?</li>""",
            RegexOption.IGNORE_CASE,
        )
        private val RESULT_ANCHOR = Regex(
            """<h2\b[^>]*>[\s\S]*?<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</a>[\s\S]*?</h2>""",
            RegexOption.IGNORE_CASE,
        )
        private val RESULT_SNIPPET = Regex(
            """<p\b[^>]*>([\s\S]*?)</p>""",
            RegexOption.IGNORE_CASE,
        )
    }
}

private val ANDROID_SEARCH_TOKEN_REGEX = Regex("""[\p{L}\p{N}]{2,}""")
private val ANDROID_SEARCH_STOP_WORDS = setOf(
    "и", "в", "во", "на", "по", "для", "как", "что", "это", "или", "при",
    "the", "and", "for", "with", "from", "how", "what", "into", "about",
)

private fun androidSearchTerms(text: String): List<String> =
    ANDROID_SEARCH_TOKEN_REGEX.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }
        .filterNot { it in ANDROID_SEARCH_STOP_WORDS }
        .distinct()
        .take(16)
        .toList()

private fun matchingTermCount(text: String, terms: List<String>): Int {
    if (terms.isEmpty()) return 0
    val available = ANDROID_SEARCH_TOKEN_REGEX.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }
        .toSet()
    return terms.count { it in available }
}

private fun androidSearchHost(url: String): String =
    try {
        URI(url).host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removePrefix("www.").orEmpty()
    } catch (_: Exception) {
        ""
    }

private fun androidSearchMatchDescription(
    query: String,
    result: AndroidWebSearchResult,
): String {
    val terms = androidSearchTerms(query)
    val titleMatches = matchingTermCount(result.title, terms)
    val snippetMatches = matchingTermCount(result.snippet, terms)
    return when {
        titleMatches > 0 && snippetMatches > 0 -> "слова запроса встречаются в заголовке и описании"
        titleMatches > 0 -> "слова запроса встречаются в заголовке"
        snippetMatches > 0 -> "слова запроса встречаются только в описании"
        else -> "точных совпадений слов в заголовке и описании мало"
    }
}

fun formatAndroidWebSearchReply(
    query: String,
    results: List<AndroidWebSearchResult>,
): String = buildString {
    appendLine("Результаты интернет-поиска: " + query)
    if (results.isEmpty()) {
        append("Поисковик не вернул подходящих ссылок. Попробуй уточнить запрос.")
    } else {
        appendLine("Порядок учитывает совпадения слов в заголовках и описаниях; одинаковые ссылки убраны, домены по возможности разные.")
        results.forEachIndexed { index, result ->
            appendLine()
            append(index + 1)
            append(". ")
            append(result.title)
            appendLine()
            append("Источник: ")
            append(androidSearchHost(result.url))
            appendLine()
            append("Сопоставление: ")
            append(androidSearchMatchDescription(query, result))
            appendLine()
            if (result.snippet.isNotBlank()) {
                appendLine(result.snippet)
            }
            append(result.url)
        }
        appendLine()
        append("Важно: это сравнение только заголовков и кратких описаний по словам запроса, а не проверка надёжности или актуальности сайтов. Сами страницы не открывались и не читались. Нажми на ссылку, чтобы открыть источник в браузере телефона.")
    }
}
