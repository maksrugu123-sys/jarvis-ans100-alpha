package org.jarvis.ans100.alpha.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100BenchmarkTraceAdapterTest {
    private fun fixture(): Triple<TypedEvidenceBundle, ANS100Decision, AndroidHybridRunTelemetry> {
        val turnId = "benchmark-turn-1"
        val evidence = TypedEvidenceBundle(
            turnId = turnId,
            records = listOf(
                TypedEvidenceRecord(
                    recordId = "user-message",
                    turnId = turnId,
                    feature = EvidenceFeature.USER_MESSAGE_TEXT,
                    source = EvidenceSourceKind.USER_MESSAGE,
                    status = EvidenceStatus.USER_PROVIDED,
                    value = EvidenceValue.Text("private synthetic input"),
                    activation = 1.0,
                    provenanceRef = "turn:$turnId:user_message",
                ),
                TypedEvidenceRecord(
                    recordId = "core-intent",
                    turnId = turnId,
                    feature = EvidenceFeature.CORE_INTENT_HINT,
                    source = EvidenceSourceKind.CORE_UNDERSTANDING,
                    status = EvidenceStatus.HEURISTIC,
                    value = EvidenceValue.Category("question"),
                    activation = 0.7,
                    provenanceRef = "turn:$turnId:core.intent",
                ),
            ),
        )
        val decision = ANS100Decision(
            turnId = turnId,
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            status = ANS100DecisionStatus.ABSTAINED,
            decisionProducer = ANS100DecisionProducer.HYBRID_POPULATION_CONTROLLER_V1,
            candidates = emptyList(),
            abstentionReason = "No supported canonical answer.",
            uncertainty = setOf(ANS100UncertaintyKind.UNCALIBRATED_MODEL_OUTPUT),
            responseGoal = "Do not infer a benchmark label.",
        )
        val telemetry = AndroidHybridRunTelemetry(
            topologyId = AndroidHybridTopologyV2.TOPOLOGY_ID,
            topologyVersion = AndroidHybridTopologyV2.TOPOLOGY_VERSION,
            populations = AndroidHybridTopologyV2.populations.map { population ->
                AndroidNeuronPopulationActivity(
                    populationId = population.id,
                    receivedEventCount = 0,
                    uniqueNeuronsProcessed = 0,
                    uniqueSpikingNeurons = 0,
                    spikeCount = 0,
                    outgoingEventCount = 0,
                )
            },
        )
        return Triple(evidence, decision, telemetry)
    }

    @Test
    fun exportsObservedNeuralTelemetryWithoutInventingCanonicalAnswer() {
        val (evidence, decision, telemetry) = fixture()
        val association = ANS100NeuralAssociationDecoder.Result(
            activeConceptCount = 0,
            associationSpikeCount = 0,
            consideredPairCount = 0,
            candidates = emptyList(),
        )
        val trace = ANS100BenchmarkTraceAdapter.create(
            taskId = "pair_disjoint:relation_status:s104729:000",
            inputText = "A is connected to B. Is A connected to B?",
            evidence = evidence,
            decision = decision,
            telemetry = telemetry,
            emittedSpikeNeuronIds = setOf(1, 2, 3),
            activatedConnectionIds = setOf("connection-a", "connection-b"),
            associationResult = association,
            inferenceDurationNs = 12_345L,
        )

        assertEquals(ANS100BenchmarkTraceAdapter.SCHEMA, trace.getString("schema"))
        assertEquals("BENCHMARK_TASK", trace.getString("record_kind"))
        assertTrue(trace.isNull("answer"))
        assertEquals("NOT_SUPPORTED_BY_CURRENT_ANS100_PIPELINE", trace.getString("answer_emission"))
        assertEquals(1, trace.getJSONObject("core_input").getInt("heuristic_record_count"))
        assertEquals(3, trace.getJSONObject("neural_outputs").getInt("unique_spiking_neurons"))
        assertEquals(2, trace.getJSONObject("neural_outputs").getInt("activated_synapses"))
        assertEquals(0, trace.getJSONObject("neural_outputs").getInt("association_input_concepts"))
        assertTrue(trace.getJSONObject("neural_outputs").getBoolean("association_candidate_set_complete"))
        assertEquals(12_345L, trace.getJSONObject("measurement").getLong("inference_duration_ns"))
        assertFalse(trace.getBoolean("neural_hypothesis_state_persisted"))
        assertTrue(trace.getJSONObject("measurement").isNull("peak_memory_mb"))
        assertFalse(trace.toString().contains("private synthetic input"))
        assertFalse(trace.has("expected_answer"))
        assertFalse(trace.has("input_text"))
        assertTrue(ANS100BenchmarkTraceAdapter.toJsonLine(trace).endsWith("}"))
    }


    @Test
    fun recordsOnlyTheCheckpointStatusWhenExplicitlyProvided() {
        val (evidence, decision, telemetry) = fixture()
        val trace = ANS100BenchmarkTraceAdapter.create(
            taskId = null,
            inputText = "test input",
            evidence = evidence,
            decision = decision,
            telemetry = telemetry,
            emittedSpikeNeuronIds = emptySet(),
            activatedConnectionIds = emptySet(),
            associationResult = ANS100NeuralAssociationDecoder.Result(0, 0, 0, emptyList()),
            hypothesisStatePersisted = true,
        )
        assertTrue(trace.getBoolean("neural_hypothesis_state_persisted"))
    }

    @Test
    fun exportsMemoryExplanationsSeparatelyAndHashesLabels() {
        val (evidence, decision, telemetry) = fixture()
        val report = ANS100WorkspaceConceptInputBridge.HypothesisReport(
            hypothesisId = "hyp:private-memory-id",
            fromLabel = "Private Concept A",
            relationType = ANS100ConceptMemory.RelationType.IS_A,
            toLabel = "Private Concept B",
            premiseEvidenceRefs = listOf("workspace-memory:one", "workspace-memory:two"),
            verificationStatus = ANS100ConceptMemory.VerificationStatus.CONFLICTING,
            supportingEvidenceRefs = listOf("workspace-memory-evidence:support"),
            contradictingEvidenceRefs = listOf("workspace-memory-evidence:counter"),
        )
        val trace = ANS100BenchmarkTraceAdapter.create(
            taskId = null,
            inputText = "test input",
            evidence = evidence,
            decision = decision,
            telemetry = telemetry,
            emittedSpikeNeuronIds = emptySet(),
            activatedConnectionIds = emptySet(),
            associationResult = ANS100NeuralAssociationDecoder.Result(0, 0, 0, emptyList()),
            memoryHypothesisReports = listOf(report),
        )
        val explanation = trace.getJSONArray("memory_hypothesis_explanations").getJSONObject(0)
        assertEquals("CONFLICTING", explanation.getString("verification_status"))
        assertEquals("IS_A", explanation.getString("relation_type"))
        assertEquals(1, explanation.getInt("supporting_evidence_count"))
        assertEquals(1, explanation.getInt("contradicting_evidence_count"))
        assertFalse(trace.toString().contains("Private Concept A"))
        assertFalse(trace.toString().contains("workspace-memory:one"))
        assertTrue(trace.toString().contains("memory_hypothesis_explanations"))
    }


    @Test
    fun exportsHypothesisCompetitionWithoutRawLabelsOrEvidence() {
        val (evidence, decision, telemetry) = fixture()
        val candidate = ANS100NeuralAssociationDecoder.Candidate(
            candidateId = "neural-association:" + "c".repeat(32),
            firstConceptId = "concept.dog",
            firstLabel = "Private Dog",
            secondConceptId = "concept.animal",
            secondLabel = "Private Animal",
            convergentNeuronIds = listOf(1200),
            activatedConnectionIds = listOf(
                "ans100-v4-evidence-general-association-left",
                "ans100-v4-evidence-general-association-right",
            ),
            evidenceRefs = listOf("memory:dog", "memory:animal"),
            activationHint = 1.0 / 3.0,
        )
        val report = ANS100WorkspaceConceptInputBridge.HypothesisReport(
            hypothesisId = "private-hypothesis-id",
            fromLabel = "Private Dog",
            relationType = ANS100ConceptMemory.RelationType.IS_A,
            toLabel = "Private Animal",
            premiseEvidenceRefs = listOf("private-evidence-1", "private-evidence-2"),
            verificationStatus = ANS100ConceptMemory.VerificationStatus.UNRESOLVED,
            supportingEvidenceRefs = emptyList(),
            contradictingEvidenceRefs = emptyList(),
        )
        val comparison = ANS100HypothesisCompetitionEngine().compare(listOf(candidate), listOf(report))
        val trace = ANS100BenchmarkTraceAdapter.create(
            taskId = null,
            inputText = "test input",
            evidence = evidence,
            decision = decision,
            telemetry = telemetry,
            emittedSpikeNeuronIds = emptySet(),
            activatedConnectionIds = emptySet(),
            associationResult = ANS100NeuralAssociationDecoder.Result(2, 1, 1, listOf(candidate)),
            memoryHypothesisReports = listOf(report),
            hypothesisComparisons = comparison,
        )
        val row = trace.getJSONArray("hypothesis_competition").getJSONObject(0)
        assertEquals("MEMORY_PATH_UNRESOLVED", row.getString("status"))
        assertEquals(1, row.getJSONArray("explanations").length())
        assertFalse(trace.toString().contains("Private Dog"))
        assertFalse(trace.toString().contains("private-evidence-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDecisionFromAnotherTurn() {
        val (evidence, decision, telemetry) = fixture()
        val wrongTurn = decision.copy(turnId = "different-turn")
        ANS100BenchmarkTraceAdapter.create(
            taskId = null,
            inputText = "test input",
            evidence = evidence,
            decision = wrongTurn,
            telemetry = telemetry,
            emittedSpikeNeuronIds = emptySet(),
            activatedConnectionIds = emptySet(),
            associationResult = ANS100NeuralAssociationDecoder.Result(0, 0, 0, emptyList()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidTaskIds() {
        val (evidence, decision, telemetry) = fixture()
        ANS100BenchmarkTraceAdapter.create(
            taskId = "../gold-label-leak",
            inputText = "test input",
            evidence = evidence,
            decision = decision,
            telemetry = telemetry,
            emittedSpikeNeuronIds = emptySet(),
            activatedConnectionIds = emptySet(),
            associationResult = ANS100NeuralAssociationDecoder.Result(0, 0, 0, emptyList()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeMeasurements() {
        val (evidence, decision, telemetry) = fixture()
        ANS100BenchmarkTraceAdapter.create(
            taskId = null,
            inputText = "test input",
            evidence = evidence,
            decision = decision,
            telemetry = telemetry,
            emittedSpikeNeuronIds = emptySet(),
            activatedConnectionIds = emptySet(),
            associationResult = ANS100NeuralAssociationDecoder.Result(0, 0, 0, emptyList()),
            inferenceDurationNs = -1L,
        )
    }

    @Test
    fun exportsCanonicalNeuralReadoutAsUncalibratedObservationNotBenchmarkAnswer() {
        val (evidence, decision, telemetry) = fixture()
        val yes = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.YES)
        val no = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.NO)
        val readout = ANS100CanonicalReadoutDecoder().decode(
            setOf(yes.startInclusive, yes.startInclusive + 1, no.startInclusive),
        )
        val trace = ANS100BenchmarkTraceAdapter.create(
            taskId = "pair_disjoint:relation_status:s104729:001",
            inputText = "A is B. Is A B?",
            evidence = evidence,
            decision = decision,
            telemetry = telemetry,
            emittedSpikeNeuronIds = setOf(
                yes.startInclusive,
                yes.startInclusive + 1,
                no.startInclusive,
            ),
            activatedConnectionIds = emptySet(),
            associationResult = ANS100NeuralAssociationDecoder.Result(0, 0, 0, emptyList()),
            canonicalReadout = readout,
        )
        val observed = trace.getJSONObject("neural_canonical_readout")
        assertEquals("YES", observed.getString("answer"))
        assertEquals("UNCALIBRATED_WINNER", observed.getString("status"))
        assertFalse(observed.getBoolean("production_decision"))
        assertEquals("NOT_CALIBRATED", observed.getString("calibration_status"))
        assertTrue(trace.isNull("answer"))
        assertEquals("NOT_SUPPORTED_BY_CURRENT_ANS100_PIPELINE", trace.getString("answer_emission"))
        assertEquals("HYBRID_POPULATION_CONTROLLER_V1", trace.getJSONObject("decision").getString("producer"))
    }

}
