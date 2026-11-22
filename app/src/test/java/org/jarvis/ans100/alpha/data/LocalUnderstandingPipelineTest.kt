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
    fun recognizesQuestionsAboutAvailableAssistantCapabilities() {
        val result = pipeline.analyze("Ты можешь искать в интернете?")
        assertEquals("capability_question", result.intent)
        assertEquals("answer_capability_question", result.taskKind)
        assertFalse(result.reasoningPlan.requiresClarification)
        assertTrue(result.reasoningPlan.steps.any { it.contains("подключённые функции") })
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
        assertEquals("selected", result.contextReference.status)
    }

    @Test
    fun selectsThePriorUserTurnThatMatchesAContextAnchor() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Обсуждаем Chromium браузер", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Речь шла о браузере Chromium.", 2L),
            LocalConversationMessage("user-2", ConversationRole.USER, "Обсуждаем JARVIS память", 3L),
            LocalConversationMessage("assistant-2", ConversationRole.ASSISTANT, "Память JARVIS хранит заметки.", 4L),
        )
        val result = pipeline.analyze("Продолжай про Chromium", history)
        assertEquals("selected", result.contextReference.status)
        assertEquals("lexical_turn_match", result.contextReference.method)
        assertTrue(result.contextReference.sourceUserMessage.contains("Chromium"))
        assertTrue(result.contextReference.matchedTerms.contains("chromium"))
    }

    @Test
    fun asksForClarificationWhenRecentTurnsMatchEqually() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Обсуждаем JARVIS сеть", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Сеть JARVIS ограничена.", 2L),
            LocalConversationMessage("user-2", ConversationRole.USER, "Обсуждаем JARVIS интерфейс", 3L),
            LocalConversationMessage("assistant-2", ConversationRole.ASSISTANT, "Интерфейс JARVIS ещё экспериментальный.", 4L),
        )
        val result = pipeline.analyze("Продолжай про JARVIS", history)
        assertEquals("ambiguous", result.contextReference.status)
        assertTrue(result.reasoningPlan.requiresClarification)
    }

    @Test
    fun affirmativeFollowThroughSelectsTheAdjacentAssistantOffer() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Добавить выбор контекстной ссылки", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Могу начать этот этап.", 2L),
        )
        val result = pipeline.analyze("Давай", history)
        assertEquals("continuation", result.intent)
        assertEquals("selected", result.contextReference.status)
        assertEquals("adjacent_assistant_offer", result.contextReference.method)
        assertTrue(result.contextReference.sourceAssistantMessage.contains("Могу начать"))
    }


    @Test
    fun affirmativeDoesNotAcceptAnAssistantMessageThatWasNotAnOffer() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Проверить описание проблемы", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Я нашёл описание проблемы.", 2L),
        )
        val result = pipeline.analyze("Давай", history)
        assertEquals("continuation", result.intent)
        assertEquals("latest_user_turn", result.contextReference.method)
        assertTrue(result.dialogueState.pendingAssistantOffer.isBlank())
    }

    @Test
    fun dialogueStateTracksAnOpenQuestionAndExpiresItAfterUserReply() {
        val waiting = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Настроить память", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Какой объём памяти выбрать?", 2L),
        )
        val state = LocalDialogueState.fromHistory(waiting)
        assertEquals("Настроить память", state.activeTopic)
        assertTrue(state.pendingAssistantQuestion.contains("Какой объём"))
        val replied = waiting + LocalConversationMessage("user-2", ConversationRole.USER, "Выбери автоматически", 3L)
        assertTrue(LocalDialogueState.fromHistory(replied).pendingAssistantQuestion.isBlank())
    }

    @Test
    fun topicChangeExpiresThePreviousAssistantOffer() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Добавить поиск", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Могу начать реализацию поиска.", 2L),
            LocalConversationMessage("user-2", ConversationRole.USER, "Теперь объясни CPU", 3L),
        )
        val result = pipeline.analyze("Давай", history)
        assertTrue(result.dialogueState.pendingAssistantOffer.isBlank())
        assertEquals("latest_user_turn", result.contextReference.method)
        assertEquals("Теперь объясни CPU", result.dialogueState.activeTopic)
    }

    @Test
    fun remembersSeveralTopicsAndReturnsToAnOlderTopicByItsName() {
        val history = listOf(
            LocalConversationMessage("u1", ConversationRole.USER, "Обсуждаем Chromium браузер", 1L),
            LocalConversationMessage("a1", ConversationRole.ASSISTANT, "Chromium использует движок Blink.", 2L),
            LocalConversationMessage("u2", ConversationRole.USER, "Обсуждаем память JARVIS", 3L),
            LocalConversationMessage("a2", ConversationRole.ASSISTANT, "Память хранит локальные записи.", 4L),
            LocalConversationMessage("u3", ConversationRole.USER, "Обсуждаем CPU", 5L),
            LocalConversationMessage("a3", ConversationRole.ASSISTANT, "CPU выполняет инструкции.", 6L),
        )
        val result = pipeline.analyze("Вернёмся к Chromium", history)
        assertTrue(result.dialogueState.topicThreads.size >= 3)
        assertEquals("selected", result.contextReference.status)
        assertEquals("lexical_turn_match", result.contextReference.method)
        assertTrue(result.contextReference.sourceUserMessage.contains("Chromium"))
    }

    @Test
    fun shortAnswerIsLinkedToThePendingAssistantQuestion() {
        val history = listOf(
            LocalConversationMessage("u1", ConversationRole.USER, "Настроить память JARVIS", 1L),
            LocalConversationMessage("a1", ConversationRole.ASSISTANT, "Какой объём памяти выбрать?", 2L),
        )
        val result = pipeline.analyze("256 мегабайт", history)
        assertEquals("continuation", result.intent)
        assertEquals("answer_to_pending_assistant_question", result.contextReference.method)
        assertTrue(result.contextReference.sourceAssistantMessage.contains("Какой объём"))
    }

    @Test
    fun explicitTopicChangeDoesNotAnswerTheOldPendingQuestion() {
        val history = listOf(
            LocalConversationMessage("u1", ConversationRole.USER, "Настроить память JARVIS", 1L),
            LocalConversationMessage("a1", ConversationRole.ASSISTANT, "Какой объём памяти выбрать?", 2L),
        )
        val result = pipeline.analyze("Теперь другая тема: объясни CPU", history)
        assertFalse(result.evidence.contains("previous_context_reference"))
    }

    @Test
    fun shortWhyFollowUpResolvesToTheLatestDialogueTurn() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Как работает память JARVIS?", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Память сохраняет подтверждённые записи.", 2L),
        )
        val result = pipeline.analyze("Почему?", history)
        assertEquals("continuation", result.intent)
        assertEquals("selected", result.contextReference.status)
        assertEquals("elliptical_follow_up", result.contextReference.method)
        assertTrue(result.refersToPreviousContext)
        assertTrue(result.contextReference.sourceAssistantMessage.contains("подтверждённые записи"))
    }

    @Test
    fun shortHowAndMoreDetailFollowUpsResolveToPreviousTurn() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Расскажи про нейронный декодер", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Декодер строит последовательность токенов.", 2L),
        )
        val how = pipeline.analyze("А как?", history)
        val more = pipeline.analyze("Расскажи подробнее", history)
        assertEquals("continuation", how.intent)
        assertEquals("elliptical_follow_up", how.contextReference.method)
        assertEquals("continuation", more.intent)
        assertEquals("elliptical_follow_up", more.contextReference.method)
    }

    @Test
    fun completeStandaloneQuestionIsNotMisclassifiedAsEllipticalFollowUp() {
        val result = pipeline.analyze("Почему небо голубое?", listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Привет", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Привет!", 2L),
        ))
        assertEquals("question", result.intent)
        assertFalse(result.evidence.contains("elliptical_follow_up"))
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
    fun tokenizerPreservesUnicodeLettersOutsideLatinAndCyrillic() {
        val result = pipeline.analyze("Vergleiche Größe und Straße")
        assertTrue(result.keyTerms.contains("vergleiche"))
        assertTrue(result.keyTerms.contains("größe"))
        assertTrue(result.keyTerms.contains("straße"))
    }

    @Test
    fun unknownTextDoesNotPretendToBeUnderstood() {
        val result = pipeline.analyze("абракадабра фывапролд")
        assertEquals("statement_or_unknown", result.intent)
        assertFalse(result.intentConfidence >= 0.5)
    }
}
