package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100TrainableLanguageModuleTest {
    private class MemoryStorage : ANS100SequenceModelStorage {
        var snapshot: String? = null
        var allowWrite = true
        override fun readSnapshot(): String? = snapshot
        override fun writeSnapshot(snapshot: String): Boolean {
            if (!allowWrite) return false
            this.snapshot = snapshot
            return true
        }
        override fun clearSnapshot(): Boolean {
            snapshot = null
            return true
        }
    }

    @Test
    fun trainedWeightsSurviveModuleRecreation() {
        val storage = MemoryStorage()
        val first = ANS100TrainableLanguageModule(storage)
        assertTrue(!first.status().trained)
        first.train(List(8) { "кот ест рыбу." }, epochs = 40)
        val generatedBefore = first.generate("кот")
        assertNotNull(generatedBefore)
        assertNotNull(storage.snapshot)

        val restored = ANS100TrainableLanguageModule(storage)
        assertTrue(restored.status().trained)
        assertEquals(generatedBefore, restored.generate("кот"))
    }

    @Test
    fun promptAnswerTrainingPersistsAndRestoresGeneration() {
        val storage = MemoryStorage()
        val module = ANS100TrainableLanguageModule(storage)
        module.trainPairs(List(8) { "кот" to "ест рыбу." }, epochs = 60)
        val before = module.generate("кот")
        assertNotNull(before)

        val restored = ANS100TrainableLanguageModule(storage)
        assertEquals(before, restored.generate("кот"))
    }

    @Test
    fun failedPersistenceDoesNotReplaceActiveModel() {
        val storage = MemoryStorage()
        val module = ANS100TrainableLanguageModule(storage)
        module.train(List(8) { "кот ест рыбу." }, epochs = 30)
        val before = module.generate("кот")
        storage.allowWrite = false
        try {
            module.applyFeedback(
                ANS100SequenceFeedbackTrainer.Feedback(
                    prompt = "кот",
                    correctedContinuation = "ест рыбу",
                    source = ANS100SequenceFeedbackTrainer.Source.EXPLICIT_USER_CORRECTION,
                ),
            )
            throw AssertionError("failed persistence must be reported")
        } catch (_: IllegalStateException) {
            // Expected: active model is not replaced until durable write succeeds.
        }
        assertEquals(before, module.generate("кот"))
    }

    @Test
    fun modelGeneratedFeedbackIsIgnored() {
        val storage = MemoryStorage()
        val module = ANS100TrainableLanguageModule(storage)
        module.train(List(8) { "кот ест рыбу." }, epochs = 20)
        val before = storage.snapshot
        val result = module.applyFeedback(
            ANS100SequenceFeedbackTrainer.Feedback(
                prompt = "кот",
                correctedContinuation = "ест рыбу",
                source = ANS100SequenceFeedbackTrainer.Source.MODEL_GENERATED,
            ),
        )
        assertNull(result)
        assertEquals(before, storage.snapshot)
    }
}
