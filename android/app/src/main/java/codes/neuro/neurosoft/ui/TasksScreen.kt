package codes.neuro.neurosoft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.MemoryItem
import codes.neuro.neurosoft.net.TaskItem
import kotlinx.coroutines.launch

private val FILTERS = listOf("open" to "Открытые", "done" to "Готово", "all" to "Все")

private fun sourceLabel(source: String) = when (source) {
    "conversation" -> "💬 из разговора"
    "button" -> "🔘 кнопка"
    "voice" -> "🎙 голосом"
    "agent" -> "✨ агент"
    else -> source
}

@Composable
fun TasksScreen(onOpenConversation: (Long) -> Unit) {
    var filter by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<Load<List<TaskItem>>>(Load.Loading) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            state = try {
                Load.Ok(Api.tasks(FILTERS[filter].first))
            } catch (e: Exception) {
                Load.Err(e.message ?: "Ошибка")
            }
        }
    }
    LaunchedEffect(filter) { reload() }

    fun toggle(task: TaskItem) {
        val current = (state as? Load.Ok)?.data ?: return
        val updated = task.copy(status = if (task.status == "done") "open" else "done")
        state = Load.Ok(current.map { if (it.id == task.id) updated else it })
        scope.launch {
            try {
                Api.setTaskStatus(task.id, updated.status)
            } catch (e: Exception) {
                reload()
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        LargeTitle("Задачи", subtitle = "лента формируется сама") {
            Text("Обновить", color = Ios.colors.tint, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(8.dp).clickable { reload() })
        }
        SegmentedControl(FILTERS.map { it.second }, filter, { filter = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        LoadContent(state, onRetry = { reload() }) { tasks ->
            if (tasks.isEmpty()) {
                EmptyState(NeuroIcons.Checklist, if (filter == 0) "Открытых задач нет" else "Пусто",
                    "Агент находит задачи в разговорах, а ещё их можно поставить голосом: нажмите кнопку на кулоне " +
                        "и скажите «создай задачу…»")
            } else {
                Section(footer = "Новые задачи автоматически уходят в подключённые сервисы — " +
                    "Настройки → Расширения (Notion, GitHub и другие).") {
                    tasks.forEach { t -> row { TaskRow(t, { toggle(t) }, onOpenConversation) } }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem, onToggle: () -> Unit, onOpenConversation: (Long) -> Unit) {
    val done = t.status == "done"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp)) { RoundCheck(done, onToggle) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.text, style = MaterialTheme.typography.bodyLarge,
                color = if (done) Ios.colors.secondary else Ios.colors.label,
                textDecoration = if (done) TextDecoration.LineThrough else null)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                t.due?.let { Capsule("⏰ $it", Ios.colors.orange) }
                Text(sourceLabel(t.source), style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondary)
            }
            if (t.exports.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    t.exports.forEach { e ->
                        Capsule((e.icon ?: "🧩") + " " + (e.name ?: e.ext) + if (e.ok) " ✓" else " ✗",
                            if (e.ok) Ios.colors.green else Ios.colors.red)
                    }
                }
            }
            val convId = t.conversationId
            if (t.conversationTitle != null && convId != null) {
                Text("из «${t.conversationTitle}»", style = MaterialTheme.typography.bodySmall, color = Ios.colors.tint,
                    modifier = Modifier.clickable { onOpenConversation(convId) })
            }
        }
    }
}

/** Память: факты, решения и «запомни это». */
@Composable
fun MemoriesScreen(onBack: () -> Unit) {
    var state by remember { mutableStateOf<Load<List<MemoryItem>>>(Load.Loading) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            state = try {
                Load.Ok(Api.memories())
            } catch (e: Exception) {
                Load.Err(e.message ?: "Ошибка")
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        NavBar("Память", onBack, backLabel = "Настройки")
        LoadContent(state, onRetry = { reload() }) { items ->
            if (items.isEmpty()) {
                EmptyState(NeuroIcons.Brain, "Пока пусто", "Дважды нажмите кнопку на кулоне — «запомни это».")
            } else {
                Section(footer = "Агент учитывает память, когда отвечает на вопросы.") {
                    items.forEach { m ->
                        row {
                            ListRow(m.text, maxSubtitleLines = 1, subtitle = listOfNotNull(
                                formatDateTime(m.createdAt),
                                if (m.source == "button") "кнопка" else null,
                                m.conversationTitle,
                            ).joinToString(" · "))
                        }
                    }
                }
            }
        }
    }
}
