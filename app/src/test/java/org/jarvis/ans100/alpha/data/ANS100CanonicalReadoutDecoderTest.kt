package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100CanonicalReadoutDecoderTest {
    @Test
    fun fourOutputGroupsAreDisjointAndCoverReservedRange() {
        val groups = ANS100CanonicalReadoutContract.outputGroups
        assertEquals(4, groups.size)
        assertEquals(600, groups.sumOf { it.size })
        assertEquals(
            (ANS100CanonicalReadoutContract.OUTPUT_START until ANS100CanonicalReadoutContract.OUTPUT_END).toSet(),
            groups.flatMap { it.startInclusive until it.endExclusive }.toSet(),
        )
        for (left in groups.indices) for (right in left + 1 until groups.size) {
            assertFalse(groups[left].startInclusive < groups[right].endExclusive &&
                groups[right].startInclusive < groups[left].endExclusive)
        }
    }

    @Test
    fun coreCandidateAndLegacyOutputNeuronsCannotBecomeReadoutInputs() {
        assertTrue(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(1_062))
        assertTrue(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(1_500))
        assertTrue(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(4_100))
        assertFalse(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(1_055))
        assertFalse(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(1_056))
        assertFalse(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(2_200))
        assertFalse(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(4_300))
        assertFalse(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(5_800))
        assertFalse(ANS100CanonicalReadoutContract.isEligibleSourceNeuron(9_400))
    }

    @Test(expected = IllegalArgumentException::class)
    fun coreCandidateInputIsRejectedAtContractBoundary() {
        ANS100CanonicalReadoutContract.validateSourceNeuronIds(listOf(1_056))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sourceNeuronDuplicatesAreRejected() {
        ANS100CanonicalReadoutContract.validateSourceNeuronIds(listOf(1_062, 1_062))
    }

    @Test
    fun canonicalSynapseTargetIsStableAndInsideItsClassGroup() {
        ANS100CanonicalReadoutContract.Answer.values().forEach { answer ->
            val first = ANS100CanonicalReadoutContract.outputNeuronId(answer, 1_062)
            val second = ANS100CanonicalReadoutContract.outputNeuronId(answer, 1_062)
            assertEquals(first, second)
            assertTrue(ANS100CanonicalReadoutContract.outputGroup(answer).contains(first))
        }
    }

    @Test
    fun staticTopologyDoesNotFeedCanonicalOutputsFromCoreCandidateLanes() {
        val connections = AndroidHybridTopologyV2.connections()
        assertEquals(AndroidHybridTopologyV2.EXPECTED_CONNECTION_COUNT, connections.size)
        assertTrue(connections.none {
            it.targetNeuronId in ANS100CanonicalReadoutContract.OUTPUT_START until
                ANS100CanonicalReadoutContract.OUTPUT_END
        })
        assertTrue(connections.filter { it.connectionId.contains("-working-to-goal-") }.all {
            it.targetNeuronId in 8_500 until 8_800
        })
    }

    @Test
    fun noCanonicalOutputSpikesMeansNoSignalEvenIfLegacyCoreLaneSpikes() {
        val legacyOnly = setOf(9_400, 9_550, 9_999)
        val result = ANS100CanonicalReadoutDecoder().decode(legacyOnly)
        assertEquals(null, result.answer)
        assertEquals(ANS100CanonicalReadoutDecoder.Result.Status.NO_LEARNED_SIGNAL, result.status)
        assertEquals("NOT_CALIBRATED", result.calibrationStatus)
    }

    @Test
    fun uniqueCanonicalWinnerIsReportedOnlyAsUncalibratedExperiment() {
        val yes = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.YES)
        val no = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.NO)
        val spikes = setOf(yes.startInclusive, yes.startInclusive + 1, no.startInclusive)
        val result = ANS100CanonicalReadoutDecoder().decode(spikes)
        assertEquals(ANS100CanonicalReadoutContract.Answer.YES, result.answer)
        assertEquals(ANS100CanonicalReadoutDecoder.Result.Status.UNCALIBRATED_WINNER, result.status)
        assertEquals(2, result.spikeCounts[ANS100CanonicalReadoutContract.Answer.YES])
        assertEquals(1, result.marginSpikeCount)
        assertEquals("NOT_CALIBRATED", result.calibrationStatus)
    }

    @Test
    fun tiedCanonicalGroupsAbstainInsteadOfInventingConflict() {
        val yes = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.YES)
        val no = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.NO)
        val result = ANS100CanonicalReadoutDecoder().decode(
            setOf(yes.startInclusive, yes.startInclusive + 1, no.startInclusive, no.startInclusive + 1),
        )
        assertEquals(null, result.answer)
        assertEquals(ANS100CanonicalReadoutDecoder.Result.Status.AMBIGUOUS_TIE, result.status)
    }

    @Test
    fun singleWeakSpikeDoesNotBecomeCanonicalAnswer() {
        val yes = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.YES)
        val result = ANS100CanonicalReadoutDecoder().decode(setOf(yes.startInclusive))
        assertEquals(null, result.answer)
        assertEquals(ANS100CanonicalReadoutDecoder.Result.Status.BELOW_MINIMUM_ACTIVITY, result.status)
    }
}
