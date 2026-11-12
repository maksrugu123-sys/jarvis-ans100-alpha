package org.jarvis.ans100.alpha

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jarvis.ans100.alpha.data.LocalSession
import org.jarvis.ans100.alpha.data.LocalSessionStatus
import org.jarvis.ans100.alpha.data.LocalTaskDraft
import org.jarvis.ans100.alpha.data.RuntimeBridgeUiState
import org.jarvis.ans100.alpha.data.RuntimePlanningAction
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
    var sessionTitle by rememberSaveable { mutableStateOf("") }
    var taskTitle by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }

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
            SectionHeading("Подключение к ядру", "защищённая локальная JPVR-консоль в Termux")
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
            Text(
                if (state.hasAccessToken) {
                    "Токен хранится в зашифрованном виде через Android Keystore."
                } else {
                    "Скопируйте токен из Termux при первом запуске консоли. Не отправляйте его другим людям."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    state.isBusy -> "Связь с JARVIS проверяется…"
                    state.isConnected && snapshot != null ->
                        "Подключено · JPVR checkpoint ${snapshot.checkpointVersion}"
                    else -> "Не подключено · локальные черновики Android остаются отдельно"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.isConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !state.isBusy && state.hasAccessToken,
                    onClick = if (state.isConnected) onRefresh else onConnect,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            state.isBusy -> "Подключение…"
                            state.isConnected -> "Обновить состояние"
                            else -> "Подключить JARVIS"
                        }
                    )
                }
                if (state.isConnected) {
                    OutlinedButton(enabled = !state.isBusy, onClick = onConnect) {
                        Text("Проверить")
                    }
                }
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
