@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package codes.neuro.neurosoft.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.Permissions
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.ble.ConnState
import codes.neuro.neurosoft.net.ConversationItem
import codes.neuro.neurosoft.net.DiaryDay
import codes.neuro.neurosoft.voice.VoiceAssistant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun HomeScreen() {
    val pager = rememberPagerState(initialPage = HomeState.page.value) { 4 }
    val scope = rememberCoroutineScope()
    var calendar by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { HomeState.page.value = pager.currentPage }

    Column(Modifier.fillMaxSize()) {
        HomeTopBar(onCalendar = { calendar = true })
        TextTabs(
            listOf(stringResource(R.string.tab_home), stringResource(R.string.tab_tasks), stringResource(R.string.tab_people),
                stringResource(R.string.tab_diary)),
            pager.currentPage,
        ) { scope.launch { pager.animateScrollToPage(it) } }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            when (page) {
                0 -> HomeTab()
                1 -> TasksTab()
                2 -> PeopleTab()
                else -> DiaryTab()
            }
        }
        BottomAskBar()
    }
    if (calendar) {
        CalendarSheet(
            selected = HomeState.day.value,
            onDismiss = { calendar = false },
            onPick = {
                HomeState.day.value = it
                calendar = false
                scope.launch { pager.animateScrollToPage(0) }
            },
        )
    }
}

// ======================================================================= верхняя панель
@Composable
private fun HomeTopBar(onCalendar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DevicePill()
        Spacer(Modifier.weight(1f))
        IconCircle(Icons.Calendar, stringResource(R.string.cd_calendar), onCalendar)
        Spacer(Modifier.width(8.dp))
        IconCircle(Icons.Search, stringResource(R.string.cd_search), { Nav.push(Route.Search) })
        Spacer(Modifier.width(8.dp))
        IconCircle(Icons.Gear, stringResource(R.string.cd_settings), { Nav.push(Route.Settings) })
    }
}

@Composable
private fun DevicePill() {
    val device by Core.device.collectAsState()
    val state = rememberLensState()
    val c = Mn.c
    val label = device.name ?: stringResource(R.string.pendant)
    Row(
        Modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(c.paper)
            .tap({ Nav.push(if (device.address == null) Route.Onboarding else Route.Device) })
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lens(state, 30.dp)
        Spacer(Modifier.width(4.dp))
        if (device.address == null) {
            Txt(stringResource(R.string.connect_pendant), T.smallStrong, c.lens, maxLines = 1)
        } else {
            Txt(label, T.smallStrong, maxLines = 1, modifier = Modifier.widthIn(max = 96.dp))
            device.battery?.let { b ->
                Spacer(Modifier.width(8.dp))
                if (device.charging == true) {
                    Icon(Icons.Bolt, null, tint = c.graphite, modifier = Modifier.size(14.dp))
                }
                Txt("$b%", T.small, if (b < 15) c.red else c.graphite, maxLines = 1)
            }
        }
    }
}

/** Навигация крупным текстом: «Главная  Задачи  Дневник». */
@Composable
private fun TextTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Mn.c
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth - with(density) { 40.dp.toPx() }
        // как у Omi — крупно, но все вкладки целиком: на узком экране и длинных словах шрифт чуть меньше
        val scale = remember(titles, available) {
            val words = titles.sumOf { measurer.measure(it, T.display).size.width }
            val gaps = with(density) { 20.dp.toPx() } * (titles.size - 1)
            (available / (words + gaps)).coerceIn(0.6f, 1f)
        }
        val style = T.display.copy(fontSize = T.display.fontSize * scale, lineHeight = T.display.lineHeight * scale)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp * scale),
        ) {
            titles.forEachIndexed { i, t ->
                val color by animateColorAsState(if (i == selected) c.ink else c.faint, label = "tab")
                Txt(t, style, color, maxLines = 1, modifier = Modifier.quietTap { onSelect(i) })
            }
        }
    }
}

