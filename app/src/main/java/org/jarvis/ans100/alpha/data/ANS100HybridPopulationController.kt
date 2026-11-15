package org.jarvis.ans100.alpha.data

/**
 * Bounded candidate competition for the current hybrid dialogue path.
 *
 * Important boundary: candidate scores below are transparent controller rules
 * over typed Core evidence, NOT learned neural scores. The seven-population
 * graph is a required participation gate; its current generic topology does
 * not yet encode candidate identity or provide candidate-specific readout.
 */
class ANS100HybridPopulationController {
    private data class CandidateScore(
        val candidate: ANS100Candidate,
        val score: Int,
    )

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
        require(evidence.turnId.isNotBlank())

        require(telemetry.topologyId == AndroidHybridTopologyV2.TOPOLOGY_ID) {
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
        } ?: throw IllegalArgumentException("hybrid decision requires original user-message evidence")
        val intentRecord = evidence.records.singleOrNull {
            it.feature == EvidenceFeature.CORE_INTENT_HINT
        }
        val taskKindRecord = evidence.records.singleOrNull {
            it.feature == EvidenceFeature.CORE_TASK_KIND_HINT
        }
        val goalRecord = evidence.records.singleOrNull {
            it.feature == EvidenceFeature.CORE_PLAN_GOAL_HINT
        }
        val clarificationRecord = evidence.records.singleOrNull {
            it.feature == EvidenceFeature.CORE_REQUIRES_CLARIFICATION_HINT
        } ?: throw IllegalArgumentException("hybrid decision requires the typed clarification flag")
        require(clarificationRecord.source == EvidenceSourceKind.CORE_REASONING_PLAN)
        require(clarificationRecord.status == EvidenceStatus.HEURISTIC)
        val requiresClarification = (clarificationRecord.value as? EvidenceValue.BooleanValue)?.value
            ?: throw IllegalArgumentException("clarification flag must be boolean")
        require(requiresClarification == understanding.reasoningPlan.requiresClarification) {
            "typed clarification evidence does not match the Core reasoning plan"
        }

        val clarificationQuestionRecord = evidence.records.singleOrNull {
            it.feature == EvidenceFeature.CORE_CLARIFICATION_QUESTION_HINT
        }
        val clarificationQuestion = (clarificationQuestionRecord?.value as? EvidenceValue.Text)?.value
            ?.take(ANS100Decision.MAX_TEXT_CHARS)
            .orEmpty()
            .ifBlank { "Нужно уточнить недостающий контекст." }

        val contextStatus = (evidence.records.singleOrNull {
            it.feature == EvidenceFeature.CORE_CONTEXT_REFERENCE_STATUS
        }?.value as? EvidenceValue.Category)?.value
        val ambiguousContext = understanding.refersToPreviousContext &&
            contextStatus in setOf("ambiguous", "unavailable")
        val hasIntent = intentRecord != null && taskKindRecord != null
        val hasGoal = goalRecord != null
        val questionMark = (evidence.records.singleOrNull {
            it.feature == EvidenceFeature.MESSAGE_HAS_QUESTION_MARK
        }?.value as? EvidenceValue.BooleanValue)?.value == true

        val answerSupport = listOfNotNull(
            userRecord.recordId,
            intentRecord?.recordId,
            taskKindRecord?.recordId,
            goalRecord?.recordId,
        ).distinct().take(ANS100Candidate.MAX_EVIDENCE_REFS)
        val clarificationSupport = listOfNotNull(
            userRecord.recordId,
            clarificationRecord.recordId.takeIf { requiresClarification },
            clarificationQuestionRecord?.recordId,
        ).distinct().take(ANS100Candidate.MAX_EVIDENCE_REFS)

        val answerCandidate = ANS100Candidate(
            candidateId = "respond_to_original_request",
            summary = "Подготовить ответ на исходное сообщение на основании доступных данных",
            supportingEvidenceIds = answerSupport,
            conflictingEvidenceIds = if (requiresClarification) {
                listOf(clarificationRecord.recordId)
            } else emptyList(),
        )
        val clarificationCandidate = ANS100Candidate(
            candidateId = "request_clarification",
            summary = "Запросить недостающий контекст вместо догадки",
            supportingEvidenceIds = clarificationSupport,
            conflictingEvidenceIds = if (!requiresClarification) {
                listOf(clarificationRecord.recordId)
            } else emptyList(),
        )
        val abstainCandidate = ANS100Candidate(
            candidateId = "abstain_without_supported_candidate",
            summary = "Воздержаться от выбора ответа при недостаточной опоре",
            supportingEvidenceIds = listOf(userRecord.recordId),
        )

