package org.jarvis.ans100.alpha

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Strict JSONL boundary: held-out labels are rejected, never silently consumed. */
object ANS100BenchmarkJsonl {
    private const val INPUT_SCHEMA = "ANS100_BENCHMARK_INPUT_V1"
    private const val TRAIN_SCHEMA = "ANS100_BENCHMARK_TRAIN_V1"
    private const val MAX_ROWS = 1_000
    private val labels = setOf("YES", "NO", "UNKNOWN", "CONFLICT")
    private val heldOutPrefixes = listOf("pair_disjoint:", "concept_disjoint:", "compositional:")

    data class PredictionFiles(val transformerJsonl: ByteArray, val ans100Jsonl: ByteArray)

    fun readDevelopmentRows(resolver: ContentResolver, uri: Uri): List<ANS100BenchmarkDevelopmentExample> =
        readRows(resolver, uri, TRAIN_SCHEMA).map { row ->
            val id = required(row, "task_id")
            val input = required(row, "input_text")
            val label = required(row, "training_label").uppercase()
            require(id.startsWith("development:")) { "$id: only development rows may train." }
            require(input.length <= 2_048) { "$id: input exceeds 2048 characters." }
            require(label in labels) { "$id: invalid canonical training label." }
            require(required(row, "input_sha256") == sha256(input)) { "$id: input SHA-256 mismatch." }
            ANS100BenchmarkDevelopmentExample(id, input, label)
        }.also { require(it.isNotEmpty()) { "Development JSONL is empty." } }

    fun readHeldOutRows(resolver: ContentResolver, uri: Uri): List<ANS100BenchmarkBatchInput> =
        readRows(resolver, uri, INPUT_SCHEMA).map { row ->
            val id = required(row, "task_id")
            val input = required(row, "input_text")
            require(heldOutPrefixes.any { id.startsWith(it) }) { "$id: not a held-out task." }
            require(input.length <= 2_048) { "$id: input exceeds 2048 characters." }
            require(!row.has("expected_answer") && !row.has("training_label") && !row.has("split")) {
                "$id: held-out input contains a forbidden label or split field."
            }
            require(required(row, "input_sha256") == sha256(input)) { "$id: input SHA-256 mismatch." }
            ANS100BenchmarkBatchInput(id, input)
        }.also { require(it.isNotEmpty()) { "Held-out JSONL is empty." } }

    fun encodePredictions(results: List<JarvisViewModel.PairedBenchmarkTaskResult>): PredictionFiles {
        require(results.isNotEmpty() && results.size <= MAX_ROWS)
        require(results.map { it.taskId }.distinct().size == results.size) { "Duplicate task IDs in results." }
        val transformerRows = ArrayList<JSONObject>(results.size)
        val ansRows = ArrayList<JSONObject>(results.size)
        results.forEach { paired ->
            val tr = paired.transformer.getOrNull()
            val transformer = JSONObject().put("task_id", paired.taskId)
                .put("answer", tr?.answer ?: JSONObject.NULL)
            if (tr != null) {
                transformer.put("latency_ms", tr.generationMs.toDouble())
                    .put("initialization_ms", tr.initializationMs.toDouble())
                    .put("output_token_estimate", tr.outputTokenEstimate)
            } else transformer.put("inference_error", paired.transformer.exceptionOrNull()?.message ?: "inference_failed")
            transformerRows.add(transformer)

            val ar = paired.ans100.getOrNull()
            val ans = JSONObject().put("task_id", paired.taskId).put("answer", ar?.answer ?: JSONObject.NULL)
            if (ar != null) {
                ans.put("latency_ms", ar.inferenceDurationNs.toDouble() / 1_000_000.0)
                    .put("readout_status", ar.readoutStatus).put("completed", ar.completed)
                    .put("processed_events", ar.processedEvents)
                    .put("encoded_token_count", ar.encodedTokenCount)
                    .put("activated_token_count", ar.activatedTokenCount)
                    .put("slot_collision_count", ar.slotCollisionCount)
                    .put("clean_source_spike_count", ar.cleanSourceSpikeCount)
                    .put("class_spike_counts", JSONObject(ar.classSpikeCounts))
                    .put("network_initialization_ms", ar.networkInitializationDurationNs.toDouble() / 1_000_000.0)
                    .put("learned_synapse_count", ar.learnedSynapseCount)
            } else ans.put("inference_error", paired.ans100.exceptionOrNull()?.message ?: "inference_failed")
            ansRows.add(ans)
        }
        return PredictionFiles(jsonl(transformerRows), jsonl(ansRows))
    }

    private fun readRows(resolver: ContentResolver, uri: Uri, schema: String): List<JSONObject> {
        val input = resolver.openInputStream(uri) ?: error("Unable to open selected JSONL file.")
        val rows = ArrayList<JSONObject>()
        input.use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (line.isBlank()) return@forEachIndexed
                    require(line.length <= 16_384) { "JSONL line ${index + 1} is too long." }
                    try { rows.add(JSONObject(line)) }
                    catch (error: JSONException) { throw IllegalArgumentException("Invalid JSON on line ${index + 1}.", error) }
                    require(rows.size <= MAX_ROWS) { "JSONL contains more than $MAX_ROWS rows." }
                }
            }
        }
        require(rows.map { required(it, "task_id") }.distinct().size == rows.size) { "Duplicate task IDs in JSONL." }
        rows.forEach { require(required(it, "schema") == schema) { "Unexpected JSONL schema." } }
        return rows
    }

    private fun required(row: JSONObject, key: String): String {
        val value = row.opt(key)
        require(value is String && value.isNotBlank()) { "Missing or empty JSON field: $key." }
        return value
    }

    private fun jsonl(rows: List<JSONObject>): ByteArray =
        (rows.joinToString("\n") { it.toString() } + "\n").toByteArray(StandardCharsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
