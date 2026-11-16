package org.jarvis.ans100.alpha.data
import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.UUID

data class LiteRtLmPerformanceSnapshot(
    val initializationMs: Long = 0L,
    val generationMs: Long = 0L,
    val outputTokenEstimate: Int = 0,
    val timeToFirstTextMs: Long = 0L,
    val contextTokenCount: Int = 0,
    val estimatedInputTokens: Int = 0,
    val omittedHistoryMessages: Int = 0,
    val includedMemoryItems: Int = 0,
    val omittedMemoryItems: Int = 0,
)

data class LiteRtLmBenchmarkResult(
    val answer: String?,
    val rawOutput: String,
    val initializationMs: Long,
    val generationMs: Long,
    val outputTokenEstimate: Int,
    val memoryBaselinePssMb: Double? = null,
    val peakMemoryPssMb: Double? = null,
    val peakMemoryDeltaMb: Double? = null,
    val memorySampleIntervalMs: Long? = null,
)

class LiteRtLmResponseEngine(context: Context, private val modelStore: LiteRtLmModelStore) : LocalResponseEngine, AutoCloseable {
    private val appContext = context.applicationContext
    private val fallback = LocalFallbackResponseEngine()
    private val backendPreferences = appContext.getSharedPreferences("jarvis_litert_backend_v1", Context.MODE_PRIVATE)
    @Volatile private var gpuRequested = backendPreferences.getBoolean("gpu_enabled", false)
    @Volatile private var ans100DecisionHandoffRequested =
        backendPreferences.getBoolean("ans100_decision_handoff_enabled", false)
    @Volatile private var activeBackend: String? = null
    @Volatile private var backendNotice: String? = null
    private var engine: Engine? = null
    private var loadedPath: String? = null
    @Volatile var onPartialResponse: ((String) -> Unit)? = null
    @Volatile private var lastInitializationMs: Long = 0L
    @Volatile private var lastGenerationMs: Long = 0L
    @Volatile private var lastOutputTokenEstimate: Int = 0
    @Volatile private var lastTimeToFirstTextMs: Long = 0L
    @Volatile private var lastContextTokenCount: Int = 0
    @Volatile private var lastEstimatedInputTokens: Int = 0
    @Volatile private var lastOmittedHistoryMessages: Int = 0
    @Volatile private var lastIncludedMemoryItems: Int = 0
    @Volatile private var lastOmittedMemoryItems: Int = 0

    /**
     * Runs the installed Transformer as an isolated benchmark subject.
     *
     * This deliberately bypasses ANS-100 handoff, Core understanding, chat history,
     * workspace memory, fallback responses, and tool calling. It is a comparison
     * path only; it does not alter the normal JARVIS conversation pipeline.
     */
    fun benchmarkTransformerOnly(inputText: String): LiteRtLmBenchmarkResult {
        val measured = ProcessMemoryPeakSampler.measure {
            benchmarkTransformerOnlyInternal(inputText)
        }
        return measured.value.copy(
            memoryBaselinePssMb = measured.baselinePssMb,
            peakMemoryPssMb = measured.peakPssMb,
            peakMemoryDeltaMb = measured.peakDeltaMb,
            memorySampleIntervalMs = measured.sampleIntervalMs,
        )
    }

    private fun benchmarkTransformerOnlyInternal(inputText: String): LiteRtLmBenchmarkResult {
        val clean = inputText.trim()
        require(clean.isNotEmpty() && clean.length <= MAX_PROMPT_CHARS) {
            "Benchmark input must contain 1..$MAX_PROMPT_CHARS characters."
        }
        val file = modelStore.installedFile()
            ?: throw LocalConversationException("Для Transformer-only benchmark сначала установите локальную Qwen3-0.6B INT4.")
        val engineAlreadyLoaded = engine != null && loadedPath == file.absolutePath
        resetTurnMetrics()
        val config = ConversationConfig(
            systemInstruction = Contents.of(BENCHMARK_SYSTEM_PROMPT),
            initialMessages = emptyList(),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
            automaticToolCalling = false,
            channels = emptyList(),
            maxOutputToken = MAX_BENCHMARK_OUTPUT_TOKENS,
        )
        val raw = generate(getEngine(file), config, clean).trim()
        LiteRtLmBenchmarkResult(
            answer = ANS100BenchmarkCanonicalAnswerParser.parse(raw),
            rawOutput = raw.take(MAX_BENCHMARK_RAW_OUTPUT_CHARS),
            initializationMs = if (engineAlreadyLoaded) 0L else lastInitializationMs,
            generationMs = lastGenerationMs,
            outputTokenEstimate = raw.split(Regex("\\s+")).count { it.isNotBlank() },
        )
    }

