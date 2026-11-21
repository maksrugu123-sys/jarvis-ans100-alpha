package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ANS100HypothesisRevisionLedgerInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun candidate() = ANS100ConceptMemory.Hypothesis(
        id = "causal-chain:a-b-c",
        fromConceptId = "concept.a",
        type = ANS100ConceptMemory.RelationType.CAUSES,
        toConceptId = "concept.c",
        premiseRelationIds = listOf("relation.a-b", "relation.b-c"),
        evidenceRefs = listOf("memory:a-b", "memory:b-c"),
        estimatedStrength = 0.64,
    )

    private fun evidence(ref: String, supports: Boolean) =
        ANS100ConceptMemory.RelationEvidence(
            fromConceptId = "concept.a",
            type = ANS100ConceptMemory.RelationType.CAUSES,
            toConceptId = "concept.c",
            supports = supports,
            source = ANS100ConceptMemory.LearningSource.VERIFIED_TOOL,
            evidenceRef = ref,
        )

    @Test
    fun directSupportAndContradictionSurviveSqliteCheckpoint() {
        val database = "ans100-revision-${System.nanoTime()}.db"
        context.deleteDatabase(database)
        val engine = ANS100HypothesisRevisionEngine()
        engine.observeTurn(
            "turn-support",
            listOf(candidate()),
            listOf(evidence("tool:support", true)),
            true,
        )
        engine.observeTurn(
            "turn-refute",
            listOf(candidate()),
            listOf(evidence("tool:refute", false)),
            true,
        )
        val ledger = ANS100HypothesisRevisionLedger(context, database)
        try {
            assertTrue(ledger.save(engine.exportSnapshot()))
            val snapshot = ledger.load()
            assertTrue(snapshot != null)
            val restored = ANS100HypothesisRevisionEngine()
            restored.restoreSnapshot(checkNotNull(snapshot))
            assertEquals(engine.snapshot(), restored.snapshot())
            assertEquals(
                ANS100HypothesisRevisionEngine.Status.CONFLICTED_DIRECT_EVIDENCE,
                restored.snapshot().single().status,
            )
        } finally {
            ledger.close()
            context.deleteDatabase(database)
        }
    }

    @Test
    fun rejectsSnapshotThatCountsPremiseEvidenceAsDirectEvidence() {
        val database = "ans100-revision-invalid-${System.nanoTime()}.db"
        context.deleteDatabase(database)
        val hypothesis = candidate()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("concept.a|CAUSES|concept.c".toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val invalid = ANS100HypothesisRevisionEngine.Snapshot(
            observedTurnIds = listOf("turn-1"),
            hypotheses = listOf(
                ANS100HypothesisRevisionEngine.PersistedHypothesis(
                    hypothesisId = "revision:$digest",
                    fromConceptId = hypothesis.fromConceptId,
                    relationType = hypothesis.type,
                    toConceptId = hypothesis.toConceptId,
                    premiseRelationIds = hypothesis.premiseRelationIds,
                    premiseEvidenceRefs = hypothesis.evidenceRefs,
                    evidence = listOf(
                        ANS100HypothesisRevisionEngine.EvidenceRecord(
                            evidenceRef = hypothesis.evidenceRefs.first(),
                            source = ANS100ConceptMemory.LearningSource.VERIFIED_TOOL,
                            supports = true,
                        ),
                    ),
                    lastUpdatedTurnId = "turn-1",
                ),
            ),
        )
        val ledger = ANS100HypothesisRevisionLedger(context, database)
        try {
            assertFalse(ledger.save(invalid))
            assertTrue(ledger.load() == null)
        } finally {
            ledger.close()
            context.deleteDatabase(database)
        }
    }
}
