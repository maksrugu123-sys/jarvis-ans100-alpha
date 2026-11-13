package org.jarvis.ans100.alpha

import android.app.Application
import android.os.Handler
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jarvis.ans100.alpha.data.LocalConversationCoordinator
import org.jarvis.ans100.alpha.data.LocalConversationMessage
import org.jarvis.ans100.alpha.data.LocalConversationRepository
import org.jarvis.ans100.alpha.data.LocalFallbackResponseEngine
import org.jarvis.ans100.alpha.data.SharedPreferencesConversationStorage
import org.jarvis.ans100.alpha.data.LocalDraftException
import org.jarvis.ans100.alpha.data.LocalDraftRepository
import org.jarvis.ans100.alpha.data.LocalDraftState
import org.jarvis.ans100.alpha.data.LocalRuntimeBridgeClient
import org.jarvis.ans100.alpha.data.LocalSessionStatus
import org.jarvis.ans100.alpha.data.RuntimeBridgeSnapshot
import org.jarvis.ans100.alpha.data.RuntimeAccessTokenStore
import org.jarvis.ans100.alpha.data.RuntimeReasoningPreviewRequest
import org.jarvis.ans100.alpha.data.RuntimeReasoningPreviewResult
import org.jarvis.ans100.alpha.data.RuntimeWorkspaceSnapshot
import org.jarvis.ans100.alpha.data.RuntimeWorkspaceMergePlan
import org.jarvis.ans100.alpha.data.SharedPreferencesDraftStorage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

data class LocalConversationUiState(
    val messages: List<LocalConversationMessage> = emptyList(),
    val isBusy: Boolean = false,
    val error: String? = null,
    val backendLabel: String = "Локальный базовый ответчик · без LLM",
    val modelInstalled: Boolean = false,
    val modelStatus: String = "Модель не установлена.",
)

data class RuntimeBridgeUiState(
    val isBusy: Boolean = false,
    val isConnected: Boolean = false,
    val snapshot: RuntimeBridgeSnapshot? = null,
    val error: String? = null,
    val message: String? = null,
    val reasoningPreview: RuntimeReasoningPreviewResult? = null,
    val hasAccessToken: Boolean = false,
)

data class RuntimeMemory(
    val id: String,
    val content: String,
    val category: String,
    val updatedAt: String,
)

data class RuntimeWorkspaceImportPreview(
    val addedCount: Int,
    val identicalCount: Int,
    val conflictCount: Int,
)

data class RuntimeWorkspaceRestorePreview(
    val backupCreatedAt: String,
    val conversationCount: Int,
    val memoryCount: Int,
    val taskCount: Int,
    val settingCount: Int,
)

data class RuntimeWorkspaceUiState(
    val revision: Long = 0L,
    val memories: List<RuntimeMemory> = emptyList(),
    val isLoaded: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val importPreview: RuntimeWorkspaceImportPreview? = null,
    val backupRevision: Long? = null,
    val restoreBackupAcknowledged: Boolean = false,
    val restorePreview: RuntimeWorkspaceRestorePreview? = null,
)

