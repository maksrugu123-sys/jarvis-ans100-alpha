package org.jarvis.ans100.alpha.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class WorkspaceTransferCryptoTest {
    private val passphrase = "correct horse battery staple"

    @Test
    fun encryptedBundleRoundTripsUtf8WorkspaceJson() {
        val original = """{"format":"jarvis.workspace","message":"Память JARVIS"}"""
        val bundle = WorkspaceTransferCrypto.encrypt(original, passphrase.toCharArray())
        val restored = WorkspaceTransferCrypto.decrypt(bundle, passphrase.toCharArray())
        assertEquals(original, restored)
    }

    @Test
    fun decryptsPortableUtf8Pbkdf2AesGcmFixture() {
        val fixture = """{"format":"jarvis.workspace.encrypted","version":{"major":1,"minor":0},"kdf":{"name":"PBKDF2-HMAC-SHA256","iterations":310000,"salt":"AAECAwQFBgcICQoLDA0ODw=="},"cipher":{"name":"AES-256-GCM","nonce":"EBESExQVFhcYGRob","tag_bits":128},"ciphertext":"mHokjsTxRYZTauZQwozmAcunh8cFx9Dc0Ta5iobquGL4INDqOpEbsaoZflSeNS9yLJpPiGYQ6Q6zjxbtlhuwFxiR57VoOg9JhRL+IaA4f8vc"}"""
        val restored = WorkspaceTransferCrypto.decrypt(
            fixture.toByteArray(StandardCharsets.UTF_8),
            "парольная фраза 2026".toCharArray(),
        )
        assertEquals(
            """{"format":"jarvis.workspace","message":"JARVIS transfer fixture"}""",
            restored,
        )
    }

    @Test
    fun wrongPassphraseIsRejected() {
        val bundle = WorkspaceTransferCrypto.encrypt(
            """{"format":"jarvis.workspace"}""",
            passphrase.toCharArray(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTransferCrypto.decrypt(bundle, "different passphrase".toCharArray())
        }
    }

    @Test
    fun authenticatedCiphertextTamperingIsRejected() {
        val bundle = WorkspaceTransferCrypto.encrypt(
            """{"format":"jarvis.workspace"}""",
            passphrase.toCharArray(),
        )
        val envelope = JSONObject(String(bundle, StandardCharsets.UTF_8))
        val ciphertext = Base64.getDecoder().decode(envelope.getString("ciphertext"))
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTransferCrypto.decrypt(
                envelope.toString().toByteArray(StandardCharsets.UTF_8),
                passphrase.toCharArray(),
            )
        }
    }
}
