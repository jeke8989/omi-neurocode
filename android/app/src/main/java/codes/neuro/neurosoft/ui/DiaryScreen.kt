package codes.neuro.neurosoft.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import codes.neuro.neurosoft.Core
import codes.neuro.neurosoft.diary.PhotoSync
import codes.neuro.neurosoft.net.Api
import codes.neuro.neurosoft.net.DiaryDay
import codes.neuro.neurosoft.net.DiaryInfo
import codes.neuro.neurosoft.net.Features
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun DiaryScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<Load<Pair<DiaryInfo, Features>>>(Load.Loading) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            state = try {
                Load.Ok(Api.diary() to Api.features())
            } catch (e: Exception) {
                Load.Err(e.message ?: "Ошибка")
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        note = "Отправляю фото: ${uris.size}…"
        scope.launch {
            val sent = PhotoSync.upload(context, uris, Core::locationNear)
            note = "Отправлено $sent из ${uris.size}. В ленту попадут только фото, связанные с событиями дня."
            try {
                Api.generateDiary(LocalDate.now().toString())
            } catch (_: Exception) {
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LargeTitle("Дневник", subtitle = "лента твоих дней") {
            PillButton("+ Фото", {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, filled = false, small = true)
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Ios.colors.secondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        LoadContent(state, onRetry = { reload() }) { (info, features) ->
            if (!features.diary) {
                Section(footer = "Лента дня собирается из разговоров, мест, вопросов и фото. Можно включить в настройках.") {
                    row { ListRow("Дневник выключен", subtitle = "Включить в Настройках", icon = NeuroIcons.Book,
                        iconColor = Ios.colors.orange, chevron = true, onClick = onOpenSettings) }
                }
                return@LoadContent
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                val today = LocalDate.now().toString()
                if (info.days.none { it.day == today }) {
                    item {
                        Section(footer = "Лента сегодняшнего дня обновляется в течение дня, итоговая — после полуночи.") {
                            row {
                                ListRow("Собрать ленту сегодня", icon = NeuroIcons.Sparkles, iconColor = Ios.colors.indigo,
                                    chevron = true, onClick = {
                                        scope.launch {
                                            note = try {
                                                Api.generateDiary(today)
                                                "Собираю ленту дня — обновите через минуту"
                                            } catch (e: Exception) {
                                                e.message
                                            }
                                        }
                                    })
                            }
                        }
                    }
                }
                if (info.days.isEmpty()) {
                    item {
                        EmptyState(NeuroIcons.Book, "Здесь будет дневник твоей жизни",
                            "Каждый день агент собирает историю: где ты был, о чём говорил, что решил. " +
                                "Фото дня, связанные с событиями, оформляются в выбранном стиле.")
                    }
                }
                items(info.days, key = { it.day }) { day ->
                    DayCard(day, info, onRefresh = {
                        scope.launch {
                            note = try {
                                Api.generateDiary(day.day)
                                "Пересобираю «${dayTitle(day.day)}» — обновите через минуту"
                            } catch (e: Exception) {
                                e.message
                            }
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun Photo(id: Long, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(Api.photoUrl(id))
            .addHeader("Authorization", Api.authHeader()).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Ios.colors.fill),
    )
}

@Composable
private fun DayCard(day: DiaryDay, info: DiaryInfo, onRefresh: () -> Unit) {
    val c = Ios.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(22.dp))
        .background(c.cell)) {
        // обложка
        val cover = day.coverPhotoId
        Box(Modifier.fillMaxWidth().height(if (cover != null) 220.dp else 120.dp)) {
            if (cover != null) {
                Photo(cover, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.indigo, c.purple, c.pink))))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,
                Color.Black.copy(alpha = 0.55f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(dayTitle(day.day).uppercase() + (day.mood?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                Text(day.title ?: "День", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            day.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.label) }
            day.quote?.takeIf { it.isNotBlank() }?.let { q ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.tint))
                    Spacer(Modifier.width(10.dp))
                    Text("«$q»", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
                        color = c.secondary)
                }
            }
            // хайлайты — таймлайн
            day.highlights.forEach { h ->
                Row {
                    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(h.emoji, style = MaterialTheme.typography.headlineSmall)
                        Text(h.time, style = MaterialTheme.typography.labelSmall, color = c.secondary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(h.title, style = MaterialTheme.typography.titleMedium, color = c.label)
                        if (h.text.isNotBlank()) Text(h.text, style = MaterialTheme.typography.bodyMedium, color = c.secondary)
                        val pid = h.photoId
                        if (pid != null && pid != day.coverPhotoId) {
                            Photo(pid, Modifier.padding(top = 6.dp).fillMaxWidth().height(170.dp)
                                .clip(RoundedCornerShape(14.dp)))
                            info.photoCaptions[pid]?.substringBefore(" (оценка")?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = c.tertiary)
                            }
                        }
                    }
                }
            }
            // места
            if (day.places.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(day.places) { p ->
                        Row(Modifier.clip(RoundedCornerShape(50)).background(c.fill).padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(NeuroIcons.Location, null, tint = c.red, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${p.name ?: "Место"} · ${p.from}–${p.to}", style = MaterialTheme.typography.labelMedium,
                                color = c.label)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val s = day.stats
                Text(
                    listOfNotNull(
                        s["conversations"]?.takeIf { it > 0 }?.let { "разговоров: $it" },
                        s["questions"]?.takeIf { it > 0 }?.let { "вопросов: $it" },
                        s["tasks"]?.takeIf { it > 0 }?.let { "задач: $it" },
                        s["places"]?.takeIf { it > 0 }?.let { "мест: $it" },
                    ).joinToString(" · ").ifBlank { if (day.status == "failed") "не удалось собрать: ${day.error ?: ""}" else "" },
                    style = MaterialTheme.typography.labelMedium, color = c.tertiary, modifier = Modifier.weight(1f),
                )
                Text("Обновить", style = MaterialTheme.typography.labelLarge, color = c.tint,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRefresh).padding(4.dp))
            }
        }
    }
}
