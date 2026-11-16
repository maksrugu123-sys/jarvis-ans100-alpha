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
    fun coreCandidateSignalsCannotReachAnyCanonicalReadoutSourceThroughStaticTopology() {
        val edges = AndroidHybridTopologyV2.connections()
        val outgoing = edges.groupBy { it.sourceNeuronId }.mapValues { (_, values) ->
            values.map { it.targetNeuronId }
        }
        val queue = java.util.ArrayDeque<Int>()
        val reachable = HashSet<Int>()
        repeat(ANS100CandidateSlotContract.INPUT_SIGNAL_COUNT) { offset ->
            queue.add(ANS100CandidateSlotContract.INPUT_START + offset)
        }
        while (queue.isNotEmpty()) {
            val source = queue.removeFirst()
            if (!reachable.add(source)) continue
            outgoing[source].orEmpty().forEach { target ->
                if (target !in reachable) queue.addLast(target)
            }
        }
        assertTrue(
            "Core-labelled candidate lanes must not reach canonical readout input populations",
            reachable.none(ANS100CanonicalReadoutContract::isEligibleSourceNeuron),
        )
        assertTrue(
            "Core-labelled candidate lanes must not reach the four canonical output groups",
            reachable.none { it in ANS100CanonicalReadoutContract.OUTPUT_START until ANS100CanonicalReadoutContract.OUTPUT_END },
        )
    }

    @Test
    fun denseSourceProjectionHasBoundedThreeOrFourWayFanInPerClass() {
        val sources = (1_062..1_573).toList()
        ANS100CanonicalReadoutContract.Answer.values().forEach { answer ->
            val fanIn = sources.groupingBy {
                ANS100CanonicalReadoutContract.outputNeuronId(answer, it)
            }.eachCount().values
            assertEquals(150, fanIn.size)
            assertTrue(fanIn.all { it in 3..4 })
            assertEquals(62, fanIn.count { it == 4 })
        }
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
    fun coreTaintedOutputSpikesAreExcludedFromCanonicalDecision() {
        val yes = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.YES)
        val no = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.NO)
        val spikes = setOf(yes.startInclusive, yes.startInclusive + 1, no.startInclusive, no.startInclusive + 1)
        val tainted = setOf(no.startInclusive, no.startInclusive + 1)
        val result = ANS100CanonicalReadoutDecoder().decode(spikes, tainted)
        assertEquals(ANS100CanonicalReadoutContract.Answer.YES, result.answer)
        assertEquals(2, result.spikeCounts[ANS100CanonicalReadoutContract.Answer.YES])
        assertEquals(0, result.spikeCounts[ANS100CanonicalReadoutContract.Answer.NO])
    }

    @Test
    fun anEarlierSpikeIsInvalidatedIfItsNeuronBecomesTaintedLaterInTheTurn() {
        val yes = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.YES)
        val earlierSpikes = setOf(yes.startInclusive, yes.startInclusive + 1)
        // The tainted neuron need not be in the set of newly emitted spikes:
        // it may have fired cleanly in an earlier partial run before contamination.
        val result = ANS100CanonicalReadoutDecoder().decode(
            earlierSpikes,
            setOf(yes.startInclusive),
        )
        assertEquals(null, result.answer)
        assertEquals(1, result.spikeCounts[ANS100CanonicalReadoutContract.Answer.YES])
        assertEquals(ANS100CanonicalReadoutDecoder.Result.Status.BELOW_MINIMUM_ACTIVITY, result.status)
    }

    @Test
    fun onlyCoreTaintedCanonicalSpikesProduceNoLearnedSignal() {
        val yes = ANS100CanonicalReadoutContract.outputGroup(ANS100CanonicalReadoutContract.Answer.YES)
        val result = ANS100CanonicalReadoutDecoder().decode(
            setOf(yes.startInclusive, yes.startInclusive + 1),
            setOf(yes.startInclusive, yes.startInclusive + 1),
        )
        assertEquals(null, result.answer)
        assertEquals(ANS100CanonicalReadoutDecoder.Result.Status.NO_LEARNED_SIGNAL, result.status)
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
