@file:OptIn(ExperimentalMaterial3Api::class)

package codes.neuro.neurosoft.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ApiException
import codes.neuro.neurosoft.net.Graph
import codes.neuro.neurosoft.net.GraphEvent
import codes.neuro.neurosoft.net.NameSuggestion
import codes.neuro.neurosoft.net.PeopleInfo
import codes.neuro.neurosoft.net.Person
import codes.neuro.neurosoft.net.PersonDetail
import codes.neuro.neurosoft.net.PersonRef
import codes.neuro.neurosoft.net.Segment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ======================================================================= голос: прослушивание
/** Один проигрыватель на всё приложение: образец голоса или звук фразы с сервера. */
object VoicePlayer {
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing
    private var player: MediaPlayer? = null

    fun toggle(context: Context, key: String, url: String) {
        if (_playing.value == key) {
            stop()
            return
        }
        stop()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(context.applicationContext, Uri.parse(url), mapOf("Authorization" to Api.authHeader()))
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            player = mp
            _playing.value = key
            mp.prepareAsync()
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        _playing.value = null
    }
}

@Composable
fun PlayButton(key: String, url: String, description: String, size: Dp = 40.dp, background: Color = Mn.c.raised,
               tint: Color = Mn.c.ink) {
    val context = LocalContext.current
    val playing by VoicePlayer.playing.collectAsState()
    IconCircle(if (playing == key) Icons.Pause else Icons.Play, description,
        { VoicePlayer.toggle(context, key, url) }, size = size, background = background, tint = tint)
}

// ======================================================================= подпись и аватар
/** «Анна», «Я» (владелец без имени) или «Голос 3». */
@Composable
fun personLabel(ref: PersonRef?): String = when {
    ref == null -> stringResource(R.string.voice_unknown)
    ref.isOwner -> ref.name ?: stringResource(R.string.people_me)
    else -> ref.name ?: stringResource(R.string.voice_n, ref.number)
}

