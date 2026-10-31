package codes.neuro.neurosoft.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.ApiException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ======================================================================= текст
@Composable
fun Txt(
    text: String,
    style: TextStyle = T.body,
    color: Color = Mn.c.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    Text(text, modifier = modifier, style = style, color = color, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, textAlign = align)
}

/** Множественное число без экспериментальных API. */
@Composable
fun plural(id: Int, n: Int): String = LocalContext.current.resources.getQuantityString(id, n, n)

// ======================================================================= кнопки
fun Modifier.tap(onClick: () -> Unit, enabled: Boolean = true, role: Role = Role.Button): Modifier =
    this.clickable(enabled = enabled, role = role, onClick = onClick)

/** Без ряби — для крупных текстовых вкладок. */
@Composable
fun Modifier.quietTap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                  icon: ImageVector? = null) {
    val c = Mn.c
    Row(
        modifier.heightIn(min = 54.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) c.ink else c.ink.copy(alpha = 0.25f))
            .tap(onClick, enabled).padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.onInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Txt(text, T.bodyStrong, c.onInk, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
                    color: Color = Mn.c.ink, small: Boolean = false, enabled: Boolean = true) {
    val c = Mn.c
    Row(
        modifier.heightIn(min = if (small) 38.dp else 54.dp).clip(RoundedCornerShape(if (small) 19.dp else 16.dp))
            .background(if (small) c.raised else c.paper).tap(onClick, enabled)
            .padding(horizontal = if (small) 14.dp else 22.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(if (small) 18.dp else 20.dp))
            Spacer(Modifier.width(7.dp))
        }
        Txt(text, if (small) T.smallStrong else T.bodyStrong, if (enabled) color else c.faint, maxLines = 1)
    }
}

/** Круглая кнопка-иконка на бумаге (верхняя панель). */
@Composable
fun IconCircle(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
               size: Dp = 42.dp, background: Color = Mn.c.paper, tint: Color = Mn.c.ink) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
         leading: (@Composable () -> Unit)? = null) {
    val c = Mn.c
    val bg by animateColorAsState(if (selected) c.ink else c.paper, label = "chip")
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(bg).tap(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Txt(text, T.smallStrong, if (selected) c.onInk else c.ink, maxLines = 1)
    }
}

@Composable
fun RoundCheck(checked: Boolean, onClick: () -> Unit, description: String) {
    val c = Mn.c
    Box(
        Modifier.size(44.dp).clip(CircleShape).tap(onClick, role = Role.Checkbox),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .then(if (checked) Modifier.background(c.ink) else Modifier.border(1.6.dp, c.faint, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Check, description, tint = c.onInk, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Mn.c
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.ink, checkedThumbColor = c.paper, checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = c.line, uncheckedThumbColor = c.paper, uncheckedBorderColor = Color.Transparent,
            disabledCheckedTrackColor = c.faint, disabledUncheckedTrackColor = c.line,
        ),
    )
}

// ======================================================================= группы строк
class GroupScope {
    internal val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/** Заголовок секции 18 Bold и белая карточка с разделителями — как в «Настройках устройства» у Omi. */
@Composable
fun Group(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    inset: Dp = 56.dp,
    trailing: (@Composable () -> Unit)? = null,
    build: GroupScope.() -> Unit,
) {
    val scope = GroupScope().apply(build)
    val c = Mn.c
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 26.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Txt(header, T.section, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
        } else {
            Spacer(Modifier.height(14.dp))
        }
        if (scope.items.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.paper)) {
                scope.items.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = inset), thickness = 0.6.dp, color = c.line)
                    row()
                }
            }
        }
        if (footer != null) {
            Txt(footer, T.caption, c.graphite, Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))
        }
    }
}

/** Строка настроек: монохромная иконка, заголовок, подпись, значение справа. */
@Composable
fun ListRow(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    accent: Boolean = false,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Mn.c
    val tint = when {
        danger -> c.red
        accent -> c.lens
        else -> c.ink
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.tap(onClick) else Modifier)
            .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint ?: tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(title, T.body, tint)
            if (subtitle != null) Txt(subtitle, T.caption, c.graphite, Modifier.padding(top = 2.dp), maxLines = 3)
        }
        if (value != null) {
            Txt(value, T.body, c.graphite, Modifier.padding(start = 10.dp).widthIn(max = 190.dp), maxLines = 1)
        }
        trailing?.invoke()
        if (chevron) {
            Icon(Icons.Chevron, null, tint = c.faint, modifier = Modifier.padding(start = 6.dp).size(18.dp))
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null,
              subtitle: String? = null, enabled: Boolean = true) {
    ListRow(title, icon = icon, subtitle = subtitle, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Toggle(checked, onChange, enabled)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, T.section, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 18.dp,
         content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Mn.c.paper)
            .then(if (onClick != null) Modifier.tap(onClick) else Modifier).padding(padding),
        content = content,
    )
}

