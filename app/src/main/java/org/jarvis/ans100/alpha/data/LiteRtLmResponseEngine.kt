package org.jarvis.ans100.alpha.data
import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

class LiteRtLmResponseEngine(context: Context, private val modelStore: LiteRtLmModelStore) : LocalResponseEngine, AutoCloseable {
    private val appContext = context.applicationContext
    private val fallback = LocalFallbackResponseEngine()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    override val backendLabel: String
        get() = if (modelStore.installedFile() != null) "LiteRT-LM · Qwen3-0.6B INT4 · CPU · offline" else fallback.backendLabel

    override fun respond(prompt: String, recentHistory: List<LocalConversationMessage>): String {
        val clean = prompt.trim()
        if (clean.length > MAX_PROMPT_CHARS) throw LocalConversationException("Ограничение компактной модели — " + MAX_PROMPT_CHARS + " символов на запрос.")
        val file = modelStore.installedFile() ?: return fallback.respond(clean, recentHistory)
        val activeEngine = getEngine(file)
        val initial = recentHistory.takeLast(MAX_HISTORY).map { item ->
            val text = item.text.take(MAX_HISTORY_CHARS)
            if (item.role == ConversationRole.USER) Message.user(text) else Message.model(text)
        }
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
            initialMessages = initial,
            samplerConfig = SamplerConfig(topK = 32, topP = 0.9, temperature = 0.6),
            automaticToolCalling = false,
            channels = emptyList(),
            maxOutputToken = MAX_OUTPUT_TOKENS,
        )
        val conversation = activeEngine.createConversation(config)
        try {
            val answer = conversation.sendMessage(clean, maxOutputToken = MAX_OUTPUT_TOKENS).toString().trim()
            if (answer.isEmpty()) throw LocalConversationException("Модель вернула пустой ответ.")
            return answer.take(LocalConversationRepository.MAX_MESSAGE_CHARS)
        } catch (error: LocalConversationException) {
            throw error
        } catch (_: Exception) {
            throw LocalConversationException("Локальная модель не смогла ответить. Проверьте файл модели и доступную память.")
        } finally {
            conversation.close()
        }
    }

    private fun checkMemory() {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: throw LocalConversationException("Не удалось проверить доступную память устройства.")
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (info.lowMemory || info.availMem < MIN_AVAILABLE_MEMORY_BYTES) {
            throw LocalConversationException("Модель не загружена: требуется не менее 1 280 МиБ доступной RAM. Закройте тяжёлые приложения. Это ограничение не гарантирует отсутствие OOM.")
        }
    }

    private fun getEngine(file: File): Engine {
        val path = file.absolutePath
        val existing = engine
        if (existing != null && loadedPath == path) return existing
        if (existing != null) closeEngine()
        checkMemory()
        val candidate = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 1280, cacheDir = appContext.cacheDir.absolutePath))
        // Do not close an Engine if initialize throws; the runtime marks it uninitialized.
        candidate.initialize()
        engine = candidate
        loadedPath = path
        return candidate
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
        private const val MAX_PROMPT_CHARS = 900
        private const val MAX_HISTORY = 4
        private const val MAX_HISTORY_CHARS = 320
        private const val MIN_AVAILABLE_MEMORY_BYTES = 1280L * 1024L * 1024L
        private const val SYSTEM_PROMPT = "Ты — локальный помощник JARVIS. Отвечай по-русски кратко и прямо. Если не знаешь, так и скажи. Не выдумывай факты и не утверждай, что выполнил действия вне чата. У тебя нет доступа к сети, файлам, приложениям или инструментам. Не проси секреты и пароли."
    }
}
