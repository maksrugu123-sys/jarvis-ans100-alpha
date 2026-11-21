package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kEvidenceEncoderTest {
    private fun bundle(turnId: String = "evidence-encoder-test"): TypedEvidenceBundle = TypedEvidenceBundle(
        turnId = turnId,
        records = listOf(
            TypedEvidenceRecord(
                recordId = "user-message",
                turnId = turnId,
                feature = EvidenceFeature.USER_MESSAGE_TEXT,
                source = EvidenceSourceKind.USER_MESSAGE,
                status = EvidenceStatus.USER_PROVIDED,
                value = EvidenceValue.Text("Объясни принцип работы"),
                activation = 1.0,
                provenanceRef = "turn:user-message",
            ),
            TypedEvidenceRecord(
                recordId = "intent-hint",
                turnId = turnId,
                feature = EvidenceFeature.CORE_INTENT_HINT,
                source = EvidenceSourceKind.CORE_UNDERSTANDING,
                status = EvidenceStatus.HEURISTIC,
                value = EvidenceValue.Category("explain"),
                activation = 0.7,
                provenanceRef = "turn:core.intent",
            ),
            TypedEvidenceRecord(
                recordId = "clarification-flag",
                turnId = turnId,
                feature = EvidenceFeature.CORE_REQUIRES_CLARIFICATION_HINT,
                source = EvidenceSourceKind.CORE_REASONING_PLAN,
                status = EvidenceStatus.HEURISTIC,
                value = EvidenceValue.BooleanValue(false),
                activation = 0.5,
                provenanceRef = "turn:core.reasoningPlan.requiresClarification",
            ),
        ),
    )

    @Test
    fun mapsTypedSourcesToTheirDedicated250kPopulations() {
        val encoded = AndroidJnvM250kEvidenceEncoder().encode(bundle(), inputTimeNs = 10L)
        val byId = encoded.events.associateBy { event ->
            encoded.recordToNeuronId.entries.single { it.value == event.targetNeuronId }.key
        }

        assertTrue(byId.getValue("user-message").targetNeuronId in AndroidJnvM250kTopology.POPULATION_RANGES.single {
            it.population == AndroidJnvM250kTopology.Population.PERCEPTION
        })
        assertTrue(byId.getValue("intent-hint").targetNeuronId in AndroidJnvM250kTopology.POPULATION_RANGES.single {
            it.population == AndroidJnvM250kTopology.Population.PATTERN_DETECTION
        })
        assertTrue(byId.getValue("clarification-flag").targetNeuronId in AndroidJnvM250kTopology.POPULATION_RANGES.single {
            it.population == AndroidJnvM250kTopology.Population.PREDICTION
        })
    }

    @Test
    fun preservesHeuristicTaintAndFalseBooleanPolarity() {
        val encoded = AndroidJnvM250kEvidenceEncoder().encode(bundle(), inputTimeNs = 10L)
        val byId = encoded.events.associateBy { event ->
            encoded.recordToNeuronId.entries.single { it.value == event.targetNeuronId }.key
        }

        assertFalse(byId.getValue("user-message").coreTainted)
        assertTrue(byId.getValue("intent-hint").coreTainted)
        assertTrue(byId.getValue("clarification-flag").coreTainted)
        assertTrue(byId.getValue("intent-hint").strength > 0.0)
        assertTrue(byId.getValue("clarification-flag").strength < 0.0)
        assertEquals(2, encoded.heuristicRecordCount)
        assertEquals(1, encoded.userProvidedRecordCount)
    }

    @Test
    fun encodingIsDeterministicAndDoesNotCollideWithinOneTurn() {
        val encoder = AndroidJnvM250kEvidenceEncoder()
        val first = encoder.encode(bundle(), inputTimeNs = 10L)
        val second = encoder.encode(bundle(), inputTimeNs = 10L)

        assertEquals(first.recordToNeuronId, second.recordToNeuronId)
        assertEquals(first.events, second.events)
        assertEquals(first.events.size, first.events.map { it.targetNeuronId }.distinct().size)
        assertEquals(first.events.size, first.events.map { it.eventId }.distinct().size)
    }

    @Test
    fun stableEvidenceSlotsReuseNeuronAddressesAcrossTurns() {
        val encoder = AndroidJnvM250kEvidenceEncoder()
        val first = encoder.encode(bundle("turn-one"), inputTimeNs = 10L)
        val second = encoder.encode(bundle("turn-two"), inputTimeNs = 10L)

        assertEquals(first.recordToNeuronId, second.recordToNeuronId)
        assertFalse(first.events.map { it.eventId } == second.events.map { it.eventId })
        assertEquals(first.recordLabels, second.recordLabels)
        assertEquals(
            first.recordProvenance.filterKeys { it !in first.conceptRecordIds },
            second.recordProvenance.filterKeys { it !in second.conceptRecordIds },
        )
        assertTrue(first.conceptRecordIds.isNotEmpty())
        assertEquals(first.conceptRecordIds, second.conceptRecordIds)
    }

    @Test
    fun extractsStableBoundedLexicalConceptSlotsFromActualMessage() {
        val encoder = AndroidJnvM250kEvidenceEncoder()
        val first = encoder.encode(bundle("lexical-one"), inputTimeNs = 10L)
        val second = encoder.encode(bundle("lexical-two"), inputTimeNs = 10L)

        assertTrue(first.conceptRecordIds.isNotEmpty())
        assertEquals(first.conceptRecordIds, second.conceptRecordIds)
        assertEquals(
            first.conceptRecordIds.map { first.recordToNeuronId.getValue(it) },
            second.conceptRecordIds.map { second.recordToNeuronId.getValue(it) },
        )
        assertTrue(first.conceptRecordIds.all { first.recordLabels.getValue(it) in setOf("объясни", "принцип", "работы") })
        assertTrue(first.conceptRecordIds.all {
            first.events.single { event -> event.targetNeuronId == first.recordToNeuronId.getValue(it) }.strength >= 1.0
        })
        assertTrue(first.conceptRecordIds.size <= AndroidJnvM250kEvidenceEncoder.MAX_LEXICAL_CONCEPTS)
        assertTrue(first.conceptRecordIds.all {
            first.recordToNeuronId.getValue(it) in AndroidJnvM250kTopology.POPULATION_RANGES.single {
                range -> range.population == AndroidJnvM250kTopology.Population.PERCEPTION
            }
        })
    }
}
