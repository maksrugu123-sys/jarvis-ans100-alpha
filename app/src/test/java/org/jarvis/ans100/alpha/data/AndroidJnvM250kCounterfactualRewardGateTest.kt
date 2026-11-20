package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kCounterfactualRewardGateTest {
    private fun effect(
        id: String,
        delta: Int,
        sensitivity: Double,
        baselineComplete: Boolean = true,
        counterfactualComplete: Boolean = true,
    ) = AndroidJnvM250kCounterfactualEvaluator.CandidateEffect(
        connectionId = id,
        baselineOutcomeSpikes = if (delta >= 0) delta else 0,
        counterfactualOutcomeSpikes = if (delta >= 0) 0 else -delta,
        outcomeSpikeDelta = delta,
        sensitivityScore = sensitivity,
        baselineComplete = baselineComplete,
        counterfactualComplete = counterfactualComplete,
    )

    private fun report(
        vararg effects: AndroidJnvM250kCounterfactualEvaluator.CandidateEffect,
        baselineComplete: Boolean = true,
    ) = AndroidJnvM250kCounterfactualEvaluator.Report(
        candidates = effects.toList(),
        baselineProcessedEvents = 4,
        baselineProcessedTimestamps = 2,
        baselineComplete = baselineComplete,
    )

    @Test
    fun positiveRewardAcceptsOnlyEdgesWhoseRemovalReducesOutcome() {
        val decision = AndroidJnvM250kCounterfactualRewardGate.evaluate(
            reward = 1.0,
            responseScores = mapOf("edge-good" to 0.8, "edge-bad" to 0.9),
            report = report(effect("edge-good", delta = 2, sensitivity = 0.5),
                effect("edge-bad", delta = -2, sensitivity = 0.9)),
        )
        assertEquals(mapOf("edge-good" to 0.4), decision.acceptedCredits)
        assertEquals(
            AndroidJnvM250kCounterfactualRewardGate.Rejection.WRONG_OUTCOME_DIRECTION,
            decision.rejected["edge-bad"],
        )
        assertTrue(decision.mayApplyReward)
    }

    @Test
    fun negativeRewardRequiresOppositeOutcomeDirection() {
        val decision = AndroidJnvM250kCounterfactualRewardGate.evaluate(
            reward = -0.5,
            responseScores = mapOf("edge-harmful" to 1.0),
            report = report(effect("edge-harmful", delta = -1, sensitivity = 0.75)),
        )
        assertEquals(mapOf("edge-harmful" to 0.75), decision.acceptedCredits)
    }

    @Test
    fun incompleteBaselineRejectsEveryCredit() {
        val decision = AndroidJnvM250kCounterfactualRewardGate.evaluate(
            reward = 1.0,
            responseScores = mapOf("edge" to 1.0),
            report = report(effect("edge", 1, 1.0), baselineComplete = false),
        )
        assertFalse(decision.baselineAccepted)
        assertFalse(decision.mayApplyReward)
        assertTrue(decision.acceptedCredits.isEmpty())
        assertEquals(
            AndroidJnvM250kCounterfactualRewardGate.Rejection.BASELINE_INCOMPLETE,
            decision.rejected["edge"],
        )
    }

    @Test
    fun incompleteCandidateAndWeakEvidenceAreRejected() {
        val decision = AndroidJnvM250kCounterfactualRewardGate.evaluate(
            reward = 1.0,
            responseScores = mapOf("edge-incomplete" to 1.0, "edge-weak" to 0.1, "edge-missing" to 0.9),
            report = report(
                effect("edge-incomplete", 1, 1.0, counterfactualComplete = false),
                effect("edge-weak", 1, 0.2),
            ),
        )
        assertTrue(decision.acceptedCredits.isEmpty())
        assertEquals(
            AndroidJnvM250kCounterfactualRewardGate.Rejection.REPLAY_INCOMPLETE,
            decision.rejected["edge-incomplete"],
        )
        assertEquals(
            AndroidJnvM250kCounterfactualRewardGate.Rejection.COMBINED_CONFIDENCE_TOO_LOW,
            decision.rejected["edge-weak"],
        )
        assertEquals(
            AndroidJnvM250kCounterfactualRewardGate.Rejection.CANDIDATE_MISSING,
            decision.rejected["edge-missing"],
        )
    }

    @Test
    fun zeroRewardNeverProducesLearningCredits() {
        val decision = AndroidJnvM250kCounterfactualRewardGate.evaluate(
            reward = 0.0,
            responseScores = mapOf("edge" to 1.0),
            report = report(effect("edge", 2, 1.0)),
        )
        assertFalse(decision.mayApplyReward)
        assertTrue(decision.acceptedCredits.isEmpty())
        assertEquals(
            AndroidJnvM250kCounterfactualRewardGate.Rejection.ZERO_REWARD,
            decision.rejected["edge"],
        )
    }
}
