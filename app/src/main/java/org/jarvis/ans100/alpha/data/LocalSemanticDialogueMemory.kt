package org.jarvis.ans100.alpha.data

import java.util.Locale

/**
 * Bounded, transcript-derived semantic bookkeeping.
 *
 * This is a conservative heuristic index, not a learned profile or durable store.
 * Every retained item points to the user message that supplied its evidence.
 */
data class LocalSemanticTask(
    val text: String,
    val status: String,
    val sourceMessageId: String,
    val updatedAtMillis: Long,
    val completedByMessageId: String? = null,
    val provenance: String = "explicit_user_request",
)

data class LocalExplicitPreference(
    val text: String,
    val sourceMessageId: String,
    val createdAtMillis: Long,
    val provenance: String = "explicit_user_statement",
)

data class LocalPreferenceConflict(
    val previousText: String,
    val previousMessageId: String,
    val currentText: String,
    val currentMessageId: String,
    val resolution: String = "needs_confirmation",
)

data class LocalSemanticDialogueMemory(
    val activeTasks: List<LocalSemanticTask> = emptyList(),
    val completedTasks: List<LocalSemanticTask> = emptyList(),
    val explicitPreferences: List<LocalExplicitPreference> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val preferenceConflicts: List<LocalPreferenceConflict> = emptyList(),
    val schema: String = "JARVIS_SEMANTIC_DIALOGUE_MEMORY_V1",
    val scope: String = "bounded_transcript_derived_heuristics",
) {
    /** Merge fresh transcript extraction into the durable index without dropping older tasks. */
    fun mergeWith(newer: LocalSemanticDialogueMemory): LocalSemanticDialogueMemory {
        val tasks = LinkedHashMap<String, LocalSemanticTask>()
        (activeTasks + completedTasks).forEach { tasks[it.sourceMessageId] = it }
        (newer.activeTasks + newer.completedTasks).forEach { tasks[it.sourceMessageId] = it }
        val allTasks = tasks.values.toList()
        val preferences = LinkedHashMap<String, LocalExplicitPreference>()
        (explicitPreferences + newer.explicitPreferences).forEach { preferences[it.sourceMessageId] = it }
        return copy(
            activeTasks = allTasks.filter { it.status != "completed" }.takeLast(8),
            completedTasks = allTasks.filter { it.status == "completed" }.takeLast(8),
            explicitPreferences = preferences.values.takeLast(8),
            unresolvedQuestions = newer.unresolvedQuestions.takeLast(4),
            preferenceConflicts = (preferenceConflicts + newer.preferenceConflicts)
                .distinctBy { "${it.previousMessageId}:${it.currentMessageId}" }.takeLast(4),
        )
    }

    fun toModelContext(): String = buildString {
        append("Смысловая память диалога (").append(schema).append("): ")
        append("это извлечённые эвристикой записи, не независимые факты.\n")
        activeTasks.take(MAX_TASKS).forEach {
            append("Активная задача [").append(it.status).append("]: ")
                .append(it.text.take(MAX_ITEM_CHARS)).append(" (источник: ")
                .append(it.sourceMessageId).append(").\n")
        }
        completedTasks.take(MAX_TASKS).forEach {
            append("Завершённая задача: ").append(it.text.take(MAX_ITEM_CHARS))
                .append(" (источник: ").append(it.sourceMessageId).append(").\n")
        }
        explicitPreferences.take(MAX_PREFERENCES).forEach {
            append("Явное предпочтение пользователя: ").append(it.text.take(MAX_ITEM_CHARS))
                .append(" (источник: ").append(it.sourceMessageId).append(").\n")
        }
        preferenceConflicts.take(MAX_CONFLICTS).forEach { conflict ->
            if (conflict.resolution == "needs_confirmation") {
                append("Противоречие в предпочтениях, не выбирать автоматически: «")
                    .append(conflict.previousText.take(MAX_ITEM_CHARS)).append("» и «")
                    .append(conflict.currentText.take(MAX_ITEM_CHARS)).append("». Нужна проверка у пользователя.\n")
            } else {
                append("Пользователь явно обновил предпочтение: «")
                    .append(conflict.previousText.take(MAX_ITEM_CHARS)).append("» → «")
                    .append(conflict.currentText.take(MAX_ITEM_CHARS)).append("».\n")
            }
        }
        unresolvedQuestions.take(MAX_QUESTIONS).forEach {
            append("Незакрытый вопрос: ").append(it.take(MAX_ITEM_CHARS)).append('\n')
        }
    }.take(MAX_CONTEXT_CHARS)

    companion object {
        private const val MAX_HISTORY_MESSAGES = 32
        private const val MAX_TASKS = 8
        private const val MAX_PREFERENCES = 8
        private const val MAX_QUESTIONS = 4
        private const val MAX_CONFLICTS = 4
        private const val MAX_ITEM_CHARS = 220
        private const val MAX_CONTEXT_CHARS = 1_800

        private val TASK_MARKERS = listOf(
            "сделай", "создай", "добавь", "исправь", "проверь", "найди", "реализуй", "напиши",
            "продолжай", "надо", "нужно", "хочу", "сделать", "добавить", "исправить", "write",
            "implement", "build", "fix", "create", "continue",
        )
        private val COMPLETION_MARKERS = listOf(
            "готово", "закончили", "задача завершена", "больше не нужно",
            "это уже сделано", "выполнено", "completed", "finished", "done",
        )
        private val PREFERENCE_MARKERS = listOf(
            "предпочитаю", "мне удобнее", "мне нравится", "отвечай", "пиши",
            "не используй", "используй", "я хочу, чтобы ты", "я хочу чтобы ты", "предпочитаю, чтобы",
            "i prefer", "please answer", "don't use", "do not use",
        )
        private val CORRECTION_MARKERS = listOf(
            "я передумал", "я передумала", "исправление", "вместо этого", "теперь предпочитаю",
            "теперь отвечай", "теперь пиши", "больше не", "отныне", "i changed my mind", "instead",
        )
        private val OPPOSITE_PREFERENCE_PAIRS = listOf(
            "коротк" to "подроб", "кратк" to "подроб", "коротк" to "длинн",
            "русск" to "английск", "формальн" to "неформальн",
            "без эмодзи" to "с эмодзи", "без смайлик" to "с эмодзи",
        )
        private val STOP = setOf(
            "сделай", "создай", "добавь", "исправь", "проверь", "найди", "реализуй",
            "продолжай", "надо", "нужно", "хочу", "мне", "чтобы", "пожалуйста",
            "предпочитаю", "отвечай", "пиши", "используй", "не", "это", "уже",
            "готово", "задача", "завершена", "выполнено", "больше", "теперь",
            "the", "please", "prefer", "answer", "use", "don't", "not",
        )

        fun fromHistory(
            history: List<LocalConversationMessage>,
            baseMemory: LocalSemanticDialogueMemory = LocalSemanticDialogueMemory(),
        ): LocalSemanticDialogueMemory {
            val messages = history.takeLast(MAX_HISTORY_MESSAGES)
            val tasks = (baseMemory.activeTasks + baseMemory.completedTasks).toMutableList()
            val preferences = baseMemory.explicitPreferences.toMutableList()
            val conflicts = baseMemory.preferenceConflicts.toMutableList()
            val questions = mutableListOf<String>()

            messages.forEachIndexed { index, message ->
                if (message.role != ConversationRole.USER || message.text.isBlank()) return@forEachIndexed
                val raw = clean(message.text)
                val normalized = raw.lowercase(Locale.ROOT).replace('ё', 'е')

                if (PREFERENCE_MARKERS.any { marker ->
                        Regex("(?iu)(?<![\\p{L}\\p{N}_])" + Regex.escape(marker) + "(?![\\p{L}\\p{N}_])")
                            .containsMatchIn(normalized)
                    }) {
                    val value = raw.take(MAX_ITEM_CHARS)
                    preferences.removeAll { it.sourceMessageId == message.id }
                    val incoming = LocalExplicitPreference(value, message.id, message.createdAtMillis)
                    val conflicting = preferences.filter { old -> preferencesConflict(old.text, value) }
                    val explicitCorrection = CORRECTION_MARKERS.any { normalized.contains(it) }
                    if (explicitCorrection && conflicting.isNotEmpty()) {
                        conflicting.forEach { old ->
                            preferences.removeAll { it.sourceMessageId == old.sourceMessageId }
                            conflicts.add(LocalPreferenceConflict(
                                old.text, old.sourceMessageId, value, message.id, "resolved_by_explicit_correction",
                            ))
                        }
                    } else if (conflicting.isNotEmpty()) {
                        conflicting.forEach { old ->
                            val conflict = LocalPreferenceConflict(old.text, old.sourceMessageId, value, message.id)
                            if (conflicts.none { it.previousMessageId == old.sourceMessageId && it.currentMessageId == message.id }) {
                                conflicts.add(conflict)
                            }
                        }
                    }
                    if (preferences.none { it.text.equals(value, ignoreCase = true) }) preferences.add(incoming)
                }

                val completion = COMPLETION_MARKERS.any { normalized.contains(it) }
                if (completion) {
                    val completionTerms = terms(raw)
                    val activeIndexes = tasks.indices.filter { tasks[it].status != "completed" }
                    val matching = if (completionTerms.isEmpty()) {
                        activeIndexes.singleOrNull() ?: -1
                    } else {
                        activeIndexes.lastOrNull { index ->
                            completionTerms.intersect(terms(tasks[index].text)).isNotEmpty()
                        } ?: -1
                    }
                    if (matching >= 0) {
                        val old = tasks.removeAt(matching)
                        tasks.add(old.copy(status = "completed", updatedAtMillis = message.createdAtMillis, completedByMessageId = message.id))
                    }
                }

                val isTask = TASK_MARKERS.any { marker ->
                    Regex("(?iu)(?<![\\p{L}\\p{N}_])" + Regex.escape(marker) + "(?![\\p{L}\\p{N}_])").containsMatchIn(normalized)
                }
                if (isTask && !completion) {
                    val answerAfter = messages.drop(index + 1).takeWhile { it.role != ConversationRole.USER }
                        .lastOrNull { it.role == ConversationRole.ASSISTANT }
                    val status = if (answerAfter == null) "open" else "in_progress"
                    val task = LocalSemanticTask(raw.take(MAX_ITEM_CHARS), status, message.id, message.createdAtMillis)
                    val taskTerms = terms(task.text)
                    val duplicate = tasks.indexOfLast { existing ->
                        taskTerms.isNotEmpty() && taskTerms.intersect(terms(existing.text)).size >= 2
                    }
                    if (duplicate >= 0) tasks.removeAt(duplicate)
                    tasks.add(task)
                }

                val assistantAfter = messages.drop(index + 1).takeWhile { it.role != ConversationRole.USER }
                    .lastOrNull { it.role == ConversationRole.ASSISTANT }
                if (index == messages.indexOfLast { it.role == ConversationRole.USER } &&
                    assistantAfter != null && assistantAfter.text.trimEnd().endsWith("?")) {
                    questions.add(assistantAfter.text.take(MAX_ITEM_CHARS))
                }
            }

            val active = tasks.filter { it.status != "completed" }.takeLast(MAX_TASKS)
            val completed = tasks.filter { it.status == "completed" }.takeLast(MAX_TASKS)
            return LocalSemanticDialogueMemory(
                activeTasks = active,
                completedTasks = completed,
                explicitPreferences = preferences.takeLast(MAX_PREFERENCES),
                unresolvedQuestions = questions.takeLast(MAX_QUESTIONS),
                preferenceConflicts = conflicts.takeLast(MAX_CONFLICTS),
            )
        }

        private fun preferencesConflict(first: String, second: String): Boolean {
            val a = first.lowercase(Locale.ROOT).replace('ё', 'е')
            val b = second.lowercase(Locale.ROOT).replace('ё', 'е')
            val oppositePair = OPPOSITE_PREFERENCE_PAIRS.any { (left, right) ->
                (a.contains(left) && b.contains(right)) || (a.contains(right) && b.contains(left))
            }
            if (oppositePair) return true
            val aNegative = listOf("не ", "don't", "do not", "без ").any { a.contains(it) }
            val bNegative = listOf("не ", "don't", "do not", "без ").any { b.contains(it) }
            val sharedTerms = terms(a).intersect(terms(b)).size
            return aNegative != bNegative && sharedTerms >= 2
        }

        private fun clean(value: String): String =
            value.filter { it >= ' ' && it != '\u007f' }.replace(Regex("\\s+"), " ").trim()

        private fun terms(value: String): Set<String> =
            Regex("[\\p{L}\\p{N}]{3,}").findAll(value.lowercase(Locale.ROOT).replace('ё', 'е'))
                .map { it.value }.filterNot { it in STOP }.toSet()
    }
}
