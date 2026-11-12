package org.jarvis.ans100.alpha.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RuntimeSessionSnapshot(
    val sessionId: String,
    val title: String,
    val status: String,
    val revision: Int,
)

data class RuntimeTaskSnapshot(
    val taskId: String,
    val sessionId: String,
    val title: String,
    val status: String,
    val revision: Int,
    val failureCode: String?,
    val resultJson: String?,
)

data class RuntimeBridgeSnapshot(
    val managerId: String,
    val checkpointVersion: Long,
    val sessions: List<RuntimeSessionSnapshot>,
    val tasks: List<RuntimeTaskSnapshot>,
    val eventCount: Int,
    val externalActionsAllowed: Boolean,
    val toolExecutionAllowed: Boolean,
    val remoteAccessAllowed: Boolean,
)

class RuntimeBridgeException(message: String) : IllegalStateException(message)

/**
 * Narrow client for the on-device Termux Python console.
 *
 * The endpoint is intentionally fixed to loopback. This client exposes only
 * task/session administration and never submits an ActionExecutor request.
 */
class LocalRuntimeBridgeClient {
    fun connect(): RuntimeBridgeSnapshot {
        val health = request("GET", "/api/health")
        if (requiredString(health, "status") != "ok" ||
            requiredString(health, "storage") != "jpvr"
        ) {
            throw RuntimeBridgeException("Локальный сервис не подтвердил готовность JPVR.")
        }
        return decodeState(request("GET", "/api/state"))
    }

    fun refresh(): RuntimeBridgeSnapshot =
        decodeState(request("GET", "/api/state"))

    fun createSession(title: String): RuntimeBridgeSnapshot =
        postState("/api/sessions", JSONObject().put("title", title))

    fun createTask(sessionId: String, title: String): RuntimeBridgeSnapshot =
        postState(
            "/api/tasks",
            JSONObject().put("session_id", sessionId).put("title", title),
        )

    fun setSessionStatus(
        sessionId: String,
        status: String,
        expectedRevision: Int,
    ): RuntimeBridgeSnapshot {
        requireStatus(status, setOf("open", "paused", "closed"))
        return postState(
            "/api/sessions/${pathSegment(sessionId)}/status",
            JSONObject().put("status", status)
                .put("expected_revision", expectedRevision),
        )
    }

    fun cancelQueuedTask(taskId: String, expectedRevision: Int): RuntimeBridgeSnapshot =
        postState(
            "/api/tasks/${pathSegment(taskId)}/cancel",
            JSONObject().put("expected_revision", expectedRevision),
        )

