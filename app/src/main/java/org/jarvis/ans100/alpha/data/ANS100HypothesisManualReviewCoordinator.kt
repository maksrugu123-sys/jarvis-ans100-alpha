package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/** Write-ahead bridge from reviewed packets to the durable structured revision engine. */
class ANS100HypothesisManualReviewCoordinator(
    private val decisionFactory: ANS100HypothesisManualReviewDecision,
    private val decisionLedger: ANS100HypothesisManualReviewLedger,
    private val revisionEngine: ANS100HypothesisRevisionEngine,
    private val revisionLedger: ANS100HypothesisRevisionLedger,
) {
    data class ReplayReport(
        val pendingAtStart: Int, val applied: Int, val deferredMissingHypothesis: Int,
        val checkpointFailures: Int, val journalFailures: Int,
    )

    @Synchronized
    fun recordAndApply(packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet,
        decision: ANS100HypothesisManualReviewDecision.Decision): Boolean {
        val evidence = decisionFactory.toRelationEvidence(packet, decision)
        if (!decisionLedger.append(decision, evidence)) return false
        if (evidence == null) return true
        replayPending()
        return decisionLedger.isApplied(decision.decisionId)
    }

    @Synchronized
    fun replayPending(limit: Int = ANS100HypothesisManualReviewLedger.MAX_ENTRIES): ReplayReport {
        val pending = runCatching { decisionLedger.loadPending(limit) }.getOrElse {
            return ReplayReport(0, 0, 0, 0, 1)
        }
        var applied = 0
        var missing = 0
        var checkpointFailures = 0
        var journalFailures = 0
        pending.forEach { entry ->
            val evidence = entry.evidence ?: return@forEach
            val revision = runCatching {
                revisionEngine.recordDirectEvidence(
                    entry.decision.hypothesisId, evidence, reviewTurnId(entry.decision.decisionId),
                )
            }.getOrElse { journalFailures++; return@forEach }
            if (revision == null) { missing++; return@forEach }
            if (!runCatching { revisionLedger.save(revisionEngine.exportSnapshot()) }.getOrDefault(false)) {
                checkpointFailures++; return@forEach
            }
            if (runCatching { decisionLedger.markApplied(entry.decision.decisionId) }.getOrDefault(false)) {
                applied++
            } else journalFailures++
        }
        return ReplayReport(pending.size, applied, missing, checkpointFailures, journalFailures)
    }

    private fun reviewTurnId(decisionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(decisionId.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "manual-review-$digest"
    }
}
