package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PeerMemorySyncClientTest {
    @Test
    fun onlyRfc1918Ipv4AddressesAreAccepted() {
        assertEquals("192.168.1.20", normalizePeerMemoryHost(" 192.168.1.20 "))
        assertEquals("10.1.2.3", normalizePeerMemoryHost("10.1.2.3"))
        assertEquals("172.31.0.8", normalizePeerMemoryHost("172.31.0.8"))
        for (host in listOf("localhost", "8.8.8.8", "172.32.0.1", "192.168.001.1", "0.0.0.0")) {
            var rejected = false
            try { normalizePeerMemoryHost(host) } catch (_: PeerMemorySyncException) { rejected = true }
            assertTrue("Expected host rejection: $host", rejected)
        }
    }

    @Test
    fun certificateFingerprintAcceptsColonSeparatedHexAndNormalizesCase() {
        val fingerprint = "AA".repeat(32).chunked(2).joinToString(":")
        assertEquals("aa".repeat(32), normalizePeerCertificateFingerprint(fingerprint))
        var rejected = false
        try { normalizePeerCertificateFingerprint("not-a-fingerprint") }
        catch (_: PeerMemorySyncException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun syncPayloadContainsOnlyApprovedMemoryFields() {
        val memory = LocalWorkspaceMemory(
            id = "local-test-1",
            content = "JARVIS project note",
            category = "project",
            updatedAt = Instant.parse("2026-11-14T12:00:00Z").toString(),
            userApproved = true,
        )
        val payload = buildPeerMemorySyncPayload(3L, listOf(memory))
        assertEquals(3L, payload.getLong("expected_revision"))
        assertEquals(setOf("expected_revision", "memories"), payload.keys().asSequence().toSet())
        val item = payload.getJSONArray("memories").getJSONObject(0)
        assertEquals(
            setOf("id", "content", "category", "updated_at", "user_approved"),
            item.keys().asSequence().toSet(),
        )
        assertEquals(true, item.getBoolean("user_approved"))
    }

    @Test
    fun unapprovedRecordsAndNegativeRevisionAreRejected() {
        val memory = LocalWorkspaceMemory("local-test-2", "private", "general",
            Instant.parse("2026-11-14T12:00:00Z").toString(), false)
        var rejected = false
        try { buildPeerMemorySyncPayload(0L, listOf(memory)) }
        catch (_: PeerMemorySyncException) { rejected = true }
        assertTrue(rejected)
        rejected = false
        try { buildPeerMemorySyncPayload(-1L, emptyList()) }
        catch (_: PeerMemorySyncException) { rejected = true }
        assertTrue(rejected)
    }
}
