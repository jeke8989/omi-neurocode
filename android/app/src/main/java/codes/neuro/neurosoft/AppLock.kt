package codes.neuro.neurosoft

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Защита приложения: ПИН-код и (по желанию) отпечаток или лицо. Блокируется при запуске и когда
 * приложение пробыло в фоне дольше выбранного времени. Запись с кулона и уведомления работают как обычно —
 * закрыт только экран приложения. ПИН хранится только как хеш (PBKDF2 с солью).
 */
object AppLock {
    private const val ITERATIONS = 20_000
    private const val MAX_TRIES = 5

    val locked = mutableStateOf(false)
    /** Защита включена (для настроек: меняется сразу после установки или отключения ПИН-кода). */
    val active = mutableStateOf(false)
    /** До какого момента (elapsedRealtime) ввод заблокирован после 5 неверных попыток. */
    val waitUntil = mutableStateOf(0L)
    private var stoppedAt = 0L
    private var started = false

    val enabled: Boolean get() = Core.prefs.pinHash.isNotEmpty()

    /** Первый показ экрана после запуска процесса — всегда под замком. */
    fun onCreate() {
        active.value = enabled
        if (!started) {
            started = true
            locked.value = enabled
        }
    }

    fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
    }

    fun onStart() {
        if (!enabled || stoppedAt == 0L) return
        val away = SystemClock.elapsedRealtime() - stoppedAt
        if (away >= Core.prefs.lockAfterSec * 1000L) locked.value = true
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return key.joinToString("") { "%02x".format(it) }
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Core.prefs.pinSalt = salt.joinToString("") { "%02x".format(it) }
        Core.prefs.pinHash = hash(pin, salt)
        Core.prefs.pinTries = 0
        locked.value = false
        active.value = true
    }

    fun disable() {
        Core.prefs.pinHash = ""
        Core.prefs.pinSalt = ""
        Core.prefs.lockBiometric = false
        locked.value = false
        active.value = false
    }

    /** Проверить ПИН. После 5 ошибок подряд — пауза 30 секунд (и дальше вдвое дольше). */
    fun check(pin: String): Boolean {
        val prefs = Core.prefs
        if (SystemClock.elapsedRealtime() < waitUntil.value) return false
        val salt = prefs.pinSalt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ok = MessageDigest.isEqual(hash(pin, salt).toByteArray(), prefs.pinHash.toByteArray())
        if (ok) {
            prefs.pinTries = 0
            locked.value = false
        } else {
            val tries = prefs.pinTries + 1
            prefs.pinTries = tries
            if (tries >= MAX_TRIES) {
                val rounds = (tries - MAX_TRIES) / MAX_TRIES
                waitUntil.value = SystemClock.elapsedRealtime() + 30_000L * (1L shl rounds.coerceAtMost(5))
            }
        }
        return ok
    }

    fun biometricAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK,
        ) == BiometricManager.BIOMETRIC_SUCCESS

    /** Системное окно «приложите палец»; успех — разблокировать. */
    fun biometric(activity: FragmentActivity, onSuccess: () -> Unit = {}) {
        val r = Core.res()
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Core.prefs.pinTries = 0
                    locked.value = false
                    onSuccess()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(r.getString(R.string.lock_biometric_title))
            .setSubtitle(r.getString(R.string.lock_biometric_sub))
            .setNegativeButtonText(r.getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        try {
            prompt.authenticate(info)
        } catch (_: Exception) {
        }
    }
}
