package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmModelStoreValidationTest {
    @Test
    fun acceptsExactResumableContentRange() {
        assertTrue(
            LiteRtLmModelStore.isValidContentRange(
                "bytes 1048576-347251839/347251840",
                expectedStart = 1_048_576L,
                expectedTotal = 347_251_840L,
            ),
        )
    }

    @Test
    fun rejectsWrongStartEndOrTotal() {
        val expectedStart = 1_048_576L
        val expectedTotal = 347_251_840L
        assertFalse(
            LiteRtLmModelStore.isValidContentRange(
                "bytes 1048575-347251839/347251840",
                expectedStart,
                expectedTotal,
            ),
        )
        assertFalse(
            LiteRtLmModelStore.isValidContentRange(
                "bytes 1048576-347251840/347251840",
                expectedStart,
                expectedTotal,
            ),
        )
        assertFalse(
            LiteRtLmModelStore.isValidContentRange(
                "bytes 1048576-347251839/347251841",
                expectedStart,
                expectedTotal,
            ),
        )
    }

    @Test
    fun rejectsMissingMalformedOverflowAndImpossibleRanges() {
        val expectedTotal = 347_251_840L
        assertFalse(LiteRtLmModelStore.isValidContentRange(null, 1L, expectedTotal))
        assertFalse(LiteRtLmModelStore.isValidContentRange("bytes 1-2", 1L, expectedTotal))
        assertFalse(
            LiteRtLmModelStore.isValidContentRange(
                "bytes 999999999999999999999-2/347251840",
                1L,
                expectedTotal,
            ),
        )
        assertFalse(LiteRtLmModelStore.isValidContentRange("bytes 1-2/3", 3L, 3L))
    }

    @Test
    fun onlyIdentityOrUnspecifiedContentEncodingIsAccepted() {
        assertTrue(LiteRtLmModelStore.isIdentityEncoding(null))
        assertTrue(LiteRtLmModelStore.isIdentityEncoding(""))
        assertTrue(LiteRtLmModelStore.isIdentityEncoding("identity"))
        assertTrue(LiteRtLmModelStore.isIdentityEncoding("IDENTITY"))
        assertFalse(LiteRtLmModelStore.isIdentityEncoding("gzip"))
        assertFalse(LiteRtLmModelStore.isIdentityEncoding("br"))
    }
}
