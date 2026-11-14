package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeBridgeClientTest {
    @Test
    fun approvedMemorySearchPayloadContainsExactlyOneReadOnlyCapability() {
        val payload = buildApprovedMemorySearchPayload(7, "  JARVIS память  ")
        assertEquals(7, payload.getInt("expected_revision"))
        val steps = payload.getJSONArray("steps")
        assertEquals(1, steps.length())
        val step = steps.getJSONObject(0)
        assertEquals(setOf("capability", "input"), step.keys().asSequence().toSet())
        assertEquals("memory.search", step.getString("capability"))
        assertEquals("JARVIS память", step.getString("input"))
        assertFalse(payload.toString().contains("shell"))
    }

    @Test
    fun payloadRejectsBlankOversizedNulAndNegativeRevision() {
        val invalid = listOf("", " ".repeat(3), "x".repeat(241), "valid\u0000query")
        for (query in invalid) {
            var rejected = false
            try {
                buildApprovedMemorySearchPayload(0, query)
            } catch (_: RuntimeBridgeException) {
                rejected = true
            }
            assertTrue("query should be rejected", rejected)
        }
        var rejectedRevision = false
        try {
            buildApprovedMemorySearchPayload(-1, "valid query")
        } catch (_: RuntimeBridgeException) {
            rejectedRevision = true
        }
        assertTrue(rejectedRevision)
    }
}
