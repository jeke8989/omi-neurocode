package codes.neuro.neurosoft.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Graph
import codes.neuro.neurosoft.net.GraphEvent
import codes.neuro.neurosoft.net.PeopleInfo
import codes.neuro.neurosoft.net.PersonRef
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Ночное небо одинаковое в светлой и тёмной теме: это карточка-окно, а не фон экрана.
internal val Sky1 = Color(0xFF0A0F1F)
internal val Sky2 = Color(0xFF141B36)
internal val StarLight = Color(0xFFF2F4FF)
internal val SkyDim = Color(0xFF97A3D4)

private const val DAY = 86_400_000L
private val GOLDEN = (PI * (3 - sqrt(5.0))).toFloat()

/**
 * Узел неба в «единичных» координатах: центр (0, 0) — вы (или «сейчас» у событий), край — радиус 1.
 * Так одну раскладку можно нарисовать и в карточке, и на весь экран с любым масштабом.
 */
class SkyNode(
    val key: Long, val x: Float, val y: Float, val r: Float, val weight: Float, val alpha: Float,
    val label: String?, val tint: Color, val center: Boolean = false,
    val person: PersonRef? = null, val event: GraphEvent? = null, val last: Long = 0L, val count: Int = 0,
)

class SkyEdge(val a: Int, val b: Int, val strength: Float, val curved: Boolean)

class SkyGraph(val nodes: List<SkyNode>, val edges: List<SkyEdge>) {
    val isEmpty get() = nodes.none { !it.center }
}

/** Оттенок события по виду: тёплые — люди и семья, холодные — работа и учёба. */
private fun kindTint(kind: String): Color = when (kind) {
    "встреча", "друзья", "семья" -> Color(0xFFFFDDB0)
    "работа", "учёба" -> Color(0xFFB4CCFF)
    "звонок" -> Color(0xFFD3C4FF)
    "поездка", "покупки" -> Color(0xFFBDEFE2)
    "отдых", "спорт", "здоровье" -> Color(0xFFFFC9D6)
    else -> StarLight
}

/** Люди вокруг вас: чем больше разговоров — тем ближе и крупнее; давно не виделись — тусклее.
 *  Линии: вы — человек (толщина по числу разговоров) и люди, которые бывают в разговорах вместе. */
fun peopleGraph(g: Graph, since: Long, now: Long, labels: Map<Long, String>, me: String): SkyGraph {
    val owner = g.people.firstOrNull { it.ref.isOwner }
    val refs = g.people.associate { it.id to it.ref }
    val weight = HashMap<Long, Int>()
    val last = HashMap<Long, Long>()
    val pairs = HashMap<Pair<Long, Long>, Int>()
    for (c in g.conversations) {
        val t = isoMillis(c.startedAt)
        if (t < since) continue
        val ps = c.people.filter { it != owner?.id && refs[it]?.ignored != true }.distinct().sorted()
        ps.forEach {
            weight[it] = (weight[it] ?: 0) + 1
            last[it] = max(last[it] ?: 0L, t)
        }
        for (i in ps.indices) for (j in i + 1 until ps.size) pairs[ps[i] to ps[j]] = (pairs[ps[i] to ps[j]] ?: 0) + 1
    }
    val wmax = (weight.values.maxOrNull() ?: 1).toFloat()
    val nodes = ArrayList<SkyNode>()
    nodes += SkyNode(owner?.id ?: -1L, 0f, 0f, 1f, 1f, 1f, me, StarLight, center = true, person = owner?.ref,
        count = g.conversations.count { isoMillis(it.startedAt) >= since })
    val index = HashMap<Long, Int>()
    weight.entries.sortedByDescending { it.value }.forEach { (id, n) ->
        val ref = refs[id] ?: return@forEach
        val k = sqrt(n / wmax)
        val angle = (id % 9973) * GOLDEN - (PI / 2).toFloat()   // постоянный угол: при смене периода не прыгает
        val dist = 0.34f + 0.6f * (1f - k)
        val days = (now - (last[id] ?: now)) / DAY.toFloat()
        index[id] = nodes.size
        nodes += SkyNode(id, cos(angle) * dist, sin(angle) * dist, 0.45f + 0.55f * k, k,
            (1f - days / 60f).coerceIn(0.45f, 1f), labels[id], StarLight, person = ref, last = last[id] ?: 0L, count = n)
    }
    val edges = ArrayList<SkyEdge>()
    for (i in 1 until nodes.size) edges += SkyEdge(0, i, nodes[i].weight, curved = false)
    val pmax = (pairs.values.maxOrNull() ?: 1).toFloat()
    pairs.forEach { (k, n) ->
        val a = index[k.first] ?: return@forEach
        val b = index[k.second] ?: return@forEach
        edges += SkyEdge(a, b, n / pmax, curved = true)
    }
    return SkyGraph(nodes, edges)
}

