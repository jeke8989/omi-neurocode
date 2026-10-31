package codes.neuro.neurosoft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** Метка на карте: событие или разговор (эмодзи в кружке). */
class MapPin(val key: String, val lat: Double, val lon: Double, val emoji: String?, val title: String?,
             val onClick: (() -> Unit)? = null)

/** Место, где побывали (с названием, если известно). */
class MapPlaceMark(val lat: Double, val lon: Double, val name: String?)

/** Камера карты: центр в «мировых» координатах (0..256 на уровне 0) и масштаб. Хранится снаружи, чтобы
 *  список под картой мог показать на ней событие. */
class MapState {
    var cx by mutableDoubleStateOf(-1.0)
    var cy by mutableDoubleStateOf(-1.0)
    var zoom by mutableFloatStateOf(-1f)
    val ready get() = zoom > 0
    /** Для каких точек камера подобрана: новые данные — подобрать заново, свои жесты — не сбивать. */
    var fittedFor = 0

    fun focus(lat: Double, lon: Double, z: Float = 16f) {
        cx = worldX(lon)
        cy = worldY(lat)
        zoom = z
    }
}

private const val TILE = 256.0
private const val MIN_ZOOM = 2f
private const val MAX_ZOOM = 18f

fun worldX(lon: Double) = (lon + 180.0) / 360.0 * TILE
fun worldY(lat: Double): Double {
    val r = lat.coerceIn(-85.0, 85.0) * PI / 180.0
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * TILE
}
fun worldLat(y: Double): Double = atan(kotlin.math.sinh(PI * (1 - 2 * y / TILE))) * 180.0 / PI

/** Подобрать камеру, чтобы все точки поместились (с полями). */
private fun fit(state: MapState, pts: List<LatLon>, wDp: Float, hDp: Float) {
    if (pts.isEmpty()) return
    val xs = pts.map { worldX(it.lon) }
    val ys = pts.map { worldY(it.lat) }
    val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-9)
    val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-9)
    state.cx = (xs.max() + xs.min()) / 2
    state.cy = (ys.max() + ys.min()) / 2
    val z = min(log2(wDp * 0.72 / spanX), log2(hDp * 0.62 / spanY)).toFloat()
    state.zoom = if (pts.size == 1 || spanX < 1e-6) 15.5f else z.coerceIn(3f, 16.5f)
}

/**
 * Карта Mnemo: спокойная серая подложка (светлая или тёмная — как тема приложения), маршрут — светом
 * линзы, места — точками с подписями, события — эмодзи. interactive=false — превью: без жестов, нажатие —
 * onOpen (обычно полноэкранная карта).
 */
