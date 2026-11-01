package codes.neuro.neurosoft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ExtDraft
import codes.neuro.neurosoft.net.Extension
import codes.neuro.neurosoft.net.McpInfo
import kotlinx.coroutines.launch

/** Поле ввода в строке группы. */
@Composable
fun FieldRow(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", secret: Boolean = false,
             keyboard: KeyboardType = KeyboardType.Text) {
    val c = Mn.c
    var show by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
        Txt(label, T.caption, c.graphite)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(top = 4.dp)) {
                if (value.isEmpty()) Txt(placeholder, T.body, c.faint, maxLines = 1)
                BasicTextField(
                    value = value, onValueChange = onChange, singleLine = true,
                    textStyle = T.body.copy(color = c.ink), cursorBrush = SolidColor(c.lens),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (secret) {
                Txt(stringResource(if (show) R.string.hide else R.string.show), T.smallStrong, c.lens,
                    Modifier.padding(start = 10.dp).quietTap { show = !show })
            }
        }
    }
}

@Composable
private fun ExtIcon(e: Extension) = EmojiTile(e.icon ?: "🧩", 40.dp)

@Composable
fun ExtensionsScreen() {
    var state by remember { mutableStateOf<Res<Pair<List<Extension>, List<Extension>>>>(Res(loading = true)) }
    var manifestUrl by remember { mutableStateOf("") }
    var byLink by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            state = try {
                Res(Api.extensions())
            } catch (e: Exception) {
                Res(error = e)
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    fun install(id: String?, url: String?) {
        busy = id ?: url
        scope.launch {
            try {
                Api.installExtension(id, url)
                reload()
                // сразу к настройкам: ключи, адрес вебхука
                id?.let { Nav.push(Route.Extension(it)) }
            } catch (e: Exception) {
                note = e.message
            }
            busy = null
        }
    }
    val c = Mn.c
    SettingsPage(stringResource(R.string.integrations)) {
        note?.let { Txt(it, T.caption, c.red, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        val data = state.data
        when {
            data == null && state.error != null -> ErrorBlock(state.error, { reload() })
            data == null -> LoadingBlock()
            else -> {
                val (mine, available) = data
                ConnectorsGroup { note = it }
                // «Подключено» — только то, что реально работает; остальное — «Нужно настроить» с тем, чего не хватает
                val (working, unfinished) = mine.partition { it.ready && it.enabled }
                if (working.isNotEmpty()) {
                    val subtitles = working.map { e ->
                        when {
                            e.events.isNotEmpty() -> plural(R.plurals.ext_events, e.events.size)
                            else -> plural(R.plurals.tools, e.tools.size)
                        }
                    }
                    Group(stringResource(R.string.connected), inset = 22.dp) {
                        working.forEachIndexed { i, e ->
                            item {
                                ListRow(listOfNotNull(e.icon, e.name).joinToString(" "), subtitle = subtitles[i], chevron = true,
                                    onClick = { Nav.push(Route.Extension(e.id)) }, trailing = null)
                            }
                        }
                    }
                }
                if (unfinished.isNotEmpty()) {
                    val needs = stringResource(R.string.ext_needs)
                    val off = stringResource(R.string.off)
                    Group(stringResource(R.string.needs_setup), inset = 22.dp) {
                        unfinished.forEach { e ->
                            val missing = e.settings.filter { it.required && !it.filled }.joinToString(", ") { it.title }
                            item {
                                ListRow(listOfNotNull(e.icon, e.name).joinToString(" "),
                                    subtitle = if (missing.isNotEmpty()) "$needs: $missing" else off, chevron = true,
                                    onClick = { Nav.push(Route.Extension(e.id)) }, trailing = null)
                            }
                        }
                    }
                }
                if (available.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.marketplace))
                    available.forEach { e ->
                        Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ExtIcon(e)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Txt(e.name, T.bodyStrong, maxLines = 1)
                                    Txt(e.description, T.caption, c.graphite, maxLines = 2)
                                }
                                Spacer(Modifier.width(8.dp))
                                if (busy == e.id) Spinner(20.dp)
                                else SecondaryButton(stringResource(R.string.add), { install(e.id, null) }, small = true)
                            }
                        }
                    }
                }
                // для разработчиков: собрать интеграцию с ассистентом, Mnemo в Claude/Cursor, свой manifest.json — свёрнуто
                Group {
                    item {
                        ListRow(stringResource(R.string.ext_developers), icon = Icons.Code, chevron = true,
                            onClick = { byLink = !byLink })
                    }
                    if (byLink) {
                        item {
                            ListRow(stringResource(R.string.ext_build), icon = Icons.Sparkle, chevron = true,
                                onClick = { Nav.push(Route.ExtensionBuilder) })
                        }
                        item {
                            ListRow(stringResource(R.string.mcp_title), icon = Icons.Link, chevron = true,
                                onClick = { Nav.push(Route.McpAccess) })
                        }
                        item { FieldRow(stringResource(R.string.manifest_link), manifestUrl, { manifestUrl = it },
                            "https://…/manifest.json", keyboard = KeyboardType.Uri) }
                        item { ListRow(stringResource(R.string.install_by_link), icon = Icons.Check, accent = true,
                            onClick = { if (manifestUrl.isNotBlank()) install(null, manifestUrl.trim()) }) }
                    }
                }
            }
        }
    }
}

