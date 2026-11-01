package codes.neuro.neurosoft.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.BuildConfig
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.Lang
import codes.neuro.neurosoft.Permissions
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.ble.ConnState
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.MemoryItem
import kotlinx.coroutines.launch

/** Каркас страницы настроек: верхняя панель, крупный заголовок, прокрутка. */
@Composable
fun SettingsPage(title: String, big: Boolean = true, actions: @Composable RowScope.() -> Unit = {},
                 content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(if (big) null else title, { Nav.pop() }, actions)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(bottom = 32.dp)) {
            if (big) Txt(title, T.display, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
            content()
        }
    }
}

@Composable
fun deviceStatusText(): String {
    val device by Core.device.collectAsState()
    val running by Core.serviceRunning.collectAsState()
    val paused by Core.paused.collectAsState()
    val upload by Core.upload.collectAsState()
    return stringResource(when {
        device.address == null -> R.string.state_not_paired
        !running -> R.string.state_recording_off
        device.state == ConnState.BLUETOOTH_OFF -> R.string.state_bluetooth_off
        device.state == ConnState.CONNECTING -> R.string.state_connecting
        device.state == ConnState.SETTING_UP -> R.string.state_setting_up
        device.state != ConnState.CONNECTED -> R.string.state_disconnected
        paused -> R.string.state_paused
        !upload.connected && !upload.resting -> R.string.state_listening_offline
        else -> R.string.state_listening
    })
}

private fun isIgnoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

