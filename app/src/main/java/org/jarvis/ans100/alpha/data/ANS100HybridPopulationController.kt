package org.jarvis.ans100.alpha.data

/**
 * Reads candidate-specific neural output lanes from the version-4 sparse graph.
 *
 * Candidate support/conflict labels are supplied by typed Core heuristics.
 * ANS-100 propagates them through isolated lanes and the controller compares
 * actual spiking readout neurons. These counts are not learned probabilities.
 */
class ANS100HybridPopulationController {
    private data class CandidateReadout(
        val candidate: ANS100Candidate,
        val supportSpikes: Int,
        val conflictSpikes: Int,
        val activation: Double,
    )

    fun decide(
        evidence: TypedEvidenceBundle,
        understanding: LocalUnderstanding,
        telemetry: AndroidHybridRunTelemetry,
        emittedSpikeNeuronIds: Set<Int>,
        processedEventCount: Int,
        pendingEvents: Int,
        budgetExhausted: Boolean,
    ): ANS100Decision {
        require(processedEventCount >= 0)
        require(pendingEvents >= 0)
        require(evidence.turnId.isNotBlank())
        require(emittedSpikeNeuronIds.size <= AndroidHybridTopologyV2.NEURON_COUNT)
        require(emittedSpikeNeuronIds.all { it in 0 until AndroidHybridTopologyV2.NEURON_COUNT })

        require(telemetry.topologyId == AndroidHybridTopologyV2.TOPOLOGY_ID) {
            "decision requires the ANS-100 hybrid topology"
        }
        require(telemetry.topologyVersion == AndroidHybridTopologyV2.TOPOLOGY_VERSION) {
            "decision requires the current candidate-lane topology version"
        }

        val requiredPopulationIds = AndroidHybridTopologyV2.populations.map { it.id }
        require(telemetry.populations.size == requiredPopulationIds.size)
        require(telemetry.populations.map { it.populationId }.toSet() == requiredPopulationIds.toSet()) {
            "hybrid telemetry must contain each population exactly once"
        }
        val activityById = telemetry.populations.associateBy { it.populationId }
        val activePopulationIds = requiredPopulationIds.filter { populationId ->
            (activityById[populationId]?.uniqueSpikingNeurons ?: 0) > 0
        }
        AndroidHybridTopologyV2.populations.forEach { population ->
            val telemetryCount = activityById.getValue(population.id).uniqueSpikingNeurons
            val emittedCount = emittedSpikeNeuronIds.count(population::contains)
            require(telemetryCount == emittedCount) {
                "population telemetry and emitted spike IDs disagree for ${population.id}"
            }
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
            (contextStatus == "ambiguous" || contextStatus == "unavailable")
        val needsClarification = requiresClarification || ambiguousContext

        val intentValue = (intentRecord?.value as? EvidenceValue.Category)?.value
        val taskKindValue = (taskKindRecord?.value as? EvidenceValue.Category)?.value
        val usableIntent = intentValue != null && intentValue != "statement_or_unknown" &&
            taskKindValue != null && understanding.intentConfidence >= 0.4
        val hasGoal = usableIntent && goalRecord != null
        val expectedSignals = mapOf(
            ANS100CandidateSlotContract.Slot.ANSWER to mapOf(
                ANS100CandidateSlotContract.Polarity.SUPPORT to (hasGoal && !needsClarification),
                ANS100CandidateSlotContract.Polarity.CONFLICT to (!hasGoal || needsClarification),
            ),
            ANS100CandidateSlotContract.Slot.CLARIFICATION to mapOf(
                ANS100CandidateSlotContract.Polarity.SUPPORT to needsClarification,
                ANS100CandidateSlotContract.Polarity.CONFLICT to !needsClarification,
            ),
            ANS100CandidateSlotContract.Slot.ABSTAIN to mapOf(
                ANS100CandidateSlotContract.Polarity.SUPPORT to (!hasGoal && !needsClarification),
                ANS100CandidateSlotContract.Polarity.CONFLICT to (hasGoal || needsClarification),
            ),
        )
        val candidateSignals = ANS100CandidateSlotContract.Slot.values().associateWith { slot ->
            ANS100CandidateSlotContract.Polarity.values().associateWith { polarity ->
                val feature = ANS100CandidateSlotContract.evidenceFeature(slot, polarity)
                val record = evidence.records.singleOrNull { it.feature == feature }
                    ?: throw IllegalArgumentException("missing candidate signal: ${feature.stableId}")
                require(record.source == EvidenceSourceKind.CORE_REASONING_PLAN)
                require(record.status == EvidenceStatus.HEURISTIC)
                val active = (record.value as? EvidenceValue.BooleanValue)?.value
                    ?: throw IllegalArgumentException("candidate signal must be boolean")
                require((record.activation > 0.0) == active) {
                    "candidate signal activation does not match its boolean value"
                }
                require(active == expectedSignals.getValue(slot).getValue(polarity)) {
                    "candidate signal does not match the current typed Core evidence"
                }
                record
            }
        }
        ANS100CandidateSlotContract.Slot.values().forEach { slot ->
            val support = (candidateSignals.getValue(slot)
                .getValue(ANS100CandidateSlotContract.Polarity.SUPPORT).value as EvidenceValue.BooleanValue).value
            val conflict = (candidateSignals.getValue(slot)
                .getValue(ANS100CandidateSlotContract.Polarity.CONFLICT).value as EvidenceValue.BooleanValue).value
            require(support != conflict) { "each candidate must have exactly one support/conflict signal" }
        }

        val answerSupport = listOfNotNull(
            userRecord.recordId,
            intentRecord?.recordId,
            taskKindRecord?.recordId,
            goalRecord?.recordId,
            candidateSignals.getValue(ANS100CandidateSlotContract.Slot.ANSWER)
                .getValue(ANS100CandidateSlotContract.Polarity.SUPPORT).recordId,
        ).distinct().take(ANS100Candidate.MAX_EVIDENCE_REFS)
        val answerConflict = listOf(
            candidateSignals.getValue(ANS100CandidateSlotContract.Slot.ANSWER)
                .getValue(ANS100CandidateSlotContract.Polarity.CONFLICT).recordId,
        )
        val clarificationSupport = listOfNotNull(
            userRecord.recordId,
            clarificationRecord.recordId.takeIf { requiresClarification },
            clarificationQuestionRecord?.recordId,
            candidateSignals.getValue(ANS100CandidateSlotContract.Slot.CLARIFICATION)
                .getValue(ANS100CandidateSlotContract.Polarity.SUPPORT).recordId,
        ).distinct().take(ANS100Candidate.MAX_EVIDENCE_REFS)
        val clarificationConflict = listOf(
            candidateSignals.getValue(ANS100CandidateSlotContract.Slot.CLARIFICATION)
                .getValue(ANS100CandidateSlotContract.Polarity.CONFLICT).recordId,
        )
        val abstainSupport = listOf(
            userRecord.recordId,
            candidateSignals.getValue(ANS100CandidateSlotContract.Slot.ABSTAIN)
                .getValue(ANS100CandidateSlotContract.Polarity.SUPPORT).recordId,
        )
        val abstainConflict = listOf(
            candidateSignals.getValue(ANS100CandidateSlotContract.Slot.ABSTAIN)
                .getValue(ANS100CandidateSlotContract.Polarity.CONFLICT).recordId,
        )

        val candidates = listOf(
            ANS100Candidate(
                candidateId = ANS100CandidateSlotContract.Slot.ANSWER.candidateId,
                summary = "Подготовить ответ на исходное сообщение на основании доступных данных",
                supportingEvidenceIds = answerSupport,
                conflictingEvidenceIds = answerConflict,
            ),
            ANS100Candidate(
                candidateId = ANS100CandidateSlotContract.Slot.CLARIFICATION.candidateId,
                summary = "Запросить недостающий контекст вместо догадки",
                supportingEvidenceIds = clarificationSupport,
                conflictingEvidenceIds = clarificationConflict,
            ),
            ANS100Candidate(
                candidateId = ANS100CandidateSlotContract.Slot.ABSTAIN.candidateId,
                summary = "Воздержаться от выбора ответа при недостаточной опоре",
                supportingEvidenceIds = abstainSupport,
                conflictingEvidenceIds = abstainConflict,
            ),
        )

        val rawReadouts = candidates.mapIndexed { index, candidate ->
            val slot = ANS100CandidateSlotContract.Slot.values()[index]
            val supportLane = ANS100CandidateSlotContract.readoutLane(
                slot, ANS100CandidateSlotContract.Polarity.SUPPORT,
            )
            val conflictLane = ANS100CandidateSlotContract.readoutLane(
                slot, ANS100CandidateSlotContract.Polarity.CONFLICT,
            )
            ANS100CandidateCompetition.Input(
                candidateId = candidate.candidateId,
                supportSpikes = ANS100CandidateSlotContract.readoutSpikeCount(
                    emittedSpikeNeuronIds, slot, ANS100CandidateSlotContract.Polarity.SUPPORT,
                ),
                conflictSpikes = ANS100CandidateSlotContract.readoutSpikeCount(
                    emittedSpikeNeuronIds, slot, ANS100CandidateSlotContract.Polarity.CONFLICT,
                ),
                supportCapacity = supportLane.size,
                conflictCapacity = conflictLane.size,
            )
        }
        val competition = ANS100CandidateCompetition().evaluate(rawReadouts)
        val readouts = rawReadouts.mapIndexed { index, input ->
            CandidateReadout(
                candidate = candidates[index],
                supportSpikes = input.supportSpikes,
                conflictSpikes = input.conflictSpikes,
                activation = competition.activations.getValue(input.candidateId),
            )
        }
        val readoutMismatchSlots = readouts.filter { readout ->
            val slot = ANS100CandidateSlotContract.slotForCandidateId(readout.candidate.candidateId)
                ?: return@filter true
            val supportSignal = (candidateSignals.getValue(slot)
                .getValue(ANS100CandidateSlotContract.Polarity.SUPPORT).value as EvidenceValue.BooleanValue).value
            val conflictSignal = (candidateSignals.getValue(slot)
                .getValue(ANS100CandidateSlotContract.Polarity.CONFLICT).value as EvidenceValue.BooleanValue).value
            supportSignal == conflictSignal ||
                supportSignal != (readout.supportSpikes > 0) ||
                conflictSignal != (readout.conflictSpikes > 0)
        }.map { it.candidate.candidateId }
        val selectedId = competition.winnerCandidateId
        val uniqueWinner = readouts.singleOrNull { it.candidate.candidateId == selectedId }

        val uncertainty = linkedSetOf(ANS100UncertaintyKind.UNCALIBRATED_MODEL_OUTPUT)
        if (missingPopulations.isNotEmpty() || readoutMismatchSlots.isNotEmpty()) {
            uncertainty += ANS100UncertaintyKind.MISSING_EVIDENCE
        }
        if (needsClarification) uncertainty += ANS100UncertaintyKind.AMBIGUOUS_REQUEST
        if (exhausted) uncertainty += ANS100UncertaintyKind.BUDGET_EXHAUSTED
        if (uniqueWinner == null) uncertainty += ANS100UncertaintyKind.CONFLICTING_EVIDENCE

        val status = when {
            exhausted || missingPopulations.isNotEmpty() || readoutMismatchSlots.isNotEmpty() ->
                ANS100DecisionStatus.ABSTAINED
            selectedId == ANS100CandidateSlotContract.Slot.ANSWER.candidateId ->
                ANS100DecisionStatus.SELECTED
            selectedId == ANS100CandidateSlotContract.Slot.CLARIFICATION.candidateId ->
                ANS100DecisionStatus.NEEDS_CLARIFICATION
            else -> ANS100DecisionStatus.ABSTAINED
        }
        val abstentionReason = when {
            exhausted -> "Ограниченный вычислительный бюджет исчерпан; обработка запроса не завершена."
            missingPopulations.isNotEmpty() ->
                "Не подтверждён полный проход по группам ANS-100: " + missingPopulations.joinToString(", ")
            readoutMismatchSlots.isNotEmpty() ->
                "Не получен отдельный нейронный readout для кандидатов: " + readoutMismatchSlots.joinToString(", ")
            status == ANS100DecisionStatus.NEEDS_CLARIFICATION -> clarificationQuestion
            status == ANS100DecisionStatus.ABSTAINED && selectedId == ANS100CandidateSlotContract.Slot.ABSTAIN.candidateId ->
                "Нейронный readout поддержал воздержание: недостаточно оснований для прямого ответа."
            status == ANS100DecisionStatus.ABSTAINED ->
                "Нейронные readout-кандидаты дали ничью или не получили положительной поддержки."
            else -> null
        }

        val readoutSummary = readouts.joinToString("; ") {
            "${it.candidate.candidateId}:support=${it.supportSpikes},conflict=${it.conflictSpikes},activation=${"%.3f".format(java.util.Locale.ROOT, it.activation)}"
        } + "; winner_margin=${"%.3f".format(java.util.Locale.ROOT, competition.winnerMargin)}"
        val decision = ANS100Decision(
            turnId = evidence.turnId,
            topologyVersion = ANS100TopologyVersion.HYBRID_SPARSE_POPULATIONS_V1,
            decisionProducer = ANS100DecisionProducer.HYBRID_POPULATION_CONTROLLER_V1,
            status = status,
            candidates = candidates,
            selectedCandidateId = if (status == ANS100DecisionStatus.SELECTED) selectedId else null,
            abstentionReason = abstentionReason,
            uncertainty = uncertainty,
            missingEvidence = buildList {
                missingPopulations.forEach { add("hybrid_population_not_reached:$it") }
                readoutMismatchSlots.forEach { add("candidate_readout_mismatch:$it") }
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
                "Кандидаты конкурируют по нормированной активности поддержки и конфликта с латеральным подавлением; activation не является вероятностью",
                "Победитель принимается только при положительной активации и достаточном отрыве от второго кандидата",
                "Сигналы поддержки и конфликта сформированы Core-эвристиками; сеть пока не выводит их смысл самостоятельно и не обучена",
                "Сравнение readout: $readoutSummary",
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
