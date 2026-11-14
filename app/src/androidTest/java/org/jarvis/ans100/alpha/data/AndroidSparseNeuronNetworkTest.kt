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
    fun initializesTenThousandDialogueNeuronsAsBoundedFeatureToOutputGraph() {
        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 128).use { network ->
            network.initializeSparseDialogueNetwork()

            val stats = network.stats()
            assertEquals(10_000, stats.neuronCount)
            assertEquals(9_996 * 4, stats.connectionCount)
            assertEquals(0, stats.residentNeuronCount)
            assertEquals(128, stats.hotSetCapacity)
        }
    }

    @Test
    fun dialogueFeatureSpikeFansOutToFourOutputsWithoutChainBacklog() {
        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 2).use { network ->
            network.initializeSparseDialogueNetwork()
            network.scheduleInput("dialog-feature-probe", 10_000_000L, 0, 1.0)

            val result = network.run(maxEvents = 5, maxTimestamps = 2)

            assertEquals(5, result.processedEvents)
            assertEquals(
                listOf(
                    0 to 1L,
                    9_996 to 1L,
                    9_997 to 1L,
                    9_998 to 1L,
                    9_999 to 1L,
                ),
                result.emittedSpikes,
            )
            assertEquals(0, result.pendingEvents)
            assertEquals(10_000, network.stats().neuronCount)
            assertTrue(network.stats().residentNeuronCount <= 2)
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
    fun sameTimestampFeatureBatchIsInsertedAndProcessedAtomically() {
        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 4).use { network ->
            network.initializeSparseChain(8, delayNs = 1_000_000L, weight = 0.75)
            network.scheduleInputs(
                listOf(
                    AndroidNeuronEvent("turn-a", 10_000_000L, 1, 1.0),
                    AndroidNeuronEvent("turn-b", 10_000_000L, 3, 1.0),
                    AndroidNeuronEvent("turn-c", 10_000_000L, 5, 1.0),
                ),
            )
            val result = network.run(maxEvents = 3, maxTimestamps = 1)
            assertEquals(3, result.processedEvents)
            assertEquals(listOf(1 to 1L, 3 to 1L, 5 to 1L), result.emittedSpikes)
            assertEquals(3, result.pendingEvents)
        }
    }

    @Test
    fun duplicateIdsRejectTheWholeBatchWithoutQueueMutation() {
        AndroidSparseNeuronNetwork(context, databaseName).use { network ->
            network.initializeSparseChain(3)
            var rejected = false
            try {
                network.scheduleInputs(
                    listOf(
                        AndroidNeuronEvent("duplicate", 10_000_000L, 0, 1.0),
                        AndroidNeuronEvent("duplicate", 10_000_000L, 1, 1.0),
                    ),
                )
            } catch (_: AndroidNeuronNetworkException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(0, network.stats().pendingEvents)
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
