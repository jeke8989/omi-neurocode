package codes.neuro.neurosoft.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.PhoneCalendar
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.TaskItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Calendar

/** Действия с обещанием: в календарь, поделиться черновиком, выбрать срок. */
object PromiseActions {
    fun share(context: Context, text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (!subject.isNullOrBlank()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Без доступа к календарю: экран новой встречи в приложении календаря (метки «в календаре» не будет). */
    fun toCalendarApp(context: Context, t: TaskItem, minutes: Int) {
        val begin = t.dueMillis() ?: nextHour()
        try {
            context.startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, t.text).putExtra(CalendarContract.Events.DESCRIPTION, notes(context, t))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + minutes * 60_000L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.promise_no_calendar, Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextHour(): Long {
        val h = 3600_000L
        return (System.currentTimeMillis() / h + 1) * h
    }

    private fun notes(context: Context, t: TaskItem) =
        listOfNotNull(t.mate?.let { context.getString(R.string.promise_with, it) }, t.evidence?.let { "«$it»" })
            .joinToString("\n")

    /**
     * Обещание и календарь — заодно: нужно в календаре и ещё нет — встреча создаётся (с напоминанием);
     * уже есть — переезжает вместе со сроком и названием; больше не нужно — удаляется. t — уже с новым
     * текстом и сроком. Встреча — того размера, что выбран (по умолчанию 30 минут).
     */
    suspend fun syncCalendar(context: Context, t: TaskItem, want: Boolean, minutes: Int) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!PhoneCalendar.granted(context) || t.id == 0L) return@withContext
            val begin = t.dueMillis() ?: nextHour()
            val end = begin + minutes * 60_000L
            val existing = t.calendarEventId?.takeIf { t.inCalendar }
            try {
                when {
                    want && existing == null -> {
                        val id = PhoneCalendar.addEvent(context, t.text, begin, end, notes(context, t))
                        codes.neuro.neurosoft.net.Api.setTaskCalendar(t.id, id, minutes)
                    }
                    want && existing != null -> {
                        PhoneCalendar.moveEvent(context, existing, begin, end, t.text)
                        codes.neuro.neurosoft.net.Api.setTaskCalendar(t.id, existing, minutes)
                    }
                    !want && t.calendarEventId != null -> {
                        PhoneCalendar.removeEvent(context, t.calendarEventId)
                        codes.neuro.neurosoft.net.Api.setTaskCalendar(t.id, null)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Mnemo", "promise calendar sync failed", e)
            }
            PhoneCalendar.sync(context, force = true)
        }

    private fun at(date: LocalDate, time: LocalTime): Long =
        LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Готовые сроки: сегодня вечером, завтра утром, в пятницу, через неделю. */
    fun quickDues(): List<Pair<Int, Long>> {
        val today = LocalDate.now()
        val now = LocalTime.now()
        val out = ArrayList<Pair<Int, Long>>()
        if (now.isBefore(LocalTime.of(18, 0))) out += R.string.due_tonight to at(today, LocalTime.of(19, 0))
        out += R.string.due_tomorrow to at(today.plusDays(1), LocalTime.of(10, 0))
        val friday = today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        if (friday.isAfter(today.plusDays(1))) out += R.string.due_friday to at(friday, LocalTime.of(10, 0))
        out += R.string.due_week to at(today.plusWeeks(1), LocalTime.of(10, 0))
        return out
    }
}

/** «Другой срок»: готовые варианты, свой день и время или без срока. onPick(null) — без срока. */
@Composable
fun DueDialog(t: TaskItem, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val context = LocalContext.current
    val c = Mn.c
    val fmt = rememberFmt()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        title = { Text(stringResource(R.string.promise_due_title), style = T.section, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(t.text, T.body, c.graphite, maxLines = 3)
                Spacer(Modifier.height(4.dp))
                Wrap {
                    PromiseActions.quickDues().forEach { (label, ms) ->
                        Chip(stringResource(label) + ", " + fmt.timeMs(ms), false, { onPick(ms) })
                    }
                    Chip(stringResource(R.string.due_pick), false, {
                        val cal = Calendar.getInstance()
                        DatePickerDialog(context, { _, y, m, d ->
                            TimePickerDialog(context, { _, h, min ->
                                onPick(PromiseActionsTime.of(y, m, d, h, min))
                            }, 10, 0, true).show()
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    })
                    Chip(stringResource(R.string.due_none), false, { onPick(null) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), style = T.bodyStrong, color = c.ink) } },
    )
}

private object PromiseActionsTime {
    fun of(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m + 1, d, h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Черновик сообщения или письма: можно поправить и отправить (поделиться) или скопировать. text == null — готовится. */
@Composable
fun DraftDialog(title: String, subject: String?, text: String?, error: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = Mn.c
    var body by remember(text) { mutableStateOf(text ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        title = { Text(title, style = T.section, color = c.ink) },
        text = {
            Column {
                when {
                    error -> Txt(stringResource(R.string.draft_failed), T.body, c.red)
                    text == null -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Spinner(18.dp)
                        Spacer(Modifier.width(10.dp))
                        Txt(stringResource(R.string.draft_writing), T.body, c.graphite)
                    }
                    else -> {
                        subject?.takeIf { it.isNotBlank() }?.let { Txt(it, T.bodyStrong, modifier = Modifier.padding(bottom = 8.dp)) }
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()
                            .then(Modifier.heightIn(min = 120.dp, max = 360.dp))) {
                            androidx.compose.foundation.text.BasicTextField(body, { body = it },
                                textStyle = T.body.copy(color = c.ink),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.lens),
                                modifier = Modifier.fillMaxWidth().verticalScrollable())
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (text != null && !error) {
                TextButton({
                    onDismiss()
                    PromiseActions.share(context, body, subject)
                }) { Text(stringResource(R.string.draft_send), style = T.bodyStrong, color = c.lens) }
            }
        },
        dismissButton = {
            Row {
                if (text != null && !error) {
                    TextButton({
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("Mnemo", listOfNotNull(subject, body).joinToString("\n\n")))
                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.copy), style = T.bodyStrong, color = c.ink) }
                }
                TextButton(onDismiss) { Text(stringResource(R.string.cancel), style = T.bodyStrong, color = c.graphite) }
            }
        },
    )
}

@Composable
private fun Modifier.verticalScrollable(): Modifier = this.verticalScroll(rememberScrollState())
