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
        assertEquals(12_345L, trace.getJSONObject("measurement").getLong("inference_duration_ns"))
        assertTrue(trace.getJSONObject("measurement").isNull("peak_memory_mb"))
        assertFalse(trace.toString().contains("private synthetic input"))
        assertFalse(trace.has("expected_answer"))
        assertFalse(trace.has("input_text"))
        assertTrue(ANS100BenchmarkTraceAdapter.toJsonLine(trace).endsWith("}"))
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
}
