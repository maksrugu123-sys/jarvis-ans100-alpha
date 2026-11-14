package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWebSearchTest {
    @Test
    fun extractsOnlyExplicitSearchRequests() {
        assertEquals("погоду в Берлине", AndroidWebSearchIntent.extract("Найди в интернете погоду в Берлине")?.query)
        assertEquals("новости Android", AndroidWebSearchIntent.extract("Поищи новости Android")?.query)
        assertEquals("Qwen 3", AndroidWebSearchIntent.extract("/search Qwen 3")?.query)
        assertNull(AndroidWebSearchIntent.extract("Просто объясни, как работает браузер"))
        assertNull(AndroidWebSearchIntent.extract("Найди"))
    }

    @Test
    fun parsesBoundedHttpsResultsAndDropsSearchEngineAndUnsafeLinks() {
        val html = """
            <ol>
              <li class="b_algo">
                <h2><a href="https://example.org/article">Example title</a></h2>
                <div class="b_caption"><p>Useful &amp; safe summary.</p></div>
              </li>
              <li class="b_algo">
                <h2><a href="https://www.bing.com/redirect">Bing internal</a></h2>
              </li>
              <li class="b_algo">
                <h2><a href="javascript:alert(1)">Unsafe script</a></h2>
              </li>
              <li class="b_algo">
                <h2><a href="https://second.example.net/path">Second result</a></h2>
                <p>Second snippet</p>
              </li>
            </ol>
        """.trimIndent()
        val results = AndroidWebSearchService().parseResults(html)
        assertEquals(2, results.size)
        assertEquals("Example title", results[0].title)
        assertEquals("https://example.org/article", results[0].url)
        assertEquals("Useful & safe summary.", results[0].snippet)
        assertEquals("Second result", results[1].title)
    }

    @Test
    fun ranksTitleMatchesAheadOfSnippetOnlyAndDiversifiesHosts() {
        val html = """
            <li class="b_algo">
              <h2><a href="https://example.org/snippet">General guide</a></h2>
              <p>JARVIS Android search reference</p>
            </li>
            <li class="b_algo">
              <h2><a href="https://www.example.org/guide">JARVIS Android native search</a></h2>
              <p>Implementation guide</p>
            </li>
            <li class="b_algo">
              <h2><a href="https://second.example.net/reference">JARVIS Android reference</a></h2>
              <p>Technical overview</p>
            </li>
            <li class="b_algo">
              <h2><a href="https://third.example.com/overview">Browser overview</a></h2>
              <p>General browser notes</p>
            </li>
            <li class="b_algo">
              <h2><a href="https://fourth.example.com/another">Another guide</a></h2>
              <p>General notes</p>
            </li>
        """.trimIndent()

        val results = AndroidWebSearchService().parseResults(html, "JARVIS Android")
        assertEquals(
            listOf(
                "https://www.example.org/guide",
                "https://second.example.net/reference",
                "https://third.example.com/overview",
                "https://fourth.example.com/another",
                "https://example.org/snippet",
            ),
            results.map { it.url },
        )
    }

    @Test
    fun removesExactDuplicatesAndCommonTrackingParameters() {
        val html = """
            <li class="b_algo">
              <h2><a href="https://www.example.org/article?utm_source=bing">First copy</a></h2>
              <p>First snippet</p>
            </li>
            <li class="b_algo">
              <h2><a href="https://example.org/article">Duplicate copy</a></h2>
              <p>Duplicate snippet</p>
            </li>
            <li class="b_algo">
              <h2><a href="https://other.example.net/article">Other result</a></h2>
              <p>Other snippet</p>
            </li>
        """.trimIndent()

        val results = AndroidWebSearchService().parseResults(html)
        assertEquals(2, results.size)
        assertEquals("First copy", results.first().title)
    }

    @Test
    fun replyExplainsMatchBasisAndDoesNotClaimToVerifyTrust() {
        val reply = formatAndroidWebSearchReply(
            "test query",
            listOf(AndroidWebSearchResult("Title", "https://example.org", "Snippet")),
        )
        assertTrue(reply.contains("https://example.org"))
        assertTrue(reply.contains("Сопоставление:"))
        assertTrue(reply.contains("не проверка надёжности"))
        assertTrue(reply.contains("страницы не открывались"))
    }

    @Test
    fun rejectsOversizedAndControlCharacterQueries() {
        val service = AndroidWebSearchService()
        var oversizedRejected = false
        var controlRejected = false
        try {
            service.search("x".repeat(AndroidWebSearchService.MAX_QUERY_CHARS + 1))
        } catch (_: AndroidWebSearchException) {
            oversizedRejected = true
        }
        try {
            service.search("bad\u0000query")
        } catch (_: AndroidWebSearchException) {
            controlRejected = true
        }
        assertTrue(oversizedRejected)
        assertTrue(controlRejected)
    }
}