/** Что умеет интеграция: инструменты, события, куда ходит, что нужно настроить (для экрана и черновика). */
@Composable
private fun ExtCapabilities(e: Extension) {
    if (e.tools.isNotEmpty()) {
        Group(stringResource(R.string.can_do)) {
            e.tools.forEach { t -> item { ListRow(t.title, subtitle = t.description, icon = Icons.Sparkle) } }
        }
    }
    // задачи сами в интеграции не уходят: это инструменты ассистента (по просьбе или по правилу)
    if (e.events.isNotEmpty()) {
        Group(stringResource(R.string.ext_sends)) {
            e.events.forEach { ev ->
                item {
                    ListRow(ev.title ?: eventTitle(ev.event), icon = when (ev.event) {
                        "task" -> Icons.Tasks
                        "memory" -> Icons.Bookmark
                        "day" -> Icons.Book
                        else -> Icons.Bubble
                    })
                }
            }
        }
    }
    val toUser = e.events.any { it.toUserUrl }
    if (e.hosts.isNotEmpty() || toUser) {
        Group(stringResource(R.string.internet_access)) {
            e.hosts.forEach { h -> item { ListRow(h, icon = Icons.Globe) } }
            if (toUser) item { ListRow(stringResource(R.string.ext_your_url), icon = Icons.Link) }
        }
    }
}

@Composable
private fun eventTitle(event: String): String = stringResource(when (event) {
    "task" -> R.string.ext_event_task
    "memory" -> R.string.ext_event_memory
    "day" -> R.string.ext_event_day
    else -> R.string.ext_event_conversation
})

/**
 * «Создать с ассистентом»: описание словами -> ассистент собирает интеграцию -> видно, что она умеет,
 * куда ходит и что нужно настроить -> «Установить» или «Поправить».
 */
