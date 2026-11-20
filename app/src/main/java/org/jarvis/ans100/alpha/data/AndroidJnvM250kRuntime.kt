package org.jarvis.ans100.alpha.data

import android.content.Context
import java.io.Closeable
import kotlin.math.exp

/** One spike emitted by the disk-backed 250k runtime. */
data class AndroidJnvM250kSpike(
    val neuronId: Int,
    val spikeSequence: Long,
    val timestampNs: Long,
)

/** Bounded result for one or more committed timestamp groups. */
data class AndroidJnvM250kRunResult(
    val processedEvents: Int,
    val processedNeuronBatches: Int,
    val emittedSpikes: List<AndroidJnvM250kSpike>,
    val pendingEvents: Int,
    val budgetExhausted: Boolean,
    val currentTimeNs: Long,
    val residentNeuronCount: Int,
    val hotSetCapacity: Int,
    val computeBackend: String,
)

/**
 * First executable event loop for the independent 250k JNVM profile.
 *
 * SQLite remains authoritative; only event-target neuron states enter the
 * device-budgeted LRU working set. A timestamp is computed as one detached
 * candidate batch and is published only after the store atomically commits
 * consumed events, changed neuron states, generated events and model time.
 *
 * Sparse STDP is applied at committed timestamps using persisted pre/post
 * traces. Reward-modulated STDP (R-STDP) and structural edge growth remain
 * separate future integrations.
 */
