package org.jarvis.ans100.alpha.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

open class PeerMemorySyncException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
class PeerMemorySyncConflict(message: String) : PeerMemorySyncException(message)

data class PeerMemorySyncResult(
    val revision: Long,
    val addedCount: Int,
    val updatedCount: Int,
    val conflictCount: Int,
    val records: List<LocalWorkspaceMemory>,
)

/** Strictly accepts private IPv4 literals; no DNS names or public endpoints are permitted. */
internal fun normalizePeerMemoryHost(value: String): String {
    val clean = value.trim()
    val parts = clean.split('.')
    if (parts.size != 4) throw PeerMemorySyncException("Укажите приватный IPv4-адрес компьютера.")
    val octets = parts.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) {
            throw PeerMemorySyncException("IPv4-адрес компьютера имеет неверный формат.")
        }
        part.toIntOrNull()?.takeIf { it in 0..255 }
            ?: throw PeerMemorySyncException("IPv4-адрес компьютера имеет неверный формат.")
    }
    if (parts.indices.any { parts[it] != octets[it].toString() }) {
        throw PeerMemorySyncException("IPv4-адрес должен быть записан без ведущих нулей.")
    }
    val privateAddress =
        octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    if (!privateAddress) {
        throw PeerMemorySyncException("Разрешён только адрес из частной локальной сети (10/8, 172.16/12 или 192.168/16).")
    }
    return clean
}

internal fun normalizePeerCertificateFingerprint(value: String): String {
    val clean = value.trim().replace(":", "").lowercase(Locale.ROOT)
    if (!Regex("^[a-f0-9]{64}$").matches(clean)) {
        throw PeerMemorySyncException("SHA-256 отпечаток сертификата должен содержать 64 шестнадцатеричных символа.")
    }
    return clean
}

internal fun buildPeerMemorySyncPayload(
    expectedRevision: Long,
    records: List<LocalWorkspaceMemory>,
): JSONObject {
    if (expectedRevision < 0L || records.size > LocalWorkspaceMemoryCache.MAX_CACHED_RECORDS) {
        throw PeerMemorySyncException("Ревизия или количество воспоминаний превышает лимит.")
    }
    val seen = HashSet<String>()
    val array = JSONArray()
    for (record in records) {
        if (!record.userApproved || record.id.isBlank() || record.id.length > 128 ||
            !Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$").matches(record.id) ||
            !seen.add(record.id) || record.content.isBlank() || record.content.length > 700 ||
            record.category.isBlank() || record.category.length > 64 ||
            record.updatedAt.isBlank() || record.updatedAt.length > 80 ||
            listOf(record.id, record.content, record.category, record.updatedAt).any { '\u0000' in it } ||
            runCatching { OffsetDateTime.parse(record.updatedAt) }.isFailure
        ) {
            throw PeerMemorySyncException("Найдена неподтверждённая или некорректная запись локальной памяти; отправка отменена.")
        }
        array.put(
            JSONObject()
                .put("id", record.id)
                .put("content", record.content)
                .put("category", record.category)
                .put("updated_at", record.updatedAt)
                .put("user_approved", true),
        )
    }
    return JSONObject()
        .put("expected_revision", expectedRevision)
        .put("memories", array)
}

/**
 * Manual LAN sync to the PC peer overlay. Only the pinned leaf certificate,
 * system IP-SAN hostname verification and the existing Keystore-backed bearer
 * token are accepted. There is deliberately no HTTP or public-host fallback.
 */