// ======================================================================= корень
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val device by Core.device.collectAsState()
    val upload by Core.upload.collectAsState()
    val features by Repo.features.state.collectAsState()
    val lens = rememberLensState()
    val fmt = rememberFmt()
    val scope = rememberCoroutineScope()
    var autoStart by remember { mutableStateOf(Core.prefs.autoStart) }
    var ignoringBattery by remember { mutableStateOf(isIgnoringBattery(context)) }
    var confirmClear by remember { mutableStateOf(false) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ignoringBattery = isIgnoringBattery(context)
    }
    LaunchedEffect(Unit) {
        Repo.features.refresh()
        Repo.serverStatus.refresh()
    }
    val server by Repo.serverStatus.state.collectAsState()
    val c = Mn.c

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.paper,
            title = { Text(stringResource(R.string.clear_chat_q), style = T.section, color = c.ink) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { try { Api.clearChat(); Repo.chat.refresh() } catch (_: Exception) {} }
                }) { Text(stringResource(R.string.clear), color = c.red, style = T.bodyStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel), color = c.ink, style = T.bodyStrong)
                }
            },
        )
    }

    SettingsPage(stringResource(R.string.settings)) {
        // кулон — крупной карточкой, как главное устройство
        Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), padding = 14.dp,
            onClick = { Nav.push(if (device.address == null) Route.Onboarding else Route.Device) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PendantRender(lens, 72.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(device.name ?: stringResource(R.string.pendant), T.section)
                    Txt(deviceStatusText() + (device.battery?.let { ", $it%" } ?: ""), T.small, c.graphite, maxLines = 2)
                }
                Icon(Icons.Chevron, null, tint = c.faint, modifier = Modifier.size(18.dp))
            }
        }

        Group(stringResource(R.string.assistant)) {
            // знакомство: имя, занятие, цели и голос — ИИ понимает контекст и называет по имени
            item { ListRow(stringResource(R.string.about_me), icon = Icons.Person, chevron = true,
                onClick = { Nav.push(Route.About) }) }
            item { ListRow(stringResource(R.string.routines_title), icon = Icons.Clock, chevron = true,
                onClick = { Nav.push(Route.Routines) }) }
            item { ListRow(stringResource(R.string.voice_and_button), icon = Icons.Speaker, chevron = true,
                onClick = { Nav.push(Route.Voice) }) }
            item { ListRow(stringResource(R.string.integrations), icon = Icons.Puzzle, chevron = true,
                onClick = { Nav.push(Route.Extensions) }) }
            item {
                var prompts by remember { mutableStateOf(Core.prefs.taskPrompts) }
                ToggleRow(stringResource(R.string.task_prompt_setting), prompts, {
                    prompts = it
                    Core.prefs.taskPrompts = it
                }, icon = Icons.Tasks)
            }
        }

        Group(stringResource(R.string.tab_people)) {
            item { ListRow(stringResource(R.string.voices_title), icon = Icons.People, chevron = true,
                onClick = { Nav.push(Route.VoicesSettings) }) }
        }

        Group(stringResource(R.string.tab_diary)) {
            item { ListRow(stringResource(R.string.diary_and_photos), icon = Icons.Book, chevron = true,
                value = features.data?.let { stringResource(if (it.diary) R.string.on else R.string.off) },
                onClick = { Nav.push(Route.DiarySettings) }) }
            item {
                var morning by remember { mutableStateOf(Core.prefs.morningSummary) }
                ToggleRow(stringResource(R.string.morning_setting), morning, {
                    morning = it
                    Core.prefs.morningSummary = it
                }, icon = Icons.Bell)
            }
        }

        Group(stringResource(R.string.appearance)) {
            item { ListRow(stringResource(R.string.theme), icon = Icons.Moon, chevron = true,
                value = themeTitle(ThemeMode.state.value), onClick = { Nav.push(Route.Theme) }) }
        }

        val bioAvailable = codes.neuro.neurosoft.AppLock.biometricAvailable(LocalContext.current)
        Group(stringResource(R.string.security)) {
            val active = codes.neuro.neurosoft.AppLock.active.value
            item {
                ToggleRow(stringResource(R.string.lock_pin), active, { on ->
                    if (on) Nav.push(Route.PinSetup) else codes.neuro.neurosoft.AppLock.disable()
                }, icon = Icons.Lock)
            }
            if (active) {
                if (bioAvailable) {
                    item {
                        var bio by remember { mutableStateOf(Core.prefs.lockBiometric) }
                        ToggleRow(stringResource(R.string.lock_biometric), bio, {
                            bio = it
                            Core.prefs.lockBiometric = it
                        }, icon = Icons.Fingerprint)
                    }
                }
                item {
                    var after by remember { mutableStateOf(Core.prefs.lockAfterSec) }
                    ListRow(stringResource(R.string.lock_after), icon = Icons.Clock, value = stringResource(when (after) {
                        0 -> R.string.lock_after_now
                        60 -> R.string.lock_after_1
                        else -> R.string.lock_after_5
                    }), onClick = {
                        after = when (after) {
                            0 -> 60
                            60 -> 300
                            else -> 0
                        }
                        Core.prefs.lockAfterSec = after
                    })
                }
                item { ListRow(stringResource(R.string.lock_change), icon = Icons.Edit, chevron = true,
                    onClick = { Nav.push(Route.PinSetup) }) }
            }
        }

        Group(stringResource(R.string.language)) {
            val code = Lang.code(Core.prefs)
            item { ListRow(stringResource(R.string.app_language), icon = Icons.Globe, chevron = true,
                value = if (Core.prefs.language.isBlank()) stringResource(R.string.lang_system)
                else Lang.supported.firstOrNull { it.first == code }?.second,
                onClick = { Nav.push(Route.Language) }) }
            item { ListRow(stringResource(R.string.speech_language), icon = Icons.Translate, chevron = true,
                value = sttChoiceTitle(features.data?.sttLanguage ?: "app"), onClick = { Nav.push(Route.SttLanguage) }) }
        }

        Group(stringResource(R.string.data)) {
            item { ListRow(stringResource(R.string.memory), icon = Icons.Bookmark, chevron = true,
                onClick = { Nav.push(Route.Memories) }) }
            item { ListRow(stringResource(R.string.trash), icon = Icons.Trash, chevron = true,
                onClick = { Nav.push(Route.Trash) }) }
            item { ListRow(stringResource(R.string.audio_keep), icon = Icons.Wave, chevron = true,
                value = features.data?.let { audioKeepTitle(it.audioKeepDays) },
                onClick = { Nav.push(Route.AudioKeep) }) }
        }

        Group(stringResource(R.string.background)) {
            item {
                ListRow(
                    stringResource(if (ignoringBattery) R.string.battery_unrestricted else R.string.battery_allow),
                    icon = Icons.Power, chevron = !ignoringBattery,
                    onClick = if (ignoringBattery) null else ({
                        try {
                            batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")))
                        } catch (_: Exception) {
                            batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }),
                )
            }
            item {
                var saver by remember { mutableStateOf(Core.prefs.batterySaver) }
                ToggleRow(stringResource(R.string.battery_saver), saver, {
                    saver = it
                    Core.setBatterySaver(it)
                }, icon = Icons.Battery)
            }
            item { ToggleRow(stringResource(R.string.autostart), autoStart, {
                autoStart = it
                Core.prefs.autoStart = it
            }, icon = Icons.Refresh) }
        }

        Group(stringResource(R.string.about)) {
            item { ListRow(stringResource(R.string.server), icon = Icons.Cloud, chevron = true,
                onClick = { Nav.push(Route.Server) },
                value = stringResource(when {
                    !Core.prefs.serverConfigured -> R.string.server_not_connected
                    upload.error == "auth" -> R.string.server_reconnect
                    upload.connected -> R.string.server_online
                    upload.resting -> R.string.server_batched
                    upload.connecting -> R.string.server_connecting
                    else -> R.string.server_offline
                })) }
            if (upload.pendingFiles > 1) {
                item { ListRow(stringResource(R.string.queue), icon = Icons.Wave, value = fmt.bytes(upload.pendingBytes)) }
            }
            // сколько стоят модели ИИ (выбираются сами, самые выгодные для каждой задачи)
            server.data?.aiCostMonth?.let { cost ->
                item { ListRow(stringResource(R.string.ai_cost_month), icon = Icons.Chip, value = "$" + String.format(java.util.Locale.US, "%.2f", cost)) }
            }
            item { ListRow(stringResource(R.string.version), icon = Icons.Info, value = BuildConfig.VERSION_NAME) }
        }
    }
}

