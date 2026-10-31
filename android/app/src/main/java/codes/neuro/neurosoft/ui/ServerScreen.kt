package codes.neuro.neurosoft.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import kotlinx.coroutines.launch

/** Команда установки сервера (репозиторий публичный). */
const val INSTALL_COMMAND =
    "curl -fsSL https://raw.githubusercontent.com/jeke8989/omi-neurocode/main/server/install.sh | sudo bash"
const val PAIR_COMMAND = "docker compose exec gateway python -m neuro.pairing"

/**
 * Подключение к своему серверу: адрес и одноразовый код из 8 цифр (его печатает установка сервера).
 * Ключ доступа приходит в ответ и хранится только в телефоне.
 */
@Composable
fun ServerScreen() {
    val upload by Core.upload.collectAsState()
    val prefs = Core.prefs
    var url by remember { mutableStateOf(prefs.serverUrl.ifBlank { prefs.defaultServerUrl }) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val c = Mn.c

    fun normalized(u: String): String {
        val t = u.trim().trimEnd('/')
        return if (t.isEmpty() || t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
    }

    val pairing by Core.pairing.collectAsState()
    LaunchedEffect(Unit) { if (!prefs.serverConfigured || upload.error == "auth") Core.autoConnect(manual = true) }

    SettingsPage(stringResource(R.string.server_connect_title)) {
        Txt(stringResource(R.string.server_connect_intro), T.small, c.graphite,
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        // подключение без кода: первый телефон — сам, следующий — с разрешения на подключённом
        val auto = when (pairing.phase) {
            "connecting" -> stringResource(R.string.pair_auto_connecting)
            "waiting" -> stringResource(R.string.pair_waiting)
            "rejected" -> stringResource(R.string.pair_rejected)
            "offline" -> stringResource(R.string.pair_offline)
            else -> null
        }
        if (auto != null && !(prefs.serverConfigured && upload.connected)) {
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pairing.phase == "connecting" || pairing.phase == "waiting") {
                        Spinner(18.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Txt(auto, T.bodyStrong, if (pairing.phase == "rejected") c.red else c.ink)
                }
                if (pairing.phase == "waiting") {
                    Txt(stringResource(R.string.pair_waiting_text), T.caption, c.graphite, Modifier.padding(top = 6.dp))
                }
            }
            Txt(stringResource(R.string.pair_or_code), T.caption, c.graphite,
                Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp))
        }
        if (prefs.serverConfigured && upload.connected) {
            Txt(stringResource(R.string.server_connected_to, prefs.serverUrl), T.smallStrong, c.lens,
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        } else if (upload.error == "auth") {
            Txt(stringResource(R.string.server_reconnect_text), T.smallStrong, c.amber,
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        Group {
            item {
                FieldRow(stringResource(R.string.pair_code), code, { v ->
                    code = v.filter { it.isDigit() }.take(8)
                    error = null
                }, placeholder = "12345678", keyboard = KeyboardType.Number)
            }
            item {
                FieldRow(stringResource(R.string.server_address), url, { url = it; error = null },
                    placeholder = prefs.defaultServerUrl.ifBlank { "http://1.2.3.4:8090" }, keyboard = KeyboardType.Uri)
            }
        }
        error?.let { Txt(errorText(it), T.small, c.red, Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) }
        PrimaryButton(stringResource(R.string.server_connect), {
            val address = normalized(url)
            busy = true
            scope.launch {
                try {
                    val token = Api.pair(address, code)
                    Core.connectServer(address, token)
                    Repo.days.refresh()
                    Repo.people.refresh()
                    Nav.pop()
                } catch (e: Exception) {
                    error = e
                }
                busy = false
            }
        }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            enabled = code.length == 8 && normalized(url).length > 10 && !busy)

        Group(stringResource(R.string.pair_where), footer = if (copied) stringResource(R.string.pair_copied) else null) {
            item {
                ListRow(stringResource(R.string.pair_step_install), icon = Icons.Code, subtitle = INSTALL_COMMAND,
                    trailing = { IconCircle(Icons.Copy, stringResource(R.string.copy), {
                        clipboard.setText(AnnotatedString(INSTALL_COMMAND))
                        copied = true
                    }, size = 38.dp, background = Mn.c.raised) })
            }
            item {
                ListRow(stringResource(R.string.pair_step_new), icon = Icons.Refresh, subtitle = PAIR_COMMAND,
                    trailing = { IconCircle(Icons.Copy, stringResource(R.string.copy), {
                        clipboard.setText(AnnotatedString(PAIR_COMMAND))
                        copied = true
                    }, size = 38.dp, background = Mn.c.raised) })
            }
        }
    }
}