@Composable
fun MnemoMap(
    modifier: Modifier,
    track: List<LatLon> = emptyList(),
    places: List<MapPlaceMark> = emptyList(),
    pins: List<MapPin> = emptyList(),
    state: MapState = remember { MapState() },
    interactive: Boolean = false,
    onOpen: (() -> Unit)? = null,
    description: String? = null,
    /** Сразу показать это место крупно (а не весь день). */
    focus: LatLon? = null,
) {
    val c = Mn.c
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = if (c.dark) "dark" else "light"
    val all = remember(track, places, pins) {
        track + places.map { LatLon(it.lat, it.lon) } + pins.map { LatLon(it.lat, it.lon) }
    }
    BoxWithConstraints(
        modifier.clipToBounds().background(if (c.dark) Color(0xFF0E0F12) else Color(0xFFF4F4F2))
            .semantics { if (description != null) contentDescription = description },
    ) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        LaunchedEffect(all, wDp, hDp) {
            val key = all.hashCode()
            if (!interactive || !state.ready || state.fittedFor != key) {
                if (focus != null) state.focus(focus.lat, focus.lon, 16f) else fit(state, all, wDp, hDp)
                state.fittedFor = key
            }
        }
        if (!state.ready) return@BoxWithConstraints
        val zoom = state.zoom
        val scale = 2.0.pow(zoom.toDouble())        // мировые единицы -> dp
        val px = density.density
        fun sx(wx: Double) = ((wx - state.cx) * scale + wDp / 2).toFloat()
        fun sy(wy: Double) = ((wy - state.cy) * scale + hDp / 2).toFloat()

        // ---------------- подложка: плитки текущего уровня
        val zi = floor(zoom).toInt().coerceIn(0, 19)
        val n = 1 shl zi
        val tileDp = (TILE / n * scale).toFloat()
        val left = state.cx - wDp / 2 / scale
        val top = state.cy - hDp / 2 / scale
        val x0 = floor(left / TILE * n).toInt()
        val x1 = floor((left + wDp / scale) / TILE * n).toInt()
        val y0 = floor(top / TILE * n).toInt().coerceAtLeast(0)
        val y1 = floor((top + hDp / scale) / TILE * n).toInt().coerceAtMost(n - 1)
        for (ty in y0..y1) for (tx in x0..x1) {
            val wrapped = ((tx % n) + n) % n
            key(zi, tx, ty) {
                val ox = sx(tx * TILE / n)
                val oy = sy(ty * TILE / n)
                AsyncImage(
                    model = ImageRequest.Builder(context).data(Api.tileUrl(style, zi, wrapped, ty))
                        .addHeader("Authorization", Api.authHeader()).crossfade(150).build(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.offset { IntOffset((ox * px).roundToInt(), (oy * px).roundToInt()) }
                        .size((tileDp + 0.6f).dp),
                )
            }
        }

        // ---------------- маршрут и места
        val ink = c.ink
        val paper = c.paper
        val lens = c.lens
        val labelStyle = T.tiny.copy(color = c.ink)
        Canvas(
            Modifier.fillMaxSize().then(
                if (interactive) Modifier
                    .pointerInput(state) {
                        detectTransformGestures { centroid, pan, gz, _ ->
                            val s0 = 2.0.pow(state.zoom.toDouble())
                            val nz = (state.zoom + log2(gz)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val s1 = 2.0.pow(nz.toDouble())
                            // точка под пальцами остаётся на месте
                            val fx = (centroid.x / px - wDp / 2).toDouble()
                            val fy = (centroid.y / px - hDp / 2).toDouble()
                            val wx = state.cx + fx / s0
                            val wy = state.cy + fy / s0
                            state.zoom = nz
                            state.cx = wx - fx / s1 - pan.x / px / s1
                            state.cy = (wy - fy / s1 - pan.y / px / s1).coerceIn(0.0, TILE)
                        }
                    }
                    .pointerInput(state) {
                        detectTapGestures(onDoubleTap = {
                            state.zoom = (state.zoom + 1f).coerceAtMost(MAX_ZOOM)
                        })
                    }
                else Modifier.pointerInput(onOpen) { detectTapGestures { onOpen?.invoke() } },
            ),
        ) {
            if (track.size > 1) {
                val path = Path()
                track.forEachIndexed { i, p ->
                    val x = sx(worldX(p.lon)) * px
                    val y = sy(worldY(p.lat)) * px
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lens.copy(alpha = 0.22f),
                    style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, lens, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val end = track.last()
                val e = Offset(sx(worldX(end.lon)) * px, sy(worldY(end.lat)) * px)
                drawCircle(lens.copy(alpha = 0.25f), 11.dp.toPx(), e)
                drawCircle(paper, 5.5f.dp.toPx(), e)
                drawCircle(lens, 3.5f.dp.toPx(), e)
            }
            val placed = ArrayList<Offset>()
            places.forEach { pl ->
                val o = Offset(sx(worldX(pl.lon)) * px, sy(worldY(pl.lat)) * px)
                drawCircle(paper, 5.dp.toPx(), o)
                drawCircle(ink, 5.dp.toPx(), o, style = Stroke(2.dp.toPx()))
                val name = pl.name ?: return@forEach
                if (placed.any { (it - o).getDistance() < 70.dp.toPx() }) return@forEach  // не налезать
                placed += o
                val layout = measurer.measure(name.take(28), labelStyle, maxLines = 1)
                val tl = Offset(o.x + 9.dp.toPx(), o.y - layout.size.height / 2f)
                drawRoundRect(paper.copy(alpha = 0.88f), topLeft = tl - Offset(5.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(layout.size.width + 10.dp.toPx(),
                        layout.size.height + 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                drawText(layout, topLeft = tl)
            }
        }

        // ---------------- метки событий (с группировкой, если налезают)
        val groups = ArrayList<MutableList<MapPin>>()
        val centers = ArrayList<Offset>()
        for (p in pins) {
            val o = Offset(sx(worldX(p.lon)), sy(worldY(p.lat)))
            val i = centers.indexOfFirst { (it - o).getDistance() < 30f }
            if (i >= 0) groups[i].add(p) else {
                groups += mutableListOf(p)
                centers += o
            }
        }
        groups.forEachIndexed { i, g ->
            val o = centers[i]
            if (o.x < -40 || o.y < -40 || o.x > wDp + 40 || o.y > hDp + 40) return@forEachIndexed
            val size = 36f
            Box(
                Modifier.offset { IntOffset(((o.x - size / 2) * px).roundToInt(), ((o.y - size / 2) * px).roundToInt()) }
                    .size(size.dp).clip(CircleShape).background(paper).border(1.5.dp, ink.copy(alpha = 0.85f), CircleShape)
                    .then(if (interactive || onOpen == null) Modifier.tap({
                        if (g.size > 1 && interactive) state.focus(g[0].lat, g[0].lon, (state.zoom + 2f).coerceAtMost(MAX_ZOOM))
                        else g[0].onClick?.invoke()
                    }) else Modifier.tap({ onOpen?.invoke() })),
                contentAlignment = Alignment.Center,
            ) {
                Txt(g[0].emoji ?: "💬", T.emoji.copy(fontSize = T.emoji.fontSize * 0.78f), maxLines = 1)
            }
            if (g.size > 1) {
                Txt("${g.size}", T.tiny, c.onInk, maxLines = 1,
                    modifier = Modifier.offset { IntOffset(((o.x + 8) * px).roundToInt(), ((o.y - 22) * px).roundToInt()) }
                        .clip(RoundedCornerShape(9.dp)).background(ink).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }

        // подпись поставщиков карты (обязательна по условиям OpenStreetMap и CARTO)
        Txt("© OpenStreetMap © CARTO", T.tiny.copy(fontSize = T.tiny.fontSize * 0.8f), c.graphite, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                .background(paper.copy(alpha = 0.75f)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}
