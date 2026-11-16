package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100NeuralInputFrameTest {
    @Test
    fun frameIsDeterministicAndRetainsLexicalFeatureProvenance() {
        val adapter = ANS100NeuralInputAdapter()
        val first = adapter.encode("turn-1", "Найди информацию про нейросети")
        val second = adapter.encode("turn-1", "Найди информацию про нейросети")

        assertEquals(first, second)
        assertTrue(first.features.isNotEmpty())
        assertTrue(first.features.all {
            it.source == ANS100NeuralInputFrame.Feature.Source.USER_TEXT_LEXICAL
        })
        assertTrue(first.features.any { it.id == "token:нейросети" })
        assertFalse(first.features.any { it.id == "token:про" })
    }

    @Test
    fun frameContainsNoPreselectedDecisionOrCandidateSignals() {
        val frame = ANS100NeuralInputAdapter().encode("turn-2", "почему нейросети обучаются")
        val ids = frame.features.map { it.id }

        assertFalse(ids.any { it.contains("intent") })
        assertFalse(ids.any { it.contains("answer") })
        assertFalse(ids.any { it.contains("candidate") })
        assertFalse(ids.any { it.contains("support") || it.contains("conflict") })
    }

    @Test
    fun frameIsBoundedAndKeepsTurnIdentity() {
        val frame = ANS100NeuralInputAdapter().encode("turn-3", "нейросеть обучение")
        assertEquals("turn-3", frame.turnId)
        assertTrue(frame.features.size <= ANS100AssociativeFeatureEncoder.MAX_FEATURES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankTurnId() {
        ANS100NeuralInputAdapter().encode(" ", "нейросеть")
    }
}
