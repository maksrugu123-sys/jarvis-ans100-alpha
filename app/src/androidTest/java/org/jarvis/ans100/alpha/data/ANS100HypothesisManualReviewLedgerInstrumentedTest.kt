package org.jarvis.ans100.alpha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ANS100HypothesisManualReviewLedgerInstrumentedTest {
    private fun revisionId(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("heat|CAUSES|expansion".toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "revision:$digest"
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val factory = ANS100HypothesisManualReviewDecision()

    private fun packet(): ANS100HypothesisEvidenceReviewPacketBuilder.Packet {
        val page = ANS100HypothesisPageVerifier.PageReport(
            revisionId(), "search-lead:one", "https://example.org/article",
            "https://example.org/article", "Heat and expansion", "A page about heat.",
            "Heat causes expansion. Heat and expansion are discussed.",
            "a".repeat(64), 1234L,
            ANS100HypothesisPageVerifier.Relevance.BOTH_ENDPOINTS_MENTIONED,
        )
        val extraction = ANS100HypothesisPassageExtractor().extract(page, "heat", "expansion")
        val audit = ANS100HypothesisSourceAudit().audit(extraction.candidates)
        val analyses = extraction.candidates.map {
            ANS100HypothesisClaimPolarityAnalyzer().analyze(
                it, "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion",
            )
        }
        val request = ANS100HypothesisRevisionEngine.ReviewRequest(
            revisionId(), "heat", ANS100ConceptMemory.RelationType.CAUSES, "expansion",
            ANS100HypothesisRevisionEngine.Status.CANDIDATE_UNVERIFIED,
            ANS100HypothesisRevisionEngine.ReviewAction.SEEK_BOTH_SIDES, 16,
            listOf("premise:one"),
            setOf(ANS100ConceptMemory.LearningSource.EXPLICIT_USER, ANS100ConceptMemory.LearningSource.VERIFIED_TOOL),
        )
        return ANS100HypothesisEvidenceReviewPacketBuilder().build(request, page, extraction, audit, analyses)
    }

    private fun decision(packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet, id: String) =
        factory.record(
            packet, id, ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            listOf(packet.passages.first().passageRef), "Проверил фрагмент вручную.", 20_000L,
        )

    @Test
    fun pendingDecisionSurvivesSqliteReopenAndCanBeMarkedApplied() {
        val name = "ans100-manual-review-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val reviewedPacket = packet()
        val d = decision(reviewedPacket, "persist-001")
        val evidence = factory.toRelationEvidence(reviewedPacket, d)
        val first = ANS100HypothesisManualReviewLedger(context, name, factory)
        assertTrue(first.append(d, evidence))
        assertEquals(1, first.loadPending().size)
        first.close()
        val reopened = ANS100HypothesisManualReviewLedger(context, name, factory)
        val pending = reopened.loadPending().single()
        assertEquals(d.decisionId, pending.decision.decisionId)
        assertEquals(evidence, pending.evidence)
        assertTrue(reopened.markApplied(d.decisionId))
        assertTrue(reopened.isApplied(d.decisionId))
        assertTrue(reopened.loadPending().isEmpty())
        reopened.close()
        context.deleteDatabase(name)
    }

    @Test
    fun sameDecisionIsIdempotentButChangedPayloadCannotReuseItsId() {
        val name = "ans100-manual-review-duplicate-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val reviewedPacket = packet()
        val d = decision(reviewedPacket, "persist-duplicate")
        val evidence = factory.toRelationEvidence(reviewedPacket, d)
        val ledger = ANS100HypothesisManualReviewLedger(context, name, factory)
        assertTrue(ledger.append(d, evidence))
        assertTrue(ledger.append(d, evidence))
        assertFalse(ledger.append(d.copy(rationale = "Изменённое решение."), evidence))
        assertEquals(1, ledger.loadPending().size)
        ledger.close()
        context.deleteDatabase(name)
    }

    @Test
    fun nonConfirmationIsPersistedWithoutBecomingPendingEvidence() {
        val name = "ans100-manual-review-no-evidence-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val reviewedPacket = packet()
        val d = factory.record(
            reviewedPacket, "persist-no-evidence",
            ANS100HypothesisManualReviewDecision.Outcome.INSUFFICIENT_EVIDENCE,
            emptyList(), "Недостаточно данных.", 20_001L,
        )
        val ledger = ANS100HypothesisManualReviewLedger(context, name, factory)
        assertTrue(ledger.append(d, null))
        assertTrue(ledger.loadPending().isEmpty())
        ledger.close()
        context.deleteDatabase(name)
    }

    @Test
    fun coordinatorReplaysDecisionIntoRevisionCheckpointExactlyOnce() {
        val suffix = System.nanoTime()
        val manualDb = "ans100-manual-coordinator-$suffix.db"
        val revisionDb = "ans100-revision-coordinator-$suffix.db"
        context.deleteDatabase(manualDb)
        context.deleteDatabase(revisionDb)
        val reviewedPacket = packet()
        val d = factory.record(
            reviewedPacket, "coordinator-001",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            listOf(reviewedPacket.passages.first().passageRef), "Подтверждаю после ручной проверки.", 30_000L,
        )
        val revisionEngine = ANS100HypothesisRevisionEngine()
        revisionEngine.observeTurn(
            "seed-manual-review",
            listOf(ANS100ConceptMemory.Hypothesis(
                "causal-chain:heat-expansion", "heat", ANS100ConceptMemory.RelationType.CAUSES,
                "expansion", listOf("relation:heat-mid", "relation:mid-expansion"),
                listOf("premise:heat-mid", "premise:mid-expansion"), 0.5,
            )),
            emptyList(), completed = true,
        )
        val decisionLedger = ANS100HypothesisManualReviewLedger(context, manualDb, factory)
        val revisionLedger = ANS100HypothesisRevisionLedger(context, revisionDb)
        val coordinator = ANS100HypothesisManualReviewCoordinator(
            factory, decisionLedger, revisionEngine, revisionLedger,
        )
        assertTrue(coordinator.recordAndApply(reviewedPacket, d))
        assertEquals(ANS100HypothesisRevisionEngine.Status.SUPPORTED_BY_DIRECT_EVIDENCE,
            revisionEngine.snapshot().single().status)
        assertTrue(decisionLedger.isApplied(d.decisionId))
        assertEquals(0, coordinator.replayPending().pendingAtStart)
        val restored = ANS100HypothesisRevisionEngine()
        restored.restoreSnapshot(revisionLedger.load()!!)
        assertEquals(1, restored.snapshot().single().directEvidenceCount)
        decisionLedger.close()
        revisionLedger.close()
        context.deleteDatabase(manualDb)
        context.deleteDatabase(revisionDb)
    }
    @Test
    fun malformedPendingRowIsRejectedWithoutBlockingOtherValidDecisions() {
        val name = "ans100-manual-review-corrupt-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val reviewedPacket = packet()
        val noEvidence = factory.record(
            reviewedPacket, "persist-corrupt",
            ANS100HypothesisManualReviewDecision.Outcome.INSUFFICIENT_EVIDENCE,
            emptyList(), "Недостаточно данных.", 20_002L,
        )
        val validDecision = decision(reviewedPacket, "persist-valid-after-corrupt")
        val validEvidence = factory.toRelationEvidence(reviewedPacket, validDecision)
        val ledger = ANS100HypothesisManualReviewLedger(context, name, factory)
        assertTrue(ledger.append(noEvidence, null))
        assertTrue(ledger.append(validDecision, validEvidence))
        ledger.writableDatabase.execSQL(
            "UPDATE manual_hypothesis_review_decisions SET state=? WHERE decision_id=?",
            arrayOf(ANS100HypothesisManualReviewLedger.State.PENDING.name, noEvidence.decisionId),
        )
        val batch = ledger.loadPendingBatch()
        assertEquals(1, batch.rejectedEntries)
        assertEquals(listOf(validDecision.decisionId), batch.entries.map { it.decision.decisionId })
        ledger.close()
        context.deleteDatabase(name)
    }


    @Test
    fun startupReplayRecoversDecisionAfterJournalCommitBeforeRevisionApply() {
        val suffix = System.nanoTime()
        val manualDb = "ans100-manual-recovery-$suffix.db"
        val revisionDb = "ans100-revision-recovery-$suffix.db"
        context.deleteDatabase(manualDb)
        context.deleteDatabase(revisionDb)
        val reviewedPacket = packet()
        val d = factory.record(
            reviewedPacket, "recovery-001",
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            listOf(reviewedPacket.passages.first().passageRef),
            "Подтверждаю после проверки.", 40_000L,
        )
        val evidence = factory.toRelationEvidence(reviewedPacket, d)
        val firstJournal = ANS100HypothesisManualReviewLedger(context, manualDb, factory)
        assertTrue(firstJournal.append(d, evidence))
        firstJournal.close() // Simulates process stop after write-ahead commit.

        val recoveredEngine = ANS100HypothesisRevisionEngine()
        recoveredEngine.observeTurn(
            "recovery-seed",
            listOf(ANS100ConceptMemory.Hypothesis(
                "causal-chain:heat-expansion", "heat", ANS100ConceptMemory.RelationType.CAUSES,
                "expansion", listOf("relation:heat-mid", "relation:mid-expansion"),
                listOf("premise:heat-mid", "premise:mid-expansion"), 0.5,
            )),
            emptyList(), completed = true,
        )
        val recoveredJournal = ANS100HypothesisManualReviewLedger(context, manualDb, factory)
        val revisionJournal = ANS100HypothesisRevisionLedger(context, revisionDb)
        val coordinator = ANS100HypothesisManualReviewCoordinator(
            factory, recoveredJournal, recoveredEngine, revisionJournal,
        )
        val report = coordinator.replayPending()
        assertEquals(1, report.pendingAtStart)
        assertEquals(1, report.applied)
        assertEquals(0, report.rejectedJournalEntries)
        assertTrue(recoveredJournal.isApplied(d.decisionId))
        assertEquals(1, recoveredEngine.snapshot().single().directEvidenceCount)
        assertEquals(0, coordinator.replayPending().pendingAtStart)
        val checkpoint = ANS100HypothesisRevisionEngine().apply {
            restoreSnapshot(checkNotNull(revisionJournal.load()))
        }
        assertEquals(1, checkpoint.snapshot().single().directEvidenceCount)
        recoveredJournal.close()
        revisionJournal.close()
        context.deleteDatabase(manualDb)
        context.deleteDatabase(revisionDb)
    }


    @Test
    fun immutableReviewSnapshotSurvivesReopenWithoutStoringFullPageBody() {
        val name = "ans100-manual-review-snapshot-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val reviewedPacket = packet()
        val d = decision(reviewedPacket, "snapshot-persist-001")
        val evidence = factory.toRelationEvidence(reviewedPacket, d)
        val first = ANS100HypothesisManualReviewLedger(context, name, factory)
        assertTrue(first.append(d, evidence, reviewedPacket))
        first.close()

        val reopened = ANS100HypothesisManualReviewLedger(context, name, factory)
        val saved = reopened.loadRecent().single()
        val snapshot = checkNotNull(saved.snapshot)
        assertEquals(d.hypothesisId, snapshot.hypothesisId)
        assertEquals(reviewedPacket.pageUrl, snapshot.pageUrl)
        assertEquals(reviewedPacket.pageContentSha256, snapshot.pageContentSha256)
        assertEquals(reviewedPacket.passages.map { it.passageRef }, snapshot.passages.map { it.passageRef })
        assertEquals(reviewedPacket.sources.map { it.canonicalUrl }, snapshot.sources.map { it.canonicalUrl })
        assertTrue(snapshot.passages.all { it.excerpt.isNotBlank() })
        assertFalse(snapshot.passages.any { it.excerpt.contains("Heat and expansion are discussed.") && it.excerpt.length > 1_000 })
        assertTrue(reopened.isApplied(d.decisionId).not())
        reopened.close()
        context.deleteDatabase(name)
    }


}
