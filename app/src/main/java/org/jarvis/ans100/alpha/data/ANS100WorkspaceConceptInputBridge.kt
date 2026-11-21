package org.jarvis.ans100.alpha.data

import java.security.MessageDigest
import java.util.Locale

/**
 * Loads explicitly approved concepts and typed relations from local workspace memory.
 *
 * Relation format: "source label | RELATION_TYPE | target label".
 * Both endpoints must resolve to exactly one approved concept label. No free-text
 * relation extraction, synonym inference, or model-generated facts are accepted.
 */
class ANS100WorkspaceConceptInputBridge {
    data class HypothesisReport(
        val hypothesisId: String,
        val fromLabel: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val toLabel: String,
        val premiseEvidenceRefs: List<String>,
        val verificationStatus: ANS100ConceptMemory.VerificationStatus,
        val supportingEvidenceRefs: List<String>,
        val contradictingEvidenceRefs: List<String>,
        val supportingEvidenceSources: Set<ANS100ConceptMemory.LearningSource> = emptySet(),
        val contradictingEvidenceSources: Set<ANS100ConceptMemory.LearningSource> = emptySet(),
    )

    data class Result(
        val batch: AndroidHybridEvidenceInputAdapter.ActivationBatch,
        val eligibleConceptCount: Int,
        val activeConceptCount: Int,
        val skippedConceptCount: Int,
        val eligibleRelationCount: Int,
        val learnedRelationCount: Int,
        val activeRelationCount: Int,
        val hypotheses: List<ANS100ConceptMemory.Hypothesis>,
        val hypothesisVerifications: List<ANS100ConceptMemory.Verification>,
        val hypothesisReports: List<HypothesisReport>,
        val relationEvidenceCount: Int,
        val causalChainHypotheses: List<ANS100CausalHypothesisEngine.Candidate> = emptyList(),
        val relationObservations: List<ANS100ConceptMemory.RelationEvidence> = emptyList(),
    ) {
        val unresolvedHypothesisCount: Int
            get() = hypothesisVerifications.count {
                it.status == ANS100ConceptMemory.VerificationStatus.UNRESOLVED
            }
    }

