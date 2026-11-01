package codes.neuro.neurosoft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Отрезок, когда кулон был без связи (для «Недавно» на экране устройства). */
data class Outage(val from: Long, val to: Long)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("neurosoft", Context.MODE_PRIVATE)

    /**
     * Сервер: адрес по умолчанию зашит в сборку, ключ доступа приложение получает один раз по коду
     * подключения (репозиторий публичный — ключ в APK не кладём). Ключ из сборки — только для своих
     * частных сборок (переменная MNEMO_API_TOKEN).
     */
    val serverUrl: String get() = (sp.getString("server_url", null) ?: BuildConfig.SERVER_URL).trim().trimEnd('/')
    val token: String get() = (sp.getString("api_token", null) ?: BuildConfig.API_TOKEN).trim()
    val serverConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
    val defaultServerUrl: String get() = BuildConfig.SERVER_URL.trim().trimEnd('/')

    fun setServer(url: String, token: String) {
        sp.edit().putString("server_url", url.trim().trimEnd('/')).putString("api_token", token.trim()).apply()
    }

    var deviceAddress: String?
        get() = sp.getString("device_address", null)
        set(value) = sp.edit().putString("device_address", value).apply()

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(value) = sp.edit().putString("device_name", value).apply()

    /** Первый запуск пройден (разрешения, кулон найден). */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", deviceAddress != null)
        set(value) = sp.edit().putBoolean("onboarded", value).apply()

    /** Язык интерфейса: "" — как в системе, иначе ru / en / tr / es. */
    var language: String
        get() = sp.getString("language", "") ?: ""
        set(value) = sp.edit().putString("language", value).apply()

    /** Тема: system — как в телефоне, light — светлая, dark — тёмная. */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(value) = sp.edit().putString("theme", value).apply()

    /** Включать запись автоматически после перезагрузки телефона. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(value) = sp.edit().putBoolean("auto_start", value).apply()

    /** Экономия батареи: когда приложение свёрнуто, записи уходят на сервер пачками. */
    var batterySaver: Boolean
        get() = sp.getBoolean("battery_saver", true)
        set(value) = sp.edit().putBoolean("battery_saver", value).apply()

    /** Пауза записи: звук не сохраняется, но кнопка-вопрос работает. */
    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(value) = sp.edit().putBoolean("paused", value).apply()

    /** Кулон давно не на связи: запись и её уведомление выключены, Bluetooth ждёт кулон сам (PendantWatch). */
    var standby: Boolean
        get() = sp.getBoolean("standby", false)
        set(value) = sp.edit().putBoolean("standby", value).apply()

    /** Реагировать на кнопку кулона. */
    var buttonActions: Boolean
        get() = sp.getBoolean("button_actions", true)
        set(value) = sp.edit().putBoolean("button_actions", value).apply()

    /** Режим чата: auto | fast | tools | deep — агент Mnemo, claude — Claude Code на сервере. */
    var chatLevel: String
        get() = sp.getString("chat_level", "auto") ?: "auto"
        set(value) = sp.edit().putString("chat_level", value).apply()

    /** Записи кулона без телефона: когда забирали в последний раз и сколько секунд звука забрали всего. */
    var offlineSyncAt: Long
        get() = sp.getLong("offline_sync_at", 0L)
        set(value) = sp.edit().putLong("offline_sync_at", value).apply()

    var offlineRecoveredSec: Long
        get() = sp.getLong("offline_recovered_sec", 0L)
        set(value) = sp.edit().putLong("offline_recovered_sec", value).apply()

    /** Уведомления внутри приложения (JSON, см. Inbox) и когда их смотрели в последний раз. */
    var inbox: String
        get() = sp.getString("inbox", "[]") ?: "[]"
        set(value) = sp.edit().putString("inbox", value).apply()

    var inboxSeenAt: Long
        get() = sp.getLong("inbox_seen_at", 0L)
        set(value) = sp.edit().putLong("inbox_seen_at", value).apply()

    /** Агент в чате: mnemo | claude | gpt. */
    var chatAgent: String
        get() = sp.getString("chat_agent", null) ?: if (chatLevel.startsWith("claude")) "claude" else "mnemo"
        set(value) = sp.edit().putString("chat_agent", value).apply()

    /** Напоминание у встреч, которые создаёт Mnemo: за сколько минут. */
    var calendarReminderMin: Int
        get() = sp.getInt("calendar_reminder_min", 15)
        set(value) = sp.edit().putInt("calendar_reminder_min", value).apply()

    /** Длительность встречи из обещания по умолчанию (минуты). */
    var calendarDefaultMin: Int
        get() = sp.getInt("calendar_default_min", 30)
        set(value) = sp.edit().putInt("calendar_default_min", value).apply()

    /** Вечером в 21:00 — «что вы пообещали сегодня». */
    var eveningDigest: Boolean
        get() = sp.getBoolean("evening_digest", true)
        set(value) = sp.edit().putBoolean("evening_digest", value).apply()

    var eveningShown: String?
        get() = sp.getString("evening_shown", null)
        set(value) = sp.edit().putString("evening_shown", value).apply()

    /** Календарь телефона подключён к Mnemo (события уходят на сервер, ассистент может менять встречи). */
    var calendarOn: Boolean
        get() = sp.getBoolean("calendar_on", false)
        set(value) = sp.edit().putBoolean("calendar_on", value).apply()

    /** Последний ответ задания по расписанию, о котором уже уведомили (номер запуска агента). */
    var routineSeenRun: Long
        get() = sp.getLong("routine_seen_run", -1L)
        set(value) = sp.edit().putLong("routine_seen_run", value).apply()

    /** Знакомство, ещё не отправленное на сервер (сервер не был подключён): JSON {name, about, goals, from, to}. */
    var pendingProfile: String?
        get() = sp.getString("pending_profile", null)
        set(value) = sp.edit().putString("pending_profile", value).apply()

    /** Двойное нажатие: remember — важный разговор, pause — пауза/продолжить запись, off — ничего. */
    var doubleTap: String
        get() = sp.getString("double_tap", "remember") ?: "remember"
        set(value) = sp.edit().putString("double_tap", value).apply()

    /** Ответ голосом: always — всегда, headphones — только когда подключены наушники, off — не озвучивать. */
    var voiceReply: String
        get() = sp.getString("voice_reply", "headphones") ?: "headphones"
        set(value) = sp.edit().putString("voice_reply", value).apply()

    /** Звуковые сигналы кнопки («слушаю», «принял»). */
    var earcons: Boolean
        get() = sp.getBoolean("earcons", true)
        set(value) = sp.edit().putBoolean("earcons", value).apply()

    /** Голос ответов: f1 — Mnemo, женский (по умолчанию), m1 — Mnemo, мужской (оба — нейроголос сервера),
     *  system — синтезатор речи телефона. */
    var ttsVoice: String
        get() = sp.getString("tts_voice", "f1") ?: "f1"
        set(value) = sp.edit().putString("tts_voice", value).apply()

    /** Защита приложения: хеш ПИН-кода (PBKDF2) и соль; пусто — защиты нет. */
    var pinHash: String
        get() = sp.getString("pin_hash", "") ?: ""
        set(value) = sp.edit().putString("pin_hash", value).apply()

    var pinSalt: String
        get() = sp.getString("pin_salt", "") ?: ""
        set(value) = sp.edit().putString("pin_salt", value).apply()

    var pinTries: Int
        get() = sp.getInt("pin_tries", 0)
        set(value) = sp.edit().putInt("pin_tries", value).apply()

    /** Входить по отпечатку или лицу (ПИН — запасной). */
    var lockBiometric: Boolean
        get() = sp.getBoolean("lock_biometric", false)
        set(value) = sp.edit().putBoolean("lock_biometric", value).apply()

    /** Через сколько секунд в фоне приложение закрывается: 0 — сразу. */
    var lockAfterSec: Int
        get() = sp.getInt("lock_after", 60)
        set(value) = sp.edit().putInt("lock_after", value).apply()

    /** «Это задача?» — спрашивать в уведомлении про задачи, в которых ассистент не уверен. */
    var taskPrompts: Boolean
        get() = sp.getBoolean("task_prompts", true)
        set(value) = sp.edit().putBoolean("task_prompts", value).apply()

    /** Про какие предложенные задачи уже спросили в уведомлении. */
    var askedTasks: Set<Long>
        get() = (sp.getString("asked_tasks", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = sp.edit().putString("asked_tasks", value.toList().takeLast(200).joinToString(",")).apply()

    /** Утром — уведомление с итогами вчерашнего дня. */
    var morningSummary: Boolean
        get() = sp.getBoolean("morning_summary", true)
        set(value) = sp.edit().putBoolean("morning_summary", value).apply()

    /** За какой день утренняя сводка уже показана (YYYY-MM-DD). */
    var morningShown: String
        get() = sp.getString("morning_shown", "") ?: ""
        set(value) = sp.edit().putString("morning_shown", value).apply()

    /** Задачи, по которым уже стоит напоминание: id -> время (мс). */
    var reminders: Map<Long, Long>
        get() = try {
            val o = JSONObject(sp.getString("reminders", "{}") ?: "{}")
            o.keys().asSequence().associate { it.toLong() to o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(value) {
            val o = JSONObject()
            value.forEach { (k, v) -> o.put(k.toString(), v) }
            sp.edit().putString("reminders", o.toString()).apply()
        }

    var voiceRate: Float
        get() = sp.getFloat("voice_rate", 1.05f)
        set(value) = sp.edit().putFloat("voice_rate", value).apply()

    /** Голосом подтверждать «запомни это». */
    var confirmRemember: Boolean
        get() = sp.getBoolean("confirm_remember", true)
        set(value) = sp.edit().putBoolean("confirm_remember", value).apply()

    /** Дневник: писать геолокацию во время записи. */
    var locationEnabled: Boolean
        get() = sp.getBoolean("location", false)
        set(value) = sp.edit().putBoolean("location", value).apply()

    /** Дневник: автоматически отправлять фото дня с телефона (в ленту попадут только подходящие). */
    var photoAutoImport: Boolean
        get() = sp.getBoolean("photo_auto", false)
        set(value) = sp.edit().putBoolean("photo_auto", value).apply()

    /** «Это вы?» — пользователь ответил «нет» для этого голоса, больше не спрашиваем. */
    var ownerDismissed: Long
        get() = sp.getLong("owner_dismissed", 0L)
        set(value) = sp.edit().putLong("owner_dismissed", value).apply()

    var photoSyncedUntil: Long
        get() = sp.getLong("photo_synced_until", 0L)
        set(value) = sp.edit().putLong("photo_synced_until", value).apply()

    /** Сколько миллисекунд звука принято сегодня (день — по часам телефона). */
    fun heardToday(today: String): Long =
        if (sp.getString("heard_day", "") == today) sp.getLong("heard_ms", 0L) else 0L

    fun addHeard(today: String, ms: Long) {
        val base = heardToday(today)
        sp.edit().putString("heard_day", today).putLong("heard_ms", base + ms).apply()
    }

    var outages: List<Outage>
        get() = try {
            val a = JSONArray(sp.getString("outages", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Outage(o.getLong("from"), o.getLong("to")) } }
        } catch (e: Exception) {
            emptyList()
        }
        set(value) {
            val a = JSONArray()
            value.takeLast(20).forEach { a.put(JSONObject().put("from", it.from).put("to", it.to)) }
            sp.edit().putString("outages", a.toString()).apply()
        }
}

object Permissions {
    fun required(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun location(): Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun photos(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun granted(context: Context, perms: Array<String>): Boolean = perms.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Без уведомлений работать можно, без Bluetooth — нет. */
    fun bluetoothGranted(context: Context): Boolean =
        missing(context).none { it != Manifest.permission.POST_NOTIFICATIONS }
}
