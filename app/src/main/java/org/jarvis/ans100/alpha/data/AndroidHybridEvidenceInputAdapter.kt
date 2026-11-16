package org.jarvis.ans100.alpha.data

import java.security.MessageDigest

/**
 * Converts typed evidence into a bounded, provenance-preserving pulse batch for
 * the hybrid v4 evidence-input population (neuron IDs 0..1199).
 *
 * This is deliberately a presence encoder, not a semantic text encoder:
 * non-boolean values activate a stable feature slot but their contents are not
 * hashed into neuron IDs. Boolean values use a paired false/true slot. The full
 * value remains available in the original TypedEvidenceBundle for later,
 * separately reviewed processing.
 */
object AndroidHybridEvidenceInputAdapter {
    const val MAX_ACTIVE_INPUTS_PER_TURN = 128
    private const val SLOT_WIDTH_PER_FEATURE = 48
    private const val MAX_ORDINAL_PER_FEATURE = SLOT_WIDTH_PER_FEATURE / 2
    private const val ACTIVE_PULSE_STRENGTH = 1.0

    /**
     * This order is a persisted input-ID contract. Append new features only;
     * never reorder existing entries or derive IDs from user text.
     */
    private val featureOrder = listOf(
        EvidenceFeature.USER_MESSAGE_TEXT,
        EvidenceFeature.MESSAGE_LENGTH_CHARS,
        EvidenceFeature.MESSAGE_HAS_QUESTION_MARK,
        EvidenceFeature.MESSAGE_HAS_EXCLAMATION_MARK,
        EvidenceFeature.MESSAGE_HAS_LINE_BREAK,
        EvidenceFeature.CORE_LANGUAGE_HINT,
        EvidenceFeature.CORE_INTENT_HINT,
        EvidenceFeature.CORE_INTENT_CONFIDENCE_HINT,
        EvidenceFeature.CORE_TASK_KIND_HINT,
        EvidenceFeature.CORE_KEY_TERM_HINT,
        EvidenceFeature.CORE_ENTITY_HINT,
        EvidenceFeature.CORE_NEGATION_HINT,
        EvidenceFeature.CORE_EXPLANATION_HINT,
        EvidenceFeature.CORE_CONTEXT_REFERENCE_STATUS,
        EvidenceFeature.CORE_CONTEXT_REFERENCE_METHOD,
        EvidenceFeature.CORE_PLAN_GOAL_HINT,
        EvidenceFeature.CORE_PLAN_STEP_HINT,
        EvidenceFeature.CORE_REQUIRES_CLARIFICATION_HINT,
        EvidenceFeature.CORE_CLARIFICATION_QUESTION_HINT,
        EvidenceFeature.CORE_EVIDENCE_NOTE_HINT,
        EvidenceFeature.APPROVED_MEMORY_REFERENCE,
        EvidenceFeature.VERIFIED_TOOL_RESULT,
    )

    data class TraceEntry(
        val recordId: String,
        val neuronId: Int,
        val featureId: String,
        val source: EvidenceSourceKind,
        val status: EvidenceStatus,
        val valueKind: EvidenceValueKind,
        val provenanceRef: String,
        /** Original bounded hint; not a calibrated probability. */
        val activationHint: Double,
    )

    data class ActivationBatch(
        val turnId: String,
        val timestampNs: Long,
        val events: List<AndroidNeuronEvent>,
        val trace: List<TraceEntry>,
        val conceptTrace: List<ANS100ConceptNeuralInputAdapter.TraceEntry> = emptyList(),
    )