@Composable
fun sttChoiceTitle(choice: String): String = when (choice) {
    "app" -> stringResource(R.string.stt_like_app)
    "auto" -> stringResource(R.string.stt_auto)
    else -> Lang.supported.firstOrNull { it.first == choice }?.second ?: choice
}

// ======================================================================= голос и кнопка
@Composable
fun VoiceSettingsScreen() {
    val prefs = Core.prefs
    val voice by Core.voice.state.collectAsState()
    var earcons by remember { mutableStateOf(prefs.earcons) }
    var confirm by remember { mutableStateOf(prefs.confirmRemember) }
    var actions by remember { mutableStateOf(prefs.buttonActions) }
    var doubleTap by remember { mutableStateOf(prefs.doubleTap) }
    var voiceReply by remember { mutableStateOf(prefs.voiceReply) }
    val headphones = remember { Core.voice.headphonesConnected() }
    val rates = listOf(0.9f, 1.05f, 1.2f, 1.4f)
    var rate by remember { mutableStateOf(rates.indexOfFirst { it == prefs.voiceRate }.let { if (it < 0) 1 else it }) }
    var ttsVoice by remember { mutableStateOf(prefs.ttsVoice) }

    SettingsPage(stringResource(R.string.voice_and_button)) {
        Group(stringResource(R.string.pendant_button)) {
            item { ToggleRow(stringResource(R.string.button_actions), actions, {
                actions = it
                prefs.buttonActions = it
            }, icon = Icons.Hand) }
        }
        Group(stringResource(R.string.voice_reply)) {
            listOf(
                Triple("always", R.string.voice_reply_always, Icons.Speaker),
                Triple("headphones", R.string.voice_reply_headphones, Icons.Bluetooth),
                Triple("off", R.string.voice_reply_off, Icons.Bubble),
            ).forEach { (key, title, icon) ->
                item {
                    ListRow(stringResource(title), icon = icon,
                        subtitle = if (key == "headphones") stringResource(if (headphones) R.string.headphones_on
                        else R.string.headphones_off) else null,
                        onClick = {
                            voiceReply = key
                            prefs.voiceReply = key
                        }) {
                        if (voiceReply == key) Icon(Icons.Check, null, tint = Mn.c.ink, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Group(stringResource(R.string.double_tap)) {
            listOf("remember" to R.string.double_remember, "pause" to R.string.double_pause, "off" to R.string.double_off)
                .forEach { (key, title) ->
                    item {
                        ListRow(stringResource(title), icon = if (key == "pause") Icons.Pause else if (key == "off")
                            Icons.Close else Icons.Bookmark, onClick = {
                            doubleTap = key
                            prefs.doubleTap = key
                        }) {
                            if (doubleTap == key) Icon(Icons.Check, null, tint = Mn.c.ink, modifier = Modifier.size(20.dp))
                        }
                    }
                }
        }
        Group(stringResource(R.string.voice_choice)) {
            listOf(
                Triple("f1", R.string.voice_f1, R.string.voice_neural_sub),
                Triple("m1", R.string.voice_m1, R.string.voice_neural_sub),
                Triple("system", R.string.voice_system, R.string.voice_system_sub),
            ).forEach { (key, title, sub) ->
                item {
                    ListRow(stringResource(title), icon = if (key == "system") Icons.Speaker else Icons.Sparkle,
                        subtitle = stringResource(sub), onClick = {
                            ttsVoice = key
                            prefs.ttsVoice = key
                            Core.voice.testVoice()
                        }) {
                        if (ttsVoice == key) Icon(Icons.Check, null, tint = Mn.c.ink, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Group(stringResource(R.string.voice)) {
            item {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Speaker, null, tint = Mn.c.ink, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Txt(stringResource(R.string.voice_speed), T.body)
                    }
                    Spacer(Modifier.height(12.dp))
                    val labels = listOf(R.string.speed_slow, R.string.speed_normal, R.string.speed_fast, R.string.speed_faster)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(labels.size) { i ->
                            Chip(stringResource(labels[i]), i == rate, {
                                rate = i
                                Core.voice.setRate(rates[i])
                            })
                        }
                    }
                }
            }
            item { ListRow(stringResource(R.string.test_voice), icon = Icons.Play,
                subtitle = if (voice.ttsReady || ttsVoice != "system") null else stringResource(R.string.tts_missing),
                onClick = { Core.voice.testVoice() }) }
            item { ToggleRow(stringResource(R.string.earcons), earcons, {
                earcons = it
                prefs.earcons = it
            }, icon = Icons.Wave) }
            item { ToggleRow(stringResource(R.string.say_remembered), confirm, {
                confirm = it
                prefs.confirmRemember = it
            }, icon = Icons.Bookmark) }
        }
    }
}

// ======================================================================= дневник и фото
@Composable
fun DiarySettingsScreen() {
    val context = LocalContext.current
    val res by Repo.features.state.collectAsState()
    val scope = rememberCoroutineScope()
    var location by remember { mutableStateOf(Core.prefs.locationEnabled) }
    var photoAuto by remember { mutableStateOf(Core.prefs.photoAutoImport) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(Unit) { Repo.features.refresh() }

    fun set(changes: Map<String, Any>) {
        scope.launch {
            try {
                Api.setFeatures(changes)
                Repo.features.refresh()
                error = null
            } catch (e: Exception) {
                error = e
            }
        }
    }
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = Core.locationPermissionGranted()
        location = ok
        Core.prefs.locationEnabled = ok
        Core.applyLocationSetting()
    }
    val photoPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = Permissions.granted(context, Permissions.photos())
        photoAuto = ok
        Core.prefs.photoAutoImport = ok
        if (ok) Core.syncPhotosNow()
    }
    val f = res.data

    SettingsPage(stringResource(R.string.diary_and_photos)) {
        error?.let { StaleNote(it) }
        Group {
            item { ToggleRow(stringResource(R.string.day_feed), f?.diary ?: true, { set(mapOf("diary" to it)) },
                icon = Icons.Book, enabled = f != null) }
            item {
                ToggleRow(stringResource(R.string.location_while_recording), location, { on ->
                    if (on && !Core.locationPermissionGranted()) locationPerm.launch(Permissions.location())
                    else {
                        location = on
                        Core.prefs.locationEnabled = on
                        Core.applyLocationSetting()
                    }
                }, icon = Icons.Pin)
            }
            item {
                ToggleRow(stringResource(R.string.photos_from_phone), photoAuto, { on ->
                    if (on && !Permissions.granted(context, Permissions.photos())) photoPerm.launch(Permissions.photos())
                    else {
                        photoAuto = on
                        Core.prefs.photoAutoImport = on
                        if (on) Core.syncPhotosNow()
                    }
                }, icon = Icons.Photo)
            }
        }
    }
}

@Composable
private fun styleTitle(key: String, fallback: String): String = when (key) {
    "watercolor" -> stringResource(R.string.style_watercolor)
    "film" -> stringResource(R.string.style_film)
    "illustration" -> stringResource(R.string.style_illustration)
    "oil" -> stringResource(R.string.style_oil)
    "comic" -> stringResource(R.string.style_comic)
    "sketch" -> stringResource(R.string.style_sketch)
    else -> fallback
}

// ======================================================================= тема
@Composable
fun themeTitle(mode: String): String = stringResource(when (mode) {
    "light" -> R.string.theme_light
    "dark" -> R.string.theme_dark
    else -> R.string.theme_system
})

@Composable
fun ThemeScreen() {
    val current = ThemeMode.state.value
    SettingsPage(stringResource(R.string.theme)) {
        Group(inset = 22.dp) {
            listOf("system", "light", "dark").forEach { mode ->
                item {
                    ListRow(themeTitle(mode), onClick = { ThemeMode.set(mode) },
                        trailing = { if (mode == current) Icon(Icons.Check, null, tint = Mn.c.ink, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemePreview(LightTokens, current == "light", Modifier.weight(1f)) { ThemeMode.set("light") }
            ThemePreview(DarkTokens, current == "dark", Modifier.weight(1f)) { ThemeMode.set("dark") }
        }
    }
}

/** Маленький экран-образец темы: фон, карточка, строки текста и свет линзы. */
@Composable
private fun ThemePreview(t: Tokens, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.clip(shape).background(t.fog)
            .border(if (selected) 2.dp else 1.dp, if (selected) Mn.c.lens else Mn.c.line, shape)
            .tap(onClick).padding(12.dp),
    ) {
        Box(Modifier.size(width = 54.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(t.ink))
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.paper).padding(10.dp)) {
            repeat(2) { i ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(t.raised))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Box(Modifier.size(width = 58.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(t.ink))
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(t.graphite))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(t.lens))
            Spacer(Modifier.width(6.dp))
            Txt(stringResource(if (t.dark) R.string.theme_dark else R.string.theme_light), T.tiny, t.ink, maxLines = 1)
        }
    }
}

// ======================================================================= язык
@Composable
fun LanguageScreen() {
    val context = LocalContext.current
    val current = Core.prefs.language
    val options = listOf("" to stringResource(R.string.lang_system)) + Lang.supported
    SettingsPage(stringResource(R.string.app_language)) {
        Group(inset = 22.dp) {
            options.forEach { (code, name) ->
                item {
                    ListRow(name, onClick = {
                        if (code != current) {
                            Core.setLanguage(code)
                            (context as? Activity)?.recreate()
                        }
                    }) {
                        if (code == current) Icon(Icons.Check, null, tint = Mn.c.ink, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SttLanguageScreen() {
    val res by Repo.features.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(Unit) { Repo.features.refresh() }
    val current = res.data?.sttLanguage ?: "app"
    SettingsPage(stringResource(R.string.speech_language)) {
        error?.let { StaleNote(it) }
        Group(inset = 22.dp) {
            (listOf("app", "auto") + Lang.supported.map { it.first }).forEach { code ->
                item {
                    ListRow(sttChoiceTitle(code), subtitle = if (code == "auto") stringResource(R.string.stt_auto_sub) else null,
                        onClick = {
                            scope.launch {
                                try {
                                    Api.setFeatures(mapOf("stt_language" to code))
                                    Repo.features.refresh()
                                    error = null
                                } catch (e: Exception) {
                                    error = e
                                }
                            }
                        }) {
                        if (code == current) Icon(Icons.Check, null, tint = Mn.c.ink, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ======================================================================= звук записей
@Composable
fun audioKeepTitle(days: Int): String =
    if (days == 0) stringResource(R.string.audio_keep_after) else plural(R.plurals.keep_days, days)

@Composable
fun AudioKeepScreen() {
    val res by Repo.features.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(Unit) { Repo.features.refresh() }
    val f = res.data
    SettingsPage(stringResource(R.string.audio_keep)) {
        error?.let { StaleNote(it) }
        Group(footer = stringResource(R.string.audio_keep_footer), inset = 22.dp) {
            (f?.audioKeepChoices ?: listOf(0, 1, 3, 7, 30)).forEach { days ->
                item {
                    ListRow(audioKeepTitle(days),
                        subtitle = if (days == 0) stringResource(R.string.audio_keep_after_sub) else null,
                        onClick = {
                            scope.launch {
                                try {
                                    Api.setFeatures(mapOf("audio_keep_days" to days))
                                    Repo.features.refresh()
                                    error = null
                                } catch (e: Exception) {
                                    error = e
                                }
                            }
                        }) {
                        if (f?.audioKeepDays == days) Icon(Icons.Check, null, tint = Mn.c.ink, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ======================================================================= память
@Composable
fun MemoriesScreen() {
    // 0 — вы просили запомнить (кнопка), 1 — заметил Mnemo, 2 — архив (лишнее, можно вернуть)
    var tab by rememberSaveable { mutableStateOf(0) }
    var state by remember { mutableStateOf<Res<Pair<List<MemoryItem>, Int>>>(Res(loading = true)) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    val c = Mn.c
    fun reload() {
        scope.launch {
            state = try {
                Res(Api.memories(archived = tab == 2))
            } catch (e: Exception) {
                Res(error = e)
            }
        }
    }
    LaunchedEffect(tab) { reload() }
    fun act(m: MemoryItem, block: suspend () -> Unit) {
        state = state.data?.let { (list, n) -> Res(list.filter { it.id != m.id } to n) } ?: state
        scope.launch {
            try {
                block()
            } catch (_: Exception) {
            }
            reload()
        }
    }
    SettingsPage(stringResource(R.string.memory)) {
        val archivedCount = state.data?.second ?: 0
        LazyRowChips(listOf(stringResource(R.string.memory_asked), stringResource(R.string.memory_noticed),
            stringResource(R.string.memory_archive) + if (archivedCount > 0) " $archivedCount" else ""), tab) { tab = it }
        val all = state.data?.first
        val items = all?.filter { m ->
            when (tab) {
                0 -> m.source == "button" || m.source == "agent"  // попросили сами: «запомни, что…»
                1 -> m.source != "button" && m.source != "agent"
                else -> true
            }
        }
        when {
            items == null && state.error != null -> ErrorBlock(state.error, { reload() })
            items == null -> LoadingBlock()
            items.isEmpty() -> EmptyBlock(Icons.Bookmark, stringResource(when (tab) {
                0 -> R.string.memory_asked_empty
                1 -> R.string.memory_noticed_empty
                else -> R.string.memory_archive_empty
            }), "")
            else -> Group(inset = 56.dp) {
                items.forEach { m ->
                    item {
                        ListRow(m.text, subtitle = listOfNotNull(
                            fmt.dayShortTitle(fmt.dayOf(m.createdAt)), m.conversationTitle,
                        ).joinToString(", "), icon = if (m.source == "button") Icons.Tap else Icons.Bookmark) {
                            if (tab == 2) {
                                Txt(stringResource(R.string.trash_restore), T.smallStrong, c.lens,
                                    Modifier.quietTap { act(m) { Api.archiveMemory(m.id, false) } }.padding(8.dp))
                            } else {
                                IconCircle(Icons.Close, stringResource(R.string.memory_to_archive),
                                    { act(m) { Api.archiveMemory(m.id, true) } }, size = 32.dp,
                                    background = c.raised, tint = c.graphite)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Ряд переключателей-чипов (вкладки внутри страницы настроек). */
@Composable
fun LazyRowChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l -> Chip(l, i == selected, { onSelect(i) }) }
    }
}