/** Круг с первой буквой имени; безымянный голос — волна; у владельца — кольцо цвета линзы. */
@Composable
fun Avatar(ref: PersonRef?, size: Dp = 40.dp) {
    val c = Mn.c
    val label = personLabel(ref)
    val owner = ref?.isOwner == true
    Box(
        Modifier.size(size).clip(CircleShape).background(if (owner) c.ink else c.raised)
            .then(if (owner) Modifier.border(2.dp, c.lens, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (ref?.name == null && !owner) {
            Icon(Icons.Wave, null, tint = c.graphite, modifier = Modifier.size(size * 0.48f))
        } else {
            Txt(label.take(1).uppercase(), if (size >= 56.dp) T.title else T.smallStrong,
                if (owner) c.onInk else c.ink, maxLines = 1)
        }
    }
}

fun isoMillis(iso: String?): Long = try {
    if (iso.isNullOrBlank()) 0L else OffsetDateTime.parse(iso).toInstant().toEpochMilli()
} catch (e: Exception) {
    0L
}

@Composable
private fun personSubtitle(p: Person, fmt: Fmt): String = listOfNotNull(
    if (p.conversations > 0) plural(R.plurals.conversations, p.conversations) else null,
    if (p.speechSec >= 60) fmt.duration(p.speechSec.toLong()) else null,
    p.lastSeen?.let { fmt.dayShortTitle(fmt.dayOf(it)) },
).joinToString(", ")

// ======================================================================= вкладка «Люди»
@Composable
fun PeopleTab() {
    val peopleRes by Repo.people.state.collectAsState()
    val graphRes by Repo.graph.state.collectAsState()
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    var refreshing by remember { mutableStateOf(false) }
    var dismissedOwner by remember { mutableStateOf(Core.prefs.ownerDismissed) }

    LaunchedEffect(Unit) {
        while (true) {
            Repo.people.refresh()
            Repo.graph.refresh()
            delay(60_000)
        }
    }

    val info = peopleRes.data
    val outdated = (peopleRes.error as? ApiException)?.kind == ApiException.Kind.NOT_FOUND
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                Repo.people.refresh()
                Repo.graph.refresh()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "sky") { ConstellationCard(graphRes.data) }
            when {
                outdated -> item(key = "outdated") {
                    EmptyBlock(Icons.Cloud, stringResource(R.string.people_server_old),
                        stringResource(R.string.people_server_old_text))
                }
                info == null && peopleRes.error != null -> item(key = "err") {
                    ErrorBlock(peopleRes.error, { scope.launch { Repo.people.refresh() } })
                }
                info == null -> item(key = "loading") { LoadingBlock() }
                !info.enabled -> item(key = "off") {
                    EmptyBlock(Icons.People, stringResource(R.string.voices_off_title), stringResource(R.string.voices_off_text)) {
                        PrimaryButton(stringResource(R.string.turn_on), {
                            scope.launch {
                                try {
                                    Api.setVoiceSettings(enabled = true)
                                    Repo.people.refresh()
                                } catch (_: Exception) {
                                }
                            }
                        })
                    }
                }
                else -> peopleSections(info, dismissedOwner, fmt, onDismissOwner = {
                    Core.prefs.ownerDismissed = it
                    dismissedOwner = it
                })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.peopleSections(
    info: PeopleInfo, dismissedOwner: Long, fmt: Fmt, onDismissOwner: (Long) -> Unit,
) {
    item(key = "stale") { StaleNote(Repo.people.state.value.error) }
    val owner = info.ownerCandidate?.takeIf { it.id != dismissedOwner }
    if (owner != null || info.suggestions.isNotEmpty()) {
        item(key = "hints-title") { SectionTitle(stringResource(R.string.people_hints)) }
        if (owner != null) item(key = "owner-${owner.id}") { OwnerCard(owner, onDismissOwner) }
        items(info.suggestions, key = { "sug-${it.id}" }) { SuggestionCard(it) }
    }
    val known = info.people.filter { !it.ref.ignored && (it.ref.isOwner || it.ref.name != null) }
    val unknown = info.people.filter { !it.ref.ignored && !it.ref.isOwner && it.ref.name == null }
    val ignored = info.people.filter { it.ref.ignored }
    if (info.people.isEmpty()) {
        item(key = "empty") {
            EmptyBlock(Icons.People, stringResource(R.string.people_empty_title), stringResource(R.string.people_empty_text))
        }
    }
    if (known.isNotEmpty()) {
        item(key = "known-title") { SectionTitle(stringResource(R.string.people_known)) }
        items(known.size, key = { "k-${known[it].id}" }) { i -> Grouped(i, known.size, inset = 70.dp) { PersonRow(known[i], fmt) } }
    }
    if (unknown.isNotEmpty()) {
        item(key = "unknown-title") { SectionTitle(stringResource(R.string.people_unknown)) }
        items(unknown.size, key = { "u-${unknown[it].id}" }) { i -> Grouped(i, unknown.size, inset = 70.dp) { PersonRow(unknown[i], fmt) } }
        item(key = "unknown-note") {
            Txt(stringResource(R.string.people_unknown_note), T.caption, Mn.c.graphite,
                Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp))
        }
    }
    if (ignored.isNotEmpty()) {
        item(key = "ignored-title") { SectionTitle(stringResource(R.string.people_ignored)) }
        items(ignored.size, key = { "i-${ignored[it].id}" }) { i -> Grouped(i, ignored.size, inset = 70.dp) { PersonRow(ignored[i], fmt) } }
    }
    item(key = "privacy") {
        Group {
            item {
                ListRow(stringResource(R.string.voices_privacy), icon = Icons.Shield, chevron = true,
                    subtitle = stringResource(if (info.others) R.string.voices_privacy_all else R.string.voices_privacy_named),
                    onClick = { Nav.push(Route.VoicesSettings) })
            }
        }
    }
}

@Composable
private fun PersonRow(p: Person, fmt: Fmt) {
    Row(
        Modifier.fillMaxWidth().tap({ Nav.push(Route.Person(p.id)) })
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(p.ref, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(personLabel(p.ref), T.bodyStrong, maxLines = 1)
            val sub = personSubtitle(p, fmt)
            if (sub.isNotBlank()) Txt(sub, T.caption, Mn.c.graphite, maxLines = 1)
        }
        if (p.hasSample) {
            PlayButton("sample-${p.id}", Api.sampleUrl(p.id), stringResource(R.string.listen_voice), size = 38.dp)
        }
    }
}

@Composable
private fun OwnerCard(p: Person, onDismiss: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.ref, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.owner_q), T.bodyStrong)
                Txt(stringResource(R.string.owner_q_text), T.caption, Mn.c.graphite)
            }
            if (p.hasSample) PlayButton("sample-${p.id}", Api.sampleUrl(p.id), stringResource(R.string.listen_voice))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.yes_its_me), {
                busy = true
                scope.launch {
                    try {
                        Api.updatePerson(p.id, isOwner = true)
                        Repo.people.refresh()
                        Repo.graph.refresh()
                    } catch (_: Exception) {
                    }
                    busy = false
                }
            }, small = true, icon = Icons.Check, enabled = !busy)
            SecondaryButton(stringResource(R.string.no), { onDismiss(p.id) }, small = true, enabled = !busy)
        }
    }
}

