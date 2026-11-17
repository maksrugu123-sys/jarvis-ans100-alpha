package org.jarvis.ans100.alpha.data

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100StandaloneBenchmarkDatabaseResetTest {
    @Test
    fun missingDatabaseIsAlreadyCleanAndDoesNotCallDelete() {
        val root = createTempDirectory(prefix = "ans100-db-reset").toFile()
        try {
            val database = File(root, "not-created.db")
            var deleteCalls = 0

            deleteBenchmarkDatabaseIfPresent(
                databaseFile = database,
                deleteDatabase = { deleteCalls++; false },
                errorMessage = "reset failed",
            )

            assertEquals(0, deleteCalls)
            assertFalse(database.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deletesExistingDatabaseAndAllSqliteSidecars() {
        val root = createTempDirectory(prefix = "ans100-db-reset").toFile()
        try {
            val database = File(root, "benchmark.db")
            val artifacts = listOf(
                database,
                File("${database.path}-journal"),
                File("${database.path}-shm"),
                File("${database.path}-wal"),
                File(root, "${database.name}-mj-test"),
            )
            artifacts.forEach { it.writeText("fixture") }
            var deleteCalls = 0

            deleteBenchmarkDatabaseIfPresent(
                databaseFile = database,
                deleteDatabase = {
                    deleteCalls++
                    artifacts.forEach { it.delete() }
                    true
                },
                errorMessage = "reset failed",
            )

            assertEquals(1, deleteCalls)
            assertTrue(artifacts.none { it.exists() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesOrphanedWalSidecarWithoutMainDatabase() {
        val root = createTempDirectory(prefix = "ans100-db-reset").toFile()
        try {
            val database = File(root, "benchmark.db")
            val wal = File("${database.path}-wal")
            wal.writeText("fixture")

            deleteBenchmarkDatabaseIfPresent(
                databaseFile = database,
                deleteDatabase = { wal.delete() },
                errorMessage = "reset failed",
            )

            assertFalse(wal.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsFailureWhenDatabaseArtifactsRemain() {
        val root = createTempDirectory(prefix = "ans100-db-reset").toFile()
        try {
            val database = File(root, "benchmark.db")
            database.writeText("fixture")
            val message = "unable to reset isolated ANS-100 benchmark database"

            val failure = runCatching {
                deleteBenchmarkDatabaseIfPresent(
                    databaseFile = database,
                    deleteDatabase = { false },
                    errorMessage = message,
                )
            }.exceptionOrNull()

            assertTrue(failure is AndroidNeuronNetworkException)
            assertEquals(message, failure?.message)
            assertTrue(database.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
