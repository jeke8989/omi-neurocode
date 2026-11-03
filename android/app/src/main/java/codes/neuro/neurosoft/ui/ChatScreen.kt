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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import codes.neuro.neurosoft.net.AssistantRole
import codes.neuro.neurosoft.net.ChatMessage
import codes.neuro.neurosoft.net.ChatRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Агенты чата: Mnemo — свой агент (события, люди, расшифровки, задачи, память, расширения), модель выбирается
// сама по сложности вопроса; Claude и GPT — тот же агент с теми же инструментами на моделях Anthropic и OpenAI.

@Composable
private fun agentTitle(agent: String): String = when (agent) {
    "claude" -> "Claude"
    "gpt" -> "GPT"
    else -> "Mnemo"
}


@Composable
fun ChatScreen(prefill: String?, context: String? = null, contextTitle: String? = null) {
    val res by Repo.chat.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    // модель ассистент выбирает сам (под каждый вопрос — подходящую); выбора агентов в чате нет
    val agent = "mnemo"
    // с какого экрана открыт чат: агент видит это событие/день; можно убрать крестиком
    var about by rememberSaveable { mutableStateOf(context) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var prefillSent by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val c = Mn.c
    // роль ассистента: тот же Mnemo с вашими данными, но другой характер и фокус
    var role by rememberSaveable { mutableStateOf(Core.prefs.chatRole) }
    val rolesRes by Repo.roles.state.collectAsState()
    val roles = rolesRes.data.orEmpty()
    val current = roles.firstOrNull { it.id == role }
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AssistantRole?>(null) }
    // стартовые подсказки — под роль и из накопленного (каждый раз другие)
    var starters by remember(role) { mutableStateOf<List<String>?>(null) }
    var switched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.roles.refresh() }
    LaunchedEffect(rolesRes.data) {
        // своя роль удалена на другом устройстве — назад к Mnemo
        if (rolesRes.data != null && current == null && role != "mnemo") {
            role = "mnemo"
            Core.prefs.chatRole = "mnemo"
        }
    }
    LaunchedEffect(role) {
        starters = try {
            Api.chatStarters(role)
        } catch (_: Exception) {
            emptyList()
        }
    }
    fun pickRole(id: String) {
        if (id != role) switched = true
        role = id
        Core.prefs.chatRole = id
        picker = false
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || sending) return
        sending = true
        error = null
        scope.launch {
            try {
                Api.sendChat(t, "auto", agent, about, role)
                input = ""
                switched = false
                Repo.chat.refresh()
            } catch (e: Exception) {
                error = e
                input = t
            }
            sending = false
        }
    }

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

    if (picker) {
        RolePicker(roles, role, onPick = { pickRole(it) }, onEdit = { picker = false; editing = it },
            onCreate = { picker = false; editing = AssistantRole("", "✨", "", "", "", true) },
            onDismiss = { picker = false })
    }
    editing?.let { r ->
        RoleEditor(r, onDismiss = { editing = null }, onSaved = { id ->
            editing = null
            scope.launch {
                Repo.roles.refresh()
                if (id != null) pickRole(id) else if (r.id == role) pickRole("mnemo")
                starters = try { Api.chatStarters(role) } catch (_: Exception) { emptyList() }
            }
        })
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(R.string.assistant), { Nav.pop() }) {
            if (count > 0) IconCircle(Icons.Trash, stringResource(R.string.clear_chat), { confirmClear = true }, size = 40.dp)
        }
        val chat = res.data
        val running = chat?.runs?.values?.any { it.status == "running" } == true
        Box(Modifier.weight(1f)) {
            when {
                chat == null && res.error != null -> ErrorBlock(res.error, { scope.launch { Repo.chat.refresh() } })
                chat == null -> LoadingBlock()
                chat.messages.isEmpty() && !sending -> ChatEmpty(current, starters) { send(it) }
                else -> LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(chat.messages, key = { it.id }) { m ->
                        val run = m.runId?.let { chat.runs[it] }
                        Message(m, run, run?.let { r -> roles.firstOrNull { it.id == r.role } })
                    }
                    val pending = chat.runs.values.firstOrNull { r ->
                        r.status == "running" && chat.messages.none { it.role == "assistant" && it.runId == r.id }
                    }
                    if (pending != null) item(key = "running") { Working(pending) }
                    else if (sending) item(key = "sending") { Working(null) }
                }
            }
        }
        // под последним ответом — следующие вопросы; сменили роль — её стартовые подсказки
        val last = chat?.messages?.lastOrNull()
        val follow = last?.takeIf { it.role == "assistant" }?.runId?.let { chat?.runs?.get(it) }?.followups.orEmpty()
        val chips = when {
            chat == null || chat.messages.isEmpty() || sending || running || input.isNotEmpty() -> emptyList()
            switched -> starters.orEmpty()
            else -> follow
        }.distinct()
        if (chips.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)) {
                items(chips, key = { it }) { q -> Chip(q, false, { send(q) }) }
            }
        }
        Composer(
            input = input, onInput = { input = it },
            about = about?.let { contextTitle ?: it }, onDropAbout = { about = null },
            sending = sending, error = error, onSend = { send(input) },
            role = current, onRole = { picker = true; scope.launch { Repo.roles.refresh() } },
        )
    }
}