@Composable
private fun SuggestionCard(s: NameSuggestion) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val ref = PersonRef(s.personId, null, s.number, s.isOwner)
    fun answer(accept: Boolean) {
        busy = true
        scope.launch {
            try {
                Api.answerSuggestion(s.id, accept)
                Repo.people.refresh()
                if (accept) Repo.graph.refresh()
            } catch (_: Exception) {
            }
            busy = false
        }
    }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(ref, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(if (s.targetOwner) stringResource(R.string.suggest_is_you, personLabel(ref))
                    else stringResource(R.string.suggest_is_it, personLabel(ref), s.name), T.bodyStrong)
                s.conversationTitle?.let { Txt(stringResource(R.string.from_conversation, it), T.caption, Mn.c.graphite, maxLines = 1) }
            }
            PlayButton("sample-${s.personId}", Api.sampleUrl(s.personId), stringResource(R.string.listen_voice))
        }
        s.evidence?.let {
            Txt(stringResource(R.string.quoted, it), T.small.copy(fontStyle = FontStyle.Italic), Mn.c.graphite,
                Modifier.padding(top = 10.dp), maxLines = 3)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.yes), { answer(true) }, small = true, icon = Icons.Check, enabled = !busy)
            SecondaryButton(stringResource(R.string.no), { answer(false) }, small = true, enabled = !busy)
        }
    }
}

