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
    fun textFeaturesFlowThroughTenNeuronsWithinBoundedEventBudget() {
        val storage = MemoryStorage()
        val result = LocalConversationSignalRuntime(storage).analyze(
            "Как подключить ANS-100 к JARVIS?",
        )

        assertEquals("JARVIS_ANS100_CONVERSATION_SIGNAL_V2", result.runtimeVersion)
        assertEquals(10, result.networkNeuronCount)
        assertEquals(10, result.activeNeuronCount)
        assertEquals(15, result.processedEvents)
        assertEquals(10, result.processedBatches)
        assertEquals(10, result.spikeCount)
        assertTrue(result.attentionGateActive)
        assertEquals(5.0 / 12.0, result.tokenVolume, 1e-12)
        assertEquals(1.0, result.lexicalDiversity, 1e-12)
        assertEquals(0.35, result.questionMarker, 1e-12)
        assertTrue(result.punctuationDensity > 0.0)
        assertTrue(result.sentenceDensity > 0.0)
        assertTrue(result.uppercaseRatio > 0.0)
        assertEquals(0.2, result.integratorAdaptation, 1e-12)
        assertEquals(1.6, result.effectiveThreshold, 1e-12)
        assertEquals(10_000_003L, result.modelTimeNs)
        assertTrue(result.statePersisted)
        assertTrue(storage.value!!.startsWith("JARVIS_ANS100_ANDROID_SIGNAL_V2|1|"))
    }

    @Test
    fun emptyFeatureSetDoesNotAdvanceClockOrWriteCheckpoint() {
        val storage = MemoryStorage()
        val result = LocalConversationSignalRuntime(storage).analyze("hi")

        assertEquals(0, result.processedEvents)
        assertEquals(0, result.processedBatches)
        assertEquals(0, result.spikeCount)
        assertEquals(0, result.activeNeuronCount)
        assertEquals(10, result.networkNeuronCount)
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
        assertEquals(2L, second.checkpointVersion)
        assertEquals(20_000_006L, second.modelTimeNs)
        assertTrue(second.integratorAdaptation > first.integratorAdaptation)
        assertTrue(second.integratorAdaptation < 0.4)
        assertEquals(10, second.networkNeuronCount)
    }

    @Test
    fun legacyTwoNeuronCheckpointMigratesAndKeepsAdaptiveState() {
        val legacy = listOf(
            "JARVIS_ANS100_ANDROID_SIGNAL_V1",
            "1", "10000001",
            "0.0", "0.2", "10000000", "12000000", "1",
            "0.0", "0.0", "10000001", "12000001", "1",
        ).joinToString("|")
        val storage = MemoryStorage(value = legacy)

        val result = LocalConversationSignalRuntime(storage).analyze("Привет?")

        assertEquals(10, result.networkNeuronCount)
        assertEquals(2L, result.checkpointVersion)
        assertTrue(result.integratorAdaptation > 0.2)
        assertTrue(result.attentionGateActive)
        assertTrue(storage.value!!.startsWith("JARVIS_ANS100_ANDROID_SIGNAL_V2|2|"))
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
    fun punctuationOnlyInputActivatesSensorsButNotTheOutputGate() {
        val storage = MemoryStorage()
        val result = LocalConversationSignalRuntime(storage).analyze("?")

        assertEquals(7, result.processedEvents)
        assertEquals(5, result.processedBatches)
        assertEquals(4, result.spikeCount)
        assertEquals(4, result.activeNeuronCount)
        assertFalse(result.attentionGateActive)
        assertEquals(0.0, result.integratorAdaptation, 1e-12)
        assertEquals(0.35, result.questionMarker, 1e-12)
    }

    @Test
    fun sharedPythonAndAndroidGoldenVectorsMatch() {
        val stream = javaClass.classLoader?.getResourceAsStream("ans100/conversation-v2.tsv")
            ?: throw AssertionError("shared ANS-100 parity fixture is missing")
        val rows = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        rows.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val fields = line.split('\t')
            assertEquals(15, fields.size)
            val result = LocalConversationSignalRuntime(MemoryStorage()).analyze(fields[1])
            assertEquals(10, result.networkNeuronCount)
            assertEquals(fields[2].toDouble(), result.tokenVolume, 1e-9)
            assertEquals(fields[3].toDouble(), result.lexicalDiversity, 1e-9)
            assertEquals(fields[4].toDouble(), result.questionMarker, 1e-9)
            assertEquals(fields[5].toDouble(), result.punctuationDensity, 1e-9)
            assertEquals(fields[6].toDouble(), result.sentenceDensity, 1e-9)
            assertEquals(fields[7].toDouble(), result.uppercaseRatio, 1e-9)
            assertEquals(fields[8].toInt(), result.processedEvents)
            assertEquals(fields[9].toInt(), result.processedBatches)
            assertEquals(fields[10].toInt(), result.spikeCount)
            assertEquals(fields[11].toBoolean(), result.attentionGateActive)
            assertEquals(fields[12].toDouble(), result.integratorAdaptation, 1e-9)
            assertEquals(fields[13].toDouble(), result.effectiveThreshold, 1e-9)
            assertEquals(fields[14].toLong(), result.modelTimeNs)
        }
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
}
