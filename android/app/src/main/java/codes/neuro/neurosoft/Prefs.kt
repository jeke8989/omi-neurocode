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

    /** Сервер и ключ зашиты в сборку (neurosoft.properties) — пользователь их не вводит. */
    val serverUrl: String get() = BuildConfig.SERVER_URL.trim().trimEnd('/')
    val token: String get() = BuildConfig.API_TOKEN.trim()
    val serverConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

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

    /** Реагировать на кнопку кулона. */
    var buttonActions: Boolean
        get() = sp.getBoolean("button_actions", true)
        set(value) = sp.edit().putBoolean("button_actions", value).apply()

    /** Двойное нажатие: remember — «запомни это», pause — пауза/продолжить запись, off — ничего. */
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
