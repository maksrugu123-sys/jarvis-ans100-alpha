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
        assertTrue(reply.startsWith("Попробую ответить по существу"))
        assertTrue(reply.contains("предварительный разбор"))
        assertFalse(reply.contains("нет достаточных сведений"))
        assertEquals("explanation", generator.lastTrace.strategy)
        assertEquals("core_analysis_and_dialogue", generator.lastTrace.source)
    }

    @Test
    fun unknownQuestionStillReceivesAReasonedPreliminaryAnswer() {
        val generator = ANS100NeuralResponseGenerator()
        val prompt = "Почему квантовая физика важна?"
        val reply = generator.respond(
            prompt, emptyList(), emptyList(), null,
            LocalUnderstandingPipeline().analyze(prompt), null,
        )

        assertTrue(reply.startsWith("Попробую ответить по существу"))
        assertTrue(reply.contains("Вероятнее всего"))
        assertFalse(reply.contains("нет достаточных сведений"))
    }

    @Test
    fun capabilityQuestionIsAnsweredDirectlyAcrossNaturalPhrasings() {
        val generator = ANS100NeuralResponseGenerator()
        val prompts = listOf(
            "Что ты умеешь?",
            "Я задаю тебе вопрос: что ты умеешь? И ты называешь свои функции",
            "Назови свои функции и возможности"
        )
        prompts.forEach { prompt ->
            val reply = generator.respond(prompt, emptyList(), emptyList(), null,
                LocalUnderstandingPipeline().analyze(prompt), null)
            assertTrue("Capability prompt was not answered directly: $prompt; reply: $reply",
                reply.startsWith("Я могу вести диалог"))
        }
    }

    @Test
    fun selfDescriptionPromptGetsACompleteIntroduction() {
        val generator = ANS100NeuralResponseGenerator()
        val prompt = "Ты можешь рассказать о себе?"
        val reply = generator.respond(prompt, emptyList(), emptyList(), null,
            LocalUnderstandingPipeline().analyze(prompt), null)

        assertTrue(reply.startsWith("Я JARVIS"))
        assertTrue(reply.contains("ANS-100"))
        assertTrue(reply.contains("локальный"))
    }

    @Test
    fun questionAboutGuessingGetsADirectMetaAnswer() {
        val generator = ANS100NeuralResponseGenerator()
        val prompt = "Можешь всегда только догадываться?"
        val reply = generator.respond(prompt, emptyList(), emptyList(), null,
            LocalUnderstandingPipeline().analyze(prompt), null)

        assertTrue(reply.startsWith("Нет. Я не должен только догадываться"))
        assertTrue(reply.contains("предварительный ответ"))
    }

    @Test
    fun guessingQuestionIsRecognizedWithNaturalRussianWordOrder() {
        val generator = ANS100NeuralResponseGenerator()
        val prompts = listOf(
            "Можешь всегда только догадываться?",
            "Ты можешь ответить, а не просто догадываться?",
            "Ты умеешь не только предполагать, но и объяснять?"
        )
        prompts.forEach { prompt ->
            val reply = generator.respond(prompt, emptyList(), emptyList(), null,
                LocalUnderstandingPipeline().analyze(prompt), null)
            assertTrue("Prompt was not routed: $prompt; reply: $reply",
                reply.startsWith("Нет. Я не должен только догадываться"))
        }
    }

    @Test
    fun actionReplyRequiresACompletedSymbolicDependencyPlan() {
        val generator = ANS100NeuralResponseGenerator()
        val prompt = "Сделай резервную копию проекта"
        val parsed = LocalUnderstandingPipeline().analyze(prompt)
        val incomplete = parsed.copy(
            reasoningResult = LocalReasoningResult(
                status = LocalReasoningStatus.NO_PLAN,
                actionIds = emptyList(),
                steps = emptyList(),
                exploredStates = 3,
                finalFacts = setOf("message_received", "intent_recognized"),
                missingGoalFacts = setOf("response_context_ready"),
            ),
        )

        val reply = generator.respond(
            prompt, emptyList(), emptyList(), null, incomplete, null,
        )

        assertTrue(reply.contains("проверка зависимостей не нашла полного плана"))
        assertFalse(reply.contains("построил предварительный план"))
        assertFalse(reply.contains("внешние действия выполнялись"))
    }

    @Test
    fun actionReplyUsesOnlyStepsFromSolvedSymbolicPlan() {
        val generator = ANS100NeuralResponseGenerator()
        val prompt = "Сделай резервную копию проекта"
        val parsed = LocalUnderstandingPipeline().analyze(prompt)
        val solved = parsed.copy(
            reasoningResult = LocalReasoningResult(
                status = LocalReasoningStatus.SOLVED,
                actionIds = listOf("review_scope", "prepare_response"),
                steps = listOf("Проверить исходные данные", "Подготовить последовательность резервного копирования"),
                exploredStates = 4,
                finalFacts = setOf("response_context_ready"),
                missingGoalFacts = emptySet(),
            ),
        )

        val reply = generator.respond(
            prompt, emptyList(), emptyList(), null, solved, null,
        )

        assertTrue(reply.contains("Проверить исходные данные"))
        assertTrue(reply.contains("Подготовить последовательность резервного копирования"))
        assertFalse(reply.contains("выполнены"))
        assertTrue(reply.contains("факты не подтверждены"))
    }

    @Test
    fun comparisonWithoutStoredFactsStillProvidesAUsefulFramework() {
        val generator = ANS100NeuralResponseGenerator()
        val prompt = "Сравни трансформер и импульсные нейроны"
        val reply = generator.respond(
            prompt, emptyList(), emptyList(), null,
            LocalUnderstandingPipeline().analyze(prompt), null,
        )

        assertTrue(reply.contains("сравнение"))
        assertTrue(reply.contains("назначение и принцип работы"))
        assertTrue(reply.contains("не буду выдумывать победителя"))
    }

    @Test
    fun latestAnswerCorrectionUsesThePersistedPromptAndUpdatesTheDecoder() {
        val modelStorage = object : ANS100SequenceModelStorage {
            var snapshot: String? = null
            override fun readSnapshot(): String? = snapshot
            override fun writeSnapshot(snapshot: String): Boolean {
                this.snapshot = snapshot
                return true
            }
            override fun clearSnapshot(): Boolean {
                snapshot = null
                return true
            }
        }
        val module = ANS100TrainableLanguageModule(modelStorage)
        module.trainPairs(
            listOf(
                "вопрос о погоде" to "сегодня ясно.",
                "вопрос о памяти" to "память хранит контекст.",
                "вопрос о нейронах" to "нейроны обрабатывают сигналы.",
                "вопрос о проекте" to "проект развивается.",
            ),
            epochs = 20,
        )
        val conversationStorage = object : ConversationStorage {
            var value: String? = null
            override fun read(): String? = value
            override fun write(value: String): Boolean {
                this.value = value
                return true
            }
        }
        val coordinator = LocalConversationCoordinator(
            LocalConversationRepository(conversationStorage),
            ANS100NeuralResponseGenerator(module),
            sequenceLanguageModule = module,
        )
        coordinator.send("Объясни, что такое погода")
        val before = modelStorage.snapshot

        val report = coordinator.correctLatestAnswer("Погода — состояние атмосферы в конкретном месте и времени.")

        assertTrue(report != null)
        assertTrue(report!!.trainingTokenCount > 0)
        assertTrue(modelStorage.snapshot != before)
    }

    @Test
    fun unchangedLatestAnswerIsNotAcceptedAsCorrectionFeedback() {
        val modelStorage = object : ANS100SequenceModelStorage {
            var snapshot: String? = null
            override fun readSnapshot(): String? = snapshot
            override fun writeSnapshot(snapshot: String): Boolean {
                this.snapshot = snapshot
                return true
            }
            override fun clearSnapshot(): Boolean {
                snapshot = null
                return true
            }
        }
        val module = ANS100TrainableLanguageModule(modelStorage)
        module.trainPairs(
            listOf(
                "вопрос о погоде" to "сегодня ясно.",
                "вопрос о памяти" to "память хранит контекст.",
                "вопрос о нейронах" to "нейроны обрабатывают сигналы.",
                "вопрос о проекте" to "проект развивается.",
            ),
            epochs = 20,
        )
        val conversationStorage = object : ConversationStorage {
            var value: String? = null
            override fun read(): String? = value
            override fun write(value: String): Boolean {
                this.value = value
                return true
            }
        }
        val coordinator = LocalConversationCoordinator(
            LocalConversationRepository(conversationStorage),
            ANS100NeuralResponseGenerator(module),
            sequenceLanguageModule = module,
        )
        coordinator.send("Объясни, что такое погода")
        val originalAnswer = coordinator.history().last().text
        val before = modelStorage.snapshot

        val report = coordinator.correctLatestAnswer(originalAnswer)

        assertTrue(report == null)
        assertEquals(before, modelStorage.snapshot)
    }

    @Test
    fun latestAnswerCorrectionDoesNotTrainWithoutACompleteExchange() {
        val module = ANS100TrainableLanguageModule(object : ANS100SequenceModelStorage {
            override fun readSnapshot(): String? = null
            override fun writeSnapshot(snapshot: String): Boolean = true
            override fun clearSnapshot(): Boolean = true
        })
        val coordinator = LocalConversationCoordinator(
            LocalConversationRepository(object : ConversationStorage {
                override fun read(): String? = null
                override fun write(value: String): Boolean = true
            }),
            ANS100NeuralResponseGenerator(module),
            sequenceLanguageModule = module,
        )

        assertTrue(coordinator.correctLatestAnswer("Исправленный ответ") == null)
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

        assertTrue(reply.startsWith("Попробую ответить по существу"))
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

        assertTrue(messages.last().text.contains("внешней языковой модели"))
        assertFalse(core.status().languageModelConfigured)
        assertTrue(core.backendLabel.contains("ANS-100 Neural Response"))
    }
}
