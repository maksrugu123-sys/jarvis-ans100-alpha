package org.jarvis.ans100.alpha.data

import java.util.Locale

data class LocalWorkspaceMemory(
    val id: String,
    val content: String,
    val category: String,
    val updatedAt: String,
    val userApproved: Boolean,
)

data class LocalWorkspaceMemorySnapshot(
    val loaded: Boolean,
    val records: List<LocalWorkspaceMemory>,
)

class LocalWorkspaceMemoryCache {
    @Volatile private var loaded: Boolean = false
    @Volatile private var records: List<LocalWorkspaceMemory> = emptyList()

    @Synchronized
    fun replace(values: List<LocalWorkspaceMemory>) {
        records = values
            .filter { it.userApproved && it.content.isNotBlank() }
            .takeLast(MAX_CACHED_RECORDS)
            .map { memory ->
                memory.copy(
                    content = memory.content.trim().take(MAX_MEMORY_CHARS),
                    category = memory.category.trim().take(MAX_CATEGORY_CHARS),
                )
            }
        loaded = true
    }

    @Synchronized
    fun clear() {
        records = emptyList()
        loaded = false
    }

    fun snapshot(): LocalWorkspaceMemorySnapshot =
        LocalWorkspaceMemorySnapshot(loaded, records.toList())

    companion object {
        const val MAX_CACHED_RECORDS = 2_000
        const val MAX_MEMORY_CHARS = 700
        const val MAX_CATEGORY_CHARS = 64
    }
}

/** Validated low-level summary returned by the real ANS-100 NetworkRuntime bridge. */
data class ConversationSignalContext(
    val runtimeVersion: String,
    val processedEvents: Int,
    val processedBatches: Int,
    val spikeCount: Int,
    val attentionGateActive: Boolean,
    val tokenVolume: Double,
    val lexicalDiversity: Double,
    val questionMarker: Double,
    val integratorAdaptation: Double = 0.0,
    val effectiveThreshold: Double = 1.0,
    val statePersisted: Boolean = false,
    val checkpointVersion: Long = 0L,
) {
    init {
        if (runtimeVersion != "JARVIS_ANS100_CONVERSATION_SIGNAL_V1" ||
            processedEvents !in 0..8 || processedBatches !in 0..8 || spikeCount !in 0..2 ||
            !tokenVolume.isFinite() || tokenVolume !in 0.0..1.0 ||
            !lexicalDiversity.isFinite() || lexicalDiversity !in 0.0..1.0 ||
            !questionMarker.isFinite() || questionMarker !in 0.0..1.0 ||
            !integratorAdaptation.isFinite() || integratorAdaptation !in 0.0..1.0 ||
            !effectiveThreshold.isFinite() || effectiveThreshold !in 1.0..1.5 ||
            checkpointVersion < 0L
        ) {
            throw RuntimeBridgeException("Сводка сигнала ANS-100 содержит недопустимые значения.")
        }
    }

    fun toModelContext(): String = buildString {
        appendLine("Runtime: $runtimeVersion")
        appendLine("Обработано событий: $processedEvents; пакетов: $processedBatches; импульсов: $spikeCount.")
        appendLine("Attention gate активирован: $attentionGateActive.")
        appendLine(
            "Числовые признаки: token_volume=${String.format(Locale.ROOT, "%.3f", tokenVolume)}, " +
                "lexical_diversity=${String.format(Locale.ROOT, "%.3f", lexicalDiversity)}, " +
                "question_marker=${String.format(Locale.ROOT, "%.3f", questionMarker)}, " +
                "integrator_adaptation=${String.format(Locale.ROOT, "%.3f", integratorAdaptation)}, " +
                "effective_threshold=${String.format(Locale.ROOT, "%.3f", effectiveThreshold)}.",
        )
        appendLine("Адаптивное состояние сохранено в отдельном JPVR: $statePersisted.")
        append("Область: только низкоуровневая обработка сигнала; это не семантическое понимание и не полноценное рассуждение.")
    }
}

/**
 * Owns every Android conversation turn. Language generation, read-only JPVR
 * recall and the optional ANS-100 signal pass are separate components.
 */
