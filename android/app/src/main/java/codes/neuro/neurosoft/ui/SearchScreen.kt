@file:OptIn(ExperimentalMaterial3Api::class)

package codes.neuro.neurosoft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.SearchHit
import codes.neuro.neurosoft.net.SearchResults
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun SearchScreen() {
    var q by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<Res<SearchResults>>(Res()) }
    var attempt by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    val fmt = rememberFmt()
    val c = Mn.c

    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(q, attempt) {
        val query = q.trim()
        if (query.length < 2) {
            state = Res()
            return@LaunchedEffect
        }
        delay(350)
        state = state.copy(loading = true)
        state = try {
            Res(Api.search(query))
        } catch (e: Exception) {
            Res(error = e)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(Icons.Back, stringResource(R.string.back), { Nav.pop() }, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.paper)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, null, tint = c.graphite, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Txt(stringResource(R.string.search_hint), T.body, c.faint, maxLines = 1)
                    BasicTextField(
                        value = q, onValueChange = { q = it }, singleLine = true,
                        textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.lens),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {}),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (q.isNotEmpty()) {
                    Icon(Icons.Close, stringResource(R.string.clear), tint = c.graphite,
                        modifier = Modifier.size(20.dp).clip(CircleShape).tap({ q = "" }))
                }
            }
        }
        val r = state.data
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            when {
                state.loading && r == null -> item { LoadingBlock() }
                state.error != null -> item { ErrorBlock(state.error, { attempt++ }) }
                r == null -> {
                    item {
                        Group {
                            item {
                                ListRow(stringResource(R.string.important_title), icon = Icons.Star, chevron = true,
                                    onClick = { Nav.push(Route.Important) })
                            }
                        }
                    }
                    item { EmptyBlock(Icons.Search, stringResource(R.string.search_title), "") }
                }
                r.isEmpty -> item {
                    EmptyBlock(Icons.Search, stringResource(R.string.search_nothing), "")
                }
                else -> {
                    section(R.string.search_conversations, r.conversations) { hit -> HitRow(hit, fmt) }
                    section(R.string.search_phrases, r.segments) { hit -> HitRow(hit, fmt) }
                    section(R.string.tab_tasks, r.tasks) { hit -> HitRow(hit, fmt) }
                    section(R.string.memory, r.memories) { hit -> HitRow(hit, fmt) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: Int, hits: List<SearchHit>, row: @Composable (SearchHit) -> Unit,
) {
    if (hits.isEmpty()) return
    item { SectionTitle(stringResource(title)) }
    itemsIndexed(hits) { i, h -> Grouped(i, hits.size, inset = 68.dp) { row(h) } }
}

@Composable
private fun HitRow(h: SearchHit, fmt: Fmt) {
    val c = Mn.c
    val target = h.conversationId
    Row(
        Modifier.fillMaxWidth().then(if (target != null) Modifier.tap({ Nav.push(Route.Conversation(target)) }) else Modifier)
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (h.kind) {
            "task" -> Icon(if (h.done) Icons.Check else Icons.Tasks, null, tint = c.graphite, modifier = Modifier.size(22.dp))
            "memory" -> Icon(Icons.Bookmark, null, tint = c.graphite, modifier = Modifier.size(22.dp))
            else -> EmojiTile(h.emoji, 40.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (h.kind == "segment") {
                Txt(h.text ?: "", T.body, maxLines = 3)
                if (h.title.isNotBlank()) Txt(h.title, T.caption, c.graphite, maxLines = 1)
            } else {
                Txt(h.title.ifBlank { stringResource(R.string.untitled) }, T.bodyStrong, maxLines = 2)
                h.text?.takeIf { it.isNotBlank() }?.let { Txt(it, T.caption, c.graphite, maxLines = 2) }
            }
        }
        h.at?.let {
            Spacer(Modifier.width(8.dp))
            Txt(fmt.dayShortTitle(fmt.dayOf(it)), T.caption, c.faint, maxLines = 1)
        }
    }
}

// ======================================================================= календарь (выбор дня)
@Composable
fun CalendarSheet(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val daysRes by Repo.days.state.collectAsState()
    val recorded = daysRes.data?.associateBy { it.day }.orEmpty()
    val fmt = rememberFmt()
    val c = Mn.c
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.parse(selected))) }
    LaunchedEffect(Unit) { Repo.days.refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.fog,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.line)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(fmt.month(month.atDay(1)), T.title, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconCircle(Icons.ChevronLeft, stringResource(R.string.prev_month), { month = month.minusMonths(1) }, size = 40.dp)
                Spacer(Modifier.width(8.dp))
                IconCircle(Icons.Chevron, stringResource(R.string.next_month), {
                    if (month < YearMonth.now()) month = month.plusMonths(1)
                }, size = 40.dp)
            }
            Card(padding = 12.dp) {
                // дни недели, начиная с понедельника
                val monday = LocalDate.of(2024, 1, 1) // понедельник
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        Txt(fmt.weekdayShort(monday.plusDays(i.toLong())), T.tiny, c.graphite,
                            Modifier.weight(1f), align = TextAlign.Center, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(6.dp))
                val first = month.atDay(1)
                val offset = first.dayOfWeek.value - 1
                val total = month.lengthOfMonth()
                val today = LocalDate.now()
                val weeks = (offset + total + 6) / 7
                for (w in 0 until weeks) {
                    Row(Modifier.fillMaxWidth()) {
                        for (dow in 0 until 7) {
                            val n = w * 7 + dow - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (n in 1..total) {
                                    val date = month.atDay(n)
                                    val iso = date.toString()
                                    val has = recorded[iso]
                                    val isSel = iso == selected
                                    val future = date.isAfter(today)
                                    Column(
                                        Modifier.size(44.dp).clip(CircleShape)
                                            .background(if (isSel) c.ink else androidx.compose.ui.graphics.Color.Transparent)
                                            .tap({ onPick(iso) }, enabled = !future),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Txt("$n", if (date == today) T.bodyStrong else T.body,
                                            when {
                                                isSel -> c.onInk
                                                future -> c.faint
                                                else -> c.ink
                                            })
                                        Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(
                                            if (has != null) (if (isSel) c.onInk else c.lens)
                                            else androidx.compose.ui.graphics.Color.Transparent))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val sel = recorded[selected]
            Txt(
                if (sel != null) fmt.dayTitle(selected) + ": " + plural(R.plurals.conversations, sel.conversations) +
                    ", " + fmt.duration(sel.seconds.toLong())
                else stringResource(R.string.calendar_hint),
                T.caption, c.graphite, Modifier.padding(start = 8.dp, top = 12.dp),
            )
        }
    }
}
