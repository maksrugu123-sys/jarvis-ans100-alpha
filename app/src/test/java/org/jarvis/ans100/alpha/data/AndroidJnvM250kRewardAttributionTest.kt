package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kRewardAttributionTest {
    @Test
    fun creditsOnlyObservedConnectionIdsAndPreservesOrderedPath() {
        var received = emptySet<String>()
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids ->
                received = ids
                AndroidJnvM250kRewardResult(id, ids.size, false, 10L)
            },
            maxCreditedConnections = 3,
        )
        attribution.beginEpisode("episode-17", "action-search")
        attribution.recordRun(runResult(listOf(
            link("edge-a", 1, 2), link("edge-b", 2, 3),
            link("edge-a", 1, 2), link("edge-unrelated-neighbor", 3, 4),
        ), omitted = 2))
        val result = attribution.finishEpisode(0.75)
        assertEquals(setOf("edge-a", "edge-b", "edge-unrelated-neighbor"), received)
        assertEquals(listOf("edge-a", "edge-b", "edge-a", "edge-unrelated-neighbor"), result.signalPathConnectionIds)
        assertEquals(received, result.creditedConnectionIds)
        assertEquals(4L, result.observedLinks)
        assertEquals(2L, result.omittedLinks)
        assertEquals("action-search", result.actionId)
        assertFalse(attribution.hasActiveEpisode())
    }

    @Test
    fun unobservedNeighborCannotBeCredited() {
        var received = emptySet<String>()
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> received = ids; AndroidJnvM250kRewardResult(id, ids.size, false, 1L) },
        )
        attribution.beginEpisode("exact-only", "action")
        attribution.recordRun(runResult(listOf(link("transmitted-edge", 10, 11))))
        attribution.finishEpisode(1.0)
        assertEquals(setOf("transmitted-edge"), received)
        assertFalse("neighbor-edge" in received)
    }

    @Test
    fun noTransmissionMeansNoRewardAndEpisodeRemainsOpen() {
        var calls = 0
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> calls++; AndroidJnvM250kRewardResult(id, ids.size, false, 0L) },
        )
        attribution.beginEpisode("empty-episode", "action")
        attribution.recordRun(runResult(emptyList(), omitted = 4))
        assertTrue(runCatching { attribution.finishEpisode(1.0) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, calls)
        assertTrue(attribution.hasActiveEpisode())
    }

    @Test
    fun failedRewardKeepsEpisodeForIdempotentRetry() {
        var calls = 0
        val ids = mutableListOf<String>()
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, selected ->
                calls++; ids += id
                if (calls == 1) error("temporary store failure")
                AndroidJnvM250kRewardResult(id, selected.size, false, 5L)
            },
        )
        attribution.beginEpisode("retry-episode", "action-write")
        attribution.recordRun(runResult(listOf(link("edge", 7, 8))))
        assertTrue(runCatching { attribution.finishEpisode(-0.5) }.isFailure)
        assertTrue(attribution.hasActiveEpisode())
        val result = attribution.finishEpisode(-0.5)
        assertEquals(listOf("episode-retry-episode", "episode-retry-episode"), ids)
        assertEquals(setOf("edge"), result.creditedConnectionIds)
        assertFalse(attribution.hasActiveEpisode())
    }

    @Test
    fun episodeLifecycleAndBoundedTrackingAreEnforced() {
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, selected -> AndroidJnvM250kRewardResult(id, selected.size, false, 0L) },
            maxCreditedConnections = 2, maxTrackedConnections = 3,
        )
        assertTrue(runCatching { attribution.beginEpisode("bad/id", "action") }.isFailure)
        attribution.beginEpisode("bounded", "action")
        assertTrue(runCatching { attribution.beginEpisode("second", "action") }.isFailure)
        val links = List(10) { link("edge-0", 100, 101) } +
            (1..30).map { i -> link("edge-$i", 100, 101+i) }
        attribution.recordRun(runResult(links))
        assertEquals(3, attribution.trackedConnectionCount())
        val result = attribution.finishEpisode(1.0)
        assertEquals(2, result.creditedConnectionIds.size)
        assertTrue("edge-0" in result.creditedConnectionIds)
        assertEquals(3, result.signalPathConnectionIds.size)
        assertEquals(37L, result.omittedLinks)
        attribution.beginEpisode("after", "action")
        attribution.abortEpisode()
        assertFalse(attribution.hasActiveEpisode())
    }

    @Test
    fun rejectsUnsafeConnectionIds() {
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> AndroidJnvM250kRewardResult(id, ids.size, false, 0L) },
        )
        attribution.beginEpisode("safe", "action")
        assertTrue(runCatching { attribution.recordRun(runResult(listOf(link("bad/id", 1, 2)))) }.isFailure)
    }

    @Test
    fun transmissionWithoutTargetResponseCannotReceiveReward() {
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> AndroidJnvM250kRewardResult(id, ids.size, false, 10L) },
        )
        attribution.beginEpisode("no-response", "action")
        attribution.recordRun(runResult(listOf(link("silent-edge", 1, 2)), emitTargetResponses = false))
        assertTrue(runCatching { attribution.finishEpisode(1.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(attribution.hasActiveEpisode())
    }

    @Test
    fun competingInputsSplitOneTargetResponseInsteadOfBothClaimingFullCredit() {
        var received = emptySet<String>()
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> received = ids; AndroidJnvM250kRewardResult(id, ids.size, false, 12L) },
        )
        attribution.beginEpisode("competition", "action")
        attribution.recordRun(runResult(listOf(link("edge-a", 1, 9), link("edge-b", 2, 9))))
        val result = attribution.finishEpisode(0.8)
        assertEquals(setOf("edge-a", "edge-b"), received)
        assertEquals(0.5, result.responseScores.getValue("edge-a"), 0.000001)
        assertEquals(0.5, result.responseScores.getValue("edge-b"), 0.000001)
        assertEquals(1L, result.contestedTargetResponses)
    }

    @Test
    fun unrelatedTargetSpikeDoesNotConfirmTransmission() {
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> AndroidJnvM250kRewardResult(id, ids.size, false, 10L) },
        )
        attribution.beginEpisode("wrong-target", "action")
        attribution.recordRun(runResult(
            listOf(link("edge-a", 1, 2)),
            explicitSpikes = listOf(AndroidJnvM250kSpike(3, 1L, 11L)),
        ))
        assertTrue(runCatching { attribution.finishEpisode(0.5) }.isFailure)
    }


    @Test
    fun weightedRewardReceivesResponseConfidencePerExactConnection() {
        var weighted = emptyMap<String, Double>()
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> error("weighted callback should be used") },
            applyWeightedReward = { id, _, credits ->
                weighted = credits
                AndroidJnvM250kRewardResult(id, credits.size, false, 12L)
            },
        )
        attribution.beginEpisode("weighted", "action")
        attribution.recordRun(runResult(listOf(link("edge-a", 1, 9), link("edge-b", 2, 9))))
        attribution.finishEpisode(0.8)
        assertEquals(0.5, weighted.getValue("edge-a"), 0.000001)
        assertEquals(0.5, weighted.getValue("edge-b"), 0.000001)
    }


    @Test
    fun incompleteTransmissionTraceReducesConfidenceConservatively() {
        var weighted = emptyMap<String, Double>()
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> AndroidJnvM250kRewardResult(id, ids.size, false, 10L) },
            applyWeightedReward = { id, _, credits ->
                weighted = credits
                AndroidJnvM250kRewardResult(id, credits.size, false, 10L)
            },
        )
        attribution.beginEpisode("partial-trace", "action")
        attribution.recordRun(runResult(
            listOf(link("edge-a", 1, 9), link("edge-b", 2, 9)),
            omitted = 2,
        ))
        val result = attribution.finishEpisode(1.0)
        assertEquals(0.5, result.observationCoverage, 0.000001)
        assertEquals(0.25, weighted.getValue("edge-a"), 0.000001)
        assertEquals(0.25, weighted.getValue("edge-b"), 0.000001)
    }

    private fun link(id: String, source: Int, target: Int) = AndroidJnvM250kCausalLink(id, source, target, 10L, 10L)
    private fun runResult(
        links: List<AndroidJnvM250kCausalLink>,
        omitted: Int = 0,
        emitTargetResponses: Boolean = true,
        explicitSpikes: List<AndroidJnvM250kSpike>? = null,
    ): AndroidJnvM250kRunResult {
        val spikes = explicitSpikes ?: if (emitTargetResponses) links.distinctBy { it.targetNeuronId }.map {
            AndroidJnvM250kSpike(it.targetNeuronId, 1L, it.arrivalTimestampNs + 1L)
        } else emptyList()
        return AndroidJnvM250kRunResult(
            processedEvents=1, processedNeuronBatches=1, emittedSpikes=spikes,
            pendingEvents=0, budgetExhausted=false,
            currentTimeNs=maxOf(10L, spikes.maxOfOrNull { it.timestampNs } ?: 10L),
            residentNeuronCount=1, hotSetCapacity=8, computeBackend="test",
            causalLinks=links, omittedCausalLinks=omitted,
        )
    }
}
