package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100DecisionPolicyTest {
    @Test
    fun emitsExplicitCorePolicyDecisionWithoutClaimingNeuralReasoning() {
        val message = "Объясни, как работает этот проект"
        val understanding = LocalUnderstandingPipeline().analyze(message)
        val evidence = TypedEvidenceEncoder().encode(
            TypedEvidenceInput("policy-turn-1", message, understanding),
        )
        val decision = ANS100DecisionPolicy().decide(
            evidence = evidence,
            understanding = understanding,
            neuralAnalysis = null,
        )

        assertEquals("decision producer", ANS100DecisionProducer.CORE_POLICY_V1, decision.decisionProducer)
        assertEquals("topology version", ANS100TopologyVersion.LEGACY_DIALOGUE_READOUT_V1, decision.topologyVersion)
        assertEquals("decision status", ANS100DecisionStatus.SELECTED, decision.status)
        assertEquals("selected candidate", "respond_to_original_request", decision.selectedCandidateId)
        decision.validateAgainst(evidence)
    }

    @Test
    fun carriesClarificationInsteadOfInventingMissingContext() {
        val message = "Продолжи тот план"
        val understanding = LocalUnderstandingPipeline().analyze(message)
        val evidence = TypedEvidenceEncoder().encode(
            TypedEvidenceInput("policy-turn-2", message, understanding),
        )
        val decision = ANS100DecisionPolicy().decide(
            evidence = evidence,
            understanding = understanding,
            neuralAnalysis = null,
        )

        if (understanding.reasoningPlan.requiresClarification) {
            assertEquals(ANS100DecisionStatus.NEEDS_CLARIFICATION, decision.status)
            assertNull(decision.selectedCandidateId)
            assertTrue(!decision.abstentionReason.isNullOrBlank())
        } else {
            assertEquals(ANS100DecisionStatus.SELECTED, decision.status)
        }
    }

    @Test
    fun neuralTelemetryIsNotConvertedIntoCandidateConfidence() {
        val message = "Что происходит?"
        val understanding = LocalUnderstandingPipeline().analyze(message)
        val evidence = TypedEvidenceEncoder().encode(
            TypedEvidenceInput("policy-turn-3", message, understanding),
        )
        val telemetry = DialogueNeuralAnalysis(
            networkNeuronCount = 10_000,
            activeFeatureCount = 2,
            processedEvents = 7,
            processedNeuronBatches = 3,
            outputSpikeCount = 1,
            pendingEvents = 0,
            classifierLabel = "question",
            classifierConfidenceMargin = 0.9,
            classifierTrainedExamples = 4,
            statePersisted = true,
        )
        val decision = ANS100DecisionPolicy().decide(evidence, understanding, telemetry)

        assertEquals(7, decision.processedEventCount)
        assertTrue(decision.candidates.all { it.activation == 0.0 })
        assertEquals(ANS100DecisionProducer.CORE_POLICY_V1, decision.decisionProducer)
    }
}
