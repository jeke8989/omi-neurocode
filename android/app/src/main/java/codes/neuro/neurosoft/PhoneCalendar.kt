package codes.neuro.neurosoft

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import codes.neuro.neurosoft.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Календарь телефона — Google, Samsung, Outlook, Яндекс: всё, что синхронизировано в телефон.
 * Подключение — одно разрешение. Телефон присылает на сервер события «2 недели назад — 2 месяца вперёд»
 * и выполняет изменения, о которых попросили ассистента (добавить, перенести, удалить встречу).
 */
object PhoneCalendar {
    private const val TAG = "Mnemo.Calendar"
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private const val DAY = 24 * 3600_000L
    private const val SYNC_EVERY = 10 * 60_000L
    private val lock = Mutex()
    @Volatile private var lastSync = 0L

    fun granted(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun active(context: Context): Boolean = Core.prefs.calendarOn && granted(context) && Core.prefs.serverConfigured

    /** Календари, куда можно добавлять встречи. */
    private fun calendars(context: Context): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.ACCOUNT_TYPE)
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection,
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                out += JSONObject().put("id", c.getLong(0)).put("name", c.getString(1) ?: "")
                    .put("account", c.getString(2) ?: "").put("primary", c.getInt(3) == 1)
                    .put("google", c.getString(5) == "com.google")
            }
        }
        return out
    }

    private fun defaultCalendar(context: Context): Long? {
        val list = calendars(context)
        return (list.firstOrNull { it.optBoolean("primary") && it.optBoolean("google") }
            ?: list.firstOrNull { it.optBoolean("primary") } ?: list.firstOrNull())?.optLong("id")
    }

    private fun attendees(context: Context, eventId: Long): List<String> {
        val out = ArrayList<String>()
        CalendarContract.Attendees.query(context.contentResolver, eventId,
            arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL))?.use { c ->
            while (c.moveToNext() && out.size < 15) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1)?.substringBefore('@')
                if (!name.isNullOrBlank()) out += name
            }
        }
        return out
    }

    private fun events(context: Context, from: Long, to: Long): JSONArray {
        val out = JSONArray()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val now = System.currentTimeMillis()
        context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.length() < 2000) {
                val id = c.getLong(0)
                val begin = c.getLong(1)
                val o = JSONObject().put("event_id", id).put("begin", begin).put("end", c.getLong(2))
                    .put("title", c.getString(3) ?: "").put("location", c.getString(4) ?: "")
                    .put("notes", (c.getString(5) ?: "").take(1000)).put("all_day", c.getInt(6) == 1)
                    .put("calendar_id", c.getLong(7)).put("calendar", c.getString(8) ?: "")
                // участники — для ближайших встреч (по ним итоги разговоров узнают имена)
                if (begin in (now - 2 * DAY)..(now + 14 * DAY)) {
                    val who = attendees(context, id)
                    if (who.isNotEmpty()) o.put("attendees", JSONArray(who))
                }
                out.put(o)
            }
        }
        return out
    }

    /** События окна -> сервер (не чаще раза в 10 минут, или сразу — force). */
    suspend fun sync(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!active(context)) return@withContext
        if (!force && System.currentTimeMillis() - lastSync < SYNC_EVERY) return@withContext
        lock.withLock {
            try {
                val now = System.currentTimeMillis()
                val from = now - 14 * DAY
                val to = now + 60 * DAY
                Api.calendarSync(JSONObject().put("from", from).put("to", to).put("events", events(context, from, to))
                    .put("calendars", JSONArray(calendars(context))))
                lastSync = now
            } catch (e: Exception) {
                Log.w(TAG, "calendar sync failed", e)
            }
        }
    }

    /** Изменения от ассистента: внести в календарь и сообщить серверу итог. */
    suspend fun runCommands(context: Context) = withContext(Dispatchers.IO) {
        if (!active(context)) return@withContext
        val commands = try {
            Api.calendarCommands()
        } catch (_: Exception) {
            return@withContext
        }
        if (commands.isEmpty()) return@withContext
        for (cmd in commands) {
            val (ok, result) = try {
                true to execute(context, cmd.optString("op"), cmd.optJSONObject("payload") ?: JSONObject())
            } catch (e: Exception) {
                Log.w(TAG, "calendar command failed", e)
                false to (e.message ?: e.javaClass.simpleName)
            }
            try {
                Api.calendarCommandDone(cmd.getLong("id"), ok, result)
            } catch (_: Exception) {
            }
        }
        sync(context, force = true)
    }

    /** Обещание -> встреча в календаре прямо с телефона. Возвращает id встречи. */
    fun addEvent(context: Context, title: String, begin: Long, end: Long, notes: String?): Long =
        execute(context, "create", JSONObject().put("title", title).put("begin", begin).put("end", end)
            .put("notes", notes ?: "")).toLong()

    /** Срок обещания поменялся — встреча переезжает вместе с ним. */
    fun moveEvent(context: Context, id: Long, begin: Long, end: Long, title: String? = null) {
        val p = JSONObject().put("event_id", id).put("begin", begin).put("end", end)
        title?.let { p.put("title", it) }
        execute(context, "update", p)
    }

    fun removeEvent(context: Context, id: Long) {
        try {
            execute(context, "delete", JSONObject().put("event_id", id))
        } catch (_: Exception) {
        }
    }

    /** Открыть встречу в приложении календаря. */
    fun open(context: Context, id: Long) {
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun execute(context: Context, op: String, p: JSONObject): String {
        val cr = context.contentResolver
        return when (op) {
            "create" -> {
                val calendar = p.optLong("calendar_id").takeIf { it > 0 && p.has("calendar_id") && !p.isNull("calendar_id") }
                    ?: defaultCalendar(context) ?: error("нет календаря, куда можно писать")
                val allDay = p.optBoolean("all_day")
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendar)
                    put(CalendarContract.Events.TITLE, p.optString("title"))
                    put(CalendarContract.Events.DTSTART, p.getLong("begin"))
                    put(CalendarContract.Events.DTEND, p.getLong("end"))
                    put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
                    p.optString("location").takeIf { it.isNotBlank() && it != "null" }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                    p.optString("notes").takeIf { it.isNotBlank() && it != "null" }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }
                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("календарь не принял встречу")
                val id = ContentUris.parseId(uri)
                // напоминание — всегда (по умолчанию за 15 минут, меняется в настройках календаря в Mnemo);
                // у события на весь день — накануне вечером
                cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, if (allDay) 5 * 60 else Core.prefs.calendarReminderMin)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
                "$id"
            }
            "update" -> {
                val id = p.getLong("event_id")
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
                val values = ContentValues()
                if (p.has("begin")) {
                    // перенос без нового конца — сохраняем длительность
                    var duration = 3600_000L
                    cr.query(uri, arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(1)) duration = c.getLong(1) - c.getLong(0)
                    }
                    values.put(CalendarContract.Events.DTSTART, p.getLong("begin"))
                    values.put(CalendarContract.Events.DTEND, if (p.has("end")) p.getLong("end") else p.getLong("begin") + duration)
                } else if (p.has("end")) {
                    values.put(CalendarContract.Events.DTEND, p.getLong("end"))
                }
                if (p.has("title")) values.put(CalendarContract.Events.TITLE, p.getString("title"))
                if (p.has("location")) values.put(CalendarContract.Events.EVENT_LOCATION, p.getString("location"))
                if (cr.update(uri, values, null, null) == 0) error("встреча не найдена")
                "$id"
            }
            "delete" -> {
                val id = p.getLong("event_id")
                if (cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) == 0) {
                    error("встреча не найдена")
                }
                "$id"
            }
            else -> error("неизвестная команда $op")
        }
    }
}
