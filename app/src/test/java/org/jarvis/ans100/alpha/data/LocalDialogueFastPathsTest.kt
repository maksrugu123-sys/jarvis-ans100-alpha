package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDialogueFastPathsTest {
    @Test
    fun answersAssistantNameAndGreetsNamedUserInOneMessage() {
        assertEquals(
            "Меня зовут JARVIS. Приятно познакомиться, Максим!",
            LocalDialogueFastPaths.assistantIdentityReply("Меня зовут Максим, а тебя?"),
        )
    }

    @Test
    fun recallsNameFromEarlierConversationTurn() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Меня зовут Максим.", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Приятно познакомиться!", 2L),
        )
        assertEquals(
            "Меня зовут JARVIS. Приятно познакомиться, Максим!",
            LocalDialogueFastPaths.assistantIdentityReply("А тебя?", history),
        )
    }

    @Test
    fun answersDirectNameQuestion() {
        assertEquals(
            "Меня зовут JARVIS. Я локальный помощник, который работает на этом устройстве.",
            LocalDialogueFastPaths.assistantIdentityReply("Можешь назвать своё имя?"),
        )
    }

    @Test
    fun answersQuestionAboutUsersNameFromEarlierConversation() {
        val history = listOf(
            LocalConversationMessage("user-name", ConversationRole.USER, "Меня зовут Максим.", 1L),
            LocalConversationMessage("assistant-name", ConversationRole.ASSISTANT, "Приятно познакомиться!", 2L),
        )
        assertEquals(
            "Тебя зовут Максим. Ты сообщил это в нашей переписке.",
            LocalDialogueFastPaths.assistantIdentityReply("Как меня зовут?", history),
        )
    }

    @Test
    fun handlesNameAndIdentityInTheSameCurrentMessageWithAlternateWording() {
        assertEquals(
            "Меня зовут JARVIS. Приятно познакомиться, Максим!",
            LocalDialogueFastPaths.assistantIdentityReply("Моё имя Максим, а тебя зовут?"),
        )
    }

    @Test
    fun recallsNameAfterManyConversationMessages() {
        val history = buildList {
            add(LocalConversationMessage("user-name", ConversationRole.USER, "Меня зовут Максим.", 1L))
            add(LocalConversationMessage("assistant-name", ConversationRole.ASSISTANT, "Приятно познакомиться!", 2L))
            repeat(30) { index ->
                add(LocalConversationMessage("user-$index", ConversationRole.USER, "Сообщение номер $index.", (index * 2L) + 3L))
                add(LocalConversationMessage("assistant-$index", ConversationRole.ASSISTANT, "Ответ номер $index.", (index * 2L) + 4L))
            }
        }
        assertEquals(
            "Тебя зовут Максим. Ты сообщил это в нашей переписке.",
            LocalDialogueFastPaths.assistantIdentityReply("Как меня зовут?", history),
        )
    }

    @Test
    fun recallsAnExplicitlyNamedProjectFromEarlierChat() {
        val history = listOf(
            LocalConversationMessage("user-project", ConversationRole.USER,
                "Я разрабатываю проект JARVIS ANS-100 Alpha.", 1L),
            LocalConversationMessage("assistant-project", ConversationRole.ASSISTANT, "Понял.", 2L),
        )
        assertEquals(
            "По нашей переписке ты работаешь над проектом: JARVIS ANS-100 Alpha.",
            LocalDialogueFastPaths.assistantIdentityReply("Над чем я работаю?", history),
        )
    }

    @Test
    fun keepsDotsInsideExplicitProjectNames() {
        val history = listOf(
            LocalConversationMessage("user-project", ConversationRole.USER,
                "Мой проект называется RAZLOM.NET.", 1L),
        )
        assertEquals(
            "По нашей переписке ты работаешь над проектом: RAZLOM.NET.",
            LocalDialogueFastPaths.assistantIdentityReply("Как называется мой проект?", history),
        )
    }

    @Test
    fun summarizesOnlyExplicitlySharedFacts() {
        val history = listOf(
            LocalConversationMessage("user-name", ConversationRole.USER, "Меня зовут Максим.", 1L),
            LocalConversationMessage("assistant-name", ConversationRole.ASSISTANT, "Приятно познакомиться!", 2L),
            LocalConversationMessage("user-project", ConversationRole.USER, "Мой проект называется RAZLOM.NET.", 3L),
            LocalConversationMessage("assistant-project", ConversationRole.ASSISTANT, "Понял.", 4L),
        )
        val answer = LocalDialogueFastPaths.assistantIdentityReply("Что ты помнишь обо мне?", history)
        assertTrue(answer!!.contains("тебя зовут Максим"))
        assertTrue(answer.contains("ты работаешь над проектом RAZLOM.NET"))
    }

    @Test
    fun doesNotInventANameOrProjectWhenNoFactWasShared() {
        assertEquals(
            "Ты ещё не называл мне своё имя в сохранённой истории этого чата.",
            LocalDialogueFastPaths.assistantIdentityReply("Как меня зовут?"),
        )
        assertEquals(
            "В сохранённой переписке я пока не нашёл явного названия твоего проекта.",
            LocalDialogueFastPaths.assistantIdentityReply("Как называется мой проект?"),
        )
    }

    @Test
    fun leavesUnrelatedQuestionsToTheLanguageModel() {
        assertNull(LocalDialogueFastPaths.assistantIdentityReply("Почему не работает сеть?"))
    }
}