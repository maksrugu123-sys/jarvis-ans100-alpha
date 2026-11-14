package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDialogFeatureClassifierTest {
    private class MemoryStorage(
        var value: String? = null,
        var allowWrite: Boolean = true,
    ) : DialogFeatureStorage {
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
    }

    @Test
    fun untrainedClassifierExposesSparseTenThousandSlotBankWithoutGuessing() {
        val storage = MemoryStorage()
        val classifier = LocalDialogFeatureClassifier(storage)
        val prediction = classifier.analyze("Привет, Джарвис!")

        assertEquals(10_000, prediction.neuronCount)
        assertTrue(prediction.activeFeatureCount in 1..511)
        assertNull(prediction.predictedLabel)
        assertEquals(0, prediction.trainedExamples)
        assertNull(storage.value)
    }

    @Test
    fun explicitFeedbackLearnsAndPersistsOnlyNumericWeights() {
        val storage = MemoryStorage()
        val classifier = LocalDialogFeatureClassifier(storage)
        repeat(8) {
            classifier.learn("привет джарвис здравствуй", "greeting")
        }

        val prediction = classifier.analyze("привет джарвис")
        assertEquals("greeting", prediction.predictedLabel)
        assertEquals(8, prediction.trainedExamples)
        assertFalse(storage.value.orEmpty().contains("привет джарвис"))
        assertTrue(storage.value.orEmpty().contains("W\tgreeting\t"))
    }

    @Test
    fun explicitPositiveRewardStrengthensTheCurrentOutputThroughEligibilityTrace() {
        val storage = MemoryStorage()
        val classifier = LocalDialogFeatureClassifier(storage)
        repeat(8) { classifier.learn("привет джарвис здравствуй", "greeting") }
        val before = classifier.analyze("привет джарвис")
        val after = classifier.applyReward("привет джарвис", 1.0)

        assertEquals("greeting", after.predictedLabel)
        assertTrue(after.confidenceMargin > before.confidenceMargin)
        assertEquals(1, after.rewardUpdates)
        assertTrue(storage.value.orEmpty().contains("R\t1"))
        assertFalse(storage.value.orEmpty().contains("привет джарвис"))
    }

    @Test
    fun explicitNegativeRewardWeakensTheCurrentOutput() {
        val storage = MemoryStorage()
        val classifier = LocalDialogFeatureClassifier(storage)
        repeat(8) { classifier.learn("привет джарвис здравствуй", "greeting") }
        val before = classifier.analyze("привет джарвис")
        val after = classifier.applyReward("привет джарвис", -1.0)

        assertTrue(after.confidenceMargin < before.confidenceMargin)
        assertEquals(1, after.rewardUpdates)
    }

    @Test
    fun versionOneCheckpointMigratesWithZeroRewardUpdates() {
        val storage = MemoryStorage(
            value = "JARVIS_ANDROID_DIALOG_FEATURES\t1\nT\t3\nW\tgreeting\t7\t0.5",
        )
        val prediction = LocalDialogFeatureClassifier(storage).analyze("обычный текст")
        assertEquals(3, prediction.trainedExamples)
        assertEquals(0, prediction.rewardUpdates)
    }

    @Test
    fun learnedWeightsAndPredictionsSurviveClassifierRecreation() {
        val storage = MemoryStorage()
        val first = LocalDialogFeatureClassifier(storage)
        repeat(8) { first.learn("спасибо большое джарвис", "thanks") }

        val restored = LocalDialogFeatureClassifier(storage)
        val prediction = restored.analyze("спасибо большое")
        assertEquals("thanks", prediction.predictedLabel)
        assertEquals(8, prediction.trainedExamples)
    }

    @Test
    fun failedCheckpointCommitDoesNotPublishCandidateWeights() {
        val storage = MemoryStorage(allowWrite = false)
        val classifier = LocalDialogFeatureClassifier(storage)
        var rejected = false
        try {
            classifier.learn("пока до свидания", "farewell")
        } catch (_: DialogFeatureClassifierException) {
            rejected = true
        }

        val prediction = classifier.analyze("пока до свидания")
        assertTrue(rejected)
        assertNull(prediction.predictedLabel)
        assertEquals(0, prediction.trainedExamples)
        assertNull(storage.value)
    }

    @Test
    fun corruptCheckpointFailsClosedAndIsNotOverwritten() {
        val storage = MemoryStorage(value = "corrupt-checkpoint")
        val classifier = LocalDialogFeatureClassifier(storage)
        var rejected = false
        try {
            classifier.analyze("обычный вопрос")
        } catch (_: DialogFeatureClassifierException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals("corrupt-checkpoint", storage.value)
    }

    @Test
    fun invalidTextAndUnsupportedLabelsAreRejected() {
        val classifier = LocalDialogFeatureClassifier(MemoryStorage())
        var rejected = false
        try {
            classifier.analyze("   ")
        } catch (_: DialogFeatureClassifierException) {
            rejected = true
        }
        assertTrue(rejected)

        rejected = false
        try {
            classifier.learn("valid words", "unsupported")
        } catch (_: DialogFeatureClassifierException) {
            rejected = true
        }
        assertTrue(rejected)

        rejected = false
        try {
            classifier.learn("x".repeat(LocalDialogFeatureClassifier.MAX_TEXT_CHARS + 1), "question")
        } catch (_: DialogFeatureClassifierException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun featureExtractionIsBoundedTo256TokensAndProducesDeterministicPredictions() {
        val phrase = (0 until 300).joinToString(" ") { index ->
            val first = 'a' + index / 26
            val second = 'a' + index % 26
            "$first$second"
        }
        val first = LocalDialogFeatureClassifier(MemoryStorage())
        val second = LocalDialogFeatureClassifier(MemoryStorage())

        val a = first.analyze(phrase)
        val b = second.analyze(phrase)
        assertTrue(a.activeFeatureCount in 1..511)
        assertTrue(a.activeFeatureIds.isNotEmpty())
        assertTrue(a.activeFeatureIds.size <= 128)
        assertTrue(a.activeFeatureIds.all { it in 0 until 10_000 })
        assertEquals(a.activeFeatureIds, b.activeFeatureIds)
        assertEquals(a.activeFeatureCount, b.activeFeatureCount)
        assertEquals(a.confidenceMargin, b.confidenceMargin, 0.0)
    }
}