/** Пустой чат: кто сейчас отвечает (роль) и стартовые подсказки из ваших данных. */
@Composable
private fun ChatEmpty(role: AssistantRole?, starters: List<String>?, onPick: (String) -> Unit) {
    val c = Mn.c
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        if (role == null || role.id == "mnemo") Lens(LensState.IDLE, 64.dp) else EmojiTile(role.emoji, 64.dp)
        Spacer(Modifier.height(12.dp))
        Txt(if (role == null || role.id == "mnemo") stringResource(R.string.chat_empty_title) else role.name, T.title)
        if (role != null && role.id != "mnemo" && role.about.isNotBlank()) {
            Txt(role.about, T.small, c.graphite, Modifier.padding(top = 4.dp), maxLines = 2)
        }
        Spacer(Modifier.height(20.dp))
        val items = when {
            starters == null -> null
            starters.isEmpty() -> listOf(R.string.chat_hint_1, R.string.chat_hint_2, R.string.chat_hint_3).map { stringResource(it) }
            else -> starters
        }
        if (items == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Spinner() }
        } else {
            items.forEach { text ->
                Txt(text, T.body, c.ink, Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(c.paper).tap({ onPick(text) }).padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

/** Выбор роли: встроенные и свои; своя — карандашом правится. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RolePicker(
    roles: List<AssistantRole>, selected: String, onPick: (String) -> Unit, onEdit: (AssistantRole) -> Unit,
    onCreate: () -> Unit, onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = Mn.c
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.fog,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.line)) }) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Txt(stringResource(R.string.role_title), T.title, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp)) }
            if (roles.isEmpty()) item { LoadingBlock() }
            items(roles, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().tap({ onPick(r.id) }).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EmojiTile(r.emoji, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(r.name, T.bodyStrong, maxLines = 1)
                        if (r.about.isNotBlank()) Txt(r.about, T.caption, c.graphite, maxLines = 2)
                    }
                    if (r.custom) IconCircle(Icons.Edit, stringResource(R.string.role_edit), { onEdit(r) }, size = 36.dp,
                        background = Color.Transparent, tint = c.graphite)
                    if (r.id == selected) Icon(Icons.Check, null, tint = c.lens, modifier = Modifier.padding(start = 4.dp).size(20.dp))
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().tap(onCreate).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.ink), contentAlignment = Alignment.Center) {
                        Icon(Icons.Plus, null, tint = c.onInk, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Txt(stringResource(R.string.role_create), T.bodyStrong, modifier = Modifier.weight(1f))
                    Icon(Icons.Chevron, null, tint = c.faint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Своя роль: эмодзи, название, характер (как «личность чата»). onSaved(id) — id роли, null — удалена. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleEditor(role: AssistantRole, onDismiss: () -> Unit, onSaved: (String?) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = Mn.c
    val scope = rememberCoroutineScope()
    var emoji by remember { mutableStateOf(role.emoji) }
    var name by remember { mutableStateOf(role.name) }
    var persona by remember { mutableStateOf(role.persona.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val isNew = role.id.isBlank()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.fog,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.line)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Txt(stringResource(if (isNew) R.string.role_new else R.string.role_edit), T.title,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp))
            Group(inset = 18.dp) {
                item { FieldRow(stringResource(R.string.role_emoji), emoji, { emoji = it.take(8) }, "✨") }
                item { FieldRow(stringResource(R.string.role_name), name, { name = it.take(40) }, stringResource(R.string.role_name_hint)) }
                item {
                    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                        Txt(stringResource(R.string.role_persona), T.caption, c.graphite)
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 96.dp)) {
                            if (persona.isEmpty()) Txt(stringResource(R.string.role_persona_hint), T.body, c.faint)
                            BasicTextField(
                                value = persona, onValueChange = { persona = it.take(2000) },
                                textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.lens),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            error?.let { Txt(errorText(it), T.caption, c.red, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            PrimaryButton(stringResource(R.string.save), {
                busy = true
                scope.launch {
                    try {
                        onSaved(Api.saveRole(role.id.ifBlank { null }, name.trim(), emoji.trim().ifBlank { "✨" }, persona.trim()))
                    } catch (e: Exception) {
                        error = e
                    }
                    busy = false
                }
            }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                enabled = !busy && name.isNotBlank() && persona.isNotBlank())
            if (!isNew) {
                Txt(stringResource(R.string.role_delete), T.bodyStrong, c.red, Modifier.align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).quietTap {
                        scope.launch {
                            try {
                                Api.deleteRole(role.id)
                                onSaved(null)
                            } catch (e: Exception) {
                                error = e
                            }
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun Message(m: ChatMessage, run: ChatRun?, role: AssistantRole?) {
    val c = Mn.c
    val fmt = rememberFmt()
    val mine = m.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine && run != null && run.steps.isNotEmpty()) Steps(run)
        if (mine) {
            Txt(m.text, T.body, c.onInk, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(c.ink).padding(horizontal = 16.dp, vertical = 10.dp))
        } else {
            // ответ ассистента: абзацы, списки, жирный и ссылки на события, разговоры и дни
            Markdown(m.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.paper)
                .padding(horizontal = 16.dp, vertical = 12.dp))
        }
        val meta = listOfNotNull(
            if (m.channel == "voice") stringResource(R.string.by_voice) else null,
            if (!mine && run != null && run.agent != "mnemo") agentTitle(run.agent) else null,
            if (!mine && run != null && run.role != "mnemo") role?.let { "${it.emoji} ${it.name}" } else null,
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
            Txt(if (tools > 0) plural(R.plurals.steps, tools) else stringResource(R.string.answer_direct), T.tiny, c.graphite)
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
            Txt(stringResource(R.string.working) + (run?.takeIf { it.agent != "mnemo" }?.let { ", " + agentTitle(it.agent) } ?: ""),
                T.smallStrong, modifier = Modifier.weight(1f))
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
    input: String, onInput: (String) -> Unit, about: String?, onDropAbout: () -> Unit, sending: Boolean,
    error: Throwable?, onSend: () -> Unit, role: AssistantRole?, onRole: () -> Unit,
) {
    val c = Mn.c
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        if (error != null) {
            Txt(errorText(error), T.caption, c.red, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // роль ассистента: «🎯 Коуч обещаний ⌄»
            Row(
                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(c.paper)
                    .tap(onRole).padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(role?.emoji ?: "🧠", T.small)
                Spacer(Modifier.width(6.dp))
                Txt(role?.name ?: "Mnemo", T.smallStrong, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
                Icon(Icons.ChevronDown, stringResource(R.string.role_title), tint = c.graphite,
                    modifier = Modifier.padding(start = 2.dp).size(16.dp))
            }
            if (about != null) {
                Spacer(Modifier.width(8.dp))
                // о чём разговор: событие или день, с которого открыт чат
                Row(
                    Modifier.weight(1f, fill = false).clip(RoundedCornerShape(16.dp))
                        .background(c.lens.copy(alpha = 0.10f)).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Sparkle, null, tint = c.lens, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Txt(stringResource(R.string.chat_about, about), T.caption, c.ink, Modifier.weight(1f, fill = false), maxLines = 1)
                    IconCircle(Icons.Close, stringResource(R.string.chat_about_drop), onDropAbout, size = 28.dp,
                        background = Color.Transparent, tint = c.graphite)
                }
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.Bottom) {
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
