package org.jarvis.ans100.alpha

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jarvis.ans100.alpha.data.LocalConversationCoordinator
import org.jarvis.ans100.alpha.data.LocalConversationSignalRuntime
import org.jarvis.ans100.alpha.data.SharedPreferencesConversationSignalStorage
import org.jarvis.ans100.alpha.data.DialogFeaturePrediction
import org.jarvis.ans100.alpha.data.DialogueNeuralAnalysis
import org.jarvis.ans100.alpha.data.LocalDialogFeatureClassifier
import org.jarvis.ans100.alpha.data.SharedPreferencesDialogFeatureStorage
import org.jarvis.ans100.alpha.data.LocalConversationMessage
import org.jarvis.ans100.alpha.data.LocalConversationRepository
import org.jarvis.ans100.alpha.data.LocalWorkspaceMemory
import org.jarvis.ans100.alpha.data.LocalWorkspaceMemoryCache
import org.jarvis.ans100.alpha.data.LiteRtLmModelStore
import org.jarvis.ans100.alpha.data.LiteRtLmDownloadCancelledException
import org.jarvis.ans100.alpha.data.LiteRtLmResponseEngine
import org.jarvis.ans100.alpha.data.LocalFallbackResponseEngine
import org.jarvis.ans100.alpha.data.SharedPreferencesConversationStorage
import org.jarvis.ans100.alpha.data.SharedPreferencesLocalWorkspaceMemoryStorage
import org.jarvis.ans100.alpha.data.LocalDraftException
import org.jarvis.ans100.alpha.data.LocalDraftRepository
import org.jarvis.ans100.alpha.data.LocalDraftState
import org.jarvis.ans100.alpha.data.LocalRuntimeBridgeClient
import org.jarvis.ans100.alpha.data.LocalSessionStatus
import org.jarvis.ans100.alpha.data.RuntimeBridgeSnapshot
import org.jarvis.ans100.alpha.data.RuntimeAccessTokenStore
import org.jarvis.ans100.alpha.data.RuntimeReasoningPreviewRequest
import org.jarvis.ans100.alpha.data.RuntimeReasoningPreviewResult
import org.jarvis.ans100.alpha.data.RuntimeTaskExecutionResult
import org.jarvis.ans100.alpha.data.PeerMemorySyncClient
import org.jarvis.ans100.alpha.data.RuntimeWorkspaceSnapshot
import org.jarvis.ans100.alpha.data.RuntimeWorkspaceMergePlan
import org.jarvis.ans100.alpha.data.SharedPreferencesDraftStorage
import org.jarvis.ans100.alpha.data.shouldUseTermuxSignal
import org.jarvis.ans100.alpha.data.AndroidWebSearchService
import org.jarvis.ans100.alpha.data.AndroidSparseNeuronNetwork
import org.jarvis.ans100.alpha.data.AndroidNeuronNetworkStats
import org.jarvis.ans100.alpha.data.AndroidNeuronEvent
import java.util.UUID
import org.jarvis.ans100.alpha.data.AndroidWebSearchIntent
import org.jarvis.ans100.alpha.data.formatAndroidWebSearchReply
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

data class LocalConversationUiState(
    val messages: List<LocalConversationMessage> = emptyList(),
    val isBusy: Boolean = false,
    val error: String? = null,
    val backendLabel: String = "Локальный базовый ответчик · без LLM",
    val modelInstalled: Boolean = false,
    val gpuEnabled: Boolean = false,
    val modelStatus: String = "Модель не установлена.",
    val modelDownloadProgress: Float? = null,
    val modelDownloadStatus: String? = null,
    val dialogFeaturePrediction: DialogFeaturePrediction? = null,
    val dialogFeatureError: String? = null,
    val lastRewardedAssistantMessageId: String? = null,
    val nativeNetworkStats: AndroidNeuronNetworkStats? = null,
    val nativeNetworkBusy: Boolean = false,
    val nativeNetworkError: String? = null,
    val nativeNetworkMessage: String? = null,
    val nativeDialogueContextId: String? = null,
    val nativeDialogueRewardNeuronIds: List<Int> = emptyList(),
    val modelInitializationMs: Long = 0L,
    val responseGenerationMs: Long = 0L,
    val lastTurnDurationMs: Long = 0L,
    val responseTokenEstimate: Int = 0,
    val timeToFirstTextMs: Long = 0L,
    val contextTokenCount: Int = 0,
    val estimatedInputTokens: Int = 0,
    val omittedHistoryMessages: Int = 0,
    val includedMemoryItems: Int = 0,
    val omittedMemoryItems: Int = 0,
    val streamingResponse: String = "",
    val ans100ProcessedEvents: Int = 0,
    val ans100SpikeCount: Int = 0,
    val ans100ActiveNeuronCount: Int = 0,
    val ans100AttentionGateActive: Boolean = false,
    val understandingIntent: String? = null,
    val reasoningPlanSteps: Int = 0,
    val reasoningNeedsClarification: Boolean = false,
)

private data class NativeDialogueFeedbackContext(
    val contextId: String,
    val rewardNeuronIds: List<Int>,
    val analysis: DialogueNeuralAnalysis,
)

data class RuntimeBridgeUiState(
    val isBusy: Boolean = false,
    val isConnected: Boolean = false,
    val snapshot: RuntimeBridgeSnapshot? = null,
    val error: String? = null,
    val message: String? = null,
    val reasoningPreview: RuntimeReasoningPreviewResult? = null,
    val taskExecutionResult: RuntimeTaskExecutionResult? = null,
    val hasAccessToken: Boolean = false,
    val extensionInstalled: Boolean = false,
    val signalEndpointAvailable: Boolean? = null,
)

data class RuntimeMemory(
    val id: String,
    val content: String,
    val category: String,
    val updatedAt: String,
)

data class LocalMemoryUiState(
    val records: List<LocalWorkspaceMemory> = emptyList(),
    val isLoaded: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val lastAddedId: String? = null,
)

