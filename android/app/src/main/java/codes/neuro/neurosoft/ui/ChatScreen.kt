package codes.neuro.neurosoft.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ChatMessage
import codes.neuro.neurosoft.net.ChatRun
import codes.neuro.neurosoft.net.ChatState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LEVELS = listOf("auto" to "Авто", "fast" to "Быстро", "tools" to "Основательно", "deep" to "Глубоко")
private val LEVEL_TITLES = mapOf("fast" to "Быстро", "tools" to "Основательно", "deep" to "Глубоко")

@Composable
fun ChatScreen() {
    var state by remember { mutableStateOf<Load<ChatState>>(Load.Loading) }
    var input by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    LaunchedEffect(refresh) {
        while (true) {
            val next = try {
                Load.Ok(Api.chat())
            } catch (e: Exception) {
                if (state is Load.Ok) state else Load.Err(e.message ?: "Ошибка")
            }
            state = next
            val running = (next as? Load.Ok)?.data?.running == true
            delay(if (running || sending) 1_000 else 4_000)
        }
    }
    val count = (state as? Load.Ok)?.data?.messages?.size ?: 0
    LaunchedEffect(count) {
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        sending = true
        error = null
        scope.launch {
            try {
                Api.sendChat(text, LEVELS[level].first)
                input = ""
                refresh++
            } catch (e: Exception) {
                error = e.message
            }
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LargeTitle("Агент", subtitle = "уровни · инструменты · расширения")
        Box(Modifier.weight(1f)) {
            LoadContent(state, onRetry = { refresh++ }) { chat ->
                if (chat.messages.isEmpty()) {
                    EmptyState(NeuroIcons.Sparkles, "Спросите что угодно",
                        "Агент ищет в интернете, помнит ваши разговоры, ставит задачи и пользуется расширениями. " +
                            "Вопросы с кнопки кулона тоже появляются здесь.")
                } else {
                    LazyColumn(
                        state = list,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(chat.messages, key = { it.id }) { m ->
                            MessageBubble(m, m.runId?.let { chat.runs[it] })
                        }
                        val pendingRun = chat.runs.values.firstOrNull { r ->
                            r.status == "running" && chat.messages.none { it.role == "assistant" && it.runId == r.id }
                        }
                        if (pendingRun != null) {
                            item(key = "running") { Thinking(pendingRun) }
                        }
                    }
                }
            }
        }
        // ------------------------------------------------------------ ввод
        Column(Modifier.fillMaxWidth().background(Ios.colors.bar).padding(horizontal = 12.dp, vertical = 8.dp)) {
            SegmentedControl(LEVELS.map { it.second }, level, { level = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Ios.colors.cell)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (input.isEmpty()) {
                        Text("Сообщение агенту", color = Ios.colors.tertiary, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = input, onValueChange = { input = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ios.colors.label),
                        cursorBrush = SolidColor(Ios.colors.tint), maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(if (input.isBlank() || sending) Ios.colors.fill else Ios.colors.tint)
                        .clickable(enabled = input.isNotBlank() && !sending) { send() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending) Spinner(16)
                    else Icon(NeuroIcons.ArrowUp, "Отправить", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            error?.let { Text(it, color = Ios.colors.red, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, run: ChatRun?) {
    val mine = m.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine && run != null && run.steps.isNotEmpty()) StepsCard(run)
        Box(
            Modifier.widthIn(max = 310.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (mine) 18.dp else 5.dp, bottomEnd = if (mine) 5.dp else 18.dp))
                .background(if (mine) Ios.colors.tint else Ios.colors.bubble)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(m.text, color = if (mine) Color.White else Ios.colors.label, style = MaterialTheme.typography.bodyLarge)
        }
        val meta = listOfNotNull(
            if (m.channel == "voice") "🎙 голосом" else null,
            if (mine) run?.level?.let { LEVEL_TITLES[it] } else null,
            formatShortTime(m.createdAt),
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.labelSmall, color = Ios.colors.tertiary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun StepsCard(run: ChatRun) {
    var open by remember { mutableStateOf(false) }
    val tools = run.steps.count { it.kind == "tool" }
    Column(
        Modifier.widthIn(max = 310.dp).padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(Ios.colors.cell).clickable { open = !open }.animateContentSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            (if (run.status == "failed") "⚠️ " else "✓ ") + (LEVEL_TITLES[run.level] ?: run.level) +
                (if (tools > 0) " · шагов: $tools" else "") + (if (open) "  ▾" else "  ›"),
            style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondary,
        )
        if (open) {
            run.steps.forEach { s ->
                Text(s.title, style = MaterialTheme.typography.bodySmall,
                    color = if (s.kind == "result") Ios.colors.tertiary else Ios.colors.secondary,
                    modifier = Modifier.padding(top = 3.dp, start = if (s.kind == "result") 10.dp else 0.dp))
            }
        }
    }
}

@Composable
private fun Thinking(run: ChatRun) {
    Column(
        Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(18.dp)).background(Ios.colors.bubble)
            .padding(horizontal = 14.dp, vertical = 10.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(14)
            Spacer(Modifier.width(8.dp))
            Text("Работаю · ${LEVEL_TITLES[run.level] ?: run.level}", style = MaterialTheme.typography.labelLarge,
                color = Ios.colors.label, fontWeight = FontWeight.SemiBold)
        }
        run.steps.filter { it.kind != "result" }.takeLast(5).forEach { s ->
            Text(s.title, style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary)
        }
    }
}
