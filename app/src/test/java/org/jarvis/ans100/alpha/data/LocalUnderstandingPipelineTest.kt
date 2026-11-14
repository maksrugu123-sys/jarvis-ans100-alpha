package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUnderstandingPipelineTest {
    private val pipeline = LocalUnderstandingPipeline()

    @Test
    fun detectsQuestionAndNegationWithoutClaimingCertainty() {
        val result = pipeline.analyze("Почему это не работает?")
        assertEquals("question", result.intent)
        assertTrue(result.containsNegation)
        assertTrue(result.intentConfidence in 0.0..1.0)
        assertTrue(result.refersToPreviousContext)
    }

    @Test
    fun recognizesExplicitActionRequest() {
        val result = pipeline.analyze("Найди ошибки и исправь их в проекте")
        assertEquals("action_request", result.intent)
        assertEquals("plan_or_explain_requested_action", result.taskKind)
        assertEquals(listOf("ошибки", "проекте"), result.reasoningPlan.focusTerms)
    }

    @Test
    fun recognizesComparisonAndExplanation() {
        assertEquals("comparison", pipeline.analyze("Что лучше: трансформер или нейронная сеть?").intent)
        assertTrue(pipeline.analyze("Объясни, зачем это нужно").asksForExplanation)
    }

    @Test
    fun recognizesSocialMessages() {
        assertEquals("greeting", pipeline.analyze("Привет, Джарвис!").intent)
        assertEquals("thanks", pipeline.analyze("Большое спасибо").intent)
        assertEquals("farewell", pipeline.analyze("Пока").intent)
    }

    @Test
    fun boundsInputAndModelContext() {
        val result = pipeline.analyze("слово ".repeat(2_000))
        assertTrue(result.keyTerms.size <= 10)
        assertTrue(result.entities.size <= 5)
        assertTrue(result.evidence.size <= 5)
        assertTrue(result.toModelContext().length < 2_000)
    }

    @Test
    fun exposesTheSharedVersionedUnderstandingContract() {
        val result = pipeline.analyze("Почему это важно?")
        assertEquals("JARVIS_UNDERSTANDING_V1", result.schema)
        assertEquals("bounded_heuristic_pre_generation_analysis", result.scope)
        assertTrue(result.toModelContext().contains("JARVIS_UNDERSTANDING_V1"))
        assertTrue(result.toModelContext().contains("эвристический"))
    }

    @Test
    fun avoidsSubstringFalsePositivesAndExtractsCyrillicNames() {
        val unrelated = pipeline.analyze("This notebook contains little details.")
        assertFalse(unrelated.containsNegation)
        assertFalse(unrelated.refersToPreviousContext)

        val named = pipeline.analyze("Сделай анализ Qwen3 и Джарвис")
        assertTrue(named.entities.contains("Qwen3"))
        assertTrue(named.entities.contains("Джарвис"))
    }

    @Test
    fun asksForContextWhenAReferenceHasNoPreviousUserTurn() {
        val result = pipeline.analyze("Продолжай с предыдущей задачей")
        assertTrue(result.refersToPreviousContext)
        assertFalse(result.hasRecentUserContext)
        assertTrue(result.reasoningPlan.requiresClarification)
        assertFalse(result.reasoningPlan.contextAvailable)
        assertTrue(result.reasoningPlan.clarificationQuestion.isNotBlank())
    }

    @Test
    fun makesRecentUserHistoryAvailableForContextDependentMessages() {
        val history = listOf(
            LocalConversationMessage(
                id = "message-user-1",
                role = ConversationRole.USER,
                text = "Мы обсуждаем ошибку в проекте JARVIS",
                createdAtMillis = 1L,
            ),
            LocalConversationMessage(
                id = "message-assistant-1",
                role = ConversationRole.ASSISTANT,
                text = "Я нашёл описание проблемы.",
                createdAtMillis = 2L,
            ),
        )
        val result = pipeline.analyze("Продолжай с этим", history)
        assertTrue(result.hasRecentUserContext)
        assertTrue(result.reasoningPlan.contextAvailable)
        assertFalse(result.reasoningPlan.requiresClarification)
    }

    @Test
    fun matchesTheSharedDesktopAndAndroidUnderstandingFixtures() {
        val stream = javaClass.getResourceAsStream("/understanding/understanding-v1.tsv")
            ?: error("Shared JARVIS understanding fixture is missing")
        val rows = stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.drop(1).map { it.split('\t') }.toList()
        }
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            val result = pipeline.analyze(row[0])
            assertEquals(row[1], result.intent)
            assertEquals(row[2].toBoolean(), result.containsNegation)
            assertEquals(row[3].toBoolean(), result.refersToPreviousContext)
            assertEquals(row[4].toBoolean(), result.asksForExplanation)
            assertEquals("JARVIS_REASONING_PLAN_V1", result.reasoningPlan.schema)
            assertEquals(row[5].toInt(), result.reasoningPlan.steps.size)
            assertEquals(row[6].toBoolean(), result.reasoningPlan.requiresClarification)
            assertFalse(result.reasoningPlan.executionAuthorized)
        }
    }

    @Test
    fun unknownTextDoesNotPretendToBeUnderstood() {
        val result = pipeline.analyze("абракадабра фывапролд")
        assertEquals("statement_or_unknown", result.intent)
        assertFalse(result.intentConfidence >= 0.5)
    }
}