/**
 * Карта воспоминаний. Самые яркие воспоминания (оценка модели плюс фото, люди, «запомни это») — крупные
 * звёзды с лучами; остальные события собираются вокруг той яркой звезды, с которой связаны сильнее всего
 * (общие люди, темы, тот же день). Свежее — ближе к центру неба, давнее — к краю. Яркие воспоминания с
 * общими людьми соединены дугами — так видно, из чего складываются истории.
 */
fun memoryGraph(g: Graph, since: Long, now: Long): SkyGraph {
    val owner = g.people.firstOrNull { it.ref.isOwner }?.id
    val evs = g.events.filter { isoMillis(it.startedAt) in since..now }.sortedBy { isoMillis(it.startedAt) }.takeLast(160)
    if (evs.isEmpty()) return SkyGraph(emptyList(), emptyList())
    fun people(e: GraphEvent) = e.people.filter { it != owner }.toSet()
    fun relation(a: GraphEvent, b: GraphEvent): Float {
        var r = 2f * people(a).intersect(people(b)).size + a.topics.intersect(b.topics.toSet()).size
        if (a.day != null && a.day == b.day) r += 0.6f
        if (kotlin.math.abs(isoMillis(a.startedAt) - isoMillis(b.startedAt)) < 3 * DAY) r += 0.3f
        return r
    }
    val n = (evs.size / 4).coerceIn(min(3, evs.size), 14)
    val ranked = evs.sortedByDescending { it.vivid }
    val anchors = ranked.take(n).filterIndexed { i, e -> i < 3 || e.vivid >= 0.45f }
    val anchorIds = anchors.map { it.id }.toSet()
    val span = max(3_600_000L, now - isoMillis(evs.first().startedAt)).toFloat()
    // яркие звёзды — по времени на спирали, потом немного расталкиваем, чтобы не слипались
    val pos = anchors.associate { e ->
        val age = ((now - isoMillis(e.startedAt)) / span).coerceIn(0f, 1f)
        val d = 0.14f + 0.7f * sqrt(age)
        val a = (e.id % 9973) * GOLDEN
        e.id to floatArrayOf(cos(a) * d, sin(a) * d)
    }
    repeat(30) {
        for (a in anchors) for (b in anchors) {
            if (a.id >= b.id) continue
            val pa = pos.getValue(a.id)
            val pb = pos.getValue(b.id)
            val dx = pb[0] - pa[0]
            val dy = pb[1] - pa[1]
            val d = max(1e-3f, hypot(dx, dy))
            val want = 0.34f
            if (d < want) {
                val push = (want - d) / 2f
                pa[0] -= dx / d * push; pa[1] -= dy / d * push
                pb[0] += dx / d * push; pb[1] += dy / d * push
            }
        }
        pos.values.forEach { p ->
            val d = hypot(p[0], p[1])
            if (d > 0.9f) { p[0] *= 0.9f / d; p[1] *= 0.9f / d }
        }
    }
    val nodes = ArrayList<SkyNode>()
    val index = HashMap<Long, Int>()
    fun label(e: GraphEvent) = listOfNotNull(e.emoji, e.title).joinToString(" ").ifBlank { null }?.take(28)
    anchors.forEach { e ->
        val p = pos.getValue(e.id)
        index[e.id] = nodes.size
        nodes += SkyNode(e.id, p[0], p[1], 0.7f + 0.9f * e.vivid, 0.6f + 0.4f * e.vivid, 1f, label(e), kindTint(e.kind),
            event = e, last = isoMillis(e.startedAt), count = e.fragments)
    }
    // остальные — вокруг самой близкой по смыслу яркой звезды; не связаны ни с одной — сами по себе
    val sats = HashMap<Long, MutableList<Pair<GraphEvent, Float>>>()
    val free = ArrayList<GraphEvent>()
    evs.filter { it.id !in anchorIds }.forEach { e ->
        val best = anchors.map { it to relation(e, it) }.maxByOrNull { it.second }
        if (best != null && best.second >= 1f) sats.getOrPut(best.first.id) { mutableListOf() } += e to best.second
        else free += e
    }
    val edges = ArrayList<SkyEdge>()
    sats.forEach { (aid, list) ->
        val ai = index.getValue(aid)
        val ap = pos.getValue(aid)
        val base = (aid % 360) / 57.3f
        list.forEachIndexed { k, (e, rel) ->
            val ang = base + k * (2 * PI.toFloat() / max(5, list.size))
            val orbit = 0.12f + 0.025f * (k / 5)
            index[e.id] = nodes.size
            nodes += SkyNode(e.id, ap[0] + cos(ang) * orbit, ap[1] + sin(ang) * orbit, 0.32f + 0.4f * e.vivid,
                0.3f * e.vivid, 0.85f, label(e), kindTint(e.kind), event = e, last = isoMillis(e.startedAt),
                count = e.fragments)
            edges += SkyEdge(ai, nodes.lastIndex, (rel / 3f).coerceIn(0.25f, 1f), curved = false)
        }
    }
    free.forEach { e ->
        val age = ((now - isoMillis(e.startedAt)) / span).coerceIn(0f, 1f)
        val d = 0.2f + 0.75f * sqrt(age)
        val a = (e.id % 9973) * GOLDEN + 0.4f
        nodes += SkyNode(e.id, cos(a) * d, sin(a) * d, 0.28f + 0.35f * e.vivid, 0.2f * e.vivid, 0.6f, label(e),
            kindTint(e.kind), event = e, last = isoMillis(e.startedAt), count = e.fragments)
    }
    // яркие воспоминания с общими людьми — дугами
    for (x in anchors.indices) for (y in x + 1 until anchors.size) {
        val r = relation(anchors[x], anchors[y])
        if (r >= 2f) edges += SkyEdge(index.getValue(anchors[x].id), index.getValue(anchors[y].id), (r / 5f).coerceIn(0.3f, 1f),
            curved = true)
    }
    return SkyGraph(nodes, edges)
}