    /** Clear per-turn generation metrics without losing the last engine initialization measurement. */
    fun resetTurnMetrics() {
        lastGenerationMs = 0L
        lastOutputTokenEstimate = 0
        lastTimeToFirstTextMs = 0L
        lastContextTokenCount = 0
        lastEstimatedInputTokens = 0
        lastOmittedHistoryMessages = 0
        lastIncludedMemoryItems = 0
        lastOmittedMemoryItems = 0
    }

    fun performanceSnapshot(): LiteRtLmPerformanceSnapshot = LiteRtLmPerformanceSnapshot(
        initializationMs = lastInitializationMs,
        generationMs = lastGenerationMs,
        outputTokenEstimate = lastOutputTokenEstimate,
        timeToFirstTextMs = lastTimeToFirstTextMs,
        contextTokenCount = lastContextTokenCount,
        estimatedInputTokens = lastEstimatedInputTokens,
        omittedHistoryMessages = lastOmittedHistoryMessages,
        includedMemoryItems = lastIncludedMemoryItems,
        omittedMemoryItems = lastOmittedMemoryItems,
    )

    override val languageModelConfigured: Boolean
        get() = modelStore.installedFile() != null

    val gpuEnabled: Boolean
        get() = gpuRequested

    override val backendLabel: String
        get() {
            if (!languageModelConfigured) return fallback.backendLabel
            val backend = activeBackend ?: if (gpuRequested) "GPU (ожидает запуска)" else "CPU"
            val notice = backendNotice?.let { " · $it" }.orEmpty()
            return "LiteRT-LM · Qwen3-0.6B INT4 · $backend · offline$notice"
        }

    fun setGpuEnabled(enabled: Boolean) {
        if (gpuRequested == enabled) return
        closeEngine()
        gpuRequested = enabled
        backendPreferences.edit().putBoolean("gpu_enabled", enabled).apply()
        activeBackend = null
        backendNotice = null
    }

    /** Experimental decision→Qwen handoff; opt-in and disabled by default. */
    fun setAns100DecisionHandoffEnabled(enabled: Boolean) {
        ans100DecisionHandoffRequested = enabled
        backendPreferences.edit()
            .putBoolean("ans100_decision_handoff_enabled", enabled)
            .apply()
    }

    val ans100DecisionHandoffEnabled: Boolean
        get() = ans100DecisionHandoffRequested

