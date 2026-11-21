package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Produces auditable causal-chain CANDIDATES from explicitly approved memory.
 *
 * A -> B and B -> C do not prove A -> C. Every candidate remains unresolved
 * until a separate direct A -> C record supports or refutes it. PREVENTS is
 * deliberately excluded because its composition is not monotonic.
 *
 * This engine never writes inferred relations back into concept memory.
 */
class ANS100CausalHypothesisEngine {
    enum class Status {
        UNRESOLVED,
        SUPPORTED_BY_STORED_RECORDS,
        REFUTED_BY_STORED_RECORDS,
        CONFLICTING_STORED_RECORDS,
    }

    data class Candidate(
        val hypothesisId: String,
        val fromConceptId: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val toConceptId: String,
        val premiseRelationIds: List<String>,
        val premiseEvidenceRefs: List<String>,
        val status: Status,
        val supportingEvidenceRefs: List<String>,
        val contradictingEvidenceRefs: List<String>,
        val supportingEvidenceSources: Set<ANS100ConceptMemory.LearningSource> = emptySet(),
        val contradictingEvidenceSources: Set<ANS100ConceptMemory.LearningSource> = emptySet(),
        val estimatedStrength: Double = 0.0,
    )

    /**
     * Only CAUSES and ENABLES chains are proposed, and only as candidates.
     * Direct verification is performed against independently stored A -> C
     * evidence; the two premise edges are never counted as direct confirmation.
     */
    fun generate(
        memory: ANS100ConceptMemory,
        activeConceptIds: Set<String>,
        observations: List<ANS100ConceptMemory.RelationEvidence> = emptyList(),
        limit: Int = DEFAULT_LIMIT,
    ): List<Candidate> {
        require(activeConceptIds.size <= ANS100ConceptNeuralInputAdapter.MAX_CONCEPTS_PER_TURN)
        require(observations.size <= ANS100ConceptMemory.MAX_VERIFICATION_EVIDENCE)
        require(limit in 0..MAX_LIMIT)
        if (limit == 0 || activeConceptIds.isEmpty()) return emptyList()

        val causalTypes = setOf(
            ANS100ConceptMemory.RelationType.CAUSES,
            ANS100ConceptMemory.RelationType.ENABLES,
        )
        val output = LinkedHashMap<String, Candidate>()
        activeConceptIds.sorted().forEach { startId ->
            causalTypes.sortedBy { it.name }.forEach { type ->
                val firstEdges = memory.related(startId, type, ANS100ConceptMemory.MAX_RECALL_LIMIT)
                    .filter { it.fromConceptId == startId }
                firstEdges.forEach firstLoop@{ first ->
                    val secondEdges = memory.related(
                        first.toConceptId,
                        type,
                        ANS100ConceptMemory.MAX_RECALL_LIMIT,
                    ).filter { it.fromConceptId == first.toConceptId && it.type == type }
                    secondEdges.forEach secondLoop@{ second ->
                        if (second.toConceptId == first.fromConceptId ||
                            second.toConceptId == first.toConceptId
                        ) return@secondLoop
                        val premiseIds = listOf(first.id, second.id)
                        val stableKey = premiseIds.joinToString("|")
                        val id = "causal-chain:${digest(stableKey)}"
                        // One revision candidate per exact relation. If several
                        // premise paths imply the same A -> C relation, keep the
                        // first deterministic path rather than double-counting
                        // the same claim as several independent hypotheses.
                        val relationKey = "${first.fromConceptId}|$type|${second.toConceptId}"
                        if (relationKey in output) return@secondLoop
                        val hypothesis = ANS100ConceptMemory.Hypothesis(
                            id = id,
                            fromConceptId = first.fromConceptId,
                            type = type,
                            toConceptId = second.toConceptId,
                            premiseRelationIds = premiseIds,
                            evidenceRefs = listOf(first.evidenceRef, second.evidenceRef),
                            estimatedStrength = (first.strength * second.strength).coerceIn(0.0, 1.0),
                        )
                        val verification = memory.verify(hypothesis, observations)
                        val status = when (verification.status) {
                            ANS100ConceptMemory.VerificationStatus.SUPPORTED ->
                                Status.SUPPORTED_BY_STORED_RECORDS
                            ANS100ConceptMemory.VerificationStatus.REFUTED ->
                                Status.REFUTED_BY_STORED_RECORDS
                            ANS100ConceptMemory.VerificationStatus.CONFLICTING ->
                                Status.CONFLICTING_STORED_RECORDS
                            ANS100ConceptMemory.VerificationStatus.UNRESOLVED ->
                                Status.UNRESOLVED
                        }
                        output[relationKey] = Candidate(
                            hypothesisId = id,
                            fromConceptId = hypothesis.fromConceptId,
                            relationType = type,
                            toConceptId = hypothesis.toConceptId,
                            premiseRelationIds = premiseIds,
                            premiseEvidenceRefs = hypothesis.evidenceRefs,
                            status = status,
                            supportingEvidenceRefs = verification.supportingEvidenceRefs,
                            contradictingEvidenceRefs = verification.contradictingEvidenceRefs,
                            supportingEvidenceSources = verification.supportingSources,
                            contradictingEvidenceSources = verification.contradictingSources,
                            estimatedStrength = hypothesis.estimatedStrength,
                        )
                        if (output.size >= limit) return output.values.toList()
                    }
                }
            }
        }
        return output.values.toList()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_LIMIT = 32
        const val MAX_LIMIT = 64
    }
}
