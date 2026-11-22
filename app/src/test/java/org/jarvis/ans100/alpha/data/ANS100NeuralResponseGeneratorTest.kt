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
            classifierLabel = "greeting",
            classifierConfidenceMargin = 0.9,
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

        assertTrue(messages.last().text.contains("локально"))
        assertFalse(core.status().languageModelConfigured)
        assertTrue(core.backendLabel.contains("ANS-100 Neural Response"))
    }
}
