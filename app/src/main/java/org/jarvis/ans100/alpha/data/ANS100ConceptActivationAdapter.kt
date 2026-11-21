package org.jarvis.ans100.alpha.data

/**
 * Grounds explicit concept labels/aliases against lexical observations.
 *
 * This is deterministic alias grounding, not semantic understanding. It does
 * not infer synonyms, translate languages, or create concepts. Ambiguous aliases
 * intentionally activate every matching concept rather than silently choosing.
 */
class ANS100ConceptActivationAdapter(
    private val encoder: ANS100AssociativeFeatureEncoder = ANS100AssociativeFeatureEncoder(),
) {
    data class Activation(
        val conceptId: String,
        val activation: Double,
        val matchedAlias: String,
        val matchedFeatureIds: List<String>,
    ) {
        init {
            require(conceptId.isNotBlank())
            require(activation.isFinite() && activation in 0.0..1.0)
            require(matchedAlias.isNotBlank())
            require(matchedFeatureIds.isNotEmpty())
        }
    }

    data class Result(
        val turnId: String,
        val activations: List<Activation>,
    )

    fun activate(
        frame: ANS100NeuralInputFrame,
        memory: ANS100ConceptMemory,
        limit: Int = DEFAULT_MAX_ACTIVATIONS,
    ): Result {
        require(limit in 0..MAX_ACTIVATIONS)
        if (limit == 0 || frame.features.isEmpty()) return Result(frame.turnId, emptyList())
        val observed = frame.features.associateBy { it.id }
        val activations = ArrayList<Activation>()
        for (concept in memory.conceptsSnapshot(MAX_CONCEPTS_SCANNED)) {
            val aliases = (concept.aliases + concept.label).distinct().sorted()
            var best: Activation? = null
            for (alias in aliases) {
                val required = encoder.encode(alias).map { it.id }.distinct()
                if (required.isEmpty() || required.any { it !in observed }) continue
                val strength = required.minOf { observed.getValue(it).activation }
                val candidate = Activation(
                    conceptId = concept.id,
                    activation = strength,
                    matchedAlias = alias,
                    matchedFeatureIds = required.sorted(),
                )
                if (best == null || candidate.activation > best.activation ||
                    (candidate.activation == best.activation && candidate.matchedAlias < best.matchedAlias)
                ) best = candidate
            }
            if (best != null) activations += best
        }
        return Result(
            turnId = frame.turnId,
            activations = activations.sortedWith(
                compareByDescending<Activation> { it.activation }.thenBy { it.conceptId },
            ).take(limit),
        )
    }

    companion object {
        const val MAX_CONCEPTS_SCANNED = ANS100ConceptMemory.MAX_CONCEPTS
        const val DEFAULT_MAX_ACTIVATIONS = 64
        const val MAX_ACTIVATIONS = 128
    }
}
