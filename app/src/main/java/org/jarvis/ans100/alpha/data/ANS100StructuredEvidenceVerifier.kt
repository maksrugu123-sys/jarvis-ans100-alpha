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

    fun verify(input: ANS100StructuredBenchmarkInputAdapter.Result): Audit {
        val query = input.query
        if (query.relation == "directed_path_at_most_two") {
            val edges = input.facts.filter { it.relation == "directed_link" }
            val adjacency = edges.groupBy { it.subject }.mapValues { (_, rows) -> rows.map { it.objectValue }.toSet() }
            val direct = query.objectValue in adjacency[query.subject].orEmpty()
            val oneIntermediate = adjacency[query.subject].orEmpty().any { middle ->
                query.objectValue in adjacency[middle].orEmpty()
            }
            val found = direct || oneIntermediate
            val refs = edges.filter { it.subject == query.subject || it.objectValue == query.objectValue ||
                (it.subject in adjacency[query.subject].orEmpty() && it.objectValue == query.objectValue)
            }.map { it.provenance }.distinct().sorted()
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
