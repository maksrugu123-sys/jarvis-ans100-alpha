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
                "V0.2",
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
                "Reasoning Engine и Task Loop пока не подключены к Android.",
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
