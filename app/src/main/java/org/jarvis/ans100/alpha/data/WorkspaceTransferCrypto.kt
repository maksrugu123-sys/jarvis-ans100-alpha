package org.jarvis.ans100.alpha.data

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable JARVIS encrypted workspace envelope.
 *
 * Interoperability contract: PBKDF2-HMAC-SHA256 (310,000 rounds), AES-256-GCM,
 * 16-byte salt, 12-byte nonce, 128-bit authentication tag and fixed AAD.
 * WebCrypto stores the GCM tag appended to ciphertext; Android does the same.
 */
object WorkspaceTransferCrypto {
    private const val FORMAT = "jarvis.workspace.encrypted"
    private const val ITERATIONS = 310_000
    private const val MIN_PASSPHRASE_CHARS = 12
    private const val MAX_PASSPHRASE_CHARS = 256
    private const val MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024
    private const val MAX_BUNDLE_BYTES = 24 * 1024 * 1024
    private val AAD = "JARVIS-WORKSPACE-ENCRYPTED-V1".toByteArray(StandardCharsets.UTF_8)
    private val random = SecureRandom()

    fun encrypt(plaintext: String, passphrase: CharArray): ByteArray {
        try {
            validatePassphrase(passphrase)
            val plainBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
            if (plainBytes.isEmpty() || plainBytes.size > MAX_PLAINTEXT_BYTES) {
                plainBytes.fill(0)
                throw IllegalArgumentException("Рабочее пространство превышает лимит 16 МиБ.")
            }
            val salt = ByteArray(16).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            try {
                val ciphertext = try {
                    val keyBytes = deriveKey(passphrase, salt)
                    try {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.ENCRYPT_MODE,
                            SecretKeySpec(keyBytes, "AES"),
                            GCMParameterSpec(128, nonce),
                        )
                        cipher.updateAAD(AAD)
                        cipher.doFinal(plainBytes)
                    } finally {
                        keyBytes.fill(0)
                    }
                } finally {
                    plainBytes.fill(0)
                }
                try {
                    val envelope = JSONObject()
                        .put("format", FORMAT)
                        .put("version", JSONObject().put("major", 1).put("minor", 0))
                        .put("kdf", JSONObject()
                            .put("name", "PBKDF2-HMAC-SHA256")
                            .put("iterations", ITERATIONS)
                            .put("salt", encodeBase64(salt)))
                        .put("cipher", JSONObject()
                            .put("name", "AES-256-GCM")
                            .put("nonce", encodeBase64(nonce))
                            .put("tag_bits", 128))
                        .put("ciphertext", encodeBase64(ciphertext))
                    return envelope.toString().toByteArray(StandardCharsets.UTF_8)
                } finally {
                    ciphertext.fill(0)
                }
            } finally {
                salt.fill(0)
                nonce.fill(0)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    fun decrypt(bundleBytes: ByteArray, passphrase: CharArray): String {
        try {
            validatePassphrase(passphrase)
            require(bundleBytes.isNotEmpty() && bundleBytes.size <= MAX_BUNDLE_BYTES) {
                "Размер зашифрованного файла недопустим."
            }
            val envelope = try {
                JSONObject(String(bundleBytes, StandardCharsets.UTF_8))
            } catch (error: Exception) {
                throw IllegalArgumentException("Файл не является корректным JSON.", error)
            }
            require(envelope.keys().asSequence().toSet() ==
                setOf("format", "version", "kdf", "cipher", "ciphertext")) {
                "Поля зашифрованного файла не соответствуют формату."
            }
            require(envelope.optString("format") == FORMAT) {
                "Формат зашифрованного файла не поддерживается."
            }
            val version = envelope.optJSONObject("version")
                ?: throw IllegalArgumentException("Версия файла отсутствует.")
            require(version.opt("major") == 1 && version.opt("minor") == 0 &&
                version.length() == 2) {
                "Версия зашифрованного файла не поддерживается."
            }
            val kdf = envelope.optJSONObject("kdf")
                ?: throw IllegalArgumentException("Параметры KDF отсутствуют.")
            require(kdf.optString("name") == "PBKDF2-HMAC-SHA256" &&
                kdf.opt("iterations") == ITERATIONS && kdf.length() == 3) {
                "Параметры PBKDF2 не поддерживаются."
            }
            val cipherMetadata = envelope.optJSONObject("cipher")
                ?: throw IllegalArgumentException("Параметры шифра отсутствуют.")
            require(cipherMetadata.optString("name") == "AES-256-GCM" &&
                cipherMetadata.opt("tag_bits") == 128 &&
                cipherMetadata.length() == 3) {
                "Параметры AES-GCM не поддерживаются."
            }
            val salt = decodeBase64(kdf.getString("salt"), 16)
            val nonce = decodeBase64(cipherMetadata.getString("nonce"), 12)
            require(salt.size == 16 && nonce.size == 12) {
                "Длина соли или nonce неверна."
            }
            val ciphertext = decodeBase64(envelope.getString("ciphertext"), MAX_PLAINTEXT_BYTES + 16)
            require(ciphertext.size in 17..(MAX_PLAINTEXT_BYTES + 16)) {
                "Размер зашифрованных данных недопустим."
            }
            val plaintext = try {
                val keyBytes = deriveKey(passphrase, salt)
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keyBytes, "AES"),
                        GCMParameterSpec(128, nonce),
                    )
                    cipher.updateAAD(AAD)
                    cipher.doFinal(ciphertext)
                } catch (error: AEADBadTagException) {
                    throw IllegalArgumentException(
                        "Пароль неверен или файл повреждён.", error,
                    )
                } finally {
                    keyBytes.fill(0)
                }
            } finally {
                salt.fill(0)
                nonce.fill(0)
                ciphertext.fill(0)
            }
            try {
                require(plaintext.size <= MAX_PLAINTEXT_BYTES) {
                    "Расшифрованные данные превышают лимит 16 МиБ."
                }
                return String(plaintext, StandardCharsets.UTF_8)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            passphrase.fill('\u0000')
            bundleBytes.fill(0)
        }
    }