@Composable
fun ExtensionBuilderScreen() {
    var text by rememberSaveable { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<ExtDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val scope = rememberCoroutineScope()
    val c = Mn.c
    fun build(fix: Boolean) {
        if (busy || text.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                draft = Api.draftExtension(text.trim(), if (fix) draft?.manifest else null, if (fix) feedback else null)
                feedback = ""
            } catch (e: Exception) {
                error = e
            }
            busy = false
        }
    }
    SettingsPage(stringResource(R.string.ext_build)) {
        Group {
            item {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
                    Box {
                        if (text.isEmpty()) Txt(stringResource(R.string.ext_build_placeholder), T.body, c.faint)
                        BasicTextField(text, { text = it }, textStyle = T.body.copy(color = c.ink),
                            cursorBrush = SolidColor(c.lens), minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (text.isEmpty() && draft == null) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.ext_example_1, R.string.ext_example_2, R.string.ext_example_3).forEach { id ->
                    val example = stringResource(id)
                    Txt(example, T.small, c.ink, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.paper)
                        .tap({ text = example }).padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
        error?.let { Txt(errorText(it), T.caption, c.red, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        if (draft == null || busy) {
            Box(Modifier.padding(16.dp)) {
                PrimaryButton(stringResource(if (busy) R.string.ext_building else R.string.ext_build_go), { build(false) },
                    enabled = text.isNotBlank() && !busy)
            }
        }
        val d = draft
        if (d != null && !busy) {
            val e = d.preview
            if (e == null || d.manifest == null) {
                Txt(d.notes ?: stringResource(R.string.ext_build_failed), T.body, c.graphite,
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            } else {
                Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExtIcon(e)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(e.name, T.bodyStrong)
                            Txt(e.description, T.caption, c.graphite)
                        }
                    }
                    d.notes?.let { Txt(it, T.small, c.ink, Modifier.padding(top = 10.dp)) }
                }
                ExtCapabilities(e)
                if (e.settings.isNotEmpty()) {
                    Group(stringResource(R.string.ext_needs)) {
                        e.settings.forEach { st -> item { ListRow(st.title, subtitle = st.hint, icon = if (st.secret) Icons.Lock else Icons.Edit) } }
                    }
                }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PrimaryButton(stringResource(R.string.ext_install), {
                        val m = draft?.manifest
                        if (m != null) scope.launch {
                            try {
                                Api.installManifest(m)
                                Nav.pop()
                                Nav.push(Route.Extension(m.optString("id")))
                            } catch (ex: Exception) {
                                error = ex
                            }
                        }
                    })
                }
                Group(stringResource(R.string.ext_fix)) {
                    item { FieldRow(stringResource(R.string.ext_fix_what), feedback, { feedback = it },
                        stringResource(R.string.ext_fix_placeholder)) }
                    item { ListRow(stringResource(R.string.ext_rebuild), icon = Icons.Refresh, accent = true,
                        onClick = { if (feedback.isNotBlank()) build(true) }) }
                }
            }
        }
    }
}

/** Mnemo в Claude Desktop, Cursor и других программах: адрес MCP и личный ключ (показывается один раз). */
@Composable
fun McpAccessScreen() {
    var info by remember { mutableStateOf<Res<McpInfo>>(Res(loading = true)) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val copied = stringResource(R.string.copied)
    val c = Mn.c
    fun run(block: suspend () -> McpInfo) {
        scope.launch {
            info = try {
                Res(block())
            } catch (e: Exception) {
                info.copy(error = e, loading = false)
            }
        }
    }
    LaunchedEffect(Unit) { run { Api.mcpInfo() } }
    fun copy(text: String) {
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Mnemo", text))
        android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
    }
    SettingsPage(stringResource(R.string.mcp_title)) {
        val d = info.data
        when {
            d == null && info.error != null -> ErrorBlock(info.error, { run { Api.mcpInfo() } })
            d == null -> LoadingBlock()
            else -> {
                Group {
                    item { ListRow(stringResource(R.string.mcp_url), subtitle = d.url, icon = Icons.Link,
                        onClick = { copy(d.url) }) }
                    val token = d.token
                    if (token != null) {
                        item { ListRow(stringResource(R.string.mcp_key), subtitle = token, icon = Icons.Lock,
                            onClick = { copy(token) }) }
                    } else {
                        item { ListRow(stringResource(if (d.enabled) R.string.mcp_key_active else R.string.mcp_key_none),
                            icon = Icons.Lock) }
                    }
                }
                d.token?.let { token ->
                    val config = """{"mcpServers": {"mnemo": {"url": "${d.url}", "headers": {"Authorization": "Bearer $token"}}}}"""
                    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Txt(stringResource(R.string.mcp_config), T.caption, c.graphite)
                        Txt(config, T.small.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), c.ink,
                            Modifier.padding(top = 6.dp))
                        Txt(stringResource(R.string.copy_all), T.smallStrong, c.lens,
                            Modifier.padding(top = 8.dp).quietTap { copy(config) })
                    }
                    Txt(stringResource(R.string.mcp_key_once), T.caption, c.amber, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                Group {
                    item { ListRow(stringResource(if (d.enabled) R.string.mcp_new_key else R.string.mcp_turn_on),
                        icon = Icons.Refresh, accent = true, onClick = { run { Api.mcpNewKey() } }) }
                    if (d.enabled) item { ListRow(stringResource(R.string.mcp_turn_off), icon = Icons.Trash, danger = true,
                        onClick = { run { Api.mcpOff() } }) }
                }
            }
        }
    }
}

@Composable
fun ExtensionScreen(id: String) {
    var ext by remember { mutableStateOf<Res<Extension>>(Res(loading = true)) }
    var values by remember { mutableStateOf(mapOf<String, String>()) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.saved)
    val c = Mn.c
    fun reload() {
        scope.launch {
            ext = try {
                val e = Api.extensions().first.firstOrNull { it.id == id }
                values = e?.settings?.associate { it.key to (it.value ?: "") } ?: emptyMap()
                if (e != null) Res(e) else Res(error = codes.neuro.neurosoft.net.ApiException(
                    codes.neuro.neurosoft.net.ApiException.Kind.NOT_FOUND))
            } catch (e: Exception) {
                Res(error = e)
            }
        }
    }
    LaunchedEffect(id) { reload() }
    val e = ext.data
    SettingsPage(e?.name ?: "", big = false) {
        when {
            e == null && ext.error != null -> ErrorBlock(ext.error, { reload() })
            e == null -> LoadingBlock()
            else -> {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiTile(e.icon ?: "🧩", 64.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Txt(e.name, T.title)
                        Txt(listOfNotNull(e.author, e.version?.let { "v$it" }).joinToString(", "), T.caption, c.graphite)
                    }
                }
                Txt(e.description, T.body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                note?.let { Txt(it, T.caption, c.graphite, Modifier.padding(horizontal = 20.dp)) }
                Group {
                    item {
                        ToggleRow(stringResource(R.string.enabled), e.enabled, { on ->
                            scope.launch {
                                try {
                                    Api.updateExtension(e.id, enabled = on)
                                    reload()
                                } catch (ex: Exception) {
                                    note = ex.message
                                }
                            }
                        }, icon = Icons.Power)
                    }
                }
                if (e.settings.isNotEmpty()) {
                    val savedPlaceholder = stringResource(R.string.saved_secret)
                    val required = stringResource(R.string.required)
                    Group(stringResource(R.string.ext_settings), inset = 22.dp, footer = e.settings.mapNotNull { s ->
                        s.hint?.let { "${s.title}: $it" } }.joinToString("\n").ifBlank { null }) {
                        e.settings.forEach { s ->
                            item {
                                FieldRow(s.title, values[s.key] ?: "", { v -> values = values + (s.key to v) },
                                    placeholder = if (s.secret && s.filled) savedPlaceholder
                                    else if (s.type == "url") "https://…" else if (s.required) required else "",
                                    secret = s.secret, keyboard = if (s.type == "url") KeyboardType.Uri else KeyboardType.Text)
                            }
                        }
                        item {
                            ListRow(stringResource(R.string.save), icon = Icons.Check, accent = true, onClick = {
                                val changed = values.filterValues { it.isNotBlank() }
                                scope.launch {
                                    note = try {
                                        Api.updateExtension(e.id, settings = changed)
                                        reload()
                                        saved
                                    } catch (ex: Exception) {
                                        ex.message
                                    }
                                }
                            })
                        }
                    }
                }
                e.delivery?.let { dv ->
                    Txt(listOfNotNull(
                        stringResource(R.string.ext_delivered, dv.sent),
                        if (dv.waiting > 0) stringResource(R.string.ext_waiting, dv.waiting) else null,
                        dv.error?.takeIf { dv.waiting > 0 || dv.failed > 0 }?.let { stringResource(R.string.ext_last_error, it) },
                    ).joinToString(". "), T.caption, if (dv.waiting > 0 || dv.failed > 0) c.amber else c.graphite,
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                ExtCapabilities(e)
                Group {
                    item {
                        ListRow(stringResource(R.string.remove_extension), icon = Icons.Trash, danger = true, onClick = {
                            scope.launch {
                                try {
                                    Api.deleteExtension(e.id)
                                    Nav.pop()
                                } catch (ex: Exception) {
                                    note = ex.message
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}
