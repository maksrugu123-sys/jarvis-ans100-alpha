package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Parses the benchmark's explicitly declared fact/query grammar and routes
 * each semantic role through a separate, stable input-neuron lane.
 *
 * This is a deterministic structural parser, NOT a semantic language
 * understanding system. Unsupported or ambiguous input is rejected rather
 * than silently flattened into lexical features.
 */
class ANS100StructuredBenchmarkInputAdapter {
    enum class Role(val laneIndex: Int) {
        FACT_SUBJECT(0),
        FACT_RELATION(1),
        FACT_OBJECT(2),
        QUERY_SUBJECT(3),
        QUERY_RELATION(4),
        QUERY_OBJECT(5),
    }

    data class Fact(
        val subject: String,
        val relation: String,
        val objectValue: String,
        val index: Int,
    ) {
        val provenance: String get() = "fact:$index"
    }

    data class Query(val subject: String, val relation: String, val objectValue: String)

    data class Result(
        val batch: AndroidHybridEvidenceInputAdapter.ActivationBatch,
        val parsedFactCount: Int,
        val encodedFeatureCount: Int,
        val activatedFeatureCount: Int,
        val slotCollisionCount: Int,
        val queryParsed: Boolean,
        val facts: List<Fact>,
        val query: Query,
    )

    private data class Feature(val role: Role, val value: String, val provenance: String)

    fun createBatch(turnId: String, inputText: String, timestampNs: Long): Result {
        require(turnId.isNotBlank() && turnId.length <= ANS100NeuralInputFrame.MAX_TURN_ID_CHARS)
        require(inputText.isNotBlank() && inputText.length <= MAX_INPUT_CHARS)
        require(timestampNs > 0L)

        val sections = splitSections(inputText)
        val facts = sections.first.mapIndexed { index, line -> parseFact(line, index) }
        val query = parseQuery(sections.second)
        val features = ArrayList<Feature>(facts.size * 3 + 3)
        facts.forEach { fact ->
            features += Feature(Role.FACT_SUBJECT, fact.subject, "fact:${fact.index}:subject")
            features += Feature(Role.FACT_RELATION, fact.relation, "fact:${fact.index}:relation")
            features += Feature(Role.FACT_OBJECT, fact.objectValue, "fact:${fact.index}:object")
        }
        features += Feature(Role.QUERY_SUBJECT, query.subject, "query:subject")
        features += Feature(Role.QUERY_RELATION, query.relation, "query:relation")
        features += Feature(Role.QUERY_OBJECT, query.objectValue, "query:object")

        val uniqueFeatures = features.distinctBy { it.role to normalize(it.value) }
        val slotOwners = LinkedHashMap<Int, Feature>()
        var collisions = 0
        uniqueFeatures.forEach { feature ->
            val slot = neuronId(feature.role, feature.value)
            if (slot in slotOwners) collisions++ else slotOwners[slot] = feature
        }

        val turnDigest = digest(turnId).take(16)
        val events = ArrayList<AndroidNeuronEvent>(slotOwners.size)
        val trace = ArrayList<ANS100ConceptNeuralInputAdapter.TraceEntry>(slotOwners.size)
        slotOwners.entries.sortedBy { it.key }.forEach { (neuronId, feature) ->
            val valueDigest = digest("${feature.role.name}:${normalize(feature.value)}")
            events += AndroidNeuronEvent(
                eventId = "ans100-structured-$timestampNs-$turnDigest-${neuronId}",
                timeNs = timestampNs,
                targetNeuronId = neuronId,
                strength = 1.0,
                coreTainted = false,
            )
            trace += ANS100ConceptNeuralInputAdapter.TraceEntry(
                conceptId = "structured:${feature.role.name.lowercase()}:${valueDigest.take(24)}",
                neuronId = neuronId,
                matchedAlias = feature.value,
                matchedFeatureIds = listOf("role:${feature.role.name.lowercase()}", "value:${normalize(feature.value)}"),
                activationHint = 1.0,
                provenanceRef = "benchmark-structured:${feature.provenance}:${valueDigest.take(16)}",
            )
        }

        val batch = AndroidHybridEvidenceInputAdapter.ActivationBatch(
            turnId = turnId,
            timestampNs = timestampNs,
            events = events,
            trace = emptyList(),
            conceptTrace = trace,
        )
        return Result(
            batch = batch,
            parsedFactCount = facts.size,
            encodedFeatureCount = uniqueFeatures.size,
            activatedFeatureCount = events.size,
            slotCollisionCount = collisions,
            queryParsed = true,
            facts = facts,
            query = query,
        )
    }

