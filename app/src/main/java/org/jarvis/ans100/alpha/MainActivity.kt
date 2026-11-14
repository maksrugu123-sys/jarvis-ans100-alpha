package org.jarvis.ans100.alpha

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val context = LocalContext.current
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

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header()
                SystemStatusCard(storageReady = ui.storageReady, notice = ui.notice)
                ConversationPanel(
                    state = ui.conversation,
                    input = conversationInput,
                    onInputChange = { conversationInput = it.take(4_000) },
                    onSend = { message ->
                        viewModel.sendConversationMessage(message)
                        conversationInput = ""
                    },
                    onClear = viewModel::clearConversation,
                    onSelectModel = { modelLauncher.launch(arrayOf("*/*")) },
                    onDownloadModel = viewModel::downloadConversationModel,
                    onCancelDownload = viewModel::cancelConversationModelDownload,
                    onRemoveModel = viewModel::removeConversationModel,
                )
                LocalMemoryPanel(
                    state = ui.localMemory,
                    onAdd = viewModel::addLocalMemory,
                    onDelete = viewModel::deleteLocalMemory,
                )
                RuntimeBridgePanel(
                    state = ui.runtime,
                    onConnect = viewModel::connectRuntime,
                    onRefresh = viewModel::refreshRuntime,
                    onCreateSession = viewModel::createRuntimeSession,
                    onCreateTask = viewModel::createRuntimeTask,
                    onSetSessionStatus = viewModel::setRuntimeSessionStatus,
                    onCancelTask = viewModel::cancelRuntimeTask,
                    onPreviewReasoning = viewModel::previewRuntimeReasoning,
                    onSaveAccessToken = viewModel::saveRuntimeAccessToken,
                    onForgetAccessToken = viewModel::forgetRuntimeAccessToken,
                    onCheckExtension = viewModel::checkTermuxExtension,
                    onOpenTermux = viewModel::openTermux,
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
    onAdd: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
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
                enabled = state.isLoaded && !state.isBusy,
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it.take(64) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Категория") },
                singleLine = true,
                enabled = state.isLoaded && !state.isBusy,
            )
            Button(
                onClick = { onAdd(content, category) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isLoaded && !state.isBusy && content.isNotBlank() && category.isNotBlank(),
            ) {
                Text(if (state.isBusy) "Сохраняем…" else "Сохранить на устройстве")
            }
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
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
                                TextButton(onClick = { onDelete(memory.id) }, enabled = !state.isBusy) {
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
private fun Header() {
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
            )
            Text(
                "ANS-100 ALPHA · ЛОКАЛЬНОЕ ПРИЛОЖЕНИЕ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                "V0.5",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SystemStatusCard(storageReady: Boolean, notice: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
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
                "Android подключается к реестру задач и JPVR через локальный Termux-сервис. Выполнение действий пока отключено.",
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
    onPreviewReasoning: (RuntimeReasoningPreviewRequest) -> Unit,
    onSaveAccessToken: (String) -> Unit,
    onForgetAccessToken: () -> Unit,
    onCheckExtension: () -> Unit,
    onOpenTermux: () -> Unit,
) {
    var accessTokenInput by remember { mutableStateOf("") }
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
    val snapshot = state.snapshot
    val selectedSession = snapshot?.sessions?.firstOrNull {
        it.sessionId == selectedSessionId
    } ?: snapshot?.sessions?.firstOrNull { it.status == "open" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
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

@Composable
private fun ConversationPanel(
    state: LocalConversationUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onSelectModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemoveModel: () -> Unit,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeading("Диалог JARVIS", "локально · без Termux и интернета")
                    Text(
                        state.backendLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.messages.isNotEmpty()) {
                    TextButton(
                        onClick = { showClearConfirmation = true },
                        enabled = !state.isBusy,
                    ) {
                        Text("Очистить")
                    }
                }
            }
            Text(
                state.modelStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.modelInstalled) {
                Button(
                    onClick = onDownloadModel,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.modelDownloadStatus != null) "Продолжить загрузку модели"
                        else "Скачать языковую модель · около 331 МиБ",
                    )
                }
            }
            if (state.modelDownloadProgress != null) {
                LinearProgressIndicator(
                    progress = { (state.modelDownloadProgress ?: 0f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    state.modelDownloadStatus ?: "Загрузка модели…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onCancelDownload,
                    enabled = state.isBusy,
                ) {
                    Text("Остановить загрузку")
                }
            } else if (state.modelDownloadStatus != null) {
                Text(
                    state.modelDownloadStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelectModel, enabled = !state.isBusy) {
                    Text("Выбрать файл")
                }
                if (state.modelInstalled) {
                    TextButton(onClick = onRemoveModel, enabled = !state.isBusy) {
                        Text("Удалить модель")
                    }
                }
            }
            if (state.messages.isEmpty()) {
                Text(
                    "Напиши «Привет» или команду /help. История хранится в данных приложения на этом телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.messages.takeLast(8).forEach { message ->
                        val userMessage = message.role == ConversationRole.USER
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (userMessage) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    if (userMessage) "Вы" else "JARVIS",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    message.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 8,
                                )
                            }
                        }
                    }
                    if (state.messages.size > 8) {
                        Text(
                            "Показаны последние 8 сообщений из ${state.messages.size}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.isBusy) {
                Text(
                    "Локальная модель загружается или формирует ответ…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Сообщение JARVIS") },
                    maxLines = 3,
                    enabled = !state.isBusy,
                )
                Button(
                    onClick = { onSend(input) },
                    enabled = input.isNotBlank() && !state.isBusy,
                ) {
                    Text("Отправить")
                }
            }
            Text(
                if (state.modelInstalled) {
                    "Ответы создаются локальной моделью. Инструменты и выполнение действий отключены."
                } else {
                    "Без установленной модели доступен базовый режим. Скачайте Qwen3 один раз через Wi-Fi или импортируйте совместимый файл; после установки модель работает офлайн."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Очистить локальный диалог?") },
            text = { Text("История на этом телефоне будет удалена. Общая память PC/JPVR не изменится.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClear()
                    },
                ) {
                    Text("Удалить историю")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

