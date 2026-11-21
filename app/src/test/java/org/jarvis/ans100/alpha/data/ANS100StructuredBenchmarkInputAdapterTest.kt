package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100StructuredBenchmarkInputAdapterTest {
    private val adapter = ANS100StructuredBenchmarkInputAdapter()

    @Test
    fun subjectAndObjectUseDifferentRoleLanesWhenDirectionIsReversed() {
        val forward = adapter.createBatch("structured-1", input("alpha", "beta"), 10L)
        val reverse = adapter.createBatch("structured-2", input("beta", "alpha"), 10L)

        val forwardSubject = forward.batch.conceptTrace.single {
            "role:query_subject" in it.matchedFeatureIds
        }.neuronId
        val forwardObject = forward.batch.conceptTrace.single {
            "role:query_object" in it.matchedFeatureIds
        }.neuronId
        val reverseSubject = reverse.batch.conceptTrace.single {
            "role:query_subject" in it.matchedFeatureIds
        }.neuronId
        val reverseObject = reverse.batch.conceptTrace.single {
            "role:query_object" in it.matchedFeatureIds
        }.neuronId

        assertNotEquals(forwardSubject, forwardObject)
        assertNotEquals(forwardSubject, reverseObject)
        assertEquals("alpha", forward.batch.conceptTrace.single {
            "role:query_subject" in it.matchedFeatureIds
        }.matchedAlias)
        assertEquals("alpha", reverse.batch.conceptTrace.single {
            "role:query_object" in it.matchedFeatureIds
        }.matchedAlias)
        assertEquals("beta", forward.batch.conceptTrace.single {
            "role:query_object" in it.matchedFeatureIds
        }.matchedAlias)
        assertEquals("beta", reverse.batch.conceptTrace.single {
            "role:query_subject" in it.matchedFeatureIds
        }.matchedAlias)
    }

    @Test
    fun emitsRoleAndProvenanceForFactsAndQuery() {
        val result = adapter.createBatch(
            "structured-2",
            """Use only the supplied facts. Do not assume unstated relations.

Facts:
- alpha is connected to beta.
- beta is explicitly not connected to gamma.

Question: What is the evidence status of 'connected' from alpha to beta? Answer exactly YES, NO, or UNKNOWN.""",
            20L,
        )

        assertEquals(2, result.parsedFactCount)
        assertTrue(result.queryParsed)
        assertTrue(result.batch.conceptTrace.any { "role:fact_subject" in it.matchedFeatureIds })
        assertTrue(result.batch.conceptTrace.any { "role:fact_relation" in it.matchedFeatureIds })
        assertTrue(result.batch.conceptTrace.any { "role:fact_object" in it.matchedFeatureIds })
        assertTrue(result.batch.conceptTrace.any { "role:query_relation" in it.matchedFeatureIds })
        assertEquals(result.activatedFeatureCount, result.batch.events.size)
        assertEquals(result.activatedFeatureCount, result.batch.conceptTrace.size)
        assertTrue(result.batch.events.all { !it.coreTainted && it.strength == 1.0 })
        assertTrue(result.batch.conceptTrace.all { it.provenanceRef.startsWith("benchmark-structured:") })
    }

    @Test
    fun parsesDirectedEdgesAndEvidencePolarity() {
        val edge = adapter.createBatch("structured-3", """Facts:
- Directed link: alpha -> beta.

Question: Is there a directed path of at most two links from alpha to beta? Treat the listed graph as complete. Answer exactly YES or NO.""", 30L)
        val evidence = adapter.createBatch("structured-4", """Facts:
- Evidence refutes the claim that alpha connects to beta.

Question: Classify the evidence about the claim that alpha connects to beta. Answer exactly YES, NO, CONFLICT, or UNKNOWN.""", 40L)

        assertTrue(edge.batch.conceptTrace.any { "value:directed_link" in it.matchedFeatureIds })
        assertTrue(evidence.batch.conceptTrace.any { "value:evidence_refutes" in it.matchedFeatureIds })
    }

    @Test
    fun deduplicatesRepeatedRoleValueWithoutInventingCollision() {
        val result = adapter.createBatch(
            "structured-5",
            """Facts:
- alpha is connected to beta.
- alpha is connected to beta.

Question: What is the evidence status of 'connected' from alpha to beta? Answer exactly YES, NO, or UNKNOWN.""",
            50L,
        )
        assertEquals(2, result.parsedFactCount)
        assertEquals(6, result.encodedFeatureCount)
        assertEquals(0, result.slotCollisionCount)
        assertEquals(result.encodedFeatureCount, result.activatedFeatureCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownFactSyntaxInsteadOfFlatteningIt() {
        adapter.createBatch("structured-6", """Facts:
- alpha maybe related to beta.

Question: What is the evidence status of 'connected' from alpha to beta? Answer exactly YES, NO, or UNKNOWN.""", 60L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedQuestionSyntax() {
        adapter.createBatch("structured-7", """Facts:
- No evidence.

Question: Is alpha related to beta?""", 70L)
    }

    @Test
    fun staysInsideReservedConceptInputNeurons() {
        val result = adapter.createBatch("structured-8", input("alpha", "beta"), 80L)
        assertTrue(result.batch.events.all {
            it.targetNeuronId in ANS100StructuredBenchmarkInputAdapter.INPUT_START until
                ANS100StructuredBenchmarkInputAdapter.INPUT_END_EXCLUSIVE
        })
        assertFalse(result.batch.events.any { it.coreTainted })
    }

    private fun input(subject: String, objectValue: String) = """Use only the supplied facts. Do not assume unstated relations.

Facts:
- $subject is connected to $objectValue.

Question: What is the evidence status of 'connected' from $subject to $objectValue? Answer exactly YES, NO, or UNKNOWN."""
}
