package codes.neuro.neurosoft

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.TaskItem
import codes.neuro.neurosoft.ui.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/**
 * «Это задача?» — когда ассистент не уверен, что услышанное стоит записать (предложенная задача),
 * приходит уведомление с кнопками «В задачи» и «Убрать». Ответ уходит на сервер прямо из шторки.
 * Про каждую задачу спрашиваем один раз и только про свежие (за 12 часов), не больше трёх за раз.
 */
object TaskPrompts {
    const val CHANNEL = "task_prompts"
    const val ACCEPT = "codes.neuro.neurosoft.TASK_ACCEPT"
    const val DISMISS = "codes.neuro.neurosoft.TASK_DISMISS"
    const val MEETING = "codes.neuro.neurosoft.MEETING_ADD"
    private const val FRESH_MS = 12 * 3600_000L
    private const val PER_CHECK = 3

    fun notificationId(id: Long) = 2000 + (id % 100_000).toInt()

    private fun channel(context: Context): NotificationManager? {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        val r = Core.res()
        val ch = NotificationChannel(CHANNEL, r.getString(R.string.task_prompt_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = r.getString(R.string.task_prompt_channel_desc)
        nm.createNotificationChannel(ch)
        return nm
    }

    /** Обещание ждёт ответа: предложенное (не уверены) или новое из разговора, ещё не подтверждённое «Да». */
    fun pending(t: TaskItem) = t.status == "suggested" ||
        (t.status == "open" && t.confirmedAt == null && t.source == "conversation")

    /** Встреча, которую ещё не поставили в календарь: спрашиваем «В календарь?» (если календарь подключён). */
    fun meetingAsk(context: Context, t: TaskItem) = t.kind == "meeting" && t.calendarEventId == null &&
        t.confirmedAt == null && t.status in setOf("open", "suggested") &&
        (t.dueMillis() ?: 0L) > System.currentTimeMillis() && PhoneCalendar.active(context)

    /** Свежий список с сервера: карточка на каждое новое обещание, убрать уведомления о решённых. */
    fun onTasks(context: Context, tasks: List<TaskItem>) {
        val prefs = Core.prefs
        val suggested = tasks.filter { pending(it) || meetingAsk(context, it) }
        // вопрос «В календарь?» — отдельный от «Это обещание?» (ключ -id): дело, ставшее встречей (в том числе
        // старое «Встреча завтра в 14:30», которое сервер один раз перевёл во встречи), спросим заново
        fun key(t: TaskItem) = if (meetingAsk(context, t)) -t.id else t.id
        val keys = suggested.map { key(it) }.toSet()
        val asked = prefs.askedTasks
        val nm = channel(context) ?: return
        // ответили в приложении или ночная проверка решила сама — уведомление больше не нужно
        (asked - keys).forEach { nm.cancel(notificationId(kotlin.math.abs(it))) }
        if (!prefs.taskPrompts) {
            prefs.askedTasks = asked.intersect(keys)
            return
        }
        val now = System.currentTimeMillis()
        val fresh = suggested.filter { key(it) !in asked && created(it) > now - FRESH_MS }.take(PER_CHECK)
        fresh.forEach { if (meetingAsk(context, it)) showMeeting(context, nm, it) else show(context, nm, it) }
        prefs.askedTasks = asked.intersect(keys) + fresh.map { key(it) }
    }

    private fun created(t: TaskItem): Long = try {
        OffsetDateTime.parse(t.createdAt).toInstant().toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    private fun action(context: Context, action: String, t: TaskItem) = PendingIntent.getBroadcast(
        context, notificationId(t.id) * 2 + if (action == ACCEPT) 1 else 0,
        Intent(context, TaskPromptReceiver::class.java).setAction(action).putExtra("id", t.id).putExtra("text", t.text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** «Вы пообещали Ивану», «Иван обещал вам», «Напомнить вам». */
    fun headline(t: TaskItem): String {
        val r = Core.res()
        val mate = t.mate
        return when {
            t.kind == "reminder" -> r.getString(R.string.promise_card_reminder)
            t.kind == "meeting" -> mate?.let { r.getString(R.string.promise_meeting_with, it) } ?: r.getString(R.string.promise_meeting)
            t.theirs -> mate?.let { r.getString(R.string.promise_card_theirs, it) } ?: r.getString(R.string.promise_card_theirs_anon)
            mate != null -> r.getString(R.string.promise_card_mine_to, mate)
            else -> r.getString(R.string.promise_card_mine)
        }
    }

    private fun dueText(t: TaskItem): String? {
        val ms = t.dueMillis() ?: return t.due?.takeIf { it.isNotBlank() }
        val at = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        return at.format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.getDefault()))
    }

    private fun show(context: Context, nm: NotificationManager, t: TaskItem, title: Int? = null) {
        val r = Core.res()
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java)
            .putExtra("open", "tasks"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val due = PendingIntent.getActivity(context, notificationId(t.id) * 2 + 7, Intent(context, MainActivity::class.java)
            .putExtra("open", "due:${t.id}"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val details = listOfNotNull(
            dueText(t)?.let { r.getString(R.string.task_prompt_due, it) },
            t.evidence?.takeIf { it.isNotBlank() }?.let { r.getString(R.string.quoted, it) },
            t.conversationTitle?.takeIf { it.isNotBlank() },
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(title?.let { r.getString(it) } ?: headline(t))
            .setContentText(t.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(t.text) + details).joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setGroup(CHANNEL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, r.getString(R.string.promise_yes), action(context, ACCEPT, t))
            .addAction(0, r.getString(R.string.promise_no), action(context, DISMISS, t))
            .addAction(0, r.getString(R.string.promise_other_due), due)
            .build()
        try {
            nm.notify(notificationId(t.id), n)
        } catch (_: SecurityException) {
        }
        Inbox.add("task", "task:${t.id}", headline(t), t.text, "tasks", t.id)
    }

    /** «Поставить встречу в календарь?» — когда, сколько длится, за сколько напомнит; кнопки: в календарь, изменить, не надо. */
    private fun showMeeting(context: Context, nm: NotificationManager, t: TaskItem) {
        val r = Core.res()
        val minutes = t.calendarMinutes ?: Core.prefs.calendarDefaultMin
        val due = t.dueMillis() ?: return
        val zone = java.time.ZoneId.systemDefault()
        val loc = java.util.Locale.getDefault()
        val start = java.time.Instant.ofEpochMilli(due).atZone(zone)
        val end = start.plusMinutes(minutes.toLong())
        val whenText = start.format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM, HH:mm", loc)) + "–" +
            end.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", loc))
        val details = listOfNotNull(whenText, r.getString(R.string.meeting_alarm, Core.prefs.calendarReminderMin),
            t.conversationTitle?.takeIf { it.isNotBlank() })
        val add = PendingIntent.getBroadcast(context, notificationId(t.id) * 2 + 11,
            Intent(context, TaskPromptReceiver::class.java).setAction(MEETING).putExtra("id", t.id).putExtra("text", t.text)
                .putExtra("due", due).putExtra("minutes", minutes).putExtra("notes", t.conversationTitle ?: ""),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val change = PendingIntent.getActivity(context, notificationId(t.id) * 2 + 7, Intent(context, MainActivity::class.java)
            .putExtra("open", "due:${t.id}"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java)
            .putExtra("open", "tasks"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(r.getString(R.string.meeting_card_title))
            .setContentText("${t.text} · $whenText")
            .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(t.text) + details).joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setGroup(CHANNEL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, r.getString(R.string.meeting_add), add)
            .addAction(0, r.getString(R.string.meeting_change), change)
            .addAction(0, r.getString(R.string.meeting_skip), action(context, DISMISS, t))
            .build()
        try {
            nm.notify(notificationId(t.id), n)
        } catch (_: SecurityException) {
        }
        Inbox.add("task", "task:${t.id}", r.getString(R.string.meeting_card_title), "${t.text} · $whenText", "tasks", t.id)
    }

    /** «В календарь»: встреча — в календарь телефона (с напоминанием), обещание — отмечено и связано с ней. */
    suspend fun addMeeting(context: Context, id: Long, text: String, due: Long, minutes: Int, notes: String?): Boolean {
        val event = try {
            PhoneCalendar.addEvent(context, text, due, due + minutes * 60_000L, notes?.takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            android.util.Log.w("Mnemo", "meeting to calendar failed", e)
            null
        }
        if (event != null) {
            try {  // нет связи — встреча всё равно в календаре; связь с обещанием догонит при следующей правке
                Api.setTaskCalendar(id, event, minutes)
                Api.setTaskStatus(id, "open")
            } catch (_: Exception) {
            }
        }
        try {
            PhoneCalendar.sync(context, force = true)
            Repo.tasks.refresh()
        } catch (_: Exception) {
        }
        return event != null
    }

    /** Встреча поставлена (или не получилось) — коротко на месте уведомления. */
    fun meetingResult(context: Context, id: Long, text: String, ok: Boolean) {
        val nm = channel(context) ?: return
        val r = Core.res()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(r.getString(if (ok) R.string.meeting_added else R.string.meeting_failed))
            .setContentText(if (ok) "$text · " + r.getString(R.string.meeting_alarm, Core.prefs.calendarReminderMin) else text)
            .setGroup(CHANNEL)
            .setAutoCancel(true)
            .setTimeoutAfter(if (ok) 5_000L else 15_000L)
            .setSilent(true)
            .build()
        try {
            nm.notify(notificationId(id), n)
        } catch (_: SecurityException) {
        }
    }

    /** Ответ принят — коротко подтверждаем на месте уведомления. Нет связи — вопрос остаётся с кнопками. */
    fun result(context: Context, id: Long, text: String, ok: Boolean, accept: Boolean) {
        val nm = channel(context) ?: return
        if (!ok) {
            show(context, nm, TaskItem(id, text, null, null, "suggested", "conversation", null, null, null),
                R.string.task_prompt_offline)
            return
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(Core.res().getString(if (accept) R.string.task_prompt_added else R.string.task_prompt_removed))
            .setContentText(text)
            .setGroup(CHANNEL)
            .setAutoCancel(true)
            .setTimeoutAfter(4_000L)
            .setSilent(true)
            .build()
        try {
            nm.notify(notificationId(id), n)
        } catch (_: SecurityException) {
        }
    }
}

class TaskPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        val id = intent.getLongExtra("id", 0L).takeIf { it > 0 } ?: return
        val text = intent.getStringExtra("text").orEmpty()
        val done = goAsync()
        if (intent.action == TaskPrompts.MEETING) {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = TaskPrompts.addMeeting(context, id, text, intent.getLongExtra("due", 0L),
                    intent.getIntExtra("minutes", Core.prefs.calendarDefaultMin), intent.getStringExtra("notes"))
                TaskPrompts.meetingResult(context, id, text, ok)
                done.finish()
            }
            return
        }
        val accept = intent.action == TaskPrompts.ACCEPT
        CoroutineScope(Dispatchers.IO).launch {
            val ok = try {
                Api.setTaskStatus(id, if (accept) "open" else "dismissed")
                true
            } catch (e: Exception) {
                false
            }
            TaskPrompts.result(context, id, text, ok, accept)
            if (ok) {
                try {
                    Repo.tasks.refresh()  // новый срок — сразу в напоминания
                } catch (_: Exception) {
                }
            }
            done.finish()
        }
    }
}