/** Плитка с цифрой: «Батарея 82 %». */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, note: String? = null,
             valueColor: Color = Mn.c.ink) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Mn.c.paper).padding(16.dp)) {
        Txt(label, T.caption, Mn.c.graphite, maxLines = 2)
        // плитки в ряду одной высоты: цифры прижаты к низу, даже если подпись в две строки
        Spacer(Modifier.height(6.dp).weight(1f))
        FitText(value, T.number, valueColor)
        if (note != null) Txt(note, T.caption, Mn.c.graphite, Modifier.padding(top = 2.dp), maxLines = 1)
    }
}

/**
 * Одна строка, которая сразу подбирает размер под ширину («2 ч 14 мин» в узкой плитке): без обрезки
 * и без «прыжков» — размер считается при отрисовке, высота строки постоянная.
 */
@Composable
fun FitText(text: String, style: TextStyle, color: Color, minScale: Float = 0.5f) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val height = with(density) { style.lineHeight.toDp() }
    Spacer(
        Modifier.fillMaxWidth().height(height).semantics { contentDescription = text }.drawWithCache {
            val base = style.copy(color = color)
            var layout = measurer.measure(text, base, maxLines = 1, softWrap = false)
            if (layout.size.width > size.width && size.width > 0f) {
                val k = (size.width / layout.size.width).coerceAtLeast(minScale)
                layout = measurer.measure(text, base.copy(fontSize = base.fontSize * k, lineHeight = base.lineHeight * k),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = size.width.toInt()))
            }
            val top = (size.height - layout.size.height) / 2f
            onDrawBehind { drawText(layout, topLeft = Offset(0f, top)) }
        },
    )
}

/** Ряд плиток одинаковой высоты. */
@Composable
fun TileRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun EmojiTile(emoji: String?, size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Mn.c.raised),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji?.takeIf { it.isNotBlank() } ?: "💬", style = T.emoji.copy(fontSize = T.emoji.fontSize * (size / 46.dp)))
    }
}

// ======================================================================= страница с навигацией
@Composable
fun TopBar(title: String?, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    val back = stringResource(R.string.back)
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 12.dp)) {
        if (onBack != null) {
            IconCircle(Icons.Back, back, onBack, Modifier.align(Alignment.CenterStart), size = 40.dp)
        }
        if (title != null) {
            Txt(title, T.bar, modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp), maxLines = 1)
        }
        Row(Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions)
    }
}

// ======================================================================= загрузка и ошибки
@Composable
fun Spinner(size: Dp = 22.dp, color: Color = Mn.c.graphite) {
    CircularProgressIndicator(Modifier.size(size), strokeWidth = 2.dp, color = color)
}

@Composable
fun LoadingBlock() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { Spinner(28.dp) }
}

/** Понятный текст ошибки на языке приложения. */
@Composable
fun errorText(e: Throwable?): String = when ((e as? ApiException)?.kind) {
    ApiException.Kind.NOT_CONFIGURED -> stringResource(R.string.err_not_configured)
    ApiException.Kind.NETWORK -> stringResource(R.string.err_network)
    ApiException.Kind.AUTH -> stringResource(R.string.err_auth)
    ApiException.Kind.NOT_FOUND -> stringResource(R.string.err_not_found)
    ApiException.Kind.DIARY_OFF -> stringResource(R.string.err_diary_off)
    ApiException.Kind.SERVER -> stringResource(R.string.err_server, (e as ApiException).detail ?: "")
    ApiException.Kind.PAIR_BAD -> stringResource(R.string.pair_bad)
    ApiException.Kind.PAIR_EXPIRED -> stringResource(R.string.pair_expired)
    ApiException.Kind.PAIR_LOCKED -> stringResource(R.string.pair_locked)
    // служебные тексты исключений (часто по-английски) не показываем — только понятное объяснение
    null -> stringResource(if (e is java.io.IOException) R.string.err_network else R.string.err_unknown)
}