// ======================================================================= экран человека
@Composable
fun PersonScreen(id: Long) {
    var state by remember(id) { mutableStateOf<Res<PersonDetail>>(Res(loading = true)) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var merge by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sameName by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fmt = rememberFmt()
    val c = Mn.c

    fun reload() {
        scope.launch {
            state = try {
                Res(Api.person(id))
            } catch (e: Exception) {
                state.copy(loading = false, error = e)
            }
        }
    }

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                Repo.people.refresh()
                Repo.graph.refresh()
            } catch (e: Exception) {
                state = state.copy(error = e)
            }
            busy = false
        }
    }
    LaunchedEffect(id) { reload() }

    val d = state.data
    if (confirmForget && d != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = c.paper,
            title = { Text(stringResource(R.string.forget_voice_q, personLabel(d.person.ref)), style = T.section, color = c.ink) },
            text = { Text(stringResource(R.string.forget_voice_text), style = T.body, color = c.graphite) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    act {
                        Api.forgetPerson(id)
                        Nav.pop()
                    }
                }) { Text(stringResource(R.string.forget), color = c.red, style = T.bodyStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.cancel), color = c.ink, style = T.bodyStrong)
                }
            },
        )
    }
    if (merge && d != null) {
        PeoplePicker(
            title = stringResource(R.string.merge_title, personLabel(d.person.ref)),
            subtitle = stringResource(R.string.merge_text),
            exclude = setOf(id),
            onDismiss = { merge = false },
            onPick = { target ->
                merge = false
                act {
                    Api.mergePerson(id, target)
                    Nav.pop()
                    Nav.push(Route.Person(target))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(null, { Nav.pop() })
        when {
            d == null && state.error != null -> ErrorBlock(state.error, { reload() })
            d == null -> LoadingBlock()
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(bottom = 28.dp)) {
                val p = d.person
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.ref, 64.dp)
                    Spacer(Modifier.weight(1f))
                    if (p.hasSample) {
                        SecondaryButton(stringResource(R.string.listen_voice), {
                            VoicePlayer.toggle(context, "sample-${p.id}", Api.sampleUrl(p.id))
                        }, small = true, icon = Icons.Play)
                    }
                }
                Txt(personLabel(p.ref), T.title, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
                Txt(when {
                    p.ref.isOwner -> stringResource(R.string.person_is_you)
                    p.ref.name == null -> stringResource(R.string.person_no_name)
                    p.firstSeen != null -> stringResource(R.string.person_known_since, fmt.dayShortTitle(fmt.dayOf(p.firstSeen)))
                    else -> ""
                }, T.small, c.graphite, Modifier.padding(horizontal = 20.dp))
                StaleNote(state.error)

                if (p.ref.name == null && !p.ref.isOwner || editing) {
                    Group(stringResource(R.string.person_name_title),
                        footer = if (p.ref.name == null) stringResource(R.string.person_name_footer) else null) {
                        item { FieldRow(stringResource(R.string.name), name, { name = it }, stringResource(R.string.name_hint)) }
                        if (d.suggestions.isNotEmpty() && p.ref.name == null) {
                            item {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    d.suggestions.forEach { s -> Chip(s.name + "?", false, { name = s.name }) }
                                }
                            }
                        }
                    }
                    PrimaryButton(stringResource(R.string.save), {
                        val n = name.trim()
                        act {
                            val r = Api.updatePerson(id, name = n)
                            state = Res(r)
                            sameName = r.sameName
                            editing = false
                            name = ""
                        }
                    }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        enabled = name.isNotBlank() && !busy)
                }
                sameName?.let { other ->
                    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                        Txt(stringResource(R.string.same_name_q, p.ref.name ?: ""), T.bodyStrong)
                        Txt(stringResource(R.string.same_name_text), T.caption, c.graphite, Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(stringResource(R.string.merge), {
                                sameName = null
                                act {
                                    Api.mergePerson(id, other)
                                    Nav.pop()
                                    Nav.push(Route.Person(other))
                                }
                            }, small = true, icon = Icons.Merge)
                            SecondaryButton(stringResource(R.string.different_people), { sameName = null }, small = true)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                TileRow {
                    StatTile(stringResource(R.string.person_conversations), p.conversations.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.person_spoke), fmt.duration(p.speechSec.toLong()), Modifier.weight(1f))
                    StatTile(stringResource(R.string.person_last), p.lastSeen?.let { fmt.dayShortTitle(fmt.dayOf(it)) } ?: "—",
                        Modifier.weight(1f))
                }
                if (d.topics.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.person_topics))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.topics.forEach { t -> Chip(t, false, { Nav.push(Route.Search) }) }
                    }
                }
                if (d.recent.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.person_recent))
                    d.recent.take(12).forEachIndexed { i, r ->
                        Grouped(i, min(12, d.recent.size)) {
                            Row(Modifier.fillMaxWidth().tap({ Nav.push(Route.Conversation(r.id)) })
                                .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                EmojiTile(r.emoji)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Txt(r.title ?: stringResource(R.string.untitled), T.bodyStrong, maxLines = 2)
                                    Txt(fmt.dayShortTitle(fmt.dayOf(r.startedAt)) + ", " + fmt.time(r.startedAt),
                                        T.caption, c.graphite, Modifier.padding(top = 3.dp))
                                }
                                if (r.speechSec > 0) Txt(fmt.duration(r.speechSec.toLong()), T.caption, c.graphite)
                            }
                        }
                    }
                }
                if (d.tasks.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.person_tasks))
                    d.tasks.forEachIndexed { i, t ->
                        var task by remember(t.id) { mutableStateOf(t) }
                        Grouped(i, d.tasks.size, inset = 50.dp) {
                            TaskRow(task, {
                                val next = if (task.status == "done") "open" else "done"
                                val before = task
                                task = task.copy(status = next)
                                scope.launch {
                                    try {
                                        Api.setTaskStatus(t.id, next)
                                    } catch (e: Exception) {
                                        task = before
                                    }
                                }
                            })
                        }
                    }
                }
                if (d.together.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.person_together))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.together.forEach { o ->
                            Chip(personLabel(o.ref), false, { Nav.push(Route.Person(o.ref.id)) },
                                leading = { Avatar(o.ref, 24.dp) })
                        }
                    }
                }
                if (d.quotes.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.person_quotes))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.quotes.take(3).forEach { q ->
                            Card(onClick = q.conversationId?.let { cid -> { Nav.push(Route.Conversation(cid)) } }, padding = 14.dp) {
                                Txt(stringResource(R.string.quoted, q.text), T.body, maxLines = 4)
                                Txt(fmt.dayShortTitle(fmt.dayOf(q.at)) + ", " + fmt.time(q.at), T.caption, c.graphite,
                                    Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }

                Group(stringResource(R.string.person_actions)) {
                    if (!p.ref.isOwner) {
                        item {
                            ListRow(stringResource(R.string.its_me), icon = Icons.Person,
                                subtitle = stringResource(R.string.its_me_sub),
                                onClick = { if (!busy) act { state = Res(Api.updatePerson(id, isOwner = true)) } })
                        }
                    }
                    if (p.ref.name != null && !editing) {
                        item {
                            ListRow(stringResource(R.string.rename), icon = Icons.Edit, onClick = {
                                name = p.ref.name ?: ""
                                editing = true
                            })
                        }
                    }
                    item {
                        ToggleRow(stringResource(R.string.ignore_voice), p.ref.ignored, { v ->
                            act { state = Res(Api.updatePerson(id, ignored = v)) }
                        }, icon = Icons.EyeOff, subtitle = stringResource(R.string.ignore_voice_sub), enabled = !busy)
                    }
                    item {
                        ListRow(stringResource(R.string.merge_with), icon = Icons.Merge, chevron = true,
                            subtitle = stringResource(R.string.merge_with_sub), onClick = { merge = true })
                    }
                    item {
                        ListRow(stringResource(R.string.forget_voice), icon = Icons.Trash, danger = true,
                            onClick = { confirmForget = true })
                    }
                }
            }
        }
    }
}

