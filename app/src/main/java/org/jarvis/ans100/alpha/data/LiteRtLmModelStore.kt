package org.jarvis.ans100.alpha.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class LiteRtLmModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "models")
    private val destination = File(directory, MODEL_FILE_NAME)
    private val partialDownload = File(directory, MODEL_FILE_NAME + ".download")

    @Volatile
    private var cancelRequested = false

    fun installedFile(): File? = destination.takeIf {
        it.isFile && it.canRead() && it.length() == EXPECTED_BYTES
    }

    fun statusMessage(): String {
        val file = installedFile() ?: return "Модель не установлена. Её можно скачать прямо в приложении или импортировать вручную."
        return "Qwen3-0.6B INT4 установлена · " + (file.length() / MIB) + " МиБ"
    }

    /**
     * Downloads the pinned Apache-2.0 no-think Qwen3 artifact directly into app-private storage.
     * A partial file is retained on network interruption/cancellation and resumed with HTTP Range.
     */
    @Synchronized
    @Throws(LiteRtLmModelException::class)
    fun downloadModel(onProgress: (Long, Long) -> Unit): File {
        cancelRequested = false
        if (!directory.exists() && !directory.mkdirs()) {
            throw LiteRtLmModelException("Не удалось создать приватную папку модели.")
        }
        if (installedFile() != null) return destination
        if (directory.usableSpace < EXPECTED_BYTES + 32L * MIB) {
            throw LiteRtLmModelException("Для загрузки нужно не менее 363 МиБ свободного места во внутренней памяти приложения.")
        }

        var offset = if (partialDownload.isFile) partialDownload.length() else 0L
        if (offset > EXPECTED_BYTES) {
            partialDownload.delete()
            offset = 0L
        }
        val digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0L) {
            FileInputStream(partialDownload).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }

        if (offset == EXPECTED_BYTES) {
            return verifyAndPublish(digest)
        }

        val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "JARVIS-ANS100-Android")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_REQUESTED_RANGE_NOT_SATISFIABLE && offset == EXPECTED_BYTES) {
                return verifyAndPublish(digest)
            }

            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val range = connection.getHeaderField("Content-Range")
                    ?: throw LiteRtLmModelException("Сервер не подтвердил продолжение загрузки.")
                val match = CONTENT_RANGE_REGEX.matchEntire(range)
                    ?: throw LiteRtLmModelException("Сервер вернул некорректный диапазон загрузки.")
                if (match.groupValues[1].toLong() != offset ||
                    match.groupValues[3].toLong() != EXPECTED_BYTES
                ) {
                    throw LiteRtLmModelException("Диапазон загрузки не совпадает с ожидаемой моделью.")
                }
            } else if (code == HttpURLConnection.HTTP_OK) {
                // The server ignored Range. Restart cleanly rather than appending duplicate bytes.
                offset = 0L
                digest.reset()
            } else {
                throw LiteRtLmModelException("Не удалось скачать модель: HTTP $code.")
            }

            val append = code == HttpURLConnection.HTTP_PARTIAL && offset > 0L
            var downloaded = offset
            var lastReportedPercent = -1L
            connection.inputStream.use { input ->
                FileOutputStream(partialDownload, append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (cancelRequested || Thread.currentThread().isInterrupted) {
                            throw LiteRtLmDownloadCancelledException()
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        if (downloaded > EXPECTED_BYTES) {
                            partialDownload.delete()
                            throw LiteRtLmModelException("Сервер передал файл больше ожидаемого размера.")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val percent = downloaded * 100L / EXPECTED_BYTES
                        if (percent != lastReportedPercent || downloaded == EXPECTED_BYTES) {
                            lastReportedPercent = percent
                            onProgress(downloaded, EXPECTED_BYTES)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (downloaded != EXPECTED_BYTES) {
                throw LiteRtLmModelException("Загрузка прервалась: получено ${downloaded / MIB} из ${EXPECTED_BYTES / MIB} МиБ. Нажмите «Продолжить загрузку».")
            }
            return verifyAndPublish(digest)
        } catch (error: LiteRtLmModelException) {
            if (error !is LiteRtLmDownloadCancelledException &&
                error.message?.contains("SHA-256") == true
            ) partialDownload.delete()
            throw error
        } catch (error: Exception) {
            throw LiteRtLmModelException(
                "Ошибка загрузки: ${error.message ?: "проверьте интернет-соединение"}. Частичная загрузка сохранена.",
            )
        } finally {
            connection.disconnect()
        }
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    private fun verifyAndPublish(digest: MessageDigest): File {
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualHash != EXPECTED_SHA256) {
            partialDownload.delete()
            throw LiteRtLmModelException("Проверка SHA-256 не пройдена. Повреждённая загрузка удалена.")
        }
        if (partialDownload.length() != EXPECTED_BYTES) {
            partialDownload.delete()
            throw LiteRtLmModelException("Размер загруженной модели не совпадает с контрольным.")
        }
        publishPartialFile()
        return installedFile() ?: throw LiteRtLmModelException("Сохранённая модель не прошла проверку.")
    }

    private fun publishPartialFile() {
        try {
            Files.move(
                partialDownload.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            if (destination.exists() && !destination.delete()) {
                throw LiteRtLmModelException("Не удалось заменить установленную модель.")
            }
            if (!partialDownload.renameTo(destination)) {
                throw LiteRtLmModelException("Не удалось сохранить модель в приватной папке.")
            }
        }
    }

    @Synchronized
    fun importModel(uri: Uri): File {
        val meta = queryMetadata(uri)
        if (meta.first != MODEL_FILE_NAME) throw LiteRtLmModelException("Нужен файл " + MODEL_FILE_NAME + ".")
        val declaredSize = meta.second
        if (declaredSize != null && declaredSize != EXPECTED_BYTES) throw LiteRtLmModelException("Размер модели должен быть 347 251 840 байт.")
        if (!directory.exists() && !directory.mkdirs()) throw LiteRtLmModelException("Не удалось создать приватную папку модели.")
        if (directory.usableSpace < (declaredSize ?: EXPECTED_BYTES) + 32L * MIB) throw LiteRtLmModelException("Недостаточно свободного места для копирования модели.")
        val temp = File(directory, MODEL_FILE_NAME + ".importing")
        if (temp.exists()) temp.delete()
        var copied = 0L
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: throw LiteRtLmModelException("Не удалось открыть выбранный файл.")
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > EXPECTED_BYTES) throw LiteRtLmModelException("Файл больше ожидаемого размера модели.")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (copied != EXPECTED_BYTES) throw LiteRtLmModelException("Файл не похож на ожидаемую Qwen3-0.6B INT4 модель.")
            if (declaredSize != null && declaredSize >= 0 && declaredSize != copied) throw LiteRtLmModelException("Файл прочитан не полностью.")
            val digest = sha256(temp)
            if (digest != EXPECTED_SHA256) throw LiteRtLmModelException("Проверка SHA-256 не пройдена. Выбран другой или повреждённый файл.")
            if (!temp.renameTo(destination)) {
                try {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (error: IOException) {
                    throw LiteRtLmModelException("Не удалось сохранить модель: ${error.message}")
                }
            }
            return installedFile() ?: throw LiteRtLmModelException("Сохранённая модель не прошла проверку.")
        } catch (error: Exception) {
            temp.delete()
            if (error is LiteRtLmModelException) throw error
            throw LiteRtLmModelException("Импорт модели не выполнен: " + (error.message ?: "ошибка чтения"))
        }
    }

    @Synchronized
    fun deleteModel(): Boolean {
        partialDownload.delete()
        return !destination.exists() || destination.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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
        const val EXPECTED_BYTES = 347_251_840L
        const val EXPECTED_SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
        const val DOWNLOAD_URL = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/6aa2daf8aba4aa456797fb8040b36a3948bcfda7/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        private const val BUFFER_BYTES = 128 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}

class LiteRtLmModelException(message: String) : IllegalStateException(message)
class LiteRtLmDownloadCancelledException : LiteRtLmModelException("Загрузка отменена. Можно продолжить позже.")
