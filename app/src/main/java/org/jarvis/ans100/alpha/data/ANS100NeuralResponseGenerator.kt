package org.jarvis.ans100.alpha.data

import java.util.Locale

/**
 * First ANS-owned response realizer.
 *
 * It does not load, call, or imitate a language model. It selects a bounded
 * response strategy from Core intent, optional trained neural readout hints,
 * approved memories, and recent dialogue, then composes a short Russian reply.
 *
 * This v1 is neuro-symbolic composition, not yet learned token-by-token language
 * generation. The trace makes the evidence used for each response inspectable.
 */
class ANS100NeuralResponseGenerator : LocalResponseEngine {
    data class GenerationTrace(
        val strategy: String,
        val source: String,
        val usedMemoryIds: List<String>,
        val usedNeuralReadout: Boolean,
        val confidence: Double,
    )

    @Volatile
    var lastTrace: GenerationTrace = GenerationTrace(
        strategy = "not_started",
        source = "none",
        usedMemoryIds = emptyList(),
        usedNeuralReadout = false,
        confidence = 0.0,
    )
        private set

    override val backendLabel: String = "ANS-100 Neural Response v1 · без LLM"
    override val languageModelConfigured: Boolean = false
    override val supportsWorkspaceMemory: Boolean = true

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
    ): String = generate(prompt, recentHistory, emptyList(), null, null, null)

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
    ): String = generate(prompt, recentHistory, memoryContext, signalContext, null, null)

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
        understandingContext: LocalUnderstanding?,
    ): String = generate(prompt, recentHistory, memoryContext, signalContext, understandingContext, null)

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
        understandingContext: LocalUnderstanding?,
        dialogueNeuralAnalysis: DialogueNeuralAnalysis?,
    ): String = generate(
        prompt, recentHistory, memoryContext, signalContext, understandingContext, dialogueNeuralAnalysis,
    )

    private fun generate(
        prompt: String,
        history: List<LocalConversationMessage>,
        memories: List<LocalWorkspaceMemory>,
        signal: ConversationSignalContext?,
        understanding: LocalUnderstanding?,
        neural: DialogueNeuralAnalysis?,
    ): String {
        val clean = prompt.trim()
        require(clean.isNotEmpty() && clean.length <= LocalConversationRepository.MAX_MESSAGE_CHARS)
        val normalized = normalize(clean)
        val parsed = understanding ?: LocalUnderstandingPipeline().analyze(clean, history)
        val queryTerms = terms(clean)
        val rankedMemories = memories.asSequence()
            .filter { it.userApproved && it.content.isNotBlank() }
            .map { memory ->
                val overlap = queryTerms.intersect(terms(memory.content + " " + memory.category)).size
                Triple(overlap, memory, queryTerms.isNotEmpty() && overlap.toDouble() / queryTerms.size >= MIN_MEMORY_MATCH_RATIO)
            }
            .filter { it.first > 0 && it.third }
            .sortedWith(compareByDescending<Triple<Int, LocalWorkspaceMemory, Boolean>> { it.first }
                .thenBy { it.second.id })
            .take(MAX_MEMORIES_PER_REPLY)
            .toList()
        val selectedMemories = rankedMemories.map { it.second }

        val neuralIntent = neural?.takeIf {
            it.classifierTrainedExamples >= MIN_TRAINED_EXAMPLES &&
                it.outputSpikeCount > 0 &&
                !it.classifierLabel.isNullOrBlank() &&
                (it.classifierConfidenceMargin ?: 0.0) >= MIN_NEURAL_MARGIN
        }?.classifierLabel
        val intent = when {
            CAPABILITY_QUESTION.containsMatchIn(normalized) -> "capability_question"
            parsed.intent == "statement_or_unknown" && neuralIntent != null -> neuralIntent
            else -> parsed.intent
        }
        val confidence = if (intent == neuralIntent) {
            ((neural?.classifierConfidenceMargin ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
        } else {
            parsed.intentConfidence
        }
        val source = when {
            intent == neuralIntent -> "trained_neural_readout_plus_core"
            selectedMemories.isNotEmpty() -> "approved_memory_plus_core"
            else -> "core_analysis_and_dialogue"
        }

        val answer = when (intent) {
            "greeting" -> "Привет! Я JARVIS. Я формирую этот ответ собственным локальным ANS-механизмом, без языковой модели. Что будем разбирать?"
            "thanks" -> "Пожалуйста! Я сохранил ход диалога и готов продолжить."
            "farewell" -> "До связи! История нашей беседы остаётся на устройстве."
            "capability_question" -> capabilityReply()
            "correction" -> "Понял, ты указываешь на ошибку в предыдущем ответе. Какая именно часть неверна? Я сопоставлю её с исходным сообщением и сохранёнными сведениями."
            "comparison" -> comparisonReply(parsed, selectedMemories)
            "explanation" -> evidenceReply(parsed, selectedMemories)
            "question" -> evidenceReply(parsed, selectedMemories)
            "action_request" -> actionReply(parsed)
            "continuation" -> continuationReply(parsed, history)
            else -> statementReply(clean, parsed, selectedMemories, normalized)
        }

        lastTrace = GenerationTrace(
            strategy = intent,
            source = source,
            usedMemoryIds = selectedMemories.map { it.id },
            usedNeuralReadout = intent == neuralIntent,
            confidence = confidence,
        )
        return answer.take(LocalConversationRepository.MAX_MESSAGE_CHARS)
    }

    private fun evidenceReply(
        understanding: LocalUnderstanding,
        memories: List<LocalWorkspaceMemory>,
    ): String {
        if (memories.isNotEmpty()) {
            val facts = memories.joinToString("\n") { memory ->
                "• ${memory.content.take(MAX_MEMORY_TEXT_CHARS)}"
            }
            return "Я нашёл совпадения в подтверждённой памяти JARVIS:\n$facts\n\nЭто сведения из сохранённой памяти, а не независимая проверка фактов."
        }
        val focus = (understanding.keyTerms + understanding.entities).distinct().take(4)
        return if (focus.isEmpty()) {
            "Я пока не могу надёжно определить предмет вопроса. Уточни, пожалуйста, о чём именно ты спрашиваешь?"
        } else {
            "Я выделил тему: ${focus.joinToString(", ")}. В доступной подтверждённой памяти пока нет достаточных сведений, чтобы дать проверенный ответ. Уточни, какой именно аспект нужно разобрать?"
        }
    }

    private fun actionReply(understanding: LocalUnderstanding): String {
        val steps = understanding.reasoningPlan.steps.take(MAX_PLAN_STEPS)
        if (understanding.reasoningPlan.requiresClarification) {
            return understanding.reasoningPlan.clarificationQuestion.ifBlank {
                "Чтобы продолжить, мне нужно одно уточнение: какой результат ты ожидаешь?"
            }
        }
        if (steps.isEmpty()) {
            return "Я распознал просьбу о действии, но пока не построил надёжный план. Уточни желаемый результат."
        }
        return "Я разобрал запрос и построил предварительный план:\n" +
            steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n") +
            "\n\nЭто план рассуждения: внешние действия не выполнялись."
    }

    private fun comparisonReply(
        understanding: LocalUnderstanding,
        memories: List<LocalWorkspaceMemory>,
    ): String {
        val options = (understanding.entities + understanding.keyTerms)
            .distinct().take(4)
        if (memories.isNotEmpty()) {
            return "Для сравнения я нашёл такие сохранённые сведения:\n" +
                memories.joinToString("\n") { "• ${it.content.take(MAX_MEMORY_TEXT_CHARS)}" } +
                "\n\nЧтобы сравнение было точным, нужны одинаковые критерии для обоих вариантов."
        }
        return if (options.size >= 2) {
            "Я выделил варианты: ${options.joinToString(" и ")}. Но у меня пока нет подтверждённых характеристик для честного сравнения. По каким критериям сравнить их?"
        } else {
            "Я распознал запрос на сравнение, но не смог надёжно выделить оба варианта. Напиши их названия."
        }
    }

    private fun continuationReply(
        understanding: LocalUnderstanding,
        history: List<LocalConversationMessage>,
    ): String {
        val reference = understanding.contextReference
        if (reference.status != "selected") {
            return "Я вижу, что ты ссылаешься на предыдущую часть разговора, но не могу однозначно выбрать её. Какую именно тему продолжить?"
        }
        val excerpt = reference.sourceAssistantMessage.ifBlank { reference.sourceUserMessage }
            .take(MAX_HISTORY_EXCERPT_CHARS)
        if (excerpt.isBlank() || history.isEmpty()) {
            return "Я понял, что нужно продолжить предыдущую тему, но связанный фрагмент не найден. Напомни, на чём мы остановились."
        }
        return "Я связал сообщение с предыдущим фрагментом:\n«$excerpt»\n\nУточни, какую часть развить дальше, чтобы я не додумывал отсутствующие сведения."
    }

    private fun statementReply(
        prompt: String,
        understanding: LocalUnderstanding,
        memories: List<LocalWorkspaceMemory>,
        normalized: String,
    ): String {
        if (memories.isNotEmpty()) {
            return "Я сопоставил твоё сообщение с сохранённой памятью:\n" +
                memories.joinToString("\n") { "• ${it.content.take(MAX_MEMORY_TEXT_CHARS)}" } +
                "\n\nТы хочешь дополнить эти сведения или разобрать их?"
        }
        val focus = (understanding.keyTerms + understanding.entities).distinct().take(5)
        return when {
            normalized.startsWith("/status") ->
                "JARVIS отвечает собственным локальным ANS-генератором. Внешняя языковая модель в этом контуре не вызывается."
            normalized.startsWith("/help") ->
                "Я могу анализировать сообщения, учитывать историю и подтверждённую память, строить ограниченные планы и задавать уточняющие вопросы. Свободная обучаемая генерация текста пока находится в разработке."
            focus.isNotEmpty() ->
                "Я выделил основные понятия: ${focus.joinToString(", ")}. Пока у меня нет подтверждённых сведений, чтобы развить тему без догадок. Что именно ты хочешь узнать или сделать с этим?"
            else ->
                "Я обработал сообщение, но его смысл пока недостаточно определён. Можешь сформулировать, какой ответ или результат тебе нужен?"
        }
    }

    private fun capabilityReply(): String =
        "Сейчас я могу локально разбирать структуру сообщения, учитывать историю диалога и подтверждённую память, а также строить ограниченные планы. Я не использую внешнюю языковую модель для этого ответа. Свободная генерация произвольного текста нейронным декодером ещё не реализована."

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .trim()

    private fun terms(value: String): Set<String> =
        TOKEN.findAll(normalize(value)).map { it.value }.filterNot { it in STOP_WORDS }.toSet()

    companion object {
        const val MAX_MEMORIES_PER_REPLY = 3
        const val MAX_MEMORY_TEXT_CHARS = 420
        const val MAX_HISTORY_EXCERPT_CHARS = 360
        const val MAX_PLAN_STEPS = 4
        const val MIN_TRAINED_EXAMPLES = 3
        const val MIN_NEURAL_MARGIN = 0.15
        const val MIN_MEMORY_MATCH_RATIO = 0.20
        private val CAPABILITY_QUESTION = Regex(
            """\\b(что ты умеешь|что умеешь|твои возможности|что можешь делать)\\b""",
        )
        private val TOKEN = Regex("[a-zа-яё0-9]{3,}")
        private val STOP_WORDS = setOf(
            "это", "как", "что", "для", "или", "мне", "можно", "когда", "где",
            "почему", "который", "которая", "которые", "есть", "чтобы", "надо",
        )
    }
}
