package codes.neuro.neurosoft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ConversationItem
import codes.neuro.neurosoft.net.DayEvents
import codes.neuro.neurosoft.net.EventItem
import codes.neuro.neurosoft.net.TrashItem
import kotlinx.coroutines.launch

/** Строка ленты дня: событие (несколько фрагментов) или отдельный свежий фрагмент. */
sealed interface FeedEntry {
    val key: String
    val at: Long

    class Event(val e: EventItem) : FeedEntry {
        override val key = "e${e.id}"
        override val at = isoMillis(e.startedAt)
    }

    class Fragment(val c: ConversationItem) : FeedEntry {
        override val key = "c${c.id}"
        override val at = isoMillis(c.startedAt)
    }
}

/** События и ещё не разобранные фрагменты — одной лентой, новые сверху. */
fun feedEntries(d: DayEvents): List<FeedEntry> =
    (d.events.map { FeedEntry.Event(it) } + d.loose.map { FeedEntry.Fragment(it) }).sortedByDescending { it.at }

private fun openEvent(e: EventItem) {
    // событие из одного фрагмента — сразу разговор, без лишнего экрана
    if (e.fragments.size == 1) Nav.push(Route.Conversation(e.fragments[0].id)) else Nav.push(Route.Event(e.id, e.day))
}

@Composable
fun EventRow(e: EventItem, fmt: Fmt) {
    val colors = Mn.c
    Row(
        Modifier.fillMaxWidth().tap({ openEvent(e) })
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiTile(e.emoji ?: e.fragments.firstNotNullOfOrNull { it.emoji })
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(e.title ?: e.fragments.firstNotNullOfOrNull { it.title } ?: stringResource(R.string.untitled),
                T.bodyStrong, maxLines = 2)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(fmt.time(e.startedAt) + "–" + fmt.time(e.endedAt), T.caption, colors.graphite, maxLines = 1)
                if (e.fragments.size > 1) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Wave, null, tint = colors.graphite, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Txt("${e.fragments.size}", T.caption, colors.graphite)
                }
                if (e.tasksCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Tasks, null, tint = colors.graphite, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Txt("${e.tasksCount}", T.caption, colors.graphite)
                }
                val others = e.people.filter { !it.isOwner }
                if (others.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Person, null, tint = colors.graphite, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Txt(others.take(2).map { personLabel(it) }.joinToString(", ") +
                        if (others.size > 2) " +${others.size - 2}" else "", T.caption, colors.graphite, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (e.fragments.any { it.status == "open" || it.status == "processing" }) Spinner(16.dp)
        else Txt(fmt.duration(e.durationSec.toLong()), T.caption, colors.graphite)
    }
}

// ======================================================================= экран события
@Composable
fun EventScreen(id: Long, day: String) {
    val res by Repo.events(day).state.collectAsState()
    val fmt = rememberFmt()
    val c = Mn.c
    val scope = rememberCoroutineScope()
    LaunchedEffect(day) { Repo.events(day).refresh() }
    val e = res.data?.events?.firstOrNull { it.id == id }

    Column(Modifier.fillMaxSize()) {
        TopBar(fmt.dayShortTitle(day), { Nav.pop() })
        when {
            e == null && res.error != null -> ErrorBlock(res.error, { scope.launch { Repo.events(day).refresh() } })
            e == null && res.data == null -> LoadingBlock()
            e == null -> EmptyBlock(Icons.Wave, stringResource(R.string.event_gone_title), stringResource(R.string.event_gone_text))
            else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "head") {
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        EmojiTile(e.emoji, 58.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(e.title ?: stringResource(R.string.untitled), T.title)
                            Txt(fmt.time(e.startedAt) + "–" + fmt.time(e.endedAt) + ", " +
                                fmt.duration(e.durationSec.toLong()), T.caption, c.graphite,
                                Modifier.padding(top = 2.dp))
                        }
                    }
                }
                e.photoId?.let { photo ->
                    item(key = "photo") {
                        ServerImage(photo, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(200.dp)
                            .clip(RoundedCornerShape(24.dp)))
                    }
                }
                if (e.lat != null && e.lon != null) {
                    item(key = "map") { SpotMap(day, e.lat, e.lon, e.emoji, e.title) }
                }
                e.summary?.let { s ->
                    item(key = "summary") { Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { Txt(s, T.body) } }
                }
                val people = e.people.filter { !it.isOwner }
                if (people.isNotEmpty()) {
                    item(key = "people-title") { SectionTitle(stringResource(R.string.event_people)) }
                    item(key = "people") {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            people.forEach { p ->
                                Chip(personLabel(p), false, { Nav.push(Route.Person(p.id)) },
                                    leading = { Avatar(p, 26.dp) })
                            }
                        }
                    }
                }
                if (e.topics.isNotEmpty()) {
                    item(key = "topics") {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            e.topics.take(8).forEach { t ->
                                Txt(t, T.small, c.graphite, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                    .background(c.paper).padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
                item(key = "parts-title") {
                    SectionTitle(plural(R.plurals.event_parts, e.fragments.size)) {
                        Txt(stringResource(R.string.event_parts_note), T.caption, c.graphite)
                    }
                }
                val parts = e.fragments.sortedBy { isoMillis(it.startedAt) }
                itemsIndexed(parts, key = { _, f -> f.id }) { i, f -> Grouped(i, parts.size) { ConversationRow(f, fmt) } }
            }
        }
    }
}

// ======================================================================= корзина
@Composable
fun TrashScreen() {
    var state by remember { mutableStateOf<Res<List<TrashItem>>>(Res(loading = true)) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    fun reload() {
        scope.launch {
            state = try {
                Res(Api.trash())
            } catch (e: Exception) {
                Res(error = e)
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    SettingsPage(stringResource(R.string.trash)) {
        val items = state.data
        when {
            items == null && state.error != null -> ErrorBlock(state.error, { reload() })
            items == null -> LoadingBlock()
            items.isEmpty() -> EmptyBlock(Icons.Trash, stringResource(R.string.trash_empty_title),
                stringResource(R.string.trash_empty_text))
            else -> Group(footer = stringResource(R.string.trash_footer)) {
                items.forEach { t ->
                    item {
                        ListRow(t.title ?: t.preview ?: stringResource(R.string.untitled),
                            subtitle = listOfNotNull(fmt.dayShortTitle(fmt.dayOf(t.startedAt)) + ", " + fmt.time(t.startedAt),
                                t.reason?.let { junkReason(it) }).joinToString(", "),
                            icon = Icons.Trash, onClick = { Nav.push(Route.Conversation(t.id)) }) {
                            SecondaryButton(stringResource(R.string.trash_restore), {
                                scope.launch {
                                    try {
                                        Api.restore(t.id)
                                        state = Res(items.filter { it.id != t.id })
                                        Repo.events(fmt.dayOf(t.startedAt)).refresh()
                                    } catch (e: Exception) {
                                        state = state.copy(error = e)
                                    }
                                }
                            }, small = true)
                        }
                    }
                }
            }
        }
        state.error?.takeIf { state.data != null }?.let { StaleNote(it) }
    }
}

/** Причина «мусора» с сервера (по-русски) — на языке приложения. */
@Composable
private fun junkReason(r: String): String = when (r.lowercase().trim()) {
    "шум" -> stringResource(R.string.junk_noise)
    "телевизор", "радио" -> stringResource(R.string.junk_tv)
    "обрывок" -> stringResource(R.string.junk_fragment)
    "не разговор" -> stringResource(R.string.junk_not_talk)
    else -> r
}
