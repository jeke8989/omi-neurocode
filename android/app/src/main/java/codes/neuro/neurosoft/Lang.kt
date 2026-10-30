package codes.neuro.neurosoft

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Язык интерфейса. Работает на всех версиях Android одинаково: активити и фоновые тексты
 * (уведомление, голос) берут строки из контекста с выбранной локалью.
 */
object Lang {
    /** Поддерживаемые языки (код, название на этом языке). */
    val supported = listOf("ru" to "Русский", "en" to "English", "tr" to "Türkçe", "es" to "Español")

    /** Язык системы, если он поддерживается, иначе английский. */
    fun systemCode(): String {
        val sys = LocaleList.getDefault()
        for (i in 0 until sys.size()) {
            val code = sys[i].language
            if (supported.any { it.first == code }) return code
        }
        return "en"
    }

    /** Код текущего языка с учётом выбора в приложении. */
    fun code(prefs: Prefs): String = prefs.language.takeIf { c -> supported.any { it.first == c } } ?: systemCode()

    fun locale(code: String): Locale = when (code) {
        "ru" -> Locale.forLanguageTag("ru-RU")
        "tr" -> Locale.forLanguageTag("tr-TR")
        "es" -> Locale.forLanguageTag("es-ES")
        else -> Locale.forLanguageTag("en-US")
    }

    /** Контекст с выбранным языком. */
    fun wrap(base: Context): Context {
        val prefs = Prefs(base)
        if (prefs.language.isBlank()) return base
        val locale = locale(code(prefs))
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
