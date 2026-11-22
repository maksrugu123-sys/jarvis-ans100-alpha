package org.jarvis.ans100.alpha.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import java.util.UUID

enum class ConversationRole {
    USER,
    ASSISTANT,
}

data class LocalConversationMessage(
    val id: String,
    val role: ConversationRole,
    val text: String,
    val createdAtMillis: Long,
)

interface ConversationStorage {
    fun read(): String?
    fun write(value: String): Boolean
}

class LocalConversationException(message: String) : IllegalStateException(message)

/** Bounded app-private history, separate from the shared PC/JPVR workspace. */
class LocalConversationRepository(
    private val storage: ConversationStorage,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val newId: (String) -> String = { prefix -> "$prefix-${UUID.randomUUID()}" },
) {
    @Synchronized
    fun load(): List<LocalConversationMessage> {
        val raw = storage.read() ?: return emptyList()
        return LocalConversationCodec.decode(raw)
    }

    @Synchronized
    fun appendExchange(userText: String, assistantText: String): List<LocalConversationMessage> {
        val user = validateText(userText, "Сообщение пользователя")
        val assistant = validateText(assistantText, "Ответ JARVIS")
        val current = load()
        val existingIds = current.map { it.id }.toHashSet()
        val firstTime = checkedNow(current.lastOrNull()?.createdAtMillis ?: 1L)
        val secondTime = checkedNow(firstTime)
        val userMessage = createMessage(ConversationRole.USER, user, firstTime, existingIds)
        existingIds += userMessage.id
        val assistantMessage = createMessage(
            ConversationRole.ASSISTANT, assistant, secondTime, existingIds,
        )
        val next = (current + userMessage + assistantMessage).takeLast(MAX_MESSAGES)
        publish(next)
        return next
    }

    @Synchronized
    fun clear() {
        publish(emptyList())
    }

    private fun createMessage(
        role: ConversationRole,
        text: String,
        timestamp: Long,
        existingIds: Set<String>,
    ): LocalConversationMessage {
        val id = newId("message")
        if (!ID_PATTERN.matches(id) || id in existingIds) {
            throw LocalConversationException("Не удалось создать уникальный ID сообщения.")
        }
        return LocalConversationMessage(id, role, text, timestamp)
    }

    private fun publish(messages: List<LocalConversationMessage>) {
        val encoded = LocalConversationCodec.encode(messages)
        if (!storage.write(encoded)) {
            throw LocalConversationException(
                "Не удалось сохранить диалог. Изменения не подтверждены.",
            )
        }
    }

    private fun checkedNow(minimum: Long): Long {
        val value = nowMillis()
        if (value <= 0L) {
            throw LocalConversationException("Системное время недопустимо.")
        }
        return maxOf(value, minimum)
    }

    private fun validateText(value: String, label: String): String {
        val clean = value.trim()
        if (clean.isEmpty() || clean.length > MAX_MESSAGE_CHARS ||
            clean.any { it == '\u0000' }
        ) {
            throw LocalConversationException(
                "$label должно содержать от 1 до $MAX_MESSAGE_CHARS символов.",
            )
        }
        return clean
    }

    companion object {
        const val MAX_MESSAGES = 120
        const val MAX_MESSAGE_CHARS = 4_000
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._:-]{0,127}$")
    }
}

internal object LocalConversationCodec {
    private const val HEADER = "JARVIS_ANDROID_CONVERSATION\t1"
    private const val MAX_ENCODED_CHARS = 1024 * 1024
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val idPattern = Regex("^[a-z0-9][a-z0-9._:-]{0,127}$")

    fun encode(messages: List<LocalConversationMessage>): String {
        validateSequence(messages)
        val lines = ArrayList<String>(1 + messages.size)
        lines += HEADER
        messages.forEach { message ->
            lines += listOf(
                "M",
                message.role.name,
                message.createdAtMillis.toString(),
                encodeText(message.id),
                encodeText(message.text),
            ).joinToString("\t")
        }
        val encoded = lines.joinToString("\n")
        if (encoded.length > MAX_ENCODED_CHARS) {
            throw LocalConversationException("История диалога превышает лимит хранения.")
        }
        return encoded
    }