/** Камера: масштаб и сдвиг (в пикселях). */
class SkyCamera {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /** Масштаб вокруг точки экрана: то, что под пальцами, остаётся под пальцами. */
    fun zoomAt(factor: Float, focus: Offset, center: Offset) {
        val nz = (zoom * factor).coerceIn(0.7f, 6f)
        val f = nz / zoom
        pan = (focus - center) - (focus - center - pan) * f
        zoom = nz
    }

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }
}

private fun nodeAt(g: SkyGraph, pos: Offset, toScreen: (SkyNode) -> Offset, radius: (SkyNode) -> Float, slop: Float): SkyNode? =
    g.nodes.minByOrNull { (toScreen(it) - pos).getDistance() }
        ?.takeIf { (toScreen(it) - pos).getDistance() <= radius(it) + slop }

/**
 * Небо: связи, звёзды и подписи. Подписи никогда не налезают друг на друга и на звёзды — менее важные
 * скрываются (при приближении место появляется, и они возвращаются). Выбранный узел и его связи — ярче,
 * остальное приглушено.
 */
@Composable
fun SkyCanvas(
    graph: SkyGraph,
    modifier: Modifier,
    camera: SkyCamera,
    selected: Long? = null,
    lens: Color = Mn.c.lens,
    nodeScale: Dp = 9.dp,
    interactive: Boolean = false,
    onTapNode: (SkyNode?) -> Unit = {},
    description: String? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val nameStyle = T.small.copy(color = StarLight)
    val centerStyle = T.smallStrong.copy(color = StarLight)
    val tap by rememberUpdatedState(onTapNode)
    val bg = remember { kotlin.random.Random(7).let { r -> List(80) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    BoxWithConstraints(modifier.semantics { if (description != null) contentDescription = description }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val center = Offset(w / 2f, h / 2f)
        val R = min(w, h) / 2f - with(density) { 34.dp.toPx() }
        val unit = with(density) { nodeScale.toPx() }
        fun screen(n: SkyNode) = center + camera.pan + Offset(n.x, n.y) * (R * camera.zoom)
        // звезда растёт с приближением, но медленнее карты — чтобы не слипались
        fun radius(n: SkyNode) = (if (n.center) unit * 1.15f else unit * n.r) * sqrt(camera.zoom).coerceIn(0.85f, 1.8f)
        val related = remember(graph, selected) {
            if (selected == null) null
            else {
                val i = graph.nodes.indexOfFirst { it.key == selected }
                if (i < 0) null else graph.edges.filter { it.a == i || it.b == i }.flatMap { listOf(it.a, it.b) }.toSet() + i
            }
        }
        Canvas(
            Modifier.fillMaxSize().then(
                if (interactive) Modifier
                    .pointerInput(graph) {
                        detectTransformGestures { centroid, pan, z, _ ->
                            camera.zoomAt(z, centroid, center)
                            camera.pan += pan
                        }
                    }
                    .pointerInput(graph) {
                        detectTapGestures(
                            onTap = { pos -> tap(nodeAt(graph, pos, ::screen, ::radius, 22.dp.toPx())) },
                            onDoubleTap = { pos -> camera.zoomAt(2f, pos, center) },
                        )
                    }
                else Modifier.pointerInput(graph) {
                    detectTapGestures { pos -> tap(nodeAt(graph, pos, ::screen, ::radius, 22.dp.toPx())) }
                },
            ),
        ) {
            bg.forEach { (x, y, s) ->
                drawCircle(StarLight.copy(alpha = 0.06f + 0.18f * s), (0.5f + s) * density.density,
                    Offset(x * size.width, y * size.height))
            }
            val pts = graph.nodes.map { screen(it) }
            // связи: от вас — прямые лучи, между людьми — мягкие дуги
            graph.edges.forEach { e ->
                val a = pts[e.a]
                val b = pts[e.b]
                val on = related == null || (e.a in related && e.b in related)
                val base = if (e.curved) 0.16f + 0.42f * e.strength else 0.08f + 0.26f * e.strength
                val alpha = (if (on) base else base * 0.18f) * min(graph.nodes[e.a].alpha, graph.nodes[e.b].alpha)
                val color = if (related != null && on) lens else StarLight
                val width = (if (e.curved) 1.2f + 1.6f * e.strength else 1f + 1.4f * e.strength) * density.density
                if (e.curved) {
                    val mid = (a + b) / 2f
                    val normal = Offset(-(b.y - a.y), b.x - a.x) * 0.18f
                    val ctrl = mid + normal
                    val path = Path().apply {
                        moveTo(a.x, a.y)
                        quadraticTo(ctrl.x, ctrl.y, b.x, b.y)
                    }
                    drawPath(path, color.copy(alpha = alpha), style = Stroke(width))
                } else {
                    drawLine(color.copy(alpha = alpha), a, b, width)
                }
            }
            // звёзды
            graph.nodes.forEachIndexed { i, n ->
                val p = pts[i]
                val r = radius(n)
                val dim = if (related == null || i in related) 1f else 0.25f
                if (n.center) {
                    drawCircle(Brush.radialGradient(listOf(lens.copy(alpha = 0.55f), Color.Transparent), p, r * 5f), r * 5f, p)
                    drawCircle(lens, r, p)
                    drawCircle(StarLight, r * 0.42f, p)
                } else {
                    val a = n.alpha * dim
                    val glow = r * (3f + 2.5f * n.weight)
                    drawCircle(Brush.radialGradient(listOf(n.tint.copy(alpha = 0.34f * a), Color.Transparent), p, glow),
                        glow, p)
                    if (n.event != null && n.weight >= 0.75f) {
                        // яркое воспоминание — с лучами, как у настоящей яркой звезды
                        val spike = r * 3.6f
                        val c = n.tint.copy(alpha = 0.55f * a)
                        drawLine(c, Offset(p.x - spike, p.y), Offset(p.x + spike, p.y), 1.2f * density.density)
                        drawLine(c, Offset(p.x, p.y - spike), Offset(p.x, p.y + spike), 1.2f * density.density)
                    }
                    drawCircle(n.tint.copy(alpha = a), r, p)
                }
                if (n.key == selected) drawCircle(lens, r + 5 * density.density, p, style = Stroke(2 * density.density))
            }
            drawLabels(graph, pts, ::radius, measurer, nameStyle, centerStyle, selected, related, density.density)
        }
    }
}

/** Подписи без наложений: сначала выбранный и центр, потом самые важные; не влезла — не рисуем. */
private fun DrawScope.drawLabels(
    graph: SkyGraph, pts: List<Offset>, radius: (SkyNode) -> Float, measurer: TextMeasurer, style: TextStyle,
    centerStyle: TextStyle, selected: Long?, related: Set<Int>?, dp: Float,
) {
    val taken = ArrayList<Rect>()
    graph.nodes.forEachIndexed { i, n -> taken += Rect(pts[i], radius(n) + 2 * dp) }
    val order = graph.nodes.indices.sortedWith(compareByDescending<Int> { graph.nodes[it].key == selected }
        .thenByDescending { graph.nodes[it].center }.thenByDescending { graph.nodes[it].weight })
    val placed = ArrayList<Rect>()
    for (i in order) {
        val n = graph.nodes[i]
        val text = n.label ?: continue
        val p = pts[i]
        if (p.x < -40 * dp || p.y < -40 * dp || p.x > size.width + 40 * dp || p.y > size.height + 40 * dp) continue
        val layout = measurer.measure(text, if (n.center) centerStyle else style, maxLines = 1)
        val tw = layout.size.width.toFloat()
        val th = layout.size.height.toFloat()
        val r = radius(n) + 4 * dp
        val candidates = listOf(
            Offset(p.x - tw / 2, p.y + r), Offset(p.x - tw / 2, p.y - r - th),
            Offset(p.x + r, p.y - th / 2), Offset(p.x - r - tw, p.y - th / 2),
        )
        val spot = candidates.firstOrNull { tl ->
            val rect = Rect(tl, androidx.compose.ui.geometry.Size(tw, th)).inflate(3 * dp)
            rect.left >= 0 && rect.right <= size.width && rect.top >= 0 && rect.bottom <= size.height &&
                placed.none { it.overlaps(rect) } &&
                taken.withIndex().none { (j, t) -> j != i && t.overlaps(rect) }
        } ?: continue
        placed += Rect(spot, androidx.compose.ui.geometry.Size(tw, th)).inflate(3 * dp)
        val dim = if (related == null || i in related) 1f else 0.3f
        drawText(layout, topLeft = spot, alpha = (n.alpha * dim).coerceIn(0.3f, 1f))
    }
}

/** Переключатель «Люди / События» на тёмном небе. */
@Composable
fun SkyModeToggle(mode: Int, onMode: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(17.dp)).background(StarLight.copy(alpha = 0.08f)).padding(3.dp)) {
        listOf(stringResource(R.string.sky_mode_memories), stringResource(R.string.sky_mode_people)).forEachIndexed { i, label ->
            Txt(label, T.smallStrong, if (mode == i) Sky1 else StarLight.copy(alpha = 0.8f), maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (mode == i) StarLight else Color.Transparent)
                    .tap({ if (mode != i) onMode(i) }).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun labelsFor(g: Graph?): Map<Long, String> {
    val map = HashMap<Long, String>()
    g?.people?.forEach { map[it.id] = personLabel(it.ref) }
    return map
}

// ======================================================================= карточка на вкладке «Люди»
/** «Созвездие» в карточке: только люди и связи (или события), без тем и фото. Нажатие — на весь экран. */
@Composable
fun ConstellationCard(graph: Graph?) {
    val now = remember { System.currentTimeMillis() }
    var mode by HomeState.skyMode
    val labels = labelsFor(graph)
    val me = stringResource(R.string.people_me)
    val sky = remember(graph, mode, labels, me) {
        val g = graph ?: Graph(emptyList(), emptyList(), emptyList())
        if (mode == 0) memoryGraph(g, now - 30 * DAY, now) else peopleGraph(g, now - 90 * DAY, now, labels, me)
    }
    val camera = remember { SkyCamera() }
    val open = { Nav.push(Route.Sky) }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Sky1, Sky2))),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 16.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.sky_title), T.section, StarLight)
                Txt(skySubtitle(sky, mode), T.caption, SkyDim, maxLines = 1)
            }
            SkyModeToggle(mode) { mode = it }
            Spacer(Modifier.width(6.dp))
            IconCircle(Icons.Expand, stringResource(R.string.sky_expand), open, size = 34.dp,
                background = StarLight.copy(alpha = 0.10f), tint = StarLight)
        }
        if (sky.isEmpty) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Txt(stringResource(if (mode == 0) R.string.sky_memories_empty else R.string.sky_empty), T.small, SkyDim,
                    modifier = Modifier.padding(horizontal = 32.dp))
            }
        } else {
            SkyCanvas(sky, Modifier.fillMaxWidth().height(260.dp), camera,
                onTapNode = { n ->
                    val e = n?.event
                    when {
                        e != null -> openGraphEvent(e)
                        n?.person != null && !n.center -> Nav.push(Route.Person(n.key))
                        else -> open()
                    }
                },
                description = skySubtitle(sky, mode))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun skySubtitle(sky: SkyGraph, mode: Int): String {
    val n = sky.nodes.count { !it.center }
    return when {
        n <= 0 -> stringResource(R.string.sky_tap_to_open)
        mode == 0 -> plural(R.plurals.memories_count, n)
        else -> plural(R.plurals.people, n)
    }
}

// ======================================================================= на весь экран
/** Карта связей на весь экран: двумя пальцами — масштаб и сдвиг, двойное нажатие — приблизить,
 *  нажатие на звезду — подробности внизу (темы, сколько разговоров, открыть карточку). */
@Composable
fun SkyScreen() {
    val graphRes by Repo.graph.state.collectAsState()
    val graph = graphRes.data
    val now = remember { System.currentTimeMillis() }
    var mode by HomeState.skyMode
    var period by remember { mutableStateOf(30) }
    var selected by remember { mutableStateOf<Long?>(null) }
    val camera = remember { SkyCamera() }
    val labels = labelsFor(graph)
    val me = stringResource(R.string.people_me)
    val fmt = rememberFmt()
    LaunchedEffect(Unit) { Repo.graph.refresh() }
    LaunchedEffect(mode, period) {
        selected = null
        camera.reset()
    }
    val sky = remember(graph, mode, period, labels, me) {
        val g = graph ?: Graph(emptyList(), emptyList(), emptyList())
        val since = now - period * DAY
        if (mode == 0) memoryGraph(g, since, now) else peopleGraph(g, since, now, labels, me)
    }
    // светлые значки строки состояния поверх тёмного неба (и обратно — при выходе)
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val before = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) ctl?.isAppearanceLightStatusBars = before }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Sky1, Sky2)))) {
        if (graph == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(28.dp, StarLight) }
        } else if (sky.isEmpty) {
            Txt(stringResource(if (mode == 0) R.string.sky_memories_empty else R.string.sky_empty), T.body, SkyDim,
                modifier = Modifier.align(Alignment.Center).padding(32.dp))
        } else {
            SkyCanvas(sky, Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp, bottom = 72.dp), camera,
                selected = selected, nodeScale = 11.dp, interactive = true,
                onTapNode = { n -> selected = if (n == null || n.key == selected) null else n.key },
                description = skySubtitle(sky, mode))
        }
        // верх: назад, название, режим
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Back, stringResource(R.string.back), { Nav.pop() }, size = 44.dp,
                background = StarLight.copy(alpha = 0.10f), tint = StarLight)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.sky_title), T.bar, StarLight, maxLines = 1)
                Txt(skySubtitle(sky, mode), T.caption, SkyDim, maxLines = 1)
            }
            SkyModeToggle(mode) { mode = it }
        }
        // справа: масштаб кнопками (для тех, кому неудобно двумя пальцами)
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconCircle(Icons.Plus, stringResource(R.string.sky_zoom_in), {
                camera.zoomAt(1.5f, Offset.Zero, Offset.Zero)
            }, size = 40.dp, background = StarLight.copy(alpha = 0.10f), tint = StarLight)
            IconCircle(Icons.Minus, stringResource(R.string.sky_zoom_out), {
                camera.zoomAt(1 / 1.5f, Offset.Zero, Offset.Zero)
            }, size = 40.dp, background = StarLight.copy(alpha = 0.10f), tint = StarLight)
        }
        // низ: период и подробности о выбранной звезде
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            val node = sky.nodes.firstOrNull { it.key == selected }
            AnimatedVisibility(node != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                node?.let { SkyDetails(it, graph, period, now, fmt, labels) }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to R.string.sky_week, 30 to R.string.sky_month, 365 to R.string.sky_all).forEach { (d, label) ->
                    val on = period == d
                    Txt(stringResource(label), T.smallStrong, if (on) Sky1 else StarLight, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            .background(if (on) StarLight else StarLight.copy(alpha = 0.10f))
                            .tap({ period = d }).padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
    }
}

