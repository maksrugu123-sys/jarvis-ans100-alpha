package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100TrainableSequenceDecoderTest {
    @Test
    fun decoderLearnsNextTokenSequenceFromExamples() {
        val examples = List(8) { "кот ест рыбу." }
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 7)

        val initial = decoder.train(examples, epochs = 1, learningRate = 0.05)
        val trained = decoder.train(examples, epochs = 60, learningRate = 0.05)
        val generated = decoder.generate("кот", maxNewTokens = 5)

        assertTrue(decoder.isTrained)
        assertTrue(trained.epochsCompleted == 60)
        assertTrue(trained.finalMeanLoss < initial.finalMeanLoss)
        assertTrue(generated.text.startsWith("ест"))
        assertTrue(generated.text.contains("рыбу"))
        assertTrue(generated.tokens.isNotEmpty())
        assertTrue(generated.meanTokenConfidence in 0.0..1.0)
    }

    @Test
    fun decoderCannotGenerateBeforeTraining() {
        val decoder = ANS100TrainableSequenceDecoder()
        assertFalse(decoder.isTrained)
        try {
            decoder.generate("привет")
            throw AssertionError("generation must require trained weights")
        } catch (_: IllegalStateException) {
            // Expected: untrained weights must never be presented as learned output.
        }
    }

    @Test
    fun trainingRejectsUnboundedOrEmptyExamples() {
        val decoder = ANS100TrainableSequenceDecoder()
        try {
            decoder.train(listOf("   "))
            throw AssertionError("blank examples must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
