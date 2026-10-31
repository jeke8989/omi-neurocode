package codes.neuro.neurosoft.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ChatMessage
import codes.neuro.neurosoft.net.ChatRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Агенты чата. Mnemo — свой агент (события, люди, расшифровки, задачи, память, расширения); GPT — тот же агент
// и те же инструменты, но на модели OpenAI; Claude — Claude Code CLI на сервере по подписке владельца
// (claude — ассистент с данными Mnemo, claude_code — правит код Mnemo). Настройки -> Claude Code.
private val AGENTS = listOf("mnemo", "claude", "gpt")
private val LEVELS = listOf("auto", "fast", "tools", "deep")
private val CLAUDE_LEVELS = listOf("claude", "claude_code")
private fun isClaude(level: String) = level == "claude" || level == "claude_code"

@Composable
private fun agentTitle(agent: String): String = when (agent) {
    "claude" -> "Claude"
    "gpt" -> "GPT"
    else -> "Mnemo"
}

@Composable
private fun levelTitle(level: String): String = stringResource(when (level) {
    "fast" -> R.string.level_fast
    "tools" -> R.string.level_tools
    "deep" -> R.string.level_deep
    "claude" -> R.string.level_claude
    "claude_code" -> R.string.level_claude_code
    else -> R.string.level_auto
})

@Composable
fun ChatScreen(prefill: String?, context: String? = null, contextTitle: String? = null) {
    val res by Repo.chat.state.collectAsState()
    val claude by Repo.claude.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var agent by rememberSaveable { mutableStateOf(Core.prefs.chatAgent.takeIf { it in AGENTS } ?: "mnemo") }
    var level by rememberSaveable { mutableStateOf(Core.prefs.chatLevel.takeIf { it in LEVELS } ?: "auto") }
    var claudeLevel by rememberSaveable { mutableStateOf(Core.prefs.chatClaudeLevel.takeIf { it in CLAUDE_LEVELS } ?: "claude") }
    // с какого экрана открыт чат: агент видит это событие/день; можно убрать крестиком
    var about by rememberSaveable { mutableStateOf(context) }
    val claudeMode = agent == "claude"
    val claudeMissing = claudeMode && claude.data?.configured == false
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var prefillSent by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val c = Mn.c

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || sending) return
        if (claudeMissing) {
            Nav.push(Route.Claude)
            return
        }
        sending = true
        error = null
        scope.launch {
            try {
                Api.sendChat(t, if (claudeMode) claudeLevel else level, agent, about)
                input = ""
                Repo.chat.refresh()
            } catch (e: Exception) {
                error = e
                input = t
            }
            sending = false
        }
    }

    LaunchedEffect(claudeMode) { if (claudeMode) Repo.claude.refresh() }
    LaunchedEffect(Unit) {
        if (prefill != null && !prefillSent) {
            prefillSent = true
            send(prefill)
        }
        while (true) {
            Repo.chat.refresh()
            val running = Repo.chat.state.value.data?.running == true
            delay(if (running || sending) 1_000 else 4_000)
        }
    }
    val count = res.data?.messages?.size ?: 0
    LaunchedEffect(count) {
        if (count > 0) list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.paper,
            title = { Text(stringResource(R.string.clear_chat_q), style = T.section, color = c.ink) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        try {
                            Api.clearChat()
                            Repo.chat.refresh()
                        } catch (e: Exception) {
                            error = e
                        }
                    }
                }) { Text(stringResource(R.string.clear), color = c.red, style = T.bodyStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel), color = c.ink, style = T.bodyStrong)
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(R.string.assistant), { Nav.pop() }) {
            if (count > 0) IconCircle(Icons.Trash, stringResource(R.string.clear_chat), { confirmClear = true }, size = 40.dp)
        }
        AgentSwitch(agent) {
            agent = it
            Core.prefs.chatAgent = it
        }
        Box(Modifier.weight(1f)) {
            val chat = res.data
            when {
                chat == null && res.error != null -> ErrorBlock(res.error, { scope.launch { Repo.chat.refresh() } })
                chat == null -> LoadingBlock()
                chat.messages.isEmpty() && !sending -> ChatEmpty { send(it) }
                else -> LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(chat.messages, key = { it.id }) { m -> Message(m, m.runId?.let { chat.runs[it] }) }
                    val pending = chat.runs.values.firstOrNull { r ->
                        r.status == "running" && chat.messages.none { it.role == "assistant" && it.runId == r.id }
                    }
                    if (pending != null) item(key = "running") { Working(pending) }
                    else if (sending) item(key = "sending") { Working(null) }
                }
            }
        }
        Composer(
            input = input, onInput = { input = it },
            levels = if (claudeMode) CLAUDE_LEVELS else LEVELS,
            level = if (claudeMode) claudeLevel else level,
            onLevel = {
                if (claudeMode) {
                    claudeLevel = it
                    Core.prefs.chatClaudeLevel = it
                } else {
                    level = it
                    Core.prefs.chatLevel = it
                }
            },
            about = about?.let { contextTitle ?: it }, onDropAbout = { about = null },
            sending = sending, error = error, claudeMissing = claudeMissing, onSend = { send(input) },
        )
    }
}

