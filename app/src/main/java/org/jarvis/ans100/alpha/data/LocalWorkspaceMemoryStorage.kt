package org.jarvis.ans100.alpha.data

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Versioned app-private storage for explicitly approved memories.
 * This stores user notes only; it is not a JPVR file or a cross-device sync mechanism.
 */
class SharedPreferencesLocalWorkspaceMemoryStorage(context: Context) : ConversationStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        "jarvis_android_approved_memories_v1",
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString("records", null)

    override fun write(value: String): Boolean =
        preferences.edit().putString("records", value).commit()
}

internal object LocalWorkspaceMemoryCodec {
    private const val HEADER_V1 = "JARVIS_ANDROID_APPROVED_MEMORY\t1"
    private const val HEADER = "JARVIS_ANDROID_APPROVED_MEMORY\t2"
    private const val MAX_ENCODED_CHARS = 3 * 1024 * 1024
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    fun encode(records: List<LocalWorkspaceMemory>): String {
        if (records.size > LocalWorkspaceMemoryCache.MAX_CACHED_RECORDS) {
            throw LocalConversationException("Локальная память превышает лимит записей.")
        }
        val ids = HashSet<String>()
        val lines = ArrayList<String>(records.size + 1)
        lines += HEADER
        records.forEach { record ->
            validate(record, ids)
            lines += listOf(
                "M",
                encodeText(record.id),
                encodeText(record.category),
                encodeText(record.content),
                encodeText(record.updatedAt),
                encodeText(record.supersededById.orEmpty()),
            ).joinToString("\t")
        }
        val result = lines.joinToString("\n")
        if (result.length > MAX_ENCODED_CHARS) {
            throw LocalConversationException("Локальная память превышает лимит хранения.")
        }
        return result
    }

    fun decode(raw: String): List<LocalWorkspaceMemory> {
        if (raw.isEmpty() || raw.length > MAX_ENCODED_CHARS || '\r' in raw) {
            throw LocalConversationException("Файл локальной памяти пуст или превышает лимит.")
        }
        val lines = raw.split('\n')
        val version = lines.firstOrNull()
        if ((version != HEADER && version != HEADER_V1) ||
            lines.size - 1 > LocalWorkspaceMemoryCache.MAX_CACHED_RECORDS
        ) {
            throw LocalConversationException("Неизвестная версия или размер локальной памяти.")
        }
        val ids = HashSet<String>()
        val result = ArrayList<LocalWorkspaceMemory>(lines.size - 1)
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            val expectedFields = if (version == HEADER_V1) 5 else 6
            if (fields.size != expectedFields || fields[0] != "M") {
                throw LocalConversationException("Повреждена запись локальной памяти.")
            }
            val record = LocalWorkspaceMemory(
                id = decodeText(fields[1]),
                category = decodeText(fields[2]),
                content = decodeText(fields[3]),
                updatedAt = decodeText(fields[4]),
                userApproved = true,
                supersededById = if (version == HEADER_V1) null else decodeText(fields[5]).ifEmpty { null },
            )
            validate(record, ids)
            result += record
        }
        if (result.any { record ->
                record.supersededById != null && result.none { it.id == record.supersededById }
            }
        ) {
            throw LocalConversationException("Связь исправления с исходной записью повреждена.")
        }
        // Version 1 is accepted for one-way migration; all subsequent writes use v2.
        if (version == HEADER && encode(result) != raw) {
            throw LocalConversationException("Локальная память имеет неканоничный формат.")
        }
        return result
    }

    private fun validate(record: LocalWorkspaceMemory, ids: MutableSet<String>) {
        if (!record.userApproved ||
            !idPattern.matches(record.id) ||
            !ids.add(record.id) ||
            record.category.isBlank() || record.category != record.category.trim() ||
            record.category.length > LocalWorkspaceMemoryCache.MAX_CATEGORY_CHARS ||
            record.content.isBlank() || record.content != record.content.trim() ||
            record.content.length > LocalWorkspaceMemoryCache.MAX_MEMORY_CHARS ||
            record.updatedAt.isBlank() || record.updatedAt != record.updatedAt.trim() ||
            record.updatedAt.length > 80 ||
            (record.supersededById != null &&
                (!idPattern.matches(record.supersededById) || record.supersededById == record.id)) ||
            listOfNotNull(record.id, record.category, record.content, record.updatedAt, record.supersededById).any { '\u0000' in it }
        ) {
            throw LocalConversationException("Запись локальной памяти не соответствует ограничениям.")
        }
    }

    private fun encodeText(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String {
        try {
            val bytes = decoder.decode(value)
            if (encoder.encodeToString(bytes) != value) {
                throw LocalConversationException("Текст локальной памяти неканоничен.")
            }
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: LocalConversationException) {
            throw error
        } catch (_: Exception) {
            throw LocalConversationException("Не удалось декодировать запись локальной памяти.")
        }
    }
}
