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


    @Test
    fun pendingTargetResponseWindowSurvivesAttributionRecreation() {
        context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        val first = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids -> AndroidJnvM250kRewardResult(id, ids.size, false, 11L) },
        )
        first.beginEpisode("pending-${UUID.randomUUID().toString().replace("-", "")}", "action")
        first.recordRun(
            AndroidJnvM250kRunResult(
                processedEvents = 1,
                processedNeuronBatches = 1,
                emittedSpikes = emptyList(),
                pendingEvents = 1,
                budgetExhausted = false,
                currentTimeNs = 10L,
                residentNeuronCount = 1,
                hotSetCapacity = 8,
                computeBackend = "instrumented-test",
                causalLinks = listOf(link("edge-pending")),
            ),
        )
        first.close()

        val credited = mutableSetOf<String>()
        val recovered = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids ->
                credited += ids
                AndroidJnvM250kRewardResult(id, ids.size, false, 11L)
            },
        )
        try {
            recovered.recordRun(
                AndroidJnvM250kRunResult(
                    processedEvents = 1,
                    processedNeuronBatches = 1,
                    emittedSpikes = listOf(AndroidJnvM250kSpike(2, 1L, 11L)),
                    pendingEvents = 0,
                    budgetExhausted = false,
                    currentTimeNs = 11L,
                    residentNeuronCount = 1,
                    hotSetCapacity = 8,
                    computeBackend = "instrumented-test",
                ),
            )
            val result = recovered.finishEpisode(0.5)
            assertEquals(setOf("edge-pending"), credited)
            assertEquals(1.0, result.responseScores.getValue("edge-pending"), 0.000001)
        } finally {
            recovered.close()
            context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        }
    }

    @Test
    fun counterfactualDecisionAndExactCreditsSurviveRewardRetry() {
        context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        val episodeId = "cf-${UUID.randomUUID().toString().replace("-", "")}"
        val first = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids -> error("unweighted reward must never run: $id $ids") },
            applyWeightedReward = { _, _, _ -> error("simulate process interruption after durable decision") },
        )
        first.beginEpisode(episodeId, "action-counterfactual")
        first.recordRun(runResult(listOf(link("edge-gated"))))
        val trustedReport = AndroidJnvM250kCounterfactualEvaluator.Report(
            candidates = listOf(
                AndroidJnvM250kCounterfactualEvaluator.CandidateEffect(
                    connectionId = "edge-gated",
                    baselineOutcomeSpikes = 2,
                    counterfactualOutcomeSpikes = 1,
                    outcomeSpikeDelta = 1,
                    sensitivityScore = 0.5,
                    baselineComplete = true,
                    counterfactualComplete = true,
                ),
            ),
            baselineProcessedEvents = 1,
            baselineProcessedTimestamps = 1,
            baselineComplete = true,
        )
        assertTrue(runCatching {
            first.finishEpisodeWithCounterfactual(0.75, trustedReport)
        }.isFailure)
        first.close()

        var replayedCredits = emptyMap<String, Double>()
        val recovered = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids -> error("unweighted reward must never run: $id $ids") },
            applyWeightedReward = { id, reward, credits ->
                assertEquals("counterfactual-episode-$episodeId", id)
                assertEquals(0.75, reward, 0.0)
                replayedCredits = credits
                AndroidJnvM250kRewardResult(id, credits.size, true, 25L)
            },
        )
        val changedReport = trustedReport.copy(
            candidates = listOf(trustedReport.candidates.single().copy(
                outcomeSpikeDelta = -1,
                counterfactualOutcomeSpikes = 3,
                sensitivityScore = 0.9,
            )),
        )
        try {
            val result = recovered.finishEpisodeWithCounterfactual(0.75, changedReport)
            assertTrue(result.replayedPersistedDecision)
            assertEquals(mapOf("edge-gated" to 0.5), replayedCredits)
            assertEquals(mapOf("edge-gated" to 0.5), result.decision.acceptedCredits)
            assertTrue(!recovered.hasActiveEpisode())
        } finally {
            recovered.close()
            context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        }
    }

    @Test
    fun rejectedCounterfactualDecisionIsTerminalAndNeverCallsReward() {
        context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        val first = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids -> error("rejected decision must not reward: $id $ids") },
            applyWeightedReward = { id, _, credits ->
                error("rejected decision must not reward: $id $credits")
            },
        )
        first.beginEpisode("cf-reject-${UUID.randomUUID().toString().replace("-", "")}", "action")
        first.recordRun(runResult(listOf(link("edge-rejected"))))
        val incomplete = AndroidJnvM250kCounterfactualEvaluator.Report(
            candidates = listOf(
                AndroidJnvM250kCounterfactualEvaluator.CandidateEffect(
                    connectionId = "edge-rejected",
                    baselineOutcomeSpikes = 1,
                    counterfactualOutcomeSpikes = 0,
                    outcomeSpikeDelta = 1,
                    sensitivityScore = 1.0,
                    baselineComplete = false,
                    counterfactualComplete = true,
                ),
            ),
            baselineProcessedEvents = 1,
            baselineProcessedTimestamps = 1,
            baselineComplete = false,
        )
        try {
            val result = first.finishEpisodeWithCounterfactual(1.0, incomplete)
            assertTrue(!result.decision.mayApplyReward)
            assertTrue(result.decision.rejected.values.all {
                it == AndroidJnvM250kCounterfactualRewardGate.Rejection.BASELINE_INCOMPLETE
            })
            assertEquals(null, result.rewardResult)
            assertTrue(!first.hasActiveEpisode())
        } finally {
            first.close()
            context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        }
    }

    @Test
    fun selectedDecisionSnapshotIsPersistedAndAutomaticallyReplayedAfterRestart() {
        context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        val episodeId = "decision-${UUID.randomUUID().toString().replace("-", "")}"
        val decision = ANS100Decision(
            turnId = "turn-counterfactual",
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            status = ANS100DecisionStatus.SELECTED,
            candidates = listOf(
                ANS100Candidate(
                    candidateId = "answer",
                    summary = "Ответить на запрос",
                    supportingEvidenceIds = emptyList(),
                ),
            ),
            selectedCandidateId = "answer",
            responseGoal = "Подготовить ответ",
        )
        val snapshot = AndroidJnvM250kDecisionEpisodeContext(
            decisionTurnId = decision.turnId,
            selectedCandidateId = "answer",
            initialStates = mapOf(
                1 to AndroidNeuronState(1),
                2 to AndroidNeuronState(2),
                3 to AndroidNeuronState(3),
            ),
            connections = listOf(
                AndroidNeuronConnection("edge-outcome", 1, 2, 1L, 1.0),
                AndroidNeuronConnection("edge-unrelated", 1, 3, 1L, 1.0),
            ),
            externalInputs = listOf(AndroidNeuronEvent("decision-input", 1L, 1, 1.0)),
            outcomeNeuronIds = setOf(2),
        )
        val first = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids -> error("unweighted path must not run: $id $ids") },
            applyWeightedReward = { _, _, _ -> error("simulate process death after durable gate decision") },
        )
        first.beginDecisionEpisode(episodeId, decision, snapshot)
        first.recordRun(runResult(listOf(link("edge-outcome"))))
        assertTrue(runCatching { first.finishEpisodeWithCounterfactual(0.8) }.isFailure)
        first.close()

        var replayed = emptyMap<String, Double>()
        val recovered = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids -> error("unweighted path must not run: $id $ids") },
            applyWeightedReward = { id, reward, credits ->
                assertEquals("counterfactual-episode-$episodeId", id)
                assertEquals(0.8, reward, 0.0)
                replayed = credits
                AndroidJnvM250kRewardResult(id, credits.size, true, 30L)
            },
        )
        try {
            val result = recovered.finishEpisodeWithCounterfactual(
                0.8,
                AndroidJnvM250kCounterfactualRewardGate.Policy(minimumSensitivity = 0.9),
            )
            assertTrue(result.replayedPersistedDecision)
            assertEquals(mapOf("edge-outcome" to 1.0), replayed)
            assertEquals(replayed, result.decision.acceptedCredits)
            assertTrue(!recovered.hasActiveEpisode())
        } finally {
            recovered.close()
            context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        }
    }

    @Test
    fun decisionEpisodeRejectsAbstainedOrMismatchedDecisionBeforeOpeningJournal() {
        context.deleteDatabase("jarvis_ans100_episode_journal_v1.db")
        val attribution = AndroidJnvM250kRewardAttribution.persistent(
            context,
            applyReward = { id, _, ids -> AndroidJnvM250kRewardResult(id, ids.size, false, 1L) },
        )
        val contextSnapshot = AndroidJnvM250kDecisionEpisodeContext(
            decisionTurnId = "turn-not-selected",
            selectedCandidateId = "answer",
            initialStates = mapOf(1 to AndroidNeuronState(1), 2 to AndroidNeuronState(2)),
            connections = listOf(AndroidNeuronConnection("edge", 1, 2, 1L, 1.0)),
            externalInputs = listOf(AndroidNeuronEvent("input", 1L, 1, 1.0)),
            outcomeNeuronIds = setOf(2),
        )
        val abstained = ANS100Decision(
            turnId = "turn-not-selected",
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            status = ANS100DecisionStatus.ABSTAINED,
            candidates = listOf(
                ANS100Candidate("answer", "Ответ", emptyList()),
            ),
            abstentionReason = "Недостаточно оснований",
            responseGoal = "Воздержаться",
        )
        try {
            assertTrue(runCatching {
                attribution.beginDecisionEpisode("not-selected", abstained, contextSnapshot)
            }.isFailure)
            assertTrue(!attribution.hasActiveEpisode())
        } finally {
            attribution.close()
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
        emittedSpikes = links.distinctBy { it.targetNeuronId }.map {
            AndroidJnvM250kSpike(it.targetNeuronId, 1L, it.arrivalTimestampNs + 1L)
        },
        pendingEvents = 0,
        budgetExhausted = false,
        currentTimeNs = maxOf(10L, links.maxOfOrNull { it.arrivalTimestampNs + 1L } ?: 10L),
        residentNeuronCount = 1,
        hotSetCapacity = 8,
        computeBackend = "instrumented-test",
        causalLinks = links,
    )
}
