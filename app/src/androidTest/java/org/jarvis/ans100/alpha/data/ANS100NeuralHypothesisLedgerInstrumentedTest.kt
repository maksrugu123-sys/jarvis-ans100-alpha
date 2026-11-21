package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ANS100NeuralHypothesisLedgerInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun pendingNextTurnForecastSurvivesLedgerRecreation() {
        val databaseName = "ans100_neural_hypotheses_test_${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val firstId = "lex-token:" + "a".repeat(24)
        val secondId = "lex-token:" + "b".repeat(24)
        val candidate = AndroidJnvM250kHypothesisReadout.Candidate(
            candidateId = "jnvm250k-association:" + "a".repeat(32),
            firstConceptId = firstId,
            firstLabel = "alpha",
            secondConceptId = secondId,
            secondLabel = "beta",
            convergentNeuronIds = listOf(70_123),
            activatedConnectionIds = listOf("ledger-edge-a", "ledger-edge-b"),
            evidenceRefs = emptyList(),
        )
        val engine = ANS100NeuralHypothesisEngine()
        repeat(2) { index ->
            engine.observe250kTurn(
                turnId = "ledger-training-$index",
                activeConceptIds = setOf(firstId, secondId),
                candidates = listOf(candidate),
                completed = true,
                pairScanComplete = true,
            )
        }
        engine.observe250kTurn(
            turnId = "ledger-prompt",
            activeConceptIds = setOf(firstId),
            candidates = emptyList(),
            completed = true,
            pairScanComplete = true,
        )
        assertEquals(1, engine.pendingPredictions().size)

        val writer = ANS100NeuralHypothesisLedger(context, databaseName)
        try {
            assertTrue(writer.save(engine.exportSnapshot()))
        } finally {
            writer.close()
        }

        val reader = ANS100NeuralHypothesisLedger(context, databaseName)
        try {
            val restoredSnapshot = reader.load() ?: error("hypothesis checkpoint was not saved")
            assertEquals(1, restoredSnapshot.pendingPredictions.size)
            assertEquals(secondId, restoredSnapshot.pendingPredictions.single().predictedConceptId)
            val restoredEngine = ANS100NeuralHypothesisEngine()
            restoredEngine.restoreSnapshot(restoredSnapshot)
            assertEquals(1, restoredEngine.pendingPredictions().size)
        } finally {
            reader.close()
            context.deleteDatabase(databaseName)
        }
    }
}
