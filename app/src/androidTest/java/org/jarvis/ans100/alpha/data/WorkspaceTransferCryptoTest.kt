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
    fun encryptedBackupUsesSeparateEnvelopeAndReadsPortableWebCryptoFixture() {
        val original = """{"format":"jarvis.workspace.backup","message":"Точная резервная копия"}"""
        val bundle = WorkspaceTransferCrypto.encryptBackup(original, passphrase.toCharArray())
        val envelope = JSONObject(String(bundle, StandardCharsets.UTF_8))
        assertEquals("jarvis.workspace.backup.encrypted", envelope.getString("format"))
        assertEquals(original, WorkspaceTransferCrypto.decryptBackup(
            bundle.copyOf(), passphrase.toCharArray(),
        ))
        expectIllegalArgument {
            WorkspaceTransferCrypto.decrypt(bundle.copyOf(), passphrase.toCharArray())
        }
        val transfer = WorkspaceTransferCrypto.encrypt(original, passphrase.toCharArray())
        expectIllegalArgument {
            WorkspaceTransferCrypto.decryptBackup(transfer, passphrase.toCharArray())
        }

        // Deterministic desktop-WebCrypto-compatible PBKDF2/AES-GCM backup fixture.
        val desktopFixture = """{"format":"jarvis.workspace.backup.encrypted","version":{"major":1,"minor":0},"kdf":{"name":"PBKDF2-HMAC-SHA256","iterations":310000,"salt":"AAECAwQFBgcICQoLDA0ODw=="},"cipher":{"name":"AES-256-GCM","nonce":"EBESExQVFhcYGRob","tag_bits":128},"ciphertext":"+YbHhSy2yZgO4jWfzxlo3t+h1VdCCi/rU2VVCmIbaHPynT3OmdrQ8bPzCM98oBoevCzt4Cop81SuIDVH/YVSGLHrhLFo6ac5mzwCv0aC6LwqIn14SCk+lk/2Lv7byAM6j2uGgVf6FRf2Y0jozoiHh64FESFFf2/hGOCdgIj5W4MCrhrlC1qKdjYci1TmPKqZYY3fgwmlIj9Mw0Sw2xgQY4yjytX/BHWSgCmu2NSa64z+DHN/y2Wv0Y6GAd2GasWPOawnpxaZcWTq4rsQLcJjl+kWjpOPR0cb21Im5mpcCxsKbC85kMotnyUvP/5Q81o/yhqqLFVzcbdaf62rmkD2guIRklpcY+kKvNZ/KBaIL720HOBVgzQuWZTXHhoFwA8BUbGtMb4/N0qprNdK67Z3PmUadLRNvYf3ednWLgXVm3NrX9IpXLQuuDBPDXK4XoPpRXVH49LbXLGTx7/hdD/TPlp3CejtRKO9oca+NKQNwKAeHBkTpmHFiH5YM9jjrqzWWClrCbe8GBuFTr8C+SyhdeKrEBZGUxKDp/FAerWOfJ4I6O3wWxh55uagmPRywDjBRTDEUq/GGut7t7JQoz/aAEUjHQww99ZDYKMnYUr7aRh7Fdhtqt4jDo1SgEsO1uEyD6uXua1WxeJWXUs3sKUJlTIQe7fwE9p0CEg7x0wxJBe4xNlRi6Xm3QuIsEih1xEjQ9YzdskTfxmsYwNQsYYUhbk/9ZKKoVFwGQLUQGuT22flhRpsncheCfGeukbAukzPT3K+WC4KhDIxNswtA82fc+cYZc/uTfspKUOVbPvZ8s72xhgI9c3lxtX7ndHffNijuGGWAw/AVJ3tUfwoq/BM7sgKp916BEmXRHUsEfTuHDINzM65BM8FL2JgRGGFu2NzN0vQ4k+gStB0PzZjD4W2+/KYz+b1R9bPj3IRjmpZfPWhnEonWlPJSw6tJrbm71PTZPCwWV7n6oA585DaYJRrhNIl61kS5dxldky5HOJPLu3EnQvCetc0s7+04m39ElyigbOdjqOw9/QZ79LkGnXu6FecGTwFn66LBOUJzMnhFZP5i0KfVWmWPMyW6D5zfHGI/8KjYSjsCBmxXeCDPlTZwQ9hkZescglqaKGHKPHSDUsjfaW8d2kWp4nO6VDDEdUkaX97eEItGg=="}"""
        val expectedDesktopPlaintext = """{"backup_id":"0123456789abcdef0123456789abcdef","created_at":"2026-11-13T12:30:00+00:00","format":"jarvis.workspace.backup","integrity":{"algorithm":"sha-256","digest":"0f5373c789c5bc4a67024f0fb40ec548b9054444e441f30bf347c92ede7afd3c"},"source_platform":"desktop","version":{"major":1,"minor":0},"workspace":{"export_id":"workspace-current","exported_at":"2026-11-13T12:00:00+00:00","format":"jarvis.workspace","integrity":{"algorithm":"sha-256","digest":"becaef7966e27cc12e12fd7fc9706a8148134c9c6bd2ca75e0d5a367e7a24673"},"records":{"conversations":[],"memories":[{"category":"test","content":"Резервная копия JARVIS","id":"memory-fixture-001","revision":0,"updated_at":"2026-11-13T12:00:00+00:00","user_approved":true}],"settings":[],"tasks":[]},"source_platform":"desktop","version":{"major":1,"minor":0},"workspace_id":"jarvis-default"}}"""
        assertEquals(
            expectedDesktopPlaintext,
            WorkspaceTransferCrypto.decryptBackup(
                desktopFixture.toByteArray(StandardCharsets.UTF_8),
                "jarvis backup interoperability 2026".toCharArray(),
            ),
        )
    }

    @Test
    fun backupPassphraseIsClearedAfterEncryptionAndDecryption() {
        val encryptionPassphrase = passphrase.toCharArray()
        val bundle = WorkspaceTransferCrypto.encryptBackup(
            """{"format":"jarvis.workspace.backup"}""",
            encryptionPassphrase,
        )
        assertEquals(true, encryptionPassphrase.all { it == '\u0000' })
        val decryptionPassphrase = passphrase.toCharArray()
        WorkspaceTransferCrypto.decryptBackup(bundle, decryptionPassphrase)
        assertEquals(true, decryptionPassphrase.all { it == '\u0000' })
    }

    @Test
    fun encryptedBackupRejectsWrongPassphraseAndTamperedCiphertext() {
        val bundle = WorkspaceTransferCrypto.encryptBackup(
            """{"format":"jarvis.workspace.backup","message":"Защищённая копия"}""",
            passphrase.toCharArray(),
        )
        expectIllegalArgument {
            WorkspaceTransferCrypto.decryptBackup(
                bundle.copyOf(),
                "different password phrase".toCharArray(),
            )
        }

        val envelope = JSONObject(String(bundle, StandardCharsets.UTF_8))
        val ciphertext = Base64.getDecoder().decode(envelope.getString("ciphertext"))
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        expectIllegalArgument {
            WorkspaceTransferCrypto.decryptBackup(
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
