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
    fun unknownTextDoesNotPretendToBeUnderstood() {
        val result = pipeline.analyze("абракадабра фывапролд")
        assertEquals("statement_or_unknown", result.intent)
        assertFalse(result.intentConfidence >= 0.5)
    }
}
