package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
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
    fun promptAnswerTrainingSupervisesOnlyTheAnswerContinuation() {
        val pairs = List(8) { "кот" to "ест рыбу." }
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 7)

        val initial = decoder.trainPairs(pairs, epochs = 1, learningRate = 0.05)
        val trained = decoder.trainPairs(pairs, epochs = 80, learningRate = 0.05)
        val generated = decoder.generate("кот", maxNewTokens = 5)

        assertTrue(trained.epochsCompleted == 80)
        assertTrue(trained.trainingTokenCount >= 24)
        assertTrue(trained.finalMeanLoss < initial.finalMeanLoss)
        assertTrue(generated.text.startsWith("ест"))
        assertTrue(generated.text.contains("рыбу"))
    }

    @Test
    fun subwordModelComposesPromptFromKnownCharactersAndReportsUnknownPieces() {
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 7)
        decoder.trainPairs(
            listOf(
                "кот" to "ест рыбу.",
                "коты" to "едят корм.",
                "пёс" to "ест корм.",
                "котик" to "ест рыбу.",
            ),
            epochs = 40,
        )

        val knownPieces = decoder.generate("котом", maxNewTokens = 4)
        val familiar = decoder.generate("котик", maxNewTokens = 4)

        assertEquals(0, knownPieces.unknownPromptTokenCount)
        assertEquals(0, familiar.unknownPromptTokenCount)
    }

    @Test
    fun versionOneWordLevelSnapshotRemainsReadable() {
        val original = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 7)
        original.train(List(8) { "кот ест рыбу." }, epochs = 5)
        val current = java.util.Base64.getUrlDecoder().decode(original.exportSnapshot())
        val legacy = java.nio.ByteBuffer.allocate(current.size - Int.SIZE_BYTES)
            .put(current, 0, 4)
            .putInt(1)
            .put(current, 8, 4)
            .put(current, 16, current.size - 16)
            .array()
        val encodedLegacy = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(legacy)

        val restored = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 99)
        restored.restoreSnapshot(encodedLegacy)

        assertTrue(restored.isTrained)
        assertEquals(original.generate("кот"), restored.generate("кот"))
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
    fun snapshotRestoresVocabularyAndExactGenerationBehavior() {
        val examples = List(8) { "кот ест рыбу." }
        val original = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 7)
        original.train(examples, epochs = 50, learningRate = 0.05)
        val before = original.generate("кот", maxNewTokens = 5)
        val snapshot = original.exportSnapshot()

        val restored = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 99)
        restored.restoreSnapshot(snapshot)
        val after = restored.generate("кот", maxNewTokens = 5)

        assertTrue(restored.isTrained)
        assertTrue(restored.vocabularySize == original.vocabularySize)
        assertTrue(after == before)
    }

    @Test
    fun invalidSnapshotDoesNotReplaceExistingModel() {
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 7)
        decoder.train(List(8) { "кот ест рыбу." }, epochs = 20)
        val before = decoder.generate("кот", maxNewTokens = 4)
        try {
            decoder.restoreSnapshot("not-a-valid-snapshot")
            throw AssertionError("invalid snapshot must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        assertTrue(decoder.generate("кот", maxNewTokens = 4) == before)
    }

    @Test
    fun fineTuningContinuesExistingWeights() {
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 7)
        decoder.train(List(8) { "кот ест рыбу." }, epochs = 30)
        val before = decoder.exportSnapshot()
        decoder.fineTune(List(4) { "кот ест рыбу." }, epochs = 5)
        assertTrue(decoder.exportSnapshot() != before)
    }


    @Test
    fun beamGenerationIsDeterministicAndRespectsTokenLimit() {
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 19)
        decoder.trainPairs(
            listOf(
                "кот" to "ест рыбу.",
                "пёс" to "ест корм.",
                "птица" to "ест зерно.",
                "рыба" to "плывёт в воде.",
            ),
            epochs = 40,
        )

        val first = decoder.generate("кот", maxNewTokens = 4)
        val second = decoder.generate("кот", maxNewTokens = 4)

        assertEquals(first, second)
        assertTrue(first.tokens.size <= 4)
        assertTrue(first.meanTokenConfidence in 0.0..1.0)
        assertFalse(first.tokens.any { it in setOf("<bos>", "<eos>", "<unk>") })
    }

    @Test
    fun beamGenerationHandlesMaximumBoundWithoutUnboundedOutput() {
        val decoder = ANS100TrainableSequenceDecoder(hiddenSize = 16, seed = 23)
        decoder.trainPairs(
            listOf("кот" to "ест рыбу.", "пёс" to "ест корм."),
            epochs = 30,
        )

        val generated = decoder.generate("кот", maxNewTokens = ANS100TrainableSequenceDecoder.MAX_GENERATION_TOKENS)

        assertTrue(generated.tokens.size <= ANS100TrainableSequenceDecoder.MAX_GENERATION_TOKENS)
        assertTrue(generated.text.length <= ANS100TrainableSequenceDecoder.MAX_GENERATION_TOKENS * ANS100TrainableSequenceDecoder.MAX_TOKEN_CHARS)
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
