package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kRewardAttributionTest {
    @Test
    fun creditsOnlyEndpointsOfObservedTransmissionsAndCapsCreditSet() {
        var receivedIds = emptySet<Int>()
        var receivedRewardId = ""
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { rewardId, _, neuronIds ->
                receivedRewardId = rewardId
                receivedIds = neuronIds
                AndroidJnvM250kRewardResult(rewardId, neuronIds.size, false, 10L)
            },
            maxCreditedNeurons = 3,
        )
        attribution.beginEpisode("episode-17", "action-search")
        attribution.recordRun(
            runResult(
                links = listOf(
                    link("edge-a", 1, 2),
                    link("edge-b", 1, 3),
                    link("edge-c", 4, 5),
                ),
                omitted = 2,
            ),
        )

        val result = attribution.finishEpisode(0.75)

        assertEquals("episode-episode-17", receivedRewardId)
        assertEquals(linkedSetOf(1, 2, 3), receivedIds)
        assertEquals(receivedIds, result.creditedNeuronIds)
        assertEquals(3L, result.observedLinks)
        assertEquals(2L, result.omittedLinks)
        assertEquals("action-search", result.actionId)
        assertFalse(attribution.hasActiveEpisode())
    }

    @Test
    fun noTransmissionMeansNoRewardAndEpisodeRemainsOpen() {
        var rewardCalls = 0
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids ->
                rewardCalls++
                AndroidJnvM250kRewardResult(id, ids.size, false, 0L)
            },
        )
        attribution.beginEpisode("empty-episode", "action-id")
        attribution.recordRun(runResult(emptyList(), omitted = 4))

        val failure = runCatching { attribution.finishEpisode(1.0) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, rewardCalls)
        assertTrue(attribution.hasActiveEpisode())
    }

    @Test
    fun failedRewardApplicationKeepsEpisodeAvailableForRetry() {
        var calls = 0
        val receivedRewardIds = ArrayList<String>()
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids ->
                calls++
                receivedRewardIds += id
                if (calls == 1) error("temporary store failure")
                AndroidJnvM250kRewardResult(id, ids.size, false, 5L)
            },
        )
        attribution.beginEpisode("retry-episode", "action-write")
        attribution.recordRun(runResult(listOf(link("edge", 7, 8))))

        assertTrue(runCatching { attribution.finishEpisode(-0.5) }.isFailure)
        assertTrue(attribution.hasActiveEpisode())
        val result = attribution.finishEpisode(-0.5)

        assertEquals(listOf("episode-retry-episode", "episode-retry-episode"), receivedRewardIds)
        assertEquals(linkedSetOf(7, 8), result.creditedNeuronIds)
        assertFalse(attribution.hasActiveEpisode())
    }

    @Test
    fun cannotOpenTwoEpisodesAtOnceAndAbortClearsCurrentEpisode() {
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> AndroidJnvM250kRewardResult(id, ids.size, false, 0L) },
        )
        attribution.beginEpisode("first", "action")
        assertTrue(runCatching { attribution.beginEpisode("second", "action") }.isFailure)
        attribution.abortEpisode()
        assertFalse(attribution.hasActiveEpisode())
        attribution.beginEpisode("second", "action")
        assertTrue(attribution.hasActiveEpisode())
    }

    @Test
    fun candidateMemoryStaysBoundedAndRetainsFrequentlyTransmittingNeuron() {
        var credited = emptySet<Int>()
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids ->
                credited = ids
                AndroidJnvM250kRewardResult(id, ids.size, false, 0L)
            },
            maxCreditedNeurons = 2,
            maxTrackedNeurons = 3,
        )
        attribution.beginEpisode("bounded-episode", "action")
        val links = (200..230).mapIndexed { index, target ->
            link("edge-$index", 100, target)
        }
        attribution.recordRun(runResult(links))

        assertEquals(3, attribution.trackedCandidateCount())
        val result = attribution.finishEpisode(1.0)
        assertEquals(2, result.creditedNeuronIds.size)
        assertTrue("frequent source neuron must survive bounded replacement", 100 in credited)
    }

    @Test
    fun rejectsUnsafeEpisodeIdentifiers() {
        val attribution = AndroidJnvM250kRewardAttribution(
            applyReward = { id, _, ids -> AndroidJnvM250kRewardResult(id, ids.size, false, 0L) },
        )
        assertTrue(runCatching { attribution.beginEpisode("bad/id", "action") }.isFailure)
    }

    private fun link(id: String, source: Int, target: Int) =
        AndroidJnvM250kCausalLink(id, source, target, 10L)

    private fun runResult(
        links: List<AndroidJnvM250kCausalLink>,
        omitted: Int = 0,
    ) = AndroidJnvM250kRunResult(
        processedEvents = 1,
        processedNeuronBatches = 1,
        emittedSpikes = emptyList(),
        pendingEvents = 0,
        budgetExhausted = false,
        currentTimeNs = 10L,
        residentNeuronCount = 1,
        hotSetCapacity = 8,
        computeBackend = "test",
        causalLinks = links,
        omittedCausalLinks = omitted,
    )
}
