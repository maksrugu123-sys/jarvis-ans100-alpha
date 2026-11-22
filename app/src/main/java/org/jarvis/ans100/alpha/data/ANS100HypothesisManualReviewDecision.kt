package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Records an explicit human review of an unverified evidence packet.
 *
 * A confirmation can be converted into one EXPLICIT_USER relation observation,
 * but this class never mutates a revision engine, memory store, or neural weights.
 * The caller must deliberately submit the returned observation to the revision
 * pipeline. A manual confirmation records the user's attestation, not objective
 * proof or source independence.
 */
class ANS100HypothesisManualReviewDecision {

    enum class Outcome {
        CONFIRM_SUPPORT,
        CONFIRM_CONTRADICTION,
        INSUFFICIENT_EVIDENCE,
        SOURCE_UNTRUSTED,
        CONFLICT_REQUIRES_REVIEW,
    }

    data class Decision(
        val decisionId: String,
        val packetFingerprint: String,
        val hypothesisId: String,
        val fromConceptId: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val toConceptId: String,
        val outcome: Outcome,
        val selectedPassageRefs: List<String>,
        val rationale: String,
        val reviewedAtEpochMs: Long,
        val decisionDigest: String,
    )

    fun record(
        packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet,
        decisionId: String,
        outcome: Outcome,
        selectedPassageRefs: List<String>,
        rationale: String,
        reviewedAtEpochMs: Long,
    ): Decision {
        validatePacket(packet)
        require(decisionId.matches(DECISION_ID_PATTERN)) { "invalid decision ID" }
        require(reviewedAtEpochMs > 0L)
        require(rationale.isNotBlank() && rationale.length <= MAX_RATIONALE_CHARS)
        require(selectedPassageRefs.size <= MAX_SELECTED_PASSAGES)
        require(selectedPassageRefs.distinct().size == selectedPassageRefs.size)
        val knownPassages = packet.passages.map { it.passageRef }.toSet()
        require(selectedPassageRefs.all { it in knownPassages }) {
            "decision references a passage outside the reviewed packet"
        }
        when (outcome) {
            Outcome.CONFIRM_SUPPORT, Outcome.CONFIRM_CONTRADICTION ->
                require(selectedPassageRefs.isNotEmpty()) {
                    "a confirmation must cite at least one reviewed passage"
                }
            Outcome.INSUFFICIENT_EVIDENCE, Outcome.SOURCE_UNTRUSTED,
            Outcome.CONFLICT_REQUIRES_REVIEW -> Unit
        }
        val unsigned = Decision(
            decisionId = decisionId,
            packetFingerprint = fingerprint(packet),
            hypothesisId = packet.hypothesisId,
            fromConceptId = packet.fromConceptId,
            relationType = packet.relationType,
            toConceptId = packet.toConceptId,
            outcome = outcome,
            selectedPassageRefs = selectedPassageRefs.sorted(),
            rationale = rationale.trim(),
            reviewedAtEpochMs = reviewedAtEpochMs,
            decisionDigest = "",
        )
        return unsigned.copy(decisionDigest = decisionDigest(unsigned))
    }

    /** Validates the decision's canonical integrity digest before durable storage/replay. */
    fun isDecisionIntact(decision: Decision): Boolean = runCatching {
        require(decision.decisionId.matches(DECISION_ID_PATTERN))
        require(decision.packetFingerprint.matches(SHA256))
        require(decision.hypothesisId.isNotBlank() && decision.fromConceptId.isNotBlank() &&
            decision.toConceptId.isNotBlank() && decision.fromConceptId != decision.toConceptId)
        require(decision.reviewedAtEpochMs > 0L)
        require(decision.rationale.isNotBlank() && decision.rationale.length <= MAX_RATIONALE_CHARS)
        require(decision.selectedPassageRefs.size <= MAX_SELECTED_PASSAGES)
        require(decision.selectedPassageRefs.distinct().size == decision.selectedPassageRefs.size)
        require(decision.selectedPassageRefs.all { it.isNotBlank() &&
            it.length <= ANS100ConceptMemory.MAX_EVIDENCE_REF_CHARS })
        require(decision.decisionDigest.matches(SHA256) &&
            decision.decisionDigest == decisionDigest(decision))
    }.isSuccess

    /**
     * Produces one explicit-user observation only when the exact reviewed packet
     * is supplied again. Non-confirmation outcomes never become evidence.
     */
    fun toRelationEvidence(
        packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet,
        decision: Decision,
    ): ANS100ConceptMemory.RelationEvidence? {
        validatePacket(packet)
        require(decision.decisionId.matches(DECISION_ID_PATTERN))
        require(decision.packetFingerprint.matches(SHA256))
        require(decision.decisionDigest.matches(SHA256) && decision.decisionDigest == decisionDigest(decision)) {
            "manual review decision was modified after recording"
        }
        require(decision.packetFingerprint == fingerprint(packet)) {
            "reviewed packet changed after the decision was recorded"
        }
        require(decision.hypothesisId == packet.hypothesisId &&
            decision.fromConceptId == packet.fromConceptId &&
            decision.relationType == packet.relationType &&
            decision.toConceptId == packet.toConceptId) {
            "decision does not belong to this hypothesis"
        }
        require(decision.reviewedAtEpochMs > 0L)
        require(decision.rationale.isNotBlank() && decision.rationale.length <= MAX_RATIONALE_CHARS)
        require(decision.selectedPassageRefs.size <= MAX_SELECTED_PASSAGES)
        require(decision.selectedPassageRefs.distinct().size == decision.selectedPassageRefs.size)
        require(decision.selectedPassageRefs.all { ref -> packet.passages.any { it.passageRef == ref } })
        val supports = when (decision.outcome) {
            Outcome.CONFIRM_SUPPORT -> true
            Outcome.CONFIRM_CONTRADICTION -> false
            Outcome.INSUFFICIENT_EVIDENCE, Outcome.SOURCE_UNTRUSTED,
            Outcome.CONFLICT_REQUIRES_REVIEW -> return null
        }
        require(decision.selectedPassageRefs.isNotEmpty())
        val evidenceRef = "manual-review:" + sha256(
            decision.decisionId + "|" + decision.packetFingerprint + "|" + decision.outcome.name,
        )
        require(evidenceRef.length <= ANS100ConceptMemory.MAX_EVIDENCE_REF_CHARS)
        require(evidenceRef !in packet.excludedEvidenceRefs)
        return ANS100ConceptMemory.RelationEvidence(
            fromConceptId = packet.fromConceptId,
            type = packet.relationType,
            toConceptId = packet.toConceptId,
            supports = supports,
            source = ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
            evidenceRef = evidenceRef,
        )
    }