data class PeerMemorySyncUiState(
    val isBusy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val remoteRevision: Long? = null,
    val addedCount: Int = 0,
    val updatedCount: Int = 0,
    val conflictCount: Int = 0,
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
    val localMemory: LocalMemoryUiState = LocalMemoryUiState(),
    val peerMemorySync: PeerMemorySyncUiState = PeerMemorySyncUiState(),
    val conversation: LocalConversationUiState = LocalConversationUiState(),
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = LocalDraftRepository(
        SharedPreferencesDraftStorage(application.applicationContext),
    )
    private val modelStore = LiteRtLmModelStore(application.applicationContext)
    private val conversationResponseEngine = LiteRtLmResponseEngine(
        application.applicationContext, modelStore,
    )
    private val conversationExecutor = Executors.newSingleThreadExecutor()
    private val nativeNetworkExecutor = Executors.newSingleThreadExecutor()
    private val nativeNeuronNetwork = AndroidSparseNeuronNetwork(application.applicationContext)
    @Volatile private var nativeDialogueGeneration: Long = 0L
    private val androidWebSearch = AndroidWebSearchService()
    // The app-local ANS-100 pass is always available; Termux may override it when connected.
    private val localSignalRuntime = LocalConversationSignalRuntime(
        SharedPreferencesConversationSignalStorage(application.applicationContext),
    )
    private val dialogFeatureClassifier = LocalDialogFeatureClassifier(
        SharedPreferencesDialogFeatureStorage(application.applicationContext),
    )
    private val workspaceMemoryCache = LocalWorkspaceMemoryCache(
        SharedPreferencesLocalWorkspaceMemoryStorage(application.applicationContext),
    )
    private val conversationCoordinator = LocalConversationCoordinator(
        LocalConversationRepository(
            SharedPreferencesConversationStorage(application.applicationContext),
        ),
        conversationResponseEngine,
        workspaceMemoryCache,
    )
    private val runtimeClient = LocalRuntimeBridgeClient()
    private val runtimeTokenStore = RuntimeAccessTokenStore(application.applicationContext)
    private val runtimeExecutor = Executors.newSingleThreadExecutor()
    private val peerMemorySyncClient = PeerMemorySyncClient()
    private val peerSyncExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var pendingWorkspaceMerge: RuntimeWorkspaceMergePlan? = null
    @Volatile private var pendingWorkspaceRestore: org.jarvis.ans100.alpha.data.RuntimeWorkspaceRestorePlan? = null
    @Volatile private var pendingWorkspaceBackupRevision: Long? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(JarvisUiState())
    val state: StateFlow<JarvisUiState> = mutableState.asStateFlow()

    init {
        val localMemorySnapshot = workspaceMemoryCache.snapshot()
        mutableState.value = mutableState.value.copy(
            localMemory = LocalMemoryUiState(
                records = localMemorySnapshot.records,
                isLoaded = localMemorySnapshot.loaded,
                error = localMemorySnapshot.error,
            ),
            conversation = mutableState.value.conversation.copy(
                gpuEnabled = conversationResponseEngine.gpuEnabled,
                backendLabel = conversationCoordinator.backendLabel,
            ),
        )
        val extensionInstalled = isTermuxInstalled()
        try {
            val token = runtimeTokenStore.load()
            runtimeClient.setAccessToken(token)
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    hasAccessToken = token != null,
                    extensionInstalled = extensionInstalled,
                    message = when {
                        !extensionInstalled -> "Встроенное ядро готово. Termux не установлен; расширение не требуется для обычной работы."
                        token != null -> "Termux обнаружен. Проверяется необязательное расширение JARVIS."
                        else -> "Termux обнаружен. Подключение необязательно; встроенное ядро уже доступно."
                    },
                ),
            )
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    extensionInstalled = extensionInstalled,
                    error = error.message ?: "Не удалось восстановить токен доступа.",
                    message = if (extensionInstalled) {
                        "Termux обнаружен, но сохранённый токен недоступен."
                    } else {
                        "Встроенное ядро готово; расширение Termux не установлено."
                    },
                ),
            )
        }
        reload()
        reloadConversation()
        refreshNativeNeuronNetwork()
        if (extensionInstalled && mutableState.value.runtime.hasAccessToken) {
            // Only attempt optional bridge discovery when the companion app is installed.
            connectRuntime()
        }
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun checkTermuxExtension() {
        val installed = isTermuxInstalled()
        val before = mutableState.value
        val current = before.runtime
        mutableState.value = before.copy(
            runtime = current.copy(
                extensionInstalled = installed,
                isConnected = installed && current.isConnected,
                snapshot = if (installed) current.snapshot else null,
                signalEndpointAvailable = if (installed) current.signalEndpointAvailable else null,
                error = null,
                message = when {
                    !installed -> "Termux не найден. JARVIS продолжает работать во встроенном режиме."
                    current.isConnected -> "Расширение Termux подключено."
                    current.hasAccessToken -> "Termux установлен. Запустите локальную консоль и подключите расширение."
                    else -> "Termux установлен. Для расширенных функций запустите локальную консоль и сохраните её токен."
                },
            ),
        )
    }

    fun openTermux() {
        if (!isTermuxInstalled()) {
            checkTermuxExtension()
            return
        }
        try {
            val intent = appContext.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME)
                ?: throw IllegalStateException("Termux установлен, но его экран запуска недоступен.")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    message = "Termux открыт. Запустите локальную консоль JARVIS, затем вернитесь в приложение.",
                    error = null,
                ),
            )
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                runtime = mutableState.value.runtime.copy(
                    error = error.message ?: "Не удалось открыть Termux.",
                ),
            )
        }
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
                    extensionInstalled = isTermuxInstalled(),
                    signalEndpointAvailable = null,
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
            val extensionInstalled = mutableState.value.runtime.extensionInstalled
            mutableState.value = mutableState.value.copy(
                runtime = RuntimeBridgeUiState(
                    extensionInstalled = extensionInstalled,
                    message = "Токен доступа удалён. Встроенное ядро продолжает работать.",
                ),
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

    fun setGpuEnabled(enabled: Boolean) {
        val before = mutableState.value
        if (before.conversation.isBusy || before.conversation.gpuEnabled == enabled) return
        // LiteRT-LM Engine creation, use and close stay on the conversation executor.
        mutableState.value = before.copy(
            conversation = before.conversation.copy(
                isBusy = true,
                gpuEnabled = enabled,
                error = null,
            ),
        )
        conversationExecutor.execute {
            try {
                conversationResponseEngine.setGpuEnabled(enabled)
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            isBusy = false,
                            gpuEnabled = conversationResponseEngine.gpuEnabled,
                            backendLabel = conversationResponseEngine.backendLabel,
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
                            gpuEnabled = conversationResponseEngine.gpuEnabled,
                            backendLabel = conversationResponseEngine.backendLabel,
                            error = error.message ?: "Не удалось изменить режим GPU.",
                        ),
                    )
                }
            }
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

    fun downloadConversationModel() {
        val before = mutableState.value
        if (before.conversation.isBusy || modelStore.installedFile() != null) return
        modelStore.prepareDownload()
        mutableState.value = before.copy(conversation = before.conversation.copy(
            isBusy = true,
            error = null,
            modelDownloadProgress = 0f,
            modelDownloadStatus = "Подготовка безопасной загрузки модели…",
        ))
        conversationExecutor.execute {
            try {
                conversationResponseEngine.reset()
                modelStore.downloadModel { bytes, total ->
                    val progress = (bytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                    val status = "Загрузка модели: ${bytes / LiteRtLmModelStore.MIB} / ${total / LiteRtLmModelStore.MIB} МиБ"
                    mainHandler.post {
                        val current = mutableState.value
                        mutableState.value = current.copy(conversation = current.conversation.copy(
                            modelDownloadProgress = progress,
                            modelDownloadStatus = status,
                        ))
                    }
                }
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(conversation = current.conversation.copy(
                        isBusy = false,
                        backendLabel = conversationResponseEngine.backendLabel,
                        gpuEnabled = conversationResponseEngine.gpuEnabled,
                        modelInstalled = modelStore.installedFile() != null,
                        modelStatus = modelStore.statusMessage(),
                        modelDownloadProgress = null,
                        modelDownloadStatus = "Модель загружена и проверена. Теперь JARVIS может работать офлайн.",
                        error = null,
                    ))
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    val cancelled = error is LiteRtLmDownloadCancelledException
                    mutableState.value = current.copy(conversation = current.conversation.copy(
                        isBusy = false,
                        modelInstalled = modelStore.installedFile() != null,
                        modelStatus = modelStore.statusMessage(),
                        modelDownloadProgress = null,
                        modelDownloadStatus = if (cancelled) {
                            "Загрузка остановлена. Нажмите «Продолжить загрузку», чтобы продолжить с сохранённого места."
                        } else {
                            "Загрузка не завершена. Можно повторить или продолжить позже."
                        },
                        error = error.message ?: "Не удалось скачать локальную модель.",
                    ))
                }
            }
        }
    }

    fun cancelConversationModelDownload() {
        modelStore.cancelDownload()
        val current = mutableState.value
        if (current.conversation.isBusy) {
            mutableState.value = current.copy(conversation = current.conversation.copy(
                modelDownloadStatus = "Останавливаем загрузку…",
            ))
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
        val searchIntent = AndroidWebSearchIntent.extract(clean)
        val dialogueGeneration = nativeDialogueGeneration + 1L
        nativeDialogueGeneration = dialogueGeneration
        mutableState.value = before.copy(
            conversation = before.conversation.copy(
                isBusy = true,
                error = null,
                streamingResponse = "",
                nativeDialogueContextId = null,
                nativeDialogueRewardNeuronIds = emptyList(),
            ),
        )
        if (searchIntent != null) {
            conversationExecutor.execute {
                try {
                    val results = androidWebSearch.search(searchIntent.query)
                    val response = formatAndroidWebSearchReply(searchIntent.query, results)
                    val messages = conversationCoordinator.sendWithExternalResult(clean, response)
                    mainHandler.post {
                        val current = mutableState.value
                        mutableState.value = current.copy(
                            conversation = current.conversation.copy(
                                messages = messages,
                                isBusy = false,
                                error = null,
                                dialogFeaturePrediction = null,
                                dialogFeatureError = null,
                                lastRewardedAssistantMessageId = null,
                                nativeDialogueContextId = null,
                                nativeDialogueRewardNeuronIds = emptyList(),
                            ),
                        )
                    }
                } catch (error: Exception) {
                    mainHandler.post {
                        val current = mutableState.value
                        mutableState.value = current.copy(
                            conversation = current.conversation.copy(
                                isBusy = false,
                                error = error.message ?: "Не удалось выполнить интернет-поиск.",
                            ),
                        )
                    }
                }
            }
            return
        }
        conversationExecutor.execute {
            val turnStartedAtNs = System.nanoTime()
            conversationResponseEngine.resetTurnMetrics()
            try {
                // The sparse intent classifier is an optional side channel; it never blocks chat.
                val dialogPredictionResult = try {
                    dialogFeatureClassifier.analyze(clean) to null
                } catch (error: Exception) {
                    null to (error.message ?: "Диалоговые признаки временно недоступны.")
                }
                val activeFeatureIds = dialogPredictionResult.first?.activeFeatureIds.orEmpty()
                val nativeFeedbackContext = if (!clean.startsWith("/") && activeFeatureIds.isNotEmpty()) {
                    enqueueNativeDialogueFeatures(
                        activeFeatureIds, dialogueGeneration, dialogPredictionResult.first,
                    )
                } else null
                val runtimeState = mutableState.value.runtime
                val signalContext = if (!clean.startsWith("/")) {
                    val useTermuxSignal = shouldUseTermuxSignal(
                        installed = runtimeState.extensionInstalled,
                        connected = runtimeState.isConnected,
                        hasToken = runtimeState.hasAccessToken,
                        endpointAvailable = runtimeState.signalEndpointAvailable,
                    )
                    val signal = if (useTermuxSignal) {
                        try {
                            val remoteSignal = runtimeClient.analyzeConversationSignal(clean)
                            mainHandler.post {
                                val current = mutableState.value
                                mutableState.value = current.copy(
                                    runtime = current.runtime.copy(signalEndpointAvailable = true),
                                )
                            }
                            remoteSignal
                        } catch (_: Exception) {
                            mainHandler.post {
                                val current = mutableState.value
                                mutableState.value = current.copy(
                                    runtime = current.runtime.copy(
                                        signalEndpointAvailable = false,
                                        message = "Сигнальное расширение Termux недоступно; используется встроенный ANS-100.",
                                    ),
                                )
                            }
                            runCatching { localSignalRuntime.analyze(clean) }.getOrNull()
                        }
                    } else {
                        runCatching { localSignalRuntime.analyze(clean) }.getOrNull()
                    }
                    if (signal == null) {
                        mainHandler.post {
                            val current = mutableState.value
                            mutableState.value = current.copy(
                                runtime = current.runtime.copy(
                                    message = "ANS-100 временно недоступен; обычный диалог продолжен без сигнального контекста.",
                                ),
                            )
                        }
                    }
                    signal
                } else {
                    null
                }
                // Neural preprocessing is optional: the normal turn is committed even
                // when both the Termux endpoint and the in-process checkpoint are unavailable.
                conversationResponseEngine.onPartialResponse = { partial ->
                    mainHandler.post {
                        if (nativeDialogueGeneration == dialogueGeneration) {
                            val current = mutableState.value
                            mutableState.value = current.copy(
                                conversation = current.conversation.copy(streamingResponse = partial),
                            )
                        }
                    }
                }
                val messages = try {
                    conversationCoordinator.send(clean, signalContext, nativeFeedbackContext?.analysis)
                } finally {
                    conversationResponseEngine.onPartialResponse = null
                }
                val elapsedMs = ((System.nanoTime() - turnStartedAtNs) / 1_000_000L).coerceAtLeast(0L)
                val modelMetrics = conversationResponseEngine.performanceSnapshot()
                val coreStatus = conversationCoordinator.status()
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            messages = messages,
                            isBusy = false,
                            streamingResponse = "",
                            error = null,
                            backendLabel = conversationResponseEngine.backendLabel,
                            gpuEnabled = conversationResponseEngine.gpuEnabled,
                            dialogFeaturePrediction = dialogPredictionResult.first,
                            dialogFeatureError = dialogPredictionResult.second,
                            lastRewardedAssistantMessageId = null,
                            nativeDialogueContextId = nativeFeedbackContext?.contextId,
                            nativeDialogueRewardNeuronIds = nativeFeedbackContext?.rewardNeuronIds.orEmpty(),
                            modelInitializationMs = modelMetrics.initializationMs,
                            responseGenerationMs = modelMetrics.generationMs,
                            lastTurnDurationMs = elapsedMs,
                            responseTokenEstimate = modelMetrics.outputTokenEstimate,
                            timeToFirstTextMs = modelMetrics.timeToFirstTextMs,
                            contextTokenCount = modelMetrics.contextTokenCount,
                            estimatedInputTokens = modelMetrics.estimatedInputTokens,
                            omittedHistoryMessages = modelMetrics.omittedHistoryMessages,
                            includedMemoryItems = modelMetrics.includedMemoryItems,
                            omittedMemoryItems = modelMetrics.omittedMemoryItems,
                            ans100ProcessedEvents = signalContext?.processedEvents ?: 0,
                            ans100SpikeCount = signalContext?.spikeCount ?: 0,
                            ans100ActiveNeuronCount = signalContext?.activeNeuronCount ?: 0,
                            ans100AttentionGateActive = signalContext?.attentionGateActive == true,
                            understandingIntent = coreStatus.lastUnderstandingIntent,
                            reasoningPlanSteps = coreStatus.lastReasoningPlanStepCount,
                            reasoningNeedsClarification = coreStatus.lastReasoningPlanRequiresClarification,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            isBusy = false,
                            streamingResponse = "",
                            backendLabel = conversationResponseEngine.backendLabel,
                            gpuEnabled = conversationResponseEngine.gpuEnabled,
                            error = error.message ?: "Локальный ответ не сохранён.",
                        ),
                    )
                }
            }
        }
    }

    fun trainDialogFeatureClassifier(expectedLabel: String) {
        if (expectedLabel !in LocalDialogFeatureClassifier.LABELS) return
        val before = mutableState.value
        if (before.conversation.isBusy) return
        val latestUserMessage = before.conversation.messages.lastOrNull {
            it.role == org.jarvis.ans100.alpha.data.ConversationRole.USER
        } ?: return
        mutableState.value = before.copy(
            conversation = before.conversation.copy(isBusy = true, dialogFeatureError = null),
        )
        conversationExecutor.execute {
            try {
                val prediction = dialogFeatureClassifier.learn(latestUserMessage.text, expectedLabel)
                val nativeContextId = before.conversation.nativeDialogueContextId
                val nativeRewardNeurons = before.conversation.nativeDialogueRewardNeuronIds
                if (nativeContextId != null && nativeRewardNeurons.isNotEmpty()) {
                    enqueueNativeDialogueLabelReward(nativeContextId, nativeRewardNeurons, expectedLabel)
                }
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            isBusy = false,
                            dialogFeaturePrediction = prediction,
                            dialogFeatureError = null,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            isBusy = false,
                            dialogFeatureError = error.message ?: "Не удалось сохранить обучение.",
                        ),
                    )
                }
            }
        }
    }


    fun applyDialogRewardFeedback(reward: Double) {
        if (reward != -1.0 && reward != 1.0) return
        val before = mutableState.value
        if (before.conversation.isBusy) return
        val assistantMessage = before.conversation.messages.lastOrNull {
            it.role == org.jarvis.ans100.alpha.data.ConversationRole.ASSISTANT
        } ?: return
        if (before.conversation.lastRewardedAssistantMessageId == assistantMessage.id) return
        val assistantIndex = before.conversation.messages.indexOfLast { it.id == assistantMessage.id }
        val userMessage = before.conversation.messages.take(assistantIndex).lastOrNull {
            it.role == org.jarvis.ans100.alpha.data.ConversationRole.USER
        } ?: return
        mutableState.value = before.copy(
            conversation = before.conversation.copy(isBusy = true, dialogFeatureError = null),
        )
        conversationExecutor.execute {
            try {
                val prediction = dialogFeatureClassifier.applyReward(userMessage.text, reward)
                val nativeContextId = before.conversation.nativeDialogueContextId
                val nativeRewardNeurons = before.conversation.nativeDialogueRewardNeuronIds
                val predictedLabel = before.conversation.dialogFeaturePrediction?.predictedLabel
                if (nativeContextId != null && nativeRewardNeurons.isNotEmpty() && predictedLabel != null) {
                    enqueueNativeDialogueReward(
                        nativeContextId,
                        nativeRewardNeurons,
                        mapOf(dialogueOutputNeuronId(predictedLabel) to reward),
                    )
                }
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            isBusy = false,
                            dialogFeaturePrediction = prediction,
                            dialogFeatureError = null,
                            lastRewardedAssistantMessageId = assistantMessage.id,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            isBusy = false,
                            dialogFeatureError = error.message ?: "Не удалось применить reward-сигнал.",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Feeds a bounded text-feature batch through the 9,996-input/4-output
     * SQLite LIF-DT graph. Output bias events arrive with the feature batch;
     * delayed feature→output events determine which output neurons spike.
     * Eligibility is stored only for pairs that actually participated in a
     * postsynaptic spike. Ordinary chat never changes synaptic weights.
     */
    private fun enqueueNativeDialogueFeatures(
        featureIds: List<Int>,
        generation: Long,
        classifierPrediction: DialogFeaturePrediction?,
    ): NativeDialogueFeedbackContext? {
        val boundedIds = featureIds.asSequence()
            .map { it % AndroidSparseNeuronNetwork.DIALOGUE_INPUT_NEURONS }
            .distinct()
            .take(MAX_DIALOGUE_FEATURE_EVENTS)
            .toList()
        if (boundedIds.isEmpty()) return null

        val contextId = "dialog-${UUID.randomUUID()}"
        // Wait for the bounded native pass before invoking the language model. The
        // network has its own single-thread executor, so SQLite/JNVM work never runs
        // on the conversation or main thread.
        return try {
            nativeNetworkExecutor.submit<NativeDialogueFeedbackContext?> {
                try {
                    mainHandler.post {
                        val current = mutableState.value
                        mutableState.value = current.copy(
                            conversation = current.conversation.copy(
                                nativeNetworkMessage = "Проверка и подготовка трёхслойной сети ANS-100 из 10 000 нейронов…",
                            ),
                        )
                    }
                    nativeNeuronNetwork.initializeSparseDialogueNetwork(
                        neuronCount = AndroidSparseNeuronNetwork.MAX_NEURONS,
                        weight = DIALOGUE_INITIAL_SYNAPSE_WEIGHT,
                        inputToHiddenWeight = AndroidSparseNeuronNetwork.DEFAULT_INPUT_TO_HIDDEN_WEIGHT,
                    )
                    val prior = nativeNeuronNetwork.stats()
                    if (prior.neuronCount != AndroidSparseNeuronNetwork.MAX_NEURONS) {
                        throw IllegalStateException(
                            "Сохранённая диалоговая сеть имеет неожиданное число нейронов: ${prior.neuronCount}.",
                        )
                    }
                    if (prior.currentTimeNs > Long.MAX_VALUE - DIALOGUE_INPUT_STEP_NS -
                        2L * AndroidSparseNeuronNetwork.DEFAULT_CONNECTION_DELAY_NS) {
                        throw IllegalStateException("Модельные часы JNVM достигли предельного значения.")
                    }
                    val nextTime = prior.currentTimeNs + DIALOGUE_INPUT_STEP_NS
                    val featureEvents = boundedIds.mapIndexed { index, neuronId ->
                        AndroidNeuronEvent(
                            eventId = "chat-$contextId-feature-$index",
                            timeNs = nextTime,
                            targetNeuronId = neuronId,
                            strength = 1.0,
                        )
                    }
                    // Only text-feature neurons receive external input. Output units
                    // must activate through learned synaptic propagation, never a direct bias event.
                    nativeNeuronNetwork.scheduleInputs(featureEvents)
                    val result = nativeNeuronNetwork.run(
                        maxEvents = MAX_DIALOGUE_EVENTS_PER_TURN,
                        maxTimestamps = MAX_DIALOGUE_TIMESTAMPS_PER_TURN,
                    )
                    val activeHiddenSpikes = result.emittedSpikes.asSequence()
                        .map { it.first }
                        .filter {
                            it in AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START until
                                AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START
                        }
                        .distinct()
                        .toList()
                    // Keep feedback/readout work bounded even if a large feature
                    // batch activates a substantial part of the hidden population.
                    val hiddenSpikes = activeHiddenSpikes
                        .take(AndroidSparseNeuronNetwork.MAX_DIALOGUE_FEATURES_PER_TURN)
                    val outputSpikes = result.emittedSpikes.asSequence()
                        .map { it.first }
                        .filter {
                            it in AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START until
                                AndroidSparseNeuronNetwork.MAX_NEURONS
                        }
                        .distinct()
                        .toList()
                    val rawReadout = if (hiddenSpikes.isEmpty()) {
                        emptyMap()
                    } else {
                        nativeNeuronNetwork.dialogueOutputScores(hiddenSpikes)
                    }
                    val networkOutputScores = if (rawReadout.isEmpty()) {
                        emptyMap()
                    } else {
                        mapOf(
                            "greeting" to (rawReadout[AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START] ?: 0.0),
                            "question" to (rawReadout[AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START + 1] ?: 0.0),
                            "thanks" to (rawReadout[AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START + 2] ?: 0.0),
                            "farewell" to (rawReadout[AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START + 3] ?: 0.0),
                        )
                    }
                    val readoutSummary = if (networkOutputScores.isEmpty()) {
                        "нет активного readout"
                    } else {
                        networkOutputScores.toSortedMap().entries.joinToString(", ") { (label, score) ->
                            "$label=${String.format(java.util.Locale.ROOT, "%.2f", score)}"
                        }
                    }
                    val hiddenSpikeTimeNs = nextTime + AndroidSparseNeuronNetwork.DEFAULT_CONNECTION_DELAY_NS
                    val eligibilityCount = if (hiddenSpikes.isEmpty() || outputSpikes.isEmpty()) {
                        0
                    } else {
                        nativeNeuronNetwork.recordDialogueEligibility(
                            contextId = contextId,
                            sourceNeuronIds = hiddenSpikes,
                            outputNeuronIds = outputSpikes,
                            preTimeNs = hiddenSpikeTimeNs,
                            postTimeNs = result.currentTimeNs,
                        )
                    }
                    if (generation != nativeDialogueGeneration) return@submit null
                    val stats = nativeNeuronNetwork.stats()
                    val predictionLabel = classifierPrediction?.predictedLabel
                    val analysis = DialogueNeuralAnalysis(
                        networkNeuronCount = stats.neuronCount,
                        activeFeatureCount = boundedIds.size,
                        activeHiddenNeuronCount = activeHiddenSpikes.size,
                        processedEvents = result.processedEvents,
                        processedNeuronBatches = result.processedNeuronBatches,
                        outputSpikeCount = outputSpikes.size,
                        pendingEvents = result.pendingEvents,
                        classifierLabel = predictionLabel,
                        classifierConfidenceMargin = if (predictionLabel != null) {
                            classifierPrediction?.confidenceMargin
                        } else null,
                        classifierTrainedExamples = classifierPrediction?.trainedExamples ?: 0,
                        statePersisted = true,
                        networkOutputScores = networkOutputScores,
                    )
                    mainHandler.post {
                        val current = mutableState.value
                        mutableState.value = current.copy(
                            conversation = current.conversation.copy(
                                nativeNetworkStats = stats,
                                nativeNetworkError = null,
                                nativeNetworkMessage =
                                    "ANS-100: входных признаков ${featureEvents.size}, активных скрытых нейронов ${activeHiddenSpikes.size}, " +
                                        "выходных импульсов ${outputSpikes.size}, обработано событий ${result.processedEvents}, " +
                                        "eligibility-связей ${eligibilityCount}; readout-веса: $readoutSummary. " +
                                        "Это слабые подсказки для языковой модели, не вероятности и не самостоятельное мышление.",
                            ),
                        )
                    }
                    NativeDialogueFeedbackContext(contextId, hiddenSpikes, analysis)
                } catch (error: Exception) {
                    mainHandler.post {
                        val current = mutableState.value
                        mutableState.value = current.copy(
                            conversation = current.conversation.copy(
                                nativeNetworkError = error.message ?: "Диалоговый нейронный проход недоступен.",
                                nativeNetworkMessage = "Чат продолжен без нейронного контекста.",
                            ),
                        )
                    }
                    null
                }
            }.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (error: Exception) {
            null
        }
    }

    private fun dialogueOutputNeuronId(label: String): Int {
        val index = when (label) {
            "greeting" -> 0
            "question" -> 1
            "thanks" -> 2
            "farewell" -> 3
            else -> throw IllegalArgumentException("Неизвестная метка диалога.")
        }
        return AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START + index
    }

    /** Explicit class selection rewards the selected output and weakens alternatives. */
    private fun enqueueNativeDialogueLabelReward(
        contextId: String,
        rewardNeuronIds: List<Int>,
        expectedLabel: String,
    ) {
        val selected = dialogueOutputNeuronId(expectedLabel)
        val rewards = (0 until AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_NEURONS).associate { index ->
            val outputId = AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START + index
            outputId to if (outputId == selected) 1.0 else -1.0 / 3.0
        }
        enqueueNativeDialogueReward(contextId, rewardNeuronIds, rewards)
    }

    /** User feedback is attached to the latest assistant response and its pending spike trace. */
    private fun enqueueNativeDialogueReward(
        contextId: String,
        rewardNeuronIds: List<Int>,
        rewards: Map<Int, Double>,
    ) {
        nativeNetworkExecutor.execute {
            try {
                val result = nativeNeuronNetwork.applyDialogueReward(contextId, rewardNeuronIds, rewards)
                val stats = nativeNeuronNetwork.stats()
                mainHandler.post {
                    val current = mutableState.value
                    val ownsFeedbackContext =
                        current.conversation.nativeDialogueContextId == contextId
                    val nextConversation = current.conversation.copy(
                        nativeNetworkStats = stats,
                        nativeNetworkError = if (ownsFeedbackContext && result.updatedSynapses == 0) {
                            null
                        } else if (ownsFeedbackContext) {
                            null
                        } else {
                            current.conversation.nativeNetworkError
                        },
                        nativeNetworkMessage = if (ownsFeedbackContext) {
                            if (result.updatedSynapses > 0) {
                                "R-STDP reward применён: обновлено синапсов ${result.updatedSynapses}; " +
                                    "просрочено eligibility: ${result.expiredSynapses}."
                            } else {
                                "R-STDP reward не изменил веса: для этого хода не найден действующий eligibility-след."
                            }
                        } else {
                            current.conversation.nativeNetworkMessage
                        },
                        nativeDialogueContextId = if (ownsFeedbackContext) null
                            else current.conversation.nativeDialogueContextId,
                        nativeDialogueRewardNeuronIds = if (ownsFeedbackContext) emptyList()
                            else current.conversation.nativeDialogueRewardNeuronIds,
                    )
                    mutableState.value = current.copy(conversation = nextConversation)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    val ownsFeedbackContext =
                        current.conversation.nativeDialogueContextId == contextId
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            nativeNetworkError = if (ownsFeedbackContext) {
                                error.message ?: "Не удалось применить R-STDP reward."
                            } else {
                                current.conversation.nativeNetworkError
                            },
                            nativeDialogueContextId = if (ownsFeedbackContext) null
                                else current.conversation.nativeDialogueContextId,
                            nativeDialogueRewardNeuronIds = if (ownsFeedbackContext) emptyList()
                                else current.conversation.nativeDialogueRewardNeuronIds,
                        ),
                    )
                }
            }
        }
    }

    fun refreshNativeNeuronNetwork() {
        nativeNetworkExecutor.execute {
            try {
                val stats = nativeNeuronNetwork.stats()
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            nativeNetworkStats = stats,
                            nativeNetworkError = null,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            nativeNetworkError = error.message ?: "Не удалось прочитать состояние JNVM.",
                        ),
                    )
                }
            }
        }
    }

    fun initializeNativeNeuronNetwork() {
        val before = mutableState.value
        if (before.conversation.nativeNetworkBusy) return
        mutableState.value = before.copy(
            conversation = before.conversation.copy(
                nativeNetworkBusy = true,
                nativeNetworkError = null,
                nativeNetworkMessage = "Создание или обновление трёхслойной разреженной сети из 10 000 нейронов…",
            ),
        )
        nativeNetworkExecutor.execute {
            try {
                nativeNeuronNetwork.initializeSparseDialogueNetwork(
                    neuronCount = AndroidSparseNeuronNetwork.MAX_NEURONS,
                    weight = DIALOGUE_INITIAL_SYNAPSE_WEIGHT,
                    inputToHiddenWeight = AndroidSparseNeuronNetwork.DEFAULT_INPUT_TO_HIDDEN_WEIGHT,
                )
                val stats = nativeNeuronNetwork.stats()
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            nativeNetworkBusy = false,
                            nativeNetworkStats = stats,
                            nativeNetworkError = null,
                            nativeNetworkMessage = "Подготовлена трёхслойная сеть: 9 000 входных, 996 скрытых и 4 выходных LIF-DT нейрона; до 75 984 разреженных синапсов, состояние хранится в SQLite JNVM.",
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            nativeNetworkBusy = false,
                            nativeNetworkError = error.message ?: "Не удалось создать сеть.",
                        ),
                    )
                }
            }
        }
    }

    fun runNativeNeuronProbe() {
        val before = mutableState.value
        if (before.conversation.nativeNetworkBusy) return
        mutableState.value = before.copy(
            conversation = before.conversation.copy(
                nativeNetworkBusy = true,
                nativeNetworkError = null,
                nativeNetworkMessage = "Обработка ограниченного пакета нейронных событий…",
            ),
        )
        nativeNetworkExecutor.execute {
            try {
                val prior = nativeNeuronNetwork.stats()
                if (prior.neuronCount == 0) {
                    throw IllegalStateException("Сначала создай сеть из 10 000 нейронов.")
                }
                nativeNeuronNetwork.initializeSparseDialogueNetwork(
                    neuronCount = AndroidSparseNeuronNetwork.MAX_NEURONS,
                    weight = DIALOGUE_INITIAL_SYNAPSE_WEIGHT,
                    inputToHiddenWeight = AndroidSparseNeuronNetwork.DEFAULT_INPUT_TO_HIDDEN_WEIGHT,
                )
                if (prior.currentTimeNs > Long.MAX_VALUE - 10_000_000L -
                    2L * AndroidSparseNeuronNetwork.DEFAULT_CONNECTION_DELAY_NS
                ) {
                    throw IllegalStateException("Модельные часы JNVM достигли предела.")
                }
                val nextTime = prior.currentTimeNs + 10_000_000L
                nativeNeuronNetwork.scheduleInputs(
                    listOf(
                        AndroidNeuronEvent(
                            eventId = "phone-probe-a-${UUID.randomUUID()}",
                            timeNs = nextTime,
                            targetNeuronId = 0,
                            strength = 1.0,
                        ),
                        AndroidNeuronEvent(
                            eventId = "phone-probe-b-${UUID.randomUUID()}",
                            timeNs = nextTime,
                            targetNeuronId = 996,
                            strength = 1.0,
                        ),
                    ),
                )
                val result = nativeNeuronNetwork.run(maxEvents = 4_096, maxTimestamps = 4)
                val stats = nativeNeuronNetwork.stats()
                val hiddenSpikes = result.emittedSpikes.count {
                    it.first in AndroidSparseNeuronNetwork.DIALOGUE_HIDDEN_START until
                        AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START
                }
                val outputSpikes = result.emittedSpikes.count {
                    it.first in AndroidSparseNeuronNetwork.DIALOGUE_OUTPUT_START until
                        AndroidSparseNeuronNetwork.MAX_NEURONS
                }
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            nativeNetworkBusy = false,
                            nativeNetworkStats = stats,
                            nativeNetworkError = null,
                            nativeNetworkMessage = "Тест трёх слоёв: событий ${result.processedEvents}; скрытых импульсов ${hiddenSpikes}; выходных импульсов ${outputSpikes}; очередь ${result.pendingEvents}.",
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        conversation = current.conversation.copy(
                            nativeNetworkBusy = false,
                            nativeNetworkStats = runCatching { nativeNeuronNetwork.stats() }.getOrNull(),
                            nativeNetworkError = error.message ?: "Импульсный тест не выполнен.",
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
                            dialogFeaturePrediction = null,
                            dialogFeatureError = null,
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
        val installed = isTermuxInstalled()
        val before = mutableState.value
        if (!installed) {
            mutableState.value = before.copy(
                runtime = before.runtime.copy(
                    extensionInstalled = false,
                    isConnected = false,
                    snapshot = null,
                    signalEndpointAvailable = null,
                    error = null,
                    message = "Termux не установлен. Встроенное ядро JARVIS работает независимо.",
                ),
            )
            return
        }
        if (!before.runtime.hasAccessToken) {
            mutableState.value = before.copy(
                runtime = before.runtime.copy(
                    extensionInstalled = true,
                    isConnected = false,
                    error = null,
                    message = "Termux найден. Сначала запустите локальную консоль и сохраните её токен.",
                ),
            )
            return
        }
        mutableState.value = before.copy(
            runtime = before.runtime.copy(extensionInstalled = true),
        )
        runtimeRequest(
            successMessage = "Необязательное расширение Termux подключено.",
            operation = { runtimeClient.connect() },
            onSuccess = {
                val current = mutableState.value
                mutableState.value = current.copy(
                    runtime = current.runtime.copy(signalEndpointAvailable = null),
                )
                refreshWorkspace()
            },
        )
    }

    fun refreshRuntime() {
        if (!mutableState.value.runtime.isConnected) {
            connectRuntime()
            return
        }
        runtimeRequest(
            successMessage = "Состояние расширения Termux обновлено.",
            operation = { runtimeClient.refresh() },
            onSuccess = {
                val current = mutableState.value
                mutableState.value = current.copy(
                    runtime = current.runtime.copy(signalEndpointAvailable = null),
                )
                refreshWorkspace()
            },
        )
    }

    fun addLocalMemory(content: String, category: String) {
        val before = mutableState.value
        if (before.localMemory.isBusy || before.peerMemorySync.isBusy || !before.localMemory.isLoaded) return
        mutableState.value = before.copy(
            localMemory = before.localMemory.copy(isBusy = true, error = null),
        )
        conversationExecutor.execute {
            try {
                val records = workspaceMemoryCache.add(content, category)
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        localMemory = LocalMemoryUiState(
                            records = records,
                            isLoaded = true,
                            isBusy = false,
                            lastAddedId = records.lastOrNull()?.id,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        localMemory = current.localMemory.copy(
                            isBusy = false,
                            error = error.message ?: "Не удалось сохранить локальное воспоминание.",
                        ),
                    )
                }
            }
        }
    }

    fun deleteLocalMemory(id: String) {
        val before = mutableState.value
        if (before.localMemory.isBusy || before.peerMemorySync.isBusy || !before.localMemory.isLoaded) return
        mutableState.value = before.copy(
            localMemory = before.localMemory.copy(isBusy = true, error = null),
        )
        conversationExecutor.execute {
            try {
                val records = workspaceMemoryCache.delete(id)
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        localMemory = LocalMemoryUiState(
                            records = records,
                            isLoaded = true,
                            isBusy = false,
                            lastAddedId = current.localMemory.lastAddedId,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        localMemory = current.localMemory.copy(
                            isBusy = false,
                            error = error.message ?: "Не удалось удалить локальное воспоминание.",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Manually merges device-approved memories with the paired PC peer overlay.
     * Only the exact approved-memory record schema is transferred; chats, tokens
     * and model files are never sent. No deletes are propagated in this V1 client.
     */
    fun syncApprovedMemories(host: String, certificateFingerprint: String) {
        val before = mutableState.value
        if (before.peerMemorySync.isBusy || before.localMemory.isBusy || !before.localMemory.isLoaded) return
        mutableState.value = before.copy(
            peerMemorySync = PeerMemorySyncUiState(
                isBusy = true,
                message = "Соединение с ПК по закреплённому TLS-сертификату…",
            ),
        )
        peerSyncExecutor.execute {
            try {
                val token = runtimeTokenStore.load()
                    ?: throw IllegalStateException("Сначала сохраните токен локальной консоли Termux в панели расширения.")
                val localSnapshot = workspaceMemoryCache.snapshot()
                if (!localSnapshot.loaded || localSnapshot.error != null) {
                    throw IllegalStateException("Локальная память не загружена или повреждена; синхронизация остановлена.")
                }
                val result = peerMemorySyncClient.sync(
                    host = host,
                    certificateFingerprint = certificateFingerprint,
                    bearerToken = token,
                    localRecords = localSnapshot.records,
                )
                workspaceMemoryCache.replace(result.records)
                val merged = workspaceMemoryCache.snapshot()
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        localMemory = current.localMemory.copy(
                            records = merged.records,
                            isLoaded = merged.loaded,
                            isBusy = false,
                            error = merged.error,
                        ),
                        peerMemorySync = PeerMemorySyncUiState(
                            isBusy = false,
                            message = "Синхронизация завершена. Новых на ПК: ${result.addedCount}; обновлено: ${result.updatedCount}; конфликтов: ${result.conflictCount}. Удаления в этой версии не синхронизируются.",
                            remoteRevision = result.revision,
                            addedCount = result.addedCount,
                            updatedCount = result.updatedCount,
                            conflictCount = result.conflictCount,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        peerMemorySync = PeerMemorySyncUiState(
                            isBusy = false,
                            error = error.message ?: "Не удалось синхронизировать память с ПК.",
                        ),
                    )
                }
            }
        }
    }

    fun refreshWorkspace() {
        pendingWorkspaceMerge = null
        pendingWorkspaceRestore = null
        if (!mutableState.value.runtime.isConnected) {
            mutableState.value = mutableState.value.copy(
                workspace = mutableState.value.workspace.copy(
                    error = null,
                    message = "Общая JPVR-память доступна через необязательное расширение Termux. Локальный диалог работает отдельно.",
                ),
            )
            return
        }
        workspaceRequest("Общая JPVR-память загружена.") {
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
        if (!before.runtime.extensionInstalled || !before.runtime.isConnected ||
            !before.runtime.hasAccessToken
        ) {
            passphrase.fill('\u0000')
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    error = null,
                    message = "Подключите необязательное расширение Termux, чтобы экспортировать общую JPVR-память.",
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
        if (!before.runtime.extensionInstalled || !before.runtime.isConnected ||
            !before.runtime.hasAccessToken || !before.workspace.isLoaded
        ) {
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
        if (!before.runtime.extensionInstalled || !before.runtime.isConnected ||
            !before.runtime.hasAccessToken || !before.workspace.isLoaded ||
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
                val approvedMemories = (0 until array.length()).mapNotNull { index ->
                    val item = array.getJSONObject(index)
                    if (!item.optBoolean("user_approved", false)) return@mapNotNull null
                    LocalWorkspaceMemory(
                        id = item.getString("id"),
                        content = item.getString("content"),
                        category = item.getString("category"),
                        updatedAt = item.getString("updated_at"),
                        userApproved = true,
                    )
                }
                workspaceMemoryCache.replace(approvedMemories)
                val localMemorySnapshot = workspaceMemoryCache.snapshot()
                pendingWorkspaceRestore = null
                pendingWorkspaceBackupRevision = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        localMemory = current.localMemory.copy(
                            records = localMemorySnapshot.records,
                            isLoaded = localMemorySnapshot.loaded,
                            error = localMemorySnapshot.error,
                        ),
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
        if (!before.runtime.extensionInstalled || !before.runtime.isConnected ||
            !before.runtime.hasAccessToken || !before.workspace.isLoaded
        ) {
            passphrase.fill('\u0000')
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    error = null,
                    message = "Для импорта общей JPVR-памяти подключите необязательное расширение Termux.",
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
                val approvedMemories = (0 until array.length()).mapNotNull { index ->
                    val item = array.getJSONObject(index)
                    if (!item.optBoolean("user_approved", false)) return@mapNotNull null
                    LocalWorkspaceMemory(
                        id = item.getString("id"),
                        content = item.getString("content"),
                        category = item.getString("category"),
                        updatedAt = item.getString("updated_at"),
                        userApproved = true,
                    )
                }
                workspaceMemoryCache.replace(approvedMemories)
                val localMemorySnapshot = workspaceMemoryCache.snapshot()
                pendingWorkspaceMerge = null
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        localMemory = current.localMemory.copy(
                            records = localMemorySnapshot.records,
                            isLoaded = localMemorySnapshot.loaded,
                            error = localMemorySnapshot.error,
                        ),
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

    /** Explicitly executes the one Android-supported read-only task: approved-memory search. */
    fun runRuntimeMemorySearch(taskId: String, revision: Int, query: String) {
        val before = mutableState.value.runtime
        if (before.isBusy) return
        if (!before.extensionInstalled || !before.isConnected || !before.hasAccessToken) {
            mutableState.value = mutableState.value.copy(
                runtime = before.copy(
                    error = null,
                    message = "Для запуска задачи подключите локальное расширение Termux.",
                ),
            )
            return
        }
        val queued = before.snapshot?.tasks?.firstOrNull { it.taskId == taskId }
        if (queued == null || queued.revision != revision || queued.status != "queued") {
            mutableState.value = mutableState.value.copy(
                runtime = before.copy(error = "Задача изменилась. Обновите состояние перед запуском."),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            runtime = before.copy(
                isBusy = true,
                error = null,
                message = "Выполняется только поиск по подтверждённой памяти…",
                taskExecutionResult = null,
            ),
        )
        runtimeExecutor.execute {
            try {
                val result = runtimeClient.runApprovedMemorySearch(taskId, revision, query)
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        runtime = current.runtime.copy(
                            isBusy = false,
                            isConnected = true,
                            snapshot = result.snapshot,
                            error = null,
                            message = result.notice,
                            taskExecutionResult = result,
                        ),
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        runtime = current.runtime.copy(
                            isBusy = false,
                            error = error.message ?: "Безопасная задача не выполнена.",
                        ),
                    )
                }
            }
        }
    }

    fun previewRuntimeReasoning(request: RuntimeReasoningPreviewRequest) {
        val before = mutableState.value.runtime
        if (before.isBusy) return
        if (!before.extensionInstalled || !before.isConnected || !before.hasAccessToken) {
            mutableState.value = mutableState.value.copy(
                runtime = before.copy(
                    error = null,
                    message = "Символьный Reasoning Engine preview доступен через подключённое расширение Termux; локальный диалог работает отдельно.",
                ),
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
        if (!before.runtime.extensionInstalled || !before.runtime.isConnected ||
            !before.runtime.hasAccessToken
        ) {
            mutableState.value = before.copy(
                workspace = before.workspace.copy(
                    error = null,
                    message = "Для общей JPVR-памяти подключите необязательное расширение Termux. Локальный диалог доступен без него.",
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
                val approvedMemories = (0 until array.length()).mapNotNull { index ->
                    val item = array.getJSONObject(index)
                    if (!item.optBoolean("user_approved", false)) return@mapNotNull null
                    LocalWorkspaceMemory(
                        id = item.getString("id"),
                        content = item.getString("content"),
                        category = item.getString("category"),
                        updatedAt = item.getString("updated_at"),
                        userApproved = true,
                    )
                }
                workspaceMemoryCache.replace(approvedMemories)
                val localMemorySnapshot = workspaceMemoryCache.snapshot()
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        localMemory = current.localMemory.copy(
                            records = localMemorySnapshot.records,
                            isLoaded = localMemorySnapshot.loaded,
                            error = localMemorySnapshot.error,
                        ),
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
        onSuccess: () -> Unit = {},
        operation: () -> RuntimeBridgeSnapshot,
    ) {
        val before = mutableState.value.runtime
        if (before.isBusy) return
        if (!before.extensionInstalled) {
            mutableState.value = mutableState.value.copy(
                runtime = before.copy(
                    error = null,
                    message = "Расширение Termux не обнаружено. Встроенные функции JARVIS доступны без него.",
                ),
            )
            return
        }
        if (!before.hasAccessToken) {
            mutableState.value = mutableState.value.copy(
                runtime = before.copy(
                    error = null,
                    message = "Сначала сохраните токен из локальной консоли Termux.",
                ),
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
                    onSuccess()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        runtime = current.runtime.copy(
                            isBusy = false,
                            isConnected = false,
                            snapshot = null,
                            error = error.message ?: "Расширение Termux пока недоступно.",
                            message = "Встроенное ядро JARVIS продолжает работать независимо от Termux.",
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

    companion object {
        private const val TERMUX_PACKAGE_NAME = "com.termux"
        private const val MAX_DIALOGUE_FEATURE_EVENTS = 128
        private const val MAX_DIALOGUE_EVENTS_PER_TURN = 4_096
        private const val MAX_DIALOGUE_TIMESTAMPS_PER_TURN = 4
        private const val DIALOGUE_INPUT_STEP_NS = 10_000_000L
        private const val DIALOGUE_INITIAL_SYNAPSE_WEIGHT = 0.15
    }

    override fun onCleared() {
        // A ViewModel can be cleared while the HTTP download is active.
        // Disconnect it first so the worker can finish and close the model engine.
        modelStore.cancelDownload()
        conversationExecutor.execute { conversationResponseEngine.close() }
        nativeNetworkExecutor.execute { nativeNeuronNetwork.close() }
        conversationExecutor.shutdown()
        nativeNetworkExecutor.shutdown()
        runtimeExecutor.shutdownNow()
        peerSyncExecutor.shutdownNow()
        super.onCleared()
    }
}
