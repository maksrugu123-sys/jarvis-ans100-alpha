package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidHybridTopologyV2Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "ans100-hybrid-v2-test-${System.nanoTime()}.db"

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun definesSevenStableContiguousPopulationsWithinTenThousandNeuronLimit() {
        val populations = AndroidHybridTopologyV2.populations
        assertEquals(7, populations.size)
        assertEquals(10_000, populations.sumOf { it.size })
        assertEquals(0, populations.first().startInclusive)
        assertEquals(10_000, populations.last().endExclusive)
        populations.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endExclusive, right.startInclusive)
        }
        assertEquals(
            listOf(1_200, 2_800, 1_800, 1_500, 1_200, 900, 600),
            populations.map { it.size },
        )
    }

    @Test
    fun createsDeterministicSparseTypedConnectionsWithoutInputToDecisionShortcut() {
        val first = AndroidHybridTopologyV2.connections()
        val second = AndroidHybridTopologyV2.connections()
        assertEquals(27_000, first.size)
        assertEquals(first, second)
        assertEquals(first.size, first.map { it.connectionId }.toSet().size)
        assertTrue(first.size < AndroidHybridTopologyV2.MAX_CONNECTION_COUNT)

        val evidence = AndroidHybridTopologyV2.populations.first()
        val decision = AndroidHybridTopologyV2.populations.last()
        assertTrue(first.none { evidence.contains(it.sourceNeuronId) && decision.contains(it.targetNeuronId) })
        assertTrue(first.all { edge ->
            AndroidHybridTopologyV2.populations.any { it.contains(edge.sourceNeuronId) } &&
                AndroidHybridTopologyV2.populations.any { it.contains(edge.targetNeuronId) }
        })
        assertTrue(first.all { it.sourceNeuronId != it.targetNeuronId && it.delayNs > 0L })
    }

    @Test
    fun opensSeparateV2DatabaseAndInitializationIsIdempotent() {
        assertNotEquals(
            AndroidSparseNeuronNetwork.DEFAULT_DATABASE_NAME,
            AndroidHybridTopologyV2.DATABASE_NAME,
        )
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 16).use { network ->
            val stats = network.stats()
            assertEquals(10_000, stats.neuronCount)
            assertEquals(27_000, stats.connectionCount)
            assertEquals(0, stats.pendingEvents)
            assertEquals(0, stats.residentNeuronCount)
            assertEquals(16, stats.hotSetCapacity)
        }
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 16).use { reopened ->
            assertEquals(10_000, reopened.stats().neuronCount)
            assertEquals(27_000, reopened.stats().connectionCount)
        }
    }

    @Test
    fun refusesToOverwriteAnExistingDifferentOrIncompleteDatabase() {
        AndroidSparseNeuronNetwork(context, databaseName).use { existing ->
            existing.initializeSparseChain(neuronCount = 100)
            var rejected = false
            try {
                existing.initializeHybridTopologyV2()
            } catch (_: AndroidNeuronNetworkException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(100, existing.stats().neuronCount)
            assertEquals(99, existing.stats().connectionCount)
        }
    }

    @Test
    fun schedulesOnlyVerifiedV2EvidenceAndRequiresPreviousTurnToDrain() {
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 16).use { network ->
            fun batch(turnId: String, timestampNs: Long): AndroidHybridEvidenceInputAdapter.ActivationBatch {
                val record = TypedEvidenceRecord(
                    recordId = "user.message.text:0",
                    turnId = turnId,
                    feature = EvidenceFeature.USER_MESSAGE_TEXT,
                    source = EvidenceSourceKind.USER_MESSAGE,
                    status = EvidenceStatus.USER_PROVIDED,
                    value = EvidenceValue.Text("hello"),
                    activation = 1.0,
                    provenanceRef = "turn:$turnId:user_message",
                )
                return AndroidHybridEvidenceInputAdapter.createBatch(
                    TypedEvidenceBundle(turnId = turnId, records = listOf(record)),
                    timestampNs,
                )
            }

            assertTrue(network.scheduleHybridEvidence(batch("turn-a", 1L)))
            assertEquals(1, network.stats().pendingEvents)

            val firstPass = network.run(maxEvents = 64, maxTimestamps = 1)
            assertEquals(1, firstPass.processedEvents)
            assertTrue(network.stats().pendingEvents > 0)

            var rejectedWhilePreviousTurnPending = false
            try {
                network.scheduleHybridEvidence(batch("turn-b", 10_000_000L))
            } catch (_: AndroidNeuronBackpressureException) {
                rejectedWhilePreviousTurnPending = true
            }
            assertTrue(rejectedWhilePreviousTurnPending)

            network.run(maxEvents = 64, maxTimestamps = 16)
            assertEquals(0, network.stats().pendingEvents)
            assertTrue(network.scheduleHybridEvidence(batch("turn-b", 20_000_000L)))
            assertEquals(1, network.stats().pendingEvents)
        }
    }

    @Test
    fun legacyDialogueDatabaseCannotAcceptHybridV2Evidence() {
        val legacyName = "ans100-legacy-reject-hybrid-${System.nanoTime()}.db"
        try {
            AndroidSparseNeuronNetwork(context, legacyName).use { legacy ->
                legacy.initializeSparseDialogueNetwork()
                val record = TypedEvidenceRecord(
                    recordId = "user.message.text:0",
                    turnId = "legacy-test",
                    feature = EvidenceFeature.USER_MESSAGE_TEXT,
                    source = EvidenceSourceKind.USER_MESSAGE,
                    status = EvidenceStatus.USER_PROVIDED,
                    value = EvidenceValue.Text("hello"),
                    activation = 1.0,
                    provenanceRef = "turn:legacy-test:user_message",
                )
                val batch = AndroidHybridEvidenceInputAdapter.createBatch(
                    TypedEvidenceBundle(turnId = "legacy-test", records = listOf(record)),
                    1L,
                )
                var rejected = false
                try {
                    legacy.scheduleHybridEvidence(batch)
                } catch (_: AndroidNeuronNetworkException) {
                    rejected = true
                }
                assertTrue(rejected)
                assertEquals(75_984, legacy.stats().connectionCount)
            }
        } finally {
            context.deleteDatabase(legacyName)
        }
    }

    @Test
    fun hybridV2UsesItsOwnDatabaseAndDoesNotReplaceLegacyTopology() {
        val legacyName = "ans100-legacy-isolation-${System.nanoTime()}.db"
        try {
            AndroidSparseNeuronNetwork(context, legacyName).use { legacy ->
                legacy.initializeSparseDialogueNetwork()
                assertEquals(10_000, legacy.stats().neuronCount)
                assertEquals(75_984, legacy.stats().connectionCount)
            }
            AndroidHybridTopologyV2.open(context, databaseName).use { hybrid ->
                assertEquals(27_000, hybrid.stats().connectionCount)
            }
            AndroidSparseNeuronNetwork(context, legacyName).use { legacyReopened ->
                assertEquals(75_984, legacyReopened.stats().connectionCount)
            }
        } finally {
            context.deleteDatabase(legacyName)
        }
    }
}
