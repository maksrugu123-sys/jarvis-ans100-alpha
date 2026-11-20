package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kCounterfactualEvaluatorTest {
    @Test
    fun suppressingAnOutcomePathChangesOnlyItsCounterfactualReplay() {
        val states = mapOf(
            1 to AndroidNeuronState(neuronId = 1),
            2 to AndroidNeuronState(neuronId = 2),
            3 to AndroidNeuronState(neuronId = 3),
        )
        val edges = listOf(
            AndroidNeuronConnection("edge-outcome", 1, 2, delayNs = 1L, weight = 1.0),
            AndroidNeuronConnection("edge-unrelated", 1, 3, delayNs = 1L, weight = 1.0),
        )
        val report = AndroidJnvM250kCounterfactualEvaluator.evaluate(
            initialStates = states,
            connections = edges,
            externalInputs = listOf(AndroidNeuronEvent("external-source", 1L, 1, 1.0)),
            candidateConnectionIds = setOf("edge-outcome", "edge-unrelated"),
            outcomeNeuronIds = setOf(2),
        )
        val outcome = report.candidates.first { it.connectionId == "edge-outcome" }
        val unrelated = report.candidates.first { it.connectionId == "edge-unrelated" }

        assertEquals(1, outcome.baselineOutcomeSpikes)
        assertEquals(0, outcome.counterfactualOutcomeSpikes)
        assertEquals(1, outcome.outcomeSpikeDelta)
        assertEquals(1.0, outcome.sensitivityScore, 0.0)
        assertEquals(0, unrelated.outcomeSpikeDelta)
        assertEquals(0.0, unrelated.sensitivityScore, 0.0)
        assertTrue(report.baselineComplete)
    }

    @Test
    fun evaluatorDoesNotMutateCallerSnapshots() {
        val state = AndroidNeuronState(neuronId = 1)
        val states = mapOf(1 to state, 2 to AndroidNeuronState(neuronId = 2))
        AndroidJnvM250kCounterfactualEvaluator.evaluate(
            initialStates = states,
            connections = listOf(AndroidNeuronConnection("edge", 1, 2, 1L, 1.0)),
            externalInputs = listOf(AndroidNeuronEvent("input", 1L, 1, 1.0)),
            candidateConnectionIds = setOf("edge"),
            outcomeNeuronIds = setOf(2),
        )
        assertEquals(state, states.getValue(1))
        assertEquals(AndroidNeuronState(neuronId = 2), states.getValue(2))
    }

    @Test
    fun candidateAndSnapshotBoundsFailClosed() {
        val states = mapOf(1 to AndroidNeuronState(neuronId = 1), 2 to AndroidNeuronState(neuronId = 2))
        val edges = (0..8).map { AndroidNeuronConnection("edge-$it", 1, 2, 1L, 0.1) }
        assertTrue(runCatching {
            AndroidJnvM250kCounterfactualEvaluator.evaluate(
                states, edges, listOf(AndroidNeuronEvent("input", 1L, 1, 1.0)),
                edges.map { it.connectionId }.toSet(), setOf(2),
            )
        }.isFailure)
    }

    @Test
    fun incompleteReplayIsMarkedAndNeverPresentedAsComplete() {
        val states = mapOf(1 to AndroidNeuronState(neuronId = 1), 2 to AndroidNeuronState(neuronId = 2))
        val report = AndroidJnvM250kCounterfactualEvaluator.evaluate(
            initialStates = states,
            connections = listOf(AndroidNeuronConnection("edge", 1, 2, 1L, 1.0)),
            externalInputs = listOf(AndroidNeuronEvent("input", 1L, 1, 1.0)),
            candidateConnectionIds = setOf("edge"),
            outcomeNeuronIds = setOf(2),
            limits = AndroidJnvM250kCounterfactualEvaluator.Limits(maxTimestampsPerReplay = 1),
        )
        assertFalse(report.baselineComplete)
        assertFalse(report.candidates.single().counterfactualComplete)
    }
}
