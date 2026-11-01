package codes.neuro.neurosoft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.MemoryItem
import codes.neuro.neurosoft.net.MemoryList
import codes.neuro.neurosoft.net.Profile
import codes.neuro.neurosoft.net.Routines
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Память ассистента — всё, что он знает и помнит, в одном месте:
 * о вас (имя, занятие, цели), правила и расписание, важные разговоры и факты из жизни.
 * Факты можно найти по смыслу («что любит мама»), исправить или убрать в архив (оттуда — вернуть).
 */
@Composable
fun MemoryScreen() {
    var list by remember { mutableStateOf<Res<MemoryList>>(Res(loading = true)) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var routines by remember { mutableStateOf<Routines?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<MemoryItem>?>(null) }
    var archive by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(0) }  // 0 — все, 1 — вы просили, 2 — заметил Mnemo
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    val c = Mn.c

    fun reload() {
        scope.launch {
            list = try {
                Res(Api.memories(archived = archive))
            } catch (e: Exception) {
                list.copy(error = e, loading = false)
            }
        }
    }
    LaunchedEffect(archive) { reload() }
    LaunchedEffect(Unit) {
        profile = try { Api.profile() } catch (_: Exception) { null }
        routines = try { Api.routines() } catch (_: Exception) { null }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            found = null
            return@LaunchedEffect
        }
        delay(400)
        found = try { Api.memories(query = q).items } catch (_: Exception) { emptyList() }
    }
    BackHandler(enabled = archive) { archive = false }

    fun act(m: MemoryItem, block: suspend () -> Unit) {
        list = list.data?.let { d -> Res(d.copy(items = d.items.filter { it.id != m.id })) } ?: list
        found = found?.filter { it.id != m.id }
        scope.launch {
            try {
                block()
            } catch (_: Exception) {
            }
            reload()
        }
    }

    editing?.let { m ->
        MemoryEditDialog(m, fmt, onDismiss = { editing = null }, onSave = { text ->
            editing = null
            val upd = m.copy(text = text, updatedAt = java.time.OffsetDateTime.now().toString())
            list = list.data?.let { d -> Res(d.copy(items = d.items.map { if (it.id == m.id) upd else it })) } ?: list
            found = found?.map { if (it.id == m.id) upd else it }
            scope.launch {
                try {
                    Api.editMemory(m.id, text)
                } catch (_: Exception) {
                }
                reload()
            }
        }, onArchive = {
            editing = null
            act(m) { Api.archiveMemory(m.id, true) }
        })
    }

    SettingsPage(stringResource(if (archive) R.string.memory_archive else R.string.memory)) {
        if (!archive) {
            SearchPill(query, { query = it }, stringResource(R.string.memory_search_hint))
        }
        val results = found
        when {
            // поиск по смыслу
            !archive && results != null -> {
                if (results.isEmpty()) EmptyBlock(Icons.Search, stringResource(R.string.search_nothing), "")
                else Group(inset = 56.dp) {
                    results.forEach { m -> item { MemoryRow(m, fmt, onOpen = { editing = m }, onArchive = { act(m) { Api.archiveMemory(m.id, true) } }) } }
                }
            }
            // архив: вернуть или удалить совсем
            archive -> {
                val d = list.data
                when {
                    d == null && list.error != null -> ErrorBlock(list.error, { reload() })
                    d == null -> LoadingBlock()
                    d.items.isEmpty() -> EmptyBlock(Icons.Bookmark, stringResource(R.string.memory_archive_empty), "")
                    else -> Group(inset = 56.dp) {
                        d.items.forEach { m ->
                            item {
                                ListRow(m.text, icon = Icons.Bookmark, subtitle = fmt.dayShortTitle(fmt.dayOf(m.createdAt))) {
                                    Txt(stringResource(R.string.trash_restore), T.smallStrong, c.lens,
                                        Modifier.quietTap { act(m) { Api.archiveMemory(m.id, false) } }.padding(8.dp))
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                // что ассистент знает о вас и что делает сам
                Group {
                    val p = profile
                    item {
                        ListRow(stringResource(R.string.about_me), icon = Icons.Person, chevron = true,
                            subtitle = listOfNotNull(p?.name, p?.about).joinToString(", ")
                                .ifBlank { stringResource(R.string.memory_about_empty) },
                            onClick = { Nav.push(Route.About) })
                    }
                    item {
                        val r = routines
                        val parts = listOfNotNull(
                            r?.rules?.size?.takeIf { it > 0 }?.let { plural(R.plurals.rules_count, it) },
                            r?.schedules?.count { it.enabled }?.takeIf { it > 0 }?.let { plural(R.plurals.schedules_count, it) },
                        )
                        ListRow(stringResource(R.string.routines_title), icon = Icons.Clock, chevron = true,
                            subtitle = parts.joinToString(", ").ifBlank { stringResource(R.string.routines_empty_title) },
                            onClick = { Nav.push(Route.Routines) })
                    }
                    item {
                        ListRow(stringResource(R.string.important_title), icon = Icons.Star, chevron = true,
                            onClick = { Nav.push(Route.Important) })
                    }
                }
                // факты из жизни
                val d = list.data
                Group(stringResource(R.string.memory_facts), trailing = {
                    d?.active?.takeIf { it > 0 }?.let { Txt("$it", T.body, c.graphite) }
                }) {}
                LazyRowChips(listOf(stringResource(R.string.memory_all), stringResource(R.string.memory_asked),
                    stringResource(R.string.memory_noticed)), filter) { filter = it }
                val items = d?.items?.filter { m -> filter == 0 || (filter == 1) == m.asked }
                when {
                    items == null && list.error != null -> ErrorBlock(list.error, { reload() })
                    items == null -> LoadingBlock()
                    items.isEmpty() -> Txt(stringResource(if (filter == 2) R.string.memory_noticed_empty else R.string.memory_asked_empty),
                        T.caption, c.graphite, Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                    else -> Group(inset = 56.dp) {
                        items.forEach { m ->
                            item { MemoryRow(m, fmt, onOpen = { editing = m }, onArchive = { act(m) { Api.archiveMemory(m.id, true) } }) }
                        }
                    }
                }
                if ((d?.archived ?: 0) > 0) {
                    Group {
                        item {
                            ListRow(stringResource(R.string.memory_archive), icon = Icons.Trash, chevron = true,
                                value = "${d?.archived}", onClick = { archive = true })
                        }
                    }
                }
            }
        }
    }
}

/** Строка факта: текст, когда (и что уточнялся), откуда; нажатие — исправить, крестик — в архив. */
@Composable
private fun MemoryRow(m: MemoryItem, fmt: Fmt, onOpen: () -> Unit, onArchive: () -> Unit) {
    val c = Mn.c
    val meta = listOfNotNull(
        fmt.dayShortTitle(fmt.dayOf(m.updatedAt ?: m.createdAt)),
        if (m.updatedAt != null) stringResource(R.string.memory_updated) else null,
        m.conversationTitle,
    ).joinToString(", ")
    ListRow(m.text, icon = if (m.asked) Icons.Tap else Icons.Bookmark, subtitle = meta, onClick = onOpen) {
        IconCircle(Icons.Close, stringResource(R.string.memory_to_archive), onArchive, size = 32.dp,
            background = c.raised, tint = c.graphite)
    }
}

@Composable
private fun MemoryEditDialog(m: MemoryItem, fmt: Fmt, onDismiss: () -> Unit, onSave: (String) -> Unit, onArchive: () -> Unit) {
    val c = Mn.c
    var text by remember(m.id) { mutableStateOf(m.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        title = { Text(stringResource(R.string.memory_edit_title), style = T.section, color = c.ink) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.fog).padding(12.dp)) {
                    BasicTextField(text, { text = it }, textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.lens),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                }
                Txt(listOfNotNull(fmt.dayShortTitle(fmt.dayOf(m.createdAt)), m.conversationTitle).joinToString(", "),
                    T.caption, c.graphite, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton({ onSave(text.trim()) }, enabled = text.trim().length >= 2 && text.trim() != m.text) {
                Text(stringResource(R.string.save), style = T.bodyStrong, color = c.lens)
            }
        },
        dismissButton = {
            TextButton(onArchive) { Text(stringResource(R.string.memory_to_archive), style = T.bodyStrong, color = c.red) }
        },
    )
}

/** Поле поиска на странице (как в общем поиске). */
@Composable
fun SearchPill(value: String, onChange: (String) -> Unit, hint: String) {
    val c = Mn.c
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(c.paper).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, null, tint = c.graphite, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt(hint, T.body, c.faint, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = T.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.lens), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Close, stringResource(R.string.clear), tint = c.graphite,
                modifier = Modifier.size(20.dp).clip(CircleShape).tap({ onChange("") }))
        }
    }
}