data class JarvisCoreStatus(
    val languageBackend: String,
    val languageModelConfigured: Boolean,
    val recentConversationMemoryAvailable: Boolean = true,
    val longTermMemoryIntegrated: Boolean = false,
    val longTermMemoryRecordCount: Int = 0,
    val memoriesRetrievedLastTurn: Int = 0,
    val reasoningIntegratedIntoConversation: Boolean = false,
    val ans100IntegratedIntoConversation: Boolean = false,
    val ans100SignalProcessorActive: Boolean = false,
    val ans100AttentionGateActive: Boolean = false,
    val ans100AdaptiveStatePersisted: Boolean = false,
    val externalActionsEnabled: Boolean = false,
) {
    fun toUserMessage(): String {
        val language = if (languageModelConfigured) {
            "файл языковой модели установлен; успешный inference ещё не подтверждён"
        } else {
            "языковая модель не установлена, используется базовый локальный ответчик"
        }
        val memory = when {
            !longTermMemoryIntegrated -> "общий JPVR workspace ещё не загружен в это приложение"
            longTermMemoryRecordCount == 0 -> "workspace загружен, подтверждённых воспоминаний нет"
            else -> "кэшировано $longTermMemoryRecordCount подтверждённых записей; найдено для прошлого запроса: $memoriesRetrievedLastTurn"
        }
        val neural = when {
            !ans100SignalProcessorActive -> "для последнего запроса нет результата локального сигнального прохода"
            ans100AttentionGateActive -> "ограниченный ALIF-DT сигнальный проход выполнен; attention gate активирован"
            else -> "ограниченный ALIF-DT сигнальный проход выполнен; attention gate не активирован"
        }
        return buildString {
            appendLine("JARVIS Core · состояние компонентов")
            appendLine("Языковой модуль: $language ($languageBackend).")
            appendLine("Кратковременная память диалога: ${if (recentConversationMemoryAvailable) "активна, история хранится локально" else "недоступна"}.")
            appendLine("Долговременная JPVR-память: $memory.")
            appendLine("Reasoning Engine: ${if (reasoningIntegratedIntoConversation) "участвует в обработке запросов" else "символьный preview пока не подключён к обычному диалогу"}.")
            appendLine("ANS-100: $neural.")
            appendLine("STDP-обучение на диалоге выключено; адаптивное состояние ALIF сохранено отдельно: $ans100AdaptiveStatePersisted.")
            append("Внешние действия: ${if (externalActionsEnabled) "разрешены отдельной политикой" else "выключены"}.")
        }
    }
}

