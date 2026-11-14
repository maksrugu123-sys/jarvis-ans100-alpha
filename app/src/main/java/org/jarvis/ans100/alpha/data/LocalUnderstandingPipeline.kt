package org.jarvis.ans100.alpha.data

import java.util.Locale

/**
 * Deterministic, bounded pre-generation analysis owned by JARVIS Core.
 *
 * This is a transparent heuristic parser, not a trained semantic model. It produces
 * evidence and a routing hint; the language model is only the response realizer.
 */
data class LocalUnderstanding(
    val language: String,
    val intent: String,
    val intentConfidence: Double,
    val taskKind: String,
    val entities: List<String>,
    val keyTerms: List<String>,
    val containsNegation: Boolean,
    val refersToPreviousContext: Boolean,
    val asksForExplanation: Boolean,
    val evidence: List<String>,
) {
    fun toModelContext(): String = buildString {
        append("JARVIS CORE — ПРЕДВАРИТЕЛЬНЫЙ АНАЛИЗ (эвристический, не абсолютная истина):\n")
        append("Язык: ").append(language)
        append("; намерение: ").append(intent)
        append("; уверенность эвристики: ").append(String.format(Locale.ROOT, "%.2f", intentConfidence))
        append("; тип задачи: ").append(taskKind).append('\n')
        if (keyTerms.isNotEmpty()) append("Ключевые термины: ").append(keyTerms.joinToString(", ")).append('\n')
        if (entities.isNotEmpty()) append("Возможные сущности: ").append(entities.joinToString(", ")).append('\n')
        append("Отрицание присутствует: ").append(containsNegation)
        append("; ссылка на предыдущий контекст: ").append(refersToPreviousContext)
        append("; запрос объяснения: ").append(asksForExplanation).append('\n')
        if (evidence.isNotEmpty()) append("Основания классификации: ").append(evidence.joinToString("; ")).append('\n')
        append("Используй этот анализ как подсказку, сверяй его с исходным сообщением. Если уверенность низкая или смысл неоднозначен, не выдумывай недостающие детали и задай один уточняющий вопрос. Не приписывай JARVIS понимание, которого этот анализ не подтверждает.")
    }
}

