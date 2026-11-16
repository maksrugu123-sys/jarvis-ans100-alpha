package org.jarvis.ans100.alpha.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Privacy-bounded export of what the Android ANS-100 runtime actually emitted.
 *
 * This is telemetry, not a canonical task answer. The current integrated
 * pipeline receives heuristic Core features and cannot independently emit
 * YES/NO/UNKNOWN/CONFLICT for the comparison corpus. Therefore answer is
 * deliberately null and must not be inferred from the controller status.
 */
object ANS100BenchmarkTraceAdapter {
    const val SCHEMA = "ANS100_BENCHMARK_TRACE_V1"
    const val MAX_TASK_ID_CHARS = 128
    const val MAX_INPUT_CHARS = TypedEvidenceEncoder.MAX_RAW_MESSAGE_CHARS
    const val MAX_ID_SAMPLE = 128

    fun create(
        taskId: String?,
        inputText: String,
        evidence: TypedEvidenceBundle,
        decision: ANS100Decision,
        telemetry: AndroidHybridRunTelemetry,
        emittedSpikeNeuronIds: Set<Int>,
        activatedConnectionIds: Set<String>,
        associationResult: ANS100NeuralAssociationDecoder.Result,
        inferenceDurationNs: Long? = null,
    ): JSONObject {
        require(inputText.isNotBlank() && inputText.length <= MAX_INPUT_CHARS)
        require(taskId == null || (
            taskId.isNotBlank() && taskId.length <= MAX_TASK_ID_CHARS &&
                taskId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]*$"))
        ))
        require(inferenceDurationNs == null || inferenceDurationNs >= 0L)
        require(evidence.turnId == decision.turnId)
        decision.validateAgainst(evidence)
        require(telemetry.topologyId == AndroidHybridTopologyV2.TOPOLOGY_ID)
        require(telemetry.topologyVersion == AndroidHybridTopologyV2.TOPOLOGY_VERSION)
        require(emittedSpikeNeuronIds.size <= AndroidHybridTopologyV2.NEURON_COUNT)
        require(emittedSpikeNeuronIds.all { it in 0 until AndroidHybridTopologyV2.NEURON_COUNT })
        require(activatedConnectionIds.size <= AndroidHybridTopologyV2.EXPECTED_CONNECTION_COUNT)
        require(associationResult.candidates.size <= ANS100NeuralAssociationDecoder.MAX_CANDIDATES)

        val heuristicRecords = evidence.records.filter { it.status == EvidenceStatus.HEURISTIC }
        val statusCounts = EvidenceStatus.values().associate { status ->
            status.name.lowercase() to evidence.records.count { it.status == status }
        }
        val sortedSpikes = emittedSpikeNeuronIds.sorted()
        val sortedConnections = activatedConnectionIds.sorted()
        val populations = JSONArray()
        telemetry.populations.sortedBy { it.populationId }.forEach { activity ->
            populations.put(JSONObject()
                .put("population_id", activity.populationId)
                .put("received_events", activity.receivedEventCount)
                .put("unique_neurons_processed", activity.uniqueNeuronsProcessed)
                .put("unique_spiking_neurons", activity.uniqueSpikingNeurons)
                .put("spike_count", activity.spikeCount)
                .put("outgoing_events", activity.outgoingEventCount))
        }
        val associations = JSONArray()
        associationResult.candidates.forEach { candidate ->
            associations.put(JSONObject()
                .put("candidate_id", candidate.candidateId)
                .put("first_concept_id_sha256", sha256(candidate.firstConceptId))
                .put("second_concept_id_sha256", sha256(candidate.secondConceptId))
                .put("shared_spiking_targets", candidate.convergentNeuronIds.size)
                .put("activated_synapses", candidate.activatedConnectionIds.size)
                .put("activation_hint", candidate.activationHint))
        }
        val neuralOutputs = JSONObject()
            .put("unique_spiking_neurons", sortedSpikes.size)
            .put("spiking_neuron_ids_sha256", sha256(sortedSpikes.joinToString(",")))
            .put("spiking_neuron_id_sample", JSONArray(sortedSpikes.take(MAX_ID_SAMPLE)))
            .put("activated_synapses", sortedConnections.size)
            .put("activated_connection_ids_sha256", sha256(sortedConnections.joinToString("\n")))
            .put("activated_connection_id_sample", JSONArray(sortedConnections.take(MAX_ID_SAMPLE)))
            .put("association_active_concepts", associationResult.activeConceptCount)
            .put("association_spikes", associationResult.associationSpikeCount)
            .put("association_pairs_examined", associationResult.consideredPairCount)
            .put("association_candidates", associations)

        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("task_id", taskId?.let { JSONObject.NULL } ?: JSONObject.NULL)
            .put("record_kind", if (taskId == null) "LIVE_APP_TURN" else "BENCHMARK_TASK")
            .put("turn_id", evidence.turnId)
            .put("input_sha256", sha256(inputText))
            .put("input_char_count", inputText.length)
            .put("answer", JSONObject.NULL)
            .put("answer_emission", "NOT_SUPPORTED_BY_CURRENT_ANS100_PIPELINE")
            .put("decision", JSONObject()
                .put("status", decision.status.name)
                .put("producer", decision.decisionProducer.name)
                .put("topology_version", decision.topologyVersion.name)
                .put("calibration_status", decision.calibrationStatus)
                .put("selected_candidate_id", decision.selectedCandidateId ?: JSONObject.NULL)
                .put("uncertainty", JSONArray(decision.uncertainty.map { it.name }.sorted()))
                .put("compute_budget_exhausted", decision.computeBudgetExhausted)
                .put("processed_events", decision.processedEventCount))
            .put("core_input", JSONObject()
                .put("heuristic_record_count", heuristicRecords.size)
                .put("heuristic_feature_ids", JSONArray(heuristicRecords.map { it.feature.stableId }.distinct().sorted()))
                .put("evidence_status_counts", JSONObject(statusCounts)))
            .put("neural_outputs", neuralOutputs)
            .put("population_activity", populations)
            .put("measurement", JSONObject()
                .put("inference_duration_ns", inferenceDurationNs ?: JSONObject.NULL)
                .put("peak_memory_mb", JSONObject.NULL)
                .put("peak_memory_status", "NOT_MEASURED_BY_THIS_ADAPTER"))
            .put("limitations", JSONArray(listOf(
                "Core supplies heuristic understanding and candidate support/conflict signals.",
                "Neural spikes and readout are observable runtime outputs, not proof of semantic understanding.",
                "Controller status is not a YES/NO/UNKNOWN/CONFLICT benchmark answer.",
                "No expected answer or gold label is accepted or exported."
            )))
        return root
    }

    fun toJsonLine(trace: JSONObject): String {
        require(trace.optString("schema") == SCHEMA)
        return trace.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
