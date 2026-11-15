package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedEvidenceTest {
    private val understanding = LocalUnderstandingPipeline().analyze("Почему проект не запускается?")

    @Test
    fun preservesUserTextAndMarksCoreInterpretationAsHeuristic() {
        val bundle = TypedEvidenceEncoder().encode(
            TypedEvidenceInput("turn-1", "Почему проект не запускается?", understanding),
        )

        val raw = bundle.records.single { it.feature == EvidenceFeature.USER_MESSAGE_TEXT }
        assertEquals(EvidenceSourceKind.USER_MESSAGE, raw.source)
        assertEquals(EvidenceStatus.USER_PROVIDED, raw.status)
        assertEquals("Почему проект не запускается?", (raw.value as EvidenceValue.Text).value)

        val intent = bundle.records.single { it.feature == EvidenceFeature.CORE_INTENT_HINT }
        assertEquals(EvidenceSourceKind.CORE_UNDERSTANDING, intent.source)
        assertEquals(EvidenceStatus.HEURISTIC, intent.status)
        assertTrue(bundle.heuristicRecordCount > 0)
    }

    @Test
    fun structuralFeaturesDescribeFormWithoutClaimingMeaning() {
        val bundle = TypedEvidenceEncoder().encode(
            TypedEvidenceInput("turn-2", "Почему?\nНет!", understanding),
        )

        assertEquals(
            true,
            (bundle.records.single { it.feature == EvidenceFeature.MESSAGE_HAS_QUESTION_MARK }.value
                as EvidenceValue.BooleanValue).value,
        )
        assertEquals(
            true,
            (bundle.records.single { it.feature == EvidenceFeature.MESSAGE_HAS_EXCLAMATION_MARK }.value
                as EvidenceValue.BooleanValue).value,
        )
        assertEquals(
            true,
            (bundle.records.single { it.feature == EvidenceFeature.MESSAGE_HAS_LINE_BREAK }.value
                as EvidenceValue.BooleanValue).value,
        )
        assertEquals(
            EvidenceStatus.OBSERVED_STRUCTURE,
            bundle.records.single { it.feature == EvidenceFeature.MESSAGE_HAS_QUESTION_MARK }.status,
        )
    }

    @Test
    fun doesNotPromoteHeuristicConfidenceToVerifiedEvidence() {
        val bundle = TypedEvidenceEncoder().encode(
            TypedEvidenceInput("turn-3", "Что это?", understanding),
        )
        assertTrue(bundle.records.filter { it.source == EvidenceSourceKind.CORE_UNDERSTANDING ||
            it.source == EvidenceSourceKind.CORE_REASONING_PLAN
        }.all { it.status == EvidenceStatus.HEURISTIC })
        assertEquals(0, bundle.verifiedToolRecordCount)
    }

    @Test
    fun emitsStableFeatureIdsAndDeterministicRecordOrder() {
        val input = TypedEvidenceInput("turn-4", "Сравни A и B", understanding)
        val encoder = TypedEvidenceEncoder()
        val first = encoder.encode(input)
        val second = encoder.encode(input)

        assertEquals(first.records, second.records)
        assertEquals(
            EvidenceFeature.CORE_INTENT_HINT.stableId,
            first.records.single { it.feature == EvidenceFeature.CORE_INTENT_HINT }.feature.stableId,
        )
        assertEquals(first.records.size, first.records.map { it.recordId }.distinct().size)
    }

    @Test
    fun boundsCoreHintsAndTotalEvidence() {
        val longText = "термин ".repeat(250)
        val expanded = LocalUnderstandingPipeline().analyze(longText)
        val bundle = TypedEvidenceEncoder().encode(
            TypedEvidenceInput("turn-5", "Запрос", expanded),
        )
        assertTrue(bundle.records.size <= TypedEvidenceBundle.MAX_RECORDS_PER_TURN)
        assertTrue(bundle.records.all { it.turnId == "turn-5" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedRawMessageInsteadOfSilentlyTruncating() {
        TypedEvidenceEncoder().encode(
            TypedEvidenceInput(
                "turn-6",
                "x".repeat(TypedEvidenceEncoder.MAX_RAW_MESSAGE_CHARS + 1),
                understanding,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun bundleRejectsCrossTurnEvidence() {
        val record = TypedEvidenceRecord(
            recordId = "message.text:0",
            turnId = "other-turn",
            feature = EvidenceFeature.USER_MESSAGE_TEXT,
            source = EvidenceSourceKind.USER_MESSAGE,
            status = EvidenceStatus.USER_PROVIDED,
            value = EvidenceValue.Text("hello"),
            activation = 1.0,
            provenanceRef = "turn:other-turn:user_message",
        )
        TypedEvidenceBundle(turnId = "turn-7", records = listOf(record))
    }

    @Test
    fun approvedMemoryAndVerifiedToolEvidenceKeepDistinctTrustLabels() {
        val bundle = TypedEvidenceEncoder().encode(
            TypedEvidenceInput(
                turnId = "turn-8",
                rawUserMessage = "Проверь результат",
                understanding = understanding,
                approvedMemory = listOf(ApprovedMemoryEvidence("memory-17", "project-note")),
                verifiedToolResults = listOf(
                    VerifiedToolEvidence("web-search", "result-29", "Найден официальный источник"),
                ),
            ),
        )

        val memory = bundle.records.single { it.feature == EvidenceFeature.APPROVED_MEMORY_REFERENCE }
        assertEquals(EvidenceStatus.USER_APPROVED_REFERENCE, memory.status)
        assertEquals(EvidenceValueKind.REFERENCE, memory.value.kind)

        val tool = bundle.records.single { it.feature == EvidenceFeature.VERIFIED_TOOL_RESULT }
        assertEquals(EvidenceStatus.VERIFIED_TOOL_OUTPUT, tool.status)
        assertEquals(EvidenceSourceKind.VERIFIED_TOOL_RESULT, tool.source)
        assertEquals(1, bundle.verifiedToolRecordCount)
        assertFalse(memory.recordId == tool.recordId)
    }
}