// ======================================================================= выбор человека
@Composable
fun PeoplePicker(title: String, subtitle: String?, exclude: Set<Long>, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val res by Repo.people.state.collectAsState()
    val c = Mn.c
    val fmt = rememberFmt()
    LaunchedEffect(Unit) { Repo.people.refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.fog,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.line)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            Txt(title, T.title, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
            subtitle?.let { Txt(it, T.small, c.graphite, Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)) }
            val list = res.data?.people.orEmpty().filter { it.id !in exclude }
            if (list.isEmpty()) {
                Txt(stringResource(R.string.people_empty_title), T.body, c.graphite, Modifier.padding(20.dp))
            }
            Group {
                list.forEach { p ->
                    item {
                        Row(Modifier.fillMaxWidth().tap({ onPick(p.id) }).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.ref, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(personLabel(p.ref), T.bodyStrong, maxLines = 1)
                                Txt(personSubtitle(p, fmt), T.caption, c.graphite, maxLines = 1)
                            }
                            if (p.hasSample) PlayButton("sample-${p.id}", Api.sampleUrl(p.id), stringResource(R.string.listen_voice), 36.dp)
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================= «Кто это?»
/**
 * Подпись фразы в расшифровке. Если голос уже известен, можно поправить весь голос («Голос 3 — это Анна»)
 * или только эту фразу (узнал неверно).
 */
@Composable
fun WhoIsThisSheet(seg: Segment, people: Map<Long, PersonRef>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val res by Repo.people.state.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Mn.c
    val current = seg.personId?.let { people[it] ?: res.data?.people?.firstOrNull { p -> p.id == it }?.ref }
    var wholeVoice by remember { mutableStateOf(current != null && current.name == null && !current.isOwner) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(Unit) { Repo.people.refresh() }
    val all = res.data?.people.orEmpty()
    val owner = all.firstOrNull { it.ref.isOwner }

    fun launchAction(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                Repo.people.refresh()
                Repo.graph.refresh()
                onDone()
            } catch (e: Exception) {
                error = e
            }
            busy = false
        }
    }

    fun pick(target: Long) = launchAction {
        if (wholeVoice && current != null) {
            if (target != current.id) Api.mergePerson(current.id, target)
        } else {
            Api.assignSegment(seg.id, target)
        }
    }

    fun named(n: String) = launchAction {
        val same = all.firstOrNull { it.ref.name.equals(n, ignoreCase = true) }
        if (wholeVoice && current != null) {
            if (same != null && same.id != current.id) Api.mergePerson(current.id, same.id)
            else Api.updatePerson(current.id, name = n)
        } else {
            if (same != null) Api.assignSegment(seg.id, same.id) else Api.assignSegment(seg.id, null, name = n)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.fog,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.line)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Txt(stringResource(R.string.who_is_this), T.title, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.quoted, seg.text), T.body, modifier = Modifier.weight(1f), maxLines = 5)
                    if (seg.hasAudio && seg.id > 0) {
                        Spacer(Modifier.width(10.dp))
                        PlayButton("seg-${seg.id}", Api.segmentAudioUrl(seg.id), stringResource(R.string.listen_phrase))
                    }
                }
                if (current != null) {
                    Txt(stringResource(R.string.now_signed_as, personLabel(current) + if (seg.sure) "" else "?"),
                        T.caption, c.graphite, Modifier.padding(top = 6.dp))
                }
            }
            if (current != null) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.scope_voice, personLabel(current)), wholeVoice, { wholeVoice = true })
                    Chip(stringResource(R.string.scope_phrase), !wholeVoice, { wholeVoice = false })
                }
            }
            error?.let { Txt(errorText(it), T.caption, c.red, Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) }
            Group(stringResource(R.string.new_name)) {
                item { FieldRow(stringResource(R.string.name), name, { name = it }, stringResource(R.string.name_hint)) }
            }
            PrimaryButton(stringResource(R.string.save), { named(name.trim()) },
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), enabled = name.isNotBlank() && !busy)

            val choices = all.filter { it.id != current?.id && !it.ref.ignored }
            Group(stringResource(R.string.choose_person)) {
                if (owner == null && current != null && wholeVoice) {
                    item {
                        ListRow(stringResource(R.string.its_me), icon = Icons.Person, onClick = {
                            if (!busy) launchAction { Api.updatePerson(current.id, isOwner = true) }
                        })
                    }
                }
                choices.forEach { p ->
                    item {
                        Row(Modifier.fillMaxWidth().tap({ if (!busy) pick(p.id) }).padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.ref, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Txt(personLabel(p.ref), T.body, modifier = Modifier.weight(1f), maxLines = 1)
                            if (p.hasSample) PlayButton("sample-${p.id}", Api.sampleUrl(p.id), stringResource(R.string.listen_voice), 34.dp)
                        }
                    }
                }
                if (!wholeVoice && current != null) {
                    item {
                        ListRow(stringResource(R.string.dont_know), icon = Icons.Help, onClick = {
                            if (!busy) launchAction { Api.assignSegment(seg.id, null) }
                        })
                    }
                }
            }
        }
    }
}

