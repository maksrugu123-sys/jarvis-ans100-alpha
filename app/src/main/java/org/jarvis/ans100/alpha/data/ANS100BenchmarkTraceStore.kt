package org.jarvis.ans100.alpha.data

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * App-private bounded JSONL sink for benchmark telemetry.
 * Adapter traces contain a hash and input length, never the raw user message.
 */
class ANS100BenchmarkTraceStore(filesDir: File) {
    private val directory = File(filesDir, DIRECTORY_NAME)
    private val traceFile = File(directory, FILE_NAME)

    @Synchronized
    @Throws(IOException::class)
    fun append(jsonLine: String): Boolean {
        if (jsonLine.isBlank() || jsonLine.length > MAX_LINE_CHARS) return false
        val trace = runCatching { JSONObject(jsonLine) }.getOrNull() ?: return false
        if (trace.optString("schema") != ANS100BenchmarkTraceAdapter.SCHEMA) return false
        if (!trace.optString("input_sha256").matches(Regex("^[a-f0-9]{64}$"))) return false
        if (trace.has("input_text") || trace.has("raw_user_message")) return false
        directory.mkdirs()
        val existing = if (traceFile.isFile) {
            traceFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.takeLast(MAX_RECORDS - 1)
        } else emptyList()
        val temporary = File(directory, "$FILE_NAME.tmp")
        if (temporary.exists()) temporary.delete()
        temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
            (existing + jsonLine).forEach { line ->
                if (line.length <= MAX_LINE_CHARS) {
                    writer.write(line)
                    writer.newLine()
                }
            }
        }
        if (traceFile.exists() && !traceFile.delete()) {
            temporary.delete()
            throw IOException("Unable to rotate ANS-100 benchmark trace")
        }
        if (!temporary.renameTo(traceFile)) {
            temporary.delete()
            throw IOException("Unable to publish ANS-100 benchmark trace")
        }
        return true
    }

    @Synchronized
    fun recordCount(): Int = if (!traceFile.isFile) 0 else
        runCatching { traceFile.useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0)

    companion object {
        const val DIRECTORY_NAME = "ans100_benchmark"
        const val FILE_NAME = "android_traces_v1.jsonl"
        const val MAX_RECORDS = 256
        const val MAX_LINE_CHARS = 64 * 1024
    }
}
