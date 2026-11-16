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
class ANS100NeuralAssociationPersistenceInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun databaseName() = "association-assessment-${UUID.randomUUID()}.db"

    @Test
    fun independentAssessmentsSurviveDatabaseReopen() {
        val name = databaseName()
        val candidateId = "neural-association:" + "c".repeat(32)
        val network = AndroidSparseNeuronNetwork(context, name)
        var networkClosed = false
        try {
            network.initializeHybridTopologyV2()
            repeat(3) { index ->
                assertEquals(
                    1,
                    network.recordNeuralAssociationEvaluation(
                        candidateId,
                        ANS100NeuralAssociationAssessment.Observation(
                            taskId = "heldout-task-$index",
                            independenceKey = "independent-unit-$index",
                            outcome = ANS100NeuralAssociationAssessment.Outcome.REPRODUCED,
                        ),
                    ),
                )
            }
            assertEquals(
                0,
                network.recordNeuralAssociationEvaluation(
                    candidateId,
                    ANS100NeuralAssociationAssessment.Observation(
                        taskId = "heldout-task-duplicate",
                        independenceKey = "independent-unit-0",
                        outcome = ANS100NeuralAssociationAssessment.Outcome.REPRODUCED,
                    ),
                ),
            )
            network.close()
            networkClosed = true

            val reopened = AndroidSparseNeuronNetwork(context, name)
            try {
                val assessment = reopened.assessNeuralAssociation(candidateId)
                assertEquals(3, assessment.successfulReproductions)
                assertEquals(ANS100NeuralAssociationAssessment.Stability.STABLE, assessment.stability)
            } finally {
                reopened.close()
            }
        } finally {
            if (!networkClosed) runCatching { network.close() }
            context.deleteDatabase(name)
        }
    }

    @Test
    fun explicitFeedbackWritesAtomicBeforeAfterHistoryAndIsIdempotent() {
        val name = databaseName()
        val candidateId = "neural-association:" + "d".repeat(32)
        val network = AndroidSparseNeuronNetwork(context, name)
        var networkClosed = false
        try {
            network.initializeHybridTopologyV2()
            val concepts = (0 until 96).map { "concept:test-$it" }
            val slots = network.resolveConceptInputSlots(concepts)
            val pair = slots.entries.toList().let { entries ->
                entries.indices.asSequence().flatMap { left ->
                    (left + 1 until entries.size).asSequence().map { right ->
                        entries[left] to entries[right]
                    }
                }.first { (left, right) ->
                    AndroidHybridTopologyV2.conceptAssociationTargets(left.value)
                        .intersect(AndroidHybridTopologyV2.conceptAssociationTargets(right.value)).isNotEmpty()
                }
            }
            val firstProjection = AndroidHybridTopologyV2.conceptAssociationProjection(pair.first.value)
            val secondProjection = AndroidHybridTopologyV2.conceptAssociationProjection(pair.second.value)
            val target = firstProjection.keys.intersect(secondProjection.keys).first()
            val connectionIds = listOf(firstProjection.getValue(target), secondProjection.getValue(target))
            val before = network.neuralAssociationWeights(connectionIds)
            assertEquals(2, network.recordNeuralAssociationEligibility("turn-feedback-1", candidateId, connectionIds, 1.0))

            val updated = network.applyNeuralAssociationFeedback(
                "feedback-event-1",
                "turn-feedback-1",
                candidateId,
                1.0,
                ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
            )
            assertEquals(2, updated)
            assertEquals(
                0,
                network.applyNeuralAssociationFeedback(
                    "feedback-event-1",
                    "turn-feedback-1",
                    candidateId,
                    1.0,
                    ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                ),
            )
            val after = network.neuralAssociationWeights(connectionIds)
            assertTrue(connectionIds.all { after.getValue(it) > before.getValue(it) })
            val history = network.neuralAssociationWeightHistory(candidateId)
            assertEquals(2, history.size)
            assertTrue(history.all { it.nextWeight > it.previousWeight && it.reward == 1.0 })
            assertEquals(ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                network.assessNeuralAssociation(candidateId).explicitFeedbackSource)
            network.close()
            networkClosed = true
            val reopened = AndroidSparseNeuronNetwork(context, name)
            try {
                assertEquals(2, reopened.neuralAssociationWeightHistory(candidateId).size)
                assertEquals(ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                    reopened.assessNeuralAssociation(candidateId).explicitFeedbackSource)
            } finally {
                reopened.close()
            }
        } finally {
            if (!networkClosed) network.close()
            context.deleteDatabase(name)
        }
    }
}
