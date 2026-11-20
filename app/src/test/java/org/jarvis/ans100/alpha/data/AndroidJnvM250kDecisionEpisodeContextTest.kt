package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kDecisionEpisodeContextTest {
    private fun selectedDecision(
        turnId: String = "turn-context",
        candidateId: String = "answer",
    ) = ANS100Decision(
        turnId = turnId,
        topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
        status = ANS100DecisionStatus.SELECTED,
        candidates = listOf(ANS100Candidate(candidateId, "Ответить", emptyList())),
        selectedCandidateId = candidateId,
        responseGoal = "Подготовить ответ",
    )

    private fun context(
        turnId: String = "turn-context",
        candidateId: String = "answer",
    ) = AndroidJnvM250kDecisionEpisodeContext(
        decisionTurnId = turnId,
        selectedCandidateId = candidateId,
        initialStates = mapOf(
            1 to AndroidNeuronState(1),
            2 to AndroidNeuronState(2),
        ),
        connections = listOf(AndroidNeuronConnection("edge", 1, 2, 1L, 1.0)),
        externalInputs = listOf(AndroidNeuronEvent("input", 1L, 1, 1.0)),
        outcomeNeuronIds = setOf(2),
    )

    @Test
    fun selectedDecisionAndSnapshotMustMatchTurnAndCandidate() {
        context().validateAgainst(selectedDecision())
        assertTrue(runCatching {
            context().validateAgainst(selectedDecision(turnId = "different-turn"))
        }.isFailure)
        assertTrue(runCatching {
            context().validateAgainst(selectedDecision(candidateId = "different-candidate"))
        }.isFailure)
    }

    @Test
    fun abstainedAndClarificationDecisionsCannotStartRewardEpisodes() {
        val abstained = ANS100Decision(
            turnId = "turn-context",
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            status = ANS100DecisionStatus.ABSTAINED,
            candidates = listOf(ANS100Candidate("answer", "Ответить", emptyList())),
            abstentionReason = "Недостаточно оснований",
            responseGoal = "Воздержаться",
        )
        assertTrue(runCatching { context().validateAgainst(abstained) }.isFailure)

        val clarification = ANS100Decision(
            turnId = "turn-context",
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            status = ANS100DecisionStatus.NEEDS_CLARIFICATION,
            candidates = listOf(ANS100Candidate("answer", "Ответить", emptyList())),
            abstentionReason = "Нужно уточнение",
            responseGoal = "Уточнить запрос",
        )
        assertTrue(runCatching { context().validateAgainst(clarification) }.isFailure)
    }

    @Test
    fun contextRejectsIncompleteGraphAndStaleExternalInput() {
        assertTrue(runCatching {
            AndroidJnvM250kDecisionEpisodeContext(
                decisionTurnId = "turn-context",
                selectedCandidateId = "answer",
                initialStates = mapOf(1 to AndroidNeuronState(1), 2 to AndroidNeuronState(2)),
                connections = listOf(AndroidNeuronConnection("edge", 1, 3, 1L, 1.0)),
                externalInputs = listOf(AndroidNeuronEvent("input", 1L, 1, 1.0)),
                outcomeNeuronIds = setOf(2),
            )
        }.isFailure)
        assertTrue(runCatching {
            AndroidJnvM250kDecisionEpisodeContext(
                decisionTurnId = "turn-context",
                selectedCandidateId = "answer",
                initialStates = mapOf(
                    1 to AndroidNeuronState(1, lastUpdateNs = 2L),
                    2 to AndroidNeuronState(2),
                ),
                connections = listOf(AndroidNeuronConnection("edge", 1, 2, 1L, 1.0)),
                externalInputs = listOf(AndroidNeuronEvent("input", 1L, 1, 1.0)),
                outcomeNeuronIds = setOf(2),
            )
        }.isFailure)
    }

    @Test
    fun contextRejectsDuplicateIdsAndOversizedInputBatches() {
        assertTrue(runCatching {
            AndroidJnvM250kDecisionEpisodeContext(
                decisionTurnId = "turn-context",
                selectedCandidateId = "answer",
                initialStates = mapOf(1 to AndroidNeuronState(1), 2 to AndroidNeuronState(2)),
                connections = listOf(
                    AndroidNeuronConnection("edge", 1, 2, 1L, 1.0),
                    AndroidNeuronConnection("edge", 1, 2, 2L, 0.5),
                ),
                externalInputs = listOf(AndroidNeuronEvent("input", 1L, 1, 1.0)),
                outcomeNeuronIds = setOf(2),
            )
        }.isFailure)
        assertTrue(runCatching {
            AndroidJnvM250kDecisionEpisodeContext(
                decisionTurnId = "turn-context",
                selectedCandidateId = "answer",
                initialStates = mapOf(1 to AndroidNeuronState(1), 2 to AndroidNeuronState(2)),
                connections = listOf(AndroidNeuronConnection("edge", 1, 2, 1L, 1.0)),
                externalInputs = (0 until 513).map {
                    AndroidNeuronEvent("input-$it", 1L, 1, 0.1)
                },
                outcomeNeuronIds = setOf(2),
            )
        }.isFailure)
    }
}
