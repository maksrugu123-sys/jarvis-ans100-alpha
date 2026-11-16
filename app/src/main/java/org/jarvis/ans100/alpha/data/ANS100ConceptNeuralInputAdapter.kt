package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Converts already-grounded concept activations into dedicated evidence-input
 * neurons (1062..1199). This is a presence pulse: activation remains in trace
 * and is not misrepresented as a calibrated spike probability.
 */
class ANS100ConceptNeuralInputAdapter {
    data class TraceEntry(
        val conceptId: String,
        val neuronId: Int,
        val matchedAlias: String,
        val matchedFeatureIds: List<String>,
        val activationHint: Double,
        val provenanceRef: String,
    )

    data class ActivationBatch(
        val turnId: String,
        val timestampNs: Long,
        val events: List<AndroidNeuronEvent>,
        val trace: List<TraceEntry>,
    )

    fun createBatch(
        activations: ANS100ConceptActivationAdapter.Result,
        memory: ANS100ConceptMemory,
        timestampNs: Long,
    ): ActivationBatch {
        require(timestampNs > 0L)
        require(activations.turnId.isNotBlank())
        require(activations.activations.size <= MAX_CONCEPTS_PER_TURN)
        require(activations.activations.map { it.conceptId }.distinct().size == activations.activations.size)

        val turnToken = digest(activations.turnId).take(16)
        val events = ArrayList<AndroidNeuronEvent>(activations.activations.size)
        val trace = ArrayList<TraceEntry>(activations.activations.size)
        val usedNeurons = HashSet<Int>()

        activations.activations.sortedBy { it.conceptId }.forEachIndexed { index, activation ->
            val neuronId = memory.neuralInputNeuronId(activation.conceptId)
                ?: throw IllegalStateException("concept neural input slots are exhausted")
            require(neuronId in ANS100ConceptMemory.NEURAL_INPUT_START until
                ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS)
            require(usedNeurons.add(neuronId)) { "two active concepts share a neural input slot" }

            events += AndroidNeuronEvent(
                eventId = "ans100-concept-$timestampNs-$turnToken-$index",
                timeNs = timestampNs,
                targetNeuronId = neuronId,
                strength = ACTIVE_PULSE_STRENGTH,
            )
            trace += TraceEntry(
                conceptId = activation.conceptId,
                neuronId = neuronId,
                matchedAlias = activation.matchedAlias,
                matchedFeatureIds = activation.matchedFeatureIds.toList(),
                activationHint = activation.activation,
                provenanceRef = "turn:${activations.turnId}:concept:${activation.conceptId}:features:${activation.matchedFeatureIds.joinToString(",")}"
                    .take(TypedEvidenceRecord.MAX_PROVENANCE_CHARS),
            )
        }
        return ActivationBatch(activations.turnId, timestampNs, events.toList(), trace.toList())
    }

    /** Merges concept pulses into the existing hybrid batch without altering Core evidence. */
    fun merge(
        evidence: AndroidHybridEvidenceInputAdapter.ActivationBatch,
        concepts: ActivationBatch,
    ): AndroidHybridEvidenceInputAdapter.ActivationBatch {
        require(evidence.turnId == concepts.turnId) { "cannot merge concept activations across turns" }
        require(evidence.timestampNs == concepts.timestampNs) { "input batches must share one model timestamp" }
        require(concepts.events.size == concepts.trace.size)
        require(concepts.events.map { it.targetNeuronId } == concepts.trace.map { it.neuronId })
        require(concepts.events.all {
            it.targetNeuronId in ANS100ConceptMemory.NEURAL_INPUT_START until
                ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
        }) { "concept pulse is outside the reserved concept input range" }

        val events = evidence.events + concepts.events
        val ids = events.map { it.eventId }
        val neurons = events.map { it.targetNeuronId }
        require(events.size <= AndroidHybridEvidenceInputAdapter.MAX_ACTIVE_INPUTS_PER_TURN) {
            "merged hybrid input budget exceeded"
        }
        require(ids.distinct().size == ids.size) { "merged input event IDs must be unique" }
        require(neurons.distinct().size == neurons.size) { "merged input neurons must be unique" }

        return evidence.copy(
            events = events,
            conceptTrace = concepts.trace,
        )
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val MAX_CONCEPTS_PER_TURN = 64
        private const val ACTIVE_PULSE_STRENGTH = 1.0
    }
}
