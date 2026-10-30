@file:OptIn(ExperimentalMaterial3Api::class)

package codes.neuro.neurosoft.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.diary.PhotoSync
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.DiaryDay
import codes.neuro.neurosoft.net.Place
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ======================================================================= общие элементы дневника
@Composable
fun ServerImage(id: Long, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(Api.photoUrl(id))
            .addHeader("Authorization", Api.authHeader()).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Mn.c.raised),
    )
}

/** Обложка дня: фото, если есть; иначе «карта дня» по местам; иначе эмодзи настроения. */
@Composable
fun DayCover(d: DiaryDay, modifier: Modifier) {
    val cover = d.coverPhotoId
    val located = d.places.filter { it.lat != null && it.lon != null }
    when {
        cover != null -> ServerImage(cover, modifier)
        located.isNotEmpty() -> DayMap(located, modifier)
        else -> Box(modifier.background(Mn.c.raised), contentAlignment = Alignment.Center) {
            Txt(d.mood?.takeWhile { !it.isLetterOrDigit() && it != ' ' }?.ifBlank { null } ?: "✨",
                T.emoji.copy(fontSize = 44.sp))
        }
    }
}

/** Схема перемещений за день: без карт и сторонних сервисов, только точки и маршрут. */
@Composable
fun DayMap(places: List<Place>, modifier: Modifier) {
    val c = Mn.c
    Canvas(modifier.background(c.raised)) {
        val step = 22.dp.toPx()
        var x = step / 2
        while (x < size.width) {
            drawLine(c.line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = step / 2
        while (y < size.height) {
            drawLine(c.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        val lats = places.mapNotNull { it.lat }
        val lons = places.mapNotNull { it.lon }
        val minLat = lats.min()
        val maxLat = lats.max()
        val minLon = lons.min()
        val maxLon = lons.max()
        val pad = 28.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val spanLat = (maxLat - minLat).coerceAtLeast(0.002)
        val spanLon = (maxLon - minLon).coerceAtLeast(0.002)
        // сохраняем пропорции, чтобы маршрут не растягивался
        val scale = minOf(w / spanLon, h / spanLat)
        val offX = pad + (w - spanLon * scale).toFloat() / 2
        val offY = pad + (h - spanLat * scale).toFloat() / 2
        val points = places.map {
            Offset(offX + ((it.lon!! - minLon) * scale).toFloat(), offY + ((maxLat - it.lat!!) * scale).toFloat())
        }
        for (i in 1 until points.size) {
            drawLine(c.graphite.copy(alpha = 0.6f), points[i - 1], points[i], strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        points.forEachIndexed { i, p ->
            val r = (if (i == points.lastIndex) 7.dp else 5.dp).toPx()
            drawCircle(Brush.radialGradient(listOf(c.lens.copy(alpha = 0.35f), Color.Transparent), center = p,
                radius = r * 3), radius = r * 3, center = p)
            drawCircle(c.paper, radius = r + 2.dp.toPx(), center = p)
            drawCircle(c.lens, radius = r, center = p)
        }
    }
}

// ======================================================================= вкладка «Дневник»
@Composable
fun DiaryTab() {
    val context = LocalContext.current
    val res by Repo.diary.state.collectAsState()
    val features by Repo.features.state.collectAsState()
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    var refreshing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val sending = stringResource(R.string.photos_sending)
    val sentTemplate = stringResource(R.string.photos_sent)
    val rebuilding = stringResource(R.string.diary_rebuilding)

    LaunchedEffect(Unit) {
        Repo.features.refresh()
        while (true) {
            Repo.diary.refresh()
            delay(60_000)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        note = sending
        scope.launch {
            val sent = PhotoSync.upload(context, uris, Core::locationNear)
            note = String.format(sentTemplate, sent, uris.size)
            try {
                Api.generateDiary(todayIso())
            } catch (_: Exception) {
            }
        }
    }

    fun rebuild(day: String) {
        scope.launch {
            note = try {
                Api.generateDiary(day)
                rebuilding
            } catch (e: Exception) {
                null
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; Repo.diary.refresh(); refreshing = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        val info = res.data
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.add_photos), {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, small = true, icon = Icons.Photo)
                    SecondaryButton(stringResource(R.string.diary_build_today), { rebuild(todayIso()) }, small = true,
                        icon = Icons.Sparkle)
                }
            }
            note?.let { n -> item { Txt(n, T.caption, Mn.c.graphite, Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) } }
            if (features.data?.diary == false) {
                item {
                    EmptyBlock(Icons.Book, stringResource(R.string.diary_off_title), stringResource(R.string.diary_off_text)) {
                        PrimaryButton(stringResource(R.string.turn_on), {
                            scope.launch {
                                try {
                                    Api.setFeatures(mapOf("diary" to true))
                                    Repo.features.refresh()
                                } catch (_: Exception) {
                                }
                            }
                        })
                    }
                }
                return@LazyColumn
            }
            when {
                info == null && res.error != null -> item { ErrorBlock(res.error, { scope.launch { Repo.diary.refresh() } }) }
                info == null -> item { LoadingBlock() }
                info.days.isEmpty() -> item {
                    EmptyBlock(Icons.Book, stringResource(R.string.diary_empty_title), stringResource(R.string.diary_empty_text))
                }
                else -> {
                    item { StaleNote(res.error) }
                    items(info.days, key = { it.day }) { d -> DiaryCard(d, fmt) }
                }
            }
        }
    }
}

@Composable
private fun DiaryCard(d: DiaryDay, fmt: Fmt) {
    val c = Mn.c
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(c.paper).tap({ Nav.push(Route.Day(d.day)) }),
    ) {
        DayCover(d, Modifier.fillMaxWidth().height(if (d.coverPhotoId != null) 210.dp else 150.dp))
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(fmt.dayTitle(d.day), T.caption, c.graphite, Modifier.weight(1f), maxLines = 1)
                d.mood?.let { Txt(it, T.caption, c.graphite, maxLines = 1) }
            }
            Spacer(Modifier.height(4.dp))
            Txt(d.title ?: stringResource(R.string.day_untitled), T.title, maxLines = 2)
            d.summary?.let {
                Spacer(Modifier.height(8.dp))
                Txt(it, T.body, c.ink, maxLines = 4)
            }
            if (d.status == "failed") {
                Spacer(Modifier.height(8.dp))
                Txt(stringResource(R.string.diary_failed), T.caption, c.red)
            }
            Spacer(Modifier.height(12.dp))
            DayStats(d, fmt)
        }
    }
}

@Composable
private fun DayStats(d: DiaryDay, fmt: Fmt) {
    val s = d.stats
    val items = listOfNotNull(
        s["conversations"]?.takeIf { it > 0 }?.let { plural(R.plurals.conversations, it) },
        s["minutes"]?.takeIf { it > 0 }?.let { fmt.duration(it * 60L) },
        s["tasks"]?.takeIf { it > 0 }?.let { plural(R.plurals.tasks, it) },
        s["places"]?.takeIf { it > 0 }?.let { plural(R.plurals.places, it) },
    )
    if (items.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.take(3).forEach { t ->
            Txt(t, T.tiny, Mn.c.ink, Modifier.clip(RoundedCornerShape(10.dp)).background(Mn.c.raised)
                .padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
        }
    }
}

// ======================================================================= экран дня
@Composable
fun DayScreen(day: String) {
    val res by Repo.diary.state.collectAsState()
    val convRes by Repo.conversations(day).state.collectAsState()
    val fmt = rememberFmt()
    val c = Mn.c
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val rebuilding = stringResource(R.string.diary_rebuilding)
    LaunchedEffect(day) {
        Repo.diary.refresh()
        Repo.conversations(day).refresh()
    }
    val d = res.data?.days?.firstOrNull { it.day == day }
    val captions = res.data?.photoCaptions.orEmpty()

    Column(Modifier.fillMaxSize()) {
        TopBar(fmt.dayShortTitle(day), { Nav.pop() }) {
            IconCircle(Icons.Refresh, stringResource(R.string.diary_rebuild), {
                scope.launch {
                    note = try {
                        Api.generateDiary(day)
                        rebuilding
                    } catch (e: Exception) {
                        null
                    }
                }
            }, size = 40.dp)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(bottom = 24.dp)) {
            note?.let { Txt(it, T.caption, c.graphite, Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) }
            if (d == null) {
                if (res.data == null && res.error == null) LoadingBlock()
                else EmptyBlock(Icons.Book, stringResource(R.string.day_not_ready_title),
                    stringResource(R.string.day_not_ready_text)) {
                    PrimaryButton(stringResource(R.string.diary_build), {
                        scope.launch {
                            note = try {
                                Api.generateDiary(day)
                                rebuilding
                            } catch (e: Exception) {
                                null
                            }
                        }
                    })
                }
            } else {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    DayCover(d, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)))
                }
                Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
                    Txt(fmt.dayTitle(day) + (d.mood?.let { ", $it" } ?: ""), T.caption, c.graphite)
                    Spacer(Modifier.height(4.dp))
                    Txt(d.title ?: stringResource(R.string.day_untitled), T.display)
                }
                // цифры дня — как «42 · 2ч 46м · 8» у Omi, но с подписями
                TileRow() {
                    StatTile(stringResource(R.string.stat_conversations), "${d.stats["conversations"] ?: 0}",
                        Modifier.weight(1f))
                    StatTile(stringResource(R.string.stat_time), fmt.duration((d.stats["minutes"] ?: 0) * 60L),
                        Modifier.weight(1f))
                    StatTile(stringResource(R.string.stat_tasks), "${d.stats["tasks"] ?: 0}", Modifier.weight(1f))
                }
                d.summary?.let {
                    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { Txt(it, T.body) }
                }
                d.quote?.takeIf { it.isNotBlank() }?.let { q ->
                    Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.lens))
                        Spacer(Modifier.width(12.dp))
                        Txt(stringResource(R.string.quoted, q), T.body.copy(fontStyle = FontStyle.Italic), c.graphite)
                    }
                }
                if (d.highlights.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.highlights))
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        d.highlights.forEachIndexed { i, h ->
                            Row(Modifier.padding(top = if (i == 0) 0.dp else 16.dp)) {
                                Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Txt(h.emoji, T.emoji)
                                    Txt(h.time, T.tiny, c.graphite)
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Txt(h.title, T.bodyStrong)
                                    if (h.text.isNotBlank()) Txt(h.text, T.small, c.graphite, Modifier.padding(top = 2.dp))
                                    val pid = h.photoId
                                    if (pid != null && pid != d.coverPhotoId) {
                                        ServerImage(pid, Modifier.padding(top = 8.dp).fillMaxWidth().height(180.dp)
                                            .clip(RoundedCornerShape(16.dp)))
                                        captions[pid]?.substringBefore(" (")?.let {
                                            Txt(it, T.caption, c.faint, Modifier.padding(top = 4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (d.places.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.places))
                    val located = d.places.filter { it.lat != null && it.lon != null }
                    if (located.size > 1 && d.coverPhotoId != null) {
                        DayMap(located, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(170.dp)
                            .clip(RoundedCornerShape(20.dp)))
                        Spacer(Modifier.height(10.dp))
                    }
                    Group {
                        d.places.forEach { p ->
                            item {
                                ListRow(p.name ?: stringResource(R.string.place_unknown), icon = Icons.Pin,
                                    value = "${p.from}–${p.to}")
                            }
                        }
                    }
                }
            }
            val convs = convRes.data.orEmpty()
            if (convs.isNotEmpty()) {
                SectionTitle(stringResource(R.string.day_conversations)) {
                    Txt(fmt.duration(convs.sumOf { it.durationSec.toLong() }), T.small, c.graphite)
                }
                convs.forEachIndexed { i, conv -> Grouped(i, convs.size) { ConversationRow(conv, fmt) } }
            }
            if (d != null) {
                Txt(stringResource(R.string.diary_footer), T.caption, c.faint,
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp), align = TextAlign.Center)
            }
        }
    }
}