        // Critic rules are deliberately explicit and inspectable. These integer
        // points are ranking heuristics, never probabilities or neural activations.
        val answerScore = (if (hasIntent) 2 else 0) +
            (if (hasGoal) 1 else 0) +
            (if (questionMark) 1 else 0) +
            (if (requiresClarification) -6 else 0)
        val clarificationScore = (if (requiresClarification) 6 else 0) +
            (if (ambiguousContext) 2 else 0) +
            (if (clarificationQuestionRecord != null) 1 else 0)
        val abstainScore = (if (!hasIntent && !hasGoal) 5 else 0) +
            (if (understanding.intentConfidence < 0.4) 1 else 0)

        val ranked = listOf(
            CandidateScore(answerCandidate, answerScore),
            CandidateScore(clarificationCandidate, clarificationScore),
            CandidateScore(abstainCandidate, abstainScore),
        )
        val winner = ranked.maxOf { it.score }
        val chosen = when {
            clarificationScore > 0 && clarificationScore == winner -> "request_clarification"
            answerScore > abstainScore && answerScore == winner -> "respond_to_original_request"
            else -> "abstain_without_supported_candidate"
        }

        val uncertainty = linkedSetOf(ANS100UncertaintyKind.UNCALIBRATED_MODEL_OUTPUT)
        if (missingPopulations.isNotEmpty()) uncertainty += ANS100UncertaintyKind.MISSING_EVIDENCE
        if (requiresClarification || ambiguousContext) uncertainty += ANS100UncertaintyKind.AMBIGUOUS_REQUEST
        if (exhausted) uncertainty += ANS100UncertaintyKind.BUDGET_EXHAUSTED
        if (!hasIntent || !hasGoal) uncertainty += ANS100UncertaintyKind.MISSING_EVIDENCE

        val status = when {
            exhausted || missingPopulations.isNotEmpty() -> ANS100DecisionStatus.ABSTAINED
            chosen == "request_clarification" -> ANS100DecisionStatus.NEEDS_CLARIFICATION
            chosen == "respond_to_original_request" -> ANS100DecisionStatus.SELECTED
            else -> ANS100DecisionStatus.ABSTAINED
        }
        val abstentionReason = when {
            exhausted -> "Ограниченный вычислительный бюджет исчерпан; обработка запроса не завершена."
            missingPopulations.isNotEmpty() ->
                "Не подтверждён полный проход по группам ANS-100: " + missingPopulations.joinToString(", ")
            status == ANS100DecisionStatus.NEEDS_CLARIFICATION -> clarificationQuestion
            status == ANS100DecisionStatus.ABSTAINED ->
                "Недостаточно типизированных оснований для выбора ответа; требуется дополнительный контекст."
            else -> null
        }

        val candidates = ranked.map { it.candidate }
        val decision = ANS100Decision(
            turnId = evidence.turnId,
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            decisionProducer = ANS100DecisionProducer.HYBRID_POPULATION_CONTROLLER_V1,
            status = status,
            candidates = candidates,
            selectedCandidateId = if (status == ANS100DecisionStatus.SELECTED) chosen else null,
            abstentionReason = abstentionReason,
            uncertainty = uncertainty,
            missingEvidence = buildList {
                if (!hasIntent) add("core_intent_or_task_kind_evidence_missing")
                if (!hasGoal) add("core_plan_goal_evidence_missing")
                missingPopulations.forEach { add("hybrid_population_not_reached:$it") }
            }.take(ANS100Decision.MAX_CONSTRAINTS),
            responseGoal = when (status) {
                ANS100DecisionStatus.NEEDS_CLARIFICATION -> "Запросить уточнение: $clarificationQuestion"
                ANS100DecisionStatus.ABSTAINED -> "Не формировать неподтверждённый ответ"
                ANS100DecisionStatus.SELECTED -> (goalRecord?.value as? EvidenceValue.Category)?.value
                    ?.take(ANS100Decision.MAX_TEXT_CHARS)
                    ?.ifBlank { "Ответить на исходное сообщение пользователя" }
                    ?: "Ответить на исходное сообщение пользователя"
            },
            responseConstraints = listOf(
                "Исходное сообщение пользователя — главный источник запроса",
                "Кандидаты и баллы сформированы прозрачными правилами над эвристическим Core evidence, а не обученной семантической сетью",
                "Популяционная активность подтверждает прохождение вычислительного пути, но пока не ранжирует кандидатов по смыслу",
                "Активация не является вероятностью; при уточнении или отказе не обходи это решение",
            ),
            activePopulationIds = activePopulationIds,
            processedEventCount = processedEventCount.coerceAtMost(100_000),
            computeBudgetExhausted = exhausted,
        )
        decision.validateAgainst(evidence)
        return decision
    }
}
