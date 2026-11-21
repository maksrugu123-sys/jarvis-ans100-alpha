package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReasoningEngineTest {
    @Test
    fun breadthFirstSearchChoosesShortestValidPlan() {
        val result = LocalReasoningEngine().plan(
            LocalReasoningProblem(
                initialFacts = setOf("start"),
                goalFacts = setOf("done"),
                actions = listOf(
                    LocalReasoningAction("a_first", "Long path, first step", setOf("start"), setOf("middle")),
                    LocalReasoningAction("b_second", "Long path, second step", setOf("middle"), setOf("done")),
                    LocalReasoningAction("z_direct", "Direct path", setOf("start"), setOf("done")),
                ),
            ),
        )

        assertEquals(LocalReasoningStatus.SOLVED, result.status)
        assertEquals(listOf("z_direct"), result.actionIds)
        assertFalse(result.executionAuthorized)
    }

    @Test
    fun actionWithMissingPreconditionIsNeverSelected() {
        val result = LocalReasoningEngine().plan(
            LocalReasoningProblem(
                initialFacts = setOf("start"),
                goalFacts = setOf("done"),
                actions = listOf(
                    LocalReasoningAction("blocked", "Cannot run", setOf("secret_fact"), setOf("done")),
                ),
            ),
        )

        assertEquals(LocalReasoningStatus.NO_PLAN, result.status)
        assertEquals(setOf("done"), result.missingGoalFacts)
    }

    @Test
    fun searchBudgetIsReportedInsteadOfClaimingNoPlan() {
        val result = LocalReasoningEngine(maxExpandedStates = 1).plan(
            LocalReasoningProblem(
                initialFacts = setOf("start"),
                goalFacts = setOf("done"),
                actions = listOf(
                    LocalReasoningAction("step_a", "A", setOf("start"), setOf("middle")),
                    LocalReasoningAction("step_b", "B", setOf("middle"), setOf("done")),
                ),
            ),
        )

        assertEquals(LocalReasoningStatus.SEARCH_LIMIT, result.status)
        assertTrue(result.exploredStates <= 1)
    }

    @Test
    fun intentAnalysisProducesDependencyCheckedQuestionPlan() {
        val parsed = LocalUnderstandingPipeline().analyze(
            "Почему нейрон использует адаптивный порог?",
            emptyList(),
        )
        val result = LocalReasoningEngine().planFor(parsed)

        assertEquals(LocalReasoningStatus.SOLVED, result.status)
        assertTrue(result.actionIds.contains("preserve_constraints"))
        assertTrue(result.finalFacts.contains("response_context_ready"))
        assertFalse(result.executionAuthorized)
    }

    @Test
    fun ambiguousContextStopsAtClarificationGoal() {
        val parsed = LocalUnderstandingPipeline().analyze("Продолжай", emptyList())
        val result = LocalReasoningEngine().planFor(parsed)

        assertEquals(LocalReasoningStatus.SOLVED, result.status)
        assertTrue(result.actionIds.contains("request_clarification"))
        assertTrue(result.finalFacts.contains("clarification_ready"))
    }
}
