package codes.neuro.neurosoft.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.Routines
import kotlinx.coroutines.launch

/**
 * Что агент делает сам: задания по расписанию и постоянные правила. Ставит и убирает их сам агент по просьбе
 * в чате («каждый день в 23:00 делай сводку в Notion», «убери это»); здесь — посмотреть, выключить, удалить.
 */
@Composable
fun RoutinesScreen() {
    var state by remember { mutableStateOf<Res<Routines>>(Res(loading = true)) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    val c = Mn.c
    fun reload() {
        scope.launch {
            state = try {
                Res(Api.routines())
            } catch (e: Exception) {
                state.copy(error = e, loading = false)
            }
        }
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (_: Exception) {
            }
            reload()
        }
    }
    LaunchedEffect(Unit) { reload() }
    SettingsPage(stringResource(R.string.routines_title)) {
        val d = state.data
        when {
            d == null && state.error != null -> ErrorBlock(state.error, { reload() })
            d == null -> LoadingBlock()
            d.rules.isEmpty() && d.schedules.isEmpty() ->
                EmptyBlock(Icons.Clock, stringResource(R.string.routines_empty_title), stringResource(R.string.routines_empty_text))
            else -> {
                if (d.schedules.isNotEmpty()) {
                    Group(stringResource(R.string.routines_schedules), inset = 56.dp) {
                        d.schedules.forEach { s ->
                            item {
                                val next = s.nextRunAt?.let { stringResource(R.string.routines_next,
                                    fmt.dayShortTitle(fmt.dayOf(it)) + ", " + fmt.time(it)) }
                                ToggleRow(s.title, s.enabled, { on ->
                                    state = Res(d.copy(schedules = d.schedules.map { if (it.id == s.id) it.copy(enabled = on) else it }))
                                    act { Api.setScheduleEnabled(s.id, on) }
                                }, icon = Icons.Clock, subtitle = listOfNotNull(s.whenText, next.takeIf { s.enabled }, s.prompt)
                                    .joinToString("\n"))
                            }
                            item {
                                ListRow(stringResource(R.string.delete), icon = Icons.Trash, danger = true,
                                    onClick = { act { Api.deleteSchedule(s.id) } })
                            }
                        }
                    }
                }
                if (d.rules.isNotEmpty()) {
                    Group(stringResource(R.string.routines_rules), inset = 56.dp) {
                        d.rules.forEach { r ->
                            item {
                                ListRow(r.text, icon = Icons.Bookmark, trailing = {
                                    IconCircle(Icons.Close, stringResource(R.string.delete), { act { Api.deleteRule(r.id) } },
                                        size = 32.dp, background = c.raised)
                                })
                            }
                        }
                    }
                }
                Txt(stringResource(R.string.routines_hint), T.caption, c.graphite,
                    Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
            }
        }
    }
}
