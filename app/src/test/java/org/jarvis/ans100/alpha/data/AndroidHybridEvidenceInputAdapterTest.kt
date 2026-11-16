package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHybridEvidenceInputAdapterTest {
    private fun record(
        feature: EvidenceFeature,
        ordinal: Int = 0,
        source: EvidenceSourceKind,
        status: EvidenceStatus,
        value: EvidenceValue = EvidenceValue.Text("sample"),
        activation: Double = 0.5,
    ) = TypedEvidenceRecord(
        recordId = "${feature.stableId}:$ordinal",
        turnId = "turn-input-1",
        feature = feature,
        source = source,
        status = status,
        value = value,
        activation = activation,
        provenanceRef = "turn:turn-input-1:${feature.stableId}",
    )

    @Test
    fun mapsEveryDeclaredFeatureIntoTheEvidenceInputPopulation() {
        val records = listOf(
            record(EvidenceFeature.USER_MESSAGE_TEXT, source = EvidenceSourceKind.USER_MESSAGE, status = EvidenceStatus.USER_PROVIDED),
            record(EvidenceFeature.MESSAGE_LENGTH_CHARS, source = EvidenceSourceKind.MESSAGE_STRUCTURE, status = EvidenceStatus.OBSERVED_STRUCTURE),
            record(EvidenceFeature.MESSAGE_HAS_QUESTION_MARK, source = EvidenceSourceKind.MESSAGE_STRUCTURE, status = EvidenceStatus.OBSERVED_STRUCTURE, value = EvidenceValue.BooleanValue(true)),
            record(EvidenceFeature.MESSAGE_HAS_EXCLAMATION_MARK, source = EvidenceSourceKind.MESSAGE_STRUCTURE, status = EvidenceStatus.OBSERVED_STRUCTURE, value = EvidenceValue.BooleanValue(false)),
            record(EvidenceFeature.MESSAGE_HAS_LINE_BREAK, source = EvidenceSourceKind.MESSAGE_STRUCTURE, status = EvidenceStatus.OBSERVED_STRUCTURE, value = EvidenceValue.BooleanValue(true)),
            record(EvidenceFeature.CORE_LANGUAGE_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_INTENT_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_INTENT_CONFIDENCE_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC, value = EvidenceValue.DecimalValue(0.7)),
            record(EvidenceFeature.CORE_TASK_KIND_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_KEY_TERM_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_ENTITY_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_NEGATION_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC, value = EvidenceValue.BooleanValue(true)),
            record(EvidenceFeature.CORE_EXPLANATION_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC, value = EvidenceValue.BooleanValue(false)),
            record(EvidenceFeature.CORE_CONTEXT_REFERENCE_STATUS, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_CONTEXT_REFERENCE_METHOD, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_PLAN_GOAL_HINT, source = EvidenceSourceKind.CORE_REASONING_PLAN, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_PLAN_STEP_HINT, source = EvidenceSourceKind.CORE_REASONING_PLAN, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_REQUIRES_CLARIFICATION_HINT, source = EvidenceSourceKind.CORE_REASONING_PLAN, status = EvidenceStatus.HEURISTIC, value = EvidenceValue.BooleanValue(true)),
            record(EvidenceFeature.CORE_CLARIFICATION_QUESTION_HINT, source = EvidenceSourceKind.CORE_REASONING_PLAN, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.CORE_EVIDENCE_NOTE_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC),
            record(EvidenceFeature.APPROVED_MEMORY_REFERENCE, source = EvidenceSourceKind.APPROVED_MEMORY, status = EvidenceStatus.USER_APPROVED_REFERENCE, value = EvidenceValue.Reference("memory-1")),
            record(EvidenceFeature.VERIFIED_TOOL_RESULT, source = EvidenceSourceKind.VERIFIED_TOOL_RESULT, status = EvidenceStatus.VERIFIED_TOOL_OUTPUT),
        )
        val ids = records.map(AndroidHybridEvidenceInputAdapter::neuronIdFor)

        assertEquals(22, ids.distinct().size)
        assertTrue(ids.all { it in 0 until 1_200 })
    }


    @Test
    fun conceptInputsCanShareTheHybridBatchWithoutLosingTrace() {
        val base = AndroidHybridEvidenceInputAdapter.createBatch(
            TypedEvidenceBundle(
                turnId = "turn-input-1",
                records = listOf(record(
                    EvidenceFeature.USER_MESSAGE_TEXT,
                    source = EvidenceSourceKind.USER_MESSAGE,
                    status = EvidenceStatus.USER_PROVIDED,
                    value = EvidenceValue.Text("Собака"),
                )),
            ),
            40L,
        )
        val memory = ANS100ConceptMemory()
        assertTrue(memory.learnConcept(
            ANS100ConceptMemory.Concept("dog", "Собака"),
            ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
        ))
        val frame = ANS100NeuralInputAdapter().encode("turn-input-1", "Собака")
        val activations = ANS100ConceptActivationAdapter().activate(frame, memory)
        val concepts = ANS100ConceptNeuralInputAdapter().createBatch(activations, memory, 40L)
        val merged = ANS100ConceptNeuralInputAdapter().merge(base, concepts)

        assertEquals(2, merged.events.size)
        assertEquals(1, merged.trace.size)
        assertEquals(1, merged.conceptTrace.size)
        assertEquals("dog", merged.conceptTrace.single().conceptId)
        assertEquals(ANS100ConceptMemory.NEURAL_INPUT_START, merged.conceptTrace.single().neuronId)
        assertEquals(
            merged.events.map { it.targetNeuronId },
            merged.trace.map { it.neuronId } + merged.conceptTrace.map { it.neuronId },
        )
    }

    @Test
    fun mapsSixCandidateSupportConflictFeaturesToReservedInputIds() {
        ANS100CandidateSlotContract.Slot.values().forEach { slot ->
            ANS100CandidateSlotContract.Polarity.values().forEach { polarity ->
                val feature = ANS100CandidateSlotContract.evidenceFeature(slot, polarity)
                val actual = AndroidHybridEvidenceInputAdapter.neuronIdFor(
                    record(
                        feature,
                        source = EvidenceSourceKind.CORE_REASONING_PLAN,
                        status = EvidenceStatus.HEURISTIC,
                        value = EvidenceValue.BooleanValue(true),
                        activation = 1.0,
                    ),
                )
                assertEquals(
                    ANS100CandidateSlotContract.inputNeuronId(slot, polarity),
                    actual,
                )
                assertTrue(actual in ANS100CandidateSlotContract.INPUT_START until
                    ANS100CandidateSlotContract.INPUT_START + ANS100CandidateSlotContract.INPUT_SIGNAL_COUNT)
            }
        }
    }

    @Test
    fun booleanValuesUseSeparateStableFalseAndTrueSlots() {
        val falseRecord = record(
            EvidenceFeature.MESSAGE_HAS_QUESTION_MARK,
            source = EvidenceSourceKind.MESSAGE_STRUCTURE,
            status = EvidenceStatus.OBSERVED_STRUCTURE,
            value = EvidenceValue.BooleanValue(false),
        )
        val trueRecord = falseRecord.copy(value = EvidenceValue.BooleanValue(true))

        assertNotEquals(
            AndroidHybridEvidenceInputAdapter.neuronIdFor(falseRecord),
            AndroidHybridEvidenceInputAdapter.neuronIdFor(trueRecord),
        )
    }

    @Test
    fun createsDeterministicBoundedPresencePulsesAndPreservesProvenance() {
        val bundle = TypedEvidenceBundle(
            turnId = "turn-input-1",
            records = listOf(
                record(EvidenceFeature.USER_MESSAGE_TEXT, source = EvidenceSourceKind.USER_MESSAGE, status = EvidenceStatus.USER_PROVIDED, value = EvidenceValue.Text("Привет")),
                record(EvidenceFeature.CORE_INTENT_HINT, source = EvidenceSourceKind.CORE_UNDERSTANDING, status = EvidenceStatus.HEURISTIC, value = EvidenceValue.Category("greeting")),
            ),
        )

        val first = AndroidHybridEvidenceInputAdapter.createBatch(bundle, 10L)
        val second = AndroidHybridEvidenceInputAdapter.createBatch(bundle, 10L)

        assertEquals(first, second)
        assertEquals("turn-input-1", first.turnId)
        assertEquals(2, first.events.size)
        assertTrue(first.events.all { it.timeNs == 10L && it.strength == 1.0 })
        assertEquals(
            listOf(false, true),
            first.events.map { it.coreTainted },
        )
        assertEquals(first.events.map { it.targetNeuronId }, first.trace.map { it.neuronId })
        assertEquals(EvidenceStatus.USER_PROVIDED, first.trace.first().status)
        assertEquals("turn:turn-input-1:user.message.text", first.trace.first().provenanceRef)

        val secondTurn = TypedEvidenceBundle(
            turnId = "turn-input-2",
            records = bundle.records.map { it.copy(turnId = "turn-input-2") },
        )
        val secondTurnBatch = AndroidHybridEvidenceInputAdapter.createBatch(secondTurn, 10L)
        assertNotEquals(first.events.first().eventId, secondTurnBatch.events.first().eventId)
    }

    @Test
    fun zeroActivationIsNotScheduledAndMissingEvidenceIsNotInvented() {
        val bundle = TypedEvidenceBundle(
            turnId = "turn-input-1",
            records = listOf(
                record(
                    EvidenceFeature.CORE_INTENT_HINT,
                    source = EvidenceSourceKind.CORE_UNDERSTANDING,
                    status = EvidenceStatus.HEURISTIC,
                    activation = 0.0,
                ),
            ),
        )

        val batch = AndroidHybridEvidenceInputAdapter.createBatch(bundle, 20L)

        assertTrue(batch.events.isEmpty())
        assertTrue(batch.trace.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHeuristicFeatureRelabelledAsVerifiedToolOutput() {
        val invalid = record(
            EvidenceFeature.CORE_INTENT_HINT,
            source = EvidenceSourceKind.VERIFIED_TOOL_RESULT,
            status = EvidenceStatus.VERIFIED_TOOL_OUTPUT,
        )
        AndroidHybridEvidenceInputAdapter.neuronIdFor(invalid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFeatureOrdinalOutsideItsReservedSlots() {
        val invalid = record(
            EvidenceFeature.CORE_KEY_TERM_HINT,
            ordinal = 24,
            source = EvidenceSourceKind.CORE_UNDERSTANDING,
            status = EvidenceStatus.HEURISTIC,
        )
        AndroidHybridEvidenceInputAdapter.neuronIdFor(invalid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMoreThanSixtyFourEvidenceRecords() {
        val records = (0..64).map { index ->
            record(
                EvidenceFeature.CORE_KEY_TERM_HINT,
                ordinal = index % 24,
                source = EvidenceSourceKind.CORE_UNDERSTANDING,
                status = EvidenceStatus.HEURISTIC,
            ).copy(recordId = "key-term:$index")
        }
        val bundle = TypedEvidenceBundle(turnId = "turn-input-1", records = records)
        AndroidHybridEvidenceInputAdapter.createBatch(bundle, 30L)
    }
}
