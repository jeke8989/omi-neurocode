package codes.neuro.neurosoft

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import codes.neuro.neurosoft.net.TaskItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Вечером, после 21:00 — «что вы пообещали сегодня»: ваши обещания за день, чего ждёте от других,
 * что завтра к сроку и что просрочено. Один раз в день, нажатие открывает «Обещания».
 */
object EveningDigest {
    private const val CHANNEL = "evening"

    fun check(context: Context, tasks: List<TaskItem>) {
        val prefs = Core.prefs
        if (!prefs.eveningDigest) return
        val now = LocalTime.now()
        if (now.isBefore(LocalTime.of(21, 0)) || now.isAfter(LocalTime.of(23, 30))) return
        val today = LocalDate.now()
        if (prefs.eveningShown == today.toString()) return
        val zone = ZoneId.systemDefault()
        fun day(iso: String?): LocalDate? = try {
            iso?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDate() }
        } catch (_: Exception) {
            null
        }
        val live = tasks.filter { it.status == "open" || it.status == "done" }
        val mine = live.filter { day(it.createdAt) == today && !it.theirs && it.kind != "reminder" }
        val theirs = live.filter { day(it.createdAt) == today && it.theirs }
        val open = tasks.filter { it.status == "open" }
        val tomorrow = open.filter { day(it.dueAt) == today.plusDays(1) }
        val overdue = open.filter { (it.dueMillis() ?: Long.MAX_VALUE) < System.currentTimeMillis() }
        prefs.eveningShown = today.toString()
        if (mine.isEmpty() && theirs.isEmpty() && tomorrow.isEmpty() && overdue.isEmpty()) return
        val r = Core.res()
        fun list(items: List<TaskItem>) = items.take(4).joinToString("\n") { t ->
            "• " + t.text + (t.mate?.let { " — $it" } ?: "")
        } + if (items.size > 4) "\n• …" else ""
        val parts = listOfNotNull(
            mine.takeIf { it.isNotEmpty() }?.let { r.getString(R.string.evening_mine) + "\n" + list(it) },
            theirs.takeIf { it.isNotEmpty() }?.let { r.getString(R.string.evening_theirs) + "\n" + list(it) },
            tomorrow.takeIf { it.isNotEmpty() }?.let { r.getString(R.string.evening_tomorrow) + "\n" + list(it) },
            overdue.takeIf { it.isNotEmpty() }?.let { r.getString(R.string.evening_overdue) + "\n" + list(it) },
        )
        val text = parts.joinToString("\n\n")
        val title = r.getString(R.string.evening_title, mine.size)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, r.getString(R.string.evening_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java).putExtra("open", "tasks"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull { it.startsWith("•") }?.removePrefix("• ") ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(901, n)
        } catch (_: SecurityException) {
        }
        Inbox.add("evening", "evening:$today", title, text, "tasks")
    }
}
