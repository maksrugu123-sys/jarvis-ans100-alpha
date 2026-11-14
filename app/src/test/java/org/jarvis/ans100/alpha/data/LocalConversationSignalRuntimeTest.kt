package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConversationSignalRuntimeTest {
    private class MemoryStorage(var value: String? = null, var allowWrite: Boolean = true) :
        ConversationSignalStorage {
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
    }

    @Test
    fun textFeaturesDriveBothNeuronsAndRespectTheEventBudget() {
        val storage = MemoryStorage()
        val result = LocalConversationSignalRuntime(storage).analyze("Привет?")

        assertEquals("JARVIS_ANS100_CONVERSATION_SIGNAL_V1", result.runtimeVersion)
        assertEquals(4, result.processedEvents)
        assertEquals(2, result.processedBatches)
        assertEquals(2, result.spikeCount)
        assertTrue(result.attentionGateActive)
        assertEquals(1.0 / 12.0, result.tokenVolume, 1e-12)
        assertEquals(1.0, result.lexicalDiversity, 1e-12)
        assertEquals(0.35, result.questionMarker, 1e-12)
        assertEquals(0.2, result.integratorAdaptation, 1e-12)
        assertEquals(1.1, result.effectiveThreshold, 1e-12)
        assertEquals(10_000_001L, result.modelTimeNs)
        assertTrue(result.statePersisted)
        assertTrue(storage.value!!.startsWith("JARVIS_ANS100_ANDROID_SIGNAL_V1|1|"))
    }

    @Test
    fun emptyFeatureSetDoesNotAdvanceClockOrWriteCheckpoint() {
        val storage = MemoryStorage()
        val result = LocalConversationSignalRuntime(storage).analyze("ok")

        assertEquals(0, result.processedEvents)
        assertEquals(0, result.processedBatches)
        assertEquals(0, result.spikeCount)
        assertFalse(result.attentionGateActive)
        assertEquals(0L, result.modelTimeNs)
        assertFalse(result.statePersisted)
        assertEquals(null, storage.value)
    }

    @Test
    fun adaptiveStateSurvivesRuntimeRecreation() {
        val storage = MemoryStorage()
        val first = LocalConversationSignalRuntime(storage).analyze("Привет?")
        val second = LocalConversationSignalRuntime(storage).analyze("Привет?")

        assertTrue(first.attentionGateActive)
        assertTrue(second.attentionGateActive)
        assertEquals(2L, second.checkpointVersion.toLong())
        assertEquals(20_000_002L, second.modelTimeNs)
        assertTrue(second.integratorAdaptation > first.integratorAdaptation)
        assertTrue(second.integratorAdaptation < 0.4)
    }

    @Test
    fun failedCheckpointDoesNotPublishCandidateState() {
        val storage = MemoryStorage(allowWrite = false)
        var rejected = false
        try {
            LocalConversationSignalRuntime(storage).analyze("Привет?")
        } catch (_: LocalConversationSignalException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(null, storage.value)
    }


    @Test
    fun v2SignalContextAcceptsTenNeuronMetricsAndRejectsOutOfBudgetCounts() {
        val context = ConversationSignalContext(
            runtimeVersion = "JARVIS_ANS100_CONVERSATION_SIGNAL_V2",
            processedEvents = 15,
            processedBatches = 10,
            spikeCount = 10,
            attentionGateActive = true,
            tokenVolume = 0.4,
            lexicalDiversity = 1.0,
            questionMarker = 0.35,
            integratorAdaptation = 0.2,
            effectiveThreshold = 1.6,
            statePersisted = true,
            activeNeuronCount = 10,
            networkNeuronCount = 10,
            punctuationDensity = 0.5,
            sentenceDensity = 0.25,
            uppercaseRatio = 0.4,
        )
        assertTrue(context.toModelContext().contains("Сеть: 10 нейронов; активных: 10."))

        var rejected = false
        try {
            context.copy(processedEvents = 65)
        } catch (_: RuntimeBridgeException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun malformedCheckpointIsRejectedInsteadOfSilentlyReset() {
        val storage = MemoryStorage("corrupted")
        var rejected = false
        try {
            LocalConversationSignalRuntime(storage).analyze("Привет?")
        } catch (_: LocalConversationSignalException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals("corrupted", storage.value)
    }

    @Test
    fun ordinaryPunctuationAloneDoesNotActivateTheAttentionGate() {
        val storage = MemoryStorage()
        val result = LocalConversationSignalRuntime(storage).analyze("?")

        assertEquals(1, result.processedEvents)
        assertEquals(1, result.processedBatches)
        assertEquals(0, result.spikeCount)
        assertFalse(result.attentionGateActive)
        assertEquals(0.0, result.integratorAdaptation, 1e-12)
        assertEquals(0.35, result.questionMarker, 1e-12)
    }
}
