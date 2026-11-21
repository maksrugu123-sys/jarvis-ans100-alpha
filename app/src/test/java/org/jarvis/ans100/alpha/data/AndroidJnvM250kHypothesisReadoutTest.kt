package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kHypothesisReadoutTest {
    private fun encoding(): AndroidJnvM250kEvidenceEncoder.Encoding {
        val turnId = "jnvm-readout-test"
        val evidence = TypedEvidenceBundle(
            turnId = turnId,
            records = listOf(
                TypedEvidenceRecord(
                    recordId = "feature-a:0",
                    turnId = turnId,
                    feature = EvidenceFeature.USER_MESSAGE_TEXT,
                    source = EvidenceSourceKind.USER_MESSAGE,
                    status = EvidenceStatus.USER_PROVIDED,
                    value = EvidenceValue.Text("data-a"),
                    activation = 1.0,
                    provenanceRef = "turn:$turnId:field-a",
                ),
                TypedEvidenceRecord(
                    recordId = "feature-b:0",
                    turnId = turnId,
                    feature = EvidenceFeature.CORE_INTENT_HINT,
                    source = EvidenceSourceKind.CORE_UNDERSTANDING,
                    status = EvidenceStatus.HEURISTIC,
                    value = EvidenceValue.Category("explain"),
                    activation = 0.8,
                    provenanceRef = "turn:$turnId:field-b",
                ),
            ),
        )
        return AndroidJnvM250kEvidenceEncoder().encode(evidence, inputTimeNs = 10L)
    }

    private fun readout(
        ambiguous: Set<Int> = emptySet(),
        omitted: Int = 0,
    ): AndroidJnvM250kHypothesisReadout.Result {
        val encoding = encoding()
        val sourceIds = encoding.recordToNeuronId.values.sorted()
        val target = 70_123
        val spikes = sourceIds.mapIndexed { index, neuronId ->
            AndroidJnvM250kSpike(neuronId, 1L, 10L + index)
        } + AndroidJnvM250kSpike(target, 1L, 20L)
        val links = sourceIds.mapIndexed { index, neuronId ->
            AndroidJnvM250kCausalLink(
                connectionId = "edge-$index",
                sourceNeuronId = neuronId,
                targetNeuronId = target,
                timestampNs = 10L + index,
                arrivalTimestampNs = 20L,
            )
        }
        return AndroidJnvM250kHypothesisReadout().decode(
            encoding = encoding,
            emittedSpikes = spikes,
            causalLinks = links,
            omittedCausalLinks = omitted,
            ambiguousTargetSpikeNeuronIds = ambiguous,
        )
    }

    @Test
    fun requiresTwoRealTransmissionsAndMatchingTargetSpike() {
        val result = readout()
        assertEquals(2, result.activeConceptIds.size)
        assertEquals(1, result.candidates.size)
        assertEquals(1, result.candidates.single().convergentNeuronIds.singleOrNull()?.let { 1 } ?: 0)
        assertTrue(result.pairScanComplete)
        assertEquals(2, result.candidates.single().activatedConnectionIds.size)
    }

    @Test
    fun ambiguousTargetSpikeCannotCreateCandidateOrNegativeVerification() {
        val result = readout(ambiguous = setOf(70_123))
        assertTrue(result.candidates.isEmpty())
        assertFalse(result.pairScanComplete)
    }

    @Test
    fun omittedTransmissionsMakePairScanIncomplete() {
        val result = readout(omitted = 1)
        assertEquals(1, result.candidates.size)
        assertFalse(result.pairScanComplete)
    }

    @Test
    fun 250kHypothesesTrackIndependentReproductionAndNonReproduction() {
        val first = readout()
        val engine = ANS100NeuralHypothesisEngine()
        val generated = engine.observe250kTurn(
            turnId = "turn-one",
            activeConceptIds = first.activeConceptIds,
            candidates = first.candidates,
            completed = true,
            pairScanComplete = first.pairScanComplete,
        )
        assertEquals(ANS100NeuralHypothesisEngine.Status.GENERATED, generated.single().status)

        val second = engine.observe250kTurn(
            turnId = "turn-two",
            activeConceptIds = first.activeConceptIds,
            candidates = emptyList(),
            completed = true,
            pairScanComplete = true,
        )
        assertEquals(
            ANS100NeuralHypothesisEngine.Status.MIXED_REPRODUCTION_SIGNAL,
            second.single().status,
        )
    }
}
