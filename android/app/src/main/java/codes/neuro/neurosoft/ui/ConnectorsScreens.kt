package codes.neuro.neurosoft.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.PhoneCalendar
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.Connector
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Сервисы одной кнопкой (Notion, Linear). «Подключить» открывает вход на сайте сервиса; после «Разрешить»
 * сервис возвращает владельца в Mnemo (codes.neuro.mnemo://oauth?code=…) — остальное сервер делает сам.
 */
object Connectors {
    /** Ссылка, с которой сервис вернул в приложение (её кладёт MainActivity). */
    val returned = mutableStateOf<String?>(null)

    /** Меняется после входа и при возвращении в приложение — экраны перечитывают статус. */
    val version = mutableIntStateOf(0)

    /** Ссылка на вход -> браузер. Ошибку (нет связи, сервис отказал) возвращает текстом. */
    suspend fun connect(context: Context, id: String): String? = try {
        val url = Api.connectorStart(id)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            null
        } catch (_: android.content.ActivityNotFoundException) {
            context.getString(R.string.svc_no_browser)
        }
    } catch (e: Exception) {
        e.message
    }
}

/** Возврат из сервиса: обменять код на доступ и показать итог. Живёт в корне приложения. */
@Composable
fun ConnectorReturnEffect() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        snapshotFlow { Connectors.returned.value }.filterNotNull().collect { link ->
            val u = Uri.parse(link)
            val code = u.getQueryParameter("code")
            val state = u.getQueryParameter("state")
            val message = when {
                u.getQueryParameter("error") != null -> context.getString(R.string.svc_denied)
                code != null && state != null -> try {
                    val card = Api.connectorFinish(code, state)
                    context.getString(R.string.svc_connected_toast, card.name)
                } catch (e: Exception) {
                    e.message
                }
                else -> null  // «Открыть Mnemo» со страницы сервера: вход уже закончен там
            }
            Connectors.returned.value = null
            Connectors.version.intValue++
            val top = Nav.stack.lastOrNull()
            if (top != Route.Extensions && top !is Route.Connector) Nav.push(Route.Extensions)
            message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
}

/** Блок «Сервисы» на экране интеграций. */
@Composable
fun ConnectorsGroup(onError: (String?) -> Unit) {
    var items by remember { mutableStateOf<List<Connector>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val version = Connectors.version.intValue
    LaunchedEffect(version) {
        items = try {
            Api.connectors()
        } catch (_: Exception) {
            items
        }
        busy = null
    }
    val list = items.orEmpty()
    val connected = stringResource(R.string.connected)
    fun connect(id: String) {
        if (busy != null) return
        busy = id
        scope.launch {
            onError(Connectors.connect(context, id))
            busy = null
        }
    }
    // календарь телефона — первым: одно разрешение, без входа в аккаунты
    val calendarOn = Core.prefs.calendarOn && PhoneCalendar.granted(context)
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PhoneCalendar.granted(context)) {
            Core.prefs.calendarOn = true
            scope.launch {
                PhoneCalendar.sync(context, force = true)
                Connectors.version.intValue++
                Nav.push(Route.Calendar)
            }
        } else {
            onError(context.getString(R.string.calendar_denied))
        }
    }
    Group(stringResource(R.string.svc_title), inset = 22.dp) {
        item {
            ListRow("📅 " + stringResource(R.string.calendar_title),
                subtitle = stringResource(if (calendarOn) R.string.connected else R.string.calendar_sub),
                chevron = calendarOn,
                onClick = { if (calendarOn) Nav.push(Route.Calendar) else askCalendar.launch(PhoneCalendar.PERMISSIONS) },
                trailing = if (calendarOn) null else ({
                    SecondaryButton(stringResource(R.string.connect), { askCalendar.launch(PhoneCalendar.PERMISSIONS) }, small = true)
                }),
            )
        }
        list.forEach { s ->
            item {
                ListRow(
                    "${s.icon} ${s.name}",
                    subtitle = when {
                        s.connected -> s.account ?: connected
                        s.status == "error" -> s.error
                        else -> s.description
                    },
                    chevron = s.status != "off",
                    onClick = { if (s.status != "off") Nav.push(Route.Connector(s.id)) else connect(s.id) },
                    trailing = if (s.status != "off") null else ({
                        if (busy == s.id) Spinner(20.dp)
                        else SecondaryButton(stringResource(R.string.connect), { connect(s.id) }, small = true)
                    }),
                )
            }
        }
    }
}