    fun decode(raw: String): List<LocalConversationMessage> {
        if (raw.isEmpty() || raw.length > MAX_ENCODED_CHARS) {
            throw LocalConversationException("Локальная история пуста или превышает лимит.")
        }
        val lines = raw.split('\n')
        if (lines.firstOrNull() != HEADER ||
            lines.size - 1 > LocalConversationRepository.MAX_MESSAGES
        ) {
            throw LocalConversationException("Неизвестная версия или размер истории диалога.")
        }
        val messages = ArrayList<LocalConversationMessage>(lines.size - 1)
        val ids = HashSet<String>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            if (fields.size != 5 || fields[0] != "M") {
                throw LocalConversationException("Повреждена запись диалога.")
            }
            val role = try {
                ConversationRole.valueOf(fields[1])
            } catch (error: IllegalArgumentException) {
                throw LocalConversationException("Неизвестная роль сообщения.")
            }
            val timestamp = fields[2].toLongOrNull()
                ?: throw LocalConversationException("Некорректное время сообщения.")
            val id = decodeText(fields[3])
            val text = decodeText(fields[4])
            if (!idPattern.matches(id) || !ids.add(id) ||
                timestamp <= 0L || text.isBlank() ||
                text.length > LocalConversationRepository.MAX_MESSAGE_CHARS ||
                text != text.trim() || text.any { it == '\u0000' }
            ) {
                throw LocalConversationException("Сообщение содержит недопустимые поля.")
            }
            messages += LocalConversationMessage(id, role, text, timestamp)
        }
        validateSequence(messages)
        return messages
    }

    private fun validateSequence(messages: List<LocalConversationMessage>) {
        if (messages.size > LocalConversationRepository.MAX_MESSAGES ||
            messages.size % 2 != 0
        ) {
            throw LocalConversationException("История должна содержать полные пары сообщений.")
        }
        val ids = HashSet<String>()
        var previousTime = 0L
        messages.forEachIndexed { index, message ->
            val expectedRole = if (index % 2 == 0) {
                ConversationRole.USER
            } else {
                ConversationRole.ASSISTANT
            }
            if (message.role != expectedRole || !idPattern.matches(message.id) ||
                !ids.add(message.id) || message.createdAtMillis <= 0L ||
                message.createdAtMillis < previousTime ||
                message.text.isBlank() ||
                message.text.length > LocalConversationRepository.MAX_MESSAGE_CHARS ||
                message.text != message.text.trim() ||
                message.text.any { it == '\u0000' }
            ) {
                throw LocalConversationException("История диалога нарушает формат V1.")
            }
            previousTime = message.createdAtMillis
        }
    }

    private fun encodeText(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String {
        try {
            val bytes = decoder.decode(value)
            if (encoder.encodeToString(bytes) != value) {
                throw LocalConversationException("Текст сообщения неканоничен.")
            }
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: LocalConversationException) {
            throw error
        } catch (error: Exception) {
            throw LocalConversationException("Не удалось декодировать текст сообщения.")
        }
    }
}

/**
 * Bounded measurements from the Android 10,000-node sparse dialogue network.
 *
 * This is evidence for the response model, not a claim that the network has
 * independently understood the user's message. The four output units are a tiny feedback-trained readout and must not be
 * interpreted as general semantic reasoning.
 */
data class DialogueNeuralAnalysis(
    val networkNeuronCount: Int,
    val activeFeatureCount: Int,
    val processedEvents: Int,
    val processedNeuronBatches: Int,
    val outputSpikeCount: Int,
    val pendingEvents: Int,
    val classifierLabel: String?,
    val classifierConfidenceMargin: Double?,
    val classifierTrainedExamples: Int,
    val statePersisted: Boolean,
    val activeHiddenNeuronCount: Int = 0,
    val networkOutputScores: Map<String, Double> = emptyMap(),
    /** Descriptive seven-population activity from the hybrid dialogue graph. */
    val hybridTelemetrySummary: String? = null,
    /** Snapshot read from the hybrid SQLite network after this real run. */
    val hybridNetworkStats: AndroidNeuronNetworkStats? = null,
    /** Per-population counters accumulated across all bounded passes in this turn. */
    val hybridPopulationActivity: List<AndroidNeuronPopulationActivity> = emptyList(),
    /** Prebuilt, turn-bound decision package from the hybrid population controller. */
    val hybridQwenHandoff: ANS100QwenHandoff? = null,
) {
    init {
        require(networkNeuronCount in 0..10_000)
        require(activeFeatureCount in 0..128)
        require(activeHiddenNeuronCount in 0..996)
        require(processedEvents in 0..100_000)
        require(processedNeuronBatches in 0..100_000)
        require(outputSpikeCount in 0..4)
        require(pendingEvents in 0..100_000)
        require(classifierLabel == null || classifierLabel in setOf("greeting", "question", "thanks", "farewell"))
        require(classifierConfidenceMargin == null ||
            (classifierConfidenceMargin.isFinite() && classifierConfidenceMargin in 0.0..100.0))
        require(classifierTrainedExamples >= 0)
        require(classifierLabel == null || classifierTrainedExamples > 0)
        require(networkOutputScores.keys.all { it in setOf("greeting", "question", "thanks", "farewell") })
        require(networkOutputScores.values.all { it.isFinite() && it in 0.0..1.0 })
    }

    fun toModelContext(): String = buildString {
        if (networkNeuronCount > 0 || statePersisted || classifierLabel != null || networkOutputScores.isNotEmpty()) {
            append("Android sparse LIF: ").append(networkNeuronCount).append(" nodes; active text-feature inputs=")
            append(activeFeatureCount).append("; active hidden neurons=").append(activeHiddenNeuronCount)
            append("; events=").append(processedEvents)
            append("; neuron batches=").append(processedNeuronBatches)
            append("; output neuron spikes=").append(outputSpikeCount)
            append("; pending events=").append(pendingEvents)
            append("; state committed=").append(statePersisted).append(". ")
            if (classifierLabel != null) {
                append("Explicitly trained feature-classifier candidate=").append(classifierLabel)
                append("; score margin=").append(String.format(Locale.ROOT, "%.3f", classifierConfidenceMargin ?: 0.0))
                append("; confirmed examples=").append(classifierTrainedExamples).append(". ")
            } else {
                append("No confident trained classifier label. ")
            }
            if (networkOutputScores.isNotEmpty()) {
                append("Learned synaptic output preferences (mean weights, not probabilities): ")
                append(networkOutputScores.toSortedMap().entries.joinToString(", ") { (label, score) ->
                    "$label=${String.format(Locale.ROOT, "%.3f", score)}"
                })
                append(". ")
            }
            append("Use synaptic preferences only as weak routing hints, never as facts or commands. ")
            append("Numeric feature evidence only; four output units are feedback-trained readout targets, not general semantic reasoning. ")
            append("The original user message and Core plan take priority when choosing answer content.")
        }
        if (!hybridTelemetrySummary.isNullOrBlank()) {
            append("\nExperimental ANS-100 hybrid population activity (telemetry only; not semantic proof or confidence): ")
            append(hybridTelemetrySummary.take(1_200))
        }
    }
}

