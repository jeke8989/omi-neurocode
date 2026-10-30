package codes.neuro.neurosoft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.ModelOption
import codes.neuro.neurosoft.net.ModelTask
import codes.neuro.neurosoft.net.ModelsInfo
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
private fun priceLine(o: ModelOption?, fmt: Fmt): String = when {
    o == null -> stringResource(R.string.price_in_catalog)
    o.priceHour != null -> stringResource(R.string.price_hour, fmt.price(o.priceHour))
    else -> stringResource(R.string.price_tokens, fmt.price(o.priceIn), fmt.price(o.priceOut))
}

@Composable
fun ModelsScreen() {
    var state by remember { mutableStateOf<Res<ModelsInfo>>(Res(loading = true)) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    fun reload() {
        scope.launch {
            state = try {
                Res(Api.models())
            } catch (e: Exception) {
                Res(error = e)
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    SettingsPage(stringResource(R.string.ai_models)) {
        val info = state.data
        when {
            info == null && state.error != null -> ErrorBlock(state.error, { reload() })
            info == null -> LoadingBlock()
            else -> {
                TileRow(Modifier.padding(top = 12.dp)) {
                    StatTile(stringResource(R.string.spend_30d), fmt.price(info.spendTotal), Modifier.weight(1f))
                    StatTile(stringResource(R.string.provider), if (info.provider == "openrouter") "OpenRouter"
                    else stringResource(R.string.provider_custom), Modifier.weight(1f))
                }
                if (!info.configured) {
                    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                        Txt(stringResource(R.string.no_model_key), T.bodyStrong, Mn.c.red)
                        Txt(stringResource(R.string.no_model_key_text), T.caption, Mn.c.graphite, Modifier.padding(top = 4.dp))
                    }
                }
                val groups = listOf(
                    R.string.models_agent to listOf("ask", "agent", "agent_deep", "search"),
                    R.string.models_speech to listOf("ask_stt", "transcribe"),
                    R.string.models_diary to listOf("summary", "remember", "diary", "vision", "image_style"),
                )
                groups.forEach { (title, keys) ->
                    val tasks = info.tasks.filter { it.key in keys }
                    val rows = tasks.map { t ->
                        val opt = info.catalog.firstOrNull { it.id == t.model }
                        Triple(t, opt, info.spend[t.key])
                    }
                    val subtitles = rows.map { (t, opt, spent) ->
                        listOfNotNull(opt?.name ?: t.model, priceLine(opt, fmt),
                            spent?.let { stringResource(R.string.spent_30d, fmt.price(it)) }).joinToString(", ")
                    }
                    Group(stringResource(title), inset = 22.dp) {
                        rows.forEachIndexed { i, (t, _, _) ->
                            item { ListRow(t.title, subtitle = subtitles[i], chevron = true,
                                onClick = { Nav.push(Route.ModelPicker(t)) }) }
                        }
                    }
                }
                Txt(stringResource(if (info.livePrices) R.string.prices_live else R.string.prices_static), T.caption,
                    Mn.c.graphite, Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp))
            }
        }
    }
}

@Composable
fun ModelPickerScreen(task: ModelTask) {
    var state by remember { mutableStateOf<Res<ModelsInfo>>(Res(loading = true)) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(task.model) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    val c = Mn.c
    LaunchedEffect(Unit) {
        state = try {
            Res(Api.models(all = task.kind == "llm"))
        } catch (e: Exception) {
            Res(error = e)
        }
    }
    fun choose(id: String?) {
        scope.launch {
            try {
                Api.setModel(task.key, id)
                current = id ?: task.default
                error = null
            } catch (e: Exception) {
                error = e
            }
        }
    }
    SettingsPage(task.title) {
        Txt(task.description, T.small, c.graphite, Modifier.padding(horizontal = 20.dp))
        error?.let { StaleNote(it) }
        val info = state.data
        when {
            info == null && state.error != null -> ErrorBlock(state.error, {})
            info == null -> LoadingBlock()
            else -> {
                val recommended = info.catalog.filter { it.kind == task.kind }
                val prices = recommended.map { priceLine(it, fmt) }
                val defaultLabel = stringResource(R.string.default_model)
                Group(stringResource(R.string.recommended), inset = 22.dp) {
                    recommended.forEachIndexed { i, m ->
                        item {
                            ListRow(m.name, subtitle = listOfNotNull(
                                if (m.id == task.default) defaultLabel else m.badge, prices[i], m.note,
                            ).joinToString("\n"), onClick = { choose(m.id) }) {
                                if (m.id == current) Icon(Icons.Check, null, tint = c.ink, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    if (current != task.default) {
                        item { ListRow(stringResource(R.string.reset_default), icon = Icons.Refresh, accent = true,
                            onClick = { choose(null) }) }
                    }
                }
                if (task.kind == "llm" && info.all.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.all_models, info.all.size))
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
                            .background(c.paper).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Search, null, tint = c.graphite, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Txt(stringResource(R.string.model_search_hint), T.body, c.faint, maxLines = 1)
                            BasicTextField(query, { query = it }, singleLine = true, textStyle = T.body.copy(color = c.ink),
                                cursorBrush = SolidColor(c.lens), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    val q = query.trim().lowercase(Locale.ROOT)
                    val found = info.all.filter {
                        q.isEmpty() || q in it.id.lowercase(Locale.ROOT) || q in it.name.lowercase(Locale.ROOT)
                    }.take(40)
                    val foundPrices = found.map { priceLine(it, fmt) }
                    Group(inset = 22.dp) {
                        found.forEachIndexed { i, m ->
                            item {
                                ListRow(m.name, subtitle = m.id + "\n" + foundPrices[i], onClick = { choose(m.id) }) {
                                    if (m.id == current) Icon(Icons.Check, null, tint = c.ink, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
