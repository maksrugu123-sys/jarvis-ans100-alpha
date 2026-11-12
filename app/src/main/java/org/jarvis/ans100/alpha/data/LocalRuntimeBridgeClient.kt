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

data class RuntimePlanningAction(
    val actionId: String,
    val summary: String,
    val preconditions: List<String>,
    val addFacts: List<String>,
    val deleteFacts: List<String> = emptyList(),
    val rationale: String = "",
)

data class RuntimeReasoningPreviewRequest(
    val taskId: String,
    val goalDescription: String,
    val initialFacts: List<String>,
    val goalFacts: List<String>,
    val actions: List<RuntimePlanningAction>,
)

data class RuntimeReasoningPreviewStep(
    val index: Int,
    val actionId: String,
    val summary: String,
    val rationale: String,
    val requiredFacts: List<String>,
    val addedFacts: List<String>,
    val deletedFacts: List<String>,
    val factsAfterStep: List<String>,
)

data class RuntimeReasoningPreviewResult(
    val taskId: String,
    val status: String,
    val expandedStates: Int,
    val goalFacts: List<String>,
    val predictedFinalFacts: List<String>,
    val plan: List<RuntimeReasoningPreviewStep>,
    val notice: String,
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
    @Volatile
    private var accessToken: String? = null

    fun setAccessToken(token: String?) {
        if (token != null && !ACCESS_TOKEN_PATTERN.matches(token)) {
            throw RuntimeBridgeException("Формат токена доступа JARVIS некорректен.")
        }
        accessToken = token
    }

    fun connect(): RuntimeBridgeSnapshot {
        val health = request("GET", "/api/health")
        if (requiredString(health, "status") != "ok" ||
            requiredString(health, "storage") != "jpvr" ||
            requiredString(health, "api_schema") != "jarvis.local-console" ||
            requiredInt(health, "api_version") != 2 ||
            !health.optBoolean("auth_required", false) ||
            requiredString(health, "auth_scheme") != "Bearer"
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

    fun previewReasoning(
        preview: RuntimeReasoningPreviewRequest,
    ): RuntimeReasoningPreviewResult {
        validateIdentifier(preview.taskId, "task_id")
        validateText(preview.goalDescription, "goal_description", 512)
        validateFacts(preview.initialFacts, "initial_facts")
        validateFacts(preview.goalFacts, "goal_facts")
        if (preview.goalFacts.isEmpty()) {
            throw RuntimeBridgeException("Укажите хотя бы один целевой факт.")
        }
        if (preview.actions.size > MAX_PREVIEW_ACTIONS) {
            throw RuntimeBridgeException("Для Android preview доступно не более 16 действий.")
        }
        val actionIds = HashSet<String>()
        val actionsJson = JSONArray()
        val possibleFacts = preview.initialFacts.toMutableSet()
        preview.actions.forEach { action ->
            validateIdentifier(action.actionId, "action_id")
            if (!actionIds.add(action.actionId)) {
                throw RuntimeBridgeException("Идентификаторы действий должны быть уникальными.")
            }
            validateText(action.summary, "action summary", 256)
            validateText(action.rationale, "action rationale", 512, allowBlank = true)
            validateFacts(action.preconditions, "preconditions")
            validateFacts(action.addFacts, "add_facts")
            validateFacts(action.deleteFacts, "delete_facts")
            if (action.addFacts.isEmpty() && action.deleteFacts.isEmpty()) {
                throw RuntimeBridgeException("Каждое действие должно менять хотя бы один факт.")
            }
            if (action.addFacts.toSet().intersect(action.deleteFacts.toSet()).isNotEmpty()) {
                throw RuntimeBridgeException("Действие не может одновременно добавлять и удалять один факт.")
            }
            possibleFacts.addAll(action.addFacts)
            if (possibleFacts.size > MAX_PREVIEW_RESULT_FACTS) {
                throw RuntimeBridgeException("Android preview ограничивает итоговую модель 64 фактами.")
            }
            actionsJson.put(JSONObject()
                .put("action_id", action.actionId)
                .put("summary", action.summary.trim())
                .put("preconditions", toJsonArray(action.preconditions))
                .put("add_facts", toJsonArray(action.addFacts))
                .put("delete_facts", toJsonArray(action.deleteFacts))
                .put("rationale", action.rationale.trim()))
        }
        val payload = JSONObject()
            .put("task_id", preview.taskId)
            .put("goal_description", preview.goalDescription.trim())
            .put("initial_facts", toJsonArray(preview.initialFacts))
            .put("goal_facts", toJsonArray(preview.goalFacts))
            .put("actions", actionsJson)
        return decodeReasoningPreview(request("POST", "/api/reasoning/preview", payload))
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
            accessToken?.let { token -> connection.setRequestProperty("Authorization", "Bearer $token") }
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

    private fun decodeReasoningPreview(json: JSONObject): RuntimeReasoningPreviewResult {
        val status = requiredString(json, "status")
        if (status !in setOf("solved", "no_plan", "depth_limit", "search_limit")) {
            throw RuntimeBridgeException("Неизвестный статус символьного планирования.")
        }
        val planJson = json.optJSONArray("plan")
            ?: throw RuntimeBridgeException("В ответе reasoning preview отсутствует план.")
        if (planJson.length() > MAX_PREVIEW_ACTIONS) {
            throw RuntimeBridgeException("План превышает лимит Android-клиента.")
        }
        val plan = ArrayList<RuntimeReasoningPreviewStep>(planJson.length())
        for (index in 0 until planJson.length()) {
            val item = planJson.optJSONObject(index)
                ?: throw RuntimeBridgeException("Некорректный шаг reasoning preview.")
            plan += RuntimeReasoningPreviewStep(
                index = requiredInt(item, "index"),
                actionId = requiredString(item, "action_id"),
                summary = requiredString(item, "summary"),
                rationale = item.optString("rationale").take(MAX_RATIONALE_CHARS),
                requiredFacts = decodeFacts(item, "required_facts"),
                addedFacts = decodeFacts(item, "added_facts"),
                deletedFacts = decodeFacts(item, "deleted_facts"),
                factsAfterStep = decodeFacts(item, "facts_after_step"),
            )
        }
        return RuntimeReasoningPreviewResult(
            taskId = requiredString(json, "task_id"),
            status = status,
            expandedStates = requiredInt(json, "expanded_states"),
            goalFacts = decodeFacts(json, "goal_facts"),
            predictedFinalFacts = decodeFacts(json, "predicted_final_facts"),
            plan = plan,
            notice = requiredString(json, "notice"),
        )
    }

    private fun decodeFacts(json: JSONObject, key: String): List<String> {
        val values = json.optJSONArray(key)
            ?: throw RuntimeBridgeException("В ответе reasoning preview отсутствует список $key.")
        val limit = if (key == "facts_after_step" || key == "predicted_final_facts") {
            MAX_PREVIEW_RESULT_FACTS
        } else {
            MAX_PREVIEW_FACTS
        }
        if (values.length() > limit) {
            throw RuntimeBridgeException("Список $key превышает лимит Android-клиента.")
        }
        val facts = ArrayList<String>(values.length())
        for (index in 0 until values.length()) {
            val value = values.opt(index)
            if (value !is String || !ID_PATTERN.matches(value)) {
                throw RuntimeBridgeException("В ответе reasoning preview найден некорректный факт.")
            }
            facts += value
        }
        if (facts.distinct().size != facts.size) {
            throw RuntimeBridgeException("В ответе reasoning preview есть повторяющиеся факты.")
        }
        return facts
    }

    private fun validateIdentifier(value: String, field: String) {
        if (!ID_PATTERN.matches(value)) {
            throw RuntimeBridgeException("Поле $field должно быть идентификатором из 1–128 символов.")
        }
    }

    private fun validateText(
        value: String,
        field: String,
        maximum: Int,
        allowBlank: Boolean = false,
    ) {
        val clean = value.trim()
        if ((!allowBlank && clean.isEmpty()) || clean.length > maximum || clean.any { it == '\u0000' }) {
            throw RuntimeBridgeException("Поле $field должно содержать не более $maximum символов.")
        }
    }

    private fun validateFacts(values: List<String>, field: String) {
        if (values.size > MAX_PREVIEW_FACTS || values.distinct().size != values.size ||
            values.any { !ID_PATTERN.matches(it) }
        ) {
            throw RuntimeBridgeException("Список $field должен содержать до 32 уникальных идентификаторов.")
        }
    }

    private fun toJsonArray(values: List<String>): JSONArray =
        JSONArray().apply { values.forEach { put(it) } }

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
        val ACCESS_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        const val CONNECT_TIMEOUT_MS = 1_800
        const val READ_TIMEOUT_MS = 2_500
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_ERROR_CHARS = 240
        const val MAX_FIELD_CHARS = 1024
        const val MAX_SESSIONS = 4096
        const val MAX_TASKS = 16384
        const val MAX_EVENTS = 65536
        const val MAX_PREVIEW_ACTIONS = 16
        const val MAX_PREVIEW_FACTS = 32
        const val MAX_PREVIEW_RESULT_FACTS = 64
        const val MAX_RATIONALE_CHARS = 512
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
