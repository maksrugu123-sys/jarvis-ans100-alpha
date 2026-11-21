package org.jarvis.ans100.alpha.data

/**
 * Deterministic, provenance-preserving projection from typed ANS evidence
 * records into the 250k perception/cognitive input populations.
 *
 * This is a sparse address encoder, not semantic understanding. It hashes
 * stable record metadata (not raw text) into population ranges, preserves
 * heuristic taint, and treats activation only as an uncalibrated input hint.
 */
class AndroidJnvM250kEvidenceEncoder {
    data class Encoding(
        val turnId: String,
        val events: List<AndroidNeuronEvent>,
        val recordToNeuronId: Map<String, Int>,
        val recordLabels: Map<String, String> = emptyMap(),
        val recordProvenance: Map<String, String> = emptyMap(),
        val recordStatuses: Map<String, EvidenceStatus> = emptyMap(),
        val heuristicRecordCount: Int,
        val userProvidedRecordCount: Int,
        val conceptRecordIds: Set<String> = emptySet(),
        val predictionEventCount: Int = 0,
    )

    fun encode(evidence: TypedEvidenceBundle, inputTimeNs: Long): Encoding {
        require(inputTimeNs >= 0L)
        require(evidence.records.isNotEmpty()) { "cannot encode an empty evidence bundle" }
        val usedTargets = HashSet<Int>(evidence.records.size + MAX_LEXICAL_CONCEPTS)
        val mapping = LinkedHashMap<String, Int>(evidence.records.size + MAX_LEXICAL_CONCEPTS)
        val events = ArrayList<AndroidNeuronEvent>(evidence.records.size + MAX_LEXICAL_CONCEPTS)
        val labels = LinkedHashMap<String, String>(evidence.records.size + MAX_LEXICAL_CONCEPTS)
        val provenance = LinkedHashMap<String, String>(evidence.records.size + MAX_LEXICAL_CONCEPTS)
        val statuses = LinkedHashMap<String, EvidenceStatus>(evidence.records.size + MAX_LEXICAL_CONCEPTS)

        evidence.records.forEachIndexed { index, record ->
            require(record.turnId == evidence.turnId)
            val range = inputRangeFor(record)
            var offset = stableHash("${record.feature.stableId}|${record.recordId}") % range.size
            var target = range.firstNeuronId + offset
            var probes = 0
            while (!usedTargets.add(target)) {
                offset = (offset + 1) % range.size
                target = range.firstNeuronId + offset
                probes++
                check(probes < range.size) { "evidence input population is exhausted" }
            }
            mapping[record.recordId] = target
            labels[record.recordId] = record.feature.stableId
            provenance[record.recordId] = record.provenanceRef
            statuses[record.recordId] = record.status
            val magnitude = record.activation.coerceIn(0.0, 1.0)
            val sign = when ((record.value as? EvidenceValue.BooleanValue)?.value) {
                false -> -1.0
                else -> 1.0
            }
            events += AndroidNeuronEvent(
                eventId = "ev-${stableHash(evidence.turnId).toString(16)}-$index-${stableHash(record.recordId).toString(16)}",
                timeNs = inputTimeNs,
                targetNeuronId = target,
                strength = (magnitude * sign).coerceIn(-1.0, 1.0),
                coreTainted = record.status == EvidenceStatus.HEURISTIC,
            )
        }
        val lexicalConceptIds = LinkedHashSet<String>()
        val message = evidence.records.firstOrNull {
            it.feature == EvidenceFeature.USER_MESSAGE_TEXT &&
                it.source == EvidenceSourceKind.USER_MESSAGE &&
                it.value is EvidenceValue.Text
        }?.let { (it.value as EvidenceValue.Text).value }.orEmpty()
        val tokens = tokenize(message).take(MAX_LEXICAL_CONCEPTS)
        val perceptionRange = range(AndroidJnvM250kTopology.Population.PERCEPTION)
        tokens.forEachIndexed { index, token ->
            val recordId = lexicalRecordId(token)
            if (recordId in mapping) return@forEachIndexed
            var offset = stableHash("lexical.token|$recordId") % perceptionRange.size
            var target = perceptionRange.firstNeuronId + offset
            var probes = 0
            while (!usedTargets.add(target)) {
                offset = (offset + 1) % perceptionRange.size
                target = perceptionRange.firstNeuronId + offset
                probes++
                check(probes < perceptionRange.size) { "lexical concept input population is exhausted" }
            }
            mapping[recordId] = target
            labels[recordId] = token
            provenance[recordId] = "turn:${evidence.turnId}:lexical:${stableHash(token).toString(16)}"
            statuses[recordId] = EvidenceStatus.USER_PROVIDED
            lexicalConceptIds += recordId
            events += AndroidNeuronEvent(
                eventId = "lex-${stableHash(evidence.turnId).toString(16)}-$index-${stableHash(recordId).toString(16)}",
                timeNs = inputTimeNs,
                targetNeuronId = target,
                strength = LEXICAL_ACTIVATION,
                coreTainted = false,
            )
        }
        return Encoding(
            turnId = evidence.turnId,
            events = events,
            recordToNeuronId = mapping,
            recordLabels = labels,
            recordProvenance = provenance,
            recordStatuses = statuses,
            heuristicRecordCount = evidence.heuristicRecordCount,
            userProvidedRecordCount = evidence.records.count { it.status == EvidenceStatus.USER_PROVIDED },
            conceptRecordIds = lexicalConceptIds,
        )
    }


