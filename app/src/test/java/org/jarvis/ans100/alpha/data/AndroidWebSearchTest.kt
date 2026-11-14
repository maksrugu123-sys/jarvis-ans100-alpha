package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWebSearchTest {
    @Test
    fun extractsOnlyExplicitSearchRequests() {
        assertEquals("погода в Берлине", AndroidWebSearchIntent.extract("Найди в интернете погоду в Берлине")?.query)
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
    fun replyContainsSourceLinksAndDisclosesThatPagesWereNotRead() {
        val reply = formatAndroidWebSearchReply(
            "test query",
            listOf(AndroidWebSearchResult("Title", "https://example.org", "Snippet")),
        )
        assertTrue(reply.contains("https://example.org"))
        assertTrue(reply.contains("сами сайты не открывались"))
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
