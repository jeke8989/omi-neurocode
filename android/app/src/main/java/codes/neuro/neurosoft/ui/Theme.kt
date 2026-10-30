@file:OptIn(ExperimentalTextApi::class)

package codes.neuro.neurosoft.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import codes.neuro.neurosoft.R

/**
 * Токены Mnemo (см. DESIGN.md). Монохром + один акцент — «свет линзы».
 * Зелёного «всё хорошо» нет: нормальное состояние — ровный синий свет.
 */
@Immutable
data class Tokens(
    val fog: Color,       // фон экранов
    val paper: Color,     // карточки, группы строк
    val raised: Color,    // плитки на бумаге (эмодзи, поля ввода)
    val ink: Color,       // текст, главные кнопки
    val onInk: Color,     // текст на чернильной кнопке
    val graphite: Color,  // вторичный текст
    val faint: Color,     // третичный текст, подсказки
    val line: Color,      // разделители
    val lens: Color,      // свет линзы: живое состояние, ссылки
    val amber: Color,     // нет связи, очередь
    val red: Color,       // ошибки, удаление
    val dark: Boolean,
)

val LightTokens = Tokens(
    fog = Color(0xFFEEF0F4), paper = Color.White, raised = Color(0xFFF3F4F7), ink = Color.Black,
    onInk = Color.White, graphite = Color(0xFF6E727A), faint = Color(0xFFA3A7AF), line = Color(0xFFE2E4EA),
    lens = Color(0xFF3B7BFF), amber = Color(0xFFF2A33A), red = Color(0xFFE5484D), dark = false,
)

val DarkTokens = Tokens(
    fog = Color.Black, paper = Color(0xFF121316), raised = Color(0xFF1C1D21), ink = Color.White,
    onInk = Color.Black, graphite = Color(0xFF8E929A), faint = Color(0xFF5C6068), line = Color(0xFF24262B),
    lens = Color(0xFF5A8FFF), amber = Color(0xFFF5B451), red = Color(0xFFFF6369), dark = true,
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

object Mn {
    val c: Tokens
        @Composable get() = LocalTokens.current
}

// ======================================================================= шрифт Golos Text (вариативный)
private fun golos(weight: Int) = Font(
    R.font.golos,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Golos = FontFamily(golos(400), golos(500), golos(600), golos(700))

/** Шкала: вкладки и заголовки 32, секции 18, текст 16, вторичный 14/13. Цифры — табличные. */
object T {
    private val base = TextStyle(fontFamily = Golos, fontFeatureSettings = "tnum")
    val display = base.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val title = base.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val bar = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val section = base.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    val body = base.copy(fontSize = 16.sp, lineHeight = 22.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.Medium)
    val small = base.copy(fontSize = 14.sp, lineHeight = 19.sp)
    val smallStrong = small.copy(fontWeight = FontWeight.Medium)
    val caption = base.copy(fontSize = 13.sp, lineHeight = 17.sp)
    val tiny = base.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
    val number = base.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val emoji = TextStyle(fontSize = 22.sp, lineHeight = 26.sp)
}

private val MaterialType = Typography(
    headlineSmall = T.title, titleLarge = T.section, titleMedium = T.bar, bodyLarge = T.body,
    bodyMedium = T.small, bodySmall = T.caption, labelLarge = T.smallStrong, labelMedium = T.tiny, labelSmall = T.tiny,
)

@Composable
fun MnemoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkTokens else LightTokens
    val scheme = if (dark) {
        darkColorScheme(primary = c.ink, onPrimary = c.onInk, background = c.fog, surface = c.paper,
            onSurface = c.ink, onBackground = c.ink, surfaceVariant = c.raised, onSurfaceVariant = c.graphite,
            surfaceContainer = c.paper, surfaceContainerHigh = c.paper, surfaceContainerLow = c.paper,
            outline = c.line, error = c.red)
    } else {
        lightColorScheme(primary = c.ink, onPrimary = c.onInk, background = c.fog, surface = c.paper,
            onSurface = c.ink, onBackground = c.ink, surfaceVariant = c.raised, onSurfaceVariant = c.graphite,
            surfaceContainer = c.paper, surfaceContainerHigh = c.paper, surfaceContainerLow = c.paper,
            outline = c.line, error = c.red)
    }
    CompositionLocalProvider(LocalTokens provides c) {
        MaterialTheme(colorScheme = scheme, typography = MaterialType, content = content)
    }
}
