package org.jarvis.ans100.alpha

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jarvis.ans100.alpha.data.ConversationRole
import org.jarvis.ans100.alpha.data.LocalSession
import org.jarvis.ans100.alpha.data.LocalSessionStatus
import org.jarvis.ans100.alpha.data.LocalTaskDraft
import org.jarvis.ans100.alpha.data.RuntimePlanningAction
import org.jarvis.ans100.alpha.data.OptionalTermuxExtensionStatus
import org.jarvis.ans100.alpha.data.resolveOptionalTermuxExtensionStatus
import org.jarvis.ans100.alpha.data.RuntimeReasoningPreviewRequest
import org.jarvis.ans100.alpha.data.RuntimeTaskSnapshot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisApp() }
    }
}

@Composable
private fun JarvisApp(viewModel: JarvisViewModel = viewModel()) {
    val ui by viewModel.state.collectAsState()
    var conversationInput by rememberSaveable { mutableStateOf("") }
    var sessionTitle by rememberSaveable { mutableStateOf("") }
    var taskTitle by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var transferPassphrase by remember { mutableStateOf("") }
    var pendingImportPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingBackupExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingBackupExportRevision by remember { mutableStateOf<Long?>(null) }
    var pendingRestorePassphrase by remember { mutableStateOf<CharArray?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var settingsCategory by rememberSaveable { mutableStateOf("appearance") }
    val context = LocalContext.current
    val uiPreferences = remember(context) { context.getSharedPreferences("jarvis_ui_preferences_v1", android.content.Context.MODE_PRIVATE) }
    var accentChoice by remember(uiPreferences) { mutableStateOf(uiPreferences.getString("accent", "blue") ?: "blue") }
    var glowEnabled by remember(uiPreferences) { mutableStateOf(uiPreferences.getBoolean("flowing_glow", true)) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.jarvis.workspace+json"),
    ) { uri: Uri? ->
        val bytes = pendingExportBytes
        if (uri != null && bytes != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } finally {
                bytes.fill(0)
                pendingExportBytes = null
            }
        } else {
            bytes?.fill(0)
            pendingExportBytes = null
        }
    }
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.jarvis.workspace-backup+json"),
    ) { uri: Uri? ->
        val bytes = pendingBackupExportBytes
        val revision = pendingBackupExportRevision
        pendingBackupExportBytes = null
        pendingBackupExportRevision = null
        if (uri != null && bytes != null && revision != null) {
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Не удалось открыть файл backup для записи.")
                output.use { it.write(bytes) }
                viewModel.markWorkspaceBackupSaved(revision)
            } catch (error: Exception) {
                viewModel.markWorkspaceBackupSaveFailed(
                    error.message ?: "Не удалось сохранить файл backup.",
                )
            } finally {
                bytes.fill(0)
            }
        } else {
            bytes?.fill(0)
            viewModel.cancelWorkspaceBackupSave()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val passphrase = pendingRestorePassphrase
        pendingRestorePassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.previewWorkspaceRestore(uri, passphrase)
        } else {
            passphrase?.fill('\u0000')
        }
    }
    val modelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importConversationModel(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val passphrase = pendingImportPassphrase
        pendingImportPassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.previewWorkspaceImport(uri, passphrase)
        } else {
            passphrase?.fill('\u0000')
        }
    }

    val selectedSession = ui.data.sessions.firstOrNull {
        it.id == ui.selectedSessionId
    }
    val sessionTasks = ui.data.tasks.filter { it.sessionId == selectedSession?.id }
    val visibleTasks = sessionTasks.filter {
        search.isBlank() || it.title.contains(search.trim(), ignoreCase = true)
    }
    val selectedTask = ui.data.tasks.firstOrNull { it.id == selectedTaskId }

    val selectedAccentColor = when (accentChoice) { "violet" -> Color(0xFFB18CFF); "mint" -> Color(0xFF52D6B4); else -> Color(0xFF4DA3FF) }
    val jarvisColors = darkColorScheme(
        primary = selectedAccentColor,
        onPrimary = Color(0xFF001B35),
        secondary = selectedAccentColor.copy(alpha = 0.86f),
        tertiary = selectedAccentColor.copy(alpha = 0.72f),
        background = Color(0xFF07111F),
        surface = Color(0xFF0C1B2D),
        surfaceVariant = Color(0xFF122943),
        onSurface = Color(0xFFE7F2FF),
        onSurfaceVariant = Color(0xFFA9C4E2),
        outline = Color(0xFF315579),
    )

    MaterialTheme(colorScheme = jarvisColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    Column(modifier = Modifier.imePadding()) {
                        if (selectedTab == 0) {
                            ConversationComposer(
                                input = conversationInput,
                                onInputChange = { conversationInput = it.take(4_000) },
                                onSend = { message ->
                                    viewModel.sendConversationMessage(message)
                                    conversationInput = ""
                                },
                                isBusy = ui.conversation.isBusy,
                                glowEnabled = glowEnabled,
                            )
                        }
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Text("◉") },
                                label = { Text("Чат") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Text("▤") },
                                label = { Text("Память") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Text("☷") },
                                label = { Text("Задачи") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Text("⚙") },
                                label = { Text("Настройки") },
                            )
                        }
                    }
                },
            ) { contentPadding ->
                Column(
                    modifier = Modifier.fillMaxSize()
                        .padding(contentPadding)
                        .then(if (selectedTab == 0) Modifier else Modifier.verticalScroll(rememberScrollState()))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (selectedTab != 0) Header(isBusy = ui.conversation.isBusy)
                    when (selectedTab) {
                        0 -> {
                            ChatTitleBar(
                                backendLabel = ui.conversation.backendLabel,
                                isBusy = ui.conversation.isBusy,
                                glowEnabled = glowEnabled,
                                onOpenSettings = { selectedTab = 3; settingsCategory = "model" },
                            )
                            ConversationPanel(
                                state = ui.conversation,
                                onClear = viewModel::clearConversation,
                                onRewardDialogFeature = viewModel::applyDialogRewardFeedback,
                                glowEnabled = glowEnabled,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                        1 -> {
                        LocalMemoryPanel(
                            state = ui.localMemory,
                            peerSync = ui.peerMemorySync,
                            onAdd = viewModel::addLocalMemory,
                            onDelete = viewModel::deleteLocalMemory,
                            onPeerSync = viewModel::syncApprovedMemories,
                        )
                        WorkspaceMemoryPanel(
                            state = ui.workspace,
                            onRefresh = viewModel::refreshWorkspace,
                            onAdd = viewModel::addWorkspaceMemory,
                            onDelete = viewModel::deleteWorkspaceMemory,
                            transferPassphrase = transferPassphrase,
                            onPassphraseChange = { transferPassphrase = it.take(256) },
                            onExport = { phrase ->
                                transferPassphrase = ""
                                viewModel.exportWorkspace(phrase.toCharArray()) { bytes ->
                                    pendingExportBytes = bytes
                                    exportLauncher.launch("jarvis-workspace-" +
                                        java.time.LocalDate.now().toString() + ".jws")
                                }
                            },
                            onChooseImport = {
                                if (transferPassphrase.length >= 12) {
                                    pendingImportPassphrase = transferPassphrase.toCharArray()
                                    transferPassphrase = ""
                                    importLauncher.launch(arrayOf(
                                        "application/vnd.jarvis.workspace+json",
                                        "application/json",
                                        "application/octet-stream",
                                        "*/*",
                                    ))
                                }
                            },
                            onConfirmImport = viewModel::confirmWorkspaceImport,
                            onCancelImport = viewModel::cancelWorkspaceImport,
                            onExportBackup = { phrase ->
                                transferPassphrase = ""
                                viewModel.exportWorkspaceBackup(phrase.toCharArray()) { bytes, revision ->
                                    pendingBackupExportBytes = bytes
                                    pendingBackupExportRevision = revision
                                    backupExportLauncher.launch(
                                        "jarvis-workspace-backup-" +
                                            java.time.LocalDate.now().toString() + ".jwb",
                                    )
                                }
                            },
                            onChooseRestore = { phrase ->
                                if (phrase.length >= 12 &&
                                    ui.workspace.backupRevision == ui.workspace.revision &&
                                    ui.workspace.restoreBackupAcknowledged
                                ) {
                                    pendingRestorePassphrase = phrase.toCharArray()
                                    transferPassphrase = ""
                                    restoreLauncher.launch(arrayOf(
                                        "application/vnd.jarvis.workspace-backup+json",
                                        "application/json",
                                        "application/octet-stream",
                                        "*/*",
                                    ))
                                }
                            },
                            onBackupAcknowledgement = viewModel::setRestoreBackupAcknowledged,
                            onConfirmRestore = viewModel::confirmWorkspaceRestore,
                            onCancelRestore = viewModel::cancelWorkspaceRestore,
                        )
                        }
                        2 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MetricCard(
                                title = "СЕССИИ",
                                value = ui.data.sessions.size.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            MetricCard(
                                title = "ЧЕРНОВИКИ",
                                value = ui.data.tasks.size.toString(),
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                SectionHeading("Сессии", "локальный контекст")
                                if (ui.data.sessions.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(ui.data.sessions, key = { it.id }) { session ->
                                            FilterChip(
                                                selected = session.id == ui.selectedSessionId,
                                                onClick = { viewModel.selectSession(session.id) },
                                                label = {
                                                    Text(
                                                        session.title + when (session.status) {
                                                            LocalSessionStatus.OPEN -> ""
                                                            LocalSessionStatus.PAUSED -> " · пауза"
                                                            LocalSessionStatus.CLOSED -> " · закрыта"
                                                        }
                                                    )
                                                },
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "Создайте первую сессию. В ней будут храниться ваши локальные черновики.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                OutlinedTextField(
                                    value = sessionTitle,
                                    onValueChange = { sessionTitle = it.take(240) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Название новой сессии") },
                                    singleLine = true,
                                    enabled = ui.storageReady,
                                )
                                Button(
                                    onClick = {
                                        viewModel.createSession(sessionTitle)
                                        sessionTitle = ""
                                    },
                                    enabled = ui.storageReady && sessionTitle.isNotBlank() &&
                                        ui.data.sessions.size < 32,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Создать сессию")
                                }

                                if (selectedSession != null) {
                                    SessionControls(
                                        session = selectedSession,
                                        onStatus = { status ->
                                            viewModel.setSessionStatus(
                                                selectedSession.id, status, selectedSession.revision,
                                            )
                                        },
                                        enabled = ui.storageReady,
                                    )
                                }
                            }
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SectionHeading("Новая задача", "пока только черновик")
                                OutlinedTextField(
                                    value = taskTitle,
                                    onValueChange = { taskTitle = it.take(240) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Что нужно сделать?") },
                                    placeholder = { Text("Например: составить план проекта") },
                                    minLines = 2,
                                    maxLines = 3,
                                    enabled = ui.storageReady &&
                                        selectedSession?.status == LocalSessionStatus.OPEN,
                                )
                                Button(
                                    onClick = {
                                        viewModel.createTask(taskTitle)
                                        taskTitle = ""
                                    },
                                    enabled = ui.storageReady && taskTitle.isNotBlank() &&
                                        selectedSession?.status == LocalSessionStatus.OPEN &&
                                        ui.data.tasks.size < 500,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Сохранить черновик")
                                }
                                if (selectedSession == null) {
                                    Text(
                                        "Сначала создайте сессию.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (selectedSession.status != LocalSessionStatus.OPEN) {
                                    Text(
                                        "Сессия не открыта. Возобновите её, чтобы добавлять задачи.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Мои задачи", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                "${visibleTasks.size}/${sessionTasks.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it.take(120) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Поиск в выбранной сессии") },
                            singleLine = true,
                        )

                        if (visibleTasks.isEmpty()) {
                            Text(
                                if (sessionTasks.isEmpty()) {
                                    "В этой сессии пока нет черновиков."
                                } else {
                                    "Поиск ничего не нашёл."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(visibleTasks.reversed(), key = { it.id }) { task ->
                                    DraftTaskCard(task = task, onDetails = { selectedTaskId = task.id })
                                }
                            }
                        }

                        Text(
                            "Локальный режим · ничего не отправляется в Python-движок и не исполняется.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
                        else -> {
                            SettingsPanel(
                                selectedCategory = settingsCategory,
                                onCategoryChange = { settingsCategory = it },
                                accentChoice = accentChoice,
                                onAccentChange = { accentChoice = it; uiPreferences.edit().putString("accent", it).apply() },
                                glowEnabled = glowEnabled,
                                onGlowChange = { glowEnabled = it; uiPreferences.edit().putBoolean("flowing_glow", it).apply() },
                                conversation = ui.conversation,
                                onSelectModel = { modelLauncher.launch(arrayOf("*/*")) },
                                onDownloadModel = viewModel::downloadConversationModel,
                                onCancelDownload = viewModel::cancelConversationModelDownload,
                                onRemoveModel = viewModel::removeConversationModel,
                                onTrainDialogFeature = viewModel::trainDialogFeatureClassifier,
                                onInitializeNativeNetwork = viewModel::initializeNativeNeuronNetwork,
                                onRunNativeNetworkProbe = viewModel::runNativeNeuronProbe,
                                storageReady = ui.storageReady, notice = ui.notice, runtime = ui.runtime,
                                onConnect = viewModel::connectRuntime, onRefresh = viewModel::refreshRuntime,
                                onCreateSession = viewModel::createRuntimeSession, onCreateTask = viewModel::createRuntimeTask,
                                onSetSessionStatus = viewModel::setRuntimeSessionStatus, onCancelTask = viewModel::cancelRuntimeTask,
                                onRunMemorySearch = viewModel::runRuntimeMemorySearch, onPreviewReasoning = viewModel::previewRuntimeReasoning,
                                onSaveAccessToken = viewModel::saveRuntimeAccessToken, onForgetAccessToken = viewModel::forgetRuntimeAccessToken,
                                onCheckExtension = viewModel::checkTermuxExtension, onOpenTermux = viewModel::openTermux,
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedTask != null) {
        TaskDetailsDialog(
            task = selectedTask,
            onDismiss = { selectedTaskId = null },
            onDelete = {
                viewModel.deleteTask(selectedTask.id)
                selectedTaskId = null
            },
        )
    }
}

@Composable
private fun LocalMemoryPanel(
    state: LocalMemoryUiState,
    peerSync: PeerMemorySyncUiState,
    onAdd: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onPeerSync: (String, String) -> Unit,
) {
    var peerHost by rememberSaveable { mutableStateOf("") }
    var peerFingerprint by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("general") }
    var search by rememberSaveable { mutableStateOf("") }
    var lastHandledAddedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.lastAddedId) {
        val savedId = state.lastAddedId
        if (savedId != null && savedId != lastHandledAddedId) {
            content = ""
            lastHandledAddedId = savedId
        }
    }
    val query = search.trim()
    val visible = state.records.asReversed().filter { memory ->
        query.isBlank() ||
            memory.content.contains(query, ignoreCase = true) ||
            memory.category.contains(query, ignoreCase = true)
    }.take(30)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionHeading(
                    "Память устройства",
                    "сохраняется в Android · работает без Termux",
                )
                Text(
                    "${state.records.size} / 2 000",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Эти заметки хранятся только на этом устройстве и доступны JARVIS после перезапуска. Они пока не включены в перенос рабочего пространства на ПК.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(700) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Новое воспоминание") },
                placeholder = { Text("Что JARVIS должен помнить?") },
                minLines = 2,
                maxLines = 4,
                enabled = state.isLoaded && !state.isBusy && !peerSync.isBusy,
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it.take(64) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Категория") },
                singleLine = true,
                enabled = state.isLoaded && !state.isBusy && !peerSync.isBusy,
            )
            Button(
                onClick = { onAdd(content, category) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isLoaded && !state.isBusy && !peerSync.isBusy &&
                    content.isNotBlank() && category.isNotBlank(),
            ) {
                Text(if (state.isBusy) "Сохраняем…" else "Сохранить на устройстве")
            }
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    SectionHeading("Синхронизация с ПК", "HTTPS · сертификат закреплён вручную")
                    Text(
                        "Введите приватный IPv4 компьютера и SHA-256 отпечаток TLS-сертификата с ПК. Используется уже сохранённый токен Termux. Передаются только одобренные заметки; история чата, ключи и модель не отправляются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = peerHost,
                        onValueChange = { peerHost = it.take(15) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("IP компьютера в локальной сети") },
                        placeholder = { Text("192.168.1.20") },
                        singleLine = true,
                        enabled = !peerSync.isBusy,
                    )
                    OutlinedTextField(
                        value = peerFingerprint,
                        onValueChange = { peerFingerprint = it.take(95) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("SHA-256 отпечаток сертификата") },
                        placeholder = { Text("64 шестнадцатеричных символа") },
                        singleLine = true,
                        enabled = !peerSync.isBusy,
                    )
                    Button(
                        onClick = { onPeerSync(peerHost, peerFingerprint) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.isLoaded && !state.isBusy && !peerSync.isBusy &&
                            peerHost.isNotBlank() && peerFingerprint.isNotBlank(),
                    ) {
                        Text(if (peerSync.isBusy) "Синхронизация…" else "Синхронизировать одобренные заметки")
                    }
                    if (peerSync.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    peerSync.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    peerSync.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (peerSync.remoteRevision != null) {
                        Text(
                            "Ревизия памяти ПК: ${peerSync.remoteRevision}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по локальным воспоминаниям") },
                singleLine = true,
            )
            Text(
                "Показано ${visible.size} из ${state.records.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (visible.isEmpty()) {
                Text(
                    if (state.isLoaded) "Локальных воспоминаний пока нет." else "Локальная память недоступна.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visible.forEach { memory ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    memory.category,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                TextButton(onClick = { onDelete(memory.id) }, enabled = !state.isBusy && !peerSync.isBusy) {
                                    Text("Удалить")
                                }
                            }
                            Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                memory.updatedAt,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceMemoryPanel(
    state: RuntimeWorkspaceUiState,
    onRefresh: () -> Unit,
    onAdd: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    transferPassphrase: String,
    onPassphraseChange: (String) -> Unit,
    onExport: (String) -> Unit,
    onChooseImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onExportBackup: (String) -> Unit,
    onChooseRestore: (String) -> Unit,
    onBackupAcknowledgement: (Boolean) -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
) {
    var confirmRestore by remember(state.restorePreview) { mutableStateOf(false) }
    var content by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("general") }
    var search by rememberSaveable { mutableStateOf("") }
    var approved by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RuntimeMemory?>(null) }
    val visible = state.memories.filter {
        search.isBlank() ||
            it.content.contains(search.trim(), ignoreCase = true) ||
            it.category.contains(search.trim(), ignoreCase = true)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionHeading("Память JARVIS", "локальная JPVR · единый формат PC/Android")
                Text(
                    if (state.isLoaded) "REV ${state.revision}" else "НЕ ЗАГРУЖЕНА",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(10_000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Новое воспоминание") },
                placeholder = { Text("Что JARVIS должен помнить?") },
                minLines = 2,
                maxLines = 4,
                enabled = !state.isBusy && state.isLoaded &&
                    state.importPreview == null && state.restorePreview == null,
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it.take(64) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Категория") },
                singleLine = true,
                enabled = !state.isBusy && state.isLoaded && state.restorePreview == null,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = approved,
                    onCheckedChange = { approved = it },
                    enabled = !state.isBusy && state.importPreview == null &&
                        state.restorePreview == null,
                )
                Text(
                    "Я подтверждаю сохранение этого воспоминания",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    onAdd(content.trim(), category.trim())
                    content = ""
                    approved = false
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isLoaded && !state.isBusy && state.importPreview == null &&
                    state.restorePreview == null && approved &&
                    content.isNotBlank() && category.isNotBlank(),
            ) {
                Text(if (state.isBusy) "Сохранение…" else "Сохранить воспоминание")
            }
            Text(
                "Зашифрованный перенос между устройствами",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = transferPassphrase,
                onValueChange = onPassphraseChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Парольная фраза · минимум 12 символов") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.isBusy,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onExport(transferPassphrase) },
                    modifier = Modifier.weight(1f),
                    enabled = state.isLoaded && !state.isBusy && state.importPreview == null &&
                        state.restorePreview == null && transferPassphrase.length >= 12,
                ) { Text("Экспорт .jws") }
                OutlinedButton(
                    onClick = onChooseImport,
                    modifier = Modifier.weight(1f),
                    enabled = state.isLoaded && !state.isBusy && state.restorePreview == null &&
                        transferPassphrase.length >= 12,
                ) { Text("Импорт .jws") }
            }
            Text(
                "Импорт .jws объединяет записи по ID и не перезаписывает локальные версии. Пароль не сохраняется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Резервная копия .jwb",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onExportBackup(transferPassphrase) },
                    modifier = Modifier.weight(1f),
                    enabled = state.isLoaded && !state.isBusy && state.restorePreview == null &&
                        transferPassphrase.length >= 12,
                ) { Text("Сохранить .jwb") }
                OutlinedButton(
                    onClick = { onChooseRestore(transferPassphrase) },
                    modifier = Modifier.weight(1f),
                    enabled = state.isLoaded && !state.isBusy && state.importPreview == null &&
                        state.restorePreview == null && transferPassphrase.length >= 12 &&
                        state.backupRevision == state.revision &&
                        state.restoreBackupAcknowledged,
                ) { Text("Выбрать backup") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.restoreBackupAcknowledged,
                    onCheckedChange = onBackupAcknowledgement,
                    enabled = !state.isBusy && state.backupRevision != null &&
                        state.backupRevision == state.revision,
                )
                Text(
                    "Я сохранил отдельный backup текущей ревизии и понимаю, что восстановление заменит всю общую память.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.backupRevision == state.revision && state.backupRevision != null) {
                Text(
                    "Отдельная копия подтверждена для REV ${state.backupRevision}. Любое изменение памяти аннулирует подтверждение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "В .jwb входит только общая память. Задачи/сессии JPVR, черновики Android, ключи, токены и нейронные checkpoint не включены.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.restorePreview?.let { preview ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Предпросмотр восстановления — ЗАМЕНА",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Дата backup: ${preview.backupCreatedAt}")
                        Text("Диалоги: ${preview.conversationCount}")
                        Text("Воспоминания: ${preview.memoryCount}")
                        Text("Сводки задач: ${preview.taskCount}")
                        Text("Настройки: ${preview.settingCount}")
                        Text(
                            "Все четыре коллекции текущей памяти будут заменены выбранной копией. Перед продолжением сохраните и подтвердите отдельный backup текущей ревизии.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { confirmRestore = true },
                                modifier = Modifier.weight(1f),
                                enabled = !state.isBusy &&
                                    state.backupRevision == state.revision &&
                                    state.restoreBackupAcknowledged,
                            ) { Text("Заменить память") }
                            OutlinedButton(
                                onClick = onCancelRestore,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isBusy,
                            ) { Text("Отмена") }
                        }
                    }
                }
            }
            state.importPreview?.let { preview ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Предпросмотр импорта",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Новых записей: ${preview.addedCount}")
                        Text("Уже совпадают: ${preview.identicalCount}")
                        Text("Конфликтов без перезаписи: ${preview.conflictCount}")
                        Text(
                            "Локальные версии конфликтующих записей сохранятся.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onConfirmImport,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isBusy && state.restorePreview == null &&
                                    preview.addedCount > 0,
                            ) { Text("Подтвердить импорт") }
                            OutlinedButton(
                                onClick = onCancelImport,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isBusy,
                            ) { Text("Отмена") }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск в памяти") },
                singleLine = true,
                enabled = state.isLoaded,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (state.isLoaded) "${visible.size} из ${state.memories.size} воспоминаний"
                    else "Нужно подключить локальный сервис",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh, enabled = !state.isBusy) {
                    Text("Обновить")
                }
            }
            if (!state.error.isNullOrBlank()) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!state.message.isNullOrBlank()) {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!state.isLoaded) {
                Text(
                    "После подключения к Termux нажмите «Обновить», чтобы загрузить общую память.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (visible.isEmpty()) {
                Text(
                    if (state.memories.isEmpty()) "Воспоминаний пока нет."
                    else "Поиск ничего не нашёл.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(visible.asReversed(), key = { it.id }) { memory ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(11.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    memory.category,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    memory.updatedAt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { deleteTarget = memory },
                                    enabled = !state.isBusy && state.importPreview == null &&
                                        state.restorePreview == null,
                                ) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmRestore && state.restorePreview != null) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Заменить всю общую память?") },
            text = {
                Text(
                    "Текущие диалоги, воспоминания, сводки задач и настройки будут заменены данными из .jwb. Отдельный backup текущей ревизии подтверждён.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = false
                    onConfirmRestore()
                }) { Text("Подтвердить замену") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Назад") }
            },
        )
    }

    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить воспоминание?") },
            text = { Text("Запись будет удалена из общей памяти JARVIS. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun Header(isBusy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "JARVIS",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "ANS-100 ALPHA · ЛОКАЛЬНОЕ ПРИЛОЖЕНИЕ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ActivityStatusPill(isBusy = isBusy)
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                border = BorderStroke(1.dp, Color(0x664DA3FF)),
            ) {
                Text(
                    "V0.6",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ActivityStatusPill(isBusy: Boolean) {
    val pulseScale by animateFloatAsState(
        targetValue = if (isBusy) 1.35f else 1f,
        animationSpec = if (isBusy) {
            infiniteRepeatable(
                animation = tween(durationMillis = 850),
                repeatMode = RepeatMode.Reverse,
            )
        } else {
            tween(durationMillis = 180)
        },
        label = "jarvis-activity-pulse",
    )
    val accent = if (isBusy) Color(0xFF73D7FF) else Color(0xFF62B5FF)
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFF0A2038),
        border = BorderStroke(1.dp, Color(0x9952AFFF)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .scale(pulseScale)
                    .background(accent, CircleShape),
            )
            Text(
                if (isBusy) "ОБРАБОТКА" else "ГОТОВ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}

@Composable
private fun SystemStatusCard(storageReady: Boolean, notice: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0x8851AFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Локальное хранилище", fontWeight = FontWeight.SemiBold)
            Text(
                if (storageReady) "Доступно · сохранение включено" else "Ошибка чтения данных",
                color = if (storageReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Встроенный диалог и локальная память работают без Termux. Подключение Termux — необязательное расширение для общих задач и JPVR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!notice.isNullOrBlank()) {
                Text(notice, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionControls(
    session: LocalSession,
    onStatus: (LocalSessionStatus) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (session.status) {
                LocalSessionStatus.OPEN -> "Открыта"
                LocalSessionStatus.PAUSED -> "На паузе"
                LocalSessionStatus.CLOSED -> "Закрыта"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        when (session.status) {
            LocalSessionStatus.OPEN -> {
                OutlinedButton(
                    enabled = enabled,
                    onClick = { onStatus(LocalSessionStatus.PAUSED) },
                ) { Text("Пауза") }
                TextButton(
                    enabled = enabled,
                    onClick = { onStatus(LocalSessionStatus.CLOSED) },
                ) { Text("Закрыть") }
            }
            LocalSessionStatus.PAUSED -> {
                Button(
                    enabled = enabled,
                    onClick = { onStatus(LocalSessionStatus.OPEN) },
                ) { Text("Продолжить") }
                TextButton(
                    enabled = enabled,
                    onClick = { onStatus(LocalSessionStatus.CLOSED) },
                ) { Text("Закрыть") }
            }
            LocalSessionStatus.CLOSED -> {
                Text("Только просмотр", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DraftTaskCard(task: LocalTaskDraft, onDetails: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title, fontWeight = FontWeight.Medium)
                Text(
                    "ЧЕРНОВИК · НЕ ОТПРАВЛЕНА",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onDetails) { Text("Детали") }
        }
    }
}

@Composable
private fun TaskDetailsDialog(
    task: LocalTaskDraft,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Черновик задачи") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge)
                Text("ID: ${task.id}", style = MaterialTheme.typography.labelSmall)
                Text("Сессия: ${task.sessionId}", style = MaterialTheme.typography.labelSmall)
                Text(
                    "Статус: локальный черновик. Движок не запускал эту задачу.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("Удалить черновик") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}


@Composable
private fun RuntimeBridgePanel(
    state: RuntimeBridgeUiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onCreateSession: (String) -> Unit,
    onCreateTask: (String, String) -> Unit,
    onSetSessionStatus: (String, String, Int) -> Unit,
    onCancelTask: (String, Int) -> Unit,
    onRunMemorySearch: (String, Int, String) -> Unit,
    onPreviewReasoning: (RuntimeReasoningPreviewRequest) -> Unit,
    onSaveAccessToken: (String) -> Unit,
    onForgetAccessToken: () -> Unit,
    onCheckExtension: () -> Unit,
    onOpenTermux: () -> Unit,
) {
    var accessTokenInput by remember { mutableStateOf("") }
    var showTermuxGuide by rememberSaveable { mutableStateOf(true) }
    var sessionTitle by rememberSaveable { mutableStateOf("") }
    var taskTitle by rememberSaveable { mutableStateOf("") }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewTaskId by rememberSaveable { mutableStateOf("android-preview") }
    var previewGoalDescription by rememberSaveable { mutableStateOf("Подготовить систему к работе") }
    var previewInitialFacts by rememberSaveable { mutableStateOf("battery_ok") }
    var previewGoalFacts by rememberSaveable { mutableStateOf("ready") }
    var previewActionsText by rememberSaveable {
        mutableStateOf(
            "power_on | Включить питание | battery_ok | powered_on\n" +
                "boot | Запустить систему | powered_on | ready"
        )
    }
    var previewInputError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMemorySearchTask by remember { mutableStateOf<RuntimeTaskSnapshot?>(null) }
    val snapshot = state.snapshot
    val selectedSession = snapshot?.sessions?.firstOrNull {
        it.sessionId == selectedSessionId
    } ?: snapshot?.sessions?.firstOrNull { it.status == "open" }

    val uriHandler = LocalUriHandler.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0x8851AFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            SectionHeading("Необязательное расширение Termux", "встроенное ядро JARVIS работает самостоятельно")
            Text(
                when (resolveOptionalTermuxExtensionStatus(
                    installed = state.extensionInstalled,
                    hasToken = state.hasAccessToken,
                    connected = state.isConnected,
                )) {
                    OptionalTermuxExtensionStatus.NOT_INSTALLED ->
                        "Termux не установлен. Диалог и встроенная обработка ANS-100 продолжают работать прямо в приложении."
                    OptionalTermuxExtensionStatus.NEEDS_TOKEN ->
                        "Termux обнаружен. Для дополнительных возможностей запустите локальную консоль и сохраните выданный ею токен."
                    OptionalTermuxExtensionStatus.READY_TO_CONNECT ->
                        "Termux установлен и токен сохранён. Запустите локальную консоль JARVIS в Termux, затем подключите расширение."
                    OptionalTermuxExtensionStatus.CONNECTED ->
                        "Расширение подключено: доступны общая JPVR-память, реестр задач/сессий и ограниченный символьный preview."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0x6656B9FF)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Как подключить Termux", fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { showTermuxGuide = !showTermuxGuide }) {
                            Text(if (showTermuxGuide) "Свернуть" else "Показать")
                        }
                    }
                    if (showTermuxGuide) {
                        Text(
                            "Шаг 1. Скачайте F-Droid только с официального сайта. Не используйте рекламные APK-сайты, случайные зеркала и ссылки из комментариев.",
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedButton(
                            onClick = { uriHandler.openUri("https://f-droid.org/") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Открыть официальный сайт F-Droid") }
                        Text(
                            "На сайте нажмите Download F-Droid, скачайте APK и откройте его. Android может попросить временно разрешить браузеру установку неизвестных приложений. Разрешите установку только для этого браузера, установите F-Droid, а затем при желании отключите это разрешение обратно.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Шаг 2. Запустите F-Droid и дождитесь первой загрузки каталога. Если список приложений пустой, подождите окончания обновления репозитория или откройте вкладку «Обновления» и потяните её вниз.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Шаг 3. Найдите именно приложение «Termux» (пакет com.termux) в официальном каталоге F-Droid. Не путайте его с Termux:API, Termux:Widget и другими дополнениями.",
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedButton(
                            onClick = { uriHandler.openUri("https://f-droid.org/packages/com.termux/") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Открыть страницу оригинального Termux") }
                        Text(
                            "Нажмите «Установить» и дождитесь завершения. Это будет последняя версия Termux, которую F-Droid уже опубликовал в своём каталоге. Она не обязательно совпадает с самым новым релизом GitHub в тот же день: публикация F-Droid иногда задерживается на несколько дней или дольше.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Важно: не смешивайте источники Termux и его дополнений. Если Termux установлен из F-Droid, все его дополнения тоже устанавливайте из F-Droid. APK из F-Droid и GitHub имеют разные подписи и могут не обновлять друг друга. Если Termux уже установлен из другого источника, сначала сделайте резервную копию его данных; удаление Termux без копии может стереть файлы и настройки.",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Шаг 4. Откройте Termux. При первом запуске он подготовит базовую среду — дождитесь окончания, не закрывайте приложение. Затем выполните команды по одной или вставьте весь блок:")
                        Text(
                            "pkg update\npkg upgrade -y\npkg install -y python git gh",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Если обновление репозитория завершается ошибкой, выполните termux-change-repo, выберите Main repository и доступное зеркало, затем повторите pkg update.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Шаг 5. Репозиторий JARVIS сейчас закрытый, поэтому обычный git clone без авторизации не сработает. Установленный GitHub CLI запросит вход через официальный сайт GitHub. Используйте аккаунт, которому открыт доступ к этому репозиторию:",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "gh auth login --hostname github.com --git-protocol https --web\ngh auth setup-git",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Termux покажет одноразовый код. Откройте официальный github.com, введите его и подтвердите вход. GitHub CLI может сохранить данные входа в своём файле конфигурации, если в Termux нет системного хранилища учётных данных. Защитите телефон блокировкой экрана, не передавайте папку конфигурации gh и резервные копии Termux. Не отправляйте код, пароль или токены в чат. Если позже вам больше не понадобится получать обновления закрытого репозитория, можно удалить сохранённый вход командой gh auth logout --hostname github.com.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Шаг 6. Скачайте именно рабочую экспериментальную ветку JARVIS (не main):")
                        Text(
                            "git clone --branch experiment/jarvis-integrated-prototype-v1 --single-branch https://github.com/maksrugu123-sys/jarvis-ans100-alpha.git\ncd jarvis-ans100-alpha",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text("Шаг 7. Запустите локальное расширение:")
                        Text(
                            "python3 -m tools.jarvis_app --port 8765 --platform android",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "При первом запуске в Termux появится одноразовый ANDROID / PWA ACCESS TOKEN. Скопируйте его сразу, вернитесь в JARVIS → Система, вставьте токен, нажмите «Сохранить токен», затем «Подключить расширение». Токен нельзя публиковать или пересылать.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Если токен потерян: остановите сервер сочетанием Ctrl+C и запустите заново команду python3 -m tools.jarvis_app --port 8765 --platform android --rotate-token. Старый токен перестанет работать.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Чтобы обновить исходный код позже, остановите сервер, откройте папку проекта и выполните: cd ~/jarvis-ans100-alpha && git pull --ff-only origin experiment/jarvis-integrated-prototype-v1. Затем снова запустите команду из шага 7.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Пока расширение нужно, Termux и процесс JARVIS должны оставаться запущенными. Чтобы остановить сервер, нажмите Ctrl+C. Основной чат, локальная память и обработка сообщений в Android-приложении продолжают работать без Termux.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !state.isBusy,
                    onClick = onCheckExtension,
                    modifier = Modifier.weight(1f),
                ) { Text("Проверить Termux") }
                if (state.extensionInstalled) {
                    OutlinedButton(
                        enabled = !state.isBusy,
                        onClick = onOpenTermux,
                        modifier = Modifier.weight(1f),
                    ) { Text("Открыть Termux") }
                }
            }
            if (state.extensionInstalled && !state.isConnected) {
                Text(
                    "В корне репозитория в Termux запустите: python3 -m tools.jarvis_app --port 8765 --platform android",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.extensionInstalled || state.hasAccessToken) {
                OutlinedTextField(
                    value = accessTokenInput,
                    onValueChange = { accessTokenInput = it.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Токен доступа из Termux") },
                    placeholder = { Text("43 символа Base64URL") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !state.isBusy,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onSaveAccessToken(accessTokenInput.trim())
                            accessTokenInput = ""
                        },
                        enabled = !state.isBusy && accessTokenInput.trim().length == 43,
                        modifier = Modifier.weight(1f),
                    ) { Text("Сохранить токен") }
                    if (state.hasAccessToken) {
                        OutlinedButton(
                            enabled = !state.isBusy,
                            onClick = onForgetAccessToken,
                        ) { Text("Удалить токен") }
                    }
                }
                if (state.hasAccessToken) {
                    Text(
                        "Токен зашифрован Android Keystore и используется только для локального соединения с расширением.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                when {
                    state.isBusy -> "Проверяется локальное расширение…"
                    state.isConnected && snapshot != null ->
                        "Подключено · JPVR checkpoint ${snapshot.checkpointVersion}"
                    state.extensionInstalled -> "Termux установлен · сервис можно подключить отдельно"
                    else -> "Автономный режим · Termux не требуется"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.isConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = !state.isBusy && state.extensionInstalled && state.hasAccessToken,
                onClick = if (state.isConnected) onRefresh else onConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.isBusy -> "Подключение…"
                        state.isConnected -> "Обновить состояние расширения"
                        !state.extensionInstalled -> "Встроенное ядро активно"
                        else -> "Подключить расширение"
                    }
                )
            }
            if (!state.error.isNullOrBlank()) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!state.message.isNullOrBlank()) {
                Text(state.message, style = MaterialTheme.typography.labelSmall)
            }

            if (state.isConnected && snapshot != null) {
                Text(
                    "Manager: ${snapshot.managerId} · Событий: ${snapshot.eventCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Инструменты: отключены · Внешние действия: отключены · Удалённый доступ: отключён",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = sessionTitle,
                    onValueChange = { sessionTitle = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Новая сессия JARVIS") },
                    singleLine = true,
                    enabled = !state.isBusy,
                )
                Button(
                    onClick = {
                        onCreateSession(sessionTitle.trim())
                        sessionTitle = ""
                    },
                    enabled = !state.isBusy && sessionTitle.isNotBlank() &&
                        snapshot.sessions.size < 128,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Создать сессию в JPVR") }

                if (snapshot.sessions.isNotEmpty()) {
                    Text("Сессии ядра", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(snapshot.sessions, key = { it.sessionId }) { session ->
                            FilterChip(
                                selected = session.sessionId == selectedSession?.sessionId,
                                onClick = { selectedSessionId = session.sessionId },
                                enabled = !state.isBusy,
                                label = {
                                    Text("${session.title} · ${runtimeStatusLabel(session.status)}")
                                },
                            )
                        }
                    }
                } else {
                    Text(
                        "В реестре пока нет сессий.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (selectedSession != null) {
                    Text(
                        "Выбрана: ${selectedSession.title} · revision ${selectedSession.revision}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (selectedSession.status) {
                            "open" -> {
                                OutlinedButton(
                                    enabled = !state.isBusy,
                                    onClick = {
                                        onSetSessionStatus(
                                            selectedSession.sessionId, "paused", selectedSession.revision,
                                        )
                                    },
                                ) { Text("Пауза") }
                                TextButton(
                                    enabled = !state.isBusy,
                                    onClick = {
                                        onSetSessionStatus(
                                            selectedSession.sessionId, "closed", selectedSession.revision,
                                        )
                                    },
                                ) { Text("Закрыть") }
                            }
                            "paused" -> {
                                Button(
                                    enabled = !state.isBusy,
                                    onClick = {
                                        onSetSessionStatus(
                                            selectedSession.sessionId, "open", selectedSession.revision,
                                        )
                                    },
                                ) { Text("Продолжить") }
                                TextButton(
                                    enabled = !state.isBusy,
                                    onClick = {
                                        onSetSessionStatus(
                                            selectedSession.sessionId, "closed", selectedSession.revision,
                                        )
                                    },
                                ) { Text("Закрыть") }
                            }
                            else -> Text(
                                "Сессия закрыта · только просмотр",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = taskTitle,
                        onValueChange = { taskTitle = it.take(512) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Новая задача ядра") },
                        placeholder = { Text("Задача попадёт в очередь, но не будет исполнена") },
                        minLines = 2,
                        maxLines = 3,
                        enabled = !state.isBusy && selectedSession.status == "open",
                    )
                    Button(
                        onClick = {
                            onCreateTask(selectedSession.sessionId, taskTitle.trim())
                            taskTitle = ""
                        },
                        enabled = !state.isBusy && selectedSession.status == "open" &&
                            taskTitle.isNotBlank() && snapshot.tasks.size < 1024,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Создать задачу в JARVIS") }
                }

                Text("Задачи реестра", style = MaterialTheme.typography.labelMedium)
                if (snapshot.tasks.isEmpty()) {
                    Text("Задач пока нет.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(snapshot.tasks.asReversed(), key = { it.taskId }) { task ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(task.title, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${runtimeStatusLabel(task.status)} · revision ${task.revision}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (!task.failureCode.isNullOrBlank()) {
                                            Text(
                                                "Ошибка: ${task.failureCode}",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                    if (task.status == "queued") {
                                        Column(horizontalAlignment = Alignment.End) {
                                            val owningSessionOpen = snapshot.sessions.any {
                                                it.sessionId == task.sessionId && it.status == "open"
                                            }
                                            TextButton(
                                                enabled = !state.isBusy && owningSessionOpen &&
                                                    task.title.isNotBlank() && task.title.length <= 240,
                                                onClick = { pendingMemorySearchTask = task },
                                            ) { Text("Запустить поиск") }
                                            TextButton(
                                                enabled = !state.isBusy,
                                                onClick = { onCancelTask(task.taskId, task.revision) },
                                            ) { Text("Отменить") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                state.taskExecutionResult?.let { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Результат безопасной задачи", fontWeight = FontWeight.SemiBold)
                            Text("Статус: ${runtimeStatusLabel(result.status)}")
                            Text("Поиск по запросу: «${result.query}»", style = MaterialTheme.typography.bodySmall)
                            if (result.matches.isEmpty()) {
                                Text("В подтверждённой памяти совпадений не найдено.")
                            } else {
                                result.matches.forEach { match ->
                                    Text(match.category, fontWeight = FontWeight.Medium)
                                    Text(match.excerpt, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(
                                "Использовались только воспоминания с явным подтверждением пользователя. Никаких внешних изменений не выполнялось.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                pendingMemorySearchTask?.let { task ->
                    AlertDialog(
                        onDismissRequest = { pendingMemorySearchTask = null },
                        title = { Text("Подтвердить запуск задачи?") },
                        text = {
                            Text(
                                "JARVIS выполнит только read-only memory.search по одобренным воспоминаниям. Запрос: «${task.title}». Браузер, команды оболочки и запись файлов не используются.",
                            )
                        },
                        confirmButton = {
                            Button(
                                enabled = !state.isBusy,
                                onClick = {
                                    onRunMemorySearch(task.taskId, task.revision, task.title)
                                    pendingMemorySearchTask = null
                                },
                            ) { Text("Запустить безопасный поиск") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingMemorySearchTask = null }) {
                                Text("Не запускать")
                            }
                        },
                    )
                }

                SectionHeading("Предварительное планирование", "Reasoning Engine V0.1 · без исполнения")
                Text(
                    "Задайте факты и простые переходы. Это отдельный символьный анализ: он не запускает очередь задач и не меняет JPVR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = previewTaskId,
                    onValueChange = { previewTaskId = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ID анализа") },
                    singleLine = true,
                    enabled = !state.isBusy,
                )
                OutlinedTextField(
                    value = previewGoalDescription,
                    onValueChange = { previewGoalDescription = it.take(512) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Что нужно получить") },
                    minLines = 2,
                    maxLines = 3,
                    enabled = !state.isBusy,
                )
                OutlinedTextField(
                    value = previewInitialFacts,
                    onValueChange = { previewInitialFacts = it.take(2048) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Начальные факты через запятую") },
                    placeholder = { Text("battery_ok, network_ready") },
                    singleLine = true,
                    enabled = !state.isBusy,
                )
                OutlinedTextField(
                    value = previewGoalFacts,
                    onValueChange = { previewGoalFacts = it.take(2048) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Целевые факты через запятую") },
                    placeholder = { Text("ready") },
                    singleLine = true,
                    enabled = !state.isBusy,
                )
                OutlinedTextField(
                    value = previewActionsText,
                    onValueChange = { previewActionsText = it.take(12_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Действия — по одному на строку") },
                    placeholder = {
                        Text("id | описание | нужны факты | добавить факты | удалить факты (необязательно)")
                    },
                    minLines = 3,
                    maxLines = 7,
                    enabled = !state.isBusy,
                )
                Text(
                    "Формат строки: id | описание | предусловия | добавить | удалить (последнее поле можно пропустить). " +
                        "Факты — идентификаторы через запятую. Максимум 16 действий и 32 факта в каждом списке.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!previewInputError.isNullOrBlank()) {
                    Text(
                        previewInputError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = {
                        try {
                            val request = buildRuntimeReasoningPreview(
                                taskId = previewTaskId,
                                goalDescription = previewGoalDescription,
                                initialFactsText = previewInitialFacts,
                                goalFactsText = previewGoalFacts,
                                actionsText = previewActionsText,
                            )
                            previewInputError = null
                            onPreviewReasoning(request)
                        } catch (error: IllegalArgumentException) {
                            previewInputError = error.message ?: "Проверьте поля символьного плана."
                        }
                    },
                    enabled = !state.isBusy && state.isConnected && snapshot != null &&
                        previewTaskId.isNotBlank() && previewGoalDescription.isNotBlank() &&
                        previewGoalFacts.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.isBusy) "Расчёт…" else "Рассчитать план без запуска") }

                state.reasoningPreview?.let { preview ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Результат символьного планирования", fontWeight = FontWeight.SemiBold)
                            Text(
                                reasoningStatusLabel(preview.status),
                                color = if (preview.status == "solved") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "ID: ${preview.taskId} · раскрыто состояний: ${preview.expandedStates}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (preview.status == "solved" && preview.plan.isEmpty()) {
                                Text("Цель уже выполняется по начальным фактам; дополнительные действия не нужны.")
                            }
                            preview.plan.forEach { step ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${step.index}. ${step.summary}", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Действие: ${step.actionId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (step.requiredFacts.isNotEmpty()) {
                                        Text(
                                            "Требует: ${step.requiredFacts.joinToString()}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (step.addedFacts.isNotEmpty() || step.deletedFacts.isNotEmpty()) {
                                        Text(
                                            "Изменения: +${step.addedFacts.joinToString()} −${step.deletedFacts.joinToString()}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            if (preview.status != "solved") {
                                Text(
                                    "Цель не была доказана в заданной модели. Измените факты или действия и повторите preview.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                "Прогнозируемые факты: ${preview.predictedFinalFacts.joinToString().ifBlank { "—" }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Важно: действия не исполнялись. Это не запуск задачи и не разрешение на выполнение.",
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else {
                Text(
                    "В Termux запустите: python3 -m tools.jarvis_app --port 8765. " +
                        "После запуска нажмите «Подключить JARVIS».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun buildRuntimeReasoningPreview(
    taskId: String,
    goalDescription: String,
    initialFactsText: String,
    goalFactsText: String,
    actionsText: String,
): RuntimeReasoningPreviewRequest {
    val id = taskId.trim()
    val description = goalDescription.trim()
    require(FACT_ID_PATTERN.matches(id)) { "ID анализа должен быть от 1 до 128 символов и содержать только буквы, цифры, точку, дефис, подчёркивание или двоеточие." }
    require(description.isNotEmpty() && description.length <= 512) {
        "Описание цели должно содержать от 1 до 512 символов."
    }
    val initialFacts = parseRuntimeFacts(initialFactsText, "Начальные факты")
    val goalFacts = parseRuntimeFacts(goalFactsText, "Целевые факты")
    require(goalFacts.isNotEmpty()) { "Добавьте хотя бы один целевой факт." }

    val lines = actionsText.lineSequence()
        .mapIndexed { index, value -> (index + 1) to value.trim() }
        .filter { it.second.isNotEmpty() }
        .toList()
    require(lines.size <= 16) { "Допускается не более 16 действий." }
    val seenActionIds = HashSet<String>()
    val actions = lines.map { (lineNumber, line) ->
        val fields = line.split('|').map(String::trim)
        require(fields.size == 4 || fields.size == 5) {
            "Строка $lineNumber: нужно 4 или 5 полей, разделённых символом |."
        }
        val actionId = fields[0]
        val summary = fields[1]
        require(FACT_ID_PATTERN.matches(actionId)) {
            "Строка $lineNumber: некорректный ID действия."
        }
        require(seenActionIds.add(actionId)) {
            "Строка $lineNumber: ID действия повторяется."
        }
        require(summary.isNotEmpty() && summary.length <= 256) {
            "Строка $lineNumber: описание действия должно быть от 1 до 256 символов."
        }
        val preconditions = parseRuntimeFacts(fields[2], "Предусловия в строке $lineNumber")
        val addFacts = parseRuntimeFacts(fields[3], "Добавляемые факты в строке $lineNumber")
        val deleteFacts = parseRuntimeFacts(fields.getOrElse(4) { "" }, "Удаляемые факты в строке $lineNumber")
        require(addFacts.isNotEmpty() || deleteFacts.isNotEmpty()) {
            "Строка $lineNumber: действие должно добавлять или удалять хотя бы один факт."
        }
        require(addFacts.intersect(deleteFacts.toSet()).isEmpty()) {
            "Строка $lineNumber: один факт нельзя одновременно добавлять и удалять."
        }
        RuntimePlanningAction(
            actionId = actionId,
            summary = summary,
            preconditions = preconditions,
            addFacts = addFacts,
            deleteFacts = deleteFacts,
        )
    }
    return RuntimeReasoningPreviewRequest(
        taskId = id,
        goalDescription = description,
        initialFacts = initialFacts,
        goalFacts = goalFacts,
        actions = actions,
    )
}

private fun parseRuntimeFacts(raw: String, label: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val facts = raw.split(',').map(String::trim)
    require(facts.size <= 32 && facts.all { it.isNotEmpty() && FACT_ID_PATTERN.matches(it) }) {
        "$label: используйте до 32 непустых идентификаторов через запятую."
    }
    require(facts.distinct().size == facts.size) { "$label: повторяющиеся факты запрещены." }
    return facts
}

private val FACT_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

private fun reasoningStatusLabel(status: String): String = when (status) {
    "solved" -> "План найден"
    "no_plan" -> "План не найден"
    "depth_limit" -> "Достигнут лимит глубины"
    "search_limit" -> "Достигнут лимит поиска"
    else -> status
}

private fun runtimeStatusLabel(status: String): String = when (status) {
    "open" -> "открыта"
    "paused" -> "пауза"
    "closed" -> "закрыта"
    "queued" -> "в очереди"
    "running" -> "в работе"
    "waiting" -> "ожидает"
    "completed" -> "завершена"
    "failed" -> "ошибка"
    "cancelled" -> "отменена"
    else -> status
}


private val JARVIS_HTTP_URL_PATTERN = Regex("""https?://[^\s<>"]+""")
private const val JARVIS_URL_ANNOTATION = "jarvis-source-url"

@Composable
private fun MessageBodyText(message: String) {
    val uriHandler = LocalUriHandler.current
    val annotatedMessage = remember(message) {
        buildAnnotatedString {
            var cursor = 0
            JARVIS_HTTP_URL_PATTERN.findAll(message).forEach { match ->
                if (match.range.first < cursor) return@forEach
                val cleanUrl = match.value.trimEnd('.', ',', ';', ':', '!', '?', ']', '}')
                if (cleanUrl.isNotEmpty()) {
                    val urlEnd = match.range.first + cleanUrl.length
                    append(message.substring(cursor, match.range.first))
                    pushStringAnnotation(
                        tag = JARVIS_URL_ANNOTATION,
                        annotation = cleanUrl,
                    )
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF83C8FF),
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(cleanUrl)
                    }
                    pop()
                    append(message.substring(urlEnd, match.range.last + 1))
                    cursor = match.range.last + 1
                }
            }
            append(message.substring(cursor))
        }
    }

    ClickableText(
        text = annotatedMessage,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        onClick = { offset ->
            annotatedMessage.getStringAnnotations(
                tag = JARVIS_URL_ANNOTATION,
                start = offset,
                end = offset,
            ).firstOrNull()?.item?.let { url ->
                runCatching { uriHandler.openUri(url) }
            }
        },
    )
}

@Composable
private fun ConversationComposer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    isBusy: Boolean,
    glowEnabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(rememberGlowBorder(glowEnabled, 22.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение JARVIS…") },
                maxLines = 4,
                enabled = !isBusy,
            )
            Button(
                onClick = { onSend(input) },
                enabled = input.isNotBlank() && !isBusy,
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Text("Отправить")
            }
        }
    }
}

@Composable
private fun ChatTitleBar(
    backendLabel: String,
    isBusy: Boolean,
    glowEnabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().then(rememberGlowBorder(glowEnabled, 18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("JARVIS", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("$backendLabel · ${if (isBusy) "формирует ответ" else "локально"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        TextButton(onClick = onOpenSettings) { Text("⚙") }
    }
}

@Composable
private fun ConversationPanel(
    state: LocalConversationUiState,
    onClear: () -> Unit,
    onRewardDialogFeature: (Double) -> Unit,
    glowEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    val messageListState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.isBusy) {
        val target = state.messages.size - 1
        if (target >= 0) messageListState.animateScrollToItem(target)
    }
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (state.messages.isNotEmpty()) {
                TextButton(onClick = { showClearConfirmation = true }, enabled = !state.isBusy) {
                    Text("Очистить диалог")
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = messageListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item(key = "jarvis-welcome") {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(modifier = Modifier.size(64.dp).then(rememberGlowBorder(glowEnabled, 32.dp)),
                                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("✦", style = MaterialTheme.typography.headlineLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("Чем могу помочь?", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold)
                            Text("Напиши сообщение. Переписка хранится локально на этом устройстве.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    val userMessage = message.role == ConversationRole.USER
                    val latestAssistantId = state.messages.lastOrNull {
                        it.role == ConversationRole.ASSISTANT
                    }?.id
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (userMessage) Arrangement.End else Arrangement.Start) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.90f),
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp,
                                bottomStart = if (userMessage) 20.dp else 6.dp,
                                bottomEnd = if (userMessage) 6.dp else 20.dp),
                            color = if (userMessage) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (!userMessage && glowEnabled) BorderStroke(1.dp, Color(0x6655BFFF)) else null,
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(if (userMessage) "Вы" else "JARVIS",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (userMessage) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.primary)
                                MessageBodyText(message.text)
                                if (!userMessage && message.id == latestAssistantId) {
                                    if (state.lastRewardedAssistantMessageId == message.id) {
                                        Text("Оценка сохранена", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = { onRewardDialogFeature(-1.0) }, enabled = !state.isBusy) {
                                                Text("👎 Неудачно")
                                            }
                                            TextButton(onClick = { onRewardDialogFeature(1.0) }, enabled = !state.isBusy) {
                                                Text("👍 Полезно")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.isBusy) {
                    item(key = "jarvis-thinking") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("JARVIS думает…", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Очистить локальный диалог?") },
            text = { Text("История переписки на этом телефоне будет удалена. Общая память JPVR не изменится.") },
            confirmButton = { TextButton(onClick = { showClearConfirmation = false; onClear() }) { Text("Удалить историю") } },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SettingsPanel(
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    accentChoice: String,
    onAccentChange: (String) -> Unit,
    glowEnabled: Boolean,
    onGlowChange: (Boolean) -> Unit,
    conversation: LocalConversationUiState,
    onSelectModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemoveModel: () -> Unit,
    onTrainDialogFeature: (String) -> Unit,
    onInitializeNativeNetwork: () -> Unit,
    onRunNativeNetworkProbe: () -> Unit,
    storageReady: Boolean,
    notice: String?,
    runtime: RuntimeBridgeUiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onCreateSession: (String) -> Unit,
    onCreateTask: (String, String) -> Unit,
    onSetSessionStatus: (String, String, Int) -> Unit,
    onCancelTask: (String, Int) -> Unit,
    onRunMemorySearch: (String, Int, String) -> Unit,
    onPreviewReasoning: (RuntimeReasoningPreviewRequest) -> Unit,
    onSaveAccessToken: (String) -> Unit,
    onForgetAccessToken: () -> Unit,
    onCheckExtension: () -> Unit,
    onOpenTermux: () -> Unit,
) {
    val categories = listOf("appearance" to "Внешний вид", "model" to "Языковая модель",
        "neural" to "ANS-100", "system" to "Система", "about" to "О приложении")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(categories, key = { it.first }) { (id, title) ->
                FilterChip(selected = selectedCategory == id, onClick = { onCategoryChange(id) },
                    label = { Text(title) })
            }
        }
        when (selectedCategory) {
            "appearance" -> {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeading("Цвет интерфейса", "выбери акцент приложения")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = accentChoice == "blue", onClick = { onAccentChange("blue") }, label = { Text("Голубой") })
                            FilterChip(selected = accentChoice == "violet", onClick = { onAccentChange("violet") }, label = { Text("Фиолетовый") })
                            FilterChip(selected = accentChoice == "mint", onClick = { onAccentChange("mint") }, label = { Text("Мятный") })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Переливающаяся подсветка", fontWeight = FontWeight.SemiBold)
                                Text("Плавное цветное свечение вокруг панели чата и поля ввода.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = glowEnabled, onCheckedChange = onGlowChange)
                        }
                        Surface(modifier = Modifier.fillMaxWidth().then(rememberGlowBorder(glowEnabled, 18.dp)),
                            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("Предпросмотр подсветки JARVIS", modifier = Modifier.padding(18.dp),
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            "model" -> {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        SectionHeading("Локальная языковая модель", "Qwen3 · LiteRT-LM · CPU")
                        Text(conversation.modelStatus, style = MaterialTheme.typography.bodyMedium)
                        Text(conversation.backendLabel, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!conversation.modelInstalled) {
                            Button(onClick = onDownloadModel, enabled = !conversation.isBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (conversation.modelDownloadStatus != null) "Продолжить загрузку" else "Скачать модель · около 331 МиБ")
                            }
                        }
                        if (conversation.modelDownloadProgress != null) {
                            LinearProgressIndicator(progress = (conversation.modelDownloadProgress ?: 0f).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth())
                            Text(conversation.modelDownloadStatus ?: "Загрузка…", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onCancelDownload, enabled = conversation.isBusy) { Text("Остановить загрузку") }
                        } else conversation.modelDownloadStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onSelectModel, enabled = !conversation.isBusy) { Text("Импортировать файл") }
                            if (conversation.modelInstalled) TextButton(onClick = onRemoveModel, enabled = !conversation.isBusy) {
                                Text("Удалить модель")
                            }
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionHeading("Скорость ответа", "измерения на этом устройстве")
                        Text("Инициализация модели: ${conversation.modelInitializationMs} мс")
                        Text("Генерация последнего ответа: ${conversation.responseGenerationMs} мс")
                        Text("Весь ход диалога: ${conversation.lastTurnDurationMs} мс")
                        Text("Оценка количества слов: ${conversation.responseTokenEstimate}")
                        Text("Счётчик слов приблизительный; это не токенизатор языковой модели.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            "neural" -> {
                val stats = conversation.nativeNetworkStats
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeading("Нативная сеть ANS-100 / JNVM", "разреженный SQLite runtime")
                        Text(if (stats == null) "Состояние загружается…"
                            else "Нейронов: ${stats.neuronCount} · связей: ${stats.connectionCount}\nОжидающих событий: ${stats.pendingEvents}\nRAM-кэш: ${stats.residentNeuronCount}/${stats.hotSetCapacity}",
                            style = MaterialTheme.typography.bodyMedium)
                        if ((stats?.neuronCount ?: 0) == 0) {
                            Button(onClick = onInitializeNativeNetwork, enabled = !conversation.nativeNetworkBusy,
                                modifier = Modifier.fillMaxWidth()) { Text("Создать сеть · 10 000 нейронов") }
                        } else {
                            OutlinedButton(onClick = onRunNativeNetworkProbe, enabled = !conversation.nativeNetworkBusy,
                                modifier = Modifier.fillMaxWidth()) { Text("Запустить тестовый импульс") }
                        }
                        if (conversation.nativeNetworkBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        conversation.nativeNetworkMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        conversation.nativeNetworkError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        SectionHeading("Анализ диалога", "локальные признаки и обратная связь")
                        Text("Активных признаков: ${conversation.dialogFeaturePrediction?.activeFeatureCount ?: 0}")
                        Text("Обучено примеров: ${conversation.dialogFeaturePrediction?.trainedExamples ?: 0}")
                        Text("Reward-событий: ${conversation.dialogFeaturePrediction?.rewardUpdates ?: 0}")
                        Text("ANS-100 (10 нейронов): активно ${conversation.ans100ActiveNeuronCount}, событий ${conversation.ans100ProcessedEvents}, импульсов ${conversation.ans100SpikeCount}, attention gate: ${if (conversation.ans100AttentionGateActive) "да" else "нет"}",
                            style = MaterialTheme.typography.bodySmall)
                        conversation.understandingIntent?.let {
                            Text("Предварительное намерение Core: $it · план: ${conversation.reasoningPlanSteps} шагов" +
                                if (conversation.reasoningNeedsClarification) " · нужно уточнение" else "")
                        }
                        conversation.dialogFeatureError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Text("Уточни правильную категорию последнего сообщения — это сохранит обучающий пример.")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onTrainDialogFeature("greeting") }, enabled = !conversation.isBusy) { Text("Приветствие") }
                            TextButton(onClick = { onTrainDialogFeature("question") }, enabled = !conversation.isBusy) { Text("Вопрос") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onTrainDialogFeature("thanks") }, enabled = !conversation.isBusy) { Text("Спасибо") }
                            TextButton(onClick = { onTrainDialogFeature("farewell") }, enabled = !conversation.isBusy) { Text("Прощание") }
                        }
                    }
                }
            }
            "system" -> {
                SystemStatusCard(storageReady = storageReady, notice = notice)
                RuntimeBridgePanel(
                    state = runtime, onConnect = onConnect, onRefresh = onRefresh,
                    onCreateSession = onCreateSession, onCreateTask = onCreateTask,
                    onSetSessionStatus = onSetSessionStatus, onCancelTask = onCancelTask,
                    onRunMemorySearch = onRunMemorySearch, onPreviewReasoning = onPreviewReasoning,
                    onSaveAccessToken = onSaveAccessToken, onForgetAccessToken = onForgetAccessToken,
                    onCheckExtension = onCheckExtension, onOpenTermux = onOpenTermux,
                )
            }
            else -> {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("JARVIS ANS-100 Alpha", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Офлайн-чат с отдельной языковой моделью, локальным Core, ограниченным ANS-100 runtime и локальной памятью.")
                        Text("ANS-100 и диалоговый классификатор остаются экспериментальными компонентами. Они не заменяют языковую модель и пока не являются полноценным самостоятельным общим интеллектом.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberGlowBorder(enabled: Boolean, cornerRadius: Dp): Modifier {
    if (!enabled) return Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        RoundedCornerShape(cornerRadius))
    val transition = rememberInfiniteTransition(label = "jarvis-flowing-glow")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 2_800), repeatMode = RepeatMode.Reverse),
        label = "jarvis-glow-phase")
    val brush = Brush.linearGradient(
        colors = listOf(Color(0xFF328CFF), Color(0xFF45D8E8), Color(0xFF9C75FF), Color(0xFF328CFF)),
        start = Offset(-220f + 440f * phase, 0f), end = Offset(220f + 440f * phase, 150f))
    return Modifier.border(BorderStroke(1.5.dp, brush), RoundedCornerShape(cornerRadius))
}

