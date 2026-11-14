package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalDialogueFastPathsTest {
    @Test
    fun answersAssistantNameAndGreetsNamedUserInOneMessage() {
        assertEquals("Меня зовут JARVIS. Приятно познакомиться, Максим!",
            LocalDialogueFastPaths.assistantIdentityReply("Меня зовут Максим, а тебя?"))
    }

    @Test
    fun recallsNameFromEarlierConversationTurn() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Меня зовут Максим.", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Приятно познакомиться!", 2L),
        )
        assertEquals("Меня зовут JARVIS. Приятно познакомиться, Максим!",
            LocalDialogueFastPaths.assistantIdentityReply("А тебя?", history))
    }

    @Test
    fun answersDirectNameQuestion() {
        assertEquals("Меня зовут JARVIS. Я локальный помощник, который работает на этом устройстве.",
            LocalDialogueFastPaths.assistantIdentityReply("Можешь назвать своё имя?"))
    }

    @Test
    fun leavesUnrelatedQuestionsToTheLanguageModel() {
        assertNull(LocalDialogueFastPaths.assistantIdentityReply("Почему не работает сеть?"))
    }
}
