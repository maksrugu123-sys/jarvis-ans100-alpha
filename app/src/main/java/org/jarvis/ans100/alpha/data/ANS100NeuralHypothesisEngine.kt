package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * A bounded, network-output-driven hypothesis lifecycle.
 *
 * Hypotheses originate only from shared association targets that actually spiked.
 * A later independent turn can reproduce or fail to reproduce that activation.
 * This verifies repeatability of a neural association signal, NOT factual truth,
 * semantic correctness, causality, or a general relation between concepts.
 */
class ANS100NeuralHypothesisEngine(
    private val maxHypotheses: Int = DEFAULT_MAX_HYPOTHESES,
) {
    enum class Status {
        GENERATED,
        REPRODUCED_ON_INDEPENDENT_TURN,
        NOT_REPRODUCED_ON_INDEPENDENT_TURN,
    }

    data class Hypothesis(
        val hypothesisId: String,
        val firstConceptId: String,
        val firstLabel: String,
        val secondConceptId: String,
        val secondLabel: String,
        val status: Status,
        val observationCount: Int,
        val verificationTurnCount: Int,
        val supportingTurnIds: List<String>,
        val nonReproducingTurnIds: List<String>,
        val sharedTargetCount: Int,
    )

    private data class State(
        val hypothesisId: String,
        val firstConceptId: String,
        val firstLabel: String,
        val secondConceptId: String,
        val secondLabel: String,
        val supportingTurns: LinkedHashSet<String> = LinkedHashSet(),
        val nonReproducingTurns: LinkedHashSet<String> = LinkedHashSet(),
        var sharedTargetCount: Int = 0,
    )

    private val states = LinkedHashMap<String, State>()
    private val observedTurns = LinkedHashSet<String>()

    init {
        require(maxHypotheses in 1..MAX_HYPOTHESES_HARD_LIMIT)
    }

    @Synchronized
    fun observeTurn(
        turnId: String,
        activeConceptIds: Set<String>,
        candidates: List<ANS100NeuralAssociationDecoder.Candidate>,
        completed: Boolean,
    ): List<Hypothesis> {
        require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")))
        require(activeConceptIds.size <= ANS100ConceptNeuralInputAdapter.MAX_CONCEPTS_PER_TURN)
        require(activeConceptIds.all { it.isNotBlank() && it.length <= 128 })
        require(candidates.size <= ANS100NeuralAssociationDecoder.MAX_CANDIDATES)
        require(candidates.all {
            it.firstConceptId in activeConceptIds && it.secondConceptId in activeConceptIds
        })
        if (!completed || !observedTurns.add(turnId)) return emptyList()
        while (observedTurns.size > MAX_REMEMBERED_TURNS) {
            observedTurns.remove(observedTurns.first())
        }

        val byPair = candidates.associateBy { pairKey(it.firstConceptId, it.secondConceptId) }
        val activePairs = activeConceptIds.sorted().let { ids ->
            buildSet {
                for (left in ids.indices) {
                    for (right in left + 1 until ids.size) add(pairKey(ids[left], ids[right]))
                }
            }
        }

        byPair.forEach { (key, candidate) ->
            val state = states[key] ?: State(
                hypothesisId = "ans100-hypothesis:${digest(key)}",
                firstConceptId = candidate.firstConceptId,
                firstLabel = candidate.firstLabel,
                secondConceptId = candidate.secondConceptId,
                secondLabel = candidate.secondLabel,
            ).also { states[key] = it }
            state.supportingTurns.add(turnId)
            state.sharedTargetCount = maxOf(state.sharedTargetCount, candidate.convergentNeuronIds.size)
        }

        // A verification opportunity exists only when both concepts were active
        // in a later completed turn. Absence of convergence is recorded separately
        // and never converted into a factual contradiction.
        states.forEach { (key, state) ->
            if (key !in byPair && key in activePairs &&
                turnId !in state.supportingTurns && turnId !in state.nonReproducingTurns
            ) {
                state.nonReproducingTurns.add(turnId)
            }
        }

        while (states.size > maxHypotheses) {
            states.remove(states.entries.first().key)
        }
        return states.values.map(::snapshot)
    }

    @Synchronized
    fun snapshot(): List<Hypothesis> = states.values.map(::snapshot)

    private fun snapshot(state: State): Hypothesis {
        val status = when {
            state.supportingTurns.size >= 2 -> Status.REPRODUCED_ON_INDEPENDENT_TURN
            state.nonReproducingTurns.isNotEmpty() -> Status.NOT_REPRODUCED_ON_INDEPENDENT_TURN
            else -> Status.GENERATED
        }
        return Hypothesis(
            hypothesisId = state.hypothesisId,
            firstConceptId = state.firstConceptId,
            firstLabel = state.firstLabel,
            secondConceptId = state.secondConceptId,
            secondLabel = state.secondLabel,
            status = status,
            observationCount = state.supportingTurns.size,
            verificationTurnCount = state.supportingTurns.size + state.nonReproducingTurns.size - 1,
            supportingTurnIds = state.supportingTurns.toList(),
            nonReproducingTurnIds = state.nonReproducingTurns.toList(),
            sharedTargetCount = state.sharedTargetCount,
        )
    }

    private fun pairKey(first: String, second: String): String =
        listOf(first, second).sorted().joinToString("|")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_MAX_HYPOTHESES = 128
        const val MAX_HYPOTHESES_HARD_LIMIT = 256
        private const val MAX_REMEMBERED_TURNS = 512
    }
}