/** Подробности о звезде: человек — сколько разговоров, когда последний, о чём; событие — когда, с кем, о чём. */
@Composable
private fun SkyDetails(n: SkyNode, graph: Graph?, period: Int, now: Long, fmt: Fmt, labels: Map<Long, String>) {
    val since = now - period * DAY
    val e = n.event
    val topics: List<String> = remember(n, graph, period) {
        when {
            e != null -> e.topics.take(6)
            graph == null -> emptyList()
            else -> {
                val counts = HashMap<String, Int>()
                graph.conversations.filter { isoMillis(it.startedAt) >= since && (n.center || n.key in it.people) }
                    .forEach { c -> c.topics.forEach { t -> counts[t] = (counts[t] ?: 0) + 1 } }
                counts.entries.sortedByDescending { it.value }.take(6).map { it.key }
            }
        }
    }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(StarLight.copy(alpha = 0.08f)).border(1.dp, StarLight.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(n.label ?: stringResource(R.string.untitled), T.bodyStrong, StarLight, maxLines = 1)
                val day = if (n.last > 0) fmt.dayShortTitle(Instant.ofEpochMilli(n.last).atZone(ZoneId.systemDefault())
                    .toLocalDate().toString()) else null
                val line = when {
                    e != null -> listOfNotNull(day, fmt.time(e.startedAt), fmt.duration(e.durationSec.toLong()))
                    else -> listOfNotNull(plural(R.plurals.conversations, n.count), day)
                }.joinToString(", ")
                Txt(line, T.caption, SkyDim, maxLines = 1)
            }
            val open: (() -> Unit)? = when {
                e != null -> ({ openGraphEvent(e) })
                n.person != null && !n.center -> ({ Nav.push(Route.Person(n.key)) })
                else -> null
            }
            if (open != null) {
                Txt(stringResource(R.string.map_open), T.smallStrong, Sky1, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(StarLight).tap(open)
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        if (e != null) {
            e.summary?.let {
                Spacer(Modifier.height(8.dp))
                Txt(it, T.small, StarLight.copy(alpha = 0.88f), maxLines = 3)
            }
            val owner = graph?.people?.firstOrNull { it.ref.isOwner }?.id
            val names = e.people.filter { it != owner }.mapNotNull { labels[it] }
            if (names.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Txt(names.joinToString(", "), T.caption, SkyDim, maxLines = 1)
            }
        }
        if (topics.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                topics.forEach { t ->
                    Txt(t, T.caption, StarLight, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(StarLight.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
    }
}

/** Открыть событие: из одного фрагмента — сразу разговор. */
fun openGraphEvent(e: GraphEvent) {
    val day = e.day ?: Instant.ofEpochMilli(isoMillis(e.startedAt)).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val first = e.firstConversation
    if (e.fragments <= 1 && first != null) Nav.push(Route.Conversation(first)) else Nav.push(Route.Event(e.id, day))
}
