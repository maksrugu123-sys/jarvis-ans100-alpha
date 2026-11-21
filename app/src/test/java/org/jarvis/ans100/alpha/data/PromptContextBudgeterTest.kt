package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContextBudgeterTest {
    private fun message(role: ConversationRole, text: String, index: Int) =
        LocalConversationMessage(
            id = "message-$index",
            role = role,
            text = text,
            createdAtMillis = 1_700_000_000_000L + index,
        )

    @Test
    fun keepsNewestCompleteHistoryPairsAndDropsOlderContextFirst() {
        val history = buildList {
            repeat(8) { index ->
                add(message(ConversationRole.USER, "старый запрос $index " + "u".repeat(220), index * 2))
                add(message(ConversationRole.ASSISTANT, "старый ответ $index " + "a".repeat(220), index * 2 + 1))
            }
        }

        val plan = PromptContextBudgeter.create(
            baseSystemInstruction = "Базовые правила JARVIS. " + "s".repeat(360),
            prompt = "Продолжай работу над последней задачей",
            recentHistory = history,
            memoryContext = emptyList(),
            signalContext = null,
            understandingContext = null,
        )

        assertTrue(plan.estimatedInputTokens <= 1_056)
        assertTrue(plan.omittedHistoryMessages > 0)
        assertTrue(plan.history.isNotEmpty())
        assertEquals(history.last().id, plan.history.last().id)
        assertEquals(history[history.size - 2].id, plan.history[plan.history.size - 2].id)
        assertEquals(0, plan.history.size % 2)
    }

    @Test
    fun neverSilentlyTruncatesAnOversizedCurrentRequest() {
        var rejected = false
        try {
            PromptContextBudgeter.create(
                baseSystemInstruction = "Базовые правила JARVIS.",
                prompt = "запрос ".repeat(700),
                recentHistory = emptyList(),
                memoryContext = emptyList(),
                signalContext = null,
                understandingContext = null,
            )
        } catch (error: LocalConversationException) {
            rejected = error.message?.contains("слишком длинный") == true
        }
        assertTrue(rejected)
    }

    @Test
    fun onlyUserApprovedMemoriesCanEnterThePrompt() {
        val memories = listOf(
            LocalWorkspaceMemory(
                id = "approved",
                content = "Пользователь предпочитает локальный режим",
                category = "preference",
                updatedAt = "2026-11-15T00:00:00Z",
                userApproved = true,
            ),
            LocalWorkspaceMemory(
                id = "unapproved",
                content = "Эта запись не должна попасть в запрос",
                category = "private",
                updatedAt = "2026-11-15T00:00:00Z",
                userApproved = false,
            ),
        )

        val plan = PromptContextBudgeter.create(
            baseSystemInstruction = "Базовые правила JARVIS.",
            prompt = "Какой режим мне нужен?",
            recentHistory = emptyList(),
            memoryContext = memories,
            signalContext = null,
            understandingContext = null,
        )

        assertTrue(plan.systemInstruction.contains("Пользователь предпочитает локальный режим"))
        assertFalse(plan.systemInstruction.contains("Эта запись не должна попасть"))
        assertEquals(1, plan.includedMemoryItems)
        assertEquals(0, plan.omittedMemoryItems)
    }

    @Test
    fun includesBoundedAndroidNeuralMeasurementsWhenTheyFit() {
        val neural = DialogueNeuralAnalysis(
            networkNeuronCount = 10_000,
            activeFeatureCount = 18,
            activeHiddenNeuronCount = 9,
            processedEvents = 22,
            processedNeuronBatches = 20,
            outputSpikeCount = 4,
            pendingEvents = 0,
            classifierLabel = null,
            classifierConfidenceMargin = null,
            classifierTrainedExamples = 0,
            statePersisted = true,
            networkOutputScores = mapOf(
                "greeting" to 0.75,
                "question" to 0.55,
                "thanks" to 0.20,
                "farewell" to 0.10,
            ),
        )

        val plan = PromptContextBudgeter.create(
            baseSystemInstruction = "Базовые правила JARVIS.",
            prompt = "Объясни принцип работы.",
            recentHistory = emptyList(),
            memoryContext = emptyList(),
            signalContext = null,
            understandingContext = null,
            dialogueNeuralAnalysis = neural,
        )

        assertTrue(plan.systemInstruction.contains("10000 nodes"))
        assertTrue(plan.systemInstruction.contains("active text-feature inputs=18"))
        assertTrue(plan.systemInstruction.contains("active hidden neurons=9"))
        assertTrue(plan.systemInstruction.contains("not general semantic reasoning"))
        assertTrue(plan.systemInstruction.contains("greeting=0.750"))
        assertTrue(plan.systemInstruction.contains("not probabilities"))
    }

    @Test
    fun optionalAns100HandoffIsIncludedOnlyWhenSuppliedAndWithinBudget() {
        val message = "Объясни принцип работы."
        val understanding = LocalUnderstandingPipeline().analyze(message)
        val evidence = TypedEvidenceEncoder().encode(
            TypedEvidenceInput("budget-handoff-turn", message, understanding),
        )
        val decision = ANS100DecisionPolicy().decide(evidence, understanding, null)
        val handoff = ANS100QwenHandoffBuilder().build(
            originalUserMessage = message,
            understanding = understanding,
            evidence = evidence,
            decision = decision,
        )

        val withoutHandoff = PromptContextBudgeter.create(
            baseSystemInstruction = "Базовые правила JARVIS.",
            prompt = message,
            recentHistory = emptyList(),
            memoryContext = emptyList(),
            signalContext = null,
            understandingContext = null,
        )
        val withHandoff = PromptContextBudgeter.create(
            baseSystemInstruction = "Базовые правила JARVIS.",
            prompt = message,
            recentHistory = emptyList(),
            memoryContext = emptyList(),
            signalContext = null,
            understandingContext = null,
            ans100Handoff = handoff,
        )

        assertFalse(withoutHandoff.systemInstruction.contains("ANS100_QWEN_HANDOFF_V1"))
        assertTrue(withHandoff.systemInstruction.contains("ANS100_QWEN_HANDOFF_V1"))
        assertTrue(withHandoff.systemInstruction.contains("CORE_POLICY_V1"))
        assertTrue(withHandoff.estimatedInputTokens <= 1_056)
    }

    @Test
    fun reportsAnEstimateRatherThanPretendingToKnowExactTokenCount() {
        val plan = PromptContextBudgeter.create(
            baseSystemInstruction = "Короткие системные правила.",
            prompt = "Привет",
            recentHistory = emptyList(),
            memoryContext = emptyList(),
            signalContext = null,
            understandingContext = null,
        )
        assertTrue(plan.estimatedInputTokens > 0)
        assertTrue(plan.estimatedInputTokens <= 1_056)
    }
}
