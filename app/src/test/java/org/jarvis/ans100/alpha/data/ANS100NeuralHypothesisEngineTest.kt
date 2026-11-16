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

    @Test fun truncatedPairScanCannotCreateNegativeVerification() {
        val engine = ANS100NeuralHypothesisEngine()
        engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true)
        val row = engine.observeTurn(
            "turn-2",
            setOf("concept.alpha", "concept.beta"),
            emptyList(),
            completed = true,
            pairScanComplete = false,
        ).single()
        assertEquals(ANS100NeuralHypothesisEngine.Status.GENERATED, row.status)
        assertEquals(0, row.nonReproducingTurnIds.size)
    }

    @Test fun incompleteAndDuplicateTurnsCannotProvideVerification() {
        val engine = ANS100NeuralHypothesisEngine()
        engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), false)
        engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true)
        val row = engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true).single()
        assertEquals(ANS100NeuralHypothesisEngine.Status.GENERATED, row.status)
        assertEquals(1, row.observationCount)
    }

    @Test fun checkpointRestorePreservesIndependentTurnVerification() {
        val original = ANS100NeuralHypothesisEngine()
        original.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true)
        val checkpoint = original.exportSnapshot()
        val restored = ANS100NeuralHypothesisEngine()
        restored.restoreSnapshot(checkpoint)
        val row = restored.observeTurn(
            "turn-2", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true,
        ).single()
        assertEquals(ANS100NeuralHypothesisEngine.Status.REPRODUCED_ON_INDEPENDENT_TURN, row.status)
        assertEquals(2, row.observationCount)
    }

    @Test fun alternatingConvergenceAndNonConvergenceRemainsMixedSignal() {
        val engine = ANS100NeuralHypothesisEngine()
        engine.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true)
        engine.observeTurn("turn-2", setOf("concept.alpha", "concept.beta"), emptyList(), true)
        val row = engine.observeTurn(
            "turn-3", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true,
        ).single()
        assertEquals(ANS100NeuralHypothesisEngine.Status.MIXED_REPRODUCTION_SIGNAL, row.status)
        assertEquals(2, row.observationCount)
        assertEquals(listOf("turn-2"), row.nonReproducingTurnIds)
    }

    @Test fun perHypothesisObservationHistoryRemainsBounded() {
        val engine = ANS100NeuralHypothesisEngine()
        repeat(ANS100NeuralHypothesisEngine.MAX_OBSERVATIONS_PER_HYPOTHESIS + 2) { index ->
            engine.observeTurn(
                "turn-$index",
                setOf("concept.alpha", "concept.beta"),
                listOf(candidate()),
                true,
            )
        }
        val saved = engine.exportSnapshot().hypotheses.single()
        assertEquals(ANS100NeuralHypothesisEngine.MAX_OBSERVATIONS_PER_HYPOTHESIS, saved.supportingTurnIds.size)
        assertEquals(ANS100NeuralHypothesisEngine.MAX_OBSERVATIONS_PER_HYPOTHESIS + 2, engine.exportSnapshot().observedTurnIds.size)
    }

    @Test fun restoredTurnIdsCannotBeCountedTwice() {
        val original = ANS100NeuralHypothesisEngine()
        original.observeTurn("turn-1", setOf("concept.alpha", "concept.beta"), listOf(candidate()), true)
        val restored = ANS100NeuralHypothesisEngine()
        restored.restoreSnapshot(original.exportSnapshot())
        val duplicate = restored.observeTurn(
            "turn-1", setOf("concept.alpha", "concept.beta"), emptyList(), true,
        )
        assertTrue(duplicate.isEmpty())
        assertEquals(1, restored.snapshot().single().observationCount)
    }

    @Test fun restoreRejectsDuplicateTurnIds() {
        val engine = ANS100NeuralHypothesisEngine()
        val invalid = ANS100NeuralHypothesisEngine.Snapshot(
            hypotheses = emptyList(),
            observedTurnIds = listOf("turn-1", "turn-1"),
        )
        var rejected = false
        try {
            engine.restoreSnapshot(invalid)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