    fun merge(
        evidenceBatch: AndroidHybridEvidenceInputAdapter.ActivationBatch,
        rawUserMessage: String,
        workspaceMemories: List<LocalWorkspaceMemory>,
        neuralInputSlotResolver: ((List<String>) -> Map<String, Int>)? = null,
    ): Result {
        require(evidenceBatch.turnId.isNotBlank())
        require(evidenceBatch.timestampNs > 0L)
        require(workspaceMemories.size <= LocalWorkspaceMemoryCache.MAX_CACHED_RECORDS)

        // Keep the existing evidence-only route for inputs outside the lexical
        // encoder's documented bound instead of silently truncating the message.
        if (rawUserMessage.length > ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS) {
            return emptyResult(evidenceBatch)
        }

        val approved = workspaceMemories.filter { it.userApproved }
        val eligibleConcepts = approved.asSequence()
            .filter { it.category.trim().lowercase(Locale.ROOT) in CONCEPT_CATEGORIES }
            .filter { it.content.isNotBlank() && it.content.trim().length <= ANS100ConceptMemory.MAX_LABEL_CHARS }
            .filter { '\n' !in it.content && '\r' !in it.content }
            .sortedBy { it.id }
            .take(ANS100ConceptMemory.MAX_CONCEPTS)
            .toList()
        val eligibleRelationEvidence = approved.asSequence()
            .filter { it.category.trim().lowercase(Locale.ROOT) in RELATION_EVIDENCE_CATEGORIES }
            .filter { it.content.isNotBlank() && it.content.trim().length <= MAX_RELATION_EVIDENCE_CHARS }
            .filter { '\n' !in it.content && '\r' !in it.content }
            .sortedBy { it.id }
            .take(ANS100ConceptMemory.MAX_VERIFICATION_EVIDENCE)
            .toList()
        val eligibleRelations = approved.asSequence()
            .filter { it.category.trim().lowercase(Locale.ROOT) in RELATION_CATEGORIES }
            .filter { it.content.isNotBlank() && it.content.trim().length <= MAX_RELATION_CHARS }
            .filter { '\n' !in it.content && '\r' !in it.content }
            .sortedBy { it.id }
            .take(ANS100ConceptMemory.MAX_RELATIONS)
            .toList()

        val memory = ANS100ConceptMemory()
        val conceptIdsByLabel = LinkedHashMap<String, MutableList<String>>()
        var learnedConceptCount = 0
        eligibleConcepts.forEach { record ->
            val label = record.content.trim()
            val id = conceptId(record.id)
            if (memory.learnConcept(
                    ANS100ConceptMemory.Concept(id = id, label = label),
                    ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
                )
            ) {
                learnedConceptCount++
                conceptIdsByLabel.getOrPut(labelKey(label)) { ArrayList() }.add(id)
            }
        }

        var learnedRelationCount = 0
        eligibleRelations.forEach { record ->
            val parsed = parseRelation(record.content) ?: return@forEach
            val fromIds = conceptIdsByLabel[labelKey(parsed.fromLabel)].orEmpty()
            val toIds = conceptIdsByLabel[labelKey(parsed.toLabel)].orEmpty()
            // Ambiguous or missing endpoints are rejected instead of guessing.
            if (fromIds.size != 1 || toIds.size != 1 || fromIds.single() == toIds.single()) {
                return@forEach
            }
            val relation = ANS100ConceptMemory.Relation(
                id = relationId(record.id),
                fromConceptId = fromIds.single(),
                type = parsed.type,
                toConceptId = toIds.single(),
                strength = 1.0,
                source = ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
                evidenceRef = "workspace-memory:${record.id}",
            )
            if (memory.learnRelation(relation)) learnedRelationCount++
        }

        val relationObservations = eligibleRelationEvidence.mapNotNull { record ->
            val parsed = parseRelationEvidence(record.content) ?: return@mapNotNull null
            val fromIds = conceptIdsByLabel[labelKey(parsed.fromLabel)].orEmpty()
            val toIds = conceptIdsByLabel[labelKey(parsed.toLabel)].orEmpty()
            if (fromIds.size != 1 || toIds.size != 1 || fromIds.single() == toIds.single()) {
                return@mapNotNull null
            }
            ANS100ConceptMemory.RelationEvidence(
                fromConceptId = fromIds.single(),
                type = parsed.type,
                toConceptId = toIds.single(),
                supports = parsed.supports,
                source = ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
                evidenceRef = "workspace-memory-evidence:${record.id}",
            )
        }.take(ANS100ConceptMemory.MAX_VERIFICATION_EVIDENCE)

        val frame = ANS100NeuralInputAdapter().encode(evidenceBatch.turnId, rawUserMessage)
        val activations = ANS100ConceptActivationAdapter().activate(frame, memory)
        // Reserve the complete approved inventory once, not just concepts
        // active in this message. SQLite preserves these IDs across later turns.
        val persistentNeuronIds = neuralInputSlotResolver?.invoke(
            memory.conceptsSnapshot().map { it.id },
        )
        val conceptBatch = ANS100ConceptNeuralInputAdapter().createBatch(
            activations = activations,
            memory = memory,
            timestampNs = evidenceBatch.timestampNs,
            neuronIdsByConceptId = persistentNeuronIds,
        )
        val merged = ANS100ConceptNeuralInputAdapter().merge(evidenceBatch, conceptBatch)
        val activeConceptIds = activations.activations.map { it.conceptId }.toSet()
        val activeRelations = activeConceptIds.asSequence()
            .flatMap { memory.related(it, limit = ANS100ConceptMemory.MAX_RECALL_LIMIT).asSequence() }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .take(MAX_ACTIVE_RELATIONS_PER_TURN)
            .toList()
        val hypotheses = memory.generateHypothesesFor(
            activeConceptIds = activeConceptIds,
            limit = MAX_HYPOTHESES_PER_TURN,
        )
        val verifications = hypotheses.map { memory.verify(it, relationObservations) }
        val hypothesisReports = hypotheses.zip(verifications).take(MAX_REPORTED_HYPOTHESES).map { (hypothesis, verification) ->
            HypothesisReport(
                hypothesisId = hypothesis.id,
                fromLabel = memory.recallConcept(hypothesis.fromConceptId)?.label ?: hypothesis.fromConceptId,
                relationType = hypothesis.type,
                toLabel = memory.recallConcept(hypothesis.toConceptId)?.label ?: hypothesis.toConceptId,
                premiseEvidenceRefs = hypothesis.evidenceRefs,
                verificationStatus = verification.status,
                supportingEvidenceRefs = verification.supportingEvidenceRefs,
                contradictingEvidenceRefs = verification.contradictingEvidenceRefs,
                supportingEvidenceSources = verification.supportingSources,
                contradictingEvidenceSources = verification.contradictingSources,
            )
        }
        // C3: causal chains are separate, explicitly unresolved candidates until
        // direct A->C evidence exists. Their premise edges are not confirmation.
        val causalChainHypotheses = ANS100CausalHypothesisEngine().generate(
            memory = memory,
            activeConceptIds = activeConceptIds,
            observations = relationObservations,
            limit = MAX_CAUSAL_HYPOTHESES_PER_TURN,
        )
        val causalReports = causalChainHypotheses.map { candidate ->
            HypothesisReport(
                hypothesisId = candidate.hypothesisId,
                fromLabel = memory.recallConcept(candidate.fromConceptId)?.label ?: candidate.fromConceptId,
                relationType = candidate.relationType,
                toLabel = memory.recallConcept(candidate.toConceptId)?.label ?: candidate.toConceptId,
                premiseEvidenceRefs = candidate.premiseEvidenceRefs,
                verificationStatus = when (candidate.status) {
                    ANS100CausalHypothesisEngine.Status.UNRESOLVED ->
                        ANS100ConceptMemory.VerificationStatus.UNRESOLVED
                    ANS100CausalHypothesisEngine.Status.SUPPORTED_BY_STORED_RECORDS ->
                        ANS100ConceptMemory.VerificationStatus.SUPPORTED
                    ANS100CausalHypothesisEngine.Status.REFUTED_BY_STORED_RECORDS ->
                        ANS100ConceptMemory.VerificationStatus.REFUTED
                    ANS100CausalHypothesisEngine.Status.CONFLICTING_STORED_RECORDS ->
                        ANS100ConceptMemory.VerificationStatus.CONFLICTING
                },
                supportingEvidenceRefs = candidate.supportingEvidenceRefs,
                contradictingEvidenceRefs = candidate.contradictingEvidenceRefs,
                supportingEvidenceSources = candidate.supportingEvidenceSources,
                contradictingEvidenceSources = candidate.contradictingEvidenceSources,
            )
        }
        val allHypothesisReports = (hypothesisReports + causalReports)
            .take(MAX_REPORTED_HYPOTHESES + MAX_CAUSAL_HYPOTHESES_PER_TURN)

        return Result(
            batch = merged,
            eligibleConceptCount = learnedConceptCount,
            activeConceptCount = conceptBatch.trace.size,
            skippedConceptCount = approved.size - learnedConceptCount - learnedRelationCount -
                relationObservations.size + conceptBatch.skippedSlotCount,
            eligibleRelationCount = eligibleRelations.size,
            learnedRelationCount = learnedRelationCount,
            activeRelationCount = activeRelations.size,
            hypotheses = hypotheses,
            hypothesisVerifications = verifications,
            hypothesisReports = allHypothesisReports,
            relationEvidenceCount = relationObservations.size,
            causalChainHypotheses = causalChainHypotheses,
            relationObservations = relationObservations,
        )
    }

