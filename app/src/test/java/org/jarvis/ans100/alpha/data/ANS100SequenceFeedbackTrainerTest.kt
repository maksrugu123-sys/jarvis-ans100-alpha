package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100SequenceFeedbackTrainerTest {
    @Test
    fun onlyExplicitCorrectionsAndVerifiedOutcomesCanChangeWeights() {
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16)
        decoder.train(List(8) { "кот ест рыбу." }, epochs = 20)
        val trainer = ANS100SequenceFeedbackTrainer()
        val before = decoder.exportSnapshot()

        val generated = trainer.apply(
            decoder,
            ANS100SequenceFeedbackTrainer.Feedback(
                prompt = "кот",
                correctedContinuation = "ест рыбу",
                source = ANS100SequenceFeedbackTrainer.Source.MODEL_GENERATED,
            ),
        )
        assertNull(generated)
        assertTrue(decoder.exportSnapshot() == before)

        val corrected = trainer.apply(
            decoder,
            ANS100SequenceFeedbackTrainer.Feedback(
                prompt = "кот",
                correctedContinuation = "ест рыбу",
                source = ANS100SequenceFeedbackTrainer.Source.EXPLICIT_USER_CORRECTION,
            ),
        )
        assertNotNull(corrected)
        assertTrue(corrected!!.trainingTokenCount == 2)
        assertTrue(decoder.exportSnapshot() != before)
    }

    @Test
    fun passiveBehaviorCannotBeUsedAsTrainingLabel() {
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16)
        decoder.train(List(4) { "солнце светит ярко." }, epochs = 10)
        val trainer = ANS100SequenceFeedbackTrainer()
        val before = decoder.exportSnapshot()
        val result = trainer.apply(
            decoder,
            ANS100SequenceFeedbackTrainer.Feedback(
                prompt = "солнце",
                correctedContinuation = "светит ярко",
                source = ANS100SequenceFeedbackTrainer.Source.IMPLICIT_BEHAVIOR,
            ),
        )
        assertNull(result)
        assertTrue(decoder.exportSnapshot() == before)
    }
}