    private fun validatePacket(packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet) {
        require(packet.status == ANS100HypothesisEvidenceReviewPacketBuilder.PacketStatus.MANUAL_REVIEW_REQUIRED)
        require(packet.hypothesisId.isNotBlank() && packet.fromConceptId.isNotBlank() &&
            packet.toConceptId.isNotBlank() && packet.fromConceptId != packet.toConceptId)
        require(packet.pageContentSha256.matches(SHA256))
        require(packet.pageStatus == "FETCHED_PAGE_UNVERIFIED")
        require(packet.passages.size <= ANS100HypothesisEvidenceReviewPacketBuilder.MAX_PASSAGES_HARD_LIMIT)
        require(packet.passages.map { it.passageRef }.distinct().size == packet.passages.size)
        require(packet.passages.all {
            it.disposition == ANS100HypothesisEvidenceReviewPacketBuilder.PassageDisposition.CANDIDATE_ONLY &&
                it.passageRef.isNotBlank() && it.sentenceSha256.matches(SHA256) &&
                it.excerpt.isNotBlank() && it.sourceUrl.startsWith("https://") &&
                it.pageContentSha256.matches(SHA256)
        })
        require(packet.sources.size <= ANS100HypothesisEvidenceReviewPacketBuilder.MAX_SOURCE_ASSESSMENTS_HARD_LIMIT)
        require(!packet.sourceIndependenceConfirmed && packet.sources.none { it.independenceConfirmed })
        require(!packet.directEvidenceCreated && !packet.revisionEngineUpdated && !packet.learningAllowed)
        require(packet.excludedEvidenceRefs.distinct().size == packet.excludedEvidenceRefs.size)
    }

    private fun decisionDigest(decision: Decision): String {
        val canonical = listOf(
            decision.decisionId, decision.packetFingerprint, decision.hypothesisId,
            decision.fromConceptId, decision.relationType.name, decision.toConceptId,
            decision.outcome.name, decision.selectedPassageRefs.sorted().joinToString("|"),
            decision.rationale, decision.reviewedAtEpochMs.toString(),
        ).joinToString("\u001f") { value -> "${value.length}:$value" }
        return sha256(canonical)
    }

    private fun fingerprint(packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet): String {
        val canonical = StringBuilder()
        fun field(value: String?) {
            val text = value.orEmpty()
            canonical.append(text.length).append(':').append(text).append('|')
        }
        field(packet.hypothesisId)
        field(packet.fromConceptId)
        field(packet.relationType.name)
        field(packet.toConceptId)
        field(packet.requestedAction.name)
        field(packet.currentRevisionStatus.name)
        field(packet.additionalEvidenceCapacity.toString())
        packet.excludedEvidenceRefs.sorted().forEach(::field)
        packet.permittedSources.map { it.name }.sorted().forEach(::field)
        field(packet.pageTitle)
        field(packet.pageDescription)
        field(packet.pageUrl)
        field(packet.pageContentSha256)
        field(packet.fetchedAtEpochMs.toString())
        field(packet.pageStatus)
        packet.passages.sortedBy { it.passageRef }.forEach { passage ->
            field(passage.passageRef)
            field(passage.excerpt)
            field(passage.sentenceSha256)
            field(passage.disposition.name)
            field(passage.polarity.name)
            field(passage.polarityReason.name)
            field(passage.matchedCue)
            field(passage.detectedNegation)
            field(passage.sourceAssessment.name)
            field(passage.sourceUrl)
            field(passage.pageContentSha256)
        }
        packet.sources.sortedWith(compareBy({ it.canonicalUrl }, { it.pageContentSha256 }, { it.searchLeadRef })).forEach { source ->
            field(source.canonicalUrl)
            field(source.host)
            field(source.pageContentSha256)
            field(source.searchLeadRef)
            field(source.assessment.name)
            field(source.duplicateOfPageSha256)
            field(source.sameHostAsPageSha256)
            field(source.independenceConfirmed.toString())
        }
        packet.warnings.map { it.name }.sorted().forEach(::field)
        field(packet.extractionTruncated.toString())
        field(packet.sourceAuditTruncated.toString())
        field(packet.sourceIndependenceConfirmed.toString())
        field(packet.directEvidenceCreated.toString())
        field(packet.revisionEngineUpdated.toString())
        field(packet.learningAllowed.toString())
        return sha256(canonical.toString())
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val MAX_SELECTED_PASSAGES = 12
        const val MAX_RATIONALE_CHARS = 1_000
        private val DECISION_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