@Composable
fun ErrorBlock(e: Throwable?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Cloud, null, tint = Mn.c.faint, modifier = Modifier.size(36.dp))
        Txt(errorText(e), T.body, Mn.c.graphite, align = TextAlign.Center)
        val kind = (e as? ApiException)?.kind
        if (kind == ApiException.Kind.NOT_CONFIGURED || kind == ApiException.Kind.AUTH) {
            PrimaryButton(stringResource(R.string.server_connect), { Nav.push(Route.Server) }, icon = Icons.Link)
        } else {
            SecondaryButton(stringResource(R.string.retry), onRetry, small = true, icon = Icons.Refresh)
        }
    }
}

/** Плашка «данные могли устареть»: показываем кэш и честно говорим, что сервер недоступен. */
@Composable
fun StaleNote(e: Throwable?, modifier: Modifier = Modifier) {
    if (e == null || e is kotlin.coroutines.cancellation.CancellationException) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Mn.c.amber))
        Spacer(Modifier.width(8.dp))
        Txt(errorText(e), T.caption, Mn.c.graphite, maxLines = 2)
    }
}

@Composable
fun EmptyBlock(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier,
               action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Mn.c.faint, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(4.dp))
        Txt(title, T.section, align = TextAlign.Center)
        Txt(text, T.small, Mn.c.graphite, align = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

// ======================================================================= даты, время, числа
class Fmt(val locale: Locale, private val res: android.content.res.Resources) {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale)
    private val weekdayFull = DateTimeFormatter.ofPattern("EEEE", locale)
    private val weekdayShort = DateTimeFormatter.ofPattern("EEE", locale)
    private val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy", locale)

    private fun local(iso: String) = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())

    fun time(iso: String?): String = try {
        if (iso.isNullOrBlank()) "" else local(iso).format(hm)
    } catch (e: Exception) {
        ""
    }

    fun timeMs(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)

    fun dayOf(iso: String?): String = try {
        if (iso.isNullOrBlank()) "" else local(iso).toLocalDate().toString()
    } catch (e: Exception) {
        iso?.take(10) ?: ""
    }

    /** «Сегодня», «Вчера» или «28 октября, среда». */
    fun dayTitle(isoDate: String): String = try {
        val d = LocalDate.parse(isoDate.take(10))
        val today = LocalDate.now()
        when (d) {
            today -> res.getString(R.string.today)
            today.minusDays(1) -> res.getString(R.string.yesterday)
            else -> d.format(dayMonth) + ", " + d.format(weekdayFull)
        }
    } catch (e: Exception) {
        isoDate
    }

    /** «Сегодня», «Вчера», «28 октября». */
    fun dayShortTitle(isoDate: String): String = try {
        val d = LocalDate.parse(isoDate.take(10))
        val today = LocalDate.now()
        when (d) {
            today -> res.getString(R.string.today)
            today.minusDays(1) -> res.getString(R.string.yesterday)
            else -> d.format(dayMonth)
        }
    } catch (e: Exception) {
        isoDate
    }

    fun weekdayShort(d: LocalDate): String = d.format(weekdayShort).trimEnd('.').replaceFirstChar { it.titlecase(locale) }

    fun month(d: LocalDate): String = d.format(monthYear).replaceFirstChar { it.titlecase(locale) }

    /** «2 ч 14 мин», «1 мин 19 с», «12 с». */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 && m > 0 -> res.getString(R.string.dur_hm, h, m)
            h > 0 -> res.getString(R.string.dur_h, h)
            m > 0 && sec > 0 && m < 10 -> res.getString(R.string.dur_ms, m, sec)
            m > 0 -> res.getString(R.string.dur_m, m)
            else -> res.getString(R.string.dur_s, sec)
        }
    }

    fun ago(ms: Long): String {
        if (ms <= 0) return "—"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 10 -> res.getString(R.string.just_now)
            else -> res.getString(R.string.ago, duration(s))
        }
    }

    fun bytes(b: Long): String = when {
        b < 1024 -> res.getString(R.string.size_b, b)
        b < 1024 * 1024 -> res.getString(R.string.size_kb, b / 1024)
        else -> res.getString(R.string.size_mb, String.format(locale, "%.1f", b / 1024.0 / 1024.0))
    }

    fun kbps(v: Double): String = res.getString(R.string.kbps, String.format(locale, "%.1f", v))

    fun price(v: Double?): String = when {
        v == null -> "—"
        v == 0.0 -> res.getString(R.string.price_free)
        v < 0.1 -> String.format(Locale.US, "$%.3f", v)
        else -> String.format(Locale.US, "$%.2f", v)
    }
}

@Composable
fun rememberFmt(): Fmt {
    val config = LocalConfiguration.current
    val context = LocalContext.current
    val locale = config.locales[0]
    return remember(locale) { Fmt(locale, context.resources) }
}

fun todayIso(): String = LocalDate.now().toString()
