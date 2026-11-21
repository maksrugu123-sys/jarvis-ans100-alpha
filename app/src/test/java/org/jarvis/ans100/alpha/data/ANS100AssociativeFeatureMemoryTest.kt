package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100AssociativeFeatureMemoryTest {
    private val encoder = ANS100AssociativeFeatureEncoder()

    @Test
    fun extractsStableBoundedRussianLexicalFeatures() {
        val first = encoder.encode("Найди информацию про нейросети")
        val second = encoder.encode("Найди информацию про нейросети")

        assertEquals(first, second)
        assertTrue(first.any { it.id == "token:нейросети" })
        assertTrue(first.any { it.kind == ANS100LexicalFeature.Kind.BIGRAM })
        assertTrue(first.size <= ANS100AssociativeFeatureEncoder.MAX_FEATURES)
        assertFalse(first.any { it.id == "token:про" })
    }

    @Test
    fun emptyOrStopWordOnlyMessagesProduceNoFeatures() {
        assertTrue(encoder.encode("").isEmpty())
        assertTrue(encoder.encode("и но или что").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedInput() {
        encoder.encode("x".repeat(ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS + 1))
    }

    @Test
    fun explicitFeedbackLearnsAssociationsThatCanBeRecalledFromSharedFeature() {
        val memory = ANS100AssociativeFeatureMemory()
        val learned = encoder.encode("нейросеть обучение")
        val query = encoder.encode("нейросеть")

        val changed = memory.learn(
            turnId = "turn-1",
            features = learned,
            reward = 1.0,
            source = ANS100AssociativeFeatureMemory.FeedbackSource.EXPLICIT_USER,
        )

        assertTrue(changed > 0)
        val recalled = memory.recall(query)
        assertTrue(recalled.any { it.featureId == "token:обучение" })
    }

    @Test
    fun modelGeneratedAndImplicitFeedbackCannotTeachAssociations() {
        val memory = ANS100AssociativeFeatureMemory()
        val features = encoder.encode("нейросеть обучение")

        for (source in listOf(
            ANS100AssociativeFeatureMemory.FeedbackSource.MODEL_GENERATED,
            ANS100AssociativeFeatureMemory.FeedbackSource.IMPLICIT_BEHAVIOR,
        )) {
            assertEquals(0, memory.learn("turn-2", features, 1.0, source))
        }
        assertTrue(memory.snapshot().isEmpty())
    }

    @Test
    fun negativeExplicitFeedbackWeakensExistingAssociation() {
        val memory = ANS100AssociativeFeatureMemory()
        val features = encoder.encode("нейросеть обучение")
        memory.learn(
            "turn-3", features, 1.0,
            ANS100AssociativeFeatureMemory.FeedbackSource.EXPLICIT_USER,
        )
        val before = memory.snapshot().single { it.leftFeatureId == "token:нейросеть" && it.rightFeatureId == "token:обучение" }.weight

        memory.learn(
            "turn-4", features, -1.0,
            ANS100AssociativeFeatureMemory.FeedbackSource.EXPLICIT_USER,
        )
        val after = memory.snapshot().single { it.leftFeatureId == "token:нейросеть" && it.rightFeatureId == "token:обучение" }.weight
        assertTrue(after < before)
    }

    @Test
    fun pairCountAndWeightsRemainBounded() {
        val memory = ANS100AssociativeFeatureMemory(maximumPairs = 2)
        val features = encoder.encode("альфа бета гамма дельта")
        memory.learn(
            "turn-5", features, 1.0,
            ANS100AssociativeFeatureMemory.FeedbackSource.VERIFIED_EXTERNAL_OUTCOME,
        )

        assertTrue(memory.snapshot().size <= 2)
        assertTrue(memory.snapshot().all { it.weight in
            ANS100AssociativeFeatureMemory.MIN_WEIGHT..ANS100AssociativeFeatureMemory.MAX_WEIGHT
        })
    }
}
