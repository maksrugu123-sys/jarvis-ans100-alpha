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
    val schema: String = "JARVIS_UNDERSTANDING_V1",
    val scope: String = "bounded_heuristic_pre_generation_analysis",
) {
    fun toModelContext(): String = buildString {
        append("JARVIS CORE — ПРЕДВАРИТЕЛЬНЫЙ АНАЛИЗ (эвристический, не абсолютная истина):\n")
        append("Схема: ").append(schema).append("; границы: ").append(scope).append('\n')
        append("Язык/письменность: ").append(language)
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
        val uniqueTerms = tokens.filter { it.length >= MIN_TERM_LENGTH && it !in STOP_WORDS }
            .distinct().take(MAX_KEY_TERMS)
        val evidence = mutableListOf<String>()
        val hasQuestion = '?' in text || QUESTION_WORDS.any { it in tokens }
        val refersBack = CONTEXT_REFERENCES.any { marker -> marker in tokens || tokens.any { it.startsWith(marker) && marker.length >= 4 } } || containsAnyPhrase(tokens, CONTEXT_PHRASES)
        val negation = NEGATIONS.any { it in tokens }
        val greeting = tokens.firstOrNull()?.let { it in GREETINGS } == true
        val thanks = containsAnyPhrase(tokens, THANKS)
        val farewell = containsAnyPhrase(tokens, FAREWELLS)
        val comparison = containsAnyPhrase(tokens, COMPARISON_MARKERS)
        val explanation = containsAnyPhrase(tokens, EXPLANATION_MARKERS)
        val actionRequest = containsAnyPhrase(tokens, ACTION_MARKERS)
        val correction = containsAnyPhrase(tokens, CORRECTION_MARKERS)

        val intent: String
        val confidence: Double
        val taskKind: String
        when {
            correction -> { intent = "correction"; confidence = 0.82; taskKind = "revise_previous_result"; evidence += "correction_marker" }
            farewell -> { intent = "farewell"; confidence = 0.90; taskKind = "social_response"; evidence += "farewell_marker" }
            thanks -> { intent = "thanks"; confidence = 0.88; taskKind = "social_response"; evidence += "gratitude_marker" }
            greeting -> { intent = "greeting"; confidence = 0.88; taskKind = "social_response"; evidence += "greeting_marker" }
            comparison -> { intent = "comparison"; confidence = 0.76; taskKind = "compare_options"; evidence += "comparison_marker" }
            explanation -> { intent = "explanation"; confidence = 0.72; taskKind = "explain_concept"; evidence += "explanation_marker" }
            actionRequest -> { intent = "action_request"; confidence = 0.68; taskKind = "plan_or_explain_requested_action"; evidence += "action_marker" }
            hasQuestion -> { intent = "question"; confidence = 0.70; taskKind = "answer_question"; evidence += "question_marker" }
            refersBack -> { intent = "continuation"; confidence = 0.64; taskKind = "continue_context"; evidence += "context_reference" }
            else -> { intent = "statement_or_unknown"; confidence = 0.30; taskKind = "interpret_and_respond"; evidence += "нет сильного маркера намерения" }
        }
        if (hasQuestion) evidence += "question_form"
        if (negation) evidence += "negation_marker"
        if (refersBack) evidence += "previous_context_reference"

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


    private fun containsAnyPhrase(tokens: List<String>, phrases: List<String>): Boolean =
        phrases.any { phrase ->
            val parts = phrase.split(' ').filter(String::isNotBlank)
            parts.isNotEmpty() && tokens.windowed(parts.size).any { it == parts }
        }

    companion object {
        private const val MAX_INPUT_CHARS = 750
        private const val MAX_TOKENS = 128
        private const val MAX_KEY_TERMS = 10
        private const val MAX_ENTITIES = 5
        private const val MAX_EVIDENCE = 5
        private const val MIN_TERM_LENGTH = 3
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
        private val ENTITY = Regex("(?<![\\p{L}\\p{N}_])[A-ZА-ЯЁ][\\p{L}\\p{N}_-]{1,47}(?![\\p{L}\\p{N}_])")
        private val CYRILLIC = Regex("[\\u0400-\\u04FF]")
        private val LATIN = Regex("[A-Za-z]")
        private val QUESTION_WORDS = setOf("кто", "что", "где", "когда", "почему", "зачем", "как", "сколько", "можно", "ли", "what", "who", "where", "when", "why", "how")
        private val NEGATIONS = setOf("не", "нет", "ни", "без", "нельзя", "never", "not", "no", "without")
        private val GREETINGS = setOf("привет", "здравствуй", "здравствуйте", "добрый", "hello", "hi", "hey")
        private val THANKS = listOf("спасибо", "благодарю", "thanks", "thank you")
        private val FAREWELLS = listOf("пока", "до свидания", "увидимся", "bye", "goodbye")
        private val COMPARISON_MARKERS = listOf("сравни", "сравнение", "что лучше", "лучше чем", "разница", "отличие", "compare", "versus", " vs ")
        private val EXPLANATION_MARKERS = listOf("объясни", "расскажи", "что означает", "что такое", "explain", "describe")
        private val ACTION_MARKERS = listOf("сделай", "создай", "найди", "исправь", "проверь", "напиши", "добавь", "удали", "открой", "помоги", "надо", "нужно", "хочу", "можешь", "можешь ли", "сделать", "implement", "build", "fix", "find", "create")
        private val CORRECTION_MARKERS = listOf("я имел в виду", "не так", "исправление", "точнее", "я ошибся", "i meant", "correction")
        private val CONTEXT_REFERENCES = listOf("это", "этот", "эта", "эти", "там", "тогда", "продолжай", "дальше", "предыдущ", "it", "that", "those", "continue", "previous")
        private val COMMON_ENTITY_STOPWORDS = setOf(
            "привет", "здравствуй", "здравствуйте", "добрый", "спасибо", "благодарю",
            "пока", "можешь", "сделай", "создай", "найди", "исправь", "проверь",
            "напиши", "добавь", "удали", "открой", "помоги", "объясни", "расскажи",
        )
        private val STOP_WORDS = setOf(
            "это", "для", "или", "что", "чтобы", "мне", "могу", "можно",
            "когда", "где", "почему", "который", "которая", "которые", "есть",
            "the", "and", "for", "with", "that", "this",
        )
        private val CONTEXT_PHRASES = listOf("как раньше", "в прошлый раз")
    }
}
