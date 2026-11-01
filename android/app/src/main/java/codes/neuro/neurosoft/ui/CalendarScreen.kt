package codes.neuro.neurosoft.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.PhoneCalendar
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.CalendarStatus
import kotlinx.coroutines.launch

/** Календарь телефона: что можно ассистенту, куда добавлять встречи, отключить. */
@Composable
fun CalendarScreen() {
    var state by remember { mutableStateOf<Res<CalendarStatus>>(Res(loading = true)) }
    var askOff by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fmt = rememberFmt()
    val c = Mn.c
    fun reload() {
        scope.launch {
            PhoneCalendar.sync(context)
            state = try {
                Res(Api.calendarStatus())
            } catch (e: Exception) {
                state.copy(error = e, loading = false)
            }
        }
    }
    fun change(block: suspend () -> CalendarStatus) {
        scope.launch {
            state = try {
                Res(block())
            } catch (e: Exception) {
                state.copy(error = e)
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    if (askOff) {
        AlertDialog(
            onDismissRequest = { askOff = false },
            containerColor = c.paper,
            title = { Text(stringResource(R.string.calendar_off_q), style = T.section, color = c.ink) },
            confirmButton = {
                TextButton({
                    askOff = false
                    Core.prefs.calendarOn = false
                    scope.launch {
                        try {
                            Api.calendarDisconnect()
                        } catch (_: Exception) {
                        }
                        Connectors.version.intValue++
                        Nav.pop()
                    }
                }) { Text(stringResource(R.string.svc_disconnect), color = c.red, style = T.bodyStrong) }
            },
            dismissButton = { TextButton({ askOff = false }) { Text(stringResource(R.string.cancel), color = c.ink, style = T.bodyStrong) } },
        )
    }
    SettingsPage(stringResource(R.string.calendar_title), big = false) {
        val s = state.data
        when {
            s == null && state.error != null -> ErrorBlock(state.error, { reload() })
            s == null -> LoadingBlock()
            else -> {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiTile("📅", 64.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Txt(stringResource(R.string.calendar_title), T.title)
                        Txt(if (s.connected) plural(R.plurals.calendar_events, s.events) +
                            (s.syncedAt?.let { ", " + fmt.time(it) } ?: "")
                            else stringResource(R.string.calendar_waiting), T.caption, c.graphite)
                    }
                }
                Group(stringResource(R.string.svc_assistant_can), inset = 56.dp) {
                    item {
                        ToggleRow(stringResource(R.string.svc_read), s.read, { on ->
                            state = Res(s.copy(read = on))
                            change { Api.calendarSettings(read = on) }
                        }, icon = Icons.Calendar, subtitle = stringResource(R.string.calendar_read_sub))
                    }
                    item {
                        ToggleRow(stringResource(R.string.calendar_write), s.write, { on ->
                            state = Res(s.copy(write = on))
                            change { Api.calendarSettings(write = on) }
                        }, icon = Icons.Edit, subtitle = stringResource(R.string.calendar_write_sub))
                    }
                }
                // встречи, которые создаёт Mnemo: длительность по умолчанию и напоминание (оно ставится всегда)
                var length by remember { mutableStateOf(Core.prefs.calendarDefaultMin) }
                var alarm by remember { mutableStateOf(Core.prefs.calendarReminderMin) }
                Group(stringResource(R.string.calendar_new_events), inset = 56.dp) {
                    item { ListRow(stringResource(R.string.calendar_length), icon = Icons.Clock) }
                }
                Wrap(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        Chip(if (m < 60) stringResource(R.string.minutes_n, m) else stringResource(R.string.hours_n, m / 60),
                            length == m, { length = m; Core.prefs.calendarDefaultMin = m })
                    }
                }
                Group(inset = 56.dp) {
                    item { ListRow(stringResource(R.string.calendar_alarm), icon = Icons.Bell) }
                }
                Wrap(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    listOf(5, 10, 15, 30, 60).forEach { m ->
                        Chip(stringResource(R.string.calendar_alarm_before, if (m < 60) stringResource(R.string.minutes_n, m)
                            else stringResource(R.string.hours_n, m / 60)), alarm == m, { alarm = m; Core.prefs.calendarReminderMin = m })
                    }
                }
                if (s.calendars.size > 1) {
                    Group(stringResource(R.string.calendar_target), inset = 56.dp) {
                        val chosen = s.target ?: s.calendars.firstOrNull { it.primary }?.id
                        s.calendars.forEach { cal ->
                            item {
                                ListRow(cal.name.ifBlank { cal.account }, subtitle = cal.account.takeIf { it != cal.name && it.isNotBlank() },
                                    icon = Icons.Calendar, onClick = {
                                        state = Res(s.copy(target = cal.id))
                                        change { Api.calendarSettings(target = cal.id) }
                                    }) {
                                    if (chosen == cal.id) Icon(Icons.Check, null, tint = c.ink, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                Group {
                    item {
                        ListRow(stringResource(R.string.svc_disconnect), icon = Icons.Trash, danger = true, onClick = { askOff = true })
                    }
                }
            }
        }
    }
}
