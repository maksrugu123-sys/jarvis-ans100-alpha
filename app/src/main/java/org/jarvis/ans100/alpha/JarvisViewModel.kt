package org.jarvis.ans100.alpha

import android.app.Application
import android.content.Intent
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Handler
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jarvis.ans100.alpha.data.LocalConversationCoordinator
import org.jarvis.ans100.alpha.data.LocalConversationSignalRuntime
import org.jarvis.ans100.alpha.data.SharedPreferencesConversationSignalStorage
import org.jarvis.ans100.alpha.data.DialogueNeuralAnalysis
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
import org.jarvis.ans100.alpha.data.AndroidHybridTopologyV2
import org.jarvis.ans100.alpha.data.AndroidHybridEvidenceInputAdapter
import org.jarvis.ans100.alpha.data.TypedEvidenceEncoder
import org.jarvis.ans100.alpha.data.TypedEvidenceInput
import org.jarvis.ans100.alpha.data.ANS100HybridPopulationController
import org.jarvis.ans100.alpha.data.ANS100CandidateSlotContract
import org.jarvis.ans100.alpha.data.ANS100QwenHandoffBuilder
import org.jarvis.ans100.alpha.data.LocalUnderstandingPipeline
import org.jarvis.ans100.alpha.data.ANS100WorkspaceConceptInputBridge
import org.jarvis.ans100.alpha.data.ANS100NeuralAssociationDecoder
import org.jarvis.ans100.alpha.data.ANS100NeuralHypothesisEngine
import org.jarvis.ans100.alpha.data.ANS100NeuralHypothesisLedger
import org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine
import org.jarvis.ans100.alpha.data.ANS100HypothesisRevisionEngine
import org.jarvis.ans100.alpha.data.ANS100HypothesisRevisionLedger
import org.jarvis.ans100.alpha.data.ANS100HypothesisManualReviewDecision
import org.jarvis.ans100.alpha.data.ANS100HypothesisManualReviewLedger
import org.jarvis.ans100.alpha.data.ANS100HypothesisManualReviewCoordinator
import org.jarvis.ans100.alpha.data.ANS100HypothesisEvidenceReviewPacketBuilder
import org.jarvis.ans100.alpha.data.ANS100ConceptMemory
import org.jarvis.ans100.alpha.data.ANS100BenchmarkTraceAdapter
import org.jarvis.ans100.alpha.data.ANS100CanonicalReadoutContract
import org.jarvis.ans100.alpha.data.ANS100CanonicalReadoutDecoder
import org.jarvis.ans100.alpha.data.ANS100BenchmarkTraceStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.jarvis.ans100.alpha.data.AndroidWebSearchIntent
import org.jarvis.ans100.alpha.data.formatAndroidWebSearchReply
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

data class NeuralAssociationFeedbackUi(
    val candidateId: String,
    val firstLabel: String,
    val secondLabel: String,
)

data class CanonicalReadoutFeedbackUi(
    val predictedAnswer: String,
    val spikeCountsSummary: String,
)

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
    val hybridNetworkSummary: String? = null,
    val hybridNetworkStats: org.jarvis.ans100.alpha.data.AndroidNeuronNetworkStats? = null,
    val hybridPopulationActivity: List<org.jarvis.ans100.alpha.data.AndroidNeuronPopulationActivity> = emptyList(),
    val hybridFeedbackAvailable: Boolean = false,
    val hybridFeedbackMessage: String? = null,
    val neuralAssociationFeedback: NeuralAssociationFeedbackUi? = null,
    val canonicalReadoutFeedback: CanonicalReadoutFeedbackUi? = null,
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

data class ANS100BenchmarkBatchInput(
    val taskId: String,
    val inputText: String,
)

data class ANS100BenchmarkDevelopmentExample(
    val taskId: String,
    val inputText: String,
    val trainingLabel: String,
)

