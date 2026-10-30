package codes.neuro.neurosoft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ======================================================================= цвета iOS
data class IosColors(
    val tint: Color, val green: Color, val orange: Color, val red: Color, val purple: Color,
    val teal: Color, val indigo: Color, val pink: Color, val yellow: Color, val gray: Color,
    val bg: Color, val cell: Color, val elevated: Color, val label: Color, val secondary: Color,
    val tertiary: Color, val separator: Color, val fill: Color, val bubble: Color, val bar: Color,
)

val LightIos = IosColors(
    tint = Color(0xFF007AFF), green = Color(0xFF34C759), orange = Color(0xFFFF9500), red = Color(0xFFFF3B30),
    purple = Color(0xFFAF52DE), teal = Color(0xFF30B0C7), indigo = Color(0xFF5856D6), pink = Color(0xFFFF2D55),
    yellow = Color(0xFFFFCC00), gray = Color(0xFF8E8E93),
    bg = Color(0xFFF2F2F7), cell = Color.White, elevated = Color.White, label = Color.Black,
    secondary = Color(0x993C3C43), tertiary = Color(0x4D3C3C43), separator = Color(0x4A3C3C43),
    fill = Color(0x1F767680), bubble = Color(0xFFE9E9EB), bar = Color(0xF5F9F9F9),
)

val DarkIos = IosColors(
    tint = Color(0xFF0A84FF), green = Color(0xFF30D158), orange = Color(0xFFFF9F0A), red = Color(0xFFFF453A),
    purple = Color(0xFFBF5AF2), teal = Color(0xFF40C8E0), indigo = Color(0xFF5E5CE6), pink = Color(0xFFFF375F),
    yellow = Color(0xFFFFD60A), gray = Color(0xFF8E8E93),
    bg = Color.Black, cell = Color(0xFF1C1C1E), elevated = Color(0xFF2C2C2E), label = Color.White,
    secondary = Color(0x99EBEBF5), tertiary = Color(0x4DEBEBF5), separator = Color(0x99545458),
    fill = Color(0x3D767680), bubble = Color(0xFF26252A), bar = Color(0xF0161618),
)

val LocalIos = staticCompositionLocalOf { LightIos }

object Ios {
    val colors: IosColors
        @Composable get() = LocalIos.current
}

private val IosTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NeuroTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkIos else LightIos
    val scheme = if (dark) {
        darkColorScheme(primary = c.tint, onPrimary = Color.White, background = c.bg, surface = c.cell,
            onSurface = c.label, onBackground = c.label, surfaceVariant = c.cell, onSurfaceVariant = c.secondary,
            outline = c.separator, error = c.red)
    } else {
        lightColorScheme(primary = c.tint, onPrimary = Color.White, background = c.bg, surface = c.cell,
            onSurface = c.label, onBackground = c.label, surfaceVariant = c.cell, onSurfaceVariant = c.secondary,
            outline = c.separator, error = c.red)
    }
    CompositionLocalProvider(LocalIos provides c) {
        MaterialTheme(colorScheme = scheme, typography = IosTypography, content = content)
    }
}

// ======================================================================= иконки (SF-подобные)
private fun icon(name: String, path: String, evenOdd: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(path),
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            fill = SolidColor(Color.Black),
        )
    }.build()

