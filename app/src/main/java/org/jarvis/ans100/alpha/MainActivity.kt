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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisApp() }
    }
}

private data class LocalTask(val id: Int, val title: String)

@Composable
private fun JarvisApp() {
    val tasks = remember { mutableStateListOf<LocalTask>() }
    var draft by remember { mutableStateOf("") }
    var nextId by remember { mutableStateOf(1) }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("JARVIS", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold)
                    Text("ANS-100 Alpha · локальный интерфейс",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Состояние системы", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Интерфейс запущен")
                        Text("Reasoning Engine и Task Loop пока не подключены",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Новая задача", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(240) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Что нужно сделать?") },
                    placeholder = { Text("Например: подготовить план проекта") },
                    minLines = 2,
                    maxLines = 4
                )
                Button(
                    onClick = {
                        val title = draft.trim()
                        if (title.isNotEmpty() && tasks.size < 100) {
                            tasks.add(LocalTask(nextId, title))
                            nextId += 1
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank() && tasks.size < 100,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Добавить черновик") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Мои задачи", style = MaterialTheme.typography.titleMedium)
                    Text("${tasks.size}/100",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tasks.isEmpty()) {
                    Text("Задач пока нет. Добавьте первую — она появится здесь как локальный черновик.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(task.title, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(4.dp))
                                    Text("ЧЕРНОВИК · ещё не отправлена в движок",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
