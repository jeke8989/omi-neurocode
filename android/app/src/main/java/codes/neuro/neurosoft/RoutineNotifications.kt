package codes.neuro.neurosoft

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import codes.neuro.neurosoft.net.Api

/** Ответ задания по расписанию («⏰ Сводка дня: …») — уведомлением и в колокольчик, нажатие открывает чат. */
object RoutineNotifications {
    private const val CHANNEL = "routines"

    suspend fun check(context: Context) {
        val prefs = Core.prefs
        val seen = prefs.routineSeenRun
        val items = try {
            Api.routineResults(maxOf(seen, 0))
        } catch (_: Exception) {
            return
        }
        if (items.isEmpty()) return
        if (seen < 0) {  // первый запуск новой версии — старые ответы не показываем
            prefs.routineSeenRun = items.maxOf { it.runId }
            return
        }
        val r = Core.res()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, r.getString(R.string.routines_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
        for (it in items) {
            val title = it.question.removePrefix("⏰").substringBefore(":").trim().ifBlank { r.getString(R.string.routines_title) }
            val text = it.answer.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1").replace("**", "").trim()
            val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java).putExtra("open", "chat"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_neuro)
                .setContentTitle("⏰ $title")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            try {
                nm.notify(3000 + (it.runId % 1000).toInt(), n)
            } catch (_: SecurityException) {
            }
            Inbox.add("schedule", "schedule:${it.runId}", "⏰ $title", text.take(500), "chat")
        }
        prefs.routineSeenRun = items.maxOf { it.runId }
    }
}
