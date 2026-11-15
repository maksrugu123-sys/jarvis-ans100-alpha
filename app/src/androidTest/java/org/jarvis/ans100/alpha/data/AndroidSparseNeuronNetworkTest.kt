package org.jarvis.ans100.alpha.data

import android.content.ContentValues
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
    fun initializesTenThousandDialogueNeuronsAsThreeStageSparseGraph() {
        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 128).use { network ->
            network.initializeSparseDialogueNetwork()

            val stats = network.stats()
            assertEquals(10_000, stats.neuronCount)
            assertEquals(9_000 * 8 + 996 * 4, stats.connectionCount)
            assertEquals(0, stats.residentNeuronCount)
            assertEquals(128, stats.hotSetCapacity)
        }
    }

    @Test
    fun upgradesDirectFeatureReadoutWeightsIntoTheHiddenProjection() {
        AndroidSparseNeuronNetwork(context, databaseName).use { network ->
            network.initializeSparseDialogueNetwork()
        }

        // Recreate a v1 direct feature-to-output graph with a deliberately
        // distinguishable learned weight on feature 0, then remove its version tag.
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        db.beginTransaction()
        try {
            db.delete("connections", null, null)
            db.delete("metadata", "key='dialogue_topology_version'", null)
            val preservedClock = 42_000_000L
            val clockValues = ContentValues().apply { put("value", preservedClock) }
            db.update("metadata", clockValues, "key='model_time_ns'", null)
            for (outputIndex in 0 until AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_NEURONS) {
                val values = ContentValues().apply {
                    put("connection_id", "dialog-feature-0-output-$outputIndex")
                    put("source_id", 0)
                    put("target_id", AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START + outputIndex)
                    put("delay_ns", AndroidSparseNeuronNetwork.DEFAULT_CONNECTION_DELAY_NS)
                    put("weight", if (outputIndex == 0) 0.90 else 0.10)
                }
                db.insertOrThrow("connections", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }

        AndroidSparseNeuronNetwork(context, databaseName).use { upgraded ->
            upgraded.initializeSparseDialogueNetwork()
            val projectedHidden = AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START
            assertEquals(0.90, upgraded.dialogueSynapseWeight(
                projectedHidden,
                AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START,
            ), 0.000001)
            assertEquals(0.10, upgraded.dialogueSynapseWeight(
                projectedHidden,
                AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START + 1,
            ), 0.000001)
            assertEquals(9_000 * 8 + 996 * 4, upgraded.stats().connectionCount)
            assertEquals(preservedClock, upgraded.stats().currentTimeNs)
        }
    }

    @Test
    fun dialogueInputPropagatesThroughHiddenLayerToFourReadouts() {
        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 2).use { network ->
            network.initializeSparseDialogueNetwork()
            // IDs 0 and 996 map to the same eight hidden targets in the
            // deterministic sparse projection. Their combined input crosses
            // each hidden neuron's threshold; hidden spikes then reach readouts.
            network.scheduleInputs(
                listOf(
                    AndroidNeuronEvent("dialog-input-a", 10_000_000L, 0, 1.0),
                    AndroidNeuronEvent("dialog-input-b", 10_000_000L, 996, 1.0),
                ),
            )

            val result = network.run(maxEvents = 64, maxTimestamps = 4)
            val hiddenSpikes = result.emittedSpikes.map { it.first }
                .filter {
                    it in AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START until
                        AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START
                }
            val outputSpikes = result.emittedSpikes.map { it.first }
                .filter {
                    it in AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START until
                        AndroidSparseNeuronNetwork.MAX_NEURONS
                }

            assertEquals(2, result.emittedSpikes.count { it.first < AndroidSparseNeuronNetwork.DIALOGUE_INPUT_NEURONS })
            assertEquals(8, hiddenSpikes.size)
            assertEquals(4, outputSpikes.size)
            assertEquals(50, result.processedEvents)
            assertEquals(0, result.pendingEvents)
            assertTrue(network.stats().residentNeuronCount <= 2)
        }
    }

    @Test
    fun explicitDialogueRewardChangesOnlyRecordedHiddenToOutputSynapsesAndPersists() {
        val contextId = "dialog-feedback-test"
        val firstOutput = AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START
        val selectedOutput = firstOutput + 1
        val initialWeight = 0.15
        val startTime = 10_000_000L
        val unrelatedHidden = AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START + 500

        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 4).use { network ->
            network.initializeSparseDialogueNetwork(weight = initialWeight)
            // Two colliding input feature slots activate eight real hidden neurons,
            // which then propagate to the four output neurons.
            network.scheduleInputs(
                listOf(
                    AndroidNeuronEvent("feedback-input-a", startTime, 0, 1.0),
                    AndroidNeuronEvent("feedback-input-b", startTime, 996, 1.0),
                ),
            )
            val run = network.run(maxEvents = 64, maxTimestamps = 4)
            val hiddenIds = run.emittedSpikes.map { it.first }.filter {
                it in AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START until
                    AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START
            }
            val outputIds = run.emittedSpikes.map { it.first }.filter {
                it in AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START until
                    AndroidSparseNeuronNetwork.MAX_NEURONS
            }
            assertEquals(8, hiddenIds.size)
            assertEquals(4, outputIds.size)

            val recorded = network.recordDialogueEligibility(
                contextId = contextId,
                sourceNeuronIds = hiddenIds,
                outputNeuronIds = outputIds,
                preTimeNs = startTime + AndroidSparseNeuronNetwork.DEFAULT_CONNECTION_DELAY_NS,
                postTimeNs = run.currentTimeNs,
            )
            assertEquals(hiddenIds.size * 4, recorded)

            val weightsBefore = (0 until 4).associateWith { index ->
                network.dialogueSynapseWeight(hiddenIds.first(), firstOutput + index)
            }
            val rewards = (0 until 4).associate { index ->
                (firstOutput + index) to if (index == 1) 1.0 else -1.0 / 3.0
            }
            val applied = network.applyDialogueReward(contextId, hiddenIds, rewards)
            assertEquals(hiddenIds.size * 4, applied.updatedSynapses)
            assertEquals(0, applied.expiredSynapses)

            assertTrue(network.dialogueSynapseWeight(hiddenIds.first(), selectedOutput) > weightsBefore.getValue(1))
            for (index in 0 until 4) {
                if (index != 1) {
                    assertTrue(
                        network.dialogueSynapseWeight(hiddenIds.first(), firstOutput + index) <
                            weightsBefore.getValue(index),
                    )
                }
            }
            assertEquals(initialWeight, network.dialogueSynapseWeight(unrelatedHidden, selectedOutput), 0.000001)
            val afterFirstReward = network.dialogueSynapseWeight(hiddenIds.first(), selectedOutput)
            val duplicate = network.applyDialogueReward(contextId, hiddenIds, rewards)
            assertEquals(0, duplicate.updatedSynapses)
            assertEquals(afterFirstReward, network.dialogueSynapseWeight(hiddenIds.first(), selectedOutput), 0.000001)

            val scores = network.dialogueOutputScores(hiddenIds)
            assertTrue(scores.getValue(selectedOutput) > scores.getValue(firstOutput))
            // Ensuring an already-current topology is idempotent must not erase learning.
            network.initializeSparseDialogueNetwork(weight = initialWeight)
            assertEquals(afterFirstReward, network.dialogueSynapseWeight(hiddenIds.first(), selectedOutput), 0.000001)
        }

        AndroidSparseNeuronNetwork(context, databaseName, hotSetCapacity = 1).use { reopened ->
            assertTrue(reopened.dialogueSynapseWeight(
                AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START,
                selectedOutput,
            ) > initialWeight)
            assertTrue(reopened.dialogueSynapseWeight(
                AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START,
                firstOutput,
            ) < initialWeight)
            assertEquals(initialWeight, reopened.dialogueSynapseWeight(unrelatedHidden, selectedOutput), 0.000001)
        }
    }

    @Test
    fun invalidDialogueRewardIsRejectedBeforeAnyWeightChanges() {
        AndroidSparseNeuronNetwork(context, databaseName).use { network ->
            network.initializeSparseDialogueNetwork(weight = 0.15)
            val sourceNeuron = AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START
            val outputNeuron = AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START
            val before = network.dialogueSynapseWeight(sourceNeuron, outputNeuron)
            var rejected = false
            try {
                network.applyDialogueReward(
                    contextId = "invalid-reward-test",
                    sourceNeuronIds = listOf(sourceNeuron),
                    outputRewards = mapOf(outputNeuron to Double.NaN),
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(before, network.dialogueSynapseWeight(sourceNeuron, outputNeuron), 0.000001)
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
