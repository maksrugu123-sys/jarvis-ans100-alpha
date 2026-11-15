package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100HybridPopulationControllerTest {
    private fun evidence(message: String, turnId: String): Pair<TypedEvidenceBundle, LocalUnderstanding> {
        val understanding = LocalUnderstandingPipeline().analyze(message)
        val bundle = TypedEvidenceEncoder().encode(
            TypedEvidenceInput(turnId, message, understanding),
        )
        return bundle to understanding
    }

    private fun telemetry(missingPopulation: String? = null) = AndroidHybridRunTelemetry(
        topologyId = AndroidHybridTopologyV2.TOPOLOGY_ID,
        topologyVersion = AndroidHybridTopologyV2.TOPOLOGY_VERSION,
        populations = AndroidHybridTopologyV2.populations.map { population ->
            val active = population.id != missingPopulation
            AndroidNeuronPopulationActivity(
                populationId = population.id,
                receivedEventCount = if (active) 1 else 0,
                uniqueNeuronsProcessed = if (active) 1 else 0,
                uniqueSpikingNeurons = if (active) 1 else 0,
                spikeCount = if (active) 1 else 0,
                outgoingEventCount = 0,
            )
        },
    )

    @Test
    fun selectsCoreCandidateOnlyAfterAllSevenPopulationsSpike() {
        val (evidence, understanding) = evidence("Объясни, как работает сеть", "hybrid-gate-1")
        val decision = ANS100HybridPopulationController().decide(
            evidence = evidence,
            understanding = understanding,
            telemetry = telemetry(),
            processedEventCount = 7,
            pendingEvents = 0,
            budgetExhausted = false,
        )

        assertEquals(ANS100DecisionStatus.SELECTED, decision.status)
        assertEquals(ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1, decision.topologyVersion)
        assertEquals(ANS100DecisionProducer.HYBRID_POPULATION_CONTROLLER_V1, decision.decisionProducer)
        assertEquals(7, decision.activePopulationIds.size)
        assertEquals(0.0, decision.candidates.single().activation, 0.0)
        decision.validateAgainst(evidence)
    }

    @Test
    fun abstainsWhenAnyRequiredPopulationDoesNotSpike() {
        val (evidence, understanding) = evidence("Объясни, как работает сеть", "hybrid-gate-2")
        val decision = ANS100HybridPopulationController().decide(
            evidence = evidence,
            understanding = understanding,
            telemetry = telemetry("critic_uncertainty"),
            processedEventCount = 6,
            pendingEvents = 0,
            budgetExhausted = false,
        )

        assertEquals(ANS100DecisionStatus.ABSTAINED, decision.status)
        assertTrue(decision.abstentionReason!!.contains("critic_uncertainty"))
        assertTrue(decision.missingEvidence.contains("hybrid_population_not_reached:critic_uncertainty"))
        assertFalse(decision.computeBudgetExhausted)
    }

    @Test
    fun abstainsWhenTheEventQueueHasNotDrained() {
        val (evidence, understanding) = evidence("Что дальше?", "hybrid-gate-3")
        val decision = ANS100HybridPopulationController().decide(
            evidence = evidence,
            understanding = understanding,
            telemetry = telemetry(),
            processedEventCount = 100,
            pendingEvents = 3,
            budgetExhausted = false,
        )

        assertEquals(ANS100DecisionStatus.ABSTAINED, decision.status)
        assertTrue(decision.computeBudgetExhausted)
        assertTrue(ANS100UncertaintyKind.BUDGET_EXHAUSTED in decision.uncertainty)
    }

    @Test
    fun preservesCoreClarificationInsteadOfSelectingAnAnswer() {
        val (evidence, understanding) = evidence("Продолжи тот план", "hybrid-gate-4")
        val decision = ANS100HybridPopulationController().decide(
            evidence = evidence,
            understanding = understanding,
            telemetry = telemetry(),
            processedEventCount = 7,
            pendingEvents = 0,
            budgetExhausted = false,
        )

        assertTrue(understanding.reasoningPlan.requiresClarification)
        assertEquals(ANS100DecisionStatus.NEEDS_CLARIFICATION, decision.status)
        assertTrue(!decision.abstentionReason.isNullOrBlank())
        assertEquals(null, decision.selectedCandidateId)
    }
}
