package codes.neuro.neurosoft.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Ответ ассистента в чате: абзацы, списки, **жирный**, *курсив*, `код` и ссылки.
 * Ссылки на свои данные открываются в приложении: event:12:2026-10-31 — событие, conversation:5 — разговор,
 * task:7 — задачи, day:2026-10-31 — день; обычные https-ссылки — в браузере.
 */
@Composable
fun Markdown(text: String, style: TextStyle = T.body, modifier: Modifier = Modifier) {
    val c = Mn.c
    val context = LocalContext.current
    val blocks = remember(text) { blocks(text) }
    val linkStyle = TextLinkStyles(SpanStyle(color = c.lens, fontWeight = FontWeight.Medium))
    fun open(url: String) {
        when {
            url.startsWith("event:") -> url.removePrefix("event:").split(":").let { p ->
                val id = p.getOrNull(0)?.toLongOrNull() ?: return
                p.getOrNull(1)?.let { day -> Nav.push(Route.Event(id, day)) }
            }
            url.startsWith("conversation:") -> url.removePrefix("conversation:").toLongOrNull()?.let {
                Nav.push(Route.Conversation(it))
            }
            url.startsWith("day:") -> Nav.push(Route.Day(url.removePrefix("day:")))
            url.startsWith("task:") -> {
                Nav.reset(Route.Home)
                HomeState.page.value = 1
            }
            url.startsWith("http://") || url.startsWith("https://") -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
            }
        }
    }

    @Composable
    fun inline(s: String, st: TextStyle = style) {
        val annotated = remember(s) {
            inlineText(s) { url, label ->
                withLink(LinkAnnotation.Clickable(url, linkStyle) { open(url) }) { append(label) }
            }
        }
        Text(annotated, style = st, color = c.ink)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Para -> inline(b.text)
                is Block.Heading -> inline(b.text, T.bodyStrong)
                is Block.Items -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    b.items.forEachIndexed { i, item ->
                        Row(Modifier.padding(start = 2.dp)) {
                            Text(if (b.numbered) "${i + 1}." else "•", style = style, color = c.graphite,
                                modifier = Modifier.widthIn(min = 18.dp))
                            Spacer(Modifier.width(4.dp))
                            inline(item)
                        }
                    }
                }
            }
        }
    }
}

private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Items(val items: List<String>, val numbered: Boolean) : Block
}

private val BULLET = Regex("""^\s*[-*•]\s+(.*)$""")
private val NUMBER = Regex("""^\s*\d+[.)]\s+(.*)$""")
private val HEADING = Regex("""^\s*#{1,6}\s+(.*)$""")

private fun blocks(text: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    var items: MutableList<String>? = null
    var numbered = false
    fun flushPara() {
        if (para.isNotBlank()) out.add(Block.Para(para.toString().trim()))
        para.clear()
    }
    fun flushItems() {
        items?.let { if (it.isNotEmpty()) out.add(Block.Items(it, numbered)) }
        items = null
    }
    for (raw in text.replace("\r", "").lines()) {
        val line = raw.trimEnd()
        val bullet = BULLET.matchEntire(line)
        val number = NUMBER.matchEntire(line)
        val heading = HEADING.matchEntire(line)
        when {
            line.isBlank() -> {
                flushPara()
                flushItems()
            }
            heading != null -> {
                flushPara()
                flushItems()
                out.add(Block.Heading(heading.groupValues[1]))
            }
            bullet != null || number != null -> {
                flushPara()
                val isNumber = bullet == null
                if (items == null || numbered != isNumber) {
                    flushItems()
                    items = ArrayList()
                    numbered = isNumber
                }
                items!!.add((bullet ?: number)!!.groupValues[1])
            }
            items != null && raw.startsWith("  ") -> items!!.let { it[it.lastIndex] = it.last() + " " + line.trim() }
            else -> {
                flushItems()
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
    }
    flushPara()
    flushItems()
    return out
}

private val INLINE = Regex("""\[([^\]]+)]\(([^)\s]+)\)|\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""")

/** **жирный**, *курсив*, `код`, [текст](ссылка) -> AnnotatedString. link(url, label) добавляет ссылку. */
private fun inlineText(s: String, link: AnnotatedString.Builder.(String, String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        var pos = 0
        for (m in INLINE.findAll(s)) {
            append(s.substring(pos, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> link(g[2], g[1])
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[3]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[4]) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[5]) }
                g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
            }
            pos = m.range.last + 1
        }
        append(s.substring(pos))
    }
