package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100NeuralAssociationDecoderTest {
    private fun trace(conceptId: String, neuronId: Int, label: String) =
        ANS100ConceptNeuralInputAdapter.TraceEntry(
            conceptId = conceptId,
            neuronId = neuronId,
            matchedAlias = label,
            matchedFeatureIds = listOf("feature:$conceptId"),
            activationHint = 1.0,
            provenanceRef = "turn:test:concept:$conceptId",
        )

    private fun convergentPair(): Triple<Int, Int, Set<Int>> {
        for (left in ANS100ConceptMemory.NEURAL_INPUT_START until
            ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
        ) {
            for (right in left + 1 until
                ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
            ) {
                val leftTargets = AndroidHybridTopologyV2.conceptAssociationTargets(left)
                val rightTargets = AndroidHybridTopologyV2.conceptAssociationTargets(right)
                val shared = leftTargets.intersect(rightTargets)
                if (shared.isNotEmpty() &&
                    (leftTargets - shared).isNotEmpty() &&
                    (rightTargets - shared).isNotEmpty()
                ) return Triple(left, right, shared)
            }
        }
        error("expected at least one convergent concept projection pair")
    }

    @Test
    fun emitsCandidateOnlyForSharedAssociationNeuronThatActuallySpiked() {
        val (left, right, shared) = convergentPair()
        val result = ANS100NeuralAssociationDecoder().decode(
            conceptTrace = listOf(trace("concept:a", left, "Собака"), trace("concept:b", right, "Животное")),
            emittedSpikeNeuronIds = shared,
        )

        assertEquals(2, result.activeConceptCount)
        assertEquals(shared.size, result.associationSpikeCount)
        assertEquals(1, result.consideredPairCount)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.single()
        assertEquals("Собака", candidate.firstLabel)
        assertEquals("Животное", candidate.secondLabel)
        assertEquals(shared.sorted(), candidate.convergentNeuronIds)
        assertTrue(candidate.evidenceRefs.any { it.contains("concept:concept:a") })
        assertTrue(candidate.evidenceRefs.any { it.contains("concept:concept:b") })
        assertTrue(candidate.activationHint in 0.0..1.0)
    }

    @Test
    fun noAssociationSpikeMeansNoNeuralCandidate() {
        val (left, right, shared) = convergentPair()
        val unrelated = (1_200 until 2_200).first { it !in shared &&
            it !in AndroidHybridTopologyV2.conceptAssociationTargets(left) &&
            it !in AndroidHybridTopologyV2.conceptAssociationTargets(right)
        }
        val result = ANS100NeuralAssociationDecoder().decode(
            conceptTrace = listOf(trace("a", left, "A"), trace("b", right, "B")),
            emittedSpikeNeuronIds = setOf(unrelated),
        )

        assertEquals(0, result.activeConceptCount)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun separateConceptProjectionsDoNotCreatePairWithoutConvergence() {
        val (left, right, shared) = convergentPair()
        val leftOnly = AndroidHybridTopologyV2.conceptAssociationTargets(left) - shared
        val rightOnly = AndroidHybridTopologyV2.conceptAssociationTargets(right) - shared
        val result = ANS100NeuralAssociationDecoder().decode(
            conceptTrace = listOf(trace("a", left, "A"), trace("b", right, "B")),
            emittedSpikeNeuronIds = leftOnly + rightOnly,
        )

        assertTrue(result.candidates.isEmpty())
        assertFalse(result.associationSpikeCount < 0)
    }

    @Test
    fun candidateIdentityAndOrderingAreDeterministicAndOutputIsBounded() {
        val (left, right, shared) = convergentPair()
        val trace = listOf(trace("a", left, "A"), trace("b", right, "B"))
        val decoder = ANS100NeuralAssociationDecoder()
        val first = decoder.decode(trace, shared)
        val second = decoder.decode(trace, shared)
        val disabled = decoder.decode(trace, shared, maxCandidates = 0)

        assertEquals(first.candidates, second.candidates)
        assertEquals(1, first.candidates.size)
        assertTrue(first.candidates.single().candidateId.startsWith("neural-association:"))
        assertTrue(disabled.candidates.isEmpty())
        assertEquals(ANS100NeuralAssociationDecoder.MAX_CANDIDATES, 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateConceptIdentityIsRejected() {
        val (left, _, shared) = convergentPair()
        val duplicate = trace("same", left, "A")
        ANS100NeuralAssociationDecoder().decode(listOf(duplicate, duplicate.copy(neuronId = left + 1)), shared)
    }
}
