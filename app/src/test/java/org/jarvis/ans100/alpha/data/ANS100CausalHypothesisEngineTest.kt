package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100CausalHypothesisEngineTest {
    private fun buildMemory(): ANS100ConceptMemory {
        val memory = ANS100ConceptMemory()
        listOf(
            "a" to "Событие A",
            "b" to "Событие B",
            "c" to "Событие C",
            "d" to "Событие D",
        ).forEach { (id, label) ->
            assertTrue(memory.learnConcept(
                ANS100ConceptMemory.Concept(id, label),
                ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
            ))
        }
        fun relation(id: String, from: String, to: String) =
            ANS100ConceptMemory.Relation(
                id = id,
                fromConceptId = from,
                type = ANS100ConceptMemory.RelationType.CAUSES,
                toConceptId = to,
                strength = 0.8,
                source = ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
                evidenceRef = "memory:$id",
            )
        assertTrue(memory.learnRelation(relation("a-b", "a", "b")))
        assertTrue(memory.learnRelation(relation("b-c", "b", "c")))
        return memory
    }

    @Test fun causalChainIsOnlyACandidateAndDoesNotBecomeStoredFact() {
        val memory = buildMemory()
        val candidate = ANS100CausalHypothesisEngine().generate(memory, setOf("a")).single()
        assertEquals("a", candidate.fromConceptId)
        assertEquals("c", candidate.toConceptId)
        assertEquals(ANS100CausalHypothesisEngine.Status.UNRESOLVED, candidate.status)
        assertTrue(candidate.supportingEvidenceRefs.isEmpty())
        assertTrue(memory.related("a").none { it.toConceptId == "c" })
        // The ordinary taxonomy hypothesis API deliberately remains non-causal.
        assertTrue(memory.generateHypotheses().isEmpty())
    }

    @Test fun separateDirectEvidenceCanSupportAChainCandidate() {
        val memory = buildMemory()
        val direct = ANS100ConceptMemory.RelationEvidence(
            fromConceptId = "a",
            type = ANS100ConceptMemory.RelationType.CAUSES,
            toConceptId = "c",
            supports = true,
            source = ANS100ConceptMemory.LearningSource.VERIFIED_TOOL,
            evidenceRef = "experiment:direct-support",
        )
        val candidate = ANS100CausalHypothesisEngine()
            .generate(memory, setOf("a"), listOf(direct)).single()
        assertEquals(
            ANS100CausalHypothesisEngine.Status.SUPPORTED_BY_STORED_RECORDS,
            candidate.status,
        )
        assertEquals(listOf("experiment:direct-support"), candidate.supportingEvidenceRefs)
        assertEquals(
            setOf(ANS100ConceptMemory.LearningSource.VERIFIED_TOOL),
            candidate.supportingEvidenceSources,
        )
    }

    @Test fun directCounterEvidenceAndConflictRemainExplicit() {
        val memory = buildMemory()
        val support = ANS100ConceptMemory.RelationEvidence(
            "a", ANS100ConceptMemory.RelationType.CAUSES, "c", true,
            ANS100ConceptMemory.LearningSource.VERIFIED_TOOL, "experiment:support",
        )
        val refutation = support.copy(supports = false, evidenceRef = "experiment:refute")
        val engine = ANS100CausalHypothesisEngine()
        assertEquals(
            ANS100CausalHypothesisEngine.Status.REFUTED_BY_STORED_RECORDS,
            engine.generate(memory, setOf("a"), listOf(refutation)).single().status,
        )
        assertEquals(
            ANS100CausalHypothesisEngine.Status.CONFLICTING_STORED_RECORDS,
            engine.generate(memory, setOf("a"), listOf(support, refutation)).single().status,
        )
    }

    @Test fun preventionRelationsAreNotComposed() {
        val memory = buildMemory()
        val prevents = ANS100ConceptMemory.Relation(
            "b-prevents-c", "b", ANS100ConceptMemory.RelationType.PREVENTS, "c",
            0.8, ANS100ConceptMemory.LearningSource.EXPLICIT_USER, "memory:prevents",
        )
        assertTrue(memory.learnRelation(prevents))
        assertTrue(ANS100CausalHypothesisEngine().generate(memory, setOf("a")).isEmpty())
    }

    @Test fun onlyActiveStartingConceptsAreExpandedAndOutputIsBounded() {
        val memory = buildMemory()
        assertTrue(ANS100CausalHypothesisEngine().generate(memory, setOf("d")).isEmpty())
        assertTrue(ANS100CausalHypothesisEngine().generate(memory, setOf("a"), limit = 0).isEmpty())
    }
}