class AndroidJnvM250kRuntime(
    context: Context,
    private val store: AndroidJnvM250kStore,
    private val computeBackend: AndroidNeuronComputeBackend = CpuAndroidNeuronComputeBackend,
) : Closeable {
    private val workingSet = AndroidJnvM250kWorkingSet(context.applicationContext, store)

    /** Schedule a bounded external input batch strictly after the committed model clock. */
    @Synchronized
    fun scheduleInputs(events: List<AndroidNeuronEvent>) {
        require(events.isNotEmpty() && events.size <= AndroidJnvM250kProfile.MAX_EVENTS_PER_ENQUEUE) {
            "input batch must contain 1..${AndroidJnvM250kProfile.MAX_EVENTS_PER_ENQUEUE} events"
        }
        require(events.map { it.eventId }.toSet().size == events.size) {
            "input event IDs must be unique within a batch"
        }
        val clock = store.stats().currentTimeNs
        if (events.any { it.timeNs <= clock }) {
            throw AndroidNeuronNetworkException("250k input events must be later than the committed model clock")
        }
        store.enqueueEvents(events)
    }

    /**
     * Processes complete timestamp groups only. If the next group would exceed
     * maxEvents, it remains untouched in SQLite for the next run call.
     */
    @Synchronized
    fun run(
        maxEvents: Int = DEFAULT_MAX_EVENTS_PER_RUN,
        maxTimestamps: Int = DEFAULT_MAX_TIMESTAMPS_PER_RUN,
    ): AndroidJnvM250kRunResult {
        require(maxEvents in 1..MAX_EVENTS_PER_RUN)
        require(maxTimestamps in 1..MAX_TIMESTAMPS_PER_RUN)

        workingSet.refreshRamBudget()
        var processedEvents = 0
        var processedBatches = 0
        var timestamps = 0
        var exhausted = false
        var clock = store.stats().currentTimeNs
        val spikes = ArrayList<AndroidJnvM250kSpike>()

        while (timestamps < maxTimestamps) {
            val group = store.nextTimestampEvents() ?: break
            if (processedEvents + group.size > maxEvents) {
                exhausted = true
                break
            }

            val timestamp = group.first().timeNs
            if (timestamp <= clock) {
                throw AndroidNeuronNetworkException("pending 250k event is not later than the committed model clock")
            }
            if (group.any { it.timeNs != timestamp }) {
                throw AndroidNeuronNetworkException("250k store returned a mixed timestamp group")
            }

            val byTarget = group.groupBy { it.targetNeuronId }.toSortedMap()
            val candidateIds = ArrayList<Int>(byTarget.size)
            val kernelInputs = ArrayList<AndroidNeuronKernel.Input>(byTarget.size)
            val taintByTarget = HashMap<Int, Boolean>(byTarget.size)

            // Read only active targets through the RAM-bounded working set.
            for ((targetId, targetEvents) in byTarget) {
                val previous = workingSet.state(targetId)
                candidateIds += targetId
                kernelInputs += AndroidNeuronKernel.Input(
                    state = previous,
                    timestampNs = timestamp,
                    input = targetEvents.sumOf { it.strength },
                )
                taintByTarget[targetId] = previous.coreTainted || targetEvents.any { it.coreTainted }
            }

            val results = computeBackend.processBatch(kernelInputs)
            if (results.size != kernelInputs.size) {
                throw AndroidNeuronNetworkException(
                    "compute backend ${computeBackend.backendName} returned an incomplete 250k candidate batch",
                )
            }

            val changed = ArrayList<AndroidNeuronState>(results.size)
            for (index in results.indices) {
                val input = kernelInputs[index]
                val result = results[index]
                AndroidNeuronKernel.validateCandidate(input, result)
                val neuronId = candidateIds[index]
                val updated = result.state.copy(coreTainted = taintByTarget.getValue(neuronId))
                changed += updated
                if (result.spiked) {
                    spikes += AndroidJnvM250kSpike(neuronId, updated.spikeSequence, timestamp)
                }
            }

            // Decay old traces before adding current spikes: simultaneous pre/post
            // spikes have no artificial ordering.
            val changedSynapses = calculateStdpUpdates(timestamp, spikes)
            val learnedWeights = changedSynapses.associate { it.connectionId to it.weight }
            val changedByNeuron = changed.associateBy { it.neuronId }
            val generated = ArrayList<AndroidNeuronEvent>()
            for (spike in spikes) {
                val updated = changedByNeuron[spike.neuronId]
                    ?: throw AndroidNeuronNetworkException("250k spike has no committed neuron candidate")
                val outgoing = store.outgoingPage(
                    sourceNeuronId = spike.neuronId,
                    limit = AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON,
                )
                val signedPolarity = if (
                    AndroidJnvM250kTopology.polarityFor(spike.neuronId) ==
                    AndroidJnvM250kTopology.Polarity.INHIBITORY
                ) -1.0 else 1.0

                for (connection in outgoing) {
                    if (timestamp > Long.MAX_VALUE - connection.delayNs) {
                        throw AndroidNeuronNetworkException("250k propagated model timestamp overflows signed 64-bit time")
                    }
                    generated += AndroidNeuronEvent(
                        eventId = "j250k-spike-${spike.neuronId}-${spike.spikeSequence}-${connection.connectionId}",
                        timeNs = timestamp + connection.delayNs,
                        targetNeuronId = connection.targetNeuronId,
                        strength = (learnedWeights[connection.connectionId] ?: connection.weight) * signedPolarity,
                        coreTainted = updated.coreTainted,
                    )
                }
            }

            if (generated.size > AndroidJnvM250kProfile.MAX_GENERATED_EVENTS_PER_TIMESTAMP) {
                throw AndroidNeuronBackpressureException("250k generated-event batch exceeds its atomic processing bound")
            }

            // Neuron states, learned weights/traces, propagated events and clock
            // become visible together at the SQLite transaction boundary.
            workingSet.commitTimestamp(timestamp, group, generated, changed, changedSynapses)
            processedEvents += group.size
            processedBatches += byTarget.size
            clock = timestamp
            timestamps++
        }

        val stats = store.stats()
        val currentHotSetCapacity = workingSet.capacity()
        val currentResidentCount = workingSet.residentCount()
        return AndroidJnvM250kRunResult(
            processedEvents = processedEvents,
            processedNeuronBatches = processedBatches,
            emittedSpikes = spikes,
            pendingEvents = stats.pendingEvents,
            budgetExhausted = exhausted,
            currentTimeNs = stats.currentTimeNs,
            residentNeuronCount = currentResidentCount,
            hotSetCapacity = currentHotSetCapacity,
            computeBackend = computeBackend.backendName,
        )
    }

    private fun calculateStdpUpdates(
        timestampNs: Long,
        spikes: List<AndroidJnvM250kSpike>,
    ): List<AndroidJnvM250kSynapseUpdate> {
        if (spikes.isEmpty()) return emptyList()
        val spikingIds = spikes.map { it.neuronId }.toSet()
        val snapshots = store.loadPlasticitySynapses(spikingIds, spikingIds)
        return snapshots.map { snapshot ->
            if (snapshot.lastUpdateNs > timestampNs) {
                throw AndroidNeuronNetworkException("250k STDP trace is newer than the model timestamp")
            }
            val elapsedNs = timestampNs - snapshot.lastUpdateNs
            val decay = exp(-elapsedNs.toDouble() / STDP_TRACE_TAU_NS)
            val decayedPre = (snapshot.preTrace * decay).coerceIn(0.0, AndroidJnvM250kStore.MAX_STDP_TRACE)
            val decayedPost = (snapshot.postTrace * decay).coerceIn(0.0, AndroidJnvM250kStore.MAX_STDP_TRACE)
            val sourceSpiked = snapshot.connection.sourceNeuronId in spikingIds
            val targetSpiked = snapshot.connection.targetNeuronId in spikingIds
            // Post-after-pre potentiates; pre-after-post depresses.
            // Both terms use traces from before this timestamp's spike increments.
            val potentiation = if (targetSpiked) STDP_A_PLUS * decayedPre else 0.0
            val depression = if (sourceSpiked) STDP_A_MINUS * decayedPost else 0.0
            val nextWeight = (snapshot.connection.weight + potentiation - depression).coerceIn(0.0, 1.0)
            val nextPre = (decayedPre + if (sourceSpiked) 1.0 else 0.0)
                .coerceAtMost(AndroidJnvM250kStore.MAX_STDP_TRACE)
            val nextPost = (decayedPost + if (targetSpiked) 1.0 else 0.0)
                .coerceAtMost(AndroidJnvM250kStore.MAX_STDP_TRACE)
            AndroidJnvM250kSynapseUpdate(
                connectionId = snapshot.connection.connectionId,
                weight = nextWeight,
                preTrace = nextPre,
                postTrace = nextPost,
                lastUpdateNs = timestampNs,
            )
        }
    }
    @Synchronized
    fun refreshRamBudget(): Int = workingSet.refreshRamBudget()

    @Synchronized
    override fun close() {
        workingSet.close()
        // Store ownership remains with the caller.
    }

    companion object {
        const val DEFAULT_MAX_EVENTS_PER_RUN = 512
        const val MAX_EVENTS_PER_RUN = 8_192
        const val DEFAULT_MAX_TIMESTAMPS_PER_RUN = 32
        const val MAX_TIMESTAMPS_PER_RUN = 1_024

        /** Initial pair-based STDP parameters; research defaults, not tuned values. */
        const val STDP_TRACE_TAU_NS = 20_000_000.0
        const val STDP_A_PLUS = 0.01
        const val STDP_A_MINUS = 0.012
    }
}