    private fun postState(path: String, body: JSONObject): RuntimeBridgeSnapshot {
        val response = request("POST", path, body)
        val state = response.optJSONObject("state")
            ?: throw RuntimeBridgeException("Сервис вернул ответ без снимка состояния.")
        return decodeState(state)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") ||
            path.contains('\\') || path.contains('?') || path.contains('#')
        ) {
            throw RuntimeBridgeException("Недопустимый локальный API-путь.")
        }

        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")

            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                if (bytes.size > MAX_REQUEST_BYTES) {
                    throw RuntimeBridgeException("Запрос превышает локальный лимит размера.")
                }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { stream -> stream.write(bytes) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.use(::readBounded)
                ?: throw RuntimeBridgeException("Локальный сервис не вернул тело ответа (HTTP $status).")
            val json = try {
                JSONObject(String(payload, StandardCharsets.UTF_8))
            } catch (error: Exception) {
                throw RuntimeBridgeException("Локальный сервис вернул некорректный JSON.")
            }
            if (status !in 200..299) {
                val message = json.optString("error").take(MAX_ERROR_CHARS)
                    .ifBlank { "HTTP $status" }
                throw RuntimeBridgeException(message)
            }
            return json
        } catch (error: RuntimeBridgeException) {
            throw error
        } catch (error: Exception) {
            throw RuntimeBridgeException(
                "Нет связи с JARVIS в Termux. Запустите локальную консоль и повторите подключение."
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeState(json: JSONObject): RuntimeBridgeSnapshot {
        val sessionsJson = json.optJSONArray("sessions")
            ?: throw RuntimeBridgeException("В снимке JARVIS отсутствует список сессий.")
        val tasksJson = json.optJSONArray("tasks")
            ?: throw RuntimeBridgeException("В снимке JARVIS отсутствует список задач.")
        val eventsJson = json.optJSONArray("events") ?: JSONArray()
        if (sessionsJson.length() > MAX_SESSIONS || tasksJson.length() > MAX_TASKS ||
            eventsJson.length() > MAX_EVENTS
        ) {
            throw RuntimeBridgeException("Снимок JARVIS превышает лимиты Android-клиента.")
        }

        val sessions = ArrayList<RuntimeSessionSnapshot>(sessionsJson.length())
        for (index in 0 until sessionsJson.length()) {
            val item = sessionsJson.optJSONObject(index)
                ?: throw RuntimeBridgeException("Некорректная запись сессии в снимке JARVIS.")
            sessions += RuntimeSessionSnapshot(
                sessionId = requiredString(item, "session_id"),
                title = item.optString("title").ifBlank { requiredString(item, "session_id") },
                status = requiredString(item, "status"),
                revision = requiredInt(item, "revision"),
            )
        }

        val tasks = ArrayList<RuntimeTaskSnapshot>(tasksJson.length())
        for (index in 0 until tasksJson.length()) {
            val item = tasksJson.optJSONObject(index)
                ?: throw RuntimeBridgeException("Некорректная запись задачи в снимке JARVIS.")
            val failure = item.opt("failure_code")
            val result = item.opt("result")
            tasks += RuntimeTaskSnapshot(
                taskId = requiredString(item, "task_id"),
                sessionId = requiredString(item, "session_id"),
                title = requiredString(item, "title"),
                status = requiredString(item, "status"),
                revision = requiredInt(item, "revision"),
                failureCode = failure.takeUnless { it == null || it == JSONObject.NULL }?.toString(),
                resultJson = result.takeUnless { it == null || it == JSONObject.NULL }?.toString(),
            )
        }

        val capabilities = json.optJSONObject("capabilities") ?: JSONObject()
        return RuntimeBridgeSnapshot(
            managerId = requiredString(json, "manager_id"),
            checkpointVersion = requiredLong(json, "checkpoint_version"),
            sessions = sessions,
            tasks = tasks,
            eventCount = eventsJson.length(),
            externalActionsAllowed = capabilities.optBoolean("external_actions", false),
            toolExecutionAllowed = capabilities.optBoolean("tool_execution", false),
            remoteAccessAllowed = capabilities.optBoolean("remote_access", false),
        )
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
                throw RuntimeBridgeException("Ответ локального сервиса превышает лимит размера.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun pathSegment(value: String): String {
        if (!ID_PATTERN.matches(value)) {
            throw RuntimeBridgeException("Некорректный идентификатор задачи или сессии.")
        }
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun requireStatus(value: String, allowed: Set<String>) {
        if (value !in allowed) throw RuntimeBridgeException("Недопустимый статус сессии.")
    }

    private fun requiredString(json: JSONObject, key: String): String {
        val value = json.opt(key)
        if (value !is String || value.isBlank() || value.length > MAX_FIELD_CHARS ||
            value.any { it == '\u0000' }
        ) {
            throw RuntimeBridgeException("Некорректное поле $key в ответе локального сервиса.")
        }
        return value
    }

    private fun requiredInt(json: JSONObject, key: String): Int {
        val value = json.opt(key)
        if (value !is Number || value.toDouble() % 1.0 != 0.0 ||
            value.toLong() !in 0..Int.MAX_VALUE.toLong()
        ) {
            throw RuntimeBridgeException("Некорректное числовое поле $key.")
        }
        return value.toInt()
    }

    private fun requiredLong(json: JSONObject, key: String): Long {
        val value = json.opt(key)
        if (value !is Number || value.toDouble() % 1.0 != 0.0 || value.toLong() < 0L) {
            throw RuntimeBridgeException("Некорректное числовое поле $key.")
        }
        return value.toLong()
    }

    private companion object {
        const val BASE_URL = "http://127.0.0.1:8765"
        const val CONNECT_TIMEOUT_MS = 1_800
        const val READ_TIMEOUT_MS = 2_500
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_ERROR_CHARS = 240
        const val MAX_FIELD_CHARS = 1024
        const val MAX_SESSIONS = 4096
        const val MAX_TASKS = 16384
        const val MAX_EVENTS = 65536
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
