package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidJnvM250kEpisodeJournalInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun openEpisodeAndExactCandidatesSurviveAttributionRecreation() {
        context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        val credited = mutableSetOf<String>()
        val first = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids ->
                credited += ids
                AndroidJnvM250kRewardResult(id, ids.size, false, 10L)
            },
        )
        first.beginEpisode("persist-${UUID.randomUUID().toString().replace("-", "")}", "action-search")
        first.recordRun(runResult(listOf(link("edge-a"), link("edge-b"), link("edge-a"))))
        first.close()

        val recovered = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids ->
                credited += ids
                AndroidJnvM250kRewardResult(id, ids.size, false, 10L)
            },
        )
        try {
            assertTrue(recovered.hasActiveEpisode())
            assertEquals(2, recovered.trackedConnectionCount())
            val result = recovered.finishEpisode(0.5)
            assertEquals(setOf("edge-a", "edge-b"), credited)
            assertEquals(3L, result.observedLinks)
            assertEquals(listOf("edge-a", "edge-b", "edge-a"), result.signalPathConnectionIds)
        } finally {
            recovered.close()
            context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        }
    }

    @Test
    fun rewardPendingEpisodeCanRetryAfterProcessRecreation() {
        context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        val first = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { _, _, _ -> error("simulate interruption during reward application") },
        )
        first.beginEpisode("retry-${UUID.randomUUID().toString().replace("-", "")}", "action-write")
        first.recordRun(runResult(listOf(link("edge-retry"))))
        val failure = runCatching { first.finishEpisode(-0.25) }.exceptionOrNull()
        assertTrue(failure != null)
        first.close()

        var replayedRewardId = ""
        val recovered = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, reward, ids ->
                replayedRewardId = id
                assertEquals(-0.25, reward, 0.0)
                assertEquals(setOf("edge-retry"), ids)
                AndroidJnvM250kRewardResult(id, ids.size, true, 20L)
            },
        )
        try {
            assertTrue(recovered.hasActiveEpisode())
            val result = recovered.finishEpisode(-0.25)
            assertEquals("episode-retry-", replayedRewardId.take("episode-retry-".length))
            assertEquals(setOf("edge-retry"), result.creditedConnectionIds)
            assertTrue(!recovered.hasActiveEpisode())
        } finally {
            recovered.close()
            context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        }
    }

    private fun link(id: String) = AndroidJnvM250kCausalLink(
        connectionId = id,
        sourceNeuronId = 1,
        targetNeuronId = 2,
        timestampNs = 10L,
    )

    private fun runResult(links: List<AndroidJnvM250kCausalLink>) = AndroidJnvM250kRunResult(
        processedEvents = 1,
        processedNeuronBatches = 1,
        emittedSpikes = links.distinctBy { it.targetNeuronId }.map {\n            AndroidJnvM250kSpike(it.targetNeuronId, 1L, it.arrivalTimestampNs + 1L)\n        },
        pendingEvents = 0,
        budgetExhausted = false,
        currentTimeNs = maxOf(10L, links.maxOfOrNull { it.arrivalTimestampNs + 1L } ?: 10L),
        residentNeuronCount = 1,
        hotSetCapacity = 8,
        computeBackend = "instrumented-test",
        causalLinks = links,
    )
}
