package org.jarvis.ans100.alpha.data

/**
 * Bounded, side-effect-free counterfactual replay for a small captured JNVM subgraph.
 *
 * The evaluator receives detached neuron/edge/input snapshots. It never opens or
 * mutates the production SQLite store, working set, weights, or reward receipts.
 * It replays the same initial states and external inputs once with all edges and
 * once per candidate with exactly that edge suppressed.
 *
 * A changed outcome is evidence of sensitivity in this replay model, not proof
 * that the edge caused a real-world outcome. The replay omits online STDP,
 * plasticity, external environment feedback and any state not supplied here.
 */
object AndroidJnvM250kCounterfactualEvaluator {
    data class Limits(
        val maxCandidateConnections: Int = DEFAULT_MAX_CANDIDATES,
        val maxEventsPerReplay: Int = DEFAULT_MAX_EVENTS,
        val maxTimestampsPerReplay: Int = DEFAULT_MAX_TIMESTAMPS,
    ) {
        init {
            require(maxCandidateConnections in 1..MAX_CANDIDATES_HARD_LIMIT)
            require(maxEventsPerReplay in 1..MAX_EVENTS_HARD_LIMIT)
            require(maxTimestampsPerReplay in 1..MAX_TIMESTAMPS_HARD_LIMIT)
        }
    }

    data class CandidateEffect(
        val connectionId: String,
        val baselineOutcomeSpikes: Int,
        val counterfactualOutcomeSpikes: Int,
        /** Positive means the outcome population spiked more in baseline. */
        val outcomeSpikeDelta: Int,
        /** Normalized absolute difference; 0 means no observed change. */
        val sensitivityScore: Double,
        val baselineComplete: Boolean,
        val counterfactualComplete: Boolean,
    )

    data class Report(
        val candidates: List<CandidateEffect>,
        val baselineProcessedEvents: Int,
        val baselineProcessedTimestamps: Int,
        val baselineComplete: Boolean,
        val limitation: String = LIMITATION,
    )

    private data class Replay(
        val outcomeSpikes: Int,
        val processedEvents: Int,
        val processedTimestamps: Int,
        val complete: Boolean,
    )

    /** Compare one bounded baseline with up to eight one-edge-disabled replays. */
    fun evaluate(
        initialStates: Map<Int, AndroidNeuronState>,
        connections: List<AndroidNeuronConnection>,
        externalInputs: List<AndroidNeuronEvent>,
        candidateConnectionIds: Set<String>,
        outcomeNeuronIds: Set<Int>,
        limits: Limits = Limits(),
    ): Report {
        require(initialStates.isNotEmpty()) { "initial neuron snapshot must not be empty" }
        require(connections.size <= AndroidJnvM250kProfile.MAX_PAGE_SIZE) {
            "counterfactual edge snapshot exceeds one bounded page"
        }
        require(externalInputs.isNotEmpty() && externalInputs.size <= limits.maxEventsPerReplay) {
            "external input snapshot must contain 1..maxEventsPerReplay events"
        }
        require(candidateConnectionIds.isNotEmpty() &&
            candidateConnectionIds.size <= limits.maxCandidateConnections) {
            "candidate set exceeds the configured counterfactual bound"
        }
        require(outcomeNeuronIds.isNotEmpty()) { "outcome neuron set must not be empty" }
        require(candidateConnectionIds.all { it.matches(CONNECTION_ID) }) {
            "candidate connection ID is invalid"
        }
        require(externalInputs.map { it.eventId }.toSet().size == externalInputs.size) {
            "external input event IDs must be unique"
        }
        require(initialStates.all { (id, state) -> id == state.neuronId }) {
            "initial state map keys must match neuron IDs"
        }
        initialStates.keys.forEach(AndroidJnvM250kProfile::requireNeuronId)
        outcomeNeuronIds.forEach(AndroidJnvM250kProfile::requireNeuronId)
        require(outcomeNeuronIds.all { it in initialStates }) {
            "every outcome neuron must have an initial state"
        }
        require(externalInputs.all { it.targetNeuronId in initialStates }) {
            "every external input target must have an initial state"
        }
        require(connections.map { it.connectionId }.toSet().size == connections.size) {
            "edge snapshot contains duplicate connection IDs"
        }
        require(connections.all {
            it.sourceNeuronId in initialStates && it.targetNeuronId in initialStates
        }) { "every edge endpoint must have an initial state" }
        require(candidateConnectionIds.all { id -> connections.any { it.connectionId == id } }) {
            "every candidate must exist in the supplied edge snapshot"
        }
        require(externalInputs.all { it.timeNs > initialStates.getValue(it.targetNeuronId).lastUpdateNs }) {
            "external inputs must occur after their target snapshot timestamps"
        }

        val orderedEdges = connections.sortedBy { it.connectionId }
        val baseline = replay(initialStates, orderedEdges, externalInputs, emptySet(), outcomeNeuronIds, limits)
        val effects = candidateConnectionIds.sorted().map { candidateId ->
            val counterfactual = replay(
                initialStates, orderedEdges, externalInputs, setOf(candidateId), outcomeNeuronIds, limits,
            )
            val delta = baseline.outcomeSpikes - counterfactual.outcomeSpikes
            val denominator = maxOf(1, baseline.outcomeSpikes, counterfactual.outcomeSpikes)
            CandidateEffect(
                connectionId = candidateId,
                baselineOutcomeSpikes = baseline.outcomeSpikes,
                counterfactualOutcomeSpikes = counterfactual.outcomeSpikes,
                outcomeSpikeDelta = delta,
                sensitivityScore = (kotlin.math.abs(delta).toDouble() / denominator).coerceIn(0.0, 1.0),
                baselineComplete = baseline.complete,
                counterfactualComplete = counterfactual.complete,
            )
        }
        return Report(
            candidates = effects,
            baselineProcessedEvents = baseline.processedEvents,
            baselineProcessedTimestamps = baseline.processedTimestamps,
            baselineComplete = baseline.complete,
        )
    }

