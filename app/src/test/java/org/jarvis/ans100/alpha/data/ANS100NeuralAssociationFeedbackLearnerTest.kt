package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100NeuralAssociationFeedbackLearnerTest {
    private fun fixture(): ANS100NeuralAssociationDecoder.Candidate {
        val start = ANS100ConceptMemory.NEURAL_INPUT_START
        val end = start + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
        for (left in start until end) {
            for (right in left + 1 until end) {
                val leftProjection = AndroidHybridTopologyV2.conceptAssociationProjection(left)
                val rightProjection = AndroidHybridTopologyV2.conceptAssociationProjection(right)
                val shared = leftProjection.keys.intersect(rightProjection.keys).sorted()
                if (shared.isEmpty()) continue
                val edges = shared.flatMap { target ->
                    listOf(leftProjection.getValue(target), rightProjection.getValue(target))
                }.distinct()
                return ANS100NeuralAssociationDecoder.Candidate(
                    candidateId = "neural-association:" + "a".repeat(32),
                    firstConceptId = "concept:first",
                    firstLabel = "Первое",
                    secondConceptId = "concept:second",
                    secondLabel = "Второе",
                    convergentNeuronIds = shared,
                    activatedConnectionIds = edges,
                    evidenceRefs = listOf("turn:test:first", "turn:test:second"),
                    activationHint = shared.size / 3.0,
                )
            }
        }
        error("no convergent projection pair found")
    }

    private fun feedback(candidate: ANS100NeuralAssociationDecoder.Candidate, reward: Double = 1.0,
                        source: ANS100NeuralAssociationFeedbackLearner.Source =
                            ANS100NeuralAssociationFeedbackLearner.Source.EXPLICIT_USER) =
        ANS100NeuralAssociationFeedbackLearner.Feedback(
            turnId = "turn:one",
            candidateId = candidate.candidateId,
            reward = reward,
            source = source,
        )

    @Test
    fun explicitPositiveFeedbackStrengthensOnlyCandidateSynapsesWithinBound() {
        val candidate = fixture()
        val weights = candidate.activatedConnectionIds.associateWith { 0.5 }
        val result = ANS100NeuralAssociationFeedbackLearner().apply(candidate, weights, feedback(candidate))

        assertTrue(result.accepted)
        assertEquals(null, result.reason)
        assertEquals(candidate.activatedConnectionIds.size, result.updates.size)
        result.updates.forEach {
            assertTrue(it.nextWeight > it.previousWeight)
            assertTrue(it.appliedDelta <= 0.01)
            assertEquals(0.5, it.previousWeight, 0.0)
        }
    }

    @Test
    fun explicitNegativeFeedbackWeakensOnlyCandidateSynapses() {
        val candidate = fixture()
        val weights = candidate.activatedConnectionIds.associateWith { 0.5 }
        val result = ANS100NeuralAssociationFeedbackLearner().apply(
            candidate, weights, feedback(candidate, reward = -1.0),
        )

        assertTrue(result.accepted)
        assertEquals(candidate.activatedConnectionIds.size, result.updates.size)
        assertTrue(result.updates.all { it.nextWeight < it.previousWeight })
    }

    @Test
    fun generatedOrImplicitFeedbackCannotTeachTheNetwork() {
        val candidate = fixture()
        val weights = candidate.activatedConnectionIds.associateWith { 0.5 }
        for (source in listOf(
            ANS100NeuralAssociationFeedbackLearner.Source.MODEL_GENERATED,
            ANS100NeuralAssociationFeedbackLearner.Source.IMPLICIT_BEHAVIOR,
        )) {
            val result = ANS100NeuralAssociationFeedbackLearner().apply(
                candidate, weights, feedback(candidate, source = source),
            )
            assertFalse(result.accepted)
            assertTrue(result.updates.isEmpty())
            assertEquals(
                ANS100NeuralAssociationFeedbackLearner.RejectionReason.UNTRUSTED_FEEDBACK_SOURCE,
                result.reason,
            )
        }
    }

    @Test
    fun mismatchedCandidateAndMissingWeightsAreRejectedWithoutUpdates() {
        val candidate = fixture()
        val weights = candidate.activatedConnectionIds.associateWith { 0.5 }
        val mismatch = feedback(candidate).copy(candidateId = "neural-association:" + "b".repeat(32))
        val mismatchResult = ANS100NeuralAssociationFeedbackLearner().apply(candidate, weights, mismatch)
        assertFalse(mismatchResult.accepted)
        assertEquals(
            ANS100NeuralAssociationFeedbackLearner.RejectionReason.CANDIDATE_ID_MISMATCH,
            mismatchResult.reason,
        )

        val incomplete = weights - candidate.activatedConnectionIds.first()
        val missingResult = ANS100NeuralAssociationFeedbackLearner().apply(
            candidate, incomplete, feedback(candidate),
        )
        assertFalse(missingResult.accepted)
        assertEquals(
            ANS100NeuralAssociationFeedbackLearner.RejectionReason.MISSING_CURRENT_WEIGHT,
            missingResult.reason,
        )
        assertTrue(missingResult.updates.isEmpty())
    }

    @Test
    fun weightsAreClampedAndRepeatedUpdatesCannotExceedSynapseBounds() {
        val candidate = fixture()
        val learner = ANS100NeuralAssociationFeedbackLearner()
        val weights = candidate.activatedConnectionIds.associateWith { 0.999 }
        val result = learner.apply(candidate, weights, feedback(candidate, reward = 1.0))
        assertTrue(result.accepted)
        assertTrue(result.updates.all { it.nextWeight in 0.05..1.0 })
        assertTrue(result.updates.all { it.appliedDelta <= 0.0010000001 })
    }
}
