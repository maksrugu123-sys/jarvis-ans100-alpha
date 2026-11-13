package org.jarvis.ans100.alpha.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

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

data class RuntimeWorkspaceSnapshot(
    val revision: Long,
    val document: JSONObject,
)

data class RuntimeWorkspaceMergePlan(
    val expectedRevision: Long,
    val mergedDocument: JSONObject,
    val addedCount: Int,
    val identicalCount: Int,
    val conflictCount: Int,
)

data class RuntimeWorkspaceBackupExport(
    val encryptedBytes: ByteArray,
    val revision: Long,
    val document: JSONObject,
)

data class RuntimeWorkspaceRestorePlan(
    val expectedRevision: Long,
    val workspace: JSONObject,
    val backupCreatedAt: String,
    val conversationCount: Int,
    val memoryCount: Int,
    val taskCount: Int,
    val settingCount: Int,
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
            requiredString(health, "auth_scheme") != "Bearer" ||
            requiredInt(health, "workspace_api_version") != 1
        ) {
            throw RuntimeBridgeException("Локальный сервис не подтвердил готовность JPVR.")
        }
        return decodeState(request("GET", "/api/state"))
    }

    fun refresh(): RuntimeBridgeSnapshot =
        decodeState(request("GET", "/api/state"))

    fun getWorkspace(): RuntimeWorkspaceSnapshot =
        decodeWorkspace(request("GET", "/api/workspace"))

    fun saveWorkspace(
        document: JSONObject,
        expectedRevision: Long,
    ): RuntimeWorkspaceSnapshot {
        if (expectedRevision < 0L) {
            throw RuntimeBridgeException("Версия общей памяти не может быть отрицательной.")
        }
        return decodeWorkspace(request(
            "POST",
            "/api/workspace",
            JSONObject()
                .put("expected_revision", expectedRevision)
                .put("workspace", document),
        ))
    }

    fun restoreWorkspace(
        document: JSONObject,
        expectedRevision: Long,
    ): RuntimeWorkspaceSnapshot {
        if (expectedRevision < 0L) {
            throw RuntimeBridgeException("Ревизия восстановления недопустима.")
        }
        validateTransferDocument(document)
        return decodeWorkspace(request(
            "POST",
            "/api/workspace/restore",
            JSONObject()
                .put("expected_revision", expectedRevision)
                .put("workspace", document),
        ))
    }

    fun createMemory(
        content: String,
        category: String,
        expectedRevision: Long,
    ): RuntimeWorkspaceSnapshot {
        val cleanContent = content.trim()
        val cleanCategory = category.trim()
        if (cleanContent.isEmpty() || cleanContent.length > 10_000) {
            throw RuntimeBridgeException("Воспоминание должно содержать от 1 до 10 000 символов.")
        }
        if (cleanCategory.isEmpty() || cleanCategory.length > 64) {
            throw RuntimeBridgeException("Категория должна содержать от 1 до 64 символов.")
        }
        val current = getWorkspace()
        val document = JSONObject(current.document.toString())
        val memories = document.getJSONObject("records").getJSONArray("memories")
        if (memories.length() >= 10_000) {
            throw RuntimeBridgeException("Достигнут лимит в 10 000 воспоминаний.")
        }
        memories.put(JSONObject()
            .put("id", UUID.randomUUID().toString().replace("-", ""))
            .put("revision", 0)
            .put("updated_at", Instant.now().toString())
            .put("content", cleanContent)
            .put("category", cleanCategory)
            .put("user_approved", true))
        return saveWorkspace(sealWorkspace(document), expectedRevision)
    }

    fun deleteMemory(memoryId: String, expectedRevision: Long): RuntimeWorkspaceSnapshot {
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$").matches(memoryId)) {
            throw RuntimeBridgeException("Идентификатор воспоминания некорректен.")
        }
        val current = getWorkspace()
        val document = JSONObject(current.document.toString())
        val records = document.getJSONObject("records")
        val oldMemories = records.getJSONArray("memories")
        val retained = JSONArray()
        var removed = false
        for (index in 0 until oldMemories.length()) {
            val item = oldMemories.getJSONObject(index)
            if (item.optString("id") == memoryId) {
                removed = true
            } else {
                retained.put(item)
            }
        }
        if (!removed) throw RuntimeBridgeException("Воспоминание уже отсутствует в общей памяти.")
        records.put("memories", retained)
        return saveWorkspace(sealWorkspace(document), expectedRevision)
    }

    fun exportEncryptedWorkspace(passphrase: CharArray): ByteArray {
        val current = getWorkspace()
        validateTransferDocument(current.document)
        return WorkspaceTransferCrypto.encrypt(canonicalJson(current.document), passphrase)
    }

    fun decryptWorkspaceBundle(bundleBytes: ByteArray, passphrase: CharArray): JSONObject {
        val plaintext = WorkspaceTransferCrypto.decrypt(bundleBytes, passphrase)
        val imported = try {
            JSONObject(plaintext)
        } catch (error: Exception) {
            throw RuntimeBridgeException("Расшифрованный файл не содержит JSON workspace.")
        }
        if (canonicalJson(imported) != plaintext) {
            throw RuntimeBridgeException("JSON workspace не соответствует каноническому формату.")
        }
        validateTransferDocument(imported)
        return imported
    }

    fun exportEncryptedWorkspaceBackup(passphrase: CharArray): RuntimeWorkspaceBackupExport {
        val current = getWorkspace()
        validateTransferDocument(current.document)
        val backup = sealWorkspaceBackup(current.document)
        val encrypted = WorkspaceTransferCrypto.encryptBackup(
            canonicalJson(backup), passphrase,
        )
        return RuntimeWorkspaceBackupExport(
            encryptedBytes = encrypted,
            revision = current.revision,
            document = JSONObject(current.document.toString()),
        )
    }

    fun decryptWorkspaceBackupBundle(bundleBytes: ByteArray, passphrase: CharArray): JSONObject {
        val plaintext = WorkspaceTransferCrypto.decryptBackup(bundleBytes, passphrase)
        val backup = try {
            JSONObject(plaintext)
        } catch (error: Exception) {
            throw RuntimeBridgeException("Резервная копия не содержит корректный JSON.")
        }
        if (canonicalJson(backup) != plaintext) {
            throw RuntimeBridgeException("Резервная копия не соответствует каноническому JSON.")
        }
        validateWorkspaceBackup(backup)
        return backup
    }

    fun prepareWorkspaceRestore(
        backup: JSONObject,
        expectedRevision: Long,
    ): RuntimeWorkspaceRestorePlan {
        validateWorkspaceBackup(backup)
        val current = getWorkspace()
        if (current.revision != expectedRevision) {
            throw RuntimeBridgeException("Локальная память изменилась. Обновите её и создайте новый backup.")
        }
        val restored = backup.getJSONObject("workspace")
        if (current.document.optString("workspace_id") != restored.optString("workspace_id")) {
            throw RuntimeBridgeException("Backup относится к другому workspace_id. Замена запрещена.")
        }
        val records = restored.getJSONObject("records")
        return RuntimeWorkspaceRestorePlan(
            expectedRevision = expectedRevision,
            workspace = JSONObject(restored.toString()),
            backupCreatedAt = backup.getString("created_at"),
            conversationCount = records.getJSONArray("conversations").length(),
            memoryCount = records.getJSONArray("memories").length(),
            taskCount = records.getJSONArray("tasks").length(),
            settingCount = records.getJSONArray("settings").length(),
        )
    }

    fun commitWorkspaceRestore(plan: RuntimeWorkspaceRestorePlan): RuntimeWorkspaceSnapshot {
        if (plan.expectedRevision < 0L) {
            throw RuntimeBridgeException("Ревизия восстановления недопустима.")
        }
        val replacement = JSONObject(plan.workspace.toString())
        validateTransferDocument(replacement)
        return restoreWorkspace(sealWorkspace(replacement), plan.expectedRevision)
    }

    private fun sealWorkspaceBackup(workspace: JSONObject): JSONObject {
        val backup = JSONObject()
            .put("format", "jarvis.workspace.backup")
            .put("version", JSONObject().put("major", 1).put("minor", 0))
            .put("backup_id", UUID.randomUUID().toString().replace("-", ""))
            .put("created_at", Instant.now().toString())
            .put("source_platform", "android")
            .put("workspace", JSONObject(workspace.toString()))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(backup).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        backup.put("integrity", JSONObject()
            .put("algorithm", "sha-256")
            .put("digest", digest))
        return backup
    }

    private fun validateWorkspaceBackup(backup: JSONObject) {
        if (backup.keys().asSequence().toSet() != setOf(
                "format", "version", "backup_id", "created_at",
                "source_platform", "workspace", "integrity",
            ) ||
            backup.opt("format") != "jarvis.workspace.backup" ||
            backup.optJSONObject("version")?.let {
                it.keys().asSequence().toSet() == setOf("major", "minor") &&
                    it.opt("major") == 1 && it.opt("minor") == 0
            } != true ||
            backup.opt("backup_id") !is String ||
            !Regex("^[a-f0-9]{32}$").matches(backup.optString("backup_id")) ||
            backup.opt("created_at") !is String ||
            runCatching { OffsetDateTime.parse(backup.optString("created_at")) }.isFailure ||
            backup.opt("source_platform") !in setOf("desktop", "android")
        ) {
            throw RuntimeBridgeException("Резервная копия не соответствует схеме JARVIS V1.")
        }
        val workspace = backup.optJSONObject("workspace")
            ?: throw RuntimeBridgeException("В резервной копии отсутствует workspace.")
        validateTransferDocument(workspace)
        val integrity = backup.optJSONObject("integrity")
            ?: throw RuntimeBridgeException("В резервной копии отсутствует checksum.")
        if (integrity.keys().asSequence().toSet() != setOf("algorithm", "digest") ||
            integrity.opt("algorithm") != "sha-256" ||
            integrity.opt("digest") !is String ||
            !Regex("^[a-f0-9]{64}$").matches(integrity.optString("digest"))
        ) {
            throw RuntimeBridgeException("Алгоритм или checksum backup не поддерживается.")
        }
        val unsigned = JSONObject(backup.toString()).also { it.remove("integrity") }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(unsigned).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (actual != integrity.optString("digest")) {
            throw RuntimeBridgeException("Контрольная сумма резервной копии не совпадает.")
        }
    }

    fun prepareWorkspaceMerge(
        imported: JSONObject,
        expectedRevision: Long,
    ): RuntimeWorkspaceMergePlan {
        validateTransferDocument(imported)
        val current = getWorkspace()
        if (current.revision != expectedRevision) {
            throw RuntimeBridgeException("Локальная память изменилась. Обновите её и повторите предпросмотр.")
        }
        if (current.document.optString("workspace_id") != imported.optString("workspace_id")) {
            throw RuntimeBridgeException("Файл относится к другому workspace_id.")
        }
        val merged = JSONObject(current.document.toString())
        val targetRecords = merged.getJSONObject("records")
        val sourceRecords = imported.getJSONObject("records")
        var added = 0
        var identical = 0
        var conflicts = 0
        for (collection in listOf("conversations", "memories", "tasks")) {
            val target = targetRecords.getJSONArray(collection)
            val source = sourceRecords.getJSONArray(collection)
            val byId = HashMap<String, JSONObject>()
            for (index in 0 until target.length()) {
                val item = target.getJSONObject(index)
                byId[item.getString("id")] = item
            }
            for (index in 0 until source.length()) {
                val item = source.getJSONObject(index)
                val existing = byId[item.getString("id")]
                if (existing == null) {
                    target.put(item)
                    byId[item.getString("id")] = item
                    added++
                } else if (canonicalJson(existing) == canonicalJson(item)) {
                    identical++
                } else {
                    conflicts++
                }
            }
        }
        val targetSettings = targetRecords.getJSONArray("settings")
        val sourceSettings = sourceRecords.getJSONArray("settings")
        val settingByKey = HashMap<String, JSONObject>()
        for (index in 0 until targetSettings.length()) {
            val item = targetSettings.getJSONObject(index)
            settingByKey[item.getString("key")] = item
        }
        for (index in 0 until sourceSettings.length()) {
            val item = sourceSettings.getJSONObject(index)
            val existing = settingByKey[item.getString("key")]
            if (existing == null) {
                targetSettings.put(item)
                settingByKey[item.getString("key")] = item
                added++
            } else if (canonicalJson(existing) == canonicalJson(item)) {
                identical++
            } else {
                conflicts++
            }
        }
        return RuntimeWorkspaceMergePlan(
            expectedRevision = expectedRevision,
            mergedDocument = merged,
            addedCount = added,
            identicalCount = identical,
            conflictCount = conflicts,
        )
    }

    fun commitWorkspaceMerge(plan: RuntimeWorkspaceMergePlan): RuntimeWorkspaceSnapshot {
        if (plan.expectedRevision < 0L) {
            throw RuntimeBridgeException("Ревизия импорта недопустима.")
        }
        val sealed = sealWorkspace(plan.mergedDocument)
        validateTransferDocument(sealed)
        return saveWorkspace(sealed, plan.expectedRevision)
    }

    private fun validateTransferDocument(document: JSONObject) {
        fun exactKeys(value: JSONObject, allowed: Set<String>, required: Set<String>) {
            val keys = value.keys().asSequence().toSet()
            if (!keys.containsAll(required) || keys.any { it !in allowed }) {
                throw RuntimeBridgeException("Поля импортируемого workspace не соответствуют схеме.")
            }
        }
        fun requiredText(value: JSONObject, key: String, maximum: Int): String {
            val text = value.opt(key)
            if (text !is String || text.length > maximum) {
                throw RuntimeBridgeException("Поле $key имеет недопустимое значение.")
            }
            return text
        }
        fun validId(value: String): Boolean =
            Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$").matches(value)
        fun validDate(value: String): Boolean =
            runCatching { OffsetDateTime.parse(value) }.isSuccess
        fun validRevision(value: JSONObject): Boolean {
            val raw = value.opt("revision")
            return (raw is Int || raw is Long) &&
                (raw as Number).toLong() in 0..2_147_483_647L
        }
        if (document.keys().asSequence().toSet() != setOf(
                "format", "version", "workspace_id", "export_id", "exported_at",
                "source_platform", "records", "integrity",
            ) ||
            document.opt("format") != "jarvis.workspace" ||
            document.optJSONObject("version")?.let {
                it.keys().asSequence().toSet() == setOf("major", "minor") &&
                    it.opt("major") == 1 && it.opt("minor") == 0
            } != true ||
            document.opt("workspace_id") !is String ||
            !validId(requiredText(document, "workspace_id", 128)) ||
            document.opt("export_id") != "workspace-current" ||
            document.opt("exported_at") !is String ||
            !validDate(requiredText(document, "exported_at", 64)) ||
            document.opt("source_platform") !is String ||
            requiredText(document, "source_platform", 16) !in setOf("desktop", "android")
        ) {
            throw RuntimeBridgeException("Файл не соответствует схеме рабочего пространства V1.")
        }
        val integrity = document.optJSONObject("integrity")
            ?: throw RuntimeBridgeException("В файле отсутствует контрольная сумма.")
        if (integrity.keys().asSequence().toSet() != setOf("algorithm", "digest") ||
            integrity.opt("algorithm") != "sha-256" ||
            integrity.opt("digest") !is String ||
            !Regex("^[a-f0-9]{64}$").matches(requiredText(integrity, "digest", 64))) {
            throw RuntimeBridgeException("Алгоритм или значение контрольной суммы не поддерживается.")
        }
        val unsigned = JSONObject(document.toString()).also { it.remove("integrity") }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(unsigned).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (actual != integrity.optString("digest")) {
            throw RuntimeBridgeException("Контрольная сумма рабочего пространства не совпадает.")
        }
        val records = document.optJSONObject("records")
            ?: throw RuntimeBridgeException("В файле отсутствуют коллекции записей.")
        exactKeys(
            records,
            setOf("conversations", "memories", "tasks", "settings"),
            setOf("conversations", "memories", "tasks", "settings"),
        )
        val recordIds = HashSet<String>()
        for ((collection, maximum) in listOf(
            "conversations" to 10_000, "memories" to 10_000,
            "tasks" to 10_000, "settings" to 1_000,
        )) {
            val array = records.optJSONArray(collection)
                ?: throw RuntimeBridgeException("Коллекция $collection должна быть массивом.")
            if (array.length() > maximum) {
                throw RuntimeBridgeException("Коллекция $collection превышает лимит.")
            }
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw RuntimeBridgeException("Запись $collection должна быть объектом.")
                if (collection != "settings") {
                    val id = requiredText(item, "id", 128)
                    if (!validId(id) || !recordIds.add(id) || !validRevision(item) ||
                        !validDate(requiredText(item, "updated_at", 64))) {
                        throw RuntimeBridgeException("Идентификатор, ревизия или дата записи $collection некорректны.")
                    }
                }
                when (collection) {
                    "memories" -> {
                        exactKeys(
                            item,
                            setOf("id", "revision", "updated_at", "content", "category", "user_approved"),
                            setOf("id", "revision", "updated_at", "content", "category", "user_approved"),
                        )
                        val content = requiredText(item, "content", 10_000)
                        val category = requiredText(item, "category", 64)
                        if (content.isBlank() || item.opt("user_approved") != true || category.length > 64) {
                            throw RuntimeBridgeException("Воспоминание не прошло проверку согласия и содержимого.")
                        }
                    }
                    "tasks" -> {
                        exactKeys(
                            item,
                            setOf("id", "revision", "updated_at", "title", "status", "result_summary"),
                            setOf("id", "revision", "updated_at", "title", "status"),
                        )
                        val title = requiredText(item, "title", 512)
                        if (title.isBlank() || requiredText(item, "status", 16) !in setOf(
                                "queued", "running", "paused", "completed", "failed", "cancelled",
                            ) ||
                            (item.has("result_summary") && item.opt("result_summary") !is String) ||
                            (item.opt("result_summary") is String &&
                                item.optString("result_summary").length > 4_000)
                        ) {
                            throw RuntimeBridgeException("Задача не прошла проверку схемы.")
                        }
                    }
                    "conversations" -> {
                        exactKeys(
                            item,
                            setOf("id", "revision", "updated_at", "title", "messages"),
                            setOf("id", "revision", "updated_at", "title", "messages"),
                        )
                        if (requiredText(item, "title", 160).length > 160) {
                            throw RuntimeBridgeException("Название диалога превышает лимит.")
                        }
                        val messages = item.optJSONArray("messages")
                            ?: throw RuntimeBridgeException("Сообщения диалога должны быть массивом.")
                        if (messages.length() > 50_000) {
                            throw RuntimeBridgeException("Диалог содержит слишком много сообщений.")
                        }
                        val messageIds = HashSet<String>()
                        for (messageIndex in 0 until messages.length()) {
                            val message = messages.optJSONObject(messageIndex)
                                ?: throw RuntimeBridgeException("Сообщение должно быть объектом.")
                            exactKeys(
                                message,
                                setOf("id", "role", "created_at", "content"),
                                setOf("id", "role", "created_at", "content"),
                            )
                            val messageId = requiredText(message, "id", 128)
                            val role = requiredText(message, "role", 16)
                            val createdAt = requiredText(message, "created_at", 64)
                            val content = requiredText(message, "content", 100_000)
                            if (!validId(messageId) || !messageIds.add(messageId) ||
                                role !in setOf("user", "assistant", "system") ||
                                !validDate(createdAt) || content.length > 100_000
                            ) {
                                throw RuntimeBridgeException("Сообщение диалога не прошло проверку.")
                            }
                        }
                    }
                }
            }
        }
        val settings = records.getJSONArray("settings")
        val settingKeys = HashSet<String>()
        for (index in 0 until settings.length()) {
            val setting = settings.optJSONObject(index)
                ?: throw RuntimeBridgeException("Настройка должна быть объектом.")
            exactKeys(setting, setOf("key", "value"), setOf("key", "value"))
            val key = requiredText(setting, "key", 64)
            val normalized = key.replace("-", "_").replace(".", "_")
            if (!Regex("^[a-z][a-z0-9._-]{0,63}$").matches(key) ||
                !settingKeys.add(key) ||
                listOf("password", "passwd", "secret", "token", "credential", "api_key", "private_key")
                    .any { normalized.contains(it) } ||
                normalized == "auth" || normalized.startsWith("auth_")
            ) {
                throw RuntimeBridgeException("Ключ настройки недопустим или содержит секрет.")
            }
            val value = setting.opt("value")
            val portable = value == JSONObject.NULL || value is String || value is Boolean ||
                (value is Int || value is Long) &&
                (value as Number).toLong() in -9_007_199_254_740_991L..9_007_199_254_740_991L
            if (!portable) {
                throw RuntimeBridgeException("Значение настройки не является переносимым типом.")
            }
        }
    }

    private fun sealWorkspace(document: JSONObject): JSONObject {
        document.put("exported_at", Instant.now().toString())
        document.put("source_platform", "android")
        document.remove("integrity")
        val canonical = canonicalJson(document).toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        document.put("integrity", JSONObject()
            .put("algorithm", "sha-256")
            .put("digest", digest))
        return document
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{", postfix = "}",
        ) { key -> JSONObject.quote(key) + ":" + canonicalJson(value.get(key)) }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[", postfix = "]",
        ) { index -> canonicalJson(value.get(index)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> throw RuntimeBridgeException("Общая память содержит неподдерживаемый JSON-тип.")
    }

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

    private fun decodeWorkspace(json: JSONObject): RuntimeWorkspaceSnapshot {
        val revision = requiredLong(json, "revision")
        val document = json.optJSONObject("workspace")
            ?: throw RuntimeBridgeException("Сервис вернул общую память без документа workspace.")
        if (document.optString("format") != "jarvis.workspace" ||
            document.optJSONObject("version")?.optInt("major", -1) != 1 ||
            document.optJSONObject("integrity")?.optString("algorithm") != "sha-256"
        ) {
            throw RuntimeBridgeException("Версия или контрольная сумма общей памяти не поддерживается.")
        }
        return RuntimeWorkspaceSnapshot(revision, document)
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
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
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
