package org.jarvis.ans100.alpha.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

enum class LocalSessionStatus {
    OPEN,
    PAUSED,
    CLOSED,
}

data class LocalSession(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val revision: Int = 1,
    val status: LocalSessionStatus = LocalSessionStatus.OPEN,
)

data class LocalTaskDraft(
    val id: String,
    val sessionId: String,
    val title: String,
    val createdAtMillis: Long,
)

data class LocalDraftState(
    val sessions: List<LocalSession> = emptyList(),
    val tasks: List<LocalTaskDraft> = emptyList(),
)

interface DraftStorage {
    fun read(): String?
    fun write(value: String): Boolean
}

class LocalDraftException(message: String) : IllegalStateException(message)

/**
 * Versioned, bounded Android-only draft storage.
 *
 * This repository deliberately does not claim to be the Python TaskSessionManager
 * or JPVR store. Records remain local drafts until a future authenticated runtime
 * bridge is designed and implemented.
 */
class LocalDraftRepository(
    private val storage: DraftStorage,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val newId: (String) -> String = { prefix -> "$prefix-${UUID.randomUUID()}" },
) {
    fun load(): LocalDraftState {
        val raw = storage.read() ?: return LocalDraftState()
        return LocalDraftCodec.decode(raw)
    }

    fun createSession(title: String): LocalDraftState {
        val cleanTitle = validateTitle(title)
        val current = load()
        if (current.sessions.size >= MAX_SESSIONS) {
            throw LocalDraftException("Достигнут лимит локальных сессий ($MAX_SESSIONS).")
        }
        val session = LocalSession(
            id = newId("ses"),
            title = cleanTitle,
            createdAtMillis = checkedNow(),
        )
        if (current.sessions.any { it.id == session.id }) {
            throw LocalDraftException("Повторился идентификатор сессии.")
        }
        return publish(current.copy(sessions = current.sessions + session))
    }

    fun createTask(sessionId: String, title: String): LocalDraftState {
        val cleanTitle = validateTitle(title)
        val current = load()
        val session = current.sessions.firstOrNull { it.id == sessionId }
            ?: throw LocalDraftException("Сессия не найдена.")
        if (session.status != LocalSessionStatus.OPEN) {
            throw LocalDraftException("Добавлять черновики можно только в открытую сессию.")
        }
        if (current.tasks.size >= MAX_TASKS) {
            throw LocalDraftException("Достигнут лимит локальных черновиков ($MAX_TASKS).")
        }
        val task = LocalTaskDraft(
            id = newId("draft"),
            sessionId = sessionId,
            title = cleanTitle,
            createdAtMillis = checkedNow(),
        )
        if (current.tasks.any { it.id == task.id }) {
            throw LocalDraftException("Повторился идентификатор черновика.")
        }
        return publish(current.copy(tasks = current.tasks + task))
    }

    fun setSessionStatus(
        sessionId: String,
        status: LocalSessionStatus,
        expectedRevision: Int,
    ): LocalDraftState {
        val current = load()
        val session = current.sessions.firstOrNull { it.id == sessionId }
            ?: throw LocalDraftException("Сессия не найдена.")
        if (session.revision != expectedRevision) {
            throw LocalDraftException("Версия сессии изменилась. Обновите экран.")
        }
        if (session.status == status) return current
        val allowed = when (session.status) {
            LocalSessionStatus.OPEN -> setOf(LocalSessionStatus.PAUSED, LocalSessionStatus.CLOSED)
            LocalSessionStatus.PAUSED -> setOf(LocalSessionStatus.OPEN, LocalSessionStatus.CLOSED)
            LocalSessionStatus.CLOSED -> emptySet()
        }
        if (status !in allowed) {
            throw LocalDraftException("Недопустимое изменение статуса сессии.")
        }
        if (session.revision == Int.MAX_VALUE) {
            throw LocalDraftException("Исчерпан счётчик ревизий сессии.")
        }
        val updated = session.copy(status = status, revision = session.revision + 1)
        return publish(current.copy(
            sessions = current.sessions.map { if (it.id == sessionId) updated else it }
        ))
    }

    fun deleteTask(taskId: String): LocalDraftState {
        val current = load()
        if (current.tasks.none { it.id == taskId }) {
            throw LocalDraftException("Черновик не найден.")
        }
        return publish(current.copy(tasks = current.tasks.filterNot { it.id == taskId }))
    }

    private fun publish(candidate: LocalDraftState): LocalDraftState {
        val encoded = LocalDraftCodec.encode(candidate)
        if (!storage.write(encoded)) {
            throw LocalDraftException("Не удалось сохранить локальные данные. Изменения не применены.")
        }
        return candidate
    }

    private fun checkedNow(): Long {
        val value = nowMillis()
        if (value <= 0L) throw LocalDraftException("Системное время недопустимо.")
        return value
    }

    private fun validateTitle(value: String): String {
        val clean = value.trim()
        if (clean.isEmpty() || clean.length > MAX_TITLE_CHARS || clean.any { it == '\u0000' }) {
            throw LocalDraftException("Название должно содержать от 1 до $MAX_TITLE_CHARS символов.")
        }
        return clean
    }

    companion object {
        const val MAX_SESSIONS = 32
        const val MAX_TASKS = 500
        const val MAX_TITLE_CHARS = 240
    }
}

