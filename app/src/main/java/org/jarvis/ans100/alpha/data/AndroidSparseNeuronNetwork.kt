package org.jarvis.ans100.alpha.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.util.LinkedHashMap
import kotlin.math.exp

data class AndroidNeuronState(
    val neuronId: Int,
    val potential: Double = 0.0,
    val adaptation: Double = 0.0,
    val lastUpdateNs: Long = 0L,
    val refractoryUntilNs: Long = 0L,
    val spikeSequence: Long = 0L,
    /** Sticky within one isolated turn if any Core-heuristic signal reached this neuron. */
    val coreTainted: Boolean = false,
) {
    init {
        require(neuronId >= 0)
        require(potential.isFinite() && potential in -2.0..2.0)
        require(adaptation.isFinite() && adaptation in 0.0..1.0)
        require(lastUpdateNs >= 0L && refractoryUntilNs >= 0L && spikeSequence >= 0L)
    }
}

data class AndroidNeuronConnection(
    val connectionId: String,
    val sourceNeuronId: Int,
    val targetNeuronId: Int,
    val delayNs: Long,
    val weight: Double,
) {
    init {
        require(connectionId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(sourceNeuronId >= 0 && targetNeuronId >= 0)
        require(sourceNeuronId != targetNeuronId)
        require(delayNs > 0L)
        require(weight.isFinite() && weight in 0.0..1.0)
    }
}

data class AndroidNeuronEvent(
    val eventId: String,
    val timeNs: Long,
    val targetNeuronId: Int,
    val strength: Double,
    /** Origin taint is inherited by every event emitted from a tainted neuron. */
    val coreTainted: Boolean = false,
) {
    init {
        require(eventId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$")))
        require(timeNs >= 0L && targetNeuronId >= 0)
        require(strength.isFinite() && strength in 0.0..1.0)
    }
}

data class AndroidNeuronNetworkStats(
    val neuronCount: Int,
    val connectionCount: Int,
    val pendingEvents: Int,
    val currentTimeNs: Long,
    val residentNeuronCount: Int,
    val hotSetCapacity: Int,
)

data class AndroidDialogueRewardResult(
    val updatedSynapses: Int,
    val expiredSynapses: Int,
)

data class AndroidNeuronPopulationActivity(
    val populationId: String,
    val receivedEventCount: Int,
    val uniqueNeuronsProcessed: Int,
    val uniqueSpikingNeurons: Int,
    val spikeCount: Int,
    val outgoingEventCount: Int,
)

data class AndroidHybridRunTelemetry(
    val topologyId: String,
    val topologyVersion: Int,
    /** Counters accumulate for the active turn across partial run() calls. */
    val populations: List<AndroidNeuronPopulationActivity>,
)

data class AndroidNeuronRunResult(
    val processedEvents: Int,
    val processedNeuronBatches: Int,
    val emittedSpikes: List<Pair<Int, Long>>,
    val pendingEvents: Int,
    val budgetExhausted: Boolean,
    val currentTimeNs: Long,
    /** Present only when this database matches the versioned eight-population hybrid topology. */
    val hybridTelemetry: AndroidHybridRunTelemetry? = null,
    /** Synapses whose propagated event reached a neuron that spiked in this run. */
    val activatedConnectionIds: Set<String> = emptySet(),
    /** All neurons whose state received Core-heuristic taint in this turn, whether or not they spiked. */
    val coreTaintedNeuronIds: Set<Int> = emptySet(),
)

private fun connectionIdFromPropagatedEvent(eventId: String): String? =
    Regex("^spike-\\d+-\\d+-(ans100-v4-[A-Za-z0-9._:-]+|ans100-v5-grown-association-[A-Za-z0-9._:-]+|ans100-v9-canonical-readout-[A-Za-z0-9._:-]+)$")
        .matchEntire(eventId)?.groupValues?.get(1)

open class AndroidNeuronNetworkException(message: String) : IllegalStateException(message)
class AndroidNeuronBackpressureException(message: String) : AndroidNeuronNetworkException(message)

/**
 * Authoritative SQLite-backed JNVM store. The database transaction is the
 * publication boundary for a complete timestamp: consumed queue records,
 * changed neuron states, generated events and the model clock commit together.
 */
internal interface AndroidNeuronStateStore : Closeable {
    fun initializeSparseChain(neuronCount: Int, delayNs: Long, weight: Double)
    fun initializeHybridTopology(topologyId: String, topologyVersion: Int, connections: List<AndroidNeuronConnection>): Boolean
    fun matchesTopology(topologyId: String, topologyVersion: Int, neuronCount: Int, connectionCount: Int): Boolean
    fun resetHybridTurnState(modelTimeNs: Long): Boolean
    fun initializeSparseDialogueNetwork(neuronCount: Int, featureNeuronCount: Int, delayNs: Long, weight: Double, inputToHiddenWeight: Double): Boolean
    fun registerNeuron(neuronId: Int)
    fun addConnection(connection: AndroidNeuronConnection)
    fun addStructuralGrowthProposal(proposal: ANS100ActivityDrivenStructuralPlasticity.GrowthProposal): Int
    fun recordStructuralPlasticityTurn(
        turnId: String,
        activeConcepts: List<ANS100ActivityDrivenStructuralPlasticity.ActiveConcept>,
    ): List<ANS100ActivityDrivenStructuralPlasticity.TurnSnapshot>
    fun structuralPlasticityGraphSnapshot(
        sourceNeuronIds: List<Int>,
    ): ANS100ActivityDrivenStructuralPlasticity.GraphSnapshot
    fun adjustStructuralSynapseWeights(feedbackId: String, connectionIds: List<String>, delta: Double, source: ANS100CandidateFeedbackLearner.FeedbackSource): Int
    fun removeStructuralSynapses(connectionIds: List<String>, maximumWeight: Double): Int
    fun structuralSynapseWeights(connectionIds: List<String>): Map<String, Double>
    fun structuralAssociationConnections(sourceNeuronIds: List<Int>): List<AndroidNeuronConnection>
    fun recordCanonicalReadoutEligibility(turnId: String, sourceNeuronIds: List<Int>): Int
    fun applyCanonicalReadoutFeedback(
        feedbackId: String,
        turnId: String,
        answer: ANS100CanonicalReadoutContract.Answer,
        source: ANS100CandidateFeedbackLearner.FeedbackSource,
    ): Int
    fun canonicalReadoutSynapseCount(): Int
    fun enqueue(event: AndroidNeuronEvent)
    fun enqueueAll(events: List<AndroidNeuronEvent>)
    fun nextTimestampEvents(maxEvents: Int): List<AndroidNeuronEvent>?
    fun outgoing(neuronId: Int): List<AndroidNeuronConnection>
    fun loadNeuron(neuronId: Int): AndroidNeuronState
    fun recordDialogueEligibility(
        contextId: String,
        sourceNeuronIds: List<Int>,
        outputNeuronIds: List<Int>,
        preTimeNs: Long,
        postTimeNs: Long,
    ): Int
    fun applyDialogueReward(
        contextId: String,
        sourceNeuronIds: List<Int>,
        outputRewards: Map<Int, Double>,
    ): AndroidDialogueRewardResult
    fun recordHybridCandidateEligibility(turnId: String, candidateId: String, connectionIds: List<String>, eligibility: Double): Int
    fun applyHybridCandidateFeedback(turnId: String, candidateId: String, reward: Double, source: ANS100CandidateFeedbackLearner.FeedbackSource): Int
    fun resolveConceptInputSlots(conceptIds: List<String>): Map<String, Int>
    fun recordNeuralAssociationEligibility(turnId: String, candidateId: String, connectionIds: List<String>, eligibility: Double): Int
    fun neuralAssociationWeights(connectionIds: List<String>): Map<String, Double>
    fun recordNeuralAssociationEvaluation(candidateId: String, observation: ANS100NeuralAssociationAssessment.Observation): Int
    fun neuralAssociationEvaluations(candidateId: String, limit: Int): List<ANS100NeuralAssociationAssessment.PersistedObservation>
    fun neuralAssociationWeightHistory(candidateId: String, limit: Int): List<ANS100NeuralAssociationAssessment.WeightChange>
    fun latestNeuralAssociationFeedback(candidateId: String): ANS100NeuralAssociationAssessment.FeedbackRecord?
    fun applyNeuralAssociationFeedback(feedbackId: String, turnId: String, candidateId: String, reward: Double, source: ANS100CandidateFeedbackLearner.FeedbackSource): Int
    fun dialogueSynapseWeight(sourceNeuronId: Int, outputNeuronId: Int): Double
    fun dialogueOutputScores(hiddenNeuronIds: List<Int>): Map<Int, Double>
    fun commitTimestamp(
        timestampNs: Long,
        consumed: List<AndroidNeuronEvent>,
        generated: List<AndroidNeuronEvent>,
        changedStates: Collection<AndroidNeuronState>,
    )
    fun stats(residentCount: Int, hotSetCapacity: Int): AndroidNeuronNetworkStats
}

/**
 * Native sparse network runtime for Android.
 *
 * It stores all inactive neuron state and the event queue in SQLite. Only a
 * small LRU hot set is resident in memory. Dirty state is never evicted:
 * each timestamp is committed atomically before updated cache entries are
 * published or old entries are evicted.
 */
class AndroidSparseNeuronNetwork(
    context: Context,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
    private val hotSetCapacity: Int = DEFAULT_HOT_SET_CAPACITY,
) : Closeable {
    private val store: AndroidNeuronStateStore
    private val hot = LinkedHashMap<Int, AndroidNeuronState>(hotSetCapacity, 0.75f, true)
    private val hybridTelemetryLock = Any()
    private var hybridTelemetryTurnId: String? = null
    private val hybridReceivedEvents = IntArray(AndroidHybridTopologyV2.populations.size)
    private val hybridProcessedNeuronIds = Array(AndroidHybridTopologyV2.populations.size) { HashSet<Int>() }
    private val hybridSpikingNeuronIds = Array(AndroidHybridTopologyV2.populations.size) { HashSet<Int>() }
    private val hybridSpikeCounts = IntArray(AndroidHybridTopologyV2.populations.size)
    private val hybridOutgoingEventCounts = IntArray(AndroidHybridTopologyV2.populations.size)

    private fun resetHybridTelemetry(turnId: String) = synchronized(hybridTelemetryLock) {
        hybridTelemetryTurnId = turnId
        hybridReceivedEvents.fill(0)
        hybridProcessedNeuronIds.forEach { it.clear() }
        hybridSpikingNeuronIds.forEach { it.clear() }
        hybridSpikeCounts.fill(0)
        hybridOutgoingEventCounts.fill(0)
    }


    init {
        require(hotSetCapacity in 1..MAX_HOT_SET_CAPACITY)
        store = AndroidNeuronSqliteStore(context.applicationContext, databaseName)
    }

    @Synchronized
    fun initializeSparseChain(
        neuronCount: Int = MAX_NEURONS,
        delayNs: Long = DEFAULT_CONNECTION_DELAY_NS,
        weight: Double = DEFAULT_CONNECTION_WEIGHT,
    ) {
        require(neuronCount in 1..MAX_NEURONS) { "neuronCount exceeds the 10,000-node capacity" }
        require(delayNs > 0L && weight.isFinite() && weight in 0.0..1.0)
        store.initializeSparseChain(neuronCount, delayNs, weight)
        hot.clear()
    }

    /**
     * Initializes the separate ANS-100 hybrid V2 database. This never migrates,
     * clears or reinterprets the legacy dialogue database.
     */
    @Synchronized
    fun initializeHybridTopologyV2() {
        val connections = AndroidHybridTopologyV2.connections()
        if (connections.size > MAX_CONNECTIONS) {
            throw AndroidNeuronNetworkException("hybrid V2 topology exceeds the connection bound")
        }
        if (store.initializeHybridTopology(
                topologyId = AndroidHybridTopologyV2.TOPOLOGY_ID,
                topologyVersion = AndroidHybridTopologyV2.TOPOLOGY_VERSION,
                connections = connections,
            )) hot.clear()
    }

    /**
     * Starts a new isolated hybrid turn. Residual membrane/adaptation/refractory
     * state is reset only after the previous event queue has drained; topology,
     * learned connection weights and monotonic spike sequences are preserved.
     */
    @Synchronized
    fun beginHybridTurn(batch: AndroidHybridEvidenceInputAdapter.ActivationBatch): Boolean {
        if (batch.events.isEmpty()) return false
        if (batch.turnId.isBlank() ||
            batch.events.size > AndroidHybridEvidenceInputAdapter.MAX_ACTIVE_INPUTS_PER_TURN ||
            batch.events.size != batch.trace.size + batch.conceptTrace.size ||
            batch.events.any { it.timeNs != batch.timestampNs || it.targetNeuronId !in 0 until 1_200 } ||
            batch.events.map { it.eventId }.toSet().size != batch.events.size ||
            batch.events.map { it.targetNeuronId } != (batch.trace.map { it.neuronId } + batch.conceptTrace.map { it.neuronId }) ||
            batch.events.map { it.coreTainted } != (
                batch.trace.map { trace ->
                    trace.status == EvidenceStatus.HEURISTIC ||
                        trace.source == EvidenceSourceKind.CORE_UNDERSTANDING ||
                        trace.source == EvidenceSourceKind.CORE_REASONING_PLAN
                } + batch.conceptTrace.map { false }
            )
        ) throw AndroidNeuronNetworkException("hybrid evidence batch is malformed, provenance-mismatched or outside the input population")
        if (!store.matchesTopology(
                topologyId = AndroidHybridTopologyV2.TOPOLOGY_ID,
                topologyVersion = AndroidHybridTopologyV2.TOPOLOGY_VERSION,
                neuronCount = AndroidHybridTopologyV2.NEURON_COUNT,
                connectionCount = AndroidHybridTopologyV2.EXPECTED_CONNECTION_COUNT,
            )
        ) throw AndroidNeuronNetworkException("network is not the initialized ANS-100 hybrid V2 topology")
        if (store.stats(hot.size, hotSetCapacity).pendingEvents != 0) {
            throw AndroidNeuronBackpressureException("previous hybrid turn must be drained before state isolation")
        }
        val currentTime = store.stats(hot.size, hotSetCapacity).currentTimeNs
        if (batch.timestampNs <= currentTime) {
            throw AndroidNeuronNetworkException("hybrid turn timestamp must be later than the committed model clock")
        }
        if (!store.resetHybridTurnState(currentTime)) {
            throw AndroidNeuronNetworkException("hybrid turn state could not be isolated")
        }
        hot.clear()
        resetHybridTelemetry(batch.turnId)
        scheduleHybridEvidence(batch)
        return true
    }

    /**
     * Atomically submits one turn's typed evidence batch only to the verified V2
     * topology. A prior turn must be fully drained before the next turn is queued.
     */
    @Synchronized
    fun scheduleHybridEvidence(batch: AndroidHybridEvidenceInputAdapter.ActivationBatch): Boolean {
        if (batch.events.isEmpty()) return false
        if (batch.turnId.isBlank() || batch.events.size > AndroidHybridEvidenceInputAdapter.MAX_ACTIVE_INPUTS_PER_TURN ||
            batch.events.size != batch.trace.size + batch.conceptTrace.size ||
            batch.events.any { it.timeNs != batch.timestampNs || it.targetNeuronId !in 0 until 1_200 } ||
            batch.events.map { it.eventId }.toSet().size != batch.events.size ||
            batch.events.map { it.targetNeuronId } != (batch.trace.map { it.neuronId } + batch.conceptTrace.map { it.neuronId }) ||
            batch.events.map { it.coreTainted } != (
                batch.trace.map { trace ->
                    trace.status == EvidenceStatus.HEURISTIC ||
                        trace.source == EvidenceSourceKind.CORE_UNDERSTANDING ||
                        trace.source == EvidenceSourceKind.CORE_REASONING_PLAN
                } + batch.conceptTrace.map { false }
            )
        ) throw AndroidNeuronNetworkException("hybrid evidence batch is malformed, provenance-mismatched or outside the input population")

        if (!store.matchesTopology(
                topologyId = AndroidHybridTopologyV2.TOPOLOGY_ID,
                topologyVersion = AndroidHybridTopologyV2.TOPOLOGY_VERSION,
                neuronCount = AndroidHybridTopologyV2.NEURON_COUNT,
                connectionCount = AndroidHybridTopologyV2.EXPECTED_CONNECTION_COUNT,
            )
        ) throw AndroidNeuronNetworkException("network is not the initialized ANS-100 hybrid V2 topology")

        if (store.stats(hot.size, hotSetCapacity).pendingEvents != 0) {
            throw AndroidNeuronBackpressureException("previous hybrid turn must be drained before a new turn is queued")
        }
        if (hybridTelemetryTurnId != batch.turnId) resetHybridTelemetry(batch.turnId)
        scheduleInputs(batch.events)
        return true
    }

    /**
     * Ensures a bounded three-stage dialogue topology: 9,000 hashed text inputs,
     * 996 LIF hidden neurons, and four reward-trained readout neurons. Each input
     * fans out to eight deterministic hidden targets; hidden units connect to all
     * four readouts. Existing v1 feature-to-output weights are projected through
     * the new input-to-hidden mapping during upgrade.
     */
    @Synchronized
    fun initializeSparseDialogueNetwork(
        neuronCount: Int = MAX_NEURONS,
        featureNeuronCount: Int = DIALOGUE_INPUT_NEURONS,
        delayNs: Long = DEFAULT_CONNECTION_DELAY_NS,
        weight: Double = DEFAULT_DIALOGUE_OUTPUT_WEIGHT,
        inputToHiddenWeight: Double = DEFAULT_INPUT_TO_HIDDEN_WEIGHT,
    ) {
        require(neuronCount == MAX_NEURONS)
        require(featureNeuronCount == DIALOGUE_INPUT_NEURONS)
        require(featureNeuronCount.toLong() * DIALOGUE_INPUT_FAN_OUT +
            DIALOGUE_HIDDEN_NEURONS.toLong() * DIALOGUE_OUTPUT_NEURONS <= MAX_CONNECTIONS)
        require(delayNs > 0L && weight.isFinite() && weight in 0.0..1.0)
        require(inputToHiddenWeight.isFinite() && inputToHiddenWeight in 0.0..1.0)
        if (store.initializeSparseDialogueNetwork(
                neuronCount, featureNeuronCount, delayNs, weight, inputToHiddenWeight,
            )) hot.clear()
    }

    @Synchronized
    fun registerNeuron(neuronId: Int) {
        require(neuronId in 0 until MAX_NEURONS)
        store.registerNeuron(neuronId)
        hot.remove(neuronId)
    }

    @Synchronized
    fun connect(connection: AndroidNeuronConnection) {
        if (connection.sourceNeuronId !in 0 until MAX_NEURONS ||
            connection.targetNeuronId !in 0 until MAX_NEURONS
        ) throw AndroidNeuronNetworkException("connection endpoint is outside the 10,000-neuron bound")
        store.addConnection(connection)
    }

    /**
     * Atomically persist a pair of bounded concept-to-association synapses.
     * This route accepts only the experimental structural-growth namespace.
     */
    @Synchronized
    fun addStructuralGrowthProposal(proposal: ANS100ActivityDrivenStructuralPlasticity.GrowthProposal): Int =
        store.addStructuralGrowthProposal(proposal)

    @Synchronized
    fun recordStructuralPlasticityTurn(
        turnId: String,
        activeConcepts: List<ANS100ActivityDrivenStructuralPlasticity.ActiveConcept>,
    ): List<ANS100ActivityDrivenStructuralPlasticity.TurnSnapshot> =
        store.recordStructuralPlasticityTurn(turnId, activeConcepts)

    @Synchronized
    fun structuralPlasticityGraphSnapshot(
        sourceNeuronIds: List<Int>,
    ): ANS100ActivityDrivenStructuralPlasticity.GraphSnapshot =
        store.structuralPlasticityGraphSnapshot(sourceNeuronIds)

    @Synchronized
    fun adjustStructuralSynapseWeights(feedbackId: String, connectionIds: List<String>, delta: Double, source: ANS100CandidateFeedbackLearner.FeedbackSource): Int {
        require(feedbackId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(connectionIds.isNotEmpty() && connectionIds.size <= MAX_STRUCTURAL_SYNAPSES_PER_UPDATE)
        require(connectionIds.distinct().size == connectionIds.size)
        require(delta.isFinite() && delta in -MAX_STRUCTURAL_WEIGHT_DELTA..MAX_STRUCTURAL_WEIGHT_DELTA)
        require(source == ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER || source == ANS100CandidateFeedbackLearner.FeedbackSource.VERIFIED_EXTERNAL_OUTCOME)
        return store.adjustStructuralSynapseWeights(feedbackId, connectionIds, delta, source)
    }

    @Synchronized
    fun removeWeakStructuralSynapses(connectionIds: List<String>, maximumWeight: Double): Int {
        require(connectionIds.isNotEmpty() && connectionIds.size <= MAX_STRUCTURAL_SYNAPSES_PER_UPDATE)
        require(connectionIds.distinct().size == connectionIds.size)
        require(maximumWeight.isFinite() && maximumWeight in 0.0..MAX_STRUCTURAL_MIN_WEIGHT)
        return store.removeStructuralSynapses(connectionIds, maximumWeight)
    }

    @Synchronized
    fun structuralSynapseWeights(connectionIds: List<String>): Map<String, Double> {
        require(connectionIds.size <= MAX_STRUCTURAL_SYNAPSES_PER_UPDATE)
        return store.structuralSynapseWeights(connectionIds)
    }

    @Synchronized
    fun structuralAssociationConnections(sourceNeuronIds: List<Int>): List<AndroidNeuronConnection> {
        require(sourceNeuronIds.size <= ANS100ActivityDrivenStructuralPlasticity.MAX_ACTIVE_CONCEPTS_PER_TURN)
        require(sourceNeuronIds.distinct().size == sourceNeuronIds.size)
        require(sourceNeuronIds.all { it in 1_062 until 1_200 })
        return store.structuralAssociationConnections(sourceNeuronIds)
    }

    /**
     * Stores only actual source-neuron spikes from the isolated general concept,
     * association and working-state populations. Core candidate lanes are rejected.
     */
    @Synchronized
    fun recordCanonicalReadoutEligibility(turnId: String, sourceNeuronIds: List<Int>): Int {
        require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        val validated = ANS100CanonicalReadoutContract.validateSourceNeuronIds(sourceNeuronIds)
        if (validated.isEmpty()) return 0
        return store.recordCanonicalReadoutEligibility(turnId, validated)
    }

    /** Train real SQLite-backed LIF synapses from the latest explicitly rated turn. */
    @Synchronized
    fun applyCanonicalReadoutFeedback(
        feedbackId: String,
        turnId: String,
        answer: ANS100CanonicalReadoutContract.Answer,
        source: ANS100CandidateFeedbackLearner.FeedbackSource,
    ): Int {
        require(feedbackId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(source == ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER ||
            source == ANS100CandidateFeedbackLearner.FeedbackSource.VERIFIED_EXTERNAL_OUTCOME)
        return store.applyCanonicalReadoutFeedback(feedbackId, turnId, answer, source)
    }

    /**
     * Benchmark labels may train only the dedicated benchmark database. The
     * normal app network cannot opt into this synthetic supervised-learning path.
     */
    @Synchronized
    fun applyCanonicalReadoutBenchmarkFeedback(
        feedbackId: String,
        turnId: String,
        answer: ANS100CanonicalReadoutContract.Answer,
    ): Int {
        if (databaseName != AndroidHybridTopologyV2.BENCHMARK_DATABASE_NAME) {
            throw AndroidNeuronNetworkException("benchmark labels are forbidden outside the isolated benchmark database")
        }
        require(feedbackId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        return store.applyCanonicalReadoutFeedback(
            feedbackId,
            turnId,
            answer,
            ANS100CandidateFeedbackLearner.FeedbackSource.CONTROLLED_BENCHMARK_LABEL,
        )
    }

    @Synchronized
    fun canonicalReadoutSynapseCount(): Int = store.canonicalReadoutSynapseCount()

    @Synchronized
    fun scheduleInput(
        eventId: String,
        timeNs: Long,
        neuronId: Int,
        strength: Double,
    ) = scheduleInputs(listOf(AndroidNeuronEvent(eventId, timeNs, neuronId, strength)))

    /** Enqueue one same-timestamp feature batch with all-or-nothing SQLite publication. */
    @Synchronized
    fun scheduleInputs(events: List<AndroidNeuronEvent>) {
        if (events.isEmpty() || events.size > MAX_EVENTS_PER_TIMESTAMP) {
            throw AndroidNeuronNetworkException("input batch must contain 1..$MAX_EVENTS_PER_TIMESTAMP events")
        }
        val timestamp = events.first().timeNs
        if (events.any { it.timeNs != timestamp }) {
            throw AndroidNeuronNetworkException("one input batch must use a single model timestamp")
        }
        if (events.map { it.eventId }.toSet().size != events.size) {
            throw AndroidNeuronNetworkException("input event IDs must be unique within a batch")
        }
        if (timestamp <= store.stats(hot.size, hotSetCapacity).currentTimeNs) {
            throw AndroidNeuronNetworkException("input must be later than the committed network clock")
        }
        store.enqueueAll(events)
    }

    @Synchronized
    fun run(
        maxEvents: Int = DEFAULT_MAX_EVENTS_PER_RUN,
        maxTimestamps: Int = DEFAULT_MAX_TIMESTAMPS_PER_RUN,
    ): AndroidNeuronRunResult {
        require(maxEvents in 1..MAX_EVENTS_PER_RUN)
        require(maxTimestamps in 1..MAX_TIMESTAMPS_PER_RUN)
        var processedEvents = 0
        var processedBatches = 0
        var timestamps = 0
        var exhausted = false
        val spikes = mutableListOf<Pair<Int, Long>>()
        val coreTaintedNeuronIds = LinkedHashSet<Int>()
        val activatedConnectionIds = LinkedHashSet<String>()
        val isHybridTopology = store.matchesTopology(
            topologyId = AndroidHybridTopologyV2.TOPOLOGY_ID,
            topologyVersion = AndroidHybridTopologyV2.TOPOLOGY_VERSION,
            neuronCount = AndroidHybridTopologyV2.NEURON_COUNT,
            connectionCount = AndroidHybridTopologyV2.EXPECTED_CONNECTION_COUNT,
        )
        val hybridPopulations = if (isHybridTopology) AndroidHybridTopologyV2.populations else emptyList()
        val receivedEvents = if (isHybridTopology) hybridReceivedEvents else null
        val processedNeuronIds = if (isHybridTopology) hybridProcessedNeuronIds else null
        val spikingNeuronIds = if (isHybridTopology) hybridSpikingNeuronIds else null
        val spikeCounts = if (isHybridTopology) hybridSpikeCounts else null
        val outgoingEventCounts = if (isHybridTopology) hybridOutgoingEventCounts else null
        fun populationIndex(neuronId: Int): Int =
            hybridPopulations.indexOfFirst { it.contains(neuronId) }

        var clock = store.stats(hot.size, hotSetCapacity).currentTimeNs

        while (timestamps < maxTimestamps) {
            val group = store.nextTimestampEvents(MAX_EVENTS_PER_TIMESTAMP) ?: break
            if (processedEvents + group.size > maxEvents) {
                exhausted = true
                break
            }
            val timestamp = group.first().timeNs
            if (timestamp <= clock) {
                throw AndroidNeuronNetworkException("pending event is not later than the committed network clock")
            }

            val byTarget = group.groupBy { it.targetNeuronId }
            if (receivedEvents != null && processedNeuronIds != null) {
                group.forEach { event ->
                    val index = populationIndex(event.targetNeuronId)
                    if (index >= 0) receivedEvents[index]++
                }
                byTarget.keys.forEach { neuronId ->
                    val index = populationIndex(neuronId)
                    if (index >= 0) processedNeuronIds[index].add(neuronId)
                }
            }
            val changed = LinkedHashMap<Int, AndroidNeuronState>()
            val generated = mutableListOf<AndroidNeuronEvent>()

            // Build detached candidates. Nothing in the resident cache changes
            // until the store has atomically committed the complete timestamp.
            for ((targetId, targetEvents) in byTarget.toSortedMap()) {
                val previous = stateFor(targetId)
                val result = processNeuron(previous, timestamp, targetEvents.sumOf { it.strength })
                val targetCoreTainted = previous.coreTainted || targetEvents.any { it.coreTainted }
                val updatedState = result.first.copy(coreTainted = targetCoreTainted)
                changed[targetId] = updatedState
                if (targetCoreTainted) coreTaintedNeuronIds += targetId
                if (result.second) {
                    // These events were delivered to a target that spiked at this timestamp.
                    // Simultaneous inputs share coarse eligibility; this is not causal attribution.
                    targetEvents.forEach { event ->
                        connectionIdFromPropagatedEvent(event.eventId)?.let(activatedConnectionIds::add)
                    }
                    val spikeSequence = updatedState.spikeSequence
                    spikes += targetId to spikeSequence
                    if (spikingNeuronIds != null && spikeCounts != null) {
                        val index = populationIndex(targetId)
                        if (index >= 0) {
                            spikingNeuronIds[index].add(targetId)
                            spikeCounts[index]++
                        }
                    }
                    val outgoingConnections = store.outgoing(targetId)
                    if (outgoingEventCounts != null) {
                        val index = populationIndex(targetId)
                        if (index >= 0) outgoingEventCounts[index] += outgoingConnections.size
                    }
                    for (connection in outgoingConnections) {
                        if (timestamp > Long.MAX_VALUE - connection.delayNs) {
                            throw AndroidNeuronNetworkException("propagated model timestamp overflows signed 64-bit time")
                        }
                        generated += AndroidNeuronEvent(
                            eventId = "spike-$targetId-$spikeSequence-${connection.connectionId}",
                            timeNs = timestamp + connection.delayNs,
                            targetNeuronId = connection.targetNeuronId,
                            strength = connection.weight,
                            coreTainted = updatedState.coreTainted,
                        )
                    }
                }
            }

            store.commitTimestamp(timestamp, group, generated, changed.values)
            // The durable COMMIT succeeded. Only now publish candidates to the
            // LRU and permit clean least-recently-used states to be evicted.
            changed.values.forEach(::publish)
            processedEvents += group.size
            processedBatches += byTarget.size
            clock = timestamp
            timestamps++
        }

        val finalStats = store.stats(hot.size, hotSetCapacity)
        val hybridTelemetry = if (
            receivedEvents != null && processedNeuronIds != null &&
            spikingNeuronIds != null && spikeCounts != null && outgoingEventCounts != null
        ) {
            AndroidHybridRunTelemetry(
                topologyId = AndroidHybridTopologyV2.TOPOLOGY_ID,
                topologyVersion = AndroidHybridTopologyV2.TOPOLOGY_VERSION,
                populations = hybridPopulations.mapIndexed { index, population ->
                    AndroidNeuronPopulationActivity(
                        populationId = population.id,
                        receivedEventCount = receivedEvents[index],
                        uniqueNeuronsProcessed = processedNeuronIds[index].size,
                        uniqueSpikingNeurons = spikingNeuronIds[index].size,
                        spikeCount = spikeCounts[index],
                        outgoingEventCount = outgoingEventCounts[index],
                    )
                },
            )
        } else null
        return AndroidNeuronRunResult(
            processedEvents = processedEvents,
            processedNeuronBatches = processedBatches,
            emittedSpikes = spikes,
            activatedConnectionIds = activatedConnectionIds,
            coreTaintedNeuronIds = coreTaintedNeuronIds,
            pendingEvents = finalStats.pendingEvents,
            budgetExhausted = exhausted,
            currentTimeNs = finalStats.currentTimeNs,
            hybridTelemetry = hybridTelemetry,
        )
    }

    /** Save eligibility only for feature/output pairs whose output really spiked. */
    @Synchronized
    fun recordDialogueEligibility(
        contextId: String,
        sourceNeuronIds: List<Int>,
        outputNeuronIds: List<Int>,
        preTimeNs: Long,
        postTimeNs: Long,
    ): Int {
        validateDialogueContext(contextId)
        val sources = sourceNeuronIds.distinct()
        val outputs = outputNeuronIds.distinct()
        require(sources.isNotEmpty() && sources.size <= MAX_DIALOGUE_FEATURES_PER_TURN)
        require(sources.all { it in DIALOGUE_HIDDEN_START until DIALOGUE_OUTPUT_START })
        require(outputs.isNotEmpty() && outputs.size <= DIALOGUE_OUTPUT_NEURONS)
        require(outputs.all { it in DIALOGUE_OUTPUT_START until MAX_NEURONS })
        require(preTimeNs >= 0L && postTimeNs >= preTimeNs)
        require(postTimeNs - preTimeNs <= MAX_DIALOGUE_PAIR_DELAY_NS)
        return store.recordDialogueEligibility(contextId, sources, outputs, preTimeNs, postTimeNs)
    }

    /** Apply delayed reward to hidden-to-output synapses and consume the turn trace. */
    @Synchronized
    fun applyDialogueReward(
        contextId: String,
        sourceNeuronIds: List<Int>,
        outputRewards: Map<Int, Double>,
    ): AndroidDialogueRewardResult {
        validateDialogueContext(contextId)
        val sources = sourceNeuronIds.distinct()
        require(sources.isNotEmpty() && sources.size <= MAX_DIALOGUE_FEATURES_PER_TURN)
        require(sources.all { it in DIALOGUE_HIDDEN_START until DIALOGUE_OUTPUT_START })
        require(outputRewards.isNotEmpty() && outputRewards.size <= DIALOGUE_OUTPUT_NEURONS)
        require(outputRewards.keys.all { it in DIALOGUE_OUTPUT_START until MAX_NEURONS })
        require(outputRewards.values.all { it.isFinite() && it in -1.0..1.0 && it != 0.0 })
        return store.applyDialogueReward(contextId, sources, outputRewards)
    }

    /**
     * Records eligibility only for existing v4 synapses belonging to the named candidate lane.
     * Callers must supply connection IDs observed in this turn's actual spike path.
     */
    @Synchronized
    fun recordHybridCandidateEligibility(
        turnId: String,
        candidateId: String,
        connectionIds: List<String>,
        eligibility: Double,
    ): Int {
        validateHybridCandidateTrace(turnId, candidateId, connectionIds, eligibility)
        return store.recordHybridCandidateEligibility(turnId, candidateId, connectionIds.distinct(), eligibility)
    }

    /** Consumes one turn/candidate trace; only explicit user or verified outcome feedback can update it. */
    @Synchronized
    fun applyHybridCandidateFeedback(
        turnId: String,
        candidateId: String,
        reward: Double,
        source: ANS100CandidateFeedbackLearner.FeedbackSource,
    ): Int {
        require(turnId.isNotBlank() && turnId.length <= 128)
        require(ANS100CandidateSlotContract.slotForCandidateId(candidateId) != null)
        require(reward.isFinite() && reward in -1.0..1.0 && reward != 0.0)
        return store.applyHybridCandidateFeedback(turnId, candidateId, reward, source)
    }

    /**
     * Resolve stable concept input neurons in one SQLite transaction. Existing
     * assignments are permanent; new concepts receive the lowest unused slot.
     * A removed workspace record never frees/reassigns its old neural identity.
     */
    @Synchronized
    fun resolveConceptInputSlots(conceptIds: List<String>): Map<String, Int> {
        require(conceptIds.size <= ANS100ConceptMemory.MAX_CONCEPTS)
        require(conceptIds.distinct().size == conceptIds.size)
        require(conceptIds.all { it.matches(Regex("^[a-z][a-z0-9_.:-]{0,95}$")) })
        return store.resolveConceptInputSlots(conceptIds)
    }

    /** Persist eligibility for actual concept-to-association synapses. */
    @Synchronized
    fun recordNeuralAssociationEligibility(turnId: String, candidateId: String, connectionIds: List<String>, eligibility: Double): Int {
        require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(candidateId.matches(Regex("^neural-association:[a-f0-9]{32}$")))
        ANS100NeuralAssociationEligibilityContract.validateEligibilityIds(connectionIds)
        require(eligibility.isFinite() && eligibility in 0.0..1.0)
        return store.recordNeuralAssociationEligibility(turnId, candidateId, connectionIds, eligibility)
    }

    @Synchronized
    fun neuralAssociationWeights(connectionIds: List<String>): Map<String, Double> {
        ANS100NeuralAssociationEligibilityContract.validateWeightQueryIds(connectionIds)
        return store.neuralAssociationWeights(connectionIds)
    }

    @Synchronized
    fun recordNeuralAssociationEvaluation(candidateId: String, observation: ANS100NeuralAssociationAssessment.Observation): Int {
        require(candidateId.matches(Regex("^neural-association:[a-f0-9]{32}$")))
        require(observation.taskId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(observation.independenceKey.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        return store.recordNeuralAssociationEvaluation(candidateId, observation)
    }

    @Synchronized
    fun neuralAssociationEvaluations(candidateId: String, limit: Int = 200): List<ANS100NeuralAssociationAssessment.PersistedObservation> {
        require(candidateId.matches(Regex("^neural-association:[a-f0-9]{32}$")))
        require(limit in 1..2_000)
        return store.neuralAssociationEvaluations(candidateId, limit)
    }

    @Synchronized
    fun neuralAssociationWeightHistory(candidateId: String, limit: Int = 200): List<ANS100NeuralAssociationAssessment.WeightChange> {
        require(candidateId.matches(Regex("^neural-association:[a-f0-9]{32}$")))
        require(limit in 1..2_000)
        return store.neuralAssociationWeightHistory(candidateId, limit)
    }

    /** Compute stability from persisted independent outcomes; this never updates weights. */
    @Synchronized
    fun assessNeuralAssociation(
        candidateId: String,
        feedback: ANS100NeuralAssociationAssessment.ExplicitFeedback = ANS100NeuralAssociationAssessment.ExplicitFeedback.NONE,
    ): ANS100NeuralAssociationAssessment.Assessment {
        val observations = neuralAssociationEvaluations(candidateId, ANS100NeuralAssociationAssessment.MAX_OBSERVATIONS)
            .map { it.observation }
        val latest = store.latestNeuralAssociationFeedback(candidateId)
        val effectiveFeedback = latest?.let {
            if (it.reward > 0.0) ANS100NeuralAssociationAssessment.ExplicitFeedback.POSITIVE
            else ANS100NeuralAssociationAssessment.ExplicitFeedback.NEGATIVE
        } ?: feedback
        return ANS100NeuralAssociationAssessment().assess(observations, effectiveFeedback, latest?.source)
    }

    /** Applies a stable user-feedback event at most once, atomically with weight updates. */
    @Synchronized
    fun applyNeuralAssociationFeedback(feedbackId: String, turnId: String, candidateId: String, reward: Double,
        source: ANS100CandidateFeedbackLearner.FeedbackSource): Int {
        require(feedbackId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        require(candidateId.matches(Regex("^neural-association:[a-f0-9]{32}$")))
        require(reward.isFinite() && reward in -1.0..1.0 && reward != 0.0)
        return store.applyNeuralAssociationFeedback(feedbackId, turnId, candidateId, reward, source)
    }

    private fun validateHybridCandidateTrace(
        turnId: String,
        candidateId: String,
        connectionIds: List<String>,
        eligibility: Double,
    ) {
        require(turnId.isNotBlank() && turnId.length <= 128)
        require(ANS100CandidateSlotContract.slotForCandidateId(candidateId) != null)
        require(connectionIds.isNotEmpty() && connectionIds.size <= 2_000)
        require(connectionIds.distinct().size == connectionIds.size)
        require(connectionIds.all { it.startsWith("ans100-v4-") })
        require(eligibility.isFinite() && eligibility in 0.0..1.0)
    }

    @Synchronized
    fun dialogueSynapseWeight(sourceNeuronId: Int, outputNeuronId: Int): Double {
        require(sourceNeuronId in DIALOGUE_HIDDEN_START until DIALOGUE_OUTPUT_START)
        require(outputNeuronId in DIALOGUE_OUTPUT_START until MAX_NEURONS)
        return store.dialogueSynapseWeight(sourceNeuronId, outputNeuronId)
    }

    /**
     * Read the learned four-way synaptic preference for active hidden neurons.
     * Values are mean connection weights, not calibrated probabilities.
     */
    @Synchronized
    fun dialogueOutputScores(hiddenNeuronIds: List<Int>): Map<Int, Double> {
        val sources = hiddenNeuronIds.distinct()
        require(sources.isNotEmpty() && sources.size <= MAX_DIALOGUE_FEATURES_PER_TURN)
        require(sources.all { it in DIALOGUE_HIDDEN_START until DIALOGUE_OUTPUT_START })
        return store.dialogueOutputScores(sources)
    }

    private fun validateDialogueContext(contextId: String) {
        require(contextId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
    }

    @Synchronized
    fun stats(): AndroidNeuronNetworkStats = store.stats(hot.size, hotSetCapacity)

    @Synchronized
    override fun close() {
        hot.clear()
        store.close()
    }

    private fun stateFor(neuronId: Int): AndroidNeuronState {
        hot[neuronId]?.let { return it }
        val loaded = store.loadNeuron(neuronId)
        publish(loaded)
        return loaded
    }

    private fun publish(state: AndroidNeuronState) {
        hot[state.neuronId] = state
        while (hot.size > hotSetCapacity) {
            val eldest = hot.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    private fun processNeuron(
        state: AndroidNeuronState,
        timestampNs: Long,
        input: Double,
    ): Pair<AndroidNeuronState, Boolean> {
        if (timestampNs < state.lastUpdateNs) {
            throw AndroidNeuronNetworkException("neuron event time moved backwards")
        }
        val elapsed = timestampNs - state.lastUpdateNs
        val decayedPotential = state.potential * exp(-elapsed.toDouble() / TAU_MEMBRANE_NS)
        val decayedAdaptation = state.adaptation * exp(-elapsed.toDouble() / TAU_ADAPTATION_NS)
        val potential = (decayedPotential + input).coerceIn(POTENTIAL_MIN, POTENTIAL_MAX)
        val threshold = (BASE_THRESHOLD + ADAPTATION_GAIN * decayedAdaptation)
            .coerceIn(BASE_THRESHOLD, MAX_THRESHOLD)
        val spiked = timestampNs >= state.refractoryUntilNs && potential >= threshold
        if (!spiked) {
            return AndroidNeuronState(
                neuronId = state.neuronId,
                potential = potential,
                adaptation = decayedAdaptation.coerceIn(0.0, 1.0),
                lastUpdateNs = timestampNs,
                refractoryUntilNs = state.refractoryUntilNs,
                spikeSequence = state.spikeSequence,
            ) to false
        }
        if (state.spikeSequence == Long.MAX_VALUE ||
            timestampNs > Long.MAX_VALUE - REFRACTORY_NS
        ) throw AndroidNeuronNetworkException("neuron spike counter or refractory clock would overflow")
        return AndroidNeuronState(
            neuronId = state.neuronId,
            potential = RESET_POTENTIAL,
            adaptation = (decayedAdaptation + ADAPTATION_PER_SPIKE).coerceIn(0.0, 1.0),
            lastUpdateNs = timestampNs,
            refractoryUntilNs = timestampNs + REFRACTORY_NS,
            spikeSequence = state.spikeSequence + 1L,
        ) to true
    }

    companion object {
        const val MAX_NEURONS = 10_000
        const val MAX_CONNECTIONS = 100_000
        const val MAX_STRUCTURAL_SYNAPSES_PER_UPDATE = 64
        const val MAX_STRUCTURAL_WEIGHT_DELTA = 0.10
        const val MAX_STRUCTURAL_MIN_WEIGHT = 0.20
        const val MAX_PENDING_EVENTS = 100_000
        const val MAX_HOT_SET_CAPACITY = 4_096
        const val DIALOGUE_OUTPUT_NEURONS = 4
        const val DIALOGUE_INPUT_NEURONS = 9_000
        const val DIALOGUE_HIDDEN_START = DIALOGUE_INPUT_NEURONS
        const val DIALOGUE_OUTPUT_START = MAX_NEURONS - DIALOGUE_OUTPUT_NEURONS
        const val DIALOGUE_HIDDEN_NEURONS = DIALOGUE_OUTPUT_START - DIALOGUE_HIDDEN_START
        const val DIALOGUE_FEATURE_NEURONS = DIALOGUE_INPUT_NEURONS
        const val DIALOGUE_INPUT_FAN_OUT = 8
        const val DIALOGUE_TOPOLOGY_VERSION = 2
        const val MAX_DIALOGUE_FEATURES_PER_TURN = 256
        const val MAX_DIALOGUE_PAIR_DELAY_NS = 10_000_000L
        const val DEFAULT_HOT_SET_CAPACITY = 128
        const val MAX_EVENTS_PER_TIMESTAMP = 10_000
        const val MAX_EVENTS_PER_RUN = 100_000
        const val MAX_TIMESTAMPS_PER_RUN = 10_000
        const val DEFAULT_MAX_EVENTS_PER_RUN = 4_096
        const val DEFAULT_MAX_TIMESTAMPS_PER_RUN = 128
        const val DEFAULT_DATABASE_NAME = "jarvis_android_dialogue_jnvm_v2.db"
        const val DEFAULT_CONNECTION_DELAY_NS = 1_000_000L
        const val DEFAULT_CONNECTION_WEIGHT = 0.75
        const val DEFAULT_DIALOGUE_OUTPUT_WEIGHT = 0.15
        const val DEFAULT_INPUT_TO_HIDDEN_WEIGHT = 0.75
        private const val POTENTIAL_MIN = -2.0
        private const val POTENTIAL_MAX = 2.0
        private const val RESET_POTENTIAL = 0.0
        private const val BASE_THRESHOLD = 1.0
        private const val MAX_THRESHOLD = 2.0
        private const val ADAPTATION_GAIN = 0.5
        private const val ADAPTATION_PER_SPIKE = 0.15
        private const val TAU_MEMBRANE_NS = 20_000_000L
        private const val TAU_ADAPTATION_NS = 100_000_000L
        private const val REFRACTORY_NS = 2_000_000L
    }
}

private object AndroidNeuronNetworkLimits {
    const val MAX_NEURONS = 10_000
}

private object AndroidNeuronSparseLimits {
    const val MAX_CONNECTIONS = 100_000
}

private class AndroidNeuronSqliteStore(
    context: Context,
    name: String,
) : SQLiteOpenHelper(context, name, null, DATABASE_VERSION), AndroidNeuronStateStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE neurons (neuron_id INTEGER PRIMARY KEY, potential REAL NOT NULL, adaptation REAL NOT NULL, last_update_ns INTEGER NOT NULL, refractory_until_ns INTEGER NOT NULL, spike_sequence INTEGER NOT NULL, core_tainted INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE connections (connection_id TEXT PRIMARY KEY, source_id INTEGER NOT NULL, target_id INTEGER NOT NULL, delay_ns INTEGER NOT NULL, weight REAL NOT NULL)",
        )
        db.execSQL("CREATE INDEX connections_source_idx ON connections(source_id)")
        createDialogueEligibilityTable(db)
        createHybridCandidateEligibilityTable(db)
        createNeuralAssociationLearningTables(db)
        createNeuralAssociationAssessmentTables(db)
        createConceptInputSlotTable(db)
        createStructuralSynapseRegistry(db)
        createStructuralSynapseFeedbackTable(db)
        createStructuralPlasticityObservationTables(db)
        createCanonicalReadoutTables(db)
        db.execSQL(
            "CREATE TABLE events (event_id TEXT PRIMARY KEY, time_ns INTEGER NOT NULL, target_id INTEGER NOT NULL, strength REAL NOT NULL, core_tainted INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX events_time_idx ON events(time_ns, event_id)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO metadata(key, value) VALUES('model_time_ns', 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDialogueEligibilityTable(db)
        if (oldVersion < 3) createHybridCandidateEligibilityTable(db)
        if (oldVersion < 4) {
            createNeuralAssociationLearningTables(db)
            // Reserve headroom for positive explicit reinforcement of concept
            // input synapses; other evidence and all non-concept weights are untouched.
            db.execSQL(
                "UPDATE connections SET weight=? WHERE source_id BETWEEN ? AND ? AND connection_id LIKE ? AND weight>=1.0",
                arrayOf(
                    AndroidHybridTopologyV2.CONCEPT_ASSOCIATION_INITIAL_WEIGHT,
                    ANS100ConceptMemory.NEURAL_INPUT_START,
                    ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS - 1,
                    "ans100-v4-evidence-general-association-%",
                ),
            )
        }
        if (oldVersion < 5) {
            createConceptInputSlotTable(db)
            // V4 slot identities were ephemeral per turn. Old association
            // weights cannot be safely attributed to stable concept IDs.
            db.execSQL(
                "UPDATE connections SET weight=? WHERE source_id BETWEEN ? AND ? AND connection_id LIKE ?",
                arrayOf(
                    AndroidHybridTopologyV2.CONCEPT_ASSOCIATION_INITIAL_WEIGHT,
                    ANS100ConceptMemory.NEURAL_INPUT_START,
                    ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS - 1,
                    "ans100-v4-evidence-general-association-%",
                ),
            )
            db.delete("neural_association_eligibility", null, null)
            db.delete("neural_association_feedback_events", null, null)
        }
        if (oldVersion < 6) createStructuralSynapseRegistry(db)
        if (oldVersion < 7) createStructuralSynapseFeedbackTable(db)
        if (oldVersion < 8) createStructuralPlasticityObservationTables(db)
        if (oldVersion < 9) createCanonicalReadoutTables(db)
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE neurons ADD COLUMN core_tainted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE events ADD COLUMN core_tainted INTEGER NOT NULL DEFAULT 0")
            // Old queued events have unknown origin; fail closed. Residual membrane
            // state is tainted until the next isolated hybrid-turn reset.
            db.execSQL("UPDATE events SET core_tainted=1")
            db.execSQL("UPDATE neurons SET core_tainted=1 WHERE potential!=0 OR adaptation!=0 OR refractory_until_ns>last_update_ns")
        }
        if (oldVersion < 11) createNeuralAssociationAssessmentTables(db)
        if (newVersion > DATABASE_VERSION) {
            throw AndroidNeuronNetworkException("Unsupported Android JNVM schema migration: $oldVersion -> $newVersion")
        }
    }

    private fun createHybridCandidateEligibilityTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS hybrid_candidate_eligibility (turn_id TEXT NOT NULL, candidate_id TEXT NOT NULL, connection_id TEXT NOT NULL, eligibility REAL NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(turn_id, candidate_id, connection_id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS hybrid_candidate_eligibility_age_idx ON hybrid_candidate_eligibility(created_at_ms)")
    }

    private fun createNeuralAssociationLearningTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS neural_association_eligibility (turn_id TEXT NOT NULL, candidate_id TEXT NOT NULL, connection_id TEXT NOT NULL, eligibility REAL NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(turn_id, candidate_id, connection_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS neural_association_eligibility_age_idx ON neural_association_eligibility(created_at_ms)")
        db.execSQL("CREATE TABLE IF NOT EXISTS neural_association_feedback_events (feedback_id TEXT PRIMARY KEY, turn_id TEXT NOT NULL, candidate_id TEXT NOT NULL, reward REAL NOT NULL, source TEXT NOT NULL, created_at_ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS neural_association_feedback_age_idx ON neural_association_feedback_events(created_at_ms)")
    }

    private fun createNeuralAssociationAssessmentTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS neural_association_task_evaluations (candidate_id TEXT NOT NULL, task_id TEXT NOT NULL, independence_key TEXT NOT NULL, outcome TEXT NOT NULL, evidence_source TEXT NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(candidate_id, task_id), UNIQUE(candidate_id, independence_key))")
        db.execSQL("CREATE INDEX IF NOT EXISTS neural_association_eval_age_idx ON neural_association_task_evaluations(candidate_id, created_at_ms)")
        db.execSQL("CREATE TABLE IF NOT EXISTS neural_association_weight_history (feedback_id TEXT NOT NULL, connection_id TEXT NOT NULL, turn_id TEXT NOT NULL, candidate_id TEXT NOT NULL, previous_weight REAL NOT NULL, next_weight REAL NOT NULL, reward REAL NOT NULL, source TEXT NOT NULL, changed_at_ms INTEGER NOT NULL, PRIMARY KEY(feedback_id, connection_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS neural_association_weight_history_candidate_idx ON neural_association_weight_history(candidate_id, changed_at_ms)")
    }

    private fun createStructuralSynapseRegistry(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS structural_synapse_registry (connection_id TEXT PRIMARY KEY, proposal_id TEXT NOT NULL, first_concept_id TEXT NOT NULL, second_concept_id TEXT NOT NULL, created_at_ms INTEGER NOT NULL, last_adjusted_at_ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS structural_synapse_proposal_idx ON structural_synapse_registry(proposal_id)")
    }

    private fun createStructuralPlasticityObservationTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS structural_plasticity_turns (sequence INTEGER PRIMARY KEY AUTOINCREMENT, turn_id TEXT NOT NULL UNIQUE, created_at_ms INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS structural_plasticity_turn_concepts (turn_id TEXT NOT NULL, concept_id TEXT NOT NULL, neuron_id INTEGER NOT NULL, label TEXT NOT NULL, provenance_ref TEXT NOT NULL, PRIMARY KEY(turn_id, concept_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS structural_plasticity_concepts_turn_idx ON structural_plasticity_turn_concepts(turn_id)")
    }

    private fun createStructuralSynapseFeedbackTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS structural_synapse_feedback_events (feedback_id TEXT NOT NULL, proposal_id TEXT NOT NULL, delta REAL NOT NULL, source TEXT NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(feedback_id, proposal_id))")
    }

    private fun createCanonicalReadoutTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS canonical_readout_eligibility (turn_id TEXT NOT NULL, source_neuron_id INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(turn_id, source_neuron_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS canonical_readout_eligibility_age_idx ON canonical_readout_eligibility(created_at_ms)")
        db.execSQL("CREATE TABLE IF NOT EXISTS canonical_readout_feedback_events (feedback_id TEXT PRIMARY KEY, turn_id TEXT NOT NULL, answer TEXT NOT NULL, source TEXT NOT NULL, created_at_ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS canonical_readout_feedback_age_idx ON canonical_readout_feedback_events(created_at_ms)")
    }

    private fun createConceptInputSlotTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS neural_concept_input_slots (concept_id TEXT PRIMARY KEY, neuron_id INTEGER NOT NULL UNIQUE, created_at_ms INTEGER NOT NULL, CHECK(neuron_id BETWEEN 1062 AND 1199))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS neural_concept_input_slots_neuron_idx ON neural_concept_input_slots(neuron_id)")
    }

    private fun createDialogueEligibilityTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS dialogue_eligibility (context_id TEXT NOT NULL, connection_id TEXT NOT NULL, feature_id INTEGER NOT NULL, output_id INTEGER NOT NULL, eligibility REAL NOT NULL, pre_time_ns INTEGER NOT NULL, post_time_ns INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(context_id, connection_id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS dialogue_eligibility_age_idx ON dialogue_eligibility(created_at_ms)")
    }

    override fun initializeHybridTopology(
        topologyId: String,
        topologyVersion: Int,
        connections: List<AndroidNeuronConnection>,
    ): Boolean {
        if (!topologyId.matches(Regex("^[a-z0-9][a-z0-9._-]{0,63}$")) ||
            topologyVersion <= 0 ||
            connections.isEmpty() ||
            connections.size > AndroidNeuronSparseLimits.MAX_CONNECTIONS ||
            connections.map { it.connectionId }.toSet().size != connections.size ||
            connections.any {
                it.sourceNeuronId !in 0 until AndroidNeuronNetworkLimits.MAX_NEURONS ||
                    it.targetNeuronId !in 0 until AndroidNeuronNetworkLimits.MAX_NEURONS
            }
        ) throw AndroidNeuronNetworkException("invalid bounded typed topology descriptor")

        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingNeurons = scalar(db, "SELECT COUNT(*) FROM neurons")
            val existingConnections = scalar(db, "SELECT COUNT(*) FROM connections")
            val existingId = db.rawQuery(
                "SELECT value FROM metadata WHERE key='ans100_topology_id'", null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val existingVersion = db.rawQuery(
                "SELECT value FROM metadata WHERE key='ans100_topology_version'", null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0).toInt() else 0 }
            if (existingNeurons != 0L || existingConnections != 0L || existingId != null || existingVersion != 0) {
                val staticConnections = scalar(db, "SELECT COUNT(*) FROM connections WHERE connection_id LIKE 'ans100-v4-%'")
                val knownDynamicConnections = scalar(
                    db,
                    "SELECT COUNT(*) FROM connections WHERE connection_id LIKE 'ans100-v5-grown-association-%' OR connection_id LIKE 'ans100-v9-canonical-readout-%'",
                )
                val compatibleCounts = staticConnections == connections.size.toLong() &&
                    existingConnections == staticConnections + knownDynamicConnections
                if (existingNeurons != AndroidNeuronNetworkLimits.MAX_NEURONS.toLong() ||
                    !compatibleCounts || existingId != topologyId
                ) throw AndroidNeuronNetworkException(
                    "hybrid database contains an unknown or incomplete topology; refusing destructive reinitialization",
                )
                if (existingVersion == topologyVersion) {
                    db.setTransactionSuccessful()
                    return false
                }
                if (existingVersion == 4 && topologyVersion == 5) {
                    migrateHybridTopologyV4ToV5(db)
                    db.update(
                        "metadata",
                        ContentValues().apply { put("value", topologyVersion) },
                        "key='ans100_topology_version'",
                        null,
                    )
                    db.setTransactionSuccessful()
                    return false
                }
                throw AndroidNeuronNetworkException(
                    "unsupported hybrid topology migration: $existingVersion -> $topologyVersion",
                )
            }

            for (id in 0 until AndroidNeuronNetworkLimits.MAX_NEURONS) insertNeuron(db, id)
            for (connection in connections) {
                if (connection.sourceNeuronId == connection.targetNeuronId) {
                    throw AndroidNeuronNetworkException("typed topology cannot contain self-connections")
                }
                insertConnection(db, connection)
            }
            fun writeMetadata(key: String, value: String) {
                val values = ContentValues().apply {
                    put("key", key)
                    put("value", value)
                }
                db.insertOrThrow("metadata", null, values)
            }
            writeMetadata("ans100_topology_id", topologyId)
            writeMetadata("ans100_topology_version", topologyVersion.toString())
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    override fun matchesTopology(
        topologyId: String,
        topologyVersion: Int,
        neuronCount: Int,
        connectionCount: Int,
    ): Boolean {
        val db = readableDatabase
        val actualNeurons = scalar(db, "SELECT COUNT(*) FROM neurons")
        val actualConnections = scalar(db, "SELECT COUNT(*) FROM connections")
        val actualId = db.rawQuery(
            "SELECT value FROM metadata WHERE key='ans100_topology_id'", null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val actualVersion = db.rawQuery(
            "SELECT value FROM metadata WHERE key='ans100_topology_version'", null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0).toInt() else 0 }
        val staticConnections = scalar(db, "SELECT COUNT(*) FROM connections WHERE connection_id LIKE 'ans100-v4-%'")
        val knownDynamicConnections = scalar(
            db,
            "SELECT COUNT(*) FROM connections WHERE connection_id LIKE 'ans100-v5-grown-association-%' OR connection_id LIKE 'ans100-v9-canonical-readout-%'",
        )
        return actualNeurons == neuronCount.toLong() &&
            staticConnections == connectionCount.toLong() &&
            actualConnections == staticConnections + knownDynamicConnections &&
            actualId == topologyId &&
            actualVersion == topologyVersion
    }

    private fun migrateHybridTopologyV4ToV5(db: SQLiteDatabase) {
        if (scalar(db, "SELECT COUNT(*) FROM events") != 0L) {
            throw AndroidNeuronBackpressureException(
                "cannot migrate ANS-100 topology while delayed neural events remain queued",
            )
        }
        val rows = mutableListOf<Pair<String, Int>>()
        db.rawQuery(
            "SELECT connection_id, source_id FROM connections WHERE connection_id LIKE 'ans100-v4-working-to-goal-%' ORDER BY source_id",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getInt(1)
        }
        if (rows.size != 1_800 || rows.map { it.second }.toSet().size != 1_800 ||
            rows.any { it.second !in 4_000 until 5_800 }
        ) {
            throw AndroidNeuronNetworkException(
                "V4-to-V5 migration found an unexpected working-to-goal projection; no topology was changed",
            )
        }
        for ((connectionId, sourceId) in rows) {
            val offset = Math.floorMod((sourceId - 4_000).toLong() * 37L + 97L, 300L).toInt()
            val targetId = 8_500 + offset
            if (db.update(
                    "connections",
                    ContentValues().apply { put("target_id", targetId) },
                    "connection_id=?",
                    arrayOf(connectionId),
                ) != 1
            ) throw AndroidNeuronNetworkException("failed to isolate canonical output neurons during migration")
        }
        val modelTime = scalar(db, "SELECT value FROM metadata WHERE key='model_time_ns'")
        db.update(
            "neurons",
            ContentValues().apply {
                put("potential", 0.0)
                put("adaptation", 0.0)
                put("last_update_ns", modelTime)
                put("refractory_until_ns", modelTime)
            },
            "neuron_id BETWEEN ? AND ?",
            arrayOf(
                ANS100CanonicalReadoutContract.OUTPUT_START.toString(),
                (ANS100CanonicalReadoutContract.OUTPUT_END - 1).toString(),
            ),
        )
    }

    override fun resetHybridTurnState(modelTimeNs: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val pending = scalar(db, "SELECT COUNT(*) FROM events")
            val clock = scalar(db, "SELECT value FROM metadata WHERE key='model_time_ns'")
            if (pending != 0L || modelTimeNs != clock) return false
            val values = ContentValues().apply {
                put("potential", 0.0)
                put("adaptation", 0.0)
                put("last_update_ns", modelTimeNs)
                put("refractory_until_ns", modelTimeNs)
                put("core_tainted", 0)
            }
            db.update("neurons", values, null, null)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    override fun initializeSparseChain(neuronCount: Int, delayNs: Long, weight: Double) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = scalar(db, "SELECT COUNT(*) FROM neurons")
            if (existing != 0L) throw AndroidNeuronNetworkException("network is already initialized")
            for (id in 0 until neuronCount) {
                insertNeuron(db, id)
                if (id > 0) {
                    insertConnection(
                        db,
                        AndroidNeuronConnection("chain-${id - 1}", id - 1, id, delayNs, weight),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun initializeSparseDialogueNetwork(
        neuronCount: Int,
        featureNeuronCount: Int,
        delayNs: Long,
        weight: Double,
        inputToHiddenWeight: Double,
    ): Boolean {
        val hiddenCount = AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_NEURONS
        val outputStart = AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START
        val connectionCount = featureNeuronCount.toLong() * AndroidSparseNeuronNetwork.DIALOGUE_INPUT_FAN_OUT +
            hiddenCount.toLong() * AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_NEURONS
        if (neuronCount != AndroidSparseNeuronNetwork.MAX_NEURONS ||
            featureNeuronCount != AndroidSparseNeuronNetwork.DIALOGUE_INPUT_NEURONS ||
            connectionCount > AndroidSparseNeuronNetwork.MAX_CONNECTIONS ||
            delayNs <= 0L || !weight.isFinite() || weight !in 0.0..1.0 ||
            !inputToHiddenWeight.isFinite() || inputToHiddenWeight !in 0.0..1.0
        ) throw AndroidNeuronNetworkException("invalid bounded multi-stage dialogue topology")

        val db = writableDatabase
        db.beginTransaction()
        var changed = false
        try {
            val existingNeurons = scalar(db, "SELECT COUNT(*) FROM neurons")
            if (existingNeurons == 0L) {
                for (id in 0 until neuronCount) insertNeuron(db, id)
                changed = true
            } else if (existingNeurons != neuronCount.toLong()) {
                throw AndroidNeuronNetworkException("existing dialogue network has an unexpected neuron count")
            }

            val topologyVersion = db.rawQuery(
                "SELECT value FROM metadata WHERE key='dialogue_topology_version'",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            if (topologyVersion > AndroidSparseNeuronNetwork.DIALOGUE_TOPOLOGY_VERSION.toLong()) {
                throw AndroidNeuronNetworkException("dialogue topology version is newer than this application")
            }
            if (topologyVersion == AndroidSparseNeuronNetwork.DIALOGUE_TOPOLOGY_VERSION.toLong()) {
                val actualConnections = scalar(db, "SELECT COUNT(*) FROM connections")
                if (actualConnections != connectionCount.toLong()) {
                    throw AndroidNeuronNetworkException("versioned dialogue topology has an unexpected connection count")
                }
                db.setTransactionSuccessful()
                return false
            }

            // Project the former direct feature-to-output weights through the
            // new deterministic input-to-hidden mapping. This preserves learned
            // readout tendencies without pretending old feature IDs are hidden IDs.
            val projectedWeightSums = HashMap<Pair<Int, Int>, Double>()
            val projectedWeightCounts = HashMap<Pair<Int, Int>, Int>()
            if (existingNeurons > 0L) {
                db.rawQuery(
                    "SELECT source_id, target_id, weight FROM connections WHERE source_id>=0 AND source_id<? AND target_id>=?",
                    arrayOf(outputStart.toString(), outputStart.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val oldFeatureId = cursor.getInt(0)
                        val outputId = cursor.getInt(1)
                        val oldWeight = cursor.getDouble(2)
                        if (outputId !in outputStart until AndroidSparseNeuronNetwork.MAX_NEURONS ||
                            !oldWeight.isFinite() || oldWeight !in 0.0..1.0
                        ) throw AndroidNeuronNetworkException("old dialogue readout contains an invalid synaptic weight")
                        val inputId = oldFeatureId % featureNeuronCount
                        for (slot in 0 until AndroidSparseNeuronNetwork.DIALOGUE_INPUT_FAN_OUT) {
                            val hiddenOffset = (inputId * 37 + slot * 113) % hiddenCount
                            val hiddenId = AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START + hiddenOffset
                            val key = hiddenId to outputId
                            projectedWeightSums[key] = (projectedWeightSums[key] ?: 0.0) + oldWeight
                            projectedWeightCounts[key] = (projectedWeightCounts[key] ?: 0) + 1
                        }
                    }
                }
                db.delete("connections", null, null)
                db.delete("events", null, null)
                db.delete("dialogue_eligibility", null, null)
                val resetHidden = ContentValues().apply {
                    put("potential", 0.0)
                    put("adaptation", 0.0)
                    put("refractory_until_ns", 0L)
                }
                db.update(
                    "neurons",
                    resetHidden,
                    "neuron_id>=? AND neuron_id<?",
                    arrayOf(
                        AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START.toString(),
                        outputStart.toString(),
                    ),
                )
            }

            for (inputId in 0 until featureNeuronCount) {
                for (slot in 0 until AndroidSparseNeuronNetwork.DIALOGUE_INPUT_FAN_OUT) {
                    val hiddenOffset = (inputId * 37 + slot * 113) % hiddenCount
                    val hiddenId = AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START + hiddenOffset
                    insertConnection(
                        db,
                        AndroidNeuronConnection(
                            connectionId = "dialog-input-$inputId-hidden-$hiddenId",
                            sourceNeuronId = inputId,
                            targetNeuronId = hiddenId,
                            delayNs = delayNs,
                            weight = inputToHiddenWeight,
                        ),
                    )
                }
            }
            for (hiddenId in AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START until outputStart) {
                for (outputIndex in 0 until AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_NEURONS) {
                    val outputId = outputStart + outputIndex
                    insertConnection(
                        db,
                        AndroidNeuronConnection(
                            connectionId = "dialog-feature-$hiddenId-output-$outputIndex",
                            sourceNeuronId = hiddenId,
                            targetNeuronId = outputId,
                            delayNs = delayNs,
                            weight = projectedWeightCounts[hiddenId to outputId]?.let { count ->
                                (projectedWeightSums.getValue(hiddenId to outputId) / count).coerceIn(0.0, 1.0)
                            } ?: weight,
                        ),
                    )
                }
            }
            val metadataValues = ContentValues().apply {
                put("value", AndroidSparseNeuronNetwork.DIALOGUE_TOPOLOGY_VERSION)
            }
            if (db.update("metadata", metadataValues, "key='dialogue_topology_version'", null) == 0) {
                metadataValues.put("key", "dialogue_topology_version")
                db.insertOrThrow("metadata", null, metadataValues)
            }
            changed = true
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    override fun registerNeuron(neuronId: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val count = scalar(db, "SELECT COUNT(*) FROM neurons")
            if (count >= AndroidSparseNeuronNetwork.MAX_NEURONS) {
                throw AndroidNeuronNetworkException("10,000-neuron capacity reached")
            }
            insertNeuron(db, neuronId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun recordStructuralPlasticityTurn(
        turnId: String,
        activeConcepts: List<ANS100ActivityDrivenStructuralPlasticity.ActiveConcept>,
    ): List<ANS100ActivityDrivenStructuralPlasticity.TurnSnapshot> {
        if (!turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) ||
            activeConcepts.size > ANS100ActivityDrivenStructuralPlasticity.MAX_ACTIVE_CONCEPTS_PER_TURN ||
            activeConcepts.map { it.conceptId }.distinct().size != activeConcepts.size ||
            activeConcepts.map { it.neuronId }.distinct().size != activeConcepts.size
        ) throw AndroidNeuronNetworkException("structural-plasticity observation is outside bounds")

        val db = writableDatabase
        db.beginTransaction()
        try {
            activeConcepts.forEach { concept ->
                val assignedNeuron = db.rawQuery(
                    "SELECT neuron_id FROM neural_concept_input_slots WHERE concept_id=?",
                    arrayOf(concept.conceptId),
                ).use { if (it.moveToFirst()) it.getInt(0) else null }
                if (assignedNeuron != concept.neuronId) {
                    throw AndroidNeuronNetworkException("structural observation does not match persistent concept slot")
                }
            }
            val alreadyRecorded = db.rawQuery(
                "SELECT 1 FROM structural_plasticity_turns WHERE turn_id=?",
                arrayOf(turnId),
            ).use { it.moveToFirst() }
            if (!alreadyRecorded) {
                db.insertOrThrow(
                    "structural_plasticity_turns",
                    null,
                    ContentValues().apply {
                        put("turn_id", turnId)
                        put("created_at_ms", System.currentTimeMillis())
                    },
                )
                activeConcepts.forEach { concept ->
                    db.insertOrThrow(
                        "structural_plasticity_turn_concepts",
                        null,
                        ContentValues().apply {
                            put("turn_id", turnId)
                            put("concept_id", concept.conceptId)
                            put("neuron_id", concept.neuronId)
                            put("label", concept.label)
                            put("provenance_ref", concept.provenanceRef)
                        },
                    )
                }
                db.delete(
                    "structural_plasticity_turn_concepts",
                    "turn_id IN (SELECT turn_id FROM structural_plasticity_turns ORDER BY sequence DESC LIMIT -1 OFFSET ?)",
                    arrayOf(MAX_PERSISTED_STRUCTURAL_TURNS.toString()),
                )
                db.delete(
                    "structural_plasticity_turns",
                    "sequence NOT IN (SELECT sequence FROM structural_plasticity_turns ORDER BY sequence DESC LIMIT ?)",
                    arrayOf(MAX_PERSISTED_STRUCTURAL_TURNS.toString()),
                )
            }

            val turnIds = mutableListOf<String>()
            db.rawQuery(
                "SELECT turn_id FROM structural_plasticity_turns ORDER BY sequence DESC LIMIT ?",
                arrayOf(STRUCTURAL_OBSERVATION_WINDOW_TURNS.toString()),
            ).use { cursor -> while (cursor.moveToNext()) turnIds += cursor.getString(0) }
            turnIds.reverse()
            val snapshots = turnIds.map { storedTurnId ->
                val concepts = mutableListOf<ANS100ActivityDrivenStructuralPlasticity.ActiveConcept>()
                db.rawQuery(
                    "SELECT concept_id, neuron_id, label, provenance_ref FROM structural_plasticity_turn_concepts WHERE turn_id=? ORDER BY concept_id",
                    arrayOf(storedTurnId),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        concepts += ANS100ActivityDrivenStructuralPlasticity.ActiveConcept(
                            conceptId = cursor.getString(0),
                            neuronId = cursor.getInt(1),
                            label = cursor.getString(2),
                            provenanceRef = cursor.getString(3),
                        )
                    }
                }
                ANS100ActivityDrivenStructuralPlasticity.TurnSnapshot(storedTurnId, concepts)
            }
            db.setTransactionSuccessful()
            return snapshots
        } finally {
            db.endTransaction()
        }
    }

    override fun structuralPlasticityGraphSnapshot(
        sourceNeuronIds: List<Int>,
    ): ANS100ActivityDrivenStructuralPlasticity.GraphSnapshot {
        if (sourceNeuronIds.size > ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS ||
            sourceNeuronIds.distinct().size != sourceNeuronIds.size ||
            sourceNeuronIds.any { it !in 1_062 until 1_200 }
        ) throw AndroidNeuronNetworkException("structural graph snapshot source list is outside bounds")
        val db = readableDatabase
        val targets = LinkedHashMap<Int, Set<Int>>()
        sourceNeuronIds.forEach { source ->
            val found = LinkedHashSet<Int>()
            db.rawQuery(
                "SELECT target_id FROM connections WHERE source_id=? AND target_id BETWEEN 1200 AND 2199",
                arrayOf(source.toString()),
            ).use { cursor -> while (cursor.moveToNext()) found += cursor.getInt(0) }
            targets[source] = found
        }
        val incoming = LinkedHashMap<Int, Int>()
        db.rawQuery(
            "SELECT target_id, COUNT(*) FROM connections WHERE target_id BETWEEN 1200 AND 2199 GROUP BY target_id",
            null,
        ).use { cursor -> while (cursor.moveToNext()) incoming[cursor.getInt(0)] = cursor.getInt(1) }

        val grownPairs = LinkedHashSet<Pair<String, String>>()
        val grownEdges = HashMap<String, Int>()
        db.rawQuery(
            "SELECT proposal_id, first_concept_id, second_concept_id FROM structural_synapse_registry GROUP BY proposal_id, first_concept_id, second_concept_id ORDER BY proposal_id",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val first = cursor.getString(1)
                val second = cursor.getString(2)
                if (first >= second) throw AndroidNeuronNetworkException("persisted structural concept pair is not canonical")
                grownPairs += first to second
                grownEdges[first] = (grownEdges[first] ?: 0) + 1
                grownEdges[second] = (grownEdges[second] ?: 0) + 1
            }
        }
        return ANS100ActivityDrivenStructuralPlasticity.GraphSnapshot(
            existingTargetsByConceptNeuron = targets,
            targetIncomingEdgeCounts = incoming,
            grownConceptPairs = grownPairs,
            grownEdgesByConcept = grownEdges,
        )
    }

    override fun addStructuralGrowthProposal(
        proposal: ANS100ActivityDrivenStructuralPlasticity.GrowthProposal,
    ): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (proposal.connections.size != 2 ||
                proposal.connections.map { it.sourceNeuronId }.distinct().size != 2 ||
                proposal.connections.map { it.targetNeuronId }.distinct().size != 1 ||
                proposal.connections.any {
                    it.sourceNeuronId !in 1_062 until 1_200 ||
                    it.targetNeuronId !in 1_200 until 2_200 ||
                    !it.connectionId.startsWith("ans100-v5-grown-association-") ||
                    it.weight !in 0.05..0.50
                }
            ) throw AndroidNeuronNetworkException("structural growth proposal is outside approved bounds")

            val firstSlot = db.rawQuery(
                "SELECT neuron_id FROM neural_concept_input_slots WHERE concept_id=?",
                arrayOf(proposal.firstConceptId),
            ).use { if (it.moveToFirst()) it.getInt(0) else null }
            val secondSlot = db.rawQuery(
                "SELECT neuron_id FROM neural_concept_input_slots WHERE concept_id=?",
                arrayOf(proposal.secondConceptId),
            ).use { if (it.moveToFirst()) it.getInt(0) else null }
            if (firstSlot != proposal.firstNeuronId || secondSlot != proposal.secondNeuronId) {
                throw AndroidNeuronNetworkException("structural proposal concept IDs do not match persistent neuron slots")
            }

            val already = db.rawQuery(
                "SELECT COUNT(*) FROM structural_synapse_registry WHERE proposal_id=?",
                arrayOf(proposal.proposalId),
            ).use { it.moveToFirst(); it.getInt(0) }
            if (already > 0) {
                db.setTransactionSuccessful()
                return 0
            }
            if (scalar(db, "SELECT COUNT(DISTINCT proposal_id) FROM structural_synapse_registry") >= MAX_STRUCTURAL_GROWN_PAIRS) {
                throw AndroidNeuronNetworkException("structural grown-pair capacity reached")
            }
            if (scalar(db, "SELECT COUNT(*) FROM connections") + 2 > AndroidSparseNeuronNetwork.MAX_CONNECTIONS) {
                throw AndroidNeuronNetworkException("global connection capacity reached")
            }
            val target = proposal.targetNeuronId
            val incoming = scalar(db, "SELECT COUNT(*) FROM connections WHERE target_id=?", arrayOf(target.toString()))
            if (incoming + 2 > 16) throw AndroidNeuronNetworkException("structural target in-degree capacity reached")
            proposal.connections.forEach { connection ->
                requireNeuron(db, connection.sourceNeuronId)
                requireNeuron(db, connection.targetNeuronId)
                val degree = scalar(
                    db,
                    "SELECT COUNT(*) FROM structural_synapse_registry WHERE connection_id IN (SELECT connection_id FROM connections WHERE source_id=?)",
                    arrayOf(connection.sourceNeuronId.toString()),
                )
                if (degree >= 8) throw AndroidNeuronNetworkException("concept structural degree capacity reached")
                insertConnection(db, connection)
                val values = ContentValues().apply {
                    put("connection_id", connection.connectionId)
                    put("proposal_id", proposal.proposalId)
                    put("first_concept_id", proposal.firstConceptId)
                    put("second_concept_id", proposal.secondConceptId)
                    put("created_at_ms", System.currentTimeMillis())
                    put("last_adjusted_at_ms", System.currentTimeMillis())
                }
                db.insertOrThrow("structural_synapse_registry", null, values)
            }
            db.setTransactionSuccessful()
            return 2
        } finally {
            db.endTransaction()
        }
    }

    override fun adjustStructuralSynapseWeights(feedbackId: String, connectionIds: List<String>, delta: Double, source: ANS100CandidateFeedbackLearner.FeedbackSource): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val proposalIds = connectionIds.mapNotNull { id ->
                db.rawQuery(
                    "SELECT proposal_id FROM structural_synapse_registry WHERE connection_id=?",
                    arrayOf(id),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
            }.toSet()
            var changed = 0
            proposalIds.forEach { proposalId ->
                val pairIds = mutableListOf<String>()
                db.rawQuery(
                    "SELECT connection_id FROM structural_synapse_registry WHERE proposal_id=? ORDER BY connection_id",
                    arrayOf(proposalId),
                ).use { cursor -> while (cursor.moveToNext()) pairIds += cursor.getString(0) }
                if (pairIds.size != 2 || !connectionIds.containsAll(pairIds)) return@forEach
                val feedback = ContentValues().apply {
                    put("feedback_id", feedbackId)
                    put("proposal_id", proposalId)
                    put("delta", delta)
                    put("source", source.name)
                    put("created_at_ms", System.currentTimeMillis())
                }
                if (db.insertWithOnConflict("structural_synapse_feedback_events", null, feedback, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return@forEach
                pairIds.forEach { id ->
                    val current = db.rawQuery(
                        "SELECT weight FROM connections WHERE connection_id=?",
                        arrayOf(id),
                    ).use { if (it.moveToFirst()) it.getDouble(0) else null } ?: return@forEach
                    val update = ContentValues().apply { put("weight", (current + delta).coerceIn(0.05, 1.0)) }
                    if (db.update("connections", update, "connection_id=?", arrayOf(id)) == 1) changed++
                    db.update(
                        "structural_synapse_registry",
                        ContentValues().apply { put("last_adjusted_at_ms", System.currentTimeMillis()) },
                        "connection_id=?",
                        arrayOf(id),
                    )
                }
            }
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }

    override fun removeStructuralSynapses(connectionIds: List<String>, maximumWeight: Double): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val proposalIds = connectionIds.mapNotNull { id ->
                db.rawQuery(
                    "SELECT proposal_id FROM structural_synapse_registry WHERE connection_id=?",
                    arrayOf(id),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
            }.toSet()
            var removed = 0
            proposalIds.forEach { proposalId ->
                val pairIds = mutableListOf<String>()
                db.rawQuery(
                    "SELECT connection_id FROM structural_synapse_registry WHERE proposal_id=? ORDER BY connection_id",
                    arrayOf(proposalId),
                ).use { cursor -> while (cursor.moveToNext()) pairIds += cursor.getString(0) }
                if (pairIds.size != 2 || !connectionIds.containsAll(pairIds)) return@forEach
                val weights = pairIds.mapNotNull { id ->
                    db.rawQuery("SELECT weight FROM connections WHERE connection_id=?", arrayOf(id))
                        .use { if (it.moveToFirst()) it.getDouble(0) else null }
                }
                if (weights.size == 2 && weights.all { it <= maximumWeight }) {
                    pairIds.forEach { id ->
                        db.delete("structural_synapse_registry", "connection_id=?", arrayOf(id))
                        removed += db.delete("connections", "connection_id=?", arrayOf(id))
                    }
                }
            }
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
        }
    }

    override fun structuralAssociationConnections(sourceNeuronIds: List<Int>): List<AndroidNeuronConnection> {
        if (sourceNeuronIds.isEmpty()) return emptyList()
        val result = ArrayList<AndroidNeuronConnection>()
        sourceNeuronIds.forEach { source ->
            readableDatabase.rawQuery(
                "SELECT c.connection_id, c.source_id, c.target_id, c.delay_ns, c.weight FROM connections c INNER JOIN structural_synapse_registry r ON r.connection_id=c.connection_id WHERE c.source_id=? ORDER BY c.connection_id LIMIT 8",
                arrayOf(source.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += AndroidNeuronConnection(
                        connectionId = cursor.getString(0),
                        sourceNeuronId = cursor.getInt(1),
                        targetNeuronId = cursor.getInt(2),
                        delayNs = cursor.getLong(3),
                        weight = cursor.getDouble(4),
                    )
                }
            }
        }
        return result
    }

    override fun structuralSynapseWeights(connectionIds: List<String>): Map<String, Double> {
        val result = LinkedHashMap<String, Double>()
        connectionIds.forEach { id ->
            readableDatabase.rawQuery(
                "SELECT c.weight FROM connections c INNER JOIN structural_synapse_registry r ON r.connection_id=c.connection_id WHERE c.connection_id=?",
                arrayOf(id),
            ).use { if (it.moveToFirst()) result[id] = it.getDouble(0) }
        }
        return result
    }

    override fun addConnection(connection: AndroidNeuronConnection) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (scalar(db, "SELECT COUNT(*) FROM connections") >= AndroidSparseNeuronNetwork.MAX_CONNECTIONS) {
                throw AndroidNeuronNetworkException("100,000-connection capacity reached")
            }
            requireNeuron(db, connection.sourceNeuronId)
            requireNeuron(db, connection.targetNeuronId)
            insertConnection(db, connection)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun enqueue(event: AndroidNeuronEvent) = enqueueAll(listOf(event))

    override fun enqueueAll(events: List<AndroidNeuronEvent>) {
        if (events.isEmpty() || events.size > AndroidSparseNeuronNetwork.MAX_EVENTS_PER_TIMESTAMP) {
            throw AndroidNeuronNetworkException("input batch exceeds the timestamp event bound")
        }
        val ids = HashSet<String>(events.size)
        if (events.any { !ids.add(it.eventId) }) {
            throw AndroidNeuronNetworkException("input batch contains duplicate event IDs")
        }
        if (events.map { it.timeNs }.toSet().size != 1) {
            throw AndroidNeuronNetworkException("input batch contains mixed timestamps")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val currentTime = scalar(db, "SELECT value FROM metadata WHERE key='model_time_ns'")
            if (events.any { it.timeNs <= currentTime }) {
                throw AndroidNeuronNetworkException("event time must be later than the committed clock")
            }
            val pending = scalar(db, "SELECT COUNT(*) FROM events")
            if (pending + events.size > AndroidSparseNeuronNetwork.MAX_PENDING_EVENTS) {
                throw AndroidNeuronBackpressureException("100,000-event queue would be exceeded")
            }
            events.forEach { event ->
                requireNeuron(db, event.targetNeuronId)
                insertEvent(db, event)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun nextTimestampEvents(maxEvents: Int): List<AndroidNeuronEvent>? {
        val db = readableDatabase
        val timestamp = db.rawQuery("SELECT MIN(time_ns) FROM events", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
        } ?: return null
        val result = mutableListOf<AndroidNeuronEvent>()
        db.rawQuery(
            "SELECT event_id, time_ns, target_id, strength, core_tainted FROM events WHERE time_ns=? ORDER BY event_id LIMIT ?",
            arrayOf(timestamp.toString(), (maxEvents + 1).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += readEvent(cursor)
            }
        }
        if (result.size > maxEvents) {
            throw AndroidNeuronBackpressureException("one timestamp exceeds the atomic batch bound")
        }
        return result
    }

    override fun outgoing(neuronId: Int): List<AndroidNeuronConnection> {
        val result = mutableListOf<AndroidNeuronConnection>()
        readableDatabase.rawQuery(
            "SELECT connection_id, source_id, target_id, delay_ns, weight FROM connections WHERE source_id=? ORDER BY connection_id LIMIT ?",
            arrayOf(neuronId.toString(), AndroidSparseNeuronNetwork.MAX_CONNECTIONS.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AndroidNeuronConnection(
                    connectionId = cursor.getString(0),
                    sourceNeuronId = cursor.getInt(1),
                    targetNeuronId = cursor.getInt(2),
                    delayNs = cursor.getLong(3),
                    weight = cursor.getDouble(4),
                )
            }
        }
        return result
    }

    override fun loadNeuron(neuronId: Int): AndroidNeuronState {
        return readableDatabase.rawQuery(
            "SELECT neuron_id, potential, adaptation, last_update_ns, refractory_until_ns, spike_sequence, core_tainted FROM neurons WHERE neuron_id=?",
            arrayOf(neuronId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw AndroidNeuronNetworkException("unknown neuron $neuronId")
            readNeuron(cursor)
        }
    }

    override fun recordDialogueEligibility(
        contextId: String,
        sourceNeuronIds: List<Int>,
        outputNeuronIds: List<Int>,
        preTimeNs: Long,
        postTimeNs: Long,
    ): Int {
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        val eligibility = exp(-(postTimeNs - preTimeNs).toDouble() / DIALOGUE_ELIGIBILITY_TAU_NS)
        db.beginTransaction()
        try {
            db.delete("dialogue_eligibility", "created_at_ms<?", arrayOf((nowMs - MAX_DIALOGUE_ELIGIBILITY_AGE_MS).toString()))
            var inserted = 0
            for (featureId in sourceNeuronIds) {
                for (outputId in outputNeuronIds) {
                    val connectionId = dialogueConnectionId(featureId, outputId)
                    val exists = db.rawQuery(
                        "SELECT 1 FROM connections WHERE connection_id=? AND source_id=? AND target_id=?",
                        arrayOf(connectionId, featureId.toString(), outputId.toString()),
                    ).use { it.moveToFirst() }
                    if (!exists) throw AndroidNeuronNetworkException("dialogue synapse is missing from the initialized topology")
                    val values = ContentValues().apply {
                        put("context_id", contextId)
                        put("connection_id", connectionId)
                        put("feature_id", featureId)
                        put("output_id", outputId)
                        put("eligibility", eligibility)
                        put("pre_time_ns", preTimeNs)
                        put("post_time_ns", postTimeNs)
                        put("created_at_ms", nowMs)
                    }
                    db.insertOrThrow("dialogue_eligibility", null, values)
                    inserted++
                }
            }
            val total = db.rawQuery("SELECT COUNT(*) FROM dialogue_eligibility", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            val excess = total - MAX_DIALOGUE_ELIGIBILITY_ROWS
            if (excess > 0L) {
                db.execSQL(
                    "DELETE FROM dialogue_eligibility WHERE rowid IN (SELECT rowid FROM dialogue_eligibility ORDER BY created_at_ms ASC, rowid ASC LIMIT ?)",
                    arrayOf(excess),
                )
            }
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    override fun applyDialogueReward(
        contextId: String,
        sourceNeuronIds: List<Int>,
        outputRewards: Map<Int, Double>,
    ): AndroidDialogueRewardResult {
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        val featureSet = sourceNeuronIds.toHashSet()
        db.beginTransaction()
        try {
            val expired = db.rawQuery(
                "SELECT COUNT(*) FROM dialogue_eligibility WHERE context_id=? AND created_at_ms<?",
                arrayOf(contextId, (nowMs - MAX_DIALOGUE_ELIGIBILITY_AGE_MS).toString()),
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val rows = mutableListOf<DialogueEligibilityRow>()
            db.rawQuery(
                "SELECT connection_id, feature_id, output_id, eligibility, created_at_ms FROM dialogue_eligibility WHERE context_id=?",
                arrayOf(contextId),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val featureId = cursor.getInt(1)
                    val outputId = cursor.getInt(2)
                    if (featureId in featureSet && outputId in outputRewards) {
                        rows += DialogueEligibilityRow(
                            connectionId = cursor.getString(0),
                            featureId = featureId,
                            outputId = outputId,
                            eligibility = cursor.getDouble(3),
                            createdAtMs = cursor.getLong(4),
                        )
                    }
                }
            }
            var updated = 0
            for (row in rows) {
                if (row.createdAtMs > nowMs) continue
                val ageMs = nowMs - row.createdAtMs
                if (ageMs > MAX_DIALOGUE_ELIGIBILITY_AGE_MS) continue
                val currentWeight = db.rawQuery(
                    "SELECT weight FROM connections WHERE connection_id=?",
                    arrayOf(row.connectionId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) throw AndroidNeuronNetworkException("dialogue synapse disappeared before reward commit")
                    cursor.getDouble(0)
                }
                val delayedEligibility = row.eligibility * exp(-(ageMs * 1_000_000.0) / DIALOGUE_REWARD_TAU_NS)
                val nextWeight = (
                    currentWeight + DIALOGUE_REWARD_RATE * outputRewards.getValue(row.outputId) * delayedEligibility
                ).coerceIn(0.0, 1.0)
                val values = ContentValues().apply { put("weight", nextWeight) }
                if (db.update("connections", values, "connection_id=?", arrayOf(row.connectionId)) != 1) {
                    throw AndroidNeuronNetworkException("dialogue synapse update did not affect exactly one row")
                }
                updated++
            }
            db.delete("dialogue_eligibility", "context_id=?", arrayOf(contextId))
            db.setTransactionSuccessful()
            return AndroidDialogueRewardResult(updatedSynapses = updated, expiredSynapses = expired)
        } finally {
            db.endTransaction()
        }
    }

    override fun resolveConceptInputSlots(conceptIds: List<String>): Map<String, Int> {
        if (conceptIds.isEmpty()) return emptyMap()
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val assigned = LinkedHashMap<String, Int>()
            val occupied = BooleanArray(ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS)
            db.rawQuery("SELECT concept_id, neuron_id FROM neural_concept_input_slots ORDER BY neuron_id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val conceptId = cursor.getString(0)
                    val neuronId = cursor.getInt(1)
                    if (neuronId !in ANS100ConceptMemory.NEURAL_INPUT_START until
                        ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS) {
                        throw AndroidNeuronNetworkException("persisted concept input slot is outside its reserved population")
                    }
                    val slot = neuronId - ANS100ConceptMemory.NEURAL_INPUT_START
                    if (occupied[slot]) throw AndroidNeuronNetworkException("duplicate persisted concept input slot")
                    occupied[slot] = true
                    assigned[conceptId] = neuronId
                }
            }
            var nextSlot = 0
            for (conceptId in conceptIds.sorted()) {
                if (assigned.containsKey(conceptId)) continue
                while (nextSlot < occupied.size && occupied[nextSlot]) nextSlot++
                if (nextSlot >= occupied.size) continue
                val neuronId = ANS100ConceptMemory.NEURAL_INPUT_START + nextSlot
                val values = ContentValues().apply {
                    put("concept_id", conceptId)
                    put("neuron_id", neuronId)
                    put("created_at_ms", nowMs)
                }
                db.insertOrThrow("neural_concept_input_slots", null, values)
                occupied[nextSlot] = true
                assigned[conceptId] = neuronId
                nextSlot++
            }
            db.setTransactionSuccessful()
            return conceptIds.distinct().sorted().mapNotNull { id -> assigned[id]?.let { id to it } }.toMap(LinkedHashMap())
        } finally {
            db.endTransaction()
        }
    }

    override fun recordCanonicalReadoutEligibility(turnId: String, sourceNeuronIds: List<Int>): Int {
        val validated = try {
            ANS100CanonicalReadoutContract.validateSourceNeuronIds(sourceNeuronIds)
        } catch (failure: IllegalArgumentException) {
            throw AndroidNeuronNetworkException("canonical readout source set is invalid: ${failure.message}")
        }
        if (validated.isEmpty()) return 0
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val version = scalar(db, "SELECT value FROM metadata WHERE key='ans100_topology_version'")
            if (version != 5L) throw AndroidNeuronNetworkException("canonical readout requires topology V5")
            val cleanSources = validated.filter { sourceId ->
                scalar(db, "SELECT core_tainted FROM neurons WHERE neuron_id=?", arrayOf(sourceId.toString())) == 0L
            }
            if (cleanSources.isEmpty()) {
                db.setTransactionSuccessful()
                return 0
            }
            val turnAlreadyRated = db.rawQuery(
                "SELECT 1 FROM canonical_readout_feedback_events WHERE turn_id=? LIMIT 1",
                arrayOf(turnId),
            ).use { it.moveToFirst() }
            if (turnAlreadyRated) {
                db.delete("canonical_readout_eligibility", "turn_id=?", arrayOf(turnId))
                db.setTransactionSuccessful()
                return 0
            }
            db.delete(
                "canonical_readout_eligibility",
                "created_at_ms<?",
                arrayOf((nowMs - MAX_DIALOGUE_ELIGIBILITY_AGE_MS).toString()),
            )
            var inserted = 0
            for (sourceNeuronId in cleanSources) {
                requireNeuron(db, sourceNeuronId)
                val values = ContentValues().apply {
                    put("turn_id", turnId)
                    put("source_neuron_id", sourceNeuronId)
                    put("created_at_ms", nowMs)
                }
                if (db.insertWithOnConflict(
                        "canonical_readout_eligibility",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                ) inserted++
            }
            val excess = scalar(db, "SELECT COUNT(*) FROM canonical_readout_eligibility") -
                MAX_CANONICAL_READOUT_ELIGIBILITY_ROWS
            if (excess > 0L) db.execSQL(
                "DELETE FROM canonical_readout_eligibility WHERE rowid IN (SELECT rowid FROM canonical_readout_eligibility ORDER BY created_at_ms ASC, rowid ASC LIMIT ?)",
                arrayOf(excess),
            )
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    override fun applyCanonicalReadoutFeedback(
        feedbackId: String,
        turnId: String,
        answer: ANS100CanonicalReadoutContract.Answer,
        source: ANS100CandidateFeedbackLearner.FeedbackSource,
    ): Int {
        if (source != ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER &&
            source != ANS100CandidateFeedbackLearner.FeedbackSource.VERIFIED_EXTERNAL_OUTCOME &&
            source != ANS100CandidateFeedbackLearner.FeedbackSource.CONTROLLED_BENCHMARK_LABEL
        ) return 0
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val turnAlreadyRated = db.rawQuery(
                "SELECT 1 FROM canonical_readout_feedback_events WHERE turn_id=? LIMIT 1",
                arrayOf(turnId),
            ).use { it.moveToFirst() }
            if (turnAlreadyRated) {
                db.delete("canonical_readout_eligibility", "turn_id=?", arrayOf(turnId))
                db.setTransactionSuccessful()
                return 0
            }
            val sourceIds = mutableListOf<Int>()
            db.rawQuery(
                "SELECT source_neuron_id FROM canonical_readout_eligibility WHERE turn_id=? AND created_at_ms>=? ORDER BY source_neuron_id",
                arrayOf(turnId, (nowMs - MAX_DIALOGUE_ELIGIBILITY_AGE_MS).toString()),
            ).use { cursor -> while (cursor.moveToNext()) sourceIds += cursor.getInt(0) }
            if (sourceIds.isEmpty()) {
                db.delete("canonical_readout_eligibility", "turn_id=?", arrayOf(turnId))
                db.setTransactionSuccessful()
                return 0
            }
            val validated = try {
                ANS100CanonicalReadoutContract.validateSourceNeuronIds(sourceIds)
            } catch (failure: IllegalArgumentException) {
                throw AndroidNeuronNetworkException("persisted canonical readout eligibility is invalid")
            }
            val feedback = ContentValues().apply {
                put("feedback_id", feedbackId)
                put("turn_id", turnId)
                put("answer", answer.wireValue)
                put("source", source.name)
                put("created_at_ms", nowMs)
            }
            if (db.insertWithOnConflict(
                    "canonical_readout_feedback_events",
                    null,
                    feedback,
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) == -1L
            ) {
                db.delete("canonical_readout_eligibility", "turn_id=?", arrayOf(turnId))
                db.setTransactionSuccessful()
                return 0
            }

            val existingReadoutSynapses = scalar(
                db,
                "SELECT COUNT(*) FROM connections WHERE connection_id LIKE 'ans100-v9-canonical-readout-%'",
            )
            val existingSourceIds = mutableSetOf<Int>()
            db.rawQuery(
                "SELECT DISTINCT source_id FROM connections WHERE connection_id LIKE 'ans100-v9-canonical-readout-%'",
                null,
            ).use { cursor -> while (cursor.moveToNext()) existingSourceIds += cursor.getInt(0) }
            val newSourceCount = validated.count { it !in existingSourceIds }
            if (existingReadoutSynapses + newSourceCount.toLong() * 4L >
                ANS100CanonicalReadoutContract.MAX_PERSISTED_SYNAPSES.toLong()
            ) {
                throw AndroidNeuronNetworkException("canonical readout learned-synapse capacity reached")
            }
            if (scalar(db, "SELECT COUNT(*) FROM connections") + newSourceCount.toLong() * 4L >
                AndroidNeuronSparseLimits.MAX_CONNECTIONS.toLong()
            ) {
                throw AndroidNeuronNetworkException("global sparse synapse capacity reached")
            }

            var updated = 0
            val negativeDelta = ANS100CanonicalReadoutContract.MAX_WEIGHT_DELTA_PER_EVENT / 3.0
            for (sourceNeuronId in validated) {
                for (classAnswer in ANS100CanonicalReadoutContract.Answer.values()) {
                    val connectionId = ANS100CanonicalReadoutContract.connectionId(classAnswer, sourceNeuronId)
                    val targetNeuronId = ANS100CanonicalReadoutContract.outputNeuronId(classAnswer, sourceNeuronId)
                    val currentWeight = db.rawQuery(
                        "SELECT weight, source_id, target_id FROM connections WHERE connection_id=?",
                        arrayOf(connectionId),
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) null else {
                            if (cursor.getInt(1) != sourceNeuronId || cursor.getInt(2) != targetNeuronId) {
                                throw AndroidNeuronNetworkException("canonical readout synapse endpoints changed")
                            }
                            cursor.getDouble(0)
                        }
                    }
                    val weight = currentWeight ?: ANS100CanonicalReadoutContract.INITIAL_SYNAPSE_WEIGHT.also {
                        requireNeuron(db, sourceNeuronId)
                        requireNeuron(db, targetNeuronId)
                        insertConnection(
                            db,
                            AndroidNeuronConnection(
                                connectionId = connectionId,
                                sourceNeuronId = sourceNeuronId,
                                targetNeuronId = targetNeuronId,
                                delayNs = ANS100CanonicalReadoutContract.SYNAPSE_DELAY_NS,
                                weight = it,
                            ),
                        )
                    }
                    val delta = if (classAnswer == answer) {
                        ANS100CanonicalReadoutContract.MAX_WEIGHT_DELTA_PER_EVENT
                    } else {
                        -negativeDelta
                    }
                    val nextWeight = (weight + delta).coerceIn(
                        ANS100CanonicalReadoutContract.MIN_SYNAPSE_WEIGHT,
                        ANS100CanonicalReadoutContract.MAX_SYNAPSE_WEIGHT,
                    )
                    if (nextWeight != weight) {
                        if (db.update(
                                "connections",
                                ContentValues().apply { put("weight", nextWeight) },
                                "connection_id=?",
                                arrayOf(connectionId),
                            ) != 1
                        ) throw AndroidNeuronNetworkException("canonical readout synapse update was not atomic")
                        updated++
                    }
                }
            }
            db.delete("canonical_readout_eligibility", "turn_id=?", arrayOf(turnId))
            val excess = scalar(db, "SELECT COUNT(*) FROM canonical_readout_feedback_events") -
                MAX_CANONICAL_READOUT_FEEDBACK_EVENTS
            if (excess > 0L) db.execSQL(
                "DELETE FROM canonical_readout_feedback_events WHERE feedback_id IN (SELECT feedback_id FROM canonical_readout_feedback_events ORDER BY created_at_ms ASC LIMIT ?)",
                arrayOf(excess),
            )
            db.setTransactionSuccessful()
            return updated
        } finally {
            db.endTransaction()
        }
    }

    override fun canonicalReadoutSynapseCount(): Int = scalar(
        readableDatabase,
        "SELECT COUNT(*) FROM connections WHERE connection_id LIKE 'ans100-v9-canonical-readout-%'",
    ).toInt()

    override fun recordNeuralAssociationEligibility(turnId: String, candidateId: String,
        connectionIds: List<String>, eligibility: Double): Int {
        if (connectionIds.size !in 2..ANS100NeuralAssociationFeedbackLearner.MAX_CONNECTIONS_PER_CANDIDATE || connectionIds.size % 2 != 0 ||
            connectionIds.distinct().size != connectionIds.size || !eligibility.isFinite() || eligibility !in 0.0..1.0)
            throw AndroidNeuronNetworkException("neural association eligibility is outside its bounds")
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete("neural_association_eligibility", "created_at_ms<?", arrayOf((nowMs - MAX_DIALOGUE_ELIGIBILITY_AGE_MS).toString()))
            val sourcesByTarget = LinkedHashMap<Int, MutableSet<Int>>()
            for (connectionId in connectionIds) {
                val endpoints = db.rawQuery("SELECT source_id, target_id FROM connections WHERE connection_id=?",
                    arrayOf(connectionId)).use { cursor ->
                    if (!cursor.moveToFirst()) throw AndroidNeuronNetworkException("unknown neural association synapse")
                    cursor.getInt(0) to cursor.getInt(1)
                }
                val isFixedProjection = connectionId.startsWith("ans100-v4-evidence-general-association-")
                val isRegisteredGrownSynapse = connectionId.startsWith("ans100-v5-grown-association-") &&
                    db.rawQuery(
                        "SELECT 1 FROM structural_synapse_registry WHERE connection_id=?",
                        arrayOf(connectionId),
                    ).use { it.moveToFirst() }
                if (endpoints.first !in ANS100ConceptMemory.NEURAL_INPUT_START until
                    ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS ||
                    endpoints.second !in 1_200 until 2_200 ||
                    (!isFixedProjection && !isRegisteredGrownSynapse))
                    throw AndroidNeuronNetworkException("synapse is outside registered concept-association topology")
                if (!sourcesByTarget.getOrPut(endpoints.second) { LinkedHashSet() }.add(endpoints.first)) {
                    throw AndroidNeuronNetworkException("candidate repeats a concept input to the same association neuron")
                }
                val values = ContentValues().apply {
                    put("turn_id", turnId); put("candidate_id", candidateId); put("connection_id", connectionId)
                    put("eligibility", eligibility); put("created_at_ms", nowMs)
                }
                db.insertOrThrow("neural_association_eligibility", null, values)
            }
            if (sourcesByTarget.values.any { it.size != 2 }) {
                throw AndroidNeuronNetworkException("each convergent association target must have exactly two concept inputs")
            }
            val excess = scalar(db, "SELECT COUNT(*) FROM neural_association_eligibility") - MAX_NEURAL_ASSOCIATION_ELIGIBILITY_ROWS
            if (excess > 0L) db.execSQL("DELETE FROM neural_association_eligibility WHERE rowid IN (SELECT rowid FROM neural_association_eligibility ORDER BY created_at_ms ASC, rowid ASC LIMIT ?)", arrayOf(excess))
            db.setTransactionSuccessful()
            return connectionIds.size
        } finally { db.endTransaction() }
    }

    override fun recordNeuralAssociationEvaluation(candidateId: String, observation: ANS100NeuralAssociationAssessment.Observation): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("candidate_id", candidateId); put("task_id", observation.taskId)
            put("independence_key", observation.independenceKey); put("outcome", observation.outcome.name)
            put("evidence_source", observation.source.name); put("created_at_ms", System.currentTimeMillis())
        }
        val inserted = db.insertWithOnConflict("neural_association_task_evaluations", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        val excess = scalar(db, "SELECT COUNT(*) FROM neural_association_task_evaluations") - MAX_NEURAL_ASSOCIATION_EVALUATIONS
        if (excess > 0L) db.execSQL("DELETE FROM neural_association_task_evaluations WHERE rowid IN (SELECT rowid FROM neural_association_task_evaluations ORDER BY created_at_ms ASC, rowid ASC LIMIT ?)", arrayOf(excess))
        return if (inserted == -1L) 0 else 1
    }

    override fun neuralAssociationEvaluations(candidateId: String, limit: Int): List<ANS100NeuralAssociationAssessment.PersistedObservation> {
        val result = ArrayList<ANS100NeuralAssociationAssessment.PersistedObservation>()
        readableDatabase.rawQuery("SELECT task_id, independence_key, outcome, evidence_source, created_at_ms FROM neural_association_task_evaluations WHERE candidate_id=? ORDER BY created_at_ms DESC LIMIT ?", arrayOf(candidateId, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) result += ANS100NeuralAssociationAssessment.PersistedObservation(
                candidateId, ANS100NeuralAssociationAssessment.Observation(
                    taskId = cursor.getString(0), independenceKey = cursor.getString(1),
                    outcome = ANS100NeuralAssociationAssessment.Outcome.valueOf(cursor.getString(2)),
                    source = ANS100NeuralAssociationAssessment.EvidenceSource.valueOf(cursor.getString(3)),
                ), cursor.getLong(4),
            )
        }
        return result
    }

    override fun latestNeuralAssociationFeedback(candidateId: String): ANS100NeuralAssociationAssessment.FeedbackRecord? {
        return readableDatabase.rawQuery("SELECT reward, source, created_at_ms FROM neural_association_feedback_events WHERE candidate_id=? ORDER BY created_at_ms DESC, rowid DESC LIMIT 1", arrayOf(candidateId)).use { cursor ->
            if (!cursor.moveToFirst()) null else ANS100NeuralAssociationAssessment.FeedbackRecord(
                reward = cursor.getDouble(0),
                source = ANS100CandidateFeedbackLearner.FeedbackSource.valueOf(cursor.getString(1)),
                recordedAtMs = cursor.getLong(2),
            )
        }
    }

    override fun neuralAssociationWeightHistory(candidateId: String, limit: Int): List<ANS100NeuralAssociationAssessment.WeightChange> {
        val result = ArrayList<ANS100NeuralAssociationAssessment.WeightChange>()
        readableDatabase.rawQuery("SELECT feedback_id, turn_id, connection_id, previous_weight, next_weight, reward, source, changed_at_ms FROM neural_association_weight_history WHERE candidate_id=? ORDER BY changed_at_ms DESC LIMIT ?", arrayOf(candidateId, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) result += ANS100NeuralAssociationAssessment.WeightChange(
                feedbackId = cursor.getString(0), turnId = cursor.getString(1), candidateId = candidateId,
                connectionId = cursor.getString(2), previousWeight = cursor.getDouble(3), nextWeight = cursor.getDouble(4),
                reward = cursor.getDouble(5), source = cursor.getString(6), changedAtMs = cursor.getLong(7),
            )
        }
        return result
    }

    override fun neuralAssociationWeights(connectionIds: List<String>): Map<String, Double> {
        if (connectionIds.isEmpty() || connectionIds.size > ANS100NeuralAssociationFeedbackLearner.MAX_CONNECTIONS_PER_CANDIDATE ||
            connectionIds.distinct().size != connectionIds.size)
            throw AndroidNeuronNetworkException("neural association weight query is outside its bounds")
        val placeholders = List(connectionIds.size) { "?" }.joinToString(",")
        val result = LinkedHashMap<String, Double>()
        readableDatabase.rawQuery(
            "SELECT c.connection_id, c.weight, c.source_id, c.target_id, " +
                "CASE WHEN r.connection_id IS NULL THEN 0 ELSE 1 END " +
                "FROM connections c LEFT JOIN structural_synapse_registry r " +
                "ON r.connection_id=c.connection_id WHERE c.connection_id IN ($placeholders)",
            connectionIds.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val connectionId = cursor.getString(0)
                val fixedProjection = connectionId.startsWith("ans100-v4-evidence-general-association-")
                val registeredGrownSynapse = connectionId.startsWith("ans100-v5-grown-association-") &&
                    cursor.getInt(4) == 1
                if (cursor.getInt(2) !in ANS100ConceptMemory.NEURAL_INPUT_START until
                    ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS ||
                    cursor.getInt(3) !in 1_200 until 2_200 ||
                    (!fixedProjection && !registeredGrownSynapse))
                    throw AndroidNeuronNetworkException("stored synapse is outside registered concept-association projection")
                result[connectionId] = cursor.getDouble(1)
            }
        }
        return result
    }

    override fun applyNeuralAssociationFeedback(feedbackId: String, turnId: String, candidateId: String,
        reward: Double, source: ANS100CandidateFeedbackLearner.FeedbackSource): Int {
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (source != ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER &&
                source != ANS100CandidateFeedbackLearner.FeedbackSource.VERIFIED_EXTERNAL_OUTCOME) {
                db.delete("neural_association_eligibility", "turn_id=? AND candidate_id=?", arrayOf(turnId, candidateId))
                db.setTransactionSuccessful()
                return 0
            }
            val event = ContentValues().apply {
                put("feedback_id", feedbackId); put("turn_id", turnId); put("candidate_id", candidateId)
                put("reward", reward); put("source", source.name); put("created_at_ms", nowMs)
            }
            if (db.insertWithOnConflict("neural_association_feedback_events", null, event, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                db.setTransactionSuccessful()
                return 0
            }
            val rows = mutableListOf<Pair<String, Double>>()
            db.rawQuery("SELECT connection_id, eligibility FROM neural_association_eligibility WHERE turn_id=? AND candidate_id=? AND created_at_ms>=?",
                arrayOf(turnId, candidateId, (nowMs - MAX_DIALOGUE_ELIGIBILITY_AGE_MS).toString())).use { cursor ->
                while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getDouble(1)
            }
            var updated = 0
            val eligibilityById = rows.toMap()
            val grownProposalIds = rows.mapNotNull { (connectionId, _) ->
                db.rawQuery(
                    "SELECT proposal_id FROM structural_synapse_registry WHERE connection_id=?",
                    arrayOf(connectionId),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
            }.toSet()
            val grownConnectionIds = LinkedHashSet<String>()
            for (proposalId in grownProposalIds) {
                val pairIds = mutableListOf<String>()
                db.rawQuery(
                    "SELECT connection_id FROM structural_synapse_registry WHERE proposal_id=? ORDER BY connection_id",
                    arrayOf(proposalId),
                ).use { cursor -> while (cursor.moveToNext()) pairIds += cursor.getString(0) }
                if (pairIds.size != 2 || !eligibilityById.keys.containsAll(pairIds)) {
                    // This candidate may combine a grown path with an unrelated
                    // fixed path at the same target. Do not train half a grown pair.
                    grownConnectionIds.addAll(pairIds.filter { it in eligibilityById })
                    continue
                }
                val pairEligibility = pairIds.map { eligibilityById.getValue(it) }
                if (kotlin.math.abs(pairEligibility[0] - pairEligibility[1]) > 0.000001) {
                    throw AndroidNeuronNetworkException("grown association pair eligibility is inconsistent")
                }
                val delta = (NEURAL_ASSOCIATION_LEARNING_RATE * reward * pairEligibility.average())
                    .coerceIn(-NEURAL_ASSOCIATION_MAX_DELTA, NEURAL_ASSOCIATION_MAX_DELTA)
                val feedbackValues = ContentValues().apply {
                    put("feedback_id", feedbackId)
                    put("proposal_id", proposalId)
                    put("delta", delta)
                    put("source", source.name)
                    put("created_at_ms", nowMs)
                }
                if (db.insertWithOnConflict(
                        "structural_synapse_feedback_events", null, feedbackValues, SQLiteDatabase.CONFLICT_IGNORE,
                    ) == -1L) {
                    grownConnectionIds.addAll(pairIds)
                    continue
                }
                pairIds.forEach { connectionId ->
                    val current = db.rawQuery(
                        "SELECT weight FROM connections WHERE connection_id=?",
                        arrayOf(connectionId),
                    ).use { if (it.moveToFirst()) it.getDouble(0) else null }
                        ?: throw AndroidNeuronNetworkException("grown synapse disappeared before feedback commit")
                    val next = (current + delta).coerceIn(NEURAL_ASSOCIATION_MIN_WEIGHT, 1.0)
                    if (next != current) {
                        if (db.update("connections", ContentValues().apply { put("weight", next) },
                                "connection_id=?", arrayOf(connectionId)) != 1)
                            throw AndroidNeuronNetworkException("grown pair update did not affect exactly one synapse")
                        db.insertOrThrow("neural_association_weight_history", null, ContentValues().apply {
                            put("feedback_id", feedbackId); put("connection_id", connectionId); put("turn_id", turnId)
                            put("candidate_id", candidateId); put("previous_weight", current); put("next_weight", next)
                            put("reward", reward); put("source", source.name); put("changed_at_ms", nowMs)
                        })
                        updated++
                    }
                    db.update(
                        "structural_synapse_registry",
                        ContentValues().apply { put("last_adjusted_at_ms", nowMs) },
                        "connection_id=?",
                        arrayOf(connectionId),
                    )
                    grownConnectionIds += connectionId
                }
            }
            for ((connectionId, eligibility) in rows) {
                if (connectionId in grownConnectionIds) continue
                val current = db.rawQuery("SELECT weight, source_id, target_id FROM connections WHERE connection_id=?",
                    arrayOf(connectionId)).use { cursor ->
                    if (!cursor.moveToFirst()) throw AndroidNeuronNetworkException("synapse disappeared before feedback commit")
                    if (cursor.getInt(1) !in ANS100ConceptMemory.NEURAL_INPUT_START until
                        ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS ||
                        cursor.getInt(2) !in 1_200 until 2_200 ||
                        !connectionId.startsWith("ans100-v4-evidence-general-association-"))
                        throw AndroidNeuronNetworkException("feedback synapse is outside registered concept-association topology")
                    cursor.getDouble(0)
                }
                val delta = (NEURAL_ASSOCIATION_LEARNING_RATE * reward * eligibility)
                    .coerceIn(-NEURAL_ASSOCIATION_MAX_DELTA, NEURAL_ASSOCIATION_MAX_DELTA)
                val next = (current + delta).coerceIn(NEURAL_ASSOCIATION_MIN_WEIGHT, 1.0)
                if (next != current) {
                    if (db.update("connections", ContentValues().apply { put("weight", next) },
                            "connection_id=?", arrayOf(connectionId)) != 1)
                        throw AndroidNeuronNetworkException("neural association update did not affect exactly one synapse")
                    db.insertOrThrow("neural_association_weight_history", null, ContentValues().apply {
                        put("feedback_id", feedbackId); put("connection_id", connectionId); put("turn_id", turnId)
                        put("candidate_id", candidateId); put("previous_weight", current); put("next_weight", next)
                        put("reward", reward); put("source", source.name); put("changed_at_ms", nowMs)
                    })
                    updated++
                }
            }
            val historyExcess = scalar(db, "SELECT COUNT(*) FROM neural_association_weight_history") - MAX_NEURAL_ASSOCIATION_WEIGHT_HISTORY
            if (historyExcess > 0L) db.execSQL("DELETE FROM neural_association_weight_history WHERE rowid IN (SELECT rowid FROM neural_association_weight_history ORDER BY changed_at_ms ASC, rowid ASC LIMIT ?)", arrayOf(historyExcess))
            db.delete("neural_association_eligibility", "turn_id=? AND candidate_id=?", arrayOf(turnId, candidateId))
            val excess = scalar(db, "SELECT COUNT(*) FROM neural_association_feedback_events") - MAX_NEURAL_ASSOCIATION_FEEDBACK_EVENTS
            if (excess > 0L) db.execSQL("DELETE FROM neural_association_feedback_events WHERE feedback_id IN (SELECT feedback_id FROM neural_association_feedback_events ORDER BY created_at_ms ASC LIMIT ?)", arrayOf(excess))
            db.setTransactionSuccessful()
            return updated
        } finally { db.endTransaction() }
    }

    override fun recordHybridCandidateEligibility(
        turnId: String,
        candidateId: String,
        connectionIds: List<String>,
        eligibility: Double,
    ): Int {
        val slot = ANS100CandidateSlotContract.slotForCandidateId(candidateId)
            ?: throw AndroidNeuronNetworkException("unknown ANS-100 candidate lane")
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete("hybrid_candidate_eligibility", "created_at_ms<?", arrayOf((nowMs - MAX_DIALOGUE_ELIGIBILITY_AGE_MS).toString()))
            for (connectionId in connectionIds) {
                val endpoints = db.rawQuery(
                    "SELECT source_id, target_id FROM connections WHERE connection_id=?",
                    arrayOf(connectionId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) throw AndroidNeuronNetworkException("candidate trace references an unknown synapse")
                    cursor.getInt(0) to cursor.getInt(1)
                }
                if (!belongsToCandidateLane(endpoints.first, slot) || !belongsToCandidateLane(endpoints.second, slot)) {
                    throw AndroidNeuronNetworkException("candidate trace references a synapse outside its isolated lane")
                }
                val values = ContentValues().apply {
                    put("turn_id", turnId)
                    put("candidate_id", candidateId)
                    put("connection_id", connectionId)
                    put("eligibility", eligibility)
                    put("created_at_ms", nowMs)
                }
                db.insertOrThrow("hybrid_candidate_eligibility", null, values)
            }
            val total = scalar(db, "SELECT COUNT(*) FROM hybrid_candidate_eligibility")
            val excess = total - MAX_HYBRID_CANDIDATE_ELIGIBILITY_ROWS
            if (excess > 0L) db.execSQL(
                "DELETE FROM hybrid_candidate_eligibility WHERE rowid IN (SELECT rowid FROM hybrid_candidate_eligibility ORDER BY created_at_ms ASC, rowid ASC LIMIT ?)",
                arrayOf(excess),
            )
            db.setTransactionSuccessful()
            return connectionIds.size
        } finally {
            db.endTransaction()
        }
    }

    override fun applyHybridCandidateFeedback(
        turnId: String,
        candidateId: String,
        reward: Double,
        source: ANS100CandidateFeedbackLearner.FeedbackSource,
    ): Int {
        val db = writableDatabase
        val nowMs = System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (source != ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER &&
                source != ANS100CandidateFeedbackLearner.FeedbackSource.VERIFIED_EXTERNAL_OUTCOME
            ) {
                db.delete("hybrid_candidate_eligibility", "turn_id=? AND candidate_id=?", arrayOf(turnId, candidateId))
                db.setTransactionSuccessful()
                return 0
            }
            val rows = mutableListOf<Pair<String, Double>>()
            db.rawQuery(
                "SELECT connection_id, eligibility FROM hybrid_candidate_eligibility WHERE turn_id=? AND candidate_id=? AND created_at_ms>=?",
                arrayOf(turnId, candidateId, (nowMs - MAX_DIALOGUE_ELIGIBILITY_AGE_MS).toString()),
            ).use { cursor -> while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getDouble(1) }
            var updated = 0
            for ((connectionId, eligibility) in rows) {
                val current = db.rawQuery("SELECT weight FROM connections WHERE connection_id=?", arrayOf(connectionId)).use {
                    if (!it.moveToFirst()) throw AndroidNeuronNetworkException("candidate synapse disappeared before feedback commit")
                    it.getDouble(0)
                }
                val delta = (HYBRID_CANDIDATE_LEARNING_RATE * reward * eligibility)
                    .coerceIn(-HYBRID_CANDIDATE_MAX_DELTA, HYBRID_CANDIDATE_MAX_DELTA)
                val next = (current + delta).coerceIn(HYBRID_CANDIDATE_MIN_WEIGHT, 1.0)
                if (next != current) {
                    val values = ContentValues().apply { put("weight", next) }
                    if (db.update("connections", values, "connection_id=?", arrayOf(connectionId)) != 1) {
                        throw AndroidNeuronNetworkException("candidate synapse update did not affect exactly one row")
                    }
                    updated++
                }
            }
            db.delete("hybrid_candidate_eligibility", "turn_id=? AND candidate_id=?", arrayOf(turnId, candidateId))
            db.setTransactionSuccessful()
            return updated
        } finally {
            db.endTransaction()
        }
    }

    private fun belongsToCandidateLane(neuronId: Int, slot: ANS100CandidateSlotContract.Slot): Boolean {
        val ranges = ANS100CandidateSlotContract.Polarity.values().flatMap { polarity ->
            listOf(
                ANS100CandidateSlotContract.associationLane(slot, polarity),
                ANS100CandidateSlotContract.workingLane(slot, polarity),
                ANS100CandidateSlotContract.candidateLane(slot, polarity),
                ANS100CandidateSlotContract.criticLane(slot, polarity),
                ANS100CandidateSlotContract.readoutLane(slot, polarity),
            )
        }
        return ranges.any { it.contains(neuronId) }
    }

    override fun dialogueSynapseWeight(sourceNeuronId: Int, outputNeuronId: Int): Double {
        val connectionId = dialogueConnectionId(sourceNeuronId, outputNeuronId)
        return readableDatabase.rawQuery(
            "SELECT weight FROM connections WHERE connection_id=?",
            arrayOf(connectionId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw AndroidNeuronNetworkException("dialogue synapse is not initialized")
            cursor.getDouble(0)
        }
    }

    override fun dialogueOutputScores(hiddenNeuronIds: List<Int>): Map<Int, Double> {
        if (hiddenNeuronIds.isEmpty() ||
            hiddenNeuronIds.size > AndroidSparseNeuronNetwork.MAX_DIALOGUE_FEATURES_PER_TURN
        ) throw AndroidNeuronNetworkException("dialogue readout hidden batch is outside its bound")
        val placeholders = List(hiddenNeuronIds.size) { "?" }.joinToString(",")
        val args = hiddenNeuronIds.map { it.toString() }.toTypedArray()
        val scores = (0 until AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_NEURONS).associate { index ->
            AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START + index to 0.0
        }.toMutableMap()
        readableDatabase.rawQuery(
            "SELECT target_id, AVG(weight) FROM connections WHERE source_id IN ($placeholders) GROUP BY target_id",
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val outputId = cursor.getInt(0)
                val score = cursor.getDouble(1)
                if (outputId !in scores || !score.isFinite() || score !in 0.0..1.0) {
                    throw AndroidNeuronNetworkException("dialogue readout contains an invalid output score")
                }
                scores[outputId] = score
            }
        }
        return scores.toMap()
    }

    private data class DialogueEligibilityRow(
        val connectionId: String,
        val featureId: Int,
        val outputId: Int,
        val eligibility: Double,
        val createdAtMs: Long,
    )

    private fun dialogueConnectionId(featureId: Int, outputId: Int): String {
        val outputIndex = outputId - AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START
        return "dialog-feature-$featureId-output-$outputIndex"
    }

    override fun commitTimestamp(
        timestampNs: Long,
        consumed: List<AndroidNeuronEvent>,
        generated: List<AndroidNeuronEvent>,
        changedStates: Collection<AndroidNeuronState>,
    ) {
        if (timestampNs < 0L) throw AndroidNeuronNetworkException("negative timestamp")
        if (generated.size > AndroidSparseNeuronNetwork.MAX_PENDING_EVENTS) {
            throw AndroidNeuronBackpressureException("fan-out exceeds the pending-event bound")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val currentTime = scalar(db, "SELECT value FROM metadata WHERE key='model_time_ns'")
            if (timestampNs <= currentTime) throw AndroidNeuronNetworkException("network clock changed during timestamp processing")
            for (event in consumed) {
                val deleted = db.delete(
                    "events",
                    "event_id=? AND time_ns=?",
                    arrayOf(event.eventId, event.timeNs.toString()),
                )
                if (deleted != 1) throw AndroidNeuronNetworkException("event disappeared before timestamp commit")
            }
            for (state in changedStates) updateNeuron(db, state)
            val remaining = scalar(db, "SELECT COUNT(*) FROM events")
            if (remaining + generated.size > AndroidSparseNeuronNetwork.MAX_PENDING_EVENTS) {
                throw AndroidNeuronBackpressureException("generated events exceed the pending-event bound")
            }
            for (event in generated) insertEvent(db, event)
            val values = ContentValues().apply { put("value", timestampNs) }
            if (db.update("metadata", values, "key='model_time_ns'", null) != 1) {
                throw AndroidNeuronNetworkException("network clock metadata is missing")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun stats(residentCount: Int, hotSetCapacity: Int): AndroidNeuronNetworkStats {
        val db = readableDatabase
        return AndroidNeuronNetworkStats(
            neuronCount = scalar(db, "SELECT COUNT(*) FROM neurons").toInt(),
            connectionCount = scalar(db, "SELECT COUNT(*) FROM connections").toInt(),
            pendingEvents = scalar(db, "SELECT COUNT(*) FROM events").toInt(),
            currentTimeNs = scalar(db, "SELECT value FROM metadata WHERE key='model_time_ns'"),
            residentNeuronCount = residentCount,
            hotSetCapacity = hotSetCapacity,
        )
    }

    override fun close() {
        super.close()
    }

    private fun insertNeuron(db: SQLiteDatabase, id: Int) {
        val values = ContentValues().apply {
            put("neuron_id", id)
            put("potential", 0.0)
            put("adaptation", 0.0)
            put("last_update_ns", 0L)
            put("refractory_until_ns", 0L)
            put("spike_sequence", 0L)
        }
        db.insertOrThrow("neurons", null, values)
    }

    private fun insertConnection(db: SQLiteDatabase, connection: AndroidNeuronConnection) {
        val values = ContentValues().apply {
            put("connection_id", connection.connectionId)
            put("source_id", connection.sourceNeuronId)
            put("target_id", connection.targetNeuronId)
            put("delay_ns", connection.delayNs)
            put("weight", connection.weight)
        }
        db.insertOrThrow("connections", null, values)
    }

    private fun insertEvent(db: SQLiteDatabase, event: AndroidNeuronEvent) {
        val values = ContentValues().apply {
            put("event_id", event.eventId)
            put("time_ns", event.timeNs)
            put("target_id", event.targetNeuronId)
            put("strength", event.strength)
            put("core_tainted", if (event.coreTainted) 1 else 0)
        }
        db.insertOrThrow("events", null, values)
    }

    private fun requireNeuron(db: SQLiteDatabase, neuronId: Int) {
        val exists = db.rawQuery(
            "SELECT 1 FROM neurons WHERE neuron_id=? LIMIT 1",
            arrayOf(neuronId.toString()),
        ).use { it.moveToFirst() }
        if (!exists) throw AndroidNeuronNetworkException("unknown neuron $neuronId")
    }

    private fun updateNeuron(db: SQLiteDatabase, state: AndroidNeuronState) {
        val values = ContentValues().apply {
            put("potential", state.potential)
            put("adaptation", state.adaptation)
            put("last_update_ns", state.lastUpdateNs)
            put("refractory_until_ns", state.refractoryUntilNs)
            put("spike_sequence", state.spikeSequence)
            put("core_tainted", if (state.coreTainted) 1 else 0)
        }
        if (db.update("neurons", values, "neuron_id=?", arrayOf(state.neuronId.toString())) != 1) {
            throw AndroidNeuronNetworkException("neuron disappeared before timestamp commit")
        }
    }

    private fun readEvent(cursor: Cursor) = AndroidNeuronEvent(
        eventId = cursor.getString(0),
        timeNs = cursor.getLong(1),
        targetNeuronId = cursor.getInt(2),
        strength = cursor.getDouble(3),
        coreTainted = cursor.getInt(4) != 0,
    )

    private fun readNeuron(cursor: Cursor) = AndroidNeuronState(
        neuronId = cursor.getInt(0),
        potential = cursor.getDouble(1),
        adaptation = cursor.getDouble(2),
        lastUpdateNs = cursor.getLong(3),
        refractoryUntilNs = cursor.getLong(4),
        spikeSequence = cursor.getLong(5),
        coreTainted = cursor.getInt(6) != 0,
    )

    private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String>): Long =
        db.rawQuery(sql, args).use { cursor -> if (!cursor.moveToFirst()) 0L else cursor.getLong(0) }

    private fun scalar(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { cursor ->
            if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
        }

    companion object {
        private const val DATABASE_VERSION = 11
        private const val MAX_PERSISTED_STRUCTURAL_TURNS = 64
        private const val STRUCTURAL_OBSERVATION_WINDOW_TURNS = 16
        private const val MAX_STRUCTURAL_GROWN_PAIRS = 512
        private const val MAX_NEURAL_ASSOCIATION_ELIGIBILITY_ROWS = 20_000L
        private const val MAX_NEURAL_ASSOCIATION_FEEDBACK_EVENTS = 10_000L
        private const val MAX_NEURAL_ASSOCIATION_EVALUATIONS = 20_000L
        private const val MAX_NEURAL_ASSOCIATION_WEIGHT_HISTORY = 20_000L
        private const val MAX_CANONICAL_READOUT_ELIGIBILITY_ROWS = 20_000L
        private const val MAX_CANONICAL_READOUT_FEEDBACK_EVENTS = 10_000L
        private const val NEURAL_ASSOCIATION_LEARNING_RATE = 0.02
        private const val NEURAL_ASSOCIATION_MAX_DELTA = 0.01
        private const val NEURAL_ASSOCIATION_MIN_WEIGHT = 0.05
        private const val MAX_HYBRID_CANDIDATE_ELIGIBILITY_ROWS = 50_000L
        private const val HYBRID_CANDIDATE_LEARNING_RATE = 0.02
        private const val HYBRID_CANDIDATE_MAX_DELTA = 0.01
        private const val HYBRID_CANDIDATE_MIN_WEIGHT = 0.05
        private const val MAX_DIALOGUE_ELIGIBILITY_ROWS = 50_000L
        private const val MAX_DIALOGUE_ELIGIBILITY_AGE_MS = 5 * 60 * 1_000L
        private const val DIALOGUE_ELIGIBILITY_TAU_NS = 20_000_000.0
        private const val DIALOGUE_REWARD_TAU_NS = 30_000_000_000.0
        private const val DIALOGUE_REWARD_RATE = 0.15
    }
}
