package codes.neuro.neurosoft.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    LaunchedEffect(Unit) { Repo.features.refresh() }
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
            item { ListRow(stringResource(R.string.voice_and_button), icon = Icons.Speaker, chevron = true,
                onClick = { Nav.push(Route.Voice) }) }
            item { ListRow(stringResource(R.string.ai_models), icon = Icons.Chip, chevron = true,
                subtitle = stringResource(R.string.ai_models_sub), onClick = { Nav.push(Route.Models) }) }
            item { ListRow(stringResource(R.string.integrations), icon = Icons.Puzzle, chevron = true,
                subtitle = stringResource(R.string.integrations_sub), onClick = { Nav.push(Route.Extensions) }) }
        }

        Group(stringResource(R.string.tab_people)) {
            item { ListRow(stringResource(R.string.voices_title), icon = Icons.People, chevron = true,
                subtitle = stringResource(R.string.voices_settings_sub), onClick = { Nav.push(Route.VoicesSettings) }) }
        }

        Group(stringResource(R.string.tab_diary)) {
            item { ListRow(stringResource(R.string.diary_and_photos), icon = Icons.Book, chevron = true,
                value = features.data?.let { stringResource(if (it.diary) R.string.on else R.string.off) },
                onClick = { Nav.push(Route.DiarySettings) }) }
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
            item { ListRow(stringResource(R.string.clear_chat), icon = Icons.Trash, danger = true,
                onClick = { confirmClear = true }) }
        }

        Group(stringResource(R.string.background), footer = stringResource(R.string.background_footer)) {
            item {
                ListRow(
                    stringResource(if (ignoringBattery) R.string.battery_unrestricted else R.string.battery_allow),
                    icon = Icons.Power, chevron = !ignoringBattery,
                    subtitle = if (ignoringBattery) null else stringResource(R.string.battery_allow_sub),
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
                }, icon = Icons.Battery, subtitle = stringResource(R.string.battery_saver_sub))
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
            item { ListRow(stringResource(R.string.version), icon = Icons.Info, value = BuildConfig.VERSION_NAME) }
        }
        Txt(stringResource(R.string.about_footer), T.caption, c.faint,
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp), align = TextAlign.Center)
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

    SettingsPage(stringResource(R.string.voice_and_button)) {
        Group(stringResource(R.string.pendant_button), footer = stringResource(R.string.button_footer)) {
            item { ToggleRow(stringResource(R.string.button_actions), actions, {
                actions = it
                prefs.buttonActions = it
            }, icon = Icons.Hand, subtitle = stringResource(R.string.button_actions_sub)) }
        }
        Group(stringResource(R.string.voice_reply), footer = stringResource(R.string.voice_reply_footer)) {
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
                subtitle = if (voice.ttsReady) null else stringResource(R.string.tts_missing),
                onClick = { Core.voice.testVoice() }) }
            item { ToggleRow(stringResource(R.string.earcons), earcons, {
                earcons = it
                prefs.earcons = it
            }, icon = Icons.Wave, subtitle = stringResource(R.string.earcons_sub)) }
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
        Group(footer = stringResource(R.string.diary_settings_footer)) {
            item { ToggleRow(stringResource(R.string.day_feed), f?.diary ?: true, { set(mapOf("diary" to it)) },
                icon = Icons.Book, subtitle = stringResource(R.string.day_feed_sub), enabled = f != null) }
            item {
                ToggleRow(stringResource(R.string.location_while_recording), location, { on ->
                    if (on && !Core.locationPermissionGranted()) locationPerm.launch(Permissions.location())
                    else {
                        location = on
                        Core.prefs.locationEnabled = on
                        Core.applyLocationSetting()
                    }
                }, icon = Icons.Pin, subtitle = stringResource(R.string.location_sub))
            }
            item {
                ToggleRow(stringResource(R.string.photos_from_phone), photoAuto, { on ->
                    if (on && !Permissions.granted(context, Permissions.photos())) photoPerm.launch(Permissions.photos())
                    else {
                        photoAuto = on
                        Core.prefs.photoAutoImport = on
                        if (on) Core.syncPhotosNow()
                    }
                }, icon = Icons.Photo, subtitle = stringResource(R.string.photos_from_phone_sub))
            }
            item { ToggleRow(stringResource(R.string.photo_ai), f?.photoAi ?: true, { set(mapOf("photo_ai" to it)) },
                icon = Icons.Sparkle, subtitle = stringResource(R.string.photo_ai_sub), enabled = f != null) }
            item { ToggleRow(stringResource(R.string.photo_styling), f?.photoStyle ?: true,
                { set(mapOf("photo_style" to it)) }, icon = Icons.Palette,
                subtitle = stringResource(R.string.photo_styling_sub), enabled = f != null) }
        }
        if (f != null && f.photoStyle) {
            SectionTitle(stringResource(R.string.photo_style))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(f.styles, key = { it.first }) { (key, title) ->
                    Chip(styleTitle(key, title), key == f.style, { set(mapOf("style" to key)) })
                }
            }
            SectionTitle(stringResource(R.string.photos_per_day))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((1..6).toList()) { n -> Chip("$n", n == f.photosPerDay, { set(mapOf("photos_per_day" to n)) }) }
            }
            Txt(stringResource(R.string.photos_per_day_footer), T.caption, Mn.c.graphite,
                Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp))
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

// ======================================================================= язык
@Composable
fun LanguageScreen() {
    val context = LocalContext.current
    val current = Core.prefs.language
    val options = listOf("" to stringResource(R.string.lang_system)) + Lang.supported
    SettingsPage(stringResource(R.string.app_language)) {
        Group(footer = stringResource(R.string.language_footer), inset = 22.dp) {
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
        Group(footer = stringResource(R.string.speech_language_footer), inset = 22.dp) {
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

// ======================================================================= память
@Composable
fun MemoriesScreen() {
    var state by remember { mutableStateOf<Res<List<MemoryItem>>>(Res(loading = true)) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    fun reload() {
        scope.launch {
            state = try {
                Res(Api.memories())
            } catch (e: Exception) {
                Res(error = e)
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    SettingsPage(stringResource(R.string.memory)) {
        val items = state.data
        when {
            items == null && state.error != null -> ErrorBlock(state.error, { reload() })
            items == null -> LoadingBlock()
            items.isEmpty() -> EmptyBlock(Icons.Bookmark, stringResource(R.string.memory_empty_title),
                stringResource(R.string.memory_empty_text))
            else -> Group(footer = stringResource(R.string.memory_footer)) {
                items.forEach { m ->
                    item {
                        ListRow(m.text, subtitle = listOfNotNull(
                            fmt.dayShortTitle(fmt.dayOf(m.createdAt)) + ", " + fmt.time(m.createdAt),
                            m.conversationTitle,
                        ).joinToString(", "), icon = if (m.source == "button") Icons.Tap else Icons.Bookmark)
                    }
                }
            }
        }
    }
}