@Composable
fun ConnectorScreen(id: String) {
    var state by remember { mutableStateOf<Res<Connector>>(Res(loading = true)) }
    var note by remember { mutableStateOf<String?>(null) }
    var askOff by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = Mn.c
    val version = Connectors.version.intValue
    fun reload() {
        scope.launch {
            state = try {
                Api.connectors().firstOrNull { it.id == id }?.let { Res(it) }
                    ?: Res(error = codes.neuro.neurosoft.net.ApiException(codes.neuro.neurosoft.net.ApiException.Kind.NOT_FOUND))
            } catch (e: Exception) {
                state.copy(error = e, loading = false)
            }
            busy = false
        }
    }
    LaunchedEffect(id, version) { reload() }
    val s = state.data
    if (askOff && s != null) {
        AlertDialog(
            onDismissRequest = { askOff = false },
            title = { Text(stringResource(R.string.svc_disconnect_q, s.name)) },
            confirmButton = {
                TextButton({
                    askOff = false
                    scope.launch {
                        try {
                            Api.disconnectConnector(s.id)
                            Connectors.version.intValue++
                            Nav.pop()
                        } catch (e: Exception) {
                            note = e.message
                        }
                    }
                }) { Text(stringResource(R.string.svc_disconnect), color = c.red) }
            },
            dismissButton = { TextButton({ askOff = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    SettingsPage(s?.name ?: "", big = false) {
        when {
            s == null && state.error != null -> ErrorBlock(state.error, { reload() })
            s == null -> LoadingBlock()
            else -> {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiTile(s.icon, 64.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Txt(s.name, T.title)
                        Txt(if (s.connected) s.account ?: stringResource(R.string.connected) else s.error ?: "",
                            T.caption, if (s.connected) c.graphite else c.amber)
                    }
                }
                Txt(s.description, T.body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                note?.let { Txt(it, T.caption, c.red, Modifier.padding(horizontal = 20.dp)) }
                if (!s.connected) {
                    Group {
                        item {
                            ListRow(stringResource(R.string.svc_reconnect), icon = Icons.Refresh, accent = true,
                                trailing = if (busy) ({ Spinner(20.dp) }) else null, onClick = {
                                    if (!busy) {
                                        busy = true
                                        scope.launch {
                                            note = Connectors.connect(context, s.id)
                                            busy = false
                                        }
                                    }
                                })
                        }
                    }
                } else {
                    fun change(updated: Connector, key: String, on: Boolean) {
                        state = Res(updated)
                        scope.launch {
                            try {
                                Api.setConnector(s.id, key, on)
                            } catch (e: Exception) {
                                note = e.message
                                reload()
                            }
                        }
                    }
                    // что ассистенту можно в сервисе — решает пользователь
                    Group(stringResource(R.string.svc_assistant_can), inset = 56.dp) {
                        item {
                            ToggleRow(stringResource(R.string.svc_read), s.read, { change(s.copy(read = it), "read", it) },
                                icon = Icons.Search, subtitle = stringResource(R.string.svc_read_sub))
                        }
                        item {
                            ToggleRow(stringResource(R.string.svc_write), s.write, { change(s.copy(write = it), "write", it) },
                                icon = Icons.Edit, subtitle = stringResource(R.string.svc_write_sub))
                        }
                    }
                }
                Group {
                    item {
                        ListRow(stringResource(R.string.svc_disconnect), icon = Icons.Trash, danger = true,
                            onClick = { askOff = true })
                    }
                }
            }
        }
    }
}
