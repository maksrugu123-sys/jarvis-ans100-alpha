package org.jarvis.ans100.alpha.data

/**
 * Independent benchmark-only evidence audit.
 *
 * This transparent rule-based audit is NOT the ANS-100 neural answer and is
 * never fed into the neural readout. It provides a reference trace so the
 * experiment can measure whether the neural system agrees with supplied facts.
 */
class ANS100StructuredEvidenceVerifier {
    data class Audit(
        val answer: String,
        val method: String,
        val supportingEvidenceRefs: List<String>,
        val contradictingEvidenceRefs: List<String>,
        val examinedFactCount: Int,
    )

    data class NeuralCandidateAudit(
        val candidateId: String,
        val status: String,
        val forwardSupportingRefs: List<String>,
        val forwardContradictingRefs: List<String>,
        val reverseSupportingRefs: List<String>,
        val reverseContradictingRefs: List<String>,
    )

    /**
     * Cross-checks an unordered co-activation candidate against explicit typed
     * benchmark facts in both directions. It does not promote the candidate to
     * a truth claim and does not alter network learning or readout.
     */
    fun verifyCandidates(
        input: ANS100StructuredBenchmarkInputAdapter.Result,
        candidates: List<ANS100NeuralAssociationDecoder.Candidate>,
    ): List<NeuralCandidateAudit> {
        require(candidates.size <= ANS100NeuralAssociationDecoder.MAX_CANDIDATES)
        return candidates.map { candidate ->
            fun matching(from: String, to: String, positive: Boolean): List<String> =
                input.facts.filter { fact ->
                    val sameDirection = fact.subject.equals(from, ignoreCase = true) &&
                        fact.objectValue.equals(to, ignoreCase = true)
                    val polarityMatches = if (positive) {
                        fact.relation == "connected_positive" || fact.relation == "evidence_supports"
                    } else {
                        fact.relation == "connected_negative" || fact.relation == "evidence_refutes"
                    }
                    sameDirection && polarityMatches
                }.map { it.provenance }.distinct().sorted()
            val forwardSupport = matching(candidate.firstLabel, candidate.secondLabel, true)
            val forwardRefute = matching(candidate.firstLabel, candidate.secondLabel, false)
            val reverseSupport = matching(candidate.secondLabel, candidate.firstLabel, true)
            val reverseRefute = matching(candidate.secondLabel, candidate.firstLabel, false)
            val status = when {
                forwardSupport.isNotEmpty() && forwardRefute.isNotEmpty() -> "FORWARD_CONFLICT"
                reverseSupport.isNotEmpty() && reverseRefute.isNotEmpty() -> "REVERSE_CONFLICT"
                forwardSupport.isNotEmpty() && reverseSupport.isNotEmpty() -> "BOTH_DIRECTIONS_SUPPORTED"
                forwardRefute.isNotEmpty() && reverseRefute.isNotEmpty() -> "BOTH_DIRECTIONS_REFUTED"
                forwardSupport.isNotEmpty() -> "FORWARD_SUPPORTED"
                forwardRefute.isNotEmpty() -> "FORWARD_REFUTED"
                reverseSupport.isNotEmpty() -> "REVERSE_SUPPORTED"
                reverseRefute.isNotEmpty() -> "REVERSE_REFUTED"
                else -> "NO_MATCHING_TYPED_EVIDENCE"
            }
            NeuralCandidateAudit(
                candidateId = candidate.candidateId,
                status = status,
                forwardSupportingRefs = forwardSupport,
                forwardContradictingRefs = forwardRefute,
                reverseSupportingRefs = reverseSupport,
                reverseContradictingRefs = reverseRefute,
            )
        }
    }

    fun verify(input: ANS100StructuredBenchmarkInputAdapter.Result): Audit {
        val query = input.query
        if (query.relation == "directed_path_at_most_two") {
            val edges = input.facts.filter { it.relation == "directed_link" }
            val directPath = edges.firstOrNull {
                it.subject == query.subject && it.objectValue == query.objectValue
            }?.let { listOf(it) }
            val twoHopPath = if (directPath == null) {
                edges.asSequence()
                    .filter { it.subject == query.subject }
                    .mapNotNull { first ->
                        edges.firstOrNull {
                            it.subject == first.objectValue && it.objectValue == query.objectValue
                        }?.let { second -> listOf(first, second) }
                    }
                    .firstOrNull()
            } else null
            val pathEvidence = directPath ?: twoHopPath.orEmpty()
            val found = pathEvidence.isNotEmpty()
            val refs = pathEvidence.map { it.provenance }.distinct().sorted()
            return Audit(
                answer = if (found) "YES" else "NO",
                method = "complete_directed_graph_path_at_most_two",
                supportingEvidenceRefs = if (found) refs else emptyList(),
                contradictingEvidenceRefs = emptyList(),
                examinedFactCount = edges.size,
            )
        }

        val matching = input.facts.filter {
            it.subject == query.subject && it.objectValue == query.objectValue
        }
        val supports = matching.filter { it.relation == "connected_positive" || it.relation == "evidence_supports" }
            .map { it.provenance }.distinct().sorted()
        val refutes = matching.filter { it.relation == "connected_negative" || it.relation == "evidence_refutes" }
            .map { it.provenance }.distinct().sorted()
        val answer = when {
            supports.isNotEmpty() && refutes.isNotEmpty() -> "CONFLICT"
            supports.isNotEmpty() -> "YES"
            refutes.isNotEmpty() -> "NO"
            else -> "UNKNOWN"
        }
        return Audit(
            answer = answer,
            method = if (query.relation == "connected") "direct_relation_evidence" else "explicit_evidence",
            supportingEvidenceRefs = supports,
            contradictingEvidenceRefs = refutes,
            examinedFactCount = matching.size,
        )
    }
}
