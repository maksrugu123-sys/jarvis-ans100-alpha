package org.jarvis.ans100.alpha.data

/**
 * Typed, provenance-preserving evidence contract for the ANS-100 hybrid path.
 *
 * This is an input schema, not a semantic model. Values emitted from
 * LocalUnderstandingPipeline are explicitly marked HEURISTIC. Structural
 * message features describe form only and must not be interpreted as meaning.
 */
enum class EvidenceSourceKind {
    USER_MESSAGE,
    MESSAGE_STRUCTURE,
    CORE_UNDERSTANDING,
    CORE_REASONING_PLAN,
    CONVERSATION_CONTEXT,
    APPROVED_MEMORY,
    VERIFIED_TOOL_RESULT,
}

enum class EvidenceStatus {
    USER_PROVIDED,
    OBSERVED_STRUCTURE,
    HEURISTIC,
    USER_APPROVED_REFERENCE,
    VERIFIED_TOOL_OUTPUT,
}

enum class EvidenceValueKind {
    TEXT,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    CATEGORY,
    REFERENCE,
}

/** Stable feature identifiers. Do not derive these identifiers from arbitrary user text. */
enum class EvidenceFeature(val stableId: String) {
    USER_MESSAGE_TEXT("user.message.text"),
    MESSAGE_LENGTH_CHARS("message.structure.length_chars"),
    MESSAGE_HAS_QUESTION_MARK("message.structure.has_question_mark"),
    MESSAGE_HAS_EXCLAMATION_MARK("message.structure.has_exclamation_mark"),
    MESSAGE_HAS_LINE_BREAK("message.structure.has_line_break"),
    CORE_LANGUAGE_HINT("core.language_hint"),
    CORE_INTENT_HINT("core.intent_hint"),
    CORE_INTENT_CONFIDENCE_HINT("core.intent_confidence_hint"),
    CORE_TASK_KIND_HINT("core.task_kind_hint"),
    CORE_KEY_TERM_HINT("core.key_term_hint"),
    CORE_ENTITY_HINT("core.entity_hint"),
    CORE_NEGATION_HINT("core.negation_hint"),
    CORE_EXPLANATION_HINT("core.explanation_hint"),
    CORE_CONTEXT_REFERENCE_STATUS("core.context_reference_status"),
    CORE_CONTEXT_REFERENCE_METHOD("core.context_reference_method"),
    CORE_PLAN_GOAL_HINT("core.plan_goal_hint"),
    CORE_PLAN_STEP_HINT("core.plan_step_hint"),
    CORE_REQUIRES_CLARIFICATION_HINT("core.requires_clarification_hint"),
    CORE_CLARIFICATION_QUESTION_HINT("core.clarification_question_hint"),
    CORE_EVIDENCE_NOTE_HINT("core.evidence_note_hint"),
    APPROVED_MEMORY_REFERENCE("memory.approved_reference"),
    VERIFIED_TOOL_RESULT("tool.verified_result"),
    CANDIDATE_ANSWER_SUPPORT("ans100.candidate.answer.support"),
    CANDIDATE_ANSWER_CONFLICT("ans100.candidate.answer.conflict"),
    CANDIDATE_CLARIFICATION_SUPPORT("ans100.candidate.clarification.support"),
    CANDIDATE_CLARIFICATION_CONFLICT("ans100.candidate.clarification.conflict"),
    CANDIDATE_ABSTAIN_SUPPORT("ans100.candidate.abstain.support"),
    CANDIDATE_ABSTAIN_CONFLICT("ans100.candidate.abstain.conflict"),
}

sealed class EvidenceValue {
    abstract val kind: EvidenceValueKind

    data class Text(val value: String) : EvidenceValue() {
        override val kind = EvidenceValueKind.TEXT
    }

    data class BooleanValue(val value: Boolean) : EvidenceValue() {
        override val kind = EvidenceValueKind.BOOLEAN
    }

    data class IntegerValue(val value: Long) : EvidenceValue() {
        override val kind = EvidenceValueKind.INTEGER
    }

    data class DecimalValue(val value: Double) : EvidenceValue() {
        init { require(value.isFinite()) { "evidence decimal must be finite" } }
        override val kind = EvidenceValueKind.DECIMAL
    }

