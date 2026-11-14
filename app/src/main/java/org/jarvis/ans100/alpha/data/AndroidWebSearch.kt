package org.jarvis.ans100.alpha.data

import android.text.Html
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
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
 * result pages are never opened or read. Each source URL is displayed for explicit user tap.
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
            throw AndroidWebSearchException("Запрос поиска должен содержать от 1 до $MAX_QUERY_CHARS символов.")
        }
        val encoded = URLEncoder.encode(clean, Charsets.UTF_8.name())
        val connection = connectionFactory(URL("$SEARCH_ENDPOINT$encoded"))
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
                    "Поисковик временно недоступен (HTTP $status). Попробуй позже.",
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
            return parseResults(body)
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

    internal fun parseResults(html: String): List<AndroidWebSearchResult> {
        if (html.length > MAX_RESPONSE_BYTES) {
            throw AndroidWebSearchException("Страница результатов слишком большая.")
        }
        val results = LinkedHashMap<String, AndroidWebSearchResult>()
        RESULT_BLOCK.findAll(html).forEach { blockMatch ->
            val block = blockMatch.value
            val anchor = RESULT_ANCHOR.find(block) ?: return@forEach
            val rawUrl = decodeHtml(anchor.groupValues[1]).trim()
            val url = safeResultUrl(rawUrl) ?: return@forEach
            val title = cleanHtmlText(anchor.groupValues[2], MAX_TITLE_CHARS)
            if (title.isBlank()) return@forEach
            val snippetRaw = RESULT_SNIPPET.find(block)?.groupValues?.get(1).orEmpty()
            val snippet = cleanHtmlText(snippetRaw, MAX_SNIPPET_CHARS)
            results.putIfAbsent(url, AndroidWebSearchResult(title, url, snippet))
            if (results.size >= MAX_RESULTS) return results.values.toList()
        }
        return results.values.toList()
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
            uri.toASCIIString().take(MAX_URL_CHARS)
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

    companion object {
        const val MAX_QUERY_CHARS = 240
        const val MAX_RESULTS = 5
        const val MAX_RESPONSE_BYTES = 1_500_000
        const val MAX_TITLE_CHARS = 180
        const val MAX_SNIPPET_CHARS = 260
        const val MAX_URL_CHARS = 2_048
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

fun formatAndroidWebSearchReply(
    query: String,
    results: List<AndroidWebSearchResult>,
): String = buildString {
    appendLine("Результаты интернет-поиска: $query")
    if (results.isEmpty()) {
        append("Поисковик не вернул подходящих ссылок. Попробуй уточнить запрос.")
    } else {
        results.forEachIndexed { index, result ->
            appendLine()
            append(index + 1)
            append(". ")
            append(result.title)
            appendLine()
            if (result.snippet.isNotBlank()) appendLine(result.snippet)
            append(result.url)
        }
        appendLine()
        append("Я показал заголовки и краткие описания из поиска. Сами сайты не открывались и не читались. Нажми на ссылку, чтобы открыть источник в браузере телефона.")
    }
}
