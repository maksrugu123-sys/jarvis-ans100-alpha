package org.jarvis.ans100.alpha.data

import android.content.Context
import java.io.Closeable
import kotlin.math.exp

/** One spike emitted by the disk-backed 250k runtime. */
data class AndroidJnvM250kSpike(
    val neuronId: Int,
    val spikeSequence: Long,
    val timestampNs: Long,
    val coreTainted: Boolean = false,
)

/**
 * One sparse synapse transmission emitted by an actual source-neuron spike.
 * This records transmission, not proof that the target's later behavior was
 * caused exclusively by this edge.
 */
data class AndroidJnvM250kCausalLink(
    val connectionId: String,
    val sourceNeuronId: Int,
    val targetNeuronId: Int,
    val timestampNs: Long,
    /** Scheduled arrival time at the target, including synaptic delay. */
    val arrivalTimestampNs: Long = timestampNs,
) {
    init {
        require(connectionId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
        AndroidJnvM250kProfile.requireNeuronId(sourceNeuronId)
        AndroidJnvM250kProfile.requireNeuronId(targetNeuronId)
        require(sourceNeuronId != targetNeuronId)
        require(timestampNs >= 0L)
        require(arrivalTimestampNs >= timestampNs)
    }
}

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
    /** Direct synapse transmissions observed in this run, capped at 256. */
    val causalLinks: List<AndroidJnvM250kCausalLink> = emptyList(),
    /** Transmissions omitted after the per-run causal-link cap was reached. */
    val omittedCausalLinks: Int = 0,
    /** Target spikes that also received non-synaptic/external input in the same timestamp group. */
    val ambiguousTargetSpikeNeuronIds: Set<Int> = emptySet(),
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
 * traces. Reward-modulated STDP applies bounded, idempotent scalar rewards to
 * eligibility traces on an explicitly credited local neuron set.
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
     * Captures a detached pre-action graph for one selected ANS decision.
     *
     * This must run before scheduleInputs/run. It rejects a non-empty event
     * queue, stale inputs, excessive graph reachability and snapshots that
     * cannot be represented completely within the replay bounds.
     */
    @Synchronized
    fun captureDecisionEpisodeContext(
        decision: ANS100Decision,
        externalInputs: List<AndroidNeuronEvent>,
        outcomeNeuronIds: Set<Int>,
        maxGraphDepth: Int = DEFAULT_COUNTERFACTUAL_GRAPH_DEPTH,
    ): AndroidJnvM250kDecisionEpisodeContext {
        require(decision.status == ANS100DecisionStatus.SELECTED && decision.selectedCandidateId != null) {
            "counterfactual episode requires a selected ANS decision"
        }
        require(maxGraphDepth in 1..MAX_COUNTERFACTUAL_GRAPH_DEPTH)
        require(externalInputs.isNotEmpty() &&
            externalInputs.size <= AndroidJnvM250kDecisionEpisodeContext.MAX_INPUTS)
        require(externalInputs.map { it.eventId }.distinct().size == externalInputs.size)
        require(outcomeNeuronIds.isNotEmpty() &&
            outcomeNeuronIds.size <= AndroidJnvM250kDecisionEpisodeContext.MAX_OUTCOME_NEURONS)
        outcomeNeuronIds.forEach(AndroidJnvM250kProfile::requireNeuronId)

        val before = store.stats()
        check(before.pendingEvents == 0) {
            "counterfactual snapshot requires an empty persisted event queue"
        }
        require(externalInputs.all { it.timeNs > before.currentTimeNs }) {
            "decision inputs must be later than the committed JNVM model clock"
        }

        val edges = LinkedHashMap<String, AndroidNeuronConnection>()
        val visitedSources = LinkedHashSet<Int>()
        var frontier = externalInputs.mapTo(sortedSetOf<Int>()) { it.targetNeuronId }
        var depth = 0
        while (frontier.isNotEmpty()) {
            val batch = frontier.filterNot { it in visitedSources }.toSortedSet()
            if (batch.isEmpty()) break
            if (depth == maxGraphDepth || edges.size == AndroidJnvM250kDecisionEpisodeContext.MAX_CONNECTIONS) {
                // Probe the boundary so a terminal frontier is accepted, but
                // never omit a reachable edge beyond the configured depth.
                check(store.outgoingConnectionsFor(batch, maxEdges = 1).isEmpty()) {
                    "counterfactual graph depth exceeds the configured complete-snapshot bound"
                }
                visitedSources += batch
                frontier = sortedSetOf()
                break
            }
            val remaining = AndroidJnvM250kDecisionEpisodeContext.MAX_CONNECTIONS - edges.size
            check(remaining > 0) {
                "reachable counterfactual graph exceeds the bounded edge snapshot"
            }
            val page = store.outgoingConnectionsFor(batch, remaining)
            val next = sortedSetOf<Int>()
            for (edge in page) {
                check(edges.put(edge.connectionId, edge) == null) {
                    "counterfactual graph contains duplicate connection IDs"
                }
                next += edge.targetNeuronId
            }
            visitedSources += batch
            frontier = next.filterNot { it in visitedSources }.toSortedSet()
            depth++
        }
        check(frontier.isEmpty()) {
            "counterfactual graph depth exceeds the configured complete-snapshot bound"
        }
        check(edges.isNotEmpty()) { "selected decision has no reachable synapses to evaluate" }

        val stateIds = LinkedHashSet<Int>()
        stateIds += externalInputs.map { it.targetNeuronId }
        edges.values.forEach { edge ->
            stateIds += edge.sourceNeuronId
            stateIds += edge.targetNeuronId
        }
        stateIds += outcomeNeuronIds
        check(stateIds.size <= AndroidJnvM250kDecisionEpisodeContext.MAX_NEURONS) {
            "reachable counterfactual graph exceeds the bounded neuron snapshot"
        }
        val states = store.loadNeuronStates(stateIds)
        return AndroidJnvM250kDecisionEpisodeContext(
            decisionTurnId = decision.turnId,
            selectedCandidateId = decision.selectedCandidateId,
            initialStates = states,
            connections = edges.values.toList(),
            externalInputs = externalInputs.toList(),
            outcomeNeuronIds = outcomeNeuronIds.toSet(),
        ).also { it.validateAgainst(decision) }
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
        val causalLinks = ArrayList<AndroidJnvM250kCausalLink>(MAX_CAUSAL_LINKS_PER_RUN)
        var omittedCausalLinks = 0
        val ambiguousTargetSpikeNeuronIds = LinkedHashSet<Int>()

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
            val timestampSpikes = ArrayList<AndroidJnvM250kSpike>()
            for (index in results.indices) {
                val input = kernelInputs[index]
                val result = results[index]
                AndroidNeuronKernel.validateCandidate(input, result)
                val neuronId = candidateIds[index]
                val updated = result.state.copy(coreTainted = taintByTarget.getValue(neuronId))
                changed += updated
                if (result.spiked) {
                    val spike = AndroidJnvM250kSpike(neuronId, updated.spikeSequence, timestamp, updated.coreTainted)
                    spikes += spike
                    timestampSpikes += spike
                    if (byTarget.getValue(neuronId).any { !it.eventId.matches(GENERATED_SYNAPSE_EVENT_ID) }) {
                        ambiguousTargetSpikeNeuronIds += neuronId
                    }
                }
            }

            // Decay old traces before adding current spikes: simultaneous pre/post
            // spikes have no artificial ordering.
            val changedSynapses = calculateStdpUpdates(timestamp, timestampSpikes)
            val learnedWeights = changedSynapses.associate { it.connectionId to it.weight }
            val changedByNeuron = changed.associateBy { it.neuronId }
            val generated = ArrayList<AndroidNeuronEvent>()
            for (spike in timestampSpikes) {
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
                    if (causalLinks.size < MAX_CAUSAL_LINKS_PER_RUN) {
                        causalLinks += AndroidJnvM250kCausalLink(
                            connectionId = connection.connectionId,
                            sourceNeuronId = spike.neuronId,
                            targetNeuronId = connection.targetNeuronId,
                            timestampNs = timestamp,
                            arrivalTimestampNs = timestamp + connection.delayNs,
                        )
                    } else {
                        omittedCausalLinks++
                    }
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
            causalLinks = causalLinks,
            omittedCausalLinks = omittedCausalLinks,
            ambiguousTargetSpikeNeuronIds = ambiguousTargetSpikeNeuronIds,
        )
    }

    /**
     * Applies delayed scalar feedback to synapses adjacent to the credited
     * neurons. Call after the relevant action/episode has reached its result.
     * Reusing rewardId is safe: the SQLite receipt prevents double learning.
     */
    @Synchronized
    fun applyReward(
        rewardId: String,
        reward: Double,
        rewardedNeuronIds: Set<Int>,
    ): AndroidJnvM250kRewardResult = store.applyReward(
        rewardId = rewardId,
        reward = reward,
        rewardedNeuronIds = rewardedNeuronIds,
        timestampNs = store.stats().currentTimeNs,
        traceTauNs = STDP_TRACE_TAU_NS,
        learningRate = RSTDP_LEARNING_RATE,
    )

    /**
     * Applies delayed feedback only to the exact synapses that transmitted in
     * the completed episode. No neighboring edge is selected implicitly.
     */
    @Synchronized
    fun applyRewardToConnections(
        rewardId: String,
        reward: Double,
        connectionIds: Set<String>,
        connectionCredits: Map<String, Double> = emptyMap(),
    ): AndroidJnvM250kRewardResult = store.applyRewardToConnections(
        rewardId = rewardId,
        reward = reward,
        connectionIds = connectionIds,
        connectionCredits = connectionCredits,
        timestampNs = store.stats().currentTimeNs,
        traceTauNs = STDP_TRACE_TAU_NS,
        learningRate = RSTDP_LEARNING_RATE,
    )

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
            val decayedEligibility = (snapshot.eligibility * decay).coerceIn(-AndroidJnvM250kStore.MAX_RSTDP_ELIGIBILITY, AndroidJnvM250kStore.MAX_RSTDP_ELIGIBILITY)
            val sourceSpiked = snapshot.connection.sourceNeuronId in spikingIds
            val targetSpiked = snapshot.connection.targetNeuronId in spikingIds
            // Post-after-pre potentiates; pre-after-post depresses.
            // Both terms use traces from before this timestamp's spike increments.
            val potentiation = if (targetSpiked) STDP_A_PLUS * decayedPre else 0.0
            val depression = if (sourceSpiked) STDP_A_MINUS * decayedPost else 0.0
            val localDelta = potentiation - depression
            val nextWeight = (snapshot.connection.weight + localDelta).coerceIn(0.0, 1.0)
            val nextEligibility = (decayedEligibility + localDelta).coerceIn(-AndroidJnvM250kStore.MAX_RSTDP_ELIGIBILITY, AndroidJnvM250kStore.MAX_RSTDP_ELIGIBILITY)
            val nextPre = (decayedPre + if (sourceSpiked) 1.0 else 0.0)
                .coerceAtMost(AndroidJnvM250kStore.MAX_STDP_TRACE)
            val nextPost = (decayedPost + if (targetSpiked) 1.0 else 0.0)
                .coerceAtMost(AndroidJnvM250kStore.MAX_STDP_TRACE)
            AndroidJnvM250kSynapseUpdate(
                connectionId = snapshot.connection.connectionId,
                weight = nextWeight,
                preTrace = nextPre,
                postTrace = nextPost,
                eligibility = nextEligibility,
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
        const val MAX_CAUSAL_LINKS_PER_RUN = 256
        const val DEFAULT_COUNTERFACTUAL_GRAPH_DEPTH = 16
        const val MAX_COUNTERFACTUAL_GRAPH_DEPTH = 16

        /** Initial pair-based STDP parameters; research defaults, not tuned values. */
        const val STDP_TRACE_TAU_NS = 20_000_000.0
        const val STDP_A_PLUS = 0.01
        const val STDP_A_MINUS = 0.012
        /** Reward gain applied to the bounded, decaying STDP eligibility trace. */
        const val RSTDP_LEARNING_RATE = 0.1
        private val GENERATED_SYNAPSE_EVENT_ID = Regex("^j250k-spike-[0-9]+-[0-9]+-.+$")
    }
}
