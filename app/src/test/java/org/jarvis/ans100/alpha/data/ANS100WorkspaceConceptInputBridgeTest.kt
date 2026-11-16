package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100WorkspaceConceptInputBridgeTest {
    private fun evidenceBatch(turnId: String = "turn-1", timestampNs: Long = 10L) =
        AndroidHybridEvidenceInputAdapter.createBatch(
            TypedEvidenceBundle(
                turnId = turnId,
                records = listOf(
                    TypedEvidenceRecord(
                        recordId = "user.message.text:0",
                        turnId = turnId,
                        feature = EvidenceFeature.USER_MESSAGE_TEXT,
                        source = EvidenceSourceKind.USER_MESSAGE,
                        status = EvidenceStatus.USER_PROVIDED,
                        value = EvidenceValue.Text("Собака рядом"),
                        activation = 1.0,
                        provenanceRef = "turn:$turnId:user_message",
                    ),
                ),
            ),
            timestampNs,
        )

    private fun memory(
        id: String,
        content: String,
        category: String,
        approved: Boolean = true,
    ) = LocalWorkspaceMemory(
        id = id,
        content = content,
        category = category,
        updatedAt = "2026-11-16T00:00:00Z",
        userApproved = approved,
    )

    @Test
    fun onlyApprovedDedicatedConceptEntriesBecomeNeuralInputPulses() {
        val bridge = ANS100WorkspaceConceptInputBridge()
        val result = bridge.merge(
            evidenceBatch(),
            "Собака рядом",
            listOf(
                memory("approved-concept", "Собака", "Понятие"),
                memory("ordinary-note", "Собака любит гулять", "Заметка"),
                memory("unapproved-concept", "Кот", "concept", approved = false),
            ),
        )

        assertEquals(1, result.eligibleConceptCount)
        assertEquals(1, result.activeConceptCount)
        assertEquals(1, result.batch.conceptTrace.size)
        assertEquals(2, result.batch.events.size)
        assertEquals(
            ANS100ConceptMemory.NEURAL_INPUT_START,
            result.batch.conceptTrace.single().neuronId,
        )
        assertTrue(result.batch.conceptTrace.single().provenanceRef.startsWith("turn:turn-1:concept:"))
    }

    @Test
    fun ordinaryWorkspaceNotesDoNotActivateConceptNeurons() {
        val result = ANS100WorkspaceConceptInputBridge().merge(
            evidenceBatch(),
            "Собака рядом",
            listOf(memory("note", "Собака", "Заметка")),
        )

        assertEquals(0, result.eligibleConceptCount)
        assertEquals(0, result.activeConceptCount)
        assertTrue(result.batch.conceptTrace.isEmpty())
        assertEquals(1, result.batch.events.size)
    }

    @Test
    fun conceptsDoNotActivateWhenLexicalAliasIsNotPresent() {
        val result = ANS100WorkspaceConceptInputBridge().merge(
            evidenceBatch(),
            "Щенок рядом",
            listOf(memory("concept", "Собака", "concept")),
        )

        assertEquals(1, result.eligibleConceptCount)
        assertEquals(0, result.activeConceptCount)
        assertTrue(result.batch.conceptTrace.isEmpty())
    }

    @Test
    fun overlongMessageFallsBackWithoutTruncation() {
        val result = ANS100WorkspaceConceptInputBridge().merge(
            evidenceBatch(),
            "x".repeat(ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS + 1),
            listOf(memory("concept", "Собака", "concept")),
        )

        assertEquals(0, result.eligibleConceptCount)
        assertEquals(0, result.activeConceptCount)
        assertTrue(result.batch.conceptTrace.isEmpty())
        assertEquals(1, result.batch.events.size)
    }

    @Test
    fun multiwordConceptRequiresExactAdjacentPhrase() {
        val bridge = ANS100WorkspaceConceptInputBridge()
        val memories = listOf(memory("solar", "Солнечная система", "concept"))
        val exact = bridge.merge(evidenceBatch(), "Солнечная система", memories)
        val reversed = bridge.merge(evidenceBatch("turn-2", 20L), "Система солнечная", memories)

        assertEquals(1, exact.activeConceptCount)
        assertEquals(0, reversed.activeConceptCount)
    }
}
