package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Bounded revision history for structured relation hypotheses.
 *
 * Candidate generation remains the responsibility of the causal-chain engine.
 * This component audits only direct evidence for the exact proposed relation.
 * Premise edges are retained as provenance and are never counted as direct
 * support. Conflicting records remain visible; this engine never writes an
 * inferred relation into ANS100ConceptMemory or changes neural weights.
 *
 * evidenceBalance is an evidence-count ratio, NOT a calibrated probability.
 */
class ANS100HypothesisRevisionEngine(
    private val maxHypotheses: Int = DEFAULT_MAX_HYPOTHESES,
) {
    enum class Status {
        CANDIDATE_UNVERIFIED,
        SUPPORTED_BY_DIRECT_EVIDENCE,
        REFUTED_BY_DIRECT_EVIDENCE,
        CONFLICTED_DIRECT_EVIDENCE,
    }

    data class EvidenceRecord(
        val evidenceRef: String,
        val source: ANS100ConceptMemory.LearningSource,
        val supports: Boolean,
    )

    data class Revision(
        val hypothesisId: String,
        val fromConceptId: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val toConceptId: String,
        val status: Status,
        val directEvidenceCount: Int,
        val supportingEvidenceCount: Int,
        val contradictingEvidenceCount: Int,
        val evidenceBalance: Double,
        val supportingEvidenceRefs: List<String>,
        val contradictingEvidenceRefs: List<String>,
        val supportingSources: Set<ANS100ConceptMemory.LearningSource>,
        val contradictingSources: Set<ANS100ConceptMemory.LearningSource>,
        val premiseRelationIds: List<String>,
        val premiseEvidenceRefs: List<String>,
        val lastUpdatedTurnId: String,
    )

    enum class ReviewAction {
        SEEK_BOTH_SIDES,
        SEEK_COUNTER_EVIDENCE,
        SEEK_SUPPORTING_EVIDENCE,
        MANUAL_CONFLICT_REVIEW,
    }

    /** Structured request for the next evidence-acquisition step; not a truth decision. */
    data class ReviewRequest(
        val hypothesisId: String,
        val fromConceptId: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val toConceptId: String,
        val currentStatus: Status,
        val action: ReviewAction,
        val additionalEvidenceCapacity: Int,
        val excludedEvidenceRefs: List<String>,
        val permittedSources: Set<ANS100ConceptMemory.LearningSource>,
    )

    data class PersistedHypothesis(
        val hypothesisId: String,
        val fromConceptId: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val toConceptId: String,
        val premiseRelationIds: List<String>,
        val premiseEvidenceRefs: List<String>,
        val evidence: List<EvidenceRecord>,
        val lastUpdatedTurnId: String,
    )

    data class Snapshot(
        val observedTurnIds: List<String>,
        val hypotheses: List<PersistedHypothesis>,
    )

    private data class State(
        val hypothesisId: String,
        val fromConceptId: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val toConceptId: String,
        val premiseRelationIds: LinkedHashSet<String> = LinkedHashSet(),
        val premiseEvidenceRefs: LinkedHashSet<String> = LinkedHashSet(),
        val evidence: LinkedHashMap<String, EvidenceRecord> = LinkedHashMap(),
        var lastUpdatedTurnId: String,
    )

    private val states = LinkedHashMap<String, State>()
    private val observedTurns = LinkedHashSet<String>()

    init {
        require(maxHypotheses in 1..MAX_HYPOTHESES_HARD_LIMIT)
    }

    /**
     * Registers candidates and revises them using only exact, direct relation
     * observations from trusted sources. An incomplete/failed turn is ignored.
     * No absence-of-evidence inference is made.
     */
    @Synchronized
    fun observeTurn(
        turnId: String,
        candidates: List<ANS100ConceptMemory.Hypothesis>,
        directEvidence: List<ANS100ConceptMemory.RelationEvidence>,
        completed: Boolean,
    ): List<Revision> {
        require(isValidTurnId(turnId))
        require(candidates.size <= MAX_CANDIDATES_PER_TURN)
        require(directEvidence.size <= ANS100ConceptMemory.MAX_VERIFICATION_EVIDENCE)
        require(candidates.map { hypothesisId(it.fromConceptId, it.type, it.toConceptId) }.distinct().size == candidates.size) {
            "one candidate per exact relation is allowed in a turn"
        }
        if (!completed || turnId in observedTurns) return snapshot()

        // Preflight every candidate and evidence reference before changing any
        // state, so a malformed observation cannot partially consume a turn.
        candidates.forEach(::validateCandidate)
        val prepared = candidates.map { candidate ->
            val key = relationKey(candidate.fromConceptId, candidate.type, candidate.toConceptId)
            val existing = states[key]
            val mergedPremises = (existing?.premiseEvidenceRefs.orEmpty() + candidate.evidenceRefs).distinct()
            val mergedRelationIds = (existing?.premiseRelationIds.orEmpty() + candidate.premiseRelationIds).distinct()
            require(mergedPremises.size <= MAX_PREMISES && mergedRelationIds.size <= MAX_PREMISES) {
                "alternative premise provenance budget exceeded for this hypothesis"
            }
            require(mergedPremises.intersect(existing?.evidence?.keys.orEmpty()).isEmpty()) {
                "premise evidence cannot be reused as direct confirmation"
            }
            val incoming = directEvidence.asSequence()
                .filter {
                    it.fromConceptId == candidate.fromConceptId &&
                        it.type == candidate.type &&
                        it.toConceptId == candidate.toConceptId &&
                        it.source.isTrustedRevisionSource()
                }
                .map {
                    require(isValidReference(it.evidenceRef))
                    require(it.evidenceRef !in mergedPremises) {
                        "premise evidence cannot be reused as direct confirmation"
                    }
                    EvidenceRecord(it.evidenceRef, it.source, it.supports)
                }
                .toList()
            require(incoming.map { it.evidenceRef }.distinct().size == incoming.size) {
                "direct evidence references must be unique within a turn"
            }
            incoming.forEach { item ->
                val previous = existing?.evidence?.get(item.evidenceRef)
                require(previous == null || previous == item) {
                    "an evidence reference cannot be rebound to different polarity or source"
                }
            }
            val newEvidenceCount = incoming.count { it.evidenceRef !in existing?.evidence?.keys.orEmpty() }
            require((existing?.evidence?.size ?: 0) + newEvidenceCount <= MAX_EVIDENCE_PER_HYPOTHESIS) {
                "direct evidence history is full; refusing to evict deduplication keys"
            }
            Triple(candidate, existing, incoming)
        }

        observedTurns.add(turnId)
        trimOldest(observedTurns, MAX_REMEMBERED_TURNS)
        prepared.forEach { (candidate, existing, incoming) ->
            val key = relationKey(candidate.fromConceptId, candidate.type, candidate.toConceptId)
            val state = existing ?: State(
                hypothesisId = hypothesisId(candidate.fromConceptId, candidate.type, candidate.toConceptId),
                fromConceptId = candidate.fromConceptId,
                relationType = candidate.type,
                toConceptId = candidate.toConceptId,
                lastUpdatedTurnId = turnId,
            ).also { states[key] = it }
            state.premiseRelationIds.addAll(candidate.premiseRelationIds)
            state.premiseEvidenceRefs.addAll(candidate.evidenceRefs)
            state.lastUpdatedTurnId = turnId
            incoming.forEach { item ->
                state.evidence.putIfAbsent(item.evidenceRef, item)
            }
        }

        while (states.size > maxHypotheses) {
            states.remove(states.entries.first().key)
        }
        return snapshot()
    }

    @Synchronized
    fun snapshot(): List<Revision> = states.values.map(::toRevision)

    /**
     * Produces a bounded challenge queue. Supported claims are challenged with
     * counter-evidence requests; refuted claims seek support; conflicts are held
     * for adjudication rather than being auto-resolved.
     */
    @Synchronized
    fun reviewQueue(limit: Int = DEFAULT_REVIEW_QUEUE_LIMIT): List<ReviewRequest> {
        require(limit in 0..MAX_REVIEW_QUEUE_LIMIT)
        if (limit == 0) return emptyList()
        return states.values.map { state ->
            val revision = toRevision(state)
            val action = when (revision.status) {
                Status.CANDIDATE_UNVERIFIED -> ReviewAction.SEEK_BOTH_SIDES
                Status.SUPPORTED_BY_DIRECT_EVIDENCE -> ReviewAction.SEEK_COUNTER_EVIDENCE
                Status.REFUTED_BY_DIRECT_EVIDENCE -> ReviewAction.SEEK_SUPPORTING_EVIDENCE
                Status.CONFLICTED_DIRECT_EVIDENCE -> ReviewAction.MANUAL_CONFLICT_REVIEW
            }
            ReviewRequest(
                hypothesisId = revision.hypothesisId,
                fromConceptId = revision.fromConceptId,
                relationType = revision.relationType,
                toConceptId = revision.toConceptId,
                currentStatus = revision.status,
                action = action,
                additionalEvidenceCapacity = (MAX_EVIDENCE_PER_HYPOTHESIS - revision.directEvidenceCount)
                    .coerceAtLeast(0),
                excludedEvidenceRefs = (revision.premiseEvidenceRefs +
                    revision.supportingEvidenceRefs + revision.contradictingEvidenceRefs).distinct(),
                permittedSources = setOf(
                    ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
                    ANS100ConceptMemory.LearningSource.VERIFIED_TOOL,
                ),
            )
        }.sortedWith(
            compareBy<ReviewRequest> { reviewPriority(it.action) }
                .thenBy { it.hypothesisId },
        ).take(limit)
    }

    private fun reviewPriority(action: ReviewAction): Int = when (action) {
        ReviewAction.MANUAL_CONFLICT_REVIEW -> 0
        ReviewAction.SEEK_BOTH_SIDES -> 1
        ReviewAction.SEEK_COUNTER_EVIDENCE -> 2
        ReviewAction.SEEK_SUPPORTING_EVIDENCE -> 3
    }

    @Synchronized
    fun exportSnapshot(): Snapshot = Snapshot(
        observedTurnIds = observedTurns.toList(),
        hypotheses = states.values.map { state ->
            PersistedHypothesis(
                hypothesisId = state.hypothesisId,
                fromConceptId = state.fromConceptId,
                relationType = state.relationType,
                toConceptId = state.toConceptId,
                premiseRelationIds = state.premiseRelationIds.toList(),
                premiseEvidenceRefs = state.premiseEvidenceRefs.toList(),
                evidence = state.evidence.values.toList(),
                lastUpdatedTurnId = state.lastUpdatedTurnId,
            )
        },
    )

    /** Restore only bounded, internally consistent history. */
    @Synchronized
    fun restoreSnapshot(snapshot: Snapshot) {
        require(snapshot.observedTurnIds.size <= MAX_REMEMBERED_TURNS)
        require(snapshot.observedTurnIds.distinct().size == snapshot.observedTurnIds.size)
        require(snapshot.observedTurnIds.all(::isValidTurnId))
        require(snapshot.hypotheses.size <= maxHypotheses)
        val restored = LinkedHashMap<String, State>()
        snapshot.hypotheses.forEach { item ->
            require(item.fromConceptId.isNotBlank() && item.fromConceptId.length <= MAX_CONCEPT_ID_CHARS)
            require(item.toConceptId.isNotBlank() && item.toConceptId.length <= MAX_CONCEPT_ID_CHARS)
            require(item.fromConceptId != item.toConceptId)
            require(item.premiseRelationIds.isNotEmpty() && item.premiseRelationIds.size <= MAX_PREMISES)
            require(item.premiseRelationIds.distinct().size == item.premiseRelationIds.size)
            require(item.premiseRelationIds.all(::isValidReference))
            require(item.premiseEvidenceRefs.size <= MAX_PREMISES)
            require(item.premiseEvidenceRefs.distinct().size == item.premiseEvidenceRefs.size)
            require(item.premiseEvidenceRefs.all(::isValidReference))
            require(item.evidence.size <= MAX_EVIDENCE_PER_HYPOTHESIS)
            require(item.evidence.map { it.evidenceRef }.distinct().size == item.evidence.size)
            require(item.evidence.all {
                isValidReference(it.evidenceRef) && it.source.isTrustedRevisionSource() &&
                    it.evidenceRef !in item.premiseEvidenceRefs
            })
            require(isValidTurnId(item.lastUpdatedTurnId))
            val expectedId = hypothesisId(item.fromConceptId, item.relationType, item.toConceptId)
            require(item.hypothesisId == expectedId)
            val key = relationKey(item.fromConceptId, item.relationType, item.toConceptId)
            require(restored.put(
                key,
                State(
                    hypothesisId = item.hypothesisId,
                    fromConceptId = item.fromConceptId,
                    relationType = item.relationType,
                    toConceptId = item.toConceptId,
                    premiseRelationIds = LinkedHashSet(item.premiseRelationIds),
                    premiseEvidenceRefs = LinkedHashSet(item.premiseEvidenceRefs),
                    evidence = LinkedHashMap(item.evidence.associateBy { it.evidenceRef }),
                    lastUpdatedTurnId = item.lastUpdatedTurnId,
                ),
            ) == null)
        }
        states.clear()
        states.putAll(restored)
        observedTurns.clear()
        observedTurns.addAll(snapshot.observedTurnIds)
    }

    private fun validateCandidate(candidate: ANS100ConceptMemory.Hypothesis) {
        require(candidate.fromConceptId.isNotBlank() && candidate.fromConceptId.length <= MAX_CONCEPT_ID_CHARS)
        require(candidate.toConceptId.isNotBlank() && candidate.toConceptId.length <= MAX_CONCEPT_ID_CHARS)
        require(candidate.fromConceptId != candidate.toConceptId)
        require(candidate.premiseRelationIds.isNotEmpty() && candidate.premiseRelationIds.size <= MAX_PREMISES)
        require(candidate.premiseRelationIds.distinct().size == candidate.premiseRelationIds.size)
        require(candidate.premiseRelationIds.all(::isValidReference))
        require(candidate.evidenceRefs.size <= MAX_PREMISES)
        require(candidate.evidenceRefs.distinct().size == candidate.evidenceRefs.size)
        require(candidate.evidenceRefs.all(::isValidReference))
        require(candidate.estimatedStrength.isFinite() && candidate.estimatedStrength in 0.0..1.0)
    }

    private fun toRevision(state: State): Revision {
        val support = state.evidence.values.filter { it.supports }
        val counter = state.evidence.values.filterNot { it.supports }
        val total = support.size + counter.size
        val status = when {
            support.isNotEmpty() && counter.isNotEmpty() -> Status.CONFLICTED_DIRECT_EVIDENCE
            support.isNotEmpty() -> Status.SUPPORTED_BY_DIRECT_EVIDENCE
            counter.isNotEmpty() -> Status.REFUTED_BY_DIRECT_EVIDENCE
            else -> Status.CANDIDATE_UNVERIFIED
        }
        return Revision(
            hypothesisId = state.hypothesisId,
            fromConceptId = state.fromConceptId,
            relationType = state.relationType,
            toConceptId = state.toConceptId,
            status = status,
            directEvidenceCount = total,
            supportingEvidenceCount = support.size,
            contradictingEvidenceCount = counter.size,
            evidenceBalance = if (total == 0) 0.5 else support.size.toDouble() / total,
            supportingEvidenceRefs = support.map { it.evidenceRef }.sorted(),
            contradictingEvidenceRefs = counter.map { it.evidenceRef }.sorted(),
            supportingSources = support.map { it.source }.toSet(),
            contradictingSources = counter.map { it.source }.toSet(),
            premiseRelationIds = state.premiseRelationIds.toList(),
            premiseEvidenceRefs = state.premiseEvidenceRefs.toList(),
            lastUpdatedTurnId = state.lastUpdatedTurnId,
        )
    }

    private fun relationKey(
        from: String,
        type: ANS100ConceptMemory.RelationType,
        to: String,
    ): String = "$from|$type|$to"

    private fun hypothesisId(
        from: String,
        type: ANS100ConceptMemory.RelationType,
        to: String,
    ): String = "revision:${digest(relationKey(from, type, to))}"

    private fun ANS100ConceptMemory.LearningSource.isTrustedRevisionSource(): Boolean =
        this == ANS100ConceptMemory.LearningSource.EXPLICIT_USER ||
            this == ANS100ConceptMemory.LearningSource.VERIFIED_TOOL

    private fun isValidTurnId(value: String): Boolean =
        value.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$"))

    private fun isValidReference(value: String): Boolean =
        value.isNotBlank() && value.length <= ANS100ConceptMemory.MAX_EVIDENCE_REF_CHARS

    private fun <T> trimOldest(values: LinkedHashSet<T>, maximum: Int) {
        while (values.size > maximum) values.remove(values.first())
    }

    private fun <K, V> trimOldest(values: LinkedHashMap<K, V>, maximum: Int) {
        while (values.size > maximum) values.remove(values.keys.first())
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_MAX_HYPOTHESES = 128
        const val MAX_HYPOTHESES_HARD_LIMIT = 256
        const val MAX_CANDIDATES_PER_TURN = 64
        const val MAX_EVIDENCE_PER_HYPOTHESIS = 16
        const val MAX_REMEMBERED_TURNS = 512
        const val MAX_CONCEPT_ID_CHARS = 96
        const val MAX_PREMISES = 8
        const val DEFAULT_REVIEW_QUEUE_LIMIT = 32
        const val MAX_REVIEW_QUEUE_LIMIT = 64
    }
}