// ======================================================================= нижняя строка
@Composable
private fun BottomAskBar() {
    val c = Mn.c
    val locale = LocalConfiguration.current.locales[0]
    val prompt = stringResource(R.string.ask_anything)
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) Nav.push(Route.Chat(prefill = text))
    }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.paper)
                .tap({ Nav.push(Route.Chat()) }).padding(start = 20.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(prompt, T.body, c.graphite, Modifier.weight(1f), maxLines = 1)
            IconCircle(Icons.Mic, stringResource(R.string.cd_voice_input), {
                try {
                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt))
                } catch (e: ActivityNotFoundException) {
                    Nav.push(Route.Chat())
                }
            }, size = 44.dp, background = c.raised)
        }
        Spacer(Modifier.width(10.dp))
        LensButton()
    }
}

/** Круглая кнопка с линзой: пауза/продолжить запись; долгое нажатие — экран кулона. */
@Composable
private fun LensButton() {
    val context = LocalContext.current
    val device by Core.device.collectAsState()
    val running by Core.serviceRunning.collectAsState()
    val paused by Core.paused.collectAsState()
    val state = rememberLensState()
    val c = Mn.c
    val description = stringResource(when {
        device.address == null -> R.string.connect_pendant
        !running -> R.string.start_recording
        paused -> R.string.resume_recording
        else -> R.string.pause_recording
    })
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(c.ink)
            .semantics { contentDescription = description }
            .combinedClickable(
                onClick = {
                    when {
                        device.address == null -> Nav.push(Route.Onboarding)
                        !running -> if (Permissions.bluetoothGranted(context)) Core.startCapture(context)
                        else Nav.push(Route.Onboarding)
                        else -> Core.setPaused(!paused)
                    }
                },
                onLongClick = { Nav.push(Route.Device) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Lens(state, 46.dp)
        if (paused && running) {
            Icon(Icons.Pause, null, tint = c.onInk, modifier = Modifier.size(16.dp))
        }
    }
}

// ======================================================================= вкладка «Главная»
@Composable
fun HomeTab() {
    val day by HomeState.day
    val today = todayIso()
    val daysRes by Repo.days.state.collectAsState()
    val convRes by Repo.conversations(day).state.collectAsState()
    val diaryRes by Repo.diary.state.collectAsState()
    val fmt = rememberFmt()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(day) {
        var n = 0
        while (true) {
            Repo.refreshAll(day)
            if (n++ % 6 == 0) Repo.diary.refresh()
            delay(if (day == todayIso()) 20_000 else 120_000)
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                Repo.refreshAll(day)
                Repo.diary.refresh()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item(key = "live") { LiveStrip() }
            item(key = "days") { DayStrip(daysRes.data?.map { it.day } ?: emptyList(), day) { HomeState.day.value = it } }
            if (day == today) {
                item(key = "answer") { AssistantCard() }
                val summaries = diaryRes.data?.days?.filter { it.status == "done" && !it.title.isNullOrBlank() }.orEmpty()
                if (summaries.isNotEmpty()) {
                    item(key = "summaries-title") { SectionTitle(stringResource(R.string.day_summaries)) }
                    item(key = "summaries") { SummariesCarousel(summaries) }
                }
            }
            val list = convRes.data
            item(key = "day-title") {
                val total = list?.sumOf { it.durationSec.toLong() } ?: 0L
                SectionTitle(fmt.dayShortTitle(day)) {
                    if (total > 0) Txt(fmt.duration(total), T.small, Mn.c.graphite)
                }
            }
            when {
                list == null && convRes.error != null -> item(key = "err") {
                    ErrorBlock(convRes.error, { scope.launch { Repo.refreshAll(day) } })
                }
                list == null -> item(key = "loading") { LoadingBlock() }
                list.isEmpty() -> item(key = "empty") {
                    EmptyBlock(Icons.Wave, stringResource(if (day == today) R.string.empty_today_title else R.string.empty_day_title),
                        stringResource(if (day == today) R.string.empty_today_text else R.string.empty_day_text))
                }
                else -> {
                    item(key = "stale") { StaleNote(convRes.error) }
                    itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                        Grouped(i, list.size) { ConversationRow(c, fmt) }
                    }
                }
            }
        }
    }
}

/** Строка внутри карточки-группы в ленивом списке: скругления сверху у первой и снизу у последней. */
@Composable
fun Grouped(index: Int, count: Int, inset: androidx.compose.ui.unit.Dp = 74.dp, content: @Composable () -> Unit) {
    val r = 20.dp
    val shape: Shape = RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp, topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp, bottomEnd = if (index == count - 1) r else 0.dp,
    )
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(Mn.c.paper)) {
        if (index > 0) HorizontalDivider(Modifier.padding(start = inset), thickness = 0.6.dp, color = Mn.c.line)
        content()
    }
}

