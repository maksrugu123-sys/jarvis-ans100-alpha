package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100NeuralHypothesisEngineTest {
    private fun candidate() = ANS100NeuralAssociationDecoder.Candidate(
        candidateId = "neural-association:" + "a".repeat(32),
        firstConceptId = "concept.alpha",
        firstLabel = "alpha",
        secondConceptId = "concept.beta",
        secondLabel = "beta",
        convergentNeuronIds = listOf(1200, 1201),
        activatedConnectionIds = listOf(
            "ans100-v4-evidence-general-association-a",
            "ans100-v4-evidence-general-association-b",
        ),
        evidenceRefs = listOf("memory:a", "memory:b"),
        activationHint = 2.0 / 3.0,
    )

    @Test fun firstConvergenceCreatesUnverifiedHypothesis() {
        val rows = ANS100NeuralHypothesisEngine().observeTurn(
            "turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true,
        )
        assertEquals(ANS100NeuralHypothesisEngine.Status.GENERATED, rows.single().status)
        assertEquals(1, rows.single().observationCount)
    }

    @Test fun independentConvergenceReproducesHypothesis() {
        val engine = ANS100NeuralHypothesisEngine()
        engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true)
        val row = engine.observeTurn("turn-2", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true).single()
        assertEquals(ANS100NeuralHypothesisEngine.Status.REPRODUCED_ON_INDEPENDENT_TURN, row.status)
        assertEquals(2, row.observationCount)
    }

    @Test fun absentConvergenceIsNotFactualRefutation() {
        val engine = ANS100NeuralHypothesisEngine()
        engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true)
        val row = engine.observeTurn("turn-2", setOf("concept.alpha", "concept.beta"), emptyList(), true).single()
        assertEquals(ANS100NeuralHypothesisEngine.Status.NOT_REPRODUCED_ON_INDEPENDENT_TURN, row.status)
        assertTrue(row.nonReproducingTurnIds.contains("turn-2"))
    }

    @Test fun incompleteAndDuplicateTurnsCannotProvideVerification() {
        val engine = ANS100NeuralHypothesisEngine()
        engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), false)
        engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true)
        val row = engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true).single()
        assertEquals(ANS100NeuralHypothesisEngine.Status.GENERATED, row.status)
        assertEquals(1, row.observationCount)
    }
}
