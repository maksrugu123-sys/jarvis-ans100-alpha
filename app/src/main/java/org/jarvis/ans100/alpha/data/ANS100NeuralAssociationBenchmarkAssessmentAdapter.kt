package org.jarvis.ans100.alpha.data

/**
 * Converts a held-out benchmark task into an association assessment only when
 * the task directly asks about the same concept pair. A missing neural
 * reproduction is INCONCLUSIVE, never an automatic negative label.
 */
class ANS100NeuralAssociationBenchmarkAssessmentAdapter {
    data class HeldOutTask(
        val taskId: String,
        val split: String,
        val family: String,
        val queryPair: Pair<String, String>,
        val expectedAnswer: String,
    )

    fun observe(
        candidate: ANS100NeuralAssociationDecoder.Candidate,
        task: HeldOutTask,
        candidateReproduced: Boolean,
    ): ANS100NeuralAssociationAssessment.Observation? {
        require(task.taskId.matches(ID_PATTERN))
        require(task.split in HELD_OUT_SPLITS) { "development data cannot assess learned associations" }
        require(task.family in DIRECT_RELATION_FAMILIES) { "multi-hop tasks cannot directly label a pair association" }
        require(task.expectedAnswer in CANONICAL_ANSWERS)
        val candidatePair = setOf(normalize(candidate.firstLabel), normalize(candidate.secondLabel))
        val queryPair = setOf(normalize(task.queryPair.first), normalize(task.queryPair.second))
        if (candidatePair != queryPair) return null

        val outcome = when (task.expectedAnswer) {
            "YES" -> if (candidateReproduced) {
                ANS100NeuralAssociationAssessment.Outcome.REPRODUCED
            } else {
                ANS100NeuralAssociationAssessment.Outcome.INCONCLUSIVE
            }
            "NO" -> if (candidateReproduced) {
                ANS100NeuralAssociationAssessment.Outcome.CONTRADICTED
            } else {
                ANS100NeuralAssociationAssessment.Outcome.INCONCLUSIVE
            }
            else -> ANS100NeuralAssociationAssessment.Outcome.INCONCLUSIVE
        }
        return ANS100NeuralAssociationAssessment.Observation(
            taskId = task.taskId,
            independenceKey = "heldout:${task.split}:${task.taskId}",
            outcome = outcome,
            source = ANS100NeuralAssociationAssessment.EvidenceSource.CONTROLLED_BENCHMARK_LABEL,
        )
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    companion object {
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        private val HELD_OUT_SPLITS = setOf("pair_disjoint", "concept_disjoint", "compositional")
        private val DIRECT_RELATION_FAMILIES = setOf("relation_status", "evidence_conflict")
        private val CANONICAL_ANSWERS = setOf("YES", "NO", "UNKNOWN", "CONFLICT")
    }
}
