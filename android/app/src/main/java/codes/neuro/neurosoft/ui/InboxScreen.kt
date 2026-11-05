package codes.neuro.neurosoft.ui

import android.app.NotificationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.Inbox
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.TaskPrompts
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.TaskItem
import kotlinx.coroutines.launch

/** Колокольчик на главной: число непрочитанных уведомлений — красной меткой. */
@Composable
fun InboxBell() {
    LaunchedEffect(Unit) { Inbox.ensureLoaded() }
    val items by Inbox.items.collectAsState()
    val seenAt by Inbox.seenAt.collectAsState()
    val unread = Inbox.unread(items, seenAt)
    val c = Mn.c
    Box {
        IconCircle(Icons.Bell, stringResource(R.string.inbox), { Nav.push(Route.Inbox) })
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).sizeIn(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(CircleShape).background(c.red).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(if (unread > 9) "9+" else "$unread", T.tiny, androidx.compose.ui.graphics.Color.White, maxLines = 1)
            }
        }
    }
}

/**
 * Уведомления: всё, что Mnemo показывал в шторке, остаётся здесь — по дням. Вопросы об обещаниях — с ответом
 * прямо в списке («Да, верно / Нет», для встречи — «В календарь»), с цитатой и разговором, откуда они;
 * нажатие — карточка обещания. Фильтр «Ждут ответа» — только то, где нужно решение. Новые — с синей точкой.
 */
@Composable
fun InboxScreen() {
    val context = LocalContext.current
    val items by Inbox.items.collectAsState()
    val tasks by Repo.tasks.state.collectAsState()
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    val c = Mn.c
    // что было новым, когда открыли экран (после открытия всё считается прочитанным)
    val newSince = remember { Inbox.ensureLoaded(); Inbox.seenAt.value }
    var onlyPending by remember { mutableStateOf(false) }
    val busy = remember { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(Unit) {
        Inbox.markSeen()
        Repo.tasks.refresh()
    }
    val byId = tasks.data?.associateBy { it.id }.orEmpty()

    fun needsAnswer(t: TaskItem?) = t != null && (TaskPrompts.pending(t) || TaskPrompts.meetingAsk(context, t))

    fun answer(t: TaskItem, accept: Boolean) {
        busy[t.id] = true
        scope.launch {
            try {
                if (accept && TaskPrompts.meetingAsk(context, t)) {
                    TaskPrompts.addMeeting(context, t.id, t.text, t.dueMillis() ?: return@launch,
                        t.calendarMinutes ?: Core.prefs.calendarDefaultMin, t.conversationTitle)
                } else {
                    Repo.tasks.edit { l -> l.map { if (it.id == t.id) it.copy(status = if (accept) "open" else "dismissed",
                        confirmedAt = if (accept) java.time.OffsetDateTime.now().toString() else it.confirmedAt) else it } }
                    Repo.tasks.saving { Api.setTaskStatus(t.id, if (accept) "open" else "dismissed") }
                }
                context.getSystemService(NotificationManager::class.java)?.cancel(TaskPrompts.notificationId(t.id))
            } catch (e: Exception) {
                // нет связи — кнопки останутся
            }
            busy.remove(t.id)
            Repo.tasks.refresh()
        }
    }

    fun restore(t: TaskItem) {
        Repo.tasks.edit { l -> l.map { if (it.id == t.id) it.copy(status = "open") else it } }
        scope.launch {
            try {
                Repo.tasks.saving { Api.setTaskStatus(t.id, "open") }
            } catch (_: Exception) {
            }
            Repo.tasks.refresh()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.inbox), { Nav.pop() }) {
            if (items.isNotEmpty()) IconCircle(Icons.Trash, stringResource(R.string.inbox_clear), { Inbox.clear() }, size = 40.dp)
        }
        if (items.isEmpty()) {
            EmptyBlock(Icons.Bell, stringResource(R.string.inbox_empty_title), "")
            return@Column
        }
        val pending = items.count { it.kind == "task" && needsAnswer(it.taskId?.let(byId::get)) }
        val shown = if (onlyPending) items.filter { it.kind == "task" && needsAnswer(it.taskId?.let(byId::get)) } else items
        val today = java.time.LocalDate.now()
        val groups = shown.groupBy {
            java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }.toList().sortedByDescending { it.first }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "filters") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.filter_all, items.size), !onlyPending, { onlyPending = false })
                    if (pending > 0 || onlyPending) Chip(stringResource(R.string.inbox_pending, pending), onlyPending, { onlyPending = true })
                }
            }
            if (shown.isEmpty()) item(key = "none") {
                Txt(stringResource(R.string.inbox_all_done), T.body, c.graphite, Modifier.padding(horizontal = 22.dp, vertical = 18.dp))
            }
            groups.forEach { (day, list) ->
                item(key = "day-$day") {
                    SectionTitle(when (day) {
                        today -> stringResource(R.string.today)
                        today.minusDays(1) -> stringResource(R.string.yesterday)
                        else -> fmt.dayShortTitle(day.toString())
                    })
                }
                itemsIndexed(list, key = { _, it -> it.key }) { i, n ->
                    val t = n.taskId?.let(byId::get)
                    Grouped(i, list.size, inset = 70.dp) {
                        InboxRow(n, t, fresh = n.at > newSince, ask = n.kind == "task" && needsAnswer(t),
                            meeting = t != null && TaskPrompts.meetingAsk(context, t), busy = t != null && busy[t.id] == true,
                            ago = fmt.ago(n.at), onOpen = { open(n) }, onAnswer = { ok -> t?.let { answer(it, ok) } },
                            onRestore = { t?.let { restore(it) } })
                    }
                }
            }
        }
    }
}