    fun createBatch(bundle: TypedEvidenceBundle, timestampNs: Long): ActivationBatch {
        require(timestampNs > 0L) { "hybrid input timestamp must be positive" }
        require(bundle.records.size <= TypedEvidenceBundle.MAX_RECORDS_PER_TURN)
        require(bundle.records.size <= MAX_ACTIVE_INPUTS_PER_TURN) {
            "hybrid input record budget exceeded"
        }

        val turnToken = MessageDigest.getInstance("SHA-256")
            .digest(bundle.turnId.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val events = ArrayList<AndroidNeuronEvent>(bundle.records.size)
        val trace = ArrayList<TraceEntry>(bundle.records.size)
        val usedNeuronIds = HashSet<Int>()

        bundle.records.forEachIndexed { index, record ->
            require(record.turnId == bundle.turnId) { "cross-turn evidence is not allowed" }
            validateSourceAndStatus(record)
            val neuronId = neuronIdFor(record)
            require(usedNeuronIds.add(neuronId)) {
                "two evidence records map to the same hybrid input neuron"
            }
            if (record.activation <= 0.0) return@forEachIndexed

            events += AndroidNeuronEvent(
                eventId = "ans100-v2-$timestampNs-$turnToken-$index",
                timeNs = timestampNs,
                targetNeuronId = neuronId,
                // The current LIF threshold is 1.0. This is a presence pulse;
                // activationHint is preserved in trace, not treated as confidence.
                strength = ACTIVE_PULSE_STRENGTH,
            )
            trace += TraceEntry(
                recordId = record.recordId,
                neuronId = neuronId,
                featureId = record.feature.stableId,
                source = record.source,
                status = record.status,
                valueKind = record.value.kind,
                provenanceRef = record.provenanceRef,
                activationHint = record.activation,
            )
        }

        return ActivationBatch(
            turnId = bundle.turnId,
            timestampNs = timestampNs,
            events = events.toList(),
            trace = trace.toList(),
        )
    }

    /** Stable feature/ordinal mapping into IDs 0..1055; candidate lanes use reserved IDs 1056..1061. */
    fun neuronIdFor(record: TypedEvidenceRecord): Int {
        validateSourceAndStatus(record)
        val candidateInputId = when (record.feature) {
            EvidenceFeature.CANDIDATE_ANSWER_SUPPORT ->
                ANS100CandidateSlotContract.inputNeuronId(
                    ANS100CandidateSlotContract.Slot.ANSWER,
                    ANS100CandidateSlotContract.Polarity.SUPPORT,
                )
            EvidenceFeature.CANDIDATE_ANSWER_CONFLICT ->
                ANS100CandidateSlotContract.inputNeuronId(
                    ANS100CandidateSlotContract.Slot.ANSWER,
                    ANS100CandidateSlotContract.Polarity.CONFLICT,
                )
            EvidenceFeature.CANDIDATE_CLARIFICATION_SUPPORT ->
                ANS100CandidateSlotContract.inputNeuronId(
                    ANS100CandidateSlotContract.Slot.CLARIFICATION,
                    ANS100CandidateSlotContract.Polarity.SUPPORT,
                )
            EvidenceFeature.CANDIDATE_CLARIFICATION_CONFLICT ->
                ANS100CandidateSlotContract.inputNeuronId(
                    ANS100CandidateSlotContract.Slot.CLARIFICATION,
                    ANS100CandidateSlotContract.Polarity.CONFLICT,
                )
            EvidenceFeature.CANDIDATE_ABSTAIN_SUPPORT ->
                ANS100CandidateSlotContract.inputNeuronId(
                    ANS100CandidateSlotContract.Slot.ABSTAIN,
                    ANS100CandidateSlotContract.Polarity.SUPPORT,
                )
            EvidenceFeature.CANDIDATE_ABSTAIN_CONFLICT ->
                ANS100CandidateSlotContract.inputNeuronId(
                    ANS100CandidateSlotContract.Slot.ABSTAIN,
                    ANS100CandidateSlotContract.Polarity.CONFLICT,
                )
            else -> null
        }
        if (candidateInputId != null) return candidateInputId
        val featureIndex = featureOrder.indexOf(record.feature)
        require(featureIndex >= 0) { "feature is not assigned to the hybrid input population" }

        val ordinalText = record.recordId.substringAfterLast(':', missingDelimiterValue = "")
        require(ordinalText.isNotEmpty() && ordinalText.all { it.isDigit() }) {
            "evidence record ID must end in its explicit numeric ordinal"
        }
        val ordinal = ordinalText.toIntOrNull()
            ?: throw IllegalArgumentException("evidence record ordinal is invalid")
        require(ordinal in 0 until MAX_ORDINAL_PER_FEATURE) {
            "evidence feature ordinal exceeds its reserved input slots"
        }

        val channel = when (val value = record.value) {
            is EvidenceValue.BooleanValue -> if (value.value) 1 else 0
            else -> 0
        }
        return featureIndex * SLOT_WIDTH_PER_FEATURE + ordinal * 2 + channel
    }

    private fun validateSourceAndStatus(record: TypedEvidenceRecord) {
        val valid = when (record.feature) {
            EvidenceFeature.USER_MESSAGE_TEXT ->
                record.source == EvidenceSourceKind.USER_MESSAGE &&
                    record.status == EvidenceStatus.USER_PROVIDED
            EvidenceFeature.MESSAGE_LENGTH_CHARS,
            EvidenceFeature.MESSAGE_HAS_QUESTION_MARK,
            EvidenceFeature.MESSAGE_HAS_EXCLAMATION_MARK,
            EvidenceFeature.MESSAGE_HAS_LINE_BREAK ->
                record.source == EvidenceSourceKind.MESSAGE_STRUCTURE &&
                    record.status == EvidenceStatus.OBSERVED_STRUCTURE
            EvidenceFeature.CORE_PLAN_GOAL_HINT,
            EvidenceFeature.CORE_PLAN_STEP_HINT,
            EvidenceFeature.CORE_REQUIRES_CLARIFICATION_HINT,
            EvidenceFeature.CORE_CLARIFICATION_QUESTION_HINT,
            EvidenceFeature.CANDIDATE_ANSWER_SUPPORT,
            EvidenceFeature.CANDIDATE_ANSWER_CONFLICT,
            EvidenceFeature.CANDIDATE_CLARIFICATION_SUPPORT,
            EvidenceFeature.CANDIDATE_CLARIFICATION_CONFLICT,
            EvidenceFeature.CANDIDATE_ABSTAIN_SUPPORT,
            EvidenceFeature.CANDIDATE_ABSTAIN_CONFLICT ->
                record.source == EvidenceSourceKind.CORE_REASONING_PLAN &&
                    record.status == EvidenceStatus.HEURISTIC
            EvidenceFeature.CORE_LANGUAGE_HINT,
            EvidenceFeature.CORE_INTENT_HINT,
            EvidenceFeature.CORE_INTENT_CONFIDENCE_HINT,
            EvidenceFeature.CORE_TASK_KIND_HINT,
            EvidenceFeature.CORE_KEY_TERM_HINT,
            EvidenceFeature.CORE_ENTITY_HINT,
            EvidenceFeature.CORE_NEGATION_HINT,
            EvidenceFeature.CORE_EXPLANATION_HINT,
            EvidenceFeature.CORE_CONTEXT_REFERENCE_STATUS,
            EvidenceFeature.CORE_CONTEXT_REFERENCE_METHOD,
            EvidenceFeature.CORE_EVIDENCE_NOTE_HINT ->
                record.source == EvidenceSourceKind.CORE_UNDERSTANDING &&
                    record.status == EvidenceStatus.HEURISTIC
            EvidenceFeature.APPROVED_MEMORY_REFERENCE ->
                record.source == EvidenceSourceKind.APPROVED_MEMORY &&
                    record.status == EvidenceStatus.USER_APPROVED_REFERENCE &&
                    record.value is EvidenceValue.Reference
            EvidenceFeature.VERIFIED_TOOL_RESULT ->
                record.source == EvidenceSourceKind.VERIFIED_TOOL_RESULT &&
                    record.status == EvidenceStatus.VERIFIED_TOOL_OUTPUT
        }
        require(valid) { "evidence source/status does not match its declared feature" }
    }
}
