package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100ConceptNeuralInputAdapterTest {
    @Test
    fun usesPersistentNeuronAssignmentsInsteadOfPerTurnAllocationOrder() {
        val memory = ANS100ConceptMemory()
        val ids = listOf("memory:alpha", "memory:beta")
        ids.forEachIndexed { index, id ->
            assertTrue(memory.learnConcept(
                ANS100ConceptMemory.Concept(id, "Concept $index"),
                ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
            ))
        }
        val activations = ANS100ConceptActivationAdapter.Result(
            turnId = "persistent-slot-test",
            activations = listOf(
                ANS100ConceptActivationAdapter.Activation("memory:alpha", 1.0, "Alpha", listOf("feature:alpha")),
                ANS100ConceptActivationAdapter.Activation("memory:beta", 1.0, "Beta", listOf("feature:beta")),
            ),
        )
        val persistent = mapOf(
            "memory:alpha" to ANS100ConceptMemory.NEURAL_INPUT_START + 42,
            "memory:beta" to ANS100ConceptMemory.NEURAL_INPUT_START + 7,
        )
        val batch = ANS100ConceptNeuralInputAdapter().createBatch(
            activations, memory, 10L, neuronIdsByConceptId = persistent,
        )
        assertEquals(listOf(1104, 1069), batch.trace.map { it.neuronId })
        assertEquals(0, batch.skippedSlotCount)
    }

    @Test
    fun conceptsWithoutPersistentAssignmentsAreSkippedWithoutAbortingTurn() {
        val memory = ANS100ConceptMemory()
        assertTrue(memory.learnConcept(
            ANS100ConceptMemory.Concept("memory:unmapped", "Unmapped"),
            ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
        ))
        val activations = ANS100ConceptActivationAdapter.Result(
            turnId = "missing-slot-test",
            activations = listOf(
                ANS100ConceptActivationAdapter.Activation("memory:unmapped", 1.0, "Unmapped", listOf("feature:x")),
            ),
        )
        val batch = ANS100ConceptNeuralInputAdapter().createBatch(
            activations, memory, 11L, neuronIdsByConceptId = emptyMap(),
        )
        assertTrue(batch.events.isEmpty())
        assertEquals(1, batch.skippedSlotCount)
    }
}