@Composable
fun ConversationRow(c: ConversationItem, fmt: Fmt, showDay: Boolean = false) {
    val colors = Mn.c
    Row(
        Modifier.fillMaxWidth().tap({ Nav.push(Route.Conversation(c.id)) })
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiTile(c.emoji)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(c.title ?: c.preview ?: stringResource(R.string.untitled), T.bodyStrong, maxLines = 2)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt((if (showDay) fmt.dayShortTitle(fmt.dayOf(c.startedAt)) + ", " else "") + fmt.time(c.startedAt),
                    T.caption, colors.graphite)
                if (c.tasksCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Tasks, null, tint = colors.graphite, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Txt("${c.tasksCount}", T.caption, colors.graphite)
                }
                // с кем говорили (кроме владельца): «Анна, Голос 3 +1»
                val others = c.people.filter { !it.isOwner }
                if (others.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Person, null, tint = colors.graphite, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    val names = others.take(2).map { personLabel(it) }.joinToString(", ") +
                        if (others.size > 2) " +${others.size - 2}" else ""
                    Txt(names, T.caption, colors.graphite, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        when (c.status) {
            "open", "processing" -> Spinner(16.dp)
            "failed" -> Icon(Icons.Info, stringResource(R.string.status_failed), tint = colors.amber,
                modifier = Modifier.size(18.dp))
            else -> Txt(fmt.duration(c.durationSec.toLong()), T.caption, colors.graphite)
        }
    }
}

// ======================================================================= живая полоса
private class Strip(val title: String, val text: String?, val action: String? = null, val onAction: (() -> Unit)? = null,
                    val warn: Boolean = false)

@Composable
private fun LiveStrip() {
    val context = LocalContext.current
    val device by Core.device.collectAsState()
    val upload by Core.upload.collectAsState()
    val running by Core.serviceRunning.collectAsState()
    val paused by Core.paused.collectAsState()
    val voice by Core.voice.state.collectAsState()
    val state = rememberLensState()
    val fmt = rememberFmt()
    val c = Mn.c
    var missing by remember { mutableStateOf(Permissions.missing(context)) }
    val days by Repo.days.state.collectAsState()
    val todaySpeech = days.data?.firstOrNull { it.day == todayIso() }?.speechSeconds?.takeIf { it > 0 }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = Permissions.missing(context)
        if (Permissions.bluetoothGranted(context) && device.address != null) Core.startCapture(context)
    }
    val now = System.currentTimeMillis()

    val strip: Strip? = when {
        device.address == null -> Strip(stringResource(R.string.live_no_device), stringResource(R.string.live_no_device_text),
            stringResource(R.string.connect), { Nav.push(Route.Onboarding) })
        !Permissions.bluetoothGranted(context) || missing.isNotEmpty() && !running ->
            Strip(stringResource(R.string.live_permissions), stringResource(R.string.live_permissions_text),
                stringResource(R.string.allow), { permissions.launch(Permissions.required()) }, warn = true)
        !Core.prefs.serverConfigured || upload.error == "auth" -> Strip(stringResource(R.string.live_server_pair),
            stringResource(R.string.live_server_pair_text), stringResource(R.string.connect), { Nav.push(Route.Server) },
            warn = true)
        !running -> Strip(stringResource(R.string.live_off), stringResource(R.string.live_off_text),
            stringResource(R.string.turn_on), { Core.startCapture(context) })
        voice.phase == VoiceAssistant.Phase.LISTENING -> Strip(stringResource(R.string.live_listening), null)
        voice.phase == VoiceAssistant.Phase.THINKING -> Strip(stringResource(R.string.live_thinking), voice.question)
        voice.phase == VoiceAssistant.Phase.SPEAKING -> Strip(stringResource(R.string.live_speaking), voice.question)
        device.state == ConnState.BLUETOOTH_OFF -> Strip(stringResource(R.string.state_bluetooth_off),
            stringResource(R.string.live_bt_off_text), warn = true)
        device.state != ConnState.CONNECTED -> Strip(
            stringResource(R.string.live_disconnected),
            if (device.disconnectedSince > 0) stringResource(R.string.live_disconnected_for,
                fmt.duration((now - device.disconnectedSince) / 1000)) else stringResource(R.string.live_searching_text),
            warn = true)
        paused -> Strip(stringResource(R.string.live_paused), stringResource(R.string.live_paused_text),
            stringResource(R.string.resume), { Core.setPaused(false) })
        !upload.connected && !upload.resting -> Strip(stringResource(R.string.live_server_offline),
            if (upload.pendingFiles > 1) stringResource(R.string.live_queue, fmt.bytes(upload.pendingBytes))
            else stringResource(R.string.live_server_offline_text), warn = true)
        upload.connected && upload.pendingFiles > 5 -> Strip(stringResource(R.string.live_catching_up),
            stringResource(R.string.live_queue_left, fmt.bytes(upload.pendingBytes)))
        state == LensState.RECORDING -> Strip(stringResource(R.string.live_recording),
            // полезное время — речь в разговорах за сегодня (считает сервер), а не время работы микрофона
            todaySpeech?.let { stringResource(R.string.live_talk_today, fmt.duration(it.toLong())) }
                ?: stringResource(R.string.live_quiet_note))
        else -> Strip(stringResource(R.string.live_connected), stringResource(R.string.live_connected_text))
    }
    if (strip == null) return
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(c.paper).animateContentSize().tap({ Nav.push(Route.Device) })
            .padding(start = 8.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lens(state, 44.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Txt(strip.title, T.bodyStrong, maxLines = 1)
            strip.text?.let { Txt(it, T.caption, c.graphite, maxLines = 2) }
        }
        if (strip.action != null && strip.onAction != null) {
            Spacer(Modifier.width(8.dp))
            SecondaryButton(strip.action, strip.onAction, small = true, color = c.ink)
        }
    }
}

// ======================================================================= лента дней
@Composable
private fun DayStrip(days: List<String>, selected: String, onSelect: (String) -> Unit) {
    val fmt = rememberFmt()
    val today = todayIso()
    val all = (listOf(today) + days + listOf(selected)).distinct().sortedDescending().take(30)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(all, key = { it }) { d ->
            val date = LocalDate.parse(d)
            val label = when (d) {
                today -> stringResource(R.string.today)
                else -> fmt.weekdayShort(date) + " " + date.dayOfMonth
            }
            Chip(label, d == selected, { onSelect(d) })
        }
    }
}