    private fun replay(
        initialStates: Map<Int, AndroidNeuronState>,
        connections: List<AndroidNeuronConnection>,
        externalInputs: List<AndroidNeuronEvent>,
        suppressedConnectionIds: Set<String>,
        outcomeNeuronIds: Set<Int>,
        limits: Limits,
    ): Replay {
        val states = initialStates.toMutableMap()
        val outgoing = connections.filterNot { it.connectionId in suppressedConnectionIds }
            .groupBy { it.sourceNeuronId }
        val queue = java.util.PriorityQueue<AndroidNeuronEvent>(
            compareBy<AndroidNeuronEvent> { it.timeNs }.thenBy { it.eventId },
        )
        externalInputs.forEach(queue::add)
        var processed = 0
        var timestamps = 0
        var outcomeSpikes = 0
        var generatedSequence = 0L
        var generatedEvents = 0
        var generationTruncated = false

        while (queue.isNotEmpty() && timestamps < limits.maxTimestampsPerReplay) {
            val time = queue.peek().timeNs
            val group = ArrayList<AndroidNeuronEvent>()
            while (queue.isNotEmpty() && queue.peek().timeNs == time) group += queue.remove()
            if (processed + group.size > limits.maxEventsPerReplay) {
                group.forEach(queue::add)
                break
            }
            val byTarget = group.groupBy { it.targetNeuronId }.toSortedMap()
            val spiked = ArrayList<Int>()
            for ((targetId, events) in byTarget) {
                val previous = states[targetId]
                    ?: throw AndroidNeuronNetworkException("counterfactual replay lacks target state")
                val input = AndroidNeuronKernel.Input(previous, time, events.sumOf { it.strength })
                val result = AndroidNeuronKernel.process(previous, time, input.input)
                AndroidNeuronKernel.validateCandidate(input, result)
                states[targetId] = result.state.copy(coreTainted = previous.coreTainted || events.any { it.coreTainted })
                if (result.spiked) spiked += targetId
            }
            val generatedForTimestamp = ArrayList<AndroidNeuronEvent>()
            for (sourceId in spiked) {
                if (sourceId in outcomeNeuronIds) outcomeSpikes++
                val source = states.getValue(sourceId)
                val polarity = if (
                    AndroidJnvM250kTopology.polarityFor(sourceId) ==
                    AndroidJnvM250kTopology.Polarity.INHIBITORY
                ) -1.0 else 1.0
                for (edge in outgoing[sourceId].orEmpty()) {
                    require(time <= Long.MAX_VALUE - edge.delayNs) {
                        "counterfactual event timestamp overflow"
                    }
                    generatedSequence++
                    generatedForTimestamp += AndroidNeuronEvent(
                        eventId = "cf-$generatedSequence-${edge.connectionId}",
                        timeNs = time + edge.delayNs,
                        targetNeuronId = edge.targetNeuronId,
                        strength = edge.weight * polarity,
                        coreTainted = source.coreTainted,
                    )
                }
            }
            // Bound total replay work, not just consumed input events. Never
            // enqueue a partial timestamp's generated fan-out.
            if (processed + generatedEvents + generatedForTimestamp.size > limits.maxEventsPerReplay) {
                generationTruncated = true
                break
            }
            queue.addAll(generatedForTimestamp)
            generatedEvents += generatedForTimestamp.size
            processed += group.size
            timestamps++
        }
        return Replay(
            outcomeSpikes = outcomeSpikes,
            processedEvents = processed,
            processedTimestamps = timestamps,
            complete = queue.isEmpty() && !generationTruncated,
        )
    }

    const val DEFAULT_MAX_CANDIDATES = 8
    const val DEFAULT_MAX_EVENTS = 512
    const val DEFAULT_MAX_TIMESTAMPS = 64
    const val MAX_CANDIDATES_HARD_LIMIT = 8
    const val MAX_EVENTS_HARD_LIMIT = 2_048
    const val MAX_TIMESTAMPS_HARD_LIMIT = 256

    private val CONNECTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private const val LIMITATION =
        "Deterministic LIF-DT replay only; no live DB mutation, STDP, environment feedback, or proof of real-world causality."
}
