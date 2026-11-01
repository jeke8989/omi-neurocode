package codes.neuro.neurosoft.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.ble.ConnState
import codes.neuro.neurosoft.net.Api
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Знакомство: как зовут, чем занимается, что важно — и голос по нескольким фразам вслух.
 * Имя и занятие помогают итогам, задачам и ассистенту (называют по имени, понимают контекст работы),
 * а фразы, прочитанные в кулон, дают точный отпечаток голоса: «вы» в расшифровках подписаны с первого дня.
 */
private val GOALS = listOf(R.string.goal_commitments, R.string.goal_people, R.string.goal_meetings, R.string.goal_diary,
    R.string.goal_ideas, R.string.goal_family, R.string.goal_health)

private val PHRASES = listOf(R.string.intro_phrase_1, R.string.intro_phrase_2, R.string.intro_phrase_3,
    R.string.intro_phrase_4)

/** Простой перенос строк для «чипсов». */
@Composable
fun Wrap(spacing: Dp = 8.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0
        var y = 0
        var rowH = 0
        val positions = placeables.map { p ->
            if (x > 0 && constraints.hasBoundedWidth && x + p.width > constraints.maxWidth) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            val pos = IntOffset(x, y)
            x += p.width + gap
            rowH = maxOf(rowH, p.height)
            pos
        }
        // без ограничения ширины (диалог меряет «естественный» размер) — по самой широкой строке, иначе падение
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
        else positions.indices.maxOfOrNull { positions[it].x + placeables[it].width } ?: 0
        layout(width, y + rowH) { placeables.forEachIndexed { i, p -> p.place(positions[i]) } }
    }
}

/** Из настроек: «О себе». */
@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize()) {
        TopBar(null, { Nav.pop() })
        IntroFlow(onboarding = false) { Nav.pop() }
    }
}

/** Два шага: «о себе» и «голос». onboarding — первый запуск (сервер может быть ещё не подключён). */
@Composable
fun IntroFlow(onboarding: Boolean, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val goalTexts = GOALS.map { stringResource(it) }
    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var goals by rememberSaveable { mutableStateOf(listOf<String>()) }
    var voiceStatus by remember { mutableStateOf("none") }
    var loaded by remember { mutableStateOf(onboarding) }
    var page by rememberSaveable { mutableStateOf(0) }
    if (onboarding) LaunchedEffect(Unit) { Core.setPaused(false) }  // кулон должен слышать фразы
    LaunchedEffect(Unit) {
        if (!onboarding) {
            try {
                val p = Api.profile()
                name = p.name.orEmpty()
                about = p.about.orEmpty()
                goals = p.goals
                voiceStatus = p.voice
            } catch (_: Exception) {
            }
            loaded = true
        }
    }

    /** Отправить сейчас; сервер ещё не подключён — отправится сам, как только подключится. */
    fun send(from: Long? = null, to: Long? = null) {
        scope.launch {
            try {
                Api.saveProfile(name.trim(), about.trim(), goals)
                if (from != null && to != null) Api.profileVoice(from, to)
            } catch (_: Exception) {
                val o = JSONObject().put("name", name.trim()).put("about", about.trim()).put("goals", JSONArray(goals))
                if (from != null && to != null) o.put("from", from).put("to", to)
                Core.prefs.pendingProfile = o.toString()
            }
        }
    }

    if (!loaded) {
        LoadingBlock()
        return
    }
    when (page) {
        0 -> AboutPage(name, { name = it }, about, { about = it }, goalTexts, goals, { g ->
            goals = if (g in goals) goals - g else goals + g
        }, onNext = {
            send()
            page = 1
        })
        else -> VoicePage(name.trim(), done = voiceStatus == "done" && !onboarding, onVoice = { from, to -> send(from, to) },
            onFinish = onFinish)
    }
}

@Composable
private fun AboutPage(
    name: String, onName: (String) -> Unit, about: String, onAbout: (String) -> Unit,
    goalTexts: List<String>, goals: List<String>, onGoal: (String) -> Unit, onNext: () -> Unit,
) {
    val c = Mn.c
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Txt(stringResource(R.string.intro_title), T.display, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Txt(stringResource(R.string.intro_text), T.body, c.graphite, Modifier.padding(horizontal = 24.dp))
            Group(inset = 18.dp) {
                item { FieldRow(stringResource(R.string.intro_name), name, onName, stringResource(R.string.intro_name_hint)) }
                item { FieldRow(stringResource(R.string.intro_about), about, onAbout, stringResource(R.string.intro_about_hint)) }
            }
            Txt(stringResource(R.string.intro_goals), T.section, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp))
            Wrap(modifier = Modifier.padding(horizontal = 16.dp)) {
                goalTexts.forEach { g -> Chip(g, g in goals, { onGoal(g) }) }
            }
        }
        PrimaryButton(stringResource(R.string.intro_next), onNext, Modifier.fillMaxWidth().padding(16.dp),
            enabled = name.isNotBlank())
    }
}

@Composable
private fun VoicePage(name: String, done: Boolean, onVoice: (Long, Long) -> Unit, onFinish: () -> Unit) {
    val c = Mn.c
    val device by Core.device.collectAsState()
    val paused by Core.paused.collectAsState()
    val listening = device.state == ConnState.CONNECTED && !paused
    var index by rememberSaveable { mutableStateOf(-1) }   // -1 — ещё не начали; PHRASES.size — готово
    var startedAt by rememberSaveable { mutableStateOf(0L) }
    val phrases = PHRASES.mapIndexed { i, id -> if (i == 0) stringResource(id, name.ifBlank { "…" }) else stringResource(id) }
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Lens(if (index in phrases.indices) LensState.LISTENING else LensState.RECORDING, 132.dp)
            Spacer(Modifier.height(16.dp))
            val title = when {
                index >= phrases.size -> R.string.intro_voice_done
                done && index < 0 -> R.string.intro_voice_known
                else -> R.string.intro_voice_title
            }
            Txt(stringResource(title), T.display, align = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            val text = when {
                index >= phrases.size -> stringResource(R.string.intro_voice_done_text)
                !listening -> stringResource(R.string.intro_voice_no_pendant)
                index < 0 -> stringResource(R.string.intro_voice_text)
                else -> stringResource(R.string.intro_voice_progress, index + 1, phrases.size)
            }
            Txt(text, T.body, c.graphite, align = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            if (index in phrases.indices) {
                Card {
                    Txt("«${phrases[index]}»", T.title, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                index >= phrases.size || !listening -> PrimaryButton(stringResource(R.string.intro_done), onFinish, Modifier.fillMaxWidth())
                index < 0 -> {
                    PrimaryButton(stringResource(if (done) R.string.intro_voice_again else R.string.intro_voice_start), {
                        startedAt = System.currentTimeMillis()
                        index = 0
                    }, Modifier.fillMaxWidth())
                    SecondaryButton(stringResource(R.string.later), onFinish, Modifier.fillMaxWidth())
                }
                else -> PrimaryButton(stringResource(if (index == phrases.size - 1) R.string.intro_done else R.string.intro_next), {
                    if (index == phrases.size - 1) onVoice(startedAt - 1_000, System.currentTimeMillis() + 1_500)
                    index++
                }, Modifier.fillMaxWidth())
            }
        }
    }
}