object NeuroIcons {
    val Waveform = icon("waveform", "M3 10h2v4H3zM7 6.5h2v11H7zM11 3h2v18h-2zM15 6.5h2v11h-2zM19 10h2v4h-2z")
    val Bubble = icon("bubble",
        "M6 3h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3h-7.5L6 21v-4a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z")
    val Book = icon("book",
        "M6 2.5h11.5a2 2 0 0 1 2 2v15a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2zM8 7v1.8h8V7zM8 11v1.8h6V11z",
        evenOdd = true)
    val Checklist = icon("checklist",
        "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm-1.2 13.6l6.3-6.3-1.4-1.4-4.9 4.9-2.3-2.3-1.4 1.4z",
        evenOdd = true)
    val Gear = icon("gear",
        "M10.3 2h3.4l.5 2.6c.6.2 1.2.5 1.7.9l2.5-.9 1.7 3-2 1.7a7 7 0 0 1 0 1.9l2 1.7-1.7 3-2.5-.9c-.5.4-1.1.7-1.7.9l-.5 2.6h-3.4l-.5-2.6c-.6-.2-1.2-.5-1.7-.9l-2.5.9-1.7-3 2-1.7a7 7 0 0 1 0-1.9l-2-1.7 1.7-3 2.5.9c.5-.4 1.1-.7 1.7-.9zM12 8.8a3.2 3.2 0 1 0 0 6.4 3.2 3.2 0 0 0 0-6.4z",
        evenOdd = true)
    val Mic = icon("mic",
        "M12 2.5a3.5 3.5 0 0 1 3.5 3.5v5.5a3.5 3.5 0 0 1-7 0V6A3.5 3.5 0 0 1 12 2.5zM5 11h2a5 5 0 0 0 10 0h2a7 7 0 0 1-6 6.9V21h-2v-3.1A7 7 0 0 1 5 11z")
    val Sparkles = icon("sparkles",
        "M10 2l1.8 5.2L17 9l-5.2 1.8L10 16l-1.8-5.2L3 9l5.2-1.8zM18 13l.9 2.1L21 16l-2.1.9L18 19l-.9-2.1L15 16l2.1-.9z")
    val ArrowUp = icon("arrow_up", "M12 3.5l7 7-1.5 1.5-4.4-4.4V21h-2.2V7.6L6.5 12 5 10.5z")
    val Photo = icon("photo",
        "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm1 13h14l-4.5-6-3.5 4.5-2.5-3zM8 7.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z",
        evenOdd = true)
    val Location = icon("location",
        "M12 2a7 7 0 0 1 7 7c0 5-7 13-7 13S5 14 5 9a7 7 0 0 1 7-7zm0 4.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z",
        evenOdd = true)
    val Puzzle = icon("puzzle",
        "M9 3a2 2 0 0 1 4 0v1h4a1 1 0 0 1 1 1v4h1a2 2 0 0 1 0 4h-1v5a1 1 0 0 1-1 1h-4v-1a2 2 0 0 0-4 0v1H5a1 1 0 0 1-1-1v-4h1a2 2 0 0 0 0-4H4V5a1 1 0 0 1 1-1h4z")
    val Server = icon("server",
        "M4 3h16a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zm0 10h16a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1zM6 6v2h2V6zm0 10v2h2v-2z",
        evenOdd = true)
    val Brain = icon("brain",
        "M9 3a3 3 0 0 1 3 1.5A3 3 0 0 1 15 3a3 3 0 0 1 3 3 3 3 0 0 1 2 2.8 3 3 0 0 1-1 2.2 3 3 0 0 1 1 2.2A3 3 0 0 1 18 16a3 3 0 0 1-3 3 3 3 0 0 1-3-1.5A3 3 0 0 1 9 19a3 3 0 0 1-3-3 3 3 0 0 1-2-2.8 3 3 0 0 1 1-2.2 3 3 0 0 1-1-2.2A3 3 0 0 1 6 6a3 3 0 0 1 3-3z")
    val Battery = icon("battery",
        "M3 7h15a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zm17 3h1.5v4H20z")
    val Speaker = icon("speaker", "M4 9h4l5-4v14l-5-4H4zM16 8.5a5 5 0 0 1 0 7l-1.4-1.4a3 3 0 0 0 0-4.2z")
}

// ======================================================================= базовые элементы
@Composable
fun LargeTitle(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) {
                Text(subtitle.uppercase(), style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondary)
            }
            Text(title, style = MaterialTheme.typography.displaySmall, color = Ios.colors.label)
        }
        trailing()
    }
}

@Composable
fun NavBar(title: String, onBack: (() -> Unit)?, backLabel: String = "Назад",
           trailing: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp)) {
        if (onBack != null) {
            Row(
                Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Ios.colors.tint,
                    modifier = Modifier.size(28.dp))
                Text(backLabel, color = Ios.colors.tint, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ios.colors.label, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp))
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

class SectionScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** Сгруппированная секция как в «Настройках» iOS: заголовок, белые строки с разделителями, подпись. */
@Composable
fun Section(header: String? = null, footer: String? = null, build: SectionScope.() -> Unit) {
    val scope = SectionScope().apply(build)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(header.uppercase(), style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary,
                modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 7.dp))
        } else {
            Spacer(Modifier.height(16.dp))
        }
        if (scope.rows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ios.colors.cell)) {
                scope.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp,
                        color = Ios.colors.separator)
                    row()
                }
            }
        }
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector? = null, color: Color, emoji: String? = null, size: Int = 29) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.24f).dp))
            .background(if (emoji != null) Ios.colors.fill else color),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji != null) Text(emoji, fontSize = (size * 0.55f).sp)
        else if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.62f).dp))
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = Ios.colors.tint,
    emoji: String? = null,
    chevron: Boolean = false,
    titleColor: Color? = null,
    maxSubtitleLines: Int = 2,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null || emoji != null) {
            IconBadge(icon, iconColor, emoji)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor ?: Ios.colors.label)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary,
                    maxLines = maxSubtitleLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = Ios.colors.secondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 180.dp))
        }
        trailing?.invoke()
        if (chevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ios.colors.tertiary,
                modifier = Modifier.padding(start = 4.dp).size(22.dp))
        }
    }
}

