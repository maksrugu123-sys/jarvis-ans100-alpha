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
    data class EncodingComparisonFiles(val lexicalAns100Jsonl: ByteArray, val structuredAns100Jsonl: ByteArray)

    fun readDevelopmentRows(resolver: ContentResolver, uri: Uri): List<ANS100BenchmarkDevelopmentExample> =
        readRows(resolver, uri, TRAIN_SCHEMA).map { row ->
            val id = required(row, "task_id")
            requireExactKeys(
                row,
                setOf("schema", "task_id", "input_text", "training_label", "input_sha256"),
                id,
            )
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
            requireExactKeys(
                row,
                setOf("schema", "task_id", "input_text", "input_sha256"),
                id,
            )
            val input = required(row, "input_text")
            require(heldOutPrefixes.any { id.startsWith(it) }) { "$id: not a held-out task." }
            require(input.length <= 2_048) { "$id: input exceeds 2048 characters." }
            require(!row.has("expected_answer") && !row.has("training_label") && !row.has("split")) {
                "$id: held-out input contains a forbidden label or split field."
            }
            require(required(row, "input_sha256") == sha256(input)) { "$id: input SHA-256 mismatch." }
            ANS100BenchmarkBatchInput(id, input)
        }.also { require(it.isNotEmpty()) { "Held-out JSONL is empty." } }

    fun encodePredictions(
        results: List<JarvisViewModel.PairedBenchmarkTaskResult>,
        inputHashes: Map<String, String> = emptyMap(),
    ): PredictionFiles {
        require(results.isNotEmpty() && results.size <= MAX_ROWS)
        require(results.map { it.taskId }.distinct().size == results.size) { "Duplicate task IDs in results." }
        val transformerRows = ArrayList<JSONObject>(results.size)
        val ansRows = ArrayList<JSONObject>(results.size)
        results.forEach { paired ->
            val tr = paired.transformer.getOrNull()
            val transformer = JSONObject().put("task_id", paired.taskId)
                .put("answer", tr?.answer ?: JSONObject.NULL)
            inputHashes[paired.taskId]?.let { transformer.put("input_sha256", it) }
            if (tr != null) {
                transformer.put("latency_ms", tr.generationMs.toDouble())
                    .put("initialization_ms", tr.initializationMs.toDouble())
                    .put("output_token_estimate", tr.outputTokenEstimate)
                    .put("memory_baseline_pss_mb", tr.memoryBaselinePssMb)
                    .put("peak_memory_mb", tr.peakMemoryPssMb)
                    .put("peak_memory_delta_mb", tr.peakMemoryDeltaMb)
                    .put("memory_sample_interval_ms", tr.memorySampleIntervalMs)
                    .put("memory_measurement_scope", "ANDROID_PROCESS_TOTAL_PSS_NOT_PER_MODEL")
                    .put("cold_start", tr.coldStart)
            } else transformer.put("inference_error", paired.transformer.exceptionOrNull()?.message ?: "inference_failed")
            transformerRows.add(transformer)

            val ar = paired.ans100.getOrNull()
            val ans = JSONObject().put("task_id", paired.taskId).put("answer", ar?.answer ?: JSONObject.NULL)
            inputHashes[paired.taskId]?.let { ans.put("input_sha256", it) }
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
                    .put("memory_baseline_pss_mb", ar.memoryBaselinePssMb)
                    .put("peak_memory_mb", ar.peakMemoryPssMb)
                    .put("peak_memory_delta_mb", ar.peakMemoryDeltaMb)
                    .put("memory_sample_interval_ms", ar.memorySampleIntervalMs)
                    .put("memory_measurement_scope", "ANDROID_PROCESS_TOTAL_PSS_NOT_PER_MODEL")
                    .put("cold_start", ar.coldStart)
                    .put("input_encoding", ar.inputEncoding)
                    .put("parsed_fact_count", ar.parsedFactCount)
                    .put("neural_association_candidate_count", ar.neuralAssociationCandidateCount)
                    .put("neural_hypothesis_count", ar.neuralHypothesisCount)
                    .put("reproduced_neural_hypothesis_count", ar.reproducedNeuralHypothesisCount)
                    .put("neural_hypothesis_status_counts", JSONObject(ar.neuralHypothesisStatusCounts))
                    .put("evidence_audit_answer", ar.evidenceAuditAnswer)
                    .put("evidence_audit_method", ar.evidenceAuditMethod)
                    .put("neural_evidence_agreement", ar.neuralEvidenceAgreement)
                    .put("neural_candidate_evidence_audit_counts", JSONObject(ar.neuralCandidateEvidenceAuditCounts))
            } else ans.put("inference_error", paired.ans100.exceptionOrNull()?.message ?: "inference_failed")
            ansRows.add(ans)
        }
        return PredictionFiles(jsonl(transformerRows), jsonl(ansRows))
    }


    fun encodeStructuredPredictions(
        results: List<JarvisViewModel.StructuredPairedBenchmarkTaskResult>,
        heldOutInputs: List<ANS100BenchmarkBatchInput>,
    ): PredictionFiles {
        require(results.isNotEmpty() && results.size <= MAX_ROWS)
        require(heldOutInputs.map { it.taskId }.distinct().size == heldOutInputs.size)
        val resultIds = results.map { it.taskId }.toSet()
        val inputIds = heldOutInputs.map { it.taskId }.toSet()
        require(resultIds.size == results.size && resultIds == inputIds) {
            "Prediction task IDs must exactly match the selected held-out input IDs."
        }
        val hashes = heldOutInputs.associate { it.taskId to sha256(it.inputText) }
        return encodePredictions(
            results.map {
                JarvisViewModel.PairedBenchmarkTaskResult(
                    taskId = it.taskId,
                    transformer = it.transformer,
                    ans100 = it.ans100Structured,
                )
            },
            inputHashes = hashes,
        )
    }

    /** Exports paired lexical-versus-structured ANS results without mixing their predictions. */
    fun encodeEncodingComparisons(
        results: List<JarvisViewModel.Ans100EncodingComparisonResult>,
    ): EncodingComparisonFiles {
        require(results.isNotEmpty() && results.size <= MAX_ROWS)
        require(results.map { it.taskId }.distinct().size == results.size)
        fun encodeRows(structured: Boolean): ByteArray {
            val rows = results.map { item ->
                val result = if (structured) item.structured else item.lexical
                val row = JSONObject().put("task_id", item.taskId)
                    .put("answer", result.getOrNull()?.answer ?: JSONObject.NULL)
                val value = result.getOrNull()
                if (value != null) {
                    row.put("readout_status", value.readoutStatus)
                        .put("completed", value.completed)
                        .put("latency_ms", value.inferenceDurationNs.toDouble() / 1_000_000.0)
                        .put("processed_events", value.processedEvents)
                        .put("encoded_feature_count", value.encodedTokenCount)
                        .put("activated_feature_count", value.activatedTokenCount)
                        .put("slot_collision_count", value.slotCollisionCount)
                        .put("clean_source_spike_count", value.cleanSourceSpikeCount)
                        .put("class_spike_counts", JSONObject(value.classSpikeCounts))
                        .put("network_initialization_ms", value.networkInitializationDurationNs.toDouble() / 1_000_000.0)
                        .put("learned_synapse_count", value.learnedSynapseCount)
                        .put("memory_baseline_pss_mb", value.memoryBaselinePssMb)
                        .put("peak_memory_mb", value.peakMemoryPssMb)
                        .put("peak_memory_delta_mb", value.peakMemoryDeltaMb)
                        .put("memory_sample_interval_ms", value.memorySampleIntervalMs)
                        .put("memory_measurement_scope", "ANDROID_PROCESS_TOTAL_PSS_NOT_PER_MODEL")
                        .put("cold_start", value.coldStart)
                        .put("input_encoding", value.inputEncoding)
                        .put("parsed_fact_count", value.parsedFactCount)
                        .put("neural_association_candidate_count", value.neuralAssociationCandidateCount)
                        .put("neural_hypothesis_count", value.neuralHypothesisCount)
                        .put("reproduced_neural_hypothesis_count", value.reproducedNeuralHypothesisCount)
                        .put("neural_hypothesis_status_counts", JSONObject(value.neuralHypothesisStatusCounts))
                        .put("evidence_audit_answer", value.evidenceAuditAnswer)
                        .put("evidence_audit_method", value.evidenceAuditMethod)
                        .put("neural_evidence_agreement", value.neuralEvidenceAgreement)
                        .put("neural_candidate_evidence_audit_counts", JSONObject(value.neuralCandidateEvidenceAuditCounts))
                } else {
                    row.put("inference_error", result.exceptionOrNull()?.message ?: "inference_failed")
                }
                row
            }
            return jsonl(rows)
        }
        return EncodingComparisonFiles(
            lexicalAns100Jsonl = encodeRows(structured = false),
            structuredAns100Jsonl = encodeRows(structured = true),
        )
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

    private fun requireExactKeys(row: JSONObject, expected: Set<String>, taskId: String) {
        val actual = row.keys().asSequence().toSet()
        require(actual == expected) {
            val unexpected = (actual - expected).sorted().joinToString()
            val missing = (expected - actual).sorted().joinToString()
            buildString {
                append("$taskId: JSON fields do not match the required schema.")
                if (unexpected.isNotEmpty()) append(" Unexpected: $unexpected.")
                if (missing.isNotEmpty()) append(" Missing: $missing.")
            }
        }
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
