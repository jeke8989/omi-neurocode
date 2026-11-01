@file:OptIn(ExperimentalMaterial3Api::class)

package codes.neuro.neurosoft.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.PersonRef
import codes.neuro.neurosoft.net.TaskItem
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

/** Имя с большой буквы: «папа» -> «Папа», «иван петров» -> «Иван Петров». */
fun String.properName(): String = split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

/** Что поменяли в обещании (null — не трогали). */
data class PromiseEdit(
    val text: String? = null, val kind: String? = null, val dueAt: Long? = null, val clearDue: Boolean = false,
    val personId: Long? = null, val personName: String? = null, val clearPerson: Boolean = false,
    /** Безымянному голосу дали имя — сохранить и в «Людях». */
    val nameVoice: Pair<Long, String>? = null,
    /** В календаре: true — должна быть встреча, false — убрать; длительность встречи в минутах. */
    val calendar: Boolean = false,
    val calendarMinutes: Int = 30,
)

/**
 * Карточка обещания: что, кто кому, с кем (человек из «Людей», голос из разговора или имя руками), срок —
 * всё правится в одном месте и сохраняется одной кнопкой. Тут же — в календарь, напомнить, открыть разговор.
 */
@Composable
fun PromiseSheet(t: TaskItem, onDismiss: () -> Unit, onSave: (PromiseEdit) -> Unit, onNudge: (TaskItem) -> Unit,
                 onDone: () -> Unit) {
    val c = Mn.c
    val fmt = rememberFmt()
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    var text by remember(t.id) { mutableStateOf(t.text) }
    var kind by remember(t.id) { mutableStateOf(t.kind) }
    var due by remember(t.id) { mutableStateOf(t.dueMillis()) }
    var dueTouched by remember(t.id) { mutableStateOf(false) }
    // с кем: выбранный человек или имя руками
    var person by remember(t.id) { mutableStateOf<PersonRef?>(null) }
    var personName by remember(t.id) { mutableStateOf(t.mate ?: "") }
    var personTouched by remember(t.id) { mutableStateOf(false) }
    var voiceName by remember(t.id) { mutableStateOf("") }
    var choosing by remember(t.id) { mutableStateOf(false) }
    // календарь: встреча на срок обещания (по умолчанию 30 минут) с напоминанием
    var inCalendar by remember(t.id) { mutableStateOf(t.inCalendar) }
    var minutes by remember(t.id) { mutableStateOf(t.calendarMinutes ?: codes.neuro.neurosoft.Core.prefs.calendarDefaultMin) }
    val askCalendar = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        if (codes.neuro.neurosoft.PhoneCalendar.granted(context)) {
            codes.neuro.neurosoft.Core.prefs.calendarOn = true
            inCalendar = true
        } else {
            // без доступа — откроем календарь с готовой встречей, но отмечать её не сможем
            PromiseActions.toCalendarApp(context, t.copy(text = text.trim().ifBlank { t.text }), minutes)
        }
    }
    // кто был в разговоре — первыми; потом все, кого знаем по имени
    var inConversation by remember(t.id) { mutableStateOf<List<PersonRef>>(emptyList()) }
    val people by Repo.people.state.collectAsState()
    LaunchedEffect(t.id) {
        if (people.data == null) launch { try { Repo.people.refresh() } catch (_: Exception) {} }
        val cid = t.conversationId ?: return@LaunchedEffect
        inConversation = try {
            Api.conversation(cid).people.values.filter { !it.isOwner && !it.ignored && !it.background }
        } catch (_: Exception) {
            emptyList()
        }
    }
    val known = people.data?.people?.map { it.ref }?.filter { !it.isOwner && it.name != null && it !in inConversation }.orEmpty()

    fun shownDue(): String? = due?.let { ms ->
        val iso = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
        fmt.dayShortTitle(fmt.dayOf(iso)) + ", " + fmt.time(iso)
    }

    fun edited(): PromiseEdit = PromiseEdit(
        text = text.trim().takeIf { it.isNotEmpty() && it != t.text },
        kind = kind.takeIf { it != t.kind },
        dueAt = if (dueTouched) due else null, clearDue = dueTouched && due == null,
        personId = if (personTouched) person?.id else null,
        personName = if (personTouched && person == null) personName.trim().properName().ifBlank { null } else null,
        clearPerson = personTouched && person == null && personName.isBlank(),
        nameVoice = person?.takeIf { it.name == null && voiceName.isNotBlank() }?.let { it.id to voiceName.trim().properName() },
        calendar = inCalendar, calendarMinutes = minutes,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.fog) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).imePadding()) {
            val isNew = t.id == 0L
            // что
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.paper)
                .padding(16.dp)) {
                if (text.isEmpty()) Txt(stringResource(R.string.promise_new_hint), T.title, c.faint)
                BasicTextField(text, { text = it }, textStyle = T.title.copy(color = c.ink), cursorBrush = SolidColor(c.lens),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
            if (isNew) LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
            t.evidence?.takeIf { it.isNotBlank() }?.let {
                Txt(stringResource(R.string.quoted, it), T.caption.copy(fontStyle = FontStyle.Italic), c.graphite,
                    Modifier.padding(horizontal = 22.dp, vertical = 8.dp), maxLines = 4)
            }

            // кто кому
            SheetLabel(stringResource(R.string.promise_kind))
            Wrap(modifier = Modifier.padding(horizontal = 16.dp)) {
                listOf("mine" to R.string.promises_mine, "waiting" to R.string.promises_theirs,
                    "reminder" to R.string.task_reminder).forEach { (k, label) ->
                    Chip(stringResource(label), kind == k, { kind = k })
                }
            }

            // с кем
            if (kind != "reminder") {
                SheetLabel(stringResource(if (kind == "waiting") R.string.promise_who_theirs else R.string.promise_who_mine))
                val current = person?.let { personLabel(it) } ?: personName.ifBlank { null }
                Group(inset = 56.dp) {
                    item {
                        ListRow(current?.properName() ?: stringResource(R.string.promise_who_none), icon = Icons.Person,
                            chevron = true, onClick = { choosing = !choosing })
                    }
                    if (choosing) {
                        (inConversation + known).take(30).forEach { p ->
                            item {
                                ListRow(personLabel(p).properName(), subtitle = if (p in inConversation)
                                    stringResource(R.string.promise_was_there) else null,
                                    onClick = {
                                        person = p
                                        personName = ""
                                        personTouched = true
                                        choosing = p.name == null  // безымянный голос — сразу спросим имя
                                    }) {
                                    if (person?.id == p.id) Icon(Icons.Check, null, tint = c.ink, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        item {
                            FieldRow(stringResource(R.string.promise_who_other), if (person == null) personName else "", {
                                personName = it
                                person = null
                                personTouched = true
                            }, stringResource(R.string.intro_name_hint))
                        }
                    }
                    // голос без имени: как его зовут — запомнится и в «Людях»
                    if (person != null && person?.name == null) {
                        item { FieldRow(stringResource(R.string.promise_voice_name), voiceName, { voiceName = it },
                            stringResource(R.string.intro_name_hint)) }
                    }
                }
            }

            // срок
            SheetLabel(stringResource(R.string.promise_due) + (shownDue()?.let { ": $it" } ?: ""))
            Wrap(modifier = Modifier.padding(horizontal = 16.dp)) {
                PromiseActions.quickDues().forEach { (label, ms) ->
                    Chip(stringResource(label) + ", " + fmt.timeMs(ms), due == ms, { due = ms; dueTouched = true })
                }
                Chip(stringResource(R.string.due_pick), false, {
                    val cal = Calendar.getInstance().apply { due?.let { timeInMillis = it } }
                    DatePickerDialog(context, { _, y, m, d ->
                        TimePickerDialog(context, { _, h, min ->
                            due = LocalDateTime.of(y, m + 1, d, h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            dueTouched = true
                        }, cal.get(Calendar.HOUR_OF_DAY).takeIf { due != null } ?: 10, cal.get(Calendar.MINUTE).takeIf { due != null } ?: 0,
                            true).show()
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                })
                if (due != null) Chip(stringResource(R.string.due_none), false, { due = null; dueTouched = true })
            }

            // календарь: метка «в календаре», длительность, напоминание
            SheetLabel(stringResource(R.string.calendar_title))
            Group(inset = 56.dp) {
                item {
                    ToggleRow(stringResource(if (inCalendar) R.string.promise_in_calendar else R.string.promise_to_calendar), inCalendar, { on ->
                        if (on && !codes.neuro.neurosoft.PhoneCalendar.granted(context)) askCalendar.launch(codes.neuro.neurosoft.PhoneCalendar.PERMISSIONS)
                        else inCalendar = on
                    }, icon = Icons.Calendar, subtitle = if (inCalendar) listOfNotNull(
                        due?.let { ms ->
                            val a = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                            val b = Instant.ofEpochMilli(ms + minutes * 60_000L).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                            fmt.dayShortTitle(fmt.dayOf(a)) + ", " + fmt.time(a) + "–" + fmt.time(b)
                        } ?: stringResource(R.string.promise_calendar_no_due),
                        stringResource(R.string.promise_calendar_alarm, codes.neuro.neurosoft.Core.prefs.calendarReminderMin),
                    ).joinToString("\n") else null)
                }
                if (inCalendar && t.inCalendar && t.calendarEventId != null) {
                    item {
                        ListRow(stringResource(R.string.promise_calendar_open), icon = Icons.Link, chevron = true,
                            onClick = { codes.neuro.neurosoft.PhoneCalendar.open(context, t.calendarEventId) })
                    }
                }
            }
            if (inCalendar) {
                Wrap(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        Chip(if (m < 60) stringResource(R.string.minutes_n, m) else stringResource(R.string.hours_n, m / 60),
                            minutes == m, { minutes = m })
                    }
                }
            }

            // действия
            Spacer(Modifier.height(18.dp))
            Wrap(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (kind == "waiting" && !isNew) {
                    Chip(stringResource(R.string.promise_nudge), false, { onNudge(t) },
                        leading = { Icon(Icons.Share, null, tint = c.ink, modifier = Modifier.size(16.dp)) })
                }
                t.conversationId?.let { cid ->
                    Chip(stringResource(R.string.promise_conversation), false, { onDismiss(); Nav.push(Route.Conversation(cid)) },
                        leading = { Icon(Icons.Wave, null, tint = c.ink, modifier = Modifier.size(16.dp)) })
                }
                if (t.status != "done" && !isNew) {
                    Chip(stringResource(R.string.promise_mark_done), false, onDone,
                        leading = { Icon(Icons.Check, null, tint = c.ink, modifier = Modifier.size(16.dp)) })
                }
            }
            PrimaryButton(stringResource(if (isNew) R.string.promise_add else R.string.save), { onSave(edited()) },
                Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).fillMaxWidth(), enabled = text.isNotBlank())
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Txt(text, T.smallStrong, Mn.c.graphite, Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp))
}