    data class Category(val value: String) : EvidenceValue() {
        override val kind = EvidenceValueKind.CATEGORY
    }

    data class Reference(val value: String) : EvidenceValue() {
        override val kind = EvidenceValueKind.REFERENCE
    }
}

data class TypedEvidenceRecord(
    val recordId: String,
    val turnId: String,
    val feature: EvidenceFeature,
    val source: EvidenceSourceKind,
    val status: EvidenceStatus,
    val value: EvidenceValue,
    /** Bounded input activation hint; not a probability or calibrated confidence. */
    val activation: Double,
    /** Identifies the exact field/turn/tool reference from which this record came. */
    val provenanceRef: String,
) {
    init {
        require(recordId.isNotBlank() && recordId.length <= MAX_ID_CHARS)
        require(turnId.isNotBlank() && turnId.length <= MAX_ID_CHARS)
        require(activation.isFinite() && activation in 0.0..1.0)
        require(provenanceRef.isNotBlank() && provenanceRef.length <= MAX_PROVENANCE_CHARS)
        when (value) {
            is EvidenceValue.Text -> require(value.value.length <= MAX_VALUE_CHARS)
            is EvidenceValue.Category -> require(value.value.isNotBlank() && value.value.length <= MAX_CATEGORY_CHARS)
            is EvidenceValue.Reference -> require(value.value.isNotBlank() && value.value.length <= MAX_PROVENANCE_CHARS)
            is EvidenceValue.DecimalValue -> Unit
            is EvidenceValue.IntegerValue -> Unit
            is EvidenceValue.BooleanValue -> Unit
        }
    }

    companion object {
        const val MAX_ID_CHARS = 96
        const val MAX_PROVENANCE_CHARS = 320
        const val MAX_VALUE_CHARS = 2_048
        const val MAX_CATEGORY_CHARS = 80
    }
}

data class TypedEvidenceBundle(
    val schema: String = SCHEMA_V1,
    val turnId: String,
    val records: List<TypedEvidenceRecord>,
) {
    init {
        require(schema == SCHEMA_V1) { "unsupported evidence schema" }
        require(turnId.isNotBlank() && turnId.length <= TypedEvidenceRecord.MAX_ID_CHARS)
        require(records.size <= MAX_RECORDS_PER_TURN) { "evidence record budget exceeded" }
        require(records.all { it.turnId == turnId }) { "evidence cannot cross turn boundaries" }
        require(records.map { it.recordId }.distinct().size == records.size) {
            "evidence record IDs must be unique within a turn"
        }
    }

    val heuristicRecordCount: Int
        get() = records.count { it.status == EvidenceStatus.HEURISTIC }

    val verifiedToolRecordCount: Int
        get() = records.count { it.status == EvidenceStatus.VERIFIED_TOOL_OUTPUT }

    companion object {
        const val SCHEMA_V1 = "JARVIS_TYPED_EVIDENCE_V1"
        const val MAX_RECORDS_PER_TURN = 64
    }
}

/**
 * These input wrappers are intentionally constructible only inside this module.
 * Adapters must obtain them from the user-approved memory store or the
 * permission-gated tool-result verifier; callers must not relabel arbitrary text.
 */
data class ApprovedMemoryEvidence internal constructor(
    val referenceId: String,
    val category: String,
) {
    init {
        require(referenceId.isNotBlank() && referenceId.length <= TypedEvidenceRecord.MAX_PROVENANCE_CHARS)
        require(category.isNotBlank() && category.length <= TypedEvidenceRecord.MAX_CATEGORY_CHARS)
    }
}

data class VerifiedToolEvidence internal constructor(
    val toolId: String,
    val resultReference: String,
    val boundedSummary: String,
) {
    init {
        require(toolId.isNotBlank() && toolId.length <= TypedEvidenceRecord.MAX_CATEGORY_CHARS)
        require(resultReference.isNotBlank() && resultReference.length <= TypedEvidenceRecord.MAX_PROVENANCE_CHARS)
        require(boundedSummary.isNotBlank() && boundedSummary.length <= TypedEvidenceRecord.MAX_VALUE_CHARS)
    }
}

