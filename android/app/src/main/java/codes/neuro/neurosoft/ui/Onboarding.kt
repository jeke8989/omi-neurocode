package codes.neuro.neurosoft.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.Permissions
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.ble.ConnState
import kotlinx.coroutines.delay

private enum class Step { WELCOME, PERMISSIONS, PAIRING, DONE }

/** Первый запуск без настроек: разрешения → кулон находится и подключается сам → запись идёт. */
@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val first = !Core.prefs.onboarded
    var step by rememberSaveable {
        mutableStateOf(when {
            first -> Step.WELCOME
            !Permissions.bluetoothGranted(context) -> Step.PERMISSIONS
            else -> Step.PAIRING
        })
    }
    fun finish() {
        Core.prefs.onboarded = true
        if (Nav.stack.size > 1) Nav.pop() else Nav.reset(Route.Home)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(null, if (first) null else ({ Nav.pop() })) {
            if (step == Step.PAIRING && first) {
                Txt(stringResource(R.string.later), T.smallStrong, Mn.c.graphite,
                    Modifier.padding(horizontal = 8.dp).quietTap { finish() })
            }
        }
        AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step",
            modifier = Modifier.weight(1f)) { s ->
            when (s) {
                Step.WELCOME -> Welcome {
                    step = if (Permissions.bluetoothGranted(context)) Step.PAIRING else Step.PERMISSIONS
                }
                Step.PERMISSIONS -> PermissionsStep { step = Step.PAIRING }
                Step.PAIRING -> Pairing { step = Step.DONE }
                Step.DONE -> Done { finish() }
            }
        }
    }
}

