package org.jarvis.ans100.alpha.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ANS100CandidateFeedbackLearnerTest {
    private val learner = ANS100CandidateFeedbackLearner()

    @Test
    fun explicitPositiveFeedbackStrengthensOnlyMatchingTurnAndCandidate() {
        val traces = listOf(
            ANS100CandidateFeedbackLearner.EligibilityTrace("turn-1", "ans100-edge-a", "answer", 1.0),
            ANS100CandidateFeedbackLearner.EligibilityTrace("turn-1", "ans100-edge-b", "clarify", 1.0),
            ANS100CandidateFeedbackLearner.EligibilityTrace("turn-0", "ans100-edge-c", "answer", 1.0),
        )
        val updates = learner.applyFeedback(
            traces,
            mapOf("ans100-edge-a" to 0.5, "ans100-edge-b" to 0.5, "ans100-edge-c" to 0.5),
            ANS100CandidateFeedbackLearner.Feedback(
                "turn-1", "answer", 1.0,
                ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
            ),
        )

        assertEquals(listOf("ans100-edge-a"), updates.map { it.connectionId })
        assertEquals(0.51, updates.single().nextWeight, 0.000001)
    }

    @Test
    fun explicitNegativeFeedbackWeakensEligibleSynapse() {
        val trace = ANS100CandidateFeedbackLearner.EligibilityTrace("turn-2", "ans100-edge-a", "answer", 0.5)
        val update = learner.applyFeedback(
            listOf(trace),
            mapOf("ans100-edge-a" to 0.5),
            ANS100CandidateFeedbackLearner.Feedback(
                "turn-2", "answer", -1.0,
                ANS100CandidateFeedbackLearner.FeedbackSource.VERIFIED_EXTERNAL_OUTCOME,
            ),
        ).single()

        assertEquals(0.49, update.nextWeight, 0.000001)
    }

    @Test
    fun modelGeneratedOrImplicitFeedbackNeverChangesWeights() {
        val trace = ANS100CandidateFeedbackLearner.EligibilityTrace("turn-3", "ans100-edge-a", "answer", 1.0)
        for (source in listOf(
            ANS100CandidateFeedbackLearner.FeedbackSource.MODEL_GENERATED,
            ANS100CandidateFeedbackLearner.FeedbackSource.IMPLICIT_BEHAVIOR,
        )) {
            val updates = learner.applyFeedback(
                listOf(trace),
                mapOf("ans100-edge-a" to 0.5),
                ANS100CandidateFeedbackLearner.Feedback("turn-3", "answer", 1.0, source),
            )
            assertTrue(updates.isEmpty())
        }
    }

    @Test
    fun perSynapseDeltaAndWeightAreBounded() {
        val trace = ANS100CandidateFeedbackLearner.EligibilityTrace("turn-4", "ans100-edge-a", "answer", 1.0)
        val update = learner.applyFeedback(
            listOf(trace),
            mapOf("ans100-edge-a" to 0.999),
            ANS100CandidateFeedbackLearner.Feedback(
                "turn-4", "answer", 1.0,
                ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
            ),
        ).single()

        assertTrue(update.appliedDelta <= 0.01)
        assertTrue(update.nextWeight <= 1.0)
    }

    @Test
    fun duplicateTracesAreRejected() {
        val trace = ANS100CandidateFeedbackLearner.EligibilityTrace("turn-5", "ans100-edge-a", "answer", 1.0)
        assertFailsWith<IllegalArgumentException> {
            learner.applyFeedback(
                listOf(trace, trace),
                mapOf("ans100-edge-a" to 0.5),
                ANS100CandidateFeedbackLearner.Feedback(
                    "turn-5", "answer", 1.0,
                    ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                ),
            )
        }
    }
}