class JarvisCore(
    private val repository: LocalConversationRepository,
    private val languageEngine: LocalResponseEngine,
    private val memoryCache: LocalWorkspaceMemoryCache = LocalWorkspaceMemoryCache(),
) {
    private var lastSignalContext: ConversationSignalContext? = null
    private var lastRetrievedMemoryCount: Int = 0

    val backendLabel: String
        get() = "JARVIS Core → ${languageEngine.backendLabel}"

    fun status(): JarvisCoreStatus {
        val snapshot = memoryCache.snapshot()
        val signal = lastSignalContext
        return JarvisCoreStatus(
            languageBackend = languageEngine.backendLabel,
            languageModelConfigured = languageEngine.languageModelConfigured,
            longTermMemoryIntegrated = snapshot.loaded,
            longTermMemoryRecordCount = snapshot.records.size,
            memoriesRetrievedLastTurn = lastRetrievedMemoryCount,
            ans100SignalProcessorActive = signal != null,
            ans100AttentionGateActive = signal?.attentionGateActive == true,
            ans100AdaptiveStatePersisted = signal?.statePersisted == true,
        )
    }

    fun history(): List<LocalConversationMessage> = repository.load()

    fun send(
        prompt: String,
        signalContext: ConversationSignalContext? = null,
    ): List<LocalConversationMessage> {
        val clean = prompt.trim()
        if (clean.isEmpty() || clean.length > LocalConversationRepository.MAX_MESSAGE_CHARS ||
            clean.any { it == '\u0000' }
        ) {
            throw LocalConversationException("Сообщение должно содержать от 1 до 4 000 символов.")
        }

        val context = repository.load().takeLast(MAX_CONTEXT_MESSAGES)
        val response = when (clean.lowercase(Locale.ROOT)) {
            "/status" -> status().toUserMessage()
            "/help" -> HELP_MESSAGE
            else -> {
                lastSignalContext = signalContext
                val memories = if (languageEngine.languageModelConfigured) {
                    retrieveMemories(clean)
                } else {
                    emptyList()
                }
                lastRetrievedMemoryCount = memories.size
                languageEngine.respond(clean, context, memories, signalContext)
            }
        }
        if (response.isBlank() ||
            response.length > LocalConversationRepository.MAX_MESSAGE_CHARS ||
            response.any { it == '\u0000' }
        ) {
            throw LocalConversationException("Компонент ответа вернул некорректный ответ.")
        }
        return repository.appendExchange(clean, response)
    }

    fun replaceWorkspaceMemories(records: List<LocalWorkspaceMemory>) =
        memoryCache.replace(records)

    fun clearWorkspaceMemories() = memoryCache.clear()

    private fun retrieveMemories(prompt: String): List<LocalWorkspaceMemory> {
        val snapshot = memoryCache.snapshot()
        if (!snapshot.loaded) return emptyList()
        val queryTokens = tokens(prompt)
        if (queryTokens.isEmpty()) return emptyList()
        val ranked = snapshot.records.mapIndexedNotNull { index, memory ->
            val textTokens = tokens(memory.content + " " + memory.category)
            val score = queryTokens.intersect(textTokens).size
            if (score == 0) null else Triple(score, index, memory)
        }.sortedWith(compareByDescending<Triple<Int, Int, LocalWorkspaceMemory>> { it.first }
            .thenBy { it.second })

        val selected = ArrayList<LocalWorkspaceMemory>(MAX_RETRIEVED_MEMORIES)
        var totalChars = 0
        for ((_, _, memory) in ranked) {
            if (selected.size >= MAX_RETRIEVED_MEMORIES) break
            val candidate = memory.copy(
                content = memory.content.trim().take(MAX_MEMORY_CHARS),
                category = memory.category.trim().take(64),
            )
            val size = candidate.content.length + candidate.category.length + 32
            if (totalChars + size > MAX_MEMORY_CONTEXT_CHARS) continue
            selected += candidate
            totalChars += size
        }
        return selected
    }

    private fun tokens(value: String): Set<String> =
        TOKEN_PATTERN.findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toSet()

    fun clear() = repository.clear()

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 24
        const val MAX_RETRIEVED_MEMORIES = 3
        const val MAX_MEMORY_CHARS = 700
        const val MAX_MEMORY_CONTEXT_CHARS = 1_800
        val TOKEN_PATTERN = Regex("[a-zа-яё0-9]{3,}")
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from",
            "как", "это", "для", "или", "что", "чтобы", "мне",
            "могу", "можно", "когда", "где", "почему", "который",
            "которая", "которые", "есть",
        )
        const val HELP_MESSAGE =
            "Я — JARVIS. JARVIS Core управляет ходом диалога, а языковая модель формирует текст ответа. " +
            "Подтверждённые воспоминания JPVR могут передаваться как контекст. ANS-100 выполняет только " +
            "ограниченную числовую обработку входного сигнала; обучение на диалоге пока выключено. " +
            "Команды: /help — справка, /status — состояние компонентов."
    }
}

/** Compatibility facade retained for existing Android ViewModel call sites. */
class LocalConversationCoordinator(
    repository: LocalConversationRepository,
    responseEngine: LocalResponseEngine,
    private val memoryCache: LocalWorkspaceMemoryCache = LocalWorkspaceMemoryCache(),
) {
    private val core = JarvisCore(repository, responseEngine, memoryCache)

    val backendLabel: String get() = core.backendLabel
    fun status(): JarvisCoreStatus = core.status()
    fun history(): List<LocalConversationMessage> = core.history()
    fun send(
        prompt: String,
        signalContext: ConversationSignalContext? = null,
    ): List<LocalConversationMessage> = core.send(prompt, signalContext)
    fun replaceWorkspaceMemories(records: List<LocalWorkspaceMemory>) =
        core.replaceWorkspaceMemories(records)
    fun clearWorkspaceMemories() = core.clearWorkspaceMemories()
    fun clear() = core.clear()
}