/** Переключатель агентов: Mnemo, Claude, GPT — под ним одна строка о том, чем выбранный хорош. */
@Composable
private fun AgentSwitch(agent: String, onAgent: (String) -> Unit) {
    val c = Mn.c
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.paper).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AGENTS.forEach { a ->
                val on = a == agent
                Row(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(19.dp))
                        .background(if (on) c.ink else c.paper).tap({ onAgent(a) }),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (a == "mnemo") {
                        Icon(Icons.Sparkle, null, tint = if (on) c.onInk else c.lens, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Txt(agentTitle(a), T.smallStrong, if (on) c.onInk else c.ink, maxLines = 1)
                }
            }
        }
        Txt(stringResource(when (agent) {
            "claude" -> R.string.agent_claude_about
            "gpt" -> R.string.agent_gpt_about
            else -> R.string.agent_mnemo_about
        }), T.tiny, c.graphite, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 2)
    }
}

@Composable
private fun ChatEmpty(onPick: (String) -> Unit) {
    val c = Mn.c
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Lens(LensState.IDLE, 64.dp)
        Spacer(Modifier.height(12.dp))
        Txt(stringResource(R.string.chat_empty_title), T.title)
        Spacer(Modifier.height(8.dp))
        Txt(stringResource(R.string.chat_empty_text), T.body, c.graphite)
        Spacer(Modifier.height(20.dp))
        listOf(R.string.chat_hint_1, R.string.chat_hint_2, R.string.chat_hint_3).forEach { id ->
            val text = stringResource(id)
            Txt(text, T.body, c.ink, Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(c.paper).tap({ onPick(text) }).padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun Message(m: ChatMessage, run: ChatRun?) {
    val c = Mn.c
    val fmt = rememberFmt()
    val mine = m.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine && run != null && run.steps.isNotEmpty()) Steps(run)
        if (mine) {
            Txt(m.text, T.body, c.onInk, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(c.ink).padding(horizontal = 16.dp, vertical = 10.dp))
        } else {
            Txt(m.text, T.body, c.ink, Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.paper)
                .padding(horizontal = 16.dp, vertical = 12.dp))
        }
        val meta = listOfNotNull(
            if (m.channel == "voice") stringResource(R.string.by_voice) else null,
            if (mine) run?.level?.let { levelTitle(it) } else null,
            if (!mine && run != null) agentTitle(run.agent) else null,
            if (!mine && run?.status == "needs_input") stringResource(R.string.agent_asks) else null,
            fmt.time(m.createdAt),
        ).joinToString(", ")
        Txt(meta, T.tiny, c.faint, Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun Steps(run: ChatRun) {
    var open by remember { mutableStateOf(false) }
    val c = Mn.c
    val tools = run.steps.count { it.kind == "tool" }
    Column(
        Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.raised).tap({ open = !open })
            .animateContentSize().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (run.status == "failed") Icons.Info else Icons.Check, null,
                tint = if (run.status == "failed") c.amber else c.graphite, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Txt(levelTitle(run.level) + if (tools > 0) ", " + plural(R.plurals.steps, tools) else "", T.tiny, c.graphite)
            Spacer(Modifier.width(4.dp))
            Icon(if (open) Icons.ChevronDown else Icons.Chevron, null, tint = c.graphite, modifier = Modifier.size(14.dp))
        }
        if (open) {
            run.steps.forEach { s ->
                Txt(s.title, T.caption, if (s.kind == "result") c.faint else c.graphite,
                    Modifier.padding(top = 4.dp, start = if (s.kind == "result") 12.dp else 0.dp), maxLines = 3)
            }
        }
    }
}

@Composable
private fun Working(run: ChatRun?) {
    val c = Mn.c
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.paper).animateContentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lens(LensState.THINKING, 34.dp)
            Spacer(Modifier.width(6.dp))
            Txt(stringResource(R.string.working) + (run?.let {
                ", " + if (isClaude(it.level)) levelTitle(it.level)
                else agentTitle(it.agent) + " · " + levelTitle(it.level).lowercase()
            } ?: ""), T.smallStrong, modifier = Modifier.weight(1f))
            if (run != null) {
                val scope = rememberCoroutineScope()
                var stopping by remember(run.id) { mutableStateOf(false) }
                Txt(stringResource(if (stopping) R.string.voice_stopping else R.string.claude_stop), T.smallStrong,
                    c.red, Modifier.padding(horizontal = 8.dp).quietTap {
                        if (!stopping) {
                            stopping = true
                            scope.launch {
                                try {
                                    Api.cancelRun(run.id)
                                    Repo.chat.refresh()
                                } catch (_: Exception) {
                                    stopping = false
                                }
                            }
                        }
                    })
            }
        }
        run?.steps?.filter { it.kind != "result" && it.kind != "level" }?.takeLast(5)?.forEach { s ->
            Txt(s.title, T.caption, c.graphite, Modifier.padding(start = 40.dp, top = 3.dp), maxLines = 2)
        }
    }
}

