package org.jarvis.ans100.alpha.data

import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

data class LocalMemoryBackupInfo(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val recordCount: Int?,
    val activeRecordCount: Int?,
    val validationError: String? = null,
)

/**
 * Stores approved JARVIS memories in the app-specific external folder:
 * Android/data/<applicationId>/files/JARVIS/Memory/.
 *
 * The directory is created on first access. AtomicFile prevents a partial write
 * from replacing the last valid memory snapshot. Legacy SharedPreferences are
 * migrated once when the new file does not exist.
 */
class PhoneFolderLocalWorkspaceMemoryStorage(context: Context) : ConversationStorage {
    private val appContext = context.applicationContext
    private val directory: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        "JARVIS/Memory",
    )
    private val memoryFile = AtomicFile(File(directory, "approved-memory.jmem"))
    private val legacyPreferences = appContext.getSharedPreferences(
        "jarvis_android_approved_memories_v1",
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun read(): String? {
        if (!directory.exists() && !directory.mkdirs()) {
            throw LocalConversationException("Не удалось создать папку памяти JARVIS.")
        }
        if (memoryFile.baseFile.exists()) {
            return memoryFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val legacy = legacyPreferences.getString("records", null)
        val initial = legacy ?: LocalWorkspaceMemoryCodec.encode(emptyList())
        if (!write(initial)) {
            throw LocalConversationException("Не удалось создать файл памяти JARVIS.")
        }
        return initial
    }

    @Synchronized
    override fun write(value: String): Boolean {
        return try {
            if (!directory.exists() && !directory.mkdirs()) return false
            val stream = memoryFile.startWrite()
            try {
                stream.write(value.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                memoryFile.finishWrite(stream)
            } catch (error: Exception) {
                memoryFile.failWrite(stream)
                throw error
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun exportSnapshot(): String {
        val content = read() ?: LocalWorkspaceMemoryCodec.encode(emptyList())
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val filename = "jarvis-memory-$stamp.jmem"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JARVIS")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw LocalConversationException("Не удалось создать файл экспорта в Downloads/JARVIS.")
            try {
                appContext.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: throw LocalConversationException("Не удалось записать файл экспорта памяти.")
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                appContext.contentResolver.update(uri, ready, null, null)
                return uri.toString()
            } catch (error: Exception) {
                appContext.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        val exports = File(directory, "exports")
        if (!exports.exists() && !exports.mkdirs()) {
            throw LocalConversationException("Не удалось создать папку экспорта памяти.")
        }
        val target = File(exports, filename)
        val temporary = File(exports, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw LocalConversationException("Не удалось завершить экспорт памяти.")
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        return target.absolutePath
    }

    /**
     * Lists only this app's own exports in Downloads/JARVIS and validates each
     * snapshot before offering it for restore. Invalid files remain visible but
     * carry no restoreable record count.
     */
    fun listExports(): List<LocalMemoryBackupInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val exports = File(directory, "exports")
            return exports.listFiles { file -> file.isFile && file.extension.equals("jmem", true) }
                .orEmpty()
                .sortedByDescending { it.lastModified() }
                .take(50)
                .map { file ->
                    val validation = runCatching {
                        require(file.length() <= 3L * 1024 * 1024) { "Файл превышает 3 МиБ." }
                        LocalWorkspaceMemoryCodec.decode(file.readText(Charsets.UTF_8))
                    }
                    val records = validation.getOrNull()
                    LocalMemoryBackupInfo(
                        uri = Uri.fromFile(file).toString(),
                        displayName = file.name,
                        sizeBytes = file.length(),
                        modifiedAtMillis = file.lastModified(),
                        recordCount = records?.size,
                        activeRecordCount = records?.count { it.supersededById == null },
                        validationError = validation.exceptionOrNull()?.message,
                    )
                }
        }

        val resolver = appContext.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val backups = mutableListOf<LocalMemoryBackupInfo>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf(Environment.DIRECTORY_DOWNLOADS + "/JARVIS/"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (!name.endsWith(".jmem", ignoreCase = true)) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn),
                )
                val validation = runCatching {
                    require(cursor.getLong(sizeColumn) in 1L..(3L * 1024 * 1024)) {
                        "Файл пуст или превышает 3 МиБ."
                    }
                    val raw = resolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 3 * 1024 * 1024) { "Файл превышает 3 МиБ." }
                            output.write(buffer, 0, count)
                        }
                        String(output.toByteArray(), Charsets.UTF_8)
                    } ?: throw LocalConversationException("Не удалось прочитать файл.")
                    LocalWorkspaceMemoryCodec.decode(raw)
                }
                val records = validation.getOrNull()
                backups += LocalMemoryBackupInfo(
                    uri = uri.toString(),
                    displayName = name,
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    modifiedAtMillis = cursor.getLong(dateColumn).coerceAtLeast(0L) * 1_000L,
                    recordCount = records?.size,
                    activeRecordCount = records?.count { it.supersededById == null },
                    validationError = validation.exceptionOrNull()?.message,
                )
            }
        }
        return backups.take(50)
    }

    fun directoryPath(): String = directory.absolutePath
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
        if (records.any { it.supersededById != null && it.supersededById !in ids }) {
            throw LocalConversationException("Связь исправления с исходной записью не найдена.")
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
