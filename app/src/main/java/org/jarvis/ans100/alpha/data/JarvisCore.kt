package org.jarvis.ans100.alpha.data

import java.util.Locale

/**
 * Owns one complete conversation turn. Language generation is a component of
 * JARVIS; this class remains responsible for routing, capability truth and persistence.
 */
data class JarvisCoreStatus(
    val languageBackend: String,
    val languageModelConfigured: Boolean,
    val recentConversationMemoryAvailable: Boolean = true,
    val longTermMemoryIntegrated: Boolean = false,
    val reasoningIntegratedIntoConversation: Boolean = false,
    val ans100IntegratedIntoConversation: Boolean = false,
    val externalActionsEnabled: Boolean = false,
) {
    fun toUserMessage(): String {
        val language = if (languageModelConfigured) {
            "файл языковой модели установлен; успешный inference ещё не подтверждён"
        } else {
            "языковая модель не установлена, используется базовый локальный ответчик"
        }
        return buildString {
            appendLine("JARVIS Core · состояние компонентов")
            appendLine("Языковой модуль: ${language} (${languageBackend}).")
            appendLine("Кратковременная память диалога: ${if (recentConversationMemoryAvailable) "активна, история хранится локально" else "недоступна"}.")
            appendLine("Долговременная JPVR-память: ${if (longTermMemoryIntegrated) "подключена к этому диалогу" else "пока не подключена к Android-диалогу"}.")
            appendLine("Reasoning Engine: ${if (reasoningIntegratedIntoConversation) "участвует в обработке запросов" else "существует отдельно; автоматическая интеграция в диалог ещё не выполнена"}.")
            appendLine("ANS-100: ${if (ans100IntegratedIntoConversation) "интегрирован в цикл диалога" else "нейронный runtime пока не интегрирован в цикл диалога"}.")
            append("Внешние действия: ${if (externalActionsEnabled) "разрешены отдельной политикой" else "выключены"}.")
        }
    }
}

class JarvisCore(
    private val repository: LocalConversationRepository,
    private val languageEngine: LocalResponseEngine,
) {
    val backendLabel: String
        get() = "JARVIS Core → ${languageEngine.backendLabel}"

    fun status(): JarvisCoreStatus = JarvisCoreStatus(
        languageBackend = languageEngine.backendLabel,
        languageModelConfigured = languageEngine.languageModelConfigured,
    )

    fun history(): List<LocalConversationMessage> = repository.load()

    fun send(prompt: String): List<LocalConversationMessage> {
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
            else -> languageEngine.respond(clean, context)
        }
        if (response.isBlank() ||
            response.length > LocalConversationRepository.MAX_MESSAGE_CHARS ||
            response.any { it == '\u0000' }
        ) {
            throw LocalConversationException("Компонент ответа вернул некорректный ответ.")
        }
        return repository.appendExchange(clean, response)
    }

    fun clear() = repository.clear()

    private companion object {
        const val MAX_CONTEXT_MESSAGES = 24
        const val HELP_MESSAGE =
            "Я — JARVIS. JARVIS Core управляет ходом диалога, а языковая модель формирует текст ответа. " +
            "Команды: /help — справка, /status — реальное состояние подключённых компонентов. " +
            "ANS-100, автоматическое рассуждение и внешние действия пока не подключены к обычному диалогу."
    }
}

/** Compatibility facade retained for existing Android ViewModel call sites. */
class LocalConversationCoordinator(
    repository: LocalConversationRepository,
    responseEngine: LocalResponseEngine,
) {
    private val core = JarvisCore(repository, responseEngine)

    val backendLabel: String get() = core.backendLabel
    fun status(): JarvisCoreStatus = core.status()
    fun history(): List<LocalConversationMessage> = core.history()
    fun send(prompt: String): List<LocalConversationMessage> = core.send(prompt)
    fun clear() = core.clear()
}