class LocalUnderstandingPipeline {
    fun analyze(input: String): LocalUnderstanding {
        val text = input.trim().take(MAX_INPUT_CHARS)
        val normalized = text.lowercase(Locale.ROOT)
        val tokens = TOKEN.findAll(normalized).map { it.value }.take(MAX_TOKENS).toList()
        val uniqueTerms = tokens.filter { it.length >= MIN_TERM_LENGTH }
            .distinct().take(MAX_KEY_TERMS)
        val evidence = mutableListOf<String>()
        val hasQuestion = '?' in text || QUESTION_WORDS.any { it in tokens }
        val refersBack = CONTEXT_REFERENCES.any { phrase -> normalized.contains(phrase) }
        val negation = NEGATIONS.any { it in tokens }
        val greeting = tokens.firstOrNull() in GREETINGS || GREETINGS.any { normalized.startsWith("$it ") }
        val thanks = THANKS.any { normalized.contains(it) }
        val farewell = FAREWELLS.any { normalized.contains(it) }
        val comparison = COMPARISON_MARKERS.any { normalized.contains(it) }
        val explanation = EXPLANATION_MARKERS.any { normalized.contains(it) }
        val actionRequest = ACTION_MARKERS.any { normalized.contains(it) }
        val correction = CORRECTION_MARKERS.any { normalized.contains(it) }

        val intent: String
        val confidence: Double
        val taskKind: String
        when {
            correction -> { intent = "correction"; confidence = 0.82; taskKind = "revise_previous_result"; evidence += "корректирующая формулировка" }
            farewell -> { intent = "farewell"; confidence = 0.90; taskKind = "social_response"; evidence += "маркер прощания" }
            thanks -> { intent = "thanks"; confidence = 0.88; taskKind = "social_response"; evidence += "маркер благодарности" }
            greeting -> { intent = "greeting"; confidence = 0.88; taskKind = "social_response"; evidence += "маркер приветствия" }
            comparison -> { intent = "comparison"; confidence = 0.76; taskKind = "compare_options"; evidence += "сравнительные маркеры" }
            actionRequest -> { intent = "action_request"; confidence = 0.68; taskKind = "plan_or_explain_requested_action"; evidence += "глагол или конструкция просьбы" }
            explanation -> { intent = "explanation"; confidence = 0.72; taskKind = "explain_concept"; evidence += "маркер объяснения" }
            hasQuestion -> { intent = "question"; confidence = 0.70; taskKind = "answer_question"; evidence += "вопросительный маркер" }
            refersBack -> { intent = "continuation"; confidence = 0.64; taskKind = "continue_context"; evidence += "ссылка на предыдущий контекст" }
            else -> { intent = "statement_or_unknown"; confidence = 0.30; taskKind = "interpret_and_respond"; evidence += "нет сильного маркера намерения" }
        }
        if (hasQuestion) evidence += "вопросительная форма"
        if (negation) evidence += "обнаружено отрицание"
        if (refersBack) evidence += "обнаружена ссылка на контекст"

        val entities = ENTITY.findAll(text).map { it.value.trim() }
            .filter { it.length in 2..48 && it.lowercase(Locale.ROOT) !in COMMON_ENTITY_STOPWORDS }
            .distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_ENTITIES).toList()
        val language = when {
            CYRILLIC.containsMatchIn(text) -> "ru_or_cyrillic"
            LATIN.containsMatchIn(text) -> "en_or_latin"
            else -> "unknown"
        }
        return LocalUnderstanding(
            language = language,
            intent = intent,
            intentConfidence = confidence,
            taskKind = taskKind,
            entities = entities,
            keyTerms = uniqueTerms,
            containsNegation = negation,
            refersToPreviousContext = refersBack,
            asksForExplanation = explanation,
            evidence = evidence.distinct().take(MAX_EVIDENCE),
        )
    }

    companion object {
        private const val MAX_INPUT_CHARS = 750
        private const val MAX_TOKENS = 128
        private const val MAX_KEY_TERMS = 10
        private const val MAX_ENTITIES = 5
        private const val MAX_EVIDENCE = 5
        private const val MIN_TERM_LENGTH = 3
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
        private val ENTITY = Regex("\\b[A-ZА-ЯЁ][\\p{L}\\p{N}_-]{1,47}\\b")
        private val CYRILLIC = Regex("[\\u0400-\\u04FF]")
        private val LATIN = Regex("[A-Za-z]")
        private val QUESTION_WORDS = setOf("кто", "что", "где", "когда", "почему", "зачем", "как", "сколько", "можно", "ли", "what", "who", "where", "when", "why", "how")
        private val NEGATIONS = setOf("не", "нет", "ни", "без", "нельзя", "never", "not", "no", "without")
        private val GREETINGS = setOf("привет", "здравствуй", "здравствуйте", "добрый", "hello", "hi", "hey")
        private val THANKS = listOf("спасибо", "благодарю", "thanks", "thank you")
        private val FAREWELLS = listOf("пока", "до свидания", "увидимся", "bye", "goodbye")
        private val COMPARISON_MARKERS = listOf("сравни", "сравнение", "что лучше", "лучше чем", "разница", "отличие", "compare", "versus", " vs ")
        private val EXPLANATION_MARKERS = listOf("объясни", "расскажи", "почему", "зачем", "что означает", "что такое", "explain", "describe")
        private val ACTION_MARKERS = listOf("сделай", "создай", "найди", "исправь", "проверь", "напиши", "добавь", "удали", "открой", "помоги", "надо", "нужно", "хочу", "можешь", "можешь ли", "сделать", "implement", "build", "fix", "find", "create")
        private val CORRECTION_MARKERS = listOf("я имел в виду", "не так", "исправление", "точнее", "я ошибся", "i meant", "correction")
        private val CONTEXT_REFERENCES = listOf("это", "этот", "эта", "эти", "там", "тогда", "продолжай", "дальше", "как раньше", "предыдущ", "в прошлый раз", "it", "that", "those", "continue", "previous")
        private val COMMON_ENTITY_STOPWORDS = setOf("привет", "здравствуйте", "добрый", "спасибо", "пока", "можешь", "сделай", "объясни", "расскажи")
    }
}
