package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ANS100NeuralAssociationBenchmarkAssessmentAdapterTest {
    private fun candidate() = ANS100NeuralAssociationDecoder.Candidate(
        candidateId = "neural-association:" + "a".repeat(32),
        firstConceptId = "concept:a",
        firstLabel = "alpha",
        secondConceptId = "concept:b",
        secondLabel = "beta",
        convergentNeuronIds = listOf(1_200),
        activatedConnectionIds = listOf(
            "ans100-v4-evidence-general-association-1",
            "ans100-v4-evidence-general-association-2",
        ),
        evidenceRefs = listOf("turn:one", "turn:two"),
        activationHint = 1.0 / 3.0,
    )

    private fun task(answer: String, split: String = "pair_disjoint", family: String = "relation_status") =
        ANS100NeuralAssociationBenchmarkAssessmentAdapter.HeldOutTask(
            taskId = "task-001",
            independenceKey = "independent-task-001",
            split = split,
            family = family,
            queryPair = "BETA" to "Alpha",
            expectedAnswer = answer,
        )

    @Test
    fun heldOutYesWithReproductionIsSuccessfulEvidence() {
        val result = ANS100NeuralAssociationBenchmarkAssessmentAdapter()
            .observe(candidate(), task("YES"), candidateReproduced = true)
        assertEquals(ANS100NeuralAssociationAssessment.Outcome.REPRODUCED, result?.outcome)
    }

    @Test
    fun explicitNoContradictsOnlyAnObservedCandidate() {
        val adapter = ANS100NeuralAssociationBenchmarkAssessmentAdapter()
        val reproduced = adapter.observe(candidate(), task("NO"), candidateReproduced = true)
        val absent = adapter.observe(candidate(), task("NO").copy(taskId = "task-002", independenceKey = "independent-task-002"), candidateReproduced = false)
        assertEquals(ANS100NeuralAssociationAssessment.Outcome.CONTRADICTED, reproduced?.outcome)
        assertEquals(ANS100NeuralAssociationAssessment.Outcome.INCONCLUSIVE, absent?.outcome)
    }

    @Test
    fun unknownAndConflictLabelsRemainInconclusive() {
        val adapter = ANS100NeuralAssociationBenchmarkAssessmentAdapter()
        for (answer in listOf("UNKNOWN", "CONFLICT")) {
            val result = adapter.observe(candidate(), task(answer), candidateReproduced = true)
            assertEquals(ANS100NeuralAssociationAssessment.Outcome.INCONCLUSIVE, result?.outcome)
        }
    }

    @Test
    fun unrelatedConceptPairsAreNotUsedToAssessCandidate() {
        val result = ANS100NeuralAssociationBenchmarkAssessmentAdapter().observe(
            candidate(), task("YES").copy(queryPair = "alpha" to "gamma"), candidateReproduced = true,
        )
        assertNull(result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun developmentTasksCannotAssessLearnedAssociations() {
        ANS100NeuralAssociationBenchmarkAssessmentAdapter().observe(
            candidate(), task("YES", split = "development"), candidateReproduced = true,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun multiHopTasksCannotDirectlyLabelPairAssociations() {
        ANS100NeuralAssociationBenchmarkAssessmentAdapter().observe(
            candidate(), task("YES", family = "two_hop_reachability"), candidateReproduced = true,
        )
    }
}
