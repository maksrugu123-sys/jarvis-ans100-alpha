package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100StructuredEvidenceVerifierTest {
    private val adapter = ANS100StructuredBenchmarkInputAdapter()
    private val verifier = ANS100StructuredEvidenceVerifier()

    @Test
    fun distinguishesPositiveNegativeUnknownAndConflict() {
        assertEquals("YES", audit("- alpha is connected to beta.", "alpha", "beta"))
        assertEquals("NO", audit("- alpha is explicitly not connected to beta.", "alpha", "beta"))
        assertEquals("UNKNOWN", audit("- gamma is connected to beta.", "alpha", "beta"))
        assertEquals(
            "CONFLICT",
            audit(
                "- alpha is connected to beta.\n- alpha is explicitly not connected to beta.",
                "alpha",
                "beta",
            ),
        )
    }

    @Test
    fun verifiesTwoHopDirectedPathWithoutUsingNeuralReadout() {
        val result = adapter.createBatch(
            "audit-path",
            """Use only the supplied facts. Do not assume unstated relations.

Facts:
- Directed link: alpha -> beta.
- Directed link: beta -> gamma.

Question: Is there a directed path of at most two links from alpha to gamma? Treat the listed graph as complete. Answer exactly YES or NO.""",
            100L,
        )
        val audit = verifier.verify(result)
        assertEquals("YES", audit.answer)
        assertEquals("complete_directed_graph_path_at_most_two", audit.method)
        assertTrue(audit.supportingEvidenceRefs.isNotEmpty())
    }

    @Test
    fun doesNotTreatReverseEdgeAsForwardPath() {
        val result = adapter.createBatch(
            "audit-reverse",
            """Use only the supplied facts. Do not assume unstated relations.

Facts:
- Directed link: beta -> alpha.

Question: Is there a directed path of at most two links from alpha to beta? Treat the listed graph as complete. Answer exactly YES or NO.""",
            200L,
        )
        assertEquals("NO", verifier.verify(result).answer)
    }

    private fun audit(facts: String, subject: String, objectValue: String): String {
        val result = adapter.createBatch(
            "audit-${subject}-${objectValue}-${facts.length}",
            """Use only the supplied facts. Do not assume unstated relations.

Facts:
$facts

Question: What is the evidence status of 'connected' from $subject to $objectValue? Answer exactly YES, NO, or UNKNOWN.""",
            300L,
        )
        return verifier.verify(result).answer
    }
}