    private fun splitSections(input: String): Pair<List<String>, String> {
        val lines = input.lines()
        val factsHeader = lines.indexOfFirst { it.trim() == "Facts:" }
        val questionIndex = lines.indexOfFirst { it.trim().startsWith("Question:") }
        require(factsHeader >= 0 && questionIndex > factsHeader) {
            "structured benchmark input must contain Facts: and Question: sections"
        }
        val factLines = lines.subList(factsHeader + 1, questionIndex)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "- No evidence." }
        require(factLines.size <= MAX_FACTS)
        val question = lines.subList(questionIndex, lines.size).joinToString(" ").trim()
            .removePrefix("Question:").trim()
        require(question.isNotBlank())
        return factLines to question
    }

    private fun parseFact(line: String, index: Int): Fact {
        val relation = RELATION_FACT.matchEntire(line)
        if (relation != null) {
            val polarity = relation.groupValues[2]
            return Fact(
                relation.groupValues[1],
                if (polarity == "is") "connected_positive" else "connected_negative",
                relation.groupValues[3],
                index,
            )
        }
        val edge = EDGE_FACT.matchEntire(line)
        if (edge != null) return Fact(edge.groupValues[1], "directed_link", edge.groupValues[2], index)
        val evidence = EVIDENCE_FACT.matchEntire(line)
        if (evidence != null) return Fact(
            evidence.groupValues[2],
            if (evidence.groupValues[1] == "supports") "evidence_supports" else "evidence_refutes",
            evidence.groupValues[3],
            index,
        )
        throw IllegalArgumentException("unsupported structured fact at line ${index + 1}: $line")
    }

    private fun parseQuery(question: String): Query {
        val direct = DIRECT_QUERY.matchEntire(question)
        if (direct != null) return Query(direct.groupValues[1], "connected", direct.groupValues[2])
        val path = PATH_QUERY.matchEntire(question)
        if (path != null) return Query(path.groupValues[1], "directed_path_at_most_two", path.groupValues[2])
        val evidence = EVIDENCE_QUERY.matchEntire(question)
        if (evidence != null) return Query(evidence.groupValues[1], "connected", evidence.groupValues[2])
        throw IllegalArgumentException("unsupported structured benchmark question")
    }

    private fun neuronId(role: Role, value: String): Int {
        val digest = digest("${role.name}:${normalize(value)}")
        val laneOffset = digest.take(8).toLong(16) % SLOTS_PER_ROLE
        return INPUT_START + role.laneIndex * SLOTS_PER_ROLE + laneOffset.toInt()
    }

    private fun normalize(value: String): String = value.lowercase()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val INPUT_START = ANS100ConceptMemory.NEURAL_INPUT_START
        const val SLOTS_PER_ROLE = 23
        const val ROLE_COUNT = 6
        const val INPUT_END_EXCLUSIVE = INPUT_START + SLOTS_PER_ROLE * ROLE_COUNT
        const val MAX_INPUT_CHARS = 2_048
        const val MAX_FACTS = 20

        private val RELATION_FACT = Regex("""- ([A-Za-z0-9_:-]+) (is explicitly not|is) connected to ([A-Za-z0-9_:-]+)\.""")
        private val EDGE_FACT = Regex("""- Directed link: ([A-Za-z0-9_:-]+) -> ([A-Za-z0-9_:-]+)\.""")
        private val EVIDENCE_FACT = Regex("""- Evidence (supports|refutes) the claim that ([A-Za-z0-9_:-]+) connects to ([A-Za-z0-9_:-]+)\.""")
        private val DIRECT_QUERY = Regex("""What is the evidence status of 'connected' from ([A-Za-z0-9_:-]+) to ([A-Za-z0-9_:-]+)\? Answer exactly YES, NO, or UNKNOWN\.""")
        private val PATH_QUERY = Regex("""Is there a directed path of at most two links from ([A-Za-z0-9_:-]+) to ([A-Za-z0-9_:-]+)\? Treat the listed graph as complete\. Answer exactly YES or NO\.""")
        private val EVIDENCE_QUERY = Regex("""Classify the evidence about the claim that ([A-Za-z0-9_:-]+) connects to ([A-Za-z0-9_:-]+)\. Answer exactly YES, NO, CONFLICT, or UNKNOWN\.""")
    }
}
