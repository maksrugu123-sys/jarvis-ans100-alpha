package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ANS100NeuralAssociationAssessmentTest {
    private fun item(index: Int, outcome: ANS100NeuralAssociationAssessment.Outcome) =
        ANS100NeuralAssociationAssessment.Observation("task-$index", "independent-$index", outcome)

    @Test
    fun repeatedIndependentReproductionsAreStableButDoNotChangeWeights() {
        val result = ANS100NeuralAssociationAssessment().assess(
            listOf(
                item(1, ANS100NeuralAssociationAssessment.Outcome.REPRODUCED),
                item(2, ANS100NeuralAssociationAssessment.Outcome.REPRODUCED),
                item(3, ANS100NeuralAssociationAssessment.Outcome.REPRODUCED),
            ),
            ANS100NeuralAssociationAssessment.ExplicitFeedback.POSITIVE,
            ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
        )
        assertEquals(ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER, result.explicitFeedbackSource)
        assertEquals(3, result.successfulReproductions)
        assertEquals(ANS100NeuralAssociationAssessment.Stability.STABLE, result.stability)
        assertEquals(ANS100NeuralAssociationAssessment.FeedbackAgreement.ALIGNED, result.feedbackAgreement)
    }

    @Test
    fun contradictionsIdentifyUnstableAssociationAndFeedbackConflict() {
        val result = ANS100NeuralAssociationAssessment().assess(
            listOf(
                item(1, ANS100NeuralAssociationAssessment.Outcome.CONTRADICTED),
                item(2, ANS100NeuralAssociationAssessment.Outcome.CONTRADICTED),
                item(3, ANS100NeuralAssociationAssessment.Outcome.REPRODUCED),
            ),
            ANS100NeuralAssociationAssessment.ExplicitFeedback.POSITIVE,
        )
        assertEquals(ANS100NeuralAssociationAssessment.Stability.UNSTABLE, result.stability)
        assertEquals(ANS100NeuralAssociationAssessment.FeedbackAgreement.CONFLICTS, result.feedbackAgreement)
    }

    @Test
    fun inconclusiveTasksDoNotCountAsSuccessOrContradiction() {
        val result = ANS100NeuralAssociationAssessment().assess(
            listOf(
                item(1, ANS100NeuralAssociationAssessment.Outcome.REPRODUCED),
                item(2, ANS100NeuralAssociationAssessment.Outcome.INCONCLUSIVE),
                item(3, ANS100NeuralAssociationAssessment.Outcome.INCONCLUSIVE),
            ),
        )
        assertEquals(1, result.successfulReproductions)
        assertEquals(0, result.contradictions)
        assertEquals(ANS100NeuralAssociationAssessment.Stability.INSUFFICIENT_EVIDENCE, result.stability)
    }

    @Test
    fun insufficientEvidenceNeverProducesAStabilityRatio() {
        val result = ANS100NeuralAssociationAssessment().assess(emptyList())
        assertNull(result.stabilityRatio)
        assertEquals(ANS100NeuralAssociationAssessment.Stability.INSUFFICIENT_EVIDENCE, result.stability)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sameTaskCannotBeCountedTwice() {
        ANS100NeuralAssociationAssessment().assess(
            listOf(item(1, ANS100NeuralAssociationAssessment.Outcome.REPRODUCED),
                item(1, ANS100NeuralAssociationAssessment.Outcome.REPRODUCED)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun correlatedTasksCannotMasqueradeAsIndependentEvidence() {
        ANS100NeuralAssociationAssessment().assess(
            listOf(
                ANS100NeuralAssociationAssessment.Observation("task-1", "same-source", ANS100NeuralAssociationAssessment.Outcome.REPRODUCED),
                ANS100NeuralAssociationAssessment.Observation("task-2", "same-source", ANS100NeuralAssociationAssessment.Outcome.REPRODUCED),
            ),
        )
    }
}