/** Одно уведомление: значок по виду, заголовок (для обещания — свежий, с именем), текст, откуда, ответ. */
@Composable
private fun InboxRow(
    n: Inbox.Item, t: TaskItem?, fresh: Boolean, ask: Boolean, meeting: Boolean, busy: Boolean, ago: String,
    onOpen: () -> Unit, onAnswer: (Boolean) -> Unit, onRestore: () -> Unit,
) {
    val c = Mn.c
    Row(Modifier.fillMaxWidth().tap(onOpen).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
        EmojiTile(when {
            n.kind == "morning" -> "☀️"
            t?.kind == "meeting" -> "📅"
            t?.kind == "reminder" || n.kind == "reminder" -> "⏰"
            t?.theirs == true -> "📥"
            n.kind == "task" -> "🤝"
            else -> "🔔"
        }, 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(if (n.kind == "task" && t != null) TaskPrompts.headline(t) else n.title, T.bodyStrong,
                    modifier = Modifier.weight(1f), maxLines = 2)
                if (fresh) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.lens))
                }
            }
            val text = t?.text ?: n.text
            if (text.isNotBlank()) Txt(text, T.body, c.ink, maxLines = 4, modifier = Modifier.padding(top = 2.dp))
            // откуда: цитата и разговор
            t?.evidence?.takeIf { it.isNotBlank() }?.let {
                Txt(stringResource(R.string.quoted, it), T.caption.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    c.graphite, Modifier.padding(top = 4.dp), maxLines = 2)
            }
            Txt(listOfNotNull(ago, t?.conversationTitle?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.inbox_from, it) })
                .joinToString(" · "), T.tiny, c.faint, Modifier.padding(top = 4.dp), maxLines = 1)
            if (n.kind == "task" && t != null) {
                when {
                    ask -> Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (busy) Spinner(18.dp) else {
                            SecondaryButton(stringResource(if (meeting) R.string.meeting_add else R.string.inbox_confirm),
                                { onAnswer(true) }, small = true)
                            SecondaryButton(stringResource(if (meeting) R.string.meeting_skip else R.string.inbox_wrong),
                                { onAnswer(false) }, small = true, color = c.graphite)
                        }
                    }
                    t.status == "done" -> InboxState(Icons.Check, stringResource(R.string.inbox_done), c.lens)
                    t.status == "dismissed" -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(stringResource(R.string.inbox_removed), T.caption, c.graphite)
                        Spacer(Modifier.width(12.dp))
                        Txt(stringResource(R.string.trash_restore), T.smallStrong, c.lens,
                            Modifier.clip(RoundedCornerShape(8.dp)).quietTap(onRestore).padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                    t.kind == "meeting" && t.inCalendar -> InboxState(Icons.Calendar, stringResource(R.string.meeting_added), c.lens)
                    else -> InboxState(Icons.Check, stringResource(R.string.inbox_in_promises), c.lens)
                }
            }
        }
    }
}

@Composable
private fun InboxState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Txt(text, T.caption, color)
    }
}

private fun open(n: Inbox.Item) {
    val to = n.open ?: return
    when {
        n.taskId != null && (n.kind == "task" || n.kind == "reminder") -> {  // сразу карточка обещания
            Nav.reset(Route.Home)
            HomeState.page.value = 0
            HomeState.dueTask.value = n.taskId
        }
        to == "tasks" -> {
            Nav.reset(Route.Home)
            HomeState.page.value = 0
        }
        to.startsWith("day:") -> Nav.push(Route.Day(to.removePrefix("day:")))
        to == "chat" -> Nav.push(Route.Chat())
    }
}
