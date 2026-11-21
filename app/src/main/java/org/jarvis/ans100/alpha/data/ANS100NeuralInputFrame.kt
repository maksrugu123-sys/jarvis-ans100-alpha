package org.jarvis.ans100.alpha.data

/**
 * Neutral lexical input boundary for ANS-100 experiments.
 *
 * This frame contains user-text-derived lexical observations only. It has no
 * intent, answer, action, candidate-winner, support, or conflict fields.
 * It is intentionally not wired into production decision routing yet.
 */
data class ANS100NeuralInputFrame(
    val turnId: String,
    val features: List<Feature>,
) {
    data class Feature(
        val id: String,
        val kind: ANS100LexicalFeature.Kind,
        val activation: Double,
        val source: Source = Source.USER_TEXT_LEXICAL,
    ) {
        enum class Source { USER_TEXT_LEXICAL }

        init {
            require(id.isNotBlank() && id.length <= ANS100LexicalFeature.MAX_FEATURE_ID_CHARS)
            require(activation.isFinite() && activation in 0.0..1.0)
        }
    }

    init {
        require(turnId.isNotBlank() && turnId.length <= MAX_TURN_ID_CHARS)
        require(features.size <= ANS100AssociativeFeatureEncoder.MAX_FEATURES)
        require(features.map { it.id }.distinct().size == features.size)
    }

    companion object {
        const val MAX_TURN_ID_CHARS = 96
    }
}

/** Converts raw user text into neutral lexical signals without preselecting an intent or action. */
class ANS100NeuralInputAdapter(
    private val encoder: ANS100AssociativeFeatureEncoder = ANS100AssociativeFeatureEncoder(),
) {
    fun encode(turnId: String, rawUserMessage: String): ANS100NeuralInputFrame {
        require(turnId.isNotBlank() && turnId.length <= ANS100NeuralInputFrame.MAX_TURN_ID_CHARS)
        val features = encoder.encode(rawUserMessage).map { feature ->
            ANS100NeuralInputFrame.Feature(
                id = feature.id,
                kind = feature.kind,
                activation = feature.activation,
            )
        }
        return ANS100NeuralInputFrame(turnId = turnId, features = features)
    }
}
