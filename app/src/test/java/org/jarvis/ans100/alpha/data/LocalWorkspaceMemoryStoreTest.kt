package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkspaceMemoryStoreTest {
    private class MemoryStorage(var value: String? = null, var allowWrite: Boolean = true) :
        ConversationStorage {
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
    }

    @Test
    fun cacheWithoutPersistentStorageDoesNotClaimDurableMemoryIsLoaded() {
        assertFalse(LocalWorkspaceMemoryCache().snapshot().loaded)
    }

    @Test
    fun mixedCaseWorkspaceIdsArePreservedExactly() {
        val storage = MemoryStorage()
        val cache = LocalWorkspaceMemoryCache(storage)
        val imported = LocalWorkspaceMemory(
            "Workspace-Memory.A1",
            "Case-sensitive identifier",
            "project",
            "2026-11-14",
            true,
        )
        cache.replace(listOf(imported))
        assertEquals(imported, LocalWorkspaceMemoryCache(storage).snapshot().records.single())
    }

    @Test
    fun approvedMemorySurvivesRepositoryRecreation() {
        val storage = MemoryStorage()
        val first = LocalWorkspaceMemoryCache(storage)
        val added = first.add("JARVIS должен помнить важную заметку", "project")
        val restored = LocalWorkspaceMemoryCache(storage).snapshot()

        assertTrue(restored.loaded)
        assertEquals(added, restored.records)
        assertEquals(true, restored.records.single().userApproved)
    }

    @Test
    fun workspaceRefreshMergesOnlyApprovedRecordsWithoutDroppingLocalNotes() {
        val storage = MemoryStorage()
        val cache = LocalWorkspaceMemoryCache(storage)
        cache.add("Локальная заметка", "local")
        cache.replace(listOf(
            LocalWorkspaceMemory("remote-1", "Подтверждённая запись", "shared", "2026-11-14", true),
            LocalWorkspaceMemory("remote-2", "Не подтверждённая запись", "shared", "2026-11-14", false),
        ))

        val records = cache.snapshot().records
        assertEquals(2, records.size)
        assertTrue(records.any { it.content == "Локальная заметка" })
        assertTrue(records.any { it.content == "Подтверждённая запись" })
        assertTrue(records.none { it.content == "Не подтверждённая запись" })
    }

    @Test
    fun failedCommitDoesNotPublishNewMemory() {
        val storage = MemoryStorage(allowWrite = false)
        val cache = LocalWorkspaceMemoryCache(storage)
        var rejected = false
        try {
            cache.add("Не сохранять без commit", "test")
        } catch (_: LocalConversationException) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(cache.snapshot().records.isEmpty())
    }

    @Test
    fun corruptMemorySnapshotIsPreservedAndMutationsFailClosed() {
        val storage = MemoryStorage("broken")
        val cache = LocalWorkspaceMemoryCache(storage)
        assertTrue(cache.snapshot().error != null)
        var rejected = false
        try {
            cache.add("Новая заметка", "test")
        } catch (_: LocalConversationException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals("broken", storage.value)
    }

    @Test
    fun boundsAreEnforcedForMemoryAndCategory() {
        val cache = LocalWorkspaceMemoryCache(MemoryStorage())
        var rejected = false
        try {
            cache.add("x".repeat(LocalWorkspaceMemoryCache.MAX_MEMORY_CHARS + 1), "test")
        } catch (_: LocalConversationException) {
            rejected = true
        }
        assertTrue(rejected)
        rejected = false
        try {
            cache.add("valid", "x".repeat(LocalWorkspaceMemoryCache.MAX_CATEGORY_CHARS + 1))
        } catch (_: LocalConversationException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun explicitCorrectionKeepsAuditHistoryButOnlyNewFactIsActive() {
        val storage = MemoryStorage()
        val cache = LocalWorkspaceMemoryCache(storage)
        val old = cache.add("Любимый цвет — синий", "preference").single()

        val history = cache.correct(old.id, "Любимый цвет — зелёный")
        val archived = history.first { it.id == old.id }
        val current = history.single { it.supersededById == null }

        assertEquals(current.id, archived.supersededById)
        assertEquals("Любимый цвет — синий", archived.content)
        assertEquals("Любимый цвет — зелёный", current.content)
        assertEquals(listOf(current), cache.activeRecords())
    }

    @Test
    fun correctionLineageSurvivesStorageRecreationAndV1SnapshotsRemainReadable() {
        val storage = MemoryStorage()
        val cache = LocalWorkspaceMemoryCache(storage)
        val old = cache.add("Тема — светлая", "preference").single()
        cache.correct(old.id, "Тема — тёмная")

        val restored = LocalWorkspaceMemoryCache(storage)
        assertEquals(2, restored.snapshot().records.size)
        assertEquals("Тема — тёмная", restored.activeRecords().single().content)

        val legacy = listOf(
            "JARVIS_ANDROID_APPROVED_MEMORY\t1",
            listOf("M", "bG9jYWwtbGVnYWN5", "cHJvamVjdA", "0JrQsNC6INC80L7QttC10L3QuNC1", "MjAyNi0xMS0yMg").joinToString("\t"),
        ).joinToString("\n")
        val migrated = LocalWorkspaceMemoryCodec.decode(legacy)
        assertEquals(1, migrated.size)
        assertEquals(null, migrated.single().supersededById)
    }

    @Test
    fun failedCorrectionCommitLeavesOldMemoryActive() {
        val storage = MemoryStorage()
        val cache = LocalWorkspaceMemoryCache(storage)
        val old = cache.add("Любимый цвет — синий", "preference").single()
        storage.allowWrite = false

        var rejected = false
        try {
            cache.correct(old.id, "Любимый цвет — зелёный")
        } catch (_: LocalConversationException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(listOf(old), cache.activeRecords())
    }
}
