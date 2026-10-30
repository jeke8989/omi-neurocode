package codes.neuro.neurosoft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("neurosoft", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(value) = sp.edit().putString("server_url", normalizeUrl(value)).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value.trim()).apply()

    var deviceAddress: String?
        get() = sp.getString("device_address", null)
        set(value) = sp.edit().putString("device_address", value).apply()

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(value) = sp.edit().putString("device_name", value).apply()

    /** Включать запись автоматически после перезагрузки телефона. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(value) = sp.edit().putBoolean("auto_start", value).apply()

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

    /** Дневник: автоматически отправлять фото дня с телефона (кандидаты; в ленту попадут только подходящие). */
    var photoAutoImport: Boolean
        get() = sp.getBoolean("photo_auto", false)
        set(value) = sp.edit().putBoolean("photo_auto", value).apply()

    var photoSyncedUntil: Long
        get() = sp.getLong("photo_synced_until", 0L)
        set(value) = sp.edit().putLong("photo_synced_until", value).apply()

    companion object {
        fun normalizeUrl(value: String): String {
            var s = value.trim().trimEnd('/')
            if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s
        }
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
