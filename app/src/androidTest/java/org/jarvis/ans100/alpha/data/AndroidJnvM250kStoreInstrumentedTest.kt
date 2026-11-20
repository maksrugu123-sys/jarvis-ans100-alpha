package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Real SQLite integration coverage for the independent 250k JNVM profile.
 *
 * This test intentionally initializes the full 250,000-row profile and is
 * therefore an opt-in instrumentation test; it is not part of lightweight
 * local unit-test discovery.
 */
@RunWith(AndroidJUnit4::class)
class AndroidJnvM250kStoreInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun graphBatchAndCheckpointSurviveReopenAndRejectPartialOrStaleWrites() {
        val databaseName = "jnvm-250k-${UUID.randomUUID()}.db"
        var store: AndroidJnvM250kStore? = null
        try {
            store = AndroidJnvM250kStore(context, databaseName)
            val initialized = store.initialize()
            assertTrue(initialized.initialized)
            assertEquals(AndroidJnvM250kProfile.NEURON_CAPACITY, initialized.neuronCount)

            // Initialization must be idempotent and must not duplicate neuron rows.
            val initializedAgain = store.initialize()
            assertEquals(AndroidJnvM250kProfile.NEURON_CAPACITY, initializedAgain.neuronCount)

            val initialCheckpoint = AndroidJnvM250kSparseConnectionInitializer.initialCheckpoint(seed = 20261120L)
            val firstBatch = AndroidJnvM250kSparseConnectionInitializer.nextBatch(
                initialCheckpoint,
                batchSize = 64,
            )
            store.persistConnectionBatch(
                connections = firstBatch.connections,
                expectedCheckpoint = null,
                nextCheckpoint = firstBatch.checkpoint,
            )
            assertEquals(64, store.stats().connectionCount)
            assertEquals(firstBatch.connections.first(), store.outgoingPage(0, limit = 1).single())

            // Closing and reopening simulates process death between worker steps.
            store.close()
            store = AndroidJnvM250kStore(context, databaseName)
            assertEquals(firstBatch.checkpoint, store.loadConnectionInitializationCheckpoint())
            assertEquals(64, store.stats().connectionCount)

            // A stale worker must not commit its batch or advance the durable cursor.
            val nextBatch = AndroidJnvM250kSparseConnectionInitializer.nextBatch(
                firstBatch.checkpoint,
                batchSize = 8,
            )
            val staleFailure = runCatching {
                store.persistConnectionBatch(
                    connections = nextBatch.connections,
                    expectedCheckpoint = initialCheckpoint,
                    nextCheckpoint = nextBatch.checkpoint,
                )
            }.exceptionOrNull()
            assertNotNull(staleFailure)
            assertEquals(64, store.stats().connectionCount)
            assertEquals(firstBatch.checkpoint, store.loadConnectionInitializationCheckpoint())

            // Force a failure after one insert has begun: the preceding new edge
            // and all metadata must roll back with the duplicate-ID failure.
            val rollbackProbe = AndroidNeuronConnection(
                connectionId = "rollback-probe",
                sourceNeuronId = 0,
                targetNeuronId = 20_000,
                delayNs = 1_000_000L,
                weight = 0.5,
            )
            val duplicateFailure = runCatching {
                store.registerConnections(listOf(rollbackProbe, firstBatch.connections.first()))
            }.exceptionOrNull()
            assertNotNull(duplicateFailure)
            assertEquals(64, store.stats().connectionCount)
            assertTrue(store.outgoingPage(0, limit = 16).none { it.connectionId == rollbackProbe.connectionId })

            // Partial checkpoint metadata is treated as corruption, never as a
            // fresh run that could silently duplicate already committed edges.
            store.close()
            store = null
            val raw = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
            try {
                assertEquals(
                    1,
                    raw.delete(
                        "metadata",
                        "key=?",
                        arrayOf("sparse_initializer_v1_slot"),
                    ),
                )
            } finally {
                raw.close()
            }

            store = AndroidJnvM250kStore(context, databaseName)
            val corruptCheckpointFailure = runCatching {
                store.loadConnectionInitializationCheckpoint()
            }.exceptionOrNull()
            assertNotNull(corruptCheckpointFailure)
            assertNull(runCatching { store.loadConnectionInitializationCheckpoint() }.getOrNull())
        } finally {
            runCatching { store?.close() }
            context.deleteDatabase(databaseName)
        }
    }
}
