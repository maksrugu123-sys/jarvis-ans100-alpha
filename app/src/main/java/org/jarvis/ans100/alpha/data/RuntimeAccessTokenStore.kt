package org.jarvis.ans100.alpha.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the local Termux bearer token encrypted with an Android Keystore AES-GCM key.
 * The plaintext token is never written to SharedPreferences.
 */
class RuntimeAccessTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(): String? {
        val envelope = preferences.getString(TOKEN_KEY, null) ?: return null
        return try {
            val parts = envelope.split('.')
            require(parts.size == 2) { "invalid token envelope" }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            require(iv.size == GCM_IV_BYTES && ciphertext.isNotEmpty()) {
                "invalid token envelope size"
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val token = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            require(TOKEN_PATTERN.matches(token)) { "invalid access token" }
            token
        } catch (error: Exception) {
            preferences.edit().remove(TOKEN_KEY).commit()
            throw IllegalStateException(
                "Сохранённый токен недоступен. Вставьте новый токен из Termux.",
                error,
            )
        }
    }

    @Synchronized
    fun save(token: String) {
        require(TOKEN_PATTERN.matches(token)) {
            "Токен должен содержать 43 символа Base64URL."
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
            val envelope = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            check(preferences.edit().putString(TOKEN_KEY, envelope).commit()) {
                "Не удалось сохранить зашифрованный токен."
            }
        } catch (error: Exception) {
            throw IllegalStateException("Не удалось защитить токен в Android Keystore.", error)
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(TOKEN_KEY).commit()) {
            "Не удалось удалить сохранённый токен."
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "jarvis_runtime_credentials_v1"
        const val TOKEN_KEY = "loopback_bearer_v1"
        const val KEY_ALIAS = "jarvis.local-console.bearer.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
