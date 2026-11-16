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

    @Test
    fun hybridRunReportsPropagatedSynapsesThatReachSpikingTargets() {
        val slot = ANS100CandidateSlotContract.Slot.ANSWER
        val sourceLane = ANS100CandidateSlotContract.associationLane(
            slot,
            ANS100CandidateSlotContract.Polarity.SUPPORT,
        )
        val targetLane = ANS100CandidateSlotContract.workingLane(
            slot,
            ANS100CandidateSlotContract.Polarity.SUPPORT,
        )
        val edge = AndroidHybridTopologyV2.connections().first {
            sourceLane.contains(it.sourceNeuronId) && targetLane.contains(it.targetNeuronId)
        }

        AndroidSparseNeuronNetwork(context, databaseName).use { network ->
            network.initializeHybridTopologyV2()
            network.scheduleInput(
                eventId = "test-source-spike",
                timeNs = 1_000_000L,
                neuronId = edge.sourceNeuronId,
                strength = 1.0,
            )
            val sourcePass = network.run(maxEvents = 64, maxTimestamps = 1)
            assertTrue(sourcePass.emittedSpikes.any { it.first == edge.sourceNeuronId })

            val targetPass = network.run(maxEvents = 64, maxTimestamps = 1)
            assertTrue(targetPass.emittedSpikes.any { it.first == edge.targetNeuronId })
            assertTrue(targetPass.activatedConnectionIds.contains(edge.connectionId))
        }
    }

    @Test
    fun hybridCandidateFeedbackIsIsolatedBoundedAndPersisted() {
        val slot = ANS100CandidateSlotContract.Slot.ANSWER
        val association = ANS100CandidateSlotContract.associationLane(slot, ANS100CandidateSlotContract.Polarity.SUPPORT)
        val working = ANS100CandidateSlotContract.workingLane(slot, ANS100CandidateSlotContract.Polarity.SUPPORT)
        val edge = AndroidHybridTopologyV2.connections().first {
            association.contains(it.sourceNeuronId) && working.contains(it.targetNeuronId)
        }
        val turnId = "hybrid-feedback-test"

        AndroidSparseNeuronNetwork(context, databaseName).use { network ->
            network.initializeHybridTopologyV2()
            assertEquals(
                1,
                network.recordHybridCandidateEligibility(
                    turnId,
                    slot.candidateId,
                    listOf(edge.connectionId),
                    1.0,
                ),
            )
            // A model-generated answer is not feedback and must not train the network.
            assertEquals(
                0,
                network.applyHybridCandidateFeedback(
                    turnId,
                    slot.candidateId,
                    -1.0,
                    ANS100CandidateFeedbackLearner.FeedbackSource.MODEL_GENERATED,
                ),
            )
            assertEquals(
                1,
                network.recordHybridCandidateEligibility(
                    turnId,
                    slot.candidateId,
                    listOf(edge.connectionId),
                    1.0,
                ),
            )
            assertEquals(
                1,
                network.applyHybridCandidateFeedback(
                    turnId,
                    slot.candidateId,
                    -1.0,
                    ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                ),
            )
        }

        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            val weight = db.rawQuery(
                "SELECT weight FROM connections WHERE connection_id=?",
                arrayOf(edge.connectionId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getDouble(0)
            }
            assertEquals(0.99, weight, 0.000001)
        } finally {
            db.close()
        }

        AndroidSparseNeuronNetwork(context, databaseName).use { reopened ->
            reopened.initializeHybridTopologyV2()
        }
        val persistedDb = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            val weight = persistedDb.rawQuery(
                "SELECT weight FROM connections WHERE connection_id=?",
                arrayOf(edge.connectionId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getDouble(0)
            }
            assertEquals(0.99, weight, 0.000001)
        } finally {
            persistedDb.close()
        }
    }

    @Test
    fun hybridCandidateEligibilityRejectsSynapsesFromAnotherCandidateLane() {
        val answer = ANS100CandidateSlotContract.Slot.ANSWER
        val clarification = ANS100CandidateSlotContract.Slot.CLARIFICATION
        val sourceLane = ANS100CandidateSlotContract.associationLane(
            clarification,
            ANS100CandidateSlotContract.Polarity.SUPPORT,
        )
        val targetLane = ANS100CandidateSlotContract.workingLane(
            clarification,
            ANS100CandidateSlotContract.Polarity.SUPPORT,
        )
        val foreignEdge = AndroidHybridTopologyV2.connections().first {
            sourceLane.contains(it.sourceNeuronId) && targetLane.contains(it.targetNeuronId)
        }

        AndroidSparseNeuronNetwork(context, databaseName).use { network ->
            network.initializeHybridTopologyV2()
            var rejected = false
            try {
                network.recordHybridCandidateEligibility(
                    "foreign-lane-test",
                    answer.candidateId,
                    listOf(foreignEdge.connectionId),
                    1.0,
                )
            } catch (_: AndroidNeuronNetworkException) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }

    @Test
    fun conceptInputSlotsPersistAcrossReopenAndNewConceptsNeverMoveOldAssignments() {
        val initialIds = listOf("memory:beta", "memory:alpha")
        var firstAssignments: Map<String, Int> = emptyMap()
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            firstAssignments = network.resolveConceptInputSlots(initialIds)
            assertEquals(2, firstAssignments.size)
            assertEquals(ANS100ConceptMemory.NEURAL_INPUT_START, firstAssignments.getValue("memory:alpha"))
            assertEquals(ANS100ConceptMemory.NEURAL_INPUT_START + 1, firstAssignments.getValue("memory:beta"))
            val extended = network.resolveConceptInputSlots(listOf("memory:gamma", "memory:alpha"))
            assertEquals(firstAssignments.getValue("memory:alpha"), extended.getValue("memory:alpha"))
            assertEquals(ANS100ConceptMemory.NEURAL_INPUT_START + 2, extended.getValue("memory:gamma"))
        }
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { reopened ->
            assertEquals(firstAssignments, reopened.resolveConceptInputSlots(initialIds))
            assertEquals(ANS100ConceptMemory.NEURAL_INPUT_START + 2,
                reopened.resolveConceptInputSlots(listOf("memory:gamma")).getValue("memory:gamma"))
        }
    }

    @Test
    fun schemaFourUpgradeResetsUnstableConceptWeightsAndClearsOldAssociationCredit() {
        val projection = AndroidHybridTopologyV2.conceptAssociationProjection(
            ANS100ConceptMemory.NEURAL_INPUT_START,
        )
        val edge = AndroidHybridTopologyV2.connections().first { it.connectionId == projection.values.first() }
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            network.initializeHybridTopologyV2()
        }
        val legacy = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            val weight = ContentValues().apply { put("weight", 0.91) }
            legacy.update("connections", weight, "connection_id=?", arrayOf(edge.connectionId))
            val eligibility = ContentValues().apply {
                put("turn_id", "legacy-turn")
                put("candidate_id", "neural-association:" + "c".repeat(32))
                put("connection_id", edge.connectionId)
                put("eligibility", 0.5)
                put("created_at_ms", System.currentTimeMillis())
            }
            legacy.insertOrThrow("neural_association_eligibility", null, eligibility)
            val feedback = ContentValues().apply {
                put("feedback_id", "legacy-feedback")
                put("turn_id", "legacy-turn")
                put("candidate_id", "neural-association:" + "c".repeat(32))
                put("reward", 1.0)
                put("source", "EXPLICIT_USER")
                put("created_at_ms", System.currentTimeMillis())
            }
            legacy.insertOrThrow("neural_association_feedback_events", null, feedback)
            legacy.version = 4
        } finally {
            legacy.close()
        }
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { upgraded ->
            assertEquals(AndroidHybridTopologyV2.EXPECTED_CONNECTION_COUNT, upgraded.stats().connectionCount)
            assertEquals(
                AndroidHybridTopologyV2.CONCEPT_ASSOCIATION_INITIAL_WEIGHT,
                upgraded.neuralAssociationWeights(listOf(edge.connectionId)).getValue(edge.connectionId),
                0.000001,
            )
            val assigned = upgraded.resolveConceptInputSlots(listOf("memory:after-migration"))
            assertEquals(ANS100ConceptMemory.NEURAL_INPUT_START, assigned.getValue("memory:after-migration"))
        }
        val migrated = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            fun count(table: String) = migrated.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
            assertEquals(0, count("neural_association_eligibility"))
            assertEquals(0, count("neural_association_feedback_events"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun neuralAssociationFeedbackIsPersistedAndDuplicateEventIsIdempotent() {
        val start = ANS100ConceptMemory.NEURAL_INPUT_START
        val end = start + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
        var candidateEdges: List<String>? = null
        loop@ for (left in start until end) {
            val leftProjection = AndroidHybridTopologyV2.conceptAssociationProjection(left)
            for (right in left + 1 until end) {
                val rightProjection = AndroidHybridTopologyV2.conceptAssociationProjection(right)
                val shared = leftProjection.keys.intersect(rightProjection.keys).sorted()
                if (shared.isNotEmpty()) {
                    candidateEdges = shared.flatMap { target ->
                        listOf(leftProjection.getValue(target), rightProjection.getValue(target))
                    }.distinct()
                    break@loop
                }
            }
        }
        val edges = requireNotNull(candidateEdges)
        val turnId = "neural-feedback-turn"
        val candidateId = "neural-association:" + "a".repeat(32)
        val feedbackId = "neural-feedback-event-1"
        var persistedExpected: Map<String, Double> = emptyMap()

        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            val saved = network.recordNeuralAssociationEligibility(turnId, candidateId, edges, 0.8)
            assertEquals(edges.size, saved)
            val before = network.neuralAssociationWeights(edges)
            assertEquals(edges.size, before.size)
            assertTrue(before.values.all { it in 0.0..1.0 })
            assertTrue(before.values.all { kotlin.math.abs(it - AndroidHybridTopologyV2.CONCEPT_ASSOCIATION_INITIAL_WEIGHT) < 0.000001 })

            val updated = network.applyNeuralAssociationFeedback(
                feedbackId, turnId, candidateId, 1.0,
                ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
            )
            assertEquals(edges.size, updated)
            val after = network.neuralAssociationWeights(edges)
            assertTrue(edges.all { after.getValue(it) > before.getValue(it) })

            // A retried tap with the same event ID must not train a second time.
            assertEquals(0, network.applyNeuralAssociationFeedback(
                feedbackId, turnId, candidateId, 1.0,
                ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
            ))
            assertEquals(after, network.neuralAssociationWeights(edges))
            persistedExpected = after
        }

        // The learned connection weights survive closing and reopening SQLite.
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { reopened ->
            assertEquals(persistedExpected, reopened.neuralAssociationWeights(edges))
        }
    }

    @Test
    fun neuralAssociationRejectsUntrustedFeedbackAndOutOfProjectionSynapses() {
        val start = ANS100ConceptMemory.NEURAL_INPUT_START
        val end = start + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
        var edges: List<String>? = null
        loop@ for (source in start until end) {
            val left = AndroidHybridTopologyV2.conceptAssociationProjection(source)
            for (other in source + 1 until end) {
                val right = AndroidHybridTopologyV2.conceptAssociationProjection(other)
                val shared = left.keys.intersect(right.keys).firstOrNull() ?: continue
                edges = listOf(left.getValue(shared), right.getValue(shared))
                break@loop
            }
        }
        val candidateEdges = requireNotNull(edges)
        val turnId = "neural-feedback-rejected"
        val candidateId = "neural-association:" + "b".repeat(32)

        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            val unrelatedEvidenceEdge = AndroidHybridTopologyV2.connections().first {
                it.sourceNeuronId < start &&
                    it.connectionId.startsWith("ans100-v4-evidence-general-association-")
            }
            var rejectedForeignEdge = false
            try {
                network.recordNeuralAssociationEligibility(
                    turnId, candidateId, listOf(unrelatedEvidenceEdge.connectionId), 0.5,
                )
            } catch (_: AndroidNeuronNetworkException) {
                rejectedForeignEdge = true
            }
            assertTrue(rejectedForeignEdge)

            network.recordNeuralAssociationEligibility(turnId, candidateId, candidateEdges, 0.5)
            val before = network.neuralAssociationWeights(candidateEdges)
            assertEquals(0, network.applyNeuralAssociationFeedback(
                "model-generated-feedback", turnId, candidateId, 1.0,
                ANS100CandidateFeedbackLearner.FeedbackSource.MODEL_GENERATED,
            ))
            assertEquals(before, network.neuralAssociationWeights(candidateEdges))
            assertEquals(0, network.applyNeuralAssociationFeedback(
                "no-eligibility-feedback", turnId, candidateId, -1.0,
                ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
            ))
        }
    }

    @Test
    fun schemaThreeUpgradeAddsLearningTablesWithoutRebuildingTopology() {
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            assertEquals(AndroidHybridTopologyV2.EXPECTED_CONNECTION_COUNT, network.stats().connectionCount)
        }
        val legacy = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            legacy.execSQL("DROP TABLE IF EXISTS neural_association_eligibility")
            legacy.execSQL("DROP TABLE IF EXISTS neural_association_feedback_events")
            legacy.execSQL(
                "UPDATE connections SET weight=1.0 WHERE source_id BETWEEN ? AND ?",
                arrayOf(
                    ANS100ConceptMemory.NEURAL_INPUT_START,
                    ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS - 1,
                ),
            )
            legacy.version = 3
        } finally {
            legacy.close()
        }
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { upgraded ->
            assertEquals(AndroidHybridTopologyV2.EXPECTED_CONNECTION_COUNT, upgraded.stats().connectionCount)
            val projection = AndroidHybridTopologyV2.conceptAssociationProjection(ANS100ConceptMemory.NEURAL_INPUT_START)
            val connectionIds = projection.values.take(2)
            val weights = upgraded.neuralAssociationWeights(connectionIds)
            assertEquals(2, weights.size)
            assertTrue(weights.values.all {
                kotlin.math.abs(it - AndroidHybridTopologyV2.CONCEPT_ASSOCIATION_INITIAL_WEIGHT) < 0.000001
            })
        }
    }

    private fun grownProposalForTest(): ANS100ActivityDrivenStructuralPlasticity.GrowthProposal {
        val learner = ANS100ActivityDrivenStructuralPlasticity()
        fun active(id: String, neuron: Int) =
            ANS100ActivityDrivenStructuralPlasticity.ActiveConcept(
                conceptId = "concept:$id",
                neuronId = neuron,
                label = "Понятие $id",
                provenanceRef = "verified-turn:$id",
            )
        val pair = listOf(active("alpha", 1_062), active("beta", 1_063))
        learner.observeTurn("growth-turn-1", pair, emptyMap(), emptyMap())
        learner.observeTurn("growth-turn-2", pair, emptyMap(), emptyMap())
        return learner.observeTurn("growth-turn-3", pair, emptyMap(), emptyMap())
            .emittedProposals.single()
    }

    @Test
    fun structuralGrowthPersistsBothSynapsesAndFeedbackIsIdempotent() {
        val proposal = grownProposalForTest()
        val ids = proposal.connections.map { it.connectionId }.sorted()
        var persisted = emptyMap<String, Double>()

        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            network.initializeHybridTopologyV2()
            assertEquals(2, network.addStructuralGrowthProposal(proposal))
            assertEquals(0, network.addStructuralGrowthProposal(proposal))
            assertEquals(2, network.structuralSynapseWeights(ids).size)
            val before = network.structuralSynapseWeights(ids)
            assertEquals(setOf(0.25), before.values.toSet())

            assertEquals(
                2,
                network.adjustStructuralSynapseWeights(
                    "structural-feedback-1",
                    ids,
                    0.10,
                    ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                ),
            )
            val after = network.structuralSynapseWeights(ids)
            assertTrue(after.values.all { it > before.values.first() })

            // Replaying the same feedback event cannot reinforce twice.
            assertEquals(
                0,
                network.adjustStructuralSynapseWeights(
                    "structural-feedback-1",
                    ids,
                    0.10,
                    ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                ),
            )
            assertEquals(after, network.structuralSynapseWeights(ids))
            persisted = after
        }

        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { reopened ->
            assertEquals(persisted, reopened.structuralSynapseWeights(ids))
        }
    }

    @Test
    fun structuralWeightChangesRequireTrustedFeedbackAndWholePair() {
        val proposal = grownProposalForTest()
        val ids = proposal.connections.map { it.connectionId }.sorted()
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            network.initializeHybridTopologyV2()
            network.addStructuralGrowthProposal(proposal)

            var rejected = false
            try {
                network.adjustStructuralSynapseWeights(
                    "model-feedback",
                    ids,
                    0.10,
                    ANS100CandidateFeedbackLearner.FeedbackSource.MODEL_GENERATED,
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(0, network.adjustStructuralSynapseWeights(
                "half-pair-feedback",
                ids.take(1),
                0.10,
                ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
            ))
            assertEquals(setOf(0.25), network.structuralSynapseWeights(ids).values.toSet())
        }
    }

    @Test
    fun weakStructuralAssociationIsRemovedOnlyAsAnEntirePair() {
        val proposal = grownProposalForTest()
        val ids = proposal.connections.map { it.connectionId }.sorted()
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            network.initializeHybridTopologyV2()
            network.addStructuralGrowthProposal(proposal)
            repeat(2) { index ->
                assertEquals(2, network.adjustStructuralSynapseWeights(
                    "negative-feedback-$index",
                    ids,
                    -0.10,
                    ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                ))
            }
            assertEquals(0, network.removeWeakStructuralSynapses(ids.take(1), 0.10))
            assertEquals(2, network.removeWeakStructuralSynapses(ids, 0.10))
            assertTrue(network.structuralSynapseWeights(ids).isEmpty())
            assertEquals(25_500, network.stats().connectionCount)
        }
    }

}
