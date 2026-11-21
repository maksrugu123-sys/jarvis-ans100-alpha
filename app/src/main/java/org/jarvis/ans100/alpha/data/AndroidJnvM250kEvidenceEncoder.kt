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
    )

    fun encode(evidence: TypedEvidenceBundle, inputTimeNs: Long): Encoding {
        require(inputTimeNs >= 0L)
        require(evidence.records.isNotEmpty()) { "cannot encode an empty evidence bundle" }
        val usedTargets = HashSet<Int>(evidence.records.size)
        val mapping = LinkedHashMap<String, Int>(evidence.records.size)
        val events = ArrayList<AndroidNeuronEvent>(evidence.records.size)

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
        return Encoding(
            turnId = evidence.turnId,
            events = events,
            recordToNeuronId = mapping,
            recordLabels = evidence.records.associate { it.recordId to it.feature.stableId },
            recordProvenance = evidence.records.associate { it.recordId to it.provenanceRef },
            recordStatuses = evidence.records.associate { it.recordId to it.status },
            heuristicRecordCount = evidence.heuristicRecordCount,
            userProvidedRecordCount = evidence.records.count { it.status == EvidenceStatus.USER_PROVIDED },
        )
    }

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
}
