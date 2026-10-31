@file:OptIn(ExperimentalMaterial3Api::class)

package codes.neuro.neurosoft.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    // смахнутая задача удаляется только после подтверждения
    var confirm by remember { mutableStateOf<TaskItem?>(null) }
    val removedRes by Repo.removedTasks.state.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            Repo.tasks.refresh()
            delay(30_000)
        }
    }

    // удалённая свайпом задача: несколько секунд можно вернуть
    var removed by remember { mutableStateOf<TaskItem?>(null) }
    LaunchedEffect(removed) {
        if (removed != null) {
            delay(5_000)
            removed = null
        }
    }

    fun delete(t: TaskItem) {
        Repo.tasks.edit { list -> list.filter { it.id != t.id } }
        removed = t
        scope.launch {
            try {
                Api.setTaskStatus(t.id, "dismissed")
            } catch (e: Exception) {
                Repo.tasks.refresh()
            }
            if (showRemoved) Repo.removedTasks.refresh()
        }
    }

    fun restore(t: TaskItem) {
        removed = null
        Repo.tasks.edit { list -> if (list.any { it.id == t.id }) list else listOf(t) + list }
        scope.launch {
            try {
                // предложенная задача после «Вернуть» — уже обычная: владелец сам решил её оставить
                Api.setTaskStatus(t.id, if (t.status == "done") "done" else "open")
                Repo.tasks.refresh()
            } catch (e: Exception) {
                Repo.tasks.refresh()
            }
            Repo.removedTasks.edit { list -> list.filter { it.id != t.id } }
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
        Box(Modifier.fillMaxSize()) {
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
                        Grouped(i, open.size, inset = 50.dp) { SwipeToDelete({ confirm = t }) { TaskRow(t, { toggle(t) }) } }
                    }
                    if (suggested.isNotEmpty()) {
                        item(key = "suggested-title") { SectionTitle(stringResource(R.string.tasks_suggested)) }
                        itemsIndexed(suggested, key = { _, t -> "s" + t.id }) { i, t ->
                            Grouped(i, suggested.size, inset = 16.dp) {
                                SwipeToDelete({ confirm = t }) { SuggestedTaskRow(t, onAnswer = { accept ->
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
                                }) }
                            }
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
                                Grouped(i, shown.size, inset = 50.dp) {
                                    SwipeToDelete({ confirm = t }) { TaskRow(t, { toggle(t) }) }
                                }
                            }
                        }
                    }
                    // удалённые: видно, что смахнули, и можно вернуть
                    item(key = "removed-title") {
                        SectionTitle(stringResource(R.string.tasks_removed), Modifier.quietTap {
                            showRemoved = !showRemoved
                            if (showRemoved) scope.launch { Repo.removedTasks.refresh() }
                        }) {
                            Icon(if (showRemoved) Icons.ChevronDown else Icons.Chevron, null, tint = Mn.c.graphite,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                    if (showRemoved) {
                        val gone = removedRes.data
                        when {
                            gone == null && removedRes.error != null -> item(key = "removed-error") {
                                ErrorBlock(removedRes.error, { scope.launch { Repo.removedTasks.refresh() } })
                            }
                            gone == null -> item(key = "removed-loading") { LoadingBlock() }
                            gone.isEmpty() -> item(key = "removed-empty") {
                                Txt(stringResource(R.string.tasks_removed_empty), T.caption, Mn.c.graphite,
                                    Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
                            }
                            else -> itemsIndexed(gone, key = { _, t -> "r" + t.id }) { i, t ->
                                Grouped(i, gone.size, inset = 16.dp) { RemovedTaskRow(t) { restore(t) } }
                            }
                        }
                    }
                }
            }
        }
        confirm?.let { t ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                containerColor = Mn.c.paper,
                title = { Text(stringResource(R.string.task_delete_q), style = T.section, color = Mn.c.ink) },
                text = { Text(t.text, style = T.body, color = Mn.c.graphite, maxLines = 4) },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        delete(t)
                    }) { Text(stringResource(R.string.delete), color = Mn.c.red, style = T.bodyStrong) }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = null }) {
                        Text(stringResource(R.string.cancel), color = Mn.c.ink, style = T.bodyStrong)
                    }
                },
            )
        }
        // «Задача удалена · Вернуть»
        AnimatedVisibility(removed != null, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val t = removed
            Row(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mn.c.ink)
                    .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(stringResource(R.string.task_deleted), T.body, Mn.c.onInk, Modifier.weight(1f), maxLines = 1)
                Txt(stringResource(R.string.trash_restore), T.bodyStrong, Mn.c.lens, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).quietTap { t?.let { restore(it) } }
                        .padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
        }
    }
}

/**
 * Свайп влево — удалить: под строкой проступает красная корзина, на пороге — лёгкая отдача, затем вопрос
 * «Удалить задачу?» (строка возвращается на место). Вправо не смахивается — случайно не удалить.
 * Для TalkBack то же действие доступно как «Удалить задачу».
 */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val c = Mn.c
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) onDelete()
            false  // строка возвращается: удаляем только после подтверждения
        },
        positionalThreshold = { it * 0.4f },
    )
    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    LaunchedEffect(armed) { if (armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val label = stringResource(R.string.cd_delete_task)
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(label) { onDelete(); true })
        },
        backgroundContent = {
            val fromStart = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val iconScale by animateFloatAsState(if (armed) 1.15f else 0.85f, label = "trash")
            Box(
                Modifier.fillMaxSize().background(c.red).padding(horizontal = 24.dp),
                contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(Icons.Trash, null, tint = Color.White, modifier = Modifier.size(24.dp).scale(iconScale))
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().background(c.paper)) { content() }
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


/** Удалённая задача: текст зачёркнут, рядом «Вернуть». */
@Composable
private fun RemovedTaskRow(t: TaskItem, onRestore: () -> Unit) {
    val c = Mn.c
    Row(Modifier.fillMaxWidth().background(c.paper).padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(t.text, T.body.copy(textDecoration = TextDecoration.LineThrough), c.graphite, maxLines = 2)
            t.conversationTitle?.takeIf { it.isNotBlank() }?.let { Txt(it, T.caption, c.faint, maxLines = 1) }
        }
        Txt(stringResource(R.string.trash_restore), T.smallStrong, c.lens, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).quietTap(onRestore).padding(horizontal = 10.dp, vertical = 8.dp))
    }
}
