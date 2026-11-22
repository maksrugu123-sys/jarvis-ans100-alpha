package org.jarvis.ans100.alpha.data

/**
 * Gate for incremental language learning. Only a user-supplied correction or
 * an independently verified external outcome may change decoder weights.
 * Model-generated text and passive behavior are never accepted as labels.
 */
class ANS100SequenceFeedbackTrainer {
    enum class Source {
        EXPLICIT_USER_CORRECTION,
        VERIFIED_EXTERNAL_OUTCOME,
        MODEL_GENERATED,
        IMPLICIT_BEHAVIOR,
    }

    data class Feedback(
        val prompt: String,
        val correctedContinuation: String,
        val source: Source,
    )

    fun apply(
        decoder: ANS100TrainableSequenceDecoder,
        feedback: Feedback,
        epochs: Int = ANS100TrainableSequenceDecoder.DEFAULT_FINE_TUNE_EPOCHS,
        learningRate: Double = ANS100TrainableSequenceDecoder.DEFAULT_FINE_TUNE_LEARNING_RATE,
    ): ANS100TrainableSequenceDecoder.TrainingReport? {
        if (feedback.source != Source.EXPLICIT_USER_CORRECTION &&
            feedback.source != Source.VERIFIED_EXTERNAL_OUTCOME
        ) return null
        val prompt = feedback.prompt.trim()
        val correction = feedback.correctedContinuation.trim()
        require(prompt.isNotEmpty() && correction.isNotEmpty())
        require(prompt.length <= ANS100TrainableSequenceDecoder.MAX_EXAMPLE_CHARS)
        require(correction.length <= ANS100TrainableSequenceDecoder.MAX_EXAMPLE_CHARS)
        val example = "$prompt $correction"
        require(example.length <= ANS100TrainableSequenceDecoder.MAX_EXAMPLE_CHARS)
        return decoder.fineTune(listOf(example), epochs, learningRate)
    }
}
