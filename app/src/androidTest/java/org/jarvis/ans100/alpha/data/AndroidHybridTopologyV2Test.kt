package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(25_500, first.size)
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
        assertTrue(first.filter { it.connectionId.contains("recurrent") || it.connectionId.contains("critic-candidate") }
            .all { it.weight == AndroidHybridTopologyV2.RECURRENT_WEIGHT })
        assertTrue(first.filterNot { it.connectionId.contains("recurrent") || it.connectionId.contains("critic-candidate") }
            .all { it.weight == AndroidHybridTopologyV2.FEED_FORWARD_WEIGHT })
        assertEquals(1.0, AndroidHybridTopologyV2.FEED_FORWARD_WEIGHT, 0.0)
        assertTrue(AndroidHybridTopologyV2.RECURRENT_WEIGHT < 1.0)
    }

    @Test
    fun typedEvidenceAndCandidateSignalsPropagateThroughAllSevenPopulations() {
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            val turnId = "seven-population-propagation"
            val message = "hello"
            val understanding = LocalUnderstandingPipeline().analyze(message)
            val evidence = TypedEvidenceEncoder().encode(
                TypedEvidenceInput(turnId, message, understanding),
            )
            val batch = AndroidHybridEvidenceInputAdapter.createBatch(evidence, timestampNs = 1L)

            assertTrue(network.scheduleHybridEvidence(batch))
            val firstPass = network.run(maxEvents = 4_096, maxTimestamps = 1)
            assertEquals(batch.events.size, firstPass.processedEvents)
            assertTrue(firstPass.pendingEvents > 0)
            val firstTelemetry = firstPass.hybridTelemetry
                ?: throw AssertionError("partial hybrid run must expose population telemetry")
            assertEquals(batch.events.size, firstTelemetry.populations.sumOf { it.receivedEventCount })

            val result = network.run(maxEvents = 4_096, maxTimestamps = 128)
            assertEquals(0, result.pendingEvents)
            assertTrue(result.emittedSpikes.isNotEmpty())

            val telemetry = result.hybridTelemetry
                ?: throw AssertionError("hybrid topology run must expose population telemetry")
            assertEquals(AndroidHybridTopologyV2.TOPOLOGY_ID, telemetry.topologyId)
            assertEquals(AndroidHybridTopologyV2.TOPOLOGY_VERSION, telemetry.topologyVersion)
            assertEquals(7, telemetry.populations.size)
            assertEquals(
                firstPass.processedEvents + result.processedEvents,
                telemetry.populations.sumOf { it.receivedEventCount },
            )
            telemetry.populations.forEach { activity ->
                assertTrue("no processed neuron in " + activity.populationId, activity.uniqueNeuronsProcessed > 0)
                assertTrue("no spiking neuron in " + activity.populationId, activity.uniqueSpikingNeurons > 0)
                assertTrue("no spike count in " + activity.populationId, activity.spikeCount > 0)
                assertTrue(
                    "spike count smaller than unique spiking count in " + activity.populationId,
                    activity.spikeCount >= activity.uniqueSpikingNeurons,
                )
            }
            assertEquals(
                0,
                telemetry.populations.last().outgoingEventCount,
            )

            val spikedIds = (firstPass.emittedSpikes + result.emittedSpikes).map { it.first }.toSet()
            AndroidHybridTopologyV2.populations.forEach { population ->
                assertTrue(
                    "no spike observed in population " + population.id,
                    spikedIds.any(population::contains),
                )
            }
            // A drained queue and bounded event count guard against an accidental
            // candidate<->critic or recurrent self-sustaining spike cycle.
            assertTrue(result.processedEvents < 4_096)
            assertFalse(result.budgetExhausted)
        }
    }


    @Test
    fun candidateRoutesAreIsolatedAndMapToTheirOwnReadoutHalves() {
        val edges = AndroidHybridTopologyV2.connections()
        ANS100CandidateSlotContract.Slot.values().forEach { slot ->
            ANS100CandidateSlotContract.Polarity.values().forEach { polarity ->
                val inputId = ANS100CandidateSlotContract.inputNeuronId(slot, polarity)
                val associationLane = ANS100CandidateSlotContract.associationLane(slot, polarity)
                val candidateLane = ANS100CandidateSlotContract.candidateLane(slot, polarity)
                val criticLane = ANS100CandidateSlotContract.criticLane(slot, polarity)
                val readoutLane = ANS100CandidateSlotContract.readoutLane(slot, polarity)

                val inputEdges = edges.filter { it.sourceNeuronId == inputId }
                assertEquals(3, inputEdges.size)
                assertTrue(inputEdges.all { associationLane.contains(it.targetNeuronId) })
                val workingLane = ANS100CandidateSlotContract.workingLane(slot, polarity)
                assertTrue(edges.filter { it.connectionId.contains("association-working-${slot.name.lowercase()}-${polarity.name.lowercase()}") }
                    .all { associationLane.contains(it.sourceNeuronId) && workingLane.contains(it.targetNeuronId) })
                assertTrue(edges.filter { it.connectionId.contains("working-candidate-${slot.name.lowercase()}-${polarity.name.lowercase()}") }
                    .all { workingLane.contains(it.sourceNeuronId) && candidateLane.contains(it.targetNeuronId) })
                assertTrue(edges.filter { it.connectionId.contains("critic-candidate-${slot.name.lowercase()}-${polarity.name.lowercase()}") }
                    .all { criticLane.contains(it.sourceNeuronId) && candidateLane.contains(it.targetNeuronId) })
                assertTrue(edges.filter { it.connectionId.contains("candidate-critic-${slot.name.lowercase()}-${polarity.name.lowercase()}") }
                    .all { candidateLane.contains(it.sourceNeuronId) && criticLane.contains(it.targetNeuronId) })
                assertTrue(edges.filter { it.connectionId.contains("candidate-readout-${slot.name.lowercase()}-${polarity.name.lowercase()}") }
                    .all { candidateLane.contains(it.sourceNeuronId) && readoutLane.contains(it.targetNeuronId) })
                assertTrue(edges.filter { it.connectionId.contains("critic-readout-${slot.name.lowercase()}-${polarity.name.lowercase()}") }
                    .all { criticLane.contains(it.sourceNeuronId) && readoutLane.contains(it.targetNeuronId) })
            }
        }
        val allReadoutRanges = ANS100CandidateSlotContract.Slot.values().flatMap { slot ->
            ANS100CandidateSlotContract.Polarity.values().map { polarity ->
                ANS100CandidateSlotContract.readoutLane(slot, polarity)
            }
        }
        allReadoutRanges.forEachIndexed { index, lane ->
            allReadoutRanges.drop(index + 1).forEach { other ->
                assertTrue(lane.endExclusive <= other.startInclusive || other.endExclusive <= lane.startInclusive)
            }
        }
    }

    @Test
    fun oneCandidateSignalSpikesOnlyItsOwnReadoutLane() {
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 32).use { network ->
            val turnId = "single-candidate-route"
            val record = TypedEvidenceRecord(
                recordId = EvidenceFeature.CANDIDATE_ANSWER_SUPPORT.stableId + ":0",
                turnId = turnId,
                feature = EvidenceFeature.CANDIDATE_ANSWER_SUPPORT,
                source = EvidenceSourceKind.CORE_REASONING_PLAN,
                status = EvidenceStatus.HEURISTIC,
                value = EvidenceValue.BooleanValue(true),
                activation = 1.0,
                provenanceRef = "turn:$turnId:candidate.answer.support",
            )
            val batch = AndroidHybridEvidenceInputAdapter.createBatch(
                TypedEvidenceBundle(turnId = turnId, records = listOf(record)),
                timestampNs = 1L,
            )
            assertEquals(
                ANS100CandidateSlotContract.inputNeuronId(
                    ANS100CandidateSlotContract.Slot.ANSWER,
                    ANS100CandidateSlotContract.Polarity.SUPPORT,
                ),
                batch.events.single().targetNeuronId,
            )
            assertTrue(network.beginHybridTurn(batch))
            val result = network.run(maxEvents = 4_096, maxTimestamps = 128)
            val spiked = result.emittedSpikes.map { it.first }.toSet()
            val expected = ANS100CandidateSlotContract.readoutLane(
                ANS100CandidateSlotContract.Slot.ANSWER,
                ANS100CandidateSlotContract.Polarity.SUPPORT,
            )
            assertTrue(spiked.any(expected::contains))
            ANS100CandidateSlotContract.Slot.values().forEach { slot ->
                ANS100CandidateSlotContract.Polarity.values().forEach { polarity ->
                    if (slot != ANS100CandidateSlotContract.Slot.ANSWER ||
                        polarity != ANS100CandidateSlotContract.Polarity.SUPPORT
                    ) {
                        val other = ANS100CandidateSlotContract.readoutLane(slot, polarity)
                        assertFalse(spiked.any(other::contains))
                    }
                }
            }
            assertEquals(0, result.pendingEvents)
        }
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
            assertEquals(25_500, stats.connectionCount)
            assertEquals(0, stats.pendingEvents)
            assertEquals(0, stats.residentNeuronCount)
            assertEquals(16, stats.hotSetCapacity)
        }
        AndroidHybridTopologyV2.open(context, databaseName, hotSetCapacity = 16).use { reopened ->
            assertEquals(10_000, reopened.stats().neuronCount)
            assertEquals(25_500, reopened.stats().connectionCount)
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
            assertTrue(network.beginHybridTurn(batch("turn-b", 20_000_000L)))
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
                assertEquals(25_500, hybrid.stats().connectionCount)
            }
            AndroidSparseNeuronNetwork(context, legacyName).use { legacyReopened ->
                assertEquals(75_984, legacyReopened.stats().connectionCount)
            }
        } finally {
            context.deleteDatabase(legacyName)
        }
    }
}