    private fun validatePassphrase(passphrase: CharArray) {
        require(passphrase.size in MIN_PASSPHRASE_CHARS..MAX_PASSPHRASE_CHARS) {
            "Парольная фраза должна содержать от 12 до 256 символов."
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        // Match WebCrypto exactly: PBKDF2 consumes UTF-8 passphrase bytes.
        val passwordBytes = String(passphrase).toByteArray(StandardCharsets.UTF_8)
        val block = ByteArray(salt.size + 4)
        salt.copyInto(block)
        block[block.lastIndex] = 1
        val mac = Mac.getInstance("HmacSHA256")
        try {
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            var u = mac.doFinal(block)
            val derived = u.copyOf()
            try {
                repeat(ITERATIONS - 1) {
                    val next = mac.doFinal(u)
                    u.fill(0)
                    u = next
                    for (index in derived.indices) {
                        derived[index] = (derived[index].toInt() xor u[index].toInt()).toByte()
                    }
                }
                return derived
            } catch (error: Exception) {
                derived.fill(0)
                throw error
            } finally {
                u.fill(0)
            }
        } finally {
            passwordBytes.fill(0)
            block.fill(0)
        }
    }
    private fun encodeBase64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun decodeBase64(value: String, maximumBytes: Int): ByteArray {
        require(value.length <= maximumBytes * 2) {
            "Base64-поле превышает допустимый размер."
        }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Некорректное Base64-поле.", error)
        }
        require(
            decoded.isNotEmpty() && decoded.size <= maximumBytes &&
                encodeBase64(decoded) == value,
        ) {
            "Размер или каноническая кодировка Base64-поля недопустимы."
        }
        return decoded
    }
}
