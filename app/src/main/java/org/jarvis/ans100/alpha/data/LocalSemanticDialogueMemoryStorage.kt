package org.jarvis.ans100.alpha.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Durable semantic dialogue memory stored beside approved local memories.
 * The transcript and this semantic index remain separate files.
 */
interface LocalSemanticDialogueMemoryStorage {
    fun read(): LocalSemanticDialogueMemory
    fun write(memory: LocalSemanticDialogueMemory): Boolean
    fun exportSnapshot(): String
    fun clear(): Boolean
}

class PhoneFolderSemanticDialogueMemoryStorage(context: Context) : LocalSemanticDialogueMemoryStorage {
    private val appContext = context.applicationContext
    private val directory = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        "JARVIS/Memory",
    )
    private val file = AtomicFile(File(directory, "semantic-dialogue.jmem"))

    @Synchronized
    override fun read(): LocalSemanticDialogueMemory {
        ensureDirectory()
        if (!file.baseFile.exists()) {
            val empty = LocalSemanticDialogueMemory()
            if (!write(empty)) throw LocalConversationException("Не удалось создать файл смысловой памяти JARVIS.")
            return empty
        }
        if (file.baseFile.length() !in 1L..MAX_FILE_BYTES) {
            throw LocalConversationException("Файл смысловой памяти пуст или превышает лимит.")
        }
        val raw = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        return LocalSemanticDialogueMemoryCodec.decode(raw)
    }

    @Synchronized
    override fun write(memory: LocalSemanticDialogueMemory): Boolean {
        return try {
            ensureDirectory()
            val encoded = LocalSemanticDialogueMemoryCodec.encode(memory)
            val stream = file.startWrite()
            try {
                stream.write(encoded.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    override fun exportSnapshot(): String {
        val raw = LocalSemanticDialogueMemoryCodec.encode(read())
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val filename = "jarvis-semantic-memory-$stamp.jmem"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JARVIS")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw LocalConversationException("Не удалось создать экспорт в Downloads/JARVIS.")
        try {
            appContext.contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(raw.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw LocalConversationException("Не удалось записать экспорт смысловой памяти.")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            appContext.contentResolver.update(uri, ready, null, null)
            return "Downloads/JARVIS/$filename"
        } catch (error: Exception) {
            appContext.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    @Synchronized
    override fun clear(): Boolean = write(LocalSemanticDialogueMemory())

    fun directoryPath(): String = directory.absolutePath

    private companion object {
        const val MAX_FILE_BYTES = 256 * 1024L
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw LocalConversationException("Не удалось создать папку памяти JARVIS.")
        }
    }
}

internal object LocalSemanticDialogueMemoryCodec {
    private const val VERSION = "JARVIS_SEMANTIC_DIALOGUE_MEMORY_JSON_V1"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_ITEMS = 8
    private const val MAX_ITEM_CHARS = 220

    fun encode(memory: LocalSemanticDialogueMemory): String {
        require(memory.activeTasks.size <= MAX_ITEMS && memory.completedTasks.size <= MAX_ITEMS)
        require(memory.explicitPreferences.size <= MAX_ITEMS && memory.unresolvedQuestions.size <= 4)
        val json = JSONObject().put("schema", VERSION)
        fun tasks(values: List<LocalSemanticTask>): JSONArray = JSONArray().apply {
            values.forEach { item ->
                require(item.text.isNotBlank() && item.text.length <= MAX_ITEM_CHARS)
                require(item.sourceMessageId.isNotBlank() && item.sourceMessageId.length <= 128)
                require(item.status in setOf("open", "in_progress", "completed"))
                put(JSONObject()
                    .put("text", item.text)
                    .put("status", item.status)
                    .put("source_message_id", item.sourceMessageId)
                    .put("updated_at_ms", item.updatedAtMillis)
                    .put("completed_by_message_id", item.completedByMessageId ?: JSONObject.NULL)
                    .put("provenance", item.provenance))
            }
        }
        json.put("active_tasks", tasks(memory.activeTasks))
        json.put("completed_tasks", tasks(memory.completedTasks))
        json.put("explicit_preferences", JSONArray().apply {
            memory.explicitPreferences.forEach { item ->
                require(item.text.isNotBlank() && item.text.length <= MAX_ITEM_CHARS)
                require(item.sourceMessageId.isNotBlank() && item.sourceMessageId.length <= 128)
                put(JSONObject().put("text", item.text)
                    .put("source_message_id", item.sourceMessageId)
                    .put("created_at_ms", item.createdAtMillis)
                    .put("provenance", item.provenance))
            }
        })
        json.put("unresolved_questions", JSONArray().apply {
            memory.unresolvedQuestions.forEach {
                require(it.isNotBlank() && it.length <= MAX_ITEM_CHARS)
                put(it)
            }
        })
        val encoded = json.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return encoded
    }

    fun decode(raw: String): LocalSemanticDialogueMemory {
        require(raw.isNotBlank() && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(raw)
        require(json.getString("schema") == VERSION)
        fun readTasks(key: String): List<LocalSemanticTask> {
            val array = json.getJSONArray(key)
            require(array.length() <= MAX_ITEMS)
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val status = item.getString("status")
                require(status in setOf("open", "in_progress", "completed"))
                LocalSemanticTask(
                    text = item.getString("text").also { require(it.isNotBlank() && it.length <= MAX_ITEM_CHARS) },
                    status = status,
                    sourceMessageId = item.getString("source_message_id").also { require(it.isNotBlank() && it.length <= 128) },
                    updatedAtMillis = item.getLong("updated_at_ms"),
                    completedByMessageId = if (item.isNull("completed_by_message_id")) null else item.getString("completed_by_message_id"),
                    provenance = item.optString("provenance", "explicit_user_request"),
                )
            }
        }
        val preferences = json.getJSONArray("explicit_preferences")
        require(preferences.length() <= MAX_ITEMS)
        val questions = json.getJSONArray("unresolved_questions")
        require(questions.length() <= 4)
        return LocalSemanticDialogueMemory(
            activeTasks = readTasks("active_tasks"),
            completedTasks = readTasks("completed_tasks"),
            explicitPreferences = List(preferences.length()) { index ->
                val item = preferences.getJSONObject(index)
                LocalExplicitPreference(
                    text = item.getString("text").also { require(it.isNotBlank() && it.length <= MAX_ITEM_CHARS) },
                    sourceMessageId = item.getString("source_message_id").also { require(it.isNotBlank() && it.length <= 128) },
                    createdAtMillis = item.getLong("created_at_ms"),
                    provenance = item.optString("provenance", "explicit_user_statement"),
                )
            },
            unresolvedQuestions = List(questions.length()) { index ->
                questions.getString(index).also { require(it.isNotBlank() && it.length <= MAX_ITEM_CHARS) }
            },
        )
    }
}
