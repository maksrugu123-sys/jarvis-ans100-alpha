package org.jarvis.ans100.alpha.data

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

data class DialogFeaturePrediction(
    val predictedLabel: String?,
    val confidenceMargin: Double,
    val activeFeatureCount: Int,
    val trainedExamples: Int,
    val neuronCount: Int,
)

interface DialogFeatureStorage {
    fun read(): String?
    fun write(value: String): Boolean
}

class SharedPreferencesDialogFeatureStorage(context: Context) : DialogFeatureStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        "jarvis_android_dialog_features_v1",
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString("checkpoint", null)
    override fun write(value: String): Boolean =
        preferences.edit().putString("checkpoint", value).commit()
}

class DialogFeatureClassifierException(message: String) : IllegalStateException(message)

/**
 * Sparse 10,000-slot intent feature bank for the native Android conversation.
 *
 * These are addressable text-feature slots, not 10,000 simultaneously simulated
 * spiking neurons and not a language model. Training is only explicit; checkpoints
 * contain numeric weights and a counter, never the user's utterances.
 */
class LocalDialogFeatureClassifier(
    private val storage: DialogFeatureStorage,
) {
    private var weights: MutableMap<String, MutableMap<Int, Double>> = emptyWeights()
    private var trainedExamples: Int = 0
    private var loadError: String? = null

    init {
        try {
            storage.read()?.let(::restore)
        } catch (error: Exception) {
            loadError = error.message ?: "Не удалось восстановить диалоговый классификатор."
        }
    }

    @Synchronized
    fun analyze(text: String): DialogFeaturePrediction {
        ensureAvailable()
        validateText(text)
        return predict(extractFeatures(text))
    }

    /** Applies one user-confirmed label and publishes weights only after storage confirms commit. */
    @Synchronized
    fun learn(text: String, expectedLabel: String): DialogFeaturePrediction {
        ensureAvailable()
        validateText(text)
        if (expectedLabel !in LABELS) {
            throw DialogFeatureClassifierException("Неизвестная метка диалога.")
        }
        val active = extractFeatures(text)
        if (active.isEmpty()) {
            throw DialogFeatureClassifierException("В тексте нет словесных признаков для обучения.")
        }
        if (trainedExamples == Int.MAX_VALUE) {
            throw DialogFeatureClassifierException("Счётчик обучающих примеров достиг предела.")
        }

        val candidate = LABELS.associateWith { label ->
            weights.getValue(label).toMutableMap()
        }.toMutableMap()
        for (label in LABELS) {
            val direction = if (label == expectedLabel) 1.0 else -1.0 / (LABELS.size - 1)
            val table = candidate.getValue(label)
            for ((index, value) in active) {
                val updated = (table[index] ?: 0.0) + direction * LEARNING_RATE * value
                table[index] = updated.coerceIn(-MAX_WEIGHT, MAX_WEIGHT)
            }
        }

        val nextCount = trainedExamples + 1
        val checkpoint = encode(candidate, nextCount)
        if (!storage.write(checkpoint)) {
            throw DialogFeatureClassifierException("Не удалось сохранить веса; прежняя модель оставлена без изменений.")
        }
        weights = candidate
        trainedExamples = nextCount
        return predict(active)
    }

    private fun predict(active: Map<Int, Double>): DialogFeaturePrediction {
        val scores = LABELS.associateWith { label ->
            active.entries.sumOf { (index, value) ->
                (weights.getValue(label)[index] ?: 0.0) * value
            }
        }
        val ranked = LABELS.sortedWith(
            compareByDescending<String> { scores.getValue(it) }.thenBy { it },
        )
        val margin = if (ranked.size < 2) 0.0 else {
            scores.getValue(ranked[0]) - scores.getValue(ranked[1])
        }
        val label = ranked.firstOrNull()
            ?.takeIf { trainedExamples > 0 && margin >= MINIMUM_MARGIN && active.isNotEmpty() }
        return DialogFeaturePrediction(
            predictedLabel = label,
            confidenceMargin = margin,
            activeFeatureCount = active.size,
            trainedExamples = trainedExamples,
            neuronCount = FEATURE_NEURON_COUNT,
        )
    }

    private fun extractFeatures(text: String): Map<Int, Double> {
        val tokens = TOKEN_PATTERN.findAll(text)
            .take(MAX_TOKENS)
            .map { it.value.lowercase(Locale.ROOT) }
            .toList()
        if (tokens.isEmpty()) return emptyMap()

        val featureTexts = ArrayList<String>(tokens.size * 2)
        tokens.forEach { featureTexts += "w:$it" }
        tokens.zipWithNext().forEach { (first, second) ->
            featureTexts += "b:${first}_${second}"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val counts = HashMap<Int, Double>()
        for (feature in featureTexts) {
            val bytes = digest.digest(feature.toByteArray(Charsets.UTF_8))
            // Interpret the first 64 digest bits as unsigned big-endian, modulo the bank size.
            var index = 0L
            for (position in 0 until HASH_BYTES) {
                index = (index * 256L + (bytes[position].toInt() and 0xff)) % FEATURE_NEURON_COUNT
            }
            val slot = index.toInt()
            counts[slot] = (counts[slot] ?: 0.0) + 1.0
        }

        val norm = sqrt(counts.values.sumOf { it * it })
        if (norm <= 0.0 || !norm.isFinite()) return emptyMap()
        return counts.mapValues { (_, count) -> count / norm }
    }

    private fun validateText(text: String) {
        if (text.isBlank() || text.length > MAX_TEXT_CHARS || '\u0000' in text) {
            throw DialogFeatureClassifierException(
                "Текст для диалогового классификатора должен содержать от 1 до $MAX_TEXT_CHARS символов.",
            )
        }
    }

    private fun ensureAvailable() {
        loadError?.let {
            throw DialogFeatureClassifierException("Checkpoint диалоговых признаков повреждён; обучение заблокировано. $it")
        }
    }

    private fun encode(
        candidate: Map<String, Map<Int, Double>>,
        count: Int,
    ): String {
        val lines = ArrayList<String>()
        lines += CHECKPOINT_HEADER
        lines += "T\t$count"
        for (label in LABELS) {
            candidate.getValue(label).toSortedMap().forEach { (index, value) ->
                lines += "W\t$label\t$index\t$value"
            }
        }
        val result = lines.joinToString("\n")
        if (result.length > MAX_CHECKPOINT_CHARS ||
            lines.size - 2 > MAX_STORED_WEIGHT_ROWS
        ) {
            throw DialogFeatureClassifierException("Checkpoint диалоговых признаков превысил лимит размера.")
        }
        return result
    }

    private fun restore(raw: String) {
        if (raw.isEmpty() || raw.length > MAX_CHECKPOINT_CHARS || '\r' in raw) {
            throw DialogFeatureClassifierException("Checkpoint диалоговых признаков пуст или превышает лимит.")
        }
        val lines = raw.split('\n')
        if (lines.size < 2 || lines[0] != CHECKPOINT_HEADER) {
            throw DialogFeatureClassifierException("Неизвестная версия checkpoint диалоговых признаков.")
        }
        val counter = lines[1].split('\t')
        if (counter.size != 2 || counter[0] != "T") {
            throw DialogFeatureClassifierException("Checkpoint не содержит счётчик обучения.")
        }
        val restoredCount = counter[1].toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: throw DialogFeatureClassifierException("Checkpoint содержит неверный счётчик обучения.")

        val restored = emptyWeights()
        var rows = 0
        for (line in lines.drop(2)) {
            val fields = line.split('\t')
            if (fields.size != 4 || fields[0] != "W") {
                throw DialogFeatureClassifierException("Checkpoint содержит некорректную запись веса.")
            }
            val label = fields[1]
            val index = fields[2].toIntOrNull()
            val value = fields[3].toDoubleOrNull()
            if (label !in LABELS || index == null || index !in 0 until FEATURE_NEURON_COUNT ||
                value == null || !value.isFinite() || value !in -MAX_WEIGHT..MAX_WEIGHT ||
                restored.getValue(label).containsKey(index)
            ) {
                throw DialogFeatureClassifierException("Checkpoint содержит недопустимый или повторный вес.")
            }
            restored.getValue(label)[index] = value
            rows++
            if (rows > MAX_STORED_WEIGHT_ROWS) {
                throw DialogFeatureClassifierException("Checkpoint содержит слишком много весов.")
            }
        }
        weights = restored
        trainedExamples = restoredCount
    }

    companion object {
        const val FEATURE_NEURON_COUNT = 10_000
        const val MAX_TEXT_CHARS = 900
        val LABELS = listOf("greeting", "question", "thanks", "farewell")

        private const val CHECKPOINT_HEADER = "JARVIS_ANDROID_DIALOG_FEATURES\t1"
        private const val MAX_TOKENS = 256
        private const val HASH_BYTES = 8
        private const val LEARNING_RATE = 0.15
        private const val MINIMUM_MARGIN = 0.08
        private const val MAX_WEIGHT = 4.0
        private const val MAX_STORED_WEIGHT_ROWS = 40_000
        private const val MAX_CHECKPOINT_CHARS = 4 * 1024 * 1024
        private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")
        private fun emptyWeights(): MutableMap<String, MutableMap<Int, Double>> =
            LABELS.associateWith { mutableMapOf<Int, Double>() }.toMutableMap()
    }
}
