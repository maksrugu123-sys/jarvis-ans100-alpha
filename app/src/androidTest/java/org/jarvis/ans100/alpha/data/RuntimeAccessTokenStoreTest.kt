package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class RuntimeAccessTokenStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearCredentials() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    @Test
    fun tokenSurvivesStoreRecreationWithoutPlaintextPreferences() {
        val token = "A".repeat(TOKEN_LENGTH)
        RuntimeAccessTokenStore(context).save(token)

        val values = preferences().all.values.filterIsInstance<String>()
        assertTrue(values.isNotEmpty())
        assertFalse(values.any { it.contains(token) })
        assertTrue(values.any { it.contains('.') })

        val restored = RuntimeAccessTokenStore(context).load()
        assertEquals(token, restored)
    }

    @Test
    fun clearRemovesTheEncryptedCredential() {
        val store = RuntimeAccessTokenStore(context)
        store.save("B".repeat(TOKEN_LENGTH))
        store.clear()

        assertNull(preferences().getString(TOKEN_KEY, null))
        assertNull(store.load())
    }

    @Test
    fun eachSaveUsesFreshGcmIv() {
        val token = "C".repeat(TOKEN_LENGTH)
        val store = RuntimeAccessTokenStore(context)
        store.save(token)
        val firstEnvelope = preferences().getString(TOKEN_KEY, null)
        store.save(token)
        val secondEnvelope = preferences().getString(TOKEN_KEY, null)

        assertNotNull(firstEnvelope)
        assertNotNull(secondEnvelope)
        assertNotEquals(firstEnvelope, secondEnvelope)
        assertEquals(token, store.load())
    }

    @Test
    fun malformedEnvelopeFailsClosedAndIsRemoved() {
        preferences().edit().putString(TOKEN_KEY, "broken.envelope.extra").commit()

        try {
            RuntimeAccessTokenStore(context).load()
            fail("malformed envelope must be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("токен"))
        }

        assertNull(preferences().getString(TOKEN_KEY, null))
    }

    @Test
    fun deletedKeystoreKeyInvalidatesAndRemovesStoredEnvelope() {
        val store = RuntimeAccessTokenStore(context)
        store.save("D".repeat(TOKEN_LENGTH))
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        assertTrue(keyStore.containsAlias(KEY_ALIAS))
        keyStore.deleteEntry(KEY_ALIAS)

        try {
            store.load()
            fail("encrypted token must not survive deletion of its Keystore key")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("токен"))
        }

        assertNull(preferences().getString(TOKEN_KEY, null))
    }

    @Test
    fun invalidTokenIsRejectedBeforePreferencesChange() {
        try {
            RuntimeAccessTokenStore(context).save("short")
            fail("invalid token must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure before any credential is persisted.
        }

        assertTrue(preferences().all.isEmpty())
    }

    @Test
    fun keystoreEncryptionKeyIsNonExportable() {
        RuntimeAccessTokenStore(context).save("E".repeat(TOKEN_LENGTH))
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null)

        assertTrue(key is SecretKey)
        assertNull(key.encoded)
    }

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "jarvis_runtime_credentials_v1"
        const val TOKEN_KEY = "loopback_bearer_v1"
        const val KEY_ALIAS = "jarvis.local-console.bearer.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TOKEN_LENGTH = 43
    }
}
