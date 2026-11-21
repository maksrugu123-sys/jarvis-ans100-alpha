package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kEvidenceEncoderTest {
    private fun bundle(
        turnId: String = "evidence-encoder-test",
        message: String = "Объясни принцип работы",
    ): TypedEvidenceBundle = TypedEvidenceBundle(
        turnId = turnId,
        records = listOf(
            TypedEvidenceRecord(
                recordId = "user-message",
                turnId = turnId,
                feature = EvidenceFeature.USER_MESSAGE_TEXT,
                source = EvidenceSourceKind.USER_MESSAGE,
                status = EvidenceStatus.USER_PROVIDED,
                value = EvidenceValue.Text(message),
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

    @Test
    fun lexicalNeuronAddressesDoNotDependOnWordOrder() {
        val encoder = AndroidJnvM250kEvidenceEncoder()
        val first = encoder.encode(bundle("word-order-a", "объясни принцип работы"), inputTimeNs = 10L)
        val second = encoder.encode(bundle("word-order-b", "работы объясни принцип"), inputTimeNs = 10L)

        assertEquals(first.conceptRecordIds, second.conceptRecordIds)
        assertEquals(
            first.conceptRecordIds.associateWith { first.recordToNeuronId.getValue(it) },
            second.conceptRecordIds.associateWith { second.recordToNeuronId.getValue(it) },
        )
    }

    @Test
    fun learnedConceptPrototypeIsRecalledIntoReservedMemoryRangeAsTaintedSignal() {
        val encoder = AndroidJnvM250kEvidenceEncoder()
        val base = encoder.encode(bundle(), inputTimeNs = 10L)
        val prototype = ANS100NeuralHypothesisEngine.ConceptPrototype(
            prototypeId = "jnvm250k-prototype:" + "c".repeat(32),
            memberConceptIds = listOf("lex-token:" + "a".repeat(24), "lex-token:" + "b".repeat(24)),
            memberLabels = listOf("альфа", "бета"),
            supportingTurnCount = 2,
            verificationTurnCount = 2,
            confidence = 1.0,
            heuristicEvidence = false,
        )
        val encoded = encoder.appendConceptPrototypes(base, listOf(prototype), recallTimeNs = 10L)
        val event = encoded.events.last()
        val memoryRange = AndroidJnvM250kTopology.POPULATION_RANGES.single {
            it.population == AndroidJnvM250kTopology.Population.MEMORY
        }
        assertTrue(event.targetNeuronId in memoryRange)
        assertTrue(event.targetNeuronId >= memoryRange.firstNeuronId + memoryRange.size / 2)
        assertTrue(event.coreTainted)
        assertEquals(1, encoded.conceptPrototypeEventCount)
        assertEquals(base.conceptRecordIds, encoded.conceptRecordIds)
        assertEquals(base.recordToNeuronId, encoded.recordToNeuronId)
    }

    @Test
    fun marksLexicalInputTruncatedWhenMoreThanTwentyFourTokensArePresent() {
        val message = (1..30).joinToString(" ") { "слово$it" }
        val encoded = AndroidJnvM250kEvidenceEncoder().encode(
            bundle("truncated-lexical", message),
            inputTimeNs = 10L,
        )
        assertEquals(AndroidJnvM250kEvidenceEncoder.MAX_LEXICAL_CONCEPTS, encoded.conceptRecordIds.size)
        assertTrue(encoded.lexicalConceptsTruncated)
    }
}
