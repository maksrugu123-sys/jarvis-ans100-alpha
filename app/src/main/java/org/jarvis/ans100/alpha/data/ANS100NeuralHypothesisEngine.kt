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
        /** Both convergence and non-convergence were observed on complete turns. */
        MIXED_REPRODUCTION_SIGNAL,
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
        val heuristicEvidence: Boolean = false,
    )

    data class PersistedHypothesis(
        val hypothesisId: String,
        val firstConceptId: String,
        val firstLabel: String,
        val secondConceptId: String,
        val secondLabel: String,
        val supportingTurnIds: List<String>,
        val nonReproducingTurnIds: List<String>,
        val sharedTargetCount: Int,
        val heuristicEvidence: Boolean = false,
    )

    data class Snapshot(
        val hypotheses: List<PersistedHypothesis>,
        val observedTurnIds: List<String>,
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
        var heuristicEvidence: Boolean = false,
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
        pairScanComplete: Boolean = true,
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
        val sortedActiveIds = activeConceptIds.sorted()
        val activePairs = LinkedHashSet<String>()
        for (left in sortedActiveIds.indices) {
            for (right in left + 1 until sortedActiveIds.size) {
                activePairs.add(pairKey(sortedActiveIds[left], sortedActiveIds[right]))
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
            trimOldest(state.supportingTurns, MAX_OBSERVATIONS_PER_HYPOTHESIS)
            state.sharedTargetCount = maxOf(state.sharedTargetCount, candidate.convergentNeuronIds.size)
        }

        // A verification opportunity exists only when both concepts were active
        // in a later completed turn. Absence of convergence is recorded separately
        // and never converted into a factual contradiction.
        states.forEach { (key, state) ->
            if (pairScanComplete && key !in byPair && key in activePairs &&
                turnId !in state.supportingTurns && turnId !in state.nonReproducingTurns
            ) {
                state.nonReproducingTurns.add(turnId)
                trimOldest(state.nonReproducingTurns, MAX_OBSERVATIONS_PER_HYPOTHESIS)
            }
        }

        while (states.size > maxHypotheses) {
            states.remove(states.entries.first().key)
        }
        return states.values.map(::snapshot)
    }

    /**
     * 250k-specific ingestion path. It shares the durable cross-turn lifecycle
     * with the legacy decoder but accepts only candidates emitted by the
     * independent JNVM spike/transmission readout.
     */
    @Synchronized
    fun observe250kTurn(
        turnId: String,
        activeConceptIds: Set<String>,
        candidates: List<AndroidJnvM250kHypothesisReadout.Candidate>,
        completed: Boolean,
        pairScanComplete: Boolean,
    ): List<Hypothesis> {
        require(turnId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")))
        require(activeConceptIds.size <= TypedEvidenceBundle.MAX_RECORDS_PER_TURN)
        require(activeConceptIds.all { it.isNotBlank() && it.length <= 96 })
        require(candidates.size <= AndroidJnvM250kHypothesisReadout.MAX_CANDIDATES_HARD_LIMIT)
        require(candidates.all {
            it.firstConceptId in activeConceptIds && it.secondConceptId in activeConceptIds
        })
        require(candidates.map { pairKey(it.firstConceptId, it.secondConceptId) }.distinct().size == candidates.size) {
            "250k readout must provide at most one candidate per evidence-slot pair"
        }
        if (!completed || !observedTurns.add(turnId)) return emptyList()
        while (observedTurns.size > MAX_REMEMBERED_TURNS) {
            observedTurns.remove(observedTurns.first())
        }

        val byPair = candidates.associateBy { pairKey(it.firstConceptId, it.secondConceptId) }
        val sortedActiveIds = activeConceptIds.sorted()
        val activePairs = LinkedHashSet<String>()
        for (left in sortedActiveIds.indices) {
            for (right in left + 1 until sortedActiveIds.size) {
                activePairs.add(pairKey(sortedActiveIds[left], sortedActiveIds[right]))
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
            trimOldest(state.supportingTurns, MAX_OBSERVATIONS_PER_HYPOTHESIS)
            state.sharedTargetCount = maxOf(state.sharedTargetCount, candidate.convergentNeuronIds.size)
            state.heuristicEvidence = state.heuristicEvidence || candidate.heuristicEvidence
        }

        if (pairScanComplete) {
            states.forEach { (key, state) ->
                if (key !in byPair && key in activePairs &&
                    turnId !in state.supportingTurns && turnId !in state.nonReproducingTurns
                ) {
                    state.nonReproducingTurns.add(turnId)
                    trimOldest(state.nonReproducingTurns, MAX_OBSERVATIONS_PER_HYPOTHESIS)
                }
            }
        }

        while (states.size > maxHypotheses) {
            states.remove(states.entries.first().key)
        }
        return states.values.map(::snapshot)
    }

    data class Prediction(
        val sourceConceptId: String,
        val predictedConceptId: String,
        val predictedLabel: String,
        val supportingTurnCount: Int,
        val verificationTurnCount: Int,
        val confidence: Double,
        val heuristicEvidence: Boolean,
    )

    /**
     * Predicts a likely co-occurring lexical feature from independently
     * repeated 250k associations. This is a bounded feature prediction, not
     * next-word generation or proof of semantic relation.
     */
    @Synchronized
    fun predict250k(
        activeConceptIds: Set<String>,
        limit: Int = DEFAULT_MAX_PREDICTIONS,
        minimumIndependentSupports: Int = MIN_PREDICTION_SUPPORTS,
        minimumConfidence: Double = MIN_PREDICTION_CONFIDENCE,
    ): List<Prediction> {
        require(activeConceptIds.size <= TypedEvidenceBundle.MAX_RECORDS_PER_TURN)
        require(activeConceptIds.all { it.isNotBlank() && it.length <= 96 })
        require(limit in 0..MAX_PREDICTIONS)
        require(minimumIndependentSupports in 2..MAX_OBSERVATIONS_PER_HYPOTHESIS)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.5..1.0)
        if (limit == 0 || activeConceptIds.isEmpty()) return emptyList()

        val candidates = ArrayList<Prediction>()
        states.values.forEach { state ->
            val firstActive = state.firstConceptId in activeConceptIds
            val secondActive = state.secondConceptId in activeConceptIds
            if (firstActive == secondActive) return@forEach
            val support = state.supportingTurns.size
            if (support < minimumIndependentSupports) return@forEach
            val total = support + state.nonReproducingTurns.size
            if (total == 0) return@forEach
            val confidence = support.toDouble() / total
            if (confidence < minimumConfidence) return@forEach
            candidates += if (firstActive) {
                Prediction(
                    sourceConceptId = state.firstConceptId,
                    predictedConceptId = state.secondConceptId,
                    predictedLabel = state.secondLabel,
                    supportingTurnCount = support,
                    verificationTurnCount = total,
                    confidence = confidence,
                    heuristicEvidence = state.heuristicEvidence,
                )
            } else {
                Prediction(
                    sourceConceptId = state.secondConceptId,
                    predictedConceptId = state.firstConceptId,
                    predictedLabel = state.firstLabel,
                    supportingTurnCount = support,
                    verificationTurnCount = total,
                    confidence = confidence,
                    heuristicEvidence = state.heuristicEvidence,
                )
            }
        }
        return candidates
            .groupBy { it.predictedConceptId }
            .values.map { group ->
                group.maxWith(compareBy<Prediction> { it.confidence }.thenBy { it.supportingTurnCount })
            }
            .sortedWith(compareByDescending<Prediction> { it.confidence }
                .thenByDescending { it.supportingTurnCount }
                .thenBy { it.predictedConceptId })
            .take(limit)
    }

    @Synchronized
    fun snapshot(): List<Hypothesis> = states.values.map(::snapshot)

    /** Export a bounded checkpoint suitable for private local persistence. */
    @Synchronized
    fun exportSnapshot(): Snapshot = Snapshot(
        hypotheses = states.values.map { state ->
            PersistedHypothesis(
                hypothesisId = state.hypothesisId,
                firstConceptId = state.firstConceptId,
                firstLabel = state.firstLabel,
                secondConceptId = state.secondConceptId,
                secondLabel = state.secondLabel,
                supportingTurnIds = state.supportingTurns.toList(),
                nonReproducingTurnIds = state.nonReproducingTurns.toList(),
                sharedTargetCount = state.sharedTargetCount,
                heuristicEvidence = state.heuristicEvidence,
            )
        },
        observedTurnIds = observedTurns.toList(),
    )

    /** Restore only internally consistent, bounded state; malformed checkpoints are rejected. */
    @Synchronized
    fun restoreSnapshot(snapshot: Snapshot) {
        require(snapshot.hypotheses.size <= maxHypotheses)
        require(snapshot.observedTurnIds.size <= MAX_REMEMBERED_TURNS)
        require(snapshot.observedTurnIds.distinct().size == snapshot.observedTurnIds.size)
        require(snapshot.observedTurnIds.all(::isValidTurnId))
        val restored = LinkedHashMap<String, State>()
        snapshot.hypotheses.forEach { item ->
            require(item.firstConceptId.isNotBlank() && item.firstConceptId.length <= 128)
            require(item.secondConceptId.isNotBlank() && item.secondConceptId.length <= 128)
            require(item.firstConceptId != item.secondConceptId)
            require(item.firstLabel.isNotBlank() && item.firstLabel.length <= 96)
            require(item.secondLabel.isNotBlank() && item.secondLabel.length <= 96)
            require(item.supportingTurnIds.size <= MAX_OBSERVATIONS_PER_HYPOTHESIS)
            require(item.nonReproducingTurnIds.size <= MAX_OBSERVATIONS_PER_HYPOTHESIS)
            require(item.supportingTurnIds.distinct().size == item.supportingTurnIds.size)
            require(item.nonReproducingTurnIds.distinct().size == item.nonReproducingTurnIds.size)
            require((item.supportingTurnIds + item.nonReproducingTurnIds).distinct().size ==
                item.supportingTurnIds.size + item.nonReproducingTurnIds.size)
            require((item.supportingTurnIds + item.nonReproducingTurnIds).all(::isValidTurnId))
            require(item.sharedTargetCount in 0..MAX_SHARED_TARGETS)
            val key = pairKey(item.firstConceptId, item.secondConceptId)
            require(item.hypothesisId == "ans100-hypothesis:${digest(key)}")
            require(restored.put(
                key,
                State(
                    hypothesisId = item.hypothesisId,
                    firstConceptId = item.firstConceptId,
                    firstLabel = item.firstLabel,
                    secondConceptId = item.secondConceptId,
                    secondLabel = item.secondLabel,
                    supportingTurns = LinkedHashSet(item.supportingTurnIds),
                    nonReproducingTurns = LinkedHashSet(item.nonReproducingTurnIds),
                    sharedTargetCount = item.sharedTargetCount,
                    heuristicEvidence = item.heuristicEvidence,
                ),
            ) == null)
        }
        states.clear()
        states.putAll(restored)
        observedTurns.clear()
        observedTurns.addAll(snapshot.observedTurnIds)
    }

    private fun snapshot(state: State): Hypothesis {
        val status = when {
            state.supportingTurns.size >= 2 && state.nonReproducingTurns.isNotEmpty() ->
                Status.MIXED_REPRODUCTION_SIGNAL
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
            heuristicEvidence = state.heuristicEvidence,
        )
    }

    private fun pairKey(first: String, second: String): String =
        listOf(first, second).sorted().joinToString("|")

    private fun isValidTurnId(value: String): Boolean =
        value.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$"))

    private fun <T> trimOldest(values: LinkedHashSet<T>, maximum: Int) {
        while (values.size > maximum) values.remove(values.first())
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_MAX_HYPOTHESES = 128
        const val MAX_HYPOTHESES_HARD_LIMIT = 256
        const val MAX_OBSERVATIONS_PER_HYPOTHESIS = 8
        const val MAX_SHARED_TARGETS = 6
        const val DEFAULT_MAX_PREDICTIONS = 8
        const val MAX_PREDICTIONS = 16
        const val MIN_PREDICTION_SUPPORTS = 2
        const val MIN_PREDICTION_CONFIDENCE = 0.75
        private const val MAX_REMEMBERED_TURNS = 512
    }
}
