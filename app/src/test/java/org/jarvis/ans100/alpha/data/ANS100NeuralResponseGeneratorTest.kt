package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100NeuralResponseGeneratorTest {
    @Test
    fun responseIsGeneratedWithoutAnyLanguageModel() {
        val generator = ANS100NeuralResponseGenerator()
        val reply = generator.respond(
            "Объясни квантовую физику",
            emptyList(),
            emptyList(),
            null,
            LocalUnderstandingPipeline().analyze("Объясни квантовую физику"),
            null,
        )

        assertFalse(generator.languageModelConfigured)
        assertTrue(reply.contains("Я выделил тему"))
        assertTrue(reply.contains("нет достаточных сведений"))
        assertEquals("explanation", generator.lastTrace.strategy)
        assertEquals("core_analysis_and_dialogue", generator.lastTrace.source)
    }

    @Test
    fun approvedMemoryCanGroundAReplyWithoutLanguageModel() {
        val generator = ANS100NeuralResponseGenerator()
        val memory = LocalWorkspaceMemory(
            id = "approved-android",
            content = "Android поддерживает локальное выполнение приложений.",
            category = "platform",
            updatedAt = "2026-11-22T00:00:00Z",
            userApproved = true,
        )
        val reply = generator.respond(
            "Что известно про Android?",
            emptyList(),
            listOf(memory),
            null,
            LocalUnderstandingPipeline().analyze("Что известно про Android?"),
            null,
        )

        assertTrue(reply.contains("Android поддерживает локальное выполнение"))
        assertEquals(listOf("approved-android"), generator.lastTrace.usedMemoryIds)
        assertEquals("approved_memory_plus_core", generator.lastTrace.source)
    }

    @Test
    fun unapprovedMemoryIsNeverUsedForAnswer() {
        val generator = ANS100NeuralResponseGenerator()
        val memory = LocalWorkspaceMemory(
            id = "unapproved",
            content = "Android has a secret feature.",
            category = "private",
            updatedAt = "2026-11-22T00:00:00Z",
            userApproved = false,
        )
        val reply = generator.respond(
            "Что известно про Android?",
            emptyList(),
            listOf(memory),
            null,
            LocalUnderstandingPipeline().analyze("Что известно про Android?"),
            null,
        )

        assertFalse(reply.contains("secret feature"))
        assertTrue(generator.lastTrace.usedMemoryIds.isEmpty())
    }

    @Test
    fun trainedNeuralReadoutMayRouteAnOtherwiseUnknownMessage() {
        val generator = ANS100NeuralResponseGenerator()
        val neural = DialogueNeuralAnalysis(
            networkNeuronCount = 10_000,
            activeFeatureCount = 3,
            processedEvents = 8,
            processedNeuronBatches = 4,
            outputSpikeCount = 1,
            pendingEvents = 0,
            classifierLabel = "greeting",
            classifierConfidenceMargin = 0.8,
            classifierTrainedExamples = 4,
            statePersisted = true,
        )
        val reply = generator.respond(
            "эй, проверка",
            emptyList(),
            emptyList(),
            null,
            LocalUnderstandingPipeline().analyze("эй, проверка"),
            neural,
        )

        assertTrue(reply.startsWith("Привет!"))
        assertTrue(generator.lastTrace.usedNeuralReadout)
        assertEquals("trained_neural_readout_plus_core", generator.lastTrace.source)
    }

    @Test
    fun untrainedNeuralReadoutCannotOverrideCoreIntent() {
        val generator = ANS100NeuralResponseGenerator()
        val neural = DialogueNeuralAnalysis(
            networkNeuronCount = 10_000,
            activeFeatureCount = 3,
            processedEvents = 8,
            processedNeuronBatches = 4,
            outputSpikeCount = 1,
            pendingEvents = 0,
            classifierLabel = null,
            classifierConfidenceMargin = null,
            classifierTrainedExamples = 0,
            statePersisted = true,
        )
        val reply = generator.respond(
            "Объясни, почему не работает интернет?",
            emptyList(),
            emptyList(),
            null,
            LocalUnderstandingPipeline().analyze("Объясни, почему не работает интернет?"),
            neural,
        )

        assertTrue(reply.contains("Я выделил тему"))
        assertFalse(generator.lastTrace.usedNeuralReadout)
    }

    @Test
    fun affirmativeFollowThroughAcceptsThePreviousAssistantOffer() {
        val generator = ANS100NeuralResponseGenerator()
        val history = listOf(
            LocalConversationMessage(
                id = "user-previous",
                role = ConversationRole.USER,
                text = "Как улучшить общение JARVIS?",
                createdAtMillis = 1_700_000_000_000L,
            ),
            LocalConversationMessage(
                id = "assistant-previous",
                role = ConversationRole.ASSISTANT,
                text = "Следующий этап — научить генератор учитывать контекст диалога. Хочешь, продолжим?",
                createdAtMillis = 1_700_000_000_001L,
            ),
        )

        val reply = generator.respond(
            "Давай",
            history,
            emptyList(),
            null,
            LocalUnderstandingPipeline().analyze("Давай", history),
            null,
        )

        assertTrue(reply.startsWith("Хорошо, продолжаю"))
        assertTrue(reply.contains("Следующий этап"))
        assertFalse(reply.contains("Какую именно тему"))
        assertEquals("continuation", generator.lastTrace.strategy)
    }

    @Test
    fun ellipticalWhyFollowUpKeepsThePreviousAssistantAnswerInContext() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Как работает память JARVIS?", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Память сохраняет подтверждённые записи.", 2L),
        )
        val understanding = LocalUnderstandingPipeline().analyze("Почему?", history)
        val generator = ANS100NeuralResponseGenerator()
        val reply = generator.respond("Почему?", history, emptyList(), null, understanding, null)
        assertTrue(reply.startsWith("Ты спрашиваешь, почему"))
        assertTrue(reply.contains("Память сохраняет подтверждённые записи"))
        assertEquals("continuation", generator.lastTrace.strategy)
    }

    @Test
    fun detailFollowUpContinuesTheSameTopicInsteadOfRequestingItAgain() {
        val history = listOf(
            LocalConversationMessage("user-1", ConversationRole.USER, "Расскажи про нейронный декодер", 1L),
            LocalConversationMessage("assistant-1", ConversationRole.ASSISTANT, "Декодер строит последовательность токенов.", 2L),
        )
        val understanding = LocalUnderstandingPipeline().analyze("Расскажи подробнее", history)
        val generator = ANS100NeuralResponseGenerator()
        val reply = generator.respond("Расскажи подробнее", history, emptyList(), null, understanding, null)
        assertTrue(reply.startsWith("Конечно. Разверну предыдущую мысль"))
        assertTrue(reply.contains("Декодер строит последовательность токенов"))
    }

    @Test
    fun trainedAnsSequenceDecoderCanProduceChatReplyWithoutLanguageModel() {
        val storage = object : ANS100SequenceModelStorage {
            var value: String? = null
            override fun readSnapshot(): String? = value
            override fun writeSnapshot(snapshot: String): Boolean {
                value = snapshot
                return true
            }
            override fun clearSnapshot(): Boolean {
                value = null
                return true
            }
        }
        val module = ANS100TrainableLanguageModule(storage)
        module.train(List(12) { "кот ест рыбу." }, epochs = 80)
        val generator = ANS100NeuralResponseGenerator(module)

        val reply = generator.respond(
            "кот",
            emptyList(),
            emptyList(),
            null,
            LocalUnderstandingPipeline().analyze("кот"),
        )

        assertTrue(reply.isNotBlank())
        assertTrue(generator.lastTrace.strategy == "learned_sequence_generation")
        assertTrue(generator.lastTrace.source == "trained_ans100_recurrent_decoder")
    }

    @Test
    fun coordinatorUsesAnsGeneratorAndDoesNotAdvertiseLanguageModel() {
        val storage = object : ConversationStorage {
            var value: String? = null
            override fun read(): String? = value
            override fun write(value: String): Boolean {
                this.value = value
                return true
            }
        }
        val repository = LocalConversationRepository(
            storage = storage,
            nowMillis = { 1_700_000_000_000L },
            newId = { prefix -> "$prefix-${System.nanoTime()}" },
        )
        val generator = ANS100NeuralResponseGenerator()
        val core = JarvisCore(repository, generator)

        val messages = core.send("Что ты умеешь?")

        assertTrue(messages.last().text.contains("внешнюю языковую модель"))
        assertFalse(core.status().languageModelConfigured)
        assertTrue(core.backendLabel.contains("ANS-100 Neural Response"))
    }
}
