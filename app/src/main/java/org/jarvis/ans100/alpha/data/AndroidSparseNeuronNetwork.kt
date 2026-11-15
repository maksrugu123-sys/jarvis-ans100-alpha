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

data class AndroidNeuronRunResult(
    val processedEvents: Int,
    val processedNeuronBatches: Int,
    val emittedSpikes: List<Pair<Int, Long>>,
    val pendingEvents: Int,
    val budgetExhausted: Boolean,
    val currentTimeNs: Long,
)

open class AndroidNeuronNetworkException(message: String) : IllegalStateException(message)
class AndroidNeuronBackpressureException(message: String) : AndroidNeuronNetworkException(message)

/**
 * Authoritative SQLite-backed JNVM store. The database transaction is the
 * publication boundary for a complete timestamp: consumed queue records,
 * changed neuron states, generated events and the model clock commit together.
 */
internal interface AndroidNeuronStateStore : Closeable {
    fun initializeSparseChain(neuronCount: Int, delayNs: Long, weight: Double)
    fun initializeSparseDialogueNetwork(neuronCount: Int, featureNeuronCount: Int, delayNs: Long, weight: Double, inputToHiddenWeight: Double): Boolean
    fun registerNeuron(neuronId: Int)
    fun addConnection(connection: AndroidNeuronConnection)
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
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val hotSetCapacity: Int = DEFAULT_HOT_SET_CAPACITY,
) : Closeable {
    private val store: AndroidNeuronStateStore
    private val hot = LinkedHashMap<Int, AndroidNeuronState>(hotSetCapacity, 0.75f, true)

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
     * Initializes a bounded feed-forward dialogue topology: feature neurons
     * fan out to four output neurons, which have no outgoing edges. Unlike the
     * generic chain fixture, a chat turn cannot cascade through all 10k nodes.
     */
    @Synchronized
    fun initializeSparseDialogueNetwork(
        neuronCount: Int = MAX_NEURONS,
        featureNeuronCount: Int = DIALOGUE_INPUT_NEURONS,
        delayNs: Long = DEFAULT_CONNECTION_DELAY_NS,
        weight: Double = DEFAULT_CONNECTION_WEIGHT,
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
            val changed = LinkedHashMap<Int, AndroidNeuronState>()
            val generated = mutableListOf<AndroidNeuronEvent>()

            // Build detached candidates. Nothing in the resident cache changes
            // until the store has atomically committed the complete timestamp.
            for ((targetId, targetEvents) in byTarget.toSortedMap()) {
                val previous = stateFor(targetId)
                val result = processNeuron(previous, timestamp, targetEvents.sumOf { it.strength })
                changed[targetId] = result.first
                if (result.second) {
                    val spikeSequence = result.first.spikeSequence
                    spikes += targetId to spikeSequence
                    for (connection in store.outgoing(targetId)) {
                        if (timestamp > Long.MAX_VALUE - connection.delayNs) {
                            throw AndroidNeuronNetworkException("propagated model timestamp overflows signed 64-bit time")
                        }
                        generated += AndroidNeuronEvent(
                            eventId = "spike-$targetId-$spikeSequence-${connection.connectionId}",
                            timeNs = timestamp + connection.delayNs,
                            targetNeuronId = connection.targetNeuronId,
                            strength = connection.weight,
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
        return AndroidNeuronRunResult(
            processedEvents = processedEvents,
            processedNeuronBatches = processedBatches,
            emittedSpikes = spikes,
            pendingEvents = finalStats.pendingEvents,
            budgetExhausted = exhausted,
            currentTimeNs = finalStats.currentTimeNs,
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
        const val MAX_DIALOGUE_FEATURES_PER_TURN = 128
        const val MAX_DIALOGUE_PAIR_DELAY_NS = 10_000_000L
        const val DEFAULT_HOT_SET_CAPACITY = 128
        const val MAX_EVENTS_PER_TIMESTAMP = 10_000
        const val MAX_EVENTS_PER_RUN = 100_000
        const val MAX_TIMESTAMPS_PER_RUN = 10_000
        const val DEFAULT_MAX_EVENTS_PER_RUN = 4_096
        const val DEFAULT_MAX_TIMESTAMPS_PER_RUN = 128
        const val DEFAULT_DATABASE_NAME = "jarvis_android_dialogue_jnvm_v2.db"
        const val DEFAULT_CONNECTION_DELAY_NS = 1_000_000L
        const val DEFAULT_CONNECTION_WEIGHT = 0.15
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

private class AndroidNeuronSqliteStore(
    context: Context,
    name: String,
) : SQLiteOpenHelper(context, name, null, DATABASE_VERSION), AndroidNeuronStateStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE neurons (neuron_id INTEGER PRIMARY KEY, potential REAL NOT NULL, adaptation REAL NOT NULL, last_update_ns INTEGER NOT NULL, refractory_until_ns INTEGER NOT NULL, spike_sequence INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE connections (connection_id TEXT PRIMARY KEY, source_id INTEGER NOT NULL, target_id INTEGER NOT NULL, delay_ns INTEGER NOT NULL, weight REAL NOT NULL)",
        )
        db.execSQL("CREATE INDEX connections_source_idx ON connections(source_id)")
        createDialogueEligibilityTable(db)
        db.execSQL(
            "CREATE TABLE events (event_id TEXT PRIMARY KEY, time_ns INTEGER NOT NULL, target_id INTEGER NOT NULL, strength REAL NOT NULL)",
        )
        db.execSQL("CREATE INDEX events_time_idx ON events(time_ns, event_id)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO metadata(key, value) VALUES('model_time_ns', 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDialogueEligibilityTable(db)
        if (newVersion > DATABASE_VERSION) {
            throw AndroidNeuronNetworkException("Unsupported Android JNVM schema migration: $oldVersion -> $newVersion")
        }
    }

    private fun createDialogueEligibilityTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS dialogue_eligibility (context_id TEXT NOT NULL, connection_id TEXT NOT NULL, feature_id INTEGER NOT NULL, output_id INTEGER NOT NULL, eligibility REAL NOT NULL, pre_time_ns INTEGER NOT NULL, post_time_ns INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(context_id, connection_id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS dialogue_eligibility_age_idx ON dialogue_eligibility(created_at_ms)")
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
            if (topologyVersion > AndroidSparseNeuronNetwork.DIALOGUE_TOPOLOGY_VERSION) {
                throw AndroidNeuronNetworkException("dialogue topology version is newer than this application")
            }
            if (topologyVersion == AndroidSparseNeuronNetwork.DIALOGUE_TOPOLOGY_VERSION) {
                val actualConnections = scalar(db, "SELECT COUNT(*) FROM connections")
                if (actualConnections != connectionCount) {
                    throw AndroidNeuronNetworkException("versioned dialogue topology has an unexpected connection count")
                }
                db.setTransactionSuccessful()
                return false
            }

            // Upgrade the former feature-to-output graph in place. Keep the
            // old weights attached to the 996 nodes now assigned as hidden units.
            val preservedWeights = HashMap<Pair<Int, Int>, Double>()
            if (existingNeurons > 0L) {
                db.rawQuery(
                    "SELECT source_id, target_id, weight FROM connections WHERE source_id>=? AND source_id<? AND target_id>=?",
                    arrayOf(
                        AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START.toString(),
                        outputStart.toString(),
                        outputStart.toString(),
                    ),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        preservedWeights[cursor.getInt(0) to cursor.getInt(1)] = cursor.getDouble(2)
                    }
                }
                db.delete("connections", null, null)
                db.delete("events", null, null)
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
                            weight = preservedWeights[hiddenId to outputId] ?: weight,
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
            "SELECT event_id, time_ns, target_id, strength FROM events WHERE time_ns=? ORDER BY event_id LIMIT ?",
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
            "SELECT neuron_id, potential, adaptation, last_update_ns, refractory_until_ns, spike_sequence FROM neurons WHERE neuron_id=?",
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

    override fun dialogueSynapseWeight(featureNeuronId: Int, outputNeuronId: Int): Double {
        val connectionId = dialogueConnectionId(featureNeuronId, outputNeuronId)
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
    )

    private fun readNeuron(cursor: Cursor) = AndroidNeuronState(
        neuronId = cursor.getInt(0),
        potential = cursor.getDouble(1),
        adaptation = cursor.getDouble(2),
        lastUpdateNs = cursor.getLong(3),
        refractoryUntilNs = cursor.getLong(4),
        spikeSequence = cursor.getLong(5),
    )

    private fun scalar(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { cursor ->
            if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
        }

    companion object {
        private const val DATABASE_VERSION = 2
        private const val MAX_DIALOGUE_ELIGIBILITY_ROWS = 50_000L
        private const val MAX_DIALOGUE_ELIGIBILITY_AGE_MS = 5 * 60 * 1_000L
        private const val DIALOGUE_ELIGIBILITY_TAU_NS = 20_000_000.0
        private const val DIALOGUE_REWARD_TAU_NS = 30_000_000_000.0
        private const val DIALOGUE_REWARD_RATE = 0.15
    }
}
