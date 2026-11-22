package org.jarvis.ans100.alpha.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.Locale
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Small, trainable Elman RNN language-sequence experiment owned by ANS.
 *
 * The network predicts the next token from a recurrent hidden state. It has no
 * pretrained weights, remote inference, prompt templates, or external LLM.
 * Training is supervised next-token learning over examples supplied by the
 * caller. This is a research prototype, not a general-purpose language model.
 *
 * The model is deliberately bounded for Android. Persistence and safe online
 * feedback are separate integration steps and are not implied by this class.
 */
class ANS100TrainableSequenceDecoder(
    private val hiddenSize: Int = DEFAULT_HIDDEN_SIZE,
    private val maxVocabularySize: Int = DEFAULT_MAX_VOCABULARY,
    private val seed: Int = DEFAULT_SEED,
) {
    data class TrainingReport(
        val exampleCount: Int,
        val trainingTokenCount: Int,
        val vocabularySize: Int,
        val epochsCompleted: Int,
        val finalMeanLoss: Double,
    )

    data class Generation(
        val text: String,
        val tokens: List<String>,
        val meanTokenConfidence: Double,
        val stoppedAtEndToken: Boolean,
    )

    private var vocabulary: List<String> = emptyList()
    private var tokenToId: Map<String, Int> = emptyMap()
    private var inputWeights = emptyArray<DoubleArray>()
    private var recurrentWeights = Array(hiddenSize) { DoubleArray(hiddenSize) }
    private var outputWeights = emptyArray<DoubleArray>()
    private var hiddenBias = DoubleArray(hiddenSize)
    private var outputBias = DoubleArray(0)
    private var trained = false

    init {
        require(hiddenSize in MIN_HIDDEN_SIZE..MAX_HIDDEN_SIZE)
        require(maxVocabularySize in MIN_VOCABULARY_SIZE..MAX_VOCABULARY_SIZE)
    }

    val isTrained: Boolean get() = trained
    val vocabularySize: Int get() = vocabulary.size

    /**
     * Reinitializes and trains from a bounded set of complete text examples.
     * At least two distinct token IDs are required to avoid a degenerate model.
     */
    fun train(
        examples: List<String>,
        epochs: Int = DEFAULT_EPOCHS,
        learningRate: Double = DEFAULT_LEARNING_RATE,
    ): TrainingReport {
        require(examples.isNotEmpty() && examples.size <= MAX_TRAINING_EXAMPLES)
        require(epochs in 1..MAX_EPOCHS)
        require(learningRate.isFinite() && learningRate in MIN_LEARNING_RATE..MAX_LEARNING_RATE)
        require(examples.all { it.isNotBlank() && it.length <= MAX_EXAMPLE_CHARS })

        val tokenized = examples.map(::tokenize).filter { it.isNotEmpty() }
        require(tokenized.isNotEmpty()) { "training examples contain no usable tokens" }
        require(tokenized.all { tokens ->
            tokens.size <= MAX_TOKENS_PER_EXAMPLE && tokens.all { it.length <= MAX_TOKEN_CHARS }
        }) {
            "a training example exceeds the token or token-length limit"
        }

        val frequencies = tokenized.flatten().groupingBy { it }.eachCount()
        val selected = frequencies.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(maxVocabularySize - RESERVED_TOKENS.size)
            .map { it.key }
        vocabulary = RESERVED_TOKENS + selected
        tokenToId = vocabulary.withIndex().associate { it.value to it.index }
        require(vocabulary.size >= RESERVED_TOKENS.size + 2) {
            "training requires at least two distinct ordinary tokens"
        }

        initializeWeights()
        val sequences = tokenized.map { tokens ->
            intArrayOf(BOS_ID) + tokens.map { tokenToId[it] ?: UNK_ID }.toIntArray() + intArrayOf(EOS_ID)
        }

        var finalLoss = 0.0
        var completedTokens = 0
        repeat(epochs) {
            var epochLoss = 0.0
            var epochTargets = 0
            sequences.forEach { sequence ->
                val result = trainSequence(sequence, learningRate)
                epochLoss += result.first
                epochTargets += result.second
            }
            finalLoss = if (epochTargets == 0) 0.0 else epochLoss / epochTargets
            completedTokens = epochTargets
        }
        trained = true
        return TrainingReport(
            exampleCount = tokenized.size,
            trainingTokenCount = tokenized.sumOf { it.size },
            vocabularySize = vocabulary.size,
            epochsCompleted = epochs,
            finalMeanLoss = finalLoss,
        )
    }

    /**
     * Continues a prompt through the learned recurrent state and predicts tokens
     * autoregressively. Greedy decoding is deterministic and bounded.
     */
    fun generate(prompt: String, maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS): Generation {
        check(trained) { "decoder must be trained before generation" }
        require(prompt.isNotBlank() && prompt.length <= MAX_EXAMPLE_CHARS)
        require(maxNewTokens in 1..MAX_GENERATION_TOKENS)

        val promptTokens = tokenize(prompt).takeLast(MAX_TOKENS_PER_EXAMPLE)
        require(promptTokens.isNotEmpty()) { "prompt contains no usable tokens" }
        var hidden = DoubleArray(hiddenSize)
        hidden = step(BOS_ID, hidden)
        promptTokens.forEach { token -> hidden = step(tokenToId[token] ?: UNK_ID, hidden) }

        val generated = ArrayList<String>(maxNewTokens)
        var confidenceSum = 0.0
        var ended = false
        for (stepIndex in 0 until maxNewTokens) {
            val probabilities = probabilities(hidden)
            val nextId = probabilities.indices.maxByOrNull { probabilities[it] } ?: EOS_ID
            val confidence = probabilities[nextId]
            if (nextId == EOS_ID) {
                ended = true
                break
            }
            if (nextId == BOS_ID || nextId == UNK_ID) break
            generated += vocabulary[nextId]
            confidenceSum += confidence
            hidden = step(nextId, hidden)
        }
        return Generation(
            text = detokenize(generated),
            tokens = generated.toList(),
            meanTokenConfidence = if (generated.isEmpty()) 0.0 else confidenceSum / generated.size,
            stoppedAtEndToken = ended,
        )
    }

    /**
     * Exports the learned vocabulary and every trainable parameter in a bounded,
     * versioned binary snapshot encoded as URL-safe Base64.
     */
    fun exportSnapshot(): String {
        check(trained) { "decoder must be trained before exporting a snapshot" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(SNAPSHOT_MAGIC)
            output.writeInt(SNAPSHOT_VERSION)
            output.writeInt(hiddenSize)
            output.writeInt(vocabulary.size)
            vocabulary.forEach(output::writeUTF)
            writeMatrix(output, inputWeights)
            writeMatrix(output, recurrentWeights)
            writeMatrix(output, outputWeights)
            writeVector(output, hiddenBias)
            writeVector(output, outputBias)
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
        require(encoded.length <= MAX_SNAPSHOT_CHARS) { "trained model snapshot exceeds storage limit" }
        return encoded
    }

    /**
     * Restores only a complete, compatible, finite snapshot. Parsing is done
     * into temporary arrays first, so a rejected snapshot never partially
     * mutates the active decoder.
     */
    fun restoreSnapshot(encoded: String) {
        require(encoded.isNotBlank() && encoded.length <= MAX_SNAPSHOT_CHARS)
        val bytes = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("model snapshot is not valid Base64", error)
        }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == SNAPSHOT_MAGIC) { "unknown model snapshot format" }
            require(input.readInt() == SNAPSHOT_VERSION) { "unsupported model snapshot version" }
            require(input.readInt() == hiddenSize) { "snapshot hidden size is incompatible" }
            val vocabSize = input.readInt()
            require(vocabSize in (RESERVED_TOKENS.size + 2)..maxVocabularySize)
            val restoredVocabulary = List(vocabSize) { input.readUTF() }
            require(restoredVocabulary.take(RESERVED_TOKENS.size) == RESERVED_TOKENS)
            require(restoredVocabulary.distinct().size == restoredVocabulary.size)
            require(restoredVocabulary.all { it.isNotBlank() && it.length <= MAX_TOKEN_CHARS })
            val restoredInput = readMatrix(input, vocabSize, hiddenSize)
            val restoredRecurrent = readMatrix(input, hiddenSize, hiddenSize)
            val restoredOutput = readMatrix(input, hiddenSize, vocabSize)
            val restoredHiddenBias = readVector(input, hiddenSize)
            val restoredOutputBias = readVector(input, vocabSize)
            require(input.available() == 0) { "model snapshot contains trailing data" }

            // Publish the fully validated state atomically after all reads succeed.
            vocabulary = restoredVocabulary
            tokenToId = vocabulary.withIndex().associate { it.value to it.index }
            inputWeights = restoredInput
            recurrentWeights = restoredRecurrent
            outputWeights = restoredOutput
            hiddenBias = restoredHiddenBias
            outputBias = restoredOutputBias
            trained = true
        }
    }

    /** Continues training from current weights; unlike train(), it never resets them. */
    fun fineTune(
        examples: List<String>,
        epochs: Int = DEFAULT_FINE_TUNE_EPOCHS,
        learningRate: Double = DEFAULT_FINE_TUNE_LEARNING_RATE,
    ): TrainingReport {
        check(trained) { "decoder must be trained before fine-tuning" }
        require(examples.isNotEmpty() && examples.size <= MAX_TRAINING_EXAMPLES)
        require(epochs in 1..MAX_EPOCHS)
        require(learningRate.isFinite() && learningRate in MIN_LEARNING_RATE..MAX_LEARNING_RATE)
        require(examples.all { it.isNotBlank() && it.length <= MAX_EXAMPLE_CHARS })
        val tokenized = examples.map(::tokenize).filter { it.isNotEmpty() }
        require(tokenized.isNotEmpty() && tokenized.all { tokens ->
            tokens.size <= MAX_TOKENS_PER_EXAMPLE && tokens.all { it.length <= MAX_TOKEN_CHARS }
        })
        require(tokenized.flatten().any { it in tokenToId && it !in RESERVED_TOKENS }) {
            "fine-tuning examples must contain at least one token from the trained vocabulary"
        }
        val sequences = tokenized.map { tokens ->
            intArrayOf(BOS_ID) + tokens.map { tokenToId[it] ?: UNK_ID }.toIntArray() + intArrayOf(EOS_ID)
        }
        var finalLoss = 0.0
        repeat(epochs) {
            var epochLoss = 0.0
            var targetCount = 0
            sequences.forEach { sequence ->
                val result = trainSequence(sequence, learningRate)
                epochLoss += result.first
                targetCount += result.second
            }
            finalLoss = if (targetCount == 0) 0.0 else epochLoss / targetCount
        }
        return TrainingReport(
            exampleCount = tokenized.size,
            trainingTokenCount = tokenized.sumOf { it.size },
            vocabularySize = vocabulary.size,
            epochsCompleted = epochs,
            finalMeanLoss = finalLoss,
        )
    }

    private fun writeMatrix(output: DataOutputStream, matrix: Array<DoubleArray>) {
        matrix.forEach { row -> row.forEach(output::writeDouble) }
    }

    private fun writeVector(output: DataOutputStream, vector: DoubleArray) {
        vector.forEach(output::writeDouble)
    }

    private fun readMatrix(input: DataInputStream, rows: Int, columns: Int): Array<DoubleArray> =
        Array(rows) { DoubleArray(columns) { readFiniteWeight(input) } }

    private fun readVector(input: DataInputStream, size: Int): DoubleArray =
        DoubleArray(size) { readFiniteWeight(input) }

    private fun readFiniteWeight(input: DataInputStream): Double {
        val value = input.readDouble()
        require(value.isFinite() && value in -MAX_ABSOLUTE_WEIGHT..MAX_ABSOLUTE_WEIGHT) {
            "snapshot contains an invalid model weight"
        }
        return value
    }

    private fun trainSequence(sequence: IntArray, learningRate: Double): Pair<Double, Int> {
        val states = Array(sequence.size) { DoubleArray(hiddenSize) }
        val logits = Array(sequence.size - 1) { DoubleArray(vocabulary.size) }
        var loss = 0.0

        for (time in 0 until sequence.size - 1) {
            states[time + 1] = step(sequence[time], states[time])
            logits[time] = logits(states[time + 1])
            val probs = softmax(logits[time])
            val target = sequence[time + 1]
            loss -= lnSafe(probs[target])
        }

        val gradInput = Array(inputWeights.size) { DoubleArray(hiddenSize) }
        val gradRecurrent = Array(hiddenSize) { DoubleArray(hiddenSize) }
        val gradOutput = Array(hiddenSize) { DoubleArray(vocabulary.size) }
        val gradHiddenBias = DoubleArray(hiddenSize)
        val gradOutputBias = DoubleArray(vocabulary.size)
        var nextHiddenGradient = DoubleArray(hiddenSize)

        for (time in sequence.size - 2 downTo 0) {
            val probs = softmax(logits[time])
            probs[sequence[time + 1]] -= 1.0
            for (h in 0 until hiddenSize) {
                for (v in vocabulary.indices) {
                    gradOutput[h][v] += states[time + 1][h] * probs[v]
                }
            }
            for (v in vocabulary.indices) gradOutputBias[v] += probs[v]

            val hiddenGradient = nextHiddenGradient.copyOf()
            for (h in 0 until hiddenSize) {
                for (v in vocabulary.indices) hiddenGradient[h] += outputWeights[h][v] * probs[v]
            }
            val rawGradient = DoubleArray(hiddenSize) { h ->
                hiddenGradient[h] * (1.0 - states[time + 1][h] * states[time + 1][h])
            }
            val inputId = sequence[time]
            for (h in 0 until hiddenSize) {
                gradInput[inputId][h] += rawGradient[h]
                gradHiddenBias[h] += rawGradient[h]
                for (previous in 0 until hiddenSize) {
                    gradRecurrent[previous][h] += states[time][previous] * rawGradient[h]
                }
            }
            nextHiddenGradient = DoubleArray(hiddenSize) { previous ->
                var sum = 0.0
                for (h in 0 until hiddenSize) sum += recurrentWeights[previous][h] * rawGradient[h]
                sum
            }
        }

        applyGradient(inputWeights, gradInput, learningRate)
        applyGradient(recurrentWeights, gradRecurrent, learningRate)
        applyGradient(outputWeights, gradOutput, learningRate)
        applyGradient(hiddenBias, gradHiddenBias, learningRate)
        applyGradient(outputBias, gradOutputBias, learningRate)
        return loss to (sequence.size - 1)
    }

    private fun step(tokenId: Int, previous: DoubleArray): DoubleArray {
        val next = DoubleArray(hiddenSize)
        for (h in 0 until hiddenSize) {
            var sum = hiddenBias[h] + inputWeights[tokenId][h]
            for (p in 0 until hiddenSize) sum += previous[p] * recurrentWeights[p][h]
            next[h] = tanh(sum)
        }
        return next
    }

    private fun logits(hidden: DoubleArray): DoubleArray =
        DoubleArray(vocabulary.size) { v ->
            var sum = outputBias[v]
            for (h in 0 until hiddenSize) sum += hidden[h] * outputWeights[h][v]
            sum
        }

    private fun probabilities(hidden: DoubleArray): DoubleArray = softmax(logits(hidden))

    private fun softmax(values: DoubleArray): DoubleArray {
        val maximum = values.maxOrNull() ?: 0.0
        val result = DoubleArray(values.size) { index -> exp((values[index] - maximum).coerceAtLeast(-60.0)) }
        val total = result.sum().coerceAtLeast(1e-12)
        for (index in result.indices) result[index] /= total
        return result
    }

    private fun initializeWeights() {
        val random = Random(seed)
        fun weight(): Double = (random.nextDouble() - 0.5) * 0.16
        inputWeights = Array(vocabulary.size) { DoubleArray(hiddenSize) { weight() } }
        recurrentWeights = Array(hiddenSize) { DoubleArray(hiddenSize) { weight() } }
        outputWeights = Array(hiddenSize) { DoubleArray(vocabulary.size) { weight() } }
        hiddenBias = DoubleArray(hiddenSize)
        outputBias = DoubleArray(vocabulary.size)
        trained = false
    }

    private fun applyGradient(weights: Array<DoubleArray>, gradient: Array<DoubleArray>, rate: Double) {
        for (row in weights.indices) {
            for (column in weights[row].indices) {
                weights[row][column] -= rate * gradient[row][column].coerceIn(-GRADIENT_CLIP, GRADIENT_CLIP)
            }
        }
    }

    private fun applyGradient(weights: DoubleArray, gradient: DoubleArray, rate: Double) {
        for (index in weights.indices) {
            weights[index] -= rate * gradient[index].coerceIn(-GRADIENT_CLIP, GRADIENT_CLIP)
        }
    }

    private fun tokenize(value: String): List<String> =
        TOKEN_PATTERN.findAll(value.lowercase(Locale.ROOT).replace('ё', 'е'))
            .map { it.value }
            .take(MAX_TOKENS_PER_EXAMPLE + 1)
            .toList()

    private fun detokenize(tokens: List<String>): String {
        val result = StringBuilder()
        tokens.forEach { token ->
            val punctuation = token.length == 1 && token[0] in PUNCTUATION
            if (result.isNotEmpty() && !punctuation) result.append(' ')
            result.append(token)
        }
        return result.toString()
    }

    private fun lnSafe(value: Double): Double = kotlin.math.ln(value.coerceAtLeast(1e-12))

    companion object {
        const val SNAPSHOT_VERSION = 1
        const val MAX_SNAPSHOT_CHARS = 12 * 1024 * 1024
        const val MAX_TOKEN_CHARS = 128
        const val DEFAULT_FINE_TUNE_EPOCHS = 2
        const val DEFAULT_FINE_TUNE_LEARNING_RATE = 0.005
        private const val SNAPSHOT_MAGIC = 0x414E5331
        private const val MAX_ABSOLUTE_WEIGHT = 1_000_000.0
        const val DEFAULT_HIDDEN_SIZE = 24
        const val DEFAULT_MAX_VOCABULARY = 256
        const val DEFAULT_SEED = 100
        const val DEFAULT_EPOCHS = 12
        const val DEFAULT_LEARNING_RATE = 0.04
        const val DEFAULT_MAX_NEW_TOKENS = 24
        const val MAX_TRAINING_EXAMPLES = 256
        const val MAX_EXAMPLE_CHARS = 8_000
        const val MAX_TOKENS_PER_EXAMPLE = 64
        const val MAX_GENERATION_TOKENS = 48
        const val MAX_EPOCHS = 100
        const val MIN_HIDDEN_SIZE = 8
        const val MAX_HIDDEN_SIZE = 64
        const val MIN_VOCABULARY_SIZE = 8
        const val MAX_VOCABULARY_SIZE = 512
        const val MIN_LEARNING_RATE = 0.0001
        const val MAX_LEARNING_RATE = 0.2
        private const val GRADIENT_CLIP = 5.0
        private const val BOS_ID = 0
        private const val EOS_ID = 1
        private const val UNK_ID = 2
        private val RESERVED_TOKENS = listOf("<bos>", "<eos>", "<unk>")
        private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+|[.,!?;:]")
        private val PUNCTUATION = setOf('.', ',', '!', '?', ';', ':')
    }
}
