@file:OptIn(ExperimentalMaterial3Api::class)

package codes.neuro.neurosoft.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.BuildConfig
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.ble.ConnState
import codes.neuro.neurosoft.ble.OfflineSync
import codes.neuro.neurosoft.ble.OmiProtocol
import kotlin.math.roundToInt

private fun mask(address: String): String =
    if (address.length > 10) address.take(5) + "•••" + address.takeLast(4) else address

@Composable
fun DeviceScreen() {
    val context = LocalContext.current
    val device by Core.device.collectAsState()
    val running by Core.serviceRunning.collectAsState()
    val paused by Core.paused.collectAsState()
    val upload by Core.upload.collectAsState()
    val outages by Core.outages.collectAsState()
    val days by Repo.days.state.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            Repo.days.refresh()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val lens = rememberLensState()
    val fmt = rememberFmt()
    val c = Mn.c
    val clipboard = LocalClipboardManager.current
    var diagnostics by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var showId by remember { mutableStateOf(false) }
    val connected = device.state == ConnState.CONNECTED
    val now = System.currentTimeMillis()

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = c.paper,
            title = { Text(stringResource(R.string.forget_q), style = T.section, color = c.ink) },
            text = { Text(stringResource(R.string.forget_text), style = T.body, color = c.graphite) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    Core.forgetDevice(context)
                    Nav.pop()
                }) { Text(stringResource(R.string.forget), color = c.red, style = T.bodyStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.cancel), color = c.ink, style = T.bodyStrong)
                }
            },
        )
    }

    SettingsPage(stringResource(R.string.device_settings), big = false) {
        // ---------------------------------------------------------- шапка: имя, статус, кулон
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(device.name ?: stringResource(R.string.pendant), T.display, align = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(c.paper).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(lensColor(lens)))
                Spacer(Modifier.width(8.dp))
                Txt(deviceStatusText(), T.smallStrong, maxLines = 1)
            }
            PendantRender(lens, 230.dp, Modifier.padding(vertical = 4.dp))
        }

        // ---------------------------------------------------------- плитки
        TileRow() {
            StatTile(stringResource(R.string.battery), device.battery?.let { "$it%" } ?: "—", Modifier.weight(1f),
                note = if (device.charging == true) stringResource(R.string.charging) else null,
                valueColor = if ((device.battery ?: 100) < 15) c.red else c.ink)
            // полезное время: речь в разговорах за сегодня (по расшифровкам на сервере), а не работа микрофона
            val today = days.data?.firstOrNull { it.day == todayIso() }
            StatTile(stringResource(R.string.talk_today), today?.let { fmt.duration(it.speechSeconds.toLong()) } ?: "—",
                Modifier.weight(1f), note = today?.let { plural(R.plurals.conversations, it.conversations) })
            StatTile(stringResource(R.string.on_phone), fmt.duration((upload.pendingFiles - 1).coerceAtLeast(0).toLong()),
                Modifier.weight(1f))
        }

        // ---------------------------------------------------------- прямо сейчас
        SectionTitle(stringResource(R.string.right_now))
        Card(Modifier.padding(horizontal = 16.dp)) {
            val (title, text) = when {
                !running -> stringResource(R.string.now_off) to stringResource(R.string.now_off_text)
                !connected -> stringResource(R.string.now_no_pendant) to
                    (if (device.disconnectedSince > 0) stringResource(R.string.now_since, fmt.timeMs(device.disconnectedSince))
                    else stringResource(R.string.live_searching_text))
                paused -> stringResource(R.string.live_paused) to stringResource(R.string.live_paused_text)
                upload.resting -> stringResource(R.string.now_batched) to
                    (if (upload.nextBurstAt > 0) stringResource(R.string.now_batched_next, fmt.timeMs(upload.nextBurstAt))
                    else stringResource(R.string.now_batched_text))
                !upload.connected -> stringResource(R.string.now_saving) to stringResource(R.string.now_saving_text)
                upload.pendingFiles > 3 -> stringResource(R.string.now_catching_up) to
                    (if (upload.oldestAt > 0) stringResource(R.string.now_range, fmt.timeMs(upload.oldestAt), fmt.timeMs(now))
                    else stringResource(R.string.now_catching_up_text))
                else -> stringResource(R.string.now_live) to stringResource(R.string.now_live_text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lens(lens, 40.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title, T.bodyStrong)
                    Txt(text, T.caption, c.graphite)
                }
            }
        }

        // ---------------------------------------------------------- недавно
        Group(stringResource(R.string.recently)) {
            if (outages.isEmpty()) {
                item { ListRow(stringResource(R.string.no_outages), icon = Icons.Bluetooth, iconTint = c.graphite) }
            } else {
                outages.takeLast(6).reversed().forEach { o ->
                    item {
                        ListRow(stringResource(R.string.disconnected_for, fmt.duration((o.to - o.from) / 1000)),
                            icon = Icons.BluetoothOff, iconTint = c.amber, value = fmt.timeMs(o.from))
                    }
                }
            }
        }

        // ---------------------------------------------------------- записи без телефона
        Group(stringResource(R.string.offline_title)) {
            val o = device.offline
            item {
                ListRow(
                    stringResource(when (o.phase) {
                        OfflineSync.Phase.SYNCING -> R.string.offline_syncing
                        OfflineSync.Phase.CHECKING -> R.string.offline_checking
                        OfflineSync.Phase.UNSUPPORTED -> R.string.offline_unsupported
                        else -> if (o.lastSyncAt > 0) R.string.offline_synced else R.string.offline_never
                    }),
                    icon = Icons.Cloud, iconTint = if (o.phase == OfflineSync.Phase.UNSUPPORTED) c.amber else c.graphite,
                    subtitle = when {
                        o.phase == OfflineSync.Phase.SYNCING -> stringResource(R.string.offline_left,
                            fmt.duration(o.pending / 10))
                        o.phase == OfflineSync.Phase.UNSUPPORTED -> stringResource(R.string.offline_update_fw)
                        o.recoveredSec > 0 -> stringResource(R.string.offline_recovered, fmt.duration(o.recoveredSec))
                        else -> null
                    },
                    value = if (o.lastSyncAt > 0 && o.phase == OfflineSync.Phase.IDLE) fmt.timeMs(o.lastSyncAt) else null,
                )
            }
        }

        // ---------------------------------------------------------- управление
        Group(stringResource(R.string.control)) {
            if (!running) {
                item { ListRow(stringResource(R.string.start_recording), icon = Icons.Play,
                    onClick = { Core.startCapture(context) }) }
            } else {
                item {
                    ListRow(stringResource(if (paused) R.string.resume_recording else R.string.pause_recording),
                        icon = if (paused) Icons.Play else Icons.Pause,
                        subtitle = if (paused) null else stringResource(R.string.pause_sub),
                        onClick = { Core.setPaused(!paused) })
                }
            }
            if ("haptic" in device.caps) {
                item { ListRow(stringResource(R.string.find_pendant), icon = Icons.Target,
                    subtitle = stringResource(if (connected) R.string.find_sub else R.string.state_disconnected),
                    onClick = if (connected) ({ Core.findDevice() }) else null) }
            }
        }

        // ---------------------------------------------------------- настройка
        Group(stringResource(R.string.setup)) {
            item { ListRow(stringResource(R.string.button_actions), icon = Icons.Hand, chevron = true,
                value = stringResource(if (Core.prefs.buttonActions) R.string.on else R.string.off),
                onClick = { Nav.push(Route.Voice) }) }
            if ("led" in device.caps) {
                item { ListRow(stringResource(R.string.led_brightness), icon = Icons.Bulb, chevron = true,
                    value = device.ledDim?.let { "$it%" }, onClick = { sheet = "led" }) }
            }
            if ("gain" in device.caps) {
                item { ListRow(stringResource(R.string.mic_gain), icon = Icons.Levels, chevron = true,
                    value = device.micGain?.let { stringResource(R.string.gain_level, it, OmiProtocol.MIC_GAIN_MAX) },
                    onClick = { sheet = "gain" }) }
            }
            item { ListRow(stringResource(R.string.speech_language), icon = Icons.Translate, chevron = true,
                onClick = { Nav.push(Route.SttLanguage) }) }
            item { ListRow(stringResource(R.string.how_to_use), icon = Icons.Help, chevron = true,
                onClick = { sheet = "help" }) }
        }

        // ---------------------------------------------------------- информация
        Group(stringResource(R.string.device_info)) {
            item { ListRow(stringResource(R.string.device_name), icon = Icons.Chip, value = device.name ?: "—") }
            item { ListRow(stringResource(R.string.firmware), icon = Icons.Code, value = device.firmware ?: "—") }
            device.address?.let { a ->
                item { ListRow(stringResource(R.string.device_id), icon = Icons.Tag, value = if (showId) a else mask(a),
                    onClick = { showId = !showId }) }
            }
            device.hardware?.let { item { ListRow(stringResource(R.string.hardware_revision), icon = Icons.Layers, value = it) } }
            device.model?.let { item { ListRow(stringResource(R.string.model_number), icon = Icons.Tag, value = it) } }
            device.manufacturer?.let { item { ListRow(stringResource(R.string.manufacturer), icon = Icons.Factory, value = it) } }
            item { ListRow(stringResource(R.string.codec), icon = Icons.Wave, value = OmiProtocol.codecName(device.codec)) }
        }

        // ---------------------------------------------------------- диагностика
        Group(stringResource(R.string.diagnostics), inset = 22.dp, trailing = {
            Icon(if (diagnostics) Icons.ChevronDown else Icons.Chevron, null, tint = c.graphite,
                modifier = Modifier.size(20.dp).quietTap { diagnostics = !diagnostics })
        }) {
            if (diagnostics) {
                item { ListRow("MTU", value = "${device.mtu}") }
                item { ListRow(stringResource(R.string.frames_received), value = "${device.frames}") }
                item { ListRow(stringResource(R.string.packets_lost), value = "${device.lostPackets}") }
                item { ListRow(stringResource(R.string.last_audio), value = fmt.ago(device.lastAudioAt)) }
                item { ListRow(stringResource(R.string.avg_frame),
                    value = stringResource(R.string.size_b, device.avgFrameBytes.toLong())) }
                item { ListRow(stringResource(R.string.mic_on_today), value = fmt.duration(device.heardTodayMs / 1000)) }
                item { ListRow(stringResource(R.string.server), value = upload.error?.let { serverErrorText(it) }
                    ?: stringResource(if (upload.connected) R.string.server_online else R.string.server_offline)) }
                item { ListRow(stringResource(R.string.last_ack), value = fmt.ago(upload.lastAckAt)) }
                item {
                    val report = buildString {
                        append("Mnemo ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}\n")
                        append("pendant=${device.name} fw=${device.firmware} hw=${device.hardware} state=${device.state} ")
                        append("battery=${device.battery} codec=${device.codec} mtu=${device.mtu}\n")
                        append("frames=${device.frames} lost=${device.lostPackets} ble=${"%.1f".format(device.bleKbps)}kbps\n")
                        append("server connected=${upload.connected} error=${upload.error} queue=${upload.pendingFiles}/${upload.pendingBytes}B ")
                        append("net=${"%.1f".format(device.netKbps)}kbps\n")
                        append("outages=${outages.takeLast(5).joinToString { "${(it.to - it.from) / 1000}s" }}")
                    }
                    ListRow(stringResource(if (copied) R.string.copied else R.string.copy_report), icon = Icons.Copy,
                        accent = true, onClick = {
                            clipboard.setText(AnnotatedString(report))
                            copied = true
                        })
                }
            } else {
                item { ListRow(stringResource(R.string.diagnostics_show), icon = Icons.Pulse, chevron = true,
                    onClick = { diagnostics = true }) }
            }
        }

        Group {
            item { ListRow(stringResource(R.string.find_other_pendant), icon = Icons.Search,
                onClick = { Nav.push(Route.Onboarding) }) }
            item { ListRow(stringResource(R.string.forget_pendant), icon = Icons.Unlink, danger = true,
                onClick = { confirmForget = true }) }
        }

        // ---------------------------------------------------------- скорость
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Bluetooth, stringResource(R.string.cd_bluetooth_speed), tint = c.graphite, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Txt(fmt.kbps(device.bleKbps), T.caption, c.graphite)
            Spacer(Modifier.width(24.dp))
            Icon(Icons.Signal, stringResource(R.string.cd_network_speed), tint = c.graphite, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Txt(fmt.kbps(device.netKbps), T.caption, c.graphite)
        }
    }

    when (sheet) {
        "led" -> SliderSheet(stringResource(R.string.led_brightness), stringResource(R.string.led_sub),
            (device.ledDim ?: 50).toFloat(), 0f..100f, 9, { "${it.roundToInt()}%" },
            onDone = { Core.setLedDim(it.roundToInt()) }, onDismiss = { sheet = null })
        "gain" -> SliderSheet(stringResource(R.string.mic_gain), stringResource(R.string.mic_gain_sub),
            (device.micGain ?: 4).toFloat(), 0f..OmiProtocol.MIC_GAIN_MAX.toFloat(), OmiProtocol.MIC_GAIN_MAX - 1,
            { gainLabel(it.roundToInt()) }, onDone = { Core.setMicGain(it.roundToInt()) }, onDismiss = { sheet = null })
        "help" -> HowToSheet { sheet = null }
    }
}