    private data class ParsedRelation(
        val fromLabel: String,
        val type: ANS100ConceptMemory.RelationType,
        val toLabel: String,
    )

    private data class ParsedRelationEvidence(
        val fromLabel: String,
        val type: ANS100ConceptMemory.RelationType,
        val toLabel: String,
        val supports: Boolean,
    )

    private fun parseRelation(raw: String): ParsedRelation? {
        val fields = raw.split('|')
        if (fields.size != 3) return null
        val from = fields[0].trim()
        val typeText = fields[1].trim()
        val to = fields[2].trim()
        if (from.isBlank() || to.isBlank() ||
            from.length > ANS100ConceptMemory.MAX_LABEL_CHARS ||
            to.length > ANS100ConceptMemory.MAX_LABEL_CHARS
        ) return null
        val type = runCatching {
            ANS100ConceptMemory.RelationType.valueOf(typeText.uppercase(Locale.ROOT))
        }.getOrNull() ?: return null
        return ParsedRelation(from, type, to)
    }

    private fun parseRelationEvidence(raw: String): ParsedRelationEvidence? {
        val fields = raw.split('|')
        if (fields.size != 4) return null
        val from = fields[0].trim()
        val typeText = fields[1].trim()
        val to = fields[2].trim()
        val polarity = fields[3].trim().uppercase(Locale.ROOT)
        if (from.isBlank() || to.isBlank() ||
            from.length > ANS100ConceptMemory.MAX_LABEL_CHARS ||
            to.length > ANS100ConceptMemory.MAX_LABEL_CHARS
        ) return null
        val type = runCatching {
            ANS100ConceptMemory.RelationType.valueOf(typeText.uppercase(Locale.ROOT))
        }.getOrNull() ?: return null
        val supports = when (polarity) {
            "SUPPORTS" -> true
            "REFUTES" -> false
            else -> return null
        }
        return ParsedRelationEvidence(from, type, to, supports)
    }

    private fun labelKey(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun conceptId(memoryId: String): String = "memory:${digest(memoryId)}"
    private fun relationId(memoryId: String): String = "relation:${digest(memoryId)}"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun emptyResult(batch: AndroidHybridEvidenceInputAdapter.ActivationBatch) = Result(
        batch = batch,
        eligibleConceptCount = 0,
        activeConceptCount = 0,
        skippedConceptCount = 0,
        eligibleRelationCount = 0,
        learnedRelationCount = 0,
        activeRelationCount = 0,
        hypotheses = emptyList(),
        hypothesisVerifications = emptyList(),
        hypothesisReports = emptyList(),
        relationEvidenceCount = 0,
    )

    companion object {
        private const val MAX_RELATION_CHARS = 240
        private const val MAX_RELATION_EVIDENCE_CHARS = 280
        private const val MAX_ACTIVE_RELATIONS_PER_TURN = 64
        private const val MAX_HYPOTHESES_PER_TURN = 64
        private const val MAX_REPORTED_HYPOTHESES = 12
        private const val MAX_CAUSAL_HYPOTHESES_PER_TURN = 32
        private val CONCEPT_CATEGORIES = setOf("concept", "понятие")
        private val RELATION_CATEGORIES = setOf("relation", "связь")
        private val RELATION_EVIDENCE_CATEGORIES = setOf("relation_evidence", "свидетельство_связи")
    }
}
