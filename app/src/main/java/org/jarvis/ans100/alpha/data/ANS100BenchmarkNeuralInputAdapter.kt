package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Converts benchmark text into clean, label-free concept-input pulses for the
 * isolated ANS-100 benchmark topology.
 *
 * No expected answer, split name, task family, Core understanding, or candidate
 * decision enters this adapter. Token-to-slot hashing is lexical, not semantic.
 */
class ANS100BenchmarkNeuralInputAdapter(
    private val encoder: ANS100AssociativeFeatureEncoder = ANS100AssociativeFeatureEncoder(),
) {
    data class Result(
        val batch: AndroidHybridEvidenceInputAdapter.ActivationBatch,
        val encodedTokenCount: Int,
        val activatedTokenCount: Int,
        val slotCollisionCount: Int,
    )

    fun createBatch(turnId: String, inputText: String, timestampNs: Long): Result {
        require(turnId.isNotBlank() && turnId.length <= ANS100NeuralInputFrame.MAX_TURN_ID_CHARS)
        require(inputText.isNotBlank() && inputText.length <= ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS)
        require(timestampNs > 0L)

        val tokens = encoder.encode(inputText)
            .filter { it.kind == ANS100LexicalFeature.Kind.TOKEN }
            .sortedBy { it.id }
        val usedSlots = HashSet<Int>()
        val events = ArrayList<AndroidNeuronEvent>(tokens.size)
        val trace = ArrayList<ANS100ConceptNeuralInputAdapter.TraceEntry>(tokens.size)
        var collisions = 0

        tokens.forEach { feature ->
            val digest = sha256(feature.id)
            val slot = ANS100ConceptMemory.NEURAL_INPUT_START +
                (digest.take(8).toLong(16) % ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS).toInt()
            if (!usedSlots.add(slot)) {
                collisions++
                return@forEach
            }
            val conceptId = "benchmark-${digest.take(24)}"
            val token = feature.id.removePrefix("token:")
            events += AndroidNeuronEvent(
                eventId = "ans100-benchmark-$timestampNs-${digest.take(12)}",
                timeNs = timestampNs,
                targetNeuronId = slot,
                strength = 1.0,
                coreTainted = false,
            )
            trace += ANS100ConceptNeuralInputAdapter.TraceEntry(
                conceptId = conceptId,
                neuronId = slot,
                matchedAlias = token,
                matchedFeatureIds = listOf(feature.id),
                activationHint = feature.activation,
                provenanceRef = "benchmark-input:${digest.take(32)}",
            )
        }

        val batch = AndroidHybridEvidenceInputAdapter.ActivationBatch(
            turnId = turnId,
            timestampNs = timestampNs,
            events = events,
            trace = emptyList(),
            conceptTrace = trace,
        )
        return Result(batch, tokens.size, events.size, collisions)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
