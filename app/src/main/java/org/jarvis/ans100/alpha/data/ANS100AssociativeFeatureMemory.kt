package org.jarvis.ans100.alpha.data

import java.util.Locale

/**
 * Experimental first-stage input/association subsystem for ANS-100.
 *
 * It extracts bounded lexical features and learns co-occurrence associations
 * only from explicit, attributable feedback. It is NOT a semantic parser,
 * neural network, or proof of language understanding. Production routing does
 * not consume this class until held-out evaluation and Android profiling pass.
 */
data class ANS100LexicalFeature(
    val id: String,
    val kind: Kind,
    val activation: Double,
) {
    enum class Kind { TOKEN, BIGRAM }
    init {
        require(id.isNotBlank() && id.length <= MAX_FEATURE_ID_CHARS)
        require(activation.isFinite() && activation in 0.0..1.0)
    }

    companion object {
        const val MAX_FEATURE_ID_CHARS = 96
    }
}

/** Deterministic lexical feature extraction; no intent labels or meaning claims. */
class ANS100AssociativeFeatureEncoder {
    fun encode(text: String): List<ANS100LexicalFeature> {
        require(text.length <= MAX_INPUT_CHARS) { "message exceeds feature encoder input bound" }
        val tokens = TOKEN_REGEX.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .take(MAX_TOKENS)
            .toList()
        if (tokens.isEmpty()) return emptyList()

        val features = LinkedHashMap<String, ANS100LexicalFeature>()
        tokens.distinct().forEach { token ->
            features["token:$token"] = ANS100LexicalFeature(
                id = "token:$token",
                kind = ANS100LexicalFeature.Kind.TOKEN,
                activation = (tokens.count { it == token }.toDouble() / tokens.size).coerceIn(0.0, 1.0),
            )
        }
        tokens.zipWithNext().forEach { (left, right) ->
            val id = "bigram:$left_$right"
            if (id.length <= ANS100LexicalFeature.MAX_FEATURE_ID_CHARS && id !in features) {
                features[id] = ANS100LexicalFeature(
                    id = id,
                    kind = ANS100LexicalFeature.Kind.BIGRAM,
                    activation = 0.5,
                )
            }
        }
        return features.values.take(MAX_FEATURES)
    }

    companion object {
        const val MAX_INPUT_CHARS = 2_048
        const val MAX_TOKENS = 96
        const val MAX_FEATURES = 128
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]{2,48}")
        private val STOP_WORDS = setOf(
            "и", "а", "но", "или", "в", "во", "на", "к", "ко", "с", "со",
            "у", "о", "об", "от", "до", "за", "из", "по", "для", "это",
            "как", "что", "чтобы", "ли", "же", "бы", "не", "ни", "я",
            "ты", "вы", "мы", "он", "она", "они", "мне", "меня", "тебя",
            "the", "and", "or", "but", "for", "with", "from", "this", "that",
            "you", "your", "are", "was", "were",
        )
    }
}

/**
 * Small bounded Hebbian-style association table for controlled experiments.
 * It learns only from explicit user feedback or verified external outcomes.
 * Model-generated text and implicit behavior cannot update associations.
 */
class ANS100AssociativeFeatureMemory(
    private val learningRate: Double = DEFAULT_LEARNING_RATE,
    private val maximumPairs: Int = MAX_PAIRS,
) {
    enum class FeedbackSource {
        EXPLICIT_USER,
        VERIFIED_EXTERNAL_OUTCOME,
        MODEL_GENERATED,
        IMPLICIT_BEHAVIOR,
    }

    data class Association(val leftFeatureId: String, val rightFeatureId: String, val weight: Double)
    data class Recall(val featureId: String, val activation: Double)

    private data class PairKey(val left: String, val right: String)
    private val weights = LinkedHashMap<PairKey, Double>()

    init {
        require(learningRate.isFinite() && learningRate in 0.0..0.1)
        require(maximumPairs in 1..MAX_PAIRS)
    }

    @Synchronized
    fun learn(
        turnId: String,
        features: List<ANS100LexicalFeature>,
        reward: Double,
        source: FeedbackSource,
    ): Int {
        require(turnId.isNotBlank() && turnId.length <= MAX_TURN_ID_CHARS)
        require(features.size <= ANS100AssociativeFeatureEncoder.MAX_FEATURES)
        require(features.map { it.id }.distinct().size == features.size)
        require(reward.isFinite() && reward in -1.0..1.0 && reward != 0.0)
        if (source != FeedbackSource.EXPLICIT_USER &&
            source != FeedbackSource.VERIFIED_EXTERNAL_OUTCOME
        ) return 0

        var changed = 0
        for (i in features.indices) {
            for (j in i + 1 until features.size) {
                if (weights.size >= maximumPairs && PairKey(features[i].id, features[j].id).canonical() !in weights) {
                    continue
                }
                val key = PairKey(features[i].id, features[j].id).canonical()
                val eligibility = (features[i].activation * features[j].activation).coerceIn(0.0, 1.0)
                if (eligibility <= 0.0) continue
                val current = weights[key] ?: INITIAL_WEIGHT
                val next = (current + learningRate * reward * eligibility).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
                if (next != current) {
                    weights[key] = next
                    changed++
                }
            }
        }
        return changed
    }

    @Synchronized
    fun recall(activeFeatures: List<ANS100LexicalFeature>, limit: Int = DEFAULT_RECALL_LIMIT): List<Recall> {
        require(activeFeatures.size <= ANS100AssociativeFeatureEncoder.MAX_FEATURES)
        require(limit in 0..MAX_RECALL_LIMIT)
        if (limit == 0 || activeFeatures.isEmpty()) return emptyList()
        val active = activeFeatures.associateBy { it.id }
        val scores = HashMap<String, Double>()
        weights.forEach { (pair, weight) ->
            val left = active[pair.left]
            val right = active[pair.right]
            if (left != null && right == null) {
                scores[pair.right] = (scores[pair.right] ?: 0.0) + weight * left.activation
            } else if (right != null && left == null) {
                scores[pair.left] = (scores[pair.left] ?: 0.0) + weight * right.activation
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { Recall(it.key, it.value.coerceIn(0.0, 1.0)) }
    }

    @Synchronized
    fun snapshot(): List<Association> = weights.entries.map {
        Association(it.key.left, it.key.right, it.value)
    }

    private fun PairKey.canonical(): PairKey =
        if (left <= right) this else PairKey(right, left)

    companion object {
        const val DEFAULT_LEARNING_RATE = 0.05
        const val INITIAL_WEIGHT = 0.5
        const val MIN_WEIGHT = 0.05
        const val MAX_WEIGHT = 1.0
        const val MAX_PAIRS = 4_096
        const val MAX_TURN_ID_CHARS = 96
        const val DEFAULT_RECALL_LIMIT = 16
        const val MAX_RECALL_LIMIT = 64
    }
}
