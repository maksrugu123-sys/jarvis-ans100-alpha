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
