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
    fun fallbackStatesThatLanguageModelIsNotInstalled() {
        val engine = LocalFallbackResponseEngine()
        assertTrue(engine.respond("/help", emptyList()).contains("не подключены"))
        assertTrue(engine.respond("объясни квантовую физику", emptyList())
            .contains("языковая модель ещё не подключена"))
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
        assertTrue(messages.last().text.contains("не интегрирован"))
        assertTrue(messages.last().text.contains("долговременная JPVR-память"))
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
        assertEquals("test-prompt", observedPrompt)
        assertEquals(2, observedContextSize)
        assertEquals("test-reply", first[1].text)
        assertEquals(4, second.size)
        assertEquals("next", second[2].text)
    }
}