@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Ios.colors.green, checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent, uncheckedTrackColor = Ios.colors.fill,
            uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null,
              icon: ImageVector? = null, iconColor: Color = Ios.colors.tint, enabled: Boolean = true) {
    ListRow(title, subtitle = subtitle, icon = icon, iconColor = iconColor) {
        IosSwitch(checked, onChange, enabled)
    }
}

/** Поле ввода внутри строки секции. */
@Composable
fun FieldRow(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "",
             secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ios.colors.label,
            modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Ios.colors.tertiary, maxLines = 1)
            }
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ios.colors.label),
                cursorBrush = SolidColor(Ios.colors.tint),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (secret) {
            Text(if (show) "Скрыть" else "Показать", color = Ios.colors.tint, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp).clickable { show = !show })
        }
    }
}

@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(9.dp)).background(Ios.colors.fill).padding(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                Modifier.weight(1f).height(30.dp)
                    .then(if (isSel) Modifier.shadow(2.dp, RoundedCornerShape(7.dp)).clip(RoundedCornerShape(7.dp))
                        .background(if (Ios.colors.bg == Color.Black) Color(0xFF636366) else Color.White) else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium, color = Ios.colors.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ios.colors.tint,
               filled: Boolean = true, enabled: Boolean = true, small: Boolean = false) {
    Box(
        modifier.height(if (small) 34.dp else 50.dp).clip(RoundedCornerShape(if (small) 17.dp else 14.dp))
            .background(if (filled) color.copy(alpha = if (enabled) 1f else 0.4f) else color.copy(alpha = 0.13f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.White else color,
            style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun RoundCheck(checked: Boolean, onClick: () -> Unit, color: Color = Ios.colors.tint) {
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .then(if (checked) Modifier.background(color) else Modifier.border(1.5.dp, Ios.colors.tertiary, CircleShape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun Capsule(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun StatusDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun Spinner(size: Int = 18) {
    CircularProgressIndicator(Modifier.size(size.dp), strokeWidth = 2.dp, color = Ios.colors.secondary)
}

// ======================================================================= загрузка
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

@Composable
fun <T> LoadContent(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        is Load.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
            Spinner(28)
        }
        is Load.Err -> Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.message, textAlign = TextAlign.Center, color = Ios.colors.secondary,
                style = MaterialTheme.typography.bodyLarge)
            PillButton("Повторить", onRetry, filled = false, small = true)
        }
        is Load.Ok -> content(state.data)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Ios.colors.tertiary, modifier = Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ios.colors.label, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ios.colors.secondary, textAlign = TextAlign.Center)
    }
}

// ======================================================================= даты и числа
private val ru: Locale = Locale.forLanguageTag("ru")
private val hm = DateTimeFormatter.ofPattern("HH:mm", ru)
private val hms = DateTimeFormatter.ofPattern("HH:mm:ss", ru)
private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", ru)
private val dayMonthTime = DateTimeFormatter.ofPattern("d MMMM, HH:mm", ru)
private val weekday = DateTimeFormatter.ofPattern("EEEE", ru)

private fun local(iso: String) = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())

fun formatDateTime(iso: String): String = try {
    val t = local(iso)
    val today = LocalDate.now()
    when (t.toLocalDate()) {
        today -> "сегодня, " + t.format(hm)
        today.minusDays(1) -> "вчера, " + t.format(hm)
        else -> t.format(dayMonthTime)
    }
} catch (e: Exception) {
    iso
}

fun formatTime(iso: String): String = try {
    local(iso).format(hms)
} catch (e: Exception) {
    ""
}

fun formatShortTime(iso: String): String = try {
    local(iso).format(hm)
} catch (e: Exception) {
    ""
}

fun isToday(iso: String): Boolean = try {
    local(iso).toLocalDate() == LocalDate.now()
} catch (e: Exception) {
    false
}

/** «Сегодня», «Вчера» или «28 октября, среда». */
fun dayTitle(isoDate: String): String = try {
    val d = LocalDate.parse(isoDate.take(10))
    val today = LocalDate.now()
    when (d) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> d.format(dayMonth) + ", " + d.format(weekday)
    }
} catch (e: Exception) {
    isoDate
}

fun agoText(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    val s = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        s < 5 -> "только что"
        s < 60 -> "$s с назад"
        s < 3600 -> "${s / 60} мин назад"
        else -> "${s / 3600} ч назад"
    }
}

fun bytesText(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КБ"
    else -> String.format(Locale.US, "%.1f МБ", b / 1024.0 / 1024.0)
}

fun priceText(v: Double?): String = when {
    v == null -> "—"
    v == 0.0 -> "бесплатно"
    v < 0.1 -> String.format(Locale.US, "$%.3f", v)
    else -> String.format(Locale.US, "$%.2f", v)
}
