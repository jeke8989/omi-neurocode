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
import codes.neuro.neurosoft.net.TaskItem
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

    private fun intent(context: Context, id: Long, text: String? = null) =
        PendingIntent.getBroadcast(
            context, (id % Int.MAX_VALUE).toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id).putExtra("text", text ?: ""),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Поставить будильники по открытым задачам со сроком (на неделю вперёд), снять лишние. */
    fun schedule(context: Context, tasks: List<TaskItem>) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val wanted = tasks.filter { it.status == "open" && it.dueAt != null }.mapNotNull { t ->
            val at = try {
                OffsetDateTime.parse(t.dueAt).toInstant().toEpochMilli()
            } catch (e: Exception) {
                return@mapNotNull null
            }
            if (at in (now - 60_000)..(now + WEEK)) Triple(t.id, at, t.text) else null
        }
        val before = Core.prefs.reminders
        val after = HashMap<Long, Long>()
        for ((id, at, text) in wanted) {
            if (before[id] != at) {
                try {
                    // без точных будильников (разрешение не нужно): Android может сдвинуть на несколько минут
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.coerceAtLeast(now + 5_000), intent(context, id, text))
                } catch (e: Exception) {
                    Log.w("Mnemo", "cannot set reminder", e)
                    continue
                }
            }
            after[id] = at
        }
        for (id in before.keys - after.keys) alarms.cancel(intent(context, id))
        Core.prefs.reminders = after
    }

    fun show(context: Context, id: Long, text: String) {
        channel(context)
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .putExtra("open", "tasks"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(Core.res().getString(R.string.reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(1000 + (id % 100_000).toInt(), n)
        } catch (_: SecurityException) {
        }
        Core.reminderBuzz()
        Core.prefs.reminders = Core.prefs.reminders - id
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        Reminders.show(context, intent.getLongExtra("id", 0), intent.getStringExtra("text").orEmpty())
    }
}
