package codes.neuro.neurosoft.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import codes.neuro.neurosoft.AppLock
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.R
import kotlinx.coroutines.delay

private const val PIN_LEN = 4

/** Экран входа: ПИН-код и (если включено) отпечаток или лицо. Запись с кулона при этом идёт как обычно. */
@Composable
fun LockScreen() {
    val activity = LocalContext.current as? FragmentActivity
    val bio = Core.prefs.lockBiometric && activity != null && AppLock.biometricAvailable(activity)
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    val wait = AppLock.waitUntil.value
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(wait) {
        while (SystemClock.elapsedRealtime() < wait) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
        now = SystemClock.elapsedRealtime()
    }
    LaunchedEffect(Unit) { if (bio && activity != null) AppLock.biometric(activity) }
    LaunchedEffect(error) {
        if (error) {
            delay(700)
            error = false
            pin = ""
        }
    }
    BackHandler { activity?.moveTaskToBack(true) }
    val blocked = now < wait
    val bioAction: () -> Unit = { activity?.let { AppLock.biometric(it) } }

    PinLayout(
        title = stringResource(R.string.lock_title),
        note = when {
            blocked -> stringResource(R.string.lock_wait, ((wait - now) / 1000 + 1).toInt())
            error -> stringResource(R.string.lock_wrong)
            else -> null
        },
        filled = pin.length,
        error = error,
        enabled = !blocked && !error,
        onDigit = { d ->
            if (pin.length < PIN_LEN) {
                pin += d
                if (pin.length == PIN_LEN && !AppLock.check(pin)) error = true
            }
        },
        onBackspace = { pin = pin.dropLast(1) },
        extra = if (bio) Icons.Fingerprint to bioAction else null,
        footer = {
            Txt(stringResource(R.string.lock_forgot), T.smallStrong, Mn.c.graphite,
                modifier = Modifier.clip(CircleShape).quietTap { forgot = true }.padding(horizontal = 14.dp, vertical = 10.dp))
        },
    )
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            containerColor = Mn.c.paper,
            title = { Text(stringResource(R.string.lock_forgot), style = T.section, color = Mn.c.ink) },
            text = { Text(stringResource(R.string.lock_forgot_text), style = T.body, color = Mn.c.graphite) },
            confirmButton = {
                TextButton(onClick = { forgot = false }) {
                    Text(stringResource(R.string.ok), color = Mn.c.ink, style = T.bodyStrong)
                }
            },
        )
    }
}

/** Новый ПИН-код: ввести и повторить. Если телефон умеет — сразу предлагаем вход по отпечатку. */
@Composable
fun PinSetupScreen() {
    val context = LocalContext.current
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    LaunchedEffect(mismatch) {
        if (mismatch) {
            delay(900)
            mismatch = false
            pin = ""
            first = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(null, { Nav.pop() })
        PinLayout(
            title = stringResource(if (first == null) R.string.lock_new else R.string.lock_repeat),
            note = if (mismatch) stringResource(R.string.lock_mismatch) else stringResource(R.string.lock_new_note),
            filled = pin.length,
            error = mismatch,
            enabled = !mismatch,
            onDigit = { d ->
                if (pin.length < PIN_LEN) {
                    pin += d
                    if (pin.length == PIN_LEN) {
                        val f = first
                        when {
                            f == null -> {
                                first = pin
                                pin = ""
                            }
                            f == pin -> {
                                AppLock.setPin(pin)
                                if (AppLock.biometricAvailable(context)) Core.prefs.lockBiometric = true
                                Nav.pop()
                            }
                            else -> mismatch = true
                        }
                    }
                }
            },
            onBackspace = { pin = pin.dropLast(1) },
        )
    }
}

@Composable
private fun PinLayout(
    title: String,
    note: String?,
    filled: Int,
    error: Boolean,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    extra: Pair<ImageVector, () -> Unit>? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = Mn.c
    Column(
        Modifier.fillMaxSize().background(c.fog).systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Lens(LensState.IDLE, 64.dp)
        Spacer(Modifier.height(22.dp))
        Txt(title, T.title, align = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.semantics {
            contentDescription = "$filled / $PIN_LEN"
        }) {
            repeat(PIN_LEN) { i ->
                val color by animateColorAsState(
                    when {
                        error -> c.red
                        i < filled -> c.ink
                        else -> c.fog
                    }, label = "dot")
                Box(Modifier.size(14.dp).clip(CircleShape).background(color)
                    .border(1.5.dp, if (error) c.red else c.ink, CircleShape))
            }
        }
        Spacer(Modifier.height(14.dp))
        Txt(note ?: " ", T.caption, if (error) c.red else c.graphite, align = TextAlign.Center, maxLines = 2,
            modifier = Modifier.heightIn(min = 34.dp))
        Spacer(Modifier.weight(0.6f))
        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { d -> PinKey(d.toString(), enabled) { onDigit(d) } }
            }
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            if (extra != null) {
                KeyIcon(extra.first, stringResource(R.string.lock_biometric_title), true, extra.second)
            } else {
                Spacer(Modifier.size(76.dp))
            }
            PinKey("0", enabled) { onDigit('0') }
            KeyIcon(Icons.Backspace, stringResource(R.string.cd_backspace), enabled, onBackspace)
        }
        Spacer(Modifier.height(10.dp))
        footer?.invoke()
        Spacer(Modifier.weight(0.4f))
    }
}

@Composable
private fun PinKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(Mn.c.paper).tap(onClick, enabled),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, T.number, if (enabled) Mn.c.ink else Mn.c.faint)
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).tap(onClick, enabled).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Mn.c.ink else Mn.c.faint, modifier = Modifier.size(28.dp))
    }
}
