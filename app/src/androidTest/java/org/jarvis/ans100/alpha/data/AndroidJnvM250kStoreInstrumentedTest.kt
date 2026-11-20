package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        var store = AndroidJnvM250kStore(context, databaseName)
        try {
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

            // Timestamp processing is all-or-nothing: consumed events, changed
            // neuron state, generated events and model clock move together.
            val inputEvents = listOf(
                AndroidNeuronEvent("input-event-a", 10L, 20_000, 0.4),
                AndroidNeuronEvent("input-event-b", 10L, 20_001, 0.6),
            )
            store.enqueueEvents(inputEvents)
            assertEquals(inputEvents, store.nextTimestampEvents())
            val changedState = AndroidNeuronState(
                neuronId = 20_000,
                potential = 0.25,
                lastUpdateNs = 10L,
            )
            val generatedEvent = AndroidNeuronEvent(
                eventId = "generated-event",
                timeNs = 20L,
                targetNeuronId = 20_002,
                strength = 0.3,
            )
            store.commitTimestamp(
                timestampNs = 10L,
                consumed = inputEvents,
                generated = listOf(generatedEvent),
                changedStates = listOf(changedState),
            )
            assertEquals(10L, store.stats().currentTimeNs)
            assertEquals(listOf(generatedEvent), store.nextTimestampEvents())
            assertEquals(changedState, store.loadNeuronPage(19_999, 1).single())

            // A missing consumed ID must roll back deletion, state, and clock.
            val missingEvent = AndroidNeuronEvent("missing-event", 20L, 20_002, 0.3)
            val incompleteCommitFailure = runCatching {
                store.commitTimestamp(
                    timestampNs = 20L,
                    consumed = listOf(missingEvent),
                    generated = emptyList(),
                    changedStates = emptyList(),
                )
            }.exceptionOrNull()
            assertNotNull(incompleteCommitFailure)
            assertEquals(10L, store.stats().currentTimeNs)
            assertEquals(listOf(generatedEvent), store.nextTimestampEvents())

            // Run real LIF-DT through the 250k store and bounded working set.
            // Neuron 4 is inhibitory under the deterministic population polarity rule.
            assertEquals(
                AndroidJnvM250kTopology.Polarity.INHIBITORY,
                AndroidJnvM250kTopology.polarityFor(4),
            )
            val existingSourceFourTargets = store.outgoingPage(4, limit = 16).map { it.targetNeuronId }.toSet()
            val sourceZeroTarget = firstBatch.connections.first { it.sourceNeuronId == 0 }.targetNeuronId
            val inhibitoryTarget = (20_000..20_031).first {
                it !in existingSourceFourTargets && it != sourceZeroTarget
            }
            store.registerConnection(
                AndroidNeuronConnection(
                    connectionId = "runtime-inhibitory-probe",
                    sourceNeuronId = 4,
                    targetNeuronId = inhibitoryTarget,
                    delayNs = 1_000_000L,
                    weight = 0.5,
                ),
            )

            AndroidJnvM250kRuntime(context, store).use { runtime ->
                runtime.scheduleInputs(
                    listOf(
                        AndroidNeuronEvent("runtime-input-excitatory", 100L, 0, 1.0),
                        AndroidNeuronEvent("runtime-input-inhibitory", 100L, 4, 1.0),
                    ),
                )
                val deferredRun = runtime.run(maxEvents = 1, maxTimestamps = 1)
                assertTrue(deferredRun.budgetExhausted)
                assertEquals(0, deferredRun.processedEvents)
                assertEquals(0L, deferredRun.currentTimeNs)
                assertEquals(2, deferredRun.pendingEvents)

                val sourceRun = runtime.run(maxEvents = 2, maxTimestamps = 1)
                assertEquals(2, sourceRun.processedEvents)
                assertEquals(setOf(0, 4), sourceRun.emittedSpikes.map { it.neuronId }.toSet())
                assertTrue(
                    "runtime must expose the actual source-to-target synapse transmission",
                    sourceRun.causalLinks.any {
                        it.sourceNeuronId == 0 && it.targetNeuronId == sourceZeroTarget
                    },
                )
                assertTrue(sourceRun.omittedCausalLinks >= 0)
                assertTrue(sourceRun.residentNeuronCount <= sourceRun.hotSetCapacity)

                val propagated = store.nextTimestampEvents()
                assertNotNull(propagated)
                val inhibitorySignal = propagated!!.single {
                    it.eventId.endsWith("runtime-inhibitory-probe")
                }
                assertEquals(-0.5, inhibitorySignal.strength, 0.000001)

                val targetRun = runtime.run(maxEvents = propagated.size, maxTimestamps = 1)
                assertEquals(propagated.size, targetRun.processedEvents)
                val inhibitedState = store.loadNeuronPage(inhibitoryTarget - 1, 1).single()
                assertEquals(-0.5, inhibitedState.potential, 0.000001)
                assertEquals("cpu-reference", targetRun.computeBackend)
            }

            // Reopen the SQLite database before reading the trace: learning
            // state must survive a runtime/store lifecycle boundary.
            store.close()
            store = AndroidJnvM250kStore(context, databaseName)

            // The source spike at t=100 must leave a persisted pre-trace. A
            // deterministic test backend forces the later postsynaptic spike so
            // the LTP direction can be checked without depending on thresholds.
            val forcedSpikeBackend = object : AndroidNeuronComputeBackend {
                override val backendName = "instrumentation-force-spike"
                override fun processBatch(
                    inputs: List<AndroidNeuronKernel.Input>,
                ): List<AndroidNeuronKernel.Result> = inputs.map {
                    AndroidNeuronKernel.process(it.state, it.timestampNs, 2.0)
                }
            }
            var positiveRewardWeight = 0.0
            AndroidJnvM250kRuntime(context, store, forcedSpikeBackend).use { learningRuntime ->
                val before = store.outgoingPage(0, limit = 16)
                    .single { it.targetNeuronId == sourceZeroTarget }
                val traceBeforePost = store.loadPlasticitySynapses(setOf(0), emptySet())
                    .single { it.connection.connectionId == before.connectionId }
                assertTrue(traceBeforePost.preTrace > 0.99)
                assertEquals(0.0, traceBeforePost.postTrace, 0.000001)

                learningRuntime.scheduleInputs(
                    listOf(AndroidNeuronEvent("stdp-post-event", 10_000_000L, sourceZeroTarget, 1.0)),
                )
                val learningRun = learningRuntime.run(maxEvents = 1, maxTimestamps = 1)
                assertEquals(listOf(sourceZeroTarget), learningRun.emittedSpikes.map { it.neuronId })

                val afterStdp = store.outgoingPage(0, limit = 16)
                    .single { it.targetNeuronId == sourceZeroTarget }
                assertTrue("post-after-pre must potentiate the synapse", afterStdp.weight > 0.5)
                val eligibility = store.loadPlasticitySynapses(setOf(0), emptySet())
                    .single { it.connection.connectionId == afterStdp.connectionId }
                assertTrue("STDP must accumulate a reward eligibility trace", eligibility.eligibility > 0.0)

                val positive = learningRuntime.applyReward(
                    rewardId = "reward-positive-once",
                    reward = 1.0,
                    rewardedNeuronIds = setOf(sourceZeroTarget),
                )
                assertTrue(positive.affectedSynapses > 0)
                assertTrue(!positive.replayed)
                positiveRewardWeight = store.outgoingPage(0, limit = 16)
                    .single { it.targetNeuronId == sourceZeroTarget }.weight
                assertTrue("positive reward must reinforce positive eligibility", positiveRewardWeight > afterStdp.weight)

                assertTrue(positiveRewardWeight > afterStdp.weight)
            }

            // Reward receipts must survive closing and reopening SQLite.
            store.close()
            store = AndroidJnvM250kStore(context, databaseName)
            AndroidJnvM250kRuntime(context, store, forcedSpikeBackend).use { resumedRuntime ->
                val replayAfterReopen = resumedRuntime.applyReward(
                    rewardId = "reward-positive-once",
                    reward = 1.0,
                    rewardedNeuronIds = setOf(sourceZeroTarget),
                )
                assertTrue(replayAfterReopen.replayed)
                assertEquals(
                    positiveRewardWeight,
                    store.outgoingPage(0, limit = 16).single { it.targetNeuronId == sourceZeroTarget }.weight,
                    0.000001,
                )
                val conflictingRewardId = runCatching {
                    resumedRuntime.applyReward(
                        rewardId = "reward-positive-once",
                        reward = -1.0,
                        rewardedNeuronIds = setOf(sourceZeroTarget),
                    )
                }.exceptionOrNull()
                assertTrue("a reward ID cannot be reused for different feedback", conflictingRewardId is AndroidNeuronNetworkException)

                // A second postsynaptic spike reuses the still-decaying pre-trace,
                // creating fresh positive eligibility for a punishment probe.
                resumedRuntime.scheduleInputs(
                    listOf(AndroidNeuronEvent("stdp-second-post-event", 20_000_000L, sourceZeroTarget, 1.0)),
                )
                val secondPost = resumedRuntime.run(maxEvents = 1, maxTimestamps = 1)
                assertEquals(listOf(sourceZeroTarget), secondPost.emittedSpikes.map { it.neuronId })
                val beforePunishment = store.outgoingPage(0, limit = 16)
                    .single { it.targetNeuronId == sourceZeroTarget }.weight
                val exactTargetConnection = store.outgoingPage(0, limit = 16)
                    .single { it.targetNeuronId == sourceZeroTarget }
                val unrelatedOutgoing = store.outgoingPage(sourceZeroTarget, limit = 16).first()
                val unrelatedWeightBefore = unrelatedOutgoing.weight
                val exactReward = resumedRuntime.applyRewardToConnections(
                    rewardId = "reward-exact-edge-once",
                    reward = 1.0,
                    connectionIds = setOf(exactTargetConnection.connectionId),
                )
                assertEquals("exact reward must affect only its selected edge", 1, exactReward.affectedSynapses)
                assertEquals(
                    "an unselected neighboring edge must remain unchanged",
                    unrelatedWeightBefore,
                    store.outgoingPage(sourceZeroTarget, limit = 16)
                        .single { it.connectionId == unrelatedOutgoing.connectionId }.weight,
                    0.000001,
                )
                val missingConnectionFailure = runCatching {
                    resumedRuntime.applyRewardToConnections(
                        rewardId = "reward-missing-edge",
                        reward = 1.0,
                        connectionIds = setOf("missing-connection-id"),
                    )
                }.exceptionOrNull()
                assertTrue("unknown connection IDs must fail closed", missingConnectionFailure is AndroidNeuronNetworkException)
                val negative = resumedRuntime.applyReward(
                    rewardId = "reward-negative-once",
                    reward = -1.0,
                    rewardedNeuronIds = setOf(sourceZeroTarget),
                )
                assertTrue(negative.affectedSynapses > 0)
                assertTrue(
                    "negative reward must weaken positive eligibility",
                    store.outgoingPage(0, limit = 16)
                        .single { it.targetNeuronId == sourceZeroTarget }.weight < beforePunishment,
                )
                val afterPunishment = store.outgoingPage(0, limit = 16)
                    .single { it.targetNeuronId == sourceZeroTarget }.weight
                val delayedRetry = resumedRuntime.applyReward(
                    rewardId = "reward-positive-once",
                    reward = 1.0,
                    rewardedNeuronIds = setOf(sourceZeroTarget),
                )
                assertTrue("a delayed retry must be recognized after the clock advances", delayedRetry.replayed)
                assertEquals(
                    afterPunishment,
                    store.outgoingPage(0, limit = 16).single { it.targetNeuronId == sourceZeroTarget }.weight,
                    0.000001,
                )
            }
            val learnedConnection = store.outgoingPage(0, limit = 16)
                .single { it.targetNeuronId == sourceZeroTarget }
            val persistedTrace = store.loadPlasticitySynapses(setOf(0), emptySet())
                .single { it.connection.connectionId == learnedConnection.connectionId }
            assertTrue(persistedTrace.preTrace > 0.0)
            assertTrue(persistedTrace.postTrace > 0.99)
            assertEquals(0.0, persistedTrace.eligibility, 0.000001)

            // Partial checkpoint metadata is treated as corruption, never as a
            // fresh run that could silently duplicate already committed edges.
            store.close()
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
            assertTrue(corruptCheckpointFailure is AndroidNeuronNetworkException)
        } finally {
            runCatching { store.close() }
            context.deleteDatabase(databaseName)
        }
    }
}
