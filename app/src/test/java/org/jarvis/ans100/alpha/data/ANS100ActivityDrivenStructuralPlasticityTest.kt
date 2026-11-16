package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100ActivityDrivenStructuralPlasticityTest {
    private fun concept(id: String, neuronId: Int = 1_062 + id.last().code % 100) =
        ANS100ActivityDrivenStructuralPlasticity.ActiveConcept(
            conceptId = "concept:$id",
            neuronId = neuronId,
            label = "Понятие $id",
            provenanceRef = "turn:$id:verified-input",
        )

    private fun observe(
        learner: ANS100ActivityDrivenStructuralPlasticity,
        turn: String,
        concepts: List<ANS100ActivityDrivenStructuralPlasticity.ActiveConcept> = listOf(
            concept("alpha", 1_062),
            concept("beta", 1_063),
        ),
        existing: Map<Int, Set<Int>> = emptyMap(),
        loads: Map<Int, Int> = emptyMap(),
    ) = learner.observeTurn(turn, concepts, existing, loads)

    @Test
    fun createsNewSharedTargetOnlyAfterRepeatedDistinctTurnCoactivation() {
        val learner = ANS100ActivityDrivenStructuralPlasticity()
        assertTrue(observe(learner, "turn-1").emittedProposals.isEmpty())
        assertTrue(observe(learner, "turn-2").emittedProposals.isEmpty())

        val result = observe(learner, "turn-3")
        val proposal = result.emittedProposals.single()
        assertEquals(3, proposal.supportingDistinctTurns)
        assertEquals(2, proposal.connections.size)
        assertEquals(1, proposal.connections.map { it.targetNeuronId }.distinct().size)
        assertEquals(setOf(1_062, 1_063), proposal.connections.map { it.sourceNeuronId }.toSet())
        assertTrue(proposal.connections.all { it.weight == 0.25 })
        assertTrue(proposal.connections.all { it.connectionId.startsWith("ans100-v5-grown-association-") })
        assertEquals(1, learner.grownPairCount())
    }

    @Test
    fun replayedTurnIdDoesNotIncreaseEligibility() {
        val learner = ANS100ActivityDrivenStructuralPlasticity()
        observe(learner, "same-turn")
        val replay = observe(learner, "same-turn")
        assertFalse(replay.acceptedTurn)
        assertTrue(replay.emittedProposals.isEmpty())
        assertTrue(observe(learner, "turn-2").emittedProposals.isEmpty())
        assertTrue(observe(learner, "turn-3").emittedProposals.isEmpty())
        assertEquals(0, learner.grownPairCount())
        assertEquals(1, learner.pendingCount())
    }

    @Test
    fun pairWithExistingSharedTargetDoesNotGrowAgain() {
        val learner = ANS100ActivityDrivenStructuralPlasticity()
        val existing = mapOf(1_062 to setOf(1_250), 1_063 to setOf(1_250))
        repeat(4) { observe(learner, "turn-$it", existing = existing) }
        assertEquals(0, learner.grownPairCount())
        assertEquals(0, learner.pendingCount())
    }

    @Test
    fun newTargetAvoidsEitherSourcesExistingTargetsAndUsesLeastLoadedCompetition() {
        val learner = ANS100ActivityDrivenStructuralPlasticity()
        val existing = mapOf(1_062 to setOf(1_250, 1_251), 1_063 to setOf(1_260))
        val loads = (1_200 until 2_200).associateWith { 1 }.toMutableMap().apply { put(1_202, 0) }
        repeat(2) { observe(learner, "turn-$it", existing = existing, loads = loads) }
        val proposal = observe(learner, "turn-2", existing = existing, loads = loads).emittedProposals.single()
        assertNotEquals(1_250, proposal.targetNeuronId)
        assertNotEquals(1_251, proposal.targetNeuronId)
        assertNotEquals(1_260, proposal.targetNeuronId)
        assertEquals(1_202, proposal.targetNeuronId)
    }

    @Test
    fun targetAtIncomingCapacityIsNotSelected() {
        val learner = ANS100ActivityDrivenStructuralPlasticity()
        val full = (1_200 until 2_200).associateWith { 12 }
        repeat(2) { observe(learner, "turn-$it", loads = full) }
        val result = observe(learner, "turn-2", loads = full)
        assertTrue(result.emittedProposals.isEmpty())
        assertEquals(1, result.capacityBlockedPairCount)
        assertEquals(0, learner.grownPairCount())
    }

    @Test
    fun pairEligibilityExpiresOutsideObservationWindow() {
        val learner = ANS100ActivityDrivenStructuralPlasticity()
        observe(learner, "pair-1")
        observe(learner, "pair-2")
        repeat(17) { observe(learner, "unrelated-$it", concepts = emptyList()) }
        val result = observe(learner, "pair-3")
        assertTrue(result.emittedProposals.isEmpty())
        assertEquals(1, learner.pendingCount())
    }

    @Test
    fun perConceptGrowthDegreeIsBounded() {
        val learner = ANS100ActivityDrivenStructuralPlasticity(maxGrownEdgesPerConcept = 1)
        val concepts = listOf(concept("alpha", 1_062), concept("beta", 1_063), concept("gamma", 1_064))
        observe(learner, "turn-1", concepts)
        observe(learner, "turn-2", concepts)
        val result = observe(learner, "turn-3", concepts)
        assertEquals(1, result.emittedProposals.size)
        assertEquals(2, result.capacityBlockedPairCount)
        assertEquals(1, learner.grownPairCount())
    }

    @Test
    fun outputIsDeterministicForSameActivityAndLoadState() {
        fun run(): ANS100ActivityDrivenStructuralPlasticity.GrowthProposal {
            val learner = ANS100ActivityDrivenStructuralPlasticity()
            observe(learner, "turn-1")
            observe(learner, "turn-2")
            return observe(learner, "turn-3").emittedProposals.single()
        }
        assertEquals(run(), run())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateConceptIdsInOneTurnAreRejected() {
        val duplicate = concept("alpha", 1_062)
        observe(
            ANS100ActivityDrivenStructuralPlasticity(),
            "turn-1",
            listOf(duplicate, duplicate.copy(neuronId = 1_063)),
        )
    }

    @Test
    fun restoredGrownPairsAreNotProposedAgainAndConsumeConceptDegree() {
        val learner = ANS100ActivityDrivenStructuralPlasticity(
            maxGrownPairs = 8,
            maxGrownEdgesPerConcept = 1,
        )
        learner.restoreGrownState(
            grownConceptPairs = setOf("concept:alpha" to "concept:beta"),
            grownEdgesByConcept = mapOf("concept:alpha" to 1, "concept:beta" to 1),
        )
        val newPairSharingAlpha = listOf(concept("alpha", 1_062), concept("gamma", 1_064))
        repeat(2) { index ->
            val result = observe(learner, "restored-turn-$index", newPairSharingAlpha)
            assertTrue(result.emittedProposals.isEmpty())
        }
        val blocked = observe(learner, "restored-turn-2", newPairSharingAlpha)
        assertTrue(blocked.emittedProposals.isEmpty())
        assertEquals(1, blocked.capacityBlockedPairCount)
        assertEquals(1, learner.grownPairCount())
    }


    @Test
    fun sameTurnGrowthDoesNotReuseTargetAlreadyConnectedToEitherConcept() {
        val learner = ANS100ActivityDrivenStructuralPlasticity(
            maxGrownPairs = 3,
            maxGrownEdgesPerConcept = 4,
        )
        val concepts = listOf(
            concept("alpha", 1_062),
            concept("beta", 1_063),
            concept("gamma", 1_064),
        )
        val loads = (1_200 until 2_200).associateWith { 12 }.toMutableMap().apply {
            this[1_500] = 0
        }
        observe(learner, "unique-target-1", concepts, loads = loads)
        observe(learner, "unique-target-2", concepts, loads = loads)
        val result = observe(learner, "unique-target-3", concepts, loads = loads)

        assertEquals(1, result.emittedProposals.size)
        assertEquals(2, result.capacityBlockedPairCount)
        assertEquals(1_500, result.emittedProposals.single().targetNeuronId)
    }

}