data class TypedEvidenceInput(
    val turnId: String,
    val rawUserMessage: String,
    val understanding: LocalUnderstanding,
    val approvedMemory: List<ApprovedMemoryEvidence> = emptyList(),
    val verifiedToolResults: List<VerifiedToolEvidence> = emptyList(),
)

/**
 * Deterministic bounded adapter from the current Core heuristic contract.
 *
 * It deliberately does not claim that key terms/entities are semantically
 * understood. They are retained as heuristic hints with field-level provenance.
 * Raw message text remains user-provided data, never an instruction to this
 * encoder or to a future agent controller.
 */
class TypedEvidenceEncoder {
    fun encode(input: TypedEvidenceInput): TypedEvidenceBundle {
        require(input.turnId.isNotBlank() && input.turnId.length <= TypedEvidenceRecord.MAX_ID_CHARS)
        require(input.rawUserMessage.length <= MAX_RAW_MESSAGE_CHARS) {
            "raw message exceeds the typed evidence input bound"
        }

        val records = ArrayList<TypedEvidenceRecord>(TypedEvidenceBundle.MAX_RECORDS_PER_TURN)

        fun add(
            feature: EvidenceFeature,
            source: EvidenceSourceKind,
            status: EvidenceStatus,
            value: EvidenceValue,
            provenance: String,
            activation: Double = DEFAULT_ACTIVATION,
            ordinal: Int = 0,
        ) {
            if (records.size >= TypedEvidenceBundle.MAX_RECORDS_PER_TURN) return
            records += TypedEvidenceRecord(
                recordId = "${feature.stableId}:$ordinal",
                turnId = input.turnId,
                feature = feature,
                source = source,
                status = status,
                value = value,
                activation = activation.coerceIn(0.0, 1.0),
                provenanceRef = provenance,
            )
        }

        val message = input.rawUserMessage
        add(
            EvidenceFeature.USER_MESSAGE_TEXT,
            EvidenceSourceKind.USER_MESSAGE,
            EvidenceStatus.USER_PROVIDED,
            EvidenceValue.Text(message),
            "turn:${input.turnId}:user_message",
            activation = if (message.isBlank()) 0.0 else 1.0,
        )
        add(
            EvidenceFeature.MESSAGE_LENGTH_CHARS,
            EvidenceSourceKind.MESSAGE_STRUCTURE,
            EvidenceStatus.OBSERVED_STRUCTURE,
            EvidenceValue.IntegerValue(message.length.toLong()),
            "turn:${input.turnId}:message.length",
            activation = (message.length.toDouble() / MAX_RAW_MESSAGE_CHARS).coerceAtMost(1.0),
        )
        add(
            EvidenceFeature.MESSAGE_HAS_QUESTION_MARK,
            EvidenceSourceKind.MESSAGE_STRUCTURE,
            EvidenceStatus.OBSERVED_STRUCTURE,
            EvidenceValue.BooleanValue('?' in message || '？' in message),
            "turn:${input.turnId}:message.question_mark",
        )
        add(
            EvidenceFeature.MESSAGE_HAS_EXCLAMATION_MARK,
            EvidenceSourceKind.MESSAGE_STRUCTURE,
            EvidenceStatus.OBSERVED_STRUCTURE,
            EvidenceValue.BooleanValue('!' in message || '！' in message),
            "turn:${input.turnId}:message.exclamation_mark",
        )
        add(
            EvidenceFeature.MESSAGE_HAS_LINE_BREAK,
            EvidenceSourceKind.MESSAGE_STRUCTURE,
            EvidenceStatus.OBSERVED_STRUCTURE,
            EvidenceValue.BooleanValue('\n' in message || '\r' in message),
            "turn:${input.turnId}:message.line_break",
        )

        val core = input.understanding
        fun coreText(feature: EvidenceFeature, value: String, field: String, ordinal: Int = 0) {
            if (value.isNotBlank()) add(
                feature, EvidenceSourceKind.CORE_UNDERSTANDING, EvidenceStatus.HEURISTIC,
                EvidenceValue.Category(value.take(TypedEvidenceRecord.MAX_CATEGORY_CHARS)),
                "turn:${input.turnId}:core.$field", activation = core.intentConfidence, ordinal = ordinal,
            )
        }
        coreText(EvidenceFeature.CORE_LANGUAGE_HINT, core.language, "language")
        coreText(EvidenceFeature.CORE_INTENT_HINT, core.intent, "intent")
        add(
            EvidenceFeature.CORE_INTENT_CONFIDENCE_HINT,
            EvidenceSourceKind.CORE_UNDERSTANDING,
            EvidenceStatus.HEURISTIC,
            EvidenceValue.DecimalValue(core.intentConfidence.coerceIn(0.0, 1.0)),
            "turn:${input.turnId}:core.intentConfidence",
            activation = core.intentConfidence.coerceIn(0.0, 1.0),
        )
        coreText(EvidenceFeature.CORE_TASK_KIND_HINT, core.taskKind, "taskKind")
        core.keyTerms.take(MAX_CORE_HINTS).forEachIndexed { index, term ->
            if (term.isNotBlank()) add(
                EvidenceFeature.CORE_KEY_TERM_HINT, EvidenceSourceKind.CORE_UNDERSTANDING,
                EvidenceStatus.HEURISTIC, EvidenceValue.Text(term.take(TypedEvidenceRecord.MAX_VALUE_CHARS)),
                "turn:${input.turnId}:core.keyTerms[$index]", activation = core.intentConfidence, ordinal = index,
            )
        }
        core.entities.take(MAX_CORE_HINTS).forEachIndexed { index, entity ->
            if (entity.isNotBlank()) add(
                EvidenceFeature.CORE_ENTITY_HINT, EvidenceSourceKind.CORE_UNDERSTANDING,
                EvidenceStatus.HEURISTIC, EvidenceValue.Text(entity.take(TypedEvidenceRecord.MAX_VALUE_CHARS)),
                "turn:${input.turnId}:core.entities[$index]", activation = core.intentConfidence, ordinal = index,
            )
        }
        add(
            EvidenceFeature.CORE_NEGATION_HINT, EvidenceSourceKind.CORE_UNDERSTANDING,
            EvidenceStatus.HEURISTIC, EvidenceValue.BooleanValue(core.containsNegation),
            "turn:${input.turnId}:core.containsNegation",
        )
        add(
            EvidenceFeature.CORE_EXPLANATION_HINT, EvidenceSourceKind.CORE_UNDERSTANDING,
            EvidenceStatus.HEURISTIC, EvidenceValue.BooleanValue(core.asksForExplanation),
            "turn:${input.turnId}:core.asksForExplanation",
        )
        coreText(EvidenceFeature.CORE_CONTEXT_REFERENCE_STATUS, core.contextReference.status, "contextReference.status")
        coreText(EvidenceFeature.CORE_CONTEXT_REFERENCE_METHOD, core.contextReference.method, "contextReference.method")
        coreText(EvidenceFeature.CORE_PLAN_GOAL_HINT, core.reasoningPlan.goal, "reasoningPlan.goal")
        core.reasoningPlan.steps.take(MAX_PLAN_HINTS).forEachIndexed { index, step ->
            if (step.isNotBlank()) add(
                EvidenceFeature.CORE_PLAN_STEP_HINT, EvidenceSourceKind.CORE_REASONING_PLAN,
                EvidenceStatus.HEURISTIC, EvidenceValue.Text(step.take(TypedEvidenceRecord.MAX_VALUE_CHARS)),
                "turn:${input.turnId}:core.reasoningPlan.steps[$index]", ordinal = index,
            )
        }
        add(
            EvidenceFeature.CORE_REQUIRES_CLARIFICATION_HINT, EvidenceSourceKind.CORE_REASONING_PLAN,
            EvidenceStatus.HEURISTIC, EvidenceValue.BooleanValue(core.reasoningPlan.requiresClarification),
            "turn:${input.turnId}:core.reasoningPlan.requiresClarification",
        )
        if (core.reasoningPlan.requiresClarification && core.reasoningPlan.clarificationQuestion.isNotBlank()) {
            add(
                EvidenceFeature.CORE_CLARIFICATION_QUESTION_HINT, EvidenceSourceKind.CORE_REASONING_PLAN,
                EvidenceStatus.HEURISTIC,
                EvidenceValue.Text(core.reasoningPlan.clarificationQuestion.take(TypedEvidenceRecord.MAX_VALUE_CHARS)),
                "turn:${input.turnId}:core.reasoningPlan.clarificationQuestion",
            )
        }
        core.evidence.take(MAX_CORE_HINTS).forEachIndexed { index, note ->
            if (note.isNotBlank()) add(
                EvidenceFeature.CORE_EVIDENCE_NOTE_HINT, EvidenceSourceKind.CORE_UNDERSTANDING,
                EvidenceStatus.HEURISTIC, EvidenceValue.Text(note.take(TypedEvidenceRecord.MAX_VALUE_CHARS)),
                "turn:${input.turnId}:core.evidence[$index]", ordinal = index,
            )
        }

        // These six typed signals form the candidate-slot input contract. Their
        // labels are derived from Core heuristics, not learned by ANS-100.
        val usableIntent = core.intent.isNotBlank() &&
            core.intent != "statement_or_unknown" &&
            core.taskKind.isNotBlank() &&
            core.intentConfidence >= 0.4
        val hasGoal = usableIntent && core.reasoningPlan.goal.isNotBlank()
        val ambiguousContext = core.refersToPreviousContext &&
            (core.contextReference.status == "ambiguous" ||
                core.contextReference.status == "unavailable")
        val needsClarification = core.reasoningPlan.requiresClarification || ambiguousContext
        val candidateSignals = listOf(
            EvidenceFeature.CANDIDATE_ANSWER_SUPPORT to (hasGoal && !needsClarification),
            EvidenceFeature.CANDIDATE_ANSWER_CONFLICT to (!hasGoal || needsClarification),
            EvidenceFeature.CANDIDATE_CLARIFICATION_SUPPORT to needsClarification,
            EvidenceFeature.CANDIDATE_CLARIFICATION_CONFLICT to !needsClarification,
            EvidenceFeature.CANDIDATE_ABSTAIN_SUPPORT to (!hasGoal && !needsClarification),
            EvidenceFeature.CANDIDATE_ABSTAIN_CONFLICT to (hasGoal && !needsClarification),
        )
        candidateSignals.forEach { (feature, active) ->
            add(
                feature,
                EvidenceSourceKind.CORE_REASONING_PLAN,
                EvidenceStatus.HEURISTIC,
                EvidenceValue.BooleanValue(active),
                "turn:${input.turnId}:candidate_signal:${feature.stableId}",
                activation = if (active) 1.0 else 0.0,
            )
        }

        input.approvedMemory.take(MAX_EXTERNAL_REFERENCES).forEachIndexed { index, memory ->
            add(
                EvidenceFeature.APPROVED_MEMORY_REFERENCE, EvidenceSourceKind.APPROVED_MEMORY,
                EvidenceStatus.USER_APPROVED_REFERENCE,
                EvidenceValue.Reference(memory.referenceId),
                "memory:${memory.referenceId}:category=${memory.category}",
                ordinal = index,
            )
        }
        input.verifiedToolResults.take(MAX_EXTERNAL_REFERENCES).forEachIndexed { index, result ->
            add(
                EvidenceFeature.VERIFIED_TOOL_RESULT, EvidenceSourceKind.VERIFIED_TOOL_RESULT,
                EvidenceStatus.VERIFIED_TOOL_OUTPUT,
                EvidenceValue.Text(result.boundedSummary),
                "tool:${result.toolId}:result=${result.resultReference}",
                ordinal = index,
            )
        }

        return TypedEvidenceBundle(turnId = input.turnId, records = records.toList())
    }

    companion object {
        const val MAX_RAW_MESSAGE_CHARS = 2_048
        private const val MAX_CORE_HINTS = 10
        private const val MAX_PLAN_HINTS = 6
        private const val MAX_EXTERNAL_REFERENCES = 4
        private const val DEFAULT_ACTIVATION = 0.5
    }
}