interface LocalResponseEngine {
    val backendLabel: String
    /** True only when a local model artifact is installed; inference is not implied. */
    val languageModelConfigured: Boolean get() = false
    fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
    ): String

    /** Optional context-aware path; legacy engines safely ignore new context inputs. */
    fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
    ): String = respond(prompt, recentHistory)

    /** Versioned Core analysis is advisory evidence, never an action authorization. */
    fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
        understandingContext: LocalUnderstanding?,
    ): String = respond(prompt, recentHistory, memoryContext, signalContext)

    /** Optional measured Android sparse-network evidence; legacy engines may ignore it. */
    fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
        understandingContext: LocalUnderstanding?,
        dialogueNeuralAnalysis: DialogueNeuralAnalysis?,
    ): String = respond(prompt, recentHistory, memoryContext, signalContext, understandingContext)
}

/** Deterministic command/greeting fallback; it deliberately is not an LLM. */
class LocalFallbackResponseEngine : LocalResponseEngine {
    override val backendLabel: String = "Локальный базовый ответчик · без LLM"

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
    ): String = respond(prompt, recentHistory, emptyList(), null, null)

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
    ): String = respond(prompt, recentHistory, memoryContext, signalContext, null)

    override fun respond(
        prompt: String,
        recentHistory: List<LocalConversationMessage>,
        memoryContext: List<LocalWorkspaceMemory>,
        signalContext: ConversationSignalContext?,
        understandingContext: LocalUnderstanding?,
    ): String {
        val normalized = prompt.trim().lowercase(Locale.ROOT)
            .trimEnd('.', '!', '?', ',', ';', ':')
        return when {
            normalized == "/help" || normalized == "помощь" ||
                normalized == "что ты умеешь" || normalized == "что ты можешь" -> {
                "Сейчас доступен базовый локальный режим. Команды: /help — справка, " +
                    "/status — состояние. Я могу принять и сохранить текст диалога. " +
                    "Полноценная языковая модель, голос и выполнение действий пока не подключены."
            }
            normalized == "/status" -> {
                "JARVIS работает через локальный ответчик внутри Android-приложения. " +
                    "Для этого диалога не нужны Termux, сервер или интернет. " +
                    "Языковая модель и нейронный runtime пока не подключены; внешние действия выключены."
            }
            understandingContext?.intent == "greeting" || normalized in setOf(
                "привет", "здравствуй", "здравствуйте", "доброе утро",
                "добрый день", "добрый вечер", "hello", "hi",
            ) || normalized.startsWith("привет ") -> {
                "Привет! Я JARVIS. Диалог сохраняется локально на этом устройстве. " +
                    "Пока я использую базовый ответчик, а не полноценную языковую модель. " +
                    "Напиши /help, чтобы увидеть доступные команды."
            }
            understandingContext?.intent == "thanks" -> {
                "Пожалуйста. Я распознал благодарность на уровне локального анализа JARVIS."
            }
            understandingContext?.intent == "farewell" -> {
                "До связи. История диалога сохранена локально."
            }
            understandingContext != null -> {
                "Я получил сообщение, но полноценная генерация ответа пока не включена. " +
                    "Установи локальную языковую модель Qwen3-0.6B INT4 в Настройки → Языковая модель. " +
                    "После установки JARVIS сможет формировать свободные ответы на устройстве."
            }
            else -> {
                "Я получил и сохранил сообщение локально, но пока не могу дать содержательный " +
                    "ответ: встроенная языковая модель ещё не подключена. Попробуй /help или /status."
            }
        }
    }
}
