package codes.neuro.neurosoft

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.TaskItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Напоминания о задачах со сроком: в назначенное время — уведомление на телефоне и вибрация кулона.
 * Будильники ставит телефон (работает и без сети), список задач приходит с сервера.
 */
object Reminders {
    const val CHANNEL = "reminders"
    private const val ACTION = "codes.neuro.neurosoft.REMINDER"
    private const val WEEK = 7 * 24 * 3600_000L

    fun channel(context: Context) {
        val r = Core.res()
        val ch = NotificationChannel(CHANNEL, r.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH)
        ch.description = r.getString(R.string.reminders_channel_desc)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    // напоминаний на одно обещание несколько: в срок, утром в день срока, вечером накануне
    private const val AT_DUE = 0
    private const val MORNING = 1
    private const val EVENING_BEFORE = 2

    private fun intent(context: Context, key: Long, t: TaskItem? = null, stage: Int = 0) =
        PendingIntent.getBroadcast(
            context, (key % Int.MAX_VALUE).toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", key / 4).putExtra("stage", stage)
                .putExtra("text", t?.text ?: "").putExtra("kind", t?.kind ?: "mine").putExtra("mate", t?.mate ?: ""),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun local(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())

    /** Когда напомнить об обещании: (этап, время). */
    private fun stages(t: TaskItem, due: Long, now: Long): List<Pair<Int, Long>> {
        val out = mutableListOf(AT_DUE to due)
        if (t.theirs || t.kind == "reminder") return out
        val d = local(due)
        val morning = d.toLocalDate().atTime(9, 0).atZone(d.zone).toInstant().toEpochMilli()
        if (d.toLocalTime().hour >= 11 && morning > now) out += MORNING to morning
        val evening = d.toLocalDate().minusDays(1).atTime(19, 0).atZone(d.zone).toInstant().toEpochMilli()
        if (evening > now && due - evening > 12 * 3600_000L) out += EVENING_BEFORE to evening
        return out
    }

    /** Поставить будильники по открытым обещаниям со сроком (на неделю вперёд), снять лишние. */
    fun schedule(context: Context, tasks: List<TaskItem>) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val wanted = ArrayList<Triple<Long, Long, Pair<TaskItem, Int>>>()
        for (t in tasks) {
            if (t.status != "open") continue
            val due = t.dueMillis() ?: continue
            if (due !in (now - 60_000)..(now + WEEK)) continue
            for ((stage, at) in stages(t, due, now)) wanted += Triple(t.id * 4 + stage, at, t to stage)
        }
        val before = Core.prefs.reminders
        val after = HashMap<Long, Long>()
        for ((key, at, ts) in wanted) {
            if (before[key] != at) {
                try {
                    // без точных будильников (разрешение не нужно): Android может сдвинуть на несколько минут
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.coerceAtLeast(now + 5_000),
                        intent(context, key, ts.first, ts.second))
                } catch (e: Exception) {
                    Log.w("Mnemo", "cannot set reminder", e)
                    continue
                }
            }
            after[key] = at
        }
        for (key in before.keys - after.keys) alarms.cancel(intent(context, key))
        Core.prefs.reminders = after
    }

    fun show(context: Context, id: Long, text: String, stage: Int = AT_DUE, kind: String = "mine", mate: String = "") {
        channel(context)
        val r = Core.res()
        val theirs = kind == "waiting"
        val title = when {
            stage == EVENING_BEFORE -> r.getString(R.string.promise_due_tomorrow)
            stage == MORNING -> r.getString(R.string.promise_due_today)
            kind == "reminder" -> r.getString(R.string.reminder_title)
            theirs -> r.getString(R.string.promise_due_theirs, mate.ifBlank { r.getString(R.string.promise_someone) })
            else -> r.getString(R.string.promise_due_now)
        }
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .putExtra("open", "tasks"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (theirs && stage == AT_DUE) {  // «Иван обещал договор — сегодня срок»: напомнить ему готовым сообщением
            val nudge = PendingIntent.getActivity(context, 5000 + (id % 100_000).toInt(), Intent(context, MainActivity::class.java)
                .putExtra("open", "nudge:$id"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, r.getString(R.string.promise_nudge), nudge)
        }
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(1000 + ((id * 4 + stage) % 100_000).toInt(), b.build())
        } catch (_: SecurityException) {
        }
        if (stage == AT_DUE) Core.reminderBuzz()
        Core.prefs.reminders = Core.prefs.reminders - (id * 4 + stage)
        Inbox.add("reminder", "reminder:$id:$stage", title, text, "tasks", id)
    }
}

/**
 * Утренняя сводка: после 7:30 — одно уведомление с итогами вчерашнего дня (ночью сервер собрал события,
 * перепроверил задачи и написал дневник). Нажатие открывает день.
 */
object Morning {
    const val CHANNEL = "summary"

    suspend fun check(context: Context) {
        val prefs = Core.prefs
        if (!prefs.morningSummary || !prefs.serverConfigured) return
        val now = LocalTime.now()
        if (now.isBefore(LocalTime.of(7, 30)) || now.isAfter(LocalTime.of(21, 0))) return
        val yesterday = LocalDate.now().minusDays(1).toString()
        if (prefs.morningShown == yesterday) return
        val d = try {
            Api.diary().days.firstOrNull { it.day == yesterday }
        } catch (e: Exception) {
            return
        } ?: return
        if (d.status != "done" || d.title.isNullOrBlank()) return
        val r = Core.res()
        val ch = NotificationChannel(CHANNEL, r.getString(R.string.morning_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = r.getString(R.string.morning_channel_desc)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(ch)
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java)
            .putExtra("open", "day:$yesterday"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = d.summary?.trim().orEmpty()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setSubText(r.getString(R.string.morning_sub))
            .setContentTitle(listOfNotNull(d.mood?.takeIf { it.length <= 4 }, d.title).joinToString(" "))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(900, n)
        } catch (_: SecurityException) {
        }
        Inbox.add("morning", "morning:$yesterday", listOfNotNull(d.mood?.takeIf { it.length <= 4 }, d.title).joinToString(" "),
            text, "day:$yesterday")
        prefs.morningShown = yesterday
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        Reminders.show(context, intent.getLongExtra("id", 0), intent.getStringExtra("text").orEmpty(),
            intent.getIntExtra("stage", 0), intent.getStringExtra("kind") ?: "mine", intent.getStringExtra("mate").orEmpty())
    }
}