data class HypothesisReviewUiState(
    val requests: List<org.jarvis.ans100.alpha.data.ANS100HypothesisRevisionEngine.ReviewRequest> = emptyList(),
    val recentDecisions: List<org.jarvis.ans100.alpha.data.ANS100HypothesisManualReviewLedger.Entry> = emptyList(),
    val isLoaded: Boolean = false,
    val error: String? = null,
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
    val hypothesisReview: HypothesisReviewUiState = HypothesisReviewUiState(),
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    // Compose-owned benchmark UI state is held by the ViewModel, not the disposable tab.
    // This keeps selected JSONL inputs, progress and export hand-off alive across tab changes.
    val benchmarkDevelopmentRowsState = mutableStateOf<List<ANS100BenchmarkDevelopmentExample>>(emptyList())
    val benchmarkHeldOutRowsState = mutableStateOf<List<ANS100BenchmarkBatchInput>>(emptyList())
    val benchmarkStatusState = mutableStateOf("Выбери два JSONL-файла, подготовленных Python-адаптером.")
    val benchmarkBusyState = mutableStateOf(false)
    val neuronComputeBenchmarkStatusState = mutableStateOf("Сравнительный тест ещё не запускался.")
    val neuronComputeBenchmarkBusyState = mutableStateOf(false)
    val neuronComputeBenchmarkReportState = mutableStateOf<org.jarvis.ans100.alpha.data.AndroidNeuronKernelBenchmarkReport?>(null)
    val benchmarkPendingFilesState = mutableStateOf<ANS100BenchmarkJsonl.PredictionFiles?>(null)
    val benchmarkPendingEncodingFilesState = mutableStateOf<ANS100BenchmarkJsonl.EncodingComparisonFiles?>(null)

    private val benchmarkPreferences = application.getSharedPreferences("jarvis_benchmark_checkpoint_v1", Application.MODE_PRIVATE)

    init {
        restoreBenchmarkCheckpoint()
    }

    /** Persist only validated task inputs and safe UI status; held-out labels are never stored. */
    fun saveBenchmarkDevelopmentRows(rows: List<ANS100BenchmarkDevelopmentExample>) {
        benchmarkDevelopmentRowsState.value = rows
        val json = JSONArray().apply {
            rows.forEach { row ->
                put(JSONObject().put("task_id", row.taskId).put("input_text", row.inputText)
                    .put("training_label", row.trainingLabel))
            }
        }
        benchmarkPreferences.edit().putString("development_rows", json.toString()).apply()
    }

    fun saveBenchmarkHeldOutRows(rows: List<ANS100BenchmarkBatchInput>) {
        benchmarkHeldOutRowsState.value = rows
        val json = JSONArray().apply {
            rows.forEach { row ->
                put(JSONObject().put("task_id", row.taskId).put("input_text", row.inputText))
            }
        }
        benchmarkPreferences.edit().putString("held_out_rows", json.toString()).apply()
    }

    private fun restoreBenchmarkCheckpoint() {
        runCatching {
            benchmarkPreferences.getString("development_rows", null)?.let { raw ->
                val json = JSONArray(raw)
                benchmarkDevelopmentRowsState.value = List(json.length()) { index ->
                    val row = json.getJSONObject(index)
                    ANS100BenchmarkDevelopmentExample(
                        row.getString("task_id"), row.getString("input_text"), row.getString("training_label"),
                    )
                }
            }
            benchmarkPreferences.getString("held_out_rows", null)?.let { raw ->
                val json = JSONArray(raw)
                benchmarkHeldOutRowsState.value = List(json.length()) { index ->
                    val row = json.getJSONObject(index)
                    ANS100BenchmarkBatchInput(row.getString("task_id"), row.getString("input_text"))
                }
            }
        }.onFailure {
            benchmarkDevelopmentRowsState.value = emptyList()
            benchmarkHeldOutRowsState.value = emptyList()
            benchmarkStatusState.value = "Не удалось восстановить контрольную точку эксперимента: ${it.message}"
        }
        if (benchmarkPreferences.getBoolean("was_running", false)) {
            benchmarkStatusState.value = "Приложение перезапускалось во время эксперимента. Входные наборы восстановлены; незавершённый запуск нужно начать заново."
            benchmarkPreferences.edit().putBoolean("was_running", false).apply()
        } else if (benchmarkDevelopmentRowsState.value.isNotEmpty() || benchmarkHeldOutRowsState.value.isNotEmpty()) {
            benchmarkStatusState.value = "Контрольная точка восстановлена: development ${benchmarkDevelopmentRowsState.value.size}, held-out ${benchmarkHeldOutRowsState.value.size}."
        }
    }

    fun markBenchmarkRunning(running: Boolean) {
        benchmarkBusyState.value = running
        benchmarkPreferences.edit().putBoolean("was_running", running).apply()
    }

    private val appContext = application.applicationContext
    private val repository = LocalDraftRepository(
        SharedPreferencesDraftStorage(application.applicationContext),
    )
    private val modelStore = LiteRtLmModelStore(application.applicationContext)
    private val conversationResponseEngine = LiteRtLmResponseEngine(
        application.applicationContext, modelStore,
    )
    private val conversationExecutor = Executors.newSingleThreadExecutor()
    private val hybridNetworkExecutor = Executors.newSingleThreadExecutor()
    private val neuronComputeBenchmarkExecutor = Executors.newSingleThreadExecutor()
    private var pairedBenchmarkSequence = 0L
    private val ans100BenchmarkRuntimeDelegate = lazy {
        org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime(appContext)
    }
    private val ans100BenchmarkRuntime by ans100BenchmarkRuntimeDelegate
    /** Separate SQLite namespace; never shares or migrates the legacy dialogue graph. */
    private val hybridNeuronNetworkDelegate = lazy {
        val context = application.applicationContext
        val computeMode = org.jarvis.ans100.alpha.data.AndroidNeuronComputeModePreferences(context).load()
        val computeBackend = org.jarvis.ans100.alpha.data.AndroidNeuronComputeBackendFactory.create(
            context,
            computeMode,
        )
        AndroidHybridTopologyV2.open(context, computeBackend = computeBackend)
    }
    private val hybridNeuronNetwork by hybridNeuronNetworkDelegate
    @Volatile private var latestHybridFeedback: Pair<String, String>? = null
    /** One explicit rating may train the top neural association candidate from the same turn. */
    @Volatile private var latestNeuralAssociationFeedback: Triple<String, String, String>? = null
    @Volatile private var pendingNeuralAssociationFeedbackUi: NeuralAssociationFeedbackUi? = null
    /** One explicit class label may train only the latest isolated canonical readout trace. */
    @Volatile private var latestCanonicalReadoutFeedback: Pair<String, String>? = null
    @Volatile private var pendingCanonicalReadoutFeedbackUi: CanonicalReadoutFeedbackUi? = null
    @Volatile private var conversationGeneration: Long = 0L
    private val androidWebSearch = AndroidWebSearchService()
    // The app-local ANS-100 pass is always available; Termux may override it when connected.
    private val localSignalRuntime = LocalConversationSignalRuntime(
        SharedPreferencesConversationSignalStorage(application.applicationContext),
    )
    private val workspaceMemoryCache = LocalWorkspaceMemoryCache(
        SharedPreferencesLocalWorkspaceMemoryStorage(application.applicationContext),
    )
    private val workspaceConceptInputBridge = ANS100WorkspaceConceptInputBridge()
    private val neuralHypothesisLedger = ANS100NeuralHypothesisLedger(appContext)
    private val neuralHypothesisEngine = ANS100NeuralHypothesisEngine().apply {
        runCatching { neuralHypothesisLedger.load()?.let { restoreSnapshot(it) } }
    }
    private val structuredHypothesisRevisionLedger = ANS100HypothesisRevisionLedger(appContext)
    private val structuredHypothesisRevisionEngine = ANS100HypothesisRevisionEngine().apply {
        runCatching { structuredHypothesisRevisionLedger.load()?.let { restoreSnapshot(it) } }
    }
    private val manualHypothesisReviewDecision = ANS100HypothesisManualReviewDecision()
    private val manualHypothesisReviewLedger = ANS100HypothesisManualReviewLedger(
        appContext,
        decisionValidator = manualHypothesisReviewDecision,
    )
    private val manualHypothesisReviewCoordinator = ANS100HypothesisManualReviewCoordinator(
        decisionFactory = manualHypothesisReviewDecision,
        decisionLedger = manualHypothesisReviewLedger,
        revisionEngine = structuredHypothesisRevisionEngine,
        revisionLedger = structuredHypothesisRevisionLedger,
    )
    // Replay the write-ahead journal only after the revision checkpoint has been restored.
    private val manualHypothesisReviewRecovery = runCatching {
        manualHypothesisReviewCoordinator.replayPending()
    }.getOrNull()
    private val benchmarkTraceStore = ANS100BenchmarkTraceStore(appContext.filesDir)
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

    private val modelMemoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                releaseModelForMemoryPressure()
            }
        }

        override fun onLowMemory() {
            releaseModelForMemoryPressure()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        private fun releaseModelForMemoryPressure() {
            if (!conversationResponseEngine.languageModelConfigured) return
            // Engine access is serialized with inference, download, import and deletion.
            conversationExecutor.execute {
                val released = runCatching {
                    conversationResponseEngine.releaseForMemoryPressure()
                }.getOrDefault(false)
                if (released) {
                    mainHandler.post {
                        val current = mutableState.value
                        mutableState.value = current.copy(
                            conversation = current.conversation.copy(
                                backendLabel = conversationResponseEngine.backendLabel,
                            ),
                        )
                    }
                }
            }
        }
    }

    init {
        appContext.registerComponentCallbacks(modelMemoryCallbacks)
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
        // The legacy three-layer dialogue graph is no longer initialized or refreshed on startup.
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

    /**
     * Executes one isolated Qwen-only benchmark item. The callback is delivered
     * on the main thread; all LiteRT engine access stays on conversationExecutor.
     */
    fun runTransformerOnlyBenchmark(
        inputText: String,
        onComplete: (Result<org.jarvis.ans100.alpha.data.LiteRtLmBenchmarkResult>) -> Unit,
    ) {
        conversationExecutor.execute {
            val result = runCatching {
                conversationResponseEngine.benchmarkTransformerOnly(inputText)
            }
            mainHandler.post { onComplete(result) }
        }
    }


    /**
     * Runs one ANS-100-only benchmark item on its isolated SQLite topology.
     * A non-null label is permitted only for the development/training split.
     */
    fun runAns100OnlyBenchmark(
        taskId: String,
        inputText: String,
        trainingLabel: String? = null,
        onComplete: (Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>) -> Unit,
    ) {
        conversationExecutor.execute {
            val result = runCatching {
                ans100BenchmarkRuntime.runTask(taskId, inputText, trainingLabel)
            }
            mainHandler.post { onComplete(result) }
        }
    }


    /** Starts the ANS-100 benchmark from an empty, isolated learning database. */
    fun resetAns100OnlyBenchmark(onComplete: (Result<Unit>) -> Unit) {
        conversationExecutor.execute {
            val result = runCatching { ans100BenchmarkRuntime.resetLearnedState() }
            mainHandler.post { onComplete(result) }
        }
    }


    data class PairedBenchmarkTaskResult(
        val taskId: String,
        val transformer: Result<org.jarvis.ans100.alpha.data.LiteRtLmBenchmarkResult>,
        val ans100: Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>,
    )

    data class Ans100EncodingComparisonResult(
        val taskId: String,
        val lexical: Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>,
        val structured: Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>,
    )

    data class StructuredPairedBenchmarkTaskResult(
        val taskId: String,
        val transformer: Result<org.jarvis.ans100.alpha.data.LiteRtLmBenchmarkResult>,
        val ans100Structured: Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>,
    )

    /** Runs the B1 role-structured encoder without changing the legacy lexical baseline. */
    fun runAns100StructuredOnlyBenchmark(
        taskId: String,
        inputText: String,
        trainingLabel: String? = null,
        onComplete: (Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>) -> Unit,
    ) {
        conversationExecutor.execute {
            val result = runCatching {
                ans100BenchmarkRuntime.runStructuredTask(taskId, inputText, trainingLabel)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun resetAns100StructuredBenchmark(onComplete: (Result<Unit>) -> Unit) {
        conversationExecutor.execute {
            val result = runCatching { ans100BenchmarkRuntime.resetStructuredLearnedState() }
            mainHandler.post { onComplete(result) }
        }
    }

    /** Development labels are accepted only by the isolated structured database. */
    fun trainAns100StructuredDevelopmentBatch(
        examples: List<ANS100BenchmarkDevelopmentExample>,
        onProgress: (completed: Int, total: Int, result: Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>) -> Unit,
        onComplete: (List<Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>>) -> Unit,
    ) {
        require(examples.isNotEmpty() && examples.size <= MAX_BENCHMARK_BATCH_SIZE)
        require(examples.map { it.taskId }.distinct().size == examples.size)
        require(examples.all {
            it.taskId.startsWith("development:") &&
                it.inputText.isNotBlank() &&
                it.inputText.length <= org.jarvis.ans100.alpha.data.ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS &&
                it.trainingLabel.uppercase() in setOf("YES", "NO", "UNKNOWN", "CONFLICT")
        }) { "structured training accepts only valid development examples" }
        conversationExecutor.execute {
            val results = ArrayList<Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>>(examples.size)
            examples.forEachIndexed { index, example ->
                val result = runCatching {
                    benchmarkSafetyStopReason()?.let { error("BENCHMARK_SAFETY_STOP: $it") }
                    val trained = ans100BenchmarkRuntime.runStructuredTask(
                        example.taskId, example.inputText, example.trainingLabel,
                    )
                    check(trained.completed) { "${example.taskId}: structured network did not drain its queue" }
                    check(trained.trainingApplied) { "${example.taskId}: no structured readout synapse update was applied" }
                    trained
                }
                results.add(result)
                mainHandler.post { onProgress(index + 1, examples.size, result) }
                if (result.isFailure) {
                    val failure = result.exceptionOrNull()
                    if (failure?.message?.startsWith("BENCHMARK_SAFETY_STOP:") == true) {
                        for (remaining in examples.drop(index + 1)) {
                            results.add(Result.failure(IllegalStateException(failure.message)))
                        }
                        mainHandler.post { onComplete(results) }
                        return@execute
                    }
                }
            }
            mainHandler.post { onComplete(results) }
        }
    }

    /** Direct paired comparison of lexical and role-structured ANS on identical held-out rows. */
    fun runAns100EncodingComparisonBatch(
        inputs: List<ANS100BenchmarkBatchInput>,
        onProgress: (completed: Int, total: Int, result: Ans100EncodingComparisonResult) -> Unit,
        onComplete: (List<Ans100EncodingComparisonResult>) -> Unit,
    ) {
        require(inputs.isNotEmpty() && inputs.size <= MAX_BENCHMARK_BATCH_SIZE)
        require(inputs.map { it.taskId }.distinct().size == inputs.size)
        require(inputs.all {
            listOf("pair_disjoint:", "concept_disjoint:", "compositional:").any { prefix -> it.taskId.startsWith(prefix) } &&
                it.inputText.isNotBlank() &&
                it.inputText.length <= org.jarvis.ans100.alpha.data.ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS
        }) { "encoding comparison accepts only valid held-out inputs" }
        conversationExecutor.execute {
            val results = ArrayList<Ans100EncodingComparisonResult>(inputs.size)
            inputs.forEachIndexed { index, input ->
                val lexical = runCatching {
                    ans100BenchmarkRuntime.runTask(input.taskId, input.inputText, null, coldStart = true)
                }
                val structured = runCatching {
                    ans100BenchmarkRuntime.runStructuredTask(input.taskId, input.inputText, null, coldStart = true)
                }
                val result = Ans100EncodingComparisonResult(input.taskId, lexical, structured)
                results.add(result)
                mainHandler.post { onProgress(index + 1, inputs.size, result) }
            }
            mainHandler.post { onComplete(results) }
        }
    }

    /**
     * Complete B5 encoding experiment: reset both isolated databases, train each
     * encoder only on the same development rows, then evaluate both on identical
     * held-out rows. No labels are passed during evaluation.
     */
    fun runCompleteAns100EncodingComparison(
        development: List<ANS100BenchmarkDevelopmentExample>,
        heldOut: List<ANS100BenchmarkBatchInput>,
        onProgress: (String) -> Unit,
        onComplete: (Result<List<Ans100EncodingComparisonResult>>) -> Unit,
    ) {
        require(development.isNotEmpty() && development.size <= MAX_BENCHMARK_BATCH_SIZE)
        require(heldOut.isNotEmpty() && heldOut.size <= MAX_BENCHMARK_BATCH_SIZE)
        require(development.map { it.taskId }.distinct().size == development.size)
        require(heldOut.map { it.taskId }.distinct().size == heldOut.size)
        require(development.all {
            it.taskId.startsWith("development:") && it.inputText.isNotBlank() &&
                it.inputText.length <= 2_048 &&
                it.trainingLabel.uppercase() in setOf("YES", "NO", "UNKNOWN", "CONFLICT")
        })
        require(heldOut.all {
            listOf("pair_disjoint:", "concept_disjoint:", "compositional:").any { prefix ->
                it.taskId.startsWith(prefix)
            } && it.inputText.isNotBlank() && it.inputText.length <= 2_048
        })
        conversationExecutor.execute {
            val result = runCatching {
                ans100BenchmarkRuntime.resetLearnedState()
                ans100BenchmarkRuntime.resetStructuredLearnedState()
                development.forEachIndexed { index, example ->
                    val trained = ans100BenchmarkRuntime.runTask(
                        example.taskId, example.inputText, example.trainingLabel,
                    )
                    check(trained.completed && trained.trainingApplied) {
                        "Lexical training failed at ${example.taskId}"
                    }
                    mainHandler.post { onProgress("Лексическое обучение: ${index + 1}/${development.size}") }
                }
                development.forEachIndexed { index, example ->
                    val trained = ans100BenchmarkRuntime.runStructuredTask(
                        example.taskId, example.inputText, example.trainingLabel,
                    )
                    check(trained.completed && trained.trainingApplied) {
                        "Structured training failed at ${example.taskId}"
                    }
                    mainHandler.post { onProgress("Структурированное обучение: ${index + 1}/${development.size}") }
                }
                val comparisons = ArrayList<Ans100EncodingComparisonResult>(heldOut.size)
                heldOut.forEachIndexed { index, input ->
                    val lexical = runCatching {
                        ans100BenchmarkRuntime.runTask(input.taskId, input.inputText, null, coldStart = true)
                    }
                    val structured = runCatching {
                        ans100BenchmarkRuntime.runStructuredTask(input.taskId, input.inputText, null, coldStart = true)
                    }
                    comparisons += Ans100EncodingComparisonResult(input.taskId, lexical, structured)
                    mainHandler.post { onProgress("Сравнение на новых данных: ${index + 1}/${heldOut.size}") }
                }
                comparisons.toList()
            }
            mainHandler.post { onComplete(result) }
        }
    }

    /** Qwen versus structured ANS on exactly the same held-out input. */
    fun runStructuredPairedBenchmarkTask(
        taskId: String,
        inputText: String,
        onComplete: (StructuredPairedBenchmarkTaskResult) -> Unit,
    ) {
        require(listOf("pair_disjoint:", "concept_disjoint:", "compositional:").any { prefix -> taskId.startsWith(prefix) })
        require(inputText.isNotBlank() &&
            inputText.length <= org.jarvis.ans100.alpha.data.ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS)
        conversationExecutor.execute {
            val result = if (pairedBenchmarkSequence++ % 2L == 0L) {
                val transformer = runCatching { conversationResponseEngine.benchmarkTransformerOnly(inputText, true) }
                val structured = runCatching { ans100BenchmarkRuntime.runStructuredTask(taskId, inputText, null, true) }
                StructuredPairedBenchmarkTaskResult(taskId, transformer, structured)
            } else {
                val structured = runCatching { ans100BenchmarkRuntime.runStructuredTask(taskId, inputText, null, true) }
                val transformer = runCatching { conversationResponseEngine.benchmarkTransformerOnly(inputText, true) }
                StructuredPairedBenchmarkTaskResult(taskId, transformer, structured)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    /**
     * Conservative device guard for the on-device paired benchmark.
     * PSS is process-wide (not model-specific); the thresholds are stop limits,
     * not performance targets.
     */
    private fun benchmarkSafetyStopReason(): String? {
        val manager = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        if (memory.lowMemory) return "Android reported low memory."
        if (memory.availMem < 384L * 1024L * 1024L) {
            return "Available system memory fell below the 384 MiB safety reserve."
        }
        val processPssMb = android.os.Debug.getPss().toLong() / 1024L
        if (processPssMb >= 1_400L) {
            return "JARVIS process PSS reached the 1400 MiB safety limit."
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val power = appContext.getSystemService(android.content.Context.POWER_SERVICE)
                as? android.os.PowerManager
            if (power != null && power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
                return "Android thermal status is severe or worse."
            }
        }
        return null
    }

    fun runStructuredPairedBenchmarkBatch(
        inputs: List<ANS100BenchmarkBatchInput>,
        onProgress: (completed: Int, total: Int, result: StructuredPairedBenchmarkTaskResult) -> Unit,
        onComplete: (List<StructuredPairedBenchmarkTaskResult>) -> Unit,
    ) {
        require(inputs.isNotEmpty() && inputs.size <= MAX_BENCHMARK_BATCH_SIZE)
        require(inputs.map { it.taskId }.distinct().size == inputs.size)
        require(inputs.all {
            listOf("pair_disjoint:", "concept_disjoint:", "compositional:").any { prefix -> it.taskId.startsWith(prefix) } &&
                it.inputText.isNotBlank() &&
                it.inputText.length <= org.jarvis.ans100.alpha.data.ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS
        }) { "structured paired evaluation accepts only valid held-out inputs" }
        conversationExecutor.execute {
            val results = ArrayList<StructuredPairedBenchmarkTaskResult>(inputs.size)
            inputs.forEachIndexed { index, input ->
                val stopReason = benchmarkSafetyStopReason()
                val result = if (stopReason != null) {
                    val stopped = Result.failure<Nothing>(
                        IllegalStateException("BENCHMARK_SAFETY_STOP: $stopReason"),
                    )
                    StructuredPairedBenchmarkTaskResult(input.taskId, stopped, stopped)
                } else if (pairedBenchmarkSequence++ % 2L == 0L) {
                    val transformer = runCatching {
                        conversationResponseEngine.benchmarkTransformerOnly(input.inputText, true)
                    }
                    val structured = runCatching {
                        ans100BenchmarkRuntime.runStructuredTask(input.taskId, input.inputText, null, true)
                    }
                    StructuredPairedBenchmarkTaskResult(input.taskId, transformer, structured)
                } else {
                    val structured = runCatching {
                        ans100BenchmarkRuntime.runStructuredTask(input.taskId, input.inputText, null, true)
                    }
                    val transformer = runCatching {
                        conversationResponseEngine.benchmarkTransformerOnly(input.inputText, true)
                    }
                    StructuredPairedBenchmarkTaskResult(input.taskId, transformer, structured)
                }
                results.add(result)
                val completed = index + 1
                mainHandler.post {
                    onProgress(completed, inputs.size, result)
                }
                if (result.transformer.exceptionOrNull()?.message?.startsWith("BENCHMARK_SAFETY_STOP:") == true ||
                    result.ans100Structured.exceptionOrNull()?.message?.startsWith("BENCHMARK_SAFETY_STOP:") == true) {
                    for (remaining in inputs.drop(index + 1)) {
                        val stopped = Result.failure<Nothing>(
                            IllegalStateException(result.transformer.exceptionOrNull()?.message ?: "BENCHMARK_SAFETY_STOP"),
                        )
                        results.add(StructuredPairedBenchmarkTaskResult(remaining.taskId, stopped, stopped))
                    }
                    mainHandler.post { onComplete(results) }
                    return@execute
                }
            }
            mainHandler.post { onComplete(results) }
        }
    }

    /**
     * Runs both models on identical held-out text, sequentially on one executor.
     * Development tasks are rejected so evaluation cannot train on its own labels.
     */
    fun runPairedBenchmarkTask(
        taskId: String,
        inputText: String,
        onComplete: (PairedBenchmarkTaskResult) -> Unit,
    ) {
        require(
            listOf("pair_disjoint:", "concept_disjoint:", "compositional:").any { prefix -> taskId.startsWith(prefix) },
        ) {
            "paired benchmark evaluation accepts only held-out split task IDs"
        }
        require(
            inputText.isNotBlank() &&
                inputText.length <= org.jarvis.ans100.alpha.data.ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS,
        ) {
            "paired benchmark input must fit the shared 2048-character model limit"
        }
        conversationExecutor.execute {
            val paired = runPairedBenchmarkTaskBlocking(taskId, inputText, coldStart = true)
            mainHandler.post { onComplete(paired) }
        }
    }

    /**
     * Train only on explicitly exported development rows. Call resetAns100OnlyBenchmark
     * before a fresh experiment; held-out rows are rejected here and by the runtime.
     */
    fun trainAns100DevelopmentBatch(
        examples: List<ANS100BenchmarkDevelopmentExample>,
        onProgress: (completed: Int, total: Int, result: Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>) -> Unit,
        onComplete: (List<Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>>) -> Unit,
    ) {
        require(examples.isNotEmpty() && examples.size <= MAX_BENCHMARK_BATCH_SIZE) {
            "development batch must contain 1..$MAX_BENCHMARK_BATCH_SIZE rows"
        }
        require(examples.map { it.taskId }.distinct().size == examples.size) {
            "development batch contains duplicate task IDs"
        }
        require(examples.all {
            it.taskId.startsWith("development:") &&
                it.inputText.isNotBlank() &&
                it.inputText.length <= org.jarvis.ans100.alpha.data.ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS &&
                it.trainingLabel.uppercase() in setOf("YES", "NO", "UNKNOWN", "CONFLICT")
        }) {
            "development batch contains an invalid task, input, or canonical label"
        }
        conversationExecutor.execute {
            val results = ArrayList<Result<org.jarvis.ans100.alpha.data.ANS100StandaloneBenchmarkRuntime.TaskResult>>(examples.size)
            examples.forEachIndexed { index, example ->
                val result = runCatching {
                    val trained = ans100BenchmarkRuntime.runTask(
                        example.taskId,
                        example.inputText,
                        example.trainingLabel,
                    )
                    check(trained.completed) {
                        "${example.taskId}: ANS-100 did not drain the training event queue."
                    }
                    check(trained.trainingApplied) {
                        "${example.taskId}: no canonical-readout synapse update was applied."
                    }
                    trained
                }
                results.add(result)
                mainHandler.post { onProgress(index + 1, examples.size, result) }
            }
            mainHandler.post { onComplete(results) }
        }
    }

    /**
     * Run every held-out row through both systems on the same text. Individual failures
     * remain attached to their task result; callers must persist null answers as abstentions.
     */
    fun runPairedBenchmarkBatch(
        inputs: List<ANS100BenchmarkBatchInput>,
        onProgress: (completed: Int, total: Int, result: PairedBenchmarkTaskResult) -> Unit,
        onComplete: (List<PairedBenchmarkTaskResult>) -> Unit,
    ) {
        require(inputs.isNotEmpty() && inputs.size <= MAX_BENCHMARK_BATCH_SIZE) {
            "held-out batch must contain 1..$MAX_BENCHMARK_BATCH_SIZE rows"
        }
        require(inputs.map { it.taskId }.distinct().size == inputs.size) {
            "held-out batch contains duplicate task IDs"
        }
        require(inputs.all { input ->
            listOf("pair_disjoint:", "concept_disjoint:", "compositional:").any { prefix -> input.taskId.startsWith(prefix) } &&
                input.inputText.isNotBlank() &&
                input.inputText.length <= org.jarvis.ans100.alpha.data.ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS
        }) {
            "paired batch accepts only valid held-out task IDs and shared-size inputs"
        }
        conversationExecutor.execute {
            val results = ArrayList<PairedBenchmarkTaskResult>(inputs.size)
            inputs.forEachIndexed { index, input ->
                val result = runPairedBenchmarkTaskBlocking(input.taskId, input.inputText, coldStart = true)
                results.add(result)
                mainHandler.post { onProgress(index + 1, inputs.size, result) }
            }
            mainHandler.post { onComplete(results) }
        }
    }

    private fun runPairedBenchmarkTaskBlocking(
        taskId: String,
        inputText: String,
        coldStart: Boolean,
    ): PairedBenchmarkTaskResult {
        return if (pairedBenchmarkSequence++ % 2L == 0L) {
            val transformer = runCatching {
                conversationResponseEngine.benchmarkTransformerOnly(inputText, coldStart)
            }
            val ans100 = runCatching { ans100BenchmarkRuntime.runTask(taskId, inputText, null, coldStart) }
            PairedBenchmarkTaskResult(taskId, transformer, ans100)
        } else {
            val ans100 = runCatching { ans100BenchmarkRuntime.runTask(taskId, inputText, null, coldStart) }
            val transformer = runCatching {
                conversationResponseEngine.benchmarkTransformerOnly(inputText, coldStart)
            }
            PairedBenchmarkTaskResult(taskId, transformer, ans100)
        }
    }

    fun runNeuronComputeBenchmark() {
        if (neuronComputeBenchmarkBusyState.value) return
        neuronComputeBenchmarkBusyState.value = true
        neuronComputeBenchmarkStatusState.value = "Подготовка CPU, GPU и гибридного теста…"
        neuronComputeBenchmarkReportState.value = null
        neuronComputeBenchmarkExecutor.execute {
            try {
                val report = org.jarvis.ans100.alpha.data.AndroidNeuronKernelComputeBenchmark(appContext).use {
                    it.run()
                }
                mainHandler.post {
                    neuronComputeBenchmarkReportState.value = report
                    neuronComputeBenchmarkBusyState.value = false
                    neuronComputeBenchmarkStatusState.value =
                        "Тест завершён. Все опубликованные результаты прошли проверку совпадения с CPU."
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    neuronComputeBenchmarkBusyState.value = false
                    neuronComputeBenchmarkStatusState.value =
                        "Тест не завершён: ${error.message ?: error.javaClass.simpleName}"
                }
            }
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
        val searchCapabilityQuestion = AndroidWebSearchIntent.isCapabilityQuestion(clean)
        val dialogueGeneration = conversationGeneration + 1L
        conversationGeneration = dialogueGeneration
        latestHybridFeedback = null
        latestNeuralAssociationFeedback = null
        pendingNeuralAssociationFeedbackUi = null
        latestCanonicalReadoutFeedback = null
        pendingCanonicalReadoutFeedbackUi = null
        mutableState.value = before.copy(
            conversation = before.conversation.copy(
                isBusy = true,
                error = null,
                streamingResponse = "",
                hybridFeedbackAvailable = false,
                hybridFeedbackMessage = null,
                neuralAssociationFeedback = null,
                canonicalReadoutFeedback = null,
            ),
        )
        if (searchIntent != null || searchCapabilityQuestion) {
            conversationExecutor.execute {
                try {
                    val response = if (searchIntent != null) {
                        val results = androidWebSearch.search(searchIntent.query)
                        formatAndroidWebSearchReply(searchIntent.query, results)
                    } else {
                        AndroidWebSearchIntent.capabilityReply()
                    }
                    val messages = conversationCoordinator.sendWithExternalResult(clean, response)
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
                // The eight-population ANS-100 graph is the only dialogue neural network.
                val hybridDialogueAnalysis = if (!clean.startsWith("/")) {
                    runHybridDialoguePass(clean, before.conversation.messages)
                } else null
                val neuralAnalysisForModel = hybridDialogueAnalysis ?: DialogueNeuralAnalysis(
                    networkNeuronCount = 0,
                    activeFeatureCount = 0,
                    processedEvents = 0,
                    processedNeuronBatches = 0,
                    outputSpikeCount = 0,
                    pendingEvents = 0,
                    classifierLabel = null,
                    classifierConfidenceMargin = null,
                    classifierTrainedExamples = 0,
                    statePersisted = false,
                )
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
                        if (conversationGeneration == dialogueGeneration) {
                            val current = mutableState.value
                            mutableState.value = current.copy(
                                conversation = current.conversation.copy(streamingResponse = partial),
                            )
                        }
                    }
                }
                val messages = try {
                    conversationCoordinator.send(clean, signalContext, neuralAnalysisForModel)
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
                            hybridNetworkSummary = if (clean.startsWith("/")) {
                                current.conversation.hybridNetworkSummary
                            } else {
                                hybridDialogueAnalysis?.hybridTelemetrySummary
                                    ?: "Гибридный проход не завершён; ответ продолжен без решения ANS-100."
                            },
                            hybridNetworkStats = if (clean.startsWith("/")) {
                                current.conversation.hybridNetworkStats
                            } else hybridDialogueAnalysis?.hybridNetworkStats,
                            hybridPopulationActivity = if (clean.startsWith("/")) {
                                current.conversation.hybridPopulationActivity
                            } else hybridDialogueAnalysis?.hybridPopulationActivity.orEmpty(),
                            hybridFeedbackAvailable = latestHybridFeedback != null,
                            hybridFeedbackMessage = null,
                            neuralAssociationFeedback = pendingNeuralAssociationFeedbackUi,
                            canonicalReadoutFeedback = pendingCanonicalReadoutFeedbackUi,
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

    /**
     * Applies one explicit rating to the latest eligible ANS-100 answer trace.
     * A rating is consumed once; model text and implicit behavior never reward it.
     */
    /**
     * Submits a decision made through an explicit human-review UI.
     * The packet must be the exact packet that was displayed to the reviewer.
     */
    fun submitManualHypothesisReview(
        packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet,
        decision: ANS100HypothesisManualReviewDecision.Decision,
    ): Boolean = runCatching {
        manualHypothesisReviewCoordinator.recordAndApply(packet, decision)
    }.getOrDefault(false)

    /** Retries durable manual decisions that have not yet reached the revision checkpoint. */
    fun retryPendingManualHypothesisReviews(): ANS100HypothesisManualReviewCoordinator.ReplayReport =
        manualHypothesisReviewCoordinator.replayPending()

    /** Bounded audit history for a future manual-review screen. */
    fun loadManualHypothesisReviewHistory(
        limit: Int = ANS100HypothesisManualReviewLedger.DEFAULT_HISTORY_LIMIT,
    ): List<ANS100HypothesisManualReviewLedger.Entry> = runCatching {
        manualHypothesisReviewLedger.loadRecent(limit)
    }.getOrDefault(emptyList())

    fun rateLatestHybridAnswer(positive: Boolean) {
        val trace = latestHybridFeedback ?: return
        latestHybridFeedback = null
        val before = mutableState.value
        mutableState.value = before.copy(conversation = before.conversation.copy(
            hybridFeedbackAvailable = false,
            hybridFeedbackMessage = "Сохраняю оценку…",
        ))
        hybridNetworkExecutor.execute {
            val updated = runCatching {
                hybridNeuronNetwork.applyHybridCandidateFeedback(
                    turnId = trace.first,
                    candidateId = trace.second,
                    reward = if (positive) 1.0 else -1.0,
                    source = org.jarvis.ans100.alpha.data.ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                )
            }.getOrDefault(0)
            mainHandler.post {
                val current = mutableState.value
                mutableState.value = current.copy(conversation = current.conversation.copy(
                    hybridFeedbackAvailable = false,
                    hybridFeedbackMessage = if (updated > 0) {
                        "Оценка ответа сохранена: обновлено синапсов — $updated."
                    } else {
                        "Оценка ответа принята, но новых следов для обучения нет."
                    },
                ))
            }
        }
    }

    /**
     * Explicitly teaches whether the two displayed concepts are related.
     * This is intentionally separate from rating the assistant's answer.
     */
    fun rateNeuralAssociationCandidate(candidateId: String, positive: Boolean) {
        val trace = latestNeuralAssociationFeedback ?: return
        if (trace.second != candidateId) return
        latestNeuralAssociationFeedback = null
        pendingNeuralAssociationFeedbackUi = null
        mutableState.value = mutableState.value.copy(
            conversation = mutableState.value.conversation.copy(
                neuralAssociationFeedback = null,
                hybridFeedbackMessage = "Сохраняю оценку связи понятий…",
            ),
        )
        hybridNetworkExecutor.execute {
            val updated = runCatching {
                hybridNeuronNetwork.applyNeuralAssociationFeedback(
                    feedbackId = trace.third,
                    turnId = trace.first,
                    candidateId = trace.second,
                    reward = if (positive) 1.0 else -1.0,
                    source = org.jarvis.ans100.alpha.data.ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                )
            }.getOrDefault(0)
            mainHandler.post {
                val current = mutableState.value
                mutableState.value = current.copy(conversation = current.conversation.copy(
                    neuralAssociationFeedback = null,
                    hybridFeedbackMessage = if (updated > 0) {
                        "Связь понятий сохранена: обновлено синапсов — $updated."
                    } else {
                        "Оценка связи принята, но подходящих следов для обучения нет."
                    },
                ))
            }
        }
    }

    /**
     * Explicitly labels the latest isolated neural readout trace. The selected
     * class is training supervision, never a model-generated reward signal.
     */
    fun rateCanonicalReadout(correctAnswer: String) {
        val trace = latestCanonicalReadoutFeedback ?: return
        val answer = ANS100CanonicalReadoutContract.Answer.values()
            .firstOrNull { it.wireValue == correctAnswer } ?: return
        latestCanonicalReadoutFeedback = null
        pendingCanonicalReadoutFeedbackUi = null
        mutableState.value = mutableState.value.copy(
            conversation = mutableState.value.conversation.copy(
                canonicalReadoutFeedback = null,
                hybridFeedbackMessage = "Сохраняю метку для нейронного считывателя…",
            ),
        )
        hybridNetworkExecutor.execute {
            val updated = runCatching {
                hybridNeuronNetwork.applyCanonicalReadoutFeedback(
                    feedbackId = trace.second,
                    turnId = trace.first,
                    answer = answer,
                    source = org.jarvis.ans100.alpha.data.ANS100CandidateFeedbackLearner.FeedbackSource.EXPLICIT_USER,
                )
            }.getOrDefault(0)
            val total = runCatching { hybridNeuronNetwork.canonicalReadoutSynapseCount() }.getOrDefault(0)
            mainHandler.post {
                val current = mutableState.value
                mutableState.value = current.copy(conversation = current.conversation.copy(
                    canonicalReadoutFeedback = null,
                    hybridFeedbackMessage = if (updated > 0) {
                        "Метка сохранена: обновлено синапсов — $updated; всего в считывателе — $total."
                    } else {
                        "Метка принята, но пригодных нейронных следов для обучения нет."
                    },
                ))
            }
        }
    }

    /**
     * Runs the eight-population graph, applies a conservative population gate,
     * and prepares the evidence-bound decision package consumed by Qwen.
     * Core still proposes the candidate; neural activity can only validate a
     * complete bounded path or force abstention, not claim semantic certainty.
     */
    private fun runHybridDialoguePass(
        message: String,
        recentHistory: List<LocalConversationMessage>,
    ): DialogueNeuralAnalysis? {
        if (message.isBlank() || message.length > TypedEvidenceEncoder.MAX_RAW_MESSAGE_CHARS) return null
        return try {
            hybridNetworkExecutor.submit<DialogueNeuralAnalysis?> {
                val neuralPassStartedAtNs = System.nanoTime()
                try {
                    var prior = hybridNeuronNetwork.stats()
                    // Drain a legacy V4 queue before the V4->V5 migration. Queued
                    // legacy events may still target the range now reserved for
                    // canonical outputs; migrating first would contaminate them.
                    var drainPasses = 0
                    while (prior.pendingEvents > 0 && drainPasses < MAX_HYBRID_DIALOGUE_MAX_PASSES) {
                        hybridNeuronNetwork.run(
                            maxEvents = MAX_HYBRID_DIALOGUE_EVENTS_PER_PASS,
                            maxTimestamps = MAX_HYBRID_DIALOGUE_TIMESTAMPS_PER_PASS,
                        )
                        prior = hybridNeuronNetwork.stats()
                        drainPasses++
                    }
                    if (prior.pendingEvents > 0) return@submit null
                    hybridNeuronNetwork.initializeHybridTopologyV2()
                    prior = hybridNeuronNetwork.stats()
                    if (prior.currentTimeNs > Long.MAX_VALUE - HYBRID_DIALOGUE_INPUT_STEP_NS) {
                        return@submit null
                    }
                    val turnId = "hybrid-" + UUID.randomUUID()
                    val understanding = LocalUnderstandingPipeline().analyze(
                        message,
                        recentHistory.takeLast(8),
                    )
                    val evidence = TypedEvidenceEncoder().encode(
                        TypedEvidenceInput(
                            turnId = turnId,
                            rawUserMessage = message,
                            understanding = understanding,
                        ),
                    )
                    val evidenceBatch = AndroidHybridEvidenceInputAdapter.createBatch(
                        evidence,
                        prior.currentTimeNs + HYBRID_DIALOGUE_INPUT_STEP_NS,
                    )
                    // Only explicitly approved workspace entries in the dedicated
                    // concept category are eligible for lexical concept activation.
                    val conceptInput = workspaceConceptInputBridge.merge(
                        evidenceBatch = evidenceBatch,
                        rawUserMessage = message,
                        workspaceMemories = workspaceMemoryCache.snapshot().records,
                        neuralInputSlotResolver = hybridNeuronNetwork::resolveConceptInputSlots,
                    )
                    val batch = conceptInput.batch
                    if (!hybridNeuronNetwork.beginHybridTurn(batch)) return@submit null

                    var result = hybridNeuronNetwork.run(
                        maxEvents = MAX_HYBRID_DIALOGUE_EVENTS_PER_PASS,
                        maxTimestamps = MAX_HYBRID_DIALOGUE_TIMESTAMPS_PER_PASS,
                    )
                    var totalProcessedEvents = result.processedEvents
                    val emittedSpikeNeuronIds = LinkedHashSet<Int>().apply {
                        addAll(result.emittedSpikes.map { it.first })
                    }
                    val coreTaintedNeuronIds = LinkedHashSet<Int>().apply {
                        addAll(result.coreTaintedNeuronIds)
                    }
                    val activatedConnectionIds = LinkedHashSet<String>().apply {
                        addAll(result.activatedConnectionIds)
                    }
                    var passes = 1
                    while (result.pendingEvents > 0 && passes < MAX_HYBRID_DIALOGUE_MAX_PASSES) {
                        result = hybridNeuronNetwork.run(
                            maxEvents = MAX_HYBRID_DIALOGUE_EVENTS_PER_PASS,
                            maxTimestamps = MAX_HYBRID_DIALOGUE_TIMESTAMPS_PER_PASS,
                        )
                        totalProcessedEvents += result.processedEvents
                        emittedSpikeNeuronIds.addAll(result.emittedSpikes.map { it.first })
                        coreTaintedNeuronIds.addAll(result.coreTaintedNeuronIds)
                        activatedConnectionIds.addAll(result.activatedConnectionIds)
                        passes++
                    }
                    val telemetry = result.hybridTelemetry ?: return@submit null
                    // Read authoritative SQLite counters after the bounded run; do not infer activity from topology size.
                    val hybridNetworkStats = hybridNeuronNetwork.stats()
                    // Decode only associations supported by actual shared spikes
                    // in the general association population; no symbolic relation
                    // hypotheses are fed into this neural candidate path.
                    val learnedAssociationConnections =
                        hybridNeuronNetwork.structuralAssociationConnections(
                            batch.conceptTrace.map { it.neuronId },
                        )

                    // Structural growth is intentionally disabled by default.
                    // When enabled for a measured experiment, only concepts whose
                    // input neurons spiked and propagated an association edge are
                    // recorded, and incomplete/budget-exhausted turns are ignored.
                    if (STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED &&
                        !result.budgetExhausted && result.pendingEvents == 0
                    ) {
                        runCatching {
                            val propagatedConcepts = batch.conceptTrace.filter { trace ->
                                if (trace.neuronId !in emittedSpikeNeuronIds) return@filter false
                                val fixedPaths = AndroidHybridTopologyV2
                                    .conceptAssociationProjection(trace.neuronId).values
                                val grownPaths = learnedAssociationConnections
                                    .asSequence()
                                    .filter { it.sourceNeuronId == trace.neuronId }
                                    .map { it.connectionId }
                                (fixedPaths.asSequence() + grownPaths)
                                    .any { it in activatedConnectionIds }
                            }.map { trace ->
                                org.jarvis.ans100.alpha.data.ANS100ActivityDrivenStructuralPlasticity.ActiveConcept(
                                    conceptId = trace.conceptId,
                                    neuronId = trace.neuronId,
                                    label = trace.matchedAlias,
                                    provenanceRef = trace.provenanceRef,
                                )
                            }
                            val snapshots = hybridNeuronNetwork.recordStructuralPlasticityTurn(
                                turnId = turnId,
                                activeConcepts = propagatedConcepts,
                            )
                            val sourceIds = snapshots.flatMap { snapshot ->
                                snapshot.activeConcepts.map { it.neuronId }
                            }.distinct().sorted()
                            val graph = hybridNeuronNetwork.structuralPlasticityGraphSnapshot(sourceIds)
                            val learner = org.jarvis.ans100.alpha.data.ANS100ActivityDrivenStructuralPlasticity(
                                maxGrownPairs = org.jarvis.ans100.alpha.data.ANS100ActivityDrivenStructuralPlasticity.MAX_GROWN_PAIRS_HARD_LIMIT,
                                maxGrownEdgesPerConcept = org.jarvis.ans100.alpha.data.ANS100ActivityDrivenStructuralPlasticity.MAX_GROWN_EDGES_PER_CONCEPT_HARD_LIMIT,
                            )
                            learner.restoreGrownState(graph.grownConceptPairs, graph.grownEdgesByConcept)
                            val knownTargets = graph.existingTargetsByConceptNeuron
                                .mapValues { it.value.toMutableSet() }.toMutableMap()
                            val incomingLoads = graph.targetIncomingEdgeCounts.toMutableMap()
                            snapshots.forEach { snapshot ->
                                val observation = learner.observeTurn(
                                    turnId = snapshot.turnId,
                                    activeConcepts = snapshot.activeConcepts,
                                    existingTargetsByConceptNeuron = knownTargets,
                                    targetIncomingEdgeCounts = incomingLoads,
                                )
                                observation.emittedProposals.forEach { proposal ->
                                    val inserted = hybridNeuronNetwork.addStructuralGrowthProposal(proposal)
                                    if (inserted == 2) {
                                        proposal.connections.forEach { connection ->
                                            knownTargets.getOrPut(connection.sourceNeuronId) { LinkedHashSet() }
                                                .add(connection.targetNeuronId)
                                        }
                                        incomingLoads[proposal.targetNeuronId] =
                                            (incomingLoads[proposal.targetNeuronId] ?: 0) + 2
                                    }
                                }
                            }
                        }
                    }

                    val canonicalReadout = ANS100CanonicalReadoutDecoder().decode(
                        emittedSpikeNeuronIds,
                        coreTaintedNeuronIds,
                    )
                    latestCanonicalReadoutFeedback = null
                    pendingCanonicalReadoutFeedbackUi = null
                    val canonicalSourceSpikes = emittedSpikeNeuronIds
                        .filterNot(coreTaintedNeuronIds::contains)
                        .filter(ANS100CanonicalReadoutContract::isEligibleSourceNeuron)
                        .sorted()
                        .take(ANS100CanonicalReadoutContract.MAX_SOURCE_NEURONS_PER_TURN)
                    val canonicalTraceSaved = if (!result.budgetExhausted && result.pendingEvents == 0 &&
                        canonicalSourceSpikes.isNotEmpty()
                    ) runCatching {
                        hybridNeuronNetwork.recordCanonicalReadoutEligibility(turnId, canonicalSourceSpikes)
                    }.getOrDefault(0) else 0
                    if (canonicalTraceSaved > 0) {
                        latestCanonicalReadoutFeedback = turnId to "canonical-feedback-" + UUID.randomUUID()
                        val classCounts = canonicalReadout.spikeCounts.entries
                            .sortedBy { it.key.ordinal }
                            .joinToString(" · ") { "${it.key.wireValue}=${it.value}" }
                        pendingCanonicalReadoutFeedbackUi = CanonicalReadoutFeedbackUi(
                            predictedAnswer = canonicalReadout.answer?.wireValue ?: canonicalReadout.status.name,
                            spikeCountsSummary = classCounts,
                        )
                    }

                    val neuralAssociationResult = ANS100NeuralAssociationDecoder().decode(
                        conceptTrace = batch.conceptTrace,
                        emittedSpikeNeuronIds = emittedSpikeNeuronIds,
                        activatedConnectionIds = activatedConnectionIds,
                        grownConnections = learnedAssociationConnections,
                    )
                    val neuralHypotheses = neuralHypothesisEngine.observeTurn(
                        turnId = turnId,
                        activeConceptIds = neuralAssociationResult.observedConceptIds.toSet(),
                        candidates = neuralAssociationResult.candidates,
                        completed = !result.budgetExhausted && result.pendingEvents == 0,
                        pairScanComplete = neuralAssociationResult.pairScanComplete &&
                            neuralAssociationResult.candidateSetComplete,
                    )
                    // Revise symbolic causal-chain candidates only after a complete
                    // neural pass. Premise edges stay provenance; only exact direct
                    // relation observations can support or refute the proposed edge.
                    val structuredHypothesisRevisions = structuredHypothesisRevisionEngine.observeTurn(
                        turnId = turnId,
                        candidates = conceptInput.causalChainHypotheses.map { candidate ->
                            ANS100ConceptMemory.Hypothesis(
                                id = candidate.hypothesisId,
                                fromConceptId = candidate.fromConceptId,
                                type = candidate.relationType,
                                toConceptId = candidate.toConceptId,
                                premiseRelationIds = candidate.premiseRelationIds,
                                evidenceRefs = candidate.premiseEvidenceRefs,
                                estimatedStrength = candidate.estimatedStrength,
                            )
                        },
                        directEvidence = conceptInput.relationObservations,
                        completed = !result.budgetExhausted && result.pendingEvents == 0,
                    )
                    // A failed checkpoint must not fail the user-facing turn.
                    val structuredHypothesisStatePersisted = runCatching {
                        structuredHypothesisRevisionLedger.save(
                            structuredHypothesisRevisionEngine.exportSnapshot(),
                        )
                    }.getOrDefault(false)
                    val structuredHypothesisReviewQueue =
                        structuredHypothesisRevisionEngine.reviewQueue()
                    // A failed checkpoint must never fail the user-facing turn.
                    val neuralHypothesisStatePersisted = runCatching {
                        neuralHypothesisLedger.save(neuralHypothesisEngine.exportSnapshot())
                    }.getOrDefault(false)
                    val hypothesisComparisons = ANS100HypothesisCompetitionEngine().compare(
                        neuralCandidates = neuralAssociationResult.candidates,
                        memoryHypotheses = conceptInput.hypothesisReports,
                    )
                    val decision = ANS100HybridPopulationController().decide(
                        evidence = evidence,
                        understanding = understanding,
                        telemetry = telemetry,
                        emittedSpikeNeuronIds = emittedSpikeNeuronIds,
                        processedEventCount = totalProcessedEvents,
                        pendingEvents = result.pendingEvents,
                        budgetExhausted = result.budgetExhausted,
                    )
                    // Capture the real Android pass. The trace adapter hashes the
                    // message and never writes raw text, expected answers or labels.
                    val benchmarkTraceStored = runCatching {
                        val trace = ANS100BenchmarkTraceAdapter.create(
                            taskId = null,
                            inputText = message,
                            evidence = evidence,
                            decision = decision,
                            telemetry = telemetry,
                            emittedSpikeNeuronIds = emittedSpikeNeuronIds,
                            activatedConnectionIds = activatedConnectionIds,
                            associationResult = neuralAssociationResult,
                            inferenceDurationNs = (System.nanoTime() - neuralPassStartedAtNs).coerceAtLeast(0L),
                            neuralHypotheses = neuralHypotheses,
                            memoryHypothesisReports = conceptInput.hypothesisReports,
                            hypothesisComparisons = hypothesisComparisons,
                            hypothesisStatePersisted = neuralHypothesisStatePersisted,
                            canonicalReadout = canonicalReadout,
                        )
                        benchmarkTraceStore.append(ANS100BenchmarkTraceAdapter.toJsonLine(trace))
                    }.getOrDefault(false)
                    // Persist eligibility only for the selected candidate and observed
                    // propagated events delivered to a target that itself spiked.
                    val selectedCandidateId = decision.selectedCandidateId
                    if (selectedCandidateId != null) {
                        val slot = ANS100CandidateSlotContract.slotForCandidateId(selectedCandidateId)
                        val laneMarker = slot?.name?.lowercase()?.let { "-$it-" }
                        val candidateConnections = if (laneMarker == null) emptyList() else {
                            activatedConnectionIds.filter { it.contains(laneMarker) &&
                                !it.startsWith("ans100-v4-candidate-input-") }.take(2_000)
                        }
                        if (candidateConnections.isNotEmpty()) {
                            val saved = hybridNeuronNetwork.recordHybridCandidateEligibility(
                                turnId = turnId,
                                candidateId = selectedCandidateId,
                                connectionIds = candidateConnections,
                                eligibility = 1.0,
                            )
                            if (saved > 0) latestHybridFeedback = turnId to selectedCandidateId
                        }
                    }
                    val handoff = ANS100QwenHandoffBuilder().build(
                        originalUserMessage = message,
                        understanding = understanding,
                        evidence = evidence,
                        decision = decision,
                        relationHypotheses = conceptInput.hypothesisReports,
                        neuralAssociations = neuralAssociationResult.candidates,
                    )
                    // Save one bounded neural-association trace for explicit concept-pair rating.
                    // The model's generated wording is never used as a teaching signal.
                    latestNeuralAssociationFeedback = null
                    pendingNeuralAssociationFeedbackUi = null
                    neuralAssociationResult.candidates.firstOrNull()?.let { candidate ->
                        val saved = hybridNeuronNetwork.recordNeuralAssociationEligibility(
                            turnId = turnId,
                            candidateId = candidate.candidateId,
                            connectionIds = candidate.activatedConnectionIds,
                            eligibility = candidate.activationHint,
                        )
                        if (saved == candidate.activatedConnectionIds.size) {
                            latestNeuralAssociationFeedback = Triple(
                                turnId, candidate.candidateId, "na-feedback-" + UUID.randomUUID(),
                            )
                            pendingNeuralAssociationFeedbackUi = NeuralAssociationFeedbackUi(
                                candidateId = candidate.candidateId,
                                firstLabel = candidate.firstLabel,
                                secondLabel = candidate.secondLabel,
                            )
                        }
                    }
                    val telemetrySummary = buildString {
                        append("topology=").append(telemetry.topologyId)
                        append(" v").append(telemetry.topologyVersion)
                        append("; neurons=").append(AndroidHybridTopologyV2.NEURON_COUNT)
                        append("; events=").append(totalProcessedEvents)
                        append("; passes=").append(passes)
                        append("; pending=").append(result.pendingEvents)
                        append("; approved_concepts=").append(conceptInput.eligibleConceptCount)
                        append("; active_concept_inputs=").append(conceptInput.activeConceptCount)
                        append("; skipped_approved_memory_entries=").append(conceptInput.skippedConceptCount)
                        append("; approved_relations=").append(conceptInput.eligibleRelationCount)
                        append("; learned_relations=").append(conceptInput.learnedRelationCount)
                        append("; active_relations=").append(conceptInput.activeRelationCount)
                        append("; relation_hypotheses=").append(conceptInput.hypotheses.size)
                        append("; unresolved_relation_hypotheses=").append(conceptInput.unresolvedHypothesisCount)
                        append("; causal_chain_candidates=").append(conceptInput.causalChainHypotheses.size)
                        append("; causal_chain_unresolved=").append(conceptInput.causalChainHypotheses.count { it.status == org.jarvis.ans100.alpha.data.ANS100CausalHypothesisEngine.Status.UNRESOLVED })
                        append("; causal_chain_supported_by_stored_records=").append(conceptInput.causalChainHypotheses.count { it.status == org.jarvis.ans100.alpha.data.ANS100CausalHypothesisEngine.Status.SUPPORTED_BY_STORED_RECORDS })
                        append("; causal_chain_refuted_by_stored_records=").append(conceptInput.causalChainHypotheses.count { it.status == org.jarvis.ans100.alpha.data.ANS100CausalHypothesisEngine.Status.REFUTED_BY_STORED_RECORDS })
                        append("; causal_chain_conflicting_records=").append(conceptInput.causalChainHypotheses.count { it.status == org.jarvis.ans100.alpha.data.ANS100CausalHypothesisEngine.Status.CONFLICTING_STORED_RECORDS })
                        append("; structured_hypothesis_revisions=").append(structuredHypothesisRevisions.size)
                        append("; structured_hypothesis_supported=").append(structuredHypothesisRevisions.count { it.status == ANS100HypothesisRevisionEngine.Status.SUPPORTED_BY_DIRECT_EVIDENCE })
                        append("; structured_hypothesis_refuted=").append(structuredHypothesisRevisions.count { it.status == ANS100HypothesisRevisionEngine.Status.REFUTED_BY_DIRECT_EVIDENCE })
                        append("; structured_hypothesis_conflicted=").append(structuredHypothesisRevisions.count { it.status == ANS100HypothesisRevisionEngine.Status.CONFLICTED_DIRECT_EVIDENCE })
                        append("; structured_hypothesis_review_requests=").append(structuredHypothesisReviewQueue.size)
                        append("; structured_hypothesis_state_persisted=").append(structuredHypothesisStatePersisted)
                        append("; explicit_relation_evidence=").append(conceptInput.relationEvidenceCount)
                        append("; supported_relation_hypotheses=").append(conceptInput.hypothesisVerifications.count { it.status == org.jarvis.ans100.alpha.data.ANS100ConceptMemory.VerificationStatus.SUPPORTED })
                        append("; refuted_relation_hypotheses=").append(conceptInput.hypothesisVerifications.count { it.status == org.jarvis.ans100.alpha.data.ANS100ConceptMemory.VerificationStatus.REFUTED })
                        append("; conflicting_relation_hypotheses=").append(conceptInput.hypothesisVerifications.count { it.status == org.jarvis.ans100.alpha.data.ANS100ConceptMemory.VerificationStatus.CONFLICTING })
                        append("; canonical_readout_status=").append(canonicalReadout.status)
                        append("; canonical_readout_answer=").append(canonicalReadout.answer?.wireValue ?: "ABSTAIN")
                        append("; canonical_readout_training_sources=").append(canonicalTraceSaved)
                        append("; canonical_readout_synapses=").append(runCatching { hybridNeuronNetwork.canonicalReadoutSynapseCount() }.getOrDefault(0))
                        append("; neural_association_active_concepts=").append(neuralAssociationResult.activeConceptCount)
                        append("; neural_association_spikes=").append(neuralAssociationResult.associationSpikeCount)
                        append("; neural_association_pairs_examined=").append(neuralAssociationResult.consideredPairCount)
                        append("; neural_association_candidates=").append(neuralAssociationResult.candidates.size)
                        append("; neural_hypotheses=").append(neuralHypotheses.size)
                        append("; neural_hypotheses_reproduced=").append(neuralHypotheses.count { it.status == org.jarvis.ans100.alpha.data.ANS100NeuralHypothesisEngine.Status.REPRODUCED_ON_INDEPENDENT_TURN })
                        append("; neural_hypotheses_mixed_signal=").append(neuralHypotheses.count { it.status == org.jarvis.ans100.alpha.data.ANS100NeuralHypothesisEngine.Status.MIXED_REPRODUCTION_SIGNAL })
                        append("; neural_hypotheses_not_reproduced=").append(neuralHypotheses.count { it.status == org.jarvis.ans100.alpha.data.ANS100NeuralHypothesisEngine.Status.NOT_REPRODUCED_ON_INDEPENDENT_TURN })
                        append("; neural_memory_comparisons=").append(hypothesisComparisons.size)
                        append("; neural_hypothesis_state_persisted=").append(neuralHypothesisStatePersisted)
                        append("; comparison_memory_unresolved=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.MEMORY_PATH_UNRESOLVED })
                        append("; comparison_user_support=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.USER_ASSERTIONS_SUPPORT })
                        append("; comparison_user_counter=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.USER_COUNTER_EVIDENCE })
                        append("; comparison_mixed_user_assertions=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.MIXED_USER_ASSERTIONS })
                        append("; comparison_verified_tool_support=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.VERIFIED_TOOL_SUPPORT })
                        append("; comparison_verified_tool_counter=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.VERIFIED_TOOL_COUNTER_EVIDENCE })
                        append("; comparison_mixed_source_support=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.MIXED_SOURCE_SUPPORT })
                        append("; comparison_mixed_source_counter=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.MIXED_SOURCE_COUNTER_EVIDENCE })
                        append("; comparison_unclassified_evidence=").append(hypothesisComparisons.count { it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.UNCLASSIFIED_SUPPORT || it.status == org.jarvis.ans100.alpha.data.ANS100HypothesisCompetitionEngine.Status.UNCLASSIFIED_COUNTER_EVIDENCE })
                        append("; benchmark_trace_saved=").append(benchmarkTraceStored)
                        append("; budgetExhausted=").append(result.budgetExhausted)
                        append("; populations=")
                        append(telemetry.populations.joinToString(" | ") { activity ->
                            activity.populationId + ":received=" + activity.receivedEventCount +
                                ",processed=" + activity.uniqueNeuronsProcessed +
                                ",spiked=" + activity.uniqueSpikingNeurons +
                                ",spikes=" + activity.spikeCount +
                                ",out=" + activity.outgoingEventCount
                        })
                        append("; decision=").append(decision.status)
                        append("; observed_eligible_synapses=").append(activatedConnectionIds.size)
                        decision.responseConstraints.firstOrNull { it.startsWith("Сравнение readout:") }?.let {
                            append("; ").append(it)
                        }
                        append("; producer=").append(decision.decisionProducer)
                    }
                    DialogueNeuralAnalysis(
                        // Legacy four-output fields deliberately remain zero: this
                        // object describes the hybrid graph, not the retired graph.
                        networkNeuronCount = 0,
                        activeFeatureCount = 0,
                        processedEvents = 0,
                        processedNeuronBatches = 0,
                        outputSpikeCount = 0,
                        pendingEvents = 0,
                        classifierLabel = null,
                        classifierConfidenceMargin = null,
                        classifierTrainedExamples = 0,
                        statePersisted = false,
                        hybridTelemetrySummary = telemetrySummary,
                        hybridNetworkStats = hybridNetworkStats,
                        hybridPopulationActivity = telemetry.populations,
                        hybridQwenHandoff = handoff,
                    )
                } catch (_: Exception) {
                    null
                }
            }.get()
        } catch (_: Exception) {
            null
        }
    }

    fun clearConversation() {
        latestHybridFeedback = null
        latestNeuralAssociationFeedback = null
        pendingNeuralAssociationFeedbackUi = null
        latestCanonicalReadoutFeedback = null
        pendingCanonicalReadoutFeedbackUi = null
        val before = mutableState.value
        if (before.conversation.isBusy || before.conversation.messages.isEmpty()) return
        mutableState.value = before.copy(
            conversation = before.conversation.copy(
                isBusy = true,
                error = null,
                canonicalReadoutFeedback = null,
                neuralAssociationFeedback = null,
                hybridFeedbackAvailable = false,
            ),
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
                            hybridFeedbackAvailable = false,
                            hybridFeedbackMessage = null,
                            neuralAssociationFeedback = null,
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

    /** Refreshes the bounded review queue and the durable human-decision journal. */
    fun refreshHypothesisReview() {
        val before = mutableState.value
        val refreshed = runCatching {
            HypothesisReviewUiState(
                requests = structuredHypothesisRevisionEngine.reviewQueue(),
                recentDecisions = manualHypothesisReviewLedger.loadRecent(limit = 40),
                isLoaded = true,
            )
        }.getOrElse { error ->
            before.hypothesisReview.copy(
                isLoaded = true,
                error = error.message ?: "Не удалось загрузить очередь проверки гипотез.",
            )
        }
        mutableState.value = mutableState.value.copy(hypothesisReview = refreshed)
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
        private const val MAX_BENCHMARK_BATCH_SIZE = 1000
        private const val TERMUX_PACKAGE_NAME = "com.termux"
        private const val HYBRID_DIALOGUE_INPUT_STEP_NS = 10_000_000L
        private const val MAX_HYBRID_DIALOGUE_EVENTS_PER_PASS = 4_096
        private const val MAX_HYBRID_DIALOGUE_TIMESTAMPS_PER_PASS = 128
        private const val MAX_HYBRID_DIALOGUE_MAX_PASSES = 4
        private const val STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED = false
    }

    override fun onCleared() {
        appContext.unregisterComponentCallbacks(modelMemoryCallbacks)
        // A ViewModel can be cleared while the HTTP download is active.
        // Disconnect it first so the worker can finish and close the model engine.
        modelStore.cancelDownload()
        conversationExecutor.execute {
            conversationResponseEngine.close()
            if (ans100BenchmarkRuntimeDelegate.isInitialized()) {
                runCatching { ans100BenchmarkRuntime.close() }
            }
        }
        hybridNetworkExecutor.execute {
            if (hybridNeuronNetworkDelegate.isInitialized()) {
                runCatching { hybridNeuronNetwork.close() }
            }
        }
        conversationExecutor.shutdown()
        hybridNetworkExecutor.shutdown()
        neuronComputeBenchmarkExecutor.shutdownNow()
        runtimeExecutor.shutdownNow()
        peerSyncExecutor.shutdownNow()
        super.onCleared()
    }
}
