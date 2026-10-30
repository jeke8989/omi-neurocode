package codes.neuro.neurosoft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
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
import codes.neuro.neurosoft.net.Extension
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
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val installed = stringResource(R.string.installed_ok)
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
            note = try {
                Api.installExtension(id, url)
                reload()
                installed
            } catch (e: Exception) {
                e.message
            }
            busy = null
        }
    }
    val c = Mn.c
    SettingsPage(stringResource(R.string.integrations)) {
        Txt(stringResource(R.string.integrations_intro), T.small, c.graphite, Modifier.padding(horizontal = 20.dp))
        note?.let { Txt(it, T.caption, c.graphite, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        val data = state.data
        when {
            data == null && state.error != null -> ErrorBlock(state.error, { reload() })
            data == null -> LoadingBlock()
            else -> {
                val (mine, available) = data
                val subtitles = mine.map { e ->
                    when {
                        !e.ready -> stringResource(R.string.needs_setup)
                        !e.enabled -> stringResource(R.string.off)
                        e.taskSync != null -> e.taskSync
                        else -> plural(R.plurals.tools, e.tools.size)
                    }
                }
                Group(stringResource(R.string.connected), footer = if (mine.isEmpty()) stringResource(R.string.connected_empty)
                else stringResource(R.string.connected_footer)) {
                    mine.forEachIndexed { i, e ->
                        item {
                            ListRow(e.name, subtitle = subtitles[i], chevron = true,
                                onClick = { Nav.push(Route.Extension(e.id)) }, trailing = null)
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Txt(e.name, T.bodyStrong, maxLines = 1)
                                        if (e.verified) {
                                            Spacer(Modifier.width(6.dp))
                                            Txt(stringResource(R.string.verified), T.tiny, c.lens, maxLines = 1)
                                        }
                                    }
                                    e.author?.let { Txt(it, T.caption, c.graphite, maxLines = 1) }
                                }
                                if (busy == e.id) Spinner(20.dp)
                                else SecondaryButton(stringResource(R.string.add), { install(e.id, null) }, small = true)
                            }
                            Txt(e.description, T.small, c.ink, Modifier.padding(top = 10.dp))
                        }
                    }
                }
                Group(stringResource(R.string.own_extension), footer = stringResource(R.string.own_extension_footer)) {
                    item { FieldRow(stringResource(R.string.manifest_link), manifestUrl, { manifestUrl = it },
                        "https://…/manifest.json", keyboard = KeyboardType.Uri) }
                    item { ListRow(stringResource(R.string.install_by_link), icon = Icons.Link, accent = true,
                        onClick = { if (manifestUrl.isNotBlank()) install(null, manifestUrl.trim()) }) }
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
                    Group(stringResource(R.string.ext_settings), footer = e.settings.mapNotNull { s ->
                        s.hint?.let { "${s.title}: $it" } }.joinToString("\n").ifBlank { null }) {
                        e.settings.forEach { s ->
                            item {
                                FieldRow(s.title, values[s.key] ?: "", { v -> values = values + (s.key to v) },
                                    placeholder = if (s.secret && s.filled) savedPlaceholder else if (s.required) required else "",
                                    secret = s.secret)
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
                e.taskSync?.let { t -> Group(stringResource(R.string.task_sync)) { item { ListRow(t, icon = Icons.Tasks) } } }
                if (e.tools.isNotEmpty()) {
                    Group(stringResource(R.string.can_do)) {
                        e.tools.forEach { t -> item { ListRow(t.title, subtitle = t.description, icon = Icons.Sparkle) } }
                    }
                }
                Group(stringResource(R.string.internet_access), footer = stringResource(R.string.internet_access_footer)) {
                    e.hosts.forEach { h -> item { ListRow(h, icon = Icons.Globe) } }
                }
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
