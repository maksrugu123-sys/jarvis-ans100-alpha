package org.jarvis.ans100.alpha.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable, bounded pre-action snapshot binding one selected ANS decision to
 * the exact 250k JNVM input, local graph and outcome readout used by replay.
 *
 * The snapshot is detached from SQLite and is persisted in the episode journal
 * before the input batch is scheduled. It is not a world-state snapshot and
 * does not prove that a neural readout equals a real external action outcome.
 */
data class AndroidJnvM250kDecisionEpisodeContext(
    val decisionTurnId: String,
    val selectedCandidateId: String,
    val initialStates: Map<Int, AndroidNeuronState>,
    val connections: List<AndroidNeuronConnection>,
    val externalInputs: List<AndroidNeuronEvent>,
    val outcomeNeuronIds: Set<Int>,
) {
    init {
        require(decisionTurnId.isNotBlank() && decisionTurnId.length <= 128)
        require(selectedCandidateId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$")))
        require(initialStates.isNotEmpty() && initialStates.size <= MAX_NEURONS)
        require(initialStates.all { (id, state) -> id == state.neuronId })
        initialStates.keys.forEach(AndroidJnvM250kProfile::requireNeuronId)
        require(connections.isNotEmpty() && connections.size <= MAX_CONNECTIONS)
        require(connections.map { it.connectionId }.distinct().size == connections.size)
        require(connections.all {
            it.sourceNeuronId in initialStates && it.targetNeuronId in initialStates
        }) { "every captured edge endpoint must have a pre-action neuron state" }
        require(externalInputs.isNotEmpty() && externalInputs.size <= MAX_INPUTS)
        require(externalInputs.map { it.eventId }.distinct().size == externalInputs.size)
        require(externalInputs.all { it.targetNeuronId in initialStates })
        require(externalInputs.all {
            it.timeNs > initialStates.getValue(it.targetNeuronId).lastUpdateNs
        })
        require(outcomeNeuronIds.isNotEmpty() && outcomeNeuronIds.size <= MAX_OUTCOME_NEURONS)
        outcomeNeuronIds.forEach(AndroidJnvM250kProfile::requireNeuronId)
        require(outcomeNeuronIds.all { it in initialStates })
    }

    fun validateAgainst(decision: ANS100Decision) {
        require(decision.status == ANS100DecisionStatus.SELECTED) {
            "counterfactual learning requires an actually selected decision"
        }
        require(decision.selectedCandidateId == selectedCandidateId)
        require(decision.turnId == decisionTurnId) {
            "decision and pre-action snapshot must belong to the same turn"
        }
    }

    internal fun toJson(): JSONObject {
        val states = JSONArray()
        initialStates.toSortedMap().values.forEach { state ->
            states.put(JSONObject().apply {
                put("id", state.neuronId)
                put("potential", state.potential)
                put("adaptation", state.adaptation)
                put("last_update_ns", state.lastUpdateNs)
                put("refractory_until_ns", state.refractoryUntilNs)
                put("spike_sequence", state.spikeSequence)
                put("core_tainted", state.coreTainted)
            })
        }
        val edges = JSONArray()
        connections.sortedBy { it.connectionId }.forEach { edge ->
            edges.put(JSONObject().apply {
                put("id", edge.connectionId)
                put("source", edge.sourceNeuronId)
                put("target", edge.targetNeuronId)
                put("delay_ns", edge.delayNs)
                put("weight", edge.weight)
            })
        }
        val inputs = JSONArray()
        externalInputs.sortedWith(compareBy<AndroidNeuronEvent> { it.timeNs }.thenBy { it.eventId })
            .forEach { event ->
                inputs.put(JSONObject().apply {
                    put("id", event.eventId)
                    put("time_ns", event.timeNs)
                    put("target", event.targetNeuronId)
                    put("strength", event.strength)
                    put("core_tainted", event.coreTainted)
                })
            }
        val outcomes = JSONArray().also { array ->
            outcomeNeuronIds.sorted().forEach { array.put(it) }
        }
        return JSONObject().apply {
            put("version", SNAPSHOT_VERSION)
            put("decision_turn_id", decisionTurnId)
            put("selected_candidate_id", selectedCandidateId)
            put("states", states)
            put("connections", edges)
            put("external_inputs", inputs)
            put("outcome_neurons", outcomes)
        }.also {
            require(it.toString().length <= MAX_JSON_CHARS) {
                "pre-action counterfactual snapshot exceeds its durable bound"
            }
        }
    }

    companion object {
        const val MAX_NEURONS = 512
        const val MAX_CONNECTIONS = AndroidJnvM250kProfile.MAX_PAGE_SIZE
        const val MAX_INPUTS = AndroidJnvM250kProfile.MAX_EVENTS_PER_ENQUEUE
        const val MAX_OUTCOME_NEURONS = 128
        const val MAX_JSON_CHARS = AndroidJnvM250kEpisodeJournal.MAX_PAYLOAD_CHARS / 2
        private const val SNAPSHOT_VERSION = 1

        internal fun fromJson(json: JSONObject): AndroidJnvM250kDecisionEpisodeContext {
            require(json.getInt("version") == SNAPSHOT_VERSION)
            val statesArray = json.getJSONArray("states")
            require(statesArray.length() in 1..MAX_NEURONS)
            val states = LinkedHashMap<Int, AndroidNeuronState>()
            for (index in 0 until statesArray.length()) {
                val item = statesArray.getJSONObject(index)
                val state = AndroidNeuronState(
                    neuronId = item.getInt("id"),
                    potential = item.getDouble("potential"),
                    adaptation = item.getDouble("adaptation"),
                    lastUpdateNs = item.getLong("last_update_ns"),
                    refractoryUntilNs = item.getLong("refractory_until_ns"),
                    spikeSequence = item.getLong("spike_sequence"),
                    coreTainted = item.getBoolean("core_tainted"),
                )
                require(states.put(state.neuronId, state) == null)
            }
            val edgesArray = json.getJSONArray("connections")
            require(edgesArray.length() in 1..MAX_CONNECTIONS)
            val edges = ArrayList<AndroidNeuronConnection>(edgesArray.length())
            for (index in 0 until edgesArray.length()) {
                val item = edgesArray.getJSONObject(index)
                edges += AndroidNeuronConnection(
                    connectionId = item.getString("id"),
                    sourceNeuronId = item.getInt("source"),
                    targetNeuronId = item.getInt("target"),
                    delayNs = item.getLong("delay_ns"),
                    weight = item.getDouble("weight"),
                )
            }
            val inputsArray = json.getJSONArray("external_inputs")
            require(inputsArray.length() in 1..MAX_INPUTS)
            val inputs = ArrayList<AndroidNeuronEvent>(inputsArray.length())
            for (index in 0 until inputsArray.length()) {
                val item = inputsArray.getJSONObject(index)
                inputs += AndroidNeuronEvent(
                    eventId = item.getString("id"),
                    timeNs = item.getLong("time_ns"),
                    targetNeuronId = item.getInt("target"),
                    strength = item.getDouble("strength"),
                    coreTainted = item.getBoolean("core_tainted"),
                )
            }
            val outcomesArray = json.getJSONArray("outcome_neurons")
            require(outcomesArray.length() in 1..MAX_OUTCOME_NEURONS)
            val outcomes = LinkedHashSet<Int>()
            for (index in 0 until outcomesArray.length()) {
                require(outcomes.add(outcomesArray.getInt(index)))
            }
            return AndroidJnvM250kDecisionEpisodeContext(
                decisionTurnId = json.getString("decision_turn_id"),
                selectedCandidateId = json.getString("selected_candidate_id"),
                initialStates = states,
                connections = edges,
                externalInputs = inputs,
                outcomeNeuronIds = outcomes,
            )
        }
    }
}
