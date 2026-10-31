package codes.neuro.neurosoft.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Линейные монохромные иконки 24×24, штрих 1.7 — одного рисунка во всём приложении. */
private fun line(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun solid(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
    }.build()

/** Шестерёнка с 8 зубцами, построенная по окружности. */
private fun gear(): ImageVector {
    val b = PathBuilder()
    val teeth = 8
    val outer = 9.6f
    val inner = 7.4f
    for (i in 0 until teeth) {
        val a = (i * 2 * Math.PI / teeth).toFloat()
        val half = (Math.PI / teeth * 0.42).toFloat()
        val pts = listOf(
            Triple(inner, a - half * 1.55f, 0), Triple(outer, a - half, 0),
            Triple(outer, a + half, 0), Triple(inner, a + half * 1.55f, 0),
        )
        pts.forEachIndexed { j, (r, ang, _) ->
            val x = 12f + r * cos(ang)
            val y = 12f + r * sin(ang)
            if (i == 0 && j == 0) b.moveTo(x, y) else b.lineTo(x, y)
        }
    }
    b.close()
    b.moveTo(15f, 12f)
    b.arcTo(3f, 3f, 0f, true, true, 9f, 12f)
    b.arcTo(3f, 3f, 0f, true, true, 15f, 12f)
    return ImageVector.Builder("gear", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = b.nodes, fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
}

object Icons {
    val Back = line("back", "M15 5l-7 7 7 7")
    val Chevron = line("chevron", "M9.5 6l6 6-6 6")
    val ChevronDown = line("chevron_down", "M6 9.5l6 6 6-6")
    val ChevronLeft = line("chevron_left", "M14.5 6l-6 6 6 6")
    val Close = line("close", "M6.5 6.5l11 11M17.5 6.5l-11 11")
    val Calendar = line("calendar",
        "M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z", "M4 10h16", "M8.5 3v4", "M15.5 3v4")
    val Search = line("search", "M10.5 4a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13z", "M15.5 15.5L20 20")
    val Gear = gear()
    val Mic = line("mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5.5 11a6.5 6.5 0 0 0 13 0",
        "M12 17.5V21")
    val ArrowUp = line("arrow_up", "M12 19V5", "M6 11l6-6 6 6")
    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5")
    val Plus = line("plus", "M12 5v14", "M5 12h14")
    val Sparkle = line("sparkle", "M12 3c.6 4.6 3.4 7.4 8 8-4.6.6-7.4 3.4-8 8-.6-4.6-3.4-7.4-8-8 4.6-.6 7.4-3.4 8-8z")
    val Bubble = line("bubble",
        "M5 5h14a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 19 17h-8l-4.5 3.5V17H5a1.5 1.5 0 0 1-1.5-1.5v-9A1.5 1.5 0 0 1 5 5z")
    val Book = line("book", "M6 4h11a1 1 0 0 1 1 1v15H7a2 2 0 0 1-2-2V5a1 1 0 0 1 1-1z", "M5 18a2 2 0 0 1 2-2h11",
        "M9 8h5")
    val Tasks = line("tasks", "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z", "M8.5 12.2l2.3 2.3 4.7-4.8")
    val Photo = line("photo", "M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z",
        "M4 15.5l4.5-4.5 4.5 4.5 2.5-2.5 4.5 4.5", "M15.5 8.5h.01")
    val Pin = line("pin", "M12 21s-6.5-6.2-6.5-11a6.5 6.5 0 0 1 13 0c0 4.8-6.5 11-6.5 11z",
        "M12 7.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z")
    val Puzzle = line("puzzle",
        "M9.5 4.5a2 2 0 0 1 4 0V6H17a1 1 0 0 1 1 1v3.5h-1.5a2 2 0 0 0 0 4H18V18a1 1 0 0 1-1 1h-3.5v-1.5a2 2 0 0 0-4 0V19H6a1 1 0 0 1-1-1v-3.5h1.5a2 2 0 0 0 0-4H5V7a1 1 0 0 1 1-1h3.5z")
    val Bookmark = line("bookmark", "M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z")
    val Battery = line("battery", "M3.5 8.5a2 2 0 0 1 2-2h11a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2z",
        "M21 10.5v3")
    val Bolt = line("bolt", "M13 3L6 13.5h5.5L10.5 21 18 10.5h-5.5z")
    val Speaker = line("speaker", "M4 9.5h3.5L12 6v12l-4.5-3.5H4z", "M15.5 9a4 4 0 0 1 0 6", "M18 6.5a7.5 7.5 0 0 1 0 11")
    val Globe = line("globe", "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z", "M3.5 12h17",
        "M12 3.5c2.4 2.3 3.5 5.2 3.5 8.5s-1.1 6.2-3.5 8.5c-2.4-2.3-3.5-5.2-3.5-8.5s1.1-6.2 3.5-8.5z")
    val Translate = line("translate", "M4 6.5h9", "M8.5 4.5v2", "M6 6.5c.5 3 2.5 5.5 5 6.5", "M11 6.5c-.7 3.5-3 6-6 7.5",
        "M13 20l3.5-8 3.5 8", "M14.2 17.5h4.6")
    val Pause = line("pause", "M8.5 5.5v13", "M15.5 5.5v13")
    val Play = line("play", "M8 5.5l10.5 6.5L8 18.5z")
    val Target = line("target", "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z", "M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8z",
        "M12 11.6v.8")
    val Tap = line("tap", "M8 12a4 4 0 1 1 8 0", "M5 12a7 7 0 1 1 14 0", "M12 12v8.5")
    val Hand = line("hand", "M9 11.5V5.5a1.5 1.5 0 0 1 3 0v5", "M12 10V8.5a1.5 1.5 0 0 1 3 0V11",
        "M15 10.5a1.5 1.5 0 0 1 3 0V15a6 6 0 0 1-6 6h-.6a6 6 0 0 1-4.8-2.4L4.6 16a1.5 1.5 0 0 1 2.3-1.9L9 16")
    val Bulb = line("bulb", "M9.5 18h5", "M10.5 21h3",
        "M12 3a6 6 0 0 1 3.5 10.9c-.6.5-1 1.2-1 2V16h-5v-.1c0-.8-.4-1.5-1-2A6 6 0 0 1 12 3z")
    val Levels = line("levels", "M6 18v-3", "M10 18v-7", "M14 18V8", "M18 18V5")
    val Info = line("info", "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z", "M12 11v5.5", "M12 7.8v.01")
    val Chip = line("chip", "M7 7h10v10H7z", "M10 3.5V7", "M14 3.5V7", "M10 17v3.5", "M14 17v3.5", "M3.5 10H7",
        "M3.5 14H7", "M17 10h3.5", "M17 14h3.5")
    val Code = line("code", "M8.5 7l-5 5 5 5", "M15.5 7l5 5-5 5", "M13.5 4.5l-3 15")
    val Tag = line("tag", "M3.5 12.6V5a1.5 1.5 0 0 1 1.5-1.5h7.6l8 8a1.5 1.5 0 0 1 0 2.1l-6.5 6.5a1.5 1.5 0 0 1-2.1 0z",
        "M8 8h.01")
    val Layers = line("layers", "M12 4l8.5 4.5L12 13 3.5 8.5z", "M3.5 12.5L12 17l8.5-4.5", "M3.5 16.5L12 21l8.5-4.5")
    val Factory = line("factory", "M3.5 20V10.5l5 3v-3l5 3V6h3l1-2.5h2V20z", "M3 20h18")
    val Unlink = line("unlink", "M8 11l-2 2a3.5 3.5 0 0 0 5 5l2-2", "M16 13l2-2a3.5 3.5 0 0 0-5-5l-2 2", "M4.5 4.5l15 15")
    val Trash = line("trash", "M4.5 6.5h15", "M9.5 6.5v-2h5v2", "M6.5 6.5l1 13h9l1-13", "M10 10.5v6", "M14 10.5v6")
    val Refresh = line("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v3.7h-3.7")
    val Bluetooth = line("bluetooth", "M7 7.5l10 9-5 4.5V3l5 4.5-10 9")
    val BluetoothOff = line("bluetooth_off", "M7 7.5l10 9-5 4.5V3l5 4.5-10 9", "M4 4l16 16")
    val Signal = line("signal", "M5 19v-2", "M9.5 19v-5", "M14 19v-8", "M18.5 19V8")
    val Clock = line("clock", "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z", "M12 7.5V12l3 2")
    val Power = line("power", "M12 3.5v8", "M7 6a8 8 0 1 0 10 0")
    val Link = line("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1",
        "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val Wave = line("wave", "M4 12h.01", "M7.5 9v6", "M11 5.5v13", "M14.5 8.5v7", "M18 10.5v3", "M20.5 12h.01")
    val Help = line("help", "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z",
        "M9.6 9.5a2.5 2.5 0 1 1 3.3 2.3c-.6.3-.9.8-.9 1.4v.3", "M12 16.5v.01")
    val Pulse = line("pulse", "M3.5 12h4l2-5 4 10 2-5h5")
    val Palette = line("palette",
        "M12 3.5a8.5 8.5 0 0 0 0 17c1.4 0 1.8-1 1.3-2-.6-1.2.2-2.5 1.5-2.5H17a3.5 3.5 0 0 0 3.5-3.5c0-5-3.8-9-8.5-9z",
        "M7.5 11h.01", "M10 7.5h.01", "M14.5 7.5h.01")
    val Copy = line("copy", "M8.5 8.5h10v11h-10z", "M5.5 15.5v-11h10")
    val Share = line("share", "M12 15V3.5", "M7.5 8L12 3.5 16.5 8", "M5 12.5V19a1.5 1.5 0 0 0 1.5 1.5h11A1.5 1.5 0 0 0 19 19v-6.5")
    val Cloud = line("cloud", "M7 18a4.5 4.5 0 0 1-.5-9A6 6 0 0 1 18 8.5a4 4 0 0 1-.5 9.5", "M12 12.5v7",
        "M9.3 15l2.7-2.7 2.7 2.7")
    val Map = line("map", "M9 5L3.5 7v12L9 17l6 2 5.5-2V5L15 7z", "M9 5v12", "M15 7v12")
    val Moon = line("moon", "M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z")
    val Dot = solid("dot", "M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8z")
    val Person = line("person", "M12 4a4 4 0 1 1 0 8 4 4 0 0 1 0-8z", "M4.5 20.5c1.2-3.6 4-5.5 7.5-5.5s6.3 1.9 7.5 5.5")
    val People = line("people", "M9 5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z", "M2.5 19.5c1-3.1 3.4-4.8 6.5-4.8s5.5 1.7 6.5 4.8",
        "M15.5 5.2a3.3 3.3 0 0 1 0 6.6", "M17.5 14.9c2 .5 3.4 2 4 4.6")
    val Merge = line("merge", "M6 4c0 6 6 6 6 11v5", "M18 4c0 6-6 6-6 11", "M9 17.5l3 3 3-3")
    val EyeOff = line("eye_off", "M3 12s3.5-6.5 9-6.5S21 12 21 12s-3.5 6.5-9 6.5S3 12 3 12z",
        "M12 9.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z", "M4 4l16 16")
    val Shield = line("shield", "M12 3.5l7 2.5v5.5c0 4.5-3 7.8-7 9-4-1.2-7-4.5-7-9V6z", "M9 12l2.2 2.2L15.5 10")
    val Bell = line("bell", "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z", "M10 20.5a2 2 0 0 0 4 0")
    val Edit = line("edit", "M4.5 19.5l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3l-10 10z", "M13.5 7l3 3")
    val Fingerprint = line("fingerprint", "M6.5 7.5a7 7 0 0 1 11 0", "M5 11.5a7 7 0 0 1 14 0v1",
        "M8.5 19a10 10 0 0 0 1-5.5v-1.8a2.5 2.5 0 0 1 5 0v1.8c0 2.6-.5 4.7-1.5 6.5",
        "M12 12.5v1.5c0 2.4-.6 4.4-1.6 6", "M16.8 18.2c.4-1.4.7-3 .7-4.7")
    val Backspace = line("backspace", "M9 5.5h10.5a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H9L3.5 12z",
        "M12 9.5l5 5", "M17 9.5l-5 5")
    val Expand = line("expand", "M4.5 9.5v-5h5", "M19.5 9.5v-5h-5", "M4.5 14.5v5h5", "M19.5 14.5v5h-5",
        "M4.5 4.5l5.5 5.5", "M19.5 19.5l-5.5-5.5")
    val Minus = line("minus", "M5 12h14")
    val Lock = line("lock", "M6.5 10.5h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19v-7a1.5 1.5 0 0 1 1.5-1.5z",
        "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")
}
