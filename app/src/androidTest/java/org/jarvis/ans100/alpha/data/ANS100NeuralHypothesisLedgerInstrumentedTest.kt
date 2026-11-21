package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ANS100NeuralHypothesisLedgerInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun candidate(): AndroidJnvM250kHypothesisReadout.Candidate {
        val first = "lex-token:" + "a".repeat(24)
        val second = "lex-token:" + "b".repeat(24)
        return AndroidJnvM250kHypothesisReadout.Candidate(
            candidateId = "jnvm250k-association:" + digest("$first|$second"),
            firstConceptId = first,
            firstLabel = "alpha",
            secondConceptId = second,
            secondLabel = "beta",
            convergentNeuronIds = listOf(70_123),
            activatedConnectionIds = listOf("edge-alpha", "edge-beta"),
            evidenceRefs = emptyList(),
        )
    }

    @Test
    fun pendingForecastAndOutcomeHistorySurviveSqliteCheckpoint() {
        val database = "ans100-hypothesis-test-${UUID.randomUUID()}.db"
        context.deleteDatabase(database)
        val engine = ANS100NeuralHypothesisEngine()
        val candidate = candidate()
        val active = setOf(candidate.firstConceptId, candidate.secondConceptId)
        repeat(2) { index ->
            engine.observe250kTurn(
                turnId = "ledger-support-$index",
                activeConceptIds = active,
                candidates = listOf(candidate),
                completed = true,
                pairScanComplete = true,
            )
        }
        engine.observe250kTurn(
            turnId = "ledger-prompt",
            activeConceptIds = setOf(candidate.firstConceptId),
            candidates = emptyList(),
            completed = true,
            pairScanComplete = true,
        )
        assertEquals(1, engine.pendingPredictions().size)

        val ledger = ANS100NeuralHypothesisLedger(context, database)
        try {
            assertTrue(ledger.save(engine.exportSnapshot()))
            val restoredSnapshot = ledger.load()
            assertNotNull(restoredSnapshot)
            val restored = ANS100NeuralHypothesisEngine()
            restored.restoreSnapshot(checkNotNull(restoredSnapshot))
            assertEquals(engine.exportSnapshot(), restored.exportSnapshot())

            restored.observe250kTurn(
                turnId = "ledger-prediction-miss",
                activeConceptIds = setOf(candidate.firstConceptId),
                candidates = emptyList(),
                completed = true,
                pairScanComplete = true,
            )
            assertEquals(1, restored.snapshot().single().predictionMissCount)
            assertTrue(ledger.save(restored.exportSnapshot()))
        } finally {
            ledger.close()
            context.deleteDatabase(database)
        }
    }

    @Test
    fun rejectsCheckpointWithPendingForecastWithoutMatchingHypothesis() {
        val database = "ans100-hypothesis-invalid-${UUID.randomUUID()}.db"
        context.deleteDatabase(database)
        val invalid = ANS100NeuralHypothesisEngine.Snapshot(
            hypotheses = emptyList(),
            observedTurnIds = emptyList(),
            pendingPredictions = listOf(
                ANS100NeuralHypothesisEngine.Prediction(
                    sourceConceptId = "lex-token:" + "c".repeat(24),
                    predictedConceptId = "lex-token:" + "d".repeat(24),
                    predictedLabel = "delta",
                    supportingTurnCount = 2,
                    verificationTurnCount = 2,
                    confidence = 1.0,
                    heuristicEvidence = false,
                ),
            ),
        )
        val ledger = ANS100NeuralHypothesisLedger(context, database)
        try {
            assertFalse(ledger.save(invalid))
            assertTrue(ledger.load() == null)
        } finally {
            ledger.close()
            context.deleteDatabase(database)
        }
    }
}
