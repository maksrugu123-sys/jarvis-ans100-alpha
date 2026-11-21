package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100BenchmarkNeuralInputAdapterTest {
    private val adapter = ANS100BenchmarkNeuralInputAdapter()

    @Test
    fun createsDeterministicCleanPulsesFromTextOnly() {
        val first = adapter.createBatch("bench-task-1", "alpha beta gamma", 10L)
        val second = adapter.createBatch("bench-task-1", "alpha beta gamma", 10L)

        assertEquals(first.batch.events.map { it.targetNeuronId }, second.batch.events.map { it.targetNeuronId })
        assertEquals(first.batch.events.map { it.eventId }, second.batch.events.map { it.eventId })
        assertTrue(first.activatedTokenCount > 0)
        assertEquals(first.activatedTokenCount, first.batch.conceptTrace.size)
        assertTrue(first.batch.trace.isEmpty())
        assertTrue(first.batch.events.all { !it.coreTainted && it.strength == 1.0 })
        assertTrue(first.batch.events.all {
            it.targetNeuronId in ANS100ConceptMemory.NEURAL_INPUT_START until
                ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
        })
        assertEquals(
            first.batch.events.map { it.targetNeuronId },
            first.batch.conceptTrace.map { it.neuronId },
        )
        assertFalse(first.batch.conceptTrace.any { it.matchedAlias.isBlank() })
    }

    @Test
    fun reportsHashSlotCollisionsInsteadOfSilentlyMergingFeatures() {
        val result = adapter.createBatch("bench-task-2", "alpha beta gamma delta epsilon zeta eta theta", 20L)
        assertEquals(result.encodedTokenCount, result.activatedTokenCount + result.slotCollisionCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyInput() {
        adapter.createBatch("bench-task-3", "   ", 30L)
    }
}
