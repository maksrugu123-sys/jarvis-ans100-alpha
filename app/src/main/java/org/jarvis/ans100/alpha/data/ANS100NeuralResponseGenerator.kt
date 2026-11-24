package org.jarvis.ans100.alpha.data

import java.util.Locale

/**
 * First ANS-owned response realizer.
 *
 * It does not load or call an external/pretrained language model. It uses the
 * ANS-owned recurrent decoder when its output is eligible, with Core analysis,
 * approved memories and dialogue safeguards for the remaining response paths.
 *
 * Without a trained sequence snapshot it falls back to bounded neuro-symbolic
 * composition. When a trained ANS decoder is available, eligible prompts may
 * use its learned token-by-token output. The trace records the selected path.
 */
class ANS100NeuralResponseGenerator(
    private val sequenceLanguageModule: ANS100TrainableLanguageModule? = null,
) : LocalResponseEngine {
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

    override val backendLabel: String = "ANS-100 Neural Response v1 · локально"
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
            .filter { it.userApproved && it.supersededById == null && it.content.isNotBlank() }
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
            CAPABILITY_QUESTION.containsMatchIn(normalized) || SELF_DESCRIPTION_QUESTION.containsMatchIn(normalized) -> "capability_question"
            META_GUESSING_QUESTION.containsMatchIn(normalized) -> "meta_reasoning_question"
            parsed.dialogueState.pendingAssistantOffer.isNotBlank() &&
                parsed.contextReference.status == "selected" && isAffirmativeFollowThrough(clean) -> "continuation"
            parsed.intent == "statement_or_unknown" && neuralIntent != null -> neuralIntent
            else -> parsed.intent
        }
        val confidence = if (intent == neuralIntent) {
            ((neural?.classifierConfidenceMargin ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
        } else {
            parsed.intentConfidence
        }

        // Prefer learned token-by-token generation only after the decoder has
        // been explicitly trained and its output passes conservative bounds.
        // Critical capability/correction paths remain deterministic and inspectable.
        if (intent !in setOf("capability_question", "correction", "greeting", "farewell")) {
            val modelContext = buildGenerationContext(
                history = history,
                memories = selectedMemories,
                understanding = parsed,
                neural = neural,
            )
            // With no dialogue or approved memories, feed the exact prompt alone.
            // Heuristic analysis text is not training data and can corrupt a short learned prefix.
            val learned = sequenceLanguageModule?.let { module ->
                if (history.isEmpty() && selectedMemories.isEmpty()) module.generate(clean)
                else module.generateWithContext(clean, modelContext)
            }
            if (learned != null &&
                learned.unknownPromptTokenCount == 0 &&
                learned.tokens.size >= MIN_LEARNED_GENERATION_TOKENS &&
                (learned.stoppedAtEndToken || learned.text.trimEnd().lastOrNull()?.let { it in COMPLETE_END_MARKERS } == true) &&
                learned.meanTokenConfidence >= MIN_LEARNED_GENERATION_CONFIDENCE &&
                learned.text.isNotBlank() &&
                learned.text.length <= LocalConversationRepository.MAX_MESSAGE_CHARS
            ) {
                lastTrace = GenerationTrace(
                    strategy = "learned_sequence_generation",
                    source = "trained_ans100_recurrent_decoder",
                    usedMemoryIds = selectedMemories.map { it.id },
                    usedNeuralReadout = true,
                    confidence = learned.meanTokenConfidence,
                )
                return learned.text.replaceFirstChar { it.uppercase(Locale.ROOT) }
            }
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
            "meta_reasoning_question" -> metaReasoningReply()
            "self_description" -> selfDescriptionReply()
            "correction" -> "Понял, ты указываешь на ошибку в предыдущем ответе. Какая именно часть неверна? Я сопоставлю её с исходным сообщением и сохранёнными сведениями."
            "comparison" -> comparisonReply(parsed, selectedMemories)
            "explanation" -> generativeKnowledgeReply(clean, parsed, selectedMemories)
            "question" -> generativeKnowledgeReply(clean, parsed, selectedMemories)
            "action_request" -> actionReply(parsed)
            "continuation" -> continuationReply(clean, parsed, history)
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

    /**
     * Packs only bounded, already-available ANS evidence into the learned
     * decoder's recurrent prompt. Current user text remains the final segment.
     */
    private fun buildGenerationContext(
        history: List<LocalConversationMessage>,
        memories: List<LocalWorkspaceMemory>,
        understanding: LocalUnderstanding,
        neural: DialogueNeuralAnalysis?,
    ): String = buildString {
        val recent = history.takeLast(MAX_CONTEXT_HISTORY_MESSAGES)
        if (recent.isNotEmpty()) {
            append("[dialogue context] ")
            recent.forEach { message ->
                append(if (message.role == ConversationRole.USER) "user: " else "ans: ")
                append(message.text.take(MAX_CONTEXT_MESSAGE_CHARS))
                append(" ")
            }
        }
        if (memories.isNotEmpty()) {
            append("[approved memory] ")
            memories.take(MAX_MEMORIES_PER_REPLY).forEach { memory ->
                append(memory.category.take(48)).append(": ")
                append(memory.content.take(MAX_MEMORY_TEXT_CHARS)).append(" ")
            }
        }
        val semanticContext = understanding.semanticMemory.toModelContext()
        if (semanticContext.isNotBlank()) {
            append("[semantic dialogue memory] ")
            append(semanticContext.take(1_800)).append(" ")
        }
        val reasoning = understanding.reasoningPlan.steps.take(MAX_PLAN_STEPS)
        if (understanding.intent != "statement_or_unknown" ||
            understanding.keyTerms.isNotEmpty() || reasoning.isNotEmpty()
        ) {
            append("[ans analysis] intent=").append(understanding.intent)
            if (understanding.keyTerms.isNotEmpty()) {
                append("; terms=").append(understanding.keyTerms.take(5).joinToString(", "))
            }
            if (understanding.entities.isNotEmpty()) {
                append("; entities=").append(understanding.entities.take(4).joinToString(", "))
            }
            if (reasoning.isNotEmpty()) {
                append("; plan=").append(reasoning.joinToString(" -> "))
            }
            append(". ")
        }
        val neuralSummary = neural?.toModelContext()?.take(MAX_NEURAL_CONTEXT_CHARS)
        if (!neuralSummary.isNullOrBlank()) {
            append("[neural telemetry] ").append(neuralSummary).append(" ")
        }
    }.take(MAX_GENERATION_CONTEXT_CHARS)

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
            "Я попробую ответить самостоятельно: сначала разберу смысл вопроса, затем предложу наиболее вероятное объяснение. Если данных окажется мало, обозначу предположения отдельно от фактов."
        } else {
            "Предварительный ответ по теме «${focus.joinToString(", ")}»: я могу построить рабочее объяснение из смысла вопроса и общих связей, даже если в моей памяти нет готового ответа. Сначала рассмотрю возможные причины и варианты, затем отмечу, что требует проверки. Это будет обоснованная гипотеза, а не подтверждённый факт."
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
        prompt: String,
        understanding: LocalUnderstanding,
        history: List<LocalConversationMessage>,
    ): String {
        val reference = understanding.contextReference
        if (reference.status != "selected") {
            return understanding.reasoningPlan.clarificationQuestion.ifBlank {
                "Я вижу несколько возможных тем. Какую именно продолжить?"
            }
        }
        val excerpt = reference.sourceAssistantMessage.ifBlank { reference.sourceUserMessage }
            .take(MAX_HISTORY_EXCERPT_CHARS)
        if (excerpt.isBlank() || history.isEmpty()) {
            return "Я понял, что нужно продолжить предыдущую тему, но связанный фрагмент не найден. Напомни, на чём мы остановились."
        }
        val acceptedOffer = understanding.dialogueState.pendingAssistantOffer.takeIf {
            it.isNotBlank() && isAffirmativeFollowThrough(prompt)
        }
        if ((reference.method == "adjacent_assistant_offer" || acceptedOffer != null) && isAffirmativeFollowThrough(prompt)) {
            val acceptedStep = extractAcceptedStep(acceptedOffer ?: reference.sourceAssistantMessage)
            return if (acceptedStep.isBlank()) {
                "Хорошо, продолжаю предыдущую тему. Я учту уже сказанное и не буду просить тебя повторять контекст."
            } else {
                "Хорошо, продолжаю с предложенного шага: «$acceptedStep». Я учитываю предыдущий разговор и перехожу к сути, а не начинаю обсуждение заново."
            }
        }
        val followUp = normalize(prompt).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        val previousAnswer = reference.sourceAssistantMessage.ifBlank { reference.sourceUserMessage }
            .take(MAX_HISTORY_EXCERPT_CHARS)
        return when {
            followUp in setOf("почему", "а почему", "зачем", "а зачем", "why") ->
                "Ты спрашиваешь, почему я дал предыдущий ответ: «$previousAnswer». Я вернусь к его причине и проверю, какие сведения действительно её подтверждают, вместо того чтобы придумывать объяснение."
            followUp in setOf("как", "а как", "how") ->
                "Ты хочешь понять, как работает то, о чём мы говорили: «$previousAnswer». Продолжу объяснение именно с этого места."
            followUp in setOf("что это", "а что это", "что", "what") ->
                "Ты уточняешь, что именно означает мой предыдущий ответ: «$previousAnswer». Разберу это понятие, не теряя исходную тему."
            followUp.contains("подробнее") ->
                "Конечно. Разверну предыдущую мысль: «$previousAnswer». Сохраню тему разговора и объясню её по шагам."
            followUp.contains("дальше") || followUp == "продолжай" ->
                "Продолжаю с того места, где мы остановились. Последняя мысль была такой: «$previousAnswer»."
            else ->
                "Я связал твой вопрос с предыдущим ответом: «$previousAnswer». Продолжу эту тему. Если я выбрал не тот фрагмент, поправь меня."
        }
    }

    private fun isAffirmativeFollowThrough(prompt: String): Boolean {
        val tokens = TOKEN.findAll(normalize(prompt)).map { it.value }.toList()
        return tokens.isNotEmpty() && tokens.first() in AFFIRMATIVE_FOLLOW_THROUGH &&
            tokens.all { it in AFFIRMATIVE_FOLLOW_THROUGH || it in setOf("же", "это", "вот", "пожалуйста") }
    }

    private fun extractAcceptedStep(previousAssistantMessage: String): String {
        val clean = previousAssistantMessage.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return ""
        val offer = Regex(
            """(?i)(?:следующий этап|следующий шаг|предлагаю|могу помочь|могу сделать|если хочешь)[^.!?]{0,260}[.!?]?""",
        ).find(clean)?.value
        val candidate = offer ?: clean
        return candidate.take(MAX_HISTORY_EXCERPT_CHARS)
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
        "Я могу вести диалог, разбирать смысл сообщений, удерживать несколько тем и возвращаться к ним, учитывать историю и подтверждённую память, формировать планы, помогать с кодом и проектами, а при подключённых инструментах — выполнять поиск и другие разрешённые действия. Я работаю локальным ANS-механизмом без внешней языковой модели. Моя свободная генерация пока развивается, поэтому сложные ответы могут быть неточными, но я должен пытаться ответить, а не останавливаться на фразе «я не знаю»."

    private fun selfDescriptionReply(): String =
        "Я JARVIS — локальный экспериментальный ассистент на базе ANS-100. Моя задача — понимать сообщения, поддерживать связный диалог, помнить важные темы и помогать решать задачи. Я анализирую контекст, строю ответ собственным механизмом и постепенно развиваю обучаемую генерацию. Я не являюсь человеком и не подключаю скрытую облачную языковую модель."

    private fun metaReasoningReply(): String =
        "Нет. Я не должен только догадываться. Если у меня есть надёжные сведения, я отвечаю по ним. Если знаний недостаточно, я всё равно формирую наиболее разумный предварительный ответ, объясняю ход рассуждения и отдельно помечаю предположения. Точную проверку фактов нельзя заменять догадкой, но нехватка памяти не должна заставлять меня прекращать разговор."

    private fun generativeKnowledgeReply(
        prompt: String,
        understanding: LocalUnderstanding,
        memories: List<LocalWorkspaceMemory>,
    ): String {
        if (memories.isNotEmpty()) return evidenceReply(understanding, memories)
        val normalized = normalize(prompt)
        val focus = (understanding.entities + understanding.keyTerms).distinct().take(4)
        val subject = focus.joinToString(", ").ifBlank { "этот вопрос" }
        val reasoning = when {
            normalized.contains("почему") || normalized.contains("зачем") ->
                "Вероятнее всего, ответ связан не с одной причиной, а с сочетанием условий. Я бы проверил исходные условия, сам механизм и то, что изменилось перед появлением результата. Затем можно исключить версии, которые не объясняют все наблюдения."
            normalized.contains("как") ->
                "Я разложу это на последовательность: что поступает на вход, какие действия или процессы происходят внутри, что влияет на результат и как его можно проверить. Такой разбор помогает получить рабочую модель даже без готовой записи в памяти."
            normalized.contains("кто") ->
                "Чтобы ответить, нужно установить, о ком или о чём идёт речь, отделить подтверждённые сведения от похожих имён и затем собрать краткое описание. Я не стану придумывать биографические детали, которых не могу обосновать."
            normalized.contains("где") || normalized.contains("когда") || normalized.contains("сколько") ->
                "Это вопрос с конкретным проверяемым ответом. Я могу сформировать предварительную оценку, но точное место, дату или число нельзя надёжно вывести только из формулировки — для этого нужен источник или дополнительные исходные данные."
            else ->
                "Мой предварительный разбор такой: сначала нужно определить основные свойства темы, затем понять, какие факторы на неё влияют, и проверить, какое объяснение лучше всего связывает их между собой. Я могу развить эту гипотезу дальше, не выдавая её за установленный факт."
        }
        return "Попробую ответить по существу. Тема: «$subject». $reasoning"
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .trim()

    private fun terms(value: String): Set<String> =
        TOKEN.findAll(normalize(value)).map { it.value }.filterNot { it in STOP_WORDS }.toSet()

    companion object {
        const val MAX_CONTEXT_HISTORY_MESSAGES = 4
        const val MAX_CONTEXT_MESSAGE_CHARS = 280
        const val MAX_NEURAL_CONTEXT_CHARS = 500
        const val MAX_GENERATION_CONTEXT_CHARS = 3_500
        const val MAX_MEMORIES_PER_REPLY = 3
        const val MAX_MEMORY_TEXT_CHARS = 420
        const val MAX_HISTORY_EXCERPT_CHARS = 360
        const val MAX_PLAN_STEPS = 4
        const val MIN_LEARNED_GENERATION_TOKENS = 2
        const val MIN_LEARNED_GENERATION_CONFIDENCE = 0.25
        const val MIN_TRAINED_EXAMPLES = 3
        const val MIN_NEURAL_MARGIN = 0.15
        const val MIN_MEMORY_MATCH_RATIO = 0.20
        private val COMPLETE_END_MARKERS = setOf('.', '!', '?')
        private val AFFIRMATIVE_FOLLOW_THROUGH = setOf(
            "давай", "да", "ок", "окей", "ладно", "поехали", "согласен", "yes", "yeah", "ok", "okay", "sure", "go",
        )
        private val CAPABILITY_QUESTION = Regex(
            """\b(что ты умеешь|что умеешь|твои возможности|что можешь делать)\b""",
        )
        private val SELF_DESCRIPTION_QUESTION = Regex(
            """\b(расскажи о себе|кто ты|что ты за ассистент|представься)\b""",
        )
        private val META_GUESSING_QUESTION = Regex(
            """\b(можешь|умеешь|будешь).{0,35}(только|всегда).{0,20}(догадываться|гадать|предполагать)\b""",
        )
        private val TOKEN = Regex("[a-zа-яё0-9]{3,}")
        private val STOP_WORDS = setOf(
            "это", "как", "что", "для", "или", "мне", "можно", "когда", "где",
            "почему", "который", "которая", "которые", "есть", "чтобы", "надо",
        )
    }
}
