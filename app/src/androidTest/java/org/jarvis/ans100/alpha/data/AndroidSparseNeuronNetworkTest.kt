package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSparseNeuronNetworkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "jnvm-test-${System.nanoTime()}.db"

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun initializesTenThousandSparseNeuronsWithoutLoadingThemAllIntoRam() {
        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 128).use { network ->
            network.initializeSparseChain(10_000)

            val stats = network.stats()
            assertEquals(10_000, stats.neuronCount)
            assertEquals(9_999, stats.connectionCount)
            assertEquals(0, stats.residentNeuronCount)
            assertEquals(128, stats.hotSetCapacity)
        }
    }

    @Test
    fun timestampCommitPropagatesSpikeAndPersistsQueue() {
        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 2).use { network ->
            network.initializeSparseChain(3, delayNs = 1_000_000L, weight = 1.0)
            network.scheduleInput("external-start", 10_000_000L, 0, 1.0)

            val first = network.run(maxEvents = 1, maxTimestamps = 1)
            assertEquals(1, first.processedEvents)
            assertEquals(listOf(0 to 1L), first.emittedSpikes)
            assertEquals(1, first.pendingEvents)

            val second = network.run(maxEvents = 1, maxTimestamps = 1)
            assertEquals(1, second.processedEvents)
            assertEquals(listOf(1 to 1L), second.emittedSpikes)
            assertEquals(1, second.pendingEvents)
            assertTrue(second.currentTimeNs > first.currentTimeNs)
        }
    }

    @Test
    fun durableStateCanBeReopenedAfterTheHotSetIsDiscarded() {
        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 1).use { network ->
            network.initializeSparseChain(2, delayNs = 1_000_000L, weight = 1.0)
            network.scheduleInput("restart-probe", 10_000_000L, 0, 1.0)
            network.run(maxEvents = 1, maxTimestamps = 1)
        }

        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 1).use { reopened ->
            val stats = reopened.stats()
            assertEquals(2, stats.neuronCount)
            assertEquals(1, stats.pendingEvents)
            assertEquals(10_000_000L, stats.currentTimeNs)
        }
    }
}
