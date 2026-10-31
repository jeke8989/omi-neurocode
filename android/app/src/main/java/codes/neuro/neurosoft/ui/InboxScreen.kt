package codes.neuro.neurosoft.ui

import android.app.NotificationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Уведомления: всё, что Mnemo показывал в шторке, остаётся здесь — вопросы «Это задача?» (с ответом прямо
 * в списке), напоминания, утренние сводки. Новые — с синей точкой.
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
    LaunchedEffect(Unit) {
        Inbox.markSeen()
        Repo.tasks.refresh()
    }
    val status = tasks.data?.associate { it.id to it.status }.orEmpty()

    fun answer(id: Long, accept: Boolean) {
        Repo.tasks.edit { l -> l.map { if (it.id == id) it.copy(status = if (accept) "open" else "dismissed") else it } }
        scope.launch {
            try {
                Api.setTaskStatus(id, if (accept) "open" else "dismissed")
                context.getSystemService(NotificationManager::class.java)?.cancel(TaskPrompts.notificationId(id))
            } catch (e: Exception) {
                // нет связи — вернём как было, кнопки останутся
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
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
            itemsIndexed(items, key = { _, it -> it.key }) { i, n ->
                Grouped(i, items.size, inset = 70.dp) {
                    Row(
                        Modifier.fillMaxWidth().tap({ open(n) }).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        EmojiTile(when (n.kind) {
                            "task" -> "❓"
                            "reminder" -> "⏰"
                            "morning" -> "☀️"
                            else -> "🔔"
                        }, 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Txt(n.title, T.bodyStrong, modifier = Modifier.weight(1f), maxLines = 2)
                                if (n.at > newSince) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.lens))
                                }
                            }
                            if (n.text.isNotBlank()) Txt(n.text, T.small, c.graphite, maxLines = 5,
                                modifier = Modifier.padding(top = 2.dp))
                            Txt(fmt.ago(n.at), T.tiny, c.faint, Modifier.padding(top = 4.dp))
                            val id = n.taskId
                            if (n.kind == "task" && id != null) {
                                when (status[id]) {
                                    "suggested" -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SecondaryButton(stringResource(R.string.task_accept), { answer(id, true) }, small = true)
                                        SecondaryButton(stringResource(R.string.task_dismiss), { answer(id, false) }, small = true,
                                            color = c.graphite)
                                    }
                                    "open", "done" -> Txt(stringResource(R.string.task_prompt_added), T.caption, c.lens,
                                        Modifier.padding(top = 6.dp))
                                    "dismissed" -> Txt(stringResource(R.string.task_prompt_removed), T.caption, c.graphite,
                                        Modifier.padding(top = 6.dp))
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun open(n: Inbox.Item) {
    val to = n.open ?: return
    when {
        to == "tasks" -> {
            Nav.reset(Route.Home)
            HomeState.page.value = 1
        }
        to.startsWith("day:") -> Nav.push(Route.Day(to.removePrefix("day:")))
        to == "chat" -> Nav.push(Route.Chat())
    }
}