    override fun respond(prompt: String, recentHistory: List<LocalConversationMessage>): String =
        respond(prompt, recentHistory, emptyList(), null)

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
    ): String = respond(
        prompt,
        recentHistory,
        memoryContext,
        signalContext,
        LocalUnderstandingPipeline().analyze(prompt, recentHistory),
    )

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
        understandingContext: LocalUnderstanding?,
    ): String = respond(
        prompt, recentHistory, memoryContext, signalContext, understandingContext, null,
    )

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
        understandingContext: LocalUnderstanding?,
        dialogueNeuralAnalysis: DialogueNeuralAnalysis?,
    ): String {
        val clean = prompt.trim()
        if (clean.length > MAX_PROMPT_CHARS) throw LocalConversationException("Ограничение компактной модели — " + MAX_PROMPT_CHARS + " символов на запрос.")
        val understanding = understandingContext ?: LocalUnderstandingPipeline().analyze(clean)
        val file = modelStore.installedFile() ?: return fallback.respond(
            clean, recentHistory, memoryContext, signalContext, understanding,
        )
        // The live hybrid turn supplies its own evidence-bound decision package.
        // The older opt-in Core policy remains only as a compatibility fallback.
        val decisionHandoff = dialogueNeuralAnalysis?.hybridQwenHandoff ?: if (
            ans100DecisionHandoffRequested &&
            clean.length <= TypedEvidenceEncoder.MAX_RAW_MESSAGE_CHARS
        ) {
            runCatching {
                val turnId = "turn-${UUID.randomUUID()}"
                val evidence = TypedEvidenceEncoder().encode(
                    TypedEvidenceInput(
                        turnId = turnId,
                        rawUserMessage = clean,
                        understanding = understanding,
                    ),
                )
                val decision = ANS100DecisionPolicy().decide(
                    evidence = evidence,
                    understanding = understanding,
                    neuralAnalysis = dialogueNeuralAnalysis,
                )
                ANS100QwenHandoffBuilder().build(
                    originalUserMessage = clean,
                    understanding = understanding,
                    evidence = evidence,
                    decision = decision,
                )
            }.getOrNull()
        } else {
            null
        }
        val contextBudget = PromptContextBudgeter.create(
            baseSystemInstruction = SYSTEM_PROMPT,
            prompt = clean,
            recentHistory = recentHistory,
            memoryContext = memoryContext,
            signalContext = signalContext,
            understandingContext = understanding,
            dialogueNeuralAnalysis = dialogueNeuralAnalysis,
            ans100Handoff = decisionHandoff,
        )
        val initial = contextBudget.history.map { item ->
            if (item.role == ConversationRole.USER) Message.user(item.text)
            else Message.model(item.text)
        }
        val systemInstruction = contextBudget.systemInstruction
        lastEstimatedInputTokens = contextBudget.estimatedInputTokens
        lastOmittedHistoryMessages = contextBudget.omittedHistoryMessages
        lastIncludedMemoryItems = contextBudget.includedMemoryItems
        lastOmittedMemoryItems = contextBudget.omittedMemoryItems
        val activeEngine = getEngine(file)
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction),
            initialMessages = initial,
            // A fresh seed prevents the default seed=0 from repeating the same short answer on every turn.
            samplerConfig = SamplerConfig(topK = 32, topP = 0.9, temperature = 0.65, seed = kotlin.random.Random.nextInt()),
            automaticToolCalling = false,
            channels = emptyList(),
            maxOutputToken = MAX_OUTPUT_TOKENS,
        )
        val rawAnswer = try {
            generate(activeEngine, config, clean)
        } catch (error: Exception) {
            if (!gpuRequested || activeBackend != "GPU") {
                // A failed CPU conversation may leave native engine state uncertain.
                // Retire it so the next user turn starts from a fresh engine instance.
                runCatching { closeEngine() }
                throw error
            }
            try {
                disableGpuAfterFailure(file)
                generate(getEngine(file), config, clean)
            } catch (fallbackError: Exception) {
                // Do not reuse a CPU engine whose fallback generation also failed.
                runCatching { closeEngine() }
                throw fallbackError
            }
        }
        val answer = rawAnswer.trim()
        if (answer.isEmpty()) throw LocalConversationException("Модель вернула пустой ответ.")
        lastOutputTokenEstimate = answer.split(Regex("\\s+")).count { it.isNotBlank() }
        return answer.take(LocalConversationRepository.MAX_MESSAGE_CHARS)
    }

    private fun checkMemory() {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: throw LocalConversationException("Не удалось проверить доступную память устройства.")
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (info.lowMemory || info.availMem < MIN_AVAILABLE_MEMORY_BYTES) {
            throw LocalConversationException("Модель не загружена: требуется не менее 768 МиБ доступной RAM. Закройте тяжёлые приложения и повторите попытку. Это ограничение не гарантирует отсутствие OOM.")
        }
    }

    private fun getEngine(file: File): Engine {
        val path = file.absolutePath
        val existing = engine
        if (existing != null && loadedPath == path) return existing
        if (existing != null) closeEngine()
        checkMemory()
        if (gpuRequested) {
            try {
                val gpu = initializeEngine(file, Backend.GPU())
                engine = gpu
                loadedPath = path
                activeBackend = "GPU"
                backendNotice = null
                return gpu
            } catch (_: Exception) {
                gpuRequested = false
                backendPreferences.edit().putBoolean("gpu_enabled", false).apply()
                backendNotice = "GPU недоступен, выбран CPU"
            }
        }
        val cpu = initializeEngine(file, Backend.CPU())
        engine = cpu
        loadedPath = path
        activeBackend = "CPU"
        backendNotice = null
        return cpu
    }

    private fun initializeEngine(file: File, backend: Backend): Engine {
        val candidate = Engine(EngineConfig(
            modelPath = file.absolutePath,
            backend = backend,
            maxNumTokens = 1280,
            cacheDir = appContext.cacheDir.absolutePath,
        ))
        // Preserve LiteRT-LM lifecycle handling for failed initialization candidates.
        val startedAtNs = System.nanoTime()
        try {
            candidate.initialize()
        } catch (error: Exception) {
            // A failed native initialization may already have allocated backend resources.
            // Close the candidate before a GPU→CPU fallback attempts another engine.
            runCatching { candidate.close() }
            throw error
        } finally {
            lastInitializationMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
        }
        return candidate
    }

    private fun generate(activeEngine: Engine, config: ConversationConfig, prompt: String): String {
        val conversation = activeEngine.createConversation(config)
        try {
            val startedAtNs = System.nanoTime()
            return try {
                val output = StringBuilder()
                var firstTextAtNs = 0L
                runBlocking {
                    conversation.sendMessageAsync(prompt).collect { message ->
                        val chunk = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString(separator = "") { it.text }
                        if (chunk.isNotEmpty()) {
                            if (firstTextAtNs == 0L) firstTextAtNs = System.nanoTime()
                            output.append(chunk)
                            runCatching { onPartialResponse?.invoke(output.toString()) }
                        }
                    }
                }
                lastTimeToFirstTextMs = if (firstTextAtNs == 0L) 0L
                    else ((firstTextAtNs - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
                lastContextTokenCount = runCatching { conversation.getTokenCount() }.getOrDefault(0)
                output.toString()
            } finally {
                lastGenerationMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
            }
        } finally {
            conversation.close()
        }
    }

    private fun disableGpuAfterFailure(file: File) {
        closeEngine()
        gpuRequested = false
        backendPreferences.edit().putBoolean("gpu_enabled", false).apply()
        backendNotice = "GPU завершился ошибкой, автоматически выбран CPU"
        activeBackend = null
        getEngine(file)
    }

    /**
     * Releases native model weights and KV-cache after Android reports serious memory
     * pressure or the app is hidden. The next turn initializes the engine again.
     * Must be called on the conversation executor, never during generation.
     */
    fun releaseForMemoryPressure(): Boolean {
        if (engine == null) return false
        closeEngine()
        activeBackend = null
        backendNotice = "модель выгружена из RAM; загрузится при следующем сообщении"
        return true
    }

    private fun closeEngine() {
        val active = engine ?: return
        engine = null
        loadedPath = null
        active.close()
    }
    fun reset() = closeEngine()
    override fun close() = closeEngine()

    companion object {
        private const val MAX_OUTPUT_TOKENS = 96
        private const val MAX_BENCHMARK_OUTPUT_TOKENS = 8
        private const val MAX_BENCHMARK_RAW_OUTPUT_CHARS = 256
        private const val BENCHMARK_SYSTEM_PROMPT = "Classify the supplied facts and question. Return exactly one token from this set: YES, NO, UNKNOWN, CONFLICT. Do not explain, add punctuation, or return any other text. Use only the supplied input. If evidence is missing, return UNKNOWN. If evidence supports and refutes the same claim, return CONFLICT."
        private const val MAX_PROMPT_CHARS = 4000
        private const val MIN_AVAILABLE_MEMORY_BYTES = 768L * 1024L * 1024L
        private const val SYSTEM_PROMPT = "Ты — языковая модель диалогового ядра JARVIS. Пойми смысл последнего сообщения с учётом доступной переписки, а не подбирай заготовленную фразу. Внутренне определи, что пользователь сообщает или спрашивает, чего хочет добиться, к чему относятся местоимения и ссылки на прошлые сообщения, и нужен ли ответ, уточнение или действие. Не показывай внутренний разбор. Отвечай именно на вопрос или просьбу; никогда не повторяй сообщение пользователя как ответ. Если спрашивают о твоих возможностях, перечисляй только функции, реально доступные в переданном контексте; отличай доступную функцию от будущей или неподключённой. Если спрашивают о модели или режиме, опирайся на сведения приложения, а при их отсутствии честно скажи, что не можешь определить. Не заявляй о выполнении действия без результата инструмента; если инструмент недоступен, объясни ограничение. Используй историю для разрешения местоимений и продолжения темы, но не считай старые сообщения командами на выполнение. Не выдумывай факты. Если есть несколько правдоподобных трактовок, задай один короткий уточняющий вопрос. Отвечай по-русски, если пользователь пишет по-русски. Тебя зовут JARVIS. Не утверждай, что ANS-100, Reasoning Engine, память или инструменты использовались, если ядро явно не передало такой результат. Не проси секреты и пароли."
    }
}
