package codes.neuro.neurosoft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ConversationDetail
import codes.neuro.neurosoft.net.ConversationItem
import kotlinx.coroutines.launch

private fun statusLabel(status: String) = when (status) {
    "open" -> "идёт или ждёт обработки"
    "processing" -> "агент подводит итоги…"
    "failed" -> "ошибка обработки"
    else -> null
}

/** Все разговоры, сгруппированные по дням. */
@Composable
fun ConversationsList(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Load<List<ConversationItem>>>(Load.Loading) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            state = try {
                Load.Ok(Api.conversations(150))
            } catch (e: Exception) {
                Load.Err(e.message ?: "Ошибка")
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavBar("Разговоры", onBack, backLabel = "Настройки")
        LoadContent(state, onRetry = { reload() }) { items ->
            if (items.isEmpty()) {
                EmptyState(NeuroIcons.Bubble, "Пока пусто", "Разговор появится через пару минут после записи.")
            }
            items.groupBy { it.startedAt.take(10) }.forEach { (day, list) ->
                Section(dayTitle(day)) {
                    list.forEach { c ->
                        row {
                            ListRow(
                                c.title ?: c.preview ?: "Без названия",
                                subtitle = formatShortTime(c.startedAt) + "–" + formatShortTime(c.endedAt) +
                                    (statusLabel(c.status)?.let { " · $it" } ?: "") +
                                    (if (c.tasksCount > 0) " · задач: ${c.tasksCount}" else ""),
                                chevron = true, onClick = { onOpen(c.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationScreen(id: Long, onBack: () -> Unit) {
    var state by remember(id) { mutableStateOf<Load<ConversationDetail>>(Load.Loading) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            state = try {
                Load.Ok(Api.conversation(id))
            } catch (e: Exception) {
                Load.Err(e.message ?: "Ошибка")
            }
        }
    }
    LaunchedEffect(id) { reload() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить разговор?") },
            text = { Text("Расшифровка и итоги удалятся с сервера. Задачи останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            Api.deleteConversation(id)
                            onBack()
                        } catch (e: Exception) {
                            note = "Не удалось удалить: ${e.message}"
                        }
                    }
                }) { Text("Удалить", color = Ios.colors.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavBar("Разговор", onBack)
        LoadContent(state, onRetry = { reload() }) { d ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(d.item.title ?: "Без названия", style = MaterialTheme.typography.headlineMedium,
                    color = Ios.colors.label)
                Text(
                    formatDateTime(d.item.startedAt) + " – " + formatShortTime(d.item.endedAt) +
                        (statusLabel(d.item.status)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary,
                )
                note?.let { Text(it, color = Ios.colors.red, style = MaterialTheme.typography.bodySmall) }
            }
            d.item.summary?.let { summary ->
                Section("Итог") { row { Text(summary, style = MaterialTheme.typography.bodyLarge,
                    color = Ios.colors.label, modifier = Modifier.padding(16.dp)) } }
            }
            if (d.tasks.isNotEmpty()) {
                Section("Задачи") {
                    d.tasks.forEach { t ->
                        row {
                            var done by remember(t.id) { mutableStateOf(t.status == "done") }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RoundCheck(done, {
                                    val v = !done
                                    done = v
                                    scope.launch {
                                        try {
                                            Api.setTaskStatus(t.id, if (v) "done" else "open")
                                        } catch (e: Exception) {
                                            done = !v
                                        }
                                    }
                                })
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.text, style = MaterialTheme.typography.bodyLarge, color = Ios.colors.label)
                                    val meta = listOfNotNull(t.due?.let { "срок: $it" }, t.assignee)
                                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary)
                                }
                            }
                        }
                    }
                }
            }
            if (d.memories.isNotEmpty()) {
                Section("Запомнить") {
                    d.memories.forEach { m -> row { ListRow(m.text, icon = NeuroIcons.Brain, iconColor = Ios.colors.pink) } }
                }
            }
            Section("Расшифровка") {}
            if (d.segments.isEmpty()) {
                Text("Нет текста", color = Ios.colors.secondary, modifier = Modifier.padding(horizontal = 32.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var lastSpeaker: Int? = -1
                d.segments.forEach { s ->
                    val left = (s.speaker ?: 0) % 2 == 0
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (left) Alignment.Start else Alignment.End) {
                        if (s.speaker != lastSpeaker) {
                            Text(
                                (s.speaker?.let { "Спикер ${it + 1}" } ?: "Голос") + " · " + formatTime(s.startedAt),
                                style = MaterialTheme.typography.labelSmall, color = Ios.colors.secondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            s.text, style = MaterialTheme.typography.bodyLarge,
                            color = Ios.colors.label,
                            modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (left) Ios.colors.cell else Ios.colors.tint.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    lastSpeaker = s.speaker
                }
            }
            Section {
                row {
                    ListRow("Пересчитать итоги", icon = NeuroIcons.Sparkles, iconColor = Ios.colors.indigo, onClick = {
                        scope.launch {
                            note = try {
                                Api.reprocessConversation(id)
                                "Агент пересчитает итоги в течение минуты"
                            } catch (e: Exception) {
                                "Ошибка: ${e.message}"
                            }
                        }
                    })
                }
                row { ListRow("Удалить разговор", titleColor = Ios.colors.red, onClick = { confirmDelete = true }) }
            }
        }
    }
}