// ======================================================================= ответ ассистента
@Composable
private fun AssistantCard() {
    val voice by Core.voice.state.collectAsState()
    val answer = voice.answer ?: return
    if (System.currentTimeMillis() - voice.at > 6 * 3600_000L) return
    val c = Mn.c
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onClick = { Nav.push(Route.Chat()) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Sparkle, null, tint = c.lens, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Txt(stringResource(R.string.assistant_answer), T.caption, c.graphite)
        }
        voice.question?.let {
            Spacer(Modifier.height(8.dp))
            Txt(stringResource(R.string.quoted, it), T.small, c.graphite, maxLines = 2)
        }
        Spacer(Modifier.height(6.dp))
        Txt(answer, T.body, maxLines = 6)
        if (voice.pending) {
            Spacer(Modifier.height(10.dp))
            Txt(stringResource(R.string.answer_in_chat), T.smallStrong, c.lens)
        }
    }
}

// ======================================================================= сводки дня
@Composable
private fun SummariesCarousel(days: List<DiaryDay>) {
    val fmt = rememberFmt()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(days.take(10), key = { it.day }) { d ->
            Column(
                Modifier.width(264.dp).clip(RoundedCornerShape(22.dp)).background(Mn.c.paper)
                    .tap({ Nav.push(Route.Day(d.day)) }),
            ) {
                DayCover(d, Modifier.fillMaxWidth().height(132.dp))
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                    Txt(d.title ?: "", T.bodyStrong, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    val n = d.stats["conversations"] ?: 0
                    val minutes = d.stats["minutes"] ?: 0
                    Txt(listOfNotNull(fmt.dayShortTitle(d.day),
                        if (n > 0) plural(R.plurals.conversations, n) else null,
                        if (minutes > 0) fmt.duration(minutes * 60L) else null).joinToString(", "),
                        T.caption, Mn.c.graphite, maxLines = 1)
                }
            }
        }
    }
}
