package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryDraftStorage : DraftStorage {
    var value: String? = null
    var acceptWrites = true
    var writes = 0

    override fun read(): String? = value

    override fun write(value: String): Boolean {
        writes += 1
        if (!acceptWrites) return false
        this.value = value
        return true
    }
}

private class SequentialIds : (String) -> String {
    private var next = 1
    override fun invoke(prefix: String): String = "$prefix-${next++.toString().padStart(4, '0')}"
}

class LocalDraftRepositoryTest {
    private fun repository(
        storage: MemoryDraftStorage,
        now: Long = 1_700_000_000_000L,
    ) = LocalDraftRepository(
        storage = storage,
        nowMillis = { now },
        newId = SequentialIds(),
    )

    @Test
    fun sessionsAndDraftsSurviveRepositoryRecreation() {
        val storage = MemoryDraftStorage()
        val first = repository(storage)
        val afterSession = first.createSession("  Личная работа  ")
        val session = afterSession.sessions.single()
        assertEquals("Личная работа", session.title)

        val afterTask = first.createTask(session.id, "Составить план")
        assertEquals(1, afterTask.tasks.size)

        val secondProcessView = repository(storage).load()
        assertEquals(afterTask, secondProcessView)
        assertEquals(session.id, secondProcessView.tasks.single().sessionId)
        assertEquals("Составить план", secondProcessView.tasks.single().title)
    }

    @Test
    fun sessionTransitionsUseRevisionsAndClosedStateIsTerminal() {
        val storage = MemoryDraftStorage()
        val repository = repository(storage)
        val session = repository.createSession("Проект").sessions.single()

        val paused = repository.setSessionStatus(
            session.id, LocalSessionStatus.PAUSED, session.revision,
        ).sessions.single()
        assertEquals(LocalSessionStatus.PAUSED, paused.status)
        assertEquals(session.revision + 1, paused.revision)

        val reopened = repository.setSessionStatus(
            session.id, LocalSessionStatus.OPEN, paused.revision,
        ).sessions.single()
        val closed = repository.setSessionStatus(
            session.id, LocalSessionStatus.CLOSED, reopened.revision,
        ).sessions.single()
        assertEquals(LocalSessionStatus.CLOSED, closed.status)
        assertEquals(closed, repository.load().sessions.single())
    }

    @Test
    fun staleSessionRevisionIsRejectedWithoutChangingStoredState() {
        val storage = MemoryDraftStorage()
        val repository = repository(storage)
        val session = repository.createSession("Ревизии").sessions.single()
        repository.setSessionStatus(
            session.id, LocalSessionStatus.PAUSED, session.revision,
        )
        val saved = repository.load()

        try {
            repository.setSessionStatus(
                session.id, LocalSessionStatus.OPEN, session.revision,
            )
            throw AssertionError("stale revision should have been rejected")
        } catch (_: LocalDraftException) {
            assertEquals(saved, repository.load())
        }
    }

    @Test
    fun taskCreationIsRejectedForPausedOrClosedSessions() {
        val storage = MemoryDraftStorage()
        val repository = repository(storage)
        val session = repository.createSession("Закрываемая").sessions.single()
        repository.setSessionStatus(
            session.id, LocalSessionStatus.PAUSED, session.revision,
        )

        try {
            repository.createTask(session.id, "Не должно сохраниться")
            throw AssertionError("paused session must reject drafts")
        } catch (_: LocalDraftException) {
            assertTrue(repository.load().tasks.isEmpty())
        }

        val paused = repository.load().sessions.single()
        val closed = repository.setSessionStatus(
            session.id, LocalSessionStatus.CLOSED, paused.revision,
        ).sessions.single()
        try {
            repository.createTask(session.id, "После закрытия")
            throw AssertionError("closed session must reject drafts")
        } catch (_: LocalDraftException) {
            assertEquals(LocalSessionStatus.CLOSED, repository.load().sessions.single().status)
            assertTrue(repository.load().tasks.isEmpty())
        }
    }

    @Test
    fun repeatedCurrentStatusDoesNotWriteAnotherSnapshot() {
        val storage = MemoryDraftStorage()
        val repository = repository(storage)
        val session = repository.createSession("Без изменений").sessions.single()
        val writesBefore = storage.writes

        val unchanged = repository.setSessionStatus(
            session.id, LocalSessionStatus.OPEN, session.revision,
        )

        assertEquals(writesBefore, storage.writes)
        assertEquals(session.revision, unchanged.sessions.single().revision)
    }

    @Test
    fun failedStorageCommitDoesNotPublishNewSession() {
        val storage = MemoryDraftStorage()
        val repository = repository(storage)
        storage.acceptWrites = false

        try {
            repository.createSession("Не сохранится")
            throw AssertionError("failed commit must be reported")
        } catch (_: LocalDraftException) {
            assertNull(storage.value)
            assertTrue(repository.load().sessions.isEmpty())
        }
    }

    @Test
    fun unknownSchemaVersionIsRejectedWithoutResettingData() {
        val storage = MemoryDraftStorage()
        storage.value = "JARVIS_ANDROID_DRAFTS\t99"

        try {
            repository(storage).load()
            throw AssertionError("unknown schema must be rejected")
        } catch (error: LocalDraftException) {
            assertNotNull(error.message)
            assertTrue(storage.value!!.endsWith("\t99"))
        }
    }

    @Test
    fun sessionCapacityIsBounded() {
        val storage = MemoryDraftStorage()
        val ids = SequentialIds()
        val repository = LocalDraftRepository(
            storage = storage,
            nowMillis = { 1_700_000_000_000L },
            newId = ids,
        )
        repeat(LocalDraftRepository.MAX_SESSIONS) { index ->
            repository.createSession("Сессия ${index + 1}")
        }
        assertEquals(LocalDraftRepository.MAX_SESSIONS, repository.load().sessions.size)
        try {
            repository.createSession("Лишняя")
            throw AssertionError("session limit must be enforced")
        } catch (_: LocalDraftException) {
            assertEquals(LocalDraftRepository.MAX_SESSIONS, repository.load().sessions.size)
        }
    }

    @Test
    fun invalidTitlesAreRejectedBeforeWriting() {
        val storage = MemoryDraftStorage()
        val repository = repository(storage)
        val before = storage.writes
        try {
            repository.createSession("   ")
            throw AssertionError("blank title must be rejected")
        } catch (_: LocalDraftException) {
            assertEquals(before, storage.writes)
        }
        try {
            repository.createSession("x".repeat(LocalDraftRepository.MAX_TITLE_CHARS + 1))
            throw AssertionError("oversized title must be rejected")
        } catch (_: LocalDraftException) {
            assertEquals(before, storage.writes)
        }
    }
}
