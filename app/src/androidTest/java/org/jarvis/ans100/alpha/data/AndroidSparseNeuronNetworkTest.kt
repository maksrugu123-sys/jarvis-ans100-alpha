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
    fun explicitDialogueRewardChangesOnlyRecordedFeatureSynapsesAndPersists() {
        val contextId = "dialog-feedback-test"
        val featureIds = listOf(0, 4, 9)
        val firstOutput = AndroidSparseNeuronNetwork.DIALOGUE_FEATURE_NEURONS
        val selectedOutput = firstOutput + 1
        val initialWeight = 0.15

        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 4).use { network ->
            network.initializeSparseDialogueNetwork(weight = initialWeight)
            val startTime = 10_000_000L
            val featureEvents = featureIds.mapIndexed { index, featureId ->
                AndroidNeuronEvent("feedback-feature-$index", startTime, featureId, 1.0)
            }
            val outputDriveEvents = (0 until AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_NEURONS).map { index ->
                AndroidNeuronEvent(
                    "feedback-drive-$index",
                    startTime,
                    firstOutput + index,
                    0.90,
                )
            }
            network.scheduleInputs(featureEvents + outputDriveEvents)
            val result = network.run(maxEvents = 64, maxTimestamps = 4)
            val outputSpikes = result.emittedSpikes.map { it.first }
                .filter { it in firstOutput until AndroidSparseNeuronNetwork.MAX_NEURONS }
                .distinct()
            assertEquals(4, outputSpikes.size)

            val recorded = network.recordDialogueEligibility(
                contextId = contextId,
                featureNeuronIds = featureIds,
                outputNeuronIds = outputSpikes,
                preTimeNs = startTime,
                postTimeNs = result.currentTimeNs,
            )
            assertEquals(featureIds.size * 4, recorded)

            val weightsBefore = (0 until 4).associateWith { index ->
                network.dialogueSynapseWeight(0, firstOutput + index)
            }
            val rewards = (0 until 4).associate { index ->
                (firstOutput + index) to if (index == 1) 1.0 else -1.0 / 3.0
            }
            val applied = network.applyDialogueReward(contextId, featureIds, rewards)
            assertEquals(featureIds.size * 4, applied.updatedSynapses)
            assertEquals(0, applied.expiredSynapses)

            assertTrue(network.dialogueSynapseWeight(0, selectedOutput) > weightsBefore.getValue(1))
            for (index in 0 until 4) {
                if (index != 1) {
                    assertTrue(
                        network.dialogueSynapseWeight(0, firstOutput + index) <
                            weightsBefore.getValue(index),
                    )
                }
            }
            assertEquals(initialWeight, network.dialogueSynapseWeight(12, selectedOutput), 0.000001)
            val afterFirstReward = network.dialogueSynapseWeight(0, selectedOutput)
            val duplicate = network.applyDialogueReward(contextId, featureIds, rewards)
            assertEquals(0, duplicate.updatedSynapses)
            assertEquals(afterFirstReward, network.dialogueSynapseWeight(0, selectedOutput), 0.000001)
        }

        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 1).use { reopened ->
            assertTrue(reopened.dialogueSynapseWeight(0, selectedOutput) > initialWeight)
            assertTrue(reopened.dialogueSynapseWeight(0, firstOutput) < initialWeight)
            assertEquals(initialWeight, reopened.dialogueSynapseWeight(12, selectedOutput), 0.000001)
        }
    }

    @Test
    fun invalidDialogueRewardIsRejectedBeforeAnyWeightChanges() {
        AndroidSparseNeuronNetwork(context, databaseName).use { network ->
            network.initializeSparseDialogueNetwork(weight = 0.15)
            val before = network.dialogueSynapseWeight(0, AndroidSparseNeuronNetwork.DIALOGUE_FEATURE_NEURONS)
            var rejected = false
            try {
                network.applyDialogueReward(
                    contextId = "invalid-reward-test",
                    featureNeuronIds = listOf(0),
                    outputRewards = mapOf(AndroidSparseNeuronNetwork.DIALOGUE_FEATURE_NEURONS to Double.NaN),
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(before, network.dialogueSynapseWeight(0, AndroidSparseNeuronNetwork.DIALOGUE_FEATURE_NEURONS), 0.000001)
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