@Composable
private fun gainLabel(level: Int): String = when {
    level <= 2 -> stringResource(R.string.gain_quiet, level)
    level >= 6 -> stringResource(R.string.gain_loud, level)
    else -> stringResource(R.string.gain_normal, level)
}

@Composable
fun serverErrorText(code: String): String = when {
    code == "auth" -> stringResource(R.string.err_auth)
    code == "not_found" -> stringResource(R.string.err_not_found)
    code == "bad_url" -> stringResource(R.string.err_not_configured)
    code.startsWith("server:") -> stringResource(R.string.err_server, code.substringAfter(':'))
    code.startsWith("closed:") -> stringResource(R.string.err_network)
    else -> stringResource(R.string.err_network)
}

@Composable
private fun SliderSheet(
    title: String, subtitle: String, initial: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    label: @Composable (Float) -> String, onDone: (Float) -> Unit, onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var value by remember { mutableStateOf(initial) }
    val c = Mn.c
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.fog,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.line)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Txt(title, T.title, modifier = Modifier.padding(top = 12.dp))
            Txt(subtitle, T.small, c.graphite, Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(24.dp))
            Txt(label(value), T.number, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Slider(
                value = value, onValueChange = { value = it }, valueRange = range, steps = steps,
                onValueChangeFinished = { onDone(value) },
                colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.line,
                    activeTickColor = c.onInk, inactiveTickColor = c.faint),
            )
        }
    }
}

@Composable
private fun HowToSheet(onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = Mn.c
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.fog,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.line)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Txt(stringResource(R.string.how_to_use), T.title, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
            Group {
                item { ListRow(stringResource(R.string.howto_one), icon = Icons.Tap, subtitle = stringResource(R.string.howto_one_sub)) }
                item { ListRow(stringResource(R.string.howto_two), icon = Icons.Bookmark, subtitle = stringResource(R.string.howto_two_sub)) }
                item { ListRow(stringResource(R.string.howto_long), icon = Icons.Power, subtitle = stringResource(R.string.howto_long_sub)) }
            }
            Group(stringResource(R.string.howto_light)) {
                item { ListRow(stringResource(R.string.howto_light_blue), icon = Icons.Dot, iconTint = c.lens) }
                item { ListRow(stringResource(R.string.howto_light_amber), icon = Icons.Dot, iconTint = c.amber) }
                item { ListRow(stringResource(R.string.howto_light_gray), icon = Icons.Dot, iconTint = c.faint) }
            }
        }
    }
}
