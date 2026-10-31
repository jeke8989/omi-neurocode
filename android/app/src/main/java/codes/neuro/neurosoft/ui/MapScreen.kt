package codes.neuro.neurosoft.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import codes.neuro.neurosoft.R
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.DayMapData
import codes.neuro.neurosoft.net.LatLon
import codes.neuro.neurosoft.net.MapSpot
import kotlinx.coroutines.launch

/** Открыть событие или разговор с карты. */
fun openSpot(s: MapSpot, day: String) {
    val first = s.firstConversation
    when {
        s.event && s.fragments > 1 -> Nav.push(Route.Event(s.id, day))
        first != null -> Nav.push(Route.Conversation(first))
        s.event -> Nav.push(Route.Event(s.id, day))
        else -> Nav.push(Route.Conversation(s.id))
    }
}

fun spotPins(d: DayMapData): List<MapPin> =
    d.spots.map { s -> MapPin("${if (s.event) "e" else "c"}${s.id}", s.lat, s.lon, s.emoji, s.title) { openSpot(s, d.day) } }

fun placeMarks(d: DayMapData): List<MapPlaceMark> = d.places.map { MapPlaceMark(it.lat, it.lon, it.name) }

/** Карта дня на весь экран: маршрут, места и события; под картой — события по порядку (нажатие — показать). */
@Composable
fun MapScreen(day: String, lat: Double?, lon: Double?) {
    var res by remember(day) { mutableStateOf<Res<DayMapData?>>(Res(loading = true)) }
    val scope = rememberCoroutineScope()
    val fmt = rememberFmt()
    val c = Mn.c
    val state = remember(day) { MapState() }
    fun load() {
        scope.launch {
            res = try {
                Res(Api.dayMap(day))
            } catch (e: Exception) {
                Res(error = e)
            }
        }
    }
    LaunchedEffect(day) { load() }
    val d = res.data

    Column(Modifier.fillMaxSize()) {
        TopBar(fmt.dayShortTitle(day), { Nav.pop() })
        when {
            res.loading -> LoadingBlock()
            res.error != null -> ErrorBlock(res.error, { load() })
            d == null || d.isEmpty -> EmptyBlock(Icons.Pin, stringResource(R.string.map_empty_title), "")
            else -> {
                MnemoMap(
                    Modifier.fillMaxWidth().weight(1f),
                    track = d.track, places = placeMarks(d), pins = spotPins(d), state = state, interactive = true,
                    focus = if (lat != null && lon != null) LatLon(lat, lon) else null,
                    description = stringResource(R.string.day_map),
                )
                if (d.spots.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).navigationBarsPadding(),
                        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)) {
                        itemsIndexed(d.spots, key = { _, s -> "${s.event}${s.id}" }) { i, s ->
                            Grouped(i, d.spots.size) {
                                Row(
                                    Modifier.fillMaxWidth().tap({ state.focus(s.lat, s.lon, 16.5f) })
                                        .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    EmojiTile(s.emoji, 40.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Txt(s.title ?: stringResource(R.string.untitled), T.bodyStrong, maxLines = 1)
                                        Txt(fmt.time(s.startedAt), T.caption, c.graphite)
                                    }
                                    IconCircle(Icons.Chevron, stringResource(R.string.map_open), { openSpot(s, day) },
                                        size = 36.dp)
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

/** Небольшая карта для экрана разговора или события: одна метка и маршрут; нажатие — карта дня. */
@Composable
fun SpotMap(day: String, lat: Double, lon: Double, emoji: String?, title: String?, track: List<LatLon> = emptyList(),
            place: String? = null) {
    val c = Mn.c
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        MnemoMap(
            Modifier.fillMaxWidth().height(168.dp).clip20(),
            track = track, pins = listOf(MapPin("here", lat, lon, emoji, title)),
            onOpen = { Nav.push(Route.Map(day, lat, lon)) }, description = place ?: stringResource(R.string.day_map),
        )
        if (place != null) {
            Row(Modifier.padding(start = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Pin, null, tint = c.graphite, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Txt(place, T.caption, c.graphite, maxLines = 1)
            }
        }
    }
}

private fun Modifier.clip20() = this.then(
    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)))