    /**
     * Converts only sufficiently repeated prior associations into bounded
     * PREDICTION-population pulses. These pulses are not added to the active
     * lexical concept set, so they cannot masquerade as observed input.
     */
    fun appendPredictions(
        encoding: Encoding,
        predictions: List<ANS100NeuralHypothesisEngine.Prediction>,
        predictionTimeNs: Long,
    ): Encoding {
        require(predictionTimeNs >= 0L)
        require(predictions.size <= ANS100NeuralHypothesisEngine.MAX_PREDICTIONS)
        require(predictions.map { it.predictedConceptId }.distinct().size == predictions.size)
        if (predictions.isEmpty()) return encoding
        val range = range(AndroidJnvM250kTopology.Population.PREDICTION)
        val usedTargets = encoding.events.mapTo(HashSet()) { it.targetNeuronId }
        val events = ArrayList<AndroidNeuronEvent>(encoding.events.size + predictions.size)
        events.addAll(encoding.events)
        val turnToken = stableHash(encoding.turnId).toString(16)
        predictions.forEachIndexed { index, prediction ->
            require(prediction.predictedConceptId.isNotBlank() && prediction.predictedConceptId.length <= 96)
            require(prediction.confidence.isFinite() && prediction.confidence in 0.0..1.0)
            var offset = stableHash("prediction|\u0024{prediction.predictedConceptId}") % range.size
            var target = range.firstNeuronId + offset
            var probes = 0
            while (!usedTargets.add(target)) {
                offset = (offset + 1) % range.size
                target = range.firstNeuronId + offset
                probes++
                check(probes < range.size) { "prediction population is exhausted" }
            }
            events += AndroidNeuronEvent(
                eventId = "pred-\u0024turnToken-\u0024index-\u0024{stableHash(prediction.predictedConceptId).toString(16)}",
                timeNs = predictionTimeNs,
                targetNeuronId = target,
                strength = prediction.confidence.coerceIn(0.0, 1.0),
                coreTainted = prediction.heuristicEvidence,
            )
        }
        return encoding.copy(events = events, predictionEventCount = predictions.size)
    }

    /**
     * Extracts bounded lexical symbols from the actual user message.
     * This is a lexical feature layer, not a synonym/meaning/intent model.
     * Stop words are discarded; tokens are never executed as instructions.
     */
    private fun tokenize(value: String): List<String> {
        val normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT)
        val seen = LinkedHashSet<String>()
        TOKEN_REGEX.findAll(normalized).forEach { match ->
            val token = match.value
            if (token !in STOP_WORDS && seen.size < MAX_LEXICAL_CONCEPTS) seen += token
        }
        return seen.toList()
    }

    private fun lexicalRecordId(token: String): String =
        "lex-token:${stableDigest(token)}"

    private fun stableDigest(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun inputRangeFor(record: TypedEvidenceRecord): AndroidJnvM250kTopology.Range =
        when (record.source) {
            EvidenceSourceKind.USER_MESSAGE,
            EvidenceSourceKind.MESSAGE_STRUCTURE -> range(AndroidJnvM250kTopology.Population.PERCEPTION)
            EvidenceSourceKind.CORE_UNDERSTANDING -> range(AndroidJnvM250kTopology.Population.PATTERN_DETECTION)
            EvidenceSourceKind.CORE_REASONING_PLAN -> range(AndroidJnvM250kTopology.Population.PREDICTION)
            EvidenceSourceKind.CONVERSATION_CONTEXT -> range(AndroidJnvM250kTopology.Population.CONTEXT)
            EvidenceSourceKind.APPROVED_MEMORY -> range(AndroidJnvM250kTopology.Population.MEMORY)
            EvidenceSourceKind.VERIFIED_TOOL_RESULT -> range(AndroidJnvM250kTopology.Population.CONTEXT)
        }

    private fun range(population: AndroidJnvM250kTopology.Population) =
        AndroidJnvM250kTopology.POPULATION_RANGES.single { it.population == population }

    /** Stable 31-bit FNV-1a, independent of JVM hash randomization. */
    private fun stableHash(value: String): Int {
        var hash = 0x811c9dc5.toInt()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toInt() and 0xff)
            hash *= 0x01000193
        }
        return hash and Int.MAX_VALUE
    }
    companion object {
        const val MAX_LEXICAL_CONCEPTS = 24
        const val LEXICAL_ACTIVATION = 0.75
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]{2,32}")
        private val STOP_WORDS = setOf(
            "и", "в", "во", "на", "не", "что", "это", "как", "а", "но", "или", "к", "ко",
            "с", "со", "по", "из", "за", "для", "от", "до", "у", "о", "об", "же", "ли",
            "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "for", "is",
            "are", "was", "were", "be", "with", "as", "at", "by", "from", "that", "this",
        )
    }
}
