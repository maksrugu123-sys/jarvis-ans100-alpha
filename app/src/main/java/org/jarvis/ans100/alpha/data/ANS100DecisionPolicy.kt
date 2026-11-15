package org.jarvis.ans100.alpha.data

/**
 * Compatibility policy adapter for callers that do not have a completed
 * hybrid-population turn. The live Android dialogue route uses
 * ANS100HybridPopulationController and its evidence-bound Qwen handoff instead.
 * This policy remains Core-heuristic orchestration, not a learned neural model.
 */
class ANS100DecisionPolicy {
    fun decide(
        evidence: TypedEvidenceBundle,
        understanding: LocalUnderstanding,
        neuralAnalysis: DialogueNeuralAnalysis?,
    ): ANS100Decision {
        val userMessage = evidence.records.single {
            it.feature == EvidenceFeature.USER_MESSAGE_TEXT
        }
        val intentHint = evidence.records.firstOrNull {
            it.feature == EvidenceFeature.CORE_INTENT_HINT
        }
        val clarificationHint = evidence.records.single {
            it.feature == EvidenceFeature.CORE_REQUIRES_CLARIFICATION_HINT
        }
        val supportingIds = listOfNotNull(
            userMessage.recordId,
            intentHint?.recordId,
            clarificationHint.recordId,
        ).distinct()

        val requiresClarification = understanding.reasoningPlan.requiresClarification
        val clarification = understanding.reasoningPlan.clarificationQuestion
            .take(ANS100Decision.MAX_TEXT_CHARS)
            .ifBlank { "Нужно уточнить недостающий контекст." }
        val candidateId = if (requiresClarification) {
            "request_clarification"
        } else {
            "respond_to_original_request"
        }
        val candidate = ANS100Candidate(
            candidateId = candidateId,
            summary = if (requiresClarification) {
                "Запросить недостающий контекст вместо догадки"
            } else {
                "Подготовить ответ на исходное сообщение, не повышая эвристики до фактов"
            },
            supportingEvidenceIds = supportingIds,
            activation = 0.0,
        )

        val decision = ANS100Decision(
            turnId = evidence.turnId,
            topologyVersion = ANS100TopologyVersion.LEGACY_DIALOGUE_READOUT_V1,
            decisionProducer = ANS100DecisionProducer.CORE_POLICY_V1,
            status = if (requiresClarification) {
                ANS100DecisionStatus.NEEDS_CLARIFICATION
            } else {
                ANS100DecisionStatus.SELECTED
            },
            candidates = listOf(candidate),
            selectedCandidateId = if (requiresClarification) null else candidateId,
            abstentionReason = if (requiresClarification) clarification else null,
            uncertainty = emptySet(),
            responseGoal = understanding.reasoningPlan.goal
                .take(ANS100Decision.MAX_TEXT_CHARS)
                .ifBlank { "Ответить на исходное сообщение пользователя" },
            responseConstraints = listOf(
                "Исходное сообщение пользователя — главный источник запроса",
                "Core intent, terms and plan fields are heuristic hints, not verified facts",
                "Legacy neural readout scores are advisory telemetry, not answer probabilities",
                "Do not execute tools or external actions",
            ),
            processedEventCount = neuralAnalysis?.processedEvents ?: 0,
        )
        decision.validateAgainst(evidence)
        return decision
    }
}
