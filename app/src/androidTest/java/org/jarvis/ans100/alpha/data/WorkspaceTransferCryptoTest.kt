package org.jarvis.ans100.alpha.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.Base64

@RunWith(AndroidJUnit4::class)
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
        val fixture = """{"format":"jarvis.workspace.encrypted","version":{"major":1,"minor":0},"kdf":{"name":"PBKDF2-HMAC-SHA256","iterations":310000,"salt":"AAECAwQFBgcICQoLDA0ODw=="},"cipher":{"name":"AES-256-GCM","nonce":"EBESExQVFhcYGRob","tag_bits":128},"ciphertext":"mHonmcbzVoYuOaAYmdznB8rig9gWz8aB0yCu2s+moSWncdT1L9xT54U8c2OjRDUkbMsTzS1EqgPk1Tqo0VPlBV8Xjxo/T6kyTbe3vixWpDk7nLiXJZ1vSqF8n1hclMB/Mdnp3KXu7UXAgufgU21a4PdWKwiZ3jsEMhurn94jPAMhn2Ngc8c1uWX3lbb5vgipEjTYVMfCBN/bYNXFXScQtT8T/Z1Np5cV7Nebiji0OLOp9tJj9c5kXpZMEH4audghl6zCtsJZJZHqH5QmMEm47Wh6Y97/FELgCd6F+KtN8BXJfaJD2vmRRhX0mrc25+A1Z/KVRecFdDd8PrvZ8JgCGGe8XATx6f4X73NSfzPRx3uDFRAbGIcIuSryFdobQeUAY1Odi5XyHUD42kF+UwUI9PFKUQ/QdcLxb/H9hF/UbPlsq3RcgzsPianAlT2oR+Nkmn/rzBECjZ1tk6+wgvwlL6MH+J+xZ2NCBlaBIm7DtnDGx3Um8iUyxecr6m2wvOdcV/dvgz0CjQ1e"}"""
        val restored = WorkspaceTransferCrypto.decrypt(
            fixture.toByteArray(StandardCharsets.UTF_8),
            "парольная фраза 2026".toCharArray(),
        )
        assertEquals(
            """{"export_id":"workspace-current","exported_at":"2026-11-13T12:00:00+00:00","format":"jarvis.workspace","integrity":{"algorithm":"sha-256","digest":"fc1b712d4a6df7207c9474170f3c55fb08b4cc7f33e4967e76b23c27b24e7e7c"},"records":{"conversations":[],"memories":[],"settings":[],"tasks":[]},"source_platform":"desktop","version":{"major":1,"minor":0},"workspace_id":"jarvis-default"}""",
            restored,
        )
    }

    @Test
    fun wrongPassphraseIsRejected() {
        val bundle = WorkspaceTransferCrypto.encrypt(
            """{"format":"jarvis.workspace"}""",
            passphrase.toCharArray(),
        )
        expectIllegalArgument {
            WorkspaceTransferCrypto.decrypt(bundle, "different passphrase".toCharArray())
        }
    }

    @Test
    fun passphraseBuffersAreClearedAfterEncryptionAndDecryption() {
        val encryptionPassphrase = passphrase.toCharArray()
        val bundle = WorkspaceTransferCrypto.encrypt(
            """{"format":"jarvis.workspace"}""",
            encryptionPassphrase,
        )
        assertEquals(true, encryptionPassphrase.all { it == '\u0000' })

        val decryptionPassphrase = passphrase.toCharArray()
        WorkspaceTransferCrypto.decrypt(bundle, decryptionPassphrase)
        assertEquals(true, decryptionPassphrase.all { it == '\u0000' })
    }

    @Test
    fun unsupportedEnvelopeFieldsAreRejected() {
        val bundle = WorkspaceTransferCrypto.encrypt(
            """{"format":"jarvis.workspace"}""",
            passphrase.toCharArray(),
        )
        val envelope = JSONObject(String(bundle, StandardCharsets.UTF_8))
            .put("unexpected", true)
        expectIllegalArgument {
            WorkspaceTransferCrypto.decrypt(
                envelope.toString().toByteArray(StandardCharsets.UTF_8),
                passphrase.toCharArray(),
            )
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
        expectIllegalArgument {
            WorkspaceTransferCrypto.decrypt(
                envelope.toString().toByteArray(StandardCharsets.UTF_8),
                passphrase.toCharArray(),
            )
        }
    }

    private inline fun expectIllegalArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected rejection.
        }
    }
}
