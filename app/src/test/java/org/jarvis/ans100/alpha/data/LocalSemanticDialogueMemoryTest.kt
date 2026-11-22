package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSemanticDialogueMemoryTest {
    @Test
    fun recordsExplicitPreferencesWithTheirSourceMessage() {
        val history = listOf(
            LocalConversationMessage("u1", ConversationRole.USER, "Предпочитаю короткие ответы на русском языке", 10L),
        )
        val memory = LocalSemanticDialogueMemory.fromHistory(history)
        assertEquals(1, memory.explicitPreferences.size)
        assertEquals("u1", memory.explicitPreferences.single().sourceMessageId)
        assertEquals("explicit_user_statement", memory.explicitPreferences.single().provenance)
        assertTrue(memory.toModelContext().contains("Предпочитаю короткие ответы"))
    }

    @Test
    fun keepsAnOpenTaskAcrossAnUnrelatedTopicDetour() {
        val history = listOf(
            LocalConversationMessage("u1", ConversationRole.USER, "Нужно добавить экспорт памяти в JARVIS", 10L),
            LocalConversationMessage("a1", ConversationRole.ASSISTANT, "Начну с отдельного механизма экспорта.", 11L),
            LocalConversationMessage("u2", ConversationRole.USER, "Теперь объясни, как работает CPU", 12L),
        )
        val memory = LocalSemanticDialogueMemory.fromHistory(history)
        assertTrue(memory.activeTasks.any { it.text.contains("экспорт памяти") })
        assertEquals("in_progress", memory.activeTasks.first { it.text.contains("экспорт памяти") }.status)
        assertEquals("u1", memory.activeTasks.first { it.text.contains("экспорт памяти") }.sourceMessageId)
    }

    @Test
    fun explicitCompletionMovesMatchingTaskOutOfActiveTasks() {
        val history = listOf(
            LocalConversationMessage("u1", ConversationRole.USER, "Создай экспорт памяти", 10L),
            LocalConversationMessage("a1", ConversationRole.ASSISTANT, "Подготовлю экспорт памяти.", 11L),
            LocalConversationMessage("u2", ConversationRole.USER, "Готово, экспорт памяти работает", 12L),
        )
        val memory = LocalSemanticDialogueMemory.fromHistory(history)
        assertFalse(memory.activeTasks.any { it.text.contains("экспорт памяти") })
        assertTrue(memory.completedTasks.any { it.text.contains("экспорт памяти") })
        assertEquals("u1", memory.completedTasks.last().sourceMessageId)
        assertEquals("u2", memory.completedTasks.last().completedByMessageId)
    }

    @Test
    fun doesNotTurnOrdinaryStatementsIntoExplicitPreferences() {
        val history = listOf(
            LocalConversationMessage("u1", ConversationRole.USER, "Я сегодня использовал русский язык", 10L),
        )
        val memory = LocalSemanticDialogueMemory.fromHistory(history)
        assertTrue(memory.explicitPreferences.isEmpty())
        assertTrue(memory.activeTasks.isEmpty())
    }


    @Test
    fun keepsOnlyTheLatestAssistantQuestionAsUnresolved() {
        val history = listOf(
            LocalConversationMessage("u1", ConversationRole.USER, "Настроить память", 10L),
            LocalConversationMessage("a1", ConversationRole.ASSISTANT, "Какой формат выбрать?", 11L),
            LocalConversationMessage("u2", ConversationRole.USER, "JSON", 12L),
            LocalConversationMessage("a2", ConversationRole.ASSISTANT, "Какой размер лимита?", 13L),
        )
        val memory = LocalSemanticDialogueMemory.fromHistory(history)
        assertEquals(listOf("Какой размер лимита?"), memory.unresolvedQuestions)
    }


    @Test
    fun persistedTaskCanBeCompletedAfterItsOriginalTurnLeavesRecentHistory() {
        val persisted = LocalSemanticDialogueMemory(
            activeTasks = listOf(
                LocalSemanticTask("Нужно реализовать экспорт памяти", "in_progress", "old-user-turn", 10L),
            ),
        )
        val memory = LocalSemanticDialogueMemory.fromHistory(
            listOf(
                LocalConversationMessage("new-user-turn", ConversationRole.USER, "Готово, экспорт памяти работает", 20L),
            ),
            persisted,
        )
        assertTrue(memory.activeTasks.none { it.sourceMessageId == "old-user-turn" })
        assertTrue(memory.completedTasks.any {
            it.sourceMessageId == "old-user-turn" && it.completedByMessageId == "new-user-turn"
        })
    }

    @Test
    fun persistedExplicitPreferencesSurviveAHistoryWindowWithoutPreferenceMarkers() {
        val persisted = LocalSemanticDialogueMemory(
            explicitPreferences = listOf(
                LocalExplicitPreference("Отвечай коротко", "old-preference-turn", 10L),
            ),
        )
        val memory = LocalSemanticDialogueMemory.fromHistory(
            listOf(LocalConversationMessage("new-turn", ConversationRole.USER, "Объясни CPU", 20L)),
            persisted,
        )
        assertEquals("old-preference-turn", memory.explicitPreferences.single().sourceMessageId)
    }


    @Test
    fun imperativeWriteRequestIsNotMistakenForUserPreference() {
        val memory = LocalSemanticDialogueMemory.fromHistory(
            listOf(LocalConversationMessage("u1", ConversationRole.USER, "Напиши программу для экспорта памяти", 10L)),
        )
        assertTrue(memory.explicitPreferences.isEmpty())
        assertTrue(memory.activeTasks.any { it.text.contains("Напиши программу") })
    }

    @Test
    fun boundsMemoryCollectionsAndContext() {
        val history = (1..80).map { index ->
            LocalConversationMessage(
                "u$index", ConversationRole.USER,
                "Предпочитаю формат ответа номер $index. Нужно создать модуль $index",
                index.toLong(),
            )
        }
        val memory = LocalSemanticDialogueMemory.fromHistory(history)
        assertTrue(memory.explicitPreferences.size <= 8)
        assertTrue(memory.activeTasks.size <= 8)
        assertTrue(memory.toModelContext().length <= 1_800)
    }
}