data class JarvisUiState(
    val data: LocalDraftState = LocalDraftState(),
    val selectedSessionId: String? = null,
    val notice: String? = null,
    val storageReady: Boolean = true,
    val runtime: RuntimeBridgeUiState = RuntimeBridgeUiState(),
    val workspace: RuntimeWorkspaceUiState = RuntimeWorkspaceUiState(),
    val conversation: LocalConversationUiState = LocalConversationUiState(),
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalDraftRepository(
        SharedPreferencesDraftStorage(application.applicationContext),
    )
    private val modelStore = LiteRtLmModelStore(application.applicationContext)
    private val conversationResponseEngine = LiteRtLmResponseEngine(
        application.applicationContext, modelStore,
    )
    private val conversationExecutor = Executors.newSingleThreadExecutor()
    private val conversationCoordinator = LocalConversationCoordinator(
        LocalConversationRepository(
            SharedPreferencesConversationStorage(application.applicationContext),
        ),
        conversationResponseEngine,
    )
    private val runtimeClient = LocalRuntimeBridgeClient()
    private val runtimeTokenStore = RuntimeAccessTokenStore(application.applicationContext)
    private val runtimeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var pendingWorkspaceMerge: RuntimeWorkspaceMergePlan? = null
    @Volatile private var pendingWorkspaceRestore: org.jarvis.ans100.alpha.data.RuntimeWorkspaceRestorePlan? = null
    @Volatile private var pendingWorkspaceBackupRevision: Long? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(JarvisUiState())
    val state: StateFlow<JarvisUiState> = mutableState.asStateFlow()

    init {
        try {
            val token = runtimeTokenStore.load()
            runtimeClient.setAccessToken(token)
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(hasAccessToken = token != null),
            )
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    error = error.message ?: "Не удалось восстановить токен доступа.",
                ),
            )
        }
        reload()
        reloadConversation()
    }

    fun saveRuntimeAccessToken(value: String) {
        val token = value.trim()
        if (!Regex("^[A-Za-z0-9_-]{43}$").matches(token)) {
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    error = "Вставьте полный 43-символьный токен из Termux.",
                    message = null,
                ),
            )
            return
        }
        try {
            runtimeTokenStore.save(token)
            runtimeClient.setAccessToken(token)
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    hasAccessToken = true,
                    isConnected = false,
                    snapshot = null,
                    error = null,
                    message = "Токен сохранён в Android Keystore.",
                ),
            )
            connectRuntime()
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    error = error.message ?: "Не удалось сохранить токен доступа.",
                    message = null,
                ),
            )
        }
    }

    fun forgetRuntimeAccessToken() {
        try {
            runtimeTokenStore.clear()
            runtimeClient.setAccessToken(null)
            mutableState.value = mutableState.value.copy(
                runtime = RuntimeBridgeUiState(message = "Токен доступа удалён."),
                workspace = RuntimeWorkspaceUiState(),
            )
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    error = error.message ?: "Не удалось удалить токен доступа.",
                ),
            )
        }
    }

    fun reload() {
        try {
            val data = repository.load()
            val selected = mutableState.value.selectedSessionId
                ?.takeIf { id -> data.sessions.any { it.id == id } }
                ?: data.sessions.firstOrNull { it.status == LocalSessionStatus.OPEN }?.id
                ?: data.sessions.firstOrNull()?.id
            mutableState.value = mutableState.value.copy(
                data = data,
                selectedSessionId = selected,
                notice = if (data.sessions.isEmpty()) {
                    "Создайте сессию, чтобы начать сохранять черновики."
                } else {
                    "Локальные данные восстановлены."
                },
                storageReady = true,
            )
        } catch (error: LocalDraftException) {
            mutableState.value = mutableState.value.copy(
                notice = error.message ?: "Не удалось прочитать локальные данные.",
                storageReady = false,
            )
        }
    }

    fun reloadConversation() {
        try {
            val messages = conversationCoordinator.history()
            mutableState.value = mutableState.value.copy(
                conversation = mutableState.value.conversation.copy(
                    messages = messages,
                    error = null,
                    backendLabel = conversationCoordinator.backendLabel,
                    modelInstalled = modelStore.installedFile() != null,
                    modelStatus = modelStore.statusMessage(),
                ),
            )
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                conversation = mutableState.value.conversation.copy(
                    error = error.message ?: "Не удалось восстановить локальный диалог.",
                ),
            )
        }
    }

    fun importConversationModel(uri: Uri) {
        val before = mutableState.value
        if (before.conversation.isBusy) return
        mutableState.value = before.copy(conversation = before.conversation.copy(isBusy = true, error = null))
        conversationExecutor.execute {
            try {
                conversationResponseEngine.reset()
                modelStore.importModel(uri)
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(conversation = current.conversation.copy(
                        isBusy = false,
                        backendLabel = conversationCoordinator.backendLabel,
                        modelInstalled = modelStore.installedFile() != null,
                        modelStatus = modelStore.statusMessage(),
                        error = null,
                    ))
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(conversation = current.conversation.copy(
                        isBusy = false,
                        modelStatus = modelStore.statusMessage(),
                        error = error.message ?: "Не удалось установить локальную модель.",
                    ))
                }
            }
        }
    }

    fun removeConversationModel() {
        val before = mutableState.value
        if (before.conversation.isBusy) return
        mutableState.value = before.copy(conversation = before.conversation.copy(isBusy = true, error = null))
        conversationExecutor.execute {
            try {
                conversationResponseEngine.reset()
                if (!modelStore.deleteModel()) throw IllegalStateException("Не удалось удалить файл модели.")
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(conversation = current.conversation.copy(
                        isBusy = false,
                        backendLabel = conversationCoordinator.backendLabel,
                        modelInstalled = false,
                        modelStatus = modelStore.statusMessage(),
                        error = null,
                    ))
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(conversation = current.conversation.copy(
                        isBusy = false,
                        modelStatus = modelStore.statusMessage(),
                        error = error.message ?: "Не удалось удалить локальную модель.",
                    ))
                }
            }
        }
    }

    fun sendConversationMessage(value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) return
        val before = mutableState.value
        if (before.conversation.isBusy) return
        if (clean.length > LocalConversationRepository.MAX_MESSAGE_CHARS) {
            mutableState.value = before.copy(
                conversation = before.conversation.copy(
                    error = "Сообщение не должно превышать 4 000 символов.",
                ),
            )
            return
        }
        mutableState.value = before.copy(
            conversation = before.conversation.copy(isBusy = true, error = null),
        )
        conversationExecutor.execute {
            try {
                val messages = conversationCoordinator.send(clean)
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            messages = messages,
                            isBusy = false,
                            error = null,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            isBusy = false,
                            error = error.message ?: "Локальный ответ не сохранён.",
                        ),
                    )
                }
            }
        }
    }

    fun clearConversation() {
        val before = mutableState.value
        if (before.conversation.isBusy || before.conversation.messages.isEmpty()) return
        mutableState.value = before.copy(
            conversation = before.conversation.copy(isBusy = true, error = null),
        )
        conversationExecutor.execute {
            try {
                conversationCoordinator.clear()
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            messages = emptyList(),
                            isBusy = false,
                            error = null,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            isBusy = false,
                            error = error.message ?: "Не удалось очистить историю диалога.",
                        ),
                    )
                }
            }
        }
    }

    fun connectRuntime() {
        runtimeRequest("Подключено к локальной JPVR-консоли JARVIS.") {
            runtimeClient.connect()
        }
        refreshWorkspace()
    }

    fun refreshRuntime() {
        runtimeRequest("Состояние JARVIS обновлено.") {
            runtimeClient.refresh()
        }
        refreshWorkspace()
    }

    fun refreshWorkspace() {
        pendingWorkspaceMerge = null
        pendingWorkspaceRestore = null
        workspaceRequest("Локальная память загружена.") {
            runtimeClient.getWorkspace()
        }
    }

    fun addWorkspaceMemory(content: String, category: String) {
        pendingWorkspaceMerge = null
        pendingWorkspaceRestore = null
        val revision = mutableState.value.workspace.revision
        workspaceRequest("Воспоминание сохранено в локальной памяти.") {
            runtimeClient.createMemory(content, category, revision)
        }
    }

    fun exportWorkspace(passphrase: CharArray, onReady: (ByteArray) -> Unit) {
        pendingWorkspaceMerge = null
        val before = mutableState.value
        if (before.workspace.isBusy) {
            passphrase.fill('\u0000')
            return
        }
        if (!before.runtime.hasAccessToken) {
            passphrase.fill('\u0000')
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    error = "Сначала сохраните токен доступа из Termux.",
                    message = null,
                ),
            )
            return
        }
        mutableState.value = before.copy(
            workspace = before.workspace.copy(
                isBusy = true, error = null, message = null, importPreview = null,
            ),
        )
        runtimeExecutor.execute {
            try {
                val bundle = runtimeClient.exportEncryptedWorkspace(passphrase)
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = null,
                            message = "Зашифрованный файл подготовлен. Выберите место сохранения.",
                        ),
                    )
                    onReady(bundle)
                }
            } catch (error: Exception) {
                passphrase.fill('\u0000')
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = error.message ?: "Экспорт памяти не выполнен.",
                            message = null,
                        ),
                    )
                }
            }
        }
    }


    fun exportWorkspaceBackup(
        passphrase: CharArray,
        onReady: (ByteArray, Long) -> Unit,
    ) {
        pendingWorkspaceMerge = null
        pendingWorkspaceRestore = null
        pendingWorkspaceBackupRevision = null
        val before = mutableState.value
        if (before.workspace.isBusy) {
            passphrase.fill('\u0000')
            return
        }
        if (!before.runtime.hasAccessToken || !before.workspace.isLoaded) {
            passphrase.fill('\u0000')
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    error = "Сначала подключите Termux и обновите локальную память.",
                    message = null,
                ),
            )
            return
        }
        mutableState.value = before.copy(
            workspace = before.workspace.copy(
                isBusy = true, error = null, message = null,
                importPreview = null, restorePreview = null,
            ),
        )
        runtimeExecutor.execute {
            try {
                val backup = runtimeClient.exportEncryptedWorkspaceBackup(passphrase)
                val memoryArray = backup.document.getJSONObject("records").getJSONArray("memories")
                val memories = (0 until memoryArray.length()).map { index ->
                    val item = memoryArray.getJSONObject(index)
                    RuntimeMemory(
                        id = item.getString("id"),
                        content = item.getString("content"),
                        category = item.getString("category"),
                        updatedAt = item.getString("updated_at"),
                    )
                }
                pendingWorkspaceBackupRevision = backup.revision
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            revision = backup.revision,
                            memories = memories,
                            isLoaded = true,
                            isBusy = false,
                            backupRevision = null,
                            restoreBackupAcknowledged = false,
                            restorePreview = null,
                            error = null,
                            message = "Резервная копия подготовлена. Выберите место сохранения .jwb.",
                        ),
                    )
                    onReady(backup.encryptedBytes, backup.revision)
                }
            } catch (error: Exception) {
                passphrase.fill('\u0000')
                pendingWorkspaceBackupRevision = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = error.message ?: "Экспорт резервной копии не выполнен.",
                            message = null,
                            backupRevision = null,
                            restoreBackupAcknowledged = false,
                        ),
                    )
                }
            }
        }
    }

    fun cancelWorkspaceBackupSave() {
        pendingWorkspaceBackupRevision = null
    }

    fun markWorkspaceBackupSaved(revision: Long) {
        val current = mutableState.value
        val pendingRevision = pendingWorkspaceBackupRevision
        pendingWorkspaceBackupRevision = null
        if (pendingRevision != revision || current.workspace.revision != revision) {
            mutableState.value = current.copy(
                workspace = current.workspace.copy(
                    backupRevision = null,
                    restoreBackupAcknowledged = false,
                    error = "Память изменилась во время сохранения backup. Обновите данные и создайте копию заново.",
                    message = null,
                ),
            )
            return
        }
        mutableState.value = current.copy(
            workspace = current.workspace.copy(
                backupRevision = revision,
                restoreBackupAcknowledged = false,
                error = null,
                message = "Backup .jwb сохранён для REV $revision. Подтвердите, что файл находится отдельно.",
            ),
        )
    }

    fun markWorkspaceBackupSaveFailed(message: String) {
        pendingWorkspaceBackupRevision = null
        val current = mutableState.value
        mutableState.value = current.copy(
            workspace = current.workspace.copy(
                backupRevision = null,
                restoreBackupAcknowledged = false,
                error = message,
                message = null,
            ),
        )
    }

    fun setRestoreBackupAcknowledged(checked: Boolean) {
        val current = mutableState.value.workspace
        val allowed = current.backupRevision != null &&
            current.backupRevision == current.revision &&
            current.isLoaded && !current.isBusy
        mutableState.value = mutableState.value.copy(
            workspace = current.copy(restoreBackupAcknowledged = checked && allowed),
        )
    }

    fun previewWorkspaceRestore(uri: Uri, passphrase: CharArray) {
        val before = mutableState.value
        val backupRevision = before.workspace.backupRevision
        if (before.workspace.isBusy) {
            passphrase.fill('\u0000')
            return
        }
        if (!before.runtime.hasAccessToken || !before.workspace.isLoaded ||
            backupRevision == null || backupRevision != before.workspace.revision ||
            !before.workspace.restoreBackupAcknowledged
        ) {
            passphrase.fill('\u0000')
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    error = "Сначала сохраните отдельный backup текущей ревизии и подтвердите это.",
                    message = null,
                    restorePreview = null,
                ),
            )
            return
        }
        val expectedRevision = before.workspace.revision
        pendingWorkspaceMerge = null
        pendingWorkspaceRestore = null
        mutableState.value = before.copy(
            workspace = before.workspace.copy(
                isBusy = true, error = null, message = null, restorePreview = null,
            ),
        )
        runtimeExecutor.execute {
            try {
                val bytes = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 24 * 1024 * 1024) {
                                throw IllegalArgumentException("Файл backup превышает лимит 24 МиБ.")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw IllegalArgumentException("Не удалось открыть выбранный backup.")
                val backup = runtimeClient.decryptWorkspaceBackupBundle(bytes, passphrase)
                val plan = runtimeClient.prepareWorkspaceRestore(backup, expectedRevision)
                pendingWorkspaceRestore = plan
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = null,
                            message = "Backup расшифрован и проверен. Проверьте сводку и подтвердите замену.",
                            restorePreview = RuntimeWorkspaceRestorePreview(
                                plan.backupCreatedAt,
                                plan.conversationCount,
                                plan.memoryCount,
                                plan.taskCount,
                                plan.settingCount,
                            ),
                        ),
                    )
                }
            } catch (error: Exception) {
                passphrase.fill('\u0000')
                pendingWorkspaceRestore = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = error.message ?: "Проверка backup не выполнена.",
                            message = null,
                            restorePreview = null,
                        ),
                    )
                }
            }
        }
    }

    fun confirmWorkspaceRestore() {
        val plan = pendingWorkspaceRestore ?: return
        val before = mutableState.value
        if (before.workspace.isBusy || before.workspace.restorePreview == null ||
            before.workspace.backupRevision != plan.expectedRevision ||
            before.workspace.revision != plan.expectedRevision ||
            !before.workspace.restoreBackupAcknowledged
        ) {
            pendingWorkspaceRestore = null
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    backupRevision = null,
                    restoreBackupAcknowledged = false,
                    restorePreview = null,
                    error = "Ревизия или подтверждение backup изменились. Повторите подготовку восстановления.",
                    message = null,
                ),
            )
            return
        }
        mutableState.value = before.copy(
            workspace = before.workspace.copy(isBusy = true, error = null, message = null),
        )
        runtimeExecutor.execute {
            try {
                val snapshot = runtimeClient.commitWorkspaceRestore(plan)
                val array = snapshot.document.getJSONObject("records").getJSONArray("memories")
                val memories = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    RuntimeMemory(
                        id = item.getString("id"),
                        content = item.getString("content"),
                        category = item.getString("category"),
                        updatedAt = item.getString("updated_at"),
                    )
                }
                pendingWorkspaceRestore = null
                pendingWorkspaceBackupRevision = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = RuntimeWorkspaceUiState(
                            revision = snapshot.revision,
                            memories = memories,
                            isLoaded = true,
                            isBusy = false,
                            message = "Память восстановлена из .jwb. Проверьте содержимое; сохраните новую копию.",
                        ),
                    )
                }
            } catch (error: Exception) {
                pendingWorkspaceRestore = null
                pendingWorkspaceBackupRevision = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            backupRevision = null,
                            restoreBackupAcknowledged = false,
                            restorePreview = null,
                            error = (error.message ?: "Восстановление не выполнено.") +
                                " Обновите память перед следующей попыткой и не повторяйте запрос вслепую.",
                            message = null,
                        ),
                    )
                }
            }
        }
    }

    fun cancelWorkspaceRestore() {
        pendingWorkspaceRestore = null
        val current = mutableState.value.workspace
        mutableState.value = mutableState.value.copy(
            workspace = current.copy(
                restorePreview = null,
                error = null,
                message = "Восстановление отменено. Локальная память не изменена.",
            ),
        )
    }

    fun previewWorkspaceImport(uri: Uri, passphrase: CharArray) {
        pendingWorkspaceRestore = null
        val before = mutableState.value
        if (before.workspace.isBusy) {
            passphrase.fill('\u0000')
            return
        }
        if (!before.runtime.hasAccessToken || !before.workspace.isLoaded) {
            passphrase.fill('\u0000')
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    error = "Сначала подключите Termux и обновите локальную память.",
                    message = null,
                    importPreview = null,
                ),
            )
            return
        }
        val expectedRevision = before.workspace.revision
        pendingWorkspaceMerge = null
        mutableState.value = before.copy(
            workspace = before.workspace.copy(
                isBusy = true, error = null, message = null, importPreview = null,
            ),
        )
        runtimeExecutor.execute {
            try {
                val bytes = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 24 * 1024 * 1024) {
                                throw IllegalArgumentException("Файл превышает лимит 24 МиБ.")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw IllegalArgumentException("Не удалось открыть выбранный файл.")
                val imported = runtimeClient.decryptWorkspaceBundle(bytes, passphrase)
                val plan = runtimeClient.prepareWorkspaceMerge(imported, expectedRevision)
                pendingWorkspaceMerge = plan
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = null,
                            message = "Файл расшифрован. Проверьте сводку и подтвердите импорт.",
                            importPreview = RuntimeWorkspaceImportPreview(
                                plan.addedCount,
                                plan.identicalCount,
                                plan.conflictCount,
                            ),
                        ),
                    )
                }
            } catch (error: Exception) {
                passphrase.fill('\u0000')
                pendingWorkspaceMerge = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = error.message ?: "Предпросмотр импорта не выполнен.",
                            message = null,
                            importPreview = null,
                        ),
                    )
                }
            }
        }
    }

    fun confirmWorkspaceImport() {
        val plan = pendingWorkspaceMerge ?: return
        val before = mutableState.value
        if (before.workspace.isBusy || before.workspace.importPreview == null) return
        mutableState.value = before.copy(
            workspace = before.workspace.copy(isBusy = true, error = null, message = null),
        )
        runtimeExecutor.execute {
            try {
                val snapshot = runtimeClient.commitWorkspaceMerge(plan)
                val array = snapshot.document.getJSONObject("records").getJSONArray("memories")
                val memories = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    RuntimeMemory(
                        id = item.getString("id"),
                        content = item.getString("content"),
                        category = item.getString("category"),
                        updatedAt = item.getString("updated_at"),
                    )
                }
                pendingWorkspaceMerge = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = RuntimeWorkspaceUiState(
                            revision = snapshot.revision,
                            memories = memories,
                            isLoaded = true,
                            isBusy = false,
                            message = "Импорт выполнен. Добавлено ${plan.addedCount}, совпадений ${plan.identicalCount}, конфликтов без перезаписи ${plan.conflictCount}.",
                        ),
                    )
                }
            } catch (error: Exception) {
                pendingWorkspaceMerge = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = error.message ?: "Подтверждение импорта не выполнено.",
                            message = null,
                            importPreview = null,
                        ),
                    )
                }
            }
        }
    }

    fun cancelWorkspaceImport() {
        pendingWorkspaceMerge = null
        val current = mutableState.value
        mutableState.value = current.copy(
            workspace = current.workspace.copy(
                error = null,
                message = "Импорт отменён. Локальная память не изменена.",
                importPreview = null,
            ),
        )
    }

    fun deleteWorkspaceMemory(memoryId: String) {
        pendingWorkspaceMerge = null
        pendingWorkspaceRestore = null
        val revision = mutableState.value.workspace.revision
        workspaceRequest("Воспоминание удалено из локальной памяти.") {
            runtimeClient.deleteMemory(memoryId, revision)
        }
    }

    fun createRuntimeSession(title: String) {
        runtimeRequest("Сессия создана и сохранена через JPVR.") {
            runtimeClient.createSession(title)
        }
    }

    fun createRuntimeTask(sessionId: String, title: String) {
        runtimeRequest("Задача создана в реестре JARVIS и сохранена через JPVR.") {
            runtimeClient.createTask(sessionId, title)
        }
    }

    fun setRuntimeSessionStatus(sessionId: String, status: String, revision: Int) {
        runtimeRequest("Статус сессии JARVIS изменён.") {
            runtimeClient.setSessionStatus(sessionId, status, revision)
        }
    }

    fun cancelRuntimeTask(taskId: String, revision: Int) {
        runtimeRequest("Ожидающая задача JARVIS отменена.") {
            runtimeClient.cancelQueuedTask(taskId, revision)
        }
    }

    fun previewRuntimeReasoning(request: RuntimeReasoningPreviewRequest) {
        val before = mutableState.value.runtime
        if (before.isBusy) return
        if (!before.hasAccessToken) {
            mutableState.value = mutableState.value.copy(
                runtime = before.copy(error = "Сначала сохраните токен доступа из Termux."),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            runtime = before.copy(
                isBusy = true,
                error = null,
                message = null,
                reasoningPreview = null,
            ),
        )
        runtimeExecutor.execute {
            try {
                val preview = runtimeClient.previewReasoning(request)
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        runtime = current.runtime.copy(
                            isBusy = false,
                            isConnected = true,
                            error = null,
                            message = preview.notice,
                            reasoningPreview = preview,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        runtime = current.runtime.copy(
                            isBusy = false,
                            isConnected = false,
                            error = error.message ?: "Символьный preview не выполнен.",
                            message = null,
                        ),
                    )
                }
            }
        }
    }

    fun selectSession(sessionId: String) {
        val exists = mutableState.value.data.sessions.any { it.id == sessionId }
        if (exists) mutableState.value = mutableState.value.copy(
            selectedSessionId = sessionId,
            notice = null,
        )
    }

    fun createSession(title: String) {
        mutate("Сессия сохранена на устройстве.") {
            val updated = repository.createSession(title)
            mutableState.value = mutableState.value.copy(
                data = updated,
                selectedSessionId = updated.sessions.lastOrNull()?.id,
                notice = "Сессия создана.",
                storageReady = true,
            )
            updated
        }
    }

    fun createTask(title: String) {
        val sessionId = mutableState.value.selectedSessionId ?: run {
            mutableState.value = mutableState.value.copy(
                notice = "Сначала создайте или выберите сессию.",
            )
            return
        }
        mutate("Черновик сохранён на устройстве.") {
            val updated = repository.createTask(sessionId, title)
            mutableState.value = mutableState.value.copy(
                data = updated,
                notice = "Черновик сохранён. Он ещё не отправлен в движок.",
                storageReady = true,
            )
            updated
        }
    }

    fun setSessionStatus(sessionId: String, status: LocalSessionStatus, expectedRevision: Int) {
        mutate("Статус сессии обновлён.") {
            val updated = repository.setSessionStatus(sessionId, status, expectedRevision)
            val currentSelection = mutableState.value.selectedSessionId
            val selected = if (
                currentSelection == sessionId && status == LocalSessionStatus.CLOSED
            ) {
                updated.sessions.firstOrNull { it.status == LocalSessionStatus.OPEN }?.id
                    ?: sessionId
            } else currentSelection
            mutableState.value = mutableState.value.copy(
                data = updated,
                selectedSessionId = selected,
                notice = "Статус сессии: ${statusLabel(status)}.",
                storageReady = true,
            )
            updated
        }
    }

    fun deleteTask(taskId: String) {
        mutate("Локальный черновик удалён.") {
            val updated = repository.deleteTask(taskId)
            mutableState.value = mutableState.value.copy(
                data = updated,
                notice = "Черновик удалён с устройства.",
                storageReady = true,
            )
            updated
        }
    }

    fun clearNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    private fun workspaceRequest(
        successMessage: String,
        operation: () -> RuntimeWorkspaceSnapshot,
    ) {
        val before = mutableState.value
        if (before.workspace.isBusy) return
        if (!before.runtime.hasAccessToken) {
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    error = "Сначала сохраните токен доступа из Termux.",
                    message = null,
                ),
            )
            return
        }
        mutableState.value = before.copy(
            workspace = before.workspace.copy(
                isBusy = true, error = null, message = null, importPreview = null,
            ),
        )
        runtimeExecutor.execute {
            try {
                val snapshot = operation()
                val records = snapshot.document.getJSONObject("records")
                val array = records.getJSONArray("memories")
                val memories = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    RuntimeMemory(
                        id = item.getString("id"),
                        content = item.getString("content"),
                        category = item.getString("category"),
                        updatedAt = item.getString("updated_at"),
                    )
                }
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = RuntimeWorkspaceUiState(
                            revision = snapshot.revision,
                            memories = memories,
                            isLoaded = true,
                            isBusy = false,
                            message = successMessage,
                            backupRevision = current.workspace.backupRevision
                                ?.takeIf { it == snapshot.revision },
                            restoreBackupAcknowledged = current.workspace.restoreBackupAcknowledged &&
                                current.workspace.backupRevision == snapshot.revision,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        workspace = current.workspace.copy(
                            isBusy = false,
                            error = error.message ?: "Операция с общей памятью не выполнена.",
                            message = null,
                        ),
                    )
                }
            }
        }
    }

    private fun runtimeRequest(
        successMessage: String,
        operation: () -> RuntimeBridgeSnapshot,
    ) {
        val before = mutableState.value.runtime
        if (before.isBusy) return
        if (!before.hasAccessToken) {
            mutableState.value = mutableState.value.copy(
                runtime = before.copy(error = "Сначала сохраните токен доступа из Termux."),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            runtime = before.copy(isBusy = true, error = null, message = null),
        )
        runtimeExecutor.execute {
            try {
                val snapshot = operation()
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        runtime = current.runtime.copy(
                            isBusy = false,
                            isConnected = true,
                            snapshot = snapshot,
                            error = null,
                            message = successMessage,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        runtime = current.runtime.copy(
                            isBusy = false,
                            isConnected = false,
                            error = error.message ?: "Локальная операция JARVIS не выполнена.",
                            message = null,
                        ),
                    )
                }
            }
        }
    }

    private inline fun mutate(
        successMessage: String,
        operation: () -> LocalDraftState,
    ) {
        try {
            val updated = operation()
            val current = mutableState.value
            val selected = current.selectedSessionId
                ?.takeIf { id -> updated.sessions.any { it.id == id } }
                ?: updated.sessions.firstOrNull { it.status == LocalSessionStatus.OPEN }?.id
                ?: updated.sessions.firstOrNull()?.id
            mutableState.value = current.copy(
                data = updated,
                selectedSessionId = selected,
                notice = if (current.notice == null) successMessage else current.notice,
                storageReady = true,
            )
        } catch (error: LocalDraftException) {
            mutableState.value = mutableState.value.copy(
                notice = error.message ?: "Локальная операция не выполнена.",
            )
        }
    }

    private fun statusLabel(status: LocalSessionStatus): String = when (status) {
        LocalSessionStatus.OPEN -> "открыта"
        LocalSessionStatus.PAUSED -> "на паузе"
        LocalSessionStatus.CLOSED -> "закрыта"
    }

    override fun onCleared() {
        conversationExecutor.execute { conversationResponseEngine.close() }
        conversationExecutor.shutdown()
        runtimeExecutor.shutdownNow()
        super.onCleared()
    }
}
