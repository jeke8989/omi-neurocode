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
    // что показать (все / я обещал / мне обещали / встречи / напоминания) и как разбить: по дню, когда обещание
    // появилось (по умолчанию), или по сроку
    var filter by rememberSaveable { mutableStateOf("all") }
    var byDue by rememberSaveable { mutableStateOf(codes.neuro.neurosoft.Core.prefs.promisesByDue) }
    val names = GroupNames(stringResource(R.string.promises_hot), stringResource(R.string.promises_overdue),
        stringResource(R.string.today), stringResource(R.string.yesterday), stringResource(R.string.group_tomorrow),
        stringResource(R.string.group_this_week), stringResource(R.string.group_later), stringResource(R.string.due_none))
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

    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var dueFor by remember { mutableStateOf<TaskItem?>(null) }
    // «Напомнить ему»: черновик сообщения (null — готовится)
    var nudgeFor by remember { mutableStateOf<TaskItem?>(null) }
    var nudgeText by remember { mutableStateOf<String?>(null) }
    var nudgeError by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // «Другой срок» из уведомления: открыть выбор срока у этого обещания
    val askDue = HomeState.dueTask.value
    LaunchedEffect(askDue, res.data) {
        val id = askDue ?: return@LaunchedEffect
        res.data?.firstOrNull { it.id == id }?.let {
            editing = it  // «Другой срок» из уведомления — карточка обещания, где срок и всё остальное
            HomeState.dueTask.value = null
        }
    }

    val askNudge = HomeState.nudgeTask.value
    LaunchedEffect(askNudge, res.data) {
        val id = askNudge ?: return@LaunchedEffect
        res.data?.firstOrNull { it.id == id }?.let {
            HomeState.nudgeTask.value = null
            nudgeFor = it
            nudgeText = null
            nudgeError = false
            try {
                nudgeText = Api.nudge(it.id)
            } catch (e: Exception) {
                nudgeError = true
            }
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
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                Repo.tasks.saving { Api.setTaskStatus(t.id, "dismissed") }
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

    // галочка: сразу на экране; повторное нажатие, пока первое ещё сохраняется, не отменяет его
    val saving = remember { mutableStateMapOf<Long, String>() }
    fun toggle(t: TaskItem) {
        val current = Repo.tasks.state.value.data?.firstOrNull { it.id == t.id }?.status ?: t.status
        val next = if (current == "done") "open" else "done"
        if (saving[t.id] == next) return
        saving[t.id] = next
        Repo.tasks.edit { list -> list.map { if (it.id == t.id) it.copy(status = next) else it } }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                Repo.tasks.saving { Api.setTaskStatus(t.id, next) }
            } catch (e: Exception) {
                Repo.tasks.edit { list -> list.map { if (it.id == t.id) it.copy(status = current) else it } }
            } finally {
                if (saving[t.id] == next) saving.remove(t.id)
            }
        }
    }

    fun setDue(t: TaskItem, ms: Long?) {
        val iso = ms?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString() }
        Repo.tasks.edit { list -> list.map { if (it.id == t.id) it.copy(dueAt = iso, due = null) else it } }
        scope.launch {
            try {
                Api.setTaskDue(t.id, ms)
            } catch (e: Exception) {
            }
            Repo.tasks.refresh()
        }
    }

    fun nudge(t: TaskItem) {
        nudgeFor = t
        nudgeText = null
        nudgeError = false
        scope.launch {
            try {
                nudgeText = Api.nudge(t.id)
            } catch (e: Exception) {
                nudgeError = true
            }
        }
    }

    dueFor?.let { t -> DueDialog(t, onDismiss = { dueFor = null }, onPick = { ms -> dueFor = null; setDue(t, ms) }) }
    nudgeFor?.let { t ->
        DraftDialog(stringResource(R.string.promise_nudge_title, t.mate ?: stringResource(R.string.promise_someone)), null,
            nudgeText, nudgeError, onDismiss = { nudgeFor = null })
    }
    editing?.let { t ->
        PromiseSheet(t, onDismiss = { editing = null }, onSave = { edit ->
            editing = null
            scope.launch {
                try {
                    val id = if (t.id == 0L) Api.createPromise(edit.copy(text = edit.text ?: t.text))
                    else t.id.also { Api.updatePromise(it, edit) }
                    // календарь — заодно: добавить, перенести вместе со сроком или убрать
                    val dueMs = if (edit.clearDue) null else edit.dueAt ?: t.dueMillis()
                    val final = t.copy(id = id, text = edit.text ?: t.text, dueAt = dueMs?.let {
                        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString() })
                    val changed = edit.calendar != t.inCalendar || edit.calendarMinutes != (t.calendarMinutes ?: edit.calendarMinutes) ||
                        edit.dueAt != null || edit.clearDue || edit.text != null
                    if (id > 0 && changed && (edit.calendar || t.calendarEventId != null)) {
                        PromiseActions.syncCalendar(context, final, edit.calendar, edit.calendarMinutes)
                    }
                } catch (_: Exception) {
                }
                Repo.tasks.refresh()  // новый срок — сразу в напоминания
                if (edit.nameVoice != null) Repo.people.refresh()
            }
        }, onNudge = { nudge(it) }, onDone = {
            editing = null
            toggle(t)
        })
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
                    val now = System.currentTimeMillis()
                    val active = all.filter { it.status == "open" || it.status == "suggested" }
                    val counts = active.groupingBy { promiseGroupKind(it) }.eachCount()
                    fun keep(t: TaskItem) = filter == "all" || promiseGroupKind(t) == filter
                    val open = all.filter { it.status == "open" && keep(it) }
                    val done = all.filter { it.status == "done" && keep(it) }
                    val suggested = all.filter { it.status == "suggested" && keep(it) }
                    val groups = if (byDue) groupByDue(open, names, now) else groupByCreated(open, names)
                    item { StaleNote(res.error) }
                    item(key = "stats") { PromiseStats(all) }
                    // своё обещание руками: «вернуть Олегу книгу до пятницы»
                    item(key = "add") {
                        SecondaryButton(stringResource(R.string.promise_new), { editing = blankPromise() },
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), icon = Icons.Plus)
                    }
                    if (active.isNotEmpty()) {
                        item(key = "filters") {
                            PromiseFilters(counts, active.size, filter, { filter = it }, byDue, {
                                byDue = it
                                codes.neuro.neurosoft.Core.prefs.promisesByDue = it
                            })
                        }
                    }
                    if (open.isEmpty() && suggested.isEmpty()) {
                        item {
                            if (filter == "all") EmptyBlock(Icons.Tasks, stringResource(R.string.promises_empty_title),
                                stringResource(R.string.promises_empty_text))
                            else Txt(stringResource(R.string.filter_empty), T.body, Mn.c.graphite,
                                Modifier.padding(horizontal = 22.dp, vertical = 18.dp))
                        }
                    }
                    // сначала «горит», потом «это обещание?», потом остальное по дням (или по срокам)
                    val (first, rest) = groups.partition { it.hot }
                    fun section(g: PromiseGroup) {
                        item(key = "title-${g.key}") { SectionTitle(g.title + "  " + g.items.size) }
                        itemsIndexed(g.items, key = { _, t -> t.id }) { i, t ->
                            Grouped(i, g.items.size, inset = 54.dp) {
                                SwipeToDelete({ confirm = t }) {
                                    PromiseRow(t, { editing = t }, { toggle(t) })
                                }
                            }
                        }
                    }
                    first.forEach { section(it) }
                    if (suggested.isNotEmpty()) {
                        item(key = "suggested-title") { SectionTitle(stringResource(R.string.promises_check)) }
                        itemsIndexed(suggested, key = { _, t -> "s" + t.id }) { i, t ->
                            Grouped(i, suggested.size, inset = 16.dp) {
                                SwipeToDelete({ confirm = t }) { SuggestedTaskRow(t, onAnswer = { accept ->
                                    val next = if (accept) "open" else "dismissed"
                                    Repo.tasks.edit { l -> if (accept) l.map { if (it.id == t.id) it.copy(status = next) else it }
                                        else l.filter { it.id != t.id } }
                                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                        try {
                                            Repo.tasks.saving { Api.setTaskStatus(t.id, next) }
                                        } catch (e: Exception) {
                                            Repo.tasks.refresh()
                                        }
                                    }
                                }) }
                            }
                        }
                    }
                    rest.forEach { section(it) }
                    if (done.isNotEmpty()) {
                        item {
                            SectionTitle(stringResource(R.string.promises_done_count, done.size),
                                Modifier.quietTap { showDone = !showDone }) {
                                Icon(if (showDone) Icons.ChevronDown else Icons.Chevron, null, tint = Mn.c.graphite,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                        if (showDone) {
                            val shown = done.take(50)
                            itemsIndexed(shown, key = { _, t -> "d" + t.id }) { i, t ->
                                Grouped(i, shown.size, inset = 54.dp) {
                                    SwipeToDelete({ confirm = t }) {
                                        PromiseRow(t, { editing = t }, { toggle(t) })
                                    }
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
                    t.exports.forEach { e -> Txt(exportLabel(e), T.tiny, if (e.ok && e.error == null) c.graphite else c.red, maxLines = 1) }
                }
            }
        }
    }
}

/** «🗂 SmartDesk ✓», «… закрыта», «… !» — отправлена, закрыта там или не дошла. */
@Composable
fun exportLabel(e: codes.neuro.neurosoft.net.TaskExport): String =
    listOfNotNull(e.icon, e.name ?: e.ext).joinToString(" ") + when {
        !e.ok -> " !"
        e.error != null -> " !"
        e.state == "closed" && e.canClose -> " · " + stringResource(R.string.task_closed_there)
        else -> " ✓"
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
            promiseWho(t)?.let { Txt(it, T.caption, c.graphite, Modifier.padding(top = 3.dp), maxLines = 1) }
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

// ======================================================================= обещания

/** Кто и кому: «Ивану», «Иван обещал вам», «Напоминание себе». */
@Composable
fun promiseWho(t: TaskItem): String? = when {
    t.kind == "reminder" -> stringResource(R.string.task_reminder)
    t.kind == "meeting" -> t.mate?.let { stringResource(R.string.promise_meeting_with, it) } ?: stringResource(R.string.promise_meeting)
    t.theirs -> stringResource(R.string.promise_from, t.mate ?: stringResource(R.string.promise_someone))
    t.mate != null -> stringResource(R.string.promise_to, t.mate!!)
    else -> null
}

/** Обещание: что, кому (или кто вам), к какому сроку. Нажатие — карточка, где всё правится. */
@Composable
fun PromiseRow(t: TaskItem, onOpen: () -> Unit, onToggle: () -> Unit) {
    val c = Mn.c
    val done = t.status == "done"
    val overdue = !done && (t.dueMillis() ?: Long.MAX_VALUE) < System.currentTimeMillis()
    Row(
        Modifier.fillMaxWidth().quietTap(onOpen).padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RoundCheck(done, onToggle, stringResource(if (done) R.string.cd_task_done else R.string.cd_task_open))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Txt(t.text, if (done) T.body.copy(textDecoration = TextDecoration.LineThrough) else T.body,
                if (done) c.graphite else c.ink, maxLines = 3)
            val who = promiseWho(t)
            val due = taskDue(t)
            if (who != null || due != null || t.inCalendar) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (who != null) {
                        // встреча — значок календаря (синий, если она уже в календаре телефона)
                        Icon(when (t.kind) {
                            "reminder" -> Icons.Bell
                            "meeting" -> Icons.Calendar
                            else -> Icons.Person
                        }, null, tint = if (t.kind == "meeting" && t.inCalendar) c.lens else c.graphite,
                            modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Txt(who, T.caption, c.graphite, maxLines = 1)
                        if (due != null) Spacer(Modifier.width(12.dp))
                    }
                    due?.let {
                        Icon(Icons.Clock, null, tint = if (overdue) c.red else c.amber, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        val long = if (t.kind == "meeting") (t.calendarMinutes ?: codes.neuro.neurosoft.Core.prefs.calendarDefaultMin)
                            .let { m -> " · " + if (m < 60 || m % 60 != 0) stringResource(R.string.minutes_n, m)
                                else stringResource(R.string.hours_n, m / 60) } else ""
                        Txt(it + long, T.caption, if (overdue) c.red else c.graphite, maxLines = 1)
                    }
                    if (t.inCalendar && t.kind != "meeting") {  // в календаре телефона
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Calendar, stringResource(R.string.promise_in_calendar), tint = c.lens, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Icon(Icons.Chevron, null, tint = c.faint, modifier = Modifier.padding(top = 12.dp).size(16.dp))
    }
}

/** Итог: сколько пообещали сегодня и сколько выполнено за неделю — измеримая польза. */
@Composable
private fun PromiseStats(all: List<TaskItem>) {
    val c = Mn.c
    val now = System.currentTimeMillis()
    fun created(t: TaskItem) = try {
        java.time.OffsetDateTime.parse(t.createdAt).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }
    val startOfDay = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val counted = all.filter { it.status == "open" || it.status == "done" }
    val today = counted.count { created(it) >= startOfDay && !it.theirs }
    val week = counted.filter { created(it) >= now - 7 * 24 * 3600_000L }
    val weekDone = week.count { it.status == "done" }
    if (week.isEmpty() && today == 0) return
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(c.paper).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(R.string.promises_today, today), T.bodyStrong)
            Txt(stringResource(R.string.promises_week, weekDone, week.size), T.caption, c.graphite)
        }
        // кольцо выполнения за неделю
        val share = if (week.isEmpty()) 0f else weekDone.toFloat() / week.size
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawArc(c.line, 0f, 360f, false, style = stroke)
                drawArc(c.lens, -90f, 360f * share, false, style = stroke)
            }
            Txt("${(share * 100).toInt()}%", T.tiny, c.ink)
        }
    }
}

/** Пустое обещание для карточки «Новое обещание» (id = 0 — ещё не сохранено). */
fun blankPromise() = TaskItem(id = 0L, text = "", due = null, assignee = null, status = "open", source = "manual",
    conversationId = null, conversationTitle = null, exportRef = null)


// ======================================================================= разбивка обещаний
/** Вид для фильтра: я обещал / мне обещали / встреча / напоминание. */
fun promiseGroupKind(t: TaskItem): String = when {
    t.kind == "meeting" -> "meeting"
    t.kind == "reminder" -> "reminder"
    t.theirs -> "theirs"
    else -> "mine"
}

class GroupNames(val hot: String, val overdue: String, val today: String, val yesterday: String, val tomorrow: String,
                 val week: String, val later: String, val noDue: String)

class PromiseGroup(val key: String, val title: String, val items: List<TaskItem>, val hot: Boolean = false)

private fun createdMs(t: TaskItem): Long = try {
    java.time.OffsetDateTime.parse(t.createdAt).toInstant().toEpochMilli()
} catch (_: Exception) {
    0L
}

private fun localDay(ms: Long): java.time.LocalDate =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

private fun startOf(d: java.time.LocalDate): Long = d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

private val soonestFirst = compareBy<TaskItem> { it.dueMillis() ?: Long.MAX_VALUE }.thenByDescending { createdMs(it) }

/** «Горит» (просрочено и срок сегодня) — наверху; остальное — по дню, когда обещание появилось: сегодня, вчера,
 *  дни недели, дальше — по месяцам. Внутри дня — по сроку. */
fun groupByCreated(open: List<TaskItem>, n: GroupNames): List<PromiseGroup> {
    val today = java.time.LocalDate.now()
    val (hot, rest) = open.partition { (it.dueMillis() ?: Long.MAX_VALUE) < startOf(today.plusDays(1)) }
    val out = ArrayList<PromiseGroup>()
    if (hot.isNotEmpty()) out += PromiseGroup("hot", n.hot, hot.sortedWith(soonestFirst), hot = true)
    val loc = java.util.Locale.getDefault()
    val day = java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", loc)
    val month = java.time.format.DateTimeFormatter.ofPattern("LLLL", loc)
    val monthYear = java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", loc)
    rest.groupBy { t ->
        val d = localDay(createdMs(t))
        if (d >= today.minusDays(6)) "d$d" else "m${d.year}-${d.monthValue}"
    }.toList().sortedByDescending { (_, list) -> list.maxOf { createdMs(it) } }.forEach { (key, list) ->
        val d = localDay(createdMs(list.first()))
        val title = when {
            key.startsWith("d") && d == today -> n.today
            key.startsWith("d") && d == today.minusDays(1) -> n.yesterday
            key.startsWith("d") -> d.format(day)
            d.year == today.year -> d.format(month)
            else -> d.format(monthYear)
        }.replaceFirstChar { it.titlecase(loc) }
        out += PromiseGroup(key, title, list.sortedWith(soonestFirst))
    }
    return out
}

/** По сроку: просрочено, сегодня, завтра, на этой неделе, позже, без срока. */
fun groupByDue(open: List<TaskItem>, n: GroupNames, now: Long): List<PromiseGroup> {
    val today = java.time.LocalDate.now()
    val tomorrow = startOf(today.plusDays(1))
    val after = startOf(today.plusDays(2))
    val weekEnd = startOf(today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)))
    val grouped = open.groupBy { t ->
        val d = t.dueMillis()
        when {
            d == null -> "none"
            d < now -> "overdue"
            d < tomorrow -> "today"
            d < after -> "tomorrow"
            d < weekEnd -> "week"
            else -> "later"
        }
    }
    return listOf("overdue" to n.overdue, "today" to n.today, "tomorrow" to n.tomorrow, "week" to n.week,
        "later" to n.later, "none" to n.noDue).mapNotNull { (k, title) ->
        grouped[k]?.let { PromiseGroup(k, title, it.sortedWith(soonestFirst), hot = k == "overdue") }
    }
}

/** Фильтр по виду и переключатель разбивки (по дате создания / по сроку). */
@Composable
fun PromiseFilters(counts: Map<String, Int>, total: Int, selected: String, onSelect: (String) -> Unit,
                   byDue: Boolean, onByDue: (Boolean) -> Unit) {
    val c = Mn.c
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        item(key = "mode") {
            Chip(stringResource(if (byDue) R.string.group_by_due else R.string.group_by_created), false, { onByDue(!byDue) },
                leading = { Icon(if (byDue) Icons.Clock else Icons.Calendar, null, tint = c.ink, modifier = Modifier.size(16.dp)) })
        }
        item(key = "all") { Chip(stringResource(R.string.filter_all, total), selected == "all", { onSelect("all") }) }
        listOf("mine" to R.string.filter_mine, "theirs" to R.string.filter_theirs, "meeting" to R.string.filter_meetings,
            "reminder" to R.string.filter_reminders).forEach { (k, label) ->
            val n = counts[k] ?: 0
            if (n > 0 || selected == k) item(key = k) {
                Chip(stringResource(label, n), selected == k, { onSelect(if (selected == k) "all" else k) })
            }
        }
    }
}
