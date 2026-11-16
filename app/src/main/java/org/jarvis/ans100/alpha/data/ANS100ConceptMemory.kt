package org.jarvis.ans100.alpha.data

/** Bounded concept-level memory with provenance; not yet a neural concept learner. */
class ANS100ConceptMemory(
    private val maxConcepts: Int = MAX_CONCEPTS,
    private val maxRelations: Int = MAX_RELATIONS,
) {
    enum class LearningSource { EXPLICIT_USER, VERIFIED_TOOL, MODEL_GENERATED, IMPLICIT_BEHAVIOR, INFERRED }
    enum class RelationType { IS_A, PART_OF, CAUSES, ENABLES, PREVENTS, HAS_PROPERTY, USED_FOR }
    enum class VerificationStatus { SUPPORTED, REFUTED, CONFLICTING, UNRESOLVED }

    data class Concept(val id: String, val label: String, val aliases: Set<String> = emptySet()) {
        init {
            require(ID_PATTERN.matches(id))
            require(label.isNotBlank() && label.length <= MAX_LABEL_CHARS)
            require(aliases.size <= MAX_ALIASES)
            require(aliases.all { it.isNotBlank() && it.length <= MAX_LABEL_CHARS })
        }
    }

    data class Relation(
        val id: String, val fromConceptId: String, val type: RelationType, val toConceptId: String,
        val strength: Double, val source: LearningSource, val evidenceRef: String,
    ) {
        init {
            require(ID_PATTERN.matches(id) && ID_PATTERN.matches(fromConceptId) && ID_PATTERN.matches(toConceptId))
            require(fromConceptId != toConceptId)
            require(strength.isFinite() && strength in 0.0..1.0)
            require(evidenceRef.isNotBlank() && evidenceRef.length <= MAX_EVIDENCE_REF_CHARS)
        }
    }

    data class Hypothesis(
        val id: String, val fromConceptId: String, val type: RelationType, val toConceptId: String,
        val premiseRelationIds: List<String>, val evidenceRefs: List<String>, val estimatedStrength: Double,
    )
    data class RelationEvidence(
        val fromConceptId: String, val type: RelationType, val toConceptId: String,
        val supports: Boolean, val source: LearningSource, val evidenceRef: String,
    )
    data class Verification(
        val hypothesisId: String, val status: VerificationStatus,
        val supportingEvidenceRefs: List<String>, val contradictingEvidenceRefs: List<String>,
    )

    private val concepts = LinkedHashMap<String, Concept>()
    private val relations = LinkedHashMap<String, Relation>()

    init {
        require(maxConcepts in 1..MAX_CONCEPTS)
        require(maxRelations in 1..MAX_RELATIONS)
    }

    @Synchronized
    fun learnConcept(concept: Concept, source: LearningSource): Boolean {
        if (!source.isTrustedLearningSource()) return false
        val existing = concepts[concept.id]
        if (existing != null) {
            require(existing.label == concept.label) { "concept ID cannot be rebound to another label" }
            concepts[concept.id] = existing.copy(aliases = (existing.aliases + concept.aliases).take(MAX_ALIASES).toSet())
            return true
        }
        if (concepts.size >= maxConcepts) return false
        concepts[concept.id] = concept
        return true
    }

    @Synchronized
    fun learnRelation(relation: Relation): Boolean {
        if (!relation.source.isTrustedLearningSource()) return false
        require(relation.fromConceptId in concepts && relation.toConceptId in concepts) {
            "relation endpoints must already exist in concept memory"
        }
        val existing = relations[relation.id]
        if (existing != null) {
            require(existing == relation) { "relation ID cannot be rebound to different content" }
            return false
        }
        if (relations.size >= maxRelations) return false
        relations[relation.id] = relation
        return true
    }

    @Synchronized
    fun recallConcept(conceptId: String): Concept? = concepts[conceptId]

    @Synchronized
    fun related(conceptId: String, type: RelationType? = null, limit: Int = DEFAULT_RECALL_LIMIT): List<Relation> {
        require(limit in 0..MAX_RECALL_LIMIT)
        if (limit == 0) return emptyList()
        return relations.values.asSequence()
            .filter { (it.fromConceptId == conceptId || it.toConceptId == conceptId) && (type == null || it.type == type) }
            .sortedWith(compareBy<Relation> { it.type.name }.thenBy { it.fromConceptId }.thenBy { it.toConceptId }.thenBy { it.id })
            .take(limit).toList()
    }

    /** Generates bounded transitive candidates only for IS_A and PART_OF. */
    @Synchronized
    fun generateHypotheses(limit: Int = DEFAULT_HYPOTHESIS_LIMIT): List<Hypothesis> {
        require(limit in 0..MAX_HYPOTHESES)
        if (limit == 0) return emptyList()
        val transitiveTypes = setOf(RelationType.IS_A, RelationType.PART_OF)
        val byStart = relations.values.filter { it.type in transitiveTypes }.groupBy { it.fromConceptId }
        val output = LinkedHashMap<String, Hypothesis>()
        for (first in relations.values.sortedBy { it.id }) {
            if (first.type !in transitiveTypes) continue
            for (second in byStart[first.toConceptId].orEmpty().sortedBy { it.id }) {
                if (second.type != first.type || second.toConceptId == first.fromConceptId) continue
                val id = "hyp:${first.id}:${second.id}"
                output.putIfAbsent(id, Hypothesis(
                    id, first.fromConceptId, first.type, second.toConceptId,
                    listOf(first.id, second.id), listOf(first.evidenceRef, second.evidenceRef),
                    (first.strength * second.strength).coerceIn(0.0, 1.0),
                ))
                if (output.size >= limit) return output.values.toList()
            }
        }
        return output.values.toList()
    }

    /**
     * Generates hypotheses reachable from concepts activated by the current input.
     *
     * This is bounded symbolic graph composition, not neural inference. Restricting
     * the starting concepts prevents unrelated memories from entering the turn.
     */
    @Synchronized
    fun generateHypothesesFor(
        activeConceptIds: Set<String>,
        limit: Int = DEFAULT_HYPOTHESIS_LIMIT,
    ): List<Hypothesis> {
        require(limit in 0..MAX_HYPOTHESES)
        require(activeConceptIds.size <= MAX_CONCEPTS)
        require(activeConceptIds.all { it in concepts }) {
            "active concept IDs must already exist in concept memory"
        }
        if (limit == 0 || activeConceptIds.isEmpty()) return emptyList()

        val transitiveTypes = setOf(RelationType.IS_A, RelationType.PART_OF)
        val byStart = relations.values
            .filter { it.type in transitiveTypes }
            .groupBy { it.fromConceptId }
        val output = LinkedHashMap<String, Hypothesis>()
        for (first in relations.values.sortedBy { it.id }) {
            if (first.fromConceptId !in activeConceptIds || first.type !in transitiveTypes) continue
            for (second in byStart[first.toConceptId].orEmpty().sortedBy { it.id }) {
                if (second.type != first.type || second.toConceptId == first.fromConceptId) continue
                val id = "hyp:${first.id}:${second.id}"
                output.putIfAbsent(id, Hypothesis(
                    id, first.fromConceptId, first.type, second.toConceptId,
                    listOf(first.id, second.id), listOf(first.evidenceRef, second.evidenceRef),
                    (first.strength * second.strength).coerceIn(0.0, 1.0),
                ))
                if (output.size >= limit) return output.values.toList()
            }
        }
        return output.values.toList()
    }

    /** Checks a candidate against direct stored relations and separately supplied evidence. */
    @Synchronized
    fun verify(hypothesis: Hypothesis, observations: List<RelationEvidence> = emptyList()): Verification {
        require(observations.size <= MAX_VERIFICATION_EVIDENCE)
        require(hypothesis.premiseRelationIds.size in 1..MAX_PREMISES)
        val matching = buildList {
            addAll(relations.values.filter {
                it.fromConceptId == hypothesis.fromConceptId && it.type == hypothesis.type &&
                    it.toConceptId == hypothesis.toConceptId
            }.map { Triple(it.evidenceRef, true, it.source) })
            observations.filter {
                it.fromConceptId == hypothesis.fromConceptId && it.type == hypothesis.type &&
                    it.toConceptId == hypothesis.toConceptId
            }.forEach { add(Triple(it.evidenceRef, it.supports, it.source)) }
        }
        val support = matching.filter { it.second && it.third.isTrustedLearningSource() }.map { it.first }.distinct().sorted()
        val conflict = matching.filter { !it.second && it.third.isTrustedLearningSource() }.map { it.first }.distinct().sorted()
        val status = when {
            support.isNotEmpty() && conflict.isNotEmpty() -> VerificationStatus.CONFLICTING
            conflict.isNotEmpty() -> VerificationStatus.REFUTED
            support.isNotEmpty() -> VerificationStatus.SUPPORTED
            else -> VerificationStatus.UNRESOLVED
        }
        return Verification(hypothesis.id, status, support, conflict)
    }

    /** Stable bounded snapshot for read-only concept activation; callers cannot mutate memory. */
    @Synchronized
    fun conceptsSnapshot(limit: Int = MAX_CONCEPTS): List<Concept> {
        require(limit in 0..MAX_CONCEPTS)
        return concepts.values.sortedBy { it.id }.take(limit)
    }

    @Synchronized fun conceptCount(): Int = concepts.size
    @Synchronized fun relationCount(): Int = relations.size

    private fun LearningSource.isTrustedLearningSource() =
        this == LearningSource.EXPLICIT_USER || this == LearningSource.VERIFIED_TOOL

    companion object {
        const val MAX_CONCEPTS = 2_048
        const val MAX_RELATIONS = 8_192
        const val MAX_LABEL_CHARS = 96
        const val MAX_ALIASES = 16
        const val MAX_EVIDENCE_REF_CHARS = 320
        const val DEFAULT_RECALL_LIMIT = 24
        const val MAX_RECALL_LIMIT = 96
        const val DEFAULT_HYPOTHESIS_LIMIT = 64
        const val MAX_HYPOTHESES = 256
        const val MAX_VERIFICATION_EVIDENCE = 128
        const val MAX_PREMISES = 8
        private val ID_PATTERN = Regex("^[a-z][a-z0-9_.:-]{0,95}$")
    }
}
