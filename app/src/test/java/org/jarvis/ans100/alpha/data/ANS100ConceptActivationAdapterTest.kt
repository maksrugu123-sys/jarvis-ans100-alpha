package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100ConceptActivationAdapterTest {
    private fun memory(): ANS100ConceptMemory {
        val memory = ANS100ConceptMemory()
        val source = ANS100ConceptMemory.LearningSource.EXPLICIT_USER
        assertTrue(memory.learnConcept(
            ANS100ConceptMemory.Concept("dog", "Собака", setOf("пёс")),
            source,
        ))
        assertTrue(memory.learnConcept(
            ANS100ConceptMemory.Concept("solar_system", "Солнечная система", setOf("планетная система")),
            source,
        ))
        return memory
    }

    @Test
    fun groundsCanonicalLabelsAndExplicitAliasesToConceptIds() {
        val frame = ANS100NeuralInputAdapter().encode("turn-1", "Пёс изучает солнечную систему")
        val result = ANS100ConceptActivationAdapter().activate(frame, memory())
        assertEquals("turn-1", result.turnId)
        assertEquals(setOf("dog", "solar_system"), result.activations.map { it.conceptId }.toSet())
        assertEquals("пёс", result.activations.single { it.conceptId == "dog" }.matchedAlias)
    }

    @Test
    fun multiwordAliasRequiresItsObservedAdjacentBigram() {
        val adapter = ANS100ConceptActivationAdapter()
        val memory = memory()
        val exact = ANS100NeuralInputAdapter().encode("exact", "Я изучаю планетную систему")
        val reversed = ANS100NeuralInputAdapter().encode("reversed", "Я изучаю систему планетную")
        assertTrue(adapter.activate(exact, memory).activations.any { it.conceptId == "solar_system" })
        assertTrue(adapter.activate(reversed, memory).activations.none { it.conceptId == "solar_system" })
    }

    @Test
    fun doesNotInventSynonymsOrPartialWordMatches() {
        val frame = ANS100NeuralInputAdapter().encode("turn-2", "Пёсик и собачий корм")
        val result = ANS100ConceptActivationAdapter().activate(frame, memory())
        assertTrue(result.activations.none { it.conceptId == "dog" })
    }

    @Test
    fun ambiguousAliasActivatesBothConceptsInsteadOfChoosingOne() {
        val memory = memory()
        val source = ANS100ConceptMemory.LearningSource.EXPLICIT_USER
        assertTrue(memory.learnConcept(ANS100ConceptMemory.Concept("pet", "Пёс"), source))
        val frame = ANS100NeuralInputAdapter().encode("turn-3", "Пёс дома")
        val result = ANS100ConceptActivationAdapter().activate(frame, memory)
        assertEquals(setOf("dog", "pet"), result.activations.map { it.conceptId }.toSet())
    }

    @Test
    fun resultOrderingAndLimitAreDeterministic() {
        val memory = memory()
        val frame = ANS100NeuralInputAdapter().encode("turn-4", "Собака и солнечная система")
        val adapter = ANS100ConceptActivationAdapter()
        val first = adapter.activate(frame, memory, limit = 1)
        val second = adapter.activate(frame, memory, limit = 1)
        assertEquals(first, second)
        assertEquals(1, first.activations.size)
    }

    @Test
    fun zeroLimitReturnsNoActivations() {
        val frame = ANS100NeuralInputAdapter().encode("turn-5", "Собака")
        assertTrue(ANS100ConceptActivationAdapter().activate(frame, memory(), limit = 0).activations.isEmpty())
    }
}
