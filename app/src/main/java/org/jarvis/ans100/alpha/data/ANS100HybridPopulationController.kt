package org.jarvis.ans100.alpha.data

/**
 * Converts completed hybrid-population telemetry into a conservative dialogue
 * gate. The graph currently has no semantic candidate decoder: Core supplies
 * the candidate, while the seven populations must demonstrate a complete
 * propagation path before the candidate can be forwarded as selected.
 */
class ANS100HybridPopulationController {
    fun decide(
        evidence: TypedEvidenceBundle,
        understanding: LocalUnderstanding,
        telemetry: AndroidHybridRunTelemetry,
        processedEventCount: Int,
        pendingEvents: Int,
        budgetExhausted: Boolean,
    ): ANS100Decision {
        require(processedEventCount >= 0)
        require(pendingEvents >= 0)

        val expectedTopology = AndroidHybridTopologyV2.TOPOLOGY_ID
        require(telemetry.topologyId == expectedTopology) {
            "decision requires the ANS-100 hybrid topology"
        }
        require(telemetry.topologyVersion == AndroidHybridTopologyV2.TOPOLOGY_VERSION) {
            "decision requires the current hybrid topology version"
        }

        val requiredPopulationIds = AndroidHybridTopologyV2.populations.map { it.id }
        val activityById = telemetry.populations.associateBy { it.populationId }
        val activePopulationIds = requiredPopulationIds.filter { populationId ->
            (activityById[populationId]?.uniqueSpikingNeurons ?: 0) > 0
        }
        val missingPopulations = requiredPopulationIds.filterNot { it in activePopulationIds }
        val exhausted = budgetExhausted || pendingEvents > 0

        val userRecord = evidence.records.singleOrNull {
            it.feature == EvidenceFeature.USER_MESSAGE_TEXT
        } ?: throw IllegalArgumentException("hybrid decision requires the original user-message evidence")
        val supportingIds = evidence.records.filter {
            it.feature == EvidenceFeature.USER_MESSAGE_TEXT ||
                it.feature == EvidenceFeature.CORE_INTENT_HINT ||
                it.feature == EvidenceFeature.CORE_PLAN_GOAL_HINT ||
                it.feature == EvidenceFeature.CORE_REQUIRES_CLARIFICATION_HINT
        }.map { it.recordId }.distinct().take(ANS100Candidate.MAX_EVIDENCE_REFS)

        val requiresClarification = understanding.reasoningPlan.requiresClarification
        val clarification = understanding.reasoningPlan.clarificationQuestion
            .take(ANS100Decision.MAX_TEXT_CHARS)
            .ifBlank { "Нужно уточнить недостающий контекст." }
        val candidateId = if (requiresClarification) "request_clarification" else "respond_to_original_request"
        val candidate = ANS100Candidate(
            candidateId = candidateId,
            summary = if (requiresClarification) {
                "Запросить недостающий контекст вместо догадки"
            } else {
                "Подготовить ответ на исходное сообщение пользователя"
            },
            supportingEvidenceIds = (supportingIds + userRecord.recordId).distinct()
                .take(ANS100Candidate.MAX_EVIDENCE_REFS),
            activation = 0.0,
        )

        val uncertainty = linkedSetOf(ANS100UncertaintyKind.UNCALIBRATED_MODEL_OUTPUT)
        if (missingPopulations.isNotEmpty()) uncertainty += ANS100UncertaintyKind.MISSING_EVIDENCE
        if (requiresClarification) uncertainty += ANS100UncertaintyKind.AMBIGUOUS_REQUEST
        if (exhausted) uncertainty += ANS100UncertaintyKind.BUDGET_EXHAUSTED

        val status = when {
            exhausted || missingPopulations.isNotEmpty() -> ANS100DecisionStatus.ABSTAINED
            requiresClarification -> ANS100DecisionStatus.NEEDS_CLARIFICATION
            else -> ANS100DecisionStatus.SELECTED
        }
        val abstentionReason = when {
            exhausted -> "Ограниченный вычислительный бюджет исчерпан; сеть не завершила обработку этого запроса."
            missingPopulations.isNotEmpty() ->
                "Не подтверждён полный проход по группам ANS-100: " + missingPopulations.joinToString(", ")
            requiresClarification -> clarification
            else -> null
        }

        val decision = ANS100Decision(
            turnId = evidence.turnId,
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            decisionProducer = ANS100DecisionProducer.HYBRID_POPULATION_CONTROLLER_V1,
            status = status,
            candidates = listOf(candidate),
            selectedCandidateId = if (status == ANS100DecisionStatus.SELECTED) candidateId else null,
            abstentionReason = abstentionReason,
            uncertainty = uncertainty,
            missingEvidence = missingPopulations.map { "hybrid_population_not_reached:$it" }
                .take(ANS100Decision.MAX_CONSTRAINTS),
            responseGoal = understanding.reasoningPlan.goal
                .take(ANS100Decision.MAX_TEXT_CHARS)
                .ifBlank { "Ответить на исходное сообщение пользователя" },
            responseConstraints = listOf(
                "Исходное сообщение пользователя — главный источник запроса",
                "Core формирует предварительного кандидата; его эвристики не являются проверенными фактами",
                "Активность семи популяций подтверждает только прохождение вычислительного пути, а не смысловую истинность",
                "Нейронная активация не является вероятностью; при отказе или уточнении не обходи это решение",
            ),
            activePopulationIds = activePopulationIds,
            processedEventCount = processedEventCount.coerceAtMost(100_000),
            computeBudgetExhausted = exhausted,
        )
        decision.validateAgainst(evidence)
        return decision
    }
}