internal object LocalDraftCodec {
    private const val HEADER = "JARVIS_ANDROID_DRAFTS"
    private const val VERSION = 1
    private const val MAX_ENCODED_CHARS = 512 * 1024
    private val idPattern = Regex("^[a-z0-9][a-z0-9._:-]{0,127}$")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(state: LocalDraftState): String {
        if (state.sessions.size > LocalDraftRepository.MAX_SESSIONS ||
            state.tasks.size > LocalDraftRepository.MAX_TASKS
        ) {
            throw LocalDraftException("Локальные данные превышают допустимый размер.")
        }
        val sessionIds = state.sessions.map { it.id }.toSet()
        if (sessionIds.size != state.sessions.size ||
            state.tasks.map { it.id }.toSet().size != state.tasks.size
        ) {
            throw LocalDraftException("Локальные данные содержат повторяющиеся идентификаторы.")
        }
        val lines = ArrayList<String>(1 + state.sessions.size + state.tasks.size)
        lines += "$HEADER\t$VERSION"
        for (session in state.sessions) {
            validateSession(session)
            lines += listOf(
                "S", encodeText(session.id), session.createdAtMillis.toString(),
                session.revision.toString(), session.status.name, encodeText(session.title),
            ).joinToString("\t")
        }
        for (task in state.tasks) {
            validateTask(task, sessionIds)
            lines += listOf(
                "T", encodeText(task.id), encodeText(task.sessionId),
                task.createdAtMillis.toString(), encodeText(task.title),
            ).joinToString("\t")
        }
        val encoded = lines.joinToString("\n")
        if (encoded.length > MAX_ENCODED_CHARS) {
            throw LocalDraftException("Файл локальных черновиков слишком большой.")
        }
        return encoded
    }

    fun decode(raw: String): LocalDraftState {
        if (raw.isEmpty() || raw.length > MAX_ENCODED_CHARS) {
            throw LocalDraftException("Файл локальных черновиков пуст или превышает лимит.")
        }
        val lines = raw.split('\n')
        if (lines.firstOrNull() != "$HEADER\t$VERSION") {
            throw LocalDraftException("Неизвестная версия локальных данных.")
        }
        val sessions = ArrayList<LocalSession>()
        val tasks = ArrayList<LocalTaskDraft>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) throw LocalDraftException("Пустая запись в локальном файле.")
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                "S" -> {
                    if (fields.size != 6 || sessions.size >= LocalDraftRepository.MAX_SESSIONS) {
                        throw LocalDraftException("Некорректная запись сессии.")
                    }
                    val session = LocalSession(
                        id = decodeText(fields[1]),
                        createdAtMillis = parseLong(fields[2], "время сессии"),
                        revision = parseInt(fields[3], "ревизия сессии"),
                        status = try {
                            LocalSessionStatus.valueOf(fields[4])
                        } catch (error: IllegalArgumentException) {
                            throw LocalDraftException("Неизвестный статус сессии.")
                        },
                        title = decodeText(fields[5]),
                    )
                    validateSession(session)
                    if (sessions.any { it.id == session.id }) {
                        throw LocalDraftException("Повторяющийся ID сессии.")
                    }
                    sessions += session
                }
                "T" -> {
                    if (fields.size != 5 || tasks.size >= LocalDraftRepository.MAX_TASKS) {
                        throw LocalDraftException("Некорректная запись черновика.")
                    }
                    val task = LocalTaskDraft(
                        id = decodeText(fields[1]),
                        sessionId = decodeText(fields[2]),
                        createdAtMillis = parseLong(fields[3], "время черновика"),
                        title = decodeText(fields[4]),
                    )
                    if (task.id.isBlank() || !idPattern.matches(task.id) ||
                        task.sessionId.isBlank() || !idPattern.matches(task.sessionId) ||
                        task.title.isBlank() || task.title.length > LocalDraftRepository.MAX_TITLE_CHARS ||
                        task.title != task.title.trim() || task.title.any { it == '\u0000' } ||
                        task.createdAtMillis <= 0L
                    ) {
                        throw LocalDraftException("Черновик содержит некорректные поля.")
                    }
                    if (tasks.any { it.id == task.id }) {
                        throw LocalDraftException("Повторяющийся ID черновика.")
                    }
                    tasks += task
                }
                else -> throw LocalDraftException("Неизвестный тип записи локального файла.")
            }
        }
        val sessionIds = sessions.map { it.id }.toSet()
        if (tasks.any { it.sessionId !in sessionIds }) {
            throw LocalDraftException("Черновик ссылается на неизвестную сессию.")
        }
        return LocalDraftState(sessions = sessions, tasks = tasks)
    }

    private fun validateSession(session: LocalSession) {
        if (!idPattern.matches(session.id) || session.title.isBlank() ||
            session.title.length > LocalDraftRepository.MAX_TITLE_CHARS ||
            session.title != session.title.trim() || session.title.any { it == '\u0000' } ||
            session.createdAtMillis <= 0L || session.revision < 1
        ) {
            throw LocalDraftException("Сессия содержит некорректные поля.")
        }
    }

    private fun validateTask(task: LocalTaskDraft, sessionIds: Set<String>) {
        if (!idPattern.matches(task.id) || !idPattern.matches(task.sessionId) ||
            task.sessionId !in sessionIds || task.title.isBlank() ||
            task.title.length > LocalDraftRepository.MAX_TITLE_CHARS ||
            task.title != task.title.trim() || task.title.any { it == '\u0000' } ||
            task.createdAtMillis <= 0L
        ) {
            throw LocalDraftException("Черновик содержит некорректные поля.")
        }
    }

    private fun encodeText(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String {
        try {
            val bytes = decoder.decode(value)
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            return text.toString()
        } catch (error: Exception) {
            throw LocalDraftException("Повреждён текст в локальном файле.")
        }
    }

    private fun parseLong(value: String, label: String): Long =
        value.toLongOrNull() ?: throw LocalDraftException("Некорректное поле: $label.")

    private fun parseInt(value: String, label: String): Int =
        value.toIntOrNull() ?: throw LocalDraftException("Некорректное поле: $label.")
}
