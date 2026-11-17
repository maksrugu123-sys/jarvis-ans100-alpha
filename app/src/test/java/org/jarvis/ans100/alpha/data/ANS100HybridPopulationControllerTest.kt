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

    private fun spikesForSignals(
        bundle: TypedEvidenceBundle,
        missingPopulation: String? = null,
    ): Set<Int> = buildSet {
        ANS100CandidateSlotContract.Slot.values().forEach { slot ->
            ANS100CandidateSlotContract.Polarity.values().forEach { polarity ->
                val feature = ANS100CandidateSlotContract.evidenceFeature(slot, polarity)
                val record = bundle.records.single { it.feature == feature }
                val active = (record.value as EvidenceValue.BooleanValue).value
                if (active) {
                    val lane = ANS100CandidateSlotContract.readoutLane(slot, polarity)
                    addAll(lane.startInclusive until lane.endExclusive)
                }
            }
        }
        AndroidHybridTopologyV2.populations
            .filterNot { it.id == "canonical_readout" || it.id == missingPopulation }
            .forEach { add(it.startInclusive) }
    }

    private fun buildTelemetry(emitted: Set<Int>) = AndroidHybridRunTelemetry(
        topologyId = AndroidHybridTopologyV2.TOPOLOGY_ID,
        topologyVersion = AndroidHybridTopologyV2.TOPOLOGY_VERSION,
        populations = AndroidHybridTopologyV2.populations.map { population ->
            val count = emitted.count(population::contains)
            AndroidNeuronPopulationActivity(
                populationId = population.id,
                receivedEventCount = count,
                uniqueNeuronsProcessed = count,
                uniqueSpikingNeurons = count,
                spikeCount = count,
                outgoingEventCount = 0,
            )
        },
    )

    private fun decide(
        evidence: TypedEvidenceBundle,
        understanding: LocalUnderstanding,
        emitted: Set<Int>? = null,
        missingPopulation: String? = null,
        runTelemetry: AndroidHybridRunTelemetry? = null,
        processed: Int = 20,
        pending: Int = 0,
        exhausted: Boolean = false,
    ): ANS100Decision {
        val actualSpikes = emitted ?: spikesForSignals(evidence, missingPopulation)
        return ANS100HybridPopulationController().decide(
            evidence = evidence,
            understanding = understanding,
            telemetry = runTelemetry ?: buildTelemetry(actualSpikes),
            emittedSpikeNeuronIds = actualSpikes,
            processedEventCount = processed,
            pendingEvents = pending,
            budgetExhausted = exhausted,
        )
    }

    @Test
    fun selectsAnswerFromItsOwnPositiveReadoutLane() {
        val (bundle, understanding) = evidence("Объясни, как работает сеть", "hybrid-competition-1")
        val decision = decide(bundle, understanding)

        assertEquals(ANS100DecisionStatus.SELECTED, decision.status)
        assertEquals(ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1, decision.topologyVersion)
        assertEquals(ANS100DecisionProducer.HYBRID_POPULATION_CONTROLLER_V1, decision.decisionProducer)
        assertEquals(7, decision.activePopulationIds.size)
        assertEquals(3, decision.candidates.size)
        assertEquals("respond_to_original_request", decision.selectedCandidateId)
        assertTrue(decision.candidates.any { it.activation > 0.0 })
        assertTrue(decision.candidates.all { it.activation in 0.0..1.0 })
        assertTrue(decision.responseConstraints.any { it.contains("отдельным спайкам readout") })
        decision.validateAgainst(bundle)
    }

    @Test
    fun criticConflictLaneSuppressesDirectAnswerAndSelectsClarification() {
        val (bundle, understanding) = evidence("Что это?", "hybrid-competition-2")
        val decision = decide(bundle, understanding)
        val answerConflictId = bundle.records.single {
            it.feature == EvidenceFeature.CANDIDATE_ANSWER_CONFLICT
        }.recordId
        val clarificationSupportId = bundle.records.single {
            it.feature == EvidenceFeature.CANDIDATE_CLARIFICATION_SUPPORT
        }.recordId
        val answer = decision.candidates.single { it.candidateId == "respond_to_original_request" }
        val clarification = decision.candidates.single { it.candidateId == "request_clarification" }

        assertTrue(understanding.reasoningPlan.requiresClarification)
        assertEquals(ANS100DecisionStatus.NEEDS_CLARIFICATION, decision.status)
        assertTrue(answerConflictId in answer.conflictingEvidenceIds)
        assertTrue(clarificationSupportId in clarification.supportingEvidenceIds)
        assertEquals(null, decision.selectedCandidateId)
    }

    @Test
    fun abstainsWhenAnyCandidateReadoutLaneIsMissing() {
        val (bundle, understanding) = evidence("Объясни, как работает сеть", "hybrid-competition-3")
        val decision = decide(bundle, understanding, emitted = emptySet())

        assertEquals(ANS100DecisionStatus.ABSTAINED, decision.status)
        assertTrue(decision.missingEvidence.any { it.startsWith("candidate_readout_mismatch:") })
        assertEquals(null, decision.selectedCandidateId)
    }

    @Test
    fun abstainsWhenReadoutScoresHaveNoPositiveUniqueWinner() {
        val (bundle, understanding) = evidence("Объясни, как работает сеть", "hybrid-competition-4")
        val allLanes = buildSet {
            addAll(spikesForSignals(bundle))
            ANS100CandidateSlotContract.Slot.values().forEach { slot ->
                ANS100CandidateSlotContract.Polarity.values().forEach { polarity ->
                    val lane = ANS100CandidateSlotContract.readoutLane(slot, polarity)
                    addAll(lane.startInclusive until lane.endExclusive)
                }
            }
        }
        val decision = decide(bundle, understanding, emitted = allLanes)

        assertEquals(ANS100DecisionStatus.ABSTAINED, decision.status)
        assertTrue(decision.uncertainty.contains(ANS100UncertaintyKind.CONFLICTING_EVIDENCE))
    }

    @Test
    fun abstainsWhenAnyRequiredPopulationDoesNotSpike() {
        val (bundle, understanding) = evidence("Объясни, как работает сеть", "hybrid-competition-5")
        val decision = decide(bundle, understanding, missingPopulation = "critic_uncertainty")

        assertEquals(ANS100DecisionStatus.ABSTAINED, decision.status)
        assertTrue(decision.abstentionReason!!.contains("critic_uncertainty"))
        assertTrue(decision.missingEvidence.contains("hybrid_population_not_reached:critic_uncertainty"))
        assertFalse(decision.computeBudgetExhausted)
    }

    @Test
    fun abstainsWhenTheEventQueueHasNotDrained() {
        val (bundle, understanding) = evidence("Что дальше?", "hybrid-competition-6")
        val decision = decide(bundle, understanding, pending = 3)

        assertEquals(ANS100DecisionStatus.ABSTAINED, decision.status)
        assertTrue(decision.computeBudgetExhausted)
        assertTrue(ANS100UncertaintyKind.BUDGET_EXHAUSTED in decision.uncertainty)
    }

    @Test
    fun preservesExplicitBudgetExhaustion() {
        val (bundle, understanding) = evidence("Объясни, как работает сеть", "hybrid-competition-7")
        val decision = decide(bundle, understanding, exhausted = true)

        assertEquals(ANS100DecisionStatus.ABSTAINED, decision.status)
        assertTrue(decision.computeBudgetExhausted)
    }
}
