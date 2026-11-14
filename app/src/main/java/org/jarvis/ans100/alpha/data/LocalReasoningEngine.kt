package org.jarvis.ans100.alpha.data

private object LocalReasoningRules {
    const val MAX_ACTIONS = 48
    const val MAX_FACTS = 256
    const val MAX_DEPTH = 12
    const val MAX_EXPANDED_STATES = 10_000
    const val MAX_SUMMARY_CHARS = 256
    val IDENTIFIER = Regex("^[a-z][a-z0-9_]{0,63}$")
    val SOCIAL_INTENTS = setOf("greeting", "thanks", "farewell")
    fun validateFacts(facts: Set<String>, name: String) {
        require(facts.size <= MAX_FACTS) { "$name contains too many facts" }
        require(facts.all { IDENTIFIER.matches(it) }) { "$name contains an invalid fact identifier" }
    }
}

/**
 * Bounded symbolic planner owned by JARVIS Core.
 *
 * This engine searches a caller-supplied finite fact/action space. It does not
 * infer world facts, browse the network, execute actions, or authorize tools.
 */
enum class LocalReasoningStatus {
    SOLVED,
    NO_PLAN,
    DEPTH_LIMIT,
    SEARCH_LIMIT,
}

data class LocalReasoningAction(
    val actionId: String,
    val summary: String,
    val preconditions: Set<String>,
    val addFacts: Set<String>,
    val deleteFacts: Set<String> = emptySet(),
) {
    init {
        require(LocalReasoningRules.IDENTIFIER.matches(actionId)) { "actionId is not a stable identifier" }
        require(summary.isNotBlank() && summary.length <= LocalReasoningRules.MAX_SUMMARY_CHARS) {
            "summary is empty or too long"
        }
        LocalReasoningRules.validateFacts(preconditions, "preconditions")
        LocalReasoningRules.validateFacts(addFacts, "addFacts")
        LocalReasoningRules.validateFacts(deleteFacts, "deleteFacts")
        require(addFacts.isNotEmpty() || deleteFacts.isNotEmpty()) {
            "an action must change at least one fact"
        }
        require(addFacts.intersect(deleteFacts).isEmpty()) {
            "one action cannot add and delete the same fact"
        }
    }
}

data class LocalReasoningProblem(
    val initialFacts: Set<String>,
    val goalFacts: Set<String>,
    val actions: List<LocalReasoningAction>,
    val maxDepth: Int = 8,
) {
    init {
        LocalReasoningRules.validateFacts(initialFacts, "initialFacts")
        LocalReasoningRules.validateFacts(goalFacts, "goalFacts")
        require(goalFacts.isNotEmpty()) { "goalFacts must not be empty" }
        require(actions.size <= LocalReasoningRules.MAX_ACTIONS) { "too many actions" }
        require(actions.map { it.actionId }.distinct().size == actions.size) {
            "action IDs must be unique"
        }
        require(maxDepth in 1..LocalReasoningRules.MAX_DEPTH) { "maxDepth is outside its bound" }
    }
}

data class LocalReasoningResult(
    val status: LocalReasoningStatus,
    val actionIds: List<String>,
    val steps: List<String>,
    val exploredStates: Int,
    val finalFacts: Set<String>,
    val missingGoalFacts: Set<String>,
    val executionAuthorized: Boolean = false,
    val scope: String = "bounded_symbolic_dependency_search",
) {
    fun toModelContext(): String = buildString {
        appendLine("JARVIS SYMBOLIC REASONING V1")
        appendLine("Статус поиска: ${status.name.lowercase()}; исследовано состояний: $exploredStates.")
        appendLine("Область: $scope; выполнение разрешено: $executionAuthorized.")
        if (steps.isNotEmpty()) {
            appendLine("Проверенная последовательность зависимостей:")
            steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
        }
        if (missingGoalFacts.isNotEmpty()) {
            appendLine("Невыполненные условия цели: ${missingGoalFacts.sorted().joinToString(", ")}.")
        }
        append("Это проверка структуры плана, а не доказательство истинности фактов или самостоятельного выполнения действий.")
    }
}