@Composable
private fun StepLayout(
    visual: @Composable () -> Unit,
    title: String,
    text: String,
    bottom: @Composable ColumnScope.() -> Unit,
    middle: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            visual()
            Spacer(Modifier.height(16.dp))
            Txt(title, T.display, align = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Txt(text, T.body, Mn.c.graphite, align = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            middle()
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            content = bottom)
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val c = Mn.c
    StepLayout(
        visual = { Lens(LensState.RECORDING, 168.dp) },
        title = stringResource(R.string.app_name),
        text = stringResource(R.string.welcome_tagline),
        middle = {
            Card {
                listOf(
                    Icons.Wave to R.string.welcome_1,
                    Icons.Tap to R.string.welcome_2,
                    Icons.Book to R.string.welcome_3,
                ).forEachIndexed { i, (icon, text) ->
                    Row(Modifier.padding(top = if (i == 0) 0.dp else 14.dp), verticalAlignment = Alignment.Top) {
                        Icon(icon, null, tint = c.ink, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Txt(stringResource(text), T.body)
                    }
                }
            }
        },
        bottom = {
            PrimaryButton(stringResource(R.string.start), onNext, Modifier.fillMaxWidth())
            Txt(stringResource(R.string.welcome_privacy), T.caption, c.graphite, Modifier.fillMaxWidth(),
                align = TextAlign.Center)
        },
    )
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.bluetoothGranted(context)) onNext() else denied = true
    }
    StepLayout(
        visual = { Icon(Icons.Bluetooth, null, tint = Mn.c.ink, modifier = Modifier.size(72.dp)) },
        title = stringResource(R.string.perm_title),
        text = stringResource(R.string.perm_text),
        middle = {
            if (denied) Txt(stringResource(R.string.perm_denied), T.small, Mn.c.red, align = TextAlign.Center)
        },
        bottom = {
            PrimaryButton(stringResource(R.string.allow), { launcher.launch(Permissions.required()) }, Modifier.fillMaxWidth())
            if (denied) {
                SecondaryButton(stringResource(R.string.open_app_settings), {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }, Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
private fun Pairing(onConnected: () -> Unit) {
    val context = LocalContext.current
    val found by Core.found.collectAsState()
    val scanning by Core.scanning.collectAsState()
    val device by Core.device.collectAsState()
    val repairing = remember { device.address != null }
    var chosen by remember { mutableStateOf<String?>(null) }
    var btOff by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val c = Mn.c

    LaunchedEffect(attempt) {
        btOff = !Core.startScan()
    }
    // Сам выбираем кулон с самым сильным сигналом: обычно он один и лежит рядом.
    LaunchedEffect(found.isNotEmpty(), repairing) {
        if (found.isEmpty() || repairing || chosen != null) return@LaunchedEffect
        delay(2_500)
        val best = Core.found.value.maxByOrNull { it.rssi } ?: return@LaunchedEffect
        chosen = best.address
        Core.selectDevice(context, best)
    }
    LaunchedEffect(device.state, chosen) {
        if (chosen != null && device.address == chosen && device.state == ConnState.CONNECTED) {
            delay(600)
            onConnected()
        }
    }
    DisposableEffect(Unit) { onDispose { Core.stopScan() } }

    val title: String
    val text: String
    when {
        btOff -> {
            title = stringResource(R.string.state_bluetooth_off)
            text = stringResource(R.string.pair_bt_off)
        }
        chosen != null -> {
            title = stringResource(R.string.pair_connecting, device.name ?: stringResource(R.string.pendant))
            text = stringResource(R.string.pair_connecting_text)
        }
        scanning -> {
            title = stringResource(R.string.pair_searching)
            text = stringResource(R.string.pair_searching_text)
        }
        found.isEmpty() -> {
            title = stringResource(R.string.pair_not_found)
            text = stringResource(R.string.pair_not_found_text)
        }
        else -> {
            title = stringResource(R.string.pair_choose)
            text = stringResource(R.string.pair_choose_text)
        }
    }

    StepLayout(
        visual = {
            if (chosen != null) PendantRender(LensState.LISTENING, 180.dp)
            else Lens(if (scanning) LensState.LISTENING else LensState.OFF, 168.dp)
        },
        title = title,
        text = text,
        middle = {
            if (found.isNotEmpty() && (chosen == null || repairing)) {
                Group {
                    found.sortedByDescending { it.rssi }.forEach { d ->
                        item {
                            ListRow(d.name, icon = Icons.Bluetooth, subtitle = signalText(d.rssi),
                                onClick = {
                                    chosen = d.address
                                    Core.selectDevice(context, d)
                                }) {
                                if (d.address == chosen) Spinner(18.dp)
                            }
                        }
                    }
                }
            }
        },
        bottom = {
            when {
                btOff -> PrimaryButton(stringResource(R.string.open_bt_settings), {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }, Modifier.fillMaxWidth())
                !scanning && chosen == null -> PrimaryButton(stringResource(R.string.search_again), { attempt++ },
                    Modifier.fillMaxWidth())
                chosen != null && device.state != ConnState.CONNECTED -> SecondaryButton(stringResource(R.string.cancel), {
                    chosen = null
                    attempt++
                }, Modifier.fillMaxWidth())
            }
            if (btOff) SecondaryButton(stringResource(R.string.search_again), { attempt++ }, Modifier.fillMaxWidth())
            Txt(stringResource(R.string.pair_hint), T.caption, c.graphite, Modifier.fillMaxWidth(), align = TextAlign.Center)
        },
    )
}

@Composable
private fun signalText(rssi: Int): String = stringResource(when {
    rssi > -60 -> R.string.signal_strong
    rssi > -78 -> R.string.signal_ok
    else -> R.string.signal_weak
})

@Composable
private fun Done(onFinish: () -> Unit) {
    val context = LocalContext.current
    val device by Core.device.collectAsState()
    val c = Mn.c
    LaunchedEffect(Unit) { Core.setPaused(false) }
    StepLayout(
        visual = { PendantRender(LensState.RECORDING, 200.dp) },
        title = stringResource(R.string.done_title),
        text = stringResource(R.string.done_text, device.name ?: stringResource(R.string.pendant)),
        middle = {
            Card {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Tap, null, tint = c.ink, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Txt(stringResource(R.string.howto_one), T.bodyStrong)
                        Txt(stringResource(R.string.howto_one_sub), T.small, c.graphite)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Bookmark, null, tint = c.ink, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Txt(stringResource(R.string.howto_two), T.bodyStrong)
                        Txt(stringResource(R.string.howto_two_sub), T.small, c.graphite)
                    }
                }
            }
        },
        bottom = {
            PrimaryButton(stringResource(R.string.open_app), onFinish, Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.battery_allow), {
                try {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) {
                }
            }, Modifier.fillMaxWidth())
        },
    )
}
