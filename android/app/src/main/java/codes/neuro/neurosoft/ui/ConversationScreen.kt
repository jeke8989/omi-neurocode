package codes.neuro.neurosoft.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ConversationDetail
import codes.neuro.neurosoft.net.Segment
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(id: Long) {
    val context = LocalContext.current
    var state by remember(id) { mutableStateOf<Res<ConversationDetail>>(Res(loading = true)) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    val c = Mn.c
    val reprocessed = stringResource(R.string.reprocess_started)
    val speakerLabel = stringResource(R.string.speaker)
    var who by remember { mutableStateOf<Segment?>(null) }

    fun reload() {
        scope.launch {
            state = try {
                Res(Api.conversation(id))
            } catch (e: Exception) {
                state.copy(loading = false, error = e)
            }
        }
    }
    LaunchedEffect(id) { reload() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.paper,
            title = { Text(stringResource(R.string.delete_conversation_q), style = T.section, color = c.ink) },
            text = { Text(stringResource(R.string.delete_conversation_text), style = T.body, color = c.graphite) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            Api.deleteConversation(id)
                            Repo.days.refresh()
                            Nav.pop()
                        } catch (e: Exception) {
                            state = state.copy(error = e)
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = c.red, style = T.bodyStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel), color = c.ink, style = T.bodyStrong)
                }
            },
        )
    }

    val d = state.data
    // подписи говорящих: имя, «Я», «Голос 3»; без отпечатка — «Спикер 1»
    val labels = HashMap<Long, String>()
    d?.people?.values?.forEach { labels[it.id] = personLabel(it) }
    // голос, прозвучавший один раз, — «Собеседник» (несколько — с номером по порядку появления)
    val once = d?.segments?.mapNotNull { s -> s.personId?.takeIf { d.people[it]?.transient == true } }?.distinct().orEmpty()
    val companion = stringResource(R.string.voice_companion)
    once.forEachIndexed { i, pid -> labels[pid] = if (once.size > 1) "$companion ${i + 1}" else companion }
    fun label(s: Segment): String? = s.personId?.let { pid -> labels[pid]?.let { if (s.sure) it else "$it?" } }
        ?: s.speaker?.let { companion }

    who?.let { seg ->
        WhoIsThisSheet(seg, d?.people.orEmpty(), onDismiss = { who = null }, onDone = {
            who = null
            reload()
        })
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(null, { Nav.pop() }) {
            if (d != null) {
                IconCircle(Icons.Share, stringResource(R.string.share), {
                    val text = buildString {
                        append(d.item.title ?: "").append("\n")
                        append(fmt.dayShortTitle(fmt.dayOf(d.item.startedAt))).append(", ").append(fmt.time(d.item.startedAt))
                        append("\n\n")
                        d.item.summary?.let { append(it).append("\n\n") }
                        d.segments.forEach { s ->
                            append(fmt.time(s.startedAt)).append("  ")
                            label(s)?.let { append(it).append(": ") }
                            append(s.text).append("\n")
                        }
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, null))
                }, size = 40.dp)
            }
        }
        when {
            d == null && state.error != null -> ErrorBlock(state.error, { reload() })
            d == null -> LoadingBlock()
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiTile(d.item.emoji, 60.dp)
                }
                Txt(d.item.title ?: d.item.preview ?: stringResource(R.string.untitled), T.title,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaPill(Icons.Calendar, fmt.dayShortTitle(fmt.dayOf(d.item.startedAt)))
                    MetaPill(Icons.Clock, fmt.time(d.item.startedAt) + "–" + fmt.time(d.item.endedAt))
                    if (d.item.durationSec > 0) MetaPill(Icons.Wave, fmt.duration(d.item.durationSec.toLong()))
                }
                // кто участвовал: тап — карточка человека
                val present = d.people.values.filter { !it.ignored && !it.transient }.sortedByDescending { it.isOwner }
                if (present.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        present.forEach { p ->
                            Chip(personLabel(p), false, { Nav.push(Route.Person(p.id)) }, leading = { Avatar(p, 24.dp) })
                        }
                    }
                }
                when (d.item.status) {
                    "open", "processing" -> Txt(stringResource(R.string.status_processing), T.caption, c.graphite,
                        Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                    "failed" -> Txt(stringResource(R.string.status_failed), T.caption, c.red,
                        Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                }
                note?.let { Txt(it, T.caption, c.graphite, Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
                StaleNote(state.error)

                d.item.summary?.let { summary ->
                    SectionTitle(stringResource(R.string.summary))
                    Card(Modifier.padding(horizontal = 16.dp)) { Txt(summary, T.body) }
                }
                if (d.tasks.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.tab_tasks))
                    d.tasks.forEachIndexed { i, t ->
                        var task by remember(t.id) { mutableStateOf(t) }
                        Grouped(i, d.tasks.size, inset = 50.dp) {
                            TaskRow(task, {
                                val before = task
                                val next = if (task.status == "done") "open" else "done"
                                task = task.copy(status = next)
                                scope.launch {
                                    try {
                                        Api.setTaskStatus(t.id, next)
                                        Repo.tasks.edit { l -> l.map { if (it.id == t.id) it.copy(status = next) else it } }
                                    } catch (e: Exception) {
                                        task = before
                                    }
                                }
                            })
                        }
                    }
                }
                if (d.memories.isNotEmpty()) {
                    Group(stringResource(R.string.remembered)) {
                        d.memories.forEach { m -> item { ListRow(m.text, icon = Icons.Bookmark) } }
                    }
                }
                SectionTitle(stringResource(R.string.transcript))
                if (d.segments.isEmpty()) {
                    Txt(stringResource(R.string.transcript_empty), T.small, c.graphite, Modifier.padding(horizontal = 22.dp))
                } else if (d.people.values.any { it.name == null && !it.isOwner } || d.segments.any { it.personId != null && !it.sure }
                    || d.segments.any { it.personId == null && it.speaker != null }) {
                    Txt(stringResource(R.string.who_hint), T.caption, c.graphite,
                        Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp))
                }
                val byPerson = d.people.isNotEmpty()
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var last: String? = "\u0000"
                    d.segments.forEach { s ->
                        val ref = s.personId?.let { d.people[it] }
                        // «я» — справа, как в мессенджере; без голосов — чередуем по номеру спикера
                        val mine = if (byPerson) ref?.isOwner == true else (s.speaker ?: 0) % 2 == 1
                        val name = label(s)
                        val key = "${s.personId}:${s.speaker}:${s.sure}"
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            if (key != last) {
                                Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
                                    .tap({ if (s.id > 0) who = s }).padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (byPerson) {
                                        Avatar(ref, 22.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Txt(listOfNotNull(name, fmt.time(s.startedAt)).joinToString(", "), T.tiny,
                                        if (ref != null && !s.sure) c.amber else c.graphite)
                                }
                            }
                            Txt(s.text, T.body, if (ref?.ignored == true) c.faint else c.ink,
                                Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                                    .background(if (mine) c.paper else c.raised)
                                    .combinedClickable(onClick = {}, onLongClick = { if (s.id > 0) who = s })
                                    .padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                        last = key
                    }
                }
                Group {
                    item {
                        ListRow(stringResource(R.string.reprocess), icon = Icons.Sparkle, onClick = {
                            scope.launch {
                                note = try {
                                    Api.reprocessConversation(id)
                                    reprocessed
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        })
                    }
                    item { ListRow(stringResource(R.string.delete_conversation), icon = Icons.Trash, danger = true,
                        onClick = { confirmDelete = true }) }
                }
            }
        }
    }
}

@Composable
private fun MetaPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Mn.c.paper).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Mn.c.graphite, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Txt(text, T.caption, Mn.c.ink, maxLines = 1)
    }
}