class PeerMemorySyncClient {
    fun sync(
        host: String,
        certificateFingerprint: String,
        bearerToken: String,
        localRecords: List<LocalWorkspaceMemory>,
    ): PeerMemorySyncResult {
        val cleanHost = normalizePeerMemoryHost(host)
        val pinHex = normalizePeerCertificateFingerprint(certificateFingerprint)
        if (!Regex("^[A-Za-z0-9_-]{43}$").matches(bearerToken)) {
            throw PeerMemorySyncException("Токен локальной консоли отсутствует или имеет неверный формат.")
        }
        val payloadTemplate = buildPeerMemorySyncPayload(0L, localRecords)
        val pin = ByteArray(32) { index -> pinHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        val tls = pinnedTlsContext(pin)
        val endpoint = "https://$cleanHost:8766/api/peer-sync/memories"

        val current = exchange(endpoint, "GET", null, bearerToken, tls)
        val revision = nonNegativeLong(current, "revision")
        parseRecords(current.optJSONArray("memories") ?: throw PeerMemorySyncException("ПК вернул память без списка записей."))

        val payload = JSONObject(payloadTemplate.toString()).put("expected_revision", revision)
        val merged = exchange(endpoint, "POST", payload, bearerToken, tls)
        val nextRevision = nonNegativeLong(merged, "revision")
        val records = parseRecords(
            merged.optJSONArray("memories")
                ?: throw PeerMemorySyncException("ПК не вернул объединённую память."),
        )
        return PeerMemorySyncResult(
            revision = nextRevision,
            addedCount = nonNegativeInt(merged, "added"),
            updatedCount = nonNegativeInt(merged, "updated"),
            conflictCount = nonNegativeInt(merged, "conflicts"),
            records = records,
        )
    }

    private fun pinnedTlsContext(expectedFingerprint: ByteArray): SSLContext {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificate authentication is not used.")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("Server certificate chain is empty.")
                chain[0].checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                if (!MessageDigest.isEqual(actual, expectedFingerprint)) {
                    throw CertificateException("Server certificate SHA-256 fingerprint does not match the paired pin.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
    }

    private fun exchange(
        endpoint: String,
        method: String,
        body: JSONObject?,
        bearerToken: String,
        tls: SSLContext,
    ): JSONObject {
        val connection = try {
            (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = tls.socketFactory
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setRequestProperty("Connection", "close")
            }
        } catch (error: Exception) {
            throw PeerMemorySyncException("Не удалось подготовить защищённое соединение с ПК.", error)
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                if (bytes.size > MAX_REQUEST_BYTES) {
                    throw PeerMemorySyncException("Пакет синхронизации превышает лимит размера.")
                }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use(::readBounded)
                ?: throw PeerMemorySyncException("ПК не вернул тело ответа (HTTP $status).")
            val response = runCatching { JSONObject(String(bytes, StandardCharsets.UTF_8)) }
                .getOrElse { throw PeerMemorySyncException("ПК вернул некорректный JSON.") }
            if (status == 409) {
                throw PeerMemorySyncConflict("Ревизия памяти ПК изменилась. Повторите синхронизацию после обновления.")
            }
            if (status !in 200..299) {
                val message = response.optString("error").take(MAX_ERROR_CHARS)
                throw PeerMemorySyncException(message.ifBlank { "Синхронизация ПК завершилась с HTTP $status." })
            }
            return response
        } catch (error: PeerMemorySyncException) {
            throw error
        } catch (error: Exception) {
            throw PeerMemorySyncException("Не удалось завершить HTTPS-синхронизацию с ПК.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRecords(array: JSONArray): List<LocalWorkspaceMemory> {
        if (array.length() > LocalWorkspaceMemoryCache.MAX_CACHED_RECORDS) {
            throw PeerMemorySyncException("На ПК больше записей, чем может принять локальная память Android.")
        }
        val result = ArrayList<LocalWorkspaceMemory>(array.length())
        val ids = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw PeerMemorySyncException("Запись памяти ПК имеет неверный формат.")
            val id = requiredString(item, "id", 128)
            val content = requiredString(item, "content", 700)
            val category = requiredString(item, "category", 64)
            val updatedAt = requiredString(item, "updated_at", 80)
            if (!Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$").matches(id) ||
                !ids.add(id) || content.isBlank() || category.isBlank() ||
                item.opt("user_approved") != true ||
                runCatching { OffsetDateTime.parse(updatedAt) }.isFailure
            ) {
                throw PeerMemorySyncException("ПК вернул запись без явного подтверждения или с некорректными полями.")
            }
            result += LocalWorkspaceMemory(id, content, category, updatedAt, true)
        }
        return result
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw PeerMemorySyncException("Ответ синхронизации превышает лимит размера.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun requiredString(json: JSONObject, key: String, maximum: Int): String {
        val value = json.opt(key)
        if (value !is String || value.isBlank() || value.length > maximum || '\u0000' in value) {
            throw PeerMemorySyncException("Поле $key в ответе ПК имеет неверный формат.")
        }
        return value
    }

    private fun nonNegativeLong(json: JSONObject, key: String): Long {
        val value = json.opt(key)
        if (value !is Number || value.toDouble() % 1.0 != 0.0 || value.toLong() < 0L) {
            throw PeerMemorySyncException("Числовое поле $key в ответе ПК имеет неверный формат.")
        }
        return value.toLong()
    }

    private fun nonNegativeInt(json: JSONObject, key: String): Int {
        val value = nonNegativeLong(json, key)
        if (value > Int.MAX_VALUE) throw PeerMemorySyncException("Счётчик $key превышает лимит.")
        return value.toInt()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_ERROR_CHARS = 240
    }
}