// ======================================================================= настройки голосов
@Composable
fun VoicesSettingsScreen() {
    val res by Repo.people.state.collectAsState()
    val scope = rememberCoroutineScope()
    var enabled by remember(res.data) { mutableStateOf(res.data?.enabled ?: true) }
    var others by remember(res.data) { mutableStateOf(res.data?.others ?: true) }
    var confirm by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val c = Mn.c
    val forgotten = stringResource(R.string.voices_forgotten)
    LaunchedEffect(Unit) { Repo.people.refresh() }

    fun save(e: Boolean? = null, o: Boolean? = null) {
        scope.launch {
            try {
                val (ne, no) = Api.setVoiceSettings(e, o)
                enabled = ne
                others = no
                Repo.people.refresh()
            } catch (ex: Exception) {
                note = null
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = c.paper,
            title = { Text(stringResource(R.string.forget_all_q), style = T.section, color = c.ink) },
            text = { Text(stringResource(R.string.forget_all_text), style = T.body, color = c.graphite) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        note = try {
                            Api.forgetAllVoices()
                            Repo.people.refresh()
                            Repo.graph.refresh()
                            forgotten
                        } catch (e: Exception) {
                            null
                        }
                    }
                }) { Text(stringResource(R.string.forget), color = c.red, style = T.bodyStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.cancel), color = c.ink, style = T.bodyStrong)
                }
            },
        )
    }

    SettingsPage(stringResource(R.string.voices_title)) {
        Txt(stringResource(R.string.voices_intro), T.small, c.graphite, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        if ((res.error as? ApiException)?.kind == ApiException.Kind.NOT_FOUND) {
            Txt(stringResource(R.string.people_server_old_text), T.small, c.amber, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        Group {
            item {
                ToggleRow(stringResource(R.string.voices_enabled), enabled, {
                    enabled = it
                    save(e = it)
                }, icon = Icons.People, subtitle = stringResource(R.string.voices_enabled_sub))
            }
            item {
                ToggleRow(stringResource(R.string.voices_others), others, {
                    others = it
                    save(o = it)
                }, icon = Icons.Person, subtitle = stringResource(R.string.voices_others_sub), enabled = enabled)
            }
        }
        Group(footer = stringResource(R.string.voices_consent)) {
            item {
                ListRow(stringResource(R.string.forget_all_voices), icon = Icons.Trash, danger = true, onClick = { confirm = true })
            }
        }
        note?.let { Txt(it, T.caption, c.graphite, Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) }
    }
}
