@file:OptIn(ExperimentalMaterial3Api::class)

package codes.neuro.neurosoft.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.TaskItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TasksTab() {
    val res by Repo.tasks.state.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            Repo.tasks.refresh()
            delay(30_000)
        }
    }

    fun toggle(t: TaskItem) {
        val next = if (t.status == "done") "open" else "done"
        Repo.tasks.edit { list -> list.map { if (it.id == t.id) it.copy(status = next) else it } }
        scope.launch {
            try {
                Api.setTaskStatus(t.id, next)
            } catch (e: Exception) {
                Repo.tasks.edit { list -> list.map { if (it.id == t.id) it.copy(status = t.status) else it } }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; Repo.tasks.refresh(); refreshing = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        val all = res.data
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            when {
                all == null && res.error != null -> item { ErrorBlock(res.error, { scope.launch { Repo.tasks.refresh() } }) }
                all == null -> item { LoadingBlock() }
                else -> {
                    val open = all.filter { it.status == "open" }
                    val done = all.filter { it.status == "done" }
                    val suggested = all.filter { it.status == "suggested" }
                    item { StaleNote(res.error) }
                    item {
                        SectionTitle(if (open.isEmpty()) stringResource(R.string.tasks_all_done)
                        else plural(R.plurals.tasks_open, open.size))
                    }
                    if (open.isEmpty()) {
                        item {
                            EmptyBlock(Icons.Tasks, stringResource(R.string.tasks_empty_title),
                                stringResource(R.string.tasks_empty_text))
                        }
                    }
                    itemsIndexed(open, key = { _, t -> t.id }) { i, t ->
                        Grouped(i, open.size, inset = 50.dp) { TaskRow(t, { toggle(t) }) }
                    }
                    if (suggested.isNotEmpty()) {
                        item(key = "suggested-title") { SectionTitle(stringResource(R.string.tasks_suggested)) }
                        itemsIndexed(suggested, key = { _, t -> "s" + t.id }) { i, t ->
                            Grouped(i, suggested.size, inset = 16.dp) {
                                SuggestedTaskRow(t, onAnswer = { accept ->
                                    val next = if (accept) "open" else "dismissed"
                                    Repo.tasks.edit { l -> if (accept) l.map { if (it.id == t.id) it.copy(status = next) else it }
                                        else l.filter { it.id != t.id } }
                                    scope.launch {
                                        try {
                                            Api.setTaskStatus(t.id, next)
                                        } catch (e: Exception) {
                                            Repo.tasks.refresh()
                                        }
                                    }
                                })
                            }
                        }
                        item(key = "suggested-note") {
                            Txt(stringResource(R.string.tasks_suggested_note), T.caption, Mn.c.graphite,
                                Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp))
                        }
                    }
                    if (done.isNotEmpty()) {
                        item {
                            SectionTitle(stringResource(R.string.tasks_done_count, done.size),
                                Modifier.quietTap { showDone = !showDone }) {
                                Icon(if (showDone) Icons.ChevronDown else Icons.Chevron, null, tint = Mn.c.graphite,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                        if (showDone) {
                            val shown = done.take(50)
                            itemsIndexed(shown, key = { _, t -> "d" + t.id }) { i, t ->
                                Grouped(i, shown.size, inset = 50.dp) { TaskRow(t, { toggle(t) }) }
                            }
                        }
                    }
                    item {
                        Txt(stringResource(R.string.tasks_footer), T.caption, Mn.c.graphite,
                            Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TaskRow(t: TaskItem, onToggle: () -> Unit) {
    val c = Mn.c
    val done = t.status == "done"
    Row(
        Modifier.fillMaxWidth().animateContentSize().padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RoundCheck(done, onToggle, stringResource(if (done) R.string.cd_task_done else R.string.cd_task_open))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Txt(t.text, if (done) T.body.copy(textDecoration = TextDecoration.LineThrough) else T.body,
                if (done) c.graphite else c.ink, maxLines = 6)
            val source = sourceLabel(t)
            TaskWho(t)
            val due = taskDue(t)
            if (due != null || source != null) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    due?.let { due ->
                        Icon(Icons.Clock, null, tint = c.amber, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Txt(due, T.caption, c.graphite, maxLines = 1)
                        if (source != null) Spacer(Modifier.width(12.dp))
                    }
                    if (source != null) Txt(source, T.caption, c.graphite, maxLines = 1)
                }
            }
            val convId = t.conversationId
            val convTitle = t.conversationTitle
            if (convTitle != null && convId != null) {
                Txt(stringResource(R.string.from_conversation, convTitle), T.caption, c.lens,
                    Modifier.padding(top = 4.dp).quietTap { Nav.push(Route.Conversation(convId)) }, maxLines = 1)
            }
            if (t.exports.isNotEmpty()) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    t.exports.forEach { e ->
                        Txt(listOfNotNull(e.icon, e.name ?: e.ext).joinToString(" ") + if (e.ok) " ✓" else " !",
                            T.tiny, if (e.ok) c.graphite else c.red, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun sourceLabel(t: TaskItem): String? = when (t.source) {
    "button" -> stringResource(R.string.source_button)
    "voice" -> stringResource(R.string.source_voice)
    "agent" -> stringResource(R.string.source_agent)
    else -> null
}

/** Служебные подписи модели («Владелец», «Спикер 1», «Голос 3») в задачах не показываем. */
private val GENERIC_WHO = Regex("^(владелец|спикер|голос|неизвестн|owner|speaker|voice|unknown)", RegexOption.IGNORE_CASE)

/** Кто делает: «Я» — мои задачи и напоминания, «Жду от: Настя» — обещали мне. */
@Composable
private fun TaskWho(t: TaskItem) {
    val c = Mn.c
    val name = t.assignee?.takeUnless { GENERIC_WHO.containsMatchIn(it) }
    val legacyOwner = t.assignee?.startsWith("Владелец", ignoreCase = true) == true
    val pid = t.personId
    val text = when {
        t.kind == "waiting" -> stringResource(R.string.task_waiting_from, name ?: stringResource(R.string.task_someone))
        t.kind == "reminder" -> stringResource(R.string.task_reminder)
        name != null -> name
        pid != null || legacyOwner -> stringResource(R.string.people_me)
        else -> return
    }
    Row(Modifier.padding(top = 4.dp).then(if (pid != null) Modifier.quietTap { Nav.push(Route.Person(pid)) } else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (t.kind == "reminder") Icons.Bell else Icons.Person, null,
            tint = if (pid != null) c.lens else c.graphite, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Txt(text, T.caption, if (pid != null) c.lens else c.graphite, maxLines = 1)
    }
}

/** Срок: «Завтра, 10:00» по настоящему времени, иначе как сказали («в понедельник»). */
@Composable
fun taskDue(t: TaskItem): String? {
    val fmt = rememberFmt()
    val at = t.dueAt ?: return t.due
    val time = fmt.time(at)
    return fmt.dayShortTitle(fmt.dayOf(at)) + if (time.isNotBlank() && time != "00:00") ", $time" else ""
}

@Composable
private fun SuggestedTaskRow(t: TaskItem, onAnswer: (Boolean) -> Unit) {
    val c = Mn.c
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(t.text, T.body, maxLines = 4)
            t.evidence?.let {
                Txt(stringResource(R.string.quoted, it), T.caption.copy(fontStyle = FontStyle.Italic), c.graphite,
                    Modifier.padding(top = 3.dp), maxLines = 2)
            }
            taskDue(t)?.let { Txt(it, T.caption, c.graphite, Modifier.padding(top = 3.dp), maxLines = 1) }
        }
        IconCircle(Icons.Check, stringResource(R.string.task_accept), { onAnswer(true) }, size = 38.dp,
            background = c.raised)
        Spacer(Modifier.width(6.dp))
        IconCircle(Icons.Close, stringResource(R.string.task_dismiss), { onAnswer(false) }, size = 38.dp,
            background = c.raised, tint = c.graphite)
    }
}
