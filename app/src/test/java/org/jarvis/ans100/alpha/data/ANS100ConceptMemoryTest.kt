package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100ConceptMemoryTest {
    private fun concept(id: String, label: String) = ANS100ConceptMemory.Concept(id, label)
    private fun relation(id: String, from: String, type: ANS100ConceptMemory.RelationType, to: String, ref: String = "evidence:$id", strength: Double = 0.9) =
        ANS100ConceptMemory.Relation(id, from, type, to, strength, ANS100ConceptMemory.LearningSource.EXPLICIT_USER, ref)

    private fun memory(): ANS100ConceptMemory {
        val memory = ANS100ConceptMemory()
        listOf(concept("animal", "Животное"), concept("mammal", "Млекопитающее"), concept("dog", "Собака"), concept("vehicle", "Транспорт"))
            .forEach { assertTrue(memory.learnConcept(it, ANS100ConceptMemory.LearningSource.EXPLICIT_USER)) }
        return memory
    }

    @Test fun conceptsAreDistinctFromWordsAndRelationsCarryTypedMeaning() {
        val memory = memory()
        assertTrue(memory.learnRelation(relation("r1", "dog", ANS100ConceptMemory.RelationType.IS_A, "mammal")))
        assertTrue(memory.learnRelation(relation("r2", "mammal", ANS100ConceptMemory.RelationType.IS_A, "animal")))
        assertEquals("Собака", memory.recallConcept("dog")?.label)
        assertEquals(2, memory.related("mammal").size)
    }

    @Test fun generatesAuditableIsAHypothesisFromTwoRelations() {
        val memory = memory()
        memory.learnRelation(relation("dog-mammal", "dog", ANS100ConceptMemory.RelationType.IS_A, "mammal"))
        memory.learnRelation(relation("mammal-animal", "mammal", ANS100ConceptMemory.RelationType.IS_A, "animal"))
        val hypothesis = memory.generateHypotheses().single()
        assertEquals("dog", hypothesis.fromConceptId)
        assertEquals("animal", hypothesis.toConceptId)
        assertEquals(listOf("dog-mammal", "mammal-animal"), hypothesis.premiseRelationIds)
        assertEquals(listOf("evidence:dog-mammal", "evidence:mammal-animal"), hypothesis.evidenceRefs)
        assertEquals(0.81, hypothesis.estimatedStrength, 0.000001)
        assertEquals(ANS100ConceptMemory.VerificationStatus.UNRESOLVED, memory.verify(hypothesis).status)
    }

    @Test fun hypothesesAreScopedToConceptsActivatedByCurrentInput() {
        val memory = memory()
        memory.learnRelation(relation("dog-mammal", "dog", ANS100ConceptMemory.RelationType.IS_A, "mammal"))
        memory.learnRelation(relation("mammal-animal", "mammal", ANS100ConceptMemory.RelationType.IS_A, "animal"))
        memory.learnRelation(relation("vehicle-animal", "vehicle", ANS100ConceptMemory.RelationType.IS_A, "animal"))

        val activeDog = memory.generateHypothesesFor(setOf("dog"))
        assertEquals(1, activeDog.size)
        assertEquals("dog", activeDog.single().fromConceptId)
        assertTrue(memory.generateHypothesesFor(setOf("vehicle")).isEmpty())
        assertTrue(memory.generateHypothesesFor(emptySet()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun activatedConceptMustExistInMemory() {
        memory().generateHypothesesFor(setOf("unknown"))
    }

    @Test fun neuralInputSlotsAreStableUniqueAndBounded() {
        val memory = memory()
        val dogSlot = memory.neuralInputNeuronId("dog")
        assertEquals(ANS100ConceptMemory.NEURAL_INPUT_START, dogSlot)
        assertEquals(dogSlot, memory.neuralInputNeuronId("dog"))
        assertTrue(memory.neuralInputNeuronId("animal") != dogSlot)
        assertEquals(2, memory.neuralInputSlotCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun neuralInputSlotCannotBeAssignedToUnknownConcept() {
        memory().neuralInputNeuronId("unknown")
    }

    @Test fun directEvidenceCanSupportRefuteOrConflictWithHypothesis() {
        val memory = memory()
        memory.learnRelation(relation("dog-mammal", "dog", ANS100ConceptMemory.RelationType.IS_A, "mammal"))
        memory.learnRelation(relation("mammal-animal", "mammal", ANS100ConceptMemory.RelationType.IS_A, "animal"))
        val hypothesis = memory.generateHypotheses().single()
        val support = ANS100ConceptMemory.RelationEvidence("dog", ANS100ConceptMemory.RelationType.IS_A, "animal", true, ANS100ConceptMemory.LearningSource.VERIFIED_TOOL, "tool:taxonomy")
        assertEquals(ANS100ConceptMemory.VerificationStatus.SUPPORTED, memory.verify(hypothesis, listOf(support)).status)
        val conflict = support.copy(supports = false, evidenceRef = "tool:counterexample")
        assertEquals(ANS100ConceptMemory.VerificationStatus.CONFLICTING, memory.verify(hypothesis, listOf(support, conflict)).status)
        assertEquals(ANS100ConceptMemory.VerificationStatus.REFUTED, memory.verify(hypothesis, listOf(conflict)).status)
    }

    @Test fun causalRelationsAreNotBlindlyChained() {
        val memory = memory()
        memory.learnRelation(relation("a-b", "dog", ANS100ConceptMemory.RelationType.CAUSES, "mammal"))
        memory.learnRelation(relation("b-c", "mammal", ANS100ConceptMemory.RelationType.CAUSES, "animal"))
        assertTrue(memory.generateHypotheses().isEmpty())
    }

    @Test fun generatedOrImplicitInformationCannotTeachConceptMemory() {
        val memory = ANS100ConceptMemory()
        assertFalse(memory.learnConcept(concept("dog", "Собака"), ANS100ConceptMemory.LearningSource.MODEL_GENERATED))
        assertFalse(memory.learnConcept(concept("dog", "Собака"), ANS100ConceptMemory.LearningSource.IMPLICIT_BEHAVIOR))
        assertEquals(0, memory.conceptCount())
    }

    @Test fun inferredRelationsCannotBeStoredAsFactsAndCapacityIsBounded() {
        val memory = memory()
        val inferred = relation("fake", "dog", ANS100ConceptMemory.RelationType.IS_A, "animal").copy(source = ANS100ConceptMemory.LearningSource.INFERRED)
        assertFalse(memory.learnRelation(inferred))
        val bounded = ANS100ConceptMemory(maxConcepts = 1)
        assertTrue(bounded.learnConcept(concept("one", "Один"), ANS100ConceptMemory.LearningSource.EXPLICIT_USER))
        assertFalse(bounded.learnConcept(concept("two", "Два"), ANS100ConceptMemory.LearningSource.EXPLICIT_USER))
    }

    @Test(expected = IllegalArgumentException::class) fun relationCannotReferToUnknownConcept() {
        memory().learnRelation(relation("bad", "dog", ANS100ConceptMemory.RelationType.IS_A, "unknown"))
    }
}