class LocalReasoningEngine(
    private val maxExpandedStates: Int = 512,
) {
    init {
        require(maxExpandedStates in 1..LocalReasoningRules.MAX_EXPANDED_STATES) {
            "maxExpandedStates is outside its bound"
        }
    }

    private data class SearchNode(
        val facts: Set<String>,
        val path: List<LocalReasoningAction>,
    )

    fun plan(problem: LocalReasoningProblem): LocalReasoningResult {
        val orderedActions = problem.actions.sortedBy { it.actionId }
        val initial = problem.initialFacts.toSortedSet()
        val queue = ArrayDeque<SearchNode>()
        queue.addLast(SearchNode(initial, emptyList()))
        val seen = mutableSetOf(stateKey(initial))
        var expanded = 0
        var depthLimited = false
        var bestFacts: Set<String> = initial

        while (queue.isNotEmpty()) {
            if (expanded >= maxExpandedStates) {
                return result(
                    LocalReasoningStatus.SEARCH_LIMIT,
                    emptyList(),
                    expanded,
                    bestFacts,
                    problem.goalFacts,
                )
            }
            val node = queue.removeFirst()
            expanded++
            bestFacts = node.facts
            if (node.facts.containsAll(problem.goalFacts)) {
                return result(
                    LocalReasoningStatus.SOLVED,
                    node.path,
                    expanded,
                    node.facts,
                    problem.goalFacts,
                )
            }
            if (node.path.size >= problem.maxDepth) {
                if (orderedActions.any { node.facts.containsAll(it.preconditions) }) {
                    depthLimited = true
                }
                continue
            }

            for (action in orderedActions) {
                if (!node.facts.containsAll(action.preconditions)) continue
                val nextFacts = (node.facts - action.deleteFacts + action.addFacts).toSortedSet()
                if (nextFacts == node.facts) continue
                val key = stateKey(nextFacts)
                if (!seen.add(key)) continue
                queue.addLast(SearchNode(nextFacts, node.path + action))
            }
        }

        return result(
            if (depthLimited) LocalReasoningStatus.DEPTH_LIMIT else LocalReasoningStatus.NO_PLAN,
            emptyList(),
            expanded,
            bestFacts,
            problem.goalFacts,
        )
    }

    /**
     * Converts Core's bounded text analysis into a finite planning problem.
     * Extracted terms remain untrusted hints and never become verified facts.
     */
    fun planFor(understanding: LocalUnderstanding): LocalReasoningResult {
        val facts = linkedSetOf("message_received", "intent_recognized")
        val hasFocus = understanding.keyTerms.isNotEmpty() || understanding.entities.isNotEmpty()
        val contextResolved = !understanding.refersToPreviousContext ||
            understanding.contextReference.status == "selected"
        if (hasFocus) facts += "focus_available"
        if (contextResolved) facts += "context_reference_resolved"
        if (understanding.reasoningPlan.requiresClarification) facts += "clarification_requested"
        if (understanding.intent in LocalReasoningRules.SOCIAL_INTENTS) facts += "social_intent"

        val actions = mutableListOf(
            LocalReasoningAction(
                actionId = "review_scope",
                summary = "Сопоставить исходное сообщение с эвристическим намерением и границами запроса",
                preconditions = setOf("message_received", "intent_recognized"),
                addFacts = setOf("scope_reviewed"),
            ),
            LocalReasoningAction(
                actionId = "check_polarity",
                summary = "Учесть отрицание и не обращать его смысл без проверки",
                preconditions = setOf("scope_reviewed"),
                addFacts = setOf("polarity_reviewed"),
            ),
        )

        if (understanding.reasoningPlan.requiresClarification) {
            actions += LocalReasoningAction(
                actionId = "request_clarification",
                summary = understanding.reasoningPlan.clarificationQuestion.ifBlank {
                    "Запросить недостающий контекст"
                },
                preconditions = setOf("scope_reviewed", "clarification_requested"),
                addFacts = setOf("clarification_ready"),
            )
            return plan(
                LocalReasoningProblem(
                    initialFacts = facts,
                    goalFacts = setOf("clarification_ready"),
                    actions = actions,
                    maxDepth = 4,
                ),
            )
        }

        if ("social_intent" in facts) {
            actions += LocalReasoningAction(
                actionId = "prepare_social_response",
                summary = "Подготовить ответ на простое социальное сообщение без выдумывания фактов",
                preconditions = setOf("scope_reviewed", "polarity_reviewed"),
                addFacts = setOf("response_context_ready"),
            )
        } else {
            actions += LocalReasoningAction(
                actionId = "resolve_focus",
                summary = "Проверить, что предмет ответа присутствует в сообщении",
                preconditions = setOf("scope_reviewed", "focus_available"),
                addFacts = setOf("focus_resolved"),
            )
            actions += LocalReasoningAction(
                actionId = "review_context",
                summary = "Проверить выбранную ссылку на недавний контекст",
                preconditions = setOf("scope_reviewed", "context_reference_resolved"),
                addFacts = setOf("context_reviewed"),
            )
            actions += LocalReasoningAction(
                actionId = "preserve_constraints",
                summary = "Сохранить отрицания, ограничения и границы запроса",
                preconditions = setOf("focus_resolved", "context_reviewed", "polarity_reviewed"),
                addFacts = setOf("constraints_preserved"),
            )
            actions += LocalReasoningAction(
                actionId = "prepare_response",
                summary = "Сформировать проверяемый контекст ответа, оставив фактическую проверку отдельным этапом",
                preconditions = setOf("constraints_preserved"),
                addFacts = setOf("response_context_ready"),
            )
        }

        return plan(
            LocalReasoningProblem(
                initialFacts = facts,
                goalFacts = setOf("response_context_ready"),
                actions = actions,
                maxDepth = LocalReasoningRules.MAX_DEPTH,
            ),
        )
    }

    private fun result(
        status: LocalReasoningStatus,
        path: List<LocalReasoningAction>,
        expanded: Int,
        facts: Set<String>,
        goals: Set<String>,
    ) = LocalReasoningResult(
        status = status,
        actionIds = path.map { it.actionId },
        steps = path.map { it.summary },
        exploredStates = expanded,
        finalFacts = facts,
        missingGoalFacts = goals - facts,
    )

    private fun stateKey(facts: Set<String>): String = facts.sorted().joinToString("\u0001")

}
