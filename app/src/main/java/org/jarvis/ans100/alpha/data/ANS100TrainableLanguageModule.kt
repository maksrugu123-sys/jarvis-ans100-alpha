package org.jarvis.ans100.alpha.data

/**
 * Owns the trainable text decoder, its durable snapshot and the trusted-feedback
 * gate. Candidate models are committed only after their snapshot is saved, so a
 * storage failure never replaces the last durable in-memory model.
 */
class ANS100TrainableLanguageModule(
    private val storage: ANS100SequenceModelStorage,
    private val feedbackTrainer: ANS100SequenceFeedbackTrainer = ANS100SequenceFeedbackTrainer(),
) {
    data class Status(
        val trained: Boolean,
        val vocabularySize: Int,
        val hiddenSize: Int,
        val storageLoaded: Boolean,
        val storageError: String?,
    )

    private var decoder = newDecoder()
    private var storageLoaded = false
    private var storageError: String? = null

    init {
        try {
            val snapshot = storage.readSnapshot()
            if (snapshot != null) {
                val candidate = newDecoder()
                candidate.restoreSnapshot(snapshot)
                decoder = candidate
                storageLoaded = true
            }
        } catch (error: Exception) {
            storageError = error.message ?: "Не удалось восстановить нейронную языковую модель."
        }
    }

    @Synchronized
    fun train(
        examples: List<String>,
        epochs: Int = ANS100TrainableSequenceDecoder.DEFAULT_EPOCHS,
        learningRate: Double = ANS100TrainableSequenceDecoder.DEFAULT_LEARNING_RATE,
    ): ANS100TrainableSequenceDecoder.TrainingReport {
        val candidate = newDecoder()
        val report = candidate.train(examples, epochs, learningRate)
        persistCandidate(candidate)
        decoder = candidate
        storageLoaded = true
        storageError = null
        return report
    }

    @Synchronized
    fun generate(prompt: String): ANS100TrainableSequenceDecoder.Generation? =
        if (decoder.isTrained) decoder.generate(prompt) else null

    @Synchronized
    fun applyFeedback(
        feedback: ANS100SequenceFeedbackTrainer.Feedback,
        epochs: Int = ANS100TrainableSequenceDecoder.DEFAULT_FINE_TUNE_EPOCHS,
        learningRate: Double = ANS100TrainableSequenceDecoder.DEFAULT_FINE_TUNE_LEARNING_RATE,
    ): ANS100TrainableSequenceDecoder.TrainingReport? {
        if (feedback.source != ANS100SequenceFeedbackTrainer.Source.EXPLICIT_USER_CORRECTION &&
            feedback.source != ANS100SequenceFeedbackTrainer.Source.VERIFIED_EXTERNAL_OUTCOME
        ) return null
        check(decoder.isTrained) { "decoder must be trained before applying feedback" }
        val candidate = newDecoder()
        candidate.restoreSnapshot(decoder.exportSnapshot())
        val report = feedbackTrainer.apply(candidate, feedback, epochs, learningRate) ?: return null
        persistCandidate(candidate)
        decoder = candidate
        storageLoaded = true
        storageError = null
        return report
    }

    @Synchronized
    fun status(): Status = Status(
        trained = decoder.isTrained,
        vocabularySize = decoder.vocabularySize,
        hiddenSize = DEFAULT_HIDDEN_SIZE,
        storageLoaded = storageLoaded,
        storageError = storageError,
    )

    private fun persistCandidate(candidate: ANS100TrainableSequenceDecoder) {
        val snapshot = candidate.exportSnapshot()
        if (!storage.writeSnapshot(snapshot)) {
            throw IllegalStateException(
                "Не удалось сохранить веса ANS. Предыдущая активная модель не заменена.",
            )
        }
    }

    private fun newDecoder() = ANS100TrainableSequenceDecoder(
        hiddenSize = DEFAULT_HIDDEN_SIZE,
        maxVocabularySize = DEFAULT_MAX_VOCABULARY,
    )

    private companion object {
        const val DEFAULT_HIDDEN_SIZE = ANS100TrainableSequenceDecoder.DEFAULT_HIDDEN_SIZE
        const val DEFAULT_MAX_VOCABULARY = ANS100TrainableSequenceDecoder.DEFAULT_MAX_VOCABULARY
    }
}
