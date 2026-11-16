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
        neuralHypotheses: List<ANS100NeuralHypothesisEngine.Hypothesis> = emptyList(),
        memoryHypothesisReports: List<ANS100WorkspaceConceptInputBridge.HypothesisReport> = emptyList(),
        hypothesisComparisons: List<ANS100HypothesisCompetitionEngine.Comparison> = emptyList(),
        hypothesisStatePersisted: Boolean = false,
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
        require(neuralHypotheses.size <= ANS100NeuralHypothesisEngine.MAX_HYPOTHESES_HARD_LIMIT)
        require(memoryHypothesisReports.size <= ANS100ConceptMemory.MAX_HYPOTHESES)
        require(hypothesisComparisons.size <= ANS100NeuralAssociationDecoder.MAX_CANDIDATES)
        val neuralCandidateIds = associationResult.candidates.map { it.candidateId }.toSet()
        require(hypothesisComparisons.all { it.neuralCandidateId in neuralCandidateIds })
        require(hypothesisComparisons.all {
            it.explanations.size <= ANS100HypothesisCompetitionEngine.MAX_EXPLANATIONS_PER_CANDIDATE
        })

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
            .put("association_input_concepts", associationResult.inputConceptCount)
            .put("association_observed_concepts", associationResult.activeConceptCount)
            .put("association_observed_concept_ids_sha256", sha256(associationResult.observedConceptIds.sorted().joinToString("\n")))
            .put("association_spikes", associationResult.associationSpikeCount)
            .put("association_pairs_examined", associationResult.consideredPairCount)
            .put("association_candidate_count_before_limit", associationResult.candidateCountBeforeLimit)
            .put("association_pair_scan_complete", associationResult.pairScanComplete)
            .put("association_candidate_set_complete", associationResult.candidateSetComplete)
            .put("association_candidates", associations)

        val hypotheses = JSONArray()
        neuralHypotheses.forEach { hypothesis ->
            hypotheses.put(JSONObject()
                .put("hypothesis_id", hypothesis.hypothesisId)
                .put("first_concept_id_sha256", sha256(hypothesis.firstConceptId))
                .put("second_concept_id_sha256", sha256(hypothesis.secondConceptId))
                .put("status", hypothesis.status.name)
                .put("observation_count", hypothesis.observationCount)
                .put("verification_turn_count", hypothesis.verificationTurnCount)
                .put("supporting_turn_count", hypothesis.supportingTurnIds.size)
                .put("non_reproducing_turn_count", hypothesis.nonReproducingTurnIds.size)
                .put("shared_target_count", hypothesis.sharedTargetCount))
        }

        val memoryExplanations = JSONArray()
        memoryHypothesisReports.forEach { report ->
            memoryExplanations.put(JSONObject()
                .put("hypothesis_id_sha256", sha256(report.hypothesisId))
                .put("from_label_sha256", sha256(report.fromLabel))
                .put("relation_type", report.relationType.name)
                .put("to_label_sha256", sha256(report.toLabel))
                .put("premise_evidence_count", report.premiseEvidenceRefs.size)
                .put("verification_status", report.verificationStatus.name)
                .put("supporting_evidence_count", report.supportingEvidenceRefs.size)
                .put("contradicting_evidence_count", report.contradictingEvidenceRefs.size))
        }

        val comparisons = JSONArray()
        hypothesisComparisons.forEach { comparison ->
            comparisons.put(JSONObject()
                .put("neural_candidate_id_sha256", sha256(comparison.neuralCandidateId))
                .put("status", comparison.status.name)
                .put("explanations", JSONArray().apply {
                    comparison.explanations.forEach { explanation ->
                        put(JSONObject()
                            .put("hypothesis_id_sha256", sha256(explanation.hypothesisId))
                            .put("relation_type", explanation.relationType.name)
                            .put("direction", explanation.direction.name)
                            .put("verification_status", explanation.verificationStatus.name)
                            .put("premise_evidence_count", explanation.premiseEvidenceCount)
                            .put("supporting_evidence_count", explanation.supportingEvidenceCount)
                            .put("contradicting_evidence_count", explanation.contradictingEvidenceCount))
                    }
                }))
        }

        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("task_id", taskId ?: JSONObject.NULL)
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
            .put("neural_hypotheses", hypotheses)
            .put("neural_hypothesis_state_persisted", hypothesisStatePersisted)
            .put("memory_hypothesis_explanations", memoryExplanations)
            .put("hypothesis_competition", comparisons)
            .put("measurement", JSONObject()
                .put("inference_duration_ns", inferenceDurationNs ?: JSONObject.NULL)
                .put("peak_memory_mb", JSONObject.NULL)
                .put("peak_memory_status", "NOT_MEASURED_BY_THIS_ADAPTER"))
            .put("limitations", JSONArray(listOf(
                "Core supplies heuristic understanding and candidate support/conflict signals.",
                "Neural spikes and readout are observable runtime outputs, not proof of semantic understanding.",
                "Controller status is not a YES/NO/UNKNOWN/CONFLICT benchmark answer.",
                "No expected answer or gold label is accepted or exported.",
                "Hypothesis reproduction means repeated neural co-activation only; it does not establish factual truth.",
                "Mixed reproduction signal means complete turns alternated between convergence and non-convergence; it is not factual contradiction.",
                "Hypothesis state persistence is a local checkpoint indicator, not a correctness or durability guarantee against storage loss.",
                "Memory relation explanations are separate from neural hypotheses; user-entered support or counter-evidence is not independently tool-verified.",
                "Hypothesis competition is a cross-reference between independent signal paths, not a calibrated winner or truth decision."
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
