package org.jarvis.ans100.alpha.data
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class LiteRtLmModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "models")
    private val destination = File(directory, MODEL_FILE_NAME)

    fun installedFile(): File? = destination.takeIf {
        it.isFile && it.canRead() && it.length() in MIN_BYTES..MAX_BYTES
    }

    fun statusMessage(): String {
        val file = installedFile() ?: return "Модель не установлена. Выберите файл .litertlm вручную."
        return "Qwen3-0.6B INT4 установлена · " + (file.length() / MIB) + " МиБ"
    }

    @Synchronized
    fun importModel(uri: Uri): File {
        val meta = queryMetadata(uri)
        if (meta.first != MODEL_FILE_NAME) throw LiteRtLmModelException("Нужен файл " + MODEL_FILE_NAME + ".")
        val declaredSize = meta.second
        if (declaredSize != null && declaredSize !in MIN_BYTES..MAX_BYTES) throw LiteRtLmModelException("Размер модели должен быть примерно 300–400 МиБ.")
        if (!directory.exists() && !directory.mkdirs()) throw LiteRtLmModelException("Не удалось создать приватную папку модели.")
        if (directory.usableSpace < (declaredSize ?: MIN_BYTES) + 32L * MIB) throw LiteRtLmModelException("Недостаточно свободного места для копирования модели.")
        val temp = File(directory, MODEL_FILE_NAME + ".importing")
        if (temp.exists()) temp.delete()
        var copied = 0L
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: throw LiteRtLmModelException("Не удалось открыть выбранный файл.")
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_BYTES) throw LiteRtLmModelException("Файл больше допустимого лимита 400 МиБ.")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (copied !in MIN_BYTES..MAX_BYTES) throw LiteRtLmModelException("Файл не похож на ожидаемую Qwen3-0.6B INT4 модель.")
            if (declaredSize != null && declaredSize >= 0 && declaredSize != copied) throw LiteRtLmModelException("Файл прочитан не полностью.")
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                if (destination.exists() && !destination.delete()) throw LiteRtLmModelException("Не удалось заменить установленную модель.")
                if (!temp.renameTo(destination)) throw LiteRtLmModelException("Не удалось сохранить модель в приватной папке.")
            }
            return installedFile() ?: throw LiteRtLmModelException("Сохранённая модель не прошла проверку размера.")
        } catch (error: Exception) {
            temp.delete()
            if (error is LiteRtLmModelException) throw error
            throw LiteRtLmModelException("Импорт модели не выполнен: " + (error.message ?: "ошибка чтения"))
        }
    }

    @Synchronized fun deleteModel(): Boolean = !destination.exists() || destination.delete()

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> {
        val cursor = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?: throw LiteRtLmModelException("Не удалось прочитать свойства файла.")
        cursor.use {
            if (!it.moveToFirst()) throw LiteRtLmModelException("Файл не найден.")
            val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = it.getColumnIndex(OpenableColumns.SIZE)
            val name = if (ni >= 0) it.getString(ni) else null
            val size = if (si >= 0 && !it.isNull(si)) it.getLong(si) else null
            return name to size
        }
    }

    companion object {
        const val MODEL_FILE_NAME = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        const val MIB = 1024L * 1024L
        const val MIN_BYTES = 300L * MIB
        const val MAX_BYTES = 400L * MIB
    }
}

class LiteRtLmModelException(message: String) : IllegalStateException(message)
