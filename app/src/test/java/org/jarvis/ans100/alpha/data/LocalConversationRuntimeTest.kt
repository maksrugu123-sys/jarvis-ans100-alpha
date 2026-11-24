package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationRuntimeTest {
    private class MemoryStorage(
        var value: String? = null,
        var allowWrite: Boolean = true,
    ) : ConversationStorage {
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
    }

    private class SemanticMemoryStorage(
        var memory: LocalSemanticDialogueMemory = LocalSemanticDialogueMemory(),
        var exportLocation: String = "Downloads/JARVIS/test.jmem",
    ) : LocalSemanticDialogueMemoryStorage {
        var exportCount = 0
        var clearCount = 0
        override fun read(): LocalSemanticDialogueMemory = memory
        override fun write(memory: LocalSemanticDialogueMemory): Boolean {
            this.memory = memory
            return true
        }
        override fun exportSnapshot(): String {
            exportCount++
            return exportLocation
        }
        override fun clear(): Boolean {
            clearCount++
            memory = LocalSemanticDialogueMemory()
            return true
        }
    }

    private fun repository(storage: MemoryStorage): LocalConversationRepository {
        var clock = 1_700_000_000_000L
        var nextId = 0
        return LocalConversationRepository(
            storage = storage,
            nowMillis = { ++clock },
            newId = { prefix -> "$prefix-test-${nextId++}" },
        )
    }

    @Test
    fun exchangePersistsAndReopensAsCompletePair() {
        val storage = MemoryStorage()
        val saved = repository(storage).appendExchange("Привет", "Здравствуйте")
        val reopened = repository(storage).load()
        assertEquals(2, saved.size)
        assertEquals(saved, reopened)
        assertEquals(ConversationRole.USER, reopened[0].role)
        assertEquals(ConversationRole.ASSISTANT, reopened[1].role)
    }

    @Test
    fun historyRetainsNewestCompleteExchangesWithinBound() {
        val repo = repository(MemoryStorage())
        repeat(65) { index -> repo.appendExchange("user-$index", "assistant-$index") }
        val history = repo.load()
        assertEquals(LocalConversationRepository.MAX_MESSAGES, history.size)
        assertEquals("user-5", history.first().text)
        assertEquals("assistant-64", history.last().text)
    }

    @Test
    fun rejectsBlankAndOversizedMessages() {
        val repo = repository(MemoryStorage())
        var blankRejected = false
        var oversizedRejected = false
        try {
            repo.appendExchange(" ", "answer")
        } catch (error: LocalConversationException) {
            blankRejected = true
        }
        try {
            repo.appendExchange("x".repeat(4_001), "answer")
        } catch (error: LocalConversationException) {
            oversizedRejected = true
        }
        assertTrue(blankRejected)
        assertTrue(oversizedRejected)
    }

    @Test
    fun failedStorageCommitDoesNotReportExchangeAsSaved() {
        val storage = MemoryStorage(allowWrite = false)
        var rejected = false
        try {
            repository(storage).appendExchange("question", "answer")
        } catch (error: LocalConversationException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(null, storage.value)
    }

    @Test
    fun corruptPersistedHistoryIsRejected() {
        var rejected = false
        try {
            repository(MemoryStorage(value = "not-a-jarvis-history")).load()
        } catch (error: LocalConversationException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun clearPersistsAnEmptyHistory() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        repo.appendExchange("question", "answer")
        repo.clear()
        assertTrue(repository(storage).load().isEmpty())
    }


    @Test
    fun semanticMemorySurvivesConversationTurnsAndIsPassedIntoCoreAnalysis() {
        val semanticStorage = SemanticMemoryStorage()
        var seenMemory: LocalSemanticDialogueMemory? = null
        val engine = object : LocalResponseEngine {
            override val backendLabel = "fake"
            override fun respond(prompt: String, recentHistory: List<LocalConversationMessage>): String = "legacy"
            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
                memoryContext: List<LocalWorkspaceMemory>,
                signalContext: ConversationSignalContext?,
                understandingContext: LocalUnderstanding?,
            ): String {
                seenMemory = understandingContext?.semanticMemory
                return "Ответ"
            }
        }
        val coordinator = LocalConversationCoordinator(
            repository(MemoryStorage()), engine,
            semanticMemoryStorage = semanticStorage,
        )

        coordinator.send("Нужно добавить экспорт памяти JARVIS")
        coordinator.send("Теперь объясни, как работает CPU")

        assertTrue(semanticStorage.memory.activeTasks.any { it.text.contains("экспорт памяти") })
        assertTrue(seenMemory?.activeTasks?.any { it.text.contains("экспорт памяти") } == true)
    }

    @Test
    fun memoryExportCommandCreatesSnapshotWithoutCallingResponseEngine() {
        val semanticStorage = SemanticMemoryStorage()
        var engineCalled = false
        val engine = object : LocalResponseEngine {
            override val backendLabel = "fake"
            override fun respond(prompt: String, recentHistory: List<LocalConversationMessage>): String {
                engineCalled = true
                return "unexpected"
            }
        }
        val coordinator = LocalConversationCoordinator(
            repository(MemoryStorage()), engine,
            semanticMemoryStorage = semanticStorage,
        )

        val result = coordinator.send("/memory export")

        assertEquals(1, semanticStorage.exportCount)
        assertTrue(result.last().text.contains("Downloads/JARVIS/test.jmem"))
        assertTrue(!engineCalled)
    }

    @Test
    fun memoryResetCommandClearsPersistentMemoryAndOldConversation() {
        val semanticStorage = SemanticMemoryStorage(
            memory = LocalSemanticDialogueMemory(
                activeTasks = listOf(LocalSemanticTask("Задача", "open", "u1", 10L)),
            ),
        )
        val repo = repository(MemoryStorage())
        repo.appendExchange("Старый диалог", "Старый ответ")
        val coordinator = LocalConversationCoordinator(
            repo, LocalFallbackResponseEngine(),
            semanticMemoryStorage = semanticStorage,
        )

        val result = coordinator.send("/memory reset")

        assertEquals(1, semanticStorage.clearCount)
        assertTrue(semanticStorage.memory.activeTasks.isEmpty())
        assertEquals(2, result.size)
        assertTrue(result.last().text.contains("сброшены"))
        assertTrue(coordinator.semanticMemory().activeTasks.isEmpty())
    }

    @Test
    fun fallbackStatesThatLanguageModelIsNotInstalled() {
        val engine = LocalFallbackResponseEngine()
        assertTrue(engine.respond("/help", emptyList()).contains("базовый локальный режим"))
        assertTrue(engine.respond("объясни квантовую физику", emptyList())
            .contains("ответ без догадок"))
    }


    @Test
    fun coreStatusCommandReportsRealIntegrationBoundariesWithoutCallingLanguageEngine() {
        var engineCalled = false
        val engine = object : LocalResponseEngine {
            override val backendLabel = "fake-language-engine"
            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
            ): String {
                engineCalled = true
                return "model-reply"
            }
        }
        val coordinator = LocalConversationCoordinator(repository(MemoryStorage()), engine)

        val messages = coordinator.send("/status")

        assertTrue(!engineCalled)
        assertTrue(messages.last().text.contains("ANS-100"))
        assertTrue(messages.last().text.contains("сеть из 10 нейронов"))
        assertTrue(messages.last().text.contains("нет результата локального сигнального прохода"))
        assertTrue(messages.last().text.contains("Долговременная JPVR-память"))
        assertTrue(coordinator.backendLabel.startsWith("JARVIS Core"))
    }

    @Test
    fun normalConversationIsStillGeneratedByInjectedLanguageComponent() {
        var observedPrompt: String? = null
        val engine = object : LocalResponseEngine {
            override val backendLabel = "fake-language-engine"
            override val languageModelConfigured = true
            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
            ): String {
                observedPrompt = prompt
                return "language-generated reply"
            }
        }
        val coordinator = LocalConversationCoordinator(repository(MemoryStorage()), engine)

        val messages = coordinator.send("Объясни, как работает JARVIS Core")

        assertEquals("Объясни, как работает JARVIS Core", observedPrompt)
        assertEquals("language-generated reply", messages.last().text)
        assertTrue(coordinator.status().languageModelConfigured)
        assertTrue(!coordinator.status().ans100IntegratedIntoConversation)
    }

    @Test
    fun androidCoreOwnsUnderstandingAndPassesItBeforeLanguageGeneration() {
        var observedUnderstanding: LocalUnderstanding? = null
        val engine = object : LocalResponseEngine {
            override val backendLabel = "fake-language-engine"
            override val languageModelConfigured = true

            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
            ): String = "legacy reply"

            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
                memoryContext: List<LocalWorkspaceMemory>,
                signalContext: ConversationSignalContext?,
                understandingContext: LocalUnderstanding?,
            ): String {
                observedUnderstanding = understandingContext
                return "model reply"
            }
        }
        val coordinator = LocalConversationCoordinator(repository(MemoryStorage()), engine)

        val messages = coordinator.send("Почему это не работает?")

        assertEquals("model reply", messages.last().text)
        assertEquals("question", observedUnderstanding?.intent)
        assertTrue(observedUnderstanding?.containsNegation == true)
        assertTrue(observedUnderstanding?.reasoningPlan?.steps?.isNotEmpty() == true)
        assertTrue(!coordinator.status().externalActionsEnabled)
        assertTrue(coordinator.status().understandingIntegratedIntoConversation)
        assertEquals("question", coordinator.status().lastUnderstandingIntent)
        assertTrue(messages.last().text == "model reply")
    }

    @Test
    fun androidCoreProvidesRecentHistoryToItsUnderstandingPipeline() {
        val repoStorage = MemoryStorage()
        val repo = repository(repoStorage)
        repo.appendExchange(
            "Мы обсуждаем ошибку в проекте JARVIS",
            "Я нашёл описание проблемы.",
        )
        var seenUnderstanding: LocalUnderstanding? = null
        val engine = object : LocalResponseEngine {
            override val backendLabel = "fake-language-engine"
            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
            ): String = "legacy reply"

            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
                memoryContext: List<LocalWorkspaceMemory>,
                signalContext: ConversationSignalContext?,
                understandingContext: LocalUnderstanding?,
            ): String {
                seenUnderstanding = understandingContext
                return "model reply"
            }
        }
        val coordinator = LocalConversationCoordinator(repo, engine)

        coordinator.send("Продолжай с этим")

        assertTrue(seenUnderstanding?.hasRecentUserContext == true)
        assertTrue(seenUnderstanding?.reasoningPlan?.contextAvailable == true)
        assertTrue(seenUnderstanding?.reasoningPlan?.requiresClarification == false)
    }

    @Test
    fun androidFallbackReceivesCoreAnalysisEvenWhenLanguageModelIsAbsent() {
        val coordinator = LocalConversationCoordinator(
            repository(MemoryStorage()),
            LocalFallbackResponseEngine(),
        )

        val messages = coordinator.send("Найди ошибку в коде")

        assertTrue(messages.last().text.contains("не располагаю достаточными сведениями"))
        assertTrue(messages.last().text.contains("Языковая модель для этого ответа не использовалась"))
        assertTrue(!coordinator.status().languageModelConfigured)
        assertTrue(coordinator.status().understandingIntegratedIntoConversation)
        assertEquals("action_request", coordinator.status().lastUnderstandingIntent)
    }

    @Test
    fun coreUsesOnlyApprovedWorkspaceMemoriesAndPassesNeuralSignalToLanguageEngine() {
        val cache = LocalWorkspaceMemoryCache()
        cache.replace(listOf(
            LocalWorkspaceMemory(
                id = "superseded-android",
                content = "Android local offline browser memory deprecated",
                category = "device",
                updatedAt = "2026-11-14T00:00:00Z",
                userApproved = true,
                supersededById = "approved-android",
            ),
            LocalWorkspaceMemory(
                id = "approved-android",
                content = "Android local offline browser memory current",
                category = "device",
                updatedAt = "2026-11-15T00:00:00Z",
                userApproved = true,
            ),
            LocalWorkspaceMemory(
                id = "unapproved-android",
                content = "Android local offline secrets",
                category = "private",
                updatedAt = "2026-11-14T00:00:00Z",
                userApproved = false,
            ),
        ))
        var seenMemories: List<LocalWorkspaceMemory> = emptyList()
        var seenSignal: ConversationSignalContext? = null
        val engine = object : LocalResponseEngine {
            override val backendLabel = "fake-language-engine"
            override val languageModelConfigured = true
            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
            ): String = "legacy reply"

            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
                memoryContext: List<LocalWorkspaceMemory>,
                signalContext: ConversationSignalContext?,
            ): String {
                seenMemories = memoryContext
                seenSignal = signalContext
                return "context-aware reply"
            }
        }
        val signal = ConversationSignalContext(
            runtimeVersion = "JARVIS_ANS100_CONVERSATION_SIGNAL_V1",
            processedEvents = 4,
            processedBatches = 2,
            spikeCount = 2,
            attentionGateActive = true,
            tokenVolume = 0.4,
            lexicalDiversity = 1.0,
            questionMarker = 0.35,
            integratorAdaptation = 0.2,
            effectiveThreshold = 1.1,
            statePersisted = true,
        )
        val coordinator = LocalConversationCoordinator(
            repository(MemoryStorage()), engine, cache,
        )

        val messages = coordinator.send("Android local offline настройка?", signal)

        assertEquals("context-aware reply", messages.last().text)
        assertEquals(listOf("approved-android"), seenMemories.map { it.id })
        assertEquals(signal, seenSignal)
        assertTrue(coordinator.status().longTermMemoryIntegrated)
        assertEquals(1, coordinator.status().longTermMemoryRecordCount)
        assertEquals(1, coordinator.status().memoriesRetrievedLastTurn)
        assertTrue(coordinator.status().ans100SignalProcessorActive)
        assertTrue(coordinator.status().ans100AttentionGateActive)
        assertTrue(coordinator.status().ans100AdaptiveStatePersisted)
        assertTrue(seenSignal?.toModelContext()?.contains("Адаптивное состояние сохранено") == true)
    }

    @Test
    fun fallbackDoesNotClaimThatCachedMemoryWasUsedByAnUnavailableLanguageModel() {
        val cache = LocalWorkspaceMemoryCache()
        cache.replace(listOf(
            LocalWorkspaceMemory(
                id = "approved",
                content = "Android offline JARVIS",
                category = "device",
                updatedAt = "2026-11-14T00:00:00Z",
                userApproved = true,
            ),
        ))
        val coordinator = LocalConversationCoordinator(
            repository(MemoryStorage()),
            LocalFallbackResponseEngine(),
            cache,
        )

        coordinator.send("Как работает Android offline JARVIS?")

        assertTrue(coordinator.status().longTermMemoryIntegrated)
        assertEquals(1, coordinator.status().longTermMemoryRecordCount)
        assertEquals(0, coordinator.status().memoriesRetrievedLastTurn)
        assertTrue(!coordinator.status().languageModelConfigured)
    }

    @Test
    fun coordinatorUsesInjectedEngineAndStoresItsReply() {
        var observedPrompt: String? = null
        var observedContextSize = -1
        val engine = object : LocalResponseEngine {
            override val backendLabel = "test-engine"
            override fun respond(
                prompt: String,
                recentHistory: List<LocalConversationMessage>,
            ): String {
                observedPrompt = prompt
                observedContextSize = recentHistory.size
                return "test-reply"
            }
        }
        val repo = repository(MemoryStorage())
        val coordinator = LocalConversationCoordinator(repo, engine)
        val first = coordinator.send("  test-prompt  ")
        val second = coordinator.send("next")
        assertEquals("next", observedPrompt)
        assertEquals(2, observedContextSize)
        assertEquals("test-reply", first[1].text)
        assertEquals(4, second.size)
        assertEquals("next", second[2].text)
    }
}