@Composable
private fun Composer(
    input: String, onInput: (String) -> Unit, levels: List<String>, level: String, onLevel: (String) -> Unit,
    about: String?, onDropAbout: () -> Unit, sending: Boolean,
    error: Throwable?, claudeMissing: Boolean, onSend: () -> Unit,
) {
    val c = Mn.c
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        if (error != null) {
            Txt(errorText(error), T.caption, c.red, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (claudeMissing) {
            Txt(stringResource(R.string.claude_connect_hint), T.smallStrong, c.lens,
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp).quietTap { Nav.push(Route.Claude) })
        }
        if (about != null) {
            // о чём разговор: событие или день, с которого открыт чат
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).clip(RoundedCornerShape(16.dp))
                    .background(c.lens.copy(alpha = 0.10f)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Sparkle, null, tint = c.lens, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Txt(stringResource(R.string.chat_about, about), T.caption, c.ink, Modifier.weight(1f, fill = false), maxLines = 1)
                IconCircle(Icons.Close, stringResource(R.string.chat_about_drop), onDropAbout, size = 28.dp,
                    background = Color.Transparent, tint = c.graphite)
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(levels, key = { it }) { l ->
                Chip(when (l) {
                    "claude" -> stringResource(R.string.claude_mode_assistant)
                    "claude_code" -> stringResource(R.string.claude_mode_code)
                    else -> levelTitle(l)
                }, l == level, { onLevel(l) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(c.paper)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                if (input.isEmpty()) Txt(stringResource(R.string.ask_anything), T.body, c.faint, maxLines = 1)
                BasicTextField(
                    value = input, onValueChange = onInput, textStyle = T.body.copy(color = c.ink),
                    cursorBrush = SolidColor(c.lens), maxLines = 6, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            val enabled = input.isNotBlank() && !sending
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (enabled) c.ink else c.line)
                    .tap(onSend, enabled),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) Spinner(18.dp, c.onInk)
                else Icon(Icons.ArrowUp, stringResource(R.string.send), tint = if (enabled) c.onInk else c.faint,
                    modifier = Modifier.size(22.dp))
            }
        }
    }
}
