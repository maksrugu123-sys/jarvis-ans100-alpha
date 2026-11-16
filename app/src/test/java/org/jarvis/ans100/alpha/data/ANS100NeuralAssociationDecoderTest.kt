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

    private fun connectionsFor(neuronId: Int, targets: Set<Int>): Set<String> {
        val projection = AndroidHybridTopologyV2.conceptAssociationProjection(neuronId)
        return targets.map { projection.getValue(it) }.toSet()
    }

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
            emittedSpikeNeuronIds = shared + setOf(left, right),
            activatedConnectionIds = connectionsFor(left, shared) + connectionsFor(right, shared),
        )

        assertEquals(2, result.activeConceptCount)
        assertEquals(shared.size, result.associationSpikeCount)
        assertEquals(1, result.consideredPairCount)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.single()
        assertEquals("Собака", candidate.firstLabel)
        assertEquals("Животное", candidate.secondLabel)
        assertEquals(shared.sorted(), candidate.convergentNeuronIds)
        assertEquals(shared.size * 2, candidate.activatedConnectionIds.size)
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
            emittedSpikeNeuronIds = setOf(unrelated, left, right),
            activatedConnectionIds = connectionsFor(left, shared) + connectionsFor(right, shared),
        )

        assertEquals(2, result.activeConceptCount)
        assertEquals(setOf("a", "b"), result.observedConceptIds.toSet())
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.candidateSetComplete)
    }

    @Test
    fun aSpikingTargetWithoutBothActivatedInputSynapsesDoesNotCreateCandidate() {
        val (left, right, shared) = convergentPair()
        val missingOneConceptPath = connectionsFor(left, shared)
        val result = ANS100NeuralAssociationDecoder().decode(
            conceptTrace = listOf(trace("a", left, "A"), trace("b", right, "B")),
            emittedSpikeNeuronIds = shared + setOf(left, right),
            activatedConnectionIds = missingOneConceptPath,
        )
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun separateConceptProjectionsDoNotCreatePairWithoutConvergence() {
        val (left, right, shared) = convergentPair()
        val leftOnly = AndroidHybridTopologyV2.conceptAssociationTargets(left) - shared
        val rightOnly = AndroidHybridTopologyV2.conceptAssociationTargets(right) - shared
        val result = ANS100NeuralAssociationDecoder().decode(
            conceptTrace = listOf(trace("a", left, "A"), trace("b", right, "B")),
            emittedSpikeNeuronIds = leftOnly + rightOnly + setOf(left, right),
            activatedConnectionIds = connectionsFor(left, leftOnly) + connectionsFor(right, rightOnly),
        )

        assertTrue(result.candidates.isEmpty())
        assertEquals(2, result.activeConceptCount)
        assertTrue(result.pairScanComplete)
        assertTrue(result.candidateSetComplete)
        assertFalse(result.associationSpikeCount < 0)
    }

    @Test
    fun candidateIdentityAndOrderingAreDeterministicAndOutputIsBounded() {
        val (left, right, shared) = convergentPair()
        val trace = listOf(trace("a", left, "A"), trace("b", right, "B"))
        val decoder = ANS100NeuralAssociationDecoder()
        val activated = connectionsFor(left, shared) + connectionsFor(right, shared)
        val sourceSpikes = shared + setOf(left, right)
        val first = decoder.decode(trace, sourceSpikes, activated)
        val second = decoder.decode(trace, sourceSpikes, activated)
        val disabled = decoder.decode(trace, sourceSpikes, activated, maxCandidates = 0)

        assertEquals(first.candidates, second.candidates)
        assertEquals(1, first.candidates.size)
        assertTrue(first.candidates.single().candidateId.startsWith("neural-association:"))
        assertTrue(disabled.candidates.isEmpty())
        assertEquals(3, ANS100NeuralAssociationDecoder.MAX_CANDIDATES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateConceptIdentityIsRejected() {
        val (left, _, shared) = convergentPair()
        val duplicate = trace("same", left, "A")
        ANS100NeuralAssociationDecoder().decode(
            listOf(duplicate, duplicate.copy(neuronId = left + 1)),
            shared,
            connectionsFor(left, shared),
        )
    }

    @Test
    fun decodesNewGrownSynapsesEvenWhenFixedProjectionsDoNotConverge() {
        val (left, right, _) = convergentPair()
        val fixedTargets = AndroidHybridTopologyV2.conceptAssociationTargets(left) +
            AndroidHybridTopologyV2.conceptAssociationTargets(right)
        val grownTarget = (1_200 until 2_200).first { it !in fixedTargets }
        val grown = listOf(
            AndroidNeuronConnection(
                "ans100-v5-grown-association-test-left",
                left,
                grownTarget,
                1_000_000L,
                0.25,
            ),
            AndroidNeuronConnection(
                "ans100-v5-grown-association-test-right",
                right,
                grownTarget,
                1_000_000L,
                0.25,
            ),
        )
        val result = ANS100NeuralAssociationDecoder().decode(
            conceptTrace = listOf(trace("grown:a", left, "Понятие A"), trace("grown:b", right, "Понятие B")),
            emittedSpikeNeuronIds = setOf(grownTarget, left, right),
            activatedConnectionIds = grown.map { it.connectionId }.toSet(),
            grownConnections = grown,
        )

        assertEquals(1, result.candidates.size)
        assertEquals(listOf(grownTarget), result.candidates.single().convergentNeuronIds)
        assertEquals(grown.map { it.connectionId }.sorted(), result.candidates.single().activatedConnectionIds)
    }

    @Test
    fun outputCapMarksCandidateSetIncomplete() {
        val ids = (ANS100ConceptMemory.NEURAL_INPUT_START until
            ANS100ConceptMemory.NEURAL_INPUT_START + 4).toList()
        val target = 2_199
        val traces = ids.mapIndexed { index, neuronId -> trace("cap.$index", neuronId, "C$index") }
        val grown = ids.mapIndexed { index, neuronId ->
            AndroidNeuronConnection("ans100-v5-grown-association-cap-$index", neuronId, target, 1_000_000L, 0.25)
        }
        val result = ANS100NeuralAssociationDecoder().decode(
            conceptTrace = traces,
            emittedSpikeNeuronIds = ids.toSet() + target,
            activatedConnectionIds = grown.map { it.connectionId }.toSet(),
            grownConnections = grown,
        )
        assertEquals(4, result.inputConceptCount)
        assertEquals(4, result.activeConceptCount)
        assertEquals(6, result.consideredPairCount)
        assertEquals(6, result.candidateCountBeforeLimit)
        assertEquals(3, result.candidates.size)
        assertTrue(result.pairScanComplete)
        assertFalse(result.candidateSetComplete)
    }

    @Test
    fun conceptWithoutItsOwnInputSpikeIsNotEligibleForNegativeObservation() {
        val (left, right, shared) = convergentPair()
        val result = ANS100NeuralAssociationDecoder().decode(
            conceptTrace = listOf(trace("a", left, "A"), trace("b", right, "B")),
            emittedSpikeNeuronIds = shared + setOf(left),
            activatedConnectionIds = connectionsFor(left, shared) + connectionsFor(right, shared),
        )
        assertEquals(listOf("a"), result.observedConceptIds)
        assertEquals(1, result.activeConceptCount)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun candidateIdentitySurvivesDifferentConvergentTargetsAcrossTasks() {
        val (left, right, shared) = convergentPair()
        val traces = listOf(trace("stable:a", left, "A"), trace("stable:b", right, "B"))
        val decoder = ANS100NeuralAssociationDecoder()
        val fixed = decoder.decode(
            conceptTrace = traces,
            emittedSpikeNeuronIds = shared + setOf(left, right),
            activatedConnectionIds = connectionsFor(left, shared) + connectionsFor(right, shared),
        ).candidates.single()

        val fixedTargets = AndroidHybridTopologyV2.conceptAssociationTargets(left) +
            AndroidHybridTopologyV2.conceptAssociationTargets(right)
        val grownTarget = (1_200 until 2_200).first { it !in fixedTargets }
        val grown = listOf(
            AndroidNeuronConnection("ans100-v5-grown-association-stable-left", left, grownTarget, 1_000_000L, 0.25),
            AndroidNeuronConnection("ans100-v5-grown-association-stable-right", right, grownTarget, 1_000_000L, 0.25),
        )
        val reproduced = decoder.decode(
            conceptTrace = traces,
            emittedSpikeNeuronIds = setOf(left, right, grownTarget),
            activatedConnectionIds = grown.map { it.connectionId }.toSet(),
            grownConnections = grown,
        ).candidates.single()

        assertEquals(fixed.candidateId, reproduced.candidateId)
        assertFalse(fixed.convergentNeuronIds == reproduced.convergentNeuronIds)
    }

}
