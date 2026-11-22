package org.jarvis.ans100.alpha.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Storage boundary so the neural decoder never depends directly on Android APIs. */
interface ANS100SequenceModelStorage {
    fun readSnapshot(): String?
    fun writeSnapshot(snapshot: String): Boolean
    fun clearSnapshot(): Boolean
}

/**
 * App-private atomic snapshot storage. It is deliberately separate from
 * SharedPreferences because model matrices can be substantially larger than
 * ordinary preference values.
 */
class FileANS100SequenceModelStorage(context: Context) : ANS100SequenceModelStorage {
    private val atomicFile = AtomicFile(
        File(context.applicationContext.filesDir, MODEL_FILE_NAME),
    )

    override fun readSnapshot(): String? {
        if (!atomicFile.baseFile.exists()) return null
        return try {
            atomicFile.openRead().use { input ->
                val bytes = input.readBytes()
                if (bytes.size > ANS100TrainableSequenceDecoder.MAX_SNAPSHOT_CHARS) {
                    throw IOException("ANS sequence model snapshot exceeds storage limit")
                }
                bytes.toString(Charsets.US_ASCII)
            }
        } catch (error: IOException) {
            throw IllegalStateException("Unable to read ANS sequence model snapshot", error)
        }
    }

    override fun writeSnapshot(snapshot: String): Boolean {
        require(snapshot.isNotBlank())
        require(snapshot.length <= ANS100TrainableSequenceDecoder.MAX_SNAPSHOT_CHARS)
        require(snapshot.all { it.code in 0x21..0x7e }) {
            "ANS model snapshot must be URL-safe Base64 text"
        }
        var output: java.io.FileOutputStream? = null
        return try {
            val stream = atomicFile.startWrite()
            output = stream
            stream.write(snapshot.toByteArray(Charsets.US_ASCII))
            atomicFile.finishWrite(stream)
            output = null
            true
        } catch (error: IOException) {
            output?.let(atomicFile::failWrite)
            false
        }
    }

    override fun clearSnapshot(): Boolean = try {
        atomicFile.delete()
        true
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val MODEL_FILE_NAME = "ans100_sequence_decoder_v1.bin"
    }
}
