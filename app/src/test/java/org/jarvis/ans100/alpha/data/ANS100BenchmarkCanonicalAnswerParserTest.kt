package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ANS100BenchmarkCanonicalAnswerParserTest {
    @Test
    fun acceptsOnlyOneCanonicalLabel() {
        assertEquals("YES", ANS100BenchmarkCanonicalAnswerParser.parse("YES"))
        assertEquals("NO", ANS100BenchmarkCanonicalAnswerParser.parse(" no "))
        assertEquals("UNKNOWN", ANS100BenchmarkCanonicalAnswerParser.parse("Unknown."))
        assertEquals("CONFLICT", ANS100BenchmarkCanonicalAnswerParser.parse("conflict!"))
    }

    @Test
    fun rejectsExplanationsAndAmbiguousOutput() {
        assertNull(ANS100BenchmarkCanonicalAnswerParser.parse("YES because the path exists"))
        assertNull(ANS100BenchmarkCanonicalAnswerParser.parse("The answer is YES"))
        assertNull(ANS100BenchmarkCanonicalAnswerParser.parse("YES\\nNO"))
        assertNull(ANS100BenchmarkCanonicalAnswerParser.parse(""))
        assertNull(ANS100BenchmarkCanonicalAnswerParser.parse("MAYBE"))
    }
}
