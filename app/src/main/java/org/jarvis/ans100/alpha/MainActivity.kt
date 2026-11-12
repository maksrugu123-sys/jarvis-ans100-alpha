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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jarvis.ans100.alpha.data.LocalSession
import org.jarvis.ans100.alpha.data.LocalSessionStatus
import org.jarvis.ans100.alpha.data.LocalTaskDraft
import org.jarvis.ans100.alpha.data.RuntimeBridgeUiState

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
                "V0.3",
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
) {
    var sessionTitle by rememberSaveable { mutableStateOf("") }
    var taskTitle by rememberSaveable { mutableStateOf("") }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
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
            SectionHeading("Подключение к ядру", "локальная JPVR-консоль в Termux")
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
                    enabled = !state.isBusy,
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
